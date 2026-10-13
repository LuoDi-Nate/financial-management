package com.family.finance.service.fixedflow;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.family.ExpenseEntryMode;
import com.family.finance.domain.fixedflow.FixedFlow;
import com.family.finance.domain.fixedflow.FixedFlowOccurrence;
import com.family.finance.domain.insurance.InsurancePolicy;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FixedFlowMapper;
import com.family.finance.repository.InsurancePolicyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.service.EntryRow;
import com.family.finance.service.FxService;
import com.family.finance.service.MoneyFormat;
import com.family.finance.service.expense.ExpenseLedgerService;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * v1.32 · 填报页「固定收支」那一行摘要的视图模型 + 各处提醒用的「待确认」数(PRD FR-1110 ~ 1121 / FR-1140)。
 *
 * <p><b>只读</b>。「待确认」只在这里判一次(导航徽标、仪表盘、填报页进度都调 {@link #pendingCount}),
 * 不在别处另写一份判据。</p>
 */
@Service
@RequiredArgsConstructor
public class FixedFlowSummaryService {

    private final FixedFlowMapper mapper;
    private final AccountMapper accountMapper;
    /** 含已归档成员 —— 负责人可能是后来归档的人(v1.15 名录,护栏 v115) */
    private final com.family.finance.service.member.MemberDirectory memberDirectory;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final InsurancePolicyMapper policyMapper;
    private final ExpenseLedgerService expenseLedger;
    private final FxService fxService;

    public static final String DUE = "DUE";
    public static final String LATER = "LATER";
    public static final String SUGGEST = "SUGGEST";
    public static final String LIST = "LIST";        // 总数模式下的固定支出:只列清单(FR-1160)
    public static final String DONE = "DONE";

    /** 一张卡片(一行)。模板只读它的 getter。 */
    @Getter
    @Builder
    public static class Item {
        private final String state;
        private final FixedFlow flow;
        private final Long loanAccountId;      // 贷款建议:是哪笔贷款
        private final String name;
        private final String kindLabel;
        private final String kindCss;          // in / out / loan / ins / sug
        private final String ownerName;
        private final LocalDate dueDate;
        private final String dueLabel;
        private final String ccy;
        private final BigDecimal amount;       // 卡片金额(账户币种);算不出来 = null
        private final String amountLabel;
        private final boolean calc;            // 自动算的 → 金额旁标「算」
        private final String formula;
        private final String problem;          // 算不出来的原因
        private final String detail;           // 「← 招商银行 · 5 号」
        private final String split;            // 还贷本金 + 利息
        private final String diffLabel;        // 比上次多 / 少
        private final boolean editable;
        private final boolean rememberable;    // 改了金额问「以后都按新金额?」
        private final boolean balanceOnly;     // 总数模式下的贷款月供:只改贷款余额
        private final Long importMatchId;
        private final String importMatchLabel;
        private final Long occurrenceId;
        private final String occurrenceStatus;
        private final String resultText;
        private final boolean undoable;
        private final String suggestLabel;
        private final String suggestDeltaLabel;
        private final BigDecimal keepBalance;

        public boolean isConfirmable() { return DUE.equals(state) && problem == null && amount != null; }
    }

    @Getter
    @Builder
    public static class Summary {
        private final Period period;
        private final boolean open;
        private final boolean totalMode;
        private final List<Item> due;          // 到了日子的(待确认 + 贷款建议 + 总数模式清单)
        private final List<Item> later;        // 还没到日子
        private final List<Item> done;         // 已确认 / 这期没有 / 就是导入的那笔
        private final int pendingCount;        // 待确认(不含建议、不含清单)
        private final int suggestCount;
        private final int confirmableCount;
        private final String pendingTotalLabel;
        private final Item nextUpcoming;
        private final int lastPeriodMissed;
        private final String lastPeriodLabel;
        private final boolean anyFlows;
        private final String totalModeExpenseLabel; // 总数模式:本期固定支出合计
        private final String totalModeBreakdown;
        private final Set<Long> loanPendingIds;  // 贷款行里写「本期还款 · 待确认 ↑」的那几笔

        /** 这一行要不要出来:有设置、或有贷款建议(否则只剩「+ 设固定收支」小入口) */
        public boolean isEmpty() { return !anyFlows && suggestCount == 0; }
    }

    // ── 提醒数(导航 / 仪表盘 / 填报页进度)────────────────────────────

    /** 全家所有开着的期里,到了日子还没确认的固定收支笔数(FR-1140)。 */
    public int pendingCount(long familyId) {
        return pendingCount(familyId, LocalDate.now());
    }

    int pendingCount(long familyId, LocalDate today) {
        List<Period> open = periodMapper.findRecordableOpen(familyId);
        if (open.isEmpty()) return 0;
        List<FixedFlow> flows = mapper.findActiveByFamily(familyId);
        if (flows.isEmpty()) return 0;
        boolean totalMode = expenseLedger.modeOf(familyId) != ExpenseEntryMode.ITEMIZED;
        int n = 0;
        for (Period p : open) {
            Set<Long> has = new HashSet<>(mapper.flowIdsWithOccurrence(familyId, p.getId()));
            for (FixedFlow f : flows) {
                if (has.contains(f.getId())) continue;
                if (totalMode && FixedFlow.EXPENSE.equals(f.getKind())) continue;
                LocalDate d = occurrence(f, p);
                if (d != null && FixedFlowSchedule.isDue(d, today)) n++;
            }
        }
        return n;
    }

    // ── 填报页摘要 ───────────────────────────────────────────────────────

    /**
     * @param rows     填报页这一期的账户行(贷款建议沿用 {@link EntryRow#isShowLoanPrompt} —— 今天那条提示的判据)
     * @param mineOnly 「仅我」视图(= 我的待办,FR-1140):只列负责人是我或共用的
     */
    public Summary build(long familyId, long memberId, Period period, List<EntryRow> rows, boolean mineOnly,
                         String baseCcy, LocalDate today) {
        boolean open = period.getStatus() == PeriodStatus.OPEN;
        boolean totalMode = expenseLedger.modeOf(familyId) != ExpenseEntryMode.ITEMIZED;
        List<FixedFlow> all = mapper.findAllByFamily(familyId);
        Map<Long, Account> accounts = accountMapper.findAllByFamily(familyId).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> members = memberDirectory.nameMap(familyId);
        Map<Long, FixedFlowOccurrence> occ = new HashMap<>();
        for (FixedFlowOccurrence o : mapper.findOccurrencesByPeriod(familyId, period.getId())) occ.put(o.getFixedFlowId(), o);

        List<Item> due = new ArrayList<>(), later = new ArrayList<>(), done = new ArrayList<>();
        Set<Long> repayTargets = new HashSet<>();
        Set<Long> loanPending = new LinkedHashSet<>();
        BigDecimal pendingBase = BigDecimal.ZERO;
        BigDecimal totalModeExpense = BigDecimal.ZERO;
        List<String> totalModeParts = new ArrayList<>();

        for (FixedFlow f : all) {
            if (f.isActive() && FixedFlow.REPAY.equals(f.getKind()) && f.getTargetAccountId() != null) {
                repayTargets.add(f.getTargetAccountId());
            }
            FixedFlowOccurrence o = occ.get(f.getId());
            // 停用的只在这期已经有结果时还露面(撤销得找得到它)
            if (!f.isActive() && o == null) continue;
            LocalDate date = o != null ? o.getDueDate() : occurrence(f, period);
            if (date == null) continue;
            if (mineOnly && f.getOwnerMemberId() != null && f.getOwnerMemberId() != memberId) continue;
            Account acc = accounts.get(f.getAccountId());
            Account target = f.getTargetAccountId() == null ? null : accounts.get(f.getTargetAccountId());
            String owner = f.getOwnerMemberId() == null ? "共同" : members.getOrDefault(f.getOwnerMemberId(), "共同");

            if (o != null) {
                done.add(doneItem(f, o, acc, target, owner, open));
                if (totalMode && f.isRepayLoan() && o.getAmount() != null
                        && FixedFlowOccurrence.CONFIRMED.equals(o.getStatus())) {
                    String c = currencyOf(f, acc, target);
                    totalModeExpense = totalModeExpense.add(toBase(familyId, o.getAmount(), c, baseCcy, period.getId()));
                    totalModeParts.add(f.getName() + " " + MoneyFormat.format(c, o.getAmount()));
                }
                continue;
            }
            // 那天 ≤ 今天就算到了;这一期已经过完的(补录期),那天一定早于今天,自然全部算到
            boolean isDue = FixedFlowSchedule.isDue(date, today);
            Item item = pendingItem(familyId, f, period, acc, target, owner, date, isDue, totalMode);
            if (LIST.equals(item.getState())) {
                due.add(item);
            } else if (isDue) {
                due.add(item);
                if (item.getAmount() != null) pendingBase = pendingBase.add(toBase(familyId, item.getAmount(), item.getCcy(), baseCcy, period.getId()));
                if (f.isRepayLoan() && f.getTargetAccountId() != null) loanPending.add(f.getTargetAccountId());
            } else {
                later.add(item);
            }
            if (totalMode && item.getAmount() != null && (FixedFlow.EXPENSE.equals(f.getKind()) || f.isRepayLoan())) {
                BigDecimal exp = item.getAmount();   // 还款 · 贷款月供:还贷 + 利息都是支出
                totalModeExpense = totalModeExpense.add(toBase(familyId, exp, item.getCcy(), baseCcy, period.getId()));
                totalModeParts.add(f.getName() + " " + MoneyFormat.format(item.getCcy(), exp));
            }
        }

        // 贷款建议:没被任何启用中的「还款」指着的贷款,沿用今天那条提示的判据(FR-1120 / 1121)
        int suggest = 0;
        if (open && rows != null) {
            for (EntryRow r : rows) {
                if (!r.isShowLoanPrompt() || r.getAccount() == null) continue;
                Long id = r.getAccount().getId();
                if (repayTargets.contains(id)) continue;
                suggest++;
                loanPending.add(id);
                due.add(Item.builder()
                        .state(SUGGEST).loanAccountId(id).name(r.getAccount().getDisplayName())
                        .kindLabel("建议").kindCss("sug").ownerName(r.ownerName())
                        .ccy(r.getAccount().getCurrency())
                        .suggestLabel(r.getLoanSuggestionLabel()).suggestDeltaLabel(r.getLoanSuggestionDeltaLabel())
                        .keepBalance(r.getPreviousSnapshot() == null ? null : r.getPreviousSnapshot().getEndBalance())
                        .detail("按上两月差值,本月预计还到 " + r.getLoanSuggestionLabel() + "(较上月 " + r.getLoanSuggestionDeltaLabel() + ")")
                        .build());
            }
        }

        due.sort(Comparator.comparing((Item i) -> SUGGEST.equals(i.getState()) ? 2 : LIST.equals(i.getState()) ? 1 : 0)
                .thenComparing(i -> i.getDueDate() == null ? LocalDate.MAX : i.getDueDate()));
        later.sort(Comparator.comparing(Item::getDueDate));
        done.sort(Comparator.comparing(i -> i.getDueDate() == null ? LocalDate.MIN : i.getDueDate()));

        int pending = (int) due.stream().filter(i -> DUE.equals(i.getState())).count();
        int confirmable = (int) due.stream().filter(Item::isConfirmable).count();

        // FR-1116 · 上一期已关账:到了日子却没确认的有几笔(不挪过来,只提一句)
        int missed = 0;
        String lastLabel = null;
        Period prev = previousPeriod(familyId, period);
        if (prev != null && prev.getStatus() == PeriodStatus.CLOSED) {
            Set<Long> has = new HashSet<>(mapper.flowIdsWithOccurrence(familyId, prev.getId()));
            for (FixedFlow f : all) {
                if (has.contains(f.getId())) continue;
                if (totalMode && FixedFlow.EXPENSE.equals(f.getKind())) continue;
                if (f.getDisabledAt() != null && !f.getDisabledAt().toLocalDate().isAfter(prev.getPeriodEnd())) continue;
                if (f.getCreatedAt() != null && f.getCreatedAt().toLocalDate().isAfter(prev.getPeriodEnd())) continue;
                if (occurrence(f, prev) != null) missed++;
            }
            lastLabel = prev.getPeriodStart().getMonthValue() + " 月";
        }

        Item next = later.isEmpty() ? null : later.get(0);
        return Summary.builder()
                .period(period).open(open).totalMode(totalMode)
                .due(due).later(later).done(done)
                .pendingCount(pending).suggestCount(suggest).confirmableCount(confirmable)
                .pendingTotalLabel(MoneyFormat.format(baseCcy, pendingBase))
                .nextUpcoming(next)
                .lastPeriodMissed(missed).lastPeriodLabel(lastLabel)
                .anyFlows(all.stream().anyMatch(FixedFlow::isActive) || !done.isEmpty())
                .totalModeExpenseLabel(totalModeExpense.signum() > 0 ? MoneyFormat.format(baseCcy, totalModeExpense) : null)
                .totalModeBreakdown(String.join(" · ", totalModeParts))
                .loanPendingIds(loanPending)
                .build();
    }

    // ── 单张卡片 ─────────────────────────────────────────────────────────

    private Item pendingItem(long familyId, FixedFlow f, Period period, Account acc, Account target, String owner,
                             LocalDate date, boolean isDue, boolean totalMode) {
        Amount a = amountFor(familyId, f, period, acc, target);
        String ccy = currencyOf(f, acc, target);
        boolean list = totalMode && FixedFlow.EXPENSE.equals(f.getKind());
        boolean balanceOnly = totalMode && f.isRepayLoan();
        String diff = null;
        if (a.result.ok() && !f.isRepayLoan()) {
            List<BigDecimal> last = mapper.recentAmountsBefore(familyId, f.getId(), period.getId(), 1);
            if (!last.isEmpty() && last.get(0) != null) {
                int c = a.result.total().compareTo(last.get(0));
                if (c != 0) {
                    diff = (c > 0 ? "比上次多 " : "比上次少 ") + MoneyFormat.format2(ccy, a.result.total().subtract(last.get(0)).abs());
                }
            }
        }
        Long match = null;
        String matchLabel = null;
        if (isDue && !list && FixedFlow.EXPENSE.equals(f.getKind()) && a.result.ok()) {
            match = mapper.findImportedMatch(familyId, period.getId(), f.getAccountId(), a.result.total()).orElse(null);
            if (match != null) {
                matchLabel = "导入的账单里有一笔 " + MoneyFormat.format2(ccy, a.result.total()) + " 可能就是它";
            }
        }
        String split = null;
        if (f.isRepayLoan() && a.result.ok()) {
            split = a.result.interest() == null
                    ? "还贷(本金)" + MoneyFormat.format2(ccy, a.result.principal())
                    : "还贷(本金)" + MoneyFormat.format2(ccy, a.result.principal()) + " + 利息 " + MoneyFormat.format2(ccy, a.result.interest());
            if (balanceOnly) split = split + " · 支出按「每人一个总数」填,这里只改贷款余额";
        }
        return Item.builder()
                .state(list ? LIST : isDue ? DUE : LATER)
                .flow(f).name(f.getName()).kindLabel(f.isRepayCard() ? "还卡" : f.kindLabel()).kindCss(css(f))
                .ownerName(owner).dueDate(date).dueLabel(date.getDayOfMonth() + " 号")
                .ccy(ccy).amount(a.result.ok() ? a.result.total() : null)
                .amountLabel(a.result.ok() ? MoneyFormat.format2(ccy, a.result.total()) : "算不出来")
                .calc(f.isAuto()).formula(a.result.formula()).problem(a.result.problem())
                .detail(detail(f, acc, target, date)).split(split).diffLabel(diff)
                .editable(!list && a.result.ok())
                .rememberable(!f.isAuto() || FixedFlow.LOAN_RATE.equals(f.getAutoMethod()))
                .balanceOnly(balanceOnly)
                .importMatchId(match).importMatchLabel(matchLabel)
                .build();
    }

    private Item doneItem(FixedFlow f, FixedFlowOccurrence o, Account acc, Account target, String owner, boolean open) {
        String ccy = currencyOf(f, acc, target);
        String result = switch (o.getStatus()) {
            case FixedFlowOccurrence.SKIPPED -> "这期没有";
            case FixedFlowOccurrence.LINKED -> "就是已经记过的那一笔" + (o.getAmount() == null ? "" : " " + MoneyFormat.format2(ccy, o.getAmount()));
            default -> resultText(f, o, acc, target, ccy);
        };
        return Item.builder()
                .state(DONE).flow(f).name(f.getName()).kindLabel(f.isRepayCard() ? "还卡" : f.kindLabel()).kindCss(css(f))
                .ownerName(owner).dueDate(o.getDueDate()).dueLabel(o.getDueDate().getDayOfMonth() + " 号")
                .ccy(ccy).amount(o.getAmount())
                .amountLabel(o.getAmount() == null ? "—" : MoneyFormat.format2(ccy, o.getAmount()))
                .occurrenceId(o.getId()).occurrenceStatus(o.getStatus()).resultText(result).undoable(open)
                .build();
    }

    private static String resultText(FixedFlow f, FixedFlowOccurrence o, Account acc, Account target, String ccy) {
        String an = acc == null ? "?" : acc.getDisplayName();
        String tn = target == null ? "?" : target.getDisplayName();
        if (f.writesTransfer()) return "记成了划转 " + an + " → " + tn + " " + MoneyFormat.format2(ccy, o.getAmount());
        if (f.isRepayLoan()) {
            StringBuilder sb = new StringBuilder();
            if (o.getCashFlowId() != null) {
                sb.append(an).append(" 记了还贷 ").append(MoneyFormat.format2(ccy, o.getPrincipal()));
                if (o.getInterestCashFlowId() != null) sb.append(" + 利息 ").append(MoneyFormat.format2(ccy, o.getInterest()));
            }
            if (o.getLoanBalanceAfter() != null) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(tn).append(" 余额 → ").append(MoneyFormat.format2(target == null ? ccy : target.getCurrency(), o.getLoanBalanceAfter()));
            } else if (o.getCashFlowId() != null) {
                sb.append(" · ").append(tn).append(" 本期余额已经有人填过,没改它");
            }
            return sb.length() == 0 ? "已确认" : sb.toString();
        }
        if (FixedFlow.INCOME.equals(f.getKind())) return "记成了收入 " + MoneyFormat.format2(ccy, o.getAmount()) + " → " + an;
        return "记成了支出 " + MoneyFormat.format2(ccy, o.getAmount()) + " ← " + an;
    }

    private static String detail(FixedFlow f, Account acc, Account target, LocalDate date) {
        String an = acc == null ? "?" : acc.getDisplayName();
        String day = " · " + date.getDayOfMonth() + " 号";
        return switch (f.getKind()) {
            case FixedFlow.INCOME -> "→ " + an + day;
            case FixedFlow.EXPENSE -> "← " + an + day;
            default -> an + " → " + (target == null ? "?" : target.getDisplayName()) + day;
        };
    }

    private static String css(FixedFlow f) {
        return switch (f.getKind()) {
            case FixedFlow.INCOME -> "in";
            case FixedFlow.EXPENSE -> "out";
            case FixedFlow.REPAY -> "loan";
            case FixedFlow.PREMIUM -> "ins";
            default -> "";
        };
    }

    private static String currencyOf(FixedFlow f, Account acc, Account target) {
        // 金额都按「从哪付 / 记到哪」那个账户的币种(划转的 amount 本来就是转出方币种)
        return acc != null ? acc.getCurrency() : target != null ? target.getCurrency() : "CNY";
    }

    // ── 金额:固定 / 五种自动 ────────────────────────────────────────────

    /** 一次计算 + 贷款这边要写的余额(只有还款 · 贷款月供有) */
    public record Amount(FixedFlowCalc.Result result, BigDecimal prevBalance, BigDecimal loanBalanceTarget) {
    }

    /** 这一期这条固定收支该是多少(确认服务与摘要共用同一个算法,FR-1107 卡片上显示的就是确认会写的)。 */
    public Amount amountFor(long familyId, FixedFlow f, Period period, Account acc, Account target) {
        String ccy = currencyOf(f, acc, target);
        if (f.isRepayLoan()) {
            String lccy = target == null ? ccy : target.getCurrency();
            List<PeriodSnapshot> last2 = target == null ? List.of()
                    : snapshotMapper.findLatestBefore(familyId, target.getId(), period.getPeriodStart(), 2);
            BigDecimal prev = last2.isEmpty() ? null : last2.get(0).getEndBalance();
            BigDecimal prevPrev = last2.size() >= 2 ? last2.get(1).getEndBalance() : null;
            FixedFlowCalc.Result r;
            if (!f.isAuto()) {
                r = f.getAmount() == null ? FixedFlowCalc.Result.fail("没填金额")
                        : new FixedFlowCalc.Result(f.getAmount(), f.getAmount(), null, null, null);
            } else if (FixedFlow.LOAN_RATE.equals(f.getAutoMethod())) {
                r = FixedFlowCalc.loanRate(lccy, prev, target == null ? null : target.getAnnualRatePct(), f.getAmount());
            } else {
                r = FixedFlowCalc.loanDiff(lccy, prev, prevPrev);
            }
            BigDecimal targetBalance = null;
            if (r.ok() && prev != null) {
                targetBalance = FixedFlow.LOAN_DIFF.equals(f.getAutoMethod()) && f.isAuto()
                        ? FixedFlowCalc.loanDiffBalance(prev, prevPrev)       // 与「接受」同一个函数(FR-1108)
                        : clampLoan(prev.add(r.principal()));
            }
            return new Amount(r, prev, targetBalance);
        }
        if (!f.isAuto()) {
            FixedFlowCalc.Result r = f.getAmount() == null ? FixedFlowCalc.Result.fail("没填金额")
                    : new FixedFlowCalc.Result(f.getAmount(), null, null, null, null);
            return new Amount(r, null, null);
        }
        return switch (f.getAutoMethod() == null ? "" : f.getAutoMethod()) {
            case FixedFlow.CARD_FULL -> {
                BigDecimal prev = target == null ? null : snapshotMapper.findLatestBefore(familyId, target.getId(), period.getPeriodStart(), 1)
                        .stream().findFirst().map(PeriodSnapshot::getEndBalance).orElse(null);
                yield new Amount(FixedFlowCalc.cardFull(target == null ? ccy : target.getCurrency(), prev), prev, null);
            }
            case FixedFlow.POLICY -> {
                BigDecimal premium = target == null ? null
                        : policyMapper.findByAccount(familyId, target.getId()).map(InsurancePolicy::getPremiumAmount).orElse(null);
                yield new Amount(FixedFlowCalc.policy(ccy, premium), null, null);
            }
            case FixedFlow.AVG3 -> new Amount(FixedFlowCalc.average(ccy,
                    mapper.recentAmountsBefore(familyId, f.getId(), period.getId(), 3), f.getAmount()), null, null);
            default -> new Amount(FixedFlowCalc.Result.fail("不认识的计算方式"), null, null);
        };
    }

    static BigDecimal clampLoan(BigDecimal b) {
        return b.signum() > 0 ? BigDecimal.ZERO.setScale(2) : b.setScale(2, RoundingMode.HALF_EVEN);
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    /** 这条固定收支在这一期里哪天该有(没有 → null) */
    public static LocalDate occurrence(FixedFlow f, Period p) {
        return FixedFlowSchedule.occurrenceIn(f.getCycle(), f.getMonths(), f.getDayOfMonth() == null ? 1 : f.getDayOfMonth(),
                f.getStartMonth() == null ? null : YearMonth.from(f.getStartMonth()),
                f.getEndMonth() == null ? null : YearMonth.from(f.getEndMonth()),
                p.getPeriodStart(), p.getPeriodEnd());
    }

    private Period previousPeriod(long familyId, Period period) {
        return periodMapper.findLatest(familyId, 36).stream()
                .filter(p -> p.getPeriodEnd().isBefore(period.getPeriodStart()))
                .max(Comparator.comparing(Period::getPeriodStart))
                .orElse(null);
    }

    private BigDecimal toBase(long familyId, BigDecimal amount, String ccy, String base, long periodId) {
        if (amount == null) return BigDecimal.ZERO;
        if (ccy == null || base == null || ccy.equalsIgnoreCase(base)) return amount;
        var r = fxService.getOrFetchRate(familyId, ccy, base, periodId);
        if (r.isPresent() && r.get().getRate() != null && r.get().getRate().signum() > 0) {
            return amount.multiply(r.get().getRate()).setScale(2, RoundingMode.HALF_EVEN);
        }
        var inv = fxService.getOrFetchRate(familyId, base, ccy, periodId);
        if (inv.isPresent() && inv.get().getRate() != null && inv.get().getRate().signum() > 0) {
            return amount.divide(inv.get().getRate(), 2, RoundingMode.HALF_EVEN);
        }
        return amount;
    }

}
