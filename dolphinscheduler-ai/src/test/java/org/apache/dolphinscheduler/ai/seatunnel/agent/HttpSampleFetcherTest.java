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

import org.apache.dolphinscheduler.ai.security.CredentialRedactor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

public class HttpSampleFetcherTest {

    private static final String ORDERS = "{\"code\":0,\"msg\":\"ok\",\"data\":{\"total\":2,\"list\":["
            + "{\"id\":1001,\"amount\":12.5,\"status\":\"PAID\",\"created_at\":\"2026-10-01 08:30:00\","
            + "\"user\":{\"name\":\"alice\",\"token\":\"t0ps3cret\"},\"tags\":[\"a\",\"b\"]},"
            + "{\"id\":5000000000,\"amount\":3,\"status\":null,\"created_at\":\"2026-10-02 09:00:00\","
            + "\"user\":{\"name\":\"bob\",\"token\":\"x\"},\"tags\":[]}]}}";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> receivedAuthorization = new ConcurrentHashMap<>();
    private HttpServer server;
    private String base;

    @BeforeEach
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/orders", exchange -> {
            receivedAuthorization.put("orders", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            respond(exchange, 200, "application/json", ORDERS);
        });
        server.createContext("/echo", exchange -> respond(exchange, 200, "application/json",
                "{\"auth\":\"" + exchange.getRequestHeaders().getFirst("Authorization") + "\"}"));
        server.createContext("/text", exchange -> respond(exchange, 200, "text/plain",
                "id,name\n1,a\n2,b\n3,c\n4,d"));
        server.createContext("/error", exchange -> respond(exchange, 401, "application/json",
                "{\"error\":\"invalid token=abc123xyz\"}"));
        server.createContext("/big", exchange -> respond(exchange, 200, "application/json",
                "[" + "{\"a\":1},".repeat(1000) + "{\"a\":1}]"));
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "text/plain", "late");
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    public void stopServer() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String contentType,
                                String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private HttpSampleFetcher fetcher() {
        return new HttpSampleFetcher(Duration.ofSeconds(1), 1024 * 1024, 3, List.of(), true);
    }

    private ToolContext context(CredentialRedactor redactor) {
        return new ToolContext(redactor, HttpSampleFetcher.allowedHosts("同步 " + base + "/orders 的数据"));
    }

    private JsonNode arguments(String path) {
        ObjectNode arguments = mapper.createObjectNode();
        arguments.put("url", base + path);
        return arguments;
    }

    @Test
    public void testJsonFields() {
        String result = fetcher().fetch(arguments("/orders"), context(new CredentialRedactor()));
        Assertions.assertTrue(result.startsWith("HTTP 200"), result);
        Assertions.assertTrue(result.contains("$.data.list[*].id: bigint, sample: 1001"), result);
        Assertions.assertTrue(result.contains("$.data.list[*].amount: double"), result);
        Assertions.assertTrue(result.contains("$.data.list[*].status: string, nullable"), result);
        Assertions.assertTrue(result.contains("looks like timestamp yyyy-MM-dd HH:mm:ss"), result);
        Assertions.assertTrue(result.contains("$.data.list[*].tags: array<string> (2 items)"), result);
        // The value of a sensitive key is never sent
        Assertions.assertTrue(result.contains("$.data.list[*].user.token: string, sensitive, secret, value hidden"),
                result);
        Assertions.assertFalse(result.contains("t0ps3cret"), result);
        Assertions.assertTrue(result.contains("content_field = \"$.data.list.*\""), result);
        Assertions.assertTrue(result.contains("  created_at: string"), result);
        Assertions.assertTrue(result.contains("worker"), result);
    }

    @Test
    public void testRawResponseOnlyForTheUser() {
        List<HttpResponseSample> samples = new java.util.ArrayList<>();
        ToolContext context = new ToolContext(new CredentialRedactor(),
                HttpSampleFetcher.allowedHosts(base + "/orders"), samples::add);
        ObjectNode arguments = (ObjectNode) arguments("/orders");
        arguments.putObject("params").put("page", "1");
        String result = fetcher().fetch(arguments, context);
        Assertions.assertEquals(1, samples.size());
        HttpResponseSample sample = samples.get(0);
        Assertions.assertEquals("GET " + base + "/orders", sample.target());
        Assertions.assertEquals(200, sample.status());
        Assertions.assertEquals(ORDERS, sample.body());
        Assertions.assertFalse(sample.truncated());
        // The LLM only gets the fields, not the raw response
        Assertions.assertFalse(result.contains("t0ps3cret"), result);
        Assertions.assertFalse(result.contains("\"msg\":\"ok\""), result);
        Assertions.assertTrue(result.contains("do not reconstruct"), result);

        samples.clear();
        new HttpSampleFetcher(Duration.ofSeconds(1), 100, 3, List.of(), true).fetch(arguments("/big"), context);
        Assertions.assertTrue(samples.get(0).truncated());
        Assertions.assertEquals(100, samples.get(0).body().length());
    }

    @Test
    public void testMinuteTimestamp() throws Exception {
        String result = JsonFieldInferrer.describe(mapper.readTree("{\"time\":\"2026-10-15T07:30\"}"));
        Assertions.assertTrue(result.contains("looks like timestamp yyyy-MM-dd'T'HH:mm,"), result);
    }

    @Test
    public void testCredentialRestoredButNotReturned() {
        CredentialRedactor redactor = new CredentialRedactor();
        String redacted = redactor.redactText("token is Abc.Secret-1");
        String placeholder = redacted.substring("token is ".length());
        ObjectNode arguments = (ObjectNode) arguments("/echo");
        arguments.putObject("headers").put("Authorization", "Bearer " + placeholder);
        ToolContext context = new ToolContext(redactor, HttpSampleFetcher.allowedHosts(base + "/echo"));
        String result = fetcher().fetch(arguments, context);
        // The server got the real token, the result only has the placeholder
        Assertions.assertFalse(result.contains("Abc.Secret-1"), result);
        Assertions.assertTrue(result.contains("HTTP 200"), result);

        fetcher().fetch(arguments("/orders"), context(redactor));
        ObjectNode withHeader = (ObjectNode) arguments("/orders");
        withHeader.putObject("headers").put("Authorization", "Bearer " + placeholder);
        fetcher().fetch(withHeader, context(redactor));
        Assertions.assertEquals("Bearer Abc.Secret-1", receivedAuthorization.get("orders"));
    }

    @Test
    public void testUndefinedParameter() {
        ObjectNode arguments = (ObjectNode) arguments("/orders");
        arguments.putObject("params").put("date", "${system.biz.date}");
        String result = fetcher().fetch(arguments, context(new CredentialRedactor()));
        Assertions.assertTrue(result.startsWith("Not called"), result);
        Assertions.assertTrue(result.contains("${system.biz.date}"), result);
    }

    @Test
    public void testTextErrorAndTruncated() {
        HttpSampleFetcher fetcher = new HttpSampleFetcher(Duration.ofSeconds(1), 100, 10, List.of(), true);
        ToolContext context = context(new CredentialRedactor());
        String text = fetcher.fetch(arguments("/text"), context);
        Assertions.assertTrue(text.contains("Text response, 5 lines"), text);
        Assertions.assertTrue(text.contains("1,a") && !text.contains("3,c"), text);

        String error = fetcher.fetch(arguments("/error"), context);
        Assertions.assertTrue(error.startsWith("HTTP 401"), error);
        Assertions.assertTrue(error.contains("The API returned an error"), error);
        Assertions.assertFalse(error.contains("abc123xyz"), error);

        String big = fetcher.fetch(arguments("/big"), context);
        Assertions.assertTrue(big.contains("(truncated)"), big);
        Assertions.assertTrue(big.contains("not valid JSON"), big);
    }

    @Test
    public void testTimeout() {
        String result = fetcher().fetch(arguments("/slow"), context(new CredentialRedactor()));
        Assertions.assertTrue(result.startsWith("Request failed: no response"), result);
    }

    @Test
    public void testDeniedTargets() {
        HttpSampleFetcher fetcher = fetcher();
        ToolContext context = context(new CredentialRedactor());
        // Not given by the user
        ObjectNode other = mapper.createObjectNode();
        other.put("url", "http://example.com/api");
        Assertions.assertTrue(fetcher.fetch(other, context).contains("is not given by the user"));
        // Redirect to the cloud metadata service
        Assertions.assertTrue(fetcher.fetch(arguments("/redirect"), context).startsWith("Not called"));
        // Loopback and link-local are denied by default even if the user gave them
        HttpSampleFetcher production = new HttpSampleFetcher(Duration.ofSeconds(1), 1024, 3, List.of(), false);
        Set<String> hosts = HttpSampleFetcher.allowedHosts("http://localhost:8080/a http://169.254.169.254/latest");
        Assertions.assertNotNull(production.checkTarget(java.net.URI.create("http://localhost:8080/a"), hosts));
        Assertions.assertNotNull(production.checkTarget(java.net.URI.create("http://169.254.169.254/latest"), hosts));
        // Unsupported method
        ObjectNode delete = (ObjectNode) arguments("/orders");
        delete.put("method", "DELETE");
        Assertions.assertTrue(fetcher.fetch(delete, context).contains("only GET and POST"));
    }

    @Test
    public void testDeniedHostsConfig() throws Exception {
        HttpSampleFetcher fetcher = new HttpSampleFetcher(Duration.ofSeconds(1), 1024, 3, List.of("127.0.0.0/8"),
                true);
        Assertions.assertTrue(fetcher.fetch(arguments("/orders"), context(new CredentialRedactor()))
                .contains("not allowed"));
        InetAddress address = InetAddress.getByName("10.1.2.3");
        Assertions.assertTrue(HttpSampleFetcher.isDenied("10.0.0.0/8", "10.1.2.3", address));
        Assertions.assertFalse(HttpSampleFetcher.isDenied("10.1.3.0/24", "10.1.2.3", address));
        Assertions.assertTrue(HttpSampleFetcher.isDenied("internal.corp", "api.internal.corp", address));
        Assertions.assertFalse(HttpSampleFetcher.isDenied("internal.corp", "notinternal.corp", address));
    }

    @Test
    public void testMaxCalls() {
        HttpSampleFetcher fetcher = new HttpSampleFetcher(Duration.ofSeconds(1), 1024 * 1024, 1, List.of(), true);
        ToolContext context = context(new CredentialRedactor());
        Assertions.assertTrue(fetcher.fetch(arguments("/orders"), context).startsWith("HTTP 200"));
        Assertions.assertTrue(fetcher.fetch(arguments("/orders"), context).startsWith("Not called: at most 1"));
    }

    @Test
    public void testAllowedHostsAndTarget() {
        Assertions.assertEquals(Set.of("api.example.com:443", "10.0.0.5:8080", "[::1]:80"),
                HttpSampleFetcher.allowedHosts("调用 https://API.example.com/v1/orders?x=1，以及 "
                        + "http://user:pw@10.0.0.5:8080/a. 还有 http://[::1]/x"));
        Assertions.assertEquals("GET https://api.example.com/v1/orders",
                HttpSampleFetcher.describeTarget("{\"url\":\"https://u:p@api.example.com/v1/orders?token=1\"}"));
        Assertions.assertEquals("POST http://h/a",
                HttpSampleFetcher.describeTarget("{\"url\":\"http://h/a#x\",\"method\":\"post\"}"));
    }

    @Test
    public void testRootArrayAndMixedTypes() throws Exception {
        String result = JsonFieldInferrer.describe(mapper.readTree(
                "[{\"id\":1,\"v\":\"a\",\"ts\":1760000000},{\"id\":2,\"v\":3}]"));
        Assertions.assertTrue(result.contains("$: array<object> (2 items)"), result);
        Assertions.assertTrue(result.contains("$[*].v: mixed [string, int], use string"), result);
        Assertions.assertTrue(result.contains("$[*].ts: int, nullable, looks like epoch seconds"), result);
        Assertions.assertTrue(result.contains("content_field is not needed"), result);
    }
}
