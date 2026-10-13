package com.family.finance.service.fixedflow;

import com.family.finance.repository.FixedFlowMapper;
import com.family.finance.service.expense.imports.BillCategoryResolver;
import com.family.finance.service.expense.imports.BillCategoryResolver.Bucket;
import com.family.finance.service.expense.imports.BillCategoryResolver.Line;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** v1.32 FR-1150 · 先确认、后导入:同账户同金额的那一行默认不勾、写原因;一条只抵一行 */
class FixedFlowImportDedupeTest {

    private static Line line(int idx, String amount, Long acct) {
        return new Line(idx, LocalDate.of(2026, 10, 2), "m" + idx, new BigDecimal(amount), 1L, "其他", null,
                Bucket.SPEND, null, null, "T" + idx, "余额宝", acct, null);
    }

    @Test
    void sameAccountSameAmount_movesToSuggestSkip_onceOnly() {
        FixedFlowMapper mapper = mock(FixedFlowMapper.class);
        when(mapper.confirmedExpenses(1L, 9L)).thenReturn(List.of(
                new FixedFlowMapper.ConfirmedExpense(6L, new BigDecimal("300.00"), "车位月保")));
        var draft = new BillCategoryResolver.Draft(null,
                List.of(line(0, "300.00", 6L), line(1, "300.00", 6L), line(2, "300.00", 7L), line(3, "45.00", 6L)), 4, null, 0);
        var out = new FixedFlowImportDedupe(mapper).markConfirmed(1L, 9L, draft).lines();
        assertThat(out.get(0).bucket()).isEqualTo(Bucket.SUGGEST_SKIP);
        assertThat(out.get(0).defaultIncluded()).as("默认不勾").isFalse();
        assertThat(out.get(0).selectable()).as("但能勾回来").isTrue();
        assertThat(out.get(0).suggestReason()).contains("已作为固定支出记过").contains("车位月保");
        assertThat(out.get(1).bucket()).as("同一天同账户两笔同样的钱是真的 —— 一条固定支出只抵一行").isEqualTo(Bucket.SPEND);
        assertThat(out.get(2).bucket()).as("别的账户不算").isEqualTo(Bucket.SPEND);
        assertThat(out.get(3).bucket()).isEqualTo(Bucket.SPEND);
    }

    @Test
    void nothingConfirmed_draftUntouched() {
        FixedFlowMapper mapper = mock(FixedFlowMapper.class);
        when(mapper.confirmedExpenses(1L, 9L)).thenReturn(List.of());
        var draft = new BillCategoryResolver.Draft(null, List.of(line(0, "300.00", 6L)), 1, null, 0);
        assertThat(new FixedFlowImportDedupe(mapper).markConfirmed(1L, 9L, draft)).isSameAs(draft);
    }
}
