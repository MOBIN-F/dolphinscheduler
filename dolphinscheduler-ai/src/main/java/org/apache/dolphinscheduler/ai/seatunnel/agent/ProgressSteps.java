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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sends the progress steps of a request: each step has a start and an end with its result and duration. The
 * strings are cleaned so that no credential reaches the browser.
 */
class ProgressSteps {

    private static final int MAX_TEXT = 300;

    private final AgentEventListener listener;
    private final CredentialRedactor redactor;
    private int nextId = 1;

    ProgressSteps(AgentEventListener listener, CredentialRedactor redactor) {
        this.listener = listener;
        this.redactor = redactor;
    }

    /**
     * A started step.
     */
    final class Started {

        private final int id;
        private final String kind;
        private final Map<String, Object> args;
        private final long start = System.currentTimeMillis();

        private Started(int id, String kind, Map<String, Object> args) {
            this.id = id;
            this.kind = kind;
            this.args = args;
        }

        void end(StepReport report) {
            StepReport value = report == null ? StepReport.ok(Map.of(), List.of()) : report;
            listener.onStepEnd(new AgentStep(id, kind, value.state(), args, clean(value.result()),
                    value.details().stream().map(ProgressSteps.this::clean).collect(Collectors.toList()),
                    System.currentTimeMillis() - start));
        }
    }

    Started start(String kind, Map<String, Object> args) {
        Map<String, Object> cleaned = clean(args);
        Started started = new Started(nextId++, kind, cleaned);
        listener.onStepStart(new AgentStep(started.id, kind, AgentStep.RUNNING, cleaned, Map.of(), List.of(), 0));
        return started;
    }

    /**
     * A step which is finished when it starts, e.g. the use of the selected connections.
     */
    void instant(String kind, Map<String, Object> args, StepReport report) {
        start(kind, args).end(report);
    }

    private Map<String, Object> clean(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value instanceof String ? clean((String) value) : value));
        return result;
    }

    private String clean(String text) {
        String cleaned = CredentialRedactor.mask(redactor.redactKnown(text));
        return cleaned.length() > MAX_TEXT ? cleaned.substring(0, MAX_TEXT) + "..." : cleaned;
    }
}
