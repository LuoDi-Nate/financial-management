package com.family.finance.service.checkup.llm;

import com.family.finance.service.llmtrace.LlmSource;
import com.family.finance.service.review.ReviewInsightService;
import com.family.finance.repository.ReviewAiCacheMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LlmSourceTest {
    String source() throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("platform","custom","displayName","原合成服务",
            "model","Org/"+"a".repeat(200),"customRevision","old-revision","selectionFingerprint","old-fingerprint"));
    }
    @Test void historicalSourceSurvivesSelectionChangeAndDoesNotUseCurrentName() throws Exception {
        String old=source();
        assertThat(new LlmSource.Context("old-fingerprint",true).current(old)).isTrue();
        assertThat(new LlmSource.Context("new-fingerprint",true).historical(old)).isTrue();
        assertThat(new LlmSource.Context("built-in",false).historical(old)).isTrue();
        assertThat(LlmSource.label(old,"fallback")).contains("原合成服务","a".repeat(200));
        assertThat(new LlmSource.Context("built-in",false).current(null)).isTrue();
        assertThat(new LlmSource.Context("custom",true).current(null)).isFalse();
    }
    @Test void promptLinkIsOptionalAndLongSourceHasNoLegacyColumnTruncation() throws Exception {
        String old=source();String attached=LlmSource.withRecord(old,42L);
        assertThat(LlmSource.recordId(attached)).isEqualTo(42L);
        assertThat(LlmSource.label(attached,"fallback")).isEqualTo(LlmSource.label(old,"fallback"));
        assertThat(LlmSource.legacyVendor("custom · Org/"+"a".repeat(200))).isEqualTo("custom");
        assertThat(LlmSource.label(LlmSource.withRecord(null,42L),"未调用")).isEqualTo("未调用");
    }
    @Test void cachedClosedReviewIsKeptAsHistoryWithoutAutomaticGenerationOrWrite() throws Exception {
        var cache=mock(ReviewAiCacheMapper.class);var router=mock(LlmRouter.class);var sources=mock(LlmSource.class);
        String old=source();when(sources.current(1,old)).thenReturn(false);
        when(cache.find(1,2,"acct")).thenReturn(new ReviewAiCacheMapper.Row(1L,1L,2L,"acct","合成旧报告","custom",null,null,old));
        var service=new ReviewInsightService(router,mock(com.family.finance.service.member.MemberDirectory.class),cache,
            mock(com.family.finance.service.expense.NormalExpenseService.class));
        ReflectionTestUtils.setField(service,"sources",sources);
        var empty=new com.family.finance.calc.review.AttributionEngine.Result(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,
            BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,List.of());
        var result=service.review(1,2,"2026-01","acct",empty,new LinkedHashMap<>(),true,false);
        assertThat(result.text()).isEqualTo("合成旧报告");assertThat(result.historical()).isTrue();
        assertThat(result.vendor()).contains("原合成服务","a".repeat(200));verifyNoInteractions(router);
        verify(cache,never()).upsert(anyLong(),anyLong(),anyString(),anyString(),anyString(),any(),any());
    }
}
