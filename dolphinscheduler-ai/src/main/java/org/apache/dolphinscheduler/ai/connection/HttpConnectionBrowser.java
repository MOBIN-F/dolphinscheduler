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

package org.apache.dolphinscheduler.ai.connection;

import org.apache.dolphinscheduler.ai.seatunnel.agent.HttpResponseSample;
import org.apache.dolphinscheduler.ai.seatunnel.agent.HttpSampleFetcher;
import org.apache.dolphinscheduler.ai.seatunnel.agent.JsonFieldInferrer;
import org.apache.dolphinscheduler.ai.seatunnel.agent.ToolContext;
import org.apache.dolphinscheduler.ai.security.CredentialRedactor;
import org.apache.dolphinscheduler.ai.security.SensitiveData;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lists the API paths of an HTTP connection and calls an API once to infer the fields of the response, with the
 * same address checks as the HTTP tool of the assistant.
 */
public class HttpConnectionBrowser implements ConnectionBrowser {

    private final HttpSampleFetcher fetcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpConnectionBrowser(HttpSampleFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.HTTP;
    }

    @Override
    public void test(SeatunnelConnection connection) {
        String base = connection.param("base_url");
        if (HttpSampleFetcher.allowedHosts(base).isEmpty()) {
            throw new IllegalArgumentException("Invalid base URL " + base);
        }
        List<BrowseNode> paths = list(connection, "");
        HttpResponseSample sample = call(connection, paths.isEmpty() ? "" : paths.get(0).path()).sample;
        if (sample == null) {
            throw new IllegalStateException("No response from " + base);
        }
    }

    @Override
    public List<BrowseNode> list(SeatunnelConnection connection, String parent) {
        return Stream.of(connection.param("paths").split("\\r?\\n"))
                .map(String::trim)
                .filter(path -> !path.isEmpty())
                .map(path -> new BrowseNode(path, path, true))
                .collect(Collectors.toList());
    }

    @Override
    public ObjectDescription describe(SeatunnelConnection connection, String path) throws Exception {
        Call call = call(connection, path);
        HttpResponseSample sample = call.sample;
        if (sample == null) {
            // Not called, the result explains why, e.g. the address is not allowed
            throw new IllegalStateException(call.result.split("\n")[0]);
        }
        // Shown to the user only, with the personal data masked
        List<String> samples = List.of(SensitiveData.maskSample(sample.body()));
        if (sample.status() < 200 || sample.status() >= 300) {
            return new ObjectDescription(path, "unknown", List.of(), null,
                    "the API returned HTTP " + sample.status(), samples);
        }
        JsonNode root = null;
        try {
            root = sample.truncated() ? null : mapper.readTree(sample.body());
        } catch (Exception e) {
            // Not JSON
        }
        if (root == null || !(root.isObject() || root.isArray())) {
            return new ObjectDescription(path, "text", List.of(), null,
                    sample.truncated() ? "the response is too large to infer the fields" : "the response is not JSON",
                    samples);
        }
        JsonFieldInferrer.RecordSchema schema = JsonFieldInferrer.records(root);
        String note = schema.recordPath() == null || schema.recordPath().equals("$") ? ""
                : "set content_field = \"" + schema.recordPath() + ".*\"";
        return new ObjectDescription(path, "json", schema.fields(), schema.recordPath(), note, samples);
    }

    private static final class Call {

        private String result;
        private HttpResponseSample sample;
    }

    private Call call(SeatunnelConnection connection, String path) {
        String base = connection.param("base_url").replaceAll("/+$", "");
        String url = path.matches("(?i)https?://.*") ? path : base + (path.startsWith("/") ? "" : "/") + path;
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("url", url);
        ObjectNode headers = arguments.putObject("headers");
        for (String header : connection.param("headers").split("\\r?\\n")) {
            int colon = header.indexOf(':');
            if (colon > 0) {
                headers.put(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
            }
        }
        String auth = connection.param("auth_type");
        if (auth.equals("BEARER")) {
            headers.put("Authorization", "Bearer " + connection.secret("token"));
        } else if (auth.equals("BASIC")) {
            headers.put("Authorization", "Basic " + basicAuth(connection.param("basic_username"),
                    connection.secret("basic_password")));
        }
        Call call = new Call();
        // Only the host of the connection can be called
        ToolContext context = new ToolContext(new CredentialRedactor(), HttpSampleFetcher.allowedHosts(base),
                sample -> call.sample = sample);
        call.result = fetcher.fetch(arguments, context);
        return call;
    }

    static String basicAuth(String username, String password) {
        return Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
