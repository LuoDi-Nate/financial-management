#!/usr/bin/env bash
# Standalone design experiment; no application build, database or remote/model request.
set -euo pipefail
task_dir=$(cd "$(dirname "$0")" && pwd)
task_tmp=$(mktemp -d /tmp/finance-v142-transport.XXXXXX)
trap 'rm -rf "$task_tmp"' EXIT
task_m2="${MAVEN_USER_HOME:-$HOME/.m2}/repository"
task_cp=""
for task_jar in \
  org/apache/httpcomponents/client5/httpclient5/5.3.1/httpclient5-5.3.1.jar \
  org/apache/httpcomponents/core5/httpcore5/5.2.4/httpcore5-5.2.4.jar \
  org/apache/httpcomponents/core5/httpcore5-h2/5.2.4/httpcore5-h2-5.2.4.jar \
  org/slf4j/slf4j-api/2.0.16/slf4j-api-2.0.16.jar \
  com/fasterxml/jackson/core/jackson-databind/2.17.2/jackson-databind-2.17.2.jar \
  com/fasterxml/jackson/core/jackson-core/2.17.2/jackson-core-2.17.2.jar \
  com/fasterxml/jackson/core/jackson-annotations/2.17.2/jackson-annotations-2.17.2.jar; do
  [[ -f "$task_m2/$task_jar" ]] || { printf 'Missing local dependency: %s\n' "$task_jar" >&2; exit 1; }
  task_cp="${task_cp:+$task_cp:}$task_m2/$task_jar"
done
keytool -genkeypair -alias fixture -keyalg RSA -storetype PKCS12 \
  -keystore "$task_tmp/fixture.p12" -storepass fixture-only -keypass fixture-only \
  -dname CN=gateway.invalid -ext SAN=dns:gateway.invalid -validity 2 >/dev/null 2>&1
javac -cp "$task_cp" -d "$task_tmp" "$task_dir/TransportSpike.java"
java -cp "$task_tmp:$task_cp" TransportSpike "$task_tmp/fixture.p12"
