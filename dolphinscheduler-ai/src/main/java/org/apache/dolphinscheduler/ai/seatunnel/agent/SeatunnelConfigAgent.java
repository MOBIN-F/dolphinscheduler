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

import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository;
import org.apache.dolphinscheduler.ai.seatunnel.validation.HoconValidator;
import org.apache.dolphinscheduler.ai.seatunnel.validation.SeatunnelEngineChecker;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationResult;
import org.apache.dolphinscheduler.ai.security.CredentialRedactor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/**
 * The SeaTunnel config assistant: generates or modifies a SeaTunnel config through a conversation, grounded by the
 * connector metadata, and makes sure the config passes the local and the engine validation before proposing it.
 *
 * <p>Flow of a request: redact credentials -> LLM with tools (list / describe connectors, validate) -> extract the
 * config -> local validation -> engine validation -> fix with the validation errors (max N rounds) -> restore the
 * credentials -> proposal. The design follows SeaTunnel CLI: ground the LLM with the engine metadata, let
 * deterministic validation decide, fix with the real errors.
 */
public class SeatunnelConfigAgent {

    private static final Logger log = LoggerFactory.getLogger(SeatunnelConfigAgent.class);

    private static final int MAX_TOOL_ROUNDS = 8;
    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final Pattern CODE_BLOCK = Pattern.compile("```(\\w*)[ \\t]*\\r?\\n(.*?)```", Pattern.DOTALL);

    private final StreamingChatModel mainModel;
    private final ChatModel fastModel;
    private final int maxTokens;
    private final Duration timeout;
    private final ConnectorMetadataRepository metadataRepository;
    private final HoconValidator validator;
    private final SeatunnelEngineChecker engineChecker;
    private final SeatunnelTools tools;
    private final int maxFixAttempts;

    private final String systemTemplate;
    private final String fixTemplate;
    private final String goldenExamples;

    public SeatunnelConfigAgent(StreamingChatModel mainModel, ChatModel fastModel, int maxTokens, Duration timeout,
                                ConnectorMetadataRepository metadataRepository, HoconValidator validator,
                                SeatunnelEngineChecker engineChecker, int maxFixAttempts) {
        this(mainModel, fastModel, maxTokens, timeout, metadataRepository, validator, engineChecker, maxFixAttempts,
                null);
    }

    /**
     * @param httpSampleFetcher calls the HTTP API given by the user to get the fields, disabled when null
     */
    public SeatunnelConfigAgent(StreamingChatModel mainModel, ChatModel fastModel, int maxTokens, Duration timeout,
                                ConnectorMetadataRepository metadataRepository, HoconValidator validator,
                                SeatunnelEngineChecker engineChecker, int maxFixAttempts,
                                HttpSampleFetcher httpSampleFetcher) {
        this.mainModel = mainModel;
        this.fastModel = fastModel;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.metadataRepository = metadataRepository;
        this.validator = validator;
        this.engineChecker = engineChecker;
        this.maxFixAttempts = maxFixAttempts;
        this.tools = new SeatunnelTools(metadataRepository, validator, httpSampleFetcher);
        this.systemTemplate = loadPrompt("system.md");
        this.fixTemplate = loadPrompt("fix.md");
        this.goldenExamples = loadPrompt("golden-examples.md");
    }

    /**
     * Handle a chat request, the events are sent to the listener. Never throws, errors are sent as error events.
     */
    public void chat(SeatunnelChatRequest request, AgentEventListener listener, BooleanSupplier cancelled) {
        try {
            doChat(request, listener, cancelled);
        } catch (CancelledException e) {
            log.info("SeaTunnel assistant request cancelled");
        } catch (Exception e) {
            log.error("SeaTunnel assistant request failed", e);
            listener.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void doChat(SeatunnelChatRequest request, AgentEventListener listener,
                        BooleanSupplier cancelled) throws Exception {
        CredentialRedactor redactor = new CredentialRedactor();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(buildSystemPrompt(request, redactor)));
        messages.addAll(buildHistory(request, redactor));
        messages.add(UserMessage.from(buildUserMessage(request, redactor)));
        ToolContext toolContext = new ToolContext(redactor, allowedHosts(request));

        listener.onStatus(AgentEventListener.STATUS_THINKING, null);
        String reply = runWithTools(messages, toolContext, listener, true, cancelled);
        String config = extractConfig(reply);
        String explanation = removeConfig(reply);
        if (config == null) {
            listener.onDone(CredentialRedactor.mask(reply));
            return;
        }

        int fixAttempts = 0;
        ValidationResult local;
        ValidationResult engine;
        while (true) {
            listener.onStatus(AgentEventListener.STATUS_VALIDATING_LOCAL, null);
            local = validator.validate(config);
            engine = ValidationResult.skipped(ValidationResult.STAGE_ENGINE, "local validation failed");
            if (local.valid()) {
                if (engineChecker.isEnabled()) {
                    listener.onStatus(AgentEventListener.STATUS_VALIDATING_ENGINE, null);
                }
                // The config still contains the placeholders of the credentials, no secret is written to disk
                engine = engineChecker.check(config);
            }
            if ((local.valid() && engine.valid()) || fixAttempts >= maxFixAttempts) {
                break;
            }
            fixAttempts++;
            listener.onStatus(AgentEventListener.STATUS_FIXING, String.valueOf(fixAttempts));
            String validation = (local.valid() ? engine : local).describe();
            messages.add(UserMessage.from(fixTemplate.replace("{{VALIDATION}}", validation)));
            String fixReply = runWithTools(messages, toolContext, listener, false, cancelled);
            String fixedConfig = extractConfig(fixReply);
            if (fixedConfig == null) {
                break;
            }
            config = fixedConfig;
        }
        // Only the config gets the real credentials back, the text shown in the chat keeps them masked
        listener.onProposal(new SeatunnelProposal(redactor.restore(config), CredentialRedactor.mask(explanation),
                local, engine, fixAttempts));
        listener.onDone(CredentialRedactor.mask(explanation));
    }

    /**
     * Call the LLM and execute the tool calls until it replies with text.
     *
     * @param streamText whether the text of the reply is streamed to the user
     * @return the final text reply
     */
    private String runWithTools(List<ChatMessage> messages, ToolContext toolContext, AgentEventListener listener,
                                boolean streamText, BooleanSupplier cancelled) throws Exception {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (cancelled.getAsBoolean()) {
                throw new CancelledException();
            }
            ChatResponse response = stream(messages, streamText ? listener : null);
            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);
            if (!aiMessage.hasToolExecutionRequests()) {
                return aiMessage.text() == null ? "" : aiMessage.text();
            }
            for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                if (SeatunnelTools.FETCH_HTTP_SAMPLE.equals(toolRequest.name())) {
                    // The query and the headers may contain credentials, only the target is shown
                    listener.onStatus(AgentEventListener.STATUS_HTTP_SAMPLE,
                            HttpSampleFetcher.describeTarget(toolRequest.arguments()));
                } else {
                    listener.onStatus(AgentEventListener.STATUS_TOOL,
                            toolRequest.name() + " " + toolRequest.arguments());
                }
                messages.add(ToolExecutionResultMessage.from(toolRequest, tools.execute(toolRequest, toolContext)));
            }
            if (streamText) {
                listener.onDelta("\n");
            }
        }
        throw new IllegalStateException("The assistant called tools too many times without an answer");
    }

    private ChatResponse stream(List<ChatMessage> messages,
                                AgentEventListener listener) throws InterruptedException, ExecutionException, TimeoutException {
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(tools.specifications())
                .maxOutputTokens(maxTokens)
                .build();
        mainModel.chat(chatRequest, new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partialResponse) {
                if (listener != null) {
                    listener.onDelta(partialResponse);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                future.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private String buildSystemPrompt(SeatunnelChatRequest request, CredentialRedactor redactor) {
        String prompt = systemTemplate
                .replace("{{SEATUNNEL_VERSION}}", metadataRepository.version())
                .replace("{{CONNECTOR_CATALOG}}", metadataRepository.catalog())
                .replace("{{TASK_CONTEXT}}", describeContext(request.context()))
                .replace("{{GOLDEN_EXAMPLES}}", goldenExamples);
        List<ChatTurn> history = request.history();
        if (history.size() > MAX_HISTORY_MESSAGES) {
            List<ChatTurn> earlier = history.subList(0, history.size() - MAX_HISTORY_MESSAGES);
            prompt += "\n\n## Summary of the earlier conversation\n\n" + summarize(earlier, redactor);
        }
        return prompt;
    }

    /**
     * Keep the recent messages, merge consecutive messages of the same role (the Bedrock Converse API requires
     * alternating roles) and start with a user message.
     */
    private List<ChatMessage> buildHistory(SeatunnelChatRequest request, CredentialRedactor redactor) {
        List<ChatTurn> history = request.history();
        List<ChatTurn> recent = history.subList(Math.max(0, history.size() - MAX_HISTORY_MESSAGES), history.size());
        List<ChatTurn> merged = new ArrayList<>();
        for (ChatTurn turn : recent) {
            if (turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String role = ChatTurn.ASSISTANT.equals(turn.role()) ? ChatTurn.ASSISTANT : ChatTurn.USER;
            if (merged.isEmpty() && ChatTurn.ASSISTANT.equals(role)) {
                continue;
            }
            if (!merged.isEmpty() && merged.get(merged.size() - 1).role().equals(role)) {
                ChatTurn last = merged.remove(merged.size() - 1);
                merged.add(new ChatTurn(role, last.content() + "\n\n" + turn.content()));
            } else {
                merged.add(new ChatTurn(role, turn.content()));
            }
        }
        // The current user message follows, so the history must end with an assistant message
        if (!merged.isEmpty() && ChatTurn.USER.equals(merged.get(merged.size() - 1).role())) {
            merged.add(new ChatTurn(ChatTurn.ASSISTANT, "(no reply)"));
        }
        return merged.stream()
                .map(turn -> ChatTurn.USER.equals(turn.role())
                        ? (ChatMessage) UserMessage.from(redactor.redactText(turn.content()))
                        : AiMessage.from(redactor.redactText(turn.content())))
                .collect(Collectors.toList());
    }

    /**
     * The HTTP tool only calls the hosts the user wrote, not the ones the LLM made up or read in a response.
     */
    private static Set<String> allowedHosts(SeatunnelChatRequest request) {
        List<String> texts = new ArrayList<>();
        texts.add(request.userInput());
        texts.add(request.currentScript());
        request.history().stream()
                .filter(turn -> ChatTurn.USER.equals(turn.role()))
                .forEach(turn -> texts.add(turn.content()));
        return HttpSampleFetcher.allowedHosts(texts.toArray(new String[0]));
    }

    private String buildUserMessage(SeatunnelChatRequest request, CredentialRedactor redactor) {
        String script = request.currentScript().trim();
        String editor = script.isEmpty()
                ? "The editor is empty."
                : "Current script in the editor:\n```hocon\n" + redactor.redactConfig(script) + "\n```";
        return editor + "\n\nUser request:\n" + redactor.redactText(request.userInput());
    }

    private String summarize(List<ChatTurn> turns, CredentialRedactor redactor) {
        String conversation = turns.stream()
                .map(turn -> turn.role() + ": " + redactor.redactText(turn.content()))
                .collect(Collectors.joining("\n"));
        try {
            return fastModel.chat("Summarize this conversation about a SeaTunnel job config in a few bullet points. "
                    + "Keep hosts, databases, tables, topics, decisions and placeholders such as ${xxx} exactly.\n\n"
                    + conversation);
        } catch (Exception e) {
            log.warn("Failed to summarize the earlier conversation", e);
            return "(earlier messages omitted)";
        }
    }

    private static String describeContext(Map<String, String> context) {
        if (context.isEmpty()) {
            return "No task context.";
        }
        StringBuilder text = new StringBuilder();
        String startupScript = context.getOrDefault("startupScript", "");
        if (startupScript.equals("seatunnel.sh")) {
            text.append("- Engine: SeaTunnel Zeta (seatunnel.sh)\n");
        } else if (startupScript.contains("flink")) {
            text.append("- Engine: Flink (").append(startupScript).append("), env may contain Flink options\n");
        } else if (startupScript.contains("spark")) {
            text.append("- Engine: Spark (").append(startupScript).append("), env may contain Spark options\n");
        }
        context.forEach((key, value) -> {
            if (value != null && !value.isEmpty()) {
                text.append("- ").append(key).append(": ").append(value).append("\n");
            }
        });
        return text.toString().trim();
    }

    /**
     * @return the content of the first hocon (or untyped) code block, or null if the reply has no config
     */
    static String extractConfig(String reply) {
        Matcher matcher = CODE_BLOCK.matcher(reply == null ? "" : reply);
        while (matcher.find()) {
            String language = matcher.group(1).toLowerCase();
            String content = matcher.group(2).trim();
            if (language.equals("hocon") || language.equals("conf")
                    || (language.isEmpty() && content.contains("source") && content.contains("sink"))) {
                return content;
            }
        }
        return null;
    }

    static String removeConfig(String reply) {
        return CODE_BLOCK.matcher(reply == null ? "" : reply).replaceAll("").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String loadPrompt(String name) {
        String resource = "/seatunnel/prompts/" + name;
        try (InputStream inputStream = SeatunnelConfigAgent.class.getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalStateException("Prompt " + resource + " not found");
            }
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            // Drop the license header
            return content.startsWith("<!--") ? content.substring(content.indexOf("-->") + 3).trim() : content.trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static class CancelledException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
