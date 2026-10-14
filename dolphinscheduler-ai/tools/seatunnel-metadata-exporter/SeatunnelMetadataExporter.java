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

import org.apache.seatunnel.shade.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.seatunnel.shade.com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.seatunnel.shade.com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.seatunnel.shade.com.fasterxml.jackson.databind.node.ObjectNode;

import org.apache.seatunnel.api.common.PluginIdentifier;
import org.apache.seatunnel.api.configuration.Option;
import org.apache.seatunnel.api.configuration.SingleChoiceOption;
import org.apache.seatunnel.api.configuration.util.Condition;
import org.apache.seatunnel.api.configuration.util.Expression;
import org.apache.seatunnel.api.configuration.util.OptionRule;
import org.apache.seatunnel.api.configuration.util.RequiredOption;
import org.apache.seatunnel.common.config.Common;
import org.apache.seatunnel.common.config.DeployMode;
import org.apache.seatunnel.plugin.discovery.PluginDiscovery;
import org.apache.seatunnel.plugin.discovery.seatunnel.SeaTunnelSinkPluginDiscovery;
import org.apache.seatunnel.plugin.discovery.seatunnel.SeaTunnelSourcePluginDiscovery;
import org.apache.seatunnel.plugin.discovery.seatunnel.SeaTunnelTransformPluginDiscovery;

import java.io.File;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports the option rules of all installed SeaTunnel connectors (source / sink / transform) as JSON, the
 * metadata is used by the DolphinScheduler AI assistant to generate and validate SeaTunnel configs.
 *
 * <p>Ported from the {@code MetadataExportCommand} of the SeaTunnel dev branch (Apache License 2.0) and adapted to
 * the SeaTunnel 2.3.x API, which has no condition rules and value constraints.
 *
 * <p>Usage: {@code java -cp "$SEATUNNEL_HOME/starter/seatunnel-starter.jar:$SEATUNNEL_HOME/lib/*:..."
 * SeatunnelMetadataExporter <output.json>}
 */
public class SeatunnelMetadataExporter {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: SeatunnelMetadataExporter <output.json>");
            System.exit(1);
        }
        Common.setDeployMode(DeployMode.CLIENT);
        new SeatunnelMetadataExporter().export(new File(args[0]));
    }

    private void export(File outputFile) throws Exception {
        Map<String, PluginDiscovery<?>> discoveries = new LinkedHashMap<>();
        discoveries.put("source", new SeaTunnelSourcePluginDiscovery());
        discoveries.put("sink", new SeaTunnelSinkPluginDiscovery());
        discoveries.put("transform", new SeaTunnelTransformPluginDiscovery());

        ObjectNode root = mapper.createObjectNode();
        root.put("version", getSeaTunnelVersion());
        root.put("exportTime", System.currentTimeMillis());
        ArrayNode connectors = root.putArray("connectors");
        for (Map.Entry<String, PluginDiscovery<?>> discovery : discoveries.entrySet()) {
            LinkedHashMap<PluginIdentifier, OptionRule> plugins = discovery.getValue().getPlugins();
            for (Map.Entry<PluginIdentifier, OptionRule> plugin : plugins.entrySet()) {
                connectors.add(exportConnector(plugin.getKey(), plugin.getValue(), discovery.getKey()));
            }
        }
        mapper.writeValue(outputFile, root);
        System.err.println("Exported " + connectors.size() + " connectors to " + outputFile.getAbsolutePath());
    }

    private ObjectNode exportConnector(PluginIdentifier id, OptionRule rule, String type) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", id.getPluginName());
        node.put("type", type);

        ArrayNode required = node.putArray("required");
        int group = 0;
        for (RequiredOption requiredOption : rule.getRequiredOptions()) {
            // Options in the same group belong to the same exclusive / bundled / conditional rule
            group++;
            String category = resolveCategory(requiredOption);
            Expression expression = requiredOption instanceof RequiredOption.ConditionalRequiredOptions
                    ? ((RequiredOption.ConditionalRequiredOptions) requiredOption).getExpression()
                    : null;
            for (Option<?> option : requiredOption.getOptions()) {
                ObjectNode optionNode = exportOption(option);
                optionNode.put("category", category);
                optionNode.put("group", group);
                if (expression != null) {
                    optionNode.put("conditionExpression", expression.toString());
                    optionNode.set("conditionTree", exportExpression(expression));
                }
                required.add(optionNode);
            }
        }

        ArrayNode optional = node.putArray("optional");
        for (Option<?> option : rule.getOptionalOptions()) {
            optional.add(exportOption(option));
        }
        return node;
    }

    private ObjectNode exportOption(Option<?> option) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key", option.key());
        Type rawType = option.typeReference().getType();
        node.put("type", resolveTypeName(rawType));
        Object defaultValue = option.defaultValue();
        if (defaultValue == null) {
            node.putNull("defaultValue");
        } else {
            try {
                node.set("defaultValue", mapper.valueToTree(defaultValue));
            } catch (IllegalArgumentException e) {
                node.put("defaultValue", String.valueOf(defaultValue));
            }
        }
        String description = option.getDescription();
        if (description != null && !description.isEmpty()) {
            node.put("description", description);
        }
        List<String> fallbackKeys = option.getFallbackKeys();
        if (fallbackKeys != null && !fallbackKeys.isEmpty()) {
            ArrayNode fallback = node.putArray("fallbackKeys");
            fallbackKeys.forEach(fallback::add);
        }
        ArrayNode optionValues = exportOptionValues(option, rawType);
        if (optionValues != null) {
            node.set("optionValues", optionValues);
        }
        return node;
    }

    private ArrayNode exportOptionValues(Option<?> option, Type rawType) {
        if (option instanceof SingleChoiceOption) {
            List<?> values = ((SingleChoiceOption<?>) option).getOptionValues();
            if (values != null && !values.isEmpty()) {
                ArrayNode array = mapper.createArrayNode();
                values.forEach(value -> array.add(String.valueOf(value)));
                return array;
            }
        }
        if (rawType instanceof Class && ((Class<?>) rawType).isEnum()) {
            Object[] constants = ((Class<?>) rawType).getEnumConstants();
            if (constants != null && constants.length > 0 && constants.length <= 50) {
                ArrayNode array = mapper.createArrayNode();
                for (Object constant : constants) {
                    array.add(String.valueOf(constant));
                }
                return array;
            }
        }
        return null;
    }

    private ObjectNode exportExpression(Expression expression) {
        ObjectNode node = mapper.createObjectNode();
        node.set("condition", exportCondition(expression.getCondition()));
        if (expression.and() != null) {
            node.put("operator", expression.and() ? "AND" : "OR");
        }
        if (expression.hasNext()) {
            node.set("next", exportExpression(expression.getNext()));
        }
        return node;
    }

    private ObjectNode exportCondition(Condition<?> condition) {
        ObjectNode node = mapper.createObjectNode();
        node.put("key", condition.getOption().key());
        if (condition.getExpectValue() != null) {
            node.put("expectValue", String.valueOf(condition.getExpectValue()));
        }
        if (condition.and() != null) {
            node.put("operator", condition.and() ? "AND" : "OR");
        }
        if (condition.hasNext()) {
            node.set("next", exportCondition(condition.getNext()));
        }
        return node;
    }

    private static String resolveTypeName(Type type) {
        if (type == null) {
            return "object";
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            StringBuilder name = new StringBuilder(resolveTypeName(parameterizedType.getRawType())).append("<");
            Type[] arguments = parameterizedType.getActualTypeArguments();
            for (int i = 0; i < arguments.length; i++) {
                name.append(i > 0 ? "," : "").append(resolveTypeName(arguments[i]));
            }
            return name.append(">").toString();
        }
        if (type instanceof Class) {
            Class<?> clazz = (Class<?>) type;
            if (clazz == String.class) {
                return "string";
            }
            if (clazz == Boolean.class || clazz == boolean.class) {
                return "boolean";
            }
            if (clazz == Integer.class || clazz == int.class) {
                return "int";
            }
            if (clazz == Long.class || clazz == long.class) {
                return "long";
            }
            if (clazz == Double.class || clazz == double.class) {
                return "double";
            }
            if (clazz == Float.class || clazz == float.class) {
                return "float";
            }
            if (List.class.isAssignableFrom(clazz)) {
                return "list";
            }
            if (Map.class.isAssignableFrom(clazz)) {
                return "map";
            }
            if (clazz.isEnum()) {
                return "enum<" + clazz.getSimpleName() + ">";
            }
            String simpleName = clazz.getSimpleName();
            if (!simpleName.isEmpty() && !simpleName.contains("$")) {
                return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
            }
        }
        return "object";
    }

    private static String resolveCategory(RequiredOption requiredOption) {
        if (requiredOption instanceof RequiredOption.AbsolutelyRequiredOptions) {
            return "absolutely_required";
        } else if (requiredOption instanceof RequiredOption.ExclusiveRequiredOptions) {
            return "exclusive";
        } else if (requiredOption instanceof RequiredOption.BundledRequiredOptions) {
            return "bundled";
        } else if (requiredOption instanceof RequiredOption.ConditionalRequiredOptions) {
            return "conditional";
        }
        return "unknown";
    }

    private static String getSeaTunnelVersion() {
        Package pkg = Common.class.getPackage();
        String version = pkg != null ? pkg.getImplementationVersion() : null;
        return version != null ? version : "unknown";
    }
}
