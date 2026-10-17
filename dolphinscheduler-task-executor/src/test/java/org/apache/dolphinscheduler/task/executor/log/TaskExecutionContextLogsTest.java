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

package org.apache.dolphinscheduler.task.executor.log;

import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.enums.DataType;
import org.apache.dolphinscheduler.plugin.task.api.enums.Direct;
import org.apache.dolphinscheduler.plugin.task.api.model.Property;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TaskExecutionContextLogsTest {

    @Test
    void testSecretsMasked() {
        Map<String, Property> params = new HashMap<>();
        params.put("stconn.pg.password", new Property("stconn.pg.password", Direct.IN, DataType.VARCHAR, "S3cret!"));
        params.put("system.biz.date", new Property("system.biz.date", Direct.IN, DataType.VARCHAR, "20261017"));
        TaskExecutionContext context = new TaskExecutionContext();
        context.setTaskName("sync");
        context.setPrepareParamsMap(params);

        String json = TaskExecutionContextLogs.toMaskedJson(context);
        Assertions.assertFalse(json.contains("S3cret!"), json);
        Assertions.assertTrue(json.contains("******"), json);
        Assertions.assertTrue(json.contains("20261017"), json);
        Assertions.assertTrue(json.contains("sync"), json);
        // The context itself is not changed
        Assertions.assertEquals("S3cret!", context.getPrepareParamsMap().get("stconn.pg.password").getValue());
    }
}
