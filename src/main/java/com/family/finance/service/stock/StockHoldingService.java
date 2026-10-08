package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.StockHoldingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 股票持仓 CRUD · v0.3 FR-52。
 *
 * <p>负责:</p>
 * <ul>
 *   <li>持仓级 AUTO/MANUAL 模式校验(必填字段)</li>
 *   <li>账户类型校验(允许 STOCK / CRYPTO 类型加持仓)</li>
 *   <li>权限校验(account 必须属于当前 family)</li>
 *   <li>ticker 规范化(US 大写 / CN 6 位 / HK 5 位前导零)</li>
 *   <li>AUTO/MANUAL 模式互转(用户从自动改手填时,manual_value 由 caller 传当前估值)</li>
 * </ul>
 *
 * <p>不负责:实际拉价(那是 StockPriceFetcher 的事)/ 估值算账户余额(AccountValuationService)。</p>
 */
@Service
@RequiredArgsConstructor
public class StockHoldingService {

    private final StockHoldingMapper holdingMapper;
    private final AccountMapper accountMapper;
    // v0.5 FR-78/79 · 现金联动(FX 换算 + 市价加回)
    private final com.family.finance.service.FxService fxService;
    private final com.family.finance.repository.PeriodMapper periodMapper;
    private final StockPriceFetcher stockPriceFetcher;
    // v0.8 Problem B · 手动改现金行记调整流水(剔出投资损益)
    private final com.family.finance.repository.CashFlowMapper cashFlowMapper;

    public List<StockHolding> findActiveByAccount(long familyId, long accountId) {
        requireHoldingAccount(familyId, accountId);
        return holdingMapper.findActiveByAccount(familyId, accountId);
    }

    public List<StockHolding> findAllByAccount(long familyId, long accountId) {
        requireHoldingAccount(familyId, accountId);
        return holdingMapper.findAllByAccount(familyId, accountId);
    }

    public StockHolding require(long familyId, long holdingId) {
        StockHolding h = holdingMapper.findById(familyId, holdingId)
            .orElseThrow(() -> new IllegalArgumentException("持仓不存在: " + holdingId));
        // 校验账户属于家庭
        Account acc = accountMapper.findById(familyId, h.getAccountId())
            .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + h.getAccountId()));
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问持仓");
        }
        return h;
    }

    /** v1.1 · 改个股行业标(资产透视维度)· 归属校验走 require */
    @Transactional
    public void updateIndustry(long familyId, long holdingId, String industryTag) {
        require(familyId, holdingId);
        holdingMapper.updateIndustry(familyId, holdingId, industryTag);
    }

    @Transactional
    public StockHolding createAuto(long familyId, long accountId,
                                   String displayName, String ticker, Market market,
                                   BigDecimal shares, BigDecimal costBasis, String currency) {
        return createAuto(familyId, accountId, displayName, ticker, market, shares, costBasis, currency, false);
    }

    /**
     * v0.5 FR-78 · 录 AUTO 持仓 · deductCash=true 时从账户现金划转买入。
     * 勾选 → 强制 costBasis;按 股数×成本 经 FX 换到账户币种,从账户币种现金行扣减(可为负 · 卖空/融资)。
     * 这是账户内「现金 → 股票」再分配,买入当刻净值中性。
     */
    @Transactional
    public StockHolding createAuto(long familyId, long accountId,
                                   String displayName, String ticker, Market market,
                                   BigDecimal shares, BigDecimal costBasis, String currency, boolean deductCash) {
        Account account = requireHoldingAccount(familyId, accountId);
        validateMarketForAccount(account.getType(), market);
        String normalizedTicker = normalizeTicker(market, ticker);
        validateAuto(normalizedTicker, market, shares, currency);
        String holdingCcy = currency != null && !currency.isBlank()
            ? currency.toUpperCase(Locale.ROOT) : market.defaultCurrency();
        if (deductCash && (costBasis == null || costBasis.signum() <= 0)) {
            throw new IllegalArgumentException("勾选「从账户现金划转买入」时,买入成本必填且 > 0");
        }
        StockHolding h = StockHolding.builder()
            .accountId(accountId)
            .displayName(blankToDefault(displayName, normalizedTicker))
            .valuationMode(ValuationMode.AUTO)
            .ticker(normalizedTicker)
            .market(market)
            .shares(shares)
            .costBasis(costBasis)
            .currency(holdingCcy)
            .cashLinked(deductCash)
            .build();
        holdingMapper.insertOwned(familyId, h);
        if (deductCash) {
            // 买入成本(持仓币种)→ FX 到账户币种 → 扣账户币种现金行(可为负)
            BigDecimal costInHoldingCcy = costBasis.multiply(shares);
            adjustAccountCash(familyId, accountId, holdingCcy, costInHoldingCcy.negate());
        }
        return h;
    }

    /**
     * v0.12 · 创建 MANUAL(未上市)持仓 · 「股数 × 单股估值」模型。
     * manual_value 语义 = 单股估值(账户币种);总市值 = shares × manual_value(见 AccountValuationService)。
     * 适合未上市/私募/拉不到价(如字节跳动期权):用户填持股数 + 自己认可的单股估值。
     */
    @Transactional
    public StockHolding createManual(long familyId, long accountId,
                                     String displayName, BigDecimal shares, BigDecimal unitValue) {
        requireHoldingAccount(familyId, accountId);
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName 必填");
        }
        if (shares == null || shares.signum() <= 0) {
            throw new IllegalArgumentException("股数必须 > 0");
        }
        if (unitValue == null || unitValue.signum() < 0) {
            throw new IllegalArgumentException("单股估值必须 ≥ 0");
        }
        StockHolding h = StockHolding.builder()
            .accountId(accountId)
            .displayName(displayName.trim())
            .valuationMode(ValuationMode.MANUAL)
            .shares(shares)
            .manualValue(unitValue)   // 单股估值
            .manualValueAt(LocalDateTime.now())
            .cashLinked(false)
            .build();
        holdingMapper.insertOwned(familyId, h);
        return h;
    }

    /**
     * v0.14 · 创建 METAL(贵金属)AUTO 持仓 · issue #4。
     * 品种(AU/AG/PT/PD)× 源(sge/intl)→ ticker;currency 随源(SGE=CNY / 国际=USD);
     * shares = 持仓量(按 unit 计:克/盎司);costBasis = 买入价/每单位。价格由 MetalPriceClient 自动拉。
     */
    @Transactional
    public StockHolding createMetal(long familyId, long accountId, String metal, String source,
                                    BigDecimal shares, String unit, BigDecimal costBasis) {
        Account account = requireHoldingAccount(familyId, accountId);
        if (account.getType() != AccountType.METAL) {
            throw new IllegalArgumentException("仅贵金属账户可加金属持仓 · 当前类型 " + account.getType());
        }
        String ticker = MetalUnit.tickerFor(metal, source);
        if (ticker == null) {
            throw new IllegalArgumentException("该品种在所选价格源无盘(如钯金无上海盘,请改选国际现货)");
        }
        if (shares == null || shares.signum() <= 0) {
            throw new IllegalArgumentException("持仓量必须 > 0");
        }
        if (costBasis != null && costBasis.signum() < 0) {
            throw new IllegalArgumentException("买入价必须 ≥ 0");
        }
        String u = MetalUnit.OUNCE.equalsIgnoreCase(unit) ? MetalUnit.OUNCE : MetalUnit.GRAM;
        String ccy = MetalUnit.currencyOf(ticker);
        String name = MetalUnit.metalLabel(ticker) + (MetalUnit.isInternational(ticker) ? " · 国际" : " · 上海");
        StockHolding h = StockHolding.builder()
            .accountId(accountId)
            .displayName(name)
            .valuationMode(ValuationMode.AUTO)
            .ticker(ticker)
            .market(Market.METAL)
            .shares(shares)
            .costBasis(costBasis)
            .currency(ccy)
            .unit(u)
            .cashLinked(false)
            .build();
        holdingMapper.insertOwned(familyId, h);
        return h;
    }

    /**
     * v0.12 · 更新 MANUAL 持仓的股数 / 单股估值(刷新 manual_value_at)。
     * 二者任一为 null 表示不改该项(至少改一项)。
     */
    @Transactional
    public StockHolding updateManual(long familyId, long holdingId, BigDecimal shares, BigDecimal unitValue) {
        StockHolding h = require(familyId, holdingId);
        if (h.getValuationMode() != ValuationMode.MANUAL) {
            throw new IllegalArgumentException("仅未上市(手填)持仓可用此接口更新");
        }
        // v1.30 · 净值行的单价由系统按净值写:这里改了,下一次刷新就被盖掉,用户还以为改成功了(护栏 v130-NAV-NOT-HAND-EDITED)
        if (h.isNavRow()) {
            throw new IllegalArgumentException("基金的净值由系统更新,只能改份额");
        }
        if (shares != null) {
            if (shares.signum() <= 0) throw new IllegalArgumentException("股数必须 > 0");
            h.setShares(shares);
        }
        if (unitValue != null) {
            if (unitValue.signum() < 0) throw new IllegalArgumentException("单股估值必须 ≥ 0");
            h.setManualValue(unitValue);
        }
        h.setManualValueAt(LocalDateTime.now());
        holdingMapper.update(familyId, h);
        return h;
    }

    /**
     * v0.12 · 给已有 AUTO/MANUAL 持仓增/减股数(收入 +股数 / 删除冲回 -股数)。
     * 不改单价、不碰现金行;调用方负责随后 applyDeltaToBalance 与记流水。
     */
    @Transactional
    public StockHolding addShares(long familyId, long holdingId, BigDecimal deltaShares) {
        StockHolding h = require(familyId, holdingId);
        if (h.getValuationMode() != ValuationMode.AUTO && h.getValuationMode() != ValuationMode.MANUAL) {
            throw new IllegalArgumentException("仅上市/未上市持仓可增减股数");
        }
        // v1.30 · 基金份额只走 FundHoldingService(要记持仓数量变动、可选现金联动);这里加份额会被记成股票收入
        if (h.isNavRow()) {
            throw new IllegalArgumentException("基金的份额请在持仓页「改份额」里改");
        }
        if (deltaShares == null || deltaShares.signum() == 0) return h;
        BigDecimal cur = h.getShares() == null ? BigDecimal.ZERO : h.getShares();
        BigDecimal next = cur.add(deltaShares);
        if (next.signum() < 0) next = BigDecimal.ZERO;   // 冲回不至于负股
        h.setShares(next);
        holdingMapper.update(familyId, h);
        return h;
    }

    /**
     * v0.12 · 持仓当前「单股估值」换算到账户币种:
     *   AUTO   最新已知价 × FX(持仓币种 → 账户币种);无价 → 返回 null(调用方拒绝或提示先刷价)
     *   MANUAL manual_value(已是账户币种单股估值)
     *   CASH   不适用 → null
     */
    public BigDecimal currentUnitValueInAccountCcy(long familyId, StockHolding h) {
        if (h == null) return null;
        Account acc = accountMapper.findById(familyId, h.getAccountId()).orElse(null);
        String acctCcy = acc != null && acc.getCurrency() != null ? acc.getCurrency() : null;
        return switch (h.getValuationMode()) {
            case MANUAL -> h.getManualValue();
            case AUTO -> {
                var snap = stockPriceFetcher.findLatestKnown(h.getTicker(), h.getMarket());
                if (snap == null || snap.getClosePrice() == null) yield null;
                // v0.14 · METAL 快照是"每克价" → 先换算到"每持仓单位价"(克/盎司)
                BigDecimal unitPrice = h.getMarket() == Market.METAL
                    ? MetalUnit.perHoldingUnit(h.getUnit(), snap.getClosePrice())
                    : snap.getClosePrice();
                String quoteCurrency = snap.getCurrency() == null || snap.getCurrency().isBlank()
                    ? h.getCurrency()
                    : snap.getCurrency();
                yield fxConvert(familyId, unitPrice, quoteCurrency, acctCcy);
            }
            case CASH -> null;
        };
    }

    /**
     * 创建 CASH 行(账户内某币种闲置现金)· v0.3 FR-52e。
     *
     * @param currency 现金币种(必填 · USD/CNY/HKD/JPY/...)· 可以与账户币种不同 · 估值时自动 FX
     * @param amount   该币种金额(必填 · ≥ 0)
     */
    @Transactional
    public StockHolding createCash(long familyId, long accountId,
                                   String displayName, String currency, BigDecimal amount) {
        requireHoldingAccount(familyId, accountId);
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency 必填(USD/CNY/HKD/...)");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount 必须 ≥ 0");
        }
        String normCcy = currency.trim().toUpperCase(Locale.ROOT);
        String label = (displayName == null || displayName.isBlank())
            ? normCcy + " 现金"
            : displayName.trim();
        StockHolding h = StockHolding.builder()
            .accountId(accountId)
            .displayName(label)
            .valuationMode(ValuationMode.CASH)
            .currency(normCcy)
            .manualValue(amount)
            .manualValueAt(LocalDateTime.now())
            .cashLinked(false)
            .build();
        holdingMapper.insertOwned(familyId, h);
        return h;
    }

    /**
     * 更新 CASH 行的金额(刷新 manual_value_at)· 币种不变。
     * 想改币种?archive 后重建。
     */
    @Transactional
    public StockHolding updateCashAmount(long familyId, long holdingId, BigDecimal newAmount) {
        StockHolding h = require(familyId, holdingId);
        if (h.getValuationMode() != ValuationMode.CASH) {
            throw new IllegalArgumentException("仅 CASH 行可用此接口更新金额");
        }
        if (newAmount == null || newAmount.signum() < 0) {
            throw new IllegalArgumentException("金额必须 ≥ 0");
        }
        java.math.BigDecimal old = h.getManualValue() == null ? java.math.BigDecimal.ZERO : h.getManualValue();
        h.setManualValue(newAmount);
        h.setManualValueAt(LocalDateTime.now());
        holdingMapper.update(familyId, h);
        // v0.8 Problem B:手动调现金 = 本金进出,不是投资损益。记一笔 is_adjustment 流水把这笔 Δ 从 PnL/收益率剔除。
        recordCashAdjustment(familyId, h.getAccountId(), newAmount.subtract(old));
        return h;
    }

    /** 把手动改现金行的 Δ 记成调整流水(剔出投资损益);best-effort:无 OPEN 期 / 账户无主理人则跳过。 */
    private void recordCashAdjustment(long familyId, long accountId, java.math.BigDecimal delta) {
        if (delta == null || delta.signum() == 0) return;
        var periodOpt = periodMapper.findBalancePeriod(familyId);
        if (periodOpt.isEmpty()) return;
        var acct = accountMapper.findById(familyId, accountId).orElse(null);
        Long memberId = acct == null ? null : acct.getPrimaryOwnerMemberId();
        if (memberId == null) return;   // submitted_by 必填,无主理人则不记(不影响余额本身)
        var period = periodOpt.get();
        com.family.finance.domain.flow.CashFlow cf = com.family.finance.domain.flow.CashFlow.builder()
                .periodId(period.getId())
                .accountId(accountId)
                .kind(delta.signum() > 0 ? com.family.finance.domain.flow.CashFlowKind.INCOME
                                         : com.family.finance.domain.flow.CashFlowKind.EXPENSE)
                .categoryCode("cash_adjust")
                .amount(delta.abs())
                .occurredAt(period.getPeriodEnd())
                .note("股票账户现金行调整(剔出投资损益)")
                .submittedBy(memberId)
                .adjustment(true)
                // v1.18 · 这笔不是人填的:人改的是现金行,这条流水是系统为剔出损益派生的
                .sourceTag(com.family.finance.domain.ledger.LedgerSource.SYSTEM_ADJUST.name())
                .build();
        cashFlowMapper.insertOwned(familyId, cf);
    }

    /**
     * AUTO → MANUAL 转换 · 用户决定不再拉价(如停牌 / 退市 / 用户不信任数据源)。
     * 当前估值由 caller 计算后传入(AccountValuationService 会算)。
     */
    @Transactional
    public StockHolding convertToManual(long familyId, long holdingId, BigDecimal currentValueAsBaseline) {
        StockHolding h = require(familyId, holdingId);
        if (h.getValuationMode() != ValuationMode.AUTO) return h;
        h.setValuationMode(ValuationMode.MANUAL);
        // v0.12 · MANUAL 语义 = 单股估值(总 = shares × manual_value)。保留 AUTO 的 shares,
        // 把传入的「整笔当前市值」除以股数折成单股估值 → 总值守恒;shares 缺失/≤0 或 baseline≤0 则退回整笔口径(shares=1)。
        BigDecimal baseline = currentValueAsBaseline == null ? BigDecimal.ZERO : currentValueAsBaseline;
        BigDecimal sh = h.getShares();
        if (sh != null && sh.signum() > 0 && baseline.signum() > 0) {
            h.setManualValue(baseline.divide(sh, 4, java.math.RoundingMode.HALF_EVEN));
        } else {
            h.setShares(BigDecimal.ONE);
            h.setManualValue(baseline);
        }
        h.setManualValueAt(LocalDateTime.now());
        holdingMapper.update(familyId, h);
        return h;
    }

    @Transactional
    public void archive(long familyId, long holdingId) {
        StockHolding h = require(familyId, holdingId); // 权限校验
        // v0.5 FR-79 · 现金联动持仓归档 → 对称按市价把现金加回账户币种现金行(全对称)
        if (Boolean.TRUE.equals(h.getCashLinked()) && h.getValuationMode() == ValuationMode.AUTO
                && h.getTicker() != null && h.getMarket() != null && h.getShares() != null) {
            BigDecimal proceeds = sellProceedsInHoldingCcy(h);
            if (proceeds != null && proceeds.signum() > 0) {
                adjustAccountCash(familyId, h.getAccountId(), h.getCurrency(), proceeds);
            }
        }
        holdingMapper.archive(familyId, holdingId);
    }

    /** 卖出收回金额(持仓币种)= 股数 × 当前市价;无价时退回成本价。 */
    private BigDecimal sellProceedsInHoldingCcy(StockHolding h) {
        var snap = stockPriceFetcher.findLatestKnown(h.getTicker(), h.getMarket());
        BigDecimal price = snap != null ? snap.getClosePrice() : h.getCostBasis();
        if (price == null) return null;
        return price.multiply(h.getShares());
    }

    /**
     * v0.5 FR-78/79 · 调整账户币种现金行 · deltaInFromCcy 为正=加回 / 负=扣减(持仓币种口径)。
     * 经 FX 换到账户币种 → 找该币种 CASH 行调整 · 没有则新建(可为负 · 卖空/融资/保证金)。
     * v0.12 起 public:收入侧对股票账户录入收入时,把外部进钱落到 CASH 现金行(扛过估值刷新)· 见 EntryService.recordIncome。
     */
    @Transactional
    public void adjustAccountCash(long familyId, long accountId, String fromCcy, BigDecimal deltaInFromCcy) {
        var account = accountMapper.findById(familyId, accountId).orElse(null);
        String acctCcy = account != null && account.getCurrency() != null
            ? account.getCurrency().toUpperCase(Locale.ROOT) : fromCcy;
        BigDecimal deltaInAcctCcy = fxConvert(familyId, deltaInFromCcy, fromCcy, acctCcy);

        StockHolding cashRow = holdingMapper.findActiveByAccount(familyId, accountId).stream()
            .filter(x -> x.getValuationMode() == ValuationMode.CASH
                && acctCcy.equalsIgnoreCase(x.getCurrency()))
            .findFirst().orElse(null);
        if (cashRow != null) {
            BigDecimal base = cashRow.getManualValue() == null ? BigDecimal.ZERO : cashRow.getManualValue();
            cashRow.setManualValue(base.add(deltaInAcctCcy).setScale(2, java.math.RoundingMode.HALF_EVEN));
            cashRow.setManualValueAt(LocalDateTime.now());
            holdingMapper.update(familyId, cashRow);
        } else {
            StockHolding row = StockHolding.builder()
                .accountId(accountId)
                .displayName(acctCcy + " 现金")
                .valuationMode(ValuationMode.CASH)
                .currency(acctCcy)
                .manualValue(deltaInAcctCcy.setScale(2, java.math.RoundingMode.HALF_EVEN))
                .manualValueAt(LocalDateTime.now())
                .cashLinked(false)
                .build();
            holdingMapper.insertOwned(familyId, row);
        }
    }

    /** FX 换算(持仓币种 → 账户币种)· 同币种直接返回 · 走当前 OPEN 周期汇率 · 缺则 1:1 兜底。 */
    private BigDecimal fxConvert(long familyId, BigDecimal amount, String from, String to) {
        if (amount == null) return BigDecimal.ZERO;
        if (from == null || to == null || from.equalsIgnoreCase(to)) return amount;
        var period = periodMapper.findBalancePeriod(familyId).orElse(null);
        if (period == null) return amount;
        var rate = fxService.getOrFetchRate(familyId, from, to, period.getId());
        if (rate.isPresent() && rate.get().getRate() != null && rate.get().getRate().signum() > 0) {
            return amount.multiply(rate.get().getRate()).setScale(2, java.math.RoundingMode.HALF_EVEN);
        }
        var inv = fxService.getOrFetchRate(familyId, to, from, period.getId());
        if (inv.isPresent() && inv.get().getRate() != null && inv.get().getRate().signum() > 0) {
            return amount.divide(inv.get().getRate(), 2, java.math.RoundingMode.HALF_EVEN);
        }
        return amount; // 兜底 1:1
    }

    @Transactional
    public void restore(long familyId, long holdingId) {
        StockHolding h = holdingMapper.findById(familyId, holdingId).orElseThrow(
            () -> new IllegalArgumentException("持仓不存在: " + holdingId));
        Account acc = accountMapper.findById(familyId, h.getAccountId()).orElseThrow();
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问持仓");
        }
        holdingMapper.restore(familyId, holdingId);
    }

    // ---------- 校验 ----------

    private Account requireHoldingAccount(long familyId, long accountId) {
        Account acc = accountMapper.findById(familyId, accountId)
            .orElseThrow(() -> new IllegalArgumentException("账户不存在: " + accountId));
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问账户");
        }
        if (!supportsHoldings(acc.getType())) {
            throw new IllegalArgumentException("该账户类型不能挂持仓(支持:股票 / 现金 / 理财 / 基金 / 加密 / 贵金属)· 当前类型 " + acc.getType());
        }
        return acc;
    }

    /**
     * v1.18.1 · 这个账户的余额是不是<b>由持仓估值接管</b>的。
     *
     * <p>判据必须与 {@code AccountValuationService} 的接管判据<b>逐字一致</b>:
     * 支持持仓的类型 <b>且</b> 真的有未归档持仓。两个条件缺一不可 ——
     * 只看类型会把「没有任何持仓的现金/理财账户」也算进来(那些账户的快照就是余额真值,
     * 给它凭空造一行现金是错的);只看持仓则会漏掉类型红线。</p>
     *
     * <p><b>为什么要有这个方法</b>:v0.12 判「余额变动要不要落到现金行」用的是
     * {@code type == STOCK},而估值接管用的是上面那条 —— 两者不一致,于是
     * <b>WEALTH/CRYPTO/METAL 且有持仓的账户</b>(如带基金持仓的余额宝)收到划转后,
     * 钱只加到快照上、没进现金行,下一次自动估值按「持仓合计」把快照覆盖掉,
     * <b>那笔钱就从余额里消失了</b>(生产实测:一笔转入 7.5w 被抹掉,家庭净资产少算)。
     * 一份判据两处用,才不会再漂。</p>
     */
    public static boolean valuationManaged(AccountType type, java.util.List<StockHolding> activeHoldings) {
        return supportsHoldings(type) && activeHoldings != null && !activeHoldings.isEmpty();
    }

    /** 同上 · 自己查持仓的便捷重载(调用方手上没有持仓列表时用)。 */
    public boolean valuationManaged(com.family.finance.domain.account.Account acc) {
        return acc != null && valuationManaged(acc.getType(), holdingMapper.findActiveByAccount(acc.getFamilyId(), acc.getId()));
    }

    public static boolean supportsHoldings(AccountType type) {
        // v1.4 · 放开 WEALTH/CASH(基金/理财/支付宝)· 支持截图导入多真实持仓。
        // 红线不变:没有持仓的账户,AccountValuationService.holdings.isEmpty()→skip,系统绝不碰其手填余额。
        // v1.30 · + FUND(基金账户就是为挂基金持仓加的)
        return type == AccountType.STOCK || type == AccountType.CRYPTO || type == AccountType.METAL
                || type == AccountType.WEALTH || type == AccountType.CASH || type == AccountType.FUND;
    }

    private void validateMarketForAccount(AccountType accountType, Market market) {
        if (market == null) {
            throw new IllegalArgumentException("market 必填");
        }
        if (accountType == AccountType.CRYPTO && market != Market.CRYPTO) {
            throw new IllegalArgumentException("CRYPTO 账户只能添加 CRYPTO 市场持仓");
        }
        if (accountType == AccountType.METAL && market != Market.METAL) {
            throw new IllegalArgumentException("贵金属账户只能添加 METAL 市场持仓");
        }
        if (accountType == AccountType.STOCK && (market == Market.CRYPTO || market == Market.METAL)) {
            throw new IllegalArgumentException("股票账户只能添加 US / CN / HK 市场持仓");
        }
    }

    private void validateAuto(String ticker, Market market, BigDecimal shares, String currency) {
        if (ticker == null || ticker.isBlank()) throw new IllegalArgumentException("ticker 必填");
        if (market == null) throw new IllegalArgumentException("market 必填");
        if (shares == null || shares.signum() <= 0) throw new IllegalArgumentException("shares 必须 > 0");
        // currency 可空 · 用 market.defaultCurrency() 兜底
    }

    /**
     * Ticker 规范化:
     *   US 大写字母(BABA)
     *   CN 6 位数字(600519 / 000001)
     *   HK 5 位前导零(0700 → 00700)
     *   CRYPTO 大写字母/数字(BTC / ETH / USDC)
     */
    static String normalizeTicker(Market market, String raw) {
        if (raw == null) return null;
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (market == null) return t;
        return switch (market) {
            case US -> t;
            case CN -> t.replaceAll("\\D", ""); // 移除非数字字符
            case CRYPTO -> t.replaceAll("[-/](USD|USDT)$", "").replaceAll("[^A-Z0-9]", "");
            case METAL -> t.replaceAll("[^A-Z0-9]", ""); // AU9999 / XAU / AGTD 等,保字母数字
            case HK -> {
                String digits = t.replaceAll("\\D", "");
                if (digits.length() < 5) {
                    digits = "0".repeat(5 - digits.length()) + digits;
                }
                yield digits;
            }
        };
    }

    private static String blankToDefault(String name, String ticker) {
        return name == null || name.isBlank() ? ticker : name.trim();
    }
}
