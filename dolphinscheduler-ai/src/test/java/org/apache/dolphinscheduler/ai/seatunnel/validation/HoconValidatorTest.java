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

import org.apache.dolphinscheduler.ai.seatunnel.metadata.ConnectorMetadataRepository;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class HoconValidatorTest {

    private final HoconValidator validator = new HoconValidator(ConnectorMetadataRepository.load("", "2.3.13"));

    private static final String VALID = String.join("\n",
            "env {",
            "  parallelism = 1",
            "  job.mode = \"BATCH\"",
            "}",
            "source {",
            "  Jdbc {",
            "    url = \"jdbc:mysql://h:3306/db\"",
            "    driver = \"com.mysql.cj.jdbc.Driver\"",
            "    username = \"${mysql_user}\"",
            "    password = \"${mysql_password}\"",
            "    query = \"select * from t\"",
            "    plugin_output = \"t\"",
            "  }",
            "}",
            "sink {",
            "  S3File {",
            "    plugin_input = \"t\"",
            "    bucket = \"s3a://b\"",
            "    path = \"/p\"",
            "    fs.s3a.endpoint = \"s3.us-east-1.amazonaws.com\"",
            "    fs.s3a.aws.credentials.provider = \"org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider\"",
            "    access_key = \"${ak}\"",
            "    secret_key = \"${sk}\"",
            "    file_format_type = \"parquet\"",
            "  }",
            "}");

    @Test
    public void testValidConfig() {
        ValidationResult result = validator.validate(VALID);
        Assertions.assertTrue(result.valid(), result.describe());
        Assertions.assertTrue(result.issues().isEmpty(), result.describe());
    }

    @Test
    public void testSyntaxError() {
        ValidationResult result = validator.validate("env { job.mode = \"BATCH\"\nsource { FakeSource { ");
        Assertions.assertFalse(result.valid());
        Assertions.assertTrue(result.describe().contains("HOCON syntax error"), result.describe());
    }

    @Test
    public void testMissingRequiredOption() {
        ValidationResult result = validator.validate(VALID.replace("    driver = \"com.mysql.cj.jdbc.Driver\"\n", ""));
        Assertions.assertFalse(result.valid());
        Assertions.assertTrue(result.describe().contains("source.Jdbc") && result.describe().contains("[driver]"),
                result.describe());
    }

    @Test
    public void testConditionalRequiredOption() {
        // access_key / secret_key are required when the credentials provider is SimpleAWSCredentialsProvider
        ValidationResult result = validator.validate(VALID.replace("    access_key = \"${ak}\"\n", ""));
        Assertions.assertFalse(result.valid());
        Assertions.assertTrue(result.describe().contains("access_key"), result.describe());
    }

    @Test
    public void testExclusiveOptions() {
        String config = "env { job.mode = \"BATCH\" }\n"
                + "source { FakeSource { plugin_output = \"f\" } }\n"
                + "sink { Console { plugin_input = \"f\" } }";
        ValidationResult result = validator.validate(config);
        Assertions.assertFalse(result.valid());
        Assertions.assertTrue(result.describe().contains("exactly one of [tables_configs, schema]"), result.describe());
    }

    @Test
    public void testUnknownOptionIsWarning() {
        ValidationResult result =
                validator.validate(VALID.replace("    query =", "    not_an_option = 1\n    query ="));
        Assertions.assertTrue(result.valid(), result.describe());
        Assertions.assertTrue(result.describe().contains("unknown options [not_an_option]"), result.describe());
    }

    @Test
    public void testConnectorNotInstalled() {
        ValidationResult result = validator.validate(VALID.replace("S3File {", "Doris {"));
        Assertions.assertFalse(result.valid());
        Assertions.assertTrue(result.describe().contains("connector 'Doris' is not available"), result.describe());
    }

    @Test
    public void testRouting() {
        ValidationResult result = validator.validate(VALID.replace("plugin_input = \"t\"", "plugin_input = \"x\""));
        Assertions.assertFalse(result.valid());
        Assertions.assertTrue(result.describe().contains("input label \"x\" has no matching plugin_output"),
                result.describe());
    }

    @Test
    public void testDuplicateConnectorsAreValidatedSeparately() {
        String config = VALID.replace("source {\n", "source {\n  Jdbc {\n    url = \"jdbc:mysql://h:3306/db\"\n"
                + "    query = \"select 1\"\n    plugin_output = \"t2\"\n  }\n");
        ValidationResult result = validator.validate(config);
        Assertions.assertFalse(result.valid());
        String errors = result.errors().stream().map(ValidationIssue::message).collect(Collectors.joining("\n"));
        Assertions.assertTrue(errors.contains("[driver]"), result.describe());
    }

    @Test
    public void testStreamingWithoutCheckpointInterval() {
        ValidationResult result = validator.validate(VALID.replace("\"BATCH\"", "\"STREAMING\""));
        Assertions.assertTrue(result.valid(), result.describe());
        Assertions.assertTrue(result.describe().contains("checkpoint.interval"), result.describe());
    }

    @Test
    public void testExtractBlocksIgnoresBracesInStringsAndComments() {
        String config = "source {\n  # a comment with { brace\n  Jdbc { query = \"select '{' from t\" }\n}\n"
                + "sink { Console {} }";
        Assertions.assertEquals(2, HoconValidator.extractBlocks(config).size());
    }
}
