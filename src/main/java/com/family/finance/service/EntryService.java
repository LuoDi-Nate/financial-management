package com.family.finance.service;

import com.family.finance.calc.ReconciliationCalculator;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.audit.AuditLogType;
import com.family.finance.domain.flow.CashFlow;
import com.family.finance.domain.flow.CashFlowKind;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.snapshot.SnapshotTodo;
import com.family.finance.domain.snapshot.TodoStatus;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.StockValuationEvent;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.domain.transfer.Transfer;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.SnapshotTodoMapper;
import com.family.finance.repository.StockValuationEventMapper;
import com.family.finance.repository.TransferMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class EntryService {

    /** 单家庭模式 · 见 prd §22.3 类 A */
    private static final long FAMILY_ID = 1L;

    /**
     * v1.19.3 · 「偿还负债」性质的支出类目 —— <b>不允许记在负债账户自己身上</b>。
     *
     * <p>放开信用卡记支出之后冒出来的新风险是双计:刷卡时在信用卡账户记一笔「消费」,
     * 月底还款时又在现金账户记一笔「还贷」,同一笔钱进了两次本月支出。这两个类目描述的都是
     * 「把欠款还掉」,记在负债账户上还意味着「用这张卡还这张卡」,本身就无意义。</p>
     *
     * <p>code 来自 {@code V2__seed.sql} 的内置四类目;这里硬编码是有意的 ——
     * 类目表是用户可见的运营数据,但这两条的<b>语义</b>是记账口径的一部分,
     * 不该随类目表被改名/停用而悄悄失效。有护栏 {@code v1193-EXPENSE-LIABILITY-CAT} 钉住。</p>
     */
    private static final java.util.Set<String> REPAYMENT_CATEGORIES =
            java.util.Set.of("loan_payment", "interest_paid");

    /**
     * v1.19.3 · 这个类目能不能记在这种账户上。
     *
     * <p>只有一条规则:<b>负债账户上不记「偿还负债」</b>。其余组合一律放行 —— 包括
     * 「信用卡 + 消费」(这正是本版要解开的那个),也包括「现金 + 还贷」(那本来就是对的记法)。</p>
     *
     * <p>提成静态谓词是为了能被直接单测:这条规则的反面是<b>本月支出凭空翻倍</b>,
     * 而那种错在报表上看着完全正常(数字是真的,只是被算了两次),靠肉眼复核发现不了。</p>
     */
    static boolean expenseCategoryAllowedOn(com.family.finance.domain.account.AccountType type, String categoryCode) {
        if (type == null || !type.isLiability()) return true;
        // categoryCode 为 null 时放行,交给 requireExpenseCategory 去报「类目不存在」——
        // 那才是这种情况的准确错误。注意 Set.of(...) 的 contains(null) 会抛 NPE(不可变集合
        // 不接受 null 查询),所以这里必须先挡一道,不能直接丢进去。
        if (categoryCode == null) return true;
        return !REPAYMENT_CATEGORIES.contains(categoryCode);
    }


    private final AccountMapper accountMapper;
    /** v1.15 FR-382 · 名字映射走名录(含已归档)—— 归档一个人,不该让历史数据里的他变成无名氏 */
    private final MemberDirectory memberDirectory;
    private final PeriodMapper periodMapper;
    private final SnapshotMapper snapshotMapper;
    private final SnapshotTodoMapper snapshotTodoMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher; // v1.1.1 lens 缓存失效事件
    private final CashFlowMapper cashFlowMapper;
    private final TransferMapper transferMapper;
    private final AuditLogService auditLogService;
    private final com.family.finance.service.config.FamilyConfigService configService;
    /** v0.4.1 FR-52f · 股票估值事件 · ledger 显示 */
    private final StockValuationEventMapper stockValuationEventMapper;
    /** v0.12 · 收入侧:类目↔账户类型校验 + 股票收入落 CASH 现金行 */
    private final com.family.finance.repository.CashFlowCategoryMapper cashFlowCategoryMapper;
    private final com.family.finance.service.stock.StockHoldingService stockHoldingService;
    /** v1.18.5 · 手填余额落托管账户时要知道「持仓+现金」当前算出来是多少(只读估值) */
    private final com.family.finance.service.stock.AccountValuationService valuationService;
    private final com.family.finance.service.expense.ExpenseCategoryService expenseCategoryService;  // v1.21
    /** v1.30 · 补录本金在本账户视角下是「已知流入」—— 不然补录完,现金账户这一期会冒出一条未解释差额 */
    private final com.family.finance.repository.PrincipalAdjustmentMapper principalAdjustmentMapper;

    public Optional<Period> findSelectedPeriod(long familyId, String periodParam) {
        if (periodParam == null || periodParam.isBlank()) {
            // v1.23 FR-625 · 双活跃窗口里默认落**补录期**(已自然结束、宽限内仍可写的那期)。
            //
            //   理由是概率:宽限窗口只有 2–5 天,用户在这几天里打开填报页,八成就是为了
            //   填上个月的账单 —— 默认值该服从这个概率,而不是服从「取最新的那个」这条机械规则。
            //   不在窗口里时(T+0 家庭、或宽限已过)没有「已结束的 OPEN 期」,
            //   下面第一条自然落空,行为与 v1.22 完全一致。
            java.time.LocalDate today = java.time.LocalDate.now();
            List<Period> open = periodMapper.findRecordableOpen(familyId);
            return open.stream()
                    .filter(p -> p.getPeriodEnd() != null && p.getPeriodEnd().isBefore(today))
                    .findFirst()
                    .or(() -> open.isEmpty() ? Optional.empty() : Optional.of(open.getLast()))
                    .or(() -> periodMapper.findLatest(familyId, 1).stream().findFirst());
        }
        if (periodParam.matches("\\d+")) {
            return periodMapper.findById(familyId, Long.parseLong(periodParam));
        }
        if (periodParam.matches("\\d{4}-\\d{2}")) {
            int year = Integer.parseInt(periodParam.substring(0, 4));
            int month = Integer.parseInt(periodParam.substring(5, 7));
            return periodMapper.findLatest(familyId, 36).stream()
                    .filter(p -> p.getPeriodStart().getYear() == year && p.getPeriodStart().getMonthValue() == month)
                    .findFirst();
        }
        return Optional.empty();
    }

    /** Thymeleaf SpEL 调用绕过 record accessor 在某些 fragment 嵌套场景下返回 null 的问题。 */
    public static List<EntryRow.LedgerEntry> safeLedger(EntryRow row) {
        return row == null || row.ledger() == null ? List.of() : row.ledger();
    }

    /** 触发源中文化 · 给 ledger 显示用 */
    private static String triggerCn(String triggerKind) {
        if (triggerKind == null) return "自动";
        return switch (triggerKind) {
            case "CRON" -> "自动(定时)";
            case "MANUAL" -> "手动刷价";
            case "HOLDING_CHANGE" -> "持仓变动";
            default -> "自动";
        };
    }

    public List<EntryRow> listRows(long familyId, long memberId, Period period, boolean mineOnly) {
        List<Account> accounts = accountMapper.findActiveByFamily(familyId).stream()
                .filter(a -> !mineOnly
                        || a.getPrimaryOwnerMemberId() == null
                        || a.getPrimaryOwnerMemberId() == memberId)
                .toList();
        Map<Long, Account> allById = accountMapper.findAllByFamily(familyId).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Map<Long, Member> members = memberDirectory.listAll(familyId).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        Map<Long, PeriodSnapshot> current = snapshotMapper.findByPeriod(familyId, period.getId()).stream()
                .collect(Collectors.toMap(PeriodSnapshot::getAccountId, Function.identity()));
        Map<Long, SnapshotTodo> todos = snapshotTodoMapper.findByPeriod(familyId, period.getId()).stream()
                .collect(Collectors.toMap(SnapshotTodo::getAccountId, Function.identity()));

        return accounts.stream()
                .map(account -> toRow(account, members, allById, current.get(account.getId()), todos.get(account.getId()), period))
                .sorted(Comparator.comparingInt(r -> Optional.ofNullable(r.account().getDisplayOrder()).orElse(0)))
                .toList();
    }

    public EntryRow rowFor(long familyId, long memberId, long periodId, long accountId) {
        Period period = periodMapper.findById(familyId, periodId)
                .filter(p -> p.getFamilyId() == familyId)
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        Account account = requireAccount(familyId, accountId);
        Map<Long, Member> members = memberDirectory.listAll(familyId).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        Map<Long, Account> allById = accountMapper.findAllByFamily(familyId).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        PeriodSnapshot current = snapshotMapper.findByPeriodAndAccount(familyId, periodId, accountId).orElse(null);
        SnapshotTodo todo = snapshotTodoMapper.findByPeriodAndAccount(familyId, periodId, accountId).orElse(null);
        return toRow(account, members, allById, current, todo, period);
    }

    @Transactional
    public EntryRow submitBalance(long familyId,
                                  long memberId,
                                  long periodId,
                                  long accountId,
                                  BigDecimal newBalance,
                                  List<CashFlowLine> cashFlowLines,
                                  List<TransferLine> transferLines,
                                  String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireAccount(familyId, accountId);
        BigDecimal normalizedBalance = normalizeBalance(account, newBalance);
        boolean overwriting = snapshotMapper.findByPeriodAndAccount(familyId, periodId, accountId).isPresent();

        // ── v1.18.5 · 手填余额落到「持仓托管」账户时,差额要记进现金行 ──────────────
        //   不这么做的话,用户敲的数会被下一次自动估值按「持仓合计」重算抹掉 ——
        //   生产实测:8-21 14:42 手填 451,497.63,8-21 16:10 CRON 估值写回 375,248.71,
        //   delta −76,248.92,那笔钱又没了。而这是【第三个变种】:
        //     v1.18.1 修的是"划转/收入进托管账户"、v1.18.3 加的写回拦截只认"流水",
        //     手填余额既不是流水、也不动持仓 —— 正好从两道防线中间漏过去。
        //   修法与前两次同源:用户说「这个账户现在有 X」,就把 X 与(持仓 + 现金)的差额
        //   记成现金行。下一次估值重算 = 持仓 + 现金 = X,他敲的数就站得住了。
        //   语义上这正是「校准」该有的样子:说不清的那部分是现金,而且在持仓页看得见、可改。
        if (stockHoldingService.valuationManaged(account)) {
            try {
                BigDecimal recomputed = valuationService.valuate(familyId, accountId).totalBaseValue();
                BigDecimal diff = normalizedBalance.subtract(recomputed).setScale(2, RoundingMode.HALF_EVEN);
                if (diff.signum() != 0) {
                    stockHoldingService.adjustAccountCash(familyId, accountId, account.getCurrency(), diff);
                    auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", accountId,
                            "手填余额校准 · 与持仓合计的差额 " + money(diff) + " 已记入现金行(否则会被下次估值抹掉)");
                }
            } catch (Exception e) {
                // 校准失败不该把「填余额」这个主流程搞挂;但要留一行,不能静默
                log.warn("手填余额校准现金行失败 · account={} · 余额已按填写值保存,但可能被下次估值覆盖: {}",
                        accountId, e.toString());
            }
        }

        snapshotMapper.upsertOwned(familyId, PeriodSnapshot.builder()
                .periodId(periodId)
                .accountId(accountId)
                .endBalance(normalizedBalance)
                .submittedBy(memberId)
                .note(blankToNull(note))
                .sourceTag(com.family.finance.domain.ledger.LedgerSource.MANUAL.name())   // v1.18 · 用户在填报页敲的数
                .build());
        // v1.23 FR-623 · 手填的也要传导 —— 用户在宽限期内直接改补录期的期末余额,
        //   进行期那张「开账延续」的快照同样过期了。判据与守门见 propagateCarriedForward。
        propagateCarriedForward(period, accountId, normalizedBalance, memberId);

        for (CashFlowLine line : cashFlowLines == null ? List.<CashFlowLine>of() : cashFlowLines) {
            insertCashFlow(period, account, memberId, line);
        }
        for (TransferLine line : transferLines == null ? List.<TransferLine>of() : transferLines) {
            insertTransfer(period, familyId, accountId, line.toAccountId(), line.amount(), line.toAmount(), line.note(), memberId, false);
        }

        adjustLoanDraft(period, account, normalizedBalance, memberId);
        snapshotTodoMapper.markDone(familyId, periodId, accountId, memberId);

        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", accountId,
                overwriting ? "覆盖余额快照" : "提交余额快照");
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新

        EntryRow row = rowFor(familyId, memberId, periodId, accountId);
        // v1.18.5 · 收口到具名谓词:这里问的是「余额变化该不该被流水解释」,不是「是不是这两个类型」
        if (account.getType().expectsFlowsToExplainBalance()
                && row.unexplained() != null
                && row.unexplained().compareTo(new BigDecimal("0.00")) != 0) {
            auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", accountId,
                    "余额轧差未解释: " + row.unexplainedLabel());
        }
        return row;
    }

    /**
     * v0.17.x · 接受贷款趋势预测:把该贷款本期余额设为 predicted(prev+Δ 夹≤0),并<b>复刻旧逻辑</b>
     * 起草一笔还款转账(默认还款来源 → 贷款,金额 = 本期还款额),标 todo done。
     * 与旧 PeriodOpener.applyLoanPrefill 做的事完全一致,只是由用户点击触发而非开账静默执行。
     */
    @Transactional
    public EntryRow acceptLoanPrediction(long familyId, long memberId, long periodId, long accountId) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account loan = requireAccount(familyId, accountId);
        if (loan.getType() != AccountType.LOAN) {
            throw new IllegalArgumentException("仅贷款账户支持趋势预填");
        }
        List<PeriodSnapshot> last2 = snapshotMapper.findLatestBefore(familyId, accountId, period.getPeriodStart(), 2);
        if (last2.isEmpty()) {
            throw new IllegalStateException("无历史余额,无法预测");
        }
        BigDecimal prev = last2.get(0).getEndBalance();
        BigDecimal prevPrev = last2.size() >= 2 ? last2.get(1).getEndBalance() : null;
        BigDecimal predicted = PeriodOpener.predictLoanBalance(prev, prevPrev);

        snapshotMapper.upsertOwned(familyId, PeriodSnapshot.builder()
                .periodId(periodId)
                .accountId(accountId)
                .endBalance(predicted)
                .submittedBy(memberId)
                .note("按上两月趋势预测本期还款")
                // v1.18 · 数字是系统按趋势算的,人只点了"接受"
                .sourceTag(com.family.finance.domain.ledger.LedgerSource.SYSTEM_ADJUST.name())
                .build());

        // 复刻旧逻辑:草稿还款转账(默认还款来源 → 贷款,金额 = predicted − prev,>0 才起草)
        BigDecimal repay = predicted.subtract(prev);
        if (loan.getDefaultPaymentSourceAccountId() != null && repay.signum() > 0) {
            transferMapper.insertOwned(familyId, com.family.finance.domain.transfer.Transfer.builder()
                    .periodId(periodId)
                    .fromAccountId(loan.getDefaultPaymentSourceAccountId())
                    .toAccountId(accountId)
                    .amount(repay)
                    .occurredAt(period.getPeriodEnd())
                    .note("按上两月趋势预填还款")
                    .submittedBy(memberId)
                    // v1.18 · 金额是系统按趋势算出来的,人只是点了"接受" → 算系统联动,不算手动填报
                    .sourceTag(com.family.finance.domain.ledger.LedgerSource.SYSTEM_ADJUST.name())
                    .draft(true)
                    .build());
        }

        snapshotTodoMapper.markDone(familyId, periodId, accountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", accountId,
                "接受贷款趋势预测 " + MoneyFormat.format(loan.getCurrency(), predicted));
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新

        return rowFor(familyId, memberId, periodId, accountId);
    }

    @Transactional
    public EntryRow addCashFlow(long familyId,
                                long memberId,
                                long periodId,
                                long accountId,
                                CashFlowKind kind,
                                String categoryCode,
                                BigDecimal amount,
                                String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireAccount(familyId, accountId);
        BigDecimal delta = kind == CashFlowKind.INCOME ? amount : amount.negate();
        creditAccountBalance(familyId, period, account, memberId, delta,
                (kind == CashFlowKind.INCOME ? "+收入 " : "-支出 ") + money(amount));
        insertCashFlow(period, account, memberId, new CashFlowLine(kind, categoryCode, amount, note));
        // v0.2 bug 修(2026-05-10): cash_flow 路径必须把 todo 标 DONE,
        // 否则 forceClose 会因 PENDING 把"上期末"覆盖回 snapshot,丢失真实数据
        snapshotTodoMapper.markDone(familyId, periodId, accountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", accountId,
                "新增现金流 " + kind + " " + money(amount));
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新

        return rowFor(familyId, memberId, periodId, accountId);
    }

    /**
     * v0.12 FR-140/141/143/144/147 · 收入侧结构化录入:一笔收入 = 金额 + 类目 + 目标账户。
     * 校验类目↔账户类型(服务端红线);写 cash_flow(INCOME · is_adjustment=0 · 真实外部流入,被 PnL 剔除)
     * + 入账(股票账户落 CASH 现金行,扛过估值刷新)+ 标 todo done。
     */
    @Transactional
    public EntryRow recordIncome(long familyId, long memberId, long periodId,
                                 long accountId, String categoryCode, BigDecimal amount, String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireAccount(familyId, accountId);
        if (account.getType().isLiability()) {
            throw new IllegalArgumentException("负债账户不能录入收入");
        }
        var cat = requireIncomeCategoryForAccount(categoryCode, account);
        BigDecimal amt = positiveMoney(amount);
        creditAccountBalance(familyId, period, account, memberId, amt,
                "+收入 " + cat.getDisplayName() + " " + money(amt));
        insertCashFlow(period, account, memberId, new CashFlowLine(CashFlowKind.INCOME, categoryCode, amt, note));
        snapshotTodoMapper.markDone(familyId, periodId, accountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", accountId,
                "收入录入 " + cat.getDisplayName() + " " + money(amt) + " → " + account.getDisplayName());
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新
        return rowFor(familyId, memberId, periodId, accountId);
    }

    /**
     * v1.8 FR-270 · 支出侧结构化录入:一笔支出 = 金额 + 类目 + 支出账户。与 recordIncome 同构,
     * 只是余额方向相反(从所选账户**扣**掉)。写 cash_flow(EXPENSE · is_adjustment=0 · 口径 A
     * 的家庭支出)+ 扣账 + 标 todo done。
     *
     * <p>⚠ FR-274 · 与收入侧同源的坑:录一笔支出会从账户余额扣掉。如果用户**已经**照银行 App
     * 填了月末余额(那个数本就含这笔消费),再录这笔就会扣第二次。所以填报页文案统一为
     * 「先录收支,最后核对余额」,且每笔录入后要让用户看见余额被改了。</p>
     *
     * <p><b>v1.19.3 · 负债账户从「整类禁止」改为「按类目禁止」。</b>此前这里拦掉所有负债账户,
     * 理由是「在贷款账户上记一笔支出等于又借了一笔,不是花钱」。那对房贷/车贷成立,但它默认了
     * 「借钱」和「花钱」互斥 —— <b>信用卡打破这个前提</b>:刷卡消费同时就是花钱、也是负债增加,
     * 是同一个动作。而 {@code AccountType} 里没有信用卡类型,信用卡只能录成 LOAN,于是被连坐,
     * <b>用户根本没法给信用卡记消费</b>(线上反馈)。</p>
     *
     * <p>余额方向不用特判:负债余额存的是负数({@link #normalizeBalance}),
     * {@link #applyDeltaToBalance} 只做 {@code base.add(delta)},所以下面那笔 {@code amt.negate()}
     * 落到信用卡上正好是「欠款变多」。</p>
     *
     * <p>放开后真正的风险是<b>支出双计</b>:刷卡 3000 记一笔「消费」,月底还款 3000 再记一笔
     * 「还贷」,本月支出就成了 6000。所以负债账户上禁掉 {@code loan_payment} / {@code interest_paid}
     * —— 这两笔本来就该记在<b>钱实际流出的那个现金账户</b>上;负债余额的下降由账户间划转
     * 或期末余额体现,不该再走支出。</p>
     */
    @Transactional
    public EntryRow recordExpense(long familyId, long memberId, long periodId,
                                  long accountId, String categoryCode, BigDecimal amount, String note) {
        return recordExpense(familyId, memberId, periodId, accountId, categoryCode, amount, note, null);
    }

    /** v1.21 FR-550 · 带消费分类的支出录入。分类为 null / 非本家庭 → 落「未分类」,不拦截提交(FR-554)。 */
    public EntryRow recordExpense(long familyId, long memberId, long periodId,
                                  long accountId, String categoryCode, BigDecimal amount, String note,
                                  Long expenseCategoryId) {
        return recordExpense(familyId, memberId, periodId, accountId, categoryCode, amount, note,
                expenseCategoryId, true);
    }

    /**
     * v1.21 FR-571 · {@code affectsBalance=false} = 「只记花了多少、花在哪」,<b>不动账户余额</b>。
     *
     * <p>为什么要这个开关:{@link #applyDeltaToBalance} <b>直接改写</b>
     * {@code period_snapshot.end_balance} —— 那是用户自己填的期末余额,不是预填值。
     * 已经核对完余额的人再记一笔(或导一批账单),余额会被<b>扣第二遍</b>。</p>
     *
     * <p>关掉之后这笔仍然计入<b>家庭消费</b>与<b>支出构成</b> —— 钱确实花了;
     * 只是不参与这个账户的余额解释与资金流出(见 V59 里那段口径说明)。</p>
     */
    public EntryRow recordExpense(long familyId, long memberId, long periodId,
                                  long accountId, String categoryCode, BigDecimal amount, String note,
                                  Long expenseCategoryId, boolean affectsBalance) {
        return recordExpense(familyId, memberId, periodId, accountId, categoryCode, amount, note,
                expenseCategoryId, affectsBalance, false);
    }

    /**
     * v1.24 FR-613 · 带「这笔是一次性的」勾的录入(<b>写入路 W1</b>)。
     *
     * <p>{@code oneOff} 是<b>纯分析期标记</b>:它只决定这笔进不进常态月均,
     * 余额 / 轧差 / 净资产一条都不碰。默认 false —— 绝大多数笔不是一次性的,
     * 每次录入都要判断一下的话,10 分钟/月 的硬约束就守不住了。</p>
     *
     * <p>逐笔只能勾成「一次性」,不能反向把一次性类目里的笔改回弹性(FR-614)。</p>
     */
    public EntryRow recordExpense(long familyId, long memberId, long periodId,
                                  long accountId, String categoryCode, BigDecimal amount, String note,
                                  Long expenseCategoryId, boolean affectsBalance, boolean oneOff) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireAccount(familyId, accountId);
        if (!expenseCategoryAllowedOn(account.getType(), categoryCode)) {
            throw new IllegalArgumentException("负债账户「" + account.getDisplayName()
                    + "」上不能记「还贷 / 利息支出」· 这两笔要记在钱实际流出的现金账户上,"
                    + "否则会和这张卡上的消费重复计入本月支出");
        }
        // 已归档账户必须拦住:全站统计(事实表 / 支出构成 / 月均支出)都按 archived_at IS NULL 排除归档账户,
        // 一旦让支出落进去,这笔钱在**所有**口径里都看不见 —— 是静默丢数据,比看得见的错更糟。
        // 填报页下拉本来就只列未归档账户(findActiveByFamily),但服务端不能只靠前端。
        if (account.getArchivedAt() != null) {
            throw new IllegalArgumentException("账户「" + account.getDisplayName()
                    + "」已归档,不能再记支出 · 归档账户不参与任何统计,记进去的钱会在报表里消失");
        }
        var cat = requireExpenseCategory(categoryCode);
        BigDecimal amt = expenseMoney(amount);
        if (affectsBalance) {
            creditAccountBalance(familyId, period, account, memberId, amt.negate(),
                    "-支出 " + cat.getDisplayName() + " " + money(amt));
        }
        Long catId = expenseCategoryService.isUsable(familyId, expenseCategoryId) ? expenseCategoryId : null;
        insertCashFlow(period, account, memberId,
                new CashFlowLine(CashFlowKind.EXPENSE, categoryCode, amt, note), catId, affectsBalance, oneOff);
        snapshotTodoMapper.markDone(familyId, periodId, accountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", accountId,
                "支出录入 " + cat.getDisplayName() + " " + money(amt) + " ← " + account.getDisplayName());
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // 透视缓存后台换新
        return rowFor(familyId, memberId, periodId, accountId);
    }

    /**
     * 校验支出类目存在且 kind=EXPENSE。
     * 支出类目都不绑定 account_type(见 FR-270a 的 4 个类目),所以不做类目↔账户类型校验;
     * 但要挡住 {@code cash_adjust}(kind=BOTH)—— 那是余额对账用的现金调整,不是家庭支出,
     * 混进来会让「支出构成」里冒出一条用户没花的钱。
     */
    private com.family.finance.domain.flow.CashFlowCategory requireExpenseCategory(String categoryCode) {
        var cat = cashFlowCategoryMapper.findByCode(categoryCode)
                .orElseThrow(() -> new IllegalArgumentException("支出类目不存在: " + categoryCode));
        if (!"EXPENSE".equals(cat.getKind())) {
            throw new IllegalArgumentException("类目「" + cat.getDisplayName() + "」不是支出类目");
        }
        return cat;
    }

    /** 校验收入类目存在 + 是 INCOME + 绑定账户类型与目标账户一致(NULL=不限)· 返回类目。 */
    private com.family.finance.domain.flow.CashFlowCategory requireIncomeCategoryForAccount(
            String categoryCode, Account account) {
        var cat = cashFlowCategoryMapper.findByCode(categoryCode)
                .orElseThrow(() -> new IllegalArgumentException("收入类目不存在: " + categoryCode));
        if (!"INCOME".equals(cat.getKind())) {
            throw new IllegalArgumentException("类目「" + cat.getDisplayName() + "」不是收入类目");
        }
        // FR-147 服务端红线:类目绑定的账户类型必须与目标账户一致 · 防「工资录进股票 / 股息录进现金」
        if (cat.getAccountType() != null && !cat.getAccountType().equals(account.getType().name())) {
            throw new IllegalArgumentException("类目「" + cat.getDisplayName() + "」只能录入 "
                    + cat.getAccountType() + " 类账户,不能录入" + account.getType() + "账户");
        }
        return cat;
    }

    /**
     * v0.12 FR-144/150 · 股票收入 · 归属到「已有持仓」+股数(上市 AUTO / 未上市 MANUAL)。
     * 计值:上市按最新已知价 × FX、未上市按持仓单股估值;value = addShares × 单价(账户币种)。
     * shares += addShares(持久)+ applyDeltaToBalance(+value) 立即入账;记 cash_flow(INCOME · is_adjustment=0
     * · ref_holding_id/ref_shares 供删除冲回)。外部流入不进 PnL → 收益率不虚高。
     */
    @Transactional
    public EntryRow recordStockIncomeExistingHolding(long familyId, long memberId, long periodId,
                                                     long accountId, long holdingId, BigDecimal addShares,
                                                     String categoryCode, String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireStockAccount(familyId, accountId);
        var cat = requireIncomeCategoryForAccount(categoryCode, account);
        StockHolding h = stockHoldingService.require(familyId, holdingId);
        if (!h.getAccountId().equals(accountId)) {
            throw new IllegalArgumentException("持仓不属于该账户");
        }
        if (h.getValuationMode() != ValuationMode.AUTO && h.getValuationMode() != ValuationMode.MANUAL) {
            throw new IllegalArgumentException("仅上市/未上市持仓可按股数录收入(券商现金请用现金入账)");
        }
        // v1.30 · 基金份额变化(定投、赎回)不是收入 —— 记成收入会让「人赚」虚高(护栏 v130-STOCK-INCOME-NO-FUND)
        if (h.isNavRow()) {
            throw new IllegalArgumentException("基金的份额变化不是收入,请在持仓页「改份额」里改");
        }
        BigDecimal shares = positiveShares(addShares);
        BigDecimal unit = stockHoldingService.currentUnitValueInAccountCcy(familyId, h);
        if (unit == null) {
            throw new IllegalArgumentException("该持仓暂无价格,请先在持仓页刷新股价后再录入");
        }
        BigDecimal value = shares.multiply(unit).setScale(2, RoundingMode.HALF_EVEN);
        stockHoldingService.addShares(familyId, holdingId, shares);
        return finishStockShareIncome(familyId, memberId, period, account, cat.getDisplayName(),
                categoryCode, value, holdingId, shares,
                shareIncomeNote(shares, h.getDisplayName(), note));
    }

    /** v0.12 · 股票收入 · 新建「未上市」持仓入账(名称 + 股数 + 单股估值)。 */
    @Transactional
    public EntryRow recordStockIncomeNewManual(long familyId, long memberId, long periodId,
                                               long accountId, String displayName, BigDecimal shares,
                                               BigDecimal unitValue, String categoryCode, String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireStockAccount(familyId, accountId);
        var cat = requireIncomeCategoryForAccount(categoryCode, account);
        BigDecimal sh = positiveShares(shares);
        BigDecimal unit = positiveUnitValue(unitValue);   // issue#3:单股估值保高精度,不像金额那样压成 2 位
        StockHolding h = stockHoldingService.createManual(familyId, accountId, displayName, sh, unit);
        BigDecimal value = sh.multiply(unit).setScale(2, RoundingMode.HALF_EVEN);
        return finishStockShareIncome(familyId, memberId, period, account, cat.getDisplayName(),
                categoryCode, value, h.getId(), sh, shareIncomeNote(sh, h.getDisplayName(), note));
    }

    /**
     * v0.12 · 股票收入 · 新建「上市」持仓入账(代码 + 市场 + 股数);按最新已知价计值。
     * 调用方(controller)应先 fetchMarket 确保有价;无价则抛错提示先刷价。收入非买入 → 不扣现金。
     */
    @Transactional
    public EntryRow recordStockIncomeNewAuto(long familyId, long memberId, long periodId,
                                             long accountId, String displayName, String ticker, Market market,
                                             BigDecimal shares, String currency, String categoryCode, String note) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account account = requireStockAccount(familyId, accountId);
        var cat = requireIncomeCategoryForAccount(categoryCode, account);
        BigDecimal sh = positiveShares(shares);
        StockHolding h = stockHoldingService.createAuto(familyId, accountId, displayName, ticker, market,
                sh, null, currency, false);
        BigDecimal unit = stockHoldingService.currentUnitValueInAccountCcy(familyId, h);
        if (unit == null) {
            throw new IllegalArgumentException("该股票暂无价格,请稍后在持仓页刷新股价后再录入");
        }
        BigDecimal value = sh.multiply(unit).setScale(2, RoundingMode.HALF_EVEN);
        return finishStockShareIncome(familyId, memberId, period, account, cat.getDisplayName(),
                categoryCode, value, h.getId(), sh, shareIncomeNote(sh, h.getDisplayName(), note));
    }

    /** 股票 +股数收入的收尾:立即入账(applyDelta)+ 记 cash_flow(带 ref 供冲回)+ 标 todo + 审计。 */
    private EntryRow finishStockShareIncome(long familyId, long memberId, Period period, Account account,
                                            String catLabel, String categoryCode, BigDecimal value,
                                            long holdingId, BigDecimal shares, String note) {
        applyDeltaToBalance(period, account, memberId, value,
                "+收入 " + catLabel + " " + money(value));
        cashFlowMapper.insertOwned(familyId, CashFlow.builder()
                .periodId(period.getId())
                .accountId(account.getId())
                .kind(CashFlowKind.INCOME)
                .categoryCode(categoryCode)
                .amount(value)
                .occurredAt(period.getPeriodEnd())
                .note(blankToNull(note))
                .submittedBy(memberId)
                .adjustment(false)
                .refHoldingId(holdingId)
                .refShares(shares)
                .sourceTag(com.family.finance.domain.ledger.LedgerSource.MANUAL.name())   // v1.18 · 人在填报页填的股数
                .build());
        snapshotTodoMapper.markDone(familyId, period.getId(), account.getId(), memberId);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", account.getId(),
                "股票收入 " + catLabel + " +" + shares.stripTrailingZeros().toPlainString() + " 股 "
                        + money(value) + " → " + account.getDisplayName());
        return rowFor(familyId, memberId, period.getId(), account.getId());
    }

    private String shareIncomeNote(BigDecimal shares, String holdingName, String userNote) {
        String base = "+" + shares.stripTrailingZeros().toPlainString() + " 股 · " + holdingName;
        if (userNote != null && !userNote.isBlank()) {
            base = base + " · " + userNote.trim();
        }
        return base.length() > 80 ? base.substring(0, 80) : base;
    }

    private BigDecimal positiveShares(BigDecimal value) {
        if (value == null) throw new IllegalArgumentException("股数必填");
        BigDecimal s = value.setScale(4, RoundingMode.HALF_EVEN);
        if (s.signum() <= 0) throw new IllegalArgumentException("股数必须大于 0");
        return s;
    }

    private Account requireStockAccount(long familyId, long accountId) {
        Account account = requireAccount(familyId, accountId);
        if (account.getType() != AccountType.STOCK) {
            throw new IllegalArgumentException("非股票账户不能按股数录入收入");
        }
        return account;
    }

    /** v0.2 FR-32 · 软删现金流(同时反向冲销余额) */
    @Transactional
    public EntryRow softDeleteCashFlow(long familyId, long memberId, long cashFlowId) {
        CashFlow cf = cashFlowMapper.findById(familyId, cashFlowId)
                .orElseThrow(() -> new IllegalArgumentException("现金流不存在: " + cashFlowId));
        Period period = requireOpenPeriod(familyId, cf.getPeriodId());
        Account account = requireAccount(familyId, cf.getAccountId());
        if (cf.getRefHoldingId() != null) {
            // v0.12 · 股票「+股数」收入:冲回持仓股数(持仓已归档/删则跳过)+ 冲回余额;不碰现金行
            BigDecimal shares = cf.getRefShares() == null ? BigDecimal.ZERO : cf.getRefShares();
            try {
                stockHoldingService.addShares(familyId, cf.getRefHoldingId(), shares.negate());
            } catch (Exception e) {
                log.warn("撤销股票收入冲回股数失败 · holding={} shares={}: {}",
                        cf.getRefHoldingId(), shares, e.toString());
            }
            applyDeltaToBalance(period, account, memberId, cf.getAmount().negate(),
                    "✕ 撤销股票收入(股数) " + money(cf.getAmount()));
        } else {
            // 反向冲销:INCOME 删 → balance -amount;EXPENSE 删 → balance +amount(股票账户走 CASH 现金行)
            BigDecimal delta = cf.getKind() == CashFlowKind.INCOME ? cf.getAmount().negate() : cf.getAmount();
            creditAccountBalance(familyId, period, account, memberId, delta,
                    "✕ 撤销 " + cf.getKind() + " " + money(cf.getAmount()));
        }
        cashFlowMapper.softDelete(familyId, cashFlowId);
        auditLogService.record(familyId, memberId, AuditLogType.CASH_FLOW_WRITE, "cash_flow", cashFlowId,
                "软删现金流 " + cf.getKind() + " " + money(cf.getAmount()));
        return rowFor(familyId, memberId, period.getId(), cf.getAccountId());
    }

    /** v0.2 FR-32 · 软删转账(同时反向冲销 from + to 两端余额) */
    @Transactional
    public EntryRow softDeleteTransfer(long familyId, long memberId, long transferId) {
        Transfer t = transferMapper.findById(familyId, transferId)
                .orElseThrow(() -> new IllegalArgumentException("转账不存在: " + transferId));
        Period period = requireOpenPeriod(familyId, t.getPeriodId());
        Account from = requireAccount(familyId, t.getFromAccountId());
        Account to = requireAccount(familyId, t.getToAccountId());
        // 反向:from +amount,to -amount(v1.18.1 · 与 addTransfer 同一条路由,否则撤销会把现金行留在原地)
        // 跨币种:收款方当初进账的是 to_amount,冲回也必须按同一个数,否则现金行会残留差额。
        BigDecimal backToAmount = t.receivedAmount();
        creditAccountBalance(familyId, period, from, memberId, t.getAmount(),
                "✕ 撤销划出到 " + to.getDisplayName() + " " + money(t.getAmount()));
        creditAccountBalance(familyId, period, to, memberId, backToAmount.negate(),
                "✕ 撤销来自 " + from.getDisplayName() + " " + money(backToAmount));
        transferMapper.softDelete(familyId, transferId);
        auditLogService.record(familyId, memberId, AuditLogType.TRANSFER_CREATE, "transfer", transferId,
                "软删转账 " + from.getDisplayName() + " → " + to.getDisplayName() + " " + money(t.getAmount()));
        return rowFor(familyId, memberId, period.getId(), t.getFromAccountId());
    }

    @Transactional
    public EntryRow addTransfer(long familyId,
                                long memberId,
                                long periodId,
                                long fromAccountId,
                                long toAccountId,
                                BigDecimal amount,
                                BigDecimal toAmount,   // v0.8 · 跨币种到账金额(转入账户币种);null=同币种
                                String note,
                                boolean confirmDuplicate) {
        Period period = requireOpenPeriod(familyId, periodId);
        Account fromAccount = requireAccount(familyId, fromAccountId);
        Account toAccount = requireAccount(familyId, toAccountId);
        // 跨币种:转出按 amount(转出账户币种),转入按 toAmount(转入账户币种);同币种 toAmount=null → 两端同 amount
        boolean crossCcy = !fromAccount.getCurrency().equals(toAccount.getCurrency());
        BigDecimal effToAmount = (toAmount != null) ? toAmount : amount;
        // 划转 A→B:A 余额 -amount,B 余额 +effToAmount
        // v1.18.1 · 必须走 creditAccountBalance 而不是直接 applyDeltaToBalance ——
        //   任一端若是「持仓估值接管」的账户,钱不落到现金行就会被下一次估值抹掉。
        //   生产上那 7.5w 正是经划转进来的(划转此前压根没走这条路由)。
        creditAccountBalance(familyId, period, fromAccount, memberId, amount.negate(),
                "↱ 划出到 " + toAccount.getDisplayName() + " " + money(amount));
        creditAccountBalance(familyId, period, toAccount, memberId, effToAmount,
                "↳ 收到来自 " + fromAccount.getDisplayName() + " " + money(effToAmount));
        Transfer created = insertTransfer(period, familyId, fromAccountId, toAccountId, amount,
                crossCcy ? effToAmount : null, note, memberId, confirmDuplicate);
        // v1.2 · 再平衡计划核销事件(AFTER_COMMIT 消费 · 纯本地规则 · 失败不影响划转)
        if (created != null && created.getId() != null) {
            eventPublisher.publishEvent(new com.family.finance.service.review.RebalancePlanService.TransferCreatedEvent(
                    familyId, created.getId(), fromAccountId, toAccountId, amount));
        }
        // v0.2 bug 修(2026-05-10): 转账路径双端都要把 todo 标 DONE,
        // 否则 forceClose 会因 PENDING 把"上期末"覆盖回 snapshot,丢失真实数据
        snapshotTodoMapper.markDone(familyId, periodId, fromAccountId, memberId);
        snapshotTodoMapper.markDone(familyId, periodId, toAccountId, memberId);
        auditLogService.record(familyId, memberId, AuditLogType.TRANSFER_CREATE, "account", fromAccountId,
                "新增转账 " + fromAccountId + " → " + toAccountId + " " + money(amount));
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新
        return rowFor(familyId, memberId, periodId, fromAccountId);
    }

    /**
     * 用户点 +收入 / -支出 / ↔划转 等"快捷按钮"时,把 delta 直接累加到本期余额上:
     *   - baseBalance = 当前 snapshot.end_balance(若没填过,fallback 到上期末,再 fallback 到 0)
     *   - newBalance = baseBalance + delta
     *   - upsert snapshot,note 标"快捷按钮调整"
     * 这样 4 种场景:
     *   1. 余额不变 — 用户进入页面时输入框默认显示上期末;不动直接提交即等于"本期=上期"
     *   2. 余额变化(原因不明)— 用户在余额输入框填新值,snapshot 直接覆盖
     *   3. A→B 转 500 — 自动 A 余额 -500、B 余额 +500
     *   4. 收入 4000 快捷 — 自动余额 +4000
     * 见 PRD § 2.4 / FR-7~9 / §7.9。
     */
    private void applyDeltaToBalance(Period period, Account account, long memberId,
                                      BigDecimal delta, String reason) {
        Optional<PeriodSnapshot> currentOpt = snapshotMapper.findByPeriodAndAccount(period.getFamilyId(), period.getId(), account.getId());
        BigDecimal base;
        if (currentOpt.isPresent()) {
            base = currentOpt.get().getEndBalance();
        } else {
            PeriodSnapshot prevSnap = snapshotMapper.findLatestBefore(period.getFamilyId(), account.getId(), period.getPeriodStart(), 1)
                    .stream().findFirst().orElse(null);
            base = prevSnap == null ? BigDecimal.ZERO : prevSnap.getEndBalance();
        }
        BigDecimal newBalance = base.add(delta).setScale(2, RoundingMode.HALF_EVEN);
        snapshotMapper.upsertOwned(period.getFamilyId(), PeriodSnapshot.builder()
                .periodId(period.getId())
                .accountId(account.getId())
                .endBalance(newBalance)
                .submittedBy(memberId)
                .note(reason + " · 余额 " + base + " → " + newBalance)
                // v1.18 · 由人的一笔填报(收入/支出/调整)派生出来的余额 → 仍算手动
                .sourceTag(com.family.finance.domain.ledger.LedgerSource.MANUAL.name())
                .build());
        auditLogService.record(period.getFamilyId(), memberId, AuditLogType.SNAPSHOT_WRITE,
                "account", account.getId(),
                reason + ":余额 " + base + " → " + newBalance);
        propagateCarriedForward(period, account.getId(), newBalance, memberId);
    }

    /**
     * v1.23 FR-623 / FR-624 · 补录期余额变了 → 把变化**传导**到进行期的预填延续值。
     *
     * <h3>为什么需要它</h3>
     * <p>进行期开账时,每个账户的余额是按「上期末」预填的。如果用户随后在宽限期内
     * 往补录期补了一笔<b>影响余额</b>的支出,上期末就变了 —— 进行期那个预填值
     * <b>过期了</b>,而 dashboard 的净资产恰恰锚进行期。不传导的话,用户补完 8 月的账,
     * 9 月的净资产还是按旧的延续值算,而且要等到关账才对。</p>
     *
     * <h3>为什么不是「补录强制不落余额」</h3>
     * <p>那会让规则自相矛盾:8/30 写的支出能影响余额,9/1 写的同一笔就不能?
     * 期还开着,规则不该变。该变的是认识:<b>余额延续是派生值,源头变了就跟着变</b>。</p>
     *
     * <h3>守门:只改没人确认过的那张</h3>
     * <p>判据是 {@code source_tag = CARRIED_FORWARD} —— 那是开账时系统代填的标记
     * ({@code PeriodOpener} 是它唯一的写入者)。用户手填过、或估值刷新写过的快照,
     * 一律<b>一分不动</b>:他已经对进行期的余额表过态了,补上期的账不该反过来推翻它。
     * 这是本版最不能犯的错(prd/v1.23.md 失败模式 ④)。</p>
     *
     * <p>非双活跃窗口时 {@code next == null},整个方法是 no-op —— T+0 的家庭零影响。</p>
     */
    private void propagateCarriedForward(Period source, long accountId,
                                         BigDecimal newEndBalance, long memberId) {
        Period next = periodMapper.findRecordableOpen(source.getFamilyId()).stream()
                .filter(p -> p.getPeriodStart().isAfter(source.getPeriodStart()))
                .findFirst()
                .orElse(null);
        if (next == null) return;   // 没有更晚的 OPEN 期 = 不在双活跃窗口里
        PeriodSnapshot nextSnap = snapshotMapper.findByPeriodAndAccount(source.getFamilyId(), next.getId(), accountId).orElse(null);
        if (nextSnap == null) return;   // 进行期还没这张快照 → 它开账时自然会读到新值
        if (!com.family.finance.domain.ledger.LedgerSource.CARRIED_FORWARD.name()
                .equals(nextSnap.getSourceTag())) {
            return;   // 有人确认过这个数 → 不覆盖
        }
        if (nextSnap.getEndBalance() != null
                && nextSnap.getEndBalance().compareTo(newEndBalance) == 0) {
            return;   // 值没变,不写库也不记审计(否则每笔补录都刷一条无变化的日志)
        }
        snapshotMapper.upsertOwned(source.getFamilyId(), PeriodSnapshot.builder()
                .periodId(next.getId())
                .accountId(accountId)
                .endBalance(newEndBalance)
                .submittedBy(memberId)
                .note("因上期补录已更新延续值 " + nextSnap.getEndBalance() + " → " + newEndBalance)
                // 仍然是「系统代填、没人确认过」→ 标记不变,下次补录还能继续传导
                .sourceTag(com.family.finance.domain.ledger.LedgerSource.CARRIED_FORWARD.name())
                .build());
        auditLogService.record(source.getFamilyId(), memberId, AuditLogType.SNAPSHOT_WRITE,
                "account", accountId,
                "上期补录传导:进行期延续值 " + nextSnap.getEndBalance() + " → " + newEndBalance);
    }

    /**
     * v0.12 · 把余额变动 delta 记到账户,按「余额归谁管」路由:
     *   - <b>由持仓估值接管的账户</b>:落到账户「CASH 现金行」(adjustAccountCash)
     *            + 同时 applyDeltaToBalance 立即反映到当期 snapshot
     *            (下次估值是「绝对重算 = 持仓 + 现金行」→ 不双计也不丢)。
     *   - 其它账户:仅 applyDeltaToBalance(snapshot 就是余额真值)。
     *
     * <p><b>v1.18.1 BUG-FIX · 这里原来判的是 {@code type == STOCK}</b>,而估值接管判的是
     * 「支持持仓的类型 + 真的有持仓」—— 两者不一致,于是 WEALTH/CRYPTO/METAL 且有持仓的账户
     * (例如挂着基金持仓的余额宝)收到钱之后只加到快照上、<b>没进现金行</b>,
     * 下一次自动估值按「持仓合计」把快照覆盖回去,<b>那笔钱就从余额里消失了</b>。
     * 生产实测:两笔转入合计 7.5w 被 8-20 06:15 的估值抹掉,家庭净资产少算同额,
     * 而且每跑一次估值就再抹一次。判据现已收口到 {@code StockHoldingService.valuationManaged},
     * 与估值接管同源。</p>
     */
    private void creditAccountBalance(long familyId, Period period, Account account, long memberId,
                                      BigDecimal delta, String reason) {
        if (stockHoldingService.valuationManaged(account)) {
            stockHoldingService.adjustAccountCash(familyId, account.getId(), account.getCurrency(), delta);
        }
        applyDeltaToBalance(period, account, memberId, delta, reason);
    }

    /**
     * v1.21 · 批量导入落库之后,<b>一次性</b>把账户余额扣掉(或撤销时加回)。
     *
     * <p>为什么不让导入走 {@link #recordExpense} 逐笔:那条路每笔都会改一次余额、
     * 写一条审计日志、发一次透视缓存失效事件。300 笔就是 300 条审计,
     * 会把同一天别的记录全冲到看不见的地方 —— 而批量导入在用户眼里本来就是<b>一个</b>动作。</p>
     *
     * <p>只做余额与审计,<b>不写 cash_flow</b>(那些已经由
     * {@code BillCommitService} 批量插好了)。它是那条路径的一部分,不是通用入口。</p>
     *
     * @param delta 正数 = 这批总支出(会从余额里扣);负数 = 撤销(加回)
     */
    @Transactional
    public void applyImportedExpense(long familyId, long memberId, Period period,
                                     Account account, BigDecimal delta, String reason) {
        if (delta == null || delta.signum() == 0) return;
        creditAccountBalance(familyId, period, account, memberId, delta.negate(), reason);
        auditLogService.record(familyId, memberId, AuditLogType.SYSTEM, "account", account.getId(),
                reason + " · " + money(delta.abs()) + " ← " + account.getDisplayName());
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId));
    }

    @Transactional
    public EntryRow quickTransfer(long familyId,
                                  long memberId,
                                  long fromAccountId,
                                  Long periodId,
                                  long toAccountId,
                                  BigDecimal amount,
                                  BigDecimal toAmount,
                                  String note,
                                  boolean confirmDuplicate) {
        Period period = periodId == null
                ? periodMapper.findBalancePeriod(familyId).orElseThrow(() -> new IllegalStateException("当前没有 OPEN 周期"))
                : requireOpenPeriod(familyId, periodId);
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新
        return addTransfer(familyId, memberId, period.getId(), fromAccountId, toAccountId, amount, toAmount, note, confirmDuplicate);
    }

    private EntryRow toRow(Account account,
                           Map<Long, Member> members,
                           Map<Long, Account> allAccountsById,
                           PeriodSnapshot current,
                           SnapshotTodo todo,
                           Period period) {
        PeriodSnapshot previous = snapshotMapper.findLatestBefore(account.getFamilyId(), account.getId(), period.getPeriodStart(), 1)
                .stream()
                .findFirst()
                .orElse(null);
        ReconciliationTotals totals = reconciliationTotals(account.getFamilyId(), period.getId(), account.getId());
        BigDecimal currentBalance = current == null ? null : current.getEndBalance();
        BigDecimal previousBalance = previous == null ? null : previous.getEndBalance();
        BigDecimal effectiveBalance = currentBalance != null
                ? currentBalance
                : (todo == null ? null : todo.getPrefilledBalance());
        BigDecimal delta = currentBalance != null && previousBalance != null
                ? currentBalance.subtract(previousBalance)
                : null;
        BigDecimal unexplained = effectiveBalance == null || previousBalance == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                : ReconciliationCalculator.unexplained(
                        effectiveBalance,
                        previousBalance,
                        totals.income(),
                        totals.expense(),
                        totals.transferIn(),
                        totals.transferOut());
        Member owner = account.getPrimaryOwnerMemberId() == null ? null : members.get(account.getPrimaryOwnerMemberId());
        boolean done = current != null || (todo != null && todo.getStatus() == TodoStatus.DONE);
        String currentLabel = currentBalance == null && todo != null && todo.getPrefilledBalance() != null
                ? "预填 " + MoneyFormat.format(account.getCurrency(), todo.getPrefilledBalance())
                : MoneyFormat.format(account.getCurrency(), currentBalance);
        String warning = account.getType().expectsFlowsToExplainBalance()
                && unexplained.signum() != 0
                ? "余额出现未解释变化,建议补一笔收入 / 支出 / 转账对上账"
                : null;
        // PRD §2.4 / FR-9:本期划转明细(供接收方看到"已收到来自 X 的 200")
        List<EntryRow.TransferRef> incoming = new ArrayList<>();
        List<EntryRow.TransferRef> outgoing = new ArrayList<>();
        // 同时为本期 ledger(本期所有流水合并视图,PRD §7.9 / FR-7~9)收集划转条目
        List<EntryRow.LedgerEntry> ledger = new ArrayList<>();
        for (Transfer t : transferMapper.findCommittedByPeriodAndAccount(account.getFamilyId(), period.getId(), account.getId())) {
            if (t.getToAccountId().equals(account.getId())) {
                Account from = allAccountsById.get(t.getFromAccountId());
                String name = from == null ? "其他账户" : from.getDisplayName();
                // issue #21 · 显示的金额配的是【这个账户】的币种符号,所以必须是它自己收到的数
                BigDecimal received = t.receivedAmount();
                incoming.add(new EntryRow.TransferRef(name, received,
                        MoneyFormat.format(account.getCurrency(), received)));
                ledger.add(new EntryRow.LedgerEntry(
                        EntryRow.LedgerKind.TRANSFER_IN,
                        t.getSubmittedAt(),
                        received,
                        "+" + MoneyFormat.format(account.getCurrency(), received),
                        name,
                        t.getNote(),
                        t.getId(),
                        period.getStatus() == PeriodStatus.OPEN, null,
                        // 对方(转出方)那一侧:它付出的钱,按它自己的币种
                        MoneyFormat.format(from == null ? account.getCurrency() : from.getCurrency(), t.getAmount())));
            } else if (t.getFromAccountId().equals(account.getId())) {
                Account to = allAccountsById.get(t.getToAccountId());
                String name = to == null ? "其他账户" : to.getDisplayName();
                outgoing.add(new EntryRow.TransferRef(name, t.getAmount(),
                        MoneyFormat.format(account.getCurrency(), t.getAmount())));
                ledger.add(new EntryRow.LedgerEntry(
                        EntryRow.LedgerKind.TRANSFER_OUT,
                        t.getSubmittedAt(),
                        t.getAmount(),
                        "−" + MoneyFormat.format(account.getCurrency(), t.getAmount()),
                        name,
                        t.getNote(),
                        t.getId(),
                        period.getStatus() == PeriodStatus.OPEN, null,
                        // 对方(转入方)那一侧:它实际收到的钱,按它自己的币种
                        MoneyFormat.format(to == null ? account.getCurrency() : to.getCurrency(), t.receivedAmount())));
            }
        }
        for (CashFlow cf : cashFlowMapper.findByPeriodAndAccount(account.getFamilyId(), period.getId(), account.getId())) {
            EntryRow.LedgerKind k = cf.getKind() == CashFlowKind.INCOME
                    ? EntryRow.LedgerKind.INCOME : EntryRow.LedgerKind.EXPENSE;
            String sign = cf.getKind() == CashFlowKind.INCOME ? "+" : "−";
            ledger.add(new EntryRow.LedgerEntry(
                    k,
                    cf.getSubmittedAt(),
                    cf.getAmount(),
                    sign + MoneyFormat.format(account.getCurrency(), cf.getAmount()),
                    cf.getCategoryCode(),
                    cf.getNote(),
                    cf.getId(),
                    period.getStatus() == PeriodStatus.OPEN, null));
        }
        if (current != null) {
            // v0.4.4:历史遗留的英文系统标记替换为中文,避免用户面暴露内部代号
            String snapNote = current.getNote();
            if (snapNote != null && snapNote.startsWith("auto-stock-valuation")) {
                snapNote = com.family.finance.service.stock.AccountValuationService.SYSTEM_VALUATION_NOTE;
            }
            ledger.add(new EntryRow.LedgerEntry(
                    EntryRow.LedgerKind.SNAPSHOT,
                    current.getSubmittedAt(),
                    current.getEndBalance(),
                    "= " + MoneyFormat.format(account.getCurrency(), current.getEndBalance()),
                    null,
                    snapNote,
                    null,
                    period.getStatus() == PeriodStatus.OPEN, null));
        }
        // v0.4.1 FR-52f · 持仓账户估值事件作为第 4 种流水
        if (com.family.finance.service.stock.StockHoldingService.supportsHoldings(account.getType())) {
            try {
                for (StockValuationEvent ev : stockValuationEventMapper.findByAccountAndPeriod(
                        account.getFamilyId(), account.getId(), period.getId())) {
                    String sign = ev.getDelta().signum() >= 0 ? "+" : "−";
                    String label = "估值变动 · " + triggerCn(ev.getTriggerKind());
                    String note = ev.getNote() != null ? ev.getNote()
                        : (ev.getPrevBalance() != null
                            ? "从 " + MoneyFormat.format(account.getCurrency(), ev.getPrevBalance())
                              + " → " + MoneyFormat.format(account.getCurrency(), ev.getNewBalance())
                            : null);
                    ledger.add(new EntryRow.LedgerEntry(
                        EntryRow.LedgerKind.VALUATION,
                        ev.getTriggeredAt(),
                        ev.getDelta().abs(),
                        sign + MoneyFormat.format(account.getCurrency(), ev.getDelta().abs()),
                        label,
                        note,
                        ev.getId(),
                        false,  // 估值事件不可删除 · 不显操作按钮
                        ev.getRefImportId()   // v1.4 · 截图导入触发的估值 → ledger 显「看明细」
                    ));
                }
            } catch (Exception ignored) {
                // ledger 渲染失败不阻塞整体页面
            }
        }
        // v0.4.22 · 倒序(新→旧)· 展开账户折叠看到的第一眼应该是最新最有价值的流水
        // null occurredAt(未知时间)放最末 · 不论升序降序都视为"信息价值最低"
        ledger.sort((a, b) -> {
            if (a.occurredAt() == null && b.occurredAt() == null) return 0;
            if (a.occurredAt() == null) return 1;
            if (b.occurredAt() == null) return -1;
            return b.occurredAt().compareTo(a.occurredAt());
        });

        // v0.17.x · 贷款趋势预测建议(填报页行内提示条)· prev+Δ 夹≤0(逻辑 follow 旧 predictLoanBalance)
        BigDecimal loanSuggestion = null;
        boolean showLoanPrompt = false;
        String loanSuggestionLabel = null;
        String loanSuggestionDeltaLabel = null;
        if (account.getType().isLiability() && previousBalance != null) {
            List<PeriodSnapshot> last2 = snapshotMapper.findLatestBefore(account.getFamilyId(), account.getId(), period.getPeriodStart(), 2);
            BigDecimal prevPrev = last2.size() >= 2 ? last2.get(1).getEndBalance() : null;
            BigDecimal predicted = PeriodOpener.predictLoanBalance(previousBalance, prevPrev);
            if (loanPromptVisible(predicted, previousBalance, currentBalance, confirmedByHuman(todo))) {
                loanSuggestion = predicted;
                loanSuggestionLabel = MoneyFormat.format(account.getCurrency(), predicted);
                loanSuggestionDeltaLabel = MoneyFormat.formatDelta(account.getCurrency(), predicted.subtract(previousBalance));
                showLoanPrompt = true;
            }
        }

        return new EntryRow(
                account,
                owner == null ? "共同" : owner.getDisplayName(),
                todo,
                current,
                previous,
                delta,
                totals.income(),
                totals.expense(),
                totals.transferIn(),
                totals.transferOut(),
                unexplained,
                currentLabel,
                MoneyFormat.format(account.getCurrency(), previousBalance),
                MoneyFormat.formatDelta(account.getCurrency(), delta),
                MoneyFormat.formatDelta(account.getCurrency(), unexplained),
                warning,
                done,
                // PRD FR-10 智能转账推断 · v0.4.18 阈值改读 ConfigService(默认 3000)
                unexplained.abs().compareTo(new BigDecimal(
                    Long.toString(configService.getLong(FAMILY_ID,
                        com.family.finance.service.config.FamilyConfigService.K_SMART_TRANSFER, 3000L)))) > 0
                && account.getType() != AccountType.LOAN,
                incoming,
                outgoing,
                ledger,
                loanSuggestion,
                showLoanPrompt,
                loanSuggestionLabel,
                loanSuggestionDeltaLabel
        );
    }

    /**
     * v0.17.x · 贷款趋势预测提示条是否显示 · 兼容闸(可测纯逻辑)。
     * 显示 iff:有实际建议(predicted≠prev)且**没有人确认过**且当前 committed==上月值(新默认态)。
     * committed==prev 天然屏蔽老账期(旧代码已把 committed 写成预测值≠prev),不打扰、不回改。
     *
     * <p>v1.16 FR-392:第四个参数从「todo 是不是 DONE」改成「是不是<b>人</b>确认的」。
     * 开账代填从此也会把 todo 标 DONE(issue #15 的口径统一),再只看状态这条提示条就永远不出现了。</p>
     */
    /**
     * v1.16 FR-392 · 这一行是不是<b>人</b>确认过的(issue #15)。
     *
     * <p>v1.16 起开账代填也会把 todo 标成 DONE(见 {@link PeriodOpener#createPeriodAndTodos}),
     * 「有数字」和「已填」统一成一件事;但贷款趋势提示条要的是另一件事 ——
     * <b>有没有人真的看过并做了选择</b>。系统代填的 DONE 其 {@code done_by_member_id} 为 NULL,
     * 人工提交的才记名到人,靠这个把两者分开。若这里退回只看状态,
     * 所有贷款账户在开账瞬间就"已完成",v0.17.x「贷款不静默外推」的那条规矩会被口径统一顺手删掉。</p>
     */
    static boolean confirmedByHuman(SnapshotTodo todo) {
        return todo != null
                && todo.getStatus() == TodoStatus.DONE
                && todo.getDoneByMemberId() != null;
    }

    static boolean loanPromptVisible(BigDecimal predicted, BigDecimal prev, BigDecimal committed,
                                     boolean confirmedByHuman) {
        return predicted != null && prev != null
                && predicted.compareTo(prev) != 0
                && !confirmedByHuman
                && committed != null && committed.compareTo(prev) == 0;
    }

    private void insertCashFlow(Period period, Account account, long memberId, CashFlowLine line) {
        insertCashFlow(period, account, memberId, line, null);
    }

    /**
     * v1.21 · 带消费分类的重载。
     *
     * <p>{@code expenseCategoryId} <b>只在 {@code categoryCode='consumption'} 时才写下去</b> ——
     * 「还贷 / 利息支出 / 转账给亲属」不是消费,给它们挂一个「餐饮美食」既没有意义,
     * 又会让报表把还贷算进消费构成里。这个过滤放在<b>写入口</b>而不是读口径:
     * 脏数据一旦落库,之后每一处读它的地方都要重复同一个 if,总有一处会漏。</p>
     */
    private void insertCashFlow(Period period, Account account, long memberId,
                                CashFlowLine line, Long expenseCategoryId) {
        insertCashFlow(period, account, memberId, line, expenseCategoryId, true);
    }

    private void insertCashFlow(Period period, Account account, long memberId,
                                CashFlowLine line, Long expenseCategoryId, boolean affectsBalance) {
        insertCashFlow(period, account, memberId, line, expenseCategoryId, affectsBalance, false);
    }

    private void insertCashFlow(Period period, Account account, long memberId,
                                CashFlowLine line, Long expenseCategoryId, boolean affectsBalance,
                                boolean oneOff) {
        /* v1.22 · 【只有收入侧的 0 才跳过】。
         * 原来这里对所有 kind 都「金额为 0 就直接 return」—— 于是一笔 0 元的支出
         * (全额优惠券 / 积分抵扣)被<b>静默丢弃</b>:用户在确认页上勾了它,
         * 提交之后它凭空消失,没有任何提示。0 元支出是真实存在的记录,
         * 它不影响金额但影响笔数,该不该记是用户的决定,不是我们的。 */
        if (line == null || line.amount() == null) {
            return;
        }
        if (line.amount().signum() == 0 && line.kind() != CashFlowKind.EXPENSE) {
            return;
        }
        if (line.kind() == null) {
            throw new IllegalArgumentException("现金流类型必填");
        }
        if (line.categoryCode() == null || line.categoryCode().isBlank()) {
            throw new IllegalArgumentException("现金流类别必填");
        }
        /* 支出可以是 0 或负数(见 expenseMoney 的注释);收入仍然必须为正 */
        BigDecimal amount = line.kind() == CashFlowKind.EXPENSE
                ? expenseMoney(line.amount())
                : positiveMoney(line.amount());
        cashFlowMapper.insertOwned(period.getFamilyId(), CashFlow.builder()
                .periodId(period.getId())
                .accountId(account.getId())
                .kind(line.kind())
                .categoryCode(line.categoryCode())
                .amount(amount)
                .occurredAt(period.getPeriodEnd())
                .note(blankToNull(line.note()))
                .submittedBy(memberId)
                .sourceTag(com.family.finance.domain.ledger.LedgerSource.MANUAL.name())   // v1.18
                .expenseCategoryId(CONSUMPTION.equals(line.categoryCode()) ? expenseCategoryId : null)
                .affectsBalance(affectsBalance)
                /* v1.24 FR-613 · 只有【消费】才谈得上一次性 —— 还贷 / 利息 / 给亲属
                 * 不进「钱花在哪」,给它们打一次性标记没有任何读它的地方。
                 * 与 expenseCategoryId 同一条规则:脏数据挡在写入口,不留给读口径去 if。 */
                .oneOff(CONSUMPTION.equals(line.categoryCode()) && oneOff)
                .build());
    }

    /** v1.21 · 「日常开支」这个性质码 —— 只有它下面的笔才谈得上「钱花在哪」 */
    public static final String CONSUMPTION = "consumption";

    private Transfer insertTransfer(Period period,
                                    long familyId,
                                    long fromAccountId,
                                    long toAccountId,
                                    BigDecimal amount,
                                    BigDecimal toAmount,
                                    String note,
                                    long memberId,
                                    boolean confirmDuplicate) {
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("转出/转入账户不能相同");
        }
        requireAccount(familyId, fromAccountId);
        requireAccount(familyId, toAccountId);
        BigDecimal normalized = positiveMoney(amount);
        int duplicate = transferMapper.countRecentDuplicate(period.getFamilyId(), period.getId(), fromAccountId, toAccountId, normalized);
        if (duplicate > 0 && !confirmDuplicate) {
            throw new IllegalArgumentException("看起来像 24 小时内重复转账,请确认后再提交");
        }
        Transfer transfer = Transfer.builder()
                .periodId(period.getId())
                .fromAccountId(fromAccountId)
                .toAccountId(toAccountId)
                .amount(normalized)
                .toAmount(toAmount == null ? null : positiveMoney(toAmount))   // v0.8 · 跨币种到账额;null=同币种
                .occurredAt(period.getPeriodEnd())
                .note(blankToNull(note))
                .submittedBy(memberId)
                .draft(false)
                .sourceTag(com.family.finance.domain.ledger.LedgerSource.MANUAL.name())   // v1.18
                .build();
        transferMapper.insertOwned(period.getFamilyId(), transfer);
        return transfer;
    }

    /**
     * 历史版本会在用户改 LOAN 余额时"自动调整 / 确认"一笔从 default_payment_source 的 transfer。
     * 经产品反馈,LOAN 余额修改应**与其他账户解耦**(避免暗中改招行卡等"还款来源"账户的余额),
     * 留给用户在 LOAN 行的快捷划转按钮显式登记。本方法保留为空 op,
     * 兼容既有调用方但不再产生联动副作用。详见 PRD §7.9 第六批维护。
     */
    private void adjustLoanDraft(Period period, Account loan, BigDecimal newBalance, long memberId) {
        // intentionally no-op
    }

    private ReconciliationTotals reconciliationTotals(long familyId, long periodId, long accountId) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (CashFlow cashFlow : cashFlowMapper.findByPeriodAndAccount(familyId, periodId, accountId)) {
            if (cashFlow.getKind() == CashFlowKind.INCOME) {
                income = income.add(cashFlow.getAmount());
            } else {
                expense = expense.add(cashFlow.getAmount());
            }
        }
        BigDecimal transferIn = BigDecimal.ZERO;
        BigDecimal transferOut = BigDecimal.ZERO;
        for (Transfer transfer : transferMapper.findCommittedByPeriodAndAccount(familyId, periodId, accountId)) {
            if (transfer.getToAccountId().equals(accountId)) {
                // issue #21 · 转入方按它自己的币种算:跨币种时是 toAmount,不是转出方付出的 amount
                transferIn = transferIn.add(transfer.receivedAmount());
            }
            if (transfer.getFromAccountId().equals(accountId)) {
                transferOut = transferOut.add(transfer.getAmount());
            }
        }
        // v1.30 · 补录本金:这一期余额里本来就有、这期才补录的那部分 —— 和转入一样是能解释余额变化的已知流入
        BigDecimal principalAdj = principalAdjustmentMapper.sumByPeriodAndAccount(familyId, periodId, accountId);
        if (principalAdj != null) transferIn = transferIn.add(principalAdj);
        return new ReconciliationTotals(
                income.setScale(2, RoundingMode.HALF_EVEN),
                expense.setScale(2, RoundingMode.HALF_EVEN),
                transferIn.setScale(2, RoundingMode.HALF_EVEN),
                transferOut.setScale(2, RoundingMode.HALF_EVEN)
        );
    }

    private Period requireOpenPeriod(long familyId, long periodId) {
        Period period = periodMapper.findById(familyId, periodId)
                .filter(p -> p.getFamilyId() == familyId)
                .orElseThrow(() -> new IllegalArgumentException("周期不存在: " + periodId));
        if (period.getStatus() != PeriodStatus.OPEN) {
            throw new IllegalStateException("周期已关闭,请先重开再修改");
        }
        return period;
    }

    private Account requireAccount(long familyId, long accountId) {
        return accountMapper.findById(familyId, accountId)
                .filter(account -> account.getFamilyId() == familyId)
                .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + accountId));
    }

    private BigDecimal normalizeBalance(Account account, BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("余额必填");
        }
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_EVEN);
        if (account.getType().isLiability() && scaled.signum() > 0) {
            return scaled.negate();
        }
        return scaled;
    }

    /**
     * 支出金额 —— <b>允许 0 和负数,且不取绝对值</b>(v1.22 FR-595 / FR-596)。
     *
     * <p>不能用 {@link #positiveMoney}:那个方法会 {@code value.abs()},
     * 于是一笔 −499 的退款冲正被<b>静默记成 +499 的消费</b> ——
     * 不报错、不提示,当月支出凭空多出一千块。这正是 PRD §9 失败模式①说的那种错。</p>
     *
     * <p>真实账单里 0 元(全额优惠 / 积分抵扣)和负数(退款冲正)都存在。
     * 挡掉它们等于替用户决定哪些交易「不算数」;取绝对值比挡掉更糟,因为它<b>无声</b>。</p>
     *
     * <p>收入与划转仍然走 {@link #positiveMoney} —— 那两条路径没有「负的」这个概念,
     * 而且 {@code transfer} 表上的 CHECK 还在。</p>
     */
    private BigDecimal expenseMoney(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额必填");
        }
        return value.setScale(2, RoundingMode.HALF_EVEN);
    }

    private BigDecimal positiveMoney(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("金额必填");
        }
        BigDecimal amount = value.abs().setScale(2, RoundingMode.HALF_EVEN);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("金额必须大于 0");
        }
        return amount;
    }

    /**
     * 单股估值校验(issue#3)· 只校验 > 0,按 manual_value 列精度对齐到 6 位,
     * 不像 {@link #positiveMoney} 那样压成 2 位 —— 未上市单股估值 15.678 必须原样落库。
     */
    private BigDecimal positiveUnitValue(BigDecimal value) {
        if (value == null) {
            throw new IllegalArgumentException("单股估值必填");
        }
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("单股估值必须大于 0");
        }
        return value.setScale(6, RoundingMode.HALF_EVEN);
    }

    private String money(BigDecimal amount) {
        return amount == null ? "—" : amount.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record CashFlowLine(CashFlowKind kind, String categoryCode, BigDecimal amount, String note) {
    }

    public record TransferLine(Long toAccountId, BigDecimal amount, BigDecimal toAmount, String note) {
    }

    private record ReconciliationTotals(BigDecimal income, BigDecimal expense, BigDecimal transferIn, BigDecimal transferOut) {
    }
}
