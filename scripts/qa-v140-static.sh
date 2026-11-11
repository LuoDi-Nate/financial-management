#!/usr/bin/env bash
set -eu
qa140_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$qa140_root"
bash scripts/qa-v139-static.sh
qa140_pass=0
check(){ if "$@"; then qa140_pass=$((qa140_pass+1)); else echo "FAIL: $*" >&2; exit 1; fi; }
java=src/main/java/com/family/finance
check test -f db/migration/V80__goal_forecast_preferences.sql
check test -f tech-design/v1.40.md
check test -f scripts/e2e/flows/61-goal-indicators.cjs
check rg -q 'RoundingMode.CEILING' "$java/calc/GoalCashflowCalculator.java"
check rg -q 'State.ACHIEVED' "$java/calc/GoalCashflowCalculator.java"
check rg -q 'minusMonths\(6\)' "$java/service/goal/MonthlyContributionService.java"
check rg -q 'closedPeriodIds' "$java/service/goal/MonthlyContributionService.java"
check rg -q 'facts.cashflowBreakdown' "$java/service/goal/MonthlyContributionService.java"
check rg -q 'source=NULL|forecast_source' db/migration/V80__goal_forecast_preferences.sql
check rg -q 'WHERE family_id = #\{familyId\} AND id = #\{goalId\} AND archived_at IS NULL' "$java/repository/GoalMapper.java"
check rg -q "CASE WHEN.*MANUAL.*forecast_manual_currency" "$java/repository/GoalMapper.java"
check rg -q 'GoalMetric.NET_PRINCIPAL,GoalMetric.TOTAL_ASSETS' "$java/service/goal/GoalForecastService.java"
check rg -q 'GoalComparator.GTE' "$java/service/goal/GoalForecastService.java"
check rg -q 'goalsHistoricalReport' "$java/web/report/ReportsController.java"
check rg -q 'goalsHistoricalReport != true' src/main/resources/templates/goals/_progress-strip.html
check rg -q 'goal_forecasts.csv' "$java/service/export/CsvExportService.java"
check rg -q 'estimateSource.*estimateMonthlyAmount.*estimateWishMonth' "$java/web/goal/GoalForecastController.java"
check node --check src/main/resources/static/js/goal-estimate.js
check node --check scripts/e2e/flows/61-goal-indicators.cjs
if rg -q 'Math.ceil|new Date\(' src/main/resources/static/js/goal-estimate.js; then echo 'FAIL: 前端重复日期公式' >&2; exit 1; fi
qa140_pass=$((qa140_pass+1))
check rg -q 'data-retire-insights' src/main/resources/templates/goals/retirement.html
check rg -q 'goals/_ai-alert :: alert' src/main/resources/templates/goals/retirement.html
check rg -q 'contextFingerprint\(snapshot, savedForecast\)' "$java/web/goal/RetirementController.java"
check rg -q 'forecastContext\(progress.forecast\(\)\)' "$java/service/goal/GoalLlmService.java"
echo "PASS: v1.40 $qa140_pass 项只读静态守护(另含v1.39原53项)"
