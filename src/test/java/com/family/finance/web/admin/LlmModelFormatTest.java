package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.member.Member;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.checkup.llm.LlmRouter;
import com.family.finance.service.config.FamilyConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;


import com.family.finance.service.checkup.llm.LlmCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.13 FR-360 · 型号手填的<b>校验边界</b>:合法的原样收下,不合法的<b>当面报错</b>。
 *
 * <p>这条测试针对的是 v0.14 留下的一个具体坏形态:那时候型号是「取值白名单 + 越权静默
 * 回落 auto」。方舟的接入点 ID({@code ep-xxxx})不以任何已知前缀开头,必然越权 ——
 * 于是用户填了、页面显示着、库里存的却是空,实际调用走的是别的型号。<b>静默回落比报错
 * 危险得多</b>:报错用户会改,回落用户查不出来。</p>
 *
 * <p>所以断言分两半:合法型号必须<b>原样落库</b>(不被规整、不被截断),不合法必须
 * <b>整单退回、一个字都不写</b>(§ saveLlm「校验先全跑完再落库」)。</p>
 */
class LlmModelFormatTest {

    private final FamilyConfigService config = mock(FamilyConfigService.class);
    private final AuditLogService audit = mock(AuditLogService.class);

    /** v1.18.4 · 默认「三家都配好了密钥」—— 校验里新增了 requireKeyConfigured,
     *  不打这个桩的话每条用例都会先撞到「还没有配密钥」,测不到它想测的东西。 */
    private void allKeysConfigured() {
        when(config.isPrivateKeyConfigured(anyLong(), anyString())).thenReturn(true);
    }

    private AiModelController controller() {
        allKeysConfigured();
        return newController();
    }

    /** v1.24.5 · 大模型这一节的端点从 IntegrationsController 搬到了 AiModelController(页面挪到了 AI 接入) */
    private AiModelController newController() {
        return new AiModelController(config, audit, mock(LlmRouter.class), new LlmSettingsView(config, new ObjectMapper()));
    }

    private static MemberPrincipal me() {
        Member m = new Member();
        m.setId(9L);
        m.setFamilyId(1L);
        m.setUsername("tester");
        return new MemberPrincipal(m);
    }

    /** 只填主选(视觉关掉、备选留空)· 返回 flash 容器供断言 */
    private RedirectAttributes saveWithPrimaryModel(String platform, String family, String modelId) {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        // v1.18.1 · 入口从 saveLlm 改名 saveLlmModels,并且不再接收三把 key
        //(密钥拆到 saveLlmKey:原来两件事共用一个「校验先全跑完再落库」的端点,
        // 导致全新装机时「要存 key 得先选平台、要能选平台得先存 key」的死锁)。
        // 本测守的东西不变:型号格式非法要当面拒绝、且不回显用户填的原串。
        controller().saveLlmModels(me(),
                platform, family, modelId,
                null, null, null,
                false, "dashscope", "qwen-vl", "",
                0.5, 2000, 25, ra);
        return ra;
    }

    private static String flashError(RedirectAttributes ra) {
        Object v = ra.getFlashAttributes().get("flashError");
        return v == null ? null : v.toString();
    }

    // ---------------- 合法:原样收下 ----------------

    @Test
    void arkEndpointId_isAccepted_andStoredVerbatim() {
        // 方舟控制台生成的接入点 ID · v0.14 的白名单必然把它判越权
        RedirectAttributes ra = saveWithPrimaryModel("ark", "doubao", "ep-20260815120000-abcde");

        assertThat(flashError(ra)).as("接入点 ID 被拒了 —— 方舟就没法用了").isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_MODEL_ID, "ep-20260815120000-abcde");
        verify(config).set(1L, FamilyConfigService.K_LLM_PLATFORM, "ark");
        verify(config).set(1L, FamilyConfigService.K_LLM_FAMILY, "doubao");
    }

    @Test
    void arkDatedModelId_isAccepted() {
        // 带日期版本的模型 ID · 同样只能从控制台抄
        assertThat(flashError(saveWithPrimaryModel("ark", "deepseek", "doubao-seed-1-6-251015"))).isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_MODEL_ID, "doubao-seed-1-6-251015");
    }

    @Test
    void recommendedModel_isAccepted() {
        assertThat(flashError(saveWithPrimaryModel("deepseek", "deepseek", "deepseek-chat"))).isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_MODEL_ID, "deepseek-chat");
    }

    @Test
    void blankModel_meansAuto_onAFamilyThatHasADefault() {
        // 留空 = 自动(系列默认型号)· 这是合法的,不是「没填」
        assertThat(flashError(saveWithPrimaryModel("dashscope", "qwen", "  "))).isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_MODEL_ID, "");
    }

    // ---------------- 不合法:报错,且一个字都不写 ----------------

    @Test
    void modelWithSpace_isRejected_notSilentlyFalledBack() {
        RedirectAttributes ra = saveWithPrimaryModel("ark", "doubao", "ep-2026 abcde");

        assertThat(flashError(ra)).contains("型号格式不合法");
        verify(config, never()).set(anyLong(), anyString(), anyString());
        verify(audit, never()).record(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(),
                anyString(), anyLong(), anyString());
    }

    @Test
    void modelWithQuote_isRejected() {
        assertThat(flashError(saveWithPrimaryModel("ark", "doubao", "ep\"; drop"))).contains("型号格式不合法");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void modelLongerThanLimit_isRejected() {
        assertThat(flashError(saveWithPrimaryModel("ark", "doubao", "e".repeat(LlmCatalog.MAX_MODEL_LENGTH + 1)))).contains("型号格式不合法");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    /**
     * 报错文案<b>不回显用户填的原串</b>:这个输入框最常见的误填就是把 API key 粘进来,
     * 回显 = 把 key 写进 flash(可能被日志/截图带走)。只说格式要求。
     */
    @Test
    void rejectMessage_doesNotEchoWhatUserTyped() {
        String pastedKey = "sk-abcdef0123456789 THIS_IS_A_KEY";
        String msg = flashError(saveWithPrimaryModel("ark", "doubao", pastedKey));

        assertThat(msg).contains("型号格式不合法").doesNotContain("sk-abcdef", "THIS_IS_A_KEY");
    }

    // ---------------- 必须手填的系列:不许静默用默认 ----------------

    @Test
    void arkFamilyWithoutRecommendedModels_requiresExplicitModel() {
        // 没有推荐型号的系列,留空不能变成「自动」—— 那样会调一个不存在的型号。
        // v1.18.4 · 「方舟三个系列都没有可预置型号」这个前提已被调研推翻(方舟现在支持直接填
        //   Model ID,豆包两个系列已预置)。剩下「方舟托管的 DeepSeek」仍不预置 ——
        //   调研没拿到可靠的现行 ID,与其编一个不如照实要求手填。被守的不变量没变,判据重指到它。
        String msg = flashError(saveWithPrimaryModel("ark", "deepseek", ""));

        assertThat(msg).contains("必须手工填写型号");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void visionOff_neverBlocksSave_butDoesNotSilentlySwallowTypos() {
        // 关掉截图识别 → 视觉那一组【一个字都不校验】
        RedirectAttributes ok = new RedirectAttributesModelMap();
        controller().saveLlmModels(me(),
                "dashscope", "qwen", "", null, null, null,
                false, "ark", "doubao-vision", "", 0.5, 2000, 25, ok);
        assertThat(flashError(ok)).as("视觉关着还要求填型号 · 用户被卡在一个他不用的字段上").isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_VISION_ENABLED, "false");

        // v1.18.4 · 填了非法型号但功能已关闭:【不许拦住保存】(那正是维护者撞上的毛病),
        //   但也不许默默吞掉 —— 保存成功,回执里说清这一组没校验也没保存。
        RedirectAttributes bad = new RedirectAttributesModelMap();
        controller().saveLlmModels(me(),
                "dashscope", "qwen", "", null, null, null,
                false, "ark", "doubao-vision", "ep 有空格", 0.5, 2000, 25, bad);
        assertThat(flashError(bad)).as("关掉的能力不许拦住整单").isNull();
        assertThat(bad.getFlashAttributes().get("flash").toString())
                .as("也不许默默吞掉").contains("没有校验也没有保存");
    }

    // ---------------- 平台/系列本身的错配 ----------------

    @Test
    void unknownPlatform_isRejected() {
        // v1.18.4 · 文案分了两种:平台【留空】→ 指路去配密钥;平台【填了但不认识】→ 直说未知平台。
        //   老文案对两种情况都回「请选择平台」,而空平台的成因几乎总是「下拉里全是禁用项」,
        //   那句话等于让用户对着一个选不动的下拉猜。
        assertThat(flashError(saveWithPrimaryModel("openai", "gpt", "gpt-4o"))).contains("未知平台");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void familyFromAnotherPlatform_isRejected() {
        // 「qwen」是百炼的系列,挂到方舟下面不成立 —— 级联下拉坏掉时会出现这种提交
        assertThat(flashError(saveWithPrimaryModel("ark", "qwen", "ep-1"))).contains("不是");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void visionFamilyMustBeAVisionFamily() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlmModels(me(),
                "dashscope", "qwen", "", null, null, null,
                true, "dashscope", "qwen", "", 0.5, 2000, 25, ra);   // 文本系列塞进视觉位
        assertThat(flashError(ra)).contains("视觉模型系列");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    @Test
    void backupIdenticalToPrimary_isRejected() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlmModels(me(),
                "dashscope", "qwen", "qwen-plus",
                "dashscope", "qwen", "qwen-plus",
                false, "dashscope", "qwen-vl", "", 0.5, 2000, 25, ra);

        assertThat(flashError(ra)).contains("等于没有备选");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    // ---------------- key 的私密红线(v1.18.1 起走独立端点 saveLlmKey)----------------

    /**
     * 存一把 key <b>只动这一把</b>,而且明文绝不进 flash / audit(§22.6 私密红线)。
     *
     * <p>v1.18.1 之前这些是 {@code saveLlm} 的一部分,和模型校验共用一个
     * 「校验先全跑完再落库」的端点 —— 于是全新装机时死锁:模型下拉与凭据级联,
     * 一家都没配则平台全禁用 → platform 为空 → 抛「请选择平台」→ 整单退回,
     * <b>key 一个字都没存进去</b>。所以密钥保存必须是自己的端点、不碰任何模型配置。</p>
     */
    @Test
    void saveKey_writesOnlyThatPlatform_andNeverLeaksPlaintext() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlmKey(me(), "ark", "ark-secret-key", ra);

        verify(config, times(1)).set(1L, FamilyConfigService.K_LLM_ARK_KEY, "ark-secret-key");
        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_QWEN_KEY), anyString());
        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_DEEPSEEK_KEY), anyString());
        // 关键:这条路径不许顺手写任何模型三元组(那正是死锁的来源)
        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_PLATFORM), anyString());
        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_MODEL_ID), anyString());

        assertThat(ra.getFlashAttributes().toString()).doesNotContain("ark-secret-key");
        verify(audit).record(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any(),
                anyString(), anyLong(),
                org.mockito.ArgumentMatchers.argThat(d -> !d.contains("ark-secret-key")));
    }

    /**
     * 空提交<b>不许当成保存成功</b>。这一格的语义是「留空 = 不改」,但用户点了这张卡的
     * 保存按钮却什么都没填,回一句「已保存」等于骗他 —— 他会以为换上了新 key、
     * 实际还在用旧的,之后调用失败根本查不到原因。
     */
    @Test
    void blankKey_isRejected_notSilentlyTreatedAsSaved() {
        for (String blank : new String[]{null, "", "   "}) {
            RedirectAttributes ra = new RedirectAttributesModelMap();
            controller().saveLlmKey(me(), "ark", blank, ra);
            assertThat(flashError(ra)).as("blank=[" + blank + "]").contains("密钥未改动");
            assertThat(ra.getFlashAttributes().get("flash")).as("blank=[" + blank + "] 不许有成功提示").isNull();
        }
        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_ARK_KEY), anyString());
    }

    @Test
    void unknownPlatformOnKeySave_isRejected() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlmKey(me(), "openai", "sk-whatever", ra);
        assertThat(flashError(ra)).contains("未知平台");
        verify(config, never()).set(anyLong(), anyString(), anyString());
    }

    // ════════════════════════════════════════════════════════════════
    // v1.18.4 · 把「用户到底有几种用法」逐个走一遍
    //
    //   维护者报的是一条:配好主选、【取消勾选】截图导入,保存却报
    //   「截图识别:请选择平台」。根因不是这一条,是整块校验按【字段填没填】分支,
    //   而不是按【用户想干什么】分支 —— 于是"关掉的能力"照样被要求填。
    //   下面按用法矩阵一条条钉,漏掉哪种用法,哪种就会再坏一次。
    // ════════════════════════════════════════════════════════════════

    /** 只填主选、把截图识别关掉、视觉三元组整个留空 —— <b>必须能存</b>。 */
    @Test
    void 用法_关掉截图识别时视觉留空也能存() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlmModels(me(),
                "dashscope", "qwen", "", null, null, null,
                false, null, null, null,          // 视觉:平台/系列/型号全空
                0.5, 2000, 25, ra);
        assertThat(flashError(ra)).as("关掉的能力不该拦住整单").isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_VISION_ENABLED, "false");
    }

    /**
     * 只配了 DeepSeek(<b>没有视觉能力</b>)+ 关掉截图识别 —— 必须能存。
     * 这正是维护者撞上的那条:视觉下拉里一个可选项都没有,关掉它还是存不下去。
     */
    @Test
    void 用法_只配无视觉能力的平台_关掉截图识别能存() {
        when(config.isPrivateKeyConfigured(anyLong(), anyString())).thenReturn(false);
        when(config.isPrivateKeyConfigured(anyLong(), eq(FamilyConfigService.K_LLM_DEEPSEEK_KEY))).thenReturn(true);
        RedirectAttributes ra = new RedirectAttributesModelMap();
        newController()
                .saveLlmModels(me(), "deepseek", "deepseek", "", null, null, null,
                        false, null, null, null, 0.5, 2000, 25, ra);
        assertThat(flashError(ra)).isNull();
    }

    /** 同上但<b>勾了</b>截图识别 —— 要明确告诉他去配哪家,而不是回一句他看不懂的「请选择平台」。 */
    @Test
    void 用法_只配无视觉能力的平台_勾了截图识别要指路() {
        when(config.isPrivateKeyConfigured(anyLong(), anyString())).thenReturn(false);
        when(config.isPrivateKeyConfigured(anyLong(), eq(FamilyConfigService.K_LLM_DEEPSEEK_KEY))).thenReturn(true);
        RedirectAttributes ra = new RedirectAttributesModelMap();
        newController()
                .saveLlmModels(me(), "deepseek", "deepseek", "", null, null, null,
                        true, "dashscope", "qwen-vl", "", 0.5, 2000, 25, ra);
        assertThat(flashError(ra)).contains("阿里云百炼").contains("火山方舟");
        assertThat(flashError(ra)).as("要给出「不用就取消勾选」这条出路").contains("取消勾选");
    }

    /** 一家密钥都没配就来存模型 —— 错误要指向「先去上面存密钥」,不能只说「请选择平台」。 */
    @Test
    void 用法_一家都没配密钥时错误要指路() {
        when(config.isPrivateKeyConfigured(anyLong(), anyString())).thenReturn(false);
        RedirectAttributes ra = new RedirectAttributesModelMap();
        newController()
                .saveLlmModels(me(), null, null, null, null, null, null,
                        false, null, null, null, 0.5, 2000, 25, ra);
        assertThat(flashError(ra)).contains("保存 API Key");
    }

    /** 选了一个<b>没配密钥</b>的平台(前端 disabled 被绕过)—— 当面拒绝,别存一份必然调不通的配置。 */
    @Test
    void 用法_选了没配密钥的平台要当面拒绝() {
        when(config.isPrivateKeyConfigured(anyLong(), anyString())).thenReturn(false);
        when(config.isPrivateKeyConfigured(anyLong(), eq(FamilyConfigService.K_LLM_QWEN_KEY))).thenReturn(true);
        RedirectAttributes ra = new RedirectAttributesModelMap();
        newController()
                .saveLlmModels(me(), "deepseek", "deepseek", "", null, null, null,
                        false, null, null, null, 0.5, 2000, 25, ra);
        assertThat(flashError(ra)).contains("还没有配密钥");
        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_PLATFORM), anyString());
    }

    /** 关掉截图识别时,<b>不许清空</b>之前配好的视觉三元组(关它往往只是暂时不用)。 */
    @Test
    void 用法_关掉截图识别不清空旧的视觉配置() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlmModels(me(), "dashscope", "qwen", "", null, null, null,
                false, null, null, null, 0.5, 2000, 25, ra);
        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_VISION_PLATFORM), anyString());
        verify(config, never()).set(anyLong(), eq(FamilyConfigService.K_LLM_VISION_MODEL_ID), anyString());
    }

    /** 关掉截图识别、但视觉三元组仍然填着 —— 顺手存下来(下次开启还在),同样不报错。 */
    @Test
    void 用法_关掉截图识别但填着视觉配置_顺手存下来() {
        RedirectAttributes ra = new RedirectAttributesModelMap();
        controller().saveLlmModels(me(), "dashscope", "qwen", "", null, null, null,
                false, "dashscope", "qwen-vl", "qwen-vl-max", 0.5, 2000, 25, ra);
        assertThat(flashError(ra)).isNull();
        verify(config).set(1L, FamilyConfigService.K_LLM_VISION_MODEL_ID, "qwen-vl-max");
    }

    /** 火山方舟现在<b>有</b>预置型号了(v1.18.4 调研:方舟支持直接填 Model ID,不必建接入点)。 */
    @Test
    void 方舟不再要求手填型号_预置了推荐型号() {
        var doubao = LlmCatalog.ARK.family("doubao").orElseThrow();
        assertThat(doubao.requiresExplicitModel()).as("以前这里是 true,页面只会说「没有可预置的型号」").isFalse();
        assertThat(doubao.models()).isNotEmpty();
        assertThat(doubao.defaultModel()).as("默认型号不带日期,不会随版本更迭失效")
                .isEqualTo("doubao-seed-evolving").doesNotContain("-25").doesNotContain("-26");

        var vision = LlmCatalog.ARK.family("doubao-vision").orElseThrow();
        assertThat(vision.requiresExplicitModel()).isFalse();
        assertThat(vision.models()).isNotEmpty();

        // 预置的每个型号都得过格式校验(否则页面选一下就报「型号格式不合法」)
        for (var f : LlmCatalog.ARK.families()) {
            for (var m : f.models()) {
                assertThat(LlmCatalog.validModel(m.id())).as(m.id()).isTrue();
            }
        }
    }
}
