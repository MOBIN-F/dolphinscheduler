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

import static org.apache.dolphinscheduler.api.AssertionsHelper.assertThrowsServiceException;
import static org.apache.dolphinscheduler.api.constants.ApiFuncIdentificationConstant.WORKFLOW_UPDATE;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.dolphinscheduler.ai.config.AiProperties;
import org.apache.dolphinscheduler.ai.seatunnel.agent.SeatunnelConfigAgent;
import org.apache.dolphinscheduler.ai.seatunnel.agent.SeatunnelProposal;
import org.apache.dolphinscheduler.ai.seatunnel.validation.SeatunnelEngineChecker;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationIssue;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationResult;
import org.apache.dolphinscheduler.api.dto.ai.SeatunnelAiChatRequest;
import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.service.impl.AiAssistantServiceImpl;
import org.apache.dolphinscheduler.dao.entity.User;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class AiAssistantServiceTest {

    private final AiProperties properties = new AiProperties();
    private final SeatunnelConfigAgent agent = mock(SeatunnelConfigAgent.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private AiAssistantServiceImpl service;
    private final User user = new User();

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        ObjectProvider<SeatunnelConfigAgent> agentProvider = mock(ObjectProvider.class);
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        ObjectProvider<SeatunnelEngineChecker> checkerProvider = mock(ObjectProvider.class);
        service = new AiAssistantServiceImpl(properties, agentProvider, checkerProvider, projectService);
        user.setId(1);
        user.setUserName("admin");
    }

    private SeatunnelAiChatRequest request(String input) {
        SeatunnelAiChatRequest request = new SeatunnelAiChatRequest();
        request.setProjectCode(100L);
        request.setUserInput(input);
        return request;
    }

    @Test
    public void testDisabled() {
        assertThrowsServiceException(Status.AI_ASSISTANT_NOT_ENABLED, () -> service.seatunnelChat(user, request("hi")));
        Assertions.assertEquals(false, service.status().get("enabled"));
    }

    @Test
    public void testEmptyInput() {
        properties.setEnabled(true);
        assertThrowsServiceException(Status.AI_ASSISTANT_INPUT_EMPTY, () -> service.seatunnelChat(user, request(" ")));
    }

    @Test
    public void testPermissionAndRateLimit() {
        properties.setEnabled(true);
        properties.setRateLimitPerUserPerMinute(2);
        Assertions.assertNotNull(service.seatunnelChat(user, request("a")));
        Assertions.assertNotNull(service.seatunnelChat(user, request("b")));
        assertThrowsServiceException(Status.AI_ASSISTANT_RATE_LIMITED, () -> service.seatunnelChat(user, request("c")));
        verify(projectService, times(3)).checkProjectAndAuthThrowException(eq(user), eq(100L),
                eq(WORKFLOW_UPDATE));
        // Another user has its own quota
        User other = new User();
        other.setId(2);
        Assertions.assertNotNull(service.seatunnelChat(other, request("a")));
    }

    @Test
    public void testSseEvents() throws Exception {
        List<SseEmitter.SseEventBuilder> events = new ArrayList<>();
        SseEmitter emitter = new SseEmitter() {

            @Override
            public void send(SseEventBuilder builder) {
                events.add(builder);
            }
        };
        AiAssistantServiceImpl.SseEventListener listener =
                new AiAssistantServiceImpl.SseEventListener(emitter, new AtomicBoolean(false));
        listener.onStatus("tool", "get_connector_info");
        listener.onDelta("hello");
        listener.onProposal(new SeatunnelProposal("env {}", "explain",
                ValidationResult.of(ValidationResult.STAGE_LOCAL,
                        List.of(ValidationIssue.warning("env", "job.mode is not set"))),
                ValidationResult.skipped(ValidationResult.STAGE_ENGINE, "not configured"), 0));
        listener.onDone("explain");
        Assertions.assertEquals(4, events.size());
        String proposal = text(events.get(2));
        Assertions.assertTrue(proposal.contains("event:proposal"), proposal);
        Assertions.assertTrue(proposal.contains("\"valid\":true"), proposal);
        Assertions.assertTrue(proposal.contains("job.mode is not set"), proposal);
        Assertions.assertTrue(text(events.get(1)).contains("{\"text\":\"hello\"}"), text(events.get(1)));
    }

    private static String text(SseEmitter.SseEventBuilder event) {
        return event.build().stream().map(data -> String.valueOf(data.getData())).collect(Collectors.joining());
    }
}
