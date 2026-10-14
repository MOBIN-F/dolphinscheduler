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

package org.apache.dolphinscheduler.ai.seatunnel.agent;

/**
 * Events of a chat request, sent to the browser as server-sent events.
 */
public interface AgentEventListener {

    String STATUS_THINKING = "thinking";
    String STATUS_TOOL = "tool";
    String STATUS_VALIDATING_LOCAL = "validating_local";
    String STATUS_VALIDATING_ENGINE = "validating_engine";
    String STATUS_FIXING = "fixing";

    /**
     * A chunk of the assistant reply.
     */
    void onDelta(String text);

    /**
     * Progress of the request, e.g. a tool call or the validation.
     */
    void onStatus(String status, String detail);

    void onProposal(SeatunnelProposal proposal);

    /**
     * The request is finished.
     *
     * @param assistantMessage the reply without the config, the browser keeps it in the history
     */
    void onDone(String assistantMessage);

    void onError(String message);
}
