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

package org.apache.dolphinscheduler.ai.llm;

import org.apache.dolphinscheduler.ai.config.AiProperties;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;

/**
 * The chat models on AWS Bedrock: a streaming main model and a cheaper fast model.
 *
 * <p>The streaming client uses the AWS CRT HTTP client instead of the default Netty client, which conflicts with
 * the Netty version of DolphinScheduler. The CRT client does not support HTTP/2, so it is built here and passed to
 * the SDK, then ConverseStream works over HTTP/1.1.
 */
public class BedrockChatModels implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BedrockChatModels.class);

    private final BedrockRuntimeAsyncClient asyncClient;
    private final BedrockRuntimeClient syncClient;
    private final StreamingChatModel mainModel;
    private final ChatModel fastModel;
    private final AiProperties.Llm properties;

    public BedrockChatModels(AiProperties.Llm properties, List<ChatModelListener> listeners) {
        this.properties = properties;
        Region region = Region.of(properties.getRegion());
        AwsCredentialsProvider credentials = credentialsProvider(properties);
        this.asyncClient = BedrockRuntimeAsyncClient.builder()
                .region(region)
                .credentialsProvider(credentials)
                .httpClient(AwsCrtAsyncHttpClient.builder().build())
                .build();
        this.syncClient = BedrockRuntimeClient.builder()
                .region(region)
                .credentialsProvider(credentials)
                .build();
        this.mainModel = BedrockStreamingChatModel.builder()
                .client(asyncClient)
                .modelId(properties.getModel())
                .timeout(properties.getTimeout())
                .listeners(listeners)
                .build();
        this.fastModel = BedrockChatModel.builder()
                .client(syncClient)
                .modelId(properties.getFastModel())
                .timeout(properties.getTimeout())
                .listeners(listeners)
                .build();
        log.info("AI assistant uses AWS Bedrock in {}, model: {}, fast model: {}", properties.getRegion(),
                properties.getModel(), properties.getFastModel());
    }

    public StreamingChatModel mainModel() {
        return mainModel;
    }

    public ChatModel fastModel() {
        return fastModel;
    }

    public int maxTokens() {
        return properties.getMaxTokens();
    }

    /**
     * Static credentials when configured, otherwise the default chain: environment variables (including the Bedrock
     * API key AWS_BEARER_TOKEN_BEDROCK), system properties, profile, instance role.
     */
    private static AwsCredentialsProvider credentialsProvider(AiProperties.Llm properties) {
        if (!properties.getAccessKeyId().isEmpty() && !properties.getSecretAccessKey().isEmpty()) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKeyId(), properties.getSecretAccessKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    @Override
    public void close() {
        asyncClient.close();
        syncClient.close();
    }
}
