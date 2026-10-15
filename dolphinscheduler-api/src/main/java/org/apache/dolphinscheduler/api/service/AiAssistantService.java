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

package org.apache.dolphinscheduler.api.service;

import org.apache.dolphinscheduler.api.dto.ai.AiConversationRequest;
import org.apache.dolphinscheduler.api.dto.ai.AiConversationVO;
import org.apache.dolphinscheduler.api.dto.ai.SeatunnelAiChatRequest;
import org.apache.dolphinscheduler.dao.entity.User;

import java.util.Map;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI assistant service.
 */
public interface AiAssistantService {

    /**
     * @return whether the assistant is enabled and the capabilities, the UI shows the assistant accordingly
     */
    Map<String, Object> status();

    /**
     * Chat with the SeaTunnel config assistant, the reply is streamed as server-sent events:
     * {@code status}, {@code delta}, {@code proposal}, {@code done} and {@code error}.
     */
    SseEmitter seatunnelChat(User loginUser, SeatunnelAiChatRequest request);

    /**
     * Query the saved conversation of a task, it is shared by the editors of the task.
     */
    AiConversationVO queryConversation(User loginUser, long projectCode, long taskCode, String assistant);

    /**
     * Save the whole conversation of a task, the credentials are redacted and the size is limited.
     */
    void saveConversation(User loginUser, AiConversationRequest request);

    /**
     * Delete the conversation of a task.
     */
    void deleteConversation(User loginUser, long projectCode, long taskCode, String assistant);
}
