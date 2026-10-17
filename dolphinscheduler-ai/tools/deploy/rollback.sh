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

# Restores the servers from a backup of install.sh: the libs, and the ui and conf/application.yaml of the api server.
#
# Usage: rollback.sh <DOLPHINSCHEDULER_HOME> [backup-dir]
#   backup-dir  ai-backup-<time>, by default the backup before install.sh first ran
#
# Stop the servers in the backup first. The database is not changed, the new tables do not affect DolphinScheduler.
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ] || [ ! -d "$1" ]; then
  sed -n '19,24p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
fi
DS_HOME=$(cd "$1"; pwd)
BACKUP="${2:-$(cat "$DS_HOME/ai-backup-original" 2>/dev/null || cat "$DS_HOME/ai-backup-latest" 2>/dev/null || true)}"

if [ -z "$BACKUP" ] || [ ! -d "$BACKUP" ] || ! ls -d "$BACKUP"/*-server >/dev/null 2>&1; then
  echo "Backup not found: '${BACKUP}'. Backups: $(ls -d "$DS_HOME"/ai-backup-2* 2>/dev/null | tr '\n' ' ')" >&2
  exit 1
fi

main_class() {
  case "$1" in
    api-server) echo "org.apache.dolphinscheduler.api.ApiApplicationServer" ;;
    master-server) echo "org.apache.dolphinscheduler.server.master.MasterServer" ;;
    worker-server) echo "org.apache.dolphinscheduler.server.worker.WorkerServer" ;;
  esac
}

for saved in "$BACKUP"/*-server; do
  server=$(basename "$saved")
  if pgrep -f "$(main_class "$server")" >/dev/null 2>&1; then
    echo "The $server is running, stop it first: $DS_HOME/bin/dolphinscheduler-daemon.sh stop $server" >&2
    exit 1
  fi
done

# Overwrites the directory with the backup and removes the files not in the backup, then compares them
restore_dir() {
  local saved="$1" target="$2"
  mkdir -p "$target" 2>/dev/null || true
  for file in "$target"/* "$target"/.[!.]*; do
    [ -e "$file" ] || continue
    [ -e "$saved/$(basename "$file")" ] || rm -rf "$file" 2>/dev/null || true
  done
  cp -a "$saved/." "$target/" 2>/dev/null || true
  diff -rq "$saved" "$target" >/dev/null 2>&1
}

echo "Restoring from $BACKUP"
FAILED=""
for saved in "$BACKUP"/*-server; do
  server=$(basename "$saved")
  dir="$DS_HOME/$server"
  if restore_dir "$saved/libs" "$dir/libs"; then
    echo "  restored $server/libs"
  else
    FAILED="$FAILED $dir/libs"
  fi
  if [ "$server" = api-server ]; then
    if [ -d "$saved/ui" ]; then
      if restore_dir "$saved/ui" "$dir/ui"; then
        echo "  restored api-server/ui"
      else
        FAILED="$FAILED $dir/ui"
      fi
    fi
    if cp -a "$saved/application.yaml" "$dir/conf/application.yaml" 2>/dev/null \
      && cmp -s "$saved/application.yaml" "$dir/conf/application.yaml"; then
      echo "  restored api-server/conf/application.yaml"
    else
      FAILED="$FAILED $dir/conf/application.yaml"
    fi
  fi
done
if [ -n "$FAILED" ]; then
  echo "ROLLBACK INCOMPLETE, these are not the same as the backup:$FAILED" >&2
  echo "Fix the permissions (run as the deployment user of DolphinScheduler) and run rollback.sh again" >&2
  exit 1
fi
if [ "$BACKUP" = "$(cat "$DS_HOME/ai-backup-original" 2>/dev/null || true)" ]; then
  # Back to the state before the AI assistant, the next installation records the original state again
  rm -f "$DS_HOME/ai-backup-original" "$DS_HOME/ai-backup-latest"
fi
echo "Done. Start the servers: $DS_HOME/bin/dolphinscheduler-daemon.sh start <server>"
