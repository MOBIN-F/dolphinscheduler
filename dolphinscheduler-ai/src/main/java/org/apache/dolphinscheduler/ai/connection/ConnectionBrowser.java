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

/**
 * Browses the objects of the connections of some types, the connections have the decrypted secrets.
 */
public interface ConnectionBrowser {

    boolean supports(ConnectionType type);

    /**
     * Connect to check the connection, throws with the reason when it fails.
     */
    void test(SeatunnelConnection connection) throws Exception;

    /**
     * @param parent the path of the parent node, empty for the top level
     */
    List<BrowseNode> list(SeatunnelConnection connection, String parent) throws Exception;

    ObjectDescription describe(SeatunnelConnection connection, String object) throws Exception;
}
