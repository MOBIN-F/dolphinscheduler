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

package org.apache.dolphinscheduler.ai.connection;

import java.util.List;
import java.util.Optional;

/**
 * Access to the SeaTunnel connections for the tools of the assistant, bound to the user of the request: the
 * registered connections are checked against the authorization of the user, the inline connections are the ones
 * the user described in the conversation. The methods throw with a reason the LLM can tell the user.
 */
public interface ConnectionToolBackend {

    /**
     * @return the connections the user may use, the secrets are masked
     */
    List<SeatunnelConnection> list();

    /**
     * @return the registered connection if the user may use it, the secrets are masked
     */
    Optional<SeatunnelConnection> registered(String key);

    List<BrowseNode> browse(String key, String parent) throws Exception;

    ObjectDescription describe(String key, String object) throws Exception;

    /**
     * @param connection a connection described in the conversation, with the real secrets
     */
    List<BrowseNode> browse(SeatunnelConnection connection, String parent) throws Exception;

    ObjectDescription describe(SeatunnelConnection connection, String object) throws Exception;
}
