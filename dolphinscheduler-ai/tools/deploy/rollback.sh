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

# Restores the api server from a backup of install.sh: libs, ui and conf/application.yaml.
#
# Usage: rollback.sh <DOLPHINSCHEDULER_HOME> [backup-dir]
#   backup-dir  api-server/ai-backup-<time>, by default the backup before the AI assistant was first installed
#
# Stop the api server first. The table t_ds_ai_conversation is kept, it does not affect DolphinScheduler.
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ] || [ ! -d "$1/api-server/libs" ]; then
  sed -n '19,24p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
fi
DS_HOME=$(cd "$1"; pwd)
API="$DS_HOME/api-server"
BACKUP="${2:-$(cat "$API/ai-backup-original" 2>/dev/null || cat "$API/ai-backup-latest" 2>/dev/null || true)}"

if [ -z "$BACKUP" ] || [ ! -d "$BACKUP/libs" ] || [ ! -f "$BACKUP/application.yaml" ]; then
  echo "Backup not found: '${BACKUP}'. Backups: $(ls -d "$API"/ai-backup-2* 2>/dev/null | tr '\n' ' ')" >&2
  exit 1
fi
if pgrep -f "org.apache.dolphinscheduler.api.ApiApplicationServer" >/dev/null 2>&1; then
  echo "The api server is running, stop it first: $DS_HOME/bin/dolphinscheduler-daemon.sh stop api-server" >&2
  exit 1
fi

echo "Restoring $API from $BACKUP"
rm -rf "$API/libs" && cp -a "$BACKUP/libs" "$API/libs"
echo "  restored libs"
rm -rf "$API/ui"
if [ -d "$BACKUP/ui" ]; then
  cp -a "$BACKUP/ui" "$API/ui"
  echo "  restored ui"
fi
cp -a "$BACKUP/application.yaml" "$API/conf/application.yaml"
echo "  restored conf/application.yaml"
if [ "$BACKUP" = "$(cat "$API/ai-backup-original" 2>/dev/null || true)" ]; then
  # Back to the state before the AI assistant, the next installation records the original state again
  rm -f "$API/ai-backup-original" "$API/ai-backup-latest"
fi
echo "Done. Start the api server: $DS_HOME/bin/dolphinscheduler-daemon.sh start api-server"
