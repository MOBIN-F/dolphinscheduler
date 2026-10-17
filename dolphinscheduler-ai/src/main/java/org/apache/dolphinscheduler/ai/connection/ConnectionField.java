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

import java.util.List;
import java.util.Map;

/**
 * A field of the connection form, the form is rendered by the browser from these definitions.
 *
 * @param input    text, password, number, select or textarea
 * @param secret   stored encrypted, never returned by the api, referenced as {@code ${stconn.<key>.<field>}} in the
 *                 scripts and injected when the task runs
 * @param showWhen the field is only shown and used when the other fields have one of the values
 */
public record ConnectionField(String key, String label, String labelZh, String input, boolean required,
        boolean secret, String defaultValue, List<String> options, Map<String, List<String>> showWhen) {

    static ConnectionField text(String key, String label, String labelZh, boolean required, String defaultValue) {
        return new ConnectionField(key, label, labelZh, "text", required, false, defaultValue, List.of(), Map.of());
    }

    static ConnectionField number(String key, String label, String labelZh, String defaultValue) {
        return new ConnectionField(key, label, labelZh, "number", true, false, defaultValue, List.of(), Map.of());
    }

    static ConnectionField textarea(String key, String label, String labelZh) {
        return new ConnectionField(key, label, labelZh, "textarea", false, false, "", List.of(), Map.of());
    }

    static ConnectionField password(String key, String label, String labelZh, boolean required) {
        return new ConnectionField(key, label, labelZh, "password", required, true, "", List.of(), Map.of());
    }

    static ConnectionField secretText(String key, String label, String labelZh) {
        return new ConnectionField(key, label, labelZh, "textarea", false, true, "", List.of(), Map.of());
    }

    static ConnectionField select(String key, String label, String labelZh, String defaultValue,
                                  List<String> options) {
        return new ConnectionField(key, label, labelZh, "select", true, false, defaultValue, options, Map.of());
    }

    ConnectionField when(String field, String... values) {
        return new ConnectionField(key, label, labelZh, input, required, secret, defaultValue, options,
                Map.of(field, List.of(values)));
    }

    /**
     * @return whether the field applies with the given values of the connection
     */
    public boolean isActive(Map<String, String> values) {
        return showWhen.entrySet().stream()
                .allMatch(condition -> condition.getValue().contains(values.getOrDefault(condition.getKey(),
                        "")));
    }
}
