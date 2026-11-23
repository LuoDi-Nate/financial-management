#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java=src/main/java/com/family/finance
rg -qF '@JsonInclude(JsonInclude.Include.NON_DEFAULT)' "$java/domain/goal/RetirementPlan.java"
rg -qF 'scopeMode = netOfLiabilities ? "FAMILY_FINANCIAL" : value;' "$java/domain/goal/RetirementPlan.java"
rg -qF 'problems, data.fundingComplete()' "$java/service/goal/RetirementPlanService.java"
rg -qF 'return result.progressAssets();' "$java/service/goal/RetirementPlanService.java"
rg -qF 'return result.progressTarget();' "$java/service/goal/RetirementPlanService.java"
test "$(rg -c '_net-funding :: rows' src/main/resources/templates/goals/retirement.html)" -eq 2
node --check scripts/e2e/flows/61-goal-indicators.cjs
echo 'PASS v1.43: 7 net funding guards'
