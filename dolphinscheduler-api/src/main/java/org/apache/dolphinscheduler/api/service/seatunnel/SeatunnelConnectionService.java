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

package org.apache.dolphinscheduler.api.service.seatunnel;

import org.apache.dolphinscheduler.ai.connection.BrowseNode;
import org.apache.dolphinscheduler.ai.connection.ConnectionSelection;
import org.apache.dolphinscheduler.ai.connection.ConnectionToolBackend;
import org.apache.dolphinscheduler.ai.connection.ObjectDescription;
import org.apache.dolphinscheduler.api.dto.seatunnel.ConnectionContextRequest;
import org.apache.dolphinscheduler.api.dto.seatunnel.SeatunnelConnectionRequest;
import org.apache.dolphinscheduler.api.dto.seatunnel.SeatunnelConnectionVO;
import org.apache.dolphinscheduler.dao.entity.TaskDefinition;
import org.apache.dolphinscheduler.dao.entity.User;

import java.util.List;
import java.util.Map;

import lombok.Value;

/**
 * The SeaTunnel connections maintained in the security center: managed by the administrators, used by the
 * authorized users in the AI assistant and referenced by the scripts as {@code ${stconn.<key>.<field>}}.
 */
public interface SeatunnelConnectionService {

    /**
     * @return the connection types with their form fields
     */
    List<Map<String, Object>> types();

    /**
     * @return all connections for an administrator, the authorized ones for other users
     */
    List<SeatunnelConnectionVO> list(User loginUser);

    SeatunnelConnectionVO create(User loginUser, SeatunnelConnectionRequest request);

    SeatunnelConnectionVO update(User loginUser, long code, SeatunnelConnectionRequest request);

    void delete(User loginUser, long code);

    /**
     * Test a connection of the form, the empty secrets are taken from the saved connection when the code is given.
     */
    void test(User loginUser, SeatunnelConnectionRequest request, Long code);

    List<SeatunnelConnectionVO> authorized(User loginUser, int userId);

    List<SeatunnelConnectionVO> unauthorized(User loginUser, int userId);

    void grant(User loginUser, int userId, String connectionIds);

    List<BrowseNode> objects(User loginUser, String connKey, String parent);

    ObjectDescription describe(User loginUser, String connKey, String object);

    /**
     * The selected connections of an AI assistant request.
     *
     * @param selections the selections for the prompt, the secrets are masked
     * @param references the secrets by their references, used in the HTTP calls of the assistant only
     */
    @Value
    class ResolvedContext {

        List<ConnectionSelection> selections;

        Map<String, String> references;
    }

    /**
     * @param taskCode the task of the script, the Kafka consumer group of the job is derived from it, may be null
     */
    ResolvedContext resolve(User loginUser, ConnectionContextRequest context, Long taskCode);

    /**
     * @return the connections of the user for the tools of the AI assistant reading the fields
     */
    ConnectionToolBackend toolBackend(User loginUser);

    /**
     * Check that the user may use the connections newly referenced by the scripts of the SeaTunnel tasks, the
     * references already saved in the tasks are not checked again.
     */
    void checkReferences(User loginUser, List<? extends TaskDefinition> taskDefinitions);
}
