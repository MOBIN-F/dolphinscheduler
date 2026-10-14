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

/**
 * A problem found in a SeaTunnel config.
 *
 * @param location e.g. {@code sink.Jdbc}, {@code env}, {@code config}
 */
public record ValidationIssue(Level level,String location,String message){

public enum Level {

    ERROR,
    WARNING
    }

    public static ValidationIssue error(String location, String message) {
        return new ValidationIssue(Level.ERROR, location, message);
    }

    public static ValidationIssue warning(String location, String message) {
        return new ValidationIssue(Level.WARNING, location, message);
    }

    @Override
    public String toString() {
        return level + " [" + location + "] " + message;
    }
}
