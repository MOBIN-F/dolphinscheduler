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

import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository;
import org.apache.dolphinscheduler.ai.seatunnel.validation.HoconValidator;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * Tools of the SeaTunnel config assistant, backed by the connector metadata and the local validation.
 */
public class SeatunnelTools {

    static final String LIST_CONNECTORS = "list_connectors";
    static final String GET_CONNECTOR_INFO = "get_connector_info";
    static final String VALIDATE_CONFIG = "validate_config";

    private static final List<String> CONNECTOR_TYPES = List.of(ConnectorMetadataRepository.SOURCE,
            ConnectorMetadataRepository.TRANSFORM, ConnectorMetadataRepository.SINK);

    private final ConnectorMetadataRepository metadataRepository;
    private final HoconValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ToolSpecification> specifications;

    public SeatunnelTools(ConnectorMetadataRepository metadataRepository, HoconValidator validator) {
        this.metadataRepository = metadataRepository;
        this.validator = validator;
        this.specifications = List.of(
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
                        .build());
    }

    public List<ToolSpecification> specifications() {
        return specifications;
    }

    /**
     * @return the tool result for the LLM, errors are returned as text so that the LLM can correct the call
     */
    public String execute(ToolExecutionRequest request) {
        JsonNode arguments;
        try {
            String raw = request.arguments();
            arguments = mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (IOException e) {
            return "Invalid tool arguments: " + e.getMessage();
        }
        switch (request.name()) {
            case LIST_CONNECTORS:
                return metadataRepository.catalog();
            case GET_CONNECTOR_INFO:
                String name = arguments.path("connector_name").asText();
                String type = arguments.path("connector_type").asText();
                return metadataRepository.find(type, name)
                        .map(metadataRepository::describe)
                        .orElse("Connector '" + name + "' (" + type + ") is not installed. Installed " + type
                                + " connectors: " + metadataRepository.names(type));
            case VALIDATE_CONFIG:
                return validator.validate(arguments.path("config").asText()).describe();
            default:
                return "Unknown tool: " + request.name();
        }
    }
}
