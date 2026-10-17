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

import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadata;
import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository;
import org.apache.dolphinscheduler.ai.seatunnel.metadata.OptionMetadata;
import org.apache.dolphinscheduler.ai.seatunnel.validation.HoconValidator;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationIssue;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Tools of the SeaTunnel config assistant, backed by the connector metadata, the local validation and optionally the
 * HTTP API given by the user.
 */
public class SeatunnelTools {

    static final String LIST_CONNECTORS = "list_connectors";
    static final String GET_CONNECTOR_INFO = "get_connector_info";
    static final String VALIDATE_CONFIG = "validate_config";
    static final String FETCH_HTTP_SAMPLE = "fetch_http_sample";

    private static final List<String> CONNECTOR_TYPES = List.of(ConnectorMetadataRepository.SOURCE,
            ConnectorMetadataRepository.TRANSFORM, ConnectorMetadataRepository.SINK);

    private final ConnectorMetadataRepository metadataRepository;
    private final HoconValidator validator;
    private final HttpSampleFetcher httpSampleFetcher;
    private final ConnectionTools connectionTools = new ConnectionTools();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ToolSpecification> specifications;

    /**
     * @param httpSampleFetcher calls the HTTP API given by the user, the tool is disabled when null
     */
    public SeatunnelTools(ConnectorMetadataRepository metadataRepository, HoconValidator validator,
                          HttpSampleFetcher httpSampleFetcher) {
        this.metadataRepository = metadataRepository;
        this.validator = validator;
        this.httpSampleFetcher = httpSampleFetcher;
        List<ToolSpecification> specifications = new ArrayList<>(List.of(
                ToolSpecification.builder()
                        .name(LIST_CONNECTORS)
                        .description("List the installed SeaTunnel source, transform and sink connectors")
                        .parameters(JsonObjectSchema.builder().build())
                        .build(),
                ToolSpecification.builder()
                        .name(GET_CONNECTOR_INFO)
                        .description("Get the options of a SeaTunnel connector: always required, exclusive, "
                                + "bundled, conditional and optional options with types, defaults and aliases. "
                                + "Call it before using a connector.")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("connector_name", "Connector name, e.g. Jdbc, Kafka, Sql")
                                .addEnumProperty("connector_type", CONNECTOR_TYPES)
                                .required("connector_name", "connector_type")
                                .build())
                        .build(),
                ToolSpecification.builder()
                        .name(VALIDATE_CONFIG)
                        .description("Validate a complete SeaTunnel HOCON config against the connector metadata")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("config", "The complete HOCON config")
                                .required("config")
                                .build())
                        .build()));
        if (httpSampleFetcher != null) {
            specifications.add(ToolSpecification.builder()
                    .name(FETCH_HTTP_SAMPLE)
                    .description("Call the HTTP API the user gave once and get the fields of the response: JSON "
                            + "paths, types, nullability, a truncated sample value per field and the array of "
                            + "records. Call it before writing the schema of the Http source. Only URLs the user "
                            + "wrote can be called. The arguments are the same as the Http source options.")
                    .parameters(JsonObjectSchema.builder()
                            .addStringProperty("url", "The full URL, e.g. https://api.example.com/v1/orders")
                            .addEnumProperty("method", List.of("GET", "POST"))
                            .addProperty("headers", JsonObjectSchema.builder()
                                    .description("HTTP headers, e.g. {\"Authorization\": \"Bearer ${_CRED_1_}\"}")
                                    .additionalProperties(true)
                                    .build())
                            .addProperty("params", JsonObjectSchema.builder()
                                    .description("Query parameters")
                                    .additionalProperties(true)
                                    .build())
                            .addStringProperty("body", "Request body of POST")
                            .required("url")
                            .build())
                    .build());
        }
        specifications.addAll(connectionTools.specifications());
        this.specifications = List.copyOf(specifications);
    }

    /**
     * @return the counts of the errors and the warnings, the issues as the details
     */
    static StepReport validationReport(ValidationResult validation) {
        if (validation.skipped()) {
            return StepReport.warn(Map.of("skipped", true, "reason", String.valueOf(validation.skipReason())),
                    List.of());
        }
        long errors = validation.errors().size();
        long warnings = validation.issues().size() - errors;
        List<String> details = validation.issues().stream().map(ValidationIssue::toString)
                .collect(Collectors.toList());
        Map<String, Object> result = Map.of("errors", errors, "warnings", warnings);
        return errors > 0 ? new StepReport(AgentStep.ERROR, result, details)
                : warnings > 0 ? StepReport.warn(result, details) : StepReport.ok(result, details);
    }

    public List<ToolSpecification> specifications() {
        return specifications;
    }

    /**
     * @return the tool result for the LLM, errors are returned as text so that the LLM can correct the call
     */
    public String execute(ToolExecutionRequest request, ToolContext context) {
        JsonNode arguments;
        try {
            String raw = request.arguments();
            arguments = mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (IOException e) {
            return "Invalid tool arguments: " + e.getMessage();
        }
        switch (request.name()) {
            case LIST_CONNECTORS:
                context.report(StepReport.ok(Map.of(
                        "sources", metadataRepository.names(ConnectorMetadataRepository.SOURCE).size(),
                        "transforms", metadataRepository.names(ConnectorMetadataRepository.TRANSFORM).size(),
                        "sinks", metadataRepository.names(ConnectorMetadataRepository.SINK).size()), List.of()));
                return metadataRepository.catalog();
            case GET_CONNECTOR_INFO:
                String name = arguments.path("connector_name").asText();
                String type = arguments.path("connector_type").asText();
                Optional<ConnectorMetadata> connector = metadataRepository.find(type, name);
                if (connector.isEmpty()) {
                    context.report(StepReport.error("not installed"));
                    return "Connector '" + name + "' (" + type + ") is not installed. Installed " + type
                            + " connectors: " + metadataRepository.names(type);
                }
                context.report(StepReport.ok(Map.of("required", connector.get().required().size(), "optional",
                        connector.get().optional().size()),
                        connector.get().required().stream().map(OptionMetadata::key).collect(Collectors.toList())));
                return metadataRepository.describe(connector.get());
            case VALIDATE_CONFIG:
                ValidationResult validation = validator.validate(arguments.path("config").asText());
                context.report(validationReport(validation));
                return validation.describe();
            case FETCH_HTTP_SAMPLE:
                if (httpSampleFetcher == null) {
                    return "Unknown tool: " + request.name();
                }
                return httpSampleFetcher.fetch(arguments, context);
            default:
                if (connectionTools.handles(request.name())) {
                    return connectionTools.execute(request.name(), arguments, context);
                }
                return "Unknown tool: " + request.name();
        }
    }
}
