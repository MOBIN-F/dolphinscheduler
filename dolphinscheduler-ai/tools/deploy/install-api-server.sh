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

# Install the AI assistant into an api server: backs up api-server/libs, removes the AWS SDK jars of the old version,
# copies the new jars and replaces the ui directory.
# Usage: install-api-server.sh <DOLPHINSCHEDULER_HOME>, then configure `ai:` in api-server/conf/application.yaml and
# restart the api server. Roll back by restoring the backup directories printed below.
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

cp -a "$LIBS" "$LIBS.bak-$SUFFIX"
echo "Backed up $LIBS to $LIBS.bak-$SUFFIX"

while read -r jar; do
  [ -n "$jar" ] && rm -f "$LIBS/$jar" && echo "Removed $jar"
done < "$PACKAGE_DIR/remove-from-api-server-libs.txt"

cp "$PACKAGE_DIR"/api-server-libs/*.jar "$LIBS/"
echo "Copied $(ls "$PACKAGE_DIR"/api-server-libs/*.jar | wc -l) jars to $LIBS"

if [ -d "$UI" ]; then
  mv "$UI" "$UI.bak-$SUFFIX"
  echo "Backed up $UI to $UI.bak-$SUFFIX"
fi
mkdir -p "$UI"
tar --warning=no-unknown-keyword -xzf "$PACKAGE_DIR"/ui/dolphinscheduler-ui-3.4.2-ai.tar.gz -C "$UI" 2>/dev/null \
  || tar -xzf "$PACKAGE_DIR"/ui/dolphinscheduler-ui-3.4.2-ai.tar.gz -C "$UI"
echo "Installed the ui to $UI"

# Two versions of the same AWS SDK jar must not exist
if ls "$LIBS" | grep -E '^(sdk-core|auth|regions|aws-core|utils)-2\.17\.282\.jar$' >/dev/null; then
  echo "WARNING: old AWS SDK jars still exist in $LIBS" >&2
fi
echo "Done. Configure 'ai:' in $DS_HOME/api-server/conf/application.yaml and restart the api server."
