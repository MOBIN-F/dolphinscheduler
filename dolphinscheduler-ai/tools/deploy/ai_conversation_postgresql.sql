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

-- Table of the AI assistant conversations, run once on an existing DolphinScheduler 3.4.2 database.

CREATE TABLE IF NOT EXISTS t_ds_ai_conversation
(
    id           bigserial   NOT NULL,
    task_code    bigint      NOT NULL,
    project_code bigint      NOT NULL,
    assistant    varchar(32) NOT NULL,
    messages     text,
    create_user  int         DEFAULT NULL,
    update_user  int         DEFAULT NULL,
    create_time  timestamp   DEFAULT NULL,
    update_time  timestamp   DEFAULT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uniq_ai_conversation_task UNIQUE (task_code, assistant)
);
