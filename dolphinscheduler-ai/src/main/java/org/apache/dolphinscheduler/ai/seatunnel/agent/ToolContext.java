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

import org.apache.dolphinscheduler.ai.connection.ConnectionSelection;
import org.apache.dolphinscheduler.ai.connection.ConnectionToolBackend;
import org.apache.dolphinscheduler.ai.security.CredentialRedactor;
import org.apache.dolphinscheduler.ai.security.HostGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * State of the tools within one chat request.
 */
public class ToolContext {

    private final CredentialRedactor redactor;
    private final Set<String> allowedHosts;
    private final Consumer<HttpResponseSample> responseListener;
    private final Map<String, String> references;
    private int httpCalls;
    private int describeCalls;
    private ConnectionToolBackend connectionBackend;
    private Set<String> allowedHostNames = Set.of();
    private HostGuard hostGuard;
    private Consumer<DataSample> sampleListener = sample -> {
    };
    private Long taskCode;
    private final List<ConnectionSelection> usedConnections = new ArrayList<>();
    private StepReport stepReport;

    /**
     * @param redactor     the redactor of the request, restores the credentials in the tool arguments
     * @param allowedHosts {@code host:port} the user gave in the conversation or the script, the only hosts the
     *                     HTTP tool may call
     */
    public ToolContext(CredentialRedactor redactor, Set<String> allowedHosts) {
        this(redactor, allowedHosts, sample -> {
        });
    }

    /**
     * @param responseListener receives the raw responses of the HTTP calls, which are shown to the user only
     */
    public ToolContext(CredentialRedactor redactor, Set<String> allowedHosts,
                       Consumer<HttpResponseSample> responseListener) {
        this(redactor, allowedHosts, responseListener, Map.of());
    }

    /**
     * @param references the secrets of the selected connections by their references such as
     *                   {@code ${stconn.api.token}}, resolved in the HTTP requests of the tools only: the script keeps
     *                   the references
     */
    public ToolContext(CredentialRedactor redactor, Set<String> allowedHosts,
                       Consumer<HttpResponseSample> responseListener, Map<String, String> references) {
        this.redactor = redactor;
        this.allowedHosts = allowedHosts;
        this.responseListener = responseListener;
        this.references = references;
    }

    /**
     * @return the text with the credential placeholders and the connection references replaced by the real values
     */
    String resolve(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (Map.Entry<String, String> reference : references.entrySet()) {
            result = result.replace(reference.getKey(), reference.getValue());
        }
        return redactor.restore(result);
    }

    /**
     * @return the text with the real values replaced by the placeholders and references again
     */
    String hide(String text) {
        String result = redactor.redactKnown(text);
        for (Map.Entry<String, String> reference : references.entrySet()) {
            if (!reference.getValue().isEmpty()) {
                result = result.replace(reference.getValue(), reference.getKey());
            }
        }
        return result;
    }

    public CredentialRedactor redactor() {
        return redactor;
    }

    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    void onHttpResponse(HttpResponseSample sample) {
        responseListener.accept(sample);
    }

    /**
     * Enable the connection tools of the request.
     *
     * @param backend          the connections of the user of the request
     * @param allowedHostNames the host names the user wrote, the only ones an inline connection may use
     * @param sampleListener   receives the sampled data, shown to the user only
     * @param taskCode         the task of the script, e.g. for the Kafka consumer group, may be null
     */
    public ToolContext withConnections(ConnectionToolBackend backend, Set<String> allowedHostNames,
                                       HostGuard hostGuard, Consumer<DataSample> sampleListener, Long taskCode) {
        this.connectionBackend = backend;
        this.allowedHostNames = allowedHostNames;
        this.hostGuard = hostGuard;
        this.sampleListener = sampleListener;
        this.taskCode = taskCode;
        return this;
    }

    ConnectionToolBackend connectionBackend() {
        return connectionBackend;
    }

    Set<String> allowedHostNames() {
        return allowedHostNames;
    }

    HostGuard hostGuard() {
        return hostGuard;
    }

    Long taskCode() {
        return taskCode;
    }

    void onSample(DataSample sample) {
        sampleListener.accept(sample);
    }

    /**
     * The registered connections the tools used, their rules are checked in the generated config like the ones of
     * the selection.
     */
    public List<ConnectionSelection> usedConnections() {
        return usedConnections;
    }

    /**
     * Report the result of the current tool call for its progress step.
     */
    void report(StepReport report) {
        this.stepReport = report;
    }

    /**
     * @return the result reported by the last tool call, null when it did not report
     */
    StepReport takeReport() {
        StepReport report = stepReport;
        stepReport = null;
        return report;
    }

    /**
     * @return the number of samples of the request including this one
     */
    int countDescribeCall() {
        return ++describeCalls;
    }

    /**
     * @return the number of HTTP calls of the request including this one
     */
    int countHttpCall() {
        return ++httpCalls;
    }
}
