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
import org.apache.dolphinscheduler.ai.seatunnel.agent.SeatunnelChatRequest;
import org.apache.dolphinscheduler.ai.seatunnel.agent.SeatunnelConfigAgent;
import org.apache.dolphinscheduler.ai.seatunnel.agent.SeatunnelProposal;
import org.apache.dolphinscheduler.ai.seatunnel.validation.SeatunnelEngineChecker;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationIssue;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationResult;
import org.apache.dolphinscheduler.api.dto.ai.SeatunnelAiChatRequest;
import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.exceptions.ServiceException;
import org.apache.dolphinscheduler.api.service.AiAssistantService;
import org.apache.dolphinscheduler.api.service.ProjectService;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.User;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

@Service
@Slf4j
public class AiAssistantServiceImpl implements AiAssistantService {

    private static final long EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long RATE_LIMIT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final AiProperties aiProperties;
    private final ObjectProvider<SeatunnelConfigAgent> seatunnelAgent;
    private final ObjectProvider<SeatunnelEngineChecker> engineChecker;
    private final ProjectService projectService;

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
                                  ProjectService projectService) {
        this.aiProperties = aiProperties;
        this.seatunnelAgent = seatunnelAgent;
        this.engineChecker = engineChecker;
        this.projectService = projectService;
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
