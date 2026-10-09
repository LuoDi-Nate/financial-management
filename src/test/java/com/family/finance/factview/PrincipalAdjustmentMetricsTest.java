package com.family.finance.factview;

import com.family.finance.calc.PnlCalculator;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.repository.FactMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * v1.30 · 补录本金(PRD FR-973 / 975)的口径:和开账基线是同一件事 —— 算本金,不算收入、不算收益。
 *
 * <p>场景(金额全是编的):一个一直在记的账户,上期期末 10 万;这期补录一只以前就买了的基金,
 * 市值 4 万,没有任何收支 / 划转。不记补录本金 → 这 4 万整笔落成「这期投资赚的」;
 * 记了 → 这期收益 0,4 万进本金(账户的累计净投入 / 家庭的开账基线)。</p>
 */
class PrincipalAdjustmentMetricsTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    private static FactViewServiceImpl svc() {
        PeriodMemberCashflowMapper pmc = mock(PeriodMemberCashflowMapper.class);
        CashflowBreakdownTest.wireBatchFromPointStubs(pmc);
        return new FactViewServiceImpl(mock(FactMapper.class), mock(com.family.finance.repository.PeriodMapper.class),
                mock(FamilyMapper.class), pmc, mock(com.family.finance.repository.AccountMapper.class),
                mock(com.family.finance.service.ProductCategoryService.class),
                mock(com.family.finance.repository.SnapshotMapper.class),
                mock(com.family.finance.repository.PeriodAccountAttrMapper.class),
                new com.family.finance.service.expense.ExpenseLedgerService(
                        mock(com.family.finance.repository.CashFlowMapper.class), pmc,
                        mock(FamilyMapper.class), mock(com.family.finance.repository.PeriodMapper.class)));
    }

    /** 走 FactProjector 造行 —— 补录本金在本期收益里扣掉、第一期归零,都是在投影这一步定的 */
    private static AccountPeriodFact row(long periodId, LocalDate ps, String prevEnd, String end, String adj) {
        return FactProjector.project(new FactBaseRow(
                1L, "支付宝", "WEALTH", "CNY", null, 0, periodId, ps, ps.plusMonths(1).minusDays(1),
                prevEnd == null ? null : new BigDecimal(prevEnd), new BigDecimal(end),
                Z, Z, Z, Z, BigDecimal.ONE, null, new BigDecimal(adj)));
    }

    private static FactSlice slice(List<AccountPeriodFact> rows) {
        FactFilter filter = new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1), false, null, "CNY");
        return new FactSlice(filter, rows, List.of(10L, 11L), null);
    }

    private static List<AccountPeriodFact> rows(String adj) {
        return List.of(
                row(10L, LocalDate.of(2026, 8, 1), "100000", "100000", "0"),
                row(11L, LocalDate.of(2026, 9, 1), "100000", "140000", adj));
    }

    @Test
    void 账户本期收益_扣掉补录本金() {
        assertThat(rows("0").get(1).periodPnlOrig()).as("不记:4 万整笔算这期赚的").isEqualByComparingTo("40000");
        assertThat(rows("40000").get(1).periodPnlOrig()).as("记了:这期收益 0").isEqualByComparingTo("0");
        assertThat(rows("40000").get(1).principalAdjBase()).isEqualByComparingTo("40000");
    }

    @Test
    void 账户第一期_补录本金归零_不和开账基线重复() {
        AccountPeriodFact first = row(10L, LocalDate.of(2026, 8, 1), null, "140000", "40000");
        assertThat(first.principalAdjOrig()).as("第一期的余额整笔算开账基线,补录本金不再认").isEqualByComparingTo("0");
        assertThat(first.periodPnlOrig()).isNull();
    }

    @Test
    void 六参版本与带补录本金为零的版本逐分相同() {
        BigDecimal a = PnlCalculator.periodPnl(new BigDecimal("12.34"), new BigDecimal("10.00"),
                new BigDecimal("1.11"), new BigDecimal("0.50"), Z, new BigDecimal("0.20"));
        BigDecimal b = PnlCalculator.periodPnl(new BigDecimal("12.34"), new BigDecimal("10.00"),
                new BigDecimal("1.11"), new BigDecimal("0.50"), Z, new BigDecimal("0.20"), Z);
        assertThat(b).isEqualTo(a);
    }

    @Test
    void 家庭级_补录本金进开账基线_钱赚不含它_恒等式闭合() {
        List<PeriodFlow> without = svc().periodFlows(slice(rows("0")));
        List<PeriodFlow> with = svc().periodFlows(slice(rows("40000")));
        assertThat(without.getLast().pnl()).as("不记:钱赚 4 万").isEqualByComparingTo("40000");
        assertThat(with.getLast().openingBaseline()).as("记了:开账基线 4 万").isEqualByComparingTo("40000");
        assertThat(with.getLast().pnl()).as("记了:钱赚 0").isEqualByComparingTo("0");
        PeriodFlow f = with.getLast();
        assertThat(f.netInflow().add(f.pnl()).add(f.openingBaseline()))
                .as("ΔNW = 人赚 + 钱赚 + 开账基线").isEqualByComparingTo(f.nwDelta());
    }

    @Test
    void 家庭级_TWR与XIRR_把补录本金当外部流入() {
        // 不记:净资产 10 万 → 14 万、没有外部流入 → 一期 40%;记了:4 万是流入 → 0%
        assertThat(svc().familyTwr(slice(rows("0")))).isNotNull().isPositive();
        assertThat(svc().familyTwr(slice(rows("40000")))).isEqualByComparingTo("0");
    }

    @Test
    void 账户级_累计净投入含补录本金_累计收益不含() {
        var perf = svc().accountPerformance(slice(rows("40000"))).getFirst();
        assertThat(perf.cumPnl()).isEqualByComparingTo("0");
        assertThat(perf.netPrincipal()).isEqualByComparingTo("40000");
        var perf0 = svc().accountPerformance(slice(rows("0"))).getFirst();
        assertThat(perf0.cumPnl()).isEqualByComparingTo("40000");
        assertThat(perf0.netPrincipal()).isEqualByComparingTo("0");
    }
}
