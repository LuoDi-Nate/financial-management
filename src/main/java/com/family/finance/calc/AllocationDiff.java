package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v0.4 FR-62a · 资产配置 4 类目 diff 纯函数。
 *
 * <p>把账户余额映射到 4 类目(现金 / 投资 / 房产 / 保险),与模板 % 对比。</p>
 *
 * <p>映射规则(优先用 product_category.liquidity_class · 次用 AccountType):</p>
 * <ul>
 *   <li>liquidity_class=LIQUID → 现金(CASH 类 + 货币基金)</li>
 *   <li>liquidity_class=ILLIQUID → 房产</li>
 *   <li>liquidity_class=SEMI_LIQUID → 投资(股 / 债 / 商品)</li>
 *   <li>liquidity_class=NA → 视 AccountType:LOAN 不计</li>
 *   <li>(保险 v0.4 占位 = 0 · v0.5 引入 INSURANCE 类型时承接)</li>
 *   <li><b>v1.27 · OTHER(车等)不进四桶</b>(PRD FR-873):原来兜底进「投资」,
 *       一辆车被当成投资,「投资超配」其实是车。现在单独算 {@link #otherAmount},页面另起一行说明。</li>
 * </ul>
 */
public final class AllocationDiff {
    private AllocationDiff() {}

    public enum Bucket { CASH, INVEST, PROPERTY, INSURANCE }

    /**
     * 从账户输入算出当前 4 类目占比(总资产分母 · 不含负债)。
     *
     * @param entries 每个有效账户一条 · balanceBase 应是绝对值正数(LOAN 由调用方传 -balance 形式或单独减)
     * @return 4 桶占比 %(和 ≤ 100 · 缺失桶为 0)
     */
    public static Map<Bucket, BigDecimal> computeCurrentPct(List<AllocationEntry> entries) {
        Map<Bucket, BigDecimal> amounts = new HashMap<>();
        for (Bucket b : Bucket.values()) amounts.put(b, BigDecimal.ZERO);

        BigDecimal totalAsset = BigDecimal.ZERO;
        for (AllocationEntry e : entries) {
            if (e.balanceBase() == null || e.balanceBase().signum() <= 0) continue;
            Bucket b = pickBucket(e);
            if (b == null) continue; // LOAN 跳过(负债不计入资产分母)
            amounts.merge(b, e.balanceBase(), BigDecimal::add);
            totalAsset = totalAsset.add(e.balanceBase());
        }
        if (totalAsset.signum() == 0) {
            return amounts.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, x -> BigDecimal.ZERO));
        }
        Map<Bucket, BigDecimal> pct = new HashMap<>();
        for (Map.Entry<Bucket, BigDecimal> e : amounts.entrySet()) {
            pct.put(e.getKey(), e.getValue()
                .multiply(new BigDecimal("100"))
                .divide(totalAsset, 2, RoundingMode.HALF_EVEN));
        }
        return pct;
    }

    /**
     * 算 diff = current% - target%(正 = 超配,负 = 欠配)。
     */
    public static Map<Bucket, BigDecimal> diff(Map<Bucket, BigDecimal> current, Map<Bucket, BigDecimal> target) {
        Map<Bucket, BigDecimal> out = new HashMap<>();
        for (Bucket b : Bucket.values()) {
            BigDecimal c = current.getOrDefault(b, BigDecimal.ZERO);
            BigDecimal t = target.getOrDefault(b, BigDecimal.ZERO);
            out.put(b, c.subtract(t).setScale(2, RoundingMode.HALF_EVEN));
        }
        return out;
    }

    /**
     * v1.27 · 每个桶的金额(本位币 · 只算正数)—— 判断「范围内有没有房产 / 保险」用(FR-870)。
     */
    public static Map<Bucket, BigDecimal> bucketAmounts(List<AllocationEntry> entries) {
        Map<Bucket, BigDecimal> amounts = new HashMap<>();
        for (Bucket b : Bucket.values()) amounts.put(b, BigDecimal.ZERO);
        for (AllocationEntry e : entries) {
            if (e.balanceBase() == null || e.balanceBase().signum() <= 0) continue;
            Bucket b = pickBucket(e);
            if (b != null) amounts.merge(b, e.balanceBase(), BigDecimal::add);
        }
        return amounts;
    }

    /** v1.27 · 「其他」类(车等)的合计 —— 不进四桶,页面单独一行「另有其他资产 N · 不参与四桶对照」 */
    public static BigDecimal otherAmount(List<AllocationEntry> entries) {
        BigDecimal sum = BigDecimal.ZERO;
        for (AllocationEntry e : entries) {
            if ("OTHER".equals(e.accountType()) && e.balanceBase() != null && e.balanceBase().signum() > 0) {
                sum = sum.add(e.balanceBase());
            }
        }
        return sum;
    }

    /**
     * v1.27 · 有效目标(PRD FR-870):范围内<b>完全没有</b>房产的,房产桶不参与对照;保险同理。
     * 其余桶的目标按原比例放大到合计 100。现金、投资两桶<b>始终参与</b> —— 没有投资的话,「投资低配」是真问题。
     *
     * <p>为什么不就拿原目标比:标普 4321 给房产 40%、保险 20%,一个只记金融资产的家庭一装上就被说
     * 「房产低配 40 个点 · 该补」—— 「买套房来平衡配置」不是建议(PRD §13 ⑧)。</p>
     *
     * @param target  锚的原目标(4 桶 %)
     * @param amounts 范围内各桶金额({@link #bucketAmounts})
     */
    public static EffectiveTarget effectiveTarget(Map<Bucket, BigDecimal> target, Map<Bucket, BigDecimal> amounts) {
        java.util.List<Bucket> dropped = new java.util.ArrayList<>();
        for (Bucket b : new Bucket[]{Bucket.PROPERTY, Bucket.INSURANCE}) {
            BigDecimal amt = amounts == null ? null : amounts.get(b);
            if (amt == null || amt.signum() <= 0) dropped.add(b);
        }
        Map<Bucket, BigDecimal> kept = new HashMap<>();
        BigDecimal keptSum = BigDecimal.ZERO;
        BigDecimal droppedSum = BigDecimal.ZERO;
        for (Bucket b : Bucket.values()) {
            BigDecimal t = target.getOrDefault(b, BigDecimal.ZERO);
            if (t == null) t = BigDecimal.ZERO;
            if (dropped.contains(b)) { droppedSum = droppedSum.add(t); continue; }
            kept.put(b, t);
            keptSum = keptSum.add(t);
        }
        // 拿掉的桶本来目标就是 0(雪球 / 永久组合的房产)→ 不用放大,只是不参与
        if (droppedSum.signum() == 0) {
            return new EffectiveTarget(kept, dropped, false, keptSum.signum() <= 0);
        }
        if (keptSum.signum() <= 0) {
            // 锚在家里有的这几类上全是 0(比如自定义全押房产)—— 没法放大,交给页面说清楚
            return new EffectiveTarget(kept, dropped, false, true);
        }
        Map<Bucket, BigDecimal> scaled = new HashMap<>();
        for (Map.Entry<Bucket, BigDecimal> e : kept.entrySet()) {
            scaled.put(e.getKey(), e.getValue().multiply(new BigDecimal("100"))
                    .divide(keptSum, 2, RoundingMode.HALF_EVEN));
        }
        return new EffectiveTarget(scaled, dropped, true, false);
    }

    /**
     * @param target     参与对照的桶 → 目标 %(已放大)
     * @param dropped    不参与的桶(范围内没有)
     * @param rescaled   是否做了放大(拿掉的桶原目标不是 0)
     * @param degenerate 参与的桶目标全是 0,没法对照
     */
    public record EffectiveTarget(Map<Bucket, BigDecimal> target, java.util.List<Bucket> dropped,
                                  boolean rescaled, boolean degenerate) {}

    /** 按映射规则把账户分到 4 桶 · LOAN / OTHER 返 null(不计入四桶分母) */
    private static Bucket pickBucket(AllocationEntry e) {
        String type = e.accountType();
        if ("LOAN".equals(type)) return null;
        // v1.27 FR-873 · 「其他」类(车等)不进任何桶 —— 必须先于 liquidity_class(OTHER 类目的流动性是 NA,
        //   但有人把车挂在别的类目上,那样会被分进投资 / 房产)
        if ("OTHER".equals(type)) return null;
        // v0.17 · 保险按类型硬定入 INSURANCE 桶 · 必须先于 liquidity_class 查询
        // (SAVINGS_INSURANCE 流动性 = SEMI_LIQUID,否则会被误分进 INVEST 桶,永远进不了保险桶)
        if ("INSURANCE".equals(type)) return Bucket.INSURANCE;

        String liq = e.liquidityClass();
        if (liq != null) {
            return switch (liq) {
                case "LIQUID" -> Bucket.CASH;
                case "ILLIQUID" -> Bucket.PROPERTY;
                case "SEMI_LIQUID" -> Bucket.INVEST;
                default -> typeFallback(type);
            };
        }
        return typeFallback(type);
    }

    private static Bucket typeFallback(String type) {
        return switch (type) {
            case "CASH" -> Bucket.CASH;
            case "STOCK", "WEALTH", "FUND" -> Bucket.INVEST;   // v1.30 FUND = 投资桶
            case "PROPERTY" -> Bucket.PROPERTY;
            case "INSURANCE" -> Bucket.INSURANCE; // v0.17 · 保险独立桶(pickBucket 已短路,此为兜底)
            default -> Bucket.INVEST; // CRYPTO / METAL 等投资类(OTHER 已在 pickBucket 短路)
        };
    }

    /**
     * 账户输入数据(给纯函数用 · 不依赖任何 Spring/MyBatis 实体)。
     */
    public record AllocationEntry(
        BigDecimal balanceBase,
        String accountType,
        String liquidityClass
    ) {}
}
