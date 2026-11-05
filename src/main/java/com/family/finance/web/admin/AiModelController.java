package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.checkup.llm.LlmCatalog;
import com.family.finance.service.checkup.llm.LlmInvocation;
import com.family.finance.service.checkup.llm.LlmSettings;
import com.family.finance.service.config.FamilyConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 「AI 大模型」那一节的写操作:密钥、型号选取、测试连接。
 *
 * <p>v1.24.5 · 从 {@code IntegrationsController} 整体搬过来,路径由 {@code /admin/integrations/llm/*}
 * 改为 {@code /admin/ai-access/llm/*},保存后回到 AI 接入页的这一节。页面挪了而后端还叫 integrations,
 * 下一个排查的人会被带偏 —— 所以端点跟着页面走。方法体本身逐字未改(各自的历史注释都还在)。</p>
 */
@Controller
@RequestMapping("/admin/ai-access/llm")
@RequiredArgsConstructor
public class AiModelController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.family.finance.service.checkup.llm.LlmTestGate testGate;

    /** 保存后回到 AI 接入页的「AI 大模型」这一节 */
    private static final String BACK = "redirect:/admin/ai-access#llm";

    private final FamilyConfigService configService;
    private final AuditLogService auditLogService;
    private final com.family.finance.service.checkup.llm.LlmRouter llmRouter;
    private final LlmSettingsView view;

    /**
     * ①-a LLM 凭据 · <b>一个平台一把 key,单独保存</b>(v1.18.1 BUG-FIX)。
     *
     * <p><b>为什么要拆</b>:原来密钥和「用哪个模型」在同一个表单、同一个端点,而端点是
     * 「校验先全跑完再落库」—— 于是全新装机(一家都没配)时出现死锁:
     * 模型下拉与凭据级联(没配 key 的平台 {@code disabled}),一家都没配 → 平台选项全禁用 →
     * 提交上来 {@code platform} 是空 → {@code parseTriple} 抛「请选择平台」→ <b>整单退回,
     * key 一个字都没写进去</b>。用户于是卡在「要存 key 得先选平台、要能选平台得先存 key」。
     * 主流程直接走不下去。</p>
     *
     * <p>拆开之后:密钥保存<b>只认平台 + key</b>,不碰任何模型配置;模型选取见
     * {@link #saveLlmModels}。两件事本来就是不同时机做的 —— 先拿到 key,再挑型号。</p>
     */
    @PostMapping("/key")
    public String saveLlmKey(@AuthenticationPrincipal MemberPrincipal me,
                             @RequestParam("platform") String platform,
                             @RequestParam(value = "apiKey", required = false) String apiKey,
                             RedirectAttributes ra) {
        long fid = me.getFamilyId();
        LlmCatalog.Platform p = LlmCatalog.platform(platform).orElse(null);
        if (p == null) {
            ra.addFlashAttribute("flashError", "未知平台:" + platform);
            return BACK;
        }
        // 空提交不当成功:这一格的语义是「留空 = 不改」,但用户点了这张卡的保存按钮却什么都没填,
        // 回一句「已保存」等于骗他(他会以为换上了新 key)。
        if (isBlank(apiKey)) {
            ra.addFlashAttribute("flashError", p.label() + ":没填内容 · 密钥未改动(要换 key 就把新的粘进来再保存)");
            return BACK;
        }
        configService.set(fid, p.keyName(), apiKey.trim());
        // 审计只记「已配/未配」,绝不记 key 明文(§22.6 私密红线)
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid, "LLM 密钥更新 · 平台=" + p.label() + " · key=已配置");
        ra.addFlashAttribute("flash", p.label() + " 的密钥已保存 · 现在可以在下面「用哪个模型」里选它了");
        return BACK;
    }

    /**
     * ①-b LLM 模型选取 · 主备/视觉三元组 + 温度 / max_tokens / timeout(v1.18.1 起不再接收 key)。
     *
     * <p><b>校验先全跑完再落库</b>:任何一处不合法就整单退回(flashError + 原样重填),
     * 不写一个字。v0.14 那套「越权型号静默回落 auto」在三级模型下是有害的 ——
     * 方舟的 {@code ep-xxxx} 必然不在任何内置清单里,静默回落的表现是
     * 「页面显示着我填的型号、实际调的是别的」,用户查不出来。宁可当面拒绝。
     * 这条纪律对<b>模型</b>是对的;把它连带套在密钥上才是上面那个死锁的成因。</p>
     */
    @PostMapping("/models")
    public String saveLlmModels(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam(value = "platform", required = false) String platform,
                          @RequestParam(value = "family", required = false) String family,
                          @RequestParam(value = "modelId", required = false) String modelId,
                          @RequestParam(value = "backupPlatform", required = false) String backupPlatform,
                          @RequestParam(value = "backupFamily", required = false) String backupFamily,
                          @RequestParam(value = "backupModelId", required = false) String backupModelId,
                          @RequestParam(value = "visionEnabled", defaultValue = "false") boolean visionEnabled,
                          @RequestParam(value = "visionPlatform", required = false) String visionPlatform,
                          @RequestParam(value = "visionFamily", required = false) String visionFamily,
                          @RequestParam(value = "visionModelId", required = false) String visionModelId,
                          @RequestParam(value = "temperature", required = false) Double temperature,
                          @RequestParam("maxTokens") int maxTokens,
                          @RequestParam("timeoutSeconds") int timeoutSeconds,
                          RedirectAttributes ra) {
        long fid = me.getFamilyId();

        // ── v1.18.4 · 按「用户想干什么」分支,不按「字段填没填」分支 ──────────────────
        //   老逻辑三组一律走同一个 parseTriple,而 parseTriple 一律要求平台可解析 ——
        //   于是【关掉截图识别、视觉平台留空】也会抛「截图识别:请选择平台」。
        //   用户配了一家没有视觉能力的平台(如 DeepSeek)时更是死路:视觉下拉里一个可选项都没有,
        //   关掉这个能力还是存不下去。主流程直接走不通(维护者 2026-08-21 实测)。
        LlmInvocation primary, backup, vision;
        String visionSkippedNote = "";
        try {
            // ① 主选:必填,而且平台必须【已配密钥】—— 只靠前端 disabled 挡不住,
            //    存进一个没密钥的平台等于存了一份必然调不通的配置。
            primary = parseTriple("主选", platform, family, modelId, LlmCatalog.Modality.TEXT, true);
            requireKeyConfigured(fid, primary, "主选");

            // ② 备选:留空 = 不设(合法),填了就按主选同样的标准校验
            backup = isBlank(backupPlatform) ? null
                    : parseTriple("备选", backupPlatform, backupFamily, backupModelId, LlmCatalog.Modality.TEXT, true);
            if (backup != null) requireKeyConfigured(fid, backup, "备选");

            // ③ 截图识别:
            //    · 关掉 → 【一个字都不校验】。能解析就顺手存着(下次开启还在),
            //      解析不出来就保留库里原值,绝不因此拦住整单。
            //    · 开启 → 正常校验;并且要有一家【既配了密钥、又有视觉能力】的平台,
            //      否则明确告诉他去配哪家,而不是让他对着空下拉发呆。
            if (!visionEnabled) {
                vision = tryParseTriple(visionPlatform, visionFamily, visionModelId, LlmCatalog.Modality.VISION);
                // 关掉了就不拦人,但也不能【默默吞掉】他填错的东西 ——
                // 非阻塞提示:保存照常成功,只在回执里说一句这一组没校验。
                if (vision == null && !(isBlank(visionPlatform) && isBlank(visionModelId))) {
                    visionSkippedNote = " · 截图识别已关闭,视觉那一组没有校验也没有保存(开启时会要求填对)";
                }
            } else {
                if (view.visionCapablePlatforms(fid).isEmpty()) {
                    throw new IllegalArgumentException(
                            "截图识别需要有视觉能力的平台,而你已配密钥的平台都没有视觉能力 · "
                            + "请先在上面为「阿里云百炼」或「火山方舟」保存密钥,或取消勾选「启用持仓截图导入」");
                }
                vision = parseTriple("截图识别", visionPlatform, visionFamily, visionModelId,
                        LlmCatalog.Modality.VISION, true);
                requireKeyConfigured(fid, vision, "截图识别");
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            return BACK;
        }
        if (backup != null && backup.equals(primary)) {
            ra.addFlashAttribute("flashError", "备选与主选完全相同,等于没有备选 · 请换一个平台/系列/型号,或清空备选平台");
            return BACK;
        }

        // ── 校验全过 → 落库(v1.18.1:密钥不在这条路径上,见 saveLlmKey)──
        writeTriple(fid, FamilyConfigService.K_LLM_PLATFORM, FamilyConfigService.K_LLM_FAMILY,
                FamilyConfigService.K_LLM_MODEL_ID, primary);
        writeTriple(fid, FamilyConfigService.K_LLM_BACKUP_PLATFORM, FamilyConfigService.K_LLM_BACKUP_FAMILY,
                FamilyConfigService.K_LLM_BACKUP_MODEL_ID, backup);   // null = 清空备选
        // v1.18.4 · vision 为 null = 关掉截图识别且解析不出三元组 → 【保留库里原值】,不清空。
        //   清空会让「关一次再开」丢掉之前选好的型号,而用户关它往往只是暂时不用。
        if (vision != null) {
            writeTriple(fid, FamilyConfigService.K_LLM_VISION_PLATFORM, FamilyConfigService.K_LLM_VISION_FAMILY,
                    FamilyConfigService.K_LLM_VISION_MODEL_ID, vision);
        }
        configService.set(fid, FamilyConfigService.K_LLM_VISION_ENABLED, String.valueOf(visionEnabled));

        double temp = temperature == null ? 0.5 : Math.max(0.0, Math.min(1.0, temperature));
        int mt = Math.max(500, Math.min(maxTokens, 8000));
        int ts = Math.max(5, Math.min(timeoutSeconds, 120));
        configService.set(fid, FamilyConfigService.K_LLM_TEMPERATURE, String.valueOf(temp));
        configService.set(fid, FamilyConfigService.K_LLM_MAX_TOKENS, String.valueOf(mt));
        configService.set(fid, FamilyConfigService.K_LLM_TIMEOUT_SECS, String.valueOf(ts));

        // 审计 · 只记「已配/未配」+ 调用坐标,不记 key 明文(§22.6 私密红线)
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "LLM 模型配置 · 主选=" + primary.label()
                + " · 备选=" + (backup == null ? "无" : backup.label())
                + " · 截图识别=" + (visionEnabled ? vision.label() : "关闭")
                + " · temperature=" + temp + " · maxTokens=" + mt + " · timeout=" + ts + "s");
        ra.addFlashAttribute("flash", "模型配置已保存 · 主选 " + primary.display()
                + (backup == null ? " · 无备选" : " · 备选 " + backup.display()) + " · 下次调用生效"
                + visionSkippedNote);
        return BACK;
    }

    /**
     * 表单三元组 → {@link LlmInvocation},不合法直接抛(message 就是给用户看的文案)。
     *
     * @param requireModel 该组是否必须能定出型号(视觉关掉时为 false:允许留着半份配置)
     */
    private static LlmInvocation parseTriple(String what, String platform, String family, String modelId,
                                             LlmCatalog.Modality modality, boolean requireModel) {
        LlmCatalog.Platform p = LlmCatalog.platform(platform)
                .orElseThrow(() -> new IllegalArgumentException(isBlank(platform)
                        // v1.18.4 · 空平台的成因几乎总是「一家密钥都没配 → 下拉里全是禁用项」,
                        //   干巴巴回一句「请选择平台」等于让用户对着一个选不动的下拉猜。
                        ? what + ":还没选平台 · 如果下拉里一个都选不了,说明还没有平台配好密钥 —— 先到上面任选一家保存 API Key"
                        : what + ":未知平台「" + platform + "」"));
        LlmCatalog.Family f = p.family(family)
                .filter(x -> x.modality() == modality)
                .orElseThrow(() -> new IllegalArgumentException(
                        what + ":「" + (family == null || family.isBlank() ? "(未选)" : family)
                        + "」不是 " + p.label() + " 的"
                        + (modality == LlmCatalog.Modality.VISION ? "视觉" : "文本") + "模型系列"));
        String m = LlmCatalog.normalizeModel(modelId);
        if (m != null && !LlmCatalog.validModel(m)) {
            // 不回显用户填的原串(可能是粘错的 key)· 只说格式要求
            throw new IllegalArgumentException(what + ":型号格式不合法 · 只允许字母/数字/点/下划线/冒号/连字符,最长 64 位");
        }
        if (m == null && requireModel && f.requiresExplicitModel()) {
            throw new IllegalArgumentException(what + ":" + p.label() + " 的「" + f.label()
                    + "」必须手工填写型号(到控制台复制接入点 ID 或模型 ID),这一家没有可预置的推荐型号");
        }
        return new LlmInvocation(p.code(), f.code(), m);
    }

    /**
     * v1.18.4 · 宽松解析:解析得出就返回,解析不出就返回 null(<b>不抛</b>)。
     * 专给「用户已经关掉这个能力」的场景用 —— 那时他填没填、填得对不对都不该拦住整单。
     */
    private static LlmInvocation tryParseTriple(String platform, String family, String modelId,
                                                LlmCatalog.Modality modality) {
        try {
            return parseTriple("", platform, family, modelId, modality, false);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * v1.18.4 · 选中的平台必须<b>已经配好密钥</b>。
     *
     * <p>前端已经把没配密钥的平台设成 disabled(v1.17.2 的级联),但那只是提示 ——
     * 表单可以被绕过,而存进去一份「指向没有密钥的平台」的配置,结果是<b>下次调用才失败</b>,
     * 且失败信息落在别的页面上,用户根本关联不回这里。当面拒绝比事后报错好。</p>
     */
    private void requireKeyConfigured(long fid, LlmInvocation inv, String what) {
        LlmCatalog.Platform p = LlmCatalog.platform(inv.platform()).orElse(null);
        if (p == null) return;
        if (!configService.isPrivateKeyConfigured(fid, p.keyName())) {
            throw new IllegalArgumentException(what + ":" + p.label() + " 还没有配密钥 · 请先在上面那张卡里粘上 API Key 点「保存密钥」");
        }
    }


    /** 型号看起来是不是「带日期的快照版本」(如 doubao-seed-2-0-pro-<b>260215</b>)。 */
    static boolean looksDateStamped(String model) {
        return model != null && model.matches(".*-\\d{6}$");
    }

    /**
     * 型号不存在时的下一步指引。带日期的型号大概率是<b>被新版本取代了</b>,
     * 这时最省事的解法是换成不带日期的别名;其它情况就是填错了。
     */
    static String staleModelHint(String model) {
        if (looksDateStamped(model)) {
            return " · 这个型号带日期,多半已被新版本取代 —— 换成不带日期的 doubao-seed-evolving(平台自动跟进),"
                 + "或到火山方舟「模型广场」复制当前的 Model ID";
        }
        return " · 到平台控制台复制当前可用的 Model ID(方舟也可填 ep- 开头的接入点 ID);"
             + "另外确认该型号已在「开通管理」里开通";
    }

    /** 写一组三元组;{@code inv} 为 null = 清空(备选可以不设) */
    private void writeTriple(long fid, String platformKey, String familyKey, String modelKey, LlmInvocation inv) {
        configService.set(fid, platformKey, inv == null ? "" : inv.platform());
        configService.set(fid, familyKey,   inv == null ? "" : inv.family());
        configService.set(fid, modelKey,    inv == null || inv.model() == null ? "" : inv.model());
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /**
     * v0.7 FR-131 · LLM 一键测试连接 · 用<b>已保存</b>的 key 发最小探测,验证链路通不通。
     * v1.13 起<b>按平台单独测</b>,并且用「这个平台当前被选中的那个型号」发 —— 只测端点通不通
     * 意义不大,方舟最常见的失败恰恰是接入点 ID 填错(key 完全正常)。
     *
     * <p>私密红线(决策 82):绝不回显 key、绝不把 key 进 flash / audit / 日志明文;
     * 失败原因经 {@link #classifyLlmError} 归类成无敏感信息的友好文案。
     */
    @PostMapping("/test")
    public String testLlm(@AuthenticationPrincipal MemberPrincipal me,
                          @RequestParam("platform") String platform,
                          RedirectAttributes ra) {
        long fid = me.getFamilyId();
        if (testGate != null && testGate.block(fid,
                com.family.finance.service.llmtrace.PromptTrace.of(com.family.finance.service.llmtrace.PromptSurface.TEST_CONNECTION),
                "模型连接自检", "ping")) {
            ra.addFlashAttribute("flash", com.family.finance.service.checkup.llm.LlmTestGate.MESSAGE);
            return BACK;
        }
        LlmCatalog.Platform p = LlmCatalog.platform(platform).orElse(null);
        if (p == null) {
            ra.addFlashAttribute("flashError", "未知平台:" + platform);
            return BACK;
        }
        if (!configService.isPrivateKeyConfigured(fid, p.keyName())) {
            ra.addFlashAttribute("flashError", p.label() + " 未配置 Key · 请先填好并保存,再测试连接");
            return BACK;
        }
        com.family.finance.service.checkup.llm.LlmClient client = llmRouter.clientFor(p.code()).orElse(null);
        if (client == null) {
            ra.addFlashAttribute("flashError", p.label() + " 客户端不可用");
            return BACK;
        }
        LlmInvocation inv = probeInvocation(fid, p);
        if (inv == null) {
            ra.addFlashAttribute("flashError", p.label() + " 还没有可测的型号 · 请先在上面选好系列并填写型号(控制台复制接入点/模型 ID),保存后再测试");
            return BACK;
        }
        String label = p.label() + " · " + inv.resolvedModel();

        boolean ok;
        String reason;
        try {
            // 最小探测:极短 prompt,验证 key→端点→型号→解析 全链路通(与业务调用走同一路径)
            String out = client.chat(inv, "你是连通性自检,无视语义,只回复两个字:ok。", "ping");
            ok = out != null && !out.isBlank();
            reason = ok ? "可用" : "返回为空";
        } catch (Exception e) {
            ok = false;
            reason = classifyLlmError(e.getMessage(), inv.resolvedModel());
        }

        // 审计 · 不记 key 明文(§22.6 / 决策 82)· 只记调用坐标 + 结果归类
        auditLogService.record(fid, me.getMemberId(), AuditLogType.FAMILY_UPDATE,
                "family_runtime_config", fid,
                "LLM 测试连接 · " + inv.label() + " · " + (ok ? "成功" : "失败:" + reason));
        if (ok) {
            ra.addFlashAttribute("flash", label + " 测试连接成功 · " + reason);
        } else {
            ra.addFlashAttribute("flashError", label + " 测试失败 · " + reason);
        }
        return BACK;
    }

    /**
     * 挑一个坐标去探这个平台:优先<b>用户当前真选中的</b>(主 → 备 → 视觉),
     * 都没选到这家才退回该平台第一个有默认型号的文本系列。
     * 方舟这类必须手填型号的平台,没选中就返回 null —— 与其拿个瞎猜的型号去报
     * "model not found",不如直接说「先去填型号」。
     */
    private LlmInvocation probeInvocation(long fid, LlmCatalog.Platform p) {
        LlmSettings s = LlmSettings.load(configService, fid);
        java.util.List<LlmInvocation> candidates = new java.util.ArrayList<>(s.chain());
        candidates.add(s.vision());
        for (LlmInvocation inv : candidates) {
            if (p.code().equals(inv.platform()) && inv.resolvable()) return inv;
        }
        return p.firstFamily(LlmCatalog.Modality.TEXT)
                .filter(f -> !f.requiresExplicitModel())
                .map(f -> new LlmInvocation(p.code(), f.code(), null))
                .orElse(null);
    }

    /**
     * 把 LLM 调用异常 message 归类成<b>无敏感信息</b>的友好原因(绝不含 key / 不回显原始 body)。
     *
     * <p>v1.13 加了方舟那几类。<b>顺序有讲究</b>:方舟的「接入点不存在」错误码叫
     * {@code InvalidEndpointOrModel},里面带 invalid —— 放在下面的 401/403 分支后面会被
     * 归类成「Key 无效」,而这恰恰是方舟最容易踩、也最需要说清楚的一条(key 是好的,
     * 是型号填错了)。所以型号/接入点这一档必须排在凭据档前面。</p>
     */
    /**
     * v1.18.5 · 多收一个 {@code model} 参数,专为「预置型号过期」这件事:
     * 方舟的型号大多带日期后缀({@code -260215}),会随版本更迭被取代。
     * 我们预置了推荐型号(v1.18.4),默认那个不带日期所以不会失效,
     * 但用户若选了带日期的那几个,总有一天会 404 —— 那时候
     * <b>报错里必须说清「是型号过期了、去哪换、换成什么」</b>,而不是让他自己猜。
     * 维护者定的口径:<b>不主动检测,报错时提示即可</b>。
     */
    static String classifyLlmError(String rawMsg, String model) {
        String m = rawMsg == null ? "" : rawMsg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("未配置") || m.contains("not configured")) return "Key 未配置";
        // ── 方舟专属:型号/接入点 与 实名认证(必须排在凭据档之前,见上方 javadoc) ──
        if (m.contains("endpoint") || m.contains("model not found") || m.contains("modelnotfound")
                || m.contains("接入点") || m.contains("does not exist"))
            return "型号不存在:「" + (model == null ? "(自动)" : model) + "」" + staleModelHint(model);
        if (m.contains("modelnotopen") || m.contains("not activated") || m.contains("未开通") || m.contains("未订阅"))
            return "该型号未在控制台开通(先去平台开通再试)";
        if (m.contains("realname") || m.contains("real name") || m.contains("实名"))
            return "账号未完成实名认证(方舟要求实名后才能调用)";
        if (m.contains("arrearage") || m.contains("欠费") || m.contains("billoverdue") || m.contains("bill overdue")
                || m.contains("overdue"))
            return "账户欠费或账单过期";
        if (m.contains("ratelimit") || m.contains("rate limit") || m.contains("429") || m.contains("too many"))
            return "调用过于频繁(限流),稍后再试";
        if (m.contains("quota") || m.contains("额度") || m.contains("freetier") || m.contains("insufficient"))
            return "免费额度已用尽(可换模型或等额度重置)";
        if (m.contains("401") || m.contains("403") || m.contains("invalid") || m.contains("incorrect")
                || m.contains("unauthor") || m.contains("forbidden") || m.contains("api key"))
            return "Key 无效或无权限";
        if (m.contains("timeout") || m.contains("超时") || m.contains("timed out")
                || m.contains("resourceaccess") || m.contains("connect") || m.contains("i/o") || m.contains("unknownhost"))
            return "网络不通或超时";
        java.util.regex.Matcher sm = java.util.regex.Pattern.compile("status=(\\d{3})").matcher(m);
        if (sm.find()) return "调用失败(已脱敏 · HTTP " + sm.group(1) + ")";
        return "调用失败(已脱敏)";
    }
}
