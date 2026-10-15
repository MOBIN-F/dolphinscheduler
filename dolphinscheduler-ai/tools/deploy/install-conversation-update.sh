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

# Update an api server which already has the AI assistant: saves the AI conversations of the tasks.
# Run the create table SQL (ai_conversation_mysql.sql or ai_conversation_postgresql.sql) first.
# Usage: install-conversation-update.sh <DOLPHINSCHEDULER_HOME>, then restart the api server.
set -eo pipefail

if [ $# -ne 1 ] || [ ! -d "$1/api-server/libs" ]; then
  echo "Usage: $0 <DOLPHINSCHEDULER_HOME>" >&2
  exit 1
fi
DS_HOME=$(cd "$1"; pwd)
PACKAGE_DIR=$(cd "$(dirname "$0")"; pwd)
LIBS="$DS_HOME/api-server/libs"
UI="$DS_HOME/api-server/ui"
SUFFIX=$(date +%Y%m%d%H%M%S)
BACKUP="$DS_HOME/api-server/backup-$SUFFIX"

if [ ! -f "$LIBS/dolphinscheduler-ai-3.4.2.jar" ]; then
  echo "dolphinscheduler-ai-3.4.2.jar not found in $LIBS, install the AI assistant package first" >&2
  exit 1
fi

mkdir -p "$BACKUP"
for jar in dolphinscheduler-api-3.4.2.jar dolphinscheduler-dao-3.4.2.jar; do
  cp -a "$LIBS/$jar" "$BACKUP/"
  cp "$PACKAGE_DIR/api-server-libs/$jar" "$LIBS/$jar"
  echo "Replaced $jar"
done
if [ -d "$UI" ]; then
  mv "$UI" "$BACKUP/ui"
fi
mkdir -p "$UI"
tar --warning=no-unknown-keyword -xzf "$PACKAGE_DIR"/ui/dolphinscheduler-ui-3.4.2-ai.tar.gz -C "$UI" 2>/dev/null \
  || tar -xzf "$PACKAGE_DIR"/ui/dolphinscheduler-ui-3.4.2-ai.tar.gz -C "$UI"
echo "Replaced the ui, backup: $BACKUP"
echo "Make sure the table t_ds_ai_conversation exists, then restart the api server."
