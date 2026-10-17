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

/**
 * A field of a source or sink object, e.g. a column of a table or a field of the Kafka messages.
 *
 * @param type   the database type of a column, or the inferred type of a JSON field
 * @param sample a truncated sample value, the values of sensitive keys are masked
 * @param note   e.g. the inferred format of a string
 */
public record DescribedField(String name, String type, boolean nullable, boolean primaryKey, String sample,
        String note) {
}
