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

package org.apache.dolphinscheduler.ai.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces credentials with placeholders such as {@code ${_CRED_1_}} before the text is sent to the LLM, and restores
 * them in the LLM output. An instance is used for one request, the same value always gets the same placeholder.
 */
public class CredentialRedactor {

    private static final String SENSITIVE_KEY =
            "(?:[\\w.-]*(?:password|passwd|pwd|secret|token|access_key|accesskey|api_key|apikey|private_key|"
                    + "credential|sasl\\.jaas\\.config)[\\w.-]*)";

    /**
     * {@code password = "xxx"}, {@code "secret_key": "xxx"} or {@code token = xxx} in a config.
     */
    private static final Pattern CONFIG_VALUE = Pattern.compile(
            "(?im)^(\\s*\"?" + SENSITIVE_KEY + "\"?\\s*[=:]\\s*)(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s,}#\"]+))");

    /**
     * {@code password is xxx}, {@code 密码是 xxx}, {@code token: xxx} in a chat message.
     */
    private static final Pattern TEXT_VALUE = Pattern.compile(
            "(?i)((?:password|passwd|pwd|secret|token|access[ _]?key|secret[ _]?key|api[ _]?key|密码|口令|密钥)"
                    + "\\s*(?:is|=|:|：|是|为)\\s*)([^\\s,，;；。\"'`]+)");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{_CRED_\\d+_}");

    public static final String MASK = "******";

    private final Map<String, String> placeholderByValue = new LinkedHashMap<>();
    private final Map<String, String> valueByPlaceholder = new LinkedHashMap<>();

    public String redactConfig(String config) {
        if (config == null || config.isEmpty()) {
            return config;
        }
        Matcher matcher = CONFIG_VALUE.matcher(config);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            boolean quoted = matcher.group(2) != null;
            String value = quoted ? matcher.group(2) : matcher.group(3);
            String replacement = isRedactable(value)
                    ? matcher.group(1) + (quoted ? "\"" + placeholder(value) + "\"" : placeholder(value))
                    : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public String redactText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Configs pasted into the message are redacted first
        String redacted = redactConfig(text);
        Matcher matcher = TEXT_VALUE.matcher(redacted);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = trimUnbalancedClosingBrackets(matcher.group(2));
            String rest = matcher.group(2).substring(value.length());
            String replacement = isRedactable(value) ? matcher.group(1) + placeholder(value) + rest : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public String restore(String text) {
        if (text == null || valueByPlaceholder.isEmpty()) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = valueByPlaceholder.getOrDefault(matcher.group(), matcher.group());
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Replace the placeholders with a mask, used for the text shown in the chat and kept in the history, so that the
     * credentials are never sent back to the LLM in the next requests.
     */
    public static String mask(String text) {
        return text == null ? null : PLACEHOLDER.matcher(text).replaceAll(MASK);
    }

    public int redactedCount() {
        return valueByPlaceholder.size();
    }

    private String placeholder(String value) {
        return placeholderByValue.computeIfAbsent(value, key -> {
            String placeholder = "${_CRED_" + (placeholderByValue.size() + 1) + "_}";
            valueByPlaceholder.put(placeholder, key);
            return placeholder;
        });
    }

    /**
     * {@code 密码是 abc)} in a sentence: the closing bracket belongs to the sentence unless it is opened in the value.
     */
    private static String trimUnbalancedClosingBrackets(String value) {
        String result = value;
        while (!result.isEmpty()) {
            char last = result.charAt(result.length() - 1);
            char open = last == ')' ? '(' : last == '）' ? '（' : last == ']' ? '[' : last == '】' ? '【' : 0;
            if (open == 0 || result.indexOf(open) >= 0) {
                break;
            }
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * Values which are already placeholders (task parameters or redacted values), booleans and numbers (e.g.
     * {@code token_expire_seconds = 60}) are kept.
     */
    private static boolean isRedactable(String value) {
        return value != null && !value.isEmpty() && !value.contains("${")
                && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false") && !value.matches("\\d+");
    }
}
