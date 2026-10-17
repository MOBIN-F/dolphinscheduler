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

package org.apache.dolphinscheduler.task.executor.log;

import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;

import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Prints the task execution context for the server log without secrets: the prepared parameters of the SeaTunnel
 * connections ({@code stconn.<key>.<field>}) hold the passwords injected by the master.
 */
public final class TaskExecutionContextLogs {

    static final String SECRET_PREFIX = "stconn.";
    static final String MASK = "******";

    private TaskExecutionContextLogs() {
    }

    public static String toMaskedJson(TaskExecutionContext taskExecutionContext) {
        JsonNode node = JSONUtils.toJsonNode(taskExecutionContext);
        JsonNode params = node.path("prepareParamsMap");
        if (params.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> entries = params.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                if (entry.getKey().startsWith(SECRET_PREFIX) && entry.getValue().isObject()) {
                    ((ObjectNode) entry.getValue()).put("value", MASK);
                }
            }
        }
        return JSONUtils.toPrettyJsonString(node);
    }
}
