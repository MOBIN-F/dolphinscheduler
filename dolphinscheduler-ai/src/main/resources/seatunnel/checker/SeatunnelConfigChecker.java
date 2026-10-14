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
import org.apache.seatunnel.shade.com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.seatunnel.shade.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.seatunnel.shade.com.typesafe.config.Config;

import org.apache.seatunnel.api.common.PluginIdentifier;
import org.apache.seatunnel.api.configuration.Option;
import org.apache.seatunnel.api.configuration.ReadonlyConfig;
import org.apache.seatunnel.api.configuration.util.ConfigValidator;
import org.apache.seatunnel.api.configuration.util.OptionRule;
import org.apache.seatunnel.api.configuration.util.RequiredOption;
import org.apache.seatunnel.common.config.Common;
import org.apache.seatunnel.common.config.DeployMode;
import org.apache.seatunnel.core.starter.utils.ConfigBuilder;
import org.apache.seatunnel.plugin.discovery.PluginDiscovery;
import org.apache.seatunnel.plugin.discovery.seatunnel.SeaTunnelSinkPluginDiscovery;
import org.apache.seatunnel.plugin.discovery.seatunnel.SeaTunnelSourcePluginDiscovery;
import org.apache.seatunnel.plugin.discovery.seatunnel.SeaTunnelTransformPluginDiscovery;

import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates a SeaTunnel job config with the SeaTunnel engine API (HOCON parsing, installed plugins, option rules
 * and option value types), and prints the result as JSON to stdout.
 *
 * <p>SeaTunnel 2.3.x {@code seatunnel.sh --check} does not validate anything, so the DolphinScheduler AI assistant
 * runs this class in a separate JVM with the jars of the installed SeaTunnel:
 * {@code java -cp "<dir>:$SEATUNNEL_HOME/starter/seatunnel-starter.jar:$SEATUNNEL_HOME/lib/*:$SEATUNNEL_HOME/connectors/*"
 * SeatunnelConfigChecker <config file>}
 */
public class SeatunnelConfigChecker {

    private static final String[] SECTIONS = {"source", "transform", "sink"};

    private final ObjectMapper mapper = new ObjectMapper();
    private final ArrayNode errors = mapper.createArrayNode();

    public static void main(String[] args) throws Exception {
        // Keep stdout for the JSON result only, the SeaTunnel classes may log to stdout
        PrintStream stdout = System.out;
        System.setOut(System.err);
        if (args.length != 1) {
            System.err.println("Usage: SeatunnelConfigChecker <config file>");
            System.exit(2);
        }
        Common.setDeployMode(DeployMode.CLIENT);
        SeatunnelConfigChecker checker = new SeatunnelConfigChecker();
        checker.check(args[0]);
        ObjectNode result = checker.mapper.createObjectNode();
        result.put("valid", checker.errors.size() == 0);
        result.set("errors", checker.errors);
        stdout.println(checker.mapper.writeValueAsString(result));
    }

    private void check(String configFile) {
        Config config;
        try {
            config = ConfigBuilder.of(Paths.get(configFile));
        } catch (Throwable e) {
            addError("parse", "config", rootMessage(e));
            return;
        }
        Map<String, LinkedHashMap<PluginIdentifier, OptionRule>> plugins = new LinkedHashMap<>();
        for (String section : SECTIONS) {
            if (!config.hasPath(section)) {
                if (!"transform".equals(section)) {
                    addError("structure", section, "missing '" + section + "' block");
                }
                continue;
            }
            List<? extends Config> pluginConfigs;
            try {
                pluginConfigs = config.getConfigList(section);
            } catch (Throwable e) {
                addError("structure", section, rootMessage(e));
                continue;
            }
            for (Config pluginConfig : pluginConfigs) {
                checkPlugin(section, pluginConfig, plugins.computeIfAbsent(section, this::discoverPlugins));
            }
        }
    }

    private void checkPlugin(String section, Config pluginConfig, Map<PluginIdentifier, OptionRule> installed) {
        String pluginName = pluginConfig.hasPath("plugin_name") ? pluginConfig.getString("plugin_name") : "";
        String location = section + "." + pluginName;
        OptionRule rule = null;
        for (Map.Entry<PluginIdentifier, OptionRule> entry : installed.entrySet()) {
            if (entry.getKey().getPluginName().equalsIgnoreCase(pluginName)) {
                rule = entry.getValue();
                break;
            }
        }
        if (rule == null) {
            List<String> names = new ArrayList<>();
            installed.keySet().forEach(id -> names.add(id.getPluginName()));
            addError("plugin", location, "plugin '" + pluginName + "' is not installed, installed " + section
                    + " plugins: " + names);
            return;
        }
        ReadonlyConfig readonlyConfig = ReadonlyConfig.fromConfig(pluginConfig);
        try {
            ConfigValidator.of(readonlyConfig).validate(rule);
        } catch (Throwable e) {
            addError("option", location, rootMessage(e));
        }
        // Read every declared option so that values with a wrong type are reported
        List<Option<?>> options = new ArrayList<>(rule.getOptionalOptions());
        for (RequiredOption requiredOption : rule.getRequiredOptions()) {
            options.addAll(requiredOption.getOptions());
        }
        for (Option<?> option : options) {
            try {
                readonlyConfig.getOptional(option);
            } catch (Throwable e) {
                addError("type", location, "option '" + option.key() + "': " + rootMessage(e));
            }
        }
    }

    private LinkedHashMap<PluginIdentifier, OptionRule> discoverPlugins(String section) {
        PluginDiscovery<?> discovery;
        if ("source".equals(section)) {
            discovery = new SeaTunnelSourcePluginDiscovery();
        } else if ("sink".equals(section)) {
            discovery = new SeaTunnelSinkPluginDiscovery();
        } else {
            discovery = new SeaTunnelTransformPluginDiscovery();
        }
        return discovery.getPlugins();
    }

    private void addError(String phase, String location, String message) {
        ObjectNode error = errors.addObject();
        error.put("phase", phase);
        error.put("location", location);
        error.put("message", message);
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
