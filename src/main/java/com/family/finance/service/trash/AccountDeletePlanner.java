package com.family.finance.service.trash;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.snapshot.SnapshotTodo;
import com.family.finance.domain.snapshot.TodoStatus;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.AccountTrashMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PrincipalAdjustmentMapper;
import com.family.finance.repository.SnapshotTodoMapper;
import com.family.finance.service.MoneyFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * v1.33 · 删除账户的「预演」—— 出一份计划,面板渲染它,删除时重算它、逐字比确认语,执行器只按它改记
 * (tech-design v1.33 选型三 · PRD FR-1211 / 1212)。<b>只读。</b>
 */
@Service
@RequiredArgsConstructor
public class AccountDeletePlanner {

    public static final String PA_IN = "PA_IN";       // 对方收到它转来的 → 改记成对方的补录本金(+)
    public static final String PA_OUT = "PA_OUT";     // 对方转给它的 → 改记成对方的本金移出(−)
    public static final String OPENING = "OPENING";   // 对方那一期是第一期 → 并进它的开账基线,不写

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter YM_COMPACT = DateTimeFormatter.ofPattern("yyyyMM");

    private final AccountMapper accountMapper;
    private final AccountTrashMapper trashMapper;
    private final PeriodMapper periodMapper;
    private final PrincipalAdjustmentMapper principalMapper;
    private final SnapshotTodoMapper todoMapper;
    private final FamilyMapper familyMapper;
    private final FactViewService factViewService;

    /** 一笔牵连的划转 */
    public record TransferImpact(long transferId, Period period, Account counterpart, boolean counterpartReceives,
                                 BigDecimal amount, String mode, String line, String phraseLine) {
        public String periodLabel() { return period.getPeriodStart().format(YM); }
        public String amountLabel() { return MoneyFormat.format2(counterpart.getCurrency(), amount); }
        public String directionLabel() { return counterpartReceives ? "收到它转来" : "转给它"; }
        /** 改记成对方的本金进出那一笔:补录本金 +X / 本金移出 −X(划转金额恒为正,符号只由方向定)*/
        public String rewriteLabel() { return (PA_OUT.equals(mode) ? "−" : "+") + amountLabel(); }
    }

    /** 删前 → 删后 一行 */
    public record Preview(String label, String netWorthBefore, String netWorthAfter, String pnlBefore, String pnlAfter) {
    }

    public record DeletePlan(Account account, List<Period> periods, int closedCount, String rangeLabel,
                             int snapshotCount, int incomeCount, String incomeLabel, int expenseCount, String expenseLabel,
                             int holdingCount, int principalCount, int fixedFlowCount, int transferRowCount,
                             List<TransferImpact> transfers, int counterpartCount, List<Preview> previews,
                             List<String> warnings, List<String> others, String phrase) {
        public List<Long> periodIds() { return periods.stream().map(Period::getId).toList(); }

        /** 「余额 21 期 · 收入 2 笔 ¥(合计) · …」 */
        public String movedSummary() {
            return "余额 " + snapshotCount + " 期 · 收入 " + incomeCount + " 笔 " + incomeLabel
                    + " · 支出 " + expenseCount + " 笔 " + expenseLabel + " · 持仓 " + holdingCount + " 条"
                    + " · 补录本金 " + principalCount + " 笔 · 固定收支 " + fixedFlowCount + " 条";
        }
    }

    public DeletePlan plan(long familyId, long accountId) {
        Account acc = accountMapper.findById(familyId, accountId)
                .filter(a -> a.getFamilyId() == familyId)
                .orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        String ccy = acc.getCurrency();

        // ── 改写的期 ──
        List<Period> periods = new ArrayList<>();
        for (Long pid : trashMapper.affectedPeriodIds(familyId, accountId)) {
            periodMapper.findById(familyId, pid).ifPresent(periods::add);
        }
        periods.sort(Comparator.comparing(Period::getPeriodStart));
        int closed = (int) periods.stream().filter(p -> p.getStatus() == PeriodStatus.CLOSED).count();
        String range = null;
        if (!periods.isEmpty()) {
            String first = periods.get(0).getPeriodStart().format(YM);
            String last = periods.get(periods.size() - 1).getPeriodStart().format(YM);
            range = (first.equals(last) ? first : first + " 至 " + last) + " 共 " + periods.size() + " 个账期";
        }

        // ── 移走的行 ──
        int snapshots = count("period_snapshot", familyId, accountId);
        Map<String, Object> inc = trashMapper.flowTotals(familyId, accountId, "INCOME");
        Map<String, Object> exp = trashMapper.flowTotals(familyId, accountId, "EXPENSE");
        int holdings = count("stock_holding", familyId, accountId);
        int principals = count("principal_adjustment", familyId, accountId);
        int fixedFlows = count("fixed_flow", familyId, accountId);
        int transferRows = count("transfer", familyId, accountId);

        // ── 牵连的划转(逐笔)──
        List<TransferImpact> impacts = new ArrayList<>();
        Set<Long> counterparts = new LinkedHashSet<>();
        for (AccountTrashMapper.TransferRow t : trashMapper.liveTransfers(familyId, accountId)) {
            boolean counterpartReceives = !Objects.equals(t.toAccountId(), accountId);
            long cid = counterpartReceives ? t.toAccountId() : t.fromAccountId();
            Account cp = accountMapper.findById(familyId, cid).orElse(null);
            Period p = periodMapper.findById(familyId, t.periodId()).orElse(null);
            if (cp == null || p == null) continue;
            BigDecimal amount = (counterpartReceives ? t.received() : t.amount()).setScale(2, RoundingMode.HALF_EVEN);
            boolean firstPeriod = principalMapper.findPreviousEndBalance(familyId, cid, p.getPeriodStart()) == null;
            String mode = firstPeriod ? OPENING : counterpartReceives ? PA_IN : PA_OUT;
            String money = MoneyFormat.format2(cp.getCurrency(), amount);
            String action = switch (mode) {
                case OPENING -> cp.getDisplayName() + "这一期第一次出现:并进它的开账基线," + cp.getDisplayName() + "收益不变";
                case PA_IN -> "改记成" + cp.getDisplayName() + "的「补录本金」+" + money + "," + cp.getDisplayName() + "收益不变";
                default -> "改记成" + cp.getDisplayName() + "的「本金移出」−" + money + "," + cp.getDisplayName() + "收益不变";
            };
            String line = p.getPeriodStart().format(YM) + "  " + cp.getDisplayName() + "  "
                    + (counterpartReceives ? "收到它转来 " : "转给它 ") + money + "  → " + action;
            String phraseLine = p.getPeriodStart().format(YM_COMPACT) + " 账期 " + cp.getDisplayName()
                    + " 的转账 " + plain(amount) + " 元也会受到影响";
            impacts.add(new TransferImpact(t.id(), p, cp, counterpartReceives, amount, mode, line, phraseLine));
            counterparts.add(cid);
        }

        // ── 删前 → 删后(事实层:减掉它自己的那一份;对方的数按改记一分不变)──
        List<Preview> previews = new ArrayList<>();
        String base = familyMapper.findById(familyId).map(f -> f.getBaseCurrency()).orElse("CNY");
        try {
            FactSlice slice = factViewService.loadDefault(familyId);
            Map<Long, List<AccountPeriodFact>> byPeriod = slice.byPeriod();
            periodMapper.findLatestClosedAsOf(familyId, LocalDate.now()).ifPresent(p ->
                    preview(previews, byPeriod.get(p.getId()), accountId, base, p.getPeriodStart().format(YM) + "(最近已关账)", true));
            periodMapper.findBalancePeriod(familyId).ifPresent(p ->
                    preview(previews, byPeriod.get(p.getId()), accountId, base, p.getPeriodStart().format(YM) + "(本期)", false));
        } catch (RuntimeException e) {
            // 预演数字只是参考;算不出来不挡删除面板
        }

        // ── 其它会变的 ──
        List<String> warnings = new ArrayList<>();
        List<String> others = new ArrayList<>();
        periodMapper.findBalancePeriod(familyId).ifPresent(cur -> {
            SnapshotTodo td = todoMapper.findByPeriodAndAccount(familyId, cur.getId(), accountId).orElse(null);
            if (td != null && td.getStatus() == TodoStatus.PENDING && todoMapper.countPendingByPeriod(familyId, cur.getId()) == 1) {
                warnings.add("它是本期最后一个没填的账户:删掉后本期就算全部填完,到点会自动关账");
            }
        });
        int planItems = count("rebalance_plan_item", familyId, accountId);
        if (planItems > 0) others.add("再平衡计划里涉及它的 " + planItems + " 条会移走");
        List<String> goals = trashMapper.goalNames(familyId, accountId);
        if (!goals.isEmpty()) others.add("目标" + String.join("", goals.stream().map(g -> "「" + g + "」").toList()) + "解绑它");
        List<String> loans = trashMapper.loansPayingFrom(familyId, accountId).stream()
                .map(id -> accountMapper.findById(familyId, id).map(Account::getDisplayName).orElse("?")).toList();
        if (!loans.isEmpty()) others.add("贷款" + String.join("", loans.stream().map(n -> "「" + n + "」").toList()) + "的默认还款来源会清空");
        if (fixedFlows > 0) others.add("用到它的 " + fixedFlows + " 条固定收支一起移走");
        if (closed > 0) others.add("AI 月度复盘(涉及的 " + closed + " 个已关账月份的存稿)和 AI 调仓建议会重新生成");

        // ── 确认语 ──
        StringBuilder phrase = new StringBuilder("我知晓删除「").append(acc.getDisplayName()).append("」");
        if (range != null) phrase.append("会改写 ").append(range).append(",");
        for (TransferImpact ti : impacts) phrase.append('\n').append(ti.phraseLine());

        return new DeletePlan(acc, periods, closed, range, snapshots,
                num(inc.get("n")), MoneyFormat.format2(ccy, dec(inc.get("total"))),
                num(exp.get("n")), MoneyFormat.format2(ccy, dec(exp.get("total"))),
                holdings, principals, fixedFlows, transferRows, impacts, counterparts.size(), previews,
                warnings, others, phrase.toString());
    }

    /** 统一换行、去掉每行行尾空白和空行之后再比(PRD FR-1212「只忽略行尾空格和换行符的差别」) */
    public static String normalizePhrase(String s) {
        if (s == null) return "";
        List<String> lines = new ArrayList<>();
        for (String l : s.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String t = l.stripTrailing();
            if (!t.isEmpty()) lines.add(t);
        }
        return String.join("\n", lines);
    }

    public static boolean phraseMatches(String expected, String typed) {
        return normalizePhrase(expected).equals(normalizePhrase(typed));
    }

    private void preview(List<Preview> out, List<AccountPeriodFact> rows, long accountId, String base, String label, boolean withPnl) {
        if (rows == null || rows.isEmpty()) return;
        BigDecimal nw = BigDecimal.ZERO, pnl = BigDecimal.ZERO, mineNw = BigDecimal.ZERO, minePnl = BigDecimal.ZERO;
        for (AccountPeriodFact f : rows) {
            BigDecimal b = f.endBalanceBase() == null ? BigDecimal.ZERO : f.endBalanceBase();
            BigDecimal p = f.periodPnlBase() == null ? BigDecimal.ZERO : f.periodPnlBase();
            nw = nw.add(b);
            pnl = pnl.add(p);
            if (Objects.equals(f.accountId(), accountId)) {
                mineNw = mineNw.add(b);
                minePnl = minePnl.add(p);
            }
        }
        out.add(new Preview(label, MoneyFormat.format(base, nw), MoneyFormat.format(base, nw.subtract(mineNw)),
                withPnl ? MoneyFormat.formatDelta(base, pnl) : null, withPnl ? MoneyFormat.formatDelta(base, pnl.subtract(minePnl)) : null));
    }

    private int count(String table, long familyId, long accountId) {
        return AccountTrashSpec.TABLES.stream().filter(t -> t.table().equals(table)).findFirst()
                .map(t -> trashMapper.countRows(t.table(), t.where(), familyId, accountId)).orElse(0);
    }

    private static String plain(BigDecimal amount) {
        return new DecimalFormat("#,##0.00").format(amount);
    }

    private static int num(Object o) { return o == null ? 0 : ((Number) o).intValue(); }

    private static BigDecimal dec(Object o) {
        if (o == null) return BigDecimal.ZERO;
        return o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }
}
