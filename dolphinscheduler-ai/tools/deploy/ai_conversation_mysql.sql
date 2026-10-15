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

CREATE TABLE IF NOT EXISTS `t_ds_ai_conversation`
(
    `id`           bigint      NOT NULL AUTO_INCREMENT COMMENT 'id',
    `task_code`    bigint      NOT NULL COMMENT 'task definition code',
    `project_code` bigint      NOT NULL COMMENT 'project code',
    `assistant`    varchar(32) NOT NULL COMMENT 'assistant type, e.g. seatunnel',
    `messages`     longtext COMMENT 'messages json, without credentials and generated scripts',
    `create_user`  int         DEFAULT NULL COMMENT 'create user id',
    `update_user`  int         DEFAULT NULL COMMENT 'update user id',
    `create_time`  datetime    DEFAULT NULL COMMENT 'create time',
    `update_time`  datetime    DEFAULT NULL COMMENT 'update time',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uniq_ai_conversation_task` (`task_code`, `assistant`)
) ENGINE = InnoDB
  AUTO_INCREMENT = 1
  DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
