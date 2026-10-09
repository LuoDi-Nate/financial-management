package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.member.Member;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * v0.2 FR-30 · 账户详情页 ViewModel · 账本视角
 *
 * 数据来自 cashFlowMapper.findAllByFamily + transferMapper.findAllByFamily +
 * snapshotMapper.findAllByFamily 的 service 层 filter。
 */
public record AccountDetail(
        Account account,
        Member owner,
        ProductCategory category,
        BigDecimal currentBalance,
        BigDecimal previousBalance,
        BigDecimal monthDelta,
        BigDecimal cumulativeNetInflow,
        BigDecimal avgMonthlyIncome,
        BigDecimal avgMonthlyExpense,
        int entryCount,
        List<TrendPoint> trend,
        List<MonthGroup> months
) {
    public String monthDeltaPctLabel() {
        if (previousBalance == null || previousBalance.signum() == 0 || monthDelta == null) return "—";
        BigDecimal pct = monthDelta.multiply(new BigDecimal("100"))
                .divide(previousBalance.abs(), 1, RoundingMode.HALF_EVEN);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    public record TrendPoint(LocalDate month, BigDecimal endBalance) {}

    /**
     * 一个月的所有流水(SNAPSHOT + cash_flow + transfer_in + transfer_out 合并)
     */
    public record MonthGroup(
            LocalDate month,
            int entryCount,
            BigDecimal netDelta,
            String netDeltaLabel,
            boolean periodOpen,
            List<Entry> entries
    ) {}

    public record Entry(
            Kind kind,
            LocalDateTime occurredAt,
            BigDecimal amount,
            String amountSignedLabel,
            String label,
            String note,
            /** cash_flow.id 或 transfer.id;SNAPSHOT 为 null */
            Long sourceId,
            /** 是否可被删除(OPEN 周期 + 非 SNAPSHOT) */
            boolean deletable,
            /**
             * v1.18 · 这一笔是<b>谁写进来的</b>(手动填报 / 自动拉价 / 券商同步 / 截图导入…)。
             *
             * <p>和 {@link Kind} 是两个维度:Kind 说性质,source 说来路。
             * v1.18 之前的历史数据是 {@code UNKNOWN}(当时没记,不等于手动)。</p>
             */
            com.family.finance.domain.ledger.LedgerSource source
    ) {}

    public enum Kind {
        SNAPSHOT, INCOME, EXPENSE, TRANSFER_IN, TRANSFER_OUT,
        /** v0.4.1 FR-52f · 股票账户估值变动事件 */
        VALUATION,
        /** v1.30 FR-968 · 持仓数量变动(只说「份额怎么变的」,不带钱:amount 恒为 null,不进月净额) */
        SHARES,
        /** v1.30 FR-974 · 补录本金(以前就有、这期才补录的钱 · 算本金,不算收入也不算收益) */
        PRINCIPAL
    }
}
