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

package org.apache.dolphinscheduler.dao.repository.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.dolphinscheduler.dao.BaseDaoTest;
import org.apache.dolphinscheduler.dao.entity.AiConversation;
import org.apache.dolphinscheduler.dao.entity.SeatunnelConnection;
import org.apache.dolphinscheduler.dao.entity.SeatunnelConnectionUser;
import org.apache.dolphinscheduler.dao.repository.AiConversationDao;
import org.apache.dolphinscheduler.dao.repository.SeatunnelConnectionDao;
import org.apache.dolphinscheduler.dao.repository.SeatunnelConnectionUserDao;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SeatunnelConnectionDaoImplTest extends BaseDaoTest {

    @Autowired
    private SeatunnelConnectionDao connectionDao;

    @Autowired
    private SeatunnelConnectionUserDao connectionUserDao;

    @Autowired
    private AiConversationDao aiConversationDao;

    private SeatunnelConnection connection(String key, long code) {
        return SeatunnelConnection.builder().code(code).connKey(key).name(key + " name").type("KAFKA")
                .params("{}").secrets("v1:x").build();
    }

    @Test
    void testQueryByKeysAndAuthorization() {
        SeatunnelConnection kafka = connection("kafka_prod", 1L);
        SeatunnelConnection pg = connection("pg_dw", 2L);
        connectionDao.insert(kafka);
        connectionDao.insert(pg);

        assertEquals("kafka_prod name", connectionDao.queryByKey("kafka_prod").get().getName());
        assertFalse(connectionDao.queryByKey("missing").isPresent());
        assertEquals(2, connectionDao.queryByKeys(Arrays.asList("kafka_prod", "pg_dw", "missing")).size());
        assertTrue(connectionDao.queryByKeys(Collections.emptyList()).isEmpty());

        assertTrue(connectionDao.queryAuthorized(7).isEmpty());
        SeatunnelConnectionUser relation = new SeatunnelConnectionUser();
        relation.setUserId(7);
        relation.setConnectionId(pg.getId());
        relation.setPerm(7);
        connectionUserDao.insert(relation);
        assertEquals(Collections.singletonList("pg_dw"),
                Collections.singletonList(connectionDao.queryAuthorized(7).get(0).getConnKey()));
        assertEquals(Collections.singletonList(pg.getId()), connectionUserDao.queryConnectionIdsByUserId(7));

        connectionUserDao.deleteByConnectionId(pg.getId());
        assertTrue(connectionDao.queryAuthorized(7).isEmpty());
    }

    @Test
    void testConversationContext() {
        aiConversationDao.upsert(AiConversation.builder().taskCode(2001L).projectCode(1L).assistant("seatunnel")
                .messages("[]").context("{\"source\":{\"connectionKey\":\"kafka_prod\"}}").updateUser(1).build());
        assertEquals("{\"source\":{\"connectionKey\":\"kafka_prod\"}}",
                aiConversationDao.queryByTaskCode(2001L, "seatunnel").get().getContext());
        aiConversationDao.upsert(AiConversation.builder().taskCode(2001L).projectCode(1L).assistant("seatunnel")
                .messages("[]").context(null).updateUser(1).build());
        assertEquals(null, aiConversationDao.queryByTaskCode(2001L, "seatunnel").get().getContext());
    }
}
