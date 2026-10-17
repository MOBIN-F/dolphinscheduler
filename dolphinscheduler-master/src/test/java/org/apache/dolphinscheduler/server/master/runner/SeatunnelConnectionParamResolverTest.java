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

package org.apache.dolphinscheduler.server.master.runner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.dao.entity.SeatunnelConnection;
import org.apache.dolphinscheduler.dao.repository.SeatunnelConnectionDao;
import org.apache.dolphinscheduler.dao.utils.SeatunnelConnectionSecretCodec;
import org.apache.dolphinscheduler.plugin.task.api.model.Property;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SeatunnelConnectionParamResolverTest {

    private static String taskParams(String script) {
        Map<String, Object> params = new HashMap<>();
        params.put("useCustom", true);
        params.put("rawScript", script);
        return JSONUtils.toJsonString(params);
    }

    @Test
    void testResolveAndEscape() {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("password", "pa\"ss\\word");
        secrets.put("ssl_ca_pem", "-----BEGIN-----\nAAA\n-----END-----");
        secrets.put("basic_password", "pw");
        SeatunnelConnectionDao dao = mock(SeatunnelConnectionDao.class);
        when(dao.queryByKeys(any())).thenReturn(Collections.singletonList(SeatunnelConnection.builder()
                .connKey("pg_dw").params("{\"basic_username\":\"user\"}")
                .secrets(SeatunnelConnectionSecretCodec.encode(secrets)).build()));
        Map<String, Property> prepared = new HashMap<>();
        prepared.put("system.biz.date", new Property());

        Map<String, Property> result = new SeatunnelConnectionParamResolver(dao).resolve("SEATUNNEL",
                taskParams("password = \"${stconn.pg_dw.password}\"\nca = \"${stconn.pg_dw.ssl_ca_pem}\"\n"
                        + "auth = \"Basic ${stconn.pg_dw.basic_auth}\""),
                prepared);
        Assertions.assertTrue(result.containsKey("system.biz.date"));
        // Escaped for the double-quoted HOCON string
        Assertions.assertEquals("pa\\\"ss\\\\word", result.get("stconn.pg_dw.password").getValue());
        Assertions.assertEquals("-----BEGIN-----\\nAAA\\n-----END-----",
                result.get("stconn.pg_dw.ssl_ca_pem").getValue());
        Assertions.assertEquals(Base64.getEncoder().encodeToString("user:pw".getBytes(StandardCharsets.UTF_8)),
                result.get("stconn.pg_dw.basic_auth").getValue());
    }

    @Test
    void testMissingConnectionOrSecret() {
        SeatunnelConnectionDao dao = mock(SeatunnelConnectionDao.class);
        when(dao.queryByKeys(any())).thenReturn(Collections.emptyList());
        SeatunnelConnectionParamResolver resolver = new SeatunnelConnectionParamResolver(dao);
        IllegalArgumentException missing = Assertions.assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("SEATUNNEL", taskParams("p = \"${stconn.gone.password}\""), new HashMap<>()));
        Assertions.assertTrue(missing.getMessage().contains("'gone' not found"));

        when(dao.queryByKeys(any())).thenReturn(Collections.singletonList(SeatunnelConnection.builder()
                .connKey("pg").params("{}").secrets("").build()));
        IllegalArgumentException noSecret = Assertions.assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("SEATUNNEL", taskParams("p = \"${stconn.pg.password}\""), new HashMap<>()));
        Assertions.assertTrue(noSecret.getMessage().contains("no secret 'password'"));
    }

    @Test
    void testOtherTasksUnchanged() {
        SeatunnelConnectionDao dao = mock(SeatunnelConnectionDao.class);
        SeatunnelConnectionParamResolver resolver = new SeatunnelConnectionParamResolver(dao);
        Map<String, Property> prepared = new HashMap<>();
        Assertions.assertSame(prepared, resolver.resolve("SHELL", taskParams("${stconn.pg.password}"), prepared));
        Assertions.assertSame(prepared, resolver.resolve("SEATUNNEL", taskParams("no reference"), prepared));
        verify(dao, never()).queryByKeys(any());
    }
}
