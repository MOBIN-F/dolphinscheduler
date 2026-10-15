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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

public class SeatunnelConfigAgentTest {

    private static final String CONFIG_WITHOUT_DRIVER = "env { job.mode = \"BATCH\" }\n"
            + "source { Jdbc { url = \"jdbc:mysql://h:3306/db\", username = \"root\", password = \"${_CRED_1_}\", "
            + "query = \"select * from t\", plugin_output = \"t\" } }\n"
            + "sink { Console { plugin_input = \"t\" } }";

    /**
     * Replies with the scripted messages in order and records the requests.
     */
    static class ScriptedModel implements StreamingChatModel {

        final Deque<AiMessage> replies = new LinkedList<>();
        final List<List<ChatMessage>> requests = new ArrayList<>();

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            requests.add(new ArrayList<>(request.messages()));
            AiMessage reply = replies.removeFirst();
            if (reply.text() != null) {
                handler.onPartialResponse(reply.text());
            }
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(reply).build());
        }
    }

    private SeatunnelConfigAgent agent(ScriptedModel model) {
        ConnectorMetadataRepository repository = ConnectorMetadataRepository.load("", "2.3.13");
        return new SeatunnelConfigAgent(model, (ChatModel) null, 4096, Duration.ofSeconds(10), repository,
                new HoconValidator(repository), new SeatunnelEngineChecker("", Duration.ofSeconds(10)), 3);
    }

    @Test
    public void testGenerateValidateFixAndRestoreCredentials() {
        ScriptedModel model = new ScriptedModel();
        model.replies.add(AiMessage.from(ToolExecutionRequest.builder().id("1").name("get_connector_info")
                .arguments("{\"connector_name\":\"Jdbc\",\"connector_type\":\"source\"}").build()));
        model.replies.add(AiMessage.from("Here is the config:\n```hocon\n" + CONFIG_WITHOUT_DRIVER
                + "\n```\nIt reads table t."));
        model.replies.add(AiMessage.from("```hocon\n" + CONFIG_WITHOUT_DRIVER.replace("username",
                "driver = \"com.mysql.cj.jdbc.Driver\", username") + "\n```\nAdded the driver."));

        RecordingListener listener = new RecordingListener();
        agent(model).chat(new SeatunnelChatRequest(List.of(), "sync t from mysql, password is S3cret!", "",
                Map.of("startupScript", "seatunnel.sh")), listener, () -> false);

        Assertions.assertNull(listener.error);
        Assertions.assertNotNull(listener.proposal);
        Assertions.assertTrue(listener.proposal.valid(), listener.proposal.localValidation().describe());
        Assertions.assertEquals(1, listener.proposal.fixAttempts());
        // The credential is restored in the proposal but never sent to the LLM
        Assertions.assertTrue(listener.proposal.script().contains("S3cret!"), listener.proposal.script());
        String sent = model.requests.toString();
        Assertions.assertFalse(sent.contains("S3cret!"), sent);
        // The tool result contains the connector options
        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) model.requests.get(1).get(3);
        Assertions.assertTrue(toolResult.text().contains("ALWAYS required"), toolResult.text());
        // The fix request contains the validation error
        UserMessage fixRequest = (UserMessage) model.requests.get(2).get(model.requests.get(2).size() - 1);
        Assertions.assertTrue(fixRequest.singleText().contains("[driver]"), fixRequest.singleText());
        Assertions.assertEquals("It reads table t.", listener.proposal.explanation().replace("Here is the config:", "")
                .trim());
        Assertions.assertTrue(listener.statuses.contains("fixing:1"), listener.statuses.toString());
    }

    @Test
    public void testQuestionWithoutConfig() {
        ScriptedModel model = new ScriptedModel();
        model.replies.add(AiMessage.from("Which database and table do you want to sync?"));
        RecordingListener listener = new RecordingListener();
        agent(model).chat(new SeatunnelChatRequest(List.of(new ChatTurn("assistant", "hi"),
                new ChatTurn("user", "a"), new ChatTurn("user", "b")), "sync mysql", "", Map.of()), listener,
                () -> false);
        Assertions.assertNull(listener.proposal);
        Assertions.assertEquals("Which database and table do you want to sync?", listener.done);
        // Leading assistant message dropped, consecutive user messages merged, history ends with the assistant
        List<ChatMessage> sent = model.requests.get(0);
        Assertions.assertTrue(sent.get(1) instanceof UserMessage);
        Assertions.assertTrue(((UserMessage) sent.get(1)).singleText().contains("a\n\nb"));
        Assertions.assertTrue(sent.get(2) instanceof AiMessage);
        Assertions.assertTrue(sent.get(3) instanceof UserMessage);
    }

    @Test
    public void testHttpSampleOnlyCallsHostsOfTheUser() {
        ScriptedModel model = new ScriptedModel();
        model.replies.add(AiMessage.from(ToolExecutionRequest.builder().id("1").name("fetch_http_sample")
                .arguments("{\"url\":\"http://made-up.example/api?token=1\"}").build()));
        model.replies.add(AiMessage.from("Please give me a sample response."));
        ConnectorMetadataRepository repository = ConnectorMetadataRepository.load("", "2.3.13");
        SeatunnelConfigAgent agent = new SeatunnelConfigAgent(model, (ChatModel) null, 4096, Duration.ofSeconds(10),
                repository, new HoconValidator(repository), new SeatunnelEngineChecker("", Duration.ofSeconds(10)),
                3, new HttpSampleFetcher(Duration.ofSeconds(1), 1024, 3, List.of(), false));
        RecordingListener listener = new RecordingListener();
        agent.chat(new SeatunnelChatRequest(List.of(new ChatTurn("user", "api is https://api.shop.com/orders")),
                "sync it to console", "", Map.of()), listener, () -> false);
        Assertions.assertTrue(listener.statuses.contains("http_sample:GET http://made-up.example/api"),
                listener.statuses.toString());
        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) model.requests.get(1)
                .get(model.requests.get(1).size() - 1);
        Assertions.assertTrue(toolResult.text().contains("made-up.example:80 is not given by the user"),
                toolResult.text());
    }

    @Test
    public void testHttpSampleToolDisabled() {
        ConnectorMetadataRepository repository = ConnectorMetadataRepository.load("", "2.3.13");
        SeatunnelTools disabled = new SeatunnelTools(repository, new HoconValidator(repository), null);
        Assertions.assertTrue(disabled.specifications().stream()
                .noneMatch(spec -> spec.name().equals(SeatunnelTools.FETCH_HTTP_SAMPLE)));
        SeatunnelTools enabled = new SeatunnelTools(repository, new HoconValidator(repository),
                new HttpSampleFetcher(Duration.ofSeconds(1), 1024, 3, List.of(), false));
        Assertions.assertTrue(enabled.specifications().stream()
                .anyMatch(spec -> spec.name().equals(SeatunnelTools.FETCH_HTTP_SAMPLE)));
    }

    @Test
    public void testExtractConfig() {
        Assertions.assertNull(SeatunnelConfigAgent.extractConfig("no config"));
        Assertions.assertEquals("source {}\nsink {}",
                SeatunnelConfigAgent.extractConfig("```\nsource {}\nsink {}\n```"));
        Assertions.assertEquals("a = 1", SeatunnelConfigAgent.extractConfig("```bash\nls\n```\n```hocon\na = 1\n```"));
    }
}
