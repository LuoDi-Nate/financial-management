package com.family.finance.service.report;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.CashflowBreakdown;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.PeriodFlow;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.10 · 封板快照的计算口径。
 *
 * <p>这些断言守的是 tech-design v1.10 §4 里几条最容易做错的:
 * ① 瀑布恒等式的差额**恰好**是开账基线(不是"大概")② 截断轴不能除零
 * ③ HHI 的分母必须取绝对值 ④ 缺期时 Δ 是 `—` 而不是 0 或 100%
 * ⑤ 本期新出现的账户不许进正贡献。</p>
 */
class SealedPeriodServiceTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    private SealedPeriodService svc() {
        /* v1.20 · 归因贡献者改成按【账户组】折叠,所以多了一个 AccountGroupingResolver 依赖。
         * 这里给一个「没建任何组」的真实 resolver(不是 mock)—— 于是每个账户的维值就是它自己的账户名,
         * 下面所有既有断言的期望值【一个字都不用改】。
         * 这本身就是一条断言:零组态必须与 v1.19.16 逐字一致。 */
        var am = mock(com.family.finance.repository.AccountMapper.class);
        var gm = mock(com.family.finance.repository.AccountGroupMapper.class);
        var pm = mock(com.family.finance.repository.PeriodAccountGroupMapper.class);
        when(am.findAllByFamily(anyLong())).thenReturn(List.of());
        when(gm.findByFamily(anyLong())).thenReturn(List.of());
        when(gm.findMembersByFamily(anyLong())).thenReturn(List.of());
        when(pm.findByPeriod(anyLong(), anyLong())).thenReturn(List.of());
        return new SealedPeriodService(mock(FactViewService.class), mock(PeriodMapper.class),
                mock(FamilyMapper.class), mock(com.family.finance.repository.SnapshotMapper.class),
                mock(com.family.finance.service.member.MemberDirectory.class),
                new com.family.finance.service.group.AccountGroupingResolver(am, gm, pm));
    }

    private AccountPeriodFact row(long accId, String name, long periodId, int month,
                                  String endBase, AccountClass cls, AccountLiquidity liq, AccountType type) {
        LocalDate ps = LocalDate.of(2026, month, 1);
        BigDecimal e = new BigDecimal(endBase);
        return new AccountPeriodFact(accId, name, type, cls, liq, "CNY", 1L, 1, periodId, ps,
                ps.plusMonths(1).minusDays(1), null, e, null, e,
                Z, Z, Z, Z, Z, Z, Z, Z, null, null, BigDecimal.ONE);
    }

    private FactSlice slice(List<AccountPeriodFact> rows, List<Long> periodIds) {
        return new FactSlice(new FactFilter(1L, PeriodType.MONTHLY,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), false, null, "CNY"),
                rows, periodIds, periodIds.getLast());
    }

    private PeriodFlow flow(String prevNw, String nw, String netInflow, String ob) {
        BigDecimal d = new BigDecimal(nw).subtract(new BigDecimal(prevNw));
        BigDecimal pnl = d.subtract(new BigDecimal(netInflow)).subtract(new BigDecimal(ob));
        return new PeriodFlow(2L, LocalDate.of(2026, 2, 1), "2026-02",
                new BigDecimal(netInflow), new BigDecimal(ob), d, pnl,
                new BigDecimal(nw), new BigDecimal(prevNw));
    }

    // ── FR-323 · 瀑布 ─────────────────────────────────────────────────

    @Test
    void 没有新账户时恒等式必须闭合() {
        var wf = svc().buildWaterfall(flow("1000000", "1050000", "30000", "0"),
                new CashflowBreakdown(new BigDecimal("80000"), new BigDecimal("50000"), new BigDecimal("30000")));
        assertThat(wf.identityHolds()).isTrue();
        assertThat(wf.identityDiff()).isEqualByComparingTo("0");
        assertThat(wf.investPnl()).isEqualByComparingTo("20000");   // 50000 − 30000
    }

    @Test
    void 有新账户时差额恰好等于开账基线() {
        // 补录一个 12 万的存量账户:它既不是人赚也不是钱赚,恒等式差额必须**精确**等于它,
        // 页面据此给出「属外部资本纳入」的解释而不是显示一个来源不明的差额。
        var wf = svc().buildWaterfall(flow("1000000", "1170000", "30000", "120000"),
                new CashflowBreakdown(new BigDecimal("80000"), new BigDecimal("50000"), new BigDecimal("30000")));
        assertThat(wf.identityHolds()).isFalse();
        assertThat(wf.identityDiff()).isEqualByComparingTo("120000");
        assertThat(wf.diffExplainedByOpening()).isTrue();
    }

    @Test
    void 四个拐点全等时轴不除零() {
        // 收入=支出=损益=0 的一期(可能真发生:一分钱没动)
        var wf = svc().buildWaterfall(flow("1000000", "1000000", "0", "0"),
                new CashflowBreakdown(Z, Z, Z));
        assertThat(wf.axis().signum()).isPositive();
        assertThat(wf.axisHi()).isGreaterThan(wf.axisLo());
    }

    @Test
    void 轴按拐点minmax加余量_且不把全正数据拉到负轴() {
        var wf = svc().buildWaterfall(flow("1000000", "1050000", "30000", "0"),
                new CashflowBreakdown(new BigDecimal("80000"), new BigDecimal("50000"), new BigDecimal("30000")));
        // 拐点:100万 / 108万 / 103万 / 105万 → lo=100万 hi=108万 pad=1.2万
        assertThat(wf.axisLo()).isEqualByComparingTo("988000.00");
        assertThat(wf.axisHi()).isEqualByComparingTo("1092000.00");
        assertThat(wf.axisTruncated()).isTrue();       // 页面必须明示截断
    }

    @Test
    void 净资产为负时轴允许到负数() {
        var wf = svc().buildWaterfall(flow("-200000", "-180000", "20000", "0"),
                new CashflowBreakdown(new BigDecimal("50000"), new BigDecimal("30000"), new BigDecimal("20000")));
        assertThat(wf.axisLo().signum()).isNegative();
        assertThat(wf.axisTruncated()).isFalse();
    }

    // ── FR-325a · 集中度 ─────────────────────────────────────────────

    @Test
    void HHI分母取绝对值_大额房贷不许把集中度顶到1() {
        // 净资产接近 0(资产 200 万、房贷 195 万),但资产分散在 4 个账户。
        // 若分母用净值求和(5 万),HHI 会爆到远大于 1 —— 集中度就成了"有没有房贷"的函数。
        var rows = List.of(
                row(1, "A", 10L, 2, "500000", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH),
                row(2, "B", 10L, 2, "500000", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH),
                row(3, "C", 10L, 2, "500000", AccountClass.ASSET, AccountLiquidity.SEMI_LIQUID, AccountType.STOCK),
                row(4, "D", 10L, 2, "500000", AccountClass.ASSET, AccountLiquidity.SEMI_LIQUID, AccountType.STOCK),
                row(5, "房贷", 10L, 2, "-1950000", AccountClass.LIABILITY, AccountLiquidity.NA, AccountType.LOAN));
        var c = svc().buildConcentration(slice(rows, List.of(10L)), 10L, null);
        assertThat(c.hhi()).isLessThanOrEqualTo(BigDecimal.ONE);
        // 分母 = 200万 + 195万 = 395万 → 房贷占 49.37% → HHI ≈ 0.49²+4×0.127² ≈ 0.309
        assertThat(c.top1Pct()).isEqualByComparingTo("49.37");
        assertThat(c.top1Name()).isEqualTo("房贷");
        assertThat(c.hhi()).isBetween(new BigDecimal("0.28"), new BigDecimal("0.34"));
    }

    @Test
    void 单账户家庭HHI为1() {
        var rows = List.of(row(1, "唯一", 10L, 2, "100000", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH));
        var c = svc().buildConcentration(slice(rows, List.of(10L)), 10L, null);
        assertThat(c.hhi()).isEqualByComparingTo("1.0000");
        assertThat(c.top1Pct()).isEqualByComparingTo("100.00");
    }

    @Test
    void 全零余额返回null而不是除零() {
        var rows = List.of(row(1, "空", 10L, 2, "0", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH));
        assertThat(svc().buildConcentration(slice(rows, List.of(10L)), 10L, null)).isNull();
    }

    // ── FR-325b · 流动性分层 ────────────────────────────────────────

    @Test
    void 分层占比合计100_且NA并入不可动() {
        var rows = List.of(
                row(1, "现金", 10L, 2, "100000", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH),
                row(2, "股票", 10L, 2, "300000", AccountClass.ASSET, AccountLiquidity.SEMI_LIQUID, AccountType.STOCK),
                row(3, "房产", 10L, 2, "500000", AccountClass.ASSET, AccountLiquidity.ILLIQUID, AccountType.PROPERTY),
                row(4, "贷款", 10L, 2, "-100000", AccountClass.LIABILITY, AccountLiquidity.NA, AccountType.LOAN));
        var t = svc().buildLiquidity(slice(rows, List.of(10L)), 10L, new BigDecimal("10000"));
        assertThat(t.liquidPct().add(t.semiLiquidPct()).add(t.illiquidPct()))
                .isEqualByComparingTo("100.00");
        assertThat(t.illiquid()).isEqualByComparingTo("600000.00");   // 房产 50万 + NA 贷款 10万
        assertThat(t.coverMonths()).isEqualByComparingTo("10.0");     // 10万 ÷ 1万
    }

    @Test
    void 月均支出为0时覆盖月数是null不是无穷() {
        var rows = List.of(row(1, "现金", 10L, 2, "100000", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH));
        assertThat(svc().buildLiquidity(slice(rows, List.of(10L)), 10L, Z).coverMonths()).isNull();
    }

    // ── FR-326 · 归因 ────────────────────────────────────────────────

    @Test
    void 本期新出现的账户不许进正贡献() {
        // 上期只有 A;本期 A 涨了 1 万,同时补录了一个 50 万的存量账户 B。
        // B 若进正贡献,页面会显示"本月最大功臣 B +50万" —— 那是补录不是赚钱。
        var rows = List.of(
                row(1, "A", 10L, 1, "100000", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH),
                row(1, "A", 20L, 2, "110000", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH),
                row(2, "B", 20L, 2, "500000", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH));
        Period prev = new Period();
        prev.setId(10L);
        prev.setPeriodStart(LocalDate.of(2026, 1, 1));
        var a = svc().buildAttribution(1L, slice(rows, List.of(10L, 20L)), 20L, prev,
                flow("100000", "610000", "0", "500000"));
        assertThat(a.positives()).extracting(SealedSnapshot.Contribution::accountName).containsExactly("A");
        assertThat(a.opened()).extracting(SealedSnapshot.Contribution::accountName).containsExactly("B");
        assertThat(a.negatives()).isEmpty();
    }

    /** v1.30.1 · 带本期转入 / 转出 / 补录本金的一行(金额全是编的) */
    private AccountPeriodFact rowT(long accId, String name, long periodId, int month, String endBase,
                                   String in, String out, String principal) {
        LocalDate ps = LocalDate.of(2026, month, 1);
        BigDecimal e = new BigDecimal(endBase);
        return new AccountPeriodFact(accId, name, AccountType.WEALTH, AccountClass.ASSET, AccountLiquidity.LIQUID,
                "CNY", 1L, 1, periodId, ps, ps.plusMonths(1).minusDays(1), null, e, null, e,
                Z, Z, Z, Z, new BigDecimal(in), new BigDecimal(in), new BigDecimal(out), new BigDecimal(out),
                null, null, BigDecimal.ONE, new BigDecimal(principal), new BigDecimal(principal));
    }

    private Period prevPeriod() {
        Period prev = new Period();
        prev.setId(10L);
        prev.setPeriodStart(LocalDate.of(2026, 1, 1));
        return prev;
    }

    /**
     * v1.30.1 · prod 2026-09 的形状(数是编的):余额宝 A 转出 30 到新开的账户 C,A 自己另外少了 10;
     * 原来 A 排「拉下来」第一名 −40、C「资本纳入 +30」—— 那 30 只是家里挪了个地方。
     */
    @Test
    void 账户间划转不进贡献_转进新账户的钱不算资本纳入() {
        var rows = List.of(
                rowT(1, "A", 10L, 1, "100", "0", "0", "0"),
                rowT(1, "A", 20L, 2, "60", "0", "30", "0"),
                rowT(3, "C", 20L, 2, "30", "30", "0", "0"));
        var a = svc().buildAttribution(1L, slice(rows, List.of(10L, 20L)), 20L, prevPeriod(),
                flow("100", "90", "0", "0"));
        assertThat(a.negatives()).extracting(SealedSnapshot.Contribution::accountName).containsExactly("A");
        assertThat(a.negatives().getFirst().amount()).as("贡献 = 余额变化 −40 − 净划转 −30 = −10")
                .isEqualByComparingTo("-10");
        assertThat(a.opened()).as("全是转进来的钱:开账基线 0,不列").isEmpty();
        assertThat(a.positives()).isEmpty();
    }

    @Test
    void 两个老账户之间划转_两边都不算贡献() {
        var rows = List.of(
                rowT(1, "A", 10L, 1, "100", "0", "0", "0"),
                rowT(2, "B", 10L, 1, "50", "0", "0", "0"),
                rowT(1, "A", 20L, 2, "75", "0", "25", "0"),
                rowT(2, "B", 20L, 2, "80", "25", "0", "0"));
        var a = svc().buildAttribution(1L, slice(rows, List.of(10L, 20L)), 20L, prevPeriod(),
                flow("150", "155", "0", "0"));
        assertThat(a.negatives()).isEmpty();
        assertThat(a.positives()).extracting(SealedSnapshot.Contribution::accountName).containsExactly("B");
        assertThat(a.positives().getFirst().amount()).as("B 余额 +30 里 25 是 A 转来的,自己只涨了 5")
                .isEqualByComparingTo("5");
    }

    @Test
    void 新账户自带的钱才是资本纳入_补录本金也列在那里() {
        var rows = List.of(
                rowT(1, "A", 10L, 1, "100", "0", "0", "0"),
                rowT(1, "A", 20L, 2, "140", "0", "0", "40"),   // 已有账户补录本金 40
                rowT(3, "C", 20L, 2, "70", "20", "0", "0"));   // 新账户:20 是转进来的,50 是自带的
        var a = svc().buildAttribution(1L, slice(rows, List.of(10L, 20L)), 20L, prevPeriod(),
                flow("100", "210", "0", "90"));
        assertThat(a.positives()).as("A 的 +40 全是补录本金,不是贡献").isEmpty();
        assertThat(a.opened()).extracting(SealedSnapshot.Contribution::accountName)
                .containsExactlyInAnyOrder("A(补录本金)", "C");
        assertThat(a.opened()).filteredOn(c -> c.accountName().equals("C")).first()
                .extracting(SealedSnapshot.Contribution::amount).satisfies(v ->
                        assertThat((BigDecimal) v).as("C 的资本纳入 = 期末 70 − 首期转入 20(与家庭开账基线同一个函数)")
                                .isEqualByComparingTo("50"));
    }

    @Test
    void 没有上期时归因返回null而不是把全部当成正贡献() {
        var rows = List.of(row(1, "A", 10L, 1, "100000", AccountClass.ASSET, AccountLiquidity.LIQUID, AccountType.CASH));
        assertThat(svc().buildAttribution(1L, slice(rows, List.of(10L)), 10L, null,
                flow("0", "100000", "0", "100000"))).isNull();
    }

    // ── FR-324 · 缺期规则 ────────────────────────────────────────────

    @Test
    void 缺上期或去年同期时Δ必须是null_不是0也不是100pct() {
        // 新用户的第一期必然如此 —— 给出 Δ 就是误导
        var r = new SealedSnapshot.ComparisonRow("净资产", new BigDecimal("1000000"), null, null, false, false);
        assertThat(r.momDelta()).isNull();
        assertThat(r.momPct()).isNull();
        assertThat(r.yoyDelta()).isNull();
        assertThat(r.yoyPct()).isNull();
    }

    @Test
    void 分母为0时百分比是null不是无穷() {
        var r = new SealedSnapshot.ComparisonRow("投资损益", new BigDecimal("5000"), Z, null, false, false);
        assertThat(r.momDelta()).isEqualByComparingTo("5000");   // 差额照给
        assertThat(r.momPct()).isNull();                         // 百分比不给
    }

    @Test
    void 比率类不给百分比只给pp差() {
        var r = new SealedSnapshot.ComparisonRow("储蓄率", new BigDecimal("0.523"),
                new BigDecimal("0.575"), new BigDecimal("0.512"), true, false);
        assertThat(r.momDelta()).isEqualByComparingTo("-0.052");  // 页面渲染成 −5.2 pp
        assertThat(r.momPct()).isNull();
        assertThat(r.yoyDelta()).isEqualByComparingTo("0.011");
    }

    // ── FR-327 · live 口径缺失时的退化 ───────────────────────────────

    @Test
    void live字段缺失时不许把净流入算成0() {
        // 兼容构造器造出来的 KpiSnapshot 里 live* 全是 null。
        // 若 explain 硬算 liveIncome−liveExpense,净流入会显示 ¥0,而百分比是用另一个净流入
        // 得出的 → tooltip 自相矛盾。这条是发布预检的 mvn test 抓出来的,补测钉住:
        // live 缺失时必须原样退回锚已关账期那套实现(数值与 v1.10 之前逐字相同)。
        var k = new com.family.finance.factview.KpiSnapshot(
                new BigDecimal("70000"), new BigDecimal("70000"), Z, null, null,
                new BigDecimal("5000"), null,
                new BigDecimal("2000"), new BigDecimal("0.0307"), null, null,
                Z, Z, new BigDecimal("65000"), new BigDecimal("3000"));
        assertThat(k.liveMonthlyInvestReturnPct()).isNull();
        assertThat(k.liveIncome()).isNull();
        assertThat(k.lastNetInflow()).isEqualByComparingTo("3000");   // 退化路径要用这个,不是 0
    }

    // ── FR-322 · 对称条 ──────────────────────────────────────────────

    @Test
    void 对称条比例尺两侧同源_且全零不除零() {
        var bs = new SealedSnapshot.BalanceSheet(new BigDecimal("4611000"), new BigDecimal("5723000"),
                new BigDecimal("1112000"), new BigDecimal("0.1943"), new BigDecimal("864000"),
                new BigDecimal("36"), new BigDecimal("24000"), 12);
        assertThat(bs.assetSharePct()).isEqualByComparingTo("83.73");
        var zero = new SealedSnapshot.BalanceSheet(Z, Z, Z, null, Z, null, Z, 1);
        assertThat(zero.assetSharePct()).isEqualByComparingTo("100.00");
    }
}
