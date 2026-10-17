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

package org.apache.dolphinscheduler.api.dto.ai;

import java.util.Map;

import lombok.Data;

/**
 * A progress step of a saved assistant message, e.g. a tool call with its result, without the details.
 */
@Data
public class AiConversationStep {

    private String kind;

    /**
     * running, ok, warn or error
     */
    private String state;

    private Map<String, Object> args;

    private Map<String, Object> result;

    private Long millis;
}
