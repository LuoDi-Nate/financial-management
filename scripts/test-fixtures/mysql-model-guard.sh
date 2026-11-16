#!/usr/bin/env bash
# Synthetic command double for rollback --check-only; never opens a database connection.
[[ "${MODEL_TEST_ERROR:-0}" != 1 ]] || exit 1
[[ "${MODEL_TEST_NEED_SF:-0}" != 1 || "$*" == *"'siliconflow'"* ]] || exit 2
[[ "${MODEL_TEST_FORBID_SF:-0}" != 1 || "$*" != *"'siliconflow'"* ]] || exit 2
[[ "$*" != *"'custom'"* ]] || exit 2
printf '%s\n' "${MODEL_TEST_COUNT:-0}"
