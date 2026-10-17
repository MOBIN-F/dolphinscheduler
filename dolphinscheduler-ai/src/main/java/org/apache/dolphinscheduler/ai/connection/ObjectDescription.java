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

import java.util.List;

/**
 * The fields of a source or sink object.
 *
 * @param format    json, text or table
 * @param recordPath the JsonPath of the records in a JSON document, e.g. {@code $.data.list}, null when not nested
 * @param note      extra information for the LLM, e.g. no message found in the topic
 * @param samples   the raw messages or response, shown to the user only: not sent to the LLM and not saved
 */
public record ObjectDescription(String object, String format, List<DescribedField> fields, String recordPath,
        String note, List<String> samples) {

    /**
     * @return the description for the LLM, without the raw samples
     */
    public String toPrompt() {
        StringBuilder text = new StringBuilder("- ").append(object).append(" (").append(format).append(")");
        if (recordPath != null && !recordPath.isEmpty() && !recordPath.equals("$")) {
            text.append(", records at ").append(recordPath);
        }
        text.append("\n");
        if (note != null && !note.isEmpty()) {
            text.append("  note: ").append(note).append("\n");
        }
        for (DescribedField field : fields) {
            text.append("  - ").append(field.name()).append(": ").append(field.type());
            if (field.primaryKey()) {
                text.append(", primary key");
            }
            if (field.nullable()) {
                text.append(", nullable");
            }
            if (field.note() != null && !field.note().isEmpty()) {
                text.append(", ").append(field.note());
            }
            if (field.sample() != null && !field.sample().isEmpty()) {
                text.append(", sample: ").append(field.sample());
            }
            text.append("\n");
        }
        return text.toString();
    }
}
