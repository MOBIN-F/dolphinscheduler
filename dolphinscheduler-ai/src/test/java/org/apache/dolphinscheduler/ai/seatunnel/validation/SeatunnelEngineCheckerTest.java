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

package org.apache.dolphinscheduler.ai.seatunnel.validation;

import java.time.Duration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public class SeatunnelEngineCheckerTest {

    @Test
    public void testDisabledWithoutHome() {
        SeatunnelEngineChecker checker = new SeatunnelEngineChecker("", Duration.ofSeconds(10));
        Assertions.assertFalse(checker.isEnabled());
        ValidationResult result = checker.check("env {}");
        Assertions.assertTrue(result.skipped());
        Assertions.assertTrue(result.valid());
    }

    @Test
    public void testParseOutput() throws Exception {
        SeatunnelEngineChecker checker = new SeatunnelEngineChecker("/opt/seatunnel", Duration.ofSeconds(10));
        ValidationResult result = checker.parse("some log line\n{\"valid\":false,\"errors\":[{\"phase\":\"option\","
                + "\"location\":\"sink.Jdbc\",\"message\":\"the options('driver') are required.\"}]}\n");
        Assertions.assertFalse(result.valid());
        Assertions.assertEquals("sink.Jdbc", result.errors().get(0).location());
        Assertions.assertTrue(result.errors().get(0).message().contains("[option]"));
    }

    /**
     * Runs against a real SeaTunnel installation when SEATUNNEL_HOME_FOR_TEST is set.
     */
    @Test
    public void testCheckWithInstalledSeatunnel() {
        String home = System.getenv("SEATUNNEL_HOME_FOR_TEST");
        Assumptions.assumeTrue(home != null && !home.isEmpty(), "SEATUNNEL_HOME_FOR_TEST is not set");
        SeatunnelEngineChecker checker = new SeatunnelEngineChecker(home, Duration.ofSeconds(120));
        ValidationResult valid = checker.check("env { job.mode = \"BATCH\" }\n"
                + "source { FakeSource { plugin_output = \"f\", schema = { fields { id = \"int\" } } } }\n"
                + "sink { Console { plugin_input = \"f\" } }");
        Assertions.assertFalse(valid.skipped(), valid.describe());
        Assertions.assertTrue(valid.valid(), valid.describe());
        ValidationResult invalid = checker.check("env { job.mode = \"BATCH\" }\n"
                + "source { FakeSource { plugin_output = \"f\" } }\nsink { Consolee { plugin_input = \"f\" } }");
        Assertions.assertFalse(invalid.valid(), invalid.describe());
        Assertions.assertTrue(invalid.describe().contains("Consolee"), invalid.describe());
    }
}
