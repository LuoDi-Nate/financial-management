package com.family.finance.service.explain;

import com.family.finance.domain.account.AccountType;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.service.checkup.FamilyDiagnose;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.5.3 · 计算指标透明化 · tooltip 真实数值格式化守护。
 *
 * <p>锁死:① 货币/百分比/月数格式化口径 ② 各页 calc 串含真实数值且与公式自洽
 * ③ 钱赚分解满足 (期末 − 起始) − 净流入 = PnL 恒等式 ④ 缺数据时降级文案不崩。</p>
 */
class MetricExplainServiceTest {

    private final MetricExplainService svc = new MetricExplainService();

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    // ---------------- 通用格式化 ----------------

    @Test
    void money_formats_with_symbol_thousands_and_handles_null() {
        assertThat(svc.money("CNY", bd("1234.56"))).isEqualTo("¥1,235");
        assertThat(svc.money("USD", bd("1000"))).isEqualTo("$1,000");
        assertThat(svc.money("HKD", bd("0"))).isEqualTo("HK$0");
        assertThat(svc.money("CNY", null)).isEqualTo("—");
    }

    @Test
    void signedMoney_prefixes_sign_and_uses_minus_glyph() {
        assertThat(svc.signedMoney("CNY", bd("3000"))).isEqualTo("+¥3,000");
        assertThat(svc.signedMoney("CNY", bd("-3000"))).isEqualTo("−¥3,000");
        assertThat(svc.signedMoney("CNY", bd("0"))).isEqualTo("¥0");
        assertThat(svc.signedMoney("CNY", null)).isEqualTo("—");
    }

    @Test
    void pct_and_months_formatting() {
        assertThat(svc.pct2Signed(bd("0.0123"))).isEqualTo("+1.23%");
        assertThat(svc.pct2Signed(bd("-0.05"))).isEqualTo("-5.00%");
        assertThat(svc.pct2Signed(null)).isEqualTo("—");
        assertThat(svc.pctUnits(bd("5.4"), 1)).isEqualTo("5.4%");
        assertThat(svc.pctFromRatio(bd("0.48"), 1)).isEqualTo("48.0%");
        assertThat(svc.months(bd("3.0"))).isEqualTo("3.0");
        assertThat(svc.months(null)).isEqualTo("—");
    }

    // ---------------- dashboard ----------------

    private KpiSnapshot kpi() {
        // netWorth, assets, liab, emergencyMonths, debtRatio, delta, deltaPct,
        // monthlyPnlAmount, monthlyInvestReturnPct, annualized, ytd,
        // liquidAssets, avgExpense, prevNetWorth, lastNetInflow
        return new KpiSnapshot(
                bd("70000"), bd("100000"), bd("30000"), bd("3.0"), bd("0.3"), bd("5000"), bd("0.077"),
                bd("2000"), bd("0.0307"), bd("0.08"), bd("12000"),
                bd("60000"), bd("20000"), bd("65000"), bd("3000"));
    }

    @Test
    void dashboard_breakdowns_carry_real_numbers() {
        List<AllocationSlice> alloc = List.of(
                new AllocationSlice("CASH", "现金\n(CASH)", bd("60000"), bd("0.6")),
                new AllocationSlice("STOCK", "股票\n(STOCK)", bd("40000"), bd("0.4")));
        List<AccountPerformance> rows = List.of(
                AccountPerformance.basic(1L, "招行储蓄", AccountType.CASH, "CNY", bd("60000"), null, List.of()),
                AccountPerformance.basic(2L, "房贷", AccountType.LOAN, "CNY", bd("-30000"), null, List.of()));

        Map<String, String> m = svc.dashboard(kpi(), alloc, rows, "CNY");

        assertThat(m.get("netWorth")).isEqualTo("总资产 ¥100,000 − 总负债 ¥30,000 = ¥70,000");
        assertThat(m.get("totalAssets")).contains("现金 ¥60,000", "股票 ¥40,000", "合计 = ¥100,000");
        assertThat(m.get("totalLiabilities")).contains("房贷 ¥30,000", "合计 = ¥30,000");
        assertThat(m.get("emergency")).isEqualTo("流动资产 ¥60,000 ÷ 月均支出 ¥20,000 = 3.0 个月");
        assertThat(m.get("monthlyPnl"))
                .contains("期末净资产 ¥70,000", "期初 ¥65,000", "本月净流入 +¥3,000", "= +3.07%");
    }

    @Test
    void emergency_degrades_when_no_expense() {
        KpiSnapshot k = new KpiSnapshot(
                bd("70000"), bd("100000"), bd("30000"), null, null, null, null,
                null, null, null, null,
                bd("60000"), BigDecimal.ZERO, null, null);
        Map<String, String> m = svc.dashboard(k, List.of(), List.of(), "CNY");
        assertThat(m.get("emergency")).contains("暂无法计算");
        assertThat(m.get("monthlyPnl")).contains("暂无法计算");
    }

    // ---------------- checkup ----------------

    @Test
    void checkup_breakdowns_use_base_currency() {
        FamilyDiagnose d = new FamilyDiagnose(
                kpi(),
                List.of(new AllocationSlice("CASH", "现金\n(CASH)", bd("100000"), bd("1.0"))),
                List.of(),
                bd("60000"), bd("3.0"), bd("0.045"), bd("0.06"), bd("12000"), 5, 0);

        Map<String, String> m = svc.checkup(d, "CNY");

        assertThat(m.get("netWorth")).isEqualTo("总资产 ¥100,000 − 总负债 ¥30,000 = ¥70,000");
        // 紧急储备三项取 kpi 同源(liquidAssets ¥60,000 / avgExpense ¥20,000 / months 3.0)
        assertThat(m.get("emergency")).isEqualTo("流动资产 ¥60,000 ÷ 月均支出 ¥20,000 = 3.0 个月");
        assertThat(m.get("liquidAssets")).contains("¥60,000");
        assertThat(m.get("familyXirr")).contains("+4.50%");
        assertThat(m.get("familyTwr")).contains("+6.00%");
        assertThat(m.get("ytdPnl")).contains("+¥12,000");
    }

    // ---------------- reports + 恒等式 ----------------

    @Test
    void reports_pnl_satisfies_identity_and_savings_uses_base() {
        // 期末 80000 − 起始 50000 − 净流入 18000 = PnL 12000
        var in = new MetricExplainService.ReportsMetricInputs(
                "CNY", "CNY",
                bd("50000"), bd("80000"), "2025-06", "2026-05",
                12, 11,
                bd("0.072"), bd("0.055"),
                bd("18000"), bd("12000"),
                bd("84477"),                       // v1.6.29 · 累计开账基线(单列进 tooltip)
                bd("5.4"), 6, bd("100000"),
                true, 8, 12,
                bd("280000"), bd("144000"), bd("35000"), bd("18000"),
                bd("36000"), bd("17000"), bd("0.528"), bd("17500"), java.time.LocalDate.of(2026, 5, 1));

        Map<String, String> m = svc.reports(in);

        assertThat(m.get("pnl"))
                .isEqualTo("(期末净资产 ¥80,000 − 起始净资产 ¥50,000) − 净流入 +¥18,000 = +¥12,000");
        assertThat(m.get("netInflow")).contains("+¥18,000", "共 11 期计入");
        // v1.6.29 · 端点必须是**真实净资产**(此前取的是剔除开账基线的趋势,首点恒为 0);
        //   开账基线单列;并标明是年化还是累计口径。
        assertThat(m.get("familyXirr"))
                .contains("−¥50,000", "+¥80,000", "= +7.20%", "含中途纳入的存量账户本金 ¥84,477", "(年化)")
                .contains("与「人赚」同口径");
        assertThat(m.get("benchmark")).contains("6 个账户", "5.4%");
        assertThat(m.get("avgIncome")).isEqualTo("近 12 月有填 8 个月 · 收入合计 ¥280,000 ÷ 8 = ¥35,000");
        assertThat(m.get("savingsRate")).contains("2026-05 账期", "收入 ¥36,000", "支出 ¥17,000", "= 52.8%");
        // v1.8 · 这条 tooltip 额外写明支出口径 + 「与收入侧方向相反」——
        //   那条反直觉的不一致必须同时出现在四处(类注释 / 方法注释 / 本 tooltip / how-to-record),
        //   少写一处下一个人就会靠猜,所以断言从 isEqualTo 收紧成「包含四处该有的话」。
        assertThat(m.get("filledMonths"))
                .contains("近 12 期中实际填过收入/支出的有 8 期")
                .contains("逐笔模式取逐笔之和", "总额模式取每人手填的总数", "永不相加")
                .contains("与收入侧方向相反");
    }

    @Test
    void reports_savings_keys_absent_when_unavailable() {
        var in = new MetricExplainService.ReportsMetricInputs(
                "CNY", "CNY",
                bd("50000"), bd("80000"), "2025-06", "2026-05",
                12, 11,
                bd("0.072"), bd("0.055"),
                bd("18000"), bd("12000"),
                BigDecimal.ZERO,                   // v1.6.29 · 无开账基线 → tooltip 不提这一项
                bd("5.4"), 6, bd("100000"),
                false, 0, 12,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                null, null, null, null, null);
        Map<String, String> m = svc.reports(in);
        assertThat(m).containsKeys("familyXirr", "benchmark", "familyTwr", "netInflow", "pnl");
        assertThat(m).doesNotContainKeys("avgIncome", "avgExpense", "savingsRate", "savingsMedian", "filledMonths");
    }
}
