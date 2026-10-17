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

package org.apache.dolphinscheduler.ai.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the AI assistant, prefix {@code ai}.
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /**
     * Whether the AI assistant is enabled.
     */
    private boolean enabled = false;

    /**
     * Max requests per user per minute.
     */
    private int rateLimitPerUserPerMinute = 10;

    private final Llm llm = new Llm();

    private final Seatunnel seatunnel = new Seatunnel();

    private final HttpSample httpSample = new HttpSample();

    private final Connection connection = new Connection();

    private final Sensitive sensitive = new Sensitive();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRateLimitPerUserPerMinute() {
        return rateLimitPerUserPerMinute;
    }

    public void setRateLimitPerUserPerMinute(int rateLimitPerUserPerMinute) {
        this.rateLimitPerUserPerMinute = rateLimitPerUserPerMinute;
    }

    public Llm getLlm() {
        return llm;
    }

    public Seatunnel getSeatunnel() {
        return seatunnel;
    }

    public HttpSample getHttpSample() {
        return httpSample;
    }

    public Connection getConnection() {
        return connection;
    }

    public Sensitive getSensitive() {
        return sensitive;
    }

    /**
     * AWS Bedrock settings.
     */
    public static class Llm {

        private String region = "us-east-1";

        /**
         * Model or inference profile id used to generate configs.
         */
        private String model = "us.anthropic.claude-sonnet-5-5";

        /**
         * Cheaper model used for auxiliary work such as summarizing long conversations.
         */
        private String fastModel = "us.anthropic.claude-haiku-5-5";

        /**
         * Optional static credentials, the AWS default credentials provider chain (instance role, environment
         * variables such as AWS_BEARER_TOKEN_BEDROCK, profile) is used when empty.
         */
        private String accessKeyId = "";

        private String secretAccessKey = "";

        private Duration timeout = Duration.ofSeconds(120);

        private int maxTokens = 8192;

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getFastModel() {
            return fastModel;
        }

        public void setFastModel(String fastModel) {
            this.fastModel = fastModel;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public void setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
        }

        public String getSecretAccessKey() {
            return secretAccessKey;
        }

        public void setSecretAccessKey(String secretAccessKey) {
            this.secretAccessKey = secretAccessKey;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    /**
     * SeaTunnel settings.
     */
    public static class Seatunnel {

        /**
         * SeaTunnel version the generated configs target, should match the connector metadata.
         */
        private String version = "2.3.13";

        /**
         * SeaTunnel installation directory, enables the engine level validation when set.
         */
        private String home = "";

        /**
         * Connector metadata json file, the built-in metadata is used when empty.
         */
        private String metadataPath = "";

        private Duration checkTimeout = Duration.ofSeconds(60);

        /**
         * Max rounds of automatic fixing when the generated config fails the validation.
         */
        private int maxFixAttempts = 3;

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getHome() {
            return home;
        }

        public void setHome(String home) {
            this.home = home;
        }

        public String getMetadataPath() {
            return metadataPath;
        }

        public void setMetadataPath(String metadataPath) {
            this.metadataPath = metadataPath;
        }

        public Duration getCheckTimeout() {
            return checkTimeout;
        }

        public void setCheckTimeout(Duration checkTimeout) {
            this.checkTimeout = checkTimeout;
        }

        public int getMaxFixAttempts() {
            return maxFixAttempts;
        }

        public void setMaxFixAttempts(int maxFixAttempts) {
            this.maxFixAttempts = maxFixAttempts;
        }
    }

    /**
     * Settings of the tool which calls the HTTP API given by the user to get the fields of the response.
     */
    public static class HttpSample {

        private boolean enabled = true;

        private Duration timeout = Duration.ofSeconds(10);

        /**
         * The response is truncated after this size.
         */
        private int maxResponseBytes = 1024 * 1024;

        private int maxCallsPerRequest = 3;

        /**
         * Hosts, IPs or CIDRs which are never called, in addition to the loopback, link-local (e.g. the cloud
         * metadata service), any-local and multicast addresses.
         */
        private List<String> deniedHosts = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        public int getMaxCallsPerRequest() {
            return maxCallsPerRequest;
        }

        public void setMaxCallsPerRequest(int maxCallsPerRequest) {
            this.maxCallsPerRequest = maxCallsPerRequest;
        }

        public List<String> getDeniedHosts() {
            return deniedHosts;
        }

        public void setDeniedHosts(List<String> deniedHosts) {
            this.deniedHosts = deniedHosts;
        }
    }

    /**
     * Settings of the SeaTunnel connections maintained in the security center.
     */
    public static class Connection {

        /**
         * Timeout to connect, list the objects and sample the data of a connection.
         */
        private Duration timeout = Duration.ofSeconds(10);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * Personal data in the sampled messages and responses: the LLM only gets the format, the user sees it masked.
     */
    public static class Sensitive {

        /**
         * Field name keywords in addition to the built-in ones (phone, email, id_card, name, age, address, ...).
         */
        private List<String> fieldKeywords = new ArrayList<>();

        public List<String> getFieldKeywords() {
            return fieldKeywords;
        }

        public void setFieldKeywords(List<String> fieldKeywords) {
            this.fieldKeywords = fieldKeywords;
        }
    }
}
