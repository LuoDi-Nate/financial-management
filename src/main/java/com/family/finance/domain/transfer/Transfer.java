package com.family.finance.domain.transfer;

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
public class Transfer {
    private Long id;
    private Long periodId;
    private Long fromAccountId;
    private Long toAccountId;
    private BigDecimal amount;
    private BigDecimal toAmount;   // v0.8 · 跨币种转账到账金额(转入账户币种);null=同币种
    private LocalDate occurredAt;
    private String note;
    private Long submittedBy;
    private LocalDateTime submittedAt;
    private boolean draft;
    /** v1.18 · 这一笔是谁写进来的({@link com.family.finance.domain.ledger.LedgerSource});历史数据为 UNKNOWN = 当时没记 */
    private String sourceTag;
    /** v1.34.2 · 历史期补记方式:NULL 普通 / KEEP 余额不动 / SHIFT 之后各期一起挪(删时按同一种方式撤回) */
    private String backfillMode;

    /**
     * 转入账户实际收到的钱(<b>转入账户的币种</b>)。
     *
     * <p>跨币种划转有两个数:{@link #amount} 是转出方付出的(转出账户币种),
     * {@link #toAmount} 是转入方收到的(转入账户币种);同币种时 toAmount 为 null,两边都是 amount。</p>
     *
     * <p>issue #21(2026-09-23):人民币账户转 1000 到美元账户、实到 100 美元,余额都对,
     * 美元账户却冒出一条「900 未解释」—— 填报页给转入方算「已知流入」时用的是 amount(1000 人民币),
     * 不是 toAmount(100 美元)。这个字段 v0.8 就有了,新增与撤销划转时用对了,
     * 而算差额、显示明细的地方一直在手写 {@code getAmount()}。
     * 收成一个方法:站在转入方那一侧取金额,只许走这里。</p>
     */
    public BigDecimal receivedAmount() {
        return toAmount != null ? toAmount : amount;
    }
}
