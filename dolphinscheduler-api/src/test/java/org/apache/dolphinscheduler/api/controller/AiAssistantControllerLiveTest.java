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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import org.apache.dolphinscheduler.api.service.ProjectService;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Streams a real chat through the api server, runs when AI_LIVE_TEST=true with AWS credentials (e.g.
 * AWS_BEARER_TOKEN_BEDROCK). SEATUNNEL_HOME_FOR_TEST enables the engine validation.
 */
@EnabledIfEnvironmentVariable(named = "AI_LIVE_TEST", matches = "true")
@TestPropertySource(properties = {"ai.enabled=true", "ai.seatunnel.home=${SEATUNNEL_HOME_FOR_TEST:}"})
public class AiAssistantControllerLiveTest extends AbstractControllerTest {

    @MockBean
    private ProjectService projectService;

    @Test
    public void testSeatunnelChatStream() throws Exception {
        MvcResult result = mockMvc.perform(post("/ai/seatunnel/chat").header(SESSION_ID, sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                .content("{\"projectCode\":1,\"userInput\":\"generate a BATCH job: FakeSource with fields id int and "
                        + "name string, 10 rows, print to Console\",\"context\":{\"startupScript\":\"seatunnel.sh\"}}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(3);
        String content = "";
        while (System.currentTimeMillis() < deadline) {
            content = result.getResponse().getContentAsString();
            if (content.contains("event:done") || content.contains("event:error")) {
                break;
            }
            Thread.sleep(500);
        }
        System.out.println(content);
        Assertions.assertTrue(content.contains("event:status"), content);
        Assertions.assertTrue(content.contains("event:delta"), content);
        Assertions.assertTrue(content.contains("event:proposal"), content);
        Assertions.assertTrue(content.contains("event:done"), content);
        Assertions.assertTrue(content.contains("FakeSource"), content);
    }
}
