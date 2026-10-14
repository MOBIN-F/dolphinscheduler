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

package org.apache.dolphinscheduler.ai.seatunnel.metadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Connector metadata of a SeaTunnel version, exported by {@code tools/seatunnel-metadata-exporter}. It grounds the
 * LLM with the real connector options instead of its memory, and is used by the local config validation.
 */
public class ConnectorMetadataRepository {

    private static final Logger log = LoggerFactory.getLogger(ConnectorMetadataRepository.class);

    public static final String SOURCE = "source";
    public static final String TRANSFORM = "transform";
    public static final String SINK = "sink";

    private static final int MAX_DESCRIPTION_LENGTH = 160;

    /**
     * Options supported by every plugin, they are not declared in the option rules of the factories.
     */
    private static final Map<String, Set<String>> COMMON_OPTIONS = Map.of(
            SOURCE, Set.of("plugin_output", "result_table_name", "parallelism"),
            TRANSFORM, Set.of("plugin_input", "source_table_name", "plugin_output", "result_table_name"),
            SINK, Set.of("plugin_input", "source_table_name", "parallelism"));

    private final String version;

    // type -> lower case name -> metadata
    private final Map<String, Map<String, ConnectorMetadata>> connectors = new LinkedHashMap<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MetadataFile(String version, List<ConnectorMetadata> connectors) {
    }

    ConnectorMetadataRepository(MetadataFile metadataFile) {
        this.version = metadataFile.version();
        for (String type : List.of(SOURCE, TRANSFORM, SINK)) {
            connectors.put(type, new TreeMap<>());
        }
        for (ConnectorMetadata connector : metadataFile.connectors()) {
            connectors.computeIfAbsent(connector.type(), key -> new TreeMap<>())
                    .put(connector.name().toLowerCase(Locale.ROOT), connector);
        }
    }

    /**
     * Load the metadata from the given file, or the built-in metadata of the given SeaTunnel version.
     */
    public static ConnectorMetadataRepository load(String metadataPath, String seatunnelVersion) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            MetadataFile metadataFile;
            if (metadataPath != null && !metadataPath.isEmpty()) {
                metadataFile = mapper.readValue(Files.readAllBytes(Paths.get(metadataPath)), MetadataFile.class);
            } else {
                String resource = "/seatunnel/connector-metadata-" + seatunnelVersion + ".json";
                try (InputStream inputStream = ConnectorMetadataRepository.class.getResourceAsStream(resource)) {
                    if (inputStream == null) {
                        throw new IllegalStateException("Built-in SeaTunnel connector metadata " + resource
                                + " not found, please set ai.seatunnel.metadata-path");
                    }
                    metadataFile = mapper.readValue(inputStream, MetadataFile.class);
                }
            }
            ConnectorMetadataRepository repository = new ConnectorMetadataRepository(metadataFile);
            log.info("Loaded SeaTunnel {} connector metadata: {}", repository.version, repository.catalog());
            return repository;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load SeaTunnel connector metadata", e);
        }
    }

    public String version() {
        return version;
    }

    public Optional<ConnectorMetadata> find(String type, String name) {
        if (type == null || name == null) {
            return Optional.empty();
        }
        Map<String, ConnectorMetadata> byName = connectors.get(type.toLowerCase(Locale.ROOT));
        return byName == null ? Optional.empty() : Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public List<String> names(String type) {
        Map<String, ConnectorMetadata> byName = connectors.get(type);
        if (byName == null) {
            return Collections.emptyList();
        }
        return byName.values().stream().map(ConnectorMetadata::name).collect(Collectors.toList());
    }

    public Set<String> commonOptions(String type) {
        return COMMON_OPTIONS.getOrDefault(type, Set.of());
    }

    /**
     * @return e.g. {@code source: Jdbc, Kafka; transform: Sql; sink: Console, Jdbc}
     */
    public String catalog() {
        List<String> parts = new ArrayList<>();
        for (String type : List.of(SOURCE, TRANSFORM, SINK)) {
            parts.add(type + ": " + String.join(", ", names(type)));
        }
        return String.join("\n", parts);
    }

    /**
     * Describe the options of a connector for the LLM, grouped by Always Required / Exactly one of / All or none of
     * / Conditional / Optional, which reduces invented options significantly.
     */
    public String describe(ConnectorMetadata connector) {
        StringBuilder text = new StringBuilder();
        text.append(connector.name()).append(" (").append(connector.type()).append(", SeaTunnel ").append(version)
                .append(")\n");

        Map<Integer, List<OptionMetadata>> groups = new LinkedHashMap<>();
        for (OptionMetadata option : connector.required()) {
            groups.computeIfAbsent(option.group() == null ? 0 : option.group(), key -> new ArrayList<>()).add(option);
        }
        List<String> always = new ArrayList<>();
        List<String> exclusive = new ArrayList<>();
        List<String> bundled = new ArrayList<>();
        List<String> conditional = new ArrayList<>();
        for (List<OptionMetadata> group : groups.values()) {
            String category = group.get(0).category();
            if ("exclusive".equals(category)) {
                exclusive.add("- exactly one of: " + group.stream().map(this::describeOption)
                        .collect(Collectors.joining(" | ")));
            } else if ("bundled".equals(category)) {
                bundled.add("- all or none of: " + group.stream().map(this::describeOption)
                        .collect(Collectors.joining(", ")));
            } else if ("conditional".equals(category)) {
                conditional.add("- when " + group.get(0).conditionExpression() + " -> " + group.stream()
                        .map(this::describeOption).collect(Collectors.joining(", ")));
            } else {
                group.forEach(option -> always.add("- " + describeOption(option)));
            }
        }
        appendSection(text, "ALWAYS required", always);
        appendSection(text, "Exclusive (configure exactly one)", exclusive);
        appendSection(text, "Bundled (configure all or none)", bundled);
        appendSection(text, "Conditional (ONLY include when the condition matches)", conditional);
        appendSection(text, "Optional", connector.optional().stream().map(option -> "- " + describeOption(option))
                .collect(Collectors.toList()));
        text.append("Common options: ")
                .append(commonOptions(connector.type()).stream().sorted().collect(Collectors.joining(", ")))
                .append("\n");
        return text.toString();
    }

    private static void appendSection(StringBuilder text, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        text.append(title).append(":\n");
        lines.forEach(line -> text.append(line).append("\n"));
    }

    private String describeOption(OptionMetadata option) {
        StringBuilder text = new StringBuilder(option.key()).append(" (").append(option.type());
        if (option.hasDefaultValue()) {
            text.append(", default ").append(option.defaultValue());
        }
        if (!option.optionValues().isEmpty()) {
            text.append(", values ").append(option.optionValues());
        }
        text.append(")");
        if (!option.fallbackKeys().isEmpty()) {
            text.append(" [aliases: ").append(String.join(", ", option.fallbackKeys())).append("]");
        }
        String description = option.description();
        if (description != null && !description.isEmpty() && !description.equalsIgnoreCase(option.key())) {
            String singleLine = description.replaceAll("\\s+", " ").trim();
            text.append(": ").append(singleLine.length() > MAX_DESCRIPTION_LENGTH
                    ? singleLine.substring(0, MAX_DESCRIPTION_LENGTH) + "..."
                    : singleLine);
        }
        return text.toString();
    }
}
