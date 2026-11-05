package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.snapshot.SnapshotTodo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record EntryRow(
        Account account,
        String ownerName,
        SnapshotTodo todo,
        PeriodSnapshot currentSnapshot,
        PeriodSnapshot previousSnapshot,
        BigDecimal delta,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal transferIn,
        BigDecimal transferOut,
        BigDecimal unexplained,
        String currentBalanceLabel,
        String previousBalanceLabel,
        String deltaLabel,
        String unexplainedLabel,
        String warning,
        boolean done,
        /** PRD FR-10:|未解释金额| > ¥3000 时给"看起来像账户间转账?"软提示 */
        boolean suggestTransfer,
        /** 本期已收到的来自其他账户的划转明细(用于接收方账户的"已收到"提示) */
        List<TransferRef> incoming,
        /** 本期已划出到其他账户的明细 */
        List<TransferRef> outgoing,
        /** PRD FR-7/8/9:本期所有流水合并视图(snapshot/cash_flow/transfer 统一为 LedgerEntry) */
        List<LedgerEntry> ledger
) {
    /** Thymeleaf bean-style accessor 显式提供,绕过 record accessor 在某些 SpEL 上下文下解析为 null 的坑。 */
    public List<LedgerEntry> getLedger() { return ledger; }
    public List<TransferRef> getIncoming() { return incoming; }
    public List<TransferRef> getOutgoing() { return outgoing; }
    public PeriodSnapshot getCurrentSnapshot() { return currentSnapshot; }
    public PeriodSnapshot getPreviousSnapshot() { return previousSnapshot; }
    public Account getAccount() { return account; }
    public SnapshotTodo getTodo() { return todo; }
    public record TransferRef(String counterpartyName, BigDecimal amount, String amountLabel) {}

    /**
     * 流水统一视图。kind 用 SNAPSHOT / INCOME / EXPENSE / TRANSFER_IN / TRANSFER_OUT 区分。
     * amountSignedLabel 已带正负号 + 币种符号(便于模板直显)。
     */
    public record LedgerEntry(
            LedgerKind kind,
            LocalDateTime occurredAt,
            BigDecimal amount,
            String amountSignedLabel,
            /** 收入/支出 → 类别名;划转 → 对方账户名;snapshot → null */
            String label,
            /** 用户备注;snapshot 时为修改人显示名 */
            String note,
            /** v0.2 FR-32 · 行 id(cash_flow.id 或 transfer.id);SNAPSHOT 类型为 null */
            Long sourceId,
            /** v0.2 · 此条目所属的周期是否 OPEN(决定是否显示删除按钮) */
            boolean periodOpen,
            /** v1.4 · VALUATION 若由截图导入触发 → 指向 holding_import,ledger 显「看明细」链接;否则 null */
            Long refImportId,
            /**
             * issue #21 · 划转时【对方账户】那一侧的金额,已按对方的币种格式化(不带正负号);其它类型为 null。
             *
             * <p>删除划转的确认框要同时说出两边各变多少。原来它拿本行这一条的金额拼两边、币种写死成 ¥ ——
             * 跨币种时(人民币转 1000、美元到 100)确认框就会写成「test银行卡 −¥1,000」或「备用金 +¥100」,
             * 两边都说错了。实际执行的撤销用的是对的数,但用户是看着确认框决定删不删的。</p>
             */
            String counterAmountLabel
    ) {
        /** 非划转条目:没有「对方账户」 */
        public LedgerEntry(LedgerKind kind, LocalDateTime occurredAt, BigDecimal amount, String amountSignedLabel,
                           String label, String note, Long sourceId, boolean periodOpen, Long refImportId) {
            this(kind, occurredAt, amount, amountSignedLabel, label, note, sourceId, periodOpen, refImportId, null);
        }
    }

    public enum LedgerKind {
        /** 本期末余额已写入(snapshot 最新值) */
        SNAPSHOT,
        INCOME,
        EXPENSE,
        TRANSFER_IN,
        TRANSFER_OUT,
        /** v0.4.1 FR-52f · 股票账户估值变动(自动估值 cron / manual / holding change 触发) */
        VALUATION
    }
}
