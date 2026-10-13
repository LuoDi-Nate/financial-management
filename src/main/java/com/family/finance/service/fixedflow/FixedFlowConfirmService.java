package com.family.finance.service.fixedflow;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.family.ExpenseEntryMode;
import com.family.finance.domain.fixedflow.FixedFlow;
import com.family.finance.domain.fixedflow.FixedFlowOccurrence;
import com.family.finance.domain.ledger.LedgerSource;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FixedFlowMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.EntryService;
import com.family.finance.service.MoneyFormat;
import com.family.finance.service.expense.ExpenseLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * v1.32 · 固定收支的确认 / 这期没有 / 撤销 / 就是它(PRD FR-1112 ~ 1115 / FR-1151)。
 *
 * <p><b>唯一的写入编排</b>,而且<b>自己不插任何流水</b>:收入、支出、还贷、利息、划转、贷款余额
 * 一律调 {@link EntryService} 的既有方法(带写入回执,来源 {@code FIXED_FLOW})——
 * 现金行联动、补录期传导、负债账户不记还贷、只在消费上写分类,这些规矩只有一份(tech-design v1.32 选型二)。</p>
 *
 * <p>每个方法一个事务:一次确认要写 2–3 样东西(还贷 + 利息 + 贷款余额),中途失败就一笔都不留。
 * 重复确认挡在库这一层(唯一键 {@code (fixed_flow_id, period_id)}),先占位、后写入、再回填 id。</p>
 */
@Service
@RequiredArgsConstructor
public class FixedFlowConfirmService {

    private final FixedFlowMapper mapper;
    private final AccountMapper accountMapper;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final EntryService entryService;
    private final FixedFlowSummaryService summaryService;
    private final ExpenseLedgerService expenseLedger;
    private final AuditLogService auditLogService;
    private final FixedFlowService fixedFlowService;

    /** 一次确认的结果:页面顶部那句「记成了什么」 */
    public record Outcome(String message) {
    }

    /**
     * 确认一笔(FR-1112 ~ 1115)。
     *
     * @param edited   卡片上当场改过的金额;null = 按卡片上算好的
     * @param remember 改了金额后勾了「以后都按新金额」(只对固定金额 / 月供生效,FR-1113)
     */
    @Transactional
    public Outcome confirm(long familyId, long memberId, long periodId, long flowId, BigDecimal edited, boolean remember) {
        Period period = requireOpenPeriod(familyId, periodId);
        FixedFlow f = mapper.findById(familyId, flowId).orElseThrow(() -> new IllegalArgumentException("这条固定收支不存在"));
        if (!f.isActive()) throw new IllegalStateException("「" + f.getName() + "」已经停用了");
        LocalDate due = FixedFlowSummaryService.occurrence(f, period);
        if (due == null) throw new IllegalArgumentException("「" + f.getName() + "」这一期没有");
        boolean totalMode = expenseLedger.modeOf(familyId) != ExpenseEntryMode.ITEMIZED;
        if (totalMode && FixedFlow.EXPENSE.equals(f.getKind())) {
            throw new IllegalStateException("支出是按「每人一个总数」填的 ——「" + f.getName() + "」算进你填的总数就行,不单独记");
        }
        Account acc = account(familyId, f.getAccountId());
        Account target = f.getTargetAccountId() == null ? null : account(familyId, f.getTargetAccountId());
        FixedFlowSummaryService.Amount calc = summaryService.amountFor(familyId, f, period, acc, target);

        // 金额:按算好的,或当场改过的
        BigDecimal total = calc.result().ok() ? calc.result().total() : null;
        BigDecimal principal = calc.result().principal();
        BigDecimal interest = calc.result().interest();
        BigDecimal loanTarget = calc.loanBalanceTarget();
        String formula = calc.result().formula();
        boolean changed = false;
        if (edited != null) {
            BigDecimal e = edited.setScale(2, RoundingMode.HALF_EVEN);
            if (e.signum() <= 0) throw new IllegalArgumentException("金额要大于 0");
            changed = total == null || e.compareTo(total) != 0;
            total = e;
            if (f.isRepayLoan()) {
                if (calc.prevBalance() == null) throw new IllegalArgumentException("这笔贷款还没有上月余额,先在填报页填一次");
                if (FixedFlow.LOAN_RATE.equals(f.getAutoMethod()) && f.isAuto()) {
                    BigDecimal i = interest == null ? BigDecimal.ZERO : interest;
                    principal = e.subtract(i);
                    if (principal.signum() <= 0) {
                        throw new IllegalArgumentException("改过的金额 " + MoneyFormat.format2(acc.getCurrency(), e)
                                + " 不够这个月的利息 " + MoneyFormat.format2(acc.getCurrency(), i));
                    }
                } else {
                    principal = e;
                    interest = null;
                }
                if (changed) loanTarget = FixedFlowSummaryService.clampLoan(calc.prevBalance().add(principal));
            }
            if (changed) formula = (formula == null ? "" : formula + " · ") + "确认时改成 " + MoneyFormat.format2(acc.getCurrency(), e);
        }
        if (total == null) {
            throw new IllegalStateException(calc.result().problem() == null ? "这一笔算不出金额" : calc.result().problem());
        }
        total = total.setScale(2, RoundingMode.HALF_EVEN);

        // 先占位:同一笔同一期第二次点 → 唯一键冲突 → 明说
        FixedFlowOccurrence o = FixedFlowOccurrence.builder()
                .fixedFlowId(f.getId()).periodId(period.getId()).dueDate(due)
                .status(FixedFlowOccurrence.CONFIRMED).amount(total).principal(principal).interest(interest)
                .formula(trim(formula)).memberId(memberId).build();
        try {
            mapper.insertOccurrenceOwned(familyId, o);
        } catch (DuplicateKeyException dup) {
            throw new IllegalStateException("「" + f.getName() + "」这一期已经确认过了");
        }

        EntryService.WriteReceipt rc = new EntryService.WriteReceipt(LedgerSource.FIXED_FLOW.name());
        String msg;
        switch (f.getKind()) {
            case FixedFlow.INCOME -> {
                entryService.recordIncome(familyId, memberId, periodId, acc.getId(), f.getCategoryCode(), total, f.getName(), rc);
                o.setCashFlowId(rc.lastCashFlowId());
                msg = "已记收入 " + MoneyFormat.format2(acc.getCurrency(), total) + " → " + acc.getDisplayName();
            }
            case FixedFlow.EXPENSE -> {
                entryService.recordExpense(familyId, memberId, periodId, acc.getId(), f.getCategoryCode(), total, f.getName(),
                        f.getExpenseCategoryId(), true, false, rc);
                o.setCashFlowId(rc.lastCashFlowId());
                msg = "已记支出 " + MoneyFormat.format2(acc.getCurrency(), total) + " ← " + acc.getDisplayName();
            }
            case FixedFlow.REPAY, FixedFlow.PREMIUM -> {
                if (f.writesTransfer()) {
                    // 信用卡账单 / 交保费:只记一笔划转,不算支出(选型四)
                    entryService.addTransfer(familyId, memberId, periodId, acc.getId(), target.getId(), total, null,
                            f.getName(), false, rc);
                    o.setTransferId(rc.lastTransferId());
                    msg = "已记划转 " + acc.getDisplayName() + " → " + target.getDisplayName() + " "
                            + MoneyFormat.format2(acc.getCurrency(), total);
                } else {
                    msg = repayLoan(familyId, memberId, period, f, acc, target, principal, interest, loanTarget,
                            calc.prevBalance(), totalMode, rc, o);
                }
            }
            default -> throw new IllegalArgumentException("不认识的种类");
        }
        mapper.fillOccurrence(familyId, o);
        if (remember && changed && (!f.isAuto() || FixedFlow.LOAN_RATE.equals(f.getAutoMethod()))) {
            mapper.updateAmount(familyId, f.getId(), total);
            msg = msg + " · 以后按 " + MoneyFormat.format2(acc.getCurrency(), total);
        }
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "fixed_flow", f.getId(),
                "确认固定收支 " + f.getName() + " · " + MoneyFormat.format2(acc.getCurrency(), total) + " · 期 " + periodId);
        return new Outcome("「" + f.getName() + "」" + msg);
    }

    /**
     * 还款 · 贷款月供:付款账户记「还贷」(本金)+「利息支出」(有才记),贷款余额写成「上月末 + 本金」。
     * 贷款这期已经有人改过余额 → 不覆盖人填的数,只记付款那边(选型三)。总数模式 → 只写贷款余额(FR-1160)。
     */
    private String repayLoan(long familyId, long memberId, Period period, FixedFlow f, Account acc, Account loan,
                             BigDecimal principal, BigDecimal interest, BigDecimal loanTarget, BigDecimal prev,
                             boolean totalMode, EntryService.WriteReceipt rc, FixedFlowOccurrence o) {
        StringBuilder msg = new StringBuilder();
        if (!totalMode) {
            entryService.recordExpense(familyId, memberId, period.getId(), acc.getId(), "loan_payment", principal,
                    f.getName() + " · 还贷", null, true, false, rc);
            o.setCashFlowId(rc.lastCashFlowId());
            msg.append("已记还贷 ").append(MoneyFormat.format2(acc.getCurrency(), principal));
            if (interest != null && interest.signum() > 0) {
                entryService.recordExpense(familyId, memberId, period.getId(), acc.getId(), "interest_paid", interest,
                        f.getName() + " · 利息", null, true, false, rc);
                o.setInterestCashFlowId(rc.lastCashFlowId());
                msg.append(" + 利息 ").append(MoneyFormat.format2(acc.getCurrency(), interest));
            }
            msg.append(" ← ").append(acc.getDisplayName());
        }
        BigDecimal current = snapshotMapper.findByPeriodAndAccount(familyId, period.getId(), loan.getId())
                .map(PeriodSnapshot::getEndBalance).orElse(null);
        boolean untouched = loanTarget != null && (current == null || prev == null || current.compareTo(prev) == 0);
        if (untouched) {
            entryService.setLoanBalanceBySystem(familyId, memberId, period.getId(), loan.getId(), loanTarget,
                    "固定收支 · " + f.getName() + " 还款后余额");
            o.setLoanBalanceBefore(current == null ? prev : current);
            o.setLoanBalanceAfter(loanTarget);
            if (msg.length() > 0) msg.append(" · ");
            msg.append(loan.getDisplayName()).append(" 余额 → ").append(MoneyFormat.format2(loan.getCurrency(), loanTarget));
        } else {
            if (msg.length() > 0) msg.append(" · ");
            msg.append(loan.getDisplayName()).append(loanTarget == null
                    ? " 还没有上月余额,贷款余额请在填报页手动填"
                    : " 本期余额已经有人填过,没改它 —— 请核对一下");
        }
        return msg.toString();
    }

    /** 这期没有(FR-1111):只记一条结果,不写任何钱 */
    @Transactional
    public Outcome skip(long familyId, long memberId, long periodId, long flowId) {
        Period period = requireOpenPeriod(familyId, periodId);
        FixedFlow f = mapper.findById(familyId, flowId).orElseThrow(() -> new IllegalArgumentException("这条固定收支不存在"));
        LocalDate due = FixedFlowSummaryService.occurrence(f, period);
        if (due == null) throw new IllegalArgumentException("「" + f.getName() + "」这一期没有");
        try {
            mapper.insertOccurrenceOwned(familyId, FixedFlowOccurrence.builder()
                    .fixedFlowId(f.getId()).periodId(period.getId()).dueDate(due)
                    .status(FixedFlowOccurrence.SKIPPED).memberId(memberId).build());
        } catch (DuplicateKeyException dup) {
            throw new IllegalStateException("「" + f.getName() + "」这一期已经处理过了");
        }
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "fixed_flow", f.getId(),
                "固定收支这期没有 " + f.getName() + " · 期 " + periodId);
        return new Outcome("「" + f.getName() + "」这期没有");
    }

    /**
     * 就是它(FR-1151):导入的账单里那一笔就是这条固定支出 → 记一条 LINKED,不另写流水。
     */
    @Transactional
    public Outcome link(long familyId, long memberId, long periodId, long flowId, long cashFlowId) {
        Period period = requireOpenPeriod(familyId, periodId);
        FixedFlow f = mapper.findById(familyId, flowId).orElseThrow(() -> new IllegalArgumentException("这条固定收支不存在"));
        if (!FixedFlow.EXPENSE.equals(f.getKind())) throw new IllegalArgumentException("只有固定支出能关联导入的账单");
        if (mapper.countLinkableImport(familyId, cashFlowId, periodId, f.getAccountId()) != 1) {
            throw new IllegalArgumentException("那一笔不是这一期「同一个账户」导入进来的支出,没法关联");
        }
        LocalDate due = FixedFlowSummaryService.occurrence(f, period);
        if (due == null) throw new IllegalArgumentException("「" + f.getName() + "」这一期没有");
        Account acc = account(familyId, f.getAccountId());
        BigDecimal amount = summaryService.amountFor(familyId, f, period, acc, null).result().total();
        try {
            mapper.insertOccurrenceOwned(familyId, FixedFlowOccurrence.builder()
                    .fixedFlowId(f.getId()).periodId(period.getId()).dueDate(due)
                    .status(FixedFlowOccurrence.LINKED).amount(amount).cashFlowId(cashFlowId).memberId(memberId).build());
        } catch (DuplicateKeyException dup) {
            throw new IllegalStateException("「" + f.getName() + "」这一期已经处理过了");
        }
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "fixed_flow", f.getId(),
                "固定收支关联导入的账单 " + f.getName() + " · 流水 " + cashFlowId);
        return new Outcome("「" + f.getName() + "」就是导入的那一笔,没有另记");
    }

    /**
     * 撤销(FR-1114):删掉那几笔(余额跟着回去)、贷款余额还原,删掉这条结果。
     * 流水已经被人在列表里删过 → 跳过(再删一次会把余额冲回两遍);贷款余额后来被人改过 → 不还原并写明。
     * LINKED / SKIPPED 只删结果 —— 关联的那一笔是导入(或人自己记)的,不是这里写的。
     */
    @Transactional
    public Outcome undo(long familyId, long memberId, long occurrenceId) {
        FixedFlowOccurrence o = mapper.findOccurrenceById(familyId, occurrenceId)
                .orElseThrow(() -> new IllegalArgumentException("这条记录不存在,可能已经撤销过了"));
        Period period = requireOpenPeriod(familyId, o.getPeriodId());
        FixedFlow f = mapper.findById(familyId, o.getFixedFlowId()).orElseThrow(() -> new IllegalArgumentException("这条固定收支不存在"));
        StringBuilder note = new StringBuilder();
        if (FixedFlowOccurrence.CONFIRMED.equals(o.getStatus())) {
            for (Long id : new Long[]{o.getInterestCashFlowId(), o.getCashFlowId()}) {
                if (id != null && mapper.countLiveCashFlow(familyId, id) == 1) {
                    entryService.softDeleteCashFlow(familyId, memberId, id);
                }
            }
            if (o.getTransferId() != null && mapper.countLiveTransfer(familyId, o.getTransferId()) == 1) {
                entryService.softDeleteTransfer(familyId, memberId, o.getTransferId());
            }
            if (o.getLoanBalanceAfter() != null && f.getTargetAccountId() != null) {
                BigDecimal current = snapshotMapper.findByPeriodAndAccount(familyId, period.getId(), f.getTargetAccountId())
                        .map(PeriodSnapshot::getEndBalance).orElse(null);
                if (current != null && current.compareTo(o.getLoanBalanceAfter()) == 0 && o.getLoanBalanceBefore() != null) {
                    entryService.setLoanBalanceBySystem(familyId, memberId, period.getId(), f.getTargetAccountId(),
                            o.getLoanBalanceBefore(), "撤销固定收支 · " + f.getName());
                } else {
                    note.append(" · 贷款余额后来改过,没有还原,请核对");
                }
            }
        }
        mapper.deleteOccurrence(familyId, o.getId());
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "fixed_flow", f.getId(),
                "撤销固定收支 " + f.getName() + " · 期 " + period.getId() + note);
        return new Outcome("已撤销「" + f.getName() + "」" + note);
    }

    /**
     * FR-1131 · 记一笔收入 / 支出时勾了「每月都有,设成固定」:记这一笔 + 用它建一条固定收支(本期那条直接算已关联)。
     * 一个事务:设置建不起来(比如类目不合适)就连这一笔也不记,页面上说原因,用户改了再提交。
     */
    @Transactional
    public Outcome recordAndMakeFixed(long familyId, long memberId, long periodId, String kind, long accountId,
                                      String categoryCode, BigDecimal amount, String note, Long expenseCategoryId,
                                      boolean affectsBalance, boolean oneOff) {
        Period period = requireOpenPeriod(familyId, periodId);
        EntryService.WriteReceipt rc = new EntryService.WriteReceipt(LedgerSource.MANUAL.name());
        if (FixedFlow.INCOME.equals(kind)) {
            entryService.recordIncome(familyId, memberId, periodId, accountId, categoryCode, amount, note, rc);
        } else {
            entryService.recordExpense(familyId, memberId, periodId, accountId, categoryCode, amount, note,
                    expenseCategoryId, affectsBalance, oneOff, rc);
        }
        fixedFlowService.createFromRecord(familyId, memberId, period, kind, accountId, categoryCode,
                expenseCategoryId, amount, note, rc.lastCashFlowId(), LocalDate.now());
        return new Outcome("已记这一笔,并设成每月 " + LocalDate.now().getDayOfMonth() + " 号的固定"
                + (FixedFlow.INCOME.equals(kind) ? "收入" : "支出") + " —— 以后到日子在「固定收支」里一键确认");
    }

    private Period requireOpenPeriod(long familyId, long periodId) {
        Period p = periodMapper.findById(familyId, periodId).orElseThrow(() -> new IllegalArgumentException("账期不存在"));
        if (p.getStatus() != PeriodStatus.OPEN) throw new IllegalStateException("这一期已经关账了,先重新打开再改");
        return p;
    }

    private Account account(long familyId, long id) {
        return accountMapper.findById(familyId, id).orElseThrow(() -> new IllegalArgumentException("账户不存在"));
    }

    private static String trim(String s) {
        return s == null ? null : s.length() > 255 ? s.substring(0, 255) : s;
    }
}
