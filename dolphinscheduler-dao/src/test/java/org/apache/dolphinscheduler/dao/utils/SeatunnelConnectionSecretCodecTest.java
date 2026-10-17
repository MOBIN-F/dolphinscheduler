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

package org.apache.dolphinscheduler.dao.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SeatunnelConnectionSecretCodecTest {

    @Test
    void testRoundTrip() {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("password", "p@ss\"w\\ord");
        secrets.put("ssl_ca_pem", "-----BEGIN CERTIFICATE-----\nABC\n-----END CERTIFICATE-----");
        String encoded = SeatunnelConnectionSecretCodec.encode(secrets);
        Assertions.assertTrue(encoded.startsWith("v1:"));
        Assertions.assertFalse(encoded.contains("p@ss"));
        // A random IV for every encryption
        Assertions.assertNotEquals(encoded, SeatunnelConnectionSecretCodec.encode(secrets));
        Assertions.assertEquals(secrets, SeatunnelConnectionSecretCodec.decode(encoded));
    }

    @Test
    void testEmptyAndTampered() {
        Assertions.assertEquals("", SeatunnelConnectionSecretCodec.encode(Collections.emptyMap()));
        Assertions.assertTrue(SeatunnelConnectionSecretCodec.decode("").isEmpty());
        Assertions.assertTrue(SeatunnelConnectionSecretCodec.decode(null).isEmpty());
        String encoded = SeatunnelConnectionSecretCodec.encode(Collections.singletonMap("password", "secret"));
        String tampered = encoded.substring(0, encoded.length() - 4) + "AAAA";
        Assertions.assertThrows(IllegalStateException.class, () -> SeatunnelConnectionSecretCodec.decode(tampered));
        Assertions.assertThrows(IllegalStateException.class, () -> SeatunnelConnectionSecretCodec.decode("plain"));
    }
}
