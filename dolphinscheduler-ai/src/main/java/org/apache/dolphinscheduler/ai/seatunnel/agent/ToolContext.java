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

import org.apache.dolphinscheduler.ai.security.CredentialRedactor;

import java.util.Set;

/**
 * State of the tools within one chat request.
 */
public class ToolContext {

    private final CredentialRedactor redactor;
    private final Set<String> allowedHosts;
    private int httpCalls;

    /**
     * @param redactor     the redactor of the request, restores the credentials in the tool arguments
     * @param allowedHosts {@code host:port} the user gave in the conversation or the script, the only hosts the
     *                     HTTP tool may call
     */
    public ToolContext(CredentialRedactor redactor, Set<String> allowedHosts) {
        this.redactor = redactor;
        this.allowedHosts = allowedHosts;
    }

    public CredentialRedactor redactor() {
        return redactor;
    }

    public Set<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * @return the number of HTTP calls of the request including this one
     */
    int countHttpCall() {
        return ++httpCalls;
    }
}
