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

import org.apache.dolphinscheduler.ai.connection.DescribedField;
import org.apache.dolphinscheduler.ai.security.SensitiveData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Infers the fields of a JSON document: paths, types, nullability, one sample value per field and the most likely
 * array of records. The result is sent to the LLM instead of the document, so the values are truncated and the
 * values of sensitive keys are masked.
 */
public class JsonFieldInferrer {

    static final int MAX_ARRAY_ELEMENTS = 50;
    static final int MAX_FIELDS = 300;
    static final int MAX_DEPTH = 10;
    static final int MAX_SAMPLE_LENGTH = 50;

    private static final String ELEMENT = "[*]";

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATE_SLASH = Pattern.compile("\\d{4}/\\d{2}/\\d{2}( \\d{2}:\\d{2}:\\d{2})?");
    private static final Pattern TIMESTAMP =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}([ T])\\d{2}:\\d{2}(:\\d{2})?(\\.\\d{1,9})?");
    private static final Pattern TIMESTAMP_ZONE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?(\\.\\d{1,9})?(Z|[+-]\\d{2}:?\\d{2})");
    private static final Pattern INTEGER_STRING = Pattern.compile("-?\\d+");

    private final Map<String, Field> fields = new LinkedHashMap<>();
    private final Map<String, Integer> objectCounts = new HashMap<>();
    private boolean tooManyFields;

    private static class Field {

        final String path;
        final String parentPath;
        final Set<String> kinds = new LinkedHashSet<>();
        int present;
        boolean nullSeen;
        int maxElements;
        String sample;
        String format;
        /**
         * The description of a sensitive value, e.g. mobile number: the field has no sample then
         */
        String sensitive;

        Field(String path, String parentPath) {
            this.path = path;
            this.parentPath = parentPath;
        }
    }

    /**
     * The fields of the records in a JSON document.
     *
     * @param recordPath the JsonPath of the array of the records, {@code $} for an array document, null when the
     *                   document is a single record
     * @param fields     the fields relative to a record, nested fields are joined with dots
     */
    public record RecordSchema(String recordPath, List<DescribedField> fields) {
    }

    /**
     * @return the description of the fields for the LLM
     */
    static String describe(JsonNode root) {
        return of(root).describe();
    }

    /**
     * @param root a JSON document, or an array of records such as the messages of a topic
     */
    public static RecordSchema records(JsonNode root) {
        JsonFieldInferrer inferrer = of(root);
        Field records = inferrer.recordArray();
        String prefix = records == null ? "$." : records.path + ELEMENT + ".";
        List<DescribedField> result = new ArrayList<>();
        for (Field field : inferrer.fields.values()) {
            if (!field.path.startsWith(prefix) || field.path.endsWith(ELEMENT)) {
                continue;
            }
            String name = field.path.substring(prefix.length()).replace(ELEMENT, "[*]");
            String note = field.sensitive == null ? field.format
                    : "sensitive, " + field.sensitive + (field.format == null ? "" : ", " + field.format);
            result.add(new DescribedField(name, inferrer.type(field), inferrer.isNullable(field), false,
                    field.sample, note));
        }
        return new RecordSchema(records == null ? null : records.path, result);
    }

    private static JsonFieldInferrer of(JsonNode root) {
        JsonFieldInferrer inferrer = new JsonFieldInferrer();
        if (root.isObject()) {
            inferrer.visitObject(root, "$", 0);
        } else {
            inferrer.visitValue(root, "$", "", null, 0);
        }
        return inferrer;
    }

    private void visitObject(JsonNode node, String path, int depth) {
        objectCounts.merge(path, 1, Integer::sum);
        if (depth >= MAX_DEPTH) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> entries = node.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            visitValue(entry.getValue(), childPath(path, entry.getKey()), entry.getKey(), path, depth + 1);
        }
    }

    private void visitValue(JsonNode value, String path, String name, String parentPath, int depth) {
        Field field = fields.get(path);
        if (field == null) {
            if (fields.size() >= MAX_FIELDS) {
                tooManyFields = true;
                return;
            }
            field = new Field(path, parentPath);
            fields.put(path, field);
        }
        field.present++;
        if (value == null || value.isNull() || value.isMissingNode()) {
            field.nullSeen = true;
        } else if (value.isObject()) {
            field.kinds.add("object");
            visitObject(value, path, depth);
        } else if (value.isArray()) {
            field.kinds.add("array");
            field.maxElements = Math.max(field.maxElements, value.size());
            if (depth < MAX_DEPTH) {
                for (int i = 0; i < Math.min(value.size(), MAX_ARRAY_ELEMENTS); i++) {
                    visitValue(value.get(i), path + ELEMENT, name, null, depth + 1);
                }
            }
        } else {
            visitScalar(value, name, field);
        }
    }

    private static void visitScalar(JsonNode value, String name, Field field) {
        String format = null;
        if (value.isBoolean()) {
            field.kinds.add("boolean");
        } else if (value.isIntegralNumber()) {
            field.kinds.add(value.canConvertToInt() ? "int" : value.canConvertToLong() ? "bigint" : "decimal");
            format = epochFormat(value.asText());
        } else if (value.isNumber()) {
            field.kinds.add("double");
        } else {
            field.kinds.add("string");
            format = stringFormat(value.asText());
        }
        if (format != null) {
            field.format = field.format == null || field.format.equals(format) ? format : "mixed formats";
        }
        // Personal or secret data: only the format is given, never the value
        String text = value.asText();
        Optional<SensitiveData.Kind> kind = SensitiveData.classify(name, text);
        if (kind.isPresent()) {
            if (field.sensitive == null) {
                field.sensitive = SensitiveData.describe(kind.get(), text);
            }
            field.sample = null;
        } else if (field.sample == null && field.sensitive == null) {
            field.sample = sample(value);
        }
    }

    private String describe() {
        StringBuilder text = new StringBuilder("JSON fields (arrays merged from their first ")
                .append(MAX_ARRAY_ELEMENTS)
                .append(" elements; nullable = missing or null in some objects):\n");
        for (Field field : fields.values()) {
            if (field.path.endsWith(ELEMENT)) {
                continue;
            }
            text.append(field.path).append(": ").append(describeField(field)).append("\n");
        }
        if (tooManyFields) {
            text.append("(more fields omitted, only the first ").append(MAX_FIELDS).append(" are listed)\n");
        }
        Field records = recordArray();
        if (records == null) {
            text.append("\nNo array of objects found, the response is a single record.\n");
            return text.toString();
        }
        String prefix = records.path + ELEMENT + ".";
        text.append("\nRecords: most likely the array at ").append(records.path).append(" (")
                .append(records.maxElements).append(" items in the response).\n");
        if (records.path.equals("$")) {
            text.append("The response is a JSON array of records, content_field is not needed.\n");
        } else {
            text.append("Suggested Http source option: content_field = \"").append(records.path)
                    .append(".*\" (or json_field with a JsonPath per field when the fields are spread over the "
                            + "response).\n");
        }
        text.append("Record fields (relative to a record):\n");
        for (Field field : fields.values()) {
            if (field.path.startsWith(prefix) && !field.path.endsWith(ELEMENT)) {
                text.append("  ").append(field.path.substring(prefix.length())).append(": ")
                        .append(describeField(field)).append("\n");
            }
        }
        return text.toString();
    }

    private String describeField(Field field) {
        StringBuilder text = new StringBuilder(type(field));
        if (field.kinds.contains("array")) {
            text.append(" (").append(field.maxElements).append(" items)");
        }
        if (isNullable(field)) {
            text.append(", nullable");
        }
        if (field.sensitive != null) {
            text.append(", sensitive, ").append(field.sensitive);
        }
        if (field.format != null) {
            text.append(", ").append(field.format);
        }
        if (field.sample != null) {
            text.append(", sample: ").append(field.sample);
        }
        return text.toString();
    }

    private String type(Field field) {
        if (field.kinds.size() == 1 && field.kinds.contains("array")) {
            Field element = fields.get(field.path + ELEMENT);
            return "array<" + (element == null ? "unknown" : type(element)) + ">";
        }
        return mergeKinds(field.kinds);
    }

    private boolean isNullable(Field field) {
        if (field.nullSeen) {
            return true;
        }
        Integer parents = field.parentPath == null ? null : objectCounts.get(field.parentPath);
        return parents != null && field.present < parents;
    }

    /**
     * The array of objects with the most elements, the shallowest one when equal.
     */
    private Field recordArray() {
        Field best = null;
        for (Field field : fields.values()) {
            Field element = fields.get(field.path + ELEMENT);
            if (!field.kinds.contains("array") || element == null || !element.kinds.equals(Set.of("object"))) {
                continue;
            }
            if (best == null || field.maxElements > best.maxElements) {
                best = field;
            }
        }
        return best;
    }

    private static String mergeKinds(Set<String> kinds) {
        if (kinds.isEmpty()) {
            return "null (type unknown)";
        }
        if (kinds.size() == 1) {
            return kinds.iterator().next();
        }
        if (Set.of("int", "bigint", "decimal", "double").containsAll(kinds)) {
            return kinds.contains("double") ? "double"
                    : kinds.contains("decimal") ? "decimal" : kinds.contains("bigint") ? "bigint" : "int";
        }
        return "mixed " + kinds + ", use string";
    }

    private static String stringFormat(String value) {
        if (TIMESTAMP_ZONE.matcher(value).matches()) {
            return "looks like timestamp with time zone (ISO 8601)";
        }
        Matcher timestamp = TIMESTAMP.matcher(value);
        if (timestamp.matches()) {
            return "looks like timestamp yyyy-MM-dd" + ("T".equals(timestamp.group(1)) ? "'T'" : " ") + "HH:mm"
                    + (timestamp.group(2) == null ? "" : ":ss") + (timestamp.group(3) == null ? "" : ".SSS");
        }
        if (DATE.matcher(value).matches()) {
            return "looks like date yyyy-MM-dd";
        }
        if (DATE_SLASH.matcher(value).matches()) {
            return "looks like date yyyy/MM/dd" + (value.length() > 10 ? " HH:mm:ss" : "");
        }
        if (INTEGER_STRING.matcher(value).matches()) {
            return "numeric string";
        }
        return null;
    }

    private static String epochFormat(String value) {
        if (value.matches("1\\d{9}")) {
            return "looks like epoch seconds";
        }
        if (value.matches("1\\d{12}")) {
            return "looks like epoch milliseconds";
        }
        return null;
    }

    private static String sample(JsonNode value) {
        if (!value.isTextual()) {
            return value.asText();
        }
        String text = value.asText();
        if (text.length() > MAX_SAMPLE_LENGTH) {
            text = text.substring(0, MAX_SAMPLE_LENGTH) + "...";
        }
        return "\"" + text.replace("\n", "\\n") + "\"";
    }

    private static String childPath(String path, String key) {
        return key.matches("[A-Za-z_][\\w-]*") ? path + "." + key : path + "['" + key.replace("'", "\\'") + "']";
    }
}
