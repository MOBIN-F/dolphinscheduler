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

import java.util.Map;

/**
 * A SeaTunnel connection maintained in the security center.
 *
 * @param key     the immutable identifier referenced by the scripts, e.g. {@code kafka_prod}
 * @param params  the non-secret fields
 * @param secrets the decrypted secret fields, only used on the server to browse the connection; empty when the
 *                connection is passed to the LLM context
 */
public record SeatunnelConnection(String key, String name, ConnectionType type, Map<String, String> params,
        Map<String, String> secrets) {

    public static final String PLACEHOLDER_PREFIX = "stconn";

    public String param(String field) {
        String value = params.get(field);
        if (value == null || value.isEmpty()) {
            return type.field(field).map(ConnectionField::defaultValue).orElse("");
        }
        return value;
    }

    public String secret(String field) {
        return secrets.getOrDefault(field, "");
    }

    /**
     * @return the reference of a secret field in the scripts, replaced by the master when the task runs
     */
    public String placeholder(String field) {
        return "${" + PLACEHOLDER_PREFIX + "." + key + "." + field + "}";
    }

    public boolean hasSecret(String field) {
        return !secret(field).isEmpty();
    }
}
