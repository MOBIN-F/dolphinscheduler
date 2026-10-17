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

import org.apache.dolphinscheduler.ai.connection.BrowseNode;
import org.apache.dolphinscheduler.ai.connection.ConnectionToolBackend;
import org.apache.dolphinscheduler.ai.connection.ConnectionType;
import org.apache.dolphinscheduler.ai.connection.DescribedField;
import org.apache.dolphinscheduler.ai.connection.ObjectDescription;
import org.apache.dolphinscheduler.ai.connection.SeatunnelConnection;
import org.apache.dolphinscheduler.ai.security.CredentialRedactor;
import org.apache.dolphinscheduler.ai.security.HostGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class ConnectionToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Registered: kafka_dev with a consumer group prefix. Records the inline connections it is asked to describe.
     */
    static class FakeBackend implements ConnectionToolBackend {

        final List<SeatunnelConnection> inline = new ArrayList<>();
        final SeatunnelConnection kafka = new SeatunnelConnection("kafka_dev", "Kafka dev", ConnectionType.KAFKA,
                Map.of("bootstrap_servers", "b:9093", "security_protocol", "SASL_SSL", "sasl_mechanism", "PLAIN",
                        "sasl_username", "unused", "consumer_group_prefix", "team-a"),
                Map.of("sasl_password", "******"));

        private ObjectDescription orders() {
            return new ObjectDescription("orders", "json", List.of(
                    new DescribedField("id", "int", false, false, "1", ""),
                    new DescribedField("phone", "string", false, false, null, "sensitive, mobile number")),
                    null, "", List.of("{\"id\":1,\"phone\":\"138****5678\"}"));
        }

        public List<SeatunnelConnection> list() {
            return List.of(kafka);
        }

        public Optional<SeatunnelConnection> registered(String key) {
            if (!key.equals("kafka_dev")) {
                throw new IllegalArgumentException("No permission to use the SeaTunnel connection " + key);
            }
            return Optional.of(kafka);
        }

        public List<BrowseNode> browse(String key, String parent) {
            return List.of();
        }

        public ObjectDescription describe(String key, String object) {
            return orders();
        }

        public List<BrowseNode> browse(SeatunnelConnection connection, String parent) {
            return List.of(new BrowseNode("shop", "shop", false));
        }

        public ObjectDescription describe(SeatunnelConnection connection, String object) {
            inline.add(connection);
            return orders();
        }
    }

    private ToolContext context(FakeBackend backend, CredentialRedactor redactor, List<DataSample> samples,
                                String userText) {
        return new ToolContext(redactor, Set.of(), sample -> {
        }).withConnections(backend, HostGuard.hostNames(userText), new HostGuard(List.of(), true), samples::add,
                123L);
    }

    @Test
    public void testDescribeRegistered() throws Exception {
        FakeBackend backend = new FakeBackend();
        List<DataSample> samples = new ArrayList<>();
        ToolContext context = context(backend, new CredentialRedactor(), samples, "");
        ConnectionTools tools = new ConnectionTools();
        String list = tools.execute(ConnectionTools.LIST_CONNECTIONS, mapper.readTree("{}"), context);
        Assertions.assertTrue(list.contains("key `kafka_dev`: Kafka \"Kafka dev\", source/sink"), list);
        Assertions.assertTrue(list.contains("bootstrap_servers=b:9093"), list);
        Assertions.assertFalse(list.contains("sasl_password"), list);

        String result = tools.execute(ConnectionTools.DESCRIBE_OBJECT,
                mapper.readTree("{\"connection\":{\"key\":\"kafka_dev\"},\"object\":\"orders\"}"), context);
        Assertions.assertTrue(result.contains("- phone: string, sensitive, mobile number"), result);
        Assertions.assertTrue(result.contains("password=\\\"${stconn.kafka_dev.sasl_password}\\\""), result);
        Assertions.assertTrue(result.contains("consumer.group = \"team-a-ds-123\""), result);
        Assertions.assertFalse(result.contains("138"), result);
        // The samples go to the user, the used connection is checked in the config
        Assertions.assertEquals("kafka_dev / orders", samples.get(0).title());
        Assertions.assertEquals(1, context.usedConnections().size());
        Assertions.assertEquals("team-a-ds-123", context.usedConnections().get(0).consumerGroup());

        Assertions.assertTrue(tools.execute(ConnectionTools.DESCRIBE_OBJECT,
                mapper.readTree("{\"connection\":{\"key\":\"other\"},\"object\":\"t\"}"), context)
                .startsWith("Failed: No permission"));
        Assertions.assertTrue(tools.execute(ConnectionTools.BROWSE_CONNECTION,
                mapper.readTree("{\"connection\":{\"key\":\"kafka_dev\"}}"), context)
                .contains("lack the list permission"));
    }

    @Test
    public void testDescribeInline() throws Exception {
        FakeBackend backend = new FakeBackend();
        CredentialRedactor redactor = new CredentialRedactor();
        String userText = redactor.redactText("kafka 在 127.0.0.1:39092，密码是 Kafk@Pass1");
        String placeholder = userText.substring(userText.indexOf("${_CRED_"), userText.indexOf("_}") + 2);
        ToolContext context = context(backend, redactor, new ArrayList<>(), userText);
        String arguments = "{\"connection\":{\"type\":\"KAFKA\",\"params\":{\"bootstrap_servers\":"
                + "\"127.0.0.1:39092\",\"security_protocol\":\"SASL_PLAINTEXT\",\"sasl_username\":\"u\","
                + "\"sasl_password\":\"" + placeholder + "\"}},\"object\":\"orders\"}";
        String result = new ConnectionTools().execute(ConnectionTools.DESCRIBE_OBJECT, mapper.readTree(arguments),
                context);
        Assertions.assertTrue(result.contains("not registered"), result);
        // The backend got the real password, the LLM did not
        Assertions.assertEquals("Kafk@Pass1", backend.inline.get(0).secret("sasl_password"));
        Assertions.assertFalse(result.contains("Kafk@Pass1"), result);
        Assertions.assertEquals(List.of("127.0.0.1"), ConnectionTools.hosts(backend.inline.get(0)));

        // A host the user did not give, and the loopback address in production
        String madeUp = arguments.replace("127.0.0.1:39092", "evil.example:9092");
        Assertions.assertTrue(new ConnectionTools().execute(ConnectionTools.DESCRIBE_OBJECT, mapper.readTree(madeUp),
                context).contains("not given by the user"));
        ToolContext production = new ToolContext(redactor, Set.of(), sample -> {
        }).withConnections(backend, HostGuard.hostNames(userText), new HostGuard(List.of(), false), sample -> {
        }, null);
        Assertions.assertTrue(new ConnectionTools().execute(ConnectionTools.DESCRIBE_OBJECT,
                mapper.readTree(arguments), production).contains("not allowed"));
        // An undefined parameter
        Assertions.assertTrue(new ConnectionTools().execute(ConnectionTools.DESCRIBE_OBJECT,
                mapper.readTree(arguments.replace(placeholder, "${kafka_password}")), context)
                .contains("undefined parameters"));
    }

    @Test
    public void testMaxDescribeCalls() throws Exception {
        ToolContext context = context(new FakeBackend(), new CredentialRedactor(), new ArrayList<>(), "");
        String arguments = "{\"connection\":{\"key\":\"kafka_dev\"},\"object\":\"orders\"}";
        for (int i = 0; i < ConnectionTools.MAX_DESCRIBE_CALLS; i++) {
            new ConnectionTools().execute(ConnectionTools.DESCRIBE_OBJECT, mapper.readTree(arguments), context);
        }
        Assertions.assertTrue(new ConnectionTools().execute(ConnectionTools.DESCRIBE_OBJECT,
                mapper.readTree(arguments), context).startsWith("Not called"));
        Assertions.assertEquals("kafka_dev / orders",
                ConnectionTools.describeTarget(mapper.readTree(arguments)));
        Assertions.assertEquals("MySQL 10.0.0.5 / shop.orders", ConnectionTools.describeTarget(mapper.readTree(
                "{\"connection\":{\"type\":\"MYSQL\",\"params\":{\"host\":\"10.0.0.5\","
                        + "\"password\":\"x\"}},\"object\":\"shop.orders\"}")));
    }
}
