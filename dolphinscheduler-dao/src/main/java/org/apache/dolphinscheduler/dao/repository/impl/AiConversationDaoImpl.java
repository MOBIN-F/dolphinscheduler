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

import org.apache.dolphinscheduler.dao.entity.AiConversation;
import org.apache.dolphinscheduler.dao.mapper.AiConversationMapper;
import org.apache.dolphinscheduler.dao.repository.AiConversationDao;
import org.apache.dolphinscheduler.dao.repository.BaseDao;

import java.util.Date;
import java.util.Optional;

import lombok.NonNull;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

@Repository
public class AiConversationDaoImpl extends BaseDao<AiConversation, AiConversationMapper>
        implements
            AiConversationDao {

    public AiConversationDaoImpl(@NonNull AiConversationMapper aiConversationMapper) {
        super(aiConversationMapper);
    }

    @Override
    public Optional<AiConversation> queryByTaskCode(long taskCode, String assistant) {
        return Optional.ofNullable(mybatisMapper.selectOne(new QueryWrapper<AiConversation>().lambda()
                .eq(AiConversation::getTaskCode, taskCode)
                .eq(AiConversation::getAssistant, assistant)));
    }

    @Override
    public void upsert(AiConversation conversation) {
        Date now = new Date();
        conversation.setUpdateTime(now);
        if (update(conversation) > 0) {
            return;
        }
        conversation.setCreateUser(conversation.getUpdateUser());
        conversation.setCreateTime(now);
        try {
            mybatisMapper.insert(conversation);
        } catch (DuplicateKeyException e) {
            // Inserted by another request at the same time, the last write wins
            update(conversation);
        }
    }

    private int update(AiConversation conversation) {
        return mybatisMapper.update(null, new UpdateWrapper<AiConversation>().lambda()
                .set(AiConversation::getMessages, conversation.getMessages())
                .set(AiConversation::getProjectCode, conversation.getProjectCode())
                .set(AiConversation::getUpdateUser, conversation.getUpdateUser())
                .set(AiConversation::getUpdateTime, conversation.getUpdateTime())
                .eq(AiConversation::getTaskCode, conversation.getTaskCode())
                .eq(AiConversation::getAssistant, conversation.getAssistant()));
    }

    @Override
    public void deleteByTaskCode(long taskCode, String assistant) {
        mybatisMapper.delete(new QueryWrapper<AiConversation>().lambda()
                .eq(AiConversation::getTaskCode, taskCode)
                .eq(AiConversation::getAssistant, assistant));
    }
}
