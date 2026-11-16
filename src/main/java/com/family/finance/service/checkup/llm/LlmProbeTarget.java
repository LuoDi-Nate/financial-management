package com.family.finance.service.checkup.llm;

import java.util.ArrayList;
import java.util.Optional;

/** 页面与连接检查共用已保存的目标;检查自动策略时固定一次型号。 */
public record LlmProbeTarget(LlmInvocation invocation, String source) {
    public static Optional<LlmProbeTarget> resolve(LlmSettings settings, LlmCatalog.Platform platform) {
        var candidates = new ArrayList<LlmInvocation>(settings.chain());
        if (settings.visionEnabled()) candidates.add(settings.vision());
        for (LlmInvocation candidate : candidates) {
            if (platform.code().equals(candidate.platform()) && candidate.resolvable()) {
                return Optional.of(new LlmProbeTarget(pin(candidate), "已保存型号"));
            }
        }
        return platform.families(LlmCatalog.Modality.TEXT).stream()
                .filter(f -> !f.requiresExplicitModel()).findFirst()
                .map(f -> new LlmProbeTarget(new LlmInvocation(platform.code(), f.code(), f.defaultModel()), "参考文字型号"));
    }

    private static LlmInvocation pin(LlmInvocation inv) {
        return new LlmInvocation(inv.platform(), inv.family(), inv.resolvedModel());
    }

    public String display() { return invocation.resolvedModel() + " · " + source; }
}
