#!/usr/bin/env bash
# Read-only architecture guards; no HTTP or database mutations.
set -eu
qa139_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$qa139_root"
qa139_pass=0
check() { if "$@"; then qa139_pass=$((qa139_pass+1)); else echo "FAIL: $*" >&2; exit 1; fi; }
code() { sed -E -e 's@^[[:space:]]*//.*$@@' -e 's@[[:space:]]//.*$@@' -e 's@^[[:space:]]*\*.*$@@' -e 's@/\*.*\*/@@g' "$1"; }
java=src/main/java/com/family/finance
check test -f tech-design/v1.39.md
check test -f db/rollback/v1.39.sql
check rg -q 'retirementPlans.active\(' "$java/service/goal/GoalProgressService.java"
check rg -q 'retirement.result\(\).reached\(' "$java/service/goal/GoalProgressService.java"
check rg -q 'facts.cashflowBreakdown\(slice, id\)' "$java/service/goal/RetirementDataService.java"
check rg -q 'closedPeriodIds\(' "$java/service/goal/RetirementDataService.java"
check rg -q 'RetirementRateSources.historicalSlice' "$java/service/goal/RetirementDataService.java"
check rg -q 'actualSnapshots.contains' "$java/service/goal/RetirementDataService.java"
if code "$java/service/goal/RetirementPlanService.java" | rg -q '\.setParamsJson\('; then echo 'FAIL: 退休计划覆盖旧参数' >&2; exit 1; fi
qa139_pass=$((qa139_pass+1))
check rg -q 'retirementExpenseNature.natureOf\(' "$java/service/expense/ExpenseLedgerService.java"
check rg -q 'router.invoke\(FAMILY_ID, trace' "$java/service/expense/imports/MerchantAiClassifier.java"
if code "$java/service/expense/imports/MerchantAiClassifier.java" | rg -q '(RestTemplate|\.primary\()'; then echo 'FAIL: 分类绕过路由' >&2; exit 1; fi
qa139_pass=$((qa139_pass+1))
check rg -q 'if \(sink.delivered\) throw e' "$java/service/ask/runtime/LocalToolLoopRuntime.java"
check rg -q 'LlmRouter.timeoutSeconds\(configService' "$java/service/ask/runtime/LocalToolLoopRuntime.java"
check rg -q 'LlmRouter.timeoutSeconds\(configService' "$java/service/checkup/llm/AbstractOpenAiCompatibleClient.java"
if sed -n '/public String page(/,/^    @PostMapping/p' "$java/web/admin/AiAccessController.java" | rg -q 'managedAgentRuntime.templateDrift\('; then echo 'FAIL: 设置页同步查模板' >&2; exit 1; fi
qa139_pass=$((qa139_pass+1))
check rg -q 'generateNowResult' "$java/web/goal/GoalController.java"
check rg -q 'context_fingerprint' "$java/repository/GoalAiReportMapper.java"
check rg -q 'retirement_plans.csv' "$java/service/export/CsvExportService.java"
for qa139_file in docker/clean-dev-data.sh deploy/deploy.sh deploy/_deploy-macos.sh; do check rg -q 'TRUNCATE TABLE retirement_plan;' "$qa139_file"; done
check rg -q 'referrerpolicy="no-referrer"' src/main/resources/templates/goals/retirement.html
check rg -q "addEventListener\('vpchange'" src/main/resources/static/js/retirement.js
check rg -q 'FAMILY_FINANCIAL' "$java/service/goal/RetirementDataService.java"
check rg -q 'reportSavings\(' "$java/service/goal/RetirementDataService.java"
check rg -q 'points.size\(\)<12' "$java/service/goal/RetirementRateSources.java"
check rg -q 'year=last-9' "$java/service/goal/RetirementRateSources.java"
check rg -q 'previousRevisionJson|PreviousRevisionJson' "$java/service/goal/RetirementPlanService.java"
check rg -q 'source=.seed.' db/migration/V79__verified_macro_sources.sql
check rg -q 'data-retire-live' src/main/resources/templates/goals/retirement.html
if rg -q 'name="monthlyIncome"|field="\*\{monthlyIncome\}"' src/main/resources/templates/goals/retirement.html; then echo 'FAIL: 标准表单仍收集持续收入' >&2; exit 1; fi
qa139_pass=$((qa139_pass+1))
check rg -q 'testStart = await guard.begin' scripts/e2e/lib/browser.cjs
for qa139_file in scripts/e2e/flows/50-account-delete-real-case.cjs scripts/e2e/flows/52-old-fixes.cjs scripts/e2e/flows/55-iphone-duo.cjs; do
  check rg -q 'ignore-table=.*llm_test_meter' "$qa139_file"
done
check rg -q 'beforeSend\(' "$java/service/checkup/llm/AbstractOpenAiCompatibleClient.java"
check rg -q 'llmTestGate.block|testGate.block' "$java/service/checkup/llm/LlmRouter.java"
check rg -q 'validateConfiguration\(true\)' src/main/resources/static/js/retirement.js
check rg -q 'select.setAttribute\(.data-lsel.' src/main/resources/static/js/retirement.js
check rg -q 'configurationComplete\(' "$java/service/goal/RetirementPlanService.java"
check rg -qF 'p.getVersion()>=2 ? data.selectedAssets()' "$java/service/goal/RetirementPlanService.java"
check rg -qF 'p.getVersion()>=2 ? data.debt()' "$java/service/goal/RetirementPlanService.java"
check rg -q 'data-retire-config-status' src/main/resources/templates/goals/retirement.html
check rg -q 'data-retire-reference' src/main/resources/templates/goals/retirement.html
check rg -q 'view.progressAssets\(\), view.progressTarget\(\)' "$java/service/goal/GoalProgressService.java"
check rg -q '目标进度 = A ÷ F' src/main/resources/templates/goals/retirement.html
if code "$java/service/goal/RetirementPlanService.java" | rg -q 'throw new IllegalArgumentException\(String.join'; then echo 'FAIL: 实时演算缺失阻塞配置保存' >&2; exit 1; fi
qa139_pass=$((qa139_pass+1))
check python3 scripts/family-isolation-audit.py
echo "PASS: v1.39 $qa139_pass 项只读静态守护"
