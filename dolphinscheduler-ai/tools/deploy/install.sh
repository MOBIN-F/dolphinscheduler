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

# Installs the SeaTunnel AI assistant into DolphinScheduler 3.4.2: api-server, master-server and worker-server.
#
# Usage: install.sh <DOLPHINSCHEDULER_HOME> [--components api,master,worker] [--seatunnel-home <dir>]
#                   [--remove-conflicts] [--no-config] [--dry-run]
#   --components        the servers to install, by default the ones found in DOLPHINSCHEDULER_HOME
#   --seatunnel-home    SeaTunnel 2.3.13 installation on the api server machine, enables the engine validation
#   --remove-conflicts  remove the other versions of the installed jars (kept in the backup)
#   --no-config         do not append the `ai:` section to api-server/conf/application.yaml
#   --dry-run           only run the checks, change nothing
#
# Stop the servers being installed first. Everything changed is backed up to ai-backup-<time>, and restored
# automatically when the installation fails. Roll back later with rollback.sh. This script never touches the
# database, run the SQL files manually.
set -euo pipefail

PACKAGE_DIR=$(cd "$(dirname "$0")"; pwd)
DS_HOME=""
COMPONENTS=""
SEATUNNEL_HOME=""
REMOVE_CONFLICTS=false
CONFIGURE=true
DRY_RUN=false
KEY_PROPERTY="seatunnel.connection.encryption.key"

usage() {
  sed -n '19,32p' "$0" | sed 's/^# \{0,1\}//'
  exit 1
}

while [ $# -gt 0 ]; do
  case "$1" in
    --components) [ $# -ge 2 ] || usage; COMPONENTS="$2"; shift 2 ;;
    --seatunnel-home) [ $# -ge 2 ] || usage; SEATUNNEL_HOME="$2"; shift 2 ;;
    --remove-conflicts) REMOVE_CONFLICTS=true; shift ;;
    --no-config) CONFIGURE=false; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    -h|--help) usage ;;
    -*) echo "Unknown option $1" >&2; usage ;;
    *) [ -z "$DS_HOME" ] || usage; DS_HOME="$1"; shift ;;
  esac
done
[ -n "$DS_HOME" ] || usage
[ -d "$DS_HOME" ] || { echo "$DS_HOME does not exist" >&2; exit 1; }
DS_HOME=$(cd "$DS_HOME"; pwd)

ERRORS=0
WARNINGS=0
ok() { echo "  [OK]    $*"; }
warn() { echo "  [WARN]  $*"; WARNINGS=$((WARNINGS + 1)); }
fail() { echo "  [ERROR] $*"; ERRORS=$((ERRORS + 1)); }

server_dir() { echo "$DS_HOME/$1-server"; }
main_class() {
  case "$1" in
    api) echo "org.apache.dolphinscheduler.api.ApiApplicationServer" ;;
    master) echo "org.apache.dolphinscheduler.server.master.MasterServer" ;;
    worker) echo "org.apache.dolphinscheduler.server.worker.WorkerServer" ;;
  esac
}

if [ -z "$COMPONENTS" ]; then
  for component in api master worker; do
    [ -d "$(server_dir $component)/libs" ] && COMPONENTS="$COMPONENTS,$component"
  done
  COMPONENTS=${COMPONENTS#,}
fi
has() { [[ ",$COMPONENTS," == *",$1,"* ]]; }

# The Maven coordinates of the jar, e.g. software.amazon.awssdk:sdk-core
JAR_LISTER=""
if command -v unzip >/dev/null; then
  JAR_LISTER="unzip -Z1"
fi
coordinates() {
  [ -n "$JAR_LISTER" ] || return 0
  $JAR_LISTER "$1" 2>/dev/null | sed -n 's#^META-INF/maven/\([^/]*\)/\([^/]*\)/pom.properties$#\1:\2#p' | sort -u
}

echo "== Checks (components: ${COMPONENTS:-none})"
[ -n "$COMPONENTS" ] || fail "no api-server, master-server or worker-server in $DS_HOME"

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
[ -n "$JAR_LISTER" ] || warn "unzip not found, the jar version conflicts are not checked"

# 2. Each server: layout, version and stopped
CONFLICTS_FILE=$(mktemp)
trap 'rm -f "$CONFLICTS_FILE"' EXIT
IFS=',' read -r -a COMPONENT_LIST <<< "$COMPONENTS"
for component in "${COMPONENT_LIST[@]}"; do
  case "$component" in api|master|worker) ;; *) fail "unknown component $component"; continue ;; esac
  dir=$(server_dir "$component")
  libs="$dir/libs"
  if [ ! -d "$libs" ]; then
    fail "$libs not found"
    continue
  fi
  if [ -f "$libs/dolphinscheduler-$component-3.4.2.jar" ]; then
    ok "$component-server: DolphinScheduler 3.4.2"
  else
    fail "$libs/dolphinscheduler-$component-3.4.2.jar not found, this package is for DolphinScheduler 3.4.2 only"
  fi
  # Everything the installation writes, so that it does not fail half way
  NOT_WRITABLE=""
  [ -w "$libs" ] || NOT_WRITABLE="$NOT_WRITABLE $libs"
  for jar in "$PACKAGE_DIR/$component-server-libs"/*.jar; do
    existing="$libs/$(basename "$jar")"
    if [ -e "$existing" ] && [ ! -w "$existing" ]; then
      NOT_WRITABLE="$NOT_WRITABLE $existing"
    fi
  done
  if [ "$component" = api ]; then
    for path in "$dir" "$dir/conf/application.yaml"; do
      [ ! -e "$path" ] || [ -w "$path" ] || NOT_WRITABLE="$NOT_WRITABLE $path"
    done
  fi
  [ -w "$DS_HOME" ] || NOT_WRITABLE="$NOT_WRITABLE $DS_HOME"
  if [ -n "$NOT_WRITABLE" ]; then
    fail "not writable by $(id -un), run as the deployment user of DolphinScheduler:$NOT_WRITABLE"
  fi
  if ! command -v pgrep >/dev/null; then
    warn "pgrep not found, make sure the $component server is stopped"
  elif pgrep -f "$(main_class "$component")" >/dev/null 2>&1; then
    fail "the $component server is running, stop it first: $DS_HOME/bin/dolphinscheduler-daemon.sh stop $component-server"
  else
    ok "$component-server is stopped"
  fi
  # Other versions of the jars to install, matched by their Maven coordinates
  if [ -n "$JAR_LISTER" ]; then
    for jar in "$PACKAGE_DIR/$component-server-libs"/*.jar; do
      name=$(basename "$jar")
      artifact=$(echo "$name" | sed -E 's/-[0-9][^-]*(-[A-Za-z0-9.]+)?\.jar$//')
      wanted=$(coordinates "$jar")
      [ -n "$wanted" ] || continue
      for existing in "$libs/$artifact"-[0-9]*.jar; do
        [ -f "$existing" ] || continue
        [ "$(basename "$existing")" != "$name" ] || continue
        grep -Fxq "$(basename "$existing")" "$PACKAGE_DIR/remove-from-api-server-libs.txt" 2>/dev/null && continue
        if [ "$(coordinates "$existing")" = "$wanted" ]; then
          echo "$existing" >> "$CONFLICTS_FILE"
        fi
      done
    done
  fi
done
if [ -s "$CONFLICTS_FILE" ]; then
  if $REMOVE_CONFLICTS; then
    warn "other versions of the jars will be removed (kept in the backup): $(xargs -n1 basename < "$CONFLICTS_FILE" | tr '\n' ' ')"
  else
    fail "other versions of the jars to install exist, they would conflict: $(tr '\n' ' ' < "$CONFLICTS_FILE")"
    echo "          rerun with --remove-conflicts to remove them, they are kept in the backup"
  fi
elif [ -n "$JAR_LISTER" ]; then
  ok "no other versions of the jars to install"
fi

# 3. api server: JDK 17+, required jars, SeaTunnel, configuration
if has api; then
  API="$(server_dir api)"
  SERVER_JAVA_HOME=$( (set +eu; [ -f "$DS_HOME/bin/env/dolphinscheduler_env.sh" ] \
    && source "$DS_HOME/bin/env/dolphinscheduler_env.sh" >/dev/null 2>&1; echo "${JAVA_HOME:-}") )
  JAVA_BIN="${SERVER_JAVA_HOME:+$SERVER_JAVA_HOME/bin/}java"
  if command -v "$JAVA_BIN" >/dev/null 2>&1; then
    JAVA_VERSION_TEXT=$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([^"]*)".*/\1/')
    JAVA_VERSION=${JAVA_VERSION_TEXT%%[._]*}
    if [[ "$JAVA_VERSION" =~ ^[0-9]+$ ]] && [ "$JAVA_VERSION" -ge 17 ]; then
      ok "api-server Java $JAVA_VERSION ($JAVA_BIN)"
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
  MISSING=0
  for prefix in jackson-databind jackson-core jackson-annotations slf4j-api httpclient httpcore commons-codec \
    commons-logging reactive-streams eventstream snappy-java aws-java-sdk-s3; do
    if ! ls "$API/libs" 2>/dev/null | grep -Eq "^${prefix}-[0-9][^/]*\.jar$"; then
      fail "$prefix-*.jar not found in $API/libs, the AI assistant needs it"
      MISSING=$((MISSING + 1))
    fi
  done
  [ "$MISSING" -gt 0 ] || ok "required DolphinScheduler jars found"
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
  CONF="$API/conf/application.yaml"
  if [ ! -f "$CONF" ]; then
    fail "$CONF not found"
  elif grep -Eq '^ai:' "$CONF"; then
    CONFIGURE=false
    warn "application.yaml already has an 'ai:' section, it is not changed: make sure ai.enabled is true"
  elif $CONFIGURE; then
    ok "the 'ai:' section will be appended to application.yaml"
  else
    warn "--no-config: add the 'ai:' section to application.yaml yourself (see application-ai.yaml)"
  fi
fi

# 4. The api server encrypts the connection secrets, the master decrypts them: the same key on both
KEYS=""
for component in api master; do
  has "$component" || continue
  properties="$(server_dir "$component")/conf/common.properties"
  value=$(grep -E "^[[:space:]]*$KEY_PROPERTY[[:space:]]*=" "$properties" 2>/dev/null | tail -1 | cut -d= -f2- \
    | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' || true)
  if [ -z "$value" ]; then
    warn "$KEY_PROPERTY is not set in $properties: the secrets of the SeaTunnel connections are only obfuscated"
  fi
  KEYS="$KEYS|$value"
done
if has api && has master && [ "$(echo "$KEYS" | cut -d'|' -f2)" != "$(echo "$KEYS" | cut -d'|' -f3)" ]; then
  fail "$KEY_PROPERTY differs between api-server and master-server, the master could not decrypt the secrets"
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

# Backup, restored automatically when a step below fails. Never reuse an existing backup directory.
BACKUP="$DS_HOME/ai-backup-$(date +%Y%m%d%H%M%S)"
SEQ=1
while ! mkdir "$BACKUP" 2>/dev/null; do
  BACKUP="$DS_HOME/ai-backup-$(date +%Y%m%d%H%M%S)-$SEQ"
  SEQ=$((SEQ + 1))
done
echo "== Backup to $BACKUP"
for component in "${COMPONENT_LIST[@]}"; do
  dir=$(server_dir "$component")
  mkdir -p "$BACKUP/$component-server"
  cp -a "$dir/libs" "$BACKUP/$component-server/libs"
  if [ "$component" = api ]; then
    [ -d "$dir/ui" ] && cp -a "$dir/ui" "$BACKUP/api-server/ui"
    cp -a "$dir/conf/application.yaml" "$BACKUP/api-server/application.yaml"
  fi
done
echo "$BACKUP" > "$DS_HOME/ai-backup-latest"
# The state before the first installation, the default of rollback.sh
[ -f "$DS_HOME/ai-backup-original" ] || echo "$BACKUP" > "$DS_HOME/ai-backup-original"
echo "  done"

UI_TMP="$DS_HOME/api-server/.ui-installing"
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
restore() {
  set +e
  echo >&2
  echo "Installation failed, restoring the backup $BACKUP" >&2
  rm -rf "$UI_TMP"
  local failed=""
  for component in "${COMPONENT_LIST[@]}"; do
    dir=$(server_dir "$component")
    restore_dir "$BACKUP/$component-server/libs" "$dir/libs" || failed="$failed $dir/libs"
    if [ "$component" = api ]; then
      if [ -d "$BACKUP/api-server/ui" ]; then
        restore_dir "$BACKUP/api-server/ui" "$dir/ui" || failed="$failed $dir/ui"
      fi
      cp -a "$BACKUP/api-server/application.yaml" "$dir/conf/application.yaml" 2>/dev/null
      cmp -s "$BACKUP/api-server/application.yaml" "$dir/conf/application.yaml" \
        || failed="$failed $dir/conf/application.yaml"
    fi
  done
  if [ -n "$failed" ]; then
    echo "RESTORE INCOMPLETE, these are not the same as the backup:$failed" >&2
    echo "Fix the permissions, then run: $PACKAGE_DIR/rollback.sh $DS_HOME $BACKUP" >&2
    exit 2
  fi
  echo "Restored and verified, the servers are unchanged." >&2
  exit 1
}
trap restore ERR

echo "== Install"
if [ -s "$CONFLICTS_FILE" ]; then
  while read -r jar; do
    rm -f "$jar"
  done < "$CONFLICTS_FILE"
  echo "  removed $(wc -l < "$CONFLICTS_FILE" | tr -d ' ') other versions of the jars"
fi
for component in "${COMPONENT_LIST[@]}"; do
  dir=$(server_dir "$component")
  if [ "$component" = api ]; then
    REMOVED=0
    while read -r jar; do
      if [ -n "$jar" ] && [ -f "$dir/libs/$jar" ]; then
        rm -f "$dir/libs/$jar"
        REMOVED=$((REMOVED + 1))
      fi
    done < "$PACKAGE_DIR/remove-from-api-server-libs.txt"
    echo "  api-server: removed $REMOVED old AWS SDK jars"
  fi
  cp "$PACKAGE_DIR/$component-server-libs"/*.jar "$dir/libs/"
  echo "  $component-server: copied $(ls "$PACKAGE_DIR/$component-server-libs"/*.jar | wc -l | tr -d ' ') jars"
  if [ "$component" = api ]; then
    rm -rf "$UI_TMP" && mkdir -p "$UI_TMP"
    # GNU tar warns about the extended headers of macOS tar for every file, they are not needed
    if tar --version 2>/dev/null | grep -q 'GNU tar'; then
      tar --warning=no-unknown-keyword -xzf "$PACKAGE_DIR/ui/dolphinscheduler-ui-3.4.2-ai.tar.gz" -C "$UI_TMP"
    else
      tar -xzf "$PACKAGE_DIR/ui/dolphinscheduler-ui-3.4.2-ai.tar.gz" -C "$UI_TMP"
    fi
    [ -f "$UI_TMP/index.html" ]
    rm -rf "$dir/ui" && mv "$UI_TMP" "$dir/ui"
    echo "  api-server: installed the ui"
    if $CONFIGURE; then
      {
        [ -n "$(tail -c 1 "$dir/conf/application.yaml")" ] && echo
        echo
        grep -v '^# Append to' "$PACKAGE_DIR/application-ai.yaml" \
          | sed "s#^\(    home: \).*#\1\"$SEATUNNEL_HOME\"#"
      } >> "$dir/conf/application.yaml"
      echo "  api-server: appended the 'ai:' section to conf/application.yaml"
    fi
    # Two versions of an AWS SDK jar must not be in the classpath
    if ls "$dir/libs" | grep -Fxq -f <(grep -v '^[[:space:]]*$' "$PACKAGE_DIR/remove-from-api-server-libs.txt"); then
      echo "  old AWS SDK jars still exist" >&2
      false
    fi
  fi
  for jar in "$PACKAGE_DIR/$component-server-libs"/*.jar; do
    cmp -s "$jar" "$dir/libs/$(basename "$jar")"
  done
done
echo "  verified the installed files"
trap - ERR

cat <<EOF

Installed: $COMPONENTS. Backup: $BACKUP

Next steps:
  1. Make sure the SQL files were run on the metadata database (this script never touches the database)
  2. Make sure the api server's IAM role can call Bedrock (see DEPLOY.md)
  3. Start the servers: $DS_HOME/bin/dolphinscheduler-daemon.sh start <api-server|master-server|worker-server>
Roll back: $PACKAGE_DIR/rollback.sh $DS_HOME
EOF
