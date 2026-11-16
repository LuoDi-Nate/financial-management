package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.family.finance.service.config.FamilyConfigService.*;

/**
 * v1.13 FR-363 · <b>读时派生</b>的 LLM 配置视图:新键有就用新键,没有就从 v0.14 那套旧键推出等价三元组。
 *
 * <p>为什么不写迁移 SQL:旧配置<b>能无损推出</b>新配置(`qwen` → 百炼/通义千问,`deepseek` → DeepSeek 官方/DeepSeek),
 * 推导逻辑一共十几行。写 SQL 的代价是「升级瞬间必须一次性把所有家庭改对,改错了没有退路」;
 * 读时派生的代价只是这段代码要多活一个版本。而且它顺带给了回滚能力 —— 旧键原封不动,
 * 万一要退回 v1.12,老代码读到的还是它认识的那份。详见 tech-design/v1.13.md §1.5。</p>
 *
 * <p><b>这个类一个字都不写库</b>。写只发生在用户点「保存」时(IntegrationsController),
 * 那一刻才把派生结果固化成新键。护栏 {@code v113-LLM-LEGACY-KEYS-KEPT} 盯着这条。</p>
 */
public record LlmSettings(
        LlmInvocation primary,
        Optional<LlmInvocation> backup,
        LlmInvocation vision,
        boolean visionEnabled,
        boolean legacy) {

    /** 旧键 {@code llm_primary_vendor} 的两个取值 → 新三元组的映射(FR-363 表) */
    private static final LlmInvocation LEGACY_QWEN =
            new LlmInvocation(LlmCatalog.P_DASHSCOPE, "qwen", null);
    private static final LlmInvocation LEGACY_DEEPSEEK =
            new LlmInvocation(LlmCatalog.P_DEEPSEEK, "deepseek", null);
    private static final LlmInvocation LEGACY_VISION =
            new LlmInvocation(LlmCatalog.P_DASHSCOPE, "qwen-vl", null);

    public static LlmSettings load(FamilyConfigService cfg, long familyId) {
        return load((key, fallback) -> cfg.getString(familyId, key, fallback));
    }

    public static LlmSettings from(java.util.Map<String, String> values) {
        return load((key, fallback) -> values.getOrDefault(key, fallback));
    }

    private static LlmSettings load(java.util.function.BiFunction<String, String, String> read) {
        String newPlatform = read.apply(K_LLM_PLATFORM, "");
        boolean legacy = LlmCatalog.normalizePlatform(newPlatform) == null;

        LlmInvocation primary;
        Optional<LlmInvocation> backup;
        if (!legacy) {
            primary = triple(read, K_LLM_PLATFORM, K_LLM_FAMILY, K_LLM_MODEL_ID,
                    LlmCatalog.Modality.TEXT);
            String bp = LlmCatalog.normalizePlatform(read.apply(K_LLM_BACKUP_PLATFORM, ""));
            backup = bp == null ? Optional.empty()
                    : Optional.of(triple(read, K_LLM_BACKUP_PLATFORM, K_LLM_BACKUP_FAMILY,
                            K_LLM_BACKUP_MODEL_ID, LlmCatalog.Modality.TEXT));
        } else {
            // 旧配置:vendor 决定主选,另一家自动成为备选(等价于 v0.14 orderByPrimaryVendor 的两家排序)
            boolean deepSeekFirst = "deepseek".equalsIgnoreCase(
                    read.apply(K_LLM_PRIMARY_VENDOR, "qwen"));
            LlmInvocation head = deepSeekFirst ? LEGACY_DEEPSEEK : LEGACY_QWEN;
            LlmInvocation tail = deepSeekFirst ? LEGACY_QWEN : LEGACY_DEEPSEEK;
            // 旧 llm_model 只在「确实属于主选那一家」时承接。旧代码里 pinnedModel() 是按前缀判断的,
            // 不匹配就退回轮询/默认 —— 这里保持同样结果,免得升级后突然拿一个 qwen 的 key 去调 deepseek 的型号。
            String legacyModel = LlmCatalog.normalizeModel(read.apply(K_LLM_MODEL, ""));
            String carried = LlmCatalog.inRecommended(head.familyDef().orElse(null), legacyModel)
                    ? legacyModel : null;
            primary = new LlmInvocation(head.platform(), head.family(), carried);
            backup = Optional.of(tail);
        }

        LlmInvocation vision;
        boolean visionEnabled;
        String newVisionPlatform = read.apply(K_LLM_VISION_PLATFORM, "");
        if (LlmCatalog.normalizePlatform(newVisionPlatform) != null) {
            vision = triple(read, K_LLM_VISION_PLATFORM, K_LLM_VISION_FAMILY,
                    K_LLM_VISION_MODEL_ID, LlmCatalog.Modality.VISION);
            visionEnabled = Boolean.parseBoolean(read.apply(K_LLM_VISION_ENABLED, "true"));
        } else {
            // 旧配置把「关闭」编码在型号里(llm_vision_model=off)。拆开:关掉时型号仍保留默认,
            // 这样用户重新打开不用再选一次。
            // 缺省不在这里写型号名(那是目录的事):读不到 = 没钉死 = 走系列默认,与 v1.12 的默认值同解
            String vm = read.apply(K_LLM_VISION_MODEL, "");
            visionEnabled = !"off".equalsIgnoreCase(vm == null ? "" : vm.trim());
            String model = visionEnabled && LlmCatalog.validModel(LlmCatalog.normalizeModel(vm))
                    ? LlmCatalog.normalizeModel(vm) : null;
            vision = new LlmInvocation(LEGACY_VISION.platform(), LEGACY_VISION.family(), model);
        }

        return new LlmSettings(primary, backup, vision, visionEnabled, legacy);
    }

    /** 读一组三元组键;系列缺失时退回该平台该形态的第一个系列(平台一定存在,调用前已校验) */
    private static LlmInvocation triple(java.util.function.BiFunction<String, String, String> read,
                                        String platformKey, String familyKey, String modelKey,
                                        LlmCatalog.Modality modality) {
        String platform = LlmCatalog.normalizePlatform(read.apply(platformKey, ""));
        LlmCatalog.Platform p = LlmCatalog.platform(platform).orElse(LlmCatalog.DASHSCOPE);
        String family = read.apply(familyKey, "");
        String resolvedFamily = p.family(family).map(LlmCatalog.Family::code)
                .orElseGet(() -> p.firstFamily(modality).map(LlmCatalog.Family::code).orElse(family));
        String model = LlmCatalog.normalizeModel(p.code(), read.apply(modelKey, ""));
        return new LlmInvocation(p.code(), resolvedFamily, model);
    }

    /** 主选 + 备选,按顺序;备选缺省或与主选完全相同则只有一个 */
    public List<LlmInvocation> chain() {
        List<LlmInvocation> list = new ArrayList<>();
        list.add(primary);
        backup.filter(b -> !b.equals(primary)).ifPresent(list::add);
        return list;
    }
}
