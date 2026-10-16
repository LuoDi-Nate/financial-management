package com.family.finance.domain.flow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashFlow {
    private Long id;
    private Long periodId;
    private Long accountId;
    private CashFlowKind kind;
    private String categoryCode;
    private BigDecimal amount;
    private LocalDate occurredAt;
    private String note;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private boolean adjustment;   // v0.8 · is_adjustment · 账户内部现金调整(剔出投资损益)
    // v0.12 · 股票「+股数」收入的冲回信息 · 删除时按 refShares 冲回持仓股数;其它流水为 null
    private Long refHoldingId;
    private BigDecimal refShares;
    /** v1.18 · 这一笔是谁写进来的({@link com.family.finance.domain.ledger.LedgerSource});历史数据为 UNKNOWN = 当时没记 */
    private String sourceTag;

    /**
     * v1.21 · 消费分类(家庭自定义树)。
     *
     * <p><b>只在 {@code categoryCode == "consumption"} 时有意义。</b>
     * 「性质」(还贷 / 利息支出 / 转账给亲属)与「钱花在哪」(餐饮 / 交通)是<b>两个维度</b>:
     * 前者决定储蓄率与负债口径(AGENTS.md 联动链 L1),后者只回答消费构成。
     * 合并两者会静默把储蓄率算错 —— 那类错误不抛异常,只是数字慢慢不对。</p>
     *
     * <p>{@code null} = 未分类(v1.21 之前的历史流水,以及用户没选的)。</p>
     */
    private Long expenseCategoryId;

    /** v1.21 · 来自哪个导入批次;null = 手工录入。整批撤销靠它。 */
    private Long importBatchId;

    /** v1.21 · 渠道交易号,重复导入时用来跳过。故意不做 DB 唯一约束(见 V59 文件头)。 */
    private String extTxNo;

    /**
     * v1.21 · 这笔要不要参与<b>该账户</b>的余额解释。默认 true(老行为)。
     *
     * <p>{@code false} = 「只记家里花了多少、花在哪」:进家庭消费(口径 A)与支出构成,
     * 但<b>不动余额、不参与轧差、不算该账户的资金流出</b>(口径 B)。</p>
     *
     * <p>为什么要这个开关:{@code applyDeltaToBalance} 直接改写 {@code period_snapshot.end_balance}
     * —— 那是用户自己填的期末余额。已经核对完余额再导账单的人会被<b>扣第二遍</b>。</p>
     *
     * <p><b>两条口径必须一起走,不能只关一半</b>:余额没动却报一笔资金流出,
     * NAV 会以为「钱是被取走的不是亏掉的」,把账户收益率算高 —— 而这不报错。</p>
     */
    @lombok.Builder.Default
    private boolean affectsBalance = true;

    /**
     * v1.24 FR-613 · 这笔是不是「一次性」支出。
     *
     * <p><b>纯分析期标记</b>:它唯一的作用是让这笔钱不进「常态月均」。
     * 余额、轧差、净资产、XIRR —— 一条都不碰。
     * {@code affects_balance} 有过「一个标记要同时退出三条链」的教训,
     * 这个标记刻意只有一条链。护栏 {@code v1240-ONEOFF-NOT-BALANCE} 守着它
     * 不出现在任何余额路径上。</p>
     */
    private boolean oneOff;

    /**
     * v1.34.2 · 往「之后已有人确认过余额」的账期里补记时用户选的方式:NULL 普通 / KEEP 余额不动 / SHIFT 之后各期一起挪。
     * 删这一笔时按同一种方式撤回。见 {@code EntryService.creditAccountBalance}。
     */
    private String backfillMode;
}
