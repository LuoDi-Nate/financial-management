package com.family.finance.domain.macro;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 宏观基准 · v0.5 FR-70 · 年度 CPI / M2。
 *
 * <p>财富水位的两条基准线数据源:</p>
 * <ul>
 *   <li>{@code cpiHeadline} 全年整体 CPI 涨幅 %(含食品)→ CPI 保命线</li>
 *   <li>{@code m2Growth} 年末 M2 同比增速 % → M2 地位线</li>
 * </ul>
 *
 * <p>全国统一数据(非家庭私有)· 主键 = year。1990-2025 由 V27 seed,
 * {@code MacroFetchJob} 每年拉最新完整年份覆盖。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MacroBenchmark {
    private Integer year;
    private BigDecimal cpiHeadline;
    private BigDecimal m2Growth;
    private String source;
    private LocalDateTime fetchedAt;
    private String sourceUrl;
    private LocalDateTime verifiedAt;
}
