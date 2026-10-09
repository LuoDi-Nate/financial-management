package com.family.finance.calc.lens;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v1.1 · 通用 Pivot 引擎(纯函数 · 零 Spring 依赖)· tech-design v1.1 决策 3/5。
 *
 * <p>输入头寸 + 查询 spec(行/列维度 · 度量 · 筛选)→ 交叉结果(cells + 行/列小计 + 总计 +
 * 每格头寸索引供钻明细)。全家 &lt;200 头寸,内存 group-by 毫秒级。</p>
 *
 * <p><b>收益归因规则(诚实降级 · 不做假分摊)</b>:查询若涉及任何持仓级维度(行业/地域,
 * 含 filters)会把持仓账户拆开 —— 此时账户级度量 latestPnl / cumReturn 返回 null(UI 显「—」),
 * cumPnl 改用持仓级持有收益(市值−成本 · 现汇近似)求和;全账户级查询则按 accountId 去重
 * 精确聚合 factview 的账户级度量。绝不按市值比例把账户收益假分摊到持仓。</p>
 *
 * <p><b>币种不变式</b>(承 CurrencyInvariance 护栏):share / cumReturn 是比值 → 视图币种无关;
 * 金额类随 valueBase 按 fx 因子缩放。LOAN 不进头寸(负债不计入资产分母,由组装层排除)。</p>
 */
public final class PivotEngine {
    private PivotEngine() {}

    public static final String UNCLASSIFIED = "未分类";
    private static final String SEP = "";

    public record Cell(List<String> row, List<String> col, List<BigDecimal> values, List<Integer> pos) {}

    public record Result(
            List<String> rowDims,
            List<String> colDims,
            List<String> measures,
            List<List<String>> rowKeys,
            List<List<String>> colKeys,
            List<Cell> cells,
            List<List<BigDecimal>> rowTotals,
            List<List<BigDecimal>> colTotals,
            List<BigDecimal> grand,
            /** true = 查询含持仓级维度,收益按持有口径/不可归因(UI 提示) */
            boolean holdingLevelSplit,
            /**
             * v1.30 FR-972 · 持仓级维度下「累计收益额」按持有口径(市值 − 成本)求和,没有成本价的持仓
             * 求不出来、只能不计入 —— 这里数出有几只(同一只按穿透拆成几份只算一次),页面写明,
             * 免得合计看起来像全算了。不是持仓级查询恒为 0。
             */
            int holdingsWithoutCost
    ) {}

    public static Result pivot(List<Position> all, LensQuery q) {
        List<LensRegistry.Dimension> rowDims = q.rowsSafe().stream().map(LensRegistry::requireDim).toList();
        List<LensRegistry.Dimension> colDims = q.colsSafe().stream().map(LensRegistry::requireDim).toList();
        List<String> measures = q.measuresSafe();
        measures.forEach(LensRegistry::requireMeasure);

        boolean holdingSplit = rowDims.stream().anyMatch(LensRegistry.Dimension::holdingLevel)
                || colDims.stream().anyMatch(LensRegistry.Dimension::holdingLevel)
                || q.filtersSafe().keySet().stream().map(LensRegistry::requireDim)
                       .anyMatch(LensRegistry.Dimension::holdingLevel);

        // 1) 筛选(标签口径 · null=未分类)
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Position p = all.get(i);
            boolean ok = true;
            for (Map.Entry<String, List<String>> f : q.filtersSafe().entrySet()) {
                if (f.getValue() == null || f.getValue().isEmpty()) continue;
                String v = labelOf(LensRegistry.requireDim(f.getKey()), p);
                if (!f.getValue().contains(v)) { ok = false; break; }
            }
            if (ok) kept.add(i);
        }

        // 2) 分组
        Map<String, List<Integer>> cellsIdx = new LinkedHashMap<>();
        Map<String, List<String>> rowKeyOf = new LinkedHashMap<>();
        Map<String, List<String>> colKeyOf = new LinkedHashMap<>();
        Map<String, List<Integer>> rowIdx = new LinkedHashMap<>();
        Map<String, List<Integer>> colIdx = new LinkedHashMap<>();
        for (int i : kept) {
            Position p = all.get(i);
            List<String> rk = rowDims.stream().map(d -> labelOf(d, p)).toList();
            List<String> ck = colDims.stream().map(d -> labelOf(d, p)).toList();
            String rkId = String.join(SEP, rk);
            String ckId = String.join(SEP, ck);
            rowKeyOf.putIfAbsent(rkId, rk);
            colKeyOf.putIfAbsent(ckId, ck);
            rowIdx.computeIfAbsent(rkId, k -> new ArrayList<>()).add(i);
            colIdx.computeIfAbsent(ckId, k -> new ArrayList<>()).add(i);
            cellsIdx.computeIfAbsent(rkId + SEP + "×" + SEP + ckId, k -> new ArrayList<>()).add(i);
        }

        // 3) 总计(占比分母)
        BigDecimal grandValue = sumValue(all, kept);
        List<BigDecimal> grand = aggregate(all, kept, measures, holdingSplit, grandValue);

        // 4) 行/列键按金额降序 · 未分类沉底
        List<String> rowOrder = orderKeys(rowIdx, rowKeyOf, all);
        List<String> colOrder = orderKeys(colIdx, colKeyOf, all);

        List<List<BigDecimal>> rowTotals = new ArrayList<>();
        for (String rk : rowOrder) rowTotals.add(aggregate(all, rowIdx.get(rk), measures, holdingSplit, grandValue));
        List<List<BigDecimal>> colTotals = new ArrayList<>();
        for (String ck : colOrder) colTotals.add(aggregate(all, colIdx.get(ck), measures, holdingSplit, grandValue));

        List<Cell> cells = new ArrayList<>();
        for (String rk : rowOrder) {
            for (String ck : colOrder) {
                List<Integer> idx = cellsIdx.get(rk + SEP + "×" + SEP + ck);
                if (idx == null) continue;
                cells.add(new Cell(rowKeyOf.get(rk), colKeyOf.get(ck),
                        aggregate(all, idx, measures, holdingSplit, grandValue), idx));
            }
        }

        int withoutCost = 0;
        if (holdingSplit) {
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (int i : kept) {
                Position p = all.get(i);
                if (p.isHolding() && p.holdingCumPnl() == null && seen.add(p.holdingId())) withoutCost++;
            }
        }
        return new Result(
                rowDims.stream().map(LensRegistry.Dimension::key).toList(),
                colDims.stream().map(LensRegistry.Dimension::key).toList(),
                measures,
                rowOrder.stream().map(rowKeyOf::get).toList(),
                colOrder.stream().map(colKeyOf::get).toList(),
                cells, rowTotals, colTotals, grand, holdingSplit, withoutCost);
    }

    // ---------- 内部 ----------

    /** 维度取值(null / 空 → 「未分类」)· v1.27 公开给超级 Agent 的「排除」用,与筛选同一种判法 */
    public static String labelOf(LensRegistry.Dimension d, Position p) {
        String v = d.extract().apply(p);
        return (v == null || v.isBlank()) ? UNCLASSIFIED : v;
    }

    /** 键排序:按金额降序,「未分类」永远沉底 */
    private static List<String> orderKeys(Map<String, List<Integer>> idxByKey,
                                          Map<String, List<String>> keyOf,
                                          List<Position> all) {
        return idxByKey.keySet().stream()
                .sorted(Comparator
                        .comparing((String k) -> keyOf.get(k).contains(UNCLASSIFIED))
                        .thenComparing(k -> sumValue(all, idxByKey.get(k)), Comparator.reverseOrder()))
                .toList();
    }

    private static BigDecimal sumValue(List<Position> all, List<Integer> idx) {
        BigDecimal s = BigDecimal.ZERO;
        for (int i : idx) {
            BigDecimal v = all.get(i).valueBase();
            if (v != null) s = s.add(v);
        }
        return s.setScale(2, RoundingMode.HALF_EVEN);
    }

    /** 按度量 key 聚合一组头寸 · 收益归因规则见类注释 */
    private static List<BigDecimal> aggregate(List<Position> all, List<Integer> idx,
                                              List<String> measures, boolean holdingSplit,
                                              BigDecimal grandValue) {
        BigDecimal value = sumValue(all, idx);

        // 账户级度量按 accountId 去重(同账户所有头寸重复携带)
        Map<Long, Position> byAccount = new HashMap<>();
        for (int i : idx) byAccount.putIfAbsent(all.get(i).accountId(), all.get(i));

        List<BigDecimal> out = new ArrayList<>();
        for (String m : measures) {
            switch (m) {
                case "value" -> out.add(value);
                case "share" -> out.add(grandValue.signum() == 0 ? BigDecimal.ZERO
                        : value.multiply(new BigDecimal("100")).divide(grandValue, 2, RoundingMode.HALF_EVEN));
                case "netPrincipal" -> out.add(holdingSplit ? null : sumAcct(byAccount, Position::acctNetPrincipal));
                case "latestPnl" -> out.add(holdingSplit ? null : sumAcct(byAccount, Position::acctLatestPnl));
                case "latestReturn" -> out.add(holdingSplit ? null : ratio(byAccount, Position::acctLatestPnl, Position::acctOpenValue));
                case "cumPnl" -> out.add(holdingSplit ? sumHolding(all, idx) : sumAcct(byAccount, Position::acctCumPnl));
                case "cumReturn" -> out.add(holdingSplit ? null : ratio(byAccount, Position::acctCumPnl, Position::acctNetPrincipal));
                default -> out.add(null);
            }
        }
        return out;
    }

    private static BigDecimal sumAcct(Map<Long, Position> byAccount,
                                      java.util.function.Function<Position, BigDecimal> f) {
        BigDecimal s = null;
        for (Position p : byAccount.values()) {
            BigDecimal v = f.apply(p);
            if (v != null) s = (s == null ? v : s.add(v));
        }
        return s == null ? null : s.setScale(2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal sumHolding(List<Position> all, List<Integer> idx) {
        BigDecimal s = null;
        for (int i : idx) {
            BigDecimal v = all.get(i).holdingCumPnl();
            if (v != null) s = (s == null ? v : s.add(v));
        }
        return s == null ? null : s.setScale(2, RoundingMode.HALF_EVEN);
    }

    /** 收益率 % = Σ分子 / Σ分母(仅分母>0 的账户集合 · 分子分母同源;比率不可加,必须此处聚合)。
     *  累计收益率 = Σ累计收益 / Σ净投入 · 本期收益率 = Σ本期收益 / Σ期初市值 */
    private static BigDecimal ratio(Map<Long, Position> byAccount,
                                    java.util.function.Function<Position, BigDecimal> numFn,
                                    java.util.function.Function<Position, BigDecimal> denFn) {
        BigDecimal num = BigDecimal.ZERO, den = BigDecimal.ZERO;
        Set<Long> counted = new HashSet<>();
        for (Position p : byAccount.values()) {
            BigDecimal d = denFn.apply(p);
            if (d == null || d.signum() <= 0) continue;
            counted.add(p.accountId());
            den = den.add(d);
            BigDecimal n = numFn.apply(p);
            if (n != null) num = num.add(n);
        }
        if (counted.isEmpty() || den.signum() == 0) return null;
        return num.multiply(new BigDecimal("100")).divide(den, 2, RoundingMode.HALF_EVEN);
    }
}
