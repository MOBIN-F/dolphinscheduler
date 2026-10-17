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

package org.apache.dolphinscheduler.ai.seatunnel.agent;

import java.util.List;
import java.util.Map;

/**
 * A step of a request shown as the progress: a tool call, a validation, an LLM call. The browser renders the title
 * and the summary from the kind, the arguments and the result, so the server sends structured data only.
 *
 * @param state   running, ok, warn or error
 * @param args    e.g. {target: kafka_dev / orders}
 * @param result  e.g. {fields: 7, sensitive: 2} or {message: the reason of a failure}
 * @param details shown when the step is expanded, without secrets or sample values
 */
public record AgentStep(int id, String kind, String state, Map<String, Object> args, Map<String, Object> result,
        List<String> details, long millis) {

    public static final String RUNNING = "running";
    public static final String OK = "ok";
    public static final String WARN = "warn";
    public static final String ERROR = "error";

    public static final String THINKING = "thinking";
    public static final String SELECTION = "selection";
    public static final String CONNECTOR_INFO = "connector_info";
    public static final String LIST_CONNECTORS = "list_connectors";
    public static final String CHECK_CONFIG = "check_config";
    public static final String LIST_CONNECTIONS = "list_connections";
    public static final String BROWSE = "browse";
    public static final String DESCRIBE = "describe";
    public static final String HTTP_SAMPLE = "http_sample";
    public static final String VALIDATE_LOCAL = "validate_local";
    public static final String VALIDATE_ENGINE = "validate_engine";
    public static final String FIXING = "fixing";
    public static final String TOOL = "tool";
}
