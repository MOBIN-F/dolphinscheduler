/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.ai.seatunnel.agent;

import org.apache.dolphinscheduler.ai.connection.BrowseNode;
import org.apache.dolphinscheduler.ai.connection.ConnectionField;
import org.apache.dolphinscheduler.ai.connection.ConnectionSelection;
import org.apache.dolphinscheduler.ai.connection.ConnectionToolBackend;
import org.apache.dolphinscheduler.ai.connection.ConnectionType;
import org.apache.dolphinscheduler.ai.connection.ConnectionType.Role;
import org.apache.dolphinscheduler.ai.connection.KafkaConsumerGroups;
import org.apache.dolphinscheduler.ai.connection.ObjectDescription;
import org.apache.dolphinscheduler.ai.connection.SeatunnelConnection;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * The tools reading the fields of the sources and sinks, so that the user does not have to give sample data: the
 * registered connections the user may use, and the connections the user described in the conversation.
 */
class ConnectionTools {

    static final String LIST_CONNECTIONS = "list_connections";
    static final String BROWSE_CONNECTION = "browse_connection";
    static final String DESCRIBE_OBJECT = "describe_object";

    static final int MAX_DESCRIBE_CALLS = 6;
    private static final int MAX_LISTED = 200;
    private static final Pattern PARAMETER = Pattern.compile("\\$\\{([^}]*)}");
    private static final List<ConnectionType> INLINE_TYPES = List.of(ConnectionType.KAFKA, ConnectionType.MYSQL,
            ConnectionType.POSTGRESQL, ConnectionType.CLICKHOUSE, ConnectionType.HTTP);
    /**
     * Fields not listed for the LLM: long values without information for the config
     */
    private static final Set<String> HIDDEN_PARAMS = Set.of("headers", "paths");

    List<ToolSpecification> specifications() {
        JsonObjectSchema connection = JsonObjectSchema.builder()
                .description("Either a registered connection {\"key\": \"kafka_prod\"}, or a connection the user "
                        + "described in the conversation {\"type\": \"KAFKA\", \"params\": {...}} with the fields: "
                        + inlineFields() + ". Keep the ${_CRED_N_} placeholders of the passwords.")
                .addStringProperty("key", "The key of a registered connection")
                .addEnumProperty("type", INLINE_TYPES.stream().map(Enum::name).collect(Collectors.toList()))
                .addProperty("params", JsonObjectSchema.builder().description("The fields of the connection")
                        .additionalProperties(true).build())
                .build();
        return List.of(
                ToolSpecification.builder()
                        .name(LIST_CONNECTIONS)
                        .description("List the registered connections the user may use (Kafka clusters, databases, "
                                + "HTTP APIs, S3 buckets) with their addresses, to find the connection the user means")
                        .parameters(JsonObjectSchema.builder().build())
                        .build(),
                ToolSpecification.builder()
                        .name(BROWSE_CONNECTION)
                        .description("List the topics, the databases / schemas (parent empty) or their tables "
                                + "(parent = database or schema), or the API paths of a connection")
                        .parameters(JsonObjectSchema.builder()
                                .addProperty("connection", connection)
                                .addStringProperty("parent", "The database or schema, empty for the top level")
                                .required("connection")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name(DESCRIBE_OBJECT)
                        .description("Read the fields of a topic (samples the latest messages), a table "
                                + "(database.table or schema.table) or an API path of a connection. Call it "
                                + "instead of asking the user for the fields or sample data.")
                        .parameters(JsonObjectSchema.builder()
                                .addProperty("connection", connection)
                                .addStringProperty("object", "The topic, database.table, schema.table or API path")
                                .required("connection", "object")
                                .build())
                        .build());
    }

    boolean handles(String tool) {
        return LIST_CONNECTIONS.equals(tool) || BROWSE_CONNECTION.equals(tool) || DESCRIBE_OBJECT.equals(tool);
    }

    /**
     * @return the target shown as the progress, without parameters or secrets, e.g. kafka_dev / orders
     */
    static String describeTarget(JsonNode arguments) {
        JsonNode connection = arguments.path("connection");
        String target = connection.hasNonNull("key") ? connection.path("key").asText()
                : ConnectionType.of(connection.path("type").asText()).map(ConnectionType::label).orElse("?") + " "
                        + hosts(connection).stream().collect(Collectors.joining(","));
        String object = arguments.path("object").asText(arguments.path("parent").asText(""));
        return object.isEmpty() ? target : target + " / " + object;
    }

    String execute(String tool, JsonNode arguments, ToolContext context) {
        ConnectionToolBackend backend = context.connectionBackend();
        if (backend == null) {
            return "The connections are not available in this request";
        }
        try {
            switch (tool) {
                case LIST_CONNECTIONS:
                    return context.hide(listConnections(backend, context));
                case BROWSE_CONNECTION:
                    return context.hide(browse(backend, arguments, context));
                case DESCRIBE_OBJECT:
                    return context.hide(describe(backend, arguments, context));
                default:
                    return "Unknown tool: " + tool;
            }
        } catch (Exception e) {
            String reason = context.hide(message(e));
            context.report(StepReport.error(reason));
            return "Failed: " + reason;
        }
    }

    private static String listConnections(ConnectionToolBackend backend, ToolContext context) {
        List<SeatunnelConnection> connections = backend.list();
        context.report(StepReport.ok(Map.of("count", connections.size()), connections.stream()
                .map(connection -> connection.key() + " (" + connection.type().label() + ")")
                .collect(Collectors.toList())));
        if (connections.isEmpty()) {
            return "The user may not use any registered connection.";
        }
        StringBuilder text = new StringBuilder("Registered connections the user may use:\n");
        for (SeatunnelConnection connection : connections) {
            text.append("- key `").append(connection.key()).append("`: ").append(connection.type().label())
                    .append(" \"").append(connection.name()).append("\", ")
                    .append(connection.type().roles().stream().map(role -> role.name().toLowerCase(Locale.ROOT))
                            .collect(Collectors.joining("/")));
            String params = connection.params().entrySet().stream()
                    .filter(entry -> !HIDDEN_PARAMS.contains(entry.getKey()) && !entry.getValue().isEmpty())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
            if (!params.isEmpty()) {
                text.append(", ").append(params);
            }
            text.append("\n");
        }
        return text.toString();
    }

    private String browse(ConnectionToolBackend backend, JsonNode arguments, ToolContext context) throws Exception {
        JsonNode connection = arguments.path("connection");
        String parent = arguments.path("parent").asText("");
        List<BrowseNode> nodes = connection.hasNonNull("key")
                ? backend.browse(connection.path("key").asText(), parent)
                : backend.browse(inline(connection, context), parent);
        context.report(nodes.isEmpty()
                ? StepReport.warn(Map.of("count", 0), List.of())
                : StepReport.ok(Map.of("count", nodes.size()),
                        nodes.stream().map(BrowseNode::path).collect(Collectors.toList())));
        if (nodes.isEmpty()) {
            return "Nothing found" + (parent.isEmpty() ? "" : " in " + parent)
                    + ". The user may lack the list permission: the topic or table name can still be described.";
        }
        String names = nodes.stream().limit(MAX_LISTED)
                .map(node -> node.path() + (node.leaf() ? "" : " (browse with parent = " + node.path() + ")"))
                .collect(Collectors.joining("\n"));
        return names + (nodes.size() > MAX_LISTED ? "\n(" + (nodes.size() - MAX_LISTED) + " more)" : "");
    }

    private String describe(ConnectionToolBackend backend, JsonNode arguments, ToolContext context) throws Exception {
        if (context.countDescribeCall() > MAX_DESCRIBE_CALLS) {
            return "Not called: at most " + MAX_DESCRIBE_CALLS + " objects can be described per request, write the "
                    + "config with the fields already known.";
        }
        JsonNode connectionNode = arguments.path("connection");
        String object = arguments.path("object").asText("").trim();
        if (object.isEmpty()) {
            return "The object is required: a topic, database.table, schema.table or API path";
        }
        String key = connectionNode.path("key").asText("");
        ObjectDescription description;
        StringBuilder text = new StringBuilder();
        if (!key.isEmpty()) {
            SeatunnelConnection connection = backend.registered(key)
                    .orElseThrow(() -> new IllegalArgumentException("Connection " + key + " not found"));
            description = backend.describe(key, object);
            text.append(describedFields(description));
            text.append(connectorBlocks(connection, object, context));
        } else {
            SeatunnelConnection connection = inline(connectionNode, context);
            description = backend.describe(connection, object);
            text.append(describedFields(description));
            text.append("\nThis connection is not registered: write its options in the config, keep the ${_CRED_N_} "
                    + "placeholders of the passwords.");
        }
        long sensitive = description.fields().stream()
                .filter(field -> field.note() != null && field.note().startsWith("sensitive")).count();
        List<String> fields = description.fields().stream()
                .map(field -> field.name() + ": " + field.type() + (field.note() != null
                        && field.note().startsWith("sensitive") ? " (sensitive)" : ""))
                .collect(Collectors.toList());
        context.report(description.fields().isEmpty()
                ? StepReport.warn(Map.of("fields", 0, "message", String.valueOf(description.note())), List.of())
                : StepReport.ok(Map.of("fields", description.fields().size(), "sensitive", sensitive), fields));
        if (!description.samples().isEmpty()) {
            context.onSample(new DataSample(describeTarget(arguments), description.samples()));
            text.append("\nThe sampled data is shown to the user in the chat, do not repeat it.");
        }
        return text.toString();
    }

    private static String describedFields(ObjectDescription description) {
        if (description.fields().isEmpty()) {
            return "No fields found for " + description.object()
                    + (description.note() == null || description.note().isEmpty() ? "" : ": " + description.note())
                    + "\n";
        }
        return "Fields:\n" + description.toPrompt();
    }

    /**
     * The connector blocks of a registered connection with the references of its secrets, so that the config never
     * needs the passwords.
     */
    private static String connectorBlocks(SeatunnelConnection connection, String object, ToolContext context) {
        StringBuilder text = new StringBuilder();
        for (Role role : connection.type().roles()) {
            String consumerGroup = null;
            String prefix = KafkaConsumerGroups.prefix(connection);
            if (role == Role.SOURCE && connection.type() == ConnectionType.KAFKA && !prefix.isEmpty()
                    && context.taskCode() != null && context.taskCode() > 0) {
                consumerGroup = KafkaConsumerGroups.ofTask(prefix, context.taskCode());
            }
            ConnectionSelection selection = new ConnectionSelection(role, connection,
                    connection.type() == ConnectionType.KAFKA || connection.type() == ConnectionType.HTTP
                            ? List.of(object)
                            : List.of(),
                    null, null, null, List.of(), consumerGroup);
            if (role == Role.SOURCE) {
                context.usedConnections().add(selection);
            }
            String prompt = selection.toPrompt();
            // Only the connector block and the rules, the selection heading is not needed here
            text.append("\nAs the ").append(role.name().toLowerCase(Locale.ROOT)).append(":\n")
                    .append(prompt.substring(prompt.indexOf("Use this connector block")));
        }
        return text.toString();
    }

    /**
     * A connection the user described in the conversation: the placeholders of the credentials are restored, and
     * every host must be one the user wrote.
     */
    private static SeatunnelConnection inline(JsonNode node, ToolContext context) {
        ConnectionType type = ConnectionType.of(node.path("type").asText(""))
                .filter(INLINE_TYPES::contains)
                .orElseThrow(() -> new IllegalArgumentException("Give the key of a registered connection, or the "
                        + "type of the connection: " + INLINE_TYPES));
        Map<String, String> params = new LinkedHashMap<>();
        Map<String, String> secrets = new LinkedHashMap<>();
        Set<String> undefined = new LinkedHashSet<>();
        for (ConnectionField field : type.fields()) {
            JsonNode value = node.path("params").path(field.key());
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            String resolved = context.resolve(value.isTextual() ? value.asText() : value.toString());
            Matcher matcher = PARAMETER.matcher(resolved);
            while (matcher.find()) {
                undefined.add(matcher.group());
            }
            (field.secret() ? secrets : params).put(field.key(), resolved);
        }
        if (!undefined.isEmpty()) {
            throw new IllegalArgumentException("the connection contains the undefined parameters " + undefined
                    + ", ask the user for their values");
        }
        SeatunnelConnection connection = new SeatunnelConnection("inline", "connection from the conversation", type,
                params, secrets);
        List<String> hosts = hosts(connection);
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("the address of the connection is required");
        }
        for (String host : hosts) {
            String denied = context.hostGuard().check(host, context.allowedHostNames());
            if (denied != null) {
                throw new IllegalArgumentException("not connected: " + denied);
            }
        }
        return connection;
    }

    private static List<String> hosts(JsonNode connection) {
        Optional<ConnectionType> type = ConnectionType.of(connection.path("type").asText(""));
        if (type.isEmpty()) {
            return List.of();
        }
        Map<String, String> params = new LinkedHashMap<>();
        connection.path("params").fields().forEachRemaining(entry -> params.put(entry.getKey(),
                entry.getValue().asText("")));
        return hosts(new SeatunnelConnection("inline", "", type.get(), params, Map.of()));
    }

    /**
     * @return the host names the connection connects to
     */
    static List<String> hosts(SeatunnelConnection connection) {
        switch (connection.type()) {
            case KAFKA:
                return Stream.of(connection.param("bootstrap_servers").split(","))
                        .map(String::trim).filter(server -> !server.isEmpty())
                        .map(ConnectionTools::hostOf).collect(Collectors.toList());
            case HTTP:
                try {
                    String host = URI.create(connection.param("base_url").trim()).getHost();
                    return host == null ? List.of() : List.of(host);
                } catch (IllegalArgumentException e) {
                    return List.of();
                }
            default:
                String host = connection.param("host").trim();
                return host.isEmpty() ? List.of() : List.of(hostOf(host));
        }
    }

    private static String hostOf(String server) {
        String value = server.replaceFirst("^[a-zA-Z]+://", "");
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        int colon = value.lastIndexOf(':');
        return colon > 0 && value.indexOf(':') == colon ? value.substring(0, colon) : value;
    }

    private static String message(Throwable error) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = error; cause != null && messages.size() < 3; cause = cause.getCause() == cause
                ? null
                : cause.getCause()) {
            if (cause.getMessage() != null && messages.stream().noneMatch(cause.getMessage()::contains)) {
                messages.add(cause.getMessage());
            }
        }
        return messages.isEmpty() ? error.getClass().getSimpleName() : String.join(": ", messages);
    }

    private static String inlineFields() {
        return INLINE_TYPES.stream()
                .map(type -> type.name() + " (" + type.fields().stream()
                        .filter(field -> !field.key().endsWith("_pem") && !HIDDEN_PARAMS.contains(field.key()))
                        .map(ConnectionField::key).collect(Collectors.joining(", ")) + ")")
                .collect(Collectors.joining("; "));
    }
}
