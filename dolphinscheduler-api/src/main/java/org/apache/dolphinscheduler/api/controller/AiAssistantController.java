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

package org.apache.dolphinscheduler.api.controller;

import org.apache.dolphinscheduler.api.dto.ai.AiConversationRequest;
import org.apache.dolphinscheduler.api.dto.ai.AiConversationVO;
import org.apache.dolphinscheduler.api.dto.ai.SeatunnelAiChatRequest;
import org.apache.dolphinscheduler.api.service.AiAssistantService;
import org.apache.dolphinscheduler.api.utils.Result;
import org.apache.dolphinscheduler.common.constants.Constants;
import org.apache.dolphinscheduler.dao.entity.User;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * AI assistant.
 */
@Tag(name = "AI_ASSISTANT_TAG")
@RestController
@RequestMapping("/ai")
public class AiAssistantController extends BaseController {

    private final AiAssistantService aiAssistantService;

    public AiAssistantController(AiAssistantService aiAssistantService) {
        this.aiAssistantService = aiAssistantService;
    }

    @Operation(summary = "aiAssistantStatus", description = "AI_ASSISTANT_STATUS_NOTES")
    @GetMapping(value = "/status")
    @ResponseStatus(HttpStatus.OK)
    public Result<Map<String, Object>> status(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser) {
        return Result.success(aiAssistantService.status());
    }

    /**
     * Chat with the SeaTunnel config assistant, the reply is streamed as server-sent events.
     */
    @Operation(summary = "seatunnelAiChat", description = "SEATUNNEL_AI_CHAT_NOTES")
    @PostMapping(value = "/seatunnel/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter seatunnelChat(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                    @RequestBody SeatunnelAiChatRequest request) {
        return aiAssistantService.seatunnelChat(loginUser, request);
    }

    @Operation(summary = "queryAiConversation", description = "QUERY_AI_CONVERSATION_NOTES")
    @GetMapping(value = "/conversations")
    @ResponseStatus(HttpStatus.OK)
    public Result<AiConversationVO> queryConversation(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                                      @RequestParam("projectCode") long projectCode,
                                                      @RequestParam("taskCode") long taskCode,
                                                      @RequestParam(value = "assistant", defaultValue = "seatunnel") String assistant) {
        return Result.success(aiAssistantService.queryConversation(loginUser, projectCode, taskCode, assistant));
    }

    @Operation(summary = "saveAiConversation", description = "SAVE_AI_CONVERSATION_NOTES")
    @PutMapping(value = "/conversations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> saveConversation(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                         @RequestBody AiConversationRequest request) {
        aiAssistantService.saveConversation(loginUser, request);
        return Result.success();
    }

    @Operation(summary = "deleteAiConversation", description = "DELETE_AI_CONVERSATION_NOTES")
    @DeleteMapping(value = "/conversations")
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> deleteConversation(@Parameter(hidden = true) @RequestAttribute(value = Constants.SESSION_USER) User loginUser,
                                           @RequestParam("projectCode") long projectCode,
                                           @RequestParam("taskCode") long taskCode,
                                           @RequestParam(value = "assistant", defaultValue = "seatunnel") String assistant) {
        aiAssistantService.deleteConversation(loginUser, projectCode, taskCode, assistant);
        return Result.success();
    }
}
