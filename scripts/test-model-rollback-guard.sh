#!/usr/bin/env bash
set -euo pipefail
qa_model_root="$(cd "$(dirname "$0")/.." && pwd)"
qa_model_tmp=$(mktemp -d /tmp/finance-model-rollback.XXXXXX)
trap 'rm -rf "$qa_model_tmp"' EXIT
mkdir -p "$qa_model_tmp/bin" "$qa_model_tmp/jar/BOOT-INF/classes" "$qa_model_tmp/app"
ln -s "$qa_model_root/scripts/test-fixtures/mysql-model-guard.sh" "$qa_model_tmp/bin/mysql"
make_jar(){
  printf 'app:\n  version: ${APP_VERSION:%s}\n' "$1" > "$qa_model_tmp/jar/BOOT-INF/classes/application.yml"
  jar --create --file "$2" -C "$qa_model_tmp/jar" BOOT-INF/classes/application.yml
}
make_jar 1.43.0 "$qa_model_tmp/app/app.jar"
make_jar 1.41.0 "$qa_model_tmp/app/app.jar.prev"
qa_model_before=$(sha256sum "$qa_model_tmp/app/app.jar" | cut -d' ' -f1)
check(){
  local expected="$1"; shift
  local code=0
  env PATH="$qa_model_tmp/bin:$PATH" FINANCE_APP_DIR="$qa_model_tmp/app" DB_PASS=synthetic "$@" \
    bash "$qa_model_root/deploy/rollback.sh" --expect-current 1.43.0 --check-only > "$qa_model_tmp/output" 2>&1 || code=$?
  if [[ "$expected" == allow ]]; then [[ "$code" == 0 ]]; else [[ "$code" != 0 ]]; fi
  [[ "$(sha256sum "$qa_model_tmp/app/app.jar" | cut -d' ' -f1)" == "$qa_model_before" ]]
  printf 'PASS %s\n' "$*"
}
check allow MODEL_TEST_COUNT=0 MODEL_TEST_NEED_SF=1
check reject MODEL_TEST_COUNT=1
check reject MODEL_TEST_COUNT=not-a-number
check reject MODEL_TEST_ERROR=1
make_jar 1.40.1 "$qa_model_tmp/app/app.jar.prev"
check allow MODEL_TEST_COUNT=0 MODEL_TEST_FORBID_SF=1
make_jar unknown "$qa_model_tmp/app/app.jar.prev"
check reject MODEL_TEST_COUNT=0
grep -q '无法读取回滚目标版本' "$qa_model_tmp/output"
make_jar 1.42.0 "$qa_model_tmp/app/app.jar.prev"
check allow MODEL_TEST_ERROR=1
printf 'PASS 7 model rollback checks; synthetic jars unchanged; no service or database operation\n'
