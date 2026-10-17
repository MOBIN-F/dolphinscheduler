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

import org.apache.dolphinscheduler.ai.connection.ConnectionSelection;
import org.apache.dolphinscheduler.ai.connection.ConnectionToolBackend;

import java.util.List;
import java.util.Map;

/**
 * A chat request of the SeaTunnel config assistant. The server is stateless, the browser sends the history and the
 * current script of the editor in every request.
 *
 * @param history       previous messages, without the generated configs
 * @param userInput     the new message of the user
 * @param currentScript the script in the editor, may be empty
 * @param context       task context, e.g. startupScript, deployMode, runMode
 * @param connections   the connections the user selected for the source and the sink, may be empty
 * @param references    the secrets of the selected connections by their references, e.g. {@code
 *                      ${stconn.api.token}}, only used in the HTTP calls of the tools and never sent to the LLM
 * @param connectionBackend the connections of the user for the tools reading the fields, may be null
 * @param taskCode      the task of the script, e.g. for the Kafka consumer group, may be null
 */
public record SeatunnelChatRequest(List<ChatTurn> history, String userInput, String currentScript,
                                   Map<String, String> context, List<ConnectionSelection> connections,
                                   Map<String, String> references, ConnectionToolBackend connectionBackend,
                                   Long taskCode) {

    public SeatunnelChatRequest(List<ChatTurn> history, String userInput, String currentScript,
                                Map<String, String> context) {
        this(history, userInput, currentScript, context, List.of(), Map.of());
    }

    public SeatunnelChatRequest(List<ChatTurn> history, String userInput, String currentScript,
                                Map<String, String> context, List<ConnectionSelection> connections,
                                Map<String, String> references) {
        this(history, userInput, currentScript, context, connections, references, null, null);
    }

    public List<ConnectionSelection> connections() {
        return connections == null ? List.of() : connections;
    }

    public Map<String, String> references() {
        return references == null ? Map.of() : references;
    }

    public List<ChatTurn> history() {
        return history == null ? List.of() : history;
    }

    public Map<String, String> context() {
        return context == null ? Map.of() : context;
    }

    public String currentScript() {
        return currentScript == null ? "" : currentScript;
    }
}
