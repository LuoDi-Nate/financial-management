package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * v0.4 FR-61b/c · 基准对照计算 · 纯函数。
 *
 * <p>给账户 XIRR 加上"vs 基准"对照:</p>
 * <ul>
 *   <li>账户级:account.xirr - product_category.benchmark_pct → diff</li>
 *   <li>家庭级:按账户余额加权平均 product_category.benchmark_pct → familyBenchmark</li>
 * </ul>
 *
 * <p>fallback:account 没 product_category_code 的,按 AccountType 兜底
 * (STOCK→6% / WEALTH→3% / FUND→6% / CASH→0.5% / PROPERTY→3% / LOAN→0% / OTHER→0%)</p>
 */
public final class BenchmarkAggregator {
    private BenchmarkAggregator() {}

    /** AccountType → 兜底年化基准 %(account 没设产品类目时) */
    private static final Map<String, BigDecimal> TYPE_FALLBACK = Map.of(
        "CASH",     new BigDecimal("0.50"),
        "STOCK",    new BigDecimal("6.00"),
        "WEALTH",   new BigDecimal("3.00"),
        "FUND",     new BigDecimal("6.00"),   // v1.30 · 同默认类目 MIXED_FUND 的基准
        "PROPERTY", new BigDecimal("3.00"),
        "LOAN",     BigDecimal.ZERO,
        "OTHER",    BigDecimal.ZERO
    );

    /** 跑赢/输判定阈值(±2%) */
    public static final BigDecimal BEAT_THRESHOLD = new BigDecimal("2.0");

    public enum BeatStatus { BEAT, FLAT, MISS, NA }

    /**
     * 单账户 diff · 用 product_category.benchmark_pct 或类型兜底。
     *
     * @param accountXirr 小数形式(0.072 = 7.2%) · null 时返回 NA
     * @param pcBenchmarkPct 产品类目年化基准 %(8.00 = 8%) · 可空
     * @param accountTypeName CASH/STOCK/WEALTH/PROPERTY/LOAN/OTHER
     * @return 基准 %(年化形式 · 6.00 = 6%)· 永不返回 null
     */
    public static BigDecimal benchmarkForAccount(BigDecimal accountXirr, BigDecimal pcBenchmarkPct, String accountTypeName) {
        if (pcBenchmarkPct != null) return pcBenchmarkPct.setScale(2, RoundingMode.HALF_EVEN);
        return TYPE_FALLBACK.getOrDefault(accountTypeName, BigDecimal.ZERO);
    }

    /** 账户级 diff = xirr% - benchmark% · 都是百分点形式 */
    public static BigDecimal diffPercentPoints(BigDecimal accountXirr, BigDecimal benchmarkPct) {
        if (accountXirr == null) return null;
        BigDecimal xirrPct = accountXirr.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_EVEN);
        return xirrPct.subtract(benchmarkPct).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** 跑赢/输判定 · diff > +2 = BEAT / diff < -2 = MISS / 之间 = FLAT */
    public static BeatStatus beatStatus(BigDecimal diffPct) {
        if (diffPct == null) return BeatStatus.NA;
        if (diffPct.compareTo(BEAT_THRESHOLD) > 0) return BeatStatus.BEAT;
        if (diffPct.compareTo(BEAT_THRESHOLD.negate()) < 0) return BeatStatus.MISS;
        return BeatStatus.FLAT;
    }

    // ── v0.10.5 · 同窗口口径(修「短账户:累计实际 vs 年化预期」口径错)──────────────────
    // 实际值是「持有 N 月的累计回报」,不外推年化;故预期也按同窗口缩放,like-for-like 比。

    /** 预期年化缩放到 N 月窗口的累计 %:(1+annual/100)^(months/12)−1,×100。annual 为 null 或 months≤0 → null。 */
    public static BigDecimal expectedOverWindowPct(BigDecimal annualPct, int months) {
        if (annualPct == null || months <= 0) return null;
        double v = (Math.pow(1.0 + annualPct.doubleValue() / 100.0, months / 12.0) - 1.0) * 100.0;
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** 同窗口 diff(pp)= 实际累计% − 预期(年化缩放到 N 月)%。任一缺失 → null。 */
    public static BigDecimal windowDiffPercentPoints(BigDecimal actualCumPct, BigDecimal annualPct, int months) {
        BigDecimal exp = expectedOverWindowPct(annualPct, months);
        if (actualCumPct == null || exp == null) return null;
        return actualCumPct.subtract(exp).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** 跑赢/输判定 · 阈值同窗口缩放(年化 ±2% 折到 N 月);月数无效 → NA。 */
    public static BeatStatus beatStatusWindow(BigDecimal windowDiffPct, int months) {
        if (windowDiffPct == null) return BeatStatus.NA;
        BigDecimal thr = expectedOverWindowPct(BEAT_THRESHOLD, months);
        if (thr == null) return BeatStatus.NA;
        if (windowDiffPct.compareTo(thr) > 0) return BeatStatus.BEAT;
        if (windowDiffPct.compareTo(thr.negate()) < 0) return BeatStatus.MISS;
        return BeatStatus.FLAT;
    }

    // ── v0.11.4 · 与「卡片显示的那个 xirr」同源的 diff(修 v0.10.5 的 cumPnl/净投入 爆值 + 脱节)──
    // 旧法用 cumPnl/净投入 当实际:净投入极小的账户会爆成 +19497pp,且与卡片头条的 XIRR 是两个数。
    // 新法直接把「显示的 xirr」当实际 —— 与 XirrCalculator.annualizedOrCumulative 同口径:
    //   满 12 期 xirr 是年化 → 减年化基准;不足 12 期 xirr 是累计回报 → 基准缩放到同窗口。like-for-like 且和头条一致。

    /**
     * diff(pp)= 显示的 xirr% − 同基基准%。
     * @param displayedXirr 卡片/列显示的那个 xirr(小数 · 0.083=8.3%)· null → null
     * @param annualBenchmarkPct 年化基准 %(4.61 = 4.61%)· null → null
     * @param months 期数;≥12 用年化基准,<12 缩放到窗口;≤0 → null
     */
    public static BigDecimal displayedDiffPercentPoints(BigDecimal displayedXirr, BigDecimal annualBenchmarkPct, int months) {
        if (displayedXirr == null || annualBenchmarkPct == null || months <= 0) return null;
        BigDecimal actualPct = displayedXirr.multiply(new BigDecimal("100"));
        BigDecimal expected = months >= 12 ? annualBenchmarkPct : expectedOverWindowPct(annualBenchmarkPct, months);
        if (expected == null) return null;
        return actualPct.subtract(expected).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** 跑赢/输判定 · 阈值同基:满 12 期 ±2pp(年化),不足 12 期 ±2% 缩放到窗口;diff/月数无效 → NA。 */
    public static BeatStatus beatStatusDisplayed(BigDecimal diffPct, int months) {
        if (diffPct == null || months <= 0) return BeatStatus.NA;
        BigDecimal thr = months >= 12 ? BEAT_THRESHOLD : expectedOverWindowPct(BEAT_THRESHOLD, months);
        if (thr == null) return BeatStatus.NA;
        if (diffPct.compareTo(thr) > 0) return BeatStatus.BEAT;
        if (diffPct.compareTo(thr.negate()) < 0) return BeatStatus.MISS;
        return BeatStatus.FLAT;
    }

    /**
     * 家庭加权基准 = Σ(account.balance × benchmark) / Σ(account.balance)
     *
     * @param entries 每个账户一条 (balance, benchmark_pct) · balance 应已折算到本位币
     * @return 加权年化 %(永不 null · 空列表返 0)
     */
    public static BigDecimal weightedFamilyBenchmark(List<BenchmarkInput> entries) {
        if (entries == null || entries.isEmpty()) return BigDecimal.ZERO;
        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;
        for (BenchmarkInput e : entries) {
            if (e.balanceBase() == null || e.balanceBase().signum() <= 0) continue;
            if (e.benchmarkPct() == null) continue;
            numerator = numerator.add(e.balanceBase().multiply(e.benchmarkPct()));
            denominator = denominator.add(e.balanceBase());
        }
        if (denominator.signum() == 0) return BigDecimal.ZERO;
        return numerator.divide(denominator, 2, RoundingMode.HALF_EVEN);
    }

    public record BenchmarkInput(BigDecimal balanceBase, BigDecimal benchmarkPct) {}
}
