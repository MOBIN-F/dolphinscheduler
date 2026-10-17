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

package org.apache.dolphinscheduler.ai.security;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HostGuardTest {

    @Test
    public void testHostNames() {
        Set<String> hosts = HostGuard.hostNames("kafka 在 kafka-1.us-east-1.example.com:9093 和 broker2:9092，"
                + "PG 是 jdbc:postgresql://10.0.0.5:5432/dw，接口 https://api.example.com/v1/orders，"
                + "同步到 public.orders_sink");
        Assertions.assertTrue(hosts.containsAll(List.of("kafka-1.us-east-1.example.com", "broker2",
                "10.0.0.5", "api.example.com")), hosts.toString());
        // A table name with an underscore is not a host
        Assertions.assertFalse(hosts.stream().anyMatch(host -> host.startsWith("public.orders")), hosts.toString());
    }

    @Test
    public void testCheck() {
        HostGuard guard = new HostGuard(List.of("10.9.0.0/16"), false);
        Set<String> allowed = Set.of("localhost", "169.254.169.254", "10.9.1.1", "127.0.0.1");
        Assertions.assertTrue(guard.check("made-up.example", allowed).contains("not given by the user"));
        Assertions.assertTrue(guard.check("localhost", allowed).contains("not allowed"));
        Assertions.assertTrue(guard.check("169.254.169.254", allowed).contains("not allowed"));
        Assertions.assertTrue(guard.check("10.9.1.1", allowed).contains("not allowed"));
        Assertions.assertNull(new HostGuard(List.of(), true).check("127.0.0.1", allowed));
    }
}
