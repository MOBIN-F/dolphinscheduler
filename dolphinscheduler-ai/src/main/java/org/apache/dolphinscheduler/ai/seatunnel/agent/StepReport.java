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

import java.util.List;
import java.util.Map;

/**
 * The result of a tool call for its progress step, reported by the tool.
 */
record StepReport(String state, Map<String, Object> result, List<String> details) {

    static final int MAX_DETAILS = 20;

    static StepReport ok(Map<String, Object> result, List<String> details) {
        return new StepReport(AgentStep.OK, result, limit(details));
    }

    static StepReport warn(Map<String, Object> result, List<String> details) {
        return new StepReport(AgentStep.WARN, result, limit(details));
    }

    static StepReport error(String message) {
        return new StepReport(AgentStep.ERROR, Map.of("message", message), List.of());
    }

    private static List<String> limit(List<String> details) {
        return details == null ? List.of() : details.subList(0, Math.min(details.size(), MAX_DETAILS));
    }
}
