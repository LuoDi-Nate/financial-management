package com.family.finance.service.trash;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.AccountTrashMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PrincipalAdjustmentMapper;
import com.family.finance.repository.SnapshotTodoMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** v1.33 · 预演:逐笔划转的改记方式、确认语、比对规则(PRD FR-1211 / 1212) */
class AccountDeletePlannerTest {

    static final long FAM = 1L, X = 50L, WAGE = 2L, YEB = 6L, FRESH = 9L;

    @Test
    void phraseNormalization_ignoresTrailingSpacesBlankLinesAndCrlf() {
        String want = "我知晓删除「测试」会改写 2025-01 至 2025-02 共 2 个账期,\n202501 账期 工资卡 的转账 111.00 元也会受到影响";
        assertThat(AccountDeletePlanner.phraseMatches(want,
                "我知晓删除「测试」会改写 2025-01 至 2025-02 共 2 个账期,   \r\n\r\n202501 账期 工资卡 的转账 111.00 元也会受到影响\n")).isTrue();
        assertThat(AccountDeletePlanner.phraseMatches(want, want.replace("111.00", "111"))).as("金额差一点也不行").isFalse();
        assertThat(AccountDeletePlanner.phraseMatches(want, want.split("\n")[0])).as("少一行也不行").isFalse();
        assertThat(AccountDeletePlanner.phraseMatches(want, " " + want)).as("行首空格不忽略").isFalse();
    }

    @Test
    void eachTransferGetsItsRewriteMode_andAPhraseLine() {
        AccountMapper accounts = mock(AccountMapper.class);
        AccountTrashMapper trash = mock(AccountTrashMapper.class);
        PeriodMapper periods = mock(PeriodMapper.class);
        PrincipalAdjustmentMapper pa = mock(PrincipalAdjustmentMapper.class);
        FamilyMapper families = mock(FamilyMapper.class);
        acc(accounts, X, "测试账户");
        acc(accounts, WAGE, "工资卡");
        acc(accounts, YEB, "余额宝");
        acc(accounts, FRESH, "招行理财");
        Period jan = period(periods, 101, "2025-01-01", PeriodStatus.CLOSED);
        Period feb = period(periods, 102, "2025-02-01", PeriodStatus.CLOSED);
        Period jun = period(periods, 106, "2025-06-01", PeriodStatus.OPEN);
        when(trash.affectedPeriodIds(FAM, X)).thenReturn(List.of(106L, 101L, 102L));
        when(trash.flowTotals(eq(FAM), eq(X), anyString())).thenReturn(Map.of("n", 0L, "total", BigDecimal.ZERO));
        when(trash.liveTransfers(FAM, X)).thenReturn(List.of(
                new AccountTrashMapper.TransferRow(1L, 101L, X, WAGE, new BigDecimal("111"), null),          // 它 → 工资卡:对方收到
                new AccountTrashMapper.TransferRow(2L, 102L, YEB, X, new BigDecimal("112"), null),           // 余额宝 → 它:对方转出
                new AccountTrashMapper.TransferRow(3L, 106L, X, FRESH, new BigDecimal("50000"), null)));     // 转去开户
        when(pa.findPreviousEndBalance(eq(FAM), eq(WAGE), any())).thenReturn(new BigDecimal("1"));
        when(pa.findPreviousEndBalance(eq(FAM), eq(YEB), any())).thenReturn(new BigDecimal("1"));
        when(pa.findPreviousEndBalance(eq(FAM), eq(FRESH), any())).thenReturn(null);   // 招行理财这一期第一次出现
        when(families.findById(FAM)).thenReturn(Optional.of(Family.builder().id(FAM).baseCurrency("CNY").build()));
        when(periods.findLatestClosedAsOf(anyLong(), any())).thenReturn(Optional.empty());
        when(periods.findBalancePeriod(anyLong())).thenReturn(Optional.empty());

        var planner = new AccountDeletePlanner(accounts, trash, periods, pa, mock(SnapshotTodoMapper.class), families,
                mock(FactViewService.class));
        var plan = planner.plan(FAM, X);

        assertThat(plan.rangeLabel()).isEqualTo("2025-01 至 2025-06 共 3 个账期");
        assertThat(plan.closedCount()).isEqualTo(2);
        assertThat(plan.transfers()).extracting(AccountDeletePlanner.TransferImpact::mode)
                .containsExactly(AccountDeletePlanner.PA_IN, AccountDeletePlanner.PA_OUT, AccountDeletePlanner.OPENING);
        assertThat(plan.transfers().get(0).line()).contains("工资卡").contains("收到它转来 ¥111.00").contains("补录本金」+¥111.00");
        assertThat(plan.transfers().get(1).line()).contains("余额宝").contains("转给它 ¥112.00").contains("本金移出」−¥112.00");
        assertThat(plan.transfers().get(2).line()).contains("第一次出现").contains("开账基线");
        // 面板上那一笔的符号由方向定(模板不许硬拼负号 · v1221-NO-HARDCODED-MINUS)
        assertThat(plan.transfers().get(0).rewriteLabel()).isEqualTo("+¥111.00");
        assertThat(plan.transfers().get(1).rewriteLabel()).isEqualTo("−¥112.00");
        assertThat(plan.phrase()).isEqualTo("""
                我知晓删除「测试账户」会改写 2025-01 至 2025-06 共 3 个账期,
                202501 账期 工资卡 的转账 111.00 元也会受到影响,
                202502 账期 余额宝 的转账 112.00 元也会受到影响,
                202506 账期 招行理财 的转账 50,000.00 元也会受到影响。""");
        assertThat(plan.counterpartCount()).isEqualTo(3);
    }

    @Test
    void accountWithoutHistory_phraseIsOneSentence_endingWithAFullStop() {
        AccountMapper accounts = mock(AccountMapper.class);
        AccountTrashMapper trash = mock(AccountTrashMapper.class);
        acc(accounts, X, "空账户");
        when(trash.affectedPeriodIds(FAM, X)).thenReturn(List.of());
        when(trash.flowTotals(eq(FAM), eq(X), anyString())).thenReturn(Map.of("n", 0L, "total", BigDecimal.ZERO));
        when(trash.liveTransfers(FAM, X)).thenReturn(List.of());
        PeriodMapper periods = mock(PeriodMapper.class);
        when(periods.findLatestClosedAsOf(anyLong(), any())).thenReturn(Optional.empty());
        when(periods.findBalancePeriod(anyLong())).thenReturn(Optional.empty());
        var plan = new AccountDeletePlanner(accounts, trash, periods, mock(PrincipalAdjustmentMapper.class),
                mock(SnapshotTodoMapper.class), mock(FamilyMapper.class), mock(FactViewService.class)).plan(FAM, X);
        assertThat(plan.phrase()).isEqualTo("我知晓删除「空账户」不会改写任何账期。");
        assertThat(plan.rangeLabel()).isNull();
    }

    @Test
    void historyButNoTransfers_firstLineEndsWithAFullStop_notAComma() {
        AccountMapper accounts = mock(AccountMapper.class);
        AccountTrashMapper trash = mock(AccountTrashMapper.class);
        PeriodMapper periods = mock(PeriodMapper.class);
        acc(accounts, X, "银行卡");
        period(periods, 101, "2025-01-01", PeriodStatus.CLOSED);
        period(periods, 102, "2025-02-01", PeriodStatus.OPEN);
        when(trash.affectedPeriodIds(FAM, X)).thenReturn(List.of(101L, 102L));
        when(trash.flowTotals(eq(FAM), eq(X), anyString())).thenReturn(Map.of("n", 0L, "total", BigDecimal.ZERO));
        when(trash.liveTransfers(FAM, X)).thenReturn(List.of());
        when(periods.findLatestClosedAsOf(anyLong(), any())).thenReturn(Optional.empty());
        when(periods.findBalancePeriod(anyLong())).thenReturn(Optional.empty());
        var plan = new AccountDeletePlanner(accounts, trash, periods, mock(PrincipalAdjustmentMapper.class),
                mock(SnapshotTodoMapper.class), mock(FamilyMapper.class), mock(FactViewService.class)).plan(FAM, X);
        assertThat(plan.phrase()).isEqualTo("我知晓删除「银行卡」会改写 2025-01 至 2025-02 共 2 个账期。");
    }

    private static void acc(AccountMapper m, long id, String name) {
        when(m.findById(FAM, id)).thenReturn(Optional.of(Account.builder().id(id).familyId(FAM).displayName(name)
                .type(AccountType.CASH).currency("CNY").build()));
    }

    private static Period period(PeriodMapper m, long id, String start, PeriodStatus st) {
        LocalDate s = LocalDate.parse(start);
        Period p = Period.builder().id(id).familyId(FAM).periodStart(s).periodEnd(s.plusMonths(1).minusDays(1)).status(st).build();
        when(m.findById(FAM, id)).thenReturn(Optional.of(p));
        return p;
    }
}
