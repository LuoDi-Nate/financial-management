package com.family.finance.service.review;

import com.family.finance.calc.review.AttributionEngine;
import com.family.finance.repository.ReviewAiCacheMapper;
import com.family.finance.service.checkup.llm.LlmRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.19.16 · AI 月度复盘缓存的两条失效规则。
 *
 * <p>线上用户(issue #17)报「改完上月、重新关账后仪表盘还是之前的数据」。
 * 复现下来数字其实都更新了,<b>只有这段 AI 解读没动</b> ——
 * {@code review_ai_cache} 按 {@code (family, period, dim)} 存,而全仓
 * <b>没有任何地方失效过它</b>({@code ReviewAiCacheMapper} 里连 delete 方法都没有)。</p>
 *
 * <p>这类失败不报错、不降级:数字全对,解读全错,而且读起来完全通顺。
 * 所以两条规则都得用测试钉住,而不是靠记得。</p>
 */
class ReviewCacheStalenessTest {

    private static final AttributionEngine.Result EMPTY_ATTR =
            new AttributionEngine.Result(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());

    private ReviewInsightService svc(ReviewAiCacheMapper cache, LlmRouter router) {
        // memberDirectory 只在真去调模型的路径上用到(脱敏),这几条用例都到不了那里
        return new ReviewInsightService(router,
                mock(com.family.finance.service.member.MemberDirectory.class), cache);
    }

    @Test
    @DisplayName("已关账的期:命中缓存就直接返回,不调模型")
    void closedPeriodUsesCache() {
        var cache = mock(ReviewAiCacheMapper.class);
        var router = mock(LlmRouter.class);
        when(cache.find(1L, 162L, "acct"))
                .thenReturn(new ReviewAiCacheMapper.Row(9L, 1L, 162L, "acct", "旧结论", "dashscope"));

        var r = svc(cache, router).review(1L, 162L, "2026-08", "acct",
                EMPTY_ATTR, new LinkedHashMap<>(), true, false);

        assertThat(r.cached()).isTrue();
        assertThat(r.text()).isEqualTo("旧结论");
        verify(router, never()).invoke(anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("进行中的期:一律不读缓存 —— 数据还在动,存下来的解读必然过期")
    void openPeriodNeverReadsCache() {
        var cache = mock(ReviewAiCacheMapper.class);
        var router = mock(LlmRouter.class);

        svc(cache, router).review(1L, 163L, "2026-09", "acct",
                EMPTY_ATTR, new LinkedHashMap<>(), false, false);

        // 连查都不该查 —— 查了再丢掉不算数,那只是碰巧没用上
        verify(cache, never()).find(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("进行中的期:也不许写缓存 —— 否则这一期关账后会把月中生成的解读当成本期定论端出来")
    void openPeriodNeverWritesCache() {
        var cache = mock(ReviewAiCacheMapper.class);
        var router = mock(LlmRouter.class);
        // router 不真跑,只确认「就算跑完了也不落库」这条路径上没有 upsert
        svc(cache, router).review(1L, 163L, "2026-09", "acct",
                EMPTY_ATTR, new LinkedHashMap<>(), false, false);

        verify(cache, never()).upsert(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("force=true 时已关账的期也跳过缓存(用户手动点重算)")
    void forceSkipsCacheEvenWhenClosed() {
        var cache = mock(ReviewAiCacheMapper.class);
        var router = mock(LlmRouter.class);

        svc(cache, router).review(1L, 162L, "2026-08", "acct",
                EMPTY_ATTR, new LinkedHashMap<>(), true, true);

        verify(cache, never()).find(anyLong(), anyLong(), anyString());
    }
}
