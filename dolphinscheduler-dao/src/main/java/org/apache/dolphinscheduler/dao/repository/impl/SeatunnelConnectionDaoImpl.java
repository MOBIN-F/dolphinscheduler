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

import org.apache.dolphinscheduler.dao.entity.SeatunnelConnection;
import org.apache.dolphinscheduler.dao.entity.SeatunnelConnectionUser;
import org.apache.dolphinscheduler.dao.mapper.SeatunnelConnectionMapper;
import org.apache.dolphinscheduler.dao.mapper.SeatunnelConnectionUserMapper;
import org.apache.dolphinscheduler.dao.repository.BaseDao;
import org.apache.dolphinscheduler.dao.repository.SeatunnelConnectionDao;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.NonNull;

import org.springframework.stereotype.Repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

@Repository
public class SeatunnelConnectionDaoImpl extends BaseDao<SeatunnelConnection, SeatunnelConnectionMapper>
        implements
            SeatunnelConnectionDao {

    private final SeatunnelConnectionUserMapper connectionUserMapper;

    public SeatunnelConnectionDaoImpl(@NonNull SeatunnelConnectionMapper seatunnelConnectionMapper,
                                      @NonNull SeatunnelConnectionUserMapper connectionUserMapper) {
        super(seatunnelConnectionMapper);
        this.connectionUserMapper = connectionUserMapper;
    }

    @Override
    public Optional<SeatunnelConnection> queryByKey(String connKey) {
        return Optional.ofNullable(mybatisMapper.selectOne(new QueryWrapper<SeatunnelConnection>().lambda()
                .eq(SeatunnelConnection::getConnKey, connKey)));
    }

    @Override
    public List<SeatunnelConnection> queryByKeys(Collection<String> connKeys) {
        if (connKeys.isEmpty()) {
            return Collections.emptyList();
        }
        return mybatisMapper.selectList(new QueryWrapper<SeatunnelConnection>().lambda()
                .in(SeatunnelConnection::getConnKey, connKeys));
    }

    @Override
    public List<SeatunnelConnection> queryAuthorized(int userId) {
        List<Integer> ids = connectionUserMapper.selectList(new QueryWrapper<SeatunnelConnectionUser>().lambda()
                .eq(SeatunnelConnectionUser::getUserId, userId))
                .stream().map(SeatunnelConnectionUser::getConnectionId).collect(Collectors.toList());
        return ids.isEmpty() ? Collections.emptyList() : queryByIds(ids);
    }
}
