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

import org.apache.dolphinscheduler.ai.security.SensitiveData.Kind;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SensitiveDataTest {

    // A valid ID number (check digit) and bank card number (Luhn) for the tests
    private static final String ID = "11010519491231002X";
    private static final String CARD = "6222020200112345679";

    @Test
    public void testClassifyByFieldName() {
        Assertions.assertEquals(Optional.of(Kind.PHONE), SensitiveData.classifyField("phone"));
        Assertions.assertEquals(Optional.of(Kind.PHONE), SensitiveData.classifyField("buyer.userPhoneNo"));
        Assertions.assertEquals(Optional.of(Kind.ID_NUMBER), SensitiveData.classifyField("id_card"));
        Assertions.assertEquals(Optional.of(Kind.NAME), SensitiveData.classifyField("realName"));
        Assertions.assertEquals(Optional.of(Kind.OTHER), SensitiveData.classifyField("age"));
        Assertions.assertEquals(Optional.of(Kind.CREDENTIAL), SensitiveData.classifyField("api_key"));
        Assertions.assertEquals(Optional.of(Kind.CREDENTIAL), SensitiveData.classifyField("accessToken"));
        // Words inside other words are not matched
        for (String field : List.of("page", "usage", "hotel", "monkey", "zip", "status", "amount", "order_id")) {
            Assertions.assertEquals(Optional.empty(), SensitiveData.classifyField(field), field);
        }
        SensitiveData.addKeywords(List.of("vip_level"));
        Assertions.assertEquals(Optional.of(Kind.OTHER), SensitiveData.classifyField("vipLevel"));
    }

    @Test
    public void testClassifyByValue() {
        Assertions.assertEquals(Optional.of(Kind.MOBILE), SensitiveData.classifyValue("13812345678"));
        Assertions.assertEquals(Optional.of(Kind.EMAIL), SensitiveData.classifyValue("amy@example.com"));
        Assertions.assertEquals(Optional.of(Kind.ID_NUMBER), SensitiveData.classifyValue(ID));
        Assertions.assertEquals(Optional.of(Kind.BANK_CARD), SensitiveData.classifyValue(CARD));
        Assertions.assertEquals(Optional.of(Kind.IP), SensitiveData.classifyValue("10.0.12.34"));
        // Not personal data: an 18 digit number with a wrong check digit, an epoch, an order number, a date
        for (String value : List.of("110105194912310021", "1760000000000", "1001", "2026-10-17 08:30:00",
                "6222020200112345670")) {
            Assertions.assertEquals(Optional.empty(), SensitiveData.classifyValue(value), value);
        }
        // A value in a field with a neutral name
        Assertions.assertEquals(Optional.of(Kind.MOBILE), SensitiveData.classify("contact", "13812345678"));
    }

    @Test
    public void testDescribeHasNoValue() {
        Assertions.assertEquals("mobile number", SensitiveData.describe(Kind.PHONE, "13812345678"));
        Assertions.assertEquals("18-character ID number", SensitiveData.describe(Kind.ID_NUMBER, ID));
        Assertions.assertEquals("value hidden", SensitiveData.describe(Kind.OTHER, "35"));
        Assertions.assertFalse(SensitiveData.describe(Kind.NAME, "Amy Lee").contains("Amy"));
    }

    @Test
    public void testMask() {
        Assertions.assertEquals("138****5678", SensitiveData.mask(Kind.MOBILE, "13812345678"));
        Assertions.assertEquals("a***@example.com", SensitiveData.mask(Kind.EMAIL, "amy@example.com"));
        Assertions.assertEquals("1101**********002X", SensitiveData.mask(Kind.ID_NUMBER, ID));
        Assertions.assertEquals("***************5679", SensitiveData.mask(Kind.BANK_CARD, CARD));
        Assertions.assertEquals("10.0.*.*", SensitiveData.mask(Kind.IP, "10.0.12.34"));
        Assertions.assertEquals("张**", SensitiveData.mask(Kind.NAME, "张小明"));
        Assertions.assertEquals("**", SensitiveData.mask(Kind.OTHER, "35"));
    }

    @Test
    public void testMaskSample() {
        String masked = SensitiveData.maskSample("{\"order_id\":1001,\"status\":\"PAID\",\"buyer\":{\"name\":\"张小明\","
                + "\"phone\":\"13812345678\",\"age\":35,\"contact\":\"amy@example.com\"},\"token\":\"t0p\","
                + "\"cards\":[\"" + CARD + "\"]}");
        for (String value : List.of("张小明", "13812345678", "35", "amy@example.com", "t0p", CARD)) {
            Assertions.assertFalse(masked.contains("\"" + value + "\"") || masked.contains(":" + value + ","),
                    value + " in " + masked);
        }
        Assertions.assertTrue(masked.contains("\"order_id\":1001"), masked);
        Assertions.assertTrue(masked.contains("\"status\":\"PAID\""), masked);
        Assertions.assertTrue(masked.contains("138****5678"), masked);
        Assertions.assertTrue(masked.contains("\"age\":\"**\""), masked);
        // Text and truncated JSON
        Assertions.assertEquals("call 138****5678 or a***@x.com, id 1101**********002X",
                SensitiveData.maskSample("call 13812345678 or amy@x.com, id " + ID));
        Assertions.assertEquals("call <mobile number> from <IPv4 address>",
                SensitiveData.hideInText("call 13812345678 from 10.0.0.1"));
    }

    @Test
    public void testWords() {
        Assertions.assertEquals(List.of("user", "phone", "no"), SensitiveData.words("userPhoneNo"));
        Assertions.assertEquals(List.of("http", "url"), SensitiveData.words("HTTPUrl"));
        Assertions.assertTrue(Set.of(true).contains(SensitiveData.isIdNumber(ID)));
        Assertions.assertTrue(SensitiveData.luhn(CARD));
    }
}
