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

package org.apache.dolphinscheduler.ai.connection;

import java.util.Locale;

/**
 * The consumer groups of a Kafka connection with an authorized consumer group prefix: a group is always the prefix
 * followed by a suffix, the prefix alone is never used as a group.
 */
public final class KafkaConsumerGroups {

    /**
     * The suffix of the group which samples the messages for the AI assistant, no offset is committed.
     */
    static final String SAMPLE_SUFFIX = "dolphinscheduler-ai-sample";

    private KafkaConsumerGroups() {
    }

    public static String prefix(SeatunnelConnection connection) {
        return connection.param("consumer_group_prefix").trim();
    }

    /**
     * @return the prefix and the suffix, joined with a hyphen unless the prefix already ends with a separator
     */
    public static String join(String prefix, String suffix) {
        if (prefix.isEmpty()) {
            return suffix;
        }
        char last = prefix.charAt(prefix.length() - 1);
        return prefix + (last == '-' || last == '_' || last == '.' || last == ':' ? "" : "-")
                + suffix.toLowerCase(Locale.ROOT);
    }

    /**
     * @return the group of the job of the task: stable, so that renaming the task does not consume from the start
     */
    public static String ofTask(String prefix, long taskCode) {
        return join(prefix, "ds-" + taskCode);
    }

    /**
     * @return whether the group is the prefix followed by a non-empty suffix
     */
    public static boolean isValid(String prefix, String group) {
        return group != null && group.startsWith(prefix) && group.length() > prefix.length()
                && !group.substring(prefix.length()).matches("[-_.:]*");
    }
}
