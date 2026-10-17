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
-- Tables of the SeaTunnel AI assistant: the conversations of the tasks and the SeaTunnel connections.
-- Run once on the DolphinScheduler 3.4.2 metadata database, the existing tables are not changed.

CREATE TABLE IF NOT EXISTS t_ds_ai_conversation
(
    id           bigserial   NOT NULL,
    task_code    bigint      NOT NULL,
    project_code bigint      NOT NULL,
    assistant    varchar(32) NOT NULL,
    messages     text,
    context      text,
    create_user  int         DEFAULT NULL,
    update_user  int         DEFAULT NULL,
    create_time  timestamp   DEFAULT NULL,
    update_time  timestamp   DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uniq_ai_conversation_task UNIQUE (task_code, assistant)
);

--
-- Table structure for t_ds_seatunnel_connection
--

CREATE TABLE IF NOT EXISTS t_ds_seatunnel_connection
(
    id          serial       NOT NULL,
    code        bigint       NOT NULL,
    conn_key    varchar(64)  NOT NULL,
    name        varchar(128) NOT NULL,
    type        varchar(32)  NOT NULL,
    description varchar(512) DEFAULT NULL,
    params      text,
    secrets     text,
    create_user int          DEFAULT NULL,
    update_user int          DEFAULT NULL,
    create_time timestamp    DEFAULT NULL,
    update_time timestamp    DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uniq_seatunnel_connection_key UNIQUE (conn_key),
    CONSTRAINT uniq_seatunnel_connection_name UNIQUE (name)
);

--
-- Table structure for t_ds_relation_seatunnel_connection_user
--

CREATE TABLE IF NOT EXISTS t_ds_relation_seatunnel_connection_user
(
    id            serial    NOT NULL,
    user_id       int       NOT NULL,
    connection_id int       NOT NULL,
    perm          int       DEFAULT 1,
    create_time   timestamp DEFAULT NULL,
    update_time   timestamp DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uniq_seatunnel_connection_user UNIQUE (user_id, connection_id)
);
