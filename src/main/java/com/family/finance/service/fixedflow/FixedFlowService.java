package com.family.finance.service.fixedflow;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.fixedflow.FixedFlow;
import com.family.finance.domain.fixedflow.FixedFlowOccurrence;
import com.family.finance.domain.flow.CashFlowCategory;
import com.family.finance.domain.insurance.InsurancePolicy;
import com.family.finance.domain.period.Period;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.CashFlowCategoryMapper;
import com.family.finance.repository.FixedFlowMapper;
import com.family.finance.repository.InsurancePolicyMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.MoneyFormat;
import com.family.finance.service.expense.ExpenseCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * v1.32 · 固定收支的<b>设置</b>:增 / 改 / 停用、校验、归档联动、记一笔时顺手设、从贷款 / 保单预填。
 *
 * <p>只写 {@code fixed_flow}(以及「记一笔时顺手设」那一条 LINKED 确认记录),<b>不写任何流水</b>。
 * 确认类写入在 {@link FixedFlowConfirmService}。</p>
 */
@Service
@RequiredArgsConstructor
public class FixedFlowService {

    /** 收入里不给设成固定的:股票类收入走持仓联动,不是「每月一笔现金」 */
    private static final Set<String> EXPENSE_NATURES = Set.of("consumption", "to_relatives");

    private final FixedFlowMapper mapper;
    private final AccountMapper accountMapper;
    private final CashFlowCategoryMapper categoryMapper;
    private final InsurancePolicyMapper policyMapper;
    private final MemberMapper memberMapper;
    private final PeriodMapper periodMapper;
    private final ExpenseCategoryService expenseCategoryService;
    private final AuditLogService auditLogService;

    /** 管理页 / 表单提交上来的东西(字段名与表单 name 一致) */
    @lombok.Data
    public static class Form {
        private Long id;
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
        private List<Integer> months;
        private Integer dayOfMonth;
        private Long ownerMemberId;
        private String endMonth;      // yyyy-MM,可空
        private String note;
    }

    public List<FixedFlow> listAll(long familyId) {
        return mapper.findAllByFamily(familyId);
    }

    public FixedFlow require(long familyId, long id) {
        return mapper.findById(familyId, id).orElseThrow(() -> new IllegalArgumentException("这条固定收支不存在"));
    }

    /** 新增或修改。返回 id。 */
    @Transactional
    public long save(long familyId, long memberId, Form form) {
        FixedFlow f = validate(familyId, form);
        if (form.getId() == null) {
            f.setStartMonth(defaultStartMonth(familyId));
            f.setCreatedBy(memberId);
            mapper.insertOwned(familyId, f);
            auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "fixed_flow", f.getId(),
                    "新增固定收支 " + f.kindLabel() + " · " + f.getName());
            return f.getId();
        }
        FixedFlow old = require(familyId, form.getId());
        f.setId(old.getId());
        if (mapper.update(familyId, f) != 1) throw new IllegalStateException("保存没成功,刷新后再试");
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "fixed_flow", f.getId(),
                "修改固定收支 " + f.kindLabel() + " · " + f.getName());
        return f.getId();
    }

    @Transactional
    public void disable(long familyId, long memberId, long id) {
        FixedFlow f = require(familyId, id);
        mapper.disable(familyId, id, FixedFlow.DISABLED_MANUAL);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "fixed_flow", id, "停用固定收支 " + f.getName());
    }

    @Transactional
    public void enable(long familyId, long memberId, long id) {
        FixedFlow f = require(familyId, id);
        Account a = accountMapper.findById(familyId, f.getAccountId()).orElse(null);
        Account t = f.getTargetAccountId() == null ? null : accountMapper.findById(familyId, f.getTargetAccountId()).orElse(null);
        if ((a != null && a.getArchivedAt() != null) || (t != null && t.getArchivedAt() != null)) {
            throw new IllegalArgumentException("用到的账户已经归档了,先恢复账户或改成别的账户");
        }
        mapper.enable(familyId, id);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "fixed_flow", id, "重新启用固定收支 " + f.getName());
    }

    /** FR-1134 · 账户归档 → 用到它的固定收支停用。返回停了几条(给归档后的提示)。 */
    @Transactional
    public int onAccountArchived(long familyId, long memberId, long accountId) {
        int n = mapper.disableByAccount(familyId, accountId);
        if (n > 0) {
            auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", accountId,
                    "账户归档 · 用到它的 " + n + " 条固定收支已停用");
        }
        return n;
    }

    /**
     * FR-1131 · 记一笔收入 / 支出时勾了「每月都有,设成固定」:照这一笔建一条(几号 = 今天),
     * 并给这一期写一条 LINKED 确认记录指向刚记的那笔 —— 否则它今天就会作为「待确认」再出现一次。
     */
    @Transactional
    public long createFromRecord(long familyId, long memberId, Period period, String kind, long accountId,
                                 String categoryCode, Long expenseCategoryId, BigDecimal amount, String note,
                                 Long cashFlowId, LocalDate today) {
        CashFlowCategory cat = categoryMapper.findByCode(categoryCode).orElse(null);
        Form form = new Form();
        form.setKind(kind);
        form.setName(note != null && !note.isBlank() ? note.trim()
                : (cat != null ? cat.getDisplayName() : FixedFlow.INCOME.equals(kind) ? "固定收入" : "固定支出"));
        form.setAmountMode(FixedFlow.FIXED);
        form.setAmount(amount);
        form.setAccountId(accountId);
        form.setCategoryCode(categoryCode);
        form.setExpenseCategoryId(expenseCategoryId);
        form.setCycle(FixedFlowSchedule.MONTHLY);
        form.setDayOfMonth(today.getDayOfMonth());
        form.setOwnerMemberId(memberId);
        FixedFlow f = validate(familyId, form);
        f.setStartMonth(period.getPeriodStart().withDayOfMonth(1));
        f.setCreatedBy(memberId);
        mapper.insertOwned(familyId, f);
        LocalDate due = FixedFlowSchedule.occurrenceIn(f.getCycle(), f.getMonths(), f.getDayOfMonth(),
                YearMonth.from(f.getStartMonth()), null, period.getPeriodStart(), period.getPeriodEnd());
        if (due != null && cashFlowId != null) {
            mapper.insertOccurrenceOwned(familyId, FixedFlowOccurrence.builder()
                    .fixedFlowId(f.getId()).periodId(period.getId()).dueDate(due)
                    .status(FixedFlowOccurrence.LINKED).amount(amount.setScale(2, RoundingMode.HALF_EVEN))
                    .cashFlowId(cashFlowId).memberId(memberId).build());
        }
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "fixed_flow", f.getId(),
                "记账时设成固定 · " + f.kindLabel() + " · " + f.getName() + " · 每月 " + f.getDayOfMonth() + " 号");
        return f.getId();
    }

    /** 账户详情上的一行(FR-1133) */
    public record TargetLine(FixedFlow flow, String text) {
    }

    /** 指着这笔贷款 / 这个保险账户的固定收支,一句话一行:「每期还款 · 自动 · 按上两月差值 · 从 工资卡 · 20 号」 */
    public List<TargetLine> linesForTarget(long familyId, long accountId) {
        return mapper.findActiveByTarget(familyId, accountId).stream().map(f -> {
            Account payer = accountMapper.findById(familyId, f.getAccountId()).orElse(null);
            String ccy = payer == null ? "CNY" : payer.getCurrency();
            String head = FixedFlow.PREMIUM.equals(f.getKind()) ? "每期交保费" : f.isRepayCard() ? "每期还信用卡" : "每期还款";
            String cyc = FixedFlowSchedule.YEARLY.equals(f.getCycle())
                    ? "每年 " + String.join("、", FixedFlowSchedule.parseMonths(f.getMonths()).stream().map(m -> m + " 月").toList())
                    : "每月";
            String text = head + " · " + amountLabel(f, ccy) + " · 从 " + (payer == null ? "?" : payer.getDisplayName())
                    + " · " + cyc + " " + f.getDayOfMonth() + " 号";
            return new TargetLine(f, text);
        }).toList();
    }

    // ── 预填(账户页一键进来)────────────────────────────────────────────

    /** FR-1120 / 1133 · 「设成固定还款」:贷款 → 还款表单预填 */
    public Form prefillForLoan(long familyId, long loanId, LocalDate today) {
        Account loan = accountMapper.findById(familyId, loanId).orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (loan.getType() != AccountType.LOAN) throw new IllegalArgumentException("只有贷款 / 信用卡账户能设固定还款");
        Form f = new Form();
        f.setKind(FixedFlow.REPAY);
        f.setTargetAccountId(loanId);
        boolean card = isCreditCard(familyId, loan);
        f.setRepayKind(card ? FixedFlow.REPAY_CARD : FixedFlow.REPAY_LOAN);
        f.setAmountMode(FixedFlow.AUTO);
        f.setAutoMethod(card ? FixedFlow.CARD_FULL : FixedFlow.LOAN_DIFF);
        f.setAccountId(loan.getDefaultPaymentSourceAccountId());
        f.setName(loan.getDisplayName());
        f.setCycle(FixedFlowSchedule.MONTHLY);
        f.setDayOfMonth(today.getDayOfMonth());
        f.setOwnerMemberId(loan.getPrimaryOwnerMemberId());
        return f;
    }

    /** FR-1105 · 保险账户「设成固定交保费」:按保单预填(年缴月份 = 生效月,缴满那期为止) */
    public Form prefillForPolicy(long familyId, long insuranceId, LocalDate today) {
        Account acc = accountMapper.findById(familyId, insuranceId).orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (acc.getType() != AccountType.INSURANCE) throw new IllegalArgumentException("只有保险账户能设固定交保费");
        InsurancePolicy p = policyMapper.findByAccount(familyId, insuranceId).orElse(null);
        Form f = new Form();
        f.setKind(FixedFlow.PREMIUM);
        f.setTargetAccountId(insuranceId);
        f.setName(acc.getDisplayName() + " · 保费");
        f.setOwnerMemberId(acc.getPrimaryOwnerMemberId());
        f.setCycle(FixedFlowSchedule.MONTHLY);
        f.setDayOfMonth(today.getDayOfMonth());
        boolean hasPremium = p != null && p.getPremiumAmount() != null && p.getPremiumAmount().signum() > 0;
        f.setAmountMode(hasPremium ? FixedFlow.AUTO : FixedFlow.FIXED);
        f.setAutoMethod(hasPremium ? FixedFlow.POLICY : null);
        if (p != null && p.getPolicyEffectiveDate() != null) {
            LocalDate eff = p.getPolicyEffectiveDate();
            f.setDayOfMonth(eff.getDayOfMonth());
            boolean annual = "ANNUAL".equals(p.getPremiumFrequency());
            if (annual) {
                f.setCycle(FixedFlowSchedule.YEARLY);
                f.setMonths(List.of(eff.getMonthValue()));
            }
            Integer total = p.getPremiumTermsTotal();
            if (total != null && total > 0) {
                YearMonth end = YearMonth.from(eff).plusMonths((long) (total - 1) * (annual ? 12 : 1));
                f.setEndMonth(end.toString());
            }
        }
        return f;
    }

    /** 信用卡判断(FR-1104):建户模板是「信用卡」,或这张卡上记过日常开支。分期贷款(loan_kind=CREDIT_CARD)不算。 */
    public boolean isCreditCard(long familyId, Account loan) {
        if (loan == null || loan.getType() != AccountType.LOAN) return false;
        if ("credit_card".equals(mapper.templateCodeOf(familyId, loan.getId()).orElse(null))) return true;
        return mapper.countConsumptionOn(familyId, loan.getId()) > 0;
    }

    // ── 校验 ────────────────────────────────────────────────────────────

    /** 种类 × 账户类型 × 金额方式;不合法一律 IllegalArgumentException(页面上原样显示) */
    FixedFlow validate(long familyId, Form form) {
        String kind = form.getKind();
        if (!Set.of(FixedFlow.INCOME, FixedFlow.EXPENSE, FixedFlow.REPAY, FixedFlow.PREMIUM).contains(kind)) {
            throw new IllegalArgumentException("先选是收入、支出、还款还是交保费");
        }
        if (form.getAccountId() == null) {
            throw new IllegalArgumentException(FixedFlow.INCOME.equals(kind) ? "选一个入账账户" : "选从哪个账户付");
        }
        Account acc = activeAccount(familyId, form.getAccountId());
        Account target = null;
        String repayKind = null;
        String mode = FixedFlow.AUTO.equals(form.getAmountMode()) ? FixedFlow.AUTO : FixedFlow.FIXED;
        String method = FixedFlow.AUTO.equals(mode) ? form.getAutoMethod() : null;
        String category = null;
        Long expenseCatId = null;

        switch (kind) {
            case FixedFlow.INCOME -> {
                if (acc.getType().isLiability()) throw new IllegalArgumentException("贷款 / 信用卡账户不能记收入");
                if (acc.getType() == AccountType.STOCK) {
                    throw new IllegalArgumentException("股票账户的收入按持仓记(填报页「股票收入」),不能设成固定");
                }
                CashFlowCategory cat = categoryMapper.findByCode(form.getCategoryCode())
                        .filter(c -> "INCOME".equals(c.getKind()))
                        .orElseThrow(() -> new IllegalArgumentException("选一个收入类目"));
                if (cat.getAccountType() != null && !cat.getAccountType().equals(acc.getType().name())) {
                    throw new IllegalArgumentException("「" + cat.getDisplayName() + "」要记在"
                            + cat.getAccountType() + "账户上,换个类目或账户");
                }
                category = cat.getCode();
                requireMethodIn(mode, method, Set.of(FixedFlow.AVG3));
            }
            case FixedFlow.EXPENSE -> {
                String code = form.getCategoryCode() == null || form.getCategoryCode().isBlank() ? "consumption" : form.getCategoryCode();
                if (!EXPENSE_NATURES.contains(code)) {
                    throw new IllegalArgumentException("还贷 / 利息请设成「还款」—— 那样能自动按贷款算、同时减贷款余额");
                }
                category = code;
                if ("consumption".equals(code) && expenseCategoryService.isUsable(familyId, form.getExpenseCategoryId())) {
                    expenseCatId = form.getExpenseCategoryId();
                }
                requireMethodIn(mode, method, Set.of(FixedFlow.AVG3));
            }
            case FixedFlow.REPAY -> {
                if (form.getTargetAccountId() == null) throw new IllegalArgumentException("选还哪个贷款 / 信用卡");
                target = activeAccount(familyId, form.getTargetAccountId());
                if (target.getType() != AccountType.LOAN) throw new IllegalArgumentException("「还哪个」要选贷款或信用卡账户");
                repayKind = FixedFlow.REPAY_CARD.equals(form.getRepayKind()) ? FixedFlow.REPAY_CARD : FixedFlow.REPAY_LOAN;
                payerCheck(acc, target);
                if (FixedFlow.REPAY_CARD.equals(repayKind)) {
                    requireMethodIn(mode, method, Set.of(FixedFlow.CARD_FULL));
                } else {
                    requireMethodIn(mode, method, Set.of(FixedFlow.LOAN_DIFF, FixedFlow.LOAN_RATE));
                    if (FixedFlow.LOAN_RATE.equals(method)
                            && (target.getAnnualRatePct() == null || target.getAnnualRatePct().signum() <= 0)) {
                        throw new IllegalArgumentException("「按年利率拆」要先在贷款「" + target.getDisplayName()
                                + "」的编辑页填年利率");
                    }
                }
            }
            case FixedFlow.PREMIUM -> {
                if (form.getTargetAccountId() == null) throw new IllegalArgumentException("选交到哪个保险账户");
                target = activeAccount(familyId, form.getTargetAccountId());
                if (target.getType() != AccountType.INSURANCE) {
                    throw new IllegalArgumentException("交保费要选保险账户;没有保险账户的(消费型保险)请设成「支出」");
                }
                payerCheck(acc, target);
                requireMethodIn(mode, method, Set.of(FixedFlow.POLICY));
                if (FixedFlow.POLICY.equals(method)) {
                    InsurancePolicy p = policyMapper.findByAccount(familyId, target.getId()).orElse(null);
                    if (p == null || p.getPremiumAmount() == null || p.getPremiumAmount().signum() <= 0) {
                        throw new IllegalArgumentException("「" + target.getDisplayName() + "」的保单登记里没填每期保费,先补上或改成固定金额");
                    }
                }
            }
            default -> throw new IllegalArgumentException("不认识的种类");
        }

        BigDecimal amount = form.getAmount() == null ? null : form.getAmount().setScale(2, RoundingMode.HALF_EVEN);
        boolean needsAmount = FixedFlow.FIXED.equals(mode) || FixedFlow.LOAN_RATE.equals(method) || FixedFlow.AVG3.equals(method);
        if (needsAmount && (amount == null || amount.signum() <= 0)) {
            throw new IllegalArgumentException(FixedFlow.LOAN_RATE.equals(method) ? "填每月的月供"
                    : FixedFlow.AVG3.equals(method) ? "填一个起始金额(还没确认过时先按它)" : "填金额");
        }
        if (!needsAmount) amount = null;

        Integer day = form.getDayOfMonth();
        if (day == null || day < 1 || day > 31) throw new IllegalArgumentException("几号要在 1–31 之间");
        String cycle = FixedFlowSchedule.YEARLY.equals(form.getCycle()) ? FixedFlowSchedule.YEARLY : FixedFlowSchedule.MONTHLY;
        String months = null;
        if (FixedFlowSchedule.YEARLY.equals(cycle)) {
            months = FixedFlowSchedule.formatMonths(form.getMonths() == null ? Set.of() : Set.copyOf(form.getMonths()));
            if (months.isEmpty()) throw new IllegalArgumentException("每年的话,勾上是哪几个月");
        }
        LocalDate endMonth = null;
        if (form.getEndMonth() != null && !form.getEndMonth().isBlank()) {
            try {
                endMonth = YearMonth.parse(form.getEndMonth().trim()).atDay(1);
            } catch (Exception e) {
                throw new IllegalArgumentException("「到哪期为止」要写成 2030-06 这样");
            }
        }
        Long owner = form.getOwnerMemberId();
        if (owner != null && memberMapper.findById(familyId, owner).isEmpty()) owner = null;
        String name = form.getName() == null ? "" : form.getName().trim();
        if (name.isEmpty()) {
            name = target != null ? target.getDisplayName() : acc.getDisplayName();
        }
        if (name.length() > 60) name = name.substring(0, 60);
        String note = form.getNote() == null || form.getNote().isBlank() ? null : form.getNote().trim();
        if (note != null && note.length() > 255) note = note.substring(0, 255);

        return FixedFlow.builder()
                .familyId(familyId).name(name).kind(kind).repayKind(repayKind)
                .amountMode(mode).autoMethod(method).amount(amount)
                .accountId(acc.getId()).targetAccountId(target == null ? null : target.getId())
                .categoryCode(category).expenseCategoryId(expenseCatId)
                .cycle(cycle).months(months).dayOfMonth(day).ownerMemberId(owner)
                .endMonth(endMonth).note(note)
                .build();
    }

    private static void requireMethodIn(String mode, String method, Set<String> allowed) {
        if (!FixedFlow.AUTO.equals(mode)) return;
        if (method == null || !allowed.contains(method)) {
            throw new IllegalArgumentException("这种固定收支能用的自动计算只有:"
                    + String.join(" / ", allowed.stream().map(FixedFlow::methodName).sorted().toList()));
        }
    }

    private static void payerCheck(Account payer, Account target) {
        if (payer.getId().equals(target.getId())) throw new IllegalArgumentException("付款账户和对方不能是同一个");
        if (payer.getType().isLiability()) {
            throw new IllegalArgumentException("付款账户要选钱实际流出的账户(银行卡 / 余额宝),不能是贷款或信用卡");
        }
        // 跨币种要两头各一个金额(划转的 to_amount),一键确认给不出来 —— 这种请在填报页手动记
        if (payer.getCurrency() != null && !payer.getCurrency().equalsIgnoreCase(target.getCurrency())) {
            throw new IllegalArgumentException("「" + payer.getDisplayName() + "」和「" + target.getDisplayName()
                    + "」币种不同,这种还款 / 交保费请在填报页手动记划转");
        }
    }

    private Account activeAccount(long familyId, long id) {
        Account a = accountMapper.findById(familyId, id).orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (a.getArchivedAt() != null) throw new IllegalArgumentException("账户「" + a.getDisplayName() + "」已归档");
        return a;
    }

    /** 新建的从「最早一个开着的期」那个月开始 —— 双开期时补录期也能用上 */
    private LocalDate defaultStartMonth(long familyId) {
        List<Period> open = periodMapper.findRecordableOpen(familyId);
        LocalDate d = open.isEmpty() ? LocalDate.now() : open.get(0).getPeriodStart();
        return d.withDayOfMonth(1);
    }

    /** 管理页金额列的一句话 */
    public static String amountLabel(FixedFlow f, String ccy) {
        if (!f.isAuto()) return f.getAmount() == null ? "—" : MoneyFormat.format2(ccy, f.getAmount());
        if (FixedFlow.LOAN_RATE.equals(f.getAutoMethod()) && f.getAmount() != null) {
            return "月供 " + MoneyFormat.format2(ccy, f.getAmount()) + " · 按年利率拆";
        }
        return f.methodLabel();
    }
}
