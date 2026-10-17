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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Personal and secret data in the sampled messages and responses: the LLM only gets a description of the format,
 * the user sees a partially masked value. A value is sensitive by its field name (e.g. phone, age, id_card) or by
 * its format (mobile number, email, ID number, bank card number, IPv4 address), whatever the field name is.
 */
public final class SensitiveData {

    /**
     * The kind of a sensitive value.
     */
    public enum Kind {
        CREDENTIAL,
        MOBILE,
        PHONE,
        EMAIL,
        ID_NUMBER,
        BANK_CARD,
        IP,
        NAME,
        OTHER
    }

    private static final Set<String> CREDENTIAL_WORDS = Set.of("password", "passwd", "pwd", "secret", "token",
            "auth", "authorization", "credential", "credentials", "session", "cookie", "signature", "apikey",
            "accesskey", "privatekey", "secretkey");

    private static final Map<String, Kind> DEFAULT_WORDS = Map.ofEntries(
            Map.entry("phone", Kind.PHONE), Map.entry("mobile", Kind.PHONE), Map.entry("tel", Kind.PHONE),
            Map.entry("telephone", Kind.PHONE), Map.entry("cellphone", Kind.PHONE), Map.entry("email", Kind.EMAIL),
            Map.entry("mail", Kind.EMAIL), Map.entry("idcard", Kind.ID_NUMBER), Map.entry("idno", Kind.ID_NUMBER),
            Map.entry("identity", Kind.ID_NUMBER), Map.entry("ssn", Kind.ID_NUMBER),
            Map.entry("passport", Kind.ID_NUMBER), Map.entry("bankcard", Kind.BANK_CARD),
            Map.entry("cardno", Kind.BANK_CARD), Map.entry("iban", Kind.BANK_CARD), Map.entry("ip", Kind.IP),
            Map.entry("name", Kind.NAME), Map.entry("realname", Kind.NAME), Map.entry("fullname", Kind.NAME),
            Map.entry("address", Kind.OTHER), Map.entry("addr", Kind.OTHER), Map.entry("age", Kind.OTHER),
            Map.entry("birthday", Kind.OTHER), Map.entry("birth", Kind.OTHER), Map.entry("dob", Kind.OTHER),
            Map.entry("gender", Kind.OTHER), Map.entry("sex", Kind.OTHER), Map.entry("salary", Kind.OTHER),
            Map.entry("income", Kind.OTHER), Map.entry("lat", Kind.OTHER), Map.entry("lng", Kind.OTHER),
            Map.entry("latitude", Kind.OTHER), Map.entry("longitude", Kind.OTHER), Map.entry("gps", Kind.OTHER),
            Map.entry("location", Kind.OTHER), Map.entry("plate", Kind.OTHER), Map.entry("license", Kind.OTHER));

    private static final Map<String, Kind> EXTRA_WORDS = new ConcurrentHashMap<>();

    private static final Pattern MOBILE = Pattern.compile("(?:\\+?86[- ]?)?1[3-9]\\d{9}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern ID_NUMBER = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern BANK_CARD = Pattern.compile("\\d{16,19}");
    private static final Pattern IP = Pattern.compile("(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}"
            + "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)");

    // In free text
    private static final Pattern TEXT_MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern TEXT_EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern TEXT_ID_NUMBER = Pattern.compile("(?<![\\dXx])\\d{17}[\\dXx](?![\\dXx])");
    private static final Pattern TEXT_BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern TEXT_IP = Pattern.compile("(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}"
            + "(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![\\d.])");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SensitiveData() {
    }

    /**
     * Add field name keywords, e.g. from the configuration.
     */
    public static void addKeywords(Collection<String> keywords) {
        if (keywords != null) {
            keywords.stream().filter(word -> word != null && !word.isBlank())
                    .forEach(word -> EXTRA_WORDS.put(normalize(word), Kind.OTHER));
        }
    }

    /**
     * @param field the field name, the last segment of a path such as {@code buyer.phone} is used
     * @param value the value as text, may be null
     * @return the kind when the field name or the value is sensitive
     */
    public static Optional<Kind> classify(String field, String value) {
        Optional<Kind> byValue = classifyValue(value);
        if (byValue.isPresent()) {
            return byValue;
        }
        return classifyField(field);
    }

    public static Optional<Kind> classifyField(String field) {
        if (field == null || field.isEmpty()) {
            return Optional.empty();
        }
        String name = field.replace("[*]", "");
        name = name.substring(name.lastIndexOf('.') + 1);
        List<String> words = words(name);
        for (int i = 0; i < words.size(); i++) {
            for (int j = i; j < Math.min(words.size(), i + 2); j++) {
                // A word, or two words joined, e.g. id + card, real + name
                String word = String.join("", words.subList(i, j + 1));
                if (CREDENTIAL_WORDS.contains(word)) {
                    return Optional.of(Kind.CREDENTIAL);
                }
                Kind kind = DEFAULT_WORDS.getOrDefault(word, EXTRA_WORDS.get(word));
                if (kind != null) {
                    return Optional.of(kind);
                }
            }
        }
        // E.g. key, api_key, secret_key, but not monkey
        return words.isEmpty() || !words.get(words.size() - 1).equals("key") ? Optional.empty()
                : Optional.of(Kind.CREDENTIAL);
    }

    public static Optional<Kind> classifyValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return Optional.empty();
        }
        String text = value.trim();
        if (MOBILE.matcher(text).matches()) {
            return Optional.of(Kind.MOBILE);
        }
        if (EMAIL.matcher(text).matches()) {
            return Optional.of(Kind.EMAIL);
        }
        if (ID_NUMBER.matcher(text).matches() && isIdNumber(text)) {
            return Optional.of(Kind.ID_NUMBER);
        }
        if (BANK_CARD.matcher(text).matches() && luhn(text)) {
            return Optional.of(Kind.BANK_CARD);
        }
        if (IP.matcher(text).matches()) {
            return Optional.of(Kind.IP);
        }
        return Optional.empty();
    }

    /**
     * @return the description of the value for the LLM, without any character of the value
     */
    public static String describe(Kind kind, String value) {
        int length = value == null ? 0 : value.length();
        switch (kind) {
            case MOBILE:
                return "mobile number";
            case PHONE:
                return value != null && MOBILE.matcher(value).matches() ? "mobile number" : "phone number";
            case EMAIL:
                return "email address";
            case ID_NUMBER:
                return length + "-character ID number";
            case BANK_CARD:
                return length + "-digit card number";
            case IP:
                return "IPv4 address";
            case NAME:
                return "name, " + length + " characters";
            case CREDENTIAL:
                return "secret, value hidden";
            default:
                return "value hidden";
        }
    }

    /**
     * @return the value partially masked for the user, e.g. 138****1234
     */
    public static String mask(Kind kind, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        switch (kind) {
            case MOBILE:
            case PHONE:
                return keepEnds(value, value.length() >= 11 ? 3 : 0, 4);
            case EMAIL: {
                int at = value.indexOf('@');
                return at <= 0 ? CredentialRedactor.MASK : value.charAt(0) + "***" + value.substring(at);
            }
            case ID_NUMBER:
                return keepEnds(value, 4, 4);
            case BANK_CARD:
                return keepEnds(value, 0, 4);
            case IP: {
                String[] parts = value.split("\\.");
                return parts.length == 4 ? parts[0] + "." + parts[1] + ".*.*" : CredentialRedactor.MASK;
            }
            case NAME:
                return value.codePointCount(0, value.length()) <= 1 ? "*"
                        : new String(Character.toChars(value.codePointAt(0)))
                                + "*".repeat(value.codePointCount(0, value.length()) - 1);
            case CREDENTIAL:
                return CredentialRedactor.MASK;
            default:
                return "**";
        }
    }

    /**
     * @return the JSON or the text of a sample, with the sensitive values masked for the user
     */
    public static String maskSample(String sample) {
        if (sample == null || sample.isEmpty()) {
            return sample;
        }
        String trimmed = sample.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                JsonNode node = MAPPER.readTree(trimmed);
                if (node != null && node.isContainerNode()) {
                    return MAPPER.writeValueAsString(maskJson(node, ""));
                }
            } catch (Exception e) {
                // Not JSON, e.g. truncated
            }
        }
        return replaceInText(sample, SensitiveData::mask);
    }

    /**
     * @return the text with the sensitive values replaced by their descriptions, e.g. for the text lines of an API
     *         response sent to the LLM
     */
    public static String hideInText(String text) {
        return replaceInText(text, (kind, value) -> "<" + describe(kind, value) + ">");
    }

    private static JsonNode maskJson(JsonNode node, String field) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                result.set(entry.getKey(), maskJson(entry.getValue(), entry.getKey()));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(element -> result.add(maskJson(element, field)));
            return result;
        }
        if (node.isNull() || node.isMissingNode()) {
            return node;
        }
        String value = node.asText();
        Optional<Kind> kind = classify(field, value);
        return kind.isPresent() ? new TextNode(mask(kind.get(), value)) : node;
    }

    private interface Replacer {

        String replace(Kind kind, String value);
    }

    private static String replaceInText(String text, Replacer replacer) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        result = replaceAll(result, TEXT_EMAIL, value -> replacer.replace(Kind.EMAIL, value));
        result = replaceAll(result, TEXT_ID_NUMBER,
                value -> isIdNumber(value) ? replacer.replace(Kind.ID_NUMBER, value) : value);
        result = replaceAll(result, TEXT_BANK_CARD,
                value -> luhn(value) ? replacer.replace(Kind.BANK_CARD, value) : value);
        result = replaceAll(result, TEXT_MOBILE, value -> replacer.replace(Kind.MOBILE, value));
        result = replaceAll(result, TEXT_IP, value -> replacer.replace(Kind.IP, value));
        return result;
    }

    private static String replaceAll(String text, Pattern pattern, Function<String, String> replacement) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement.apply(matcher.group())));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String keepEnds(String value, int head, int tail) {
        if (value.length() <= head + tail) {
            return "*".repeat(value.length());
        }
        return value.substring(0, head) + "*".repeat(value.length() - head - tail)
                + value.substring(value.length() - tail);
    }

    /**
     * The words of a field name: split by separators and camel case, e.g. userPhoneNo -> user, phone, no.
     */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        for (String part : name.split("[^A-Za-z0-9]+")) {
            for (String word : part.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                if (!word.isEmpty()) {
                    words.add(word.toLowerCase(Locale.ROOT));
                }
            }
        }
        return words;
    }

    private static String normalize(String word) {
        return String.join("", words(word));
    }

    /**
     * The check digit of an 18-character ID number (GB 11643), so that other 18-digit numbers are not masked.
     */
    static boolean isIdNumber(String value) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        String checks = "10X98765432";
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (value.charAt(i) - '0') * weights[i];
        }
        return Character.toUpperCase(value.charAt(17)) == checks.charAt(sum % 11);
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (twice) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            twice = !twice;
        }
        return sum % 10 == 0;
    }
}
