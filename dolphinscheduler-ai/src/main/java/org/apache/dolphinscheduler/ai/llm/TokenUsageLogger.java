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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

/**
 * Logs the model and the token usage of every LLM call, the prompts are not logged.
 */
public class TokenUsageLogger implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageLogger.class);

    @Override
    public void onResponse(ChatModelResponseContext context) {
        TokenUsage usage = context.chatResponse().tokenUsage();
        log.info("LLM call finished, model: {}, input tokens: {}, output tokens: {}, finish reason: {}",
                context.chatResponse().modelName(),
                usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount(),
                context.chatResponse().finishReason());
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        log.warn("LLM call failed: {}", context.error().getMessage());
    }
}
