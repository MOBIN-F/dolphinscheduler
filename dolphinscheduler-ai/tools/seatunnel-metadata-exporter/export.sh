#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Export the option rules of the connectors installed in a SeaTunnel home as JSON, used by the AI assistant.
# Usage: export.sh <SEATUNNEL_HOME> <output.json>
set -eo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <SEATUNNEL_HOME> <output.json>" >&2
  exit 1
fi

SEATUNNEL_HOME=$(cd "$1"; pwd)
OUTPUT=$2
TOOL_DIR=$(cd "$(dirname "$0")"; pwd)
CLASS_PATH="$SEATUNNEL_HOME/starter/seatunnel-starter.jar:$SEATUNNEL_HOME/lib/*:$SEATUNNEL_HOME/connectors/*"

BUILD_DIR=$(mktemp -d)
trap 'rm -rf "$BUILD_DIR"' EXIT
javac -nowarn -cp "$CLASS_PATH" -d "$BUILD_DIR" "$TOOL_DIR/SeatunnelMetadataExporter.java"
SEATUNNEL_HOME=$SEATUNNEL_HOME java -cp "$BUILD_DIR:$CLASS_PATH" SeatunnelMetadataExporter "$OUTPUT"
