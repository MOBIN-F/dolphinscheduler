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

package org.apache.dolphinscheduler.ai.config;

import org.apache.dolphinscheduler.ai.llm.BedrockChatModels;
import org.apache.dolphinscheduler.ai.llm.TokenUsageLogger;
import org.apache.dolphinscheduler.ai.seatunnel.agent.SeatunnelConfigAgent;
import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository;
import org.apache.dolphinscheduler.ai.seatunnel.validation.HoconValidator;
import org.apache.dolphinscheduler.ai.seatunnel.validation.SeatunnelEngineChecker;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Beans of the AI assistant, they are only created when {@code ai.enabled} is true.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
    public BedrockChatModels bedrockChatModels(AiProperties properties) {
        return new BedrockChatModels(properties.getLlm(), List.of(new TokenUsageLogger()));
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
    public ConnectorMetadataRepository seatunnelConnectorMetadataRepository(AiProperties properties) {
        AiProperties.Seatunnel seatunnel = properties.getSeatunnel();
        return ConnectorMetadataRepository.load(seatunnel.getMetadataPath(), seatunnel.getVersion());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
    public SeatunnelEngineChecker seatunnelEngineChecker(AiProperties properties) {
        AiProperties.Seatunnel seatunnel = properties.getSeatunnel();
        return new SeatunnelEngineChecker(seatunnel.getHome(), seatunnel.getCheckTimeout());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true")
    public SeatunnelConfigAgent seatunnelConfigAgent(AiProperties properties, BedrockChatModels models,
                                                     ConnectorMetadataRepository metadataRepository,
                                                     SeatunnelEngineChecker engineChecker) {
        return new SeatunnelConfigAgent(models.mainModel(), models.fastModel(), models.maxTokens(),
                properties.getLlm().getTimeout(), metadataRepository, new HoconValidator(metadataRepository),
                engineChecker, properties.getSeatunnel().getMaxFixAttempts());
    }
}
