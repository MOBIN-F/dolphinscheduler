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

package org.apache.dolphinscheduler.dao.entity;

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * A connection of SeaTunnel jobs maintained in the security center, e.g. a Kafka cluster or a database. The
 * scripts reference its secrets as {@code ${stconn.<connKey>.<field>}}, the master injects them when the task runs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_ds_seatunnel_connection")
public class SeatunnelConnection {

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    private long code;

    /**
     * The immutable identifier referenced by the scripts, e.g. kafka_prod.
     */
    private String connKey;

    private String name;

    /**
     * KAFKA, MYSQL, POSTGRESQL, CLICKHOUSE, HTTP or S3.
     */
    private String type;

    private String description;

    /**
     * The non-secret fields as json.
     */
    private String params;

    /**
     * The secret fields as json, encrypted with {@code SeatunnelConnectionSecretCodec}.
     */
    private String secrets;

    private Integer createUser;

    private Integer updateUser;

    private Date createTime;

    private Date updateTime;
}
