package com.family.finance.service.group;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.group.AccountGroup;
import com.family.finance.domain.account.AccountType;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.TrendPoint;
import com.family.finance.repository.AccountGroupMapper;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodAccountGroupMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.20 FR-482/483 · 账户行折叠的口径。
 *
 * <p>这个类里最重要的不是「加对了没有」,而是<b>「该给 null 的有没有真的给 null」</b> ——
 * 一个错的收益率比没有收益率危险得多:它看起来完全合理,没人会去质疑它。</p>
 */
class AccountRowGrouperTest {

    private static final long FAMILY = 1L;

    private AccountRowGrouper grouper(List<Account> accounts,
                                      List<AccountGroupMapper.Member> members,
                                      List<AccountGroup> groups) {
        AccountMapper am = mock(AccountMapper.class);
        AccountGroupMapper gm = mock(AccountGroupMapper.class);
        PeriodAccountGroupMapper pm = mock(PeriodAccountGroupMapper.class);
        when(am.findAllByFamily(anyLong())).thenReturn(accounts);
        when(gm.findByFamily(anyLong())).thenReturn(groups);
        when(gm.findMembersByFamily(anyLong())).thenReturn(members);
        when(pm.findByPeriod(anyLong())).thenReturn(List.of());
        return new AccountRowGrouper(new AccountGroupingResolver(am, gm, pm));
    }

    private static Account acct(long id, String name) {
        Account a = new Account();
        a.setId(id);
        a.setDisplayName(name);
        return a;
    }

    private static AccountGroup group(long id, String name) {
        AccountGroup g = new AccountGroup();
        g.setId(id);
        g.setName(name);
        return g;
    }

    /** 全字段构造:金额给值,比率也给值 —— 好验证「组行确实把比率抹成了 null」 */
    private static AccountPerformance perf(long id, String name, String amount, String rate,
                                           List<TrendPoint> spark) {
        BigDecimal v = new BigDecimal(amount);
        BigDecimal r = rate == null ? null : new BigDecimal(rate);
        return new AccountPerformance(
                id, name, AccountType.CASH, "CNY",
                v, r, spark,
                v, v, v, v, r, v,
                new BigDecimal("-5.5"), 8,
                "0,10 80,4", "up",
                r, new BigDecimal("3.0"), new BigDecimal("1.5"));
    }

    private static TrendPoint tp(long periodId, int month, String value) {
        return new TrendPoint(periodId, LocalDate.of(2026, month, 1), "2026-" + month, new BigDecimal(value));
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1 · 零组态:必须逐字一致 —— 这一版「不需要开关」全靠这条
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("没建任何组 → 逐行原样,一个字段都不加工")
    void zeroConfigIsByteIdentical() {
        var rows = List.of(perf(1, "招行", "1000", "0.05", List.of()),
                           perf(2, "支付宝", "500", "0.03", List.of()));
        var out = grouper(List.of(acct(1, "招行"), acct(2, "支付宝")), List.of(), List.of())
                .fold(FAMILY, null, null, rows);

        assertThat(out).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(out.get(i).grouped()).isFalse();
            assertThat(out.get(i).accountId()).isEqualTo(rows.get(i).accountId());
            assertThat(out.get(i).accountName()).isEqualTo(rows.get(i).accountName());
            // 比率类必须原样保留 —— 没建组的人不该因为这一版少看到任何一个数
            assertThat(out.get(i).xirr()).isEqualTo(rows.get(i).xirr());
            assertThat(out.get(i).returnBase()).isEqualTo(rows.get(i).returnBase());
            assertThat(out.get(i).maxDrawdownPct()).isEqualTo(rows.get(i).maxDrawdownPct());
            assertThat(out.get(i).planActualDiffPct()).isEqualTo(rows.get(i).planActualDiffPct());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2 · 组行:金额加、比率 null
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("组行的金额类相加")
    void groupSumsAmounts() {
        var out = twoAccountGroup();
        var g = out.stream().filter(AccountRowView::grouped).findFirst().orElseThrow();
        assertThat(g.accountName()).isEqualTo("日常周转");
        assertThat(g.currentValue()).isEqualByComparingTo("1500");   // 1000 + 500
        assertThat(g.cumPnl()).isEqualByComparingTo("1500");
        assertThat(g.netPrincipal()).isEqualByComparingTo("1500");
        assertThat(g.latestPnl()).isEqualByComparingTo("1500");
        assertThat(g.sharePct()).isEqualByComparingTo("1500");
    }

    @Test
    @DisplayName("【最重要】组行的收益率 / 回撤 / 预实一律 null —— 宁可显示「—」也不给一个算错的数")
    void groupNullsOutRatios() {
        var g = twoAccountGroup().stream().filter(AccountRowView::grouped).findFirst().orElseThrow();
        assertThat(g.xirr())
                .as("XIRR 是资金加权年化,不能相加也不能加权平均")
                .isNull();
        assertThat(g.returnBase())
                .as("本位币年化同理")
                .isNull();
        assertThat(g.maxDrawdownPct())
                .as("合并时序上成员开/关户会造出假回撤")
                .isNull();
        assertThat(g.expectedReturnPct())
                .as("预期收益率是账户级配置,组没有")
                .isNull();
        assertThat(g.planActualDiffPct())
                .as("没有预期就没有预实")
                .isNull();
    }

    @Test
    @DisplayName("组行不是账户:accountId 为 null(点不进账户详情,也拿不到基准)")
    void groupRowHasNoAccountId() {
        var g = twoAccountGroup().stream().filter(AccountRowView::grouped).findFirst().orElseThrow();
        assertThat(g.accountId()).isNull();
        assertThat(g.groupId()).isEqualTo(9L);
        assertThat(g.memberCount()).isEqualTo(2);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3 · 边界
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("只有一个成员的组不折叠 —— 折了反而要多点一次,比率还平白变成「—」")
    void singleMemberGroupStaysFlat() {
        var out = grouper(List.of(acct(1, "招行")),
                          List.of(new AccountGroupMapper.Member(9L, 1L)),
                          List.of(group(9, "日常周转")))
                .fold(FAMILY, null, null, List.of(perf(1, "招行", "1000", "0.05", List.of())));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).grouped()).isFalse();
        assertThat(out.get(0).accountName()).isEqualTo("招行");
        assertThat(out.get(0).xirr()).isEqualByComparingTo("0.05");   // 比率没被抹掉
    }

    @Test
    @DisplayName("组行落在【首个成员】原来的位次上 —— 建组前后视线落点不变")
    void groupKeepsPositionOfFirstMember() {
        var out = grouper(List.of(acct(1, "股票"), acct(2, "招行"), acct(3, "支付宝")),
                          List.of(new AccountGroupMapper.Member(9L, 2L),
                                  new AccountGroupMapper.Member(9L, 3L)),
                          List.of(group(9, "日常周转")))
                .fold(FAMILY, null, null, List.of(
                        perf(1, "股票", "9000", "0.12", List.of()),
                        perf(2, "招行", "1000", "0.05", List.of()),
                        perf(3, "支付宝", "500", "0.03", List.of())));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).accountName()).isEqualTo("股票");        // 第一行不变
        assertThat(out.get(1).accountName()).isEqualTo("日常周转");     // 组顶掉原来「招行」的位置
    }

    @Test
    @DisplayName("sparkline 按账期对齐相加;成员期数不同时,前几期只算已有的那个")
    void sparklineMergesByPeriod() {
        var out = grouper(List.of(acct(1, "招行"), acct(2, "支付宝")),
                          List.of(new AccountGroupMapper.Member(9L, 1L),
                                  new AccountGroupMapper.Member(9L, 2L)),
                          List.of(group(9, "日常周转")))
                .fold(FAMILY, null, null, List.of(
                        perf(1, "招行", "1000", "0.05", List.of(tp(1, 1, "100"), tp(2, 2, "200"))),
                        // 支付宝是第 2 期才开的 —— 第 1 期不该被当成 0 拉低曲线,它就是不存在
                        perf(2, "支付宝", "500", "0.03", List.of(tp(2, 2, "50")))));

        var g = out.stream().filter(AccountRowView::grouped).findFirst().orElseThrow();
        // 合并后:第1期=100、第2期=250 → 上升 → 有 2 点,能画
        assertThat(g.sparklinePoints()).isNotNull();
        assertThat(g.sparklineTrend()).isEqualTo("up");
    }

    @Test
    @DisplayName("flatten:组行后面紧跟成员行,成员行带 memberOf 标记(默认隐藏)")
    void flattenInterleavesMembers() {
        AccountRowGrouper g = grouper(List.of(acct(1, "招行"), acct(2, "支付宝")),
                                      List.of(new AccountGroupMapper.Member(9L, 1L),
                                              new AccountGroupMapper.Member(9L, 2L)),
                                      List.of(group(9, "日常周转")));
        var folded = g.fold(FAMILY, null, null, List.of(
                perf(1, "招行", "1000", "0.05", List.of()),
                perf(2, "支付宝", "500", "0.03", List.of())));
        var flat = g.flatten(folded);

        assertThat(flat).hasSize(3);
        assertThat(flat.get(0).grouped()).isTrue();
        assertThat(flat.get(0).memberOf()).isNull();
        assertThat(flat.get(1).memberOf()).isEqualTo(9L);
        assertThat(flat.get(2).memberOf()).isEqualTo(9L);
        // 成员行是【原样】的账户行:比率齐全、能点进详情
        assertThat(flat.get(1).xirr()).isNotNull();
        assertThat(flat.get(1).accountId()).isNotNull();
    }

    @Test
    @DisplayName("折叠后的顶层列表不含成员行 —— 横条图吃它,含了就会把钱算两遍")
    void foldedTopLevelExcludesMembers() {
        var out = twoAccountGroup();
        assertThat(out).hasSize(1);
        assertThat(out.get(0).currentValue()).isEqualByComparingTo("1500");
        assertThat(out.stream().anyMatch(v -> v.memberOf() != null)).isFalse();
    }

    private List<AccountRowView> twoAccountGroup() {
        return grouper(List.of(acct(1, "招行"), acct(2, "支付宝")),
                       List.of(new AccountGroupMapper.Member(9L, 1L),
                               new AccountGroupMapper.Member(9L, 2L)),
                       List.of(group(9, "日常周转")))
                .fold(FAMILY, null, null, List.of(
                        perf(1, "招行", "1000", "0.05", List.of()),
                        perf(2, "支付宝", "500", "0.03", List.of())));
    }
}
