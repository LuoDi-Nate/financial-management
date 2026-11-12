package com.family.finance.web.admin;

import com.family.finance.service.checkup.llm.LlmCatalog;
import com.family.finance.service.checkup.llm.LlmInvocation;
import com.family.finance.service.checkup.llm.LlmSettings;
import com.family.finance.service.checkup.llm.LlmProbeTarget;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * 管理页「AI 大模型」那一节要显示的东西(凭据状态 / 三组三元组 / 型号目录 / 调用参数)。
 *
 * <p>v1.24.5 · 这一节从「数据源接入」挪到了「AI 接入」(维护者定:AI 的东西放一处)。
 * 原来它和超级 Agent 的配置分在两页:百炼的 Key、型号在数据源接入,Agent / MCP / 口令在 AI 接入,
 * 排查一个问题要在两页之间来回切;代码里的错误提示也在两页之间互相指。</p>
 *
 * <p>抽成组件而不是塞进某个控制器:AI 接入页要用它渲染,端点那边(截图识别能不能开)也要用它判。</p>
 */
@Component
@RequiredArgsConstructor
public class LlmSettingsView {

    private final FamilyConfigService configService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public record CredentialCard(String code, String label, boolean ready, String masked,
                                 String consoleUrl, String howTo, String probe) {}

    

    /**
     * v1.13 · 管理页 LLM 段的展示数据。<b>当前生效值一律从 {@link LlmSettings} 读</b>,
     * 而不是直接读配置键 —— 老家庭升级后新键还是空的,直接读键页面会显示成「什么都没选」,
     * 但实际调用走的是派生出来的百炼/通义千问。让页面和实际调用说同一件事。
     */
    public void addLlmAttributes(long fid, Model model) {
        // 调用参数(原来在数据源接入页的 page() 里单独塞,跟着这一节一起搬过来)
        model.addAttribute("llmMaxTokens",      configService.getInt(fid,  FamilyConfigService.K_LLM_MAX_TOKENS, 2000));
        model.addAttribute("llmTimeoutSeconds", configService.getInt(fid,  FamilyConfigService.K_LLM_TIMEOUT_SECS, 25));
        model.addAttribute("llmTemperature",    configService.getString(fid, FamilyConfigService.K_LLM_TEMPERATURE, "0.5"));
        LlmSettings s = LlmSettings.load(configService, fid);
        var cards = LlmCatalog.PLATFORMS.stream().map(p -> new CredentialCard(p.code(), p.label(),
                configService.isPrivateKeyConfigured(fid, p.keyName()), configService.maskedSecret(fid, p.keyName()),
                p.keyConsoleUrl(), p.keyHowTo(), LlmProbeTarget.resolve(s, p).map(LlmProbeTarget::display)
                        .orElse("请先选择型号并保存"))).toList();
        model.addAttribute("llmCredentialCards", cards);
        model.addAttribute("llmKeyCount", cards.stream().filter(CredentialCard::ready).count());
        model.addAttribute("llmPlatformCount", cards.size());
        model.addAttribute("llmModelPattern", LlmCatalog.MODEL_PATTERN);
        model.addAttribute("llmModelMaxLength", LlmCatalog.MAX_MODEL_LENGTH);
        model.addAttribute("llmVisionPlatformsLabel", platformsWith(LlmCatalog.Modality.VISION).stream()
                .map(LlmCatalog.Platform::label).collect(java.util.stream.Collectors.joining(" / ")));
        model.addAttribute("llmLegacy", s.legacy());   // 还没保存过新配置 → 页面提示「保存一次即可固化」
        // 三把 key 各自的「已配/未配」(永不回显值)
        model.addAttribute("qwenKeyConfigured",     configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_QWEN_KEY));
        model.addAttribute("deepseekKeyConfigured", configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY));
        model.addAttribute("arkKeyConfigured",      configService.isPrivateKeyConfigured(fid, FamilyConfigService.K_LLM_ARK_KEY));
        // v1.17.2 · 可辨认掩码(留头尾):用户手上常有多把 key,只说"已配置"他没法确认当前是哪一把
        model.addAttribute("qwenKeyMasked",     configService.maskedSecret(fid, FamilyConfigService.K_LLM_QWEN_KEY));
        model.addAttribute("deepseekKeyMasked", configService.maskedSecret(fid, FamilyConfigService.K_LLM_DEEPSEEK_KEY));
        model.addAttribute("arkKeyMasked",      configService.maskedSecret(fid, FamilyConfigService.K_LLM_ARK_KEY));
        // v1.17.2 · 「用哪个模型」与上面的凭据表单级联:没配 key 的平台在下拉里不可选。
        // 判据用【平台 code → 有没有 key】的映射,而不是在模板里逐个 if —— 以后加第四家平台时
        // 只要这里多一行,三处下拉自动跟上(v0.14 加 METAL 那次就是漏了模板里的硬编码分支)。
        // v1.18.4 · 截图识别能不能开,取决于「有没有一家【既配了密钥、又有视觉能力】的平台」。
        //   DeepSeek 没有视觉能力 —— 只配了它的用户,视觉下拉里一个可选项都没有。
        //   此前那个 checkbox 照样可勾,勾了必然存不下去(而且报的是「请选择平台」,
        //   指向一个他根本选不了的下拉)。现在直接禁用并说清去配哪家。
        model.addAttribute("visionCapableReady", !visionCapablePlatforms(fid).isEmpty());
        model.addAttribute("platformReady", cards.stream().collect(java.util.stream.Collectors.toMap(
                CredentialCard::code, CredentialCard::ready)));
        // 主选三元组
        model.addAttribute("llmPlatform",       s.primary().platform());
        model.addAttribute("llmFamily",         s.primary().family());
        model.addAttribute("llmModelId",        s.primary().model() == null ? "" : s.primary().model());
        model.addAttribute("llmPrimaryDisplay", s.primary().display());
        // 备选三元组(可为空 = 不设备选)
        LlmInvocation backup = s.backup().orElse(null);
        model.addAttribute("llmBackupPlatform", backup == null ? "" : backup.platform());
        model.addAttribute("llmBackupFamily",   backup == null ? "" : backup.family());
        model.addAttribute("llmBackupModelId",  backup == null || backup.model() == null ? "" : backup.model());
        model.addAttribute("llmBackupDisplay",  backup == null ? "未设置" : backup.display());
        // 视觉三元组 + 独立开关(FR-362:开关不再编码在型号里)
        model.addAttribute("llmVisionEnabled",  s.visionEnabled());
        model.addAttribute("llmVisionPlatform", s.vision().platform());
        model.addAttribute("llmVisionFamily",   s.vision().family());
        model.addAttribute("llmVisionModelId",  s.vision().model() == null ? "" : s.vision().model());
        model.addAttribute("llmVisionDisplay",  s.vision().display());
        // 型号目录(级联下拉的唯一数据源 · FR-364)· 服务端注入,模板里不再有第二份写死清单
        model.addAttribute("llmCatalogJson", catalogJson());
        // 平台下拉的 option 仍由服务端渲染(而不是全交给 JS):JS 挂了的话至少还能提交一组合法平台,
        // 而且「哪些平台能做视觉」这件事按目录如实过滤 —— DeepSeek 官方没有视觉系列,就不该出现在截图识别里。
        model.addAttribute("llmPlatformsText",   platformsWith(LlmCatalog.Modality.TEXT));
        model.addAttribute("llmPlatformsVision", platformsWith(LlmCatalog.Modality.VISION));
        Object draft = model.asMap().get("llmDraft");
        if (draft instanceof java.util.Map<?, ?> values) values.forEach((key, value) -> model.addAttribute(key.toString(), value));
    }

    /** 支持该形态的平台(视觉:DeepSeek 官方没有视觉系列 → 不出现在截图识别的平台下拉里) */
    private static java.util.List<LlmCatalog.Platform> platformsWith(LlmCatalog.Modality m) {
        return LlmCatalog.PLATFORMS.stream().filter(p -> !p.families(m).isEmpty()).toList();
    }

    /**
     * 把 {@link LlmCatalog} 序列化给前端级联用。<b>只挑前端需要的字段</b> ——
     * {@code keyName} / {@code baseUrl} 是服务端实现细节,没有理由出现在 HTML 里。
     */
    private String catalogJson() {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (LlmCatalog.Platform p : LlmCatalog.PLATFORMS) {
            java.util.List<java.util.Map<String, Object>> fams = new java.util.ArrayList<>();
            for (LlmCatalog.Family f : p.families()) {
                java.util.List<java.util.Map<String, Object>> models = f.models().stream()
                        .map(m -> java.util.Map.<String, Object>of("id", m.id(), "label", m.label()))
                        .toList();
                java.util.Map<String, Object> fm = new java.util.LinkedHashMap<>();
                fm.put("code", f.code());
                fm.put("label", f.label());
                fm.put("modality", f.modality().name());
                fm.put("mustFillModel", f.requiresExplicitModel());
                fm.put("defaultModel", f.defaultModel() == null ? "" : f.defaultModel());
                fm.put("models", models);
                fams.add(fm);
            }
            java.util.Map<String, Object> pm = new java.util.LinkedHashMap<>();
            pm.put("code", p.code());
            pm.put("label", p.label());
            pm.put("keyHowTo", p.keyHowTo());
            pm.put("modelRotation", p.modelRotation());
            pm.put("families", fams);
            out.add(pm);
        }
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** v1.18.4 · 「已配密钥 且 有视觉能力」的平台 —— 截图识别能不能开,取决于它非空。 */
    public java.util.List<LlmCatalog.Platform> visionCapablePlatforms(long fid) {
        return LlmCatalog.PLATFORMS.stream()
                .filter(p -> !p.families(LlmCatalog.Modality.VISION).isEmpty())
                .filter(p -> configService.isPrivateKeyConfigured(fid, p.keyName()))
                .toList();
    }
}
