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

package org.apache.dolphinscheduler.api.service.impl;

import static org.apache.dolphinscheduler.api.constants.ApiFuncIdentificationConstant.WORKFLOW_UPDATE;

import org.apache.dolphinscheduler.ai.config.AiProperties;
import org.apache.dolphinscheduler.ai.seatunnel.agent.AgentEventListener;
import org.apache.dolphinscheduler.ai.seatunnel.agent.HttpResponseSample;
import org.apache.dolphinscheduler.ai.seatunnel.agent.SeatunnelChatRequest;
import org.apache.dolphinscheduler.ai.seatunnel.agent.SeatunnelConfigAgent;
import org.apache.dolphinscheduler.ai.seatunnel.agent.SeatunnelProposal;
import org.apache.dolphinscheduler.ai.seatunnel.validation.SeatunnelEngineChecker;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationIssue;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationResult;
import org.apache.dolphinscheduler.ai.security.CredentialRedactor;
import org.apache.dolphinscheduler.api.dto.ai.AiConversationMessage;
import org.apache.dolphinscheduler.api.dto.ai.AiConversationRequest;
import org.apache.dolphinscheduler.api.dto.ai.AiConversationVO;
import org.apache.dolphinscheduler.api.dto.ai.SeatunnelAiChatRequest;
import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.exceptions.ServiceException;
import org.apache.dolphinscheduler.api.service.AiAssistantService;
import org.apache.dolphinscheduler.api.service.ProjectService;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.AiConversation;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.repository.AiConversationDao;
import org.apache.dolphinscheduler.dao.repository.UserDao;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

@Service
@Slf4j
public class AiAssistantServiceImpl implements AiAssistantService {

    private static final long EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long RATE_LIMIT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private static final Set<String> ASSISTANTS = Set.of("seatunnel");
    private static final Set<String> ROLES = Set.of("user", "assistant");
    private static final int MAX_SAVED_MESSAGES = 200;
    private static final int MAX_MESSAGE_LENGTH = 20000;
    private static final int MAX_CONVERSATION_BYTES = 1024 * 1024;

    private final AiProperties aiProperties;
    private final ObjectProvider<SeatunnelConfigAgent> seatunnelAgent;
    private final ObjectProvider<SeatunnelEngineChecker> engineChecker;
    private final ProjectService projectService;
    private final AiConversationDao aiConversationDao;
    private final UserDao userDao;

    /**
     * Each request runs several LLM calls and a validation process, so the concurrency is bounded.
     */
    private final ExecutorService executor = new ThreadPoolExecutor(2, 16, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            new ThreadFactoryBuilder().setNameFormat("ai-assistant-%d").setDaemon(true).build());

    // user id -> request times in the current window
    private final Cache<Integer, Deque<Long>> requestTimes = CacheBuilder.newBuilder()
            .expireAfterAccess(RATE_LIMIT_WINDOW_MILLIS, TimeUnit.MILLISECONDS)
            .maximumSize(10000)
            .build();

    public AiAssistantServiceImpl(AiProperties aiProperties, ObjectProvider<SeatunnelConfigAgent> seatunnelAgent,
                                  ObjectProvider<SeatunnelEngineChecker> engineChecker,
                                  ProjectService projectService, AiConversationDao aiConversationDao,
                                  UserDao userDao) {
        this.aiProperties = aiProperties;
        this.seatunnelAgent = seatunnelAgent;
        this.engineChecker = engineChecker;
        this.projectService = projectService;
        this.aiConversationDao = aiConversationDao;
        this.userDao = userDao;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", aiProperties.isEnabled());
        SeatunnelEngineChecker checker = engineChecker.getIfAvailable();
        status.put("seatunnelEngineValidation", checker != null && checker.isEnabled());
        status.put("seatunnelVersion", aiProperties.getSeatunnel().getVersion());
        return status;
    }

    @Override
    public SseEmitter seatunnelChat(User loginUser, SeatunnelAiChatRequest request) {
        SeatunnelConfigAgent agent = seatunnelAgent.getIfAvailable();
        if (!aiProperties.isEnabled() || agent == null) {
            throw new ServiceException(Status.AI_ASSISTANT_NOT_ENABLED);
        }
        if (StringUtils.isBlank(request.getUserInput())) {
            throw new ServiceException(Status.AI_ASSISTANT_INPUT_EMPTY);
        }
        projectService.checkProjectAndAuthThrowException(loginUser, request.getProjectCode(), WORKFLOW_UPDATE);
        acquire(loginUser);

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));

        SeatunnelChatRequest chatRequest = new SeatunnelChatRequest(request.getHistory(), request.getUserInput(),
                request.getCurrentScript(), request.getContext());
        try {
            executor.execute(() -> {
                long start = System.currentTimeMillis();
                SseEventListener listener = new SseEventListener(emitter, cancelled);
                agent.chat(chatRequest, listener, cancelled::get);
                // Audit log, the conversation content is not logged
                log.info("AI assistant request finished, user: {}, project: {}, history size: {}, proposal: {}, "
                        + "valid: {}, fix attempts: {}, cost: {}ms", loginUser.getUserName(),
                        request.getProjectCode(), chatRequest.history().size(), listener.proposal != null,
                        listener.proposal != null && listener.proposal.valid(),
                        listener.proposal == null ? 0 : listener.proposal.fixAttempts(),
                        System.currentTimeMillis() - start);
                try {
                    emitter.complete();
                } catch (IllegalStateException e) {
                    // already completed because the browser closed the connection
                }
            });
        } catch (RejectedExecutionException e) {
            throw new ServiceException(Status.AI_ASSISTANT_BUSY);
        }
        return emitter;
    }

    @Override
    public AiConversationVO queryConversation(User loginUser, long projectCode, long taskCode, String assistant) {
        checkConversationAccess(loginUser, projectCode, assistant);
        Optional<AiConversation> conversation = queryConversationOfProject(loginUser, projectCode, taskCode,
                assistant);
        if (!conversation.isPresent()) {
            return new AiConversationVO(new ArrayList<>(), null, null);
        }
        List<AiConversationMessage> messages = JSONUtils.parseObject(conversation.get().getMessages(),
                new TypeReference<List<AiConversationMessage>>() {
                });
        Integer updateUser = conversation.get().getUpdateUser();
        User user = updateUser == null ? null : userDao.queryById(updateUser);
        return new AiConversationVO(messages == null ? new ArrayList<>() : messages,
                conversation.get().getUpdateTime(), user == null ? null : user.getUserName());
    }

    @Override
    public void saveConversation(User loginUser, AiConversationRequest request) {
        checkConversationAccess(loginUser, request.getProjectCode(), request.getAssistant());
        queryConversationOfProject(loginUser, request.getProjectCode(), request.getTaskCode(),
                request.getAssistant());
        List<AiConversationMessage> messages = sanitize(loginUser, request.getMessages());
        String json = JSONUtils.toJsonString(messages);
        // Drop the oldest messages until the conversation fits
        while (messages.size() > 1 && json.getBytes(StandardCharsets.UTF_8).length > MAX_CONVERSATION_BYTES) {
            messages = messages.subList(1, messages.size());
            json = JSONUtils.toJsonString(messages);
        }
        aiConversationDao.upsert(AiConversation.builder()
                .taskCode(request.getTaskCode())
                .projectCode(request.getProjectCode())
                .assistant(request.getAssistant())
                .messages(json)
                .updateUser(loginUser.getId())
                .build());
    }

    @Override
    public void deleteConversation(User loginUser, long projectCode, long taskCode, String assistant) {
        checkConversationAccess(loginUser, projectCode, assistant);
        queryConversationOfProject(loginUser, projectCode, taskCode, assistant);
        aiConversationDao.deleteByTaskCode(taskCode, assistant);
        log.info("AI assistant conversation deleted, user: {}, project: {}, task: {}, assistant: {}",
                loginUser.getUserName(), projectCode, taskCode, assistant);
    }

    private void checkConversationAccess(User loginUser, long projectCode, String assistant) {
        if (!aiProperties.isEnabled()) {
            throw new ServiceException(Status.AI_ASSISTANT_NOT_ENABLED);
        }
        if (!ASSISTANTS.contains(assistant)) {
            throw new ServiceException(Status.REQUEST_PARAMS_NOT_VALID_ERROR, "assistant");
        }
        projectService.checkProjectAndAuthThrowException(loginUser, projectCode, WORKFLOW_UPDATE);
    }

    /**
     * The conversation must belong to the project, so that the conversation of another project can not be read or
     * overwritten with a task code.
     */
    private Optional<AiConversation> queryConversationOfProject(User loginUser, long projectCode, long taskCode,
                                                                String assistant) {
        Optional<AiConversation> conversation = aiConversationDao.queryByTaskCode(taskCode, assistant);
        if (conversation.isPresent() && conversation.get().getProjectCode() != projectCode) {
            throw new ServiceException(Status.USER_NO_OPERATION_PROJECT_PERM, loginUser.getUserName(),
                    conversation.get().getProjectCode());
        }
        return conversation;
    }

    /**
     * Keep the allowed fields only, redact the credentials, limit the number and the length of the messages.
     */
    private List<AiConversationMessage> sanitize(User loginUser, List<AiConversationMessage> messages) {
        List<AiConversationMessage> result = new ArrayList<>();
        if (messages == null) {
            return result;
        }
        int from = Math.max(0, messages.size() - MAX_SAVED_MESSAGES);
        for (AiConversationMessage message : messages.subList(from, messages.size())) {
            if (message == null || !ROLES.contains(message.getRole())) {
                continue;
            }
            AiConversationMessage saved = new AiConversationMessage();
            saved.setRole(message.getRole());
            String content = message.getContent() == null ? "" : message.getContent();
            content = CredentialRedactor.mask(new CredentialRedactor().redactText(content));
            saved.setContent(content.length() > MAX_MESSAGE_LENGTH ? content.substring(0, MAX_MESSAGE_LENGTH)
                    : content);
            saved.setTime(message.getTime());
            if ("user".equals(message.getRole())) {
                saved.setUserName(StringUtils.isBlank(message.getUserName()) ? loginUser.getUserName()
                        : StringUtils.abbreviate(message.getUserName(), 128));
            } else {
                saved.setProposalState(StringUtils.abbreviate(message.getProposalState(), 16));
                saved.setValid(message.getValid());
                saved.setFixAttempts(message.getFixAttempts());
                saved.setError(StringUtils.abbreviate(message.getError(), 1000));
            }
            result.add(saved);
        }
        return result;
    }

    /**
     * Sliding window rate limit per user.
     */
    private void acquire(User loginUser) {
        int limit = aiProperties.getRateLimitPerUserPerMinute();
        if (limit <= 0) {
            return;
        }
        Deque<Long> times = requestTimes.asMap().computeIfAbsent(loginUser.getId(), key -> new LinkedList<>());
        synchronized (times) {
            long now = System.currentTimeMillis();
            while (!times.isEmpty() && now - times.peekFirst() > RATE_LIMIT_WINDOW_MILLIS) {
                times.pollFirst();
            }
            if (times.size() >= limit) {
                throw new ServiceException(Status.AI_ASSISTANT_RATE_LIMITED);
            }
            times.addLast(now);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    /**
     * Sends the agent events as server-sent events with json data.
     */
    public static class SseEventListener implements AgentEventListener {

        private final SseEmitter emitter;
        private final AtomicBoolean cancelled;
        private volatile SeatunnelProposal proposal;

        public SseEventListener(SseEmitter emitter, AtomicBoolean cancelled) {
            this.emitter = emitter;
            this.cancelled = cancelled;
        }

        @Override
        public void onDelta(String text) {
            send("delta", Map.of("text", text));
        }

        @Override
        public void onStatus(String status, String detail) {
            Map<String, Object> data = new HashMap<>();
            data.put("status", status);
            data.put("detail", detail);
            send("status", data);
        }

        @Override
        public void onProposal(SeatunnelProposal proposal) {
            this.proposal = proposal;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("script", proposal.script());
            data.put("explanation", proposal.explanation());
            data.put("valid", proposal.valid());
            data.put("fixAttempts", proposal.fixAttempts());
            List<Map<String, Object>> validations = new ArrayList<>();
            for (ValidationResult result : Arrays.asList(proposal.localValidation(), proposal.engineValidation())) {
                Map<String, Object> validation = new LinkedHashMap<>();
                validation.put("stage", result.stage());
                validation.put("skipped", result.skipped());
                validation.put("skipReason", result.skipReason());
                validation.put("valid", result.valid());
                List<Map<String, String>> issues = new ArrayList<>();
                for (ValidationIssue issue : result.issues()) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("level", issue.level().name());
                    item.put("location", issue.location());
                    item.put("message", issue.message());
                    issues.add(item);
                }
                validation.put("issues", issues);
                validations.add(validation);
            }
            data.put("validations", validations);
            send("proposal", data);
        }

        @Override
        public void onHttpResponse(HttpResponseSample sample) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("target", sample.target());
            data.put("status", sample.status());
            data.put("contentType", sample.contentType());
            data.put("size", sample.size());
            data.put("truncated", sample.truncated());
            data.put("body", sample.body());
            send("http_response", data);
        }

        @Override
        public void onDone(String assistantMessage) {
            send("done", Map.of("message", assistantMessage == null ? "" : assistantMessage));
        }

        @Override
        public void onError(String message) {
            send("error", Map.of("message", message == null ? "" : message));
        }

        private void send(String name, Object data) {
            if (cancelled.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().name(name).data(JSONUtils.toJsonString(data)));
            } catch (IOException | IllegalStateException e) {
                // The browser closed the connection, stop the request
                cancelled.set(true);
            }
        }
    }
}
