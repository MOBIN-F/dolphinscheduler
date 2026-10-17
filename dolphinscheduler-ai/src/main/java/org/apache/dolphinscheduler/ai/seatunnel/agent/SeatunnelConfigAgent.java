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

import org.apache.dolphinscheduler.ai.connection.ConnectionConfigChecker;
import org.apache.dolphinscheduler.ai.connection.ConnectionSelection;
import org.apache.dolphinscheduler.ai.connection.ConnectionType;
import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository;
import org.apache.dolphinscheduler.ai.seatunnel.validation.HoconValidator;
import org.apache.dolphinscheduler.ai.seatunnel.validation.SeatunnelEngineChecker;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationIssue;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationResult;
import org.apache.dolphinscheduler.ai.security.CredentialRedactor;
import org.apache.dolphinscheduler.ai.security.HostGuard;
import org.apache.dolphinscheduler.ai.security.SensitiveData;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    private static final int MAX_TOOL_ROUNDS = 12;
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
    private final HostGuard hostGuard;
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
        this.hostGuard = httpSampleFetcher == null ? new HostGuard(List.of(), false) : httpSampleFetcher.hostGuard();
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
        // The raw response is shown to the user with the personal data masked
        ToolContext toolContext = new ToolContext(redactor, allowedHosts(request),
                sample -> listener.onHttpResponse(new HttpResponseSample(sample.target(), sample.status(),
                        sample.contentType(), sample.size(), sample.truncated(),
                        SensitiveData.maskSample(sample.body()))),
                request.references())
                        .withConnections(request.connectionBackend(), allowedHostNames(request), hostGuard,
                                listener::onSample, request.taskCode());

        ProgressSteps steps = new ProgressSteps(listener, redactor);
        reportSelections(request.connections(), steps);
        listener.onStatus(AgentEventListener.STATUS_THINKING, null);
        String reply = runWithTools(messages, toolContext, listener, steps, true, cancelled);
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
            ProgressSteps.Started localStep = steps.start(AgentStep.VALIDATE_LOCAL, Map.of());
            List<ConnectionSelection> rules = new ArrayList<>(request.connections());
            rules.addAll(toolContext.usedConnections());
            local = withConnectionRules(validator.validate(config), config, rules);
            localStep.end(SeatunnelTools.validationReport(local));
            engine = ValidationResult.skipped(ValidationResult.STAGE_ENGINE, "local validation failed");
            if (local.valid()) {
                ProgressSteps.Started engineStep = steps.start(AgentStep.VALIDATE_ENGINE, Map.of());
                if (engineChecker.isEnabled()) {
                    listener.onStatus(AgentEventListener.STATUS_VALIDATING_ENGINE, null);
                }
                // The config still contains the placeholders of the credentials, no secret is written to disk
                engine = engineChecker.check(config);
                engineStep.end(SeatunnelTools.validationReport(engine));
            }
            if ((local.valid() && engine.valid()) || fixAttempts >= maxFixAttempts) {
                break;
            }
            fixAttempts++;
            listener.onStatus(AgentEventListener.STATUS_FIXING, String.valueOf(fixAttempts));
            ValidationResult failed = local.valid() ? engine : local;
            String validation = failed.describe();
            ProgressSteps.Started fixStep = steps.start(AgentStep.FIXING, Map.of("round", fixAttempts));
            messages.add(UserMessage.from(fixTemplate.replace("{{VALIDATION}}", validation)));
            String fixReply = runWithTools(messages, toolContext, listener, steps, false, cancelled);
            String fixedConfig = extractConfig(fixReply);
            List<String> reasons = failed.errors().stream().map(ValidationIssue::toString).collect(Collectors.toList());
            fixStep.end(fixedConfig == null
                    ? new StepReport(AgentStep.ERROR, Map.of("generated", false), reasons)
                    : StepReport.ok(Map.of("generated", true), reasons));
            if (fixedConfig == null) {
                break;
            }
            config = fixedConfig;
        }
        // Only the config gets the real credentials back, the text shown in the chat keeps them masked
        listener.onProposal(new SeatunnelProposal(redactor.restoreConfig(config), CredentialRedactor.mask(explanation),
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
                                ProgressSteps steps, boolean streamText,
                                BooleanSupplier cancelled) throws Exception {
        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (cancelled.getAsBoolean()) {
                throw new CancelledException();
            }
            ProgressSteps.Started thinking = steps.start(AgentStep.THINKING, Map.of());
            ChatResponse response = stream(messages, streamText ? listener : null);
            thinking.end(null);
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
                } else if (connectionStatus(toolRequest.name()) != null) {
                    // The parameters of a connection may contain credentials, only the target is shown
                    listener.onStatus(connectionStatus(toolRequest.name()), connectionTarget(toolRequest));
                } else {
                    listener.onStatus(AgentEventListener.STATUS_TOOL,
                            toolRequest.name() + " " + toolRequest.arguments());
                }
                ProgressSteps.Started step = steps.start(stepKind(toolRequest.name()), stepArgs(toolRequest));
                String result = tools.execute(toolRequest, toolContext);
                StepReport report = toolContext.takeReport();
                step.end(report != null ? report : fallbackReport(result));
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
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
                .replace("{{SELECTED_CONNECTIONS}}", describeConnections(request.connections()))
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
        // The HTTP connection the user selected
        request.connections().stream()
                .filter(selection -> selection.connection().type() == ConnectionType.HTTP)
                .forEach(selection -> texts.add(selection.connection().param("base_url")));
        return HttpSampleFetcher.allowedHosts(texts.toArray(new String[0]));
    }

    /**
     * Add the issues of the rules of the selected connections, e.g. the consumer group prefix of a Kafka cluster.
     */
    private static ValidationResult withConnectionRules(ValidationResult local, String config,
                                                        List<ConnectionSelection> connections) {
        List<ValidationIssue> issues = ConnectionConfigChecker.check(config, connections);
        if (issues.isEmpty()) {
            return local;
        }
        List<ValidationIssue> all = new ArrayList<>(local.issues());
        all.addAll(issues);
        return ValidationResult.of(ValidationResult.STAGE_LOCAL, all);
    }

    private static String stepKind(String tool) {
        switch (tool) {
            case SeatunnelTools.GET_CONNECTOR_INFO:
                return AgentStep.CONNECTOR_INFO;
            case SeatunnelTools.LIST_CONNECTORS:
                return AgentStep.LIST_CONNECTORS;
            case SeatunnelTools.VALIDATE_CONFIG:
                return AgentStep.CHECK_CONFIG;
            case SeatunnelTools.FETCH_HTTP_SAMPLE:
                return AgentStep.HTTP_SAMPLE;
            case ConnectionTools.LIST_CONNECTIONS:
                return AgentStep.LIST_CONNECTIONS;
            case ConnectionTools.BROWSE_CONNECTION:
                return AgentStep.BROWSE;
            case ConnectionTools.DESCRIBE_OBJECT:
                return AgentStep.DESCRIBE;
            default:
                return AgentStep.TOOL;
        }
    }

    /**
     * @return the arguments shown in the progress: names and targets only, never parameters or credentials
     */
    private static Map<String, Object> stepArgs(ToolExecutionRequest toolRequest) {
        JsonNode arguments;
        try {
            String raw = toolRequest.arguments();
            arguments = MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (IOException e) {
            arguments = MAPPER.createObjectNode();
        }
        switch (toolRequest.name()) {
            case SeatunnelTools.GET_CONNECTOR_INFO:
                return Map.of("name", arguments.path("connector_name").asText(), "type",
                        arguments.path("connector_type").asText());
            case SeatunnelTools.FETCH_HTTP_SAMPLE:
                return Map.of("target", HttpSampleFetcher.describeTarget(toolRequest.arguments()));
            case ConnectionTools.LIST_CONNECTIONS:
            case SeatunnelTools.LIST_CONNECTORS:
            case SeatunnelTools.VALIDATE_CONFIG:
                return Map.of();
            case ConnectionTools.BROWSE_CONNECTION:
            case ConnectionTools.DESCRIBE_OBJECT:
                return Map.of("target", ConnectionTools.describeTarget(arguments));
            default:
                return Map.of("name", toolRequest.name());
        }
    }

    /**
     * The result of a tool which did not report: a failure by its text, otherwise done.
     */
    static StepReport fallbackReport(String result) {
        String text = result == null ? "" : result;
        for (String prefix : List.of("Failed", "Not called", "Request failed", "Unknown tool", "Invalid tool")) {
            if (text.startsWith(prefix)) {
                return StepReport.error(text.split("\n")[0]);
            }
        }
        return StepReport.ok(Map.of(), List.of());
    }

    /**
     * The fields of the selected connections, so that the progress shows where the fields come from.
     */
    private static void reportSelections(List<ConnectionSelection> selections, ProgressSteps steps) {
        for (ConnectionSelection selection : selections) {
            int fields = selection.descriptions().stream().mapToInt(description -> description.fields().size())
                    .sum();
            List<String> details = selection.descriptions().stream()
                    .flatMap(description -> description.fields().stream()
                            .map(field -> description.object() + "." + field.name() + ": " + field.type()))
                    .collect(Collectors.toList());
            steps.instant(AgentStep.SELECTION,
                    Map.of("role", selection.role().name().toLowerCase(Locale.ROOT), "target", selection.summary()),
                    StepReport.ok(Map.of("fields", fields, "objects", selection.objects().size(), "newTable",
                            selection.newTable() != null && !selection.newTable().isEmpty()), details));
        }
    }

    private static String connectionStatus(String tool) {
        switch (tool) {
            case ConnectionTools.LIST_CONNECTIONS:
                return AgentEventListener.STATUS_LIST_CONNECTIONS;
            case ConnectionTools.BROWSE_CONNECTION:
                return AgentEventListener.STATUS_BROWSE;
            case ConnectionTools.DESCRIBE_OBJECT:
                return AgentEventListener.STATUS_DESCRIBE;
            default:
                return null;
        }
    }

    private static String connectionTarget(ToolExecutionRequest toolRequest) {
        try {
            String raw = toolRequest.arguments();
            return ConnectionTools.describeTarget(MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw));
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The inline connections of the tools may only use the host names the user wrote.
     */
    private static Set<String> allowedHostNames(SeatunnelChatRequest request) {
        List<String> texts = new ArrayList<>();
        texts.add(request.userInput());
        texts.add(request.currentScript());
        request.history().stream()
                .filter(turn -> ChatTurn.USER.equals(turn.role()))
                .forEach(turn -> texts.add(turn.content()));
        return HostGuard.hostNames(texts.toArray(new String[0]));
    }

    private static String describeConnections(List<ConnectionSelection> connections) {
        if (connections.isEmpty()) {
            return "No connection is selected, the user describes the source and the sink in the conversation.";
        }
        return connections.stream().map(ConnectionSelection::toPrompt).collect(Collectors.joining("\n"));
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
