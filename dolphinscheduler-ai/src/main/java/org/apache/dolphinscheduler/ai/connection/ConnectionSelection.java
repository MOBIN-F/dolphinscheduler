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

package org.apache.dolphinscheduler.ai.connection;

import org.apache.dolphinscheduler.ai.connection.ConnectionType.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The connection the user selected for the source or the sink of the job, with the selected objects and their
 * fields. Rendered into the prompt, the secrets are references replaced by the master when the task runs.
 *
 * @param connection   the connection, its secrets are masked
 * @param objects      the selected topics, tables ({@code database.table} or {@code schema.table}) or API paths
 * @param newTable     the table to create in the sink database, instead of an existing table
 * @param path         the target path of an S3 sink
 * @param fileFormat   the file format of an S3 sink, e.g. parquet
 * @param descriptions the fields of the selected objects
 * @param consumerGroup the consumer group of a Kafka source with a consumer group prefix, e.g. the prefix with the
 *                      task code; null to let the LLM add a suffix to the prefix
 */
public record ConnectionSelection(Role role, SeatunnelConnection connection, List<String> objects, String newTable,
        String path, String fileFormat, List<ObjectDescription> descriptions, String consumerGroup) {

    public ConnectionSelection(Role role, SeatunnelConnection connection, List<String> objects, String newTable,
                               String path, String fileFormat, List<ObjectDescription> descriptions) {
        this(role, connection, objects, newTable, path, fileFormat, descriptions, null);
    }

    private static final String PLAIN_LOGIN = "org.apache.kafka.common.security.plain.PlainLoginModule";
    private static final String SCRAM_LOGIN = "org.apache.kafka.common.security.scram.ScramLoginModule";

    public ConnectionSelection {
        objects = objects == null ? List.of() : List.copyOf(objects);
        descriptions = descriptions == null ? List.of() : List.copyOf(descriptions);
    }

    /**
     * @return the section of the prompt for the selection
     */
    public String toPrompt() {
        StringBuilder text = new StringBuilder();
        String side = role == Role.SOURCE ? "Source" : "Sink";
        text.append("### ").append(side).append(": connection `").append(connection.key()).append("` (")
                .append(connection.type().label()).append(", \"").append(connection.name()).append("\")\n\n");
        text.append("Use this connector block, keep every value exactly as it is, including the quoted "
                + "`${stconn...}` references, and add the options the job needs:\n```hocon\n");
        List<String> lines = options();
        text.append(connector()).append(" {\n");
        lines.forEach(line -> text.append("  ").append(line).append("\n"));
        text.append("}\n```\n");
        if (!objects.isEmpty()) {
            text.append("Selected ").append(connection.type().objectKind()).append("s: ")
                    .append(String.join(", ", objects)).append("\n");
        }
        String groupPrefix = kafkaGroupPrefix();
        if (!groupPrefix.isEmpty()) {
            text.append("The cluster only authorizes the consumer groups starting with `").append(groupPrefix)
                    .append("`: consumer.group is required, ")
                    .append(consumerGroup == null || consumerGroup.isEmpty()
                            ? "it must be the prefix followed by a suffix describing the job, never the prefix alone"
                            : "use exactly \"" + consumerGroup + "\"")
                    .append(".\n");
        }
        if (newTable != null && !newTable.isEmpty()) {
            text.append("The target table `").append(newTable).append("` does not exist: create it from the source "
                    + "fields with generate_sink_sql = true and schema_save_mode = \"CREATE_SCHEMA_WHEN_NOT_EXIST\".\n");
        }
        if (!descriptions.isEmpty()) {
            text.append(role == Role.SOURCE ? "Source fields:\n" : "Sink table structure:\n");
            descriptions.forEach(description -> text.append(description.toPrompt()));
        }
        return text.toString();
    }

    /**
     * @return the consumer group prefix of a Kafka source, empty when the groups are not restricted
     */
    String kafkaGroupPrefix() {
        return role == Role.SOURCE && connection.type() == ConnectionType.KAFKA
                ? KafkaConsumerGroups.prefix(connection)
                : "";
    }

    /**
     * @return the SeaTunnel connector name
     */
    public String connector() {
        switch (connection.type()) {
            case KAFKA:
                return "Kafka";
            case MYSQL:
            case POSTGRESQL:
                return "Jdbc";
            case CLICKHOUSE:
                return "Clickhouse";
            case HTTP:
                return "Http";
            case S3:
                return "S3File";
            default:
                throw new IllegalStateException("Unknown type " + connection.type());
        }
    }

    List<String> options() {
        switch (connection.type()) {
            case KAFKA:
                return kafkaOptions();
            case MYSQL:
            case POSTGRESQL:
                return jdbcOptions();
            case CLICKHOUSE:
                return clickhouseOptions();
            case HTTP:
                return httpOptions();
            case S3:
                return s3Options();
            default:
                return List.of();
        }
    }

    private List<String> kafkaOptions() {
        List<String> lines = new ArrayList<>();
        lines.add("bootstrap.servers = " + quote(connection.param("bootstrap_servers")));
        if (!objects.isEmpty()) {
            lines.add("topic = " + quote(String.join(",", objects)));
        }
        if (!kafkaGroupPrefix().isEmpty() && consumerGroup != null && !consumerGroup.isEmpty()) {
            lines.add("consumer.group = " + quote(consumerGroup));
        }
        String protocol = connection.param("security_protocol");
        if (protocol.equals("PLAINTEXT")) {
            return lines;
        }
        List<String> config = new ArrayList<>();
        config.add("security.protocol = " + quote(protocol));
        if (protocol.startsWith("SASL")) {
            String mechanism = connection.param("sasl_mechanism");
            config.add("sasl.mechanism = " + quote(mechanism));
            String jaas = (mechanism.startsWith("SCRAM") ? SCRAM_LOGIN : PLAIN_LOGIN) + " required username=\""
                    + connection.param("sasl_username") + "\" password=\"" + connection.placeholder("sasl_password")
                    + "\";";
            config.add("sasl.jaas.config = " + quote(jaas));
        }
        if (connection.hasSecret("ssl_ca_pem")) {
            config.add("ssl.truststore.type = \"PEM\"");
            config.add("ssl.truststore.certificates = " + quote(connection.placeholder("ssl_ca_pem")));
        }
        if (connection.hasSecret("ssl_cert_pem") && connection.hasSecret("ssl_key_pem")) {
            config.add("ssl.keystore.type = \"PEM\"");
            config.add("ssl.keystore.certificate.chain = " + quote(connection.placeholder("ssl_cert_pem")));
            config.add("ssl.keystore.key = " + quote(connection.placeholder("ssl_key_pem")));
            if (connection.hasSecret("ssl_key_password")) {
                config.add("ssl.key.password = " + quote(connection.placeholder("ssl_key_password")));
            }
        }
        lines.add("kafka.config = {");
        config.forEach(line -> lines.add("  " + line));
        lines.add("}");
        return lines;
    }

    private List<String> jdbcOptions() {
        List<String> lines = new ArrayList<>();
        boolean mysql = connection.type() == ConnectionType.MYSQL;
        String database = connection.param("database");
        if (mysql && database.isEmpty() && !objects.isEmpty() && objects.get(0).contains(".")) {
            database = objects.get(0).substring(0, objects.get(0).indexOf('.'));
        }
        String params = connection.param("jdbc_params");
        String url = (mysql ? "jdbc:mysql://" : "jdbc:postgresql://") + connection.param("host") + ":"
                + connection.param("port") + "/" + database + (params.isEmpty() ? "" : "?" + params);
        lines.add("url = " + quote(url));
        lines.add("driver = " + quote(mysql ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver"));
        lines.add("username = " + quote(connection.param("username")));
        lines.add("password = " + quote(connection.placeholder("password")));
        return lines;
    }

    private List<String> clickhouseOptions() {
        List<String> lines = new ArrayList<>();
        lines.add("host = " + quote(connection.param("host") + ":" + connection.param("port")));
        lines.add("database = " + quote(connection.param("database")));
        lines.add("username = " + quote(connection.param("username")));
        lines.add("password = " + quote(connection.hasSecret("password") ? connection.placeholder("password") : ""));
        return lines;
    }

    private List<String> httpOptions() {
        List<String> lines = new ArrayList<>();
        String base = connection.param("base_url").replaceAll("/+$", "");
        if (objects.size() == 1) {
            String object = objects.get(0);
            String url = object.matches("(?i)https?://.*") ? object
                    : base + (object.startsWith("/") ? "" : "/") + object;
            lines.add("url = " + quote(url));
        }
        lines.add("method = \"GET\"");
        List<String> headers = new ArrayList<>();
        String auth = connection.param("auth_type");
        if (auth.equals("BEARER")) {
            headers.add("Authorization = " + quote("Bearer " + connection.placeholder("token")));
        } else if (auth.equals("BASIC")) {
            headers.add("Authorization = " + quote("Basic " + connection.placeholder("basic_auth")));
        }
        for (String header : connection.param("headers").split("\\r?\\n")) {
            int colon = header.indexOf(':');
            if (colon > 0) {
                headers.add(quote(header.substring(0, colon).trim()) + " = "
                        + quote(header.substring(colon + 1).trim()));
            }
        }
        if (!headers.isEmpty()) {
            lines.add("headers {");
            headers.forEach(header -> lines.add("  " + header));
            lines.add("}");
        }
        return lines;
    }

    private List<String> s3Options() {
        List<String> lines = new ArrayList<>();
        lines.add("bucket = " + quote("s3a://" + connection.param("bucket")));
        if (path != null && !path.isEmpty()) {
            lines.add("path = " + quote(path.startsWith("/") ? path : "/" + path));
        }
        lines.add("tmp_path = \"/tmp/seatunnel\"");
        String endpoint = connection.param("endpoint");
        lines.add("fs.s3a.endpoint = " + quote(endpoint.isEmpty()
                ? "s3." + connection.param("region") + ".amazonaws.com"
                : endpoint));
        if (connection.param("auth_type").equals("ACCESS_KEY")) {
            lines.add("fs.s3a.aws.credentials.provider = "
                    + "\"org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider\"");
            lines.add("access_key = " + quote(connection.param("access_key")));
            lines.add("secret_key = " + quote(connection.placeholder("secret_key")));
        } else {
            lines.add("fs.s3a.aws.credentials.provider = \"com.amazonaws.auth.InstanceProfileCredentialsProvider\"");
        }
        if (fileFormat != null && !fileFormat.isEmpty()) {
            lines.add("file_format_type = " + quote(fileFormat.toLowerCase(Locale.ROOT)));
        }
        return lines;
    }

    /**
     * @return a HOCON string
     */
    static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    /**
     * @return the selection summary shown in the chat when the user changes the selection
     */
    public String summary() {
        return connection.name() + (objects.isEmpty() ? "" : " / " + objects.stream().collect(Collectors.joining(
                ", ")));
    }
}
