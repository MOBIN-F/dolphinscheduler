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

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Metadata of a connector option, exported from the option rule of the connector factory.
 *
 * @param category            for required options: absolutely_required / exclusive / bundled / conditional
 * @param group               options with the same group belong to the same required rule
 * @param conditionExpression for conditional options, e.g. {@code 'file_format_type' == TEXT}
 * @param conditionTree       structured condition: {@code {condition: {key, expectValue, operator, next}, operator, next}}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OptionMetadata(
        String key,
        String type,
        JsonNode defaultValue,
        String description,
        List<String> fallbackKeys,
        List<String> optionValues,
        String category,
        Integer group,
        String conditionExpression,
        JsonNode conditionTree) {

    public List<String> fallbackKeys() {
        return fallbackKeys == null ? List.of() : fallbackKeys;
    }

    public List<String> optionValues() {
        return optionValues == null ? List.of() : optionValues;
    }

    /**
     * @return the key and the fallback keys
     */
    public List<String> allKeys() {
        if (fallbackKeys().isEmpty()) {
            return List.of(key);
        }
        List<String> keys = new ArrayList<>();
        keys.add(key);
        keys.addAll(fallbackKeys());
        return keys;
    }

    public boolean hasDefaultValue() {
        return defaultValue != null && !defaultValue.isNull();
    }
}
