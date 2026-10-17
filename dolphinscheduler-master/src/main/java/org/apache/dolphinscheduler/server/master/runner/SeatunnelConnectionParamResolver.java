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

package org.apache.dolphinscheduler.server.master.runner;

import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.SeatunnelConnection;
import org.apache.dolphinscheduler.dao.repository.SeatunnelConnectionDao;
import org.apache.dolphinscheduler.dao.utils.SeatunnelConnectionSecretCodec;
import org.apache.dolphinscheduler.plugin.task.api.enums.DataType;
import org.apache.dolphinscheduler.plugin.task.api.enums.Direct;
import org.apache.dolphinscheduler.plugin.task.api.model.Property;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Resolves the references {@code ${stconn.<key>.<field>}} of the SeaTunnel connections in the script of a SeaTunnel
 * task: the secrets are added to the prepared parameters, which the SeaTunnel task of the worker replaces in the
 * script, so the secrets are never saved in the task definition.
 */
@Slf4j
@Component
public class SeatunnelConnectionParamResolver {

    public static final String PREFIX = "stconn.";

    private static final String SEATUNNEL = "SEATUNNEL";
    private static final Pattern REFERENCE = Pattern.compile("\\$\\{stconn\\.([a-z][a-z0-9_]{1,63})\\.([a-z_]+)}");

    private final SeatunnelConnectionDao connectionDao;

    public SeatunnelConnectionParamResolver(SeatunnelConnectionDao connectionDao) {
        this.connectionDao = connectionDao;
    }

    /**
     * @return the prepared parameters with the referenced secrets
     * @throws IllegalArgumentException when a referenced connection or secret does not exist
     */
    public Map<String, Property> resolve(String taskType, String taskParams, Map<String, Property> prepareParams) {
        if (!SEATUNNEL.equals(taskType) || taskParams == null || !taskParams.contains("${" + PREFIX)) {
            return prepareParams;
        }
        JsonNode params = JSONUtils.parseObject(taskParams);
        String script = params == null ? "" : params.path("rawScript").asText("");
        Map<String, Set<String>> fieldsByKey = new LinkedHashMap<>();
        Matcher matcher = REFERENCE.matcher(script);
        while (matcher.find()) {
            fieldsByKey.computeIfAbsent(matcher.group(1), key -> new LinkedHashSet<>()).add(matcher.group(2));
        }
        if (fieldsByKey.isEmpty()) {
            return prepareParams;
        }
        Map<String, SeatunnelConnection> connections = connectionDao.queryByKeys(fieldsByKey.keySet()).stream()
                .collect(Collectors.toMap(SeatunnelConnection::getConnKey, Function.identity()));
        Map<String, Property> result = new LinkedHashMap<>(prepareParams == null ? Collections.emptyMap()
                : prepareParams);
        for (Map.Entry<String, Set<String>> entry : fieldsByKey.entrySet()) {
            String key = entry.getKey();
            SeatunnelConnection connection = connections.get(key);
            if (connection == null) {
                throw new IllegalArgumentException("SeaTunnel connection '" + key + "' not found");
            }
            Map<String, String> secrets = SeatunnelConnectionSecretCodec.decode(connection.getSecrets());
            for (String field : entry.getValue()) {
                String value = value(connection, secrets, field);
                if (value == null) {
                    throw new IllegalArgumentException("SeaTunnel connection '" + key + "' has no secret '" + field
                            + "'");
                }
                String name = PREFIX + key + "." + field;
                result.put(name, new Property(name, Direct.IN, DataType.VARCHAR, escape(value)));
            }
        }
        log.info("Resolved the SeaTunnel connections {} of the SeaTunnel task", fieldsByKey.keySet());
        return result;
    }

    private static String value(SeatunnelConnection connection, Map<String, String> secrets, String field) {
        if (field.equals("basic_auth")) {
            Map<String, String> params = JSONUtils.parseObject(connection.getParams(),
                    new TypeReference<Map<String, String>>() {
                    });
            String username = params == null ? "" : params.getOrDefault("basic_username", "");
            return Base64.getEncoder().encodeToString((username + ":" + secrets.getOrDefault("basic_password", ""))
                    .getBytes(StandardCharsets.UTF_8));
        }
        return secrets.get(field);
    }

    /**
     * The references are always in double-quoted strings of the HOCON or JSON config.
     */
    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
