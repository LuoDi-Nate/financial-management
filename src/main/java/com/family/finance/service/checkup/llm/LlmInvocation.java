package com.family.finance.service.checkup.llm;

import java.util.Optional;

/**
 * v1.13 · 一次调用的<b>完整坐标</b>:去哪个平台(端点+凭据)、用哪个系列、用哪个型号。
 *
 * <p>v0.14 到 v1.12 只有一个 {@code primaryVendor} 字符串在担这三件事,于是
 * 「平台 = 模型系列 = 型号前缀」被默认成同一件事。火山方舟一来就穿帮:同一个平台上
 * 同时挂着豆包和 DeepSeek,而 DeepSeek 官方平台上也有 DeepSeek —— 「用 deepseek」
 * 这句话不再能确定去哪个端点、扣谁的钱。所以这里必须是三元组。</p>
 *
 * @param platform 平台 code(见 {@link LlmCatalog})· 决定 baseUrl + 用哪把 key
 * @param family   模型系列 code · 决定「自动」时的默认型号与可选清单
 * @param model    型号;<b>null = 自动</b>(百炼走多型号轮询,其余用系列默认)
 */
public record LlmInvocation(String platform, String family, String model,
        @com.fasterxml.jackson.annotation.JsonIgnore ResolvedLlmTarget target) {

    public LlmInvocation(String platform, String family, String model) { this(platform, family, model, null); }
    public LlmInvocation withTarget(ResolvedLlmTarget value) { return new LlmInvocation(platform, family, model, value); }
    public String sourceJson() { return target == null ? null : target.sourceJson(resolvedModel(), family); }
    @Override public String toString() { return "LlmInvocation[" + label() + "]"; }
    @Override public boolean equals(Object other) {
        return other instanceof LlmInvocation x && java.util.Objects.equals(platform, x.platform)
            && java.util.Objects.equals(family, x.family) && java.util.Objects.equals(model, x.model);
    }
    @Override public int hashCode() { return java.util.Objects.hash(platform, family, model); }

    public static LlmInvocation of(String platform, String family, String model) {
        return new LlmInvocation(platform, family, LlmCatalog.normalizeModel(platform, model));
    }

    public boolean auto() { return model == null; }

    public boolean sameStrategy(LlmInvocation other) {
        if (other == null || !platform.equals(other.platform) || !family.equals(other.family)) return false;
        if (platformDef().map(LlmCatalog.Platform::modelRotation).orElse(false))
            return java.util.Objects.equals(model, other.model);
        return java.util.Objects.equals(resolvedModel(), other.resolvedModel());
    }

    public Optional<LlmCatalog.Platform> platformDef() { return LlmCatalog.platform(platform); }

    public Optional<LlmCatalog.Family> familyDef() {
        return platformDef().flatMap(p -> p.family(family));
    }

    /**
     * 实际发给对方的型号:显式填了就用填的;否则用系列默认。
     * 系列要求显式填型号(方舟)而又没填 → 返回 null,由 {@link LlmRouter} 在编排时就把它剔掉,
     * 不让「没填型号」变成一次注定失败的出网调用。
     */
    public String resolvedModel() {
        if (model != null) return model;
        return familyDef().map(LlmCatalog.Family::defaultModel).orElse(null);
    }

    /** 配置是否自洽(平台/系列存在,且该系列在要求显式型号时确实填了) */
    public boolean resolvable() {
        return platformDef().isPresent() && familyDef().isPresent()
                && (!familyDef().get().requiresExplicitModel() || model != null);
    }

    /** 审计 / 日志用的短标识:{@code ark/doubao:ep-2024xxxx} · 不含任何凭据 */
    public String label() {
        return platform + "/" + family + (model == null ? ":auto" : ":" + model);
    }

    /**
     * 面向用户的小徽记(体检面板 / 透视洞察右上角那行 mono 小字):{@code dashscope · qwen-plus}。
     *
     * <p>v1.12 那里显示的是 {@code vendor()},也就是 "qwen" / "deepseek" —— 三级模型下这不够用了:
     * 「deepseek」既可能是 DeepSeek 官方也可能是方舟托管的,看不出扣的是谁的钱。所以带上平台。
     * 型号为「自动」时如实写 auto(百炼每次随机轮询,答这一次的到底是池子里哪个,客户端没回报)。</p>
     */
    public String badge() {
        return platform + " · " + (model == null ? "auto" : model);
    }

    /** 给用户看的中文标识:{@code 火山方舟 · 豆包 Doubao · ep-2024xxxx} */
    public String display() {
        String p = LlmCatalog.labelOf(platform);
        String f = familyDef().map(LlmCatalog.Family::label).orElse(family);
        return p + " · " + f + " · " + (model == null ? "自动" : model);
    }
}
