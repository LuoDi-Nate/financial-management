package com.family.finance.domain.stock;

import java.util.Locale;

/**
 * v1.30 · 「净值行」的两种(issue #25 · tech-design/v1.30.md 选型一 / 选型六)。
 *
 * <p>净值行在库里仍是 {@link ValuationMode#MANUAL} 行 —— 估值就是 {@code manual_value × shares},
 * 一行没改。这个枚举只说明<b>单价由谁写、怎么写</b>:</p>
 * <ul>
 *   <li>{@link #FUND} 场外基金:单价 = 单位净值,系统每次同步写最新一条;{@code nav_date} = 净值日期。</li>
 *   <li>{@link #MMF} 货币基金:单价恒 1、份额 = 金额;系统每次同步按逐日万份收益把收益结转成份额;
 *       {@code nav_date} = <b>已结转到哪一天</b>。</li>
 * </ul>
 *
 * <p>库里存字符串、实体里是 {@code String}(同 v1.29 的 {@code instrument_kind}):老 jar 根本不读这一列,
 * 新代码读到不认识的值经 {@link #of} 落成 null = 普通手填行 —— 回滚、前滚都不炸。</p>
 */
public enum NavMode {
    FUND("场外基金"),
    MMF("货币基金");

    private final String label;

    NavMode(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** 库里的字符串 → 枚举;空 / 不认识返回 null(= 普通手填行) */
    public static NavMode of(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
