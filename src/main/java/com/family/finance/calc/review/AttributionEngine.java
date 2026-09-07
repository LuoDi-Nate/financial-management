package com.family.finance.calc.review;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.2 · 月度归因引擎(纯函数 · 零 Spring · 照 PivotEngine 模式)· tech-design v1.2 §1。
 *
 * <p><b>恒等式严格闭合</b>:ΔNW = 人赚 + Σ账户钱赚 + 开账基线 + 未归因(差额如实显示,不吞不摊)。
 * 账户钱赚再作<b>两步法</b>拆分:标的 = 原币损益 × 期末汇率;汇率重估 = 本位币损益 − 标的
 * (两项和恒等 pnlBase → 无近似残差;本位币账户重估恒 0)。</p>
 */
public final class AttributionEngine {

    private AttributionEngine() {}

    /** 单账户输入(anchor 期实况,全部本位币口径除注明 Orig) */
    public record AcctInput(long accountId, String accountName, String currency,
                            BigDecimal pnlBase, BigDecimal pnlOrig,
                            BigDecimal endBase, BigDecimal endOrig,
                            BigDecimal prevEndBase, BigDecimal prevEndOrig,
                            Map<String, String> labels) {}   // dimKey → 标签值(null=未分类)

    /** 单账户归因输出 */
    public record AcctSlice(long accountId, String accountName,
                            BigDecimal pnlBase, BigDecimal underlying, BigDecimal fxEffect,
                            Map<String, String> labels) {}

    /** 汇总结果 */
    public record Result(BigDecimal delta, BigDecimal humanEarned, BigDecimal opening,
                         BigDecimal moneyEarnedTotal, BigDecimal fxTotal, BigDecimal unattributed,
                         List<AcctSlice> slices) {}

    /** 期趋势行:periodLabel → (分组值 → 该组本期钱赚合计) */
    public record TrendRow(String periodLabel, Map<String, BigDecimal> byGroup) {}

    public static Result attribute(List<AcctInput> rows, BigDecimal delta,
                                   BigDecimal humanEarned, BigDecimal opening) {
        BigDecimal money = BigDecimal.ZERO, fxTotal = BigDecimal.ZERO;
        List<AcctSlice> out = new ArrayList<>();
        for (AcctInput r : rows) {
            BigDecimal pnl = nz(r.pnlBase());
            if (pnl.signum() == 0 && nz(r.pnlOrig()).signum() == 0) continue;   // 零贡献不进列表
            BigDecimal fxEnd = fxEnd(r);
            BigDecimal underlying = nz(r.pnlOrig()).multiply(fxEnd, MathContext.DECIMAL64);
            BigDecimal fxEffect = pnl.subtract(underlying);                     // 闭合:underlying + fxEffect ≡ pnlBase
            money = money.add(pnl);
            fxTotal = fxTotal.add(fxEffect);
            out.add(new AcctSlice(r.accountId(), r.accountName(), pnl, underlying, fxEffect, r.labels()));
        }
        out.sort(Comparator.comparing((AcctSlice s) -> s.pnlBase().abs()).reversed());
        BigDecimal unattributed = nz(delta).subtract(nz(humanEarned)).subtract(nz(opening)).subtract(money);
        return new Result(nz(delta), nz(humanEarned), nz(opening), money, fxTotal, unattributed, out);
    }

    /** 按维度把账户贡献聚合(dimKey=null → 按账户本身);null 标签落「未分类」沉底 */
    public static LinkedHashMap<String, BigDecimal> groupBy(Result r, String dimKey) {
        LinkedHashMap<String, BigDecimal> acc = new LinkedHashMap<>();
        for (AcctSlice s : r.slices()) {
            // v1.20 · 账户不再是特例。它和别的维度一样有标签(已分组=组名 / 未分组=账户名),
            // 由 AccountGroupingResolver 统一给。dimKey==null 的老调用回落到 accountName,
            // 保证没有传标签的调用方(测试/历史代码)行为不变。
            String labeled = dimKey == null ? null : s.labels().get(dimKey);
            String key = labeled != null ? labeled
                    : (dimKey == null || "acct".equals(dimKey) ? s.accountName() : "未分类");
            acc.merge(key, s.pnlBase(), BigDecimal::add);
        }
        // 按绝对值排序 · 未分类沉底
        LinkedHashMap<String, BigDecimal> sorted = new LinkedHashMap<>();
        acc.entrySet().stream()
                .sorted((a, b) -> {
                    boolean ua = "未分类".equals(a.getKey()), ub = "未分类".equals(b.getKey());
                    if (ua != ub) return ua ? 1 : -1;
                    return b.getValue().abs().compareTo(a.getValue().abs());
                })
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /**
     * 期末汇率:endBase/endOrig;期末清仓(endOrig=0)回退期初口径;再退 1(本币或无信息)。
     * 用途仅限两步法拆分,不做任何对外汇率展示。
     */
    static BigDecimal fxEnd(AcctInput r) {
        if (nz(r.endOrig()).signum() != 0) return nz(r.endBase()).divide(r.endOrig(), MathContext.DECIMAL64);
        if (nz(r.prevEndOrig()).signum() != 0) return nz(r.prevEndBase()).divide(r.prevEndOrig(), MathContext.DECIMAL64);
        return BigDecimal.ONE;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
