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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.service.ProjectService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Save, query and delete the AI conversation of a task through the api with the H2 database.
 */
@TestPropertySource(properties = {"ai.enabled=true"})
public class AiConversationControllerTest extends AbstractControllerTest {

    @MockBean
    private ProjectService projectService;

    @Test
    public void testSaveQueryDelete() throws Exception {
        String body = "{\"projectCode\":1,\"taskCode\":2001,\"assistant\":\"seatunnel\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"password is Abc123!\",\"time\":1},"
                + "{\"role\":\"assistant\",\"content\":\"done\",\"proposalState\":\"accepted\",\"valid\":true,"
                + "\"script\":\"env {}\"}]}";
        mockMvc.perform(put("/ai/conversations").header(SESSION_ID, sessionId)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(Status.SUCCESS.getCode()));

        mockMvc.perform(get("/ai/conversations").header(SESSION_ID, sessionId)
                .param("projectCode", "1").param("taskCode", "2001"))
                .andExpect(jsonPath("$.code").value(Status.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.messages.length()").value(2))
                .andExpect(jsonPath("$.data.messages[0].content").value("password is ******"))
                .andExpect(jsonPath("$.data.messages[0].userName").value("admin"))
                .andExpect(jsonPath("$.data.messages[1].proposalState").value("accepted"))
                .andExpect(jsonPath("$.data.messages[1].script").doesNotExist())
                .andExpect(jsonPath("$.data.updateUserName").value("admin"));

        mockMvc.perform(delete("/ai/conversations").header(SESSION_ID, sessionId)
                .param("projectCode", "1").param("taskCode", "2001"))
                .andExpect(jsonPath("$.code").value(Status.SUCCESS.getCode()));
        mockMvc.perform(get("/ai/conversations").header(SESSION_ID, sessionId)
                .param("projectCode", "1").param("taskCode", "2001"))
                .andExpect(jsonPath("$.data.messages.length()").value(0));
    }
}
