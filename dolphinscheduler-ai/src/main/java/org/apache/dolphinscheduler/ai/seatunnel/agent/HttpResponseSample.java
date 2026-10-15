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
 * The raw response of an HTTP API call, shown to the user only: it is not sent to the LLM and not saved.
 *
 * @param target      the method and the URL without the query, e.g. {@code GET https://host/path}
 * @param size        the size of the response in bytes, at least the read size when truncated
 * @param truncated   whether the body is truncated
 * @param body        the response text, truncated
 */
public record HttpResponseSample(String target, int status, String contentType, long size, boolean truncated,
        String body) {
}
