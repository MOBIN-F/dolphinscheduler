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
 * A chat request of the SeaTunnel config assistant. The server is stateless, the browser sends the history and the
 * current script of the editor in every request.
 *
 * @param history       previous messages, without the generated configs
 * @param userInput     the new message of the user
 * @param currentScript the script in the editor, may be empty
 * @param context       task context, e.g. startupScript, deployMode, runMode
 */
public record SeatunnelChatRequest(List<ChatTurn> history, String userInput, String currentScript,
                                   Map<String, String> context) {

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
