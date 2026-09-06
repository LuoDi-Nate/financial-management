package com.family.finance.service.checkup.llm;

import com.family.finance.calc.review.AttributionEngine;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.repository.ReviewAiCacheMapper;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.lens.LensAiTagService;
import com.family.finance.service.review.ReviewInsightService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.13 §0.1 回归 · <b>六个调用点全部</b>受主选配置影响。
 *
 * <p>这一版存在的原因就是这个 bug:主备顺序<b>写在配置里、却没人读</b>。
 * 六处调用点各自注入 {@code List<LlmClient>} 裸遍历,实际顺序由 Spring 的 bean 顺序决定 ——
 * 用户在管理页把主选改成 DeepSeek,调用照旧先打百炼,页面上却显示配好了。</p>
 *
 * <p>所以只测 {@link LlmRouter} 本身是不够的:路由排对了,但只要有<b>一个</b>调用点还留着
 * 自己的 client 列表,那一处就依旧不听配置。这里对六处<b>逐个</b>断言两件事:
 * ① 结构上手里只有路由、没有任何 client(回退到裸遍历 → 红);
 * ② 端到端确实按配置里的主选先打(拿两个假 client 看谁先被调到)。</p>
 */
class LlmCallSiteRoutingTest {

    /** v1.13 收口后允许调用 LLM 的全部业务入口 · 加一处新的就往这里加一行(并接受同样的约束) */
    private static final List<Class<?>> CALL_SITES = List.of(
            LlmDiagnoseService.class,
            com.family.finance.service.lens.LensInsightService.class,
            LensAiTagService.class,
            com.family.finance.service.allocation.RebalanceAdvisorService.class,
            com.family.finance.service.goal.GoalLlmService.class,
            ReviewInsightService.class);

    // ==================== ① 结构:手里只有路由 ====================

    @Test
    void everyCallSite_holdsTheRouter_andNoClientAtAll() {
        for (Class<?> c : CALL_SITES) {
            List<String> routers = new ArrayList<>();
            List<String> clients = new ArrayList<>();
            for (Field f : c.getDeclaredFields()) {
                String generic = f.getGenericType().getTypeName();
                if (LlmRouter.class.equals(f.getType())) routers.add(f.getName());
                if (generic.contains(LlmClient.class.getName())) clients.add(f.getName() + ":" + generic);
            }
            assertThat(routers)
                    .as("%s 没有(或有多个)LlmRouter 字段 · 主备编排必须从这一个入口走", c.getSimpleName())
                    .hasSize(1);
            assertThat(clients)
                    .as("%s 又自己拿着 client 了(%s)· 这正是 §0.1 那个 bug 的形状:"
                        + "手里有列表就会按注入顺序遍历,配置里的主选形同虚设", c.getSimpleName(), clients)
                    .isEmpty();
        }
    }

    /**
     * 连 {@code import} 都不该有:字段没了但 import 还在,通常意味着某个方法里还在
     * {@code new} 或强转 client —— 反射看字段看不到那种写法。
     */
    @Test
    void everyCallSite_doesNotImportLlmClient_andGoesThroughTheRouter() throws Exception {
        for (Class<?> c : CALL_SITES) {
            Path src = Path.of("src/main/java", c.getName().replace('.', '/') + ".java");
            assertThat(Files.exists(src)).as("%s 找不到源文件", src).isTrue();
            String text = Files.readString(src, StandardCharsets.UTF_8);

            assertThat(text)
                    .as("%s 还 import 着 LlmClient · 唯一合法的注入点是 LlmRouter", c.getSimpleName())
                    .doesNotContain("import " + LlmClient.class.getName() + ";");
            assertThat(text)
                    .as("%s 没有一处 llmRouter.invoke( · 那它是怎么调到模型的?", c.getSimpleName())
                    .contains("llmRouter.invoke(");
        }
    }

    // ==================== ② 端到端:按配置里的主选先打 ====================

    /** 记录被调到的顺序;第一个候选永远失败 → 能同时看出「谁先」和「失败后换谁」 */
    private static final class FakeClient implements LlmClient {
        private final String platform;
        private final String reply;
        private final List<String> log;
        FakeClient(String platform, String reply, List<String> log) {
            this.platform = platform; this.reply = reply; this.log = log;
        }
        @Override public String platform() { return platform; }
        @Override public boolean available() { return true; }
        @Override public String chat(LlmInvocation invocation, String systemPrompt, String userPrompt) {
            log.add(invocation.platform());
            if (reply == null) throw new IllegalStateException("模拟 " + platform + " 失败");
            return reply;
        }
    }

    /** 主选 = DeepSeek 官方、备选 = 阿里云百炼(与 bean 注入顺序<b>相反</b>,顺序只能来自配置) */
    private static FamilyConfigService configPrimaryDeepseek() {
        Map<String, String> values = Map.of(
                FamilyConfigService.K_LLM_PLATFORM, LlmCatalog.P_DEEPSEEK,
                FamilyConfigService.K_LLM_FAMILY, "deepseek",
                FamilyConfigService.K_LLM_BACKUP_PLATFORM, LlmCatalog.P_DASHSCOPE,
                FamilyConfigService.K_LLM_BACKUP_FAMILY, "qwen");
        FamilyConfigService cfg = mock(FamilyConfigService.class);
        when(cfg.getString(anyLong(), anyString(), any())).thenAnswer(i -> {
            String key = i.getArgument(1);
            return values.containsKey(key) ? values.get(key) : i.getArgument(2);
        });
        return cfg;
    }

    private static LlmRouter routerWithLog(List<String> log, String reply) {
        // bean 顺序故意是「百炼在前」:回退到裸遍历的话,日志第一条就会是 dashscope
        return new LlmRouter(List.of(
                new FakeClient(LlmCatalog.P_DASHSCOPE, reply, log),
                new FakeClient(LlmCatalog.P_DEEPSEEK, reply, log)), configPrimaryDeepseek());
    }

    @Test
    void lensAiTag_callsPrimaryFirst_thenBackup() {
        List<String> log = new ArrayList<>();
        // 第一家返回不可用输出(白名单过滤后为空)→ 按 Handler 约定继续试下一家
        LlmRouter router = new LlmRouter(List.of(
                new FakeClient(LlmCatalog.P_DASHSCOPE, "{\"宁德时代\":{\"industry\":\"NEW_ENERGY\"}}", log),
                new FakeClient(LlmCatalog.P_DEEPSEEK, "输出里没有一个合法标签", log)),
                configPrimaryDeepseek());

        Map<String, LensAiTagService.Tags> out =
                new LensAiTagService(router, new ObjectMapper()).suggest(1L, List.of("宁德时代"));

        assertThat(log).as("打标没有按配置的主选先打").containsExactly(
                LlmCatalog.P_DEEPSEEK, LlmCatalog.P_DASHSCOPE);
        assertThat(out.get("宁德时代").industry()).isEqualTo("NEW_ENERGY");   // 换到备选后拿到结果
    }

    @Test
    void reviewInsight_callsPrimaryFirst_andBadgeShowsWhoAnswered() {
        List<String> log = new ArrayList<>();
        LlmRouter router = routerWithLog(log, "· 本期结构无显著异常");

        // v1.15:展示/脱敏口径改走 MemberDirectory(含归档),不再是「仅活跃」的 memberMapper
        MemberDirectory members = mock(MemberDirectory.class);
        when(members.listAll(1L)).thenReturn(List.of());
        ReviewAiCacheMapper cache = mock(ReviewAiCacheMapper.class);

        var attr = new AttributionEngine.Result(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        var review = new ReviewInsightService(router, members, cache)
                // v1.19.16 · 多了一个 periodClosed 参数(未关账的期不碰缓存)。
                // 这里传「已关账 + force」——force 本来就是这条用例的意思:一定要真打一次模型。
                .review(1L, 7L, "2026-08", "account", attr, new LinkedHashMap<>(), true, true);

        assertThat(log).as("复盘没有按配置的主选先打").containsExactly(LlmCatalog.P_DEEPSEEK);
        assertThat(review.vendor()).as("徽记要说出真正回答的是谁(而不是写死第一家)")
                .startsWith(LlmCatalog.P_DEEPSEEK);
    }
}
