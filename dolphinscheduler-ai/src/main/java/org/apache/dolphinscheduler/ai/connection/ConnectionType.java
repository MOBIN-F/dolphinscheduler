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

import static org.apache.dolphinscheduler.ai.connection.ConnectionField.number;
import static org.apache.dolphinscheduler.ai.connection.ConnectionField.password;
import static org.apache.dolphinscheduler.ai.connection.ConnectionField.secretText;
import static org.apache.dolphinscheduler.ai.connection.ConnectionField.select;
import static org.apache.dolphinscheduler.ai.connection.ConnectionField.text;
import static org.apache.dolphinscheduler.ai.connection.ConnectionField.textarea;

import java.util.List;
import java.util.Optional;

/**
 * Types of the SeaTunnel connections maintained in the security center.
 */
public enum ConnectionType {

    KAFKA("Kafka", List.of(Role.SOURCE, Role.SINK), "topic", List.of(
            text("bootstrap_servers", "Bootstrap servers", "Bootstrap servers", true, ""),
            text("consumer_group_prefix", "Consumer group prefix (authorized)", "消费者组前缀（已授权）", false, ""),
            select("security_protocol", "Security protocol", "安全协议", "PLAINTEXT",
                    List.of("PLAINTEXT", "SASL_PLAINTEXT", "SASL_SSL", "SSL")),
            select("sasl_mechanism", "SASL mechanism", "SASL 机制", "PLAIN",
                    List.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"))
                            .when("security_protocol", "SASL_PLAINTEXT", "SASL_SSL"),
            text("sasl_username", "SASL username", "SASL 用户名", true, "")
                    .when("security_protocol", "SASL_PLAINTEXT", "SASL_SSL"),
            password("sasl_password", "SASL password", "SASL 密码", true)
                    .when("security_protocol", "SASL_PLAINTEXT", "SASL_SSL"),
            secretText("ssl_ca_pem", "CA certificates (PEM)", "CA 证书（PEM）")
                    .when("security_protocol", "SASL_SSL", "SSL"),
            secretText("ssl_cert_pem", "Client certificate chain (PEM)", "客户端证书链（PEM）")
                    .when("security_protocol", "SSL"),
            secretText("ssl_key_pem", "Client private key (PEM)", "客户端私钥（PEM）")
                    .when("security_protocol", "SSL"),
            password("ssl_key_password", "Private key password", "私钥密码", false)
                    .when("security_protocol", "SSL"))),

    MYSQL("MySQL", List.of(Role.SOURCE, Role.SINK), "table", List.of(
            text("host", "Host", "主机", true, ""),
            number("port", "Port", "端口", "3306"),
            text("database", "Default database", "默认数据库", false, ""),
            text("username", "Username", "用户名", true, ""),
            password("password", "Password", "密码", true),
            text("jdbc_params", "JDBC parameters", "JDBC 参数", false, "useSSL=false&serverTimezone=UTC"))),

    POSTGRESQL("PostgreSQL", List.of(Role.SOURCE, Role.SINK), "table", List.of(
            text("host", "Host", "主机", true, ""),
            number("port", "Port", "端口", "5432"),
            text("database", "Database", "数据库", true, ""),
            text("username", "Username", "用户名", true, ""),
            password("password", "Password", "密码", true),
            text("jdbc_params", "JDBC parameters", "JDBC 参数", false, ""))),

    CLICKHOUSE("ClickHouse", List.of(Role.SOURCE, Role.SINK), "table", List.of(
            text("host", "Host", "主机", true, ""),
            number("port", "HTTP port", "HTTP 端口", "8123"),
            text("database", "Default database", "默认数据库", false, "default"),
            text("username", "Username", "用户名", true, "default"),
            password("password", "Password", "密码", false))),

    HTTP("HTTP", List.of(Role.SOURCE), "path", List.of(
            text("base_url", "Base URL", "基础地址", true, "https://"),
            select("auth_type", "Authentication", "认证方式", "NONE", List.of("NONE", "BEARER", "BASIC")),
            password("token", "Bearer token", "Bearer token", true).when("auth_type", "BEARER"),
            text("basic_username", "Username", "用户名", true, "").when("auth_type", "BASIC"),
            password("basic_password", "Password", "密码", true).when("auth_type", "BASIC"),
            textarea("headers", "Headers (Name: value per line)", "请求头（每行一个 名称: 值）"),
            textarea("paths", "API paths (one per line)", "接口路径（每行一个）"))),

    S3("S3", List.of(Role.SINK), "path", List.of(
            text("region", "Region", "区域", true, "us-east-1"),
            text("endpoint", "Endpoint (optional)", "Endpoint（可选）", false, ""),
            text("bucket", "Bucket", "Bucket", true, ""),
            select("auth_type", "Authentication", "认证方式", "INSTANCE_ROLE",
                    List.of("INSTANCE_ROLE", "ACCESS_KEY")),
            text("access_key", "Access key", "Access key", true, "").when("auth_type", "ACCESS_KEY"),
            password("secret_key", "Secret key", "Secret key", true).when("auth_type", "ACCESS_KEY")));

    /**
     * The side of the job a connection is used for.
     */
    public enum Role {
        SOURCE,
        SINK
    }

    private final String label;
    private final List<Role> roles;
    private final String objectKind;
    private final List<ConnectionField> fields;

    ConnectionType(String label, List<Role> roles, String objectKind, List<ConnectionField> fields) {
        this.label = label;
        this.roles = roles;
        this.objectKind = objectKind;
        this.fields = fields;
    }

    public String label() {
        return label;
    }

    public List<Role> roles() {
        return roles;
    }

    /**
     * @return what the objects of the connection are: topic, table or path
     */
    public String objectKind() {
        return objectKind;
    }

    public List<ConnectionField> fields() {
        return fields;
    }

    public Optional<ConnectionField> field(String key) {
        return fields.stream().filter(field -> field.key().equals(key)).findFirst();
    }

    public static Optional<ConnectionType> of(String name) {
        for (ConnectionType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
