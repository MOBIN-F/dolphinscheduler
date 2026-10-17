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

import org.apache.dolphinscheduler.ai.seatunnel.agent.JsonFieldInferrer;
import org.apache.dolphinscheduler.ai.security.SensitiveData;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Lists the topics of a Kafka connection and samples the latest messages of a topic to infer the fields.
 */
public class KafkaConnectionBrowser implements ConnectionBrowser {

    static final int MAX_SAMPLES = 5;
    static final int MAX_SAMPLE_CHARS = 4000;

    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    public KafkaConnectionBrowser(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.KAFKA;
    }

    @Override
    public void test(SeatunnelConnection connection) {
        try {
            listTopics(connection);
        } catch (Exception e) {
            throw new IllegalStateException(explain(e, connection, null), e);
        }
    }

    @Override
    public List<BrowseNode> list(SeatunnelConnection connection, String parent) {
        try {
            return listTopics(connection).stream().map(topic -> new BrowseNode(topic, topic, true))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new IllegalStateException(explain(e, connection, null)
                    + ". The topic name can still be typed when the user may read the topic", e);
        }
    }

    private List<String> listTopics(SeatunnelConnection connection) throws Exception {
        try (Admin admin = Admin.create(properties(connection))) {
            return admin.listTopics(new ListTopicsOptions().listInternal(false)
                    .timeoutMs((int) timeout.toMillis()))
                    .names().get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .stream().sorted().collect(Collectors.toList());
        }
    }

    @Override
    public ObjectDescription describe(SeatunnelConnection connection, String topic) {
        List<String> messages;
        try {
            messages = latestMessages(connection, topic);
        } catch (Exception e) {
            throw new IllegalStateException(explain(e, connection, topic), e);
        }
        if (messages.isEmpty()) {
            return new ObjectDescription(topic, "unknown", List.of(), null,
                    "no message found in the topic, ask the user for the message format and fields", List.of());
        }
        ArrayNode records = mapper.createArrayNode();
        for (String message : messages) {
            try {
                JsonNode node = mapper.readTree(message);
                if (node != null && node.isObject()) {
                    records.add(node);
                }
            } catch (Exception e) {
                // Not JSON
            }
        }
        // Shown to the user only, with the personal data masked
        List<String> samples = messages.stream()
                .map(SensitiveData::maskSample)
                .map(message -> message.length() > MAX_SAMPLE_CHARS ? message.substring(0, MAX_SAMPLE_CHARS) + "..."
                        : message)
                .collect(Collectors.toList());
        if (records.isEmpty()) {
            return new ObjectDescription(topic, "text", List.of(), null,
                    "the latest " + messages.size() + " messages are not JSON objects, use format = \"text\" or ask "
                            + "the user for the format",
                    samples);
        }
        JsonFieldInferrer.RecordSchema schema = JsonFieldInferrer.records(records);
        String note = records.size() < messages.size()
                ? (messages.size() - records.size()) + " of the latest " + messages.size() + " messages are not JSON "
                        + "objects"
                : "inferred from the latest " + messages.size() + " messages";
        return new ObjectDescription(topic, "json", schema.fields(), null, note, samples);
    }

    /**
     * @return the values of the latest messages of all partitions, the newest first
     */
    private List<String> latestMessages(SeatunnelConnection connection, String topic) {
        Properties properties = properties(connection);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(MAX_SAMPLES * 4));
        // A cluster authorizing the consumer groups by prefix: the prefix with a suffix, the offsets are not committed
        String prefix = KafkaConsumerGroups.prefix(connection);
        if (!prefix.isEmpty()) {
            properties.put(ConsumerConfig.GROUP_ID_CONFIG,
                    KafkaConsumerGroups.join(prefix, KafkaConsumerGroups.SAMPLE_SUFFIX));
        }
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic, timeout);
            if (partitionInfos == null || partitionInfos.isEmpty()) {
                throw new IllegalArgumentException("Topic " + topic + " not found");
            }
            List<TopicPartition> partitions = partitionInfos.stream()
                    .map(info -> new TopicPartition(topic, info.partition())).collect(Collectors.toList());
            // No consumer group, nothing is committed
            consumer.assign(partitions);
            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions, timeout);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, timeout);
            boolean empty = true;
            for (TopicPartition partition : partitions) {
                long start = Math.max(beginning.get(partition), end.get(partition) - MAX_SAMPLES);
                consumer.seek(partition, start);
                empty &= start >= end.get(partition);
            }
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (!empty && System.currentTimeMillis() < deadline && !reachedEnd(consumer, partitions, end)) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records.stream()
                    .filter(record -> record.value() != null)
                    .sorted(Comparator.comparingLong((ConsumerRecord<byte[], byte[]> record) -> record.timestamp())
                            .reversed())
                    .limit(MAX_SAMPLES)
                    .map(record -> new String(record.value(), StandardCharsets.UTF_8))
                    .collect(Collectors.toList());
        }
    }

    /**
     * @return the reason of a failure the user can act on, e.g. the wrong security protocol
     */
    String explain(Throwable error, SeatunnelConnection connection, String topic) {
        String protocol = connection.param("security_protocol");
        String servers = connection.param("bootstrap_servers");
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SaslAuthenticationException) {
                return "Kafka authentication failed, check the SASL mechanism, username and password ("
                        + cause.getMessage() + ")";
            }
            if (cause instanceof SslAuthenticationException) {
                return "TLS handshake with Kafka failed, check the security protocol and the CA certificate ("
                        + cause.getMessage() + ")";
            }
            if (cause instanceof TopicAuthorizationException) {
                return "No permission to read the topic " + (topic == null ? "" : topic)
                        + ", the Kafka account needs the Read and Describe permissions of the topic";
            }
            if (cause instanceof GroupAuthorizationException) {
                return "No permission for the consumer group, set the authorized consumer group prefix of the "
                        + "connection (" + cause.getMessage() + ")";
            }
            if (cause instanceof ClusterAuthorizationException) {
                return "No permission to list the topics of the Kafka cluster";
            }
            if (cause instanceof UnknownTopicOrPartitionException
                    || (cause instanceof IllegalArgumentException && cause.getMessage() != null
                            && cause.getMessage().startsWith("Topic "))) {
                return "Topic " + (topic == null ? "" : topic) + " not found in the Kafka cluster";
            }
            if (cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof org.apache.kafka.common.errors.TimeoutException) {
                String hint = protocol.endsWith("SSL") ? ""
                        : "; if the brokers require TLS (often port 9093, e.g. a cloud Kafka), the security "
                                + "protocol should be SASL_SSL or SSL instead of " + protocol;
                return "No response from Kafka " + servers + " within " + timeout.getSeconds() + "s"
                        + ", check the network from the api server to the brokers" + hint;
            }
        }
        return "Failed to access Kafka " + servers + ": " + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : " " + error.getMessage());
    }

    private static boolean reachedEnd(KafkaConsumer<byte[], byte[]> consumer, List<TopicPartition> partitions,
                                      Map<TopicPartition, Long> end) {
        return partitions.stream().allMatch(partition -> consumer.position(partition) >= end.get(partition));
    }

    /**
     * @return the client properties of the connection with the real secrets
     */
    Properties properties(SeatunnelConnection connection) {
        Properties properties = new Properties();
        properties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, connection.param("bootstrap_servers"));
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, String.valueOf(timeout.toMillis()));
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, String.valueOf(timeout.toMillis()));
        properties.put(CommonClientConfigs.CLIENT_ID_CONFIG, "dolphinscheduler-ai-assistant");
        String protocol = connection.param("security_protocol");
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
        if (protocol.startsWith("SASL")) {
            String mechanism = connection.param("sasl_mechanism");
            properties.put(SaslConfigs.SASL_MECHANISM, mechanism);
            properties.put(SaslConfigs.SASL_JAAS_CONFIG, (mechanism.startsWith("SCRAM")
                    ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                    : "org.apache.kafka.common.security.plain.PlainLoginModule")
                    + " required username=\"" + connection.param("sasl_username") + "\" password=\""
                    + connection.secret("sasl_password") + "\";");
        }
        if (connection.hasSecret("ssl_ca_pem")) {
            properties.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "PEM");
            properties.put(SslConfigs.SSL_TRUSTSTORE_CERTIFICATES_CONFIG, connection.secret("ssl_ca_pem"));
        }
        if (connection.hasSecret("ssl_cert_pem") && connection.hasSecret("ssl_key_pem")) {
            properties.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "PEM");
            properties.put(SslConfigs.SSL_KEYSTORE_CERTIFICATE_CHAIN_CONFIG, connection.secret("ssl_cert_pem"));
            properties.put(SslConfigs.SSL_KEYSTORE_KEY_CONFIG, connection.secret("ssl_key_pem"));
            if (connection.hasSecret("ssl_key_password")) {
                properties.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, connection.secret("ssl_key_password"));
            }
        }
        return properties;
    }
}
