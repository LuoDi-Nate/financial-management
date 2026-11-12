#!/usr/bin/env bash
set -eu
qa141_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$qa141_root"
qa141_pass=0
check(){ if "$@"; then qa141_pass=$((qa141_pass+1)); else echo "FAIL: $*" >&2; exit 1; fi; }
java=src/main/java/com/family/finance
llm="$java/service/checkup/llm"
tpl=src/main/resources/templates/admin/_llm-settings.html
check test -f tech-design/v1.41.md
check test -f "$llm/SiliconFlowLlmClient.java"
check rg -q 'extends AbstractOpenAiCompatibleClient' "$llm/SiliconFlowLlmClient.java"
check rg -q 'PLATFORMS = List.of.*SILICONFLOW' "$llm/LlmCatalog.java"
check rg -q 'K_LLM_SILICONFLOW_KEY' "$java/service/config/FamilyConfigService.java"
check rg -q 'FINANCE_LLM_SILICONFLOW_API_KEY' "$java/service/config/FamilyConfigService.java"
check rg -q 'LlmCatalog.PLATFORMS.stream' "$java/web/admin/LlmSettingsView.java"
check rg -qF 'th:each="card : ${llmCredentialCards}"' "$tpl"
check rg -q 'llmPlatformCount' "$tpl"
check rg -q 'LlmProbeTarget.resolve' "$java/web/admin/AiModelController.java"
check rg -q 'LlmProbeTarget.resolve' "$java/web/admin/LlmSettingsView.java"
check rg -q 'visionEnabled\(\)' "$llm/LlmProbeTarget.java"
check rg -q 'sameStrategy' "$java/web/admin/AiModelController.java"
check rg -q 'Transactional' "$java/web/admin/AiModelController.java"
check rg -q 'LlmFailure.describe' "$java/service/holdingimport/VisionLlmClient.java"
check rg -q 'LlmFailure.describe' "$java/service/ask/runtime/LocalToolLoopRuntime.java"
check rg -q 'data-lsel' "$tpl"
check rg -q '/js/lens-select.js' "$tpl"
check rg -q 'data-model-pattern' "$tpl"
check node --check src/main/resources/static/js/llm-settings.js
check test -f src/test/java/com/family/finance/service/checkup/llm/LlmIntegrationV141Test.java
check test -f src/test/java/com/family/finance/web/admin/AiPlatformV141Test.java
check test -f src/test/java/com/family/finance/service/ask/runtime/LocalToolLoopSiliconflowTest.java
check test -f scripts/e2e/flows/62-ai-platforms.cjs
check node --check scripts/e2e/flows/62-ai-platforms.cjs
if rg -q 'fetch\(|XMLHttpRequest|api.siliconflow' src/main/resources/static/js/llm-settings.js; then echo 'FAIL: 配置脚本不得直接调用外部模型' >&2; exit 1; fi
qa141_pass=$((qa141_pass+1))
echo "PASS: v1.41 $qa141_pass 项只读静态守护"
