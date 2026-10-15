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
import org.apache.dolphinscheduler.dao.repository.AiConversationDao;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AiConversationDaoImplTest extends BaseDaoTest {

    @Autowired
    private AiConversationDao aiConversationDao;

    private AiConversation conversation(String messages, int user) {
        return AiConversation.builder().taskCode(1001L).projectCode(1L).assistant("seatunnel")
                .messages(messages).updateUser(user).build();
    }

    @Test
    void testUpsertQueryAndDelete() {
        assertFalse(aiConversationDao.queryByTaskCode(1001L, "seatunnel").isPresent());

        aiConversationDao.upsert(conversation("[1]", 1));
        AiConversation inserted = aiConversationDao.queryByTaskCode(1001L, "seatunnel").get();
        assertEquals("[1]", inserted.getMessages());
        assertEquals(1, inserted.getCreateUser());

        // The second save updates the same row, the creator is kept
        aiConversationDao.upsert(conversation("[1,2]", 2));
        AiConversation updated = aiConversationDao.queryByTaskCode(1001L, "seatunnel").get();
        assertEquals(inserted.getId(), updated.getId());
        assertEquals("[1,2]", updated.getMessages());
        assertEquals(1, updated.getCreateUser());
        assertEquals(2, updated.getUpdateUser());

        // Another assistant of the same task is a different conversation
        assertFalse(aiConversationDao.queryByTaskCode(1001L, "sql").isPresent());

        aiConversationDao.deleteByTaskCode(1001L, "seatunnel");
        assertFalse(aiConversationDao.queryByTaskCode(1001L, "seatunnel").isPresent());
        // Deleting a missing conversation does nothing
        aiConversationDao.deleteByTaskCode(1001L, "seatunnel");
        assertTrue(aiConversationDao.queryAll().isEmpty());
    }
}
