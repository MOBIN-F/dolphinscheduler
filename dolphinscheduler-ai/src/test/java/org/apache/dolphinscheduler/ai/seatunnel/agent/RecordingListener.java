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

import java.util.ArrayList;
import java.util.List;

/**
 * Records the events of a chat request.
 */
class RecordingListener implements AgentEventListener {

    final StringBuilder deltas = new StringBuilder();
    final List<String> statuses = new ArrayList<>();
    SeatunnelProposal proposal;
    String done;
    String error;

    @Override
    public void onDelta(String text) {
        deltas.append(text);
    }

    @Override
    public void onStatus(String status, String detail) {
        statuses.add(detail == null ? status : status + ":" + detail);
    }

    @Override
    public void onProposal(SeatunnelProposal proposal) {
        this.proposal = proposal;
    }

    @Override
    public void onDone(String assistantMessage) {
        this.done = assistantMessage;
    }

    @Override
    public void onError(String message) {
        this.error = message;
    }
}
