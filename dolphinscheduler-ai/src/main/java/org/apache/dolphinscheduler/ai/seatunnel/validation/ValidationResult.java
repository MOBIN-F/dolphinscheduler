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

package org.apache.dolphinscheduler.ai.seatunnel.validation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Result of a validation stage.
 *
 * @param stage   {@link #STAGE_LOCAL} or {@link #STAGE_ENGINE}
 * @param skipped true if the stage did not run, e.g. the engine validation is not configured
 */
public record ValidationResult(String stage, boolean skipped, List<ValidationIssue> issues, String skipReason) {

    public static final String STAGE_LOCAL = "local";
    public static final String STAGE_ENGINE = "engine";

    public static ValidationResult of(String stage, List<ValidationIssue> issues) {
        return new ValidationResult(stage, false, List.copyOf(issues), null);
    }

    public static ValidationResult skipped(String stage, String reason) {
        return new ValidationResult(stage, true, List.of(), reason);
    }

    public boolean valid() {
        return issues.stream().noneMatch(issue -> issue.level() == ValidationIssue.Level.ERROR);
    }

    public List<ValidationIssue> errors() {
        return issues.stream().filter(issue -> issue.level() == ValidationIssue.Level.ERROR)
                .collect(Collectors.toList());
    }

    /**
     * @return the issues as text, used as the feedback for the LLM to fix the config
     */
    public String describe() {
        if (skipped) {
            return stage + " validation skipped: " + skipReason;
        }
        if (issues.isEmpty()) {
            return stage + " validation passed";
        }
        return stage + " validation " + (valid() ? "passed with warnings" : "failed") + ":\n"
                + issues.stream().map(ValidationIssue::toString).collect(Collectors.joining("\n"));
    }
}
