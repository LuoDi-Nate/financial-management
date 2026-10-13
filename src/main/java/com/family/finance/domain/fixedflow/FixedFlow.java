package com.family.finance.domain.fixedflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.32 · 一条固定收支(PRD FR-1100)—— 每期都会有的那一笔的<b>设置</b>,不是流水。
 *
 * <p>本身不进任何金额计算;钱只在人点「确认」之后写进去的流水 / 划转 / 快照里
 * (见 {@code FixedFlowConfirmService})。种类 / 方式用字符串常量而不是枚举:
 * 和 v1.29 {@code instrument_kind}、v1.30 {@code nav_mode} 一样,认不出的值读成「不认识」而不是让页面 500。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixedFlow {

    public static final String INCOME = "INCOME";
    public static final String EXPENSE = "EXPENSE";
    public static final String REPAY = "REPAY";
    public static final String PREMIUM = "PREMIUM";

    public static final String REPAY_LOAN = "LOAN";
    public static final String REPAY_CARD = "CARD";

    public static final String FIXED = "FIXED";
    public static final String AUTO = "AUTO";

    /** ① 按上月和上上月的差值(还款 · 贷款月供) */
    public static final String LOAN_DIFF = "LOAN_DIFF";
    /** ② 月供固定,按年利率拆本金 / 利息(还款 · 贷款月供) */
    public static final String LOAN_RATE = "LOAN_RATE";
    /** ③ 全额还上期账单(还款 · 信用卡账单) */
    public static final String CARD_FULL = "CARD_FULL";
    /** ④ 按保单每期保费(交保费) */
    public static final String POLICY = "POLICY";
    /** ⑤ 近 3 期平均(收入 / 支出) */
    public static final String AVG3 = "AVG3";

    public static final String DISABLED_MANUAL = "MANUAL";
    public static final String DISABLED_ACCOUNT_ARCHIVED = "ACCOUNT_ARCHIVED";

    private Long id;
    private Long familyId;
    private String name;
    private String kind;
    private String repayKind;
    private String amountMode;
    private String autoMethod;
    private BigDecimal amount;
    private Long accountId;
    private Long targetAccountId;
    private String categoryCode;
    private Long expenseCategoryId;
    private String cycle;
    private String months;
    private Integer dayOfMonth;
    private Long ownerMemberId;
    private LocalDate startMonth;
    private LocalDate endMonth;
    private String note;
    private LocalDateTime disabledAt;
    private String disabledReason;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isActive() { return disabledAt == null; }

    public boolean isAuto() { return AUTO.equals(amountMode); }

    public boolean isRepayLoan() { return REPAY.equals(kind) && REPAY_LOAN.equals(repayKind); }

    public boolean isRepayCard() { return REPAY.equals(kind) && REPAY_CARD.equals(repayKind); }

    /** 写进去的是不是划转(信用卡账单 / 交保费) */
    public boolean writesTransfer() { return isRepayCard() || PREMIUM.equals(kind); }

    /** 种类的中文名(卡片上的小标) */
    public String kindLabel() {
        if (kind == null) return "";
        return switch (kind) {
            case INCOME -> "收入";
            case EXPENSE -> "支出";
            case REPAY -> "还款";
            case PREMIUM -> "交保费";
            default -> kind;
        };
    }

    /** 金额方式的一句话(管理页「金额」列) */
    public String methodLabel() {
        if (!isAuto()) return "固定金额";
        if (autoMethod == null) return "自动";
        return switch (autoMethod) {
            case LOAN_DIFF -> "自动 · 按上两月差值";
            case LOAN_RATE -> "月供固定 · 按年利率拆";
            case CARD_FULL -> "自动 · 全额还上期";
            case POLICY -> "自动 · 按保单保费";
            case AVG3 -> "自动 · 近 3 期平均";
            default -> "自动";
        };
    }

    public static String methodName(String method) {
        if (method == null) return "";
        return switch (method) {
            case LOAN_DIFF -> "按上月和上上月的差值";
            case LOAN_RATE -> "月供固定,按年利率拆本金 / 利息";
            case CARD_FULL -> "全额还上期账单";
            case POLICY -> "按保单每期保费";
            case AVG3 -> "近 3 期平均";
            default -> method;
        };
    }
}
