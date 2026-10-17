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
import static org.apache.dolphinscheduler.api.constants.ApiFuncIdentificationConstant.WORKFLOW_DEFINITION;
import static org.apache.dolphinscheduler.api.constants.ApiFuncIdentificationConstant.WORKFLOW_UPDATE;
import static org.mockito.ArgumentMatchers.any;
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
import org.apache.dolphinscheduler.api.dto.ai.AiConversationMessage;
import org.apache.dolphinscheduler.api.dto.ai.AiConversationRequest;
import org.apache.dolphinscheduler.api.dto.ai.AiConversationStep;
import org.apache.dolphinscheduler.api.dto.ai.AiConversationVO;
import org.apache.dolphinscheduler.api.dto.ai.SeatunnelAiChatRequest;
import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.service.impl.AiAssistantServiceImpl;
import org.apache.dolphinscheduler.api.service.seatunnel.SeatunnelConnectionService;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.AiConversation;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.repository.AiConversationDao;
import org.apache.dolphinscheduler.dao.repository.UserDao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class AiAssistantServiceTest {

    private final AiProperties properties = new AiProperties();
    private final SeatunnelConfigAgent agent = mock(SeatunnelConfigAgent.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final AiConversationDao conversationDao = mock(AiConversationDao.class);
    private final UserDao userDao = mock(UserDao.class);
    private final SeatunnelConnectionService connectionService = mock(SeatunnelConnectionService.class);
    private AiAssistantServiceImpl service;
    private final User user = new User();

    @SuppressWarnings("unchecked")
    @BeforeEach
    public void setUp() {
        ObjectProvider<SeatunnelConfigAgent> agentProvider = mock(ObjectProvider.class);
        when(agentProvider.getIfAvailable()).thenReturn(agent);
        ObjectProvider<SeatunnelEngineChecker> checkerProvider = mock(ObjectProvider.class);
        when(connectionService.resolve(any(), any(), any()))
                .thenReturn(new SeatunnelConnectionService.ResolvedContext(new ArrayList<>(), new HashMap<>()));
        service = new AiAssistantServiceImpl(properties, agentProvider, checkerProvider, projectService,
                conversationDao, userDao, connectionService);
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

    private static AiConversationMessage message(String role, String content) {
        AiConversationMessage message = new AiConversationMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    @Test
    public void testConversationDisabledOrInvalidAssistant() {
        assertThrowsServiceException(Status.AI_ASSISTANT_NOT_ENABLED,
                () -> service.queryConversation(user, 100L, 1L, "seatunnel"));
        properties.setEnabled(true);
        assertThrowsServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR,
                () -> service.queryConversation(user, 100L, 1L, "unknown"));
    }

    @Test
    public void testSaveConversationRedactsAndLimits() {
        properties.setEnabled(true);
        when(conversationDao.queryByTaskCode(1L, "seatunnel")).thenReturn(Optional.empty());
        List<AiConversationMessage> messages = new ArrayList<>();
        for (int i = 0; i < 210; i++) {
            messages.add(message("assistant", "reply " + i));
        }
        messages.add(message("user", "密码是 S3cret!"));
        messages.add(message("system", "ignored"));
        AiConversationRequest request = new AiConversationRequest();
        request.setProjectCode(100L);
        request.setTaskCode(1L);
        request.setAssistant("seatunnel");
        request.setMessages(messages);

        service.saveConversation(user, request);

        verify(projectService).checkProjectAndAuthThrowException(eq(user), eq(100L), eq(WORKFLOW_UPDATE));
        ArgumentCaptor<AiConversation> captor = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversationDao).upsert(captor.capture());
        AiConversation saved = captor.getValue();
        Assertions.assertEquals(1, saved.getUpdateUser());
        Assertions.assertFalse(saved.getMessages().contains("S3cret!"), saved.getMessages());
        Assertions.assertTrue(saved.getMessages().contains("******"), saved.getMessages());
        Assertions.assertFalse(saved.getMessages().contains("ignored"), saved.getMessages());
        List<?> savedMessages = JSONUtils.parseObject(saved.getMessages(), List.class);
        Assertions.assertEquals(199, savedMessages.size());
        Assertions.assertTrue(saved.getMessages().contains("\"userName\":\"admin\""), saved.getMessages());
    }

    @Test
    public void testConversationOfAnotherProjectIsRejected() {
        properties.setEnabled(true);
        when(conversationDao.queryByTaskCode(1L, "seatunnel")).thenReturn(Optional.of(
                AiConversation.builder().taskCode(1L).projectCode(999L).assistant("seatunnel").messages("[]")
                        .build()));
        assertThrowsServiceException(Status.USER_NO_OPERATION_PROJECT_PERM,
                () -> service.queryConversation(user, 100L, 1L, "seatunnel"));
        assertThrowsServiceException(Status.USER_NO_OPERATION_PROJECT_PERM,
                () -> service.deleteConversation(user, 100L, 1L, "seatunnel"));
    }

    @Test
    public void testSavedStepsAreLimitedAndRedacted() {
        properties.setEnabled(true);
        when(conversationDao.queryByTaskCode(1L, "seatunnel")).thenReturn(Optional.empty());
        List<AiConversationStep> steps = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            AiConversationStep step = new AiConversationStep();
            step.setKind("describe");
            step.setState("error");
            step.setArgs(java.util.Collections.singletonMap("target", "kafka_dev / orders"));
            step.setResult(java.util.Collections.singletonMap("message", "login failed, password is S3cret!"));
            step.setMillis(12L);
            steps.add(step);
        }
        AiConversationMessage reply = message("assistant", "done");
        reply.setSteps(steps);
        AiConversationRequest request = new AiConversationRequest();
        request.setProjectCode(100L);
        request.setTaskCode(1L);
        request.setAssistant("seatunnel");
        request.setMessages(new ArrayList<>(java.util.Collections.singletonList(reply)));

        service.saveConversation(user, request);

        ArgumentCaptor<AiConversation> captor = ArgumentCaptor.forClass(AiConversation.class);
        verify(conversationDao).upsert(captor.capture());
        String saved = captor.getValue().getMessages();
        Assertions.assertFalse(saved.contains("S3cret!"), saved);
        Assertions.assertTrue(saved.contains("kafka_dev / orders"), saved);
        List<AiConversationMessage> messages = JSONUtils.parseObject(saved,
                new com.fasterxml.jackson.core.type.TypeReference<List<AiConversationMessage>>() {
                });
        Assertions.assertEquals(60, messages.get(0).getSteps().size());
    }

    @Test
    public void testQueryAndDeleteConversation() {
        properties.setEnabled(true);
        when(conversationDao.queryByTaskCode(1L, "seatunnel")).thenReturn(Optional.empty());
        Assertions.assertTrue(service.queryConversation(user, 100L, 1L, "seatunnel").getMessages().isEmpty());

        User other = new User();
        other.setUserName("other");
        when(userDao.queryById(2)).thenReturn(other);
        when(conversationDao.queryByTaskCode(1L, "seatunnel")).thenReturn(Optional.of(
                AiConversation.builder().taskCode(1L).projectCode(100L).assistant("seatunnel")
                        .messages("[{\"role\":\"user\",\"content\":\"hi\",\"userName\":\"other\"}]")
                        .updateUser(2).build()));
        AiConversationVO conversation = service.queryConversation(user, 100L, 1L, "seatunnel");
        Assertions.assertEquals(1, conversation.getMessages().size());
        Assertions.assertEquals("other", conversation.getUpdateUserName());

        service.deleteConversation(user, 100L, 1L, "seatunnel");
        verify(conversationDao).deleteByTaskCode(1L, "seatunnel");
        // Reading only needs the view permission (read-only task view), deleting needs the edit permission
        verify(projectService, times(2)).checkProjectAndAuthThrowException(eq(user), eq(100L),
                eq(WORKFLOW_DEFINITION));
        verify(projectService).checkProjectAndAuthThrowException(eq(user), eq(100L), eq(WORKFLOW_UPDATE));
    }

    private static String text(SseEmitter.SseEventBuilder event) {
        return event.build().stream().map(data -> String.valueOf(data.getData())).collect(Collectors.joining());
    }
}
