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
 * The connection selected for the source or the sink of a SeaTunnel job in the AI assistant.
 */
@Data
public class ConnectionSelectionRequest {

    private String connectionKey;

    /**
     * Topics, tables ({@code database.table}, {@code schema.table}) or API paths.
     */
    private List<String> objects;

    /**
     * A table to create in the sink database instead of an existing table.
     */
    private String newTable;

    /**
     * The target path of an S3 sink.
     */
    private String path;

    /**
     * The file format of an S3 sink, e.g. parquet.
     */
    private String fileFormat;

    /**
     * The fields of the objects, described by the browser; described again on the server when missing.
     */
    private List<DescribedObject> descriptions;
}
