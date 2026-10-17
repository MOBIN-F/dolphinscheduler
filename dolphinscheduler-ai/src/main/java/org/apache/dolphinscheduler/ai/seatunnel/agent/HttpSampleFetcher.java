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
import org.apache.dolphinscheduler.ai.security.CredentialRedactor;
import org.apache.dolphinscheduler.ai.security.HostGuard;
import org.apache.dolphinscheduler.ai.security.SensitiveData;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Calls an HTTP API the user gave, so that the LLM knows the fields of the response when it writes the schema of the
 * Http source. Only the inferred fields with truncated sample values are returned, not the response.
 *
 * <p>The api-server is usually in the internal network, so only the hosts the user wrote in the conversation or the
 * script are called, and the loopback, link-local (e.g. the cloud metadata service), any-local and multicast
 * addresses are always denied. Private addresses are allowed, the APIs are usually internal.
 */
public class HttpSampleFetcher {

    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://(\\[[0-9a-f:.]+])?[^\\s\"'`<>()\\[\\]{}，。；、]*");
    private static final Pattern PARAMETER = Pattern.compile("\\$\\{([^}]*)}");
    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    private static final int MAX_REDIRECTS = 2;
    private static final int MAX_TEXT_LINES = 3;
    private static final int MAX_LINE_LENGTH = 200;
    private static final int MAX_ERROR_BODY = 500;
    /**
     * Max length of the raw response shown to the user.
     */
    static final int MAX_RAW_CHARS = 20_000;

    private static final String WORKER_NOTE = "\nNote: the request was sent from the DolphinScheduler api-server. "
            + "The SeaTunnel job runs on a worker, which must be able to reach the address as well.\n"
            + "The raw response is shown to the user in the chat, do not reconstruct or repeat it.";

    private final HttpClient client;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final int maxCallsPerRequest;
    private final HostGuard hostGuard;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpSampleFetcher(AiProperties.HttpSample properties) {
        this(properties.getTimeout(), properties.getMaxResponseBytes(), properties.getMaxCallsPerRequest(),
                properties.getDeniedHosts(), false);
    }

    /**
     * @param allowLoopback allow the loopback addresses, for tests only
     */
    HttpSampleFetcher(Duration timeout, int maxResponseBytes, int maxCallsPerRequest, List<String> deniedHosts,
                      boolean allowLoopback) {
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.maxCallsPerRequest = maxCallsPerRequest;
        this.hostGuard = new HostGuard(deniedHosts, allowLoopback);
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @return {@code host:port} of the http / https URLs in the texts
     */
    public static Set<String> allowedHosts(String... texts) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            Matcher matcher = URL.matcher(text);
            while (matcher.find()) {
                String hostKey = hostKey(matcher.group().replaceAll("[.,;:!?]+$", ""));
                if (hostKey != null) {
                    hosts.add(hostKey);
                }
            }
        }
        return hosts;
    }

    /**
     * @return the method and the URL without the query, shown as the progress in the chat
     */
    static String describeTarget(String rawArguments) {
        JsonNode arguments;
        try {
            arguments =
                    new ObjectMapper().readTree(rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments);
        } catch (IOException e) {
            return "";
        }
        String url = arguments.path("url").asText();
        int end = url.length();
        for (char c : new char[]{'?', '#'}) {
            int index = url.indexOf(c);
            if (index >= 0) {
                end = Math.min(end, index);
            }
        }
        return method(arguments) + " " + url.substring(0, end).replaceFirst("://[^/@]*@", "://");
    }

    /**
     * @return the result for the LLM, errors are returned as text
     */
    public String fetch(JsonNode arguments, ToolContext context) {
        if (context.countHttpCall() > maxCallsPerRequest) {
            return "Not called: at most " + maxCallsPerRequest + " HTTP calls per request. Write the config with the "
                    + "fields you already know, or ask the user for a sample response.";
        }
        String method = method(arguments);
        if (!method.equals("GET") && !method.equals("POST")) {
            return "Not called: only GET and POST are supported.";
        }
        String url = context.resolve(arguments.path("url").asText().trim());
        Map<String, String> headers = restore(arguments.path("headers"), context);
        Map<String, String> params = restore(arguments.path("params"), context);
        JsonNode bodyNode = arguments.path("body");
        String body = bodyNode.isMissingNode() || bodyNode.isNull() ? null
                : context.resolve(bodyNode.isTextual() ? bodyNode.asText() : bodyNode.toString());

        Set<String> undefined = new LinkedHashSet<>();
        collectParameters(url, undefined);
        headers.values().forEach(value -> collectParameters(value, undefined));
        params.values().forEach(value -> collectParameters(value, undefined));
        collectParameters(body, undefined);
        if (!undefined.isEmpty()) {
            return "Not called: the request contains the undefined parameters " + undefined + ". Ask the user for "
                    + "their values, or use sample values (e.g. a date) in the arguments of this call.";
        }
        try {
            String result = context.hide(send(method, buildUri(url, params), headers, body, context));
            context.report(report(result));
            return result;
        } catch (IllegalArgumentException e) {
            return context.hide("Not called: invalid request, " + e.getMessage());
        }
    }

    private String send(String method, URI uri, Map<String, String> headers, String body,
                        ToolContext context) {
        String currentMethod = method;
        String currentBody = body;
        URI current = uri;
        Set<String> allowedHosts = new LinkedHashSet<>(context.allowedHosts());
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            String denied = checkTarget(current, allowedHosts);
            if (denied != null) {
                return "Not called: " + denied;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(current).timeout(timeout);
            boolean hasAccept = false;
            boolean hasContentType = false;
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (RESTRICTED_HEADERS.contains(name)) {
                    continue;
                }
                hasAccept |= name.equals("accept");
                hasContentType |= name.equals("content-type");
                request.header(header.getKey(), header.getValue());
            }
            if (!hasAccept) {
                request.header("Accept", "application/json, text/plain, */*");
            }
            if (currentMethod.equals("POST")) {
                String content = currentBody == null ? "" : currentBody;
                if (!hasContentType && (content.trim().startsWith("{") || content.trim().startsWith("["))) {
                    request.header("Content-Type", "application/json");
                }
                request.POST(HttpRequest.BodyPublishers.ofString(content));
            } else {
                request.GET();
            }
            HttpResponse<InputStream> response;
            byte[] bytes;
            try {
                response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream inputStream = response.body()) {
                    bytes = inputStream.readNBytes(maxResponseBytes + 1);
                }
            } catch (HttpTimeoutException e) {
                return "Request failed: no response within " + timeout.getSeconds() + "s." + WORKER_NOTE;
            } catch (IOException e) {
                return "Request failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage()) + WORKER_NOTE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Request interrupted.";
            }
            int status = response.statusCode();
            String location = response.headers().firstValue("Location").orElse(null);
            if (status >= 300 && status < 400 && location != null) {
                URI next = current.resolve(location);
                // e.g. http to https on the same host, other hosts must be given by the user
                String nextHostKey = hostKey(next.toString());
                if (nextHostKey != null && hostName(nextHostKey).equals(hostName(hostKey(current.toString())))) {
                    allowedHosts.add(nextHostKey);
                }
                current = next;
                if (status == 303 || (status != 307 && status != 308)) {
                    currentMethod = "GET";
                    currentBody = null;
                }
                continue;
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            boolean truncated = bytes.length > maxResponseBytes;
            String responseBody = new String(bytes, 0, Math.min(bytes.length, maxResponseBytes), charset(contentType));
            context.onHttpResponse(new HttpResponseSample(target(currentMethod, current), status, contentType,
                    Math.min(bytes.length, maxResponseBytes), truncated || responseBody.length() > MAX_RAW_CHARS,
                    responseBody.length() > MAX_RAW_CHARS ? responseBody.substring(0, MAX_RAW_CHARS) : responseBody));
            return describeResponse(status, contentType, responseBody, truncated);
        }
        return "Not called: more than " + MAX_REDIRECTS + " redirects.";
    }

    /**
     * @return the progress step of a call: the status and the number of JSON fields, or the reason it failed
     */
    static StepReport report(String result) {
        Matcher status = Pattern.compile("^HTTP (\\d{3})").matcher(result);
        if (!status.find()) {
            return StepReport.error(result.split("\n")[0]);
        }
        int code = Integer.parseInt(status.group(1));
        long fields = result.lines().filter(line -> line.startsWith("$")).count();
        Map<String, Object> values = Map.of("status", code, "fields", fields);
        return code >= 200 && code < 300 ? StepReport.ok(values, List.of())
                : new StepReport(AgentStep.ERROR, values, List.of());
    }

    /**
     * @return why the URI must not be called, or null if allowed
     */
    String checkTarget(URI uri, Set<String> allowedHosts) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "only http and https URLs are supported.";
        }
        String hostKey = hostKey(uri.toString());
        if (hostKey == null) {
            return "the URL has no host.";
        }
        if (!allowedHosts.contains(hostKey)) {
            return "the host " + hostKey + " is not given by the user. Only the addresses the user wrote in the "
                    + "conversation or in the script can be called, ask the user for the full URL of the API.";
        }
        String denied = hostGuard.checkAddress(hostName(hostKey));
        return denied == null ? null : denied + ".";
    }

    /**
     * @return the address checks, shared with the other tools connecting to the addresses the user gave
     */
    public HostGuard hostGuard() {
        return hostGuard;
    }

    private String describeResponse(int status, String contentType, String body, boolean truncated) {
        StringBuilder text = new StringBuilder("HTTP ").append(status)
                .append(", content-type: ").append(contentType.isEmpty() ? "(none)" : contentType)
                .append(", ").append(truncated ? "more than " + maxResponseBytes
                        : body.getBytes(charset(contentType)).length)
                .append(" bytes")
                .append(truncated ? " (truncated)" : "").append("\n");
        if (status < 200 || status >= 300) {
            text.append("The API returned an error. Response (first ").append(MAX_ERROR_BODY).append(" chars): ")
                    .append(mask(truncate(body, MAX_ERROR_BODY)));
            return text.append(WORKER_NOTE).toString();
        }
        String trimmed = body.trim();
        if (contentType.toLowerCase(Locale.ROOT).contains("json") || trimmed.startsWith("{")
                || trimmed.startsWith("[")) {
            try {
                JsonNode root = mapper.readTree(trimmed);
                if (root != null && (root.isObject() || root.isArray())) {
                    text.append(JsonFieldInferrer.describe(root));
                    return text.append(WORKER_NOTE).toString();
                }
            } catch (IOException e) {
                text.append("The response is not valid JSON").append(truncated ? " (it was truncated)" : "")
                        .append(": ").append(truncate(String.valueOf(e.getMessage()).split("\n")[0], MAX_LINE_LENGTH))
                        .append("\n");
            }
        }
        List<String> lines = trimmed.isEmpty() ? List.of() : List.of(trimmed.split("\\r?\\n"));
        text.append("Text response, ").append(lines.size()).append(truncated ? "+" : "").append(" lines.");
        if (!lines.isEmpty()) {
            text.append(" First lines:\n").append(lines.stream().limit(MAX_TEXT_LINES)
                    .map(line -> mask(truncate(line, MAX_LINE_LENGTH))).collect(Collectors.joining("\n")));
        }
        return text.append(WORKER_NOTE).toString();
    }

    /**
     * @return the method and the URL without the user info, the query and the fragment
     */
    private static String target(String method, URI uri) {
        return method + " " + uri.getScheme() + "://" + uri.getRawAuthority().substring(
                uri.getRawAuthority().lastIndexOf('@') + 1) + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }

    private static String method(JsonNode arguments) {
        String method = arguments.path("method").asText("").trim().toUpperCase(Locale.ROOT);
        return method.isEmpty() ? "GET" : method;
    }

    private static Map<String, String> restore(JsonNode node, ToolContext context) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                JsonNode value = entry.getValue();
                values.put(entry.getKey(), context.resolve(value.isTextual() ? value.asText() : value.toString()));
            }
        }
        return values;
    }

    private static void collectParameters(String text, Set<String> parameters) {
        if (text == null) {
            return;
        }
        Matcher matcher = PARAMETER.matcher(text);
        while (matcher.find()) {
            parameters.add(matcher.group());
        }
    }

    private static URI buildUri(String url, Map<String, String> params) {
        if (params.isEmpty()) {
            return URI.create(url);
        }
        String query = params.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        int fragment = url.indexOf('#');
        String base = fragment >= 0 ? url.substring(0, fragment) : url;
        return URI.create(base + (base.contains("?") ? "&" : "?") + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * @return {@code host:port} in lower case with the default port of the scheme, or null if the URL has no host
     */
    static String hostKey(String url) {
        Matcher matcher = Pattern.compile("(?i)^(https?)://([^/?#]*)").matcher(url);
        if (!matcher.find()) {
            return null;
        }
        String authority = matcher.group(2);
        authority = authority.substring(authority.lastIndexOf('@') + 1).toLowerCase(Locale.ROOT);
        String host = authority;
        String port = null;
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            if (end < 0) {
                return null;
            }
            host = authority.substring(0, end + 1);
            if (authority.length() > end + 2 && authority.charAt(end + 1) == ':') {
                port = authority.substring(end + 2);
            }
        } else if (authority.contains(":")) {
            host = authority.substring(0, authority.indexOf(':'));
            port = authority.substring(authority.indexOf(':') + 1);
        }
        if (host.isEmpty() || (port != null && !port.matches("\\d{1,5}"))) {
            return null;
        }
        if (port == null) {
            port = matcher.group(1).equalsIgnoreCase("https") ? "443" : "80";
        }
        return host + ":" + port;
    }

    /**
     * @return the host of {@code host:port}, without the brackets of an IPv6 address
     */
    private static String hostName(String hostKey) {
        return hostKey.substring(0, hostKey.lastIndexOf(':')).replaceAll("^\\[|]$", "");
    }

    /**
     * @param entry a host name (also matches the sub domains), an IP or a CIDR
     */
    static boolean isDenied(String entry, String host, InetAddress address) {
        return HostGuard.isDenied(entry, host, address);
    }

    private static Charset charset(String contentType) {
        Matcher matcher = Pattern.compile("(?i)charset=\"?([\\w.-]+)").matcher(contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (RuntimeException e) {
                // Fall back to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /**
     * Mask credentials such as {@code token=xxx} in the text of the response.
     */
    private static String mask(String text) {
        return SensitiveData.hideInText(CredentialRedactor.mask(new CredentialRedactor().redactText(text)));
    }
}
