package com.family.finance.domain.fixedflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.32 · 某条固定收支在某一期的结果(PRD FR-1112 ~ 1114 / FR-1151)。
 *
 * <p>一条固定收支一期最多一条(唯一键 {@code (fixed_flow_id, period_id)} —— 双击、两个人同时点都挡在库这一层)。
 * 记下这次写了哪几笔的 id 与贷款余额写前写后,撤销时按 id 精确删、按值还原,不靠猜。
 * <b>不进任何金额计算。</b></p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixedFlowOccurrence {

    public static final String CONFIRMED = "CONFIRMED";
    public static final String SKIPPED = "SKIPPED";
    public static final String LINKED = "LINKED";

    private Long id;
    private Long familyId;
    private Long fixedFlowId;
    private Long periodId;
    private LocalDate dueDate;
    private String status;
    private BigDecimal amount;
    private BigDecimal principal;
    private BigDecimal interest;
    private Long cashFlowId;
    private Long interestCashFlowId;
    private Long transferId;
    private BigDecimal loanBalanceBefore;
    private BigDecimal loanBalanceAfter;
    private String formula;
    private Long memberId;
    private LocalDateTime createdAt;
}
