package com.family.finance.factview;

import java.util.List;

/**
 * 月末余额序列 → SVG polyline 的归一化。
 *
 * <p>v1.20 从 {@code FactViewServiceImpl} 的两个 private static 里提出来 —— 因为账户组的组行
 * 需要在<b>合并后的时序</b>上重算同样的东西。留在原处就得抄一份,而<b>抄一份归一化函数的下场是它会漂</b>:
 * 两条曲线用不同的 viewBox 或不同的 min/max 基准,在同一张表里并排显示,视觉上完全不可比,
 * 而且没有任何测试会失败。</p>
 */
public final class Sparkline {

    private Sparkline() {}

    /** 把月末余额序列归一化成 viewBox 0 0 80 22 的 polyline points;&lt;2 点返回 null(模板降级)。 */
    public static String points(List<TrendPoint> spark) {
        if (spark == null || spark.size() < 2) return null;
        double min = spark.stream().mapToDouble(p -> p.value().doubleValue()).min().orElse(0);
        double max = spark.stream().mapToDouble(p -> p.value().doubleValue()).max().orElse(0);
        double range = max - min;
        int n = spark.size();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            long x = Math.round(80.0 * i / (n - 1));
            double norm = range == 0 ? 0.5 : (spark.get(i).value().doubleValue() - min) / range;
            long y = Math.round(20.0 - norm * 18.0);   // 值越高 y 越小(视觉向上)
            if (i > 0) sb.append(' ');
            sb.append(x).append(',').append(y);
        }
        return sb.toString();
    }

    public static String trend(List<TrendPoint> spark) {
        if (spark == null || spark.size() < 2) return "none";
        int c = spark.get(spark.size() - 1).value().compareTo(spark.get(0).value());
        return c > 0 ? "up" : c < 0 ? "down" : "flat";
    }
}
