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

import org.apache.dolphinscheduler.ai.config.AiProperties;
import org.apache.dolphinscheduler.ai.llm.BedrockChatModels;
import org.apache.dolphinscheduler.ai.llm.TokenUsageLogger;
import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository;
import org.apache.dolphinscheduler.ai.seatunnel.validation.HoconValidator;
import org.apache.dolphinscheduler.ai.seatunnel.validation.SeatunnelEngineChecker;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * End to end test with the real AWS Bedrock, only runs when AWS_BEARER_TOKEN_BEDROCK (or other AWS credentials) is
 * set via AI_LIVE_TEST=true. SEATUNNEL_HOME_FOR_TEST enables the engine validation.
 */
public class SeatunnelConfigAgentLiveTest {

    @Test
    public void testGenerateConfig() {
        Assumptions.assumeTrue("true".equals(System.getenv("AI_LIVE_TEST")), "AI_LIVE_TEST is not true");
        AiProperties properties = new AiProperties();
        ConnectorMetadataRepository repository = ConnectorMetadataRepository.load("", "2.3.13");
        String home = System.getenv("SEATUNNEL_HOME_FOR_TEST");
        try (BedrockChatModels models = new BedrockChatModels(properties.getLlm(), List.of(new TokenUsageLogger()))) {
            SeatunnelConfigAgent agent = new SeatunnelConfigAgent(models.mainModel(), models.fastModel(),
                    models.maxTokens(), Duration.ofSeconds(180), repository, new HoconValidator(repository),
                    new SeatunnelEngineChecker(home, Duration.ofSeconds(120)), 3);
            RecordingListener listener = new RecordingListener();
            agent.chat(new SeatunnelChatRequest(List.of(),
                    "把 MySQL (host 10.0.0.5:3306, 库 shop, 用户 etl, 密码是 Etl@2026) 的 orders 表全量同步到 "
                            + "ClickHouse (host ch:8123, 库 dw, 表 orders, 用户 default)",
                    "", Map.of("startupScript", "seatunnel.sh", "deployMode", "local")), listener, () -> false);
            System.out.println("statuses: " + listener.statuses);
            System.out.println("reply: " + listener.deltas);
            Assertions.assertNull(listener.error, listener.error);
            Assertions.assertNotNull(listener.proposal, "no config proposed: " + listener.done);
            System.out.println("script:\n" + listener.proposal.script());
            System.out.println(listener.proposal.localValidation().describe());
            System.out.println(listener.proposal.engineValidation().describe());
            Assertions.assertTrue(listener.proposal.valid());
            Assertions.assertTrue(listener.proposal.script().contains("\"Etl@2026\""));
            Assertions.assertFalse(listener.done.contains("Etl@2026"), listener.done);
        }
    }
}
