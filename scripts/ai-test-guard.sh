#!/usr/bin/env bash

finance_ai_guard_begin() {
  FINANCE_AI_GUARD_FILE="$(mktemp /tmp/finance-ai-guard.XXXXXX)" || return 1
  node "$(dirname "${BASH_SOURCE[0]}")/ai-test-guard.cjs" begin "$1" "$FINANCE_AI_GUARD_FILE"
}

finance_ai_guard_end() {
  local guard_rc=0
  node "$(dirname "${BASH_SOURCE[0]}")/ai-test-guard.cjs" end "$1" "$FINANCE_AI_GUARD_FILE" || guard_rc=$?
  rm -f "$FINANCE_AI_GUARD_FILE"
  return "$guard_rc"
}
