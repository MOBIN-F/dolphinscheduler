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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.apache.dolphinscheduler.api.enums.Status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The AI assistant is disabled by default, the api server starts without any AI bean.
 */
public class AiAssistantControllerTest extends AbstractControllerTest {

    @Test
    public void testStatusWhenDisabled() throws Exception {
        mockMvc.perform(get("/ai/status").header(SESSION_ID, sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(Status.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.seatunnelEngineValidation").value(false));
    }

    @Test
    public void testChatWhenDisabled() throws Exception {
        mockMvc.perform(post("/ai/seatunnel/chat").header(SESSION_ID, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .content("{\"projectCode\":1,\"userInput\":\"hello\"}"))
                .andExpect(jsonPath("$.code").value(Status.AI_ASSISTANT_NOT_ENABLED.getCode()));
    }

    @Test
    public void testRequiresLogin() throws Exception {
        mockMvc.perform(get("/ai/status")).andExpect(status().isUnauthorized());
    }
}
