package com.family.finance.domain.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.30 · 持仓数量变动(PRD FR-968 · tech-design/v1.30.md 选型九)。
 *
 * <p><b>只给人看,不参与任何金额计算</b>:钱的变化只记在 {@code stock_valuation_event} 里。
 * {@link #valueDelta} 是「约合多少钱」的说明,任何汇总都不许读它(护栏 {@code v130-SHARE-EVENT-NO-MONEY})。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldingShareEvent {
    private Long id;
    private Long familyId;
    private Long accountId;
    private Long holdingId;
    private Long periodId;
    /** {@link ShareEventReason} 的 name() */
    private String reason;
    private BigDecimal sharesBefore;
    private BigDecimal sharesAfter;
    private BigDecimal sharesDelta;
    /** 当时的单价(账户币种):基金 = 单位净值,货币基金 = 1 */
    private BigDecimal unitValue;
    /** 约合金额 —— 只写给人看 */
    private BigDecimal valueDelta;
    private LocalDate dateFrom;
    private LocalDate dateTo;
    private Long memberId;
    private LocalDateTime createdAt;

    /** 查询时带出来的持仓名(时间线展示用,不落库) */
    private String holdingName;

    public ShareEventReason reasonEnum() {
        return ShareEventReason.of(reason);
    }
}
