package com.family.finance.domain.goal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 家庭财务目标 · v0.3 FR-50。
 *
 * <p>三类目标(RETIREMENT / EDUCATION / EMERGENCY)共用此实体,
 * 类型特定参数存在 {@link #paramsJson},Service 层用 Jackson 反序列化到 GoalParams 子类。</p>
 *
 * <p>EMERGENCY 类型的 {@link #targetValue} / {@link #targetDate} 为 NULL,
 * 目标值由 params(months_target × baseline)derived 计算。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Goal {
    private Long id;
    private Long familyId;
    private GoalType goalType;
    private String name;
    private String description;   // v0.16.x 可选描述
    private BigDecimal targetValue;
    private LocalDate targetDate;
    private String paramsJson;
    // ── v0.16 通用追踪目标 ──
    private GoalMetric metric;         // 追踪指标(NULL 视为 AMOUNT_TOTAL)
    private GoalComparator comparator; // 达标方向(默认 GTE)
    private TimeMode timeMode;         // OPEN 长期 | DEADLINE 截止型
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime archivedAt;
    private String forecastSource;
    private BigDecimal forecastManualAmount;
    private String forecastManualCurrency;
    private String forecastWishMonth;
    private Boolean forecastWishOverride;

    /** 追踪指标(空安全,默认金额合计)。 */
    public GoalMetric metricOrDefault() { return metric == null ? GoalMetric.AMOUNT_TOTAL : metric; }
    /** 达标方向(空安全,默认 GTE)。 */
    public GoalComparator comparatorOrDefault() { return comparator == null ? GoalComparator.GTE : comparator; }
    /** 时间模式(空安全,默认 OPEN)。 */
    public TimeMode timeModeOrDefault() { return timeMode == null ? TimeMode.OPEN : timeMode; }
}
