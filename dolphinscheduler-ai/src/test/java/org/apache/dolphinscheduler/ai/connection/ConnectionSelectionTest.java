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

import org.apache.dolphinscheduler.ai.connection.ConnectionType.Role;
import org.apache.dolphinscheduler.ai.seatunnel.agent.JsonFieldInferrer;
import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository;
import org.apache.dolphinscheduler.ai.seatunnel.validation.HoconValidator;
import org.apache.dolphinscheduler.ai.seatunnel.validation.ValidationIssue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class ConnectionSelectionTest {

    private static final String MASK = "******";

    @Test
    public void testKafkaSaslSslOptions() {
        SeatunnelConnection kafka = new SeatunnelConnection("kafka_prod", "Kafka prod", ConnectionType.KAFKA,
                Map.of("bootstrap_servers", "b1:9093,b2:9093", "security_protocol", "SASL_SSL",
                        "sasl_mechanism", "SCRAM-SHA-512", "sasl_username", "etl"),
                Map.of("sasl_password", MASK, "ssl_ca_pem", MASK));
        ConnectionSelection selection = new ConnectionSelection(Role.SOURCE, kafka, List.of("orders", "refunds"),
                null, null, null, List.of());
        String prompt = selection.toPrompt();
        Assertions.assertTrue(prompt.contains("Kafka {"), prompt);
        Assertions.assertTrue(prompt.contains("bootstrap.servers = \"b1:9093,b2:9093\""), prompt);
        Assertions.assertTrue(prompt.contains("topic = \"orders,refunds\""), prompt);
        Assertions.assertTrue(prompt.contains("sasl.jaas.config = \"org.apache.kafka.common.security.scram."
                + "ScramLoginModule required username=\\\"etl\\\" password=\\\"${stconn.kafka_prod.sasl_password}"
                + "\\\";\""), prompt);
        Assertions.assertTrue(prompt.contains("ssl.truststore.certificates = \"${stconn.kafka_prod.ssl_ca_pem}\""),
                prompt);
        Assertions.assertFalse(prompt.contains(MASK), prompt);
        // No client certificate configured
        Assertions.assertFalse(prompt.contains("ssl.keystore"), prompt);
    }

    @Test
    public void testJdbcSinkWithNewTableIsValid() {
        SeatunnelConnection pg = new SeatunnelConnection("pg_dw", "PG dw", ConnectionType.POSTGRESQL,
                Map.of("host", "pg", "port", "5432", "database", "dw", "username", "etl"), Map.of("password", MASK));
        ConnectionSelection selection = new ConnectionSelection(Role.SINK, pg, List.of(), "public.orders", null,
                null, List.of());
        String prompt = selection.toPrompt();
        Assertions.assertTrue(prompt.contains("url = \"jdbc:postgresql://pg:5432/dw\""), prompt);
        Assertions.assertTrue(prompt.contains("password = \"${stconn.pg_dw.password}\""), prompt);
        Assertions.assertTrue(prompt.contains("CREATE_SCHEMA_WHEN_NOT_EXIST"), prompt);

        // The rendered block passes the local validation once the job options are added
        String config = "env { job.mode = \"BATCH\" }\n"
                + "source { FakeSource { plugin_output = \"t\", schema = { fields { id = \"bigint\" } } } }\n"
                + "sink { Jdbc {\n" + String.join("\n", selection.options()) + "\n"
                + "plugin_input = \"t\"\ngenerate_sink_sql = true\ndatabase = \"dw\"\ntable = \"public.orders\"\n} }";
        ConnectorMetadataRepository repository = ConnectorMetadataRepository.load("", "2.3.13");
        Assertions.assertTrue(new HoconValidator(repository).validate(config).valid(),
                new HoconValidator(repository).validate(config).describe());
    }

    @Test
    public void testMysqlDatabaseFromTheTable() {
        SeatunnelConnection mysql = new SeatunnelConnection("mysql_shop", "MySQL", ConnectionType.MYSQL,
                Map.of("host", "m", "port", "3306", "username", "u", "jdbc_params", "useSSL=false"),
                Map.of("password", MASK));
        String prompt = new ConnectionSelection(Role.SOURCE, mysql, List.of("shop.orders"), null, null, null,
                List.of(new ObjectDescription("shop.orders", "table", List.of(
                        new DescribedField("id", "BIGINT", false, true, "", ""),
                        new DescribedField("amount", "DECIMAL(10,2)", true, false, "", "order amount")),
                        null, "", List.of("raw"))))
                                .toPrompt();
        Assertions.assertTrue(prompt.contains("url = \"jdbc:mysql://m:3306/shop?useSSL=false\""), prompt);
        Assertions.assertTrue(prompt.contains("- id: BIGINT, primary key"), prompt);
        Assertions.assertTrue(prompt.contains("- amount: DECIMAL(10,2), nullable, order amount"), prompt);
        // The raw samples are not in the prompt
        Assertions.assertFalse(prompt.contains("raw"), prompt);
    }

    @Test
    public void testHttpAndS3Options() {
        SeatunnelConnection http = new SeatunnelConnection("api", "API", ConnectionType.HTTP,
                Map.of("base_url", "https://api.example.com/", "auth_type", "BASIC", "basic_username", "u",
                        "headers", "X-Tenant: shop\nbad line"),
                Map.of("basic_password", MASK));
        String httpPrompt = new ConnectionSelection(Role.SOURCE, http, List.of("/v1/orders"), null, null, null,
                List.of()).toPrompt();
        Assertions.assertTrue(httpPrompt.contains("url = \"https://api.example.com/v1/orders\""), httpPrompt);
        Assertions.assertTrue(httpPrompt.contains("Authorization = \"Basic ${stconn.api.basic_auth}\""), httpPrompt);
        Assertions.assertTrue(httpPrompt.contains("\"X-Tenant\" = \"shop\""), httpPrompt);

        SeatunnelConnection s3 = new SeatunnelConnection("lake", "Lake", ConnectionType.S3,
                Map.of("region", "us-east-1", "bucket", "my-lake", "auth_type", "INSTANCE_ROLE"), Map.of());
        String s3Prompt = new ConnectionSelection(Role.SINK, s3, List.of(), null, "warehouse/orders", "PARQUET",
                List.of()).toPrompt();
        Assertions.assertTrue(s3Prompt.contains("bucket = \"s3a://my-lake\""), s3Prompt);
        Assertions.assertTrue(s3Prompt.contains("path = \"/warehouse/orders\""), s3Prompt);
        Assertions.assertTrue(s3Prompt.contains("fs.s3a.endpoint = \"s3.us-east-1.amazonaws.com\""), s3Prompt);
        Assertions.assertTrue(s3Prompt.contains("InstanceProfileCredentialsProvider"), s3Prompt);
        Assertions.assertTrue(s3Prompt.contains("file_format_type = \"parquet\""), s3Prompt);
        Assertions.assertFalse(s3Prompt.contains("secret_key"), s3Prompt);
    }

    @Test
    public void testKafkaClientProperties() {
        SeatunnelConnection kafka = new SeatunnelConnection("k", "K", ConnectionType.KAFKA,
                Map.of("bootstrap_servers", "b:9093", "security_protocol", "SSL"),
                Map.of("ssl_ca_pem", "CA", "ssl_cert_pem", "CERT", "ssl_key_pem", "KEY"));
        Properties properties = new KafkaConnectionBrowser(Duration.ofSeconds(5)).properties(kafka);
        Assertions.assertEquals("SSL", properties.get("security.protocol"));
        Assertions.assertEquals("PEM", properties.get("ssl.truststore.type"));
        Assertions.assertEquals("CA", properties.get("ssl.truststore.certificates"));
        Assertions.assertEquals("CERT", properties.get("ssl.keystore.certificate.chain"));
        Assertions.assertNull(properties.get("sasl.jaas.config"));
    }

    private static SeatunnelConnection kafkaWithPrefix(String prefix) {
        return new SeatunnelConnection("kafka_dev", "Kafka dev", ConnectionType.KAFKA,
                Map.of("bootstrap_servers", "b:9093", "security_protocol", "PLAINTEXT", "consumer_group_prefix",
                        prefix),
                Map.of());
    }

    @Test
    public void testConsumerGroupNames() {
        Assertions.assertEquals("team-a-ds-123", KafkaConsumerGroups.ofTask("team-a", 123L));
        Assertions.assertEquals("team-a-ds-123", KafkaConsumerGroups.ofTask("team-a-", 123L));
        Assertions.assertEquals("team.a.ds-123", KafkaConsumerGroups.ofTask("team.a.", 123L));
        Assertions.assertTrue(KafkaConsumerGroups.isValid("team-a", "team-a-orders"));
        Assertions.assertFalse(KafkaConsumerGroups.isValid("team-a", "team-a"));
        Assertions.assertFalse(KafkaConsumerGroups.isValid("team-a", "team-a-"));
        Assertions.assertFalse(KafkaConsumerGroups.isValid("team-a", "other-orders"));
        Assertions.assertFalse(KafkaConsumerGroups.isValid("team-a", null));
    }

    @Test
    public void testConsumerGroupRenderedAndChecked() {
        ConnectionSelection source = new ConnectionSelection(Role.SOURCE, kafkaWithPrefix("team-a"),
                List.of("orders"), null, null, null, List.of(), "team-a-ds-123");
        String prompt = source.toPrompt();
        Assertions.assertTrue(prompt.contains("consumer.group = \"team-a-ds-123\""), prompt);
        Assertions.assertTrue(prompt.contains("use exactly \"team-a-ds-123\""), prompt);

        String block = "source { Kafka { bootstrap.servers = \"b:9093\", topic = \"orders\", %s } }\n"
                + "sink { Console {} }";
        List<ConnectionSelection> selections = List.of(source);
        Assertions.assertTrue(ConnectionConfigChecker.check(String.format(block,
                "consumer.group = \"team-a-ds-123\""), selections).isEmpty());
        // Missing, the prefix alone, another prefix
        Assertions.assertTrue(ConnectionConfigChecker.check(String.format(block, ""), selections).get(0)
                .message().contains("consumer.group is required"));
        Assertions.assertTrue(ConnectionConfigChecker.check(String.format(block, "consumer.group = \"team-a\""),
                selections).get(0).message().contains("not the prefix alone"));
        Assertions.assertEquals(ValidationIssue.Level.ERROR, ConnectionConfigChecker.check(String.format(block,
                "consumer.group = \"other-x\""), selections).get(0).level());
        // Another suffix is allowed but not the group of the task
        Assertions.assertEquals(ValidationIssue.Level.WARNING, ConnectionConfigChecker.check(String.format(block,
                "consumer.group = \"team-a-other\""), selections).get(0).level());
        // Another Kafka cluster in the config is not checked
        Assertions.assertTrue(ConnectionConfigChecker.check("source { Kafka { bootstrap.servers = \"x:9092\" } }",
                selections).isEmpty());

        // Without the task code, the LLM adds a suffix and the prefix alone is still rejected
        ConnectionSelection external = new ConnectionSelection(Role.SOURCE, kafkaWithPrefix("team-a"),
                List.of("orders"), null, null, null, List.of());
        Assertions.assertFalse(external.toPrompt().contains("consumer.group ="), external.toPrompt());
        Assertions.assertTrue(external.toPrompt().contains("never the prefix alone"), external.toPrompt());
        Assertions.assertTrue(ConnectionConfigChecker.check(String.format(block, "consumer.group = \"team-a-x\""),
                List.of(external)).isEmpty());
        // No prefix: nothing is required
        ConnectionSelection open = new ConnectionSelection(Role.SOURCE, kafkaWithPrefix(""), List.of("orders"), null,
                null, null, List.of());
        Assertions.assertTrue(ConnectionConfigChecker.check(String.format(block, ""), List.of(open)).isEmpty());
    }

    @Test
    public void testSampleConsumerGroup() {
        Properties properties = new KafkaConnectionBrowser(Duration.ofSeconds(5)).properties(kafkaWithPrefix("team-a"));
        // The admin client has no group, the consumer adds it when sampling
        Assertions.assertNull(properties.get("group.id"));
        Assertions.assertEquals("team-a-dolphinscheduler-ai-sample",
                KafkaConsumerGroups.join("team-a", KafkaConsumerGroups.SAMPLE_SUFFIX));
    }

    @Test
    public void testKafkaErrorsExplained() {
        KafkaConnectionBrowser browser = new KafkaConnectionBrowser(Duration.ofSeconds(10));
        SeatunnelConnection plain = new SeatunnelConnection("k", "K", ConnectionType.KAFKA,
                Map.of("bootstrap_servers", "kafka-1.example.com:9093", "security_protocol", "SASL_PLAINTEXT"), Map.of());
        String timeout = browser.explain(new java.util.concurrent.ExecutionException(
                new org.apache.kafka.common.errors.TimeoutException("Timed out")), plain, "orders");
        Assertions.assertTrue(timeout.contains("within 10s") && timeout.contains("SASL_SSL"), timeout);
        Assertions.assertTrue(browser.explain(new org.apache.kafka.common.errors.TopicAuthorizationException(
                "denied"), plain, "orders").contains("No permission to read the topic orders"));
        Assertions.assertTrue(browser.explain(new org.apache.kafka.common.errors.GroupAuthorizationException(
                "denied"), plain, "orders").contains("consumer group prefix"));
        Assertions.assertTrue(browser.explain(new org.apache.kafka.common.errors.SaslAuthenticationException(
                "bad"), plain, null).contains("username and password"));
        Assertions.assertTrue(browser.explain(new IllegalArgumentException("Topic orders not found"), plain,
                "orders").contains("not found"));
    }

    @Test
    public void testSensitiveFieldsHiddenFromTheLlm() throws Exception {
        JsonFieldInferrer.RecordSchema schema = JsonFieldInferrer.records(new ObjectMapper().readTree(
                "[{\"id\":1,\"age\":35,\"phone\":\"13812345678\",\"contact\":\"amy@example.com\","
                        + "\"status\":\"PAID\"}]"));
        String prompt = new ObjectDescription("users", "json", schema.fields(), null, "", List.of()).toPrompt();
        Assertions.assertTrue(prompt.contains("- age: int, sensitive, value hidden"), prompt);
        Assertions.assertTrue(prompt.contains("- phone: string, sensitive, mobile number"), prompt);
        Assertions.assertTrue(prompt.contains("- contact: string, sensitive, email address"), prompt);
        Assertions.assertTrue(prompt.contains("sample: \"PAID\""), prompt);
        for (String value : List.of("35", "13812345678", "amy@example.com")) {
            Assertions.assertFalse(prompt.contains(value), value + " in " + prompt);
        }
    }

    @Test
    public void testFieldActivation() {
        ConnectionField password = ConnectionType.KAFKA.field("sasl_password").orElseThrow();
        Assertions.assertFalse(password.isActive(Map.of("security_protocol", "PLAINTEXT")));
        Assertions.assertTrue(password.isActive(Map.of("security_protocol", "SASL_SSL")));
        Assertions.assertTrue(ConnectionType.KAFKA.field("bootstrap_servers").orElseThrow().isActive(Map.of()));
    }

    @Test
    public void testRecordsOfMessages() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonFieldInferrer.RecordSchema messages = JsonFieldInferrer.records(mapper.readTree(
                "[{\"id\":1,\"user\":{\"name\":\"a\"}},{\"id\":2,\"user\":{\"name\":\"b\"},\"tag\":\"x\"}]"));
        Assertions.assertEquals("$", messages.recordPath());
        Assertions.assertEquals(List.of("id", "user", "user.name", "tag"),
                messages.fields().stream().map(DescribedField::name).collect(java.util.stream.Collectors.toList()));
        Assertions.assertTrue(messages.fields().get(3).nullable());
        JsonFieldInferrer.RecordSchema nested = JsonFieldInferrer.records(mapper.readTree(
                "{\"code\":0,\"data\":{\"list\":[{\"id\":1},{\"id\":2}]}}"));
        Assertions.assertEquals("$.data.list", nested.recordPath());
        Assertions.assertEquals("id", nested.fields().get(0).name());
        JsonFieldInferrer.RecordSchema single = JsonFieldInferrer.records(mapper.readTree("{\"a\":1}"));
        Assertions.assertNull(single.recordPath());
        Assertions.assertEquals("a", single.fields().get(0).name());
    }
}
