#!/usr/bin/env bash
set -eu
qa142_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$qa142_root"
qa142_pass=0
qa142_java=src/main/java/com/family/finance
qa142_llm="$qa142_java/service/checkup/llm"
java_code_only(){ sed -E -e 's@^[[:space:]]*//.*$@@' -e 's@[[:space:]]//.*$@@' -e 's@^[[:space:]]*\*.*$@@' -e 's@/\*.*\*/@@g' "$1"; }
check(){ if "$2"; then qa142_pass=$((qa142_pass+1)); printf 'PASS %s\n' "$1"; else printf 'FAIL %s\n' "$1" >&2; exit 1; fi; }
atomic(){
  rg -q '@Transactional' "$qa142_java/service/config/CustomLlmConfigService.java" &&
  rg -q 'mapper.lockFamily\(fid\)' "$qa142_java/service/config/CustomLlmConfigService.java" &&
  rg -q 'mapper.upsert\(fid, KEY, encoded\)' "$qa142_java/service/config/CustomLlmConfigService.java" &&
  rg -q 'expectedEditToken' "$qa142_java/web/admin/AiModelController.java";
}
snapshot(){
  rg -q 'config.llmSnapshot\(fid\)' "$qa142_llm/LlmTargetResolver.java" &&
  rg -q 'invokePrepared' "$qa142_java/service/lens/LensAiTagService.java" &&
  rg -q 'prepareBatch\(familyId\)' "$qa142_java/service/holdingimport/HoldingImportService.java" &&
  rg -q 'sink.selected' "$qa142_java/service/ask/runtime/LocalToolLoopRuntime.java";
}
ready(){
  rg -q 'authMode.equals\("NONE"\)' "$qa142_java/service/config/CustomLlmConfigService.java" &&
  rg -q 'targets.configured' "$qa142_java/web/admin/AiModelController.java" &&
  rg -q 'targets.configured' "$qa142_java/web/admin/LlmSettingsView.java" &&
  rg -q 'targets.configured' "$qa142_java/service/holdingimport/VisionLlmClient.java";
}
policy(){
  rg -q 'validateAnswers' "$qa142_llm/CustomLlmTransport.java" &&
  rg -q 'mapped\(b\)' "$qa142_llm/CustomEndpointPolicy.java" &&
  rg -q 'DENIED_HOSTS' "$qa142_llm/CustomEndpointPolicy.java" &&
  rg -q 'inCidr' "$qa142_llm/CustomEndpointPolicy.java" &&
  test -f src/test/java/com/family/finance/service/checkup/llm/CustomEndpointPolicyTest.java;
}
transport(){
  test "$(rg -cF '.newCall(' "$qa142_llm/CustomLlmTransport.java")" = 1 &&
  rg -qF '.proxy(Proxy.NO_PROXY)' "$qa142_llm/CustomLlmTransport.java" &&
  rg -qF '.followRedirects(false).followSslRedirects(false)' "$qa142_llm/CustomLlmTransport.java" &&
  rg -qF '.retryOnConnectionFailure(false)' "$qa142_llm/CustomLlmTransport.java" &&
  rg -q 'gate.beforeSend' "$qa142_llm/CustomLlmTransport.java" &&
  rg -q 'gate.observedSend' "$qa142_llm/CustomLlmTransport.java";
}
model(){
  rg -q 'P_CUSTOM.equals\(platform\)' "$qa142_llm/LlmCatalog.java" &&
  rg -q 'normalizeModel\(p.code\(\), modelId\)' "$qa142_java/web/admin/AiModelController.java" &&
  rg -q "platform.value !== 'custom'" src/main/resources/static/js/llm-settings.js &&
  node --check src/main/resources/static/js/llm-settings.js;
}
surfaces(){
  rg -q 'transport.json' "$qa142_llm/CustomLlmClient.java" &&
  rg -q 'customTransport.json' "$qa142_java/service/holdingimport/VisionLlmClient.java" &&
  rg -q 'customTransport.open' "$qa142_java/service/ask/runtime/LocalToolLoopRuntime.java" &&
  rg -q 'targets.prepare' "$qa142_java/web/admin/AiModelController.java" &&
  test -f scripts/e2e/flows/63-custom-llm.cjs;
}
safe(){
  rg -q 'FamilyRuntimeConfigMapper: INFO' src/main/resources/application.yml &&
  rg -q 'PromptRecordMapper: INFO' src/main/resources/application.yml &&
  ! java_code_only "$qa142_llm/CustomLlmTransport.java" | rg -q 'log\.(info|debug|warn|error)\(' &&
  ! rg -q 'fetch\(|XMLHttpRequest' src/main/resources/static/js/llm-settings.js;
}
source(){
  for qa142_mapper in PromptRecord ReviewAiCache RebalanceAdviceCache GoalAiReport HoldingImport AskMessage; do
    rg -q 'source_json' "$qa142_java/repository/${qa142_mapper}Mapper.java" || return 1;
  done
  rg -q 'historical' "$qa142_java/service/llmtrace/LlmSource.java" &&
  rg -q 'sourceJson' "$qa142_java/service/llmtrace/PromptPeekView.java" &&
  node --check scripts/test-custom-llm-migration.cjs;
}
baseline(){
  rg -q 'modelRotation' "$qa142_llm/LlmInvocation.java" &&
  rg -q 'LlmCatalog.PLATFORMS.stream' "$qa142_java/web/admin/LlmSettingsView.java" &&
  rg -q 'sameStrategy' "$qa142_java/web/admin/AiModelController.java" &&
  test -f scripts/e2e/flows/62-ai-platforms.cjs &&
  node --check scripts/e2e/flows/63-custom-llm.cjs;
}
check v142-CUSTOM-CONFIG-ATOMIC atomic
check v142-CUSTOM-ONE-SNAPSHOT snapshot
check v142-CUSTOM-READY ready
check v142-CUSTOM-URL-POLICY policy
check v142-CUSTOM-TRANSPORT transport
check v142-CUSTOM-EXPLICIT-MODEL model
check v142-CUSTOM-ALL-SURFACES surfaces
check v142-CUSTOM-SAFE-FAILURE safe
check v142-CUSTOM-SOURCE-CACHE source
check v142-CUSTOM-BASELINE baseline
printf 'PASS: v1.42 %s 项只读静态守护\n' "$qa142_pass"
