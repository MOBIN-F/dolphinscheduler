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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class CredentialRedactorTest {

    @Test
    public void testRedactAndRestoreConfig() {
        CredentialRedactor redactor = new CredentialRedactor();
        String config = "source {\n  Jdbc {\n    user = \"root\"\n    password = \"p@ss\\\"word\"\n"
                + "    secret_key: abc123xyz\n    token_expire_seconds = 60\n    api_key = \"${api_key}\"\n  }\n}";
        String redacted = redactor.redactConfig(config);
        Assertions.assertFalse(redacted.contains("p@ss"), redacted);
        Assertions.assertFalse(redacted.contains("abc123xyz"), redacted);
        Assertions.assertTrue(redacted.contains("user = \"root\""), redacted);
        Assertions.assertTrue(redacted.contains("token_expire_seconds = 60"), redacted);
        Assertions.assertTrue(redacted.contains("api_key = \"${api_key}\""), redacted);
        Assertions.assertEquals(2, redactor.redactedCount());
        Assertions.assertEquals(config, redactor.restore(redacted));
    }

    @Test
    public void testRedactText() {
        CredentialRedactor redactor = new CredentialRedactor();
        String text = "数据库密码是 Secr3t!，Kafka token: tk-123";
        String redacted = redactor.redactText(text);
        Assertions.assertFalse(redacted.contains("Secr3t!"), redacted);
        Assertions.assertFalse(redacted.contains("tk-123"), redacted);
        Assertions.assertEquals(text, redactor.restore(redacted));
    }

    @Test
    public void testClosingBracketOfSentenceIsNotPartOfValue() {
        CredentialRedactor redactor = new CredentialRedactor();
        String redacted = redactor.redactText("MySQL (密码是 Etl@2026) 的 orders 表, token: a(b)");
        Assertions.assertTrue(redacted.contains("${_CRED_1_}) 的"), redacted);
        Assertions.assertEquals("Etl@2026", redactor.restore("${_CRED_1_}"));
        Assertions.assertEquals("a(b)", redactor.restore("${_CRED_2_}"));
    }

    @Test
    public void testMask() {
        Assertions.assertEquals("password is ******", CredentialRedactor.mask("password is ${_CRED_1_}"));
    }

    @Test
    public void testSameValueSamePlaceholder() {
        CredentialRedactor redactor = new CredentialRedactor();
        String first = redactor.redactConfig("password = \"same\"");
        String second = redactor.redactConfig("password = \"same\"");
        Assertions.assertEquals(first, second);
        Assertions.assertEquals(1, redactor.redactedCount());
    }

    @Test
    public void testPasswordsWithQuotes() {
        CredentialRedactor redactor = new CredentialRedactor();
        String text = redactor.redactText("用户 postgres，密码是 Pg\"Pa\\ss1，目标表 t");
        Assertions.assertFalse(text.contains("Pa"), text);
        Assertions.assertTrue(text.endsWith("，目标表 t"), text);
        Assertions.assertEquals("用户 postgres，密码是 Pg\"Pa\\ss1，目标表 t", redactor.restore(text));

        String quoted = redactor.redactText("password is \"a b c\" and token: 'x y'");
        Assertions.assertFalse(quoted.contains("a b c") || quoted.contains("x y"), quoted);
        Assertions.assertTrue(quoted.matches("password is \"\\$\\{_CRED_\\d+_}\" and token: '\\$\\{_CRED_\\d+_}'"),
                quoted);
        String chinese = redactor.redactText("密码为“p 1”。");
        Assertions.assertFalse(chinese.contains("p 1"), chinese);
        Assertions.assertTrue(chinese.endsWith("”。"), chinese);
    }

    @Test
    public void testRestoreConfigKeepsItValid() {
        CredentialRedactor redactor = new CredentialRedactor();
        String text = redactor.redactText("密码是 Pg\"Pa\\ss1，token 是 a\\b");
        String password = text.substring(text.indexOf("${"), text.indexOf("}") + 1);
        String token = text.substring(text.lastIndexOf("${"), text.lastIndexOf("}") + 1);
        String config = "source { Jdbc { password = \"" + password + "\", note = \"say \\\"hi\\\" " + password
                + "\", token = " + token + " } }";
        String restored = redactor.restoreConfig(config);
        com.typesafe.config.Config parsed = com.typesafe.config.ConfigFactory.parseString(restored);
        Assertions.assertEquals("Pg\"Pa\\ss1", parsed.getString("source.Jdbc.password"));
        Assertions.assertEquals("say \"hi\" Pg\"Pa\\ss1", parsed.getString("source.Jdbc.note"));
        Assertions.assertEquals("a\\b", parsed.getString("source.Jdbc.token"));
        // Without credentials nothing changes
        Assertions.assertEquals("a = \"${x}\"", new CredentialRedactor().restoreConfig("a = \"${x}\""));
    }
}
