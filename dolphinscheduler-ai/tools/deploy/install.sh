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

# Installs the SeaTunnel AI assistant into the api server of DolphinScheduler 3.4.2.
#
# Usage: install.sh <DOLPHINSCHEDULER_HOME> [--seatunnel-home <dir>] [--no-config] [--dry-run]
#   --seatunnel-home  SeaTunnel 2.3.13 installation on this machine, enables the engine validation
#   --no-config       do not append the `ai:` section to api-server/conf/application.yaml
#   --dry-run         only run the checks, change nothing
#
# Stop the api server first. Everything changed is backed up to api-server/ai-backup-<time>, and restored
# automatically when the installation fails. Roll back later with rollback.sh.
set -euo pipefail

PACKAGE_DIR=$(cd "$(dirname "$0")"; pwd)
DS_HOME=""
SEATUNNEL_HOME=""
CONFIGURE=true
DRY_RUN=false

usage() {
  sed -n '19,27p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --seatunnel-home) [ $# -ge 2 ] || usage; SEATUNNEL_HOME="$2"; shift 2 ;;
    --no-config) CONFIGURE=false; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) usage ;;
    -*) echo "Unknown option $1" >&2; usage ;;
    *) [ -z "$DS_HOME" ] || usage; DS_HOME="$1"; shift ;;
  esac
done
[ -n "$DS_HOME" ] || usage

ERRORS=0
WARNINGS=0
ok() { echo "  [OK]    $*"; }
warn() { echo "  [WARN]  $*"; WARNINGS=$((WARNINGS + 1)); }
fail() { echo "  [ERROR] $*"; ERRORS=$((ERRORS + 1)); }

[ -d "$DS_HOME" ] || { echo "$DS_HOME does not exist" >&2; exit 1; }
DS_HOME=$(cd "$DS_HOME"; pwd)
API="$DS_HOME/api-server"
LIBS="$API/libs"
UI="$API/ui"
CONF="$API/conf/application.yaml"

echo "== Checks"

# 1. Package integrity
if command -v sha256sum >/dev/null; then
  SHA_CHECK="sha256sum -c --quiet"
else
  SHA_CHECK="shasum -a 256 -c --quiet"
fi
if (cd "$PACKAGE_DIR" && $SHA_CHECK SHA256SUMS >/dev/null 2>&1); then
  ok "package files match SHA256SUMS"
else
  fail "package files do not match SHA256SUMS, upload the package again"
fi

# 2. DolphinScheduler layout and version
if [ -d "$LIBS" ] && [ -f "$CONF" ]; then
  ok "api server found: $API"
else
  fail "$LIBS or $CONF not found, is $DS_HOME the DolphinScheduler home?"
fi
if [ -f "$LIBS/dolphinscheduler-api-3.4.2.jar" ]; then
  ok "DolphinScheduler 3.4.2"
else
  fail "$LIBS/dolphinscheduler-api-3.4.2.jar not found, this package is for DolphinScheduler 3.4.2 only"
fi

# 3. The api server must be stopped
if ! command -v pgrep >/dev/null; then
  warn "pgrep not found, make sure the api server is stopped"
elif pgrep -f "org.apache.dolphinscheduler.api.ApiApplicationServer" >/dev/null 2>&1; then
  fail "the api server is running, stop it first: $DS_HOME/bin/dolphinscheduler-daemon.sh stop api-server"
else
  ok "the api server is stopped"
fi

# 4. JDK 17+, the api server uses JAVA_HOME of bin/env/dolphinscheduler_env.sh
SERVER_JAVA_HOME=$( (set +eu; [ -f "$DS_HOME/bin/env/dolphinscheduler_env.sh" ] \
  && source "$DS_HOME/bin/env/dolphinscheduler_env.sh" >/dev/null 2>&1; echo "${JAVA_HOME:-}") )
JAVA_BIN="${SERVER_JAVA_HOME:+$SERVER_JAVA_HOME/bin/}java"
if command -v "$JAVA_BIN" >/dev/null 2>&1; then
  JAVA_VERSION_TEXT=$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([^"]*)".*/\1/')
  JAVA_VERSION=${JAVA_VERSION_TEXT%%[._]*}
  if [[ "$JAVA_VERSION" =~ ^[0-9]+$ ]] && [ "$JAVA_VERSION" -ge 17 ]; then
    ok "Java $JAVA_VERSION ($JAVA_BIN)"
  else
    fail "the api server runs on Java $JAVA_VERSION_TEXT ($JAVA_BIN), the AI assistant needs Java 17+"
  fi
  if command -v "${SERVER_JAVA_HOME:+$SERVER_JAVA_HOME/bin/}javac" >/dev/null 2>&1; then
    ok "JDK (javac found, needed by the engine validation)"
  else
    warn "javac not found, it is a JRE: the engine validation will be skipped, only the local validation runs"
  fi
else
  fail "java not found (JAVA_HOME=${SERVER_JAVA_HOME:-not set})"
fi

# 5. Jars the AI assistant needs from DolphinScheduler
MISSING=0
for prefix in jackson-databind jackson-core jackson-annotations slf4j-api httpclient httpcore commons-codec \
  commons-logging reactive-streams eventstream; do
  if ls "$LIBS" 2>/dev/null | grep -Eq "^${prefix}-[0-9][^/]*\.jar$"; then
    :
  else
    fail "$prefix-*.jar not found in $LIBS, the AI assistant needs it"
    MISSING=$((MISSING + 1))
  fi
done
[ "$MISSING" -gt 0 ] || ok "required DolphinScheduler jars found"

# 6. SeaTunnel installation
if [ -n "$SEATUNNEL_HOME" ]; then
  if [ -f "$SEATUNNEL_HOME/starter/seatunnel-starter.jar" ] && [ -d "$SEATUNNEL_HOME/connectors" ]; then
    SEATUNNEL_HOME=$(cd "$SEATUNNEL_HOME"; pwd)
    ok "SeaTunnel found: $SEATUNNEL_HOME"
  else
    fail "$SEATUNNEL_HOME/starter/seatunnel-starter.jar not found, check --seatunnel-home"
  fi
elif $CONFIGURE; then
  warn "--seatunnel-home not given: ai.seatunnel.home stays empty, the engine validation is skipped"
fi

# 7. Configuration
if grep -Eq '^ai:' "$CONF" 2>/dev/null; then
  CONFIGURE=false
  warn "application.yaml already has an 'ai:' section, it is not changed: make sure ai.enabled is true"
elif $CONFIGURE; then
  ok "the 'ai:' section will be appended to application.yaml"
else
  warn "--no-config: add the 'ai:' section to application.yaml yourself (see application-ai.yaml)"
fi

echo
if [ "$ERRORS" -gt 0 ]; then
  echo "$ERRORS error(s), nothing is changed." >&2
  exit 1
fi
if $DRY_RUN; then
  echo "Dry run: all checks passed ($WARNINGS warning(s)), nothing is changed."
  exit 0
fi

# Backup, restored automatically when a step below fails
# Never reuse an existing backup directory, e.g. two runs within one second
BACKUP="$API/ai-backup-$(date +%Y%m%d%H%M%S)"
SEQ=1
while ! mkdir "$BACKUP" 2>/dev/null; do
  BACKUP="$API/ai-backup-$(date +%Y%m%d%H%M%S)-$SEQ"
  SEQ=$((SEQ + 1))
done
UI_TMP="$API/.ui-installing"
echo "== Backup to $BACKUP"
cp -a "$LIBS" "$BACKUP/libs"
[ -d "$UI" ] && cp -a "$UI" "$BACKUP/ui"
cp -a "$CONF" "$BACKUP/application.yaml"
echo "$BACKUP" > "$API/ai-backup-latest"
# The state before the first installation, the default of rollback.sh
if [ ! -f "$API/ai-backup-original" ] && [ ! -f "$LIBS/dolphinscheduler-ai-3.4.2.jar" ]; then
  echo "$BACKUP" > "$API/ai-backup-original"
fi
echo "  done"

restore() {
  echo >&2
  echo "Installation failed, restoring the backup $BACKUP" >&2
  rm -rf "$UI_TMP"
  rm -rf "$LIBS" && cp -a "$BACKUP/libs" "$LIBS"
  rm -rf "$UI"
  [ -d "$BACKUP/ui" ] && cp -a "$BACKUP/ui" "$UI"
  cp -a "$BACKUP/application.yaml" "$CONF"
  echo "Restored, the api server is unchanged." >&2
}
trap restore ERR

echo "== Install"
REMOVED=0
while read -r jar; do
  if [ -n "$jar" ] && [ -f "$LIBS/$jar" ]; then
    rm -f "$LIBS/$jar"
    REMOVED=$((REMOVED + 1))
  fi
done < "$PACKAGE_DIR/remove-from-api-server-libs.txt"
echo "  removed $REMOVED old AWS SDK jars"

cp "$PACKAGE_DIR"/api-server-libs/*.jar "$LIBS/"
echo "  copied $(ls "$PACKAGE_DIR"/api-server-libs/*.jar | wc -l | tr -d ' ') jars"

rm -rf "$UI_TMP" && mkdir -p "$UI_TMP"
tar -xzf "$PACKAGE_DIR/ui/dolphinscheduler-ui-3.4.2-ai.tar.gz" -C "$UI_TMP"
[ -f "$UI_TMP/index.html" ]
rm -rf "$UI" && mv "$UI_TMP" "$UI"
echo "  installed the ui"

if $CONFIGURE; then
  {
    [ -n "$(tail -c 1 "$CONF")" ] && echo
    echo
    grep -v '^# Append to' "$PACKAGE_DIR/application-ai.yaml" \
      | sed "s#^\(    home: \).*#\1\"$SEATUNNEL_HOME\"#"
  } >> "$CONF"
  echo "  appended the 'ai:' section to $CONF"
fi

# Two versions of an AWS SDK jar must not be in the classpath
if ls "$LIBS" | grep -Fxq -f <(grep -v '^[[:space:]]*$' "$PACKAGE_DIR/remove-from-api-server-libs.txt"); then
  echo "  old AWS SDK jars still exist" >&2
  false
fi
for jar in "$PACKAGE_DIR"/api-server-libs/*.jar; do
  cmp -s "$jar" "$LIBS/$(basename "$jar")"
done
echo "  verified the installed files"
trap - ERR

cat <<EOF

Installed. Backup: $BACKUP

Next steps:
  1. Make sure the table t_ds_ai_conversation exists (run ai_conversation_*.sql manually, this script never touches the database)
  2. Make sure the api server's IAM role can call Bedrock (see DEPLOY.md)
  3. Start the api server: $DS_HOME/bin/dolphinscheduler-daemon.sh start api-server
     and check api-server/logs: "AI assistant uses AWS Bedrock in us-east-1"
Roll back: $PACKAGE_DIR/rollback.sh $DS_HOME
EOF
