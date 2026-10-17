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

import java.util.Date;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * A SeaTunnel connection without its secrets.
 */
@Data
public class SeatunnelConnectionVO {

    private Integer id;

    private long code;

    private String connKey;

    private String name;

    private String type;

    private String description;

    private Map<String, String> params;

    /**
     * The secret fields which have a value, the values are never returned.
     */
    private List<String> secretsSet;

    private String updateUserName;

    private Date updateTime;
}
