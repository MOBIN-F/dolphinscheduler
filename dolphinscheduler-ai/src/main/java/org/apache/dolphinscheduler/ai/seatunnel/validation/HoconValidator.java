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

package org.apache.dolphinscheduler.ai.seatunnel.validation;

import static org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository.SINK;
import static org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository.SOURCE;
import static org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository.TRANSFORM;

import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadata;
import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository;
import org.apache.dolphinscheduler.ai.seatunnel.metadata.OptionMetadata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

/**
 * Fast, deterministic validation of a SeaTunnel config against the connector metadata, ported from the
 * {@code validate_hocon} of SeaTunnel CLI (Apache License 2.0). The errors are fed back to the LLM to fix the config.
 *
 * <p>Checks: HOCON syntax, env / source / sink blocks, installed connectors, required / exclusive / bundled /
 * conditional options, unknown options, conditional options whose condition does not match, plugin_output /
 * plugin_input routing and the checkpoint interval of STREAMING jobs.
 *
 * <p>{@code ${xxx}} placeholders are not reported, DolphinScheduler replaces them with the task parameters before the
 * job runs.
 */
public class HoconValidator {

    private static final List<String> SECTIONS = List.of(SOURCE, TRANSFORM, SINK);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");
    private static final Set<String> OUTPUT_KEYS = Set.of("plugin_output", "result_table_name");
    private static final Set<String> INPUT_KEYS = Set.of("plugin_input", "source_table_name");

    private final ConnectorMetadataRepository metadataRepository;

    public HoconValidator(ConnectorMetadataRepository metadataRepository) {
        this.metadataRepository = metadataRepository;
    }

    /**
     * A plugin block in a section, e.g. {@code Jdbc { ... }} in {@code source { ... }}.
     */
    record PluginBlock(String section, String name, String body, int line) {

        String location() {
            return section + "." + name;
        }
    }

    public ValidationResult validate(String configText) {
        List<ValidationIssue> issues = new ArrayList<>();
        String parsable = replacePlaceholders(configText);

        Config config;
        try {
            config = ConfigFactory.parseString(parsable);
        } catch (ConfigException e) {
            issues.add(ValidationIssue.error("config", "HOCON syntax error: " + e.getMessage()));
            return ValidationResult.of(ValidationResult.STAGE_LOCAL, issues);
        }
        checkEnv(config, issues);

        List<PluginBlock> blocks = extractBlocks(parsable);
        Set<String> sections = blocks.stream().map(PluginBlock::section).collect(Collectors.toSet());
        for (String section : List.of(SOURCE, SINK)) {
            if (!config.hasPath(section)) {
                issues.add(ValidationIssue.error(section, "missing '" + section + "' block"));
            } else if (!sections.contains(section)) {
                issues.add(ValidationIssue.error(section, "'" + section + "' block has no plugin"));
            }
        }
        for (PluginBlock block : blocks) {
            checkBlock(block, issues);
        }
        checkRouting(blocks, issues);
        return ValidationResult.of(ValidationResult.STAGE_LOCAL, issues);
    }

    private void checkEnv(Config config, List<ValidationIssue> issues) {
        if (!config.hasPath("env")) {
            issues.add(
                    ValidationIssue.warning("env", "missing 'env' block, recommended to set job.mode and parallelism"));
            return;
        }
        Config env = config.getConfig("env");
        Optional<String> mode = lookup(env, "job.mode");
        if (mode.isEmpty()) {
            issues.add(ValidationIssue.warning("env", "job.mode is not set, BATCH is used by default"));
            return;
        }
        String jobMode = mode.get().toUpperCase(Locale.ROOT);
        if (!"BATCH".equals(jobMode) && !"STREAMING".equals(jobMode)) {
            issues.add(ValidationIssue.error("env", "job.mode must be BATCH or STREAMING, but is " + mode.get()));
        } else if ("STREAMING".equals(jobMode) && lookup(env, "checkpoint.interval").isEmpty()) {
            issues.add(ValidationIssue.warning("env", "STREAMING job should set checkpoint.interval, e.g. 10000"));
        }
    }

    private void checkBlock(PluginBlock block, List<ValidationIssue> issues) {
        Optional<ConnectorMetadata> metadata = metadataRepository.find(block.section(), block.name());
        if (metadata.isEmpty()) {
            issues.add(ValidationIssue.error(block.location(), "connector '" + block.name()
                    + "' is not available, available " + block.section() + " connectors: "
                    + metadataRepository.names(block.section())));
            return;
        }
        ConnectorMetadata connector = metadata.get();
        if (!connector.name().equals(block.name())) {
            issues.add(ValidationIssue.warning(block.location(), "use the exact connector name '" + connector.name()
                    + "'"));
        }
        Config blockConfig;
        try {
            blockConfig = ConfigFactory.parseString(block.body());
        } catch (ConfigException e) {
            issues.add(ValidationIssue.error(block.location(), "HOCON syntax error: " + e.getMessage()));
            return;
        }
        checkRequiredOptions(block, connector, blockConfig, issues);
        checkConditionalMismatch(block, connector, blockConfig, issues);
        checkUnknownOptions(block, connector, blockConfig, issues);
    }

    private void checkRequiredOptions(PluginBlock block, ConnectorMetadata connector, Config blockConfig,
                                      List<ValidationIssue> issues) {
        Map<Integer, List<OptionMetadata>> groups = new LinkedHashMap<>();
        for (OptionMetadata option : connector.required()) {
            groups.computeIfAbsent(option.group() == null ? 0 : option.group(), key -> new ArrayList<>()).add(option);
        }
        for (List<OptionMetadata> group : groups.values()) {
            String category = group.get(0).category();
            List<OptionMetadata> present = group.stream().filter(option -> isPresent(blockConfig, option))
                    .collect(Collectors.toList());
            if ("exclusive".equals(category)) {
                if (present.size() != 1) {
                    issues.add(ValidationIssue.error(block.location(), "configure exactly one of "
                            + keys(group) + (present.isEmpty() ? ", none is configured"
                                    : ", but configured "
                                            + keys(present))));
                }
            } else if ("bundled".equals(category)) {
                if (!present.isEmpty() && present.size() != group.size()) {
                    issues.add(ValidationIssue.error(block.location(), "options " + keys(group)
                            + " must be configured together, missing " + keys(missing(group, present))));
                }
            } else if ("conditional".equals(category)) {
                JsonNode condition = group.get(0).conditionTree();
                if (condition != null && evaluate(condition, connector, blockConfig)) {
                    List<OptionMetadata> missing = missing(group, present).stream()
                            .filter(option -> !option.hasDefaultValue()).collect(Collectors.toList());
                    if (!missing.isEmpty()) {
                        issues.add(ValidationIssue.error(block.location(), "missing options " + keys(missing)
                                + " required when " + group.get(0).conditionExpression()));
                    }
                }
            } else {
                List<OptionMetadata> missing = missing(group, present).stream()
                        .filter(option -> !option.hasDefaultValue()).collect(Collectors.toList());
                if (!missing.isEmpty()) {
                    issues.add(ValidationIssue.error(block.location(), "missing required options " + keys(missing)));
                }
            }
        }
    }

    /**
     * Options which only exist in conditional rules must not be configured when none of the conditions matches,
     * they cause type mismatch or unknown option errors at runtime.
     */
    private void checkConditionalMismatch(PluginBlock block, ConnectorMetadata connector, Config blockConfig,
                                          List<ValidationIssue> issues) {
        Set<String> unconditional = new HashSet<>();
        connector.optional().forEach(option -> unconditional.addAll(option.allKeys()));
        connector.required().stream().filter(option -> !"conditional".equals(option.category()))
                .forEach(option -> unconditional.addAll(option.allKeys()));

        Map<String, List<OptionMetadata>> conditionalByKey = new LinkedHashMap<>();
        connector.required().stream().filter(option -> "conditional".equals(option.category()))
                .forEach(option -> conditionalByKey.computeIfAbsent(option.key(), key -> new ArrayList<>())
                        .add(option));
        for (Map.Entry<String, List<OptionMetadata>> entry : conditionalByKey.entrySet()) {
            List<OptionMetadata> options = entry.getValue();
            if (unconditional.contains(entry.getKey()) || !isPresent(blockConfig, options.get(0))) {
                continue;
            }
            boolean matched = options.stream().anyMatch(option -> option.conditionTree() == null
                    || evaluate(option.conditionTree(), connector, blockConfig));
            if (!matched) {
                issues.add(ValidationIssue.warning(block.location(), "'" + entry.getKey()
                        + "' only takes effect when " + options.stream().map(OptionMetadata::conditionExpression)
                                .collect(Collectors.joining(" or "))
                        + ", remove it if the condition does not match"));
            }
        }
    }

    private void checkUnknownOptions(PluginBlock block, ConnectorMetadata connector, Config blockConfig,
                                     List<ValidationIssue> issues) {
        Set<String> known = new HashSet<>(metadataRepository.commonOptions(block.section()));
        connector.allOptions().forEach(option -> known.addAll(option.allKeys()));
        List<String> unknown = new ArrayList<>();
        collectUnknownKeys(blockConfig.root(), "", known, unknown);
        if (!unknown.isEmpty()) {
            issues.add(ValidationIssue.warning(block.location(), "unknown options " + unknown
                    + " for " + connector.name() + " " + connector.type() + ", check the option names"));
        }
    }

    private static void collectUnknownKeys(ConfigObject object, String prefix, Set<String> known,
                                           List<String> unknown) {
        for (Map.Entry<String, ConfigValue> entry : object.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (known.contains(key)) {
                continue;
            }
            // A dotted option such as fs.s3a.endpoint is parsed as nested objects when the key is not quoted
            boolean isPrefixOfKnown = known.stream().anyMatch(knownKey -> knownKey.startsWith(key + "."));
            if (isPrefixOfKnown && entry.getValue().valueType() == ConfigValueType.OBJECT) {
                collectUnknownKeys((ConfigObject) entry.getValue(), key, known, unknown);
            } else {
                unknown.add(key);
            }
        }
    }

    private void checkRouting(List<PluginBlock> blocks, List<ValidationIssue> issues) {
        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, String> inputs = new LinkedHashMap<>();
        for (PluginBlock block : blocks) {
            Config blockConfig;
            try {
                blockConfig = ConfigFactory.parseString(block.body());
            } catch (ConfigException e) {
                continue;
            }
            for (String key : OUTPUT_KEYS) {
                for (String label : labels(blockConfig, key)) {
                    String previous = outputs.put(label, block.location());
                    if (previous != null) {
                        issues.add(ValidationIssue.error(block.location(), "duplicate output label \"" + label
                                + "\", already used by " + previous));
                    }
                }
            }
            for (String key : INPUT_KEYS) {
                for (String label : labels(blockConfig, key)) {
                    inputs.put(label, block.location());
                }
            }
        }
        for (Map.Entry<String, String> input : inputs.entrySet()) {
            if (!outputs.containsKey(input.getKey())) {
                issues.add(ValidationIssue.error(input.getValue(), "input label \"" + input.getKey()
                        + "\" has no matching plugin_output, available labels: " + outputs.keySet()));
            }
        }
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            if (!inputs.containsKey(output.getKey()) && !inputs.isEmpty()) {
                issues.add(ValidationIssue.warning(output.getValue(), "output label \"" + output.getKey()
                        + "\" is not consumed by any transform or sink"));
            }
        }
    }

    private static List<String> labels(Config config, String key) {
        if (!config.hasPath(key)) {
            return List.of();
        }
        ConfigValue value = config.getValue(key);
        if (value.valueType() == ConfigValueType.LIST) {
            return config.getStringList(key);
        }
        return List.of(String.valueOf(value.unwrapped()));
    }

    /**
     * Evaluate a condition tree exported from the option rule, the value of an option which is not configured is its
     * default value.
     */
    private boolean evaluate(JsonNode expression, ConnectorMetadata connector, Config blockConfig) {
        boolean result = evaluateCondition(expression.get("condition"), connector, blockConfig);
        JsonNode current = expression;
        while (current.hasNonNull("next")) {
            JsonNode next = current.get("next");
            boolean nextResult = evaluateCondition(next.get("condition"), connector, blockConfig);
            result = "OR".equals(current.path("operator").asText()) ? result || nextResult : result && nextResult;
            current = next;
        }
        return result;
    }

    private boolean evaluateCondition(JsonNode condition, ConnectorMetadata connector, Config blockConfig) {
        if (condition == null || condition.isNull()) {
            return false;
        }
        boolean result = matches(condition, connector, blockConfig);
        JsonNode current = condition;
        while (current.hasNonNull("next")) {
            JsonNode next = current.get("next");
            boolean nextResult = matches(next, connector, blockConfig);
            result = "OR".equals(current.path("operator").asText()) ? result || nextResult : result && nextResult;
            current = next;
        }
        return result;
    }

    private boolean matches(JsonNode condition, ConnectorMetadata connector, Config blockConfig) {
        String key = condition.path("key").asText();
        String expected = condition.path("expectValue").asText(null);
        Optional<String> actual = lookup(blockConfig, key);
        if (actual.isEmpty()) {
            actual = connector.findOption(key).filter(OptionMetadata::hasDefaultValue)
                    .map(option -> option.defaultValue().asText());
        }
        return actual.isPresent() && expected != null && actual.get().equalsIgnoreCase(expected);
    }

    private static boolean isPresent(Config config, OptionMetadata option) {
        return option.allKeys().stream().anyMatch(key -> lookup(config, key).isPresent()
                || hasObject(config, key));
    }

    private static boolean hasObject(Config config, String key) {
        try {
            return config.hasPath(key) || config.root().containsKey(key);
        } catch (ConfigException e) {
            return config.root().containsKey(key);
        }
    }

    /**
     * Get a scalar value by a dotted key, the key may be written as nested objects or as a quoted key.
     */
    private static Optional<String> lookup(Config config, String key) {
        try {
            if (config.hasPath(key)) {
                ConfigValue value = config.getValue(key);
                return value.valueType() == ConfigValueType.OBJECT || value.valueType() == ConfigValueType.LIST
                        ? Optional.empty()
                        : Optional.of(String.valueOf(value.unwrapped()));
            }
        } catch (ConfigException e) {
            // the key is not a valid path, try the quoted key below
        }
        ConfigValue value = config.root().get(key);
        if (value == null || value.valueType() == ConfigValueType.OBJECT || value.valueType() == ConfigValueType.LIST) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(value.unwrapped()));
    }

    private static List<OptionMetadata> missing(List<OptionMetadata> group, List<OptionMetadata> present) {
        return group.stream().filter(option -> !present.contains(option)).collect(Collectors.toList());
    }

    private static String keys(List<OptionMetadata> options) {
        return options.stream().map(OptionMetadata::key).distinct().collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * Replace {@code ${xxx}} with plain tokens so that the config can be parsed without the task parameters.
     */
    static String replacePlaceholders(String configText) {
        Matcher matcher = PLACEHOLDER.matcher(configText);
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (matcher.find()) {
            matcher.appendReplacement(result, "__ds_param_" + index++ + "__");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Extract the plugin blocks of the source / transform / sink sections from the raw text, the blocks with the
     * same connector name are kept separately (the HOCON parser would merge them).
     */
    static List<PluginBlock> extractBlocks(String text) {
        List<PluginBlock> blocks = new ArrayList<>();
        Deque<int[]> openPositions = new ArrayDeque<>();
        Deque<String> openNames = new ArrayDeque<>();
        boolean inString = false;
        boolean inComment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inComment) {
                inComment = c != '\n';
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '#' || (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/')) {
                inComment = true;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                openPositions.push(new int[]{i});
                openNames.push(nameBefore(text, i));
            } else if (c == '}' && !openPositions.isEmpty()) {
                int start = openPositions.pop()[0];
                String name = openNames.pop();
                // A plugin block is directly inside a top level section
                if (openPositions.size() == 1 && SECTIONS.contains(openNames.peek())) {
                    blocks.add(new PluginBlock(openNames.peek(), name, text.substring(start + 1, i),
                            lineOf(text, start)));
                }
            }
        }
        return blocks;
    }

    private static String nameBefore(String text, int bracePosition) {
        int end = bracePosition - 1;
        while (end >= 0 && (Character.isWhitespace(text.charAt(end)) || text.charAt(end) == '='
                || text.charAt(end) == ':')) {
            end--;
        }
        int start = end;
        while (start >= 0
                && (Character.isLetterOrDigit(text.charAt(start)) || "_-.\"".indexOf(text.charAt(start)) >= 0)) {
            start--;
        }
        String name = text.substring(start + 1, end + 1).replace("\"", "");
        String lower = name.toLowerCase(Locale.ROOT);
        return SECTIONS.contains(lower) ? lower : name;
    }

    private static int lineOf(String text, int position) {
        int line = 1;
        for (int i = 0; i < position; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
