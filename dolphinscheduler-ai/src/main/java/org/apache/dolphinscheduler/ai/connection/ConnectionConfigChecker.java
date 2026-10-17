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

import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

/**
 * Checks the rules of the selected connections in a generated config, e.g. the consumer group of a Kafka cluster
 * authorizing the groups by prefix. The issues are fixed by the LLM like the other validation errors.
 */
public final class ConnectionConfigChecker {

    private ConnectionConfigChecker() {
    }

    public static List<ValidationIssue> check(String configText, List<ConnectionSelection> selections) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (selections == null || selections.stream().allMatch(selection -> selection.kafkaGroupPrefix().isEmpty())) {
            return issues;
        }
        Config config;
        try {
            config = ConfigFactory.parseString(configText);
        } catch (ConfigException e) {
            // The syntax errors are reported by the local validation
            return issues;
        }
        if (!config.hasPath("source")) {
            return issues;
        }
        for (ConnectionSelection selection : selections) {
            String prefix = selection.kafkaGroupPrefix();
            if (prefix.isEmpty()) {
                continue;
            }
            String servers = selection.connection().param("bootstrap_servers");
            for (Map.Entry<String, ConfigValue> plugin : config.getObject("source").entrySet()) {
                if (!plugin.getKey().equalsIgnoreCase("Kafka")
                        || plugin.getValue().valueType() != ConfigValueType.OBJECT) {
                    continue;
                }
                Config kafka = config.getConfig("source").getConfig("\"" + plugin.getKey() + "\"");
                if (!servers.equals(string(kafka, "bootstrap.servers"))) {
                    continue;
                }
                String group = string(kafka, "consumer.group");
                String location = "source.Kafka";
                if (group == null || group.isEmpty()) {
                    issues.add(ValidationIssue.error(location,
                            "consumer.group is required, the cluster only authorizes "
                                    + "the consumer groups starting with '" + prefix + "': use "
                                    + expected(selection)));
                } else if (!KafkaConsumerGroups.isValid(prefix, group)) {
                    issues.add(ValidationIssue.error(location, "consumer.group '" + group + "' must be the prefix '"
                            + prefix + "' followed by a suffix, not the prefix alone: use " + expected(selection)));
                } else if (selection.consumerGroup() != null && !selection.consumerGroup().isEmpty()
                        && !group.equals(selection.consumerGroup())) {
                    issues.add(ValidationIssue.warning(location, "consumer.group of the task should be '"
                            + selection.consumerGroup() + "'"));
                }
            }
        }
        return issues;
    }

    private static String expected(ConnectionSelection selection) {
        return selection.consumerGroup() == null || selection.consumerGroup().isEmpty()
                ? "the prefix followed by a suffix describing the job"
                : "\"" + selection.consumerGroup() + "\"";
    }

    private static String string(Config config, String path) {
        try {
            return config.hasPath(path) ? config.getString(path) : null;
        } catch (ConfigException e) {
            return null;
        }
    }
}
