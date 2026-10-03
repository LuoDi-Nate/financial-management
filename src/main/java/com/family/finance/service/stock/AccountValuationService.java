package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.StockPriceSnapshot;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.domain.ledger.LedgerSource;
import com.family.finance.domain.stock.StockValuationEvent;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.repository.StockPriceSnapshotMapper;
import com.family.finance.repository.StockValuationEventMapper;
import com.family.finance.service.FamilyService;
import com.family.finance.service.FxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * 持仓账户自动估值 · v0.3 FR-52 · 决策 26。
 *
 * <p>核心 backward compat 红线(已设计):</p>
 * <ul>
 *   <li>**只对"有 stock_holding 持仓"的持仓账户写回 account_balance**</li>
 *   <li>v0.2 用户没创建过持仓的老账户 · 完全不动 · 沿用手填行为</li>
 *   <li>用户创建持仓后,系统才接管自动估值</li>
 * </ul>
 *
 * <p>账户余额 = SUM(AUTO 持仓.shares × latest_price × fx_to_base)
 *            + SUM(MANUAL 持仓.manual_value)</p>
 *
 * <p>价格降级链:今日价 → 历史 snapshot 最近行(stale)→ 跳过该持仓(全局 partial-valued 标识)。</p>
 */
@Service
@Slf4j
public class AccountValuationService {


    private final AccountMapper accountMapper;
    private final StockHoldingMapper holdingMapper;
    private final StockPriceSnapshotMapper priceMapper;
    private final SnapshotMapper snapshotMapper;
    private final PeriodMapper periodMapper;
    private final MemberMapper memberMapper;
    private final FxService fxService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher; // v1.1.1 lens 缓存失效事件
    private final FamilyService familyService;
    /** v0.4.1 FR-52f · 估值事件审计 + ledger 显示 */
    private final StockValuationEventMapper valuationEventMapper;
    /** v1.18.3 · 估值写回被拦下时要留痕 —— 只写日志等于页面上看不出来(v1.17.3 的教训) */
    private final com.family.finance.service.AuditLogService auditLogService;

    /** |Δ| > 此阈值才写 event(避免微小价格波动产生噪音流水) */
    private static final BigDecimal EVENT_THRESHOLD = new BigDecimal("0.01");

    public AccountValuationService(AccountMapper accountMapper,
                                   StockHoldingMapper holdingMapper,
                                   StockPriceSnapshotMapper priceMapper,
                                   SnapshotMapper snapshotMapper,
                                   PeriodMapper periodMapper,
                                   MemberMapper memberMapper,
                                   FxService fxService,
                                   org.springframework.context.ApplicationEventPublisher eventPublisher,
                                   FamilyService familyService,
                                   StockValuationEventMapper valuationEventMapper,
                                   com.family.finance.service.AuditLogService auditLogService) {
        this.accountMapper = accountMapper;
        this.holdingMapper = holdingMapper;
        this.priceMapper = priceMapper;
        this.snapshotMapper = snapshotMapper;
        this.periodMapper = periodMapper;
        this.memberMapper = memberMapper;
        this.fxService = fxService;
        this.eventPublisher = eventPublisher;
        this.familyService = familyService;
        this.valuationEventMapper = valuationEventMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 单账户估值 · 用于手动触发(用户在持仓页 click [刷新])。
     *
     * @return 估值结果 + 是否含陈旧价格
     */
    public ValuationResult valuate(long familyId, long accountId) {
        Account acc = accountMapper.findById(familyId, accountId)
            .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + accountId));
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问账户");
        }
        return valuateInternal(acc);
    }

    /**
     * 全家所有持仓账户估值刷新 · cron 默认调用。
     * 兼容老 API · 默认 trigger=CRON · 无用户。
     */
    public int refreshAllForFamily(long familyId) {
        return refreshAllForFamily(familyId, TriggerKind.CRON, null);
    }

    /**
     * 全家所有持仓账户估值刷新(v0.4.1 加 trigger 审计)。
     * 仅遍历**有 holding** 的持仓账户 · 写回 account_balance(当前 OPEN 周期)
     * + 若余额变化 > {@link #EVENT_THRESHOLD} 写一条 stock_valuation_event。
     *
     * @param trigger 触发源:CRON / MANUAL / HOLDING_CHANGE
     * @param triggeredByMemberId 用户 ID(MANUAL 时记 · 其它 null)
     */
    public int refreshAllForFamily(long familyId, TriggerKind trigger, Long triggeredByMemberId) {
        return refreshAllForFamily(familyId, trigger, triggeredByMemberId, null);
    }

    /**
     * v1.18 · 多一个 {@code explicitSource}:调用方明确知道"这次变动是谁引起的"就传进来
     * (例如券商同步传 SYNC_BROKER_FUTU)。传 null 则由 {@link #inferSource} 按触发方式 + 持仓市场推断。
     */
    public int refreshAllForFamily(long familyId, TriggerKind trigger, Long triggeredByMemberId,
                                   LedgerSource explicitSource) {
        Period currentOpen = periodMapper.findBalancePeriod(familyId).orElse(null);
        if (currentOpen == null) {
            log.info("family={} no OPEN period · skip valuation refresh", familyId);
            return 0;
        }
        List<Account> accounts = accountMapper.findActiveByFamily(familyId);
        int refreshed = 0;
        for (Account acc : accounts) {
            List<StockHolding> holdings = holdingMapper.findActiveByAccount(acc.getFamilyId(), acc.getId());
            // backward compat 红线:无 holding 的老账户不接管 · 用户继续手填。
            // v1.18.1 · 判据收口到 StockHoldingService.valuationManaged —— 录入侧要用同一条
            // (「余额变动该不该落到现金行」必须和「估值会不会覆盖这张快照」是同一个判断)。
            if (!StockHoldingService.valuationManaged(acc.getType(), holdings)) continue;
            ValuationResult r = valuateInternal(acc);
            // v0.4.1:先取 prev_balance 再写回
            BigDecimal prevBalance = snapshotMapper.findByPeriodAndAccount(familyId, currentOpen.getId(), acc.getId())
                .map(s -> s.getEndBalance())
                .orElse(null);
            LedgerSource src = inferSource(explicitSource, trigger, null, holdings);
            // v1.18.3 · 没写回就【不写事件】—— 否则等于记一个没发生的变化,而且会把
            //   「上次估值时间」推到现在,让下一次刷新的窗口变空、第二次就拦不住。
            if (!writeBackBalance(familyId, currentOpen.getId(), acc, r.totalBaseValue(), src)) continue;
            // 若变化超阈值,写事件
            recordValuationEventIfChanged(familyId, acc.getId(), currentOpen.getId(),
                prevBalance, r.totalBaseValue(), trigger, triggeredByMemberId, null, src);
            refreshed++;
        }
        log.info("family={} valuation refresh · trigger={} refreshed={}", familyId, trigger, refreshed);
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId)); // v1.1.1 透视缓存后台换新
        return refreshed;
    }

    /** 触发源枚举 · 用于 stock_valuation_event 审计 · v1.4 加 IMPORT(截图导入) */
    public enum TriggerKind { CRON, MANUAL, HOLDING_CHANGE, IMPORT }

    /**
     * v1.4 · 只刷新单个账户(截图导入确认后调用)· 估值事件挂 refImportId,ledger 可展开看导入明细。
     * 复用 valuate + writeBack + 事件写入;没有持仓则不接管(红线不变)。
     */
    public void refreshOneAccount(long familyId, long accountId, TriggerKind trigger,
                                  Long triggeredByMemberId, Long refImportId) {
        refreshOneAccount(familyId, accountId, trigger, triggeredByMemberId, refImportId, null);
    }

    /** v1.18 · 带显式来源的版本(见 {@link #refreshAllForFamily(long, TriggerKind, Long, LedgerSource)})。 */
    public void refreshOneAccount(long familyId, long accountId, TriggerKind trigger,
                                  Long triggeredByMemberId, Long refImportId, LedgerSource explicitSource) {
        Period currentOpen = periodMapper.findBalancePeriod(familyId).orElse(null);
        if (currentOpen == null) return;
        Account acc = accountMapper.findById(familyId, accountId).orElse(null);
        if (acc == null) return;
        List<StockHolding> holdings = holdingMapper.findActiveByAccount(familyId, accountId);
        // 红线:无持仓不接管 · v1.18.1 判据与录入侧同源(见 StockHoldingService.valuationManaged)
        if (!StockHoldingService.valuationManaged(acc.getType(), holdings)) return;
        ValuationResult r = valuateInternal(acc);
        BigDecimal prevBalance = snapshotMapper.findByPeriodAndAccount(familyId, currentOpen.getId(), accountId)
            .map(s -> s.getEndBalance()).orElse(null);
        LedgerSource src = inferSource(explicitSource, trigger, refImportId, holdings);
        // v1.18.3 · 同上:没写回就不写事件(见 writeBackBalance 的返回值说明)
        if (!writeBackBalance(familyId, currentOpen.getId(), acc, r.totalBaseValue(), src)) return;
        recordValuationEventIfChanged(familyId, accountId, currentOpen.getId(),
            prevBalance, r.totalBaseValue(), trigger, triggeredByMemberId, refImportId, src);
        eventPublisher.publishEvent(new com.family.finance.service.lens.LensStaleEvent(familyId));
    }

    private void recordValuationEventIfChanged(long familyId, long accountId, long periodId,
                                               BigDecimal prevBalance, BigDecimal newBalance,
                                               TriggerKind trigger, Long triggeredByMemberId) {
        recordValuationEventIfChanged(familyId, accountId, periodId, prevBalance, newBalance,
                trigger, triggeredByMemberId, null, LedgerSource.UNKNOWN);
    }

    private void recordValuationEventIfChanged(long familyId, long accountId, long periodId,
                                               BigDecimal prevBalance, BigDecimal newBalance,
                                               TriggerKind trigger, Long triggeredByMemberId, Long refImportId) {
        recordValuationEventIfChanged(familyId, accountId, periodId, prevBalance, newBalance,
                trigger, triggeredByMemberId, refImportId, LedgerSource.UNKNOWN);
    }

    /**
     * 推断这次估值的<b>来源</b>(v1.18)—— 用户在流水里看到一笔估值变动,要能分出是拉股价、拉金价还是券商同步。
     *
     * <p>优先级:显式传入的 &gt; 截图导入 &gt; 手动 &gt; 按持仓市场推断。</p>
     *
     * <p>混合持仓(同一账户里既有股票又有黄金)按<b>持仓数量占多</b>的那类算 —— 一次估值只能挂一个来源,
     * 与其编一个"混合"标签,不如取主要那类;真要逐笔精确,得把事件拆到持仓级别,那是另一件事。</p>
     */
    static LedgerSource inferSource(
            LedgerSource explicit,
            TriggerKind trigger, Long refImportId, java.util.List<StockHolding> holdings) {
        if (explicit != null) return explicit;
        if (refImportId != null || trigger == TriggerKind.IMPORT) {
            return LedgerSource.IMPORT_SCREENSHOT;
        }
        if (trigger == TriggerKind.MANUAL) return LedgerSource.MANUAL;
        if (holdings == null || holdings.isEmpty()) return LedgerSource.SYNC_STOCK_API;
        int metal = 0, crypto = 0, stock = 0;
        for (StockHolding h : holdings) {
            if (h.getMarket() == null) { stock++; continue; }
            switch (h.getMarket()) {
                case METAL -> metal++;
                case CRYPTO -> crypto++;
                default -> stock++;
            }
        }
        if (metal >= crypto && metal >= stock) return LedgerSource.SYNC_METAL_API;
        if (crypto >= stock) return LedgerSource.SYNC_CRYPTO_API;
        return LedgerSource.SYNC_STOCK_API;
    }

    private void recordValuationEventIfChanged(long familyId, long accountId, long periodId,
                                               BigDecimal prevBalance, BigDecimal newBalance,
                                               TriggerKind trigger, Long triggeredByMemberId, Long refImportId,
                                               LedgerSource source) {
        if (newBalance == null) return;
        BigDecimal delta = newBalance.subtract(prevBalance == null ? BigDecimal.ZERO : prevBalance);
        if (delta.abs().compareTo(EVENT_THRESHOLD) <= 0) {
            // 微小变化不写事件 · 避免噪音
            return;
        }
        try {
            valuationEventMapper.insert(StockValuationEvent.builder()
                .familyId(familyId)
                .accountId(accountId)
                .periodId(periodId)
                .prevBalance(prevBalance)
                .newBalance(newBalance)
                .delta(delta)
                .triggerKind(trigger == null ? TriggerKind.CRON.name() : trigger.name())
                .triggeredByMemberId(triggeredByMemberId)
                .note(null)
                .refImportId(refImportId)
                .sourceTag((source == null ? LedgerSource.UNKNOWN : source).name())
                .build());
        } catch (Exception e) {
            log.warn("write stock_valuation_event failed · account={} delta={}: {}",
                accountId, delta, e.toString());
        }
    }

    /**
     * 跨家庭刷新(cron 每个市场拉完价后调用)。
     */
    public int refreshAll() {
        // 找所有有 holding 的家庭(distinct family_id from stock_holding → account.family_id)
        // 简化:遍历所有家庭(实际 v0.3 是单家庭)
        // TODO: 多家庭支持时优化为只刷新有 holding 的家庭
        List<Long> familyIds = accountMapper.findActiveByFamily(1L).stream()
            .map(Account::getFamilyId).distinct().toList();
        // 退化:本版只刷 family=1(单家庭设计 · 见 SECURITY.md)
        return refreshAllForFamily(1L);
    }

    // ---------- 内部 ----------

    private ValuationResult valuateInternal(Account acc) {
        List<StockHolding> holdings = holdingMapper.findActiveByAccount(acc.getFamilyId(), acc.getId());
        if (holdings.isEmpty()) {
            return new ValuationResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }

        BigDecimal autoBase = BigDecimal.ZERO;
        BigDecimal manualBase = BigDecimal.ZERO;
        BigDecimal cashBase = BigDecimal.ZERO;
        int staleCount = 0;
        int missingCount = 0;

        for (StockHolding h : holdings) {
            switch (h.getValuationMode()) {
                case MANUAL -> {
                    if (h.getManualValue() != null) {
                        // v0.12 · 未上市持仓 = 股数 × 单股估值(manual_value 语义 = 单股·账户币种)。
                        // 老数据经 V35 迁移 shares=1(1×原整笔值=原值);shares 仍缺则兜底 1(向后兼容)。
                        BigDecimal sh = h.getShares() == null ? BigDecimal.ONE : h.getShares();
                        manualBase = manualBase.add(h.getManualValue().multiply(sh));
                    }
                }
                case CASH -> {
                    // v0.3 FR-52e:账户内某币种现金 · 用 FX 换到账户币种
                    if (h.getManualValue() != null && h.getCurrency() != null) {
                        BigDecimal fxRate = resolveFxRate(acc.getFamilyId(), h.getCurrency(), acc.getCurrency());
                        BigDecimal v = h.getManualValue().multiply(fxRate).setScale(2, RoundingMode.HALF_EVEN);
                        cashBase = cashBase.add(v);
                    }
                }
                case AUTO -> {
                    Optional<StockPriceSnapshot> priceOpt = priceMapper.findLatest(h.getTicker(), h.getMarket().name());
                    if (priceOpt.isEmpty()) {
                        missingCount++;
                        continue;
                    }
                    StockPriceSnapshot price = priceOpt.get();
                    int staleDays = (int) java.time.temporal.ChronoUnit.DAYS.between(
                        price.getTradeDate(), java.time.LocalDate.now());
                    if (staleDays > 7) staleCount++;
                    // v0.14 · METAL:快照是"每克价",按持仓单位(克/盎司)换算到"每持仓单位价"再 × shares
                    BigDecimal effectiveClose = h.getMarket() == Market.METAL
                        ? MetalUnit.perHoldingUnit(h.getUnit(), price.getClosePrice())
                        : price.getClosePrice();
                    BigDecimal originalMarketValue = effectiveClose.multiply(h.getShares());
                    String quoteCurrency = price.getCurrency() == null || price.getCurrency().isBlank()
                        ? h.getCurrency()
                        : price.getCurrency();
                    BigDecimal fxRate = resolveFxRate(acc.getFamilyId(), quoteCurrency, acc.getCurrency());
                    BigDecimal accCurrencyValue = originalMarketValue.multiply(fxRate)
                        .setScale(2, RoundingMode.HALF_EVEN);
                    autoBase = autoBase.add(accCurrencyValue);
                }
            }
        }

        BigDecimal total = autoBase.add(manualBase).add(cashBase);
        return new ValuationResult(total, autoBase, manualBase, cashBase, staleCount, missingCount);
    }

    /**
     * 把估值写回 account_balance(period_snapshot 表)· 复用既有 upsert 逻辑。
     * 这是关键集成点:下游 fact_view / dashboard / XIRR / 目标进度 自动反映,零改动。
     *
     * <p>schema 把 submitted_by NOT NULL · 系统自动写入用 account.primary_owner_member_id 兜底;
     * 若该字段也为 null,用 family 第一个 member。区分系统/用户写入靠 note 标识"系统估值"。</p>
     */
    /**
     * @return true = 真的写回了;false = 没写(拦下 或 定不出 submittedBy)。
     *
     * <p><b>返回值必须被调用方尊重</b>:拦下了却照样写 {@code stock_valuation_event},
     * 等于记了一个<b>没发生过的变化</b> —— 而且那条事件会把「上次估值时间」推到现在,
     * 于是下一次刷新的窗口变空、<b>第二次就拦不住了</b>,钱照样没。
     * 这个洞是 e2e 主线 19 抓出来的(余额掉了两倍的钱),不是推理出来的。</p>
     */
    private boolean writeBackBalance(long familyId, long periodId, Account acc, BigDecimal balance,
                                     LedgerSource source) {
        Long submittedBy = acc.getPrimaryOwnerMemberId();
        if (submittedBy == null) {
            // 兜底:family 第一个 member
            submittedBy = memberMapper.findActiveByFamily(familyId).stream()
                .findFirst().map(m -> m.getId()).orElse(null);
        }
        if (submittedBy == null) {
            log.warn("can't resolve submittedBy for account={} · skip valuation writeback", acc.getId());
            return false;
        }

        // ── v1.18.3 · fail-closed:不许把刚进账户的钱盖掉(复盘方案 B)────────────────
        //   这是全系统唯一一条【自动的、破坏性的、对余额的写】——
        //   period_snapshot 是覆盖写,被盖掉的旧值没有任何地方留底,所以一旦盖错就不可恢复。
        //   v1.18.1 修掉了已知的那条路径(钱现在会落进现金行),这里是兜底:
        //   万一还有我没找到的路径,宁可【这次不写】,也不要把钱抹掉。
        //   判据与事后对账扫描共用一份(ErasureDetector),不许两处各写一套。
        BigDecimal current = snapshotMapper.findByPeriodAndAccount(acc.getFamilyId(), periodId, acc.getId())
                .map(PeriodSnapshot::getEndBalance).orElse(null);
        if (current != null && balance != null) {
            try {
                java.time.LocalDateTime since = valuationEventMapper.lastEventAt(acc.getFamilyId(), acc.getId(), periodId);
                java.util.List<BigDecimal> windowFlows =
                        valuationEventMapper.findFlowsAfter(acc.getFamilyId(), acc.getId(), periodId, since);
                BigDecimal windowFlow = com.family.finance.calc.reconcile.ErasureDetector.erasedAmount(
                        balance.subtract(current), windowFlows,
                        com.family.finance.calc.reconcile.ErasureDetector.MIN_EPSILON);
                if (windowFlow != null) {
                    // 留痕要显式:v1.17.3 的教训是「失败只写日志 = 页面上看不出来」。
                    // 这条会出现在管理页审计日志里,账目对账页也读它。
                    auditLogService.record(familyId, submittedBy,
                            com.family.finance.domain.audit.AuditLogType.SYSTEM,
                            "account", acc.getId(),
                            BLOCKED_WRITEBACK_NOTE + " · 账户=" + acc.getDisplayName()
                                    + " · 这次写回会把本期刚进出的 " + windowFlow + " 抹平,已拒绝覆盖");
                    log.warn("valuation writeback BLOCKED · account={} would erase flow={} (delta={}) · 余额保持不变",
                            acc.getId(), windowFlow, balance.subtract(current));
                    return false;
                }
            } catch (Exception e) {
                // 兜底检查本身不许把估值搞挂 —— 查不动就放行,但要留一行日志
                log.warn("erasure pre-check failed · account={} · 放行本次写回: {}", acc.getId(), e.toString());
            }
        }

        PeriodSnapshot snap = PeriodSnapshot.builder()
            .periodId(periodId)
            .accountId(acc.getId())
            .endBalance(balance)
            .submittedBy(submittedBy)
            .note(SYSTEM_VALUATION_NOTE)
            // v1.18 · 和同一次刷新写的 stock_valuation_event 用同一个来源判定,别两处各算一次
            .sourceTag((source == null ? LedgerSource.UNKNOWN : source).name())
            .build();
        snapshotMapper.upsertOwned(acc.getFamilyId(), snap);
        return true;
    }

    /** v1.18.3 · 估值写回被拦下时的审计标识 —— 账目对账页按它读「被拦下的写回」 */
    public static final String BLOCKED_WRITEBACK_NOTE = "估值写回被拦下(会抹掉刚进账户的钱)";

    /** 系统估值同步写入 period_snapshot 时使用的 note 标识(中文 · 用户面友好) */
    public static final String SYSTEM_VALUATION_NOTE = "系统估值同步";

    /** 历史遗留的英文标识 · ledger 渲染时一并视作系统估值(保留判别能力) */
    public static final String LEGACY_VALUATION_NOTE = "auto-stock-valuation v0.3";

    /**
     * 取 fromCurrency → toCurrency 的汇率(可能跨币种 · 自动经 base 中转)。
     *
     * <p>解析顺序:</p>
     * <ol>
     *   <li>直接 from→to</li>
     *   <li>反向 to→from · 用 1/rate</li>
     *   <li>**经家庭 base 中转** · from→base × base→to(每段各自尝试直接 / 反向)</li>
     * </ol>
     *
     * <p>典型场景:账户 currency=HKD · 持仓 currency=USD · base=CNY · fx_rate 表只存 CNY→other</p>
     * <ul>
     *   <li>查 USD→HKD:直接 ✗ · 反向 ✗</li>
     *   <li>链式:USD→CNY(用 1 / (CNY→USD))× CNY→HKD = 6.80 × 1.152 ≈ 7.83</li>
     * </ul>
     */
    // ---------- v1.1 · 资产透视(lens)· 每持仓在账户币种下的现值/成本 ----------

    /** v1.1 · lens 头寸组装用 · 与 valuateInternal 同口径(改口径需两处同步) */
    public record HoldingLine(com.family.finance.domain.stock.StockHolding holding,
                              BigDecimal valueAcctCcy, BigDecimal costAcctCcy) {}

    /**
     * 每个活跃持仓的账户币种现值 + 成本(供资产透视按行业/地域拆账户)。
     * MANUAL = 单股手填值×股数(账户币种 · v0.12 语义),成本按 costBasis×shares(可空);
     * CASH   = 各币种现金经 FX → 账户币种,成本=现值(现金无持有损益);
     * AUTO   = 最新价×股数(METAL 每克归一)经 FX → 账户币种,缺价按 0 计(与 valuateInternal 的账户合计口径一致),成本可空。
     */
    public List<HoldingLine> perHoldingLines(Account acc) {
        List<StockHolding> holdings = holdingMapper.findActiveByAccount(acc.getFamilyId(), acc.getId());
        List<HoldingLine> out = new java.util.ArrayList<>();
        for (StockHolding h : holdings) {
            BigDecimal value = BigDecimal.ZERO;
            BigDecimal cost = null;
            switch (h.getValuationMode()) {
                case MANUAL -> {
                    BigDecimal sh = h.getShares() == null ? BigDecimal.ONE : h.getShares();
                    if (h.getManualValue() != null) value = h.getManualValue().multiply(sh);
                    if (h.getCostBasis() != null) cost = h.getCostBasis().multiply(sh);
                }
                case CASH -> {
                    if (h.getManualValue() != null && h.getCurrency() != null) {
                        BigDecimal fx = resolveFxRate(acc.getFamilyId(), h.getCurrency(), acc.getCurrency());
                        value = h.getManualValue().multiply(fx);
                    }
                    cost = value; // 现金头寸无持有损益
                }
                case AUTO -> {
                    var priceOpt = priceMapper.findLatest(h.getTicker(), h.getMarket().name());
                    if (priceOpt.isPresent() && h.getShares() != null) {
                        var price = priceOpt.get();
                        BigDecimal effectiveClose = h.getMarket() == Market.METAL
                            ? MetalUnit.perHoldingUnit(h.getUnit(), price.getClosePrice())
                            : price.getClosePrice();
                        String quoteCcy = price.getCurrency() == null || price.getCurrency().isBlank()
                            ? h.getCurrency() : price.getCurrency();
                        BigDecimal fx = resolveFxRate(acc.getFamilyId(), quoteCcy, acc.getCurrency());
                        value = effectiveClose.multiply(h.getShares()).multiply(fx);
                        if (h.getCostBasis() != null && h.getCurrency() != null) {
                            BigDecimal cfx = resolveFxRate(acc.getFamilyId(), h.getCurrency(), acc.getCurrency());
                            cost = h.getCostBasis().multiply(h.getShares()).multiply(cfx);
                        }
                    }
                }
            }
            out.add(new HoldingLine(h,
                value.setScale(2, RoundingMode.HALF_EVEN),
                cost == null ? null : cost.setScale(2, RoundingMode.HALF_EVEN)));
        }
        return out;
    }

    /**
     * v1.26 · 某币种 → 该账户币种的汇率。券商同步里「拉不到价、按券商收盘价估值」的持仓(IBKR 的伦敦 / 东京 …)
     * 要以手动估值行落库,而手动估值行的单价语义是<b>账户币种</b> —— 折算与现金行用同一套取价。
     */
    public BigDecimal fxToAccountCurrency(long familyId, long accountId, String fromCurrency) {
        Account acc = accountMapper.findById(familyId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + accountId));
        return resolveFxRate(familyId, fromCurrency, acc.getCurrency());
    }

    /** v1.29 · 账户币种(同步结果里写「市值 USD 640」用);账户不存在返回 null */
    public String accountCurrency(long familyId, long accountId) {
        return accountMapper.findById(familyId, accountId).map(Account::getCurrency).orElse(null);
    }

    private BigDecimal resolveFxRate(long familyId, String fromCurrency, String toCurrency) {
        if (fromCurrency == null || toCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return BigDecimal.ONE;
        }
        Period current = periodMapper.findBalancePeriod(familyId).orElse(null);
        if (current == null) return BigDecimal.ONE;

        // 1 + 2: 直接 + 反向
        BigDecimal direct = directOrReverse(familyId, fromCurrency, toCurrency, current.getId());
        if (direct != null) return direct;

        // 3: 经家庭 base 中转
        String base = familyService.require(familyId).getBaseCurrency();
        if (base != null
                && !base.equalsIgnoreCase(fromCurrency)
                && !base.equalsIgnoreCase(toCurrency)) {
            BigDecimal fromToBase = directOrReverse(familyId, fromCurrency, base, current.getId());
            BigDecimal baseToOut  = directOrReverse(familyId, base, toCurrency, current.getId());
            if (fromToBase != null && baseToOut != null) {
                BigDecimal chained = fromToBase.multiply(baseToOut).setScale(8, RoundingMode.HALF_EVEN);
                log.debug("fx chained · {}→{}→{} = {}", fromCurrency, base, toCurrency, chained);
                return chained;
            }
        }

        log.warn("no fx rate · {}/{} · family={} · using 1.0 fallback", fromCurrency, toCurrency, familyId);
        return BigDecimal.ONE;
    }

    /** 尝试 from→to · 失败试反向 to→from 取倒数 · 都失败返回 null。 */
    private BigDecimal directOrReverse(long familyId, String from, String to, long periodId) {
        var d = fxService.getOrFetchRate(familyId, from, to, periodId);
        if (d.isPresent() && d.get().getRate() != null && d.get().getRate().signum() > 0) {
            return d.get().getRate();
        }
        var r = fxService.getOrFetchRate(familyId, to, from, periodId);
        if (r.isPresent() && r.get().getRate() != null && r.get().getRate().signum() > 0) {
            return BigDecimal.ONE.divide(r.get().getRate(), 8, RoundingMode.HALF_EVEN);
        }
        return null;
    }

    /**
     * 估值结果值对象。
     *
     * @param totalBaseValue  账户余额(账户币种 · 写入 account_balance)= auto+manual+cash
     * @param autoBaseValue   AUTO 持仓合计(账户币种)
     * @param manualBaseValue MANUAL 持仓合计(账户币种 · 用户直接填)
     * @param cashBaseValue   CASH 行合计(账户币种 · 各行 currency 已 FX 至账户币种)· v0.3 FR-52e
     * @param staleCount      陈旧 > 7 天的 AUTO 持仓数
     * @param missingCount    完全无价的 AUTO 持仓数
     */
    public record ValuationResult(
        BigDecimal totalBaseValue,
        BigDecimal autoBaseValue,
        BigDecimal manualBaseValue,
        BigDecimal cashBaseValue,
        int staleCount,
        int missingCount
    ) {
        public boolean hasIssues() { return staleCount > 0 || missingCount > 0; }
    }
}
