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

package org.apache.dolphinscheduler.api.dto.seatunnel;

import java.util.List;

import lombok.Data;

/**
 * The fields of a connection object, without the raw samples.
 */
@Data
public class DescribedObject {

    private String object;

    private String format;

    private String recordPath;

    private String note;

    private List<Field> fields;

    @Data
    public static class Field {

        private String name;

        private String type;

        private boolean nullable;

        private boolean primaryKey;

        private String sample;

        private String note;
    }
}
