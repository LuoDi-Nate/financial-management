package com.family.finance.service.fund;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.stock.HoldingShareEvent;
import com.family.finance.domain.stock.NavMode;
import com.family.finance.domain.stock.ShareEventReason;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FundNavSnapshotMapper;
import com.family.finance.repository.HoldingShareEventMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.penetration.EastMoneyFundClient;
import com.family.finance.service.penetration.FundPenetrationService;
import com.family.finance.service.stock.AccountValuationService;
import com.family.finance.service.stock.StockHoldingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * v1.30 · 用户对场外基金 / 货币基金做的每一个动作(PRD FR-959~961 / 965 / 967 / 970)。
 *
 * <p>所有写都在这里;每个动作写一条「持仓数量变动」(只给人看),余额交给估值服务按 MANUAL 支重算。
 * 勾了「用账户里的现金」→ 走 {@link StockHoldingService#adjustAccountCash}(股票的「买入扣现金」同一个方法),
 * 余额不变、不产生估值事件。</p>
 *
 * <p>事务边界:取净值(网络)在事务外,写库(持仓 + 现金行 + 持仓数量变动)在一个事务里,估值写回在提交之后 ——
 * 不让一次网络请求占着数据库连接,也不让「份额改了、事件没记上」这种半截状态落库。</p>
 */
@Slf4j
@Service
public class FundHoldingService {

    private final FundCatalog catalog;
    private final EastMoneyFundClient client;
    private final FundNavSnapshotMapper snapMapper;
    private final StockHoldingMapper holdingMapper;
    private final HoldingShareEventMapper shareMapper;
    private final AccountMapper accountMapper;
    private final PeriodMapper periodMapper;
    private final StockHoldingService stockHoldingService;
    private final AccountValuationService valuationService;
    private final FundPenetrationService penetrationService;
    private final TransactionTemplate tx;

    public FundHoldingService(FundCatalog catalog, EastMoneyFundClient client, FundNavSnapshotMapper snapMapper,
                              StockHoldingMapper holdingMapper, HoldingShareEventMapper shareMapper,
                              AccountMapper accountMapper, PeriodMapper periodMapper,
                              StockHoldingService stockHoldingService, AccountValuationService valuationService,
                              FundPenetrationService penetrationService, PlatformTransactionManager tm) {
        this.catalog = catalog;
        this.client = client;
        this.snapMapper = snapMapper;
        this.holdingMapper = holdingMapper;
        this.shareMapper = shareMapper;
        this.accountMapper = accountMapper;
        this.periodMapper = periodMapper;
        this.stockHoldingService = stockHoldingService;
        this.valuationService = valuationService;
        this.penetrationService = penetrationService;
        this.tx = tm == null ? null : new TransactionTemplate(tm);
    }

    private void inTx(Runnable r) {
        if (tx == null) r.run(); else tx.executeWithoutResult(st -> r.run());
    }

    /** 估算份额存 4 位(选型八:改为自动估值前后余额差 < 0.01) */
    static final int SHARE_SCALE = 4;

    // ---------- 能不能用(PRD §3.1)----------

    /** 「添加持仓」里有没有「场外基金」:基金 / 证券 / 理财 / 现金账户,且账户币种是人民币(选型七) */
    public static boolean accountAllowsFunds(AccountType type, String currency) {
        if (type == null) return false;
        boolean typeOk = type == AccountType.FUND || type == AccountType.STOCK
                || type == AccountType.WEALTH || type == AccountType.CASH;
        return typeOk && "CNY".equalsIgnoreCase(currency == null ? "" : currency.trim());
    }

    public static boolean accountAllowsFunds(Account a) {
        return a != null && accountAllowsFunds(a.getType(), a.getCurrency());
    }

    // ---------- 报价(添加页第 2 步)----------

    /** 选中某只基金后给页面看的:分类 + 最新净值(货基是最新万份收益)+ 拿不到时的原因 */
    public record Quote(FundCatalog.Classified fund, LocalDate navDate, BigDecimal unitNav,
                        BigDecimal incomePer10k, BigDecimal yield7d, String error) {
        public boolean ok() { return error == null; }
    }

    public Quote quote(long familyId, String code) {
        FundCatalog.Classified c = catalog.find(familyId, code);
        if (c == null) return new Quote(null, null, null, null, null, "代码表里没有这只基金");
        if (!c.supported()) return new Quote(c, null, null, null, null, c.reason());
        if (c.kind() == FundCatalog.Kind.MMF) {
            LocalDate today = LocalDate.now(FundNavService.CN);
            FundNavSnapshotMapper.Row last = snapMapper.findLatestIncome(code);
            if (last == null || last.navDate().isBefore(today.minusDays(3))) {
                var inc = client.mmfIncome(familyId, code, today.minusDays(10), today);
                if (inc.ok()) {
                    for (var d : inc.days()) {
                        snapMapper.upsert(new FundNavSnapshotMapper.Row(code, d.date(), null, d.per10k(), d.yield7d(),
                                "eastmoney-lsjz", null));
                    }
                }
                last = snapMapper.findLatestIncome(code);
            }
            return last == null ? new Quote(c, null, null, null, null, "现在拿不到这只货币基金的收益数据,稍后再试")
                    : new Quote(c, last.navDate(), BigDecimal.ONE, last.incomePer10k(), last.yield7d(), null);
        }
        FundNavSnapshotMapper.Row snap = latestNavFresh(familyId, code);
        return snap == null ? new Quote(c, null, null, null, null, "现在拿不到这只基金的净值,稍后再试")
                : new Quote(c, snap.navDate(), snap.unitNav(), null, null, null);
    }

    /** 30 分钟内拉过就用快照;否则现拉一次 */
    private FundNavSnapshotMapper.Row latestNavFresh(long familyId, String code) {
        LocalDateTime last = snapMapper.lastFetchedAt(code);
        FundNavSnapshotMapper.Row snap = snapMapper.findLatestNav(code);
        if (snap == null || last == null || last.isBefore(LocalDateTime.now().minus(FundNavService.CRON_THROTTLE))) {
            var f = client.latestNav(familyId, code);
            if (f.ok()) {
                snapMapper.upsert(new FundNavSnapshotMapper.Row(code, f.navDate(), f.unitNav(), null, null, f.source(), null));
                snap = snapMapper.findLatestNav(code);
            }
        }
        return snap;
    }

    // ---------- 添加(FR-959~961 / 964 / 970)----------

    public enum By { SHARES, VALUE }

    public StockHolding create(long familyId, Long memberId, long accountId, String code, By by,
                               BigDecimal amount, boolean cashLinked) {
        Account acc = requireEligibleAccount(familyId, accountId);
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException(by == By.VALUE ? "市值必须大于 0" : "份额必须大于 0");
        String c = code == null ? "" : code.trim();
        Quote q = quote(familyId, c);
        if (q.fund() == null) throw new IllegalArgumentException(q.error());
        if (!q.fund().supported()) throw new IllegalArgumentException(q.error());
        if (!q.ok()) throw new IllegalArgumentException(q.error());
        LocalDateTime at = FundNavService.nowSec();
        boolean mmf = q.fund().kind() == FundCatalog.Kind.MMF;

        BigDecimal unit = mmf ? BigDecimal.ONE : q.unitNav();
        BigDecimal shares;
        LocalDate navDate;
        LocalDate estimatedOn = null;
        if (mmf) {
            shares = amount.setScale(2, RoundingMode.HALF_UP);                    // 货基:份额 = 金额
            navDate = LocalDate.now(FundNavService.CN).minusDays(1);             // 录入日的金额含截至前一天的收益
        } else if (by == By.VALUE) {
            shares = amount.divide(unit, SHARE_SCALE, RoundingMode.HALF_UP);
            navDate = q.navDate();
            estimatedOn = q.navDate();
        } else {
            shares = amount;
            navDate = q.navDate();
        }
        StockHolding h = StockHolding.builder()
                .accountId(accountId)
                .displayName(q.fund().info().name())
                .valuationMode(ValuationMode.MANUAL)
                .shares(shares)
                .manualValue(unit)
                .manualValueAt(at)
                .cashLinked(false)
                .fundCode(c)
                .penetrateState("PENDING")
                .navMode((mmf ? NavMode.MMF : NavMode.FUND).name())
                .navDate(navDate)
                .navCheckedAt(at)
                .sharesEstimatedOn(estimatedOn)
                .build();
        BigDecimal value = shares.multiply(unit).setScale(2, RoundingMode.HALF_UP);
        BigDecimal finalShares = shares;
        inTx(() -> {
            holdingMapper.insertOwned(familyId, h);
            if (cashLinked) stockHoldingService.adjustAccountCash(familyId, accountId, acc.getCurrency(), value.negate());
            recordEvent(familyId, memberId, h, cashLinked ? ShareEventReason.CASH_BUY : ShareEventReason.CREATE,
                    BigDecimal.ZERO, finalShares, unit, null, null);
        });
        afterHoldingChange(familyId, accountId, memberId);
        try { penetrationService.penetrateFamilyAsync(familyId); } catch (Exception ignored) {}
        return h;
    }

    // ---------- 手填 → 自动(FR-965)/ 改回手填 ----------

    /** 这一行能不能「改为按净值自动估值」:手填行、不是期权等、认出了 6 位代码且代码表里有、账户合格 */
    public boolean convertible(long familyId, StockHolding h, Account acc) {
        if (h == null || h.getValuationMode() != ValuationMode.MANUAL || h.isNavRow() || h.isDerivative()) return false;
        if (h.getFundCode() == null || !h.getFundCode().matches("\\d{6}")) return false;
        if (!accountAllowsFunds(acc)) return false;
        if (h.getSyncSource() != null && !"SCREENSHOT".equals(h.getSyncSource())) return false;   // 券商同步行归券商管
        FundCatalog.Classified c = catalog.find(familyId, h.getFundCode());
        return c != null && c.supported();
    }

    /** 确认框要的数:按哪天的哪个净值、把多少钱折成多少份 */
    public record ConvertPlan(FundCatalog.Classified fund, LocalDate navDate, BigDecimal unit, BigDecimal currentValue,
                              BigDecimal newShares, String error) {}

    public ConvertPlan planConvert(long familyId, long holdingId) {
        StockHolding h = stockHoldingService.require(familyId, holdingId);
        Account acc = accountMapper.findById(familyId, h.getAccountId()).orElseThrow();
        if (!convertible(familyId, h, acc)) return new ConvertPlan(null, null, null, null, null, "这一行不能改为按净值自动估值");
        Quote q = quote(familyId, h.getFundCode());
        if (!q.ok()) return new ConvertPlan(q.fund(), null, null, null, null, q.error());
        BigDecimal value = currentValue(h);
        boolean mmf = q.fund().kind() == FundCatalog.Kind.MMF;
        BigDecimal shares = mmf ? value.setScale(2, RoundingMode.HALF_UP) : value.divide(q.unitNav(), SHARE_SCALE, RoundingMode.HALF_UP);
        return new ConvertPlan(q.fund(), mmf ? LocalDate.now(FundNavService.CN).minusDays(1) : q.navDate(),
                mmf ? BigDecimal.ONE : q.unitNav(), value, shares, null);
    }

    public void convert(long familyId, Long memberId, long holdingId) {
        ConvertPlan p = planConvert(familyId, holdingId);   // 网络在事务外
        if (p.error() != null) throw new IllegalArgumentException(p.error());
        StockHolding h = stockHoldingService.require(familyId, holdingId);
        boolean mmf = p.fund().kind() == FundCatalog.Kind.MMF;
        inTx(() -> {
            int n = holdingMapper.writeNav(familyId, holdingId, p.newShares(), p.unit(),
                    (mmf ? NavMode.MMF : NavMode.FUND).name(), p.navDate(), null, mmf ? null : p.navDate(), FundNavService.nowSec());
            if (n != 1) throw new IllegalStateException("改为按净值自动估值没有成功");
            recordEvent(familyId, memberId, h, ShareEventReason.CONVERT, h.getShares(), p.newShares(), p.unit(), null, null);
        });
        afterHoldingChange(familyId, h.getAccountId(), memberId);
    }

    public void navOff(long familyId, long holdingId) {
        StockHolding h = stockHoldingService.require(familyId, holdingId);
        if (!h.isNavRow()) return;
        holdingMapper.clearNavMode(familyId, holdingId);
    }

    /** 「在别处被改过,已暂停自动更新」→ 用户核对后点「继续自动更新」:以当前份额 / 单价重新接管 */
    public void resume(long familyId, long holdingId) {
        StockHolding h = stockHoldingService.require(familyId, holdingId);
        if (!h.isNavRow()) return;
        LocalDate navDate = h.nav() == NavMode.MMF ? LocalDate.now(FundNavService.CN).minusDays(1) : h.getNavDate();
        holdingMapper.writeNav(familyId, holdingId, h.getShares(), h.getManualValue(), h.getNavMode(), navDate, null,
                h.getSharesEstimatedOn(), FundNavService.nowSec());
    }

    // ---------- 改份额 / 改金额(FR-967 / 970)----------

    public void editShares(long familyId, Long memberId, long holdingId, BigDecimal newShares, boolean cashLinked) {
        StockHolding h = stockHoldingService.require(familyId, holdingId);
        if (!h.isNavRow()) throw new IllegalArgumentException("这一行不是按净值估值的基金");
        if (newShares == null || newShares.signum() < 0) throw new IllegalArgumentException("份额不能为负");
        boolean mmf = h.nav() == NavMode.MMF;
        BigDecimal target = mmf ? newShares.setScale(2, RoundingMode.HALF_UP) : newShares;
        BigDecimal old = h.getShares() == null ? BigDecimal.ZERO : h.getShares();
        BigDecimal delta = target.subtract(old);
        if (delta.signum() == 0) return;
        BigDecimal unit = h.getManualValue() == null ? BigDecimal.ONE : h.getManualValue();
        Account acc = accountMapper.findById(familyId, h.getAccountId()).orElseThrow();
        // 货基核对:App 里的金额含截至前一天的收益 → 已结转到 = 前一天(选型六 约定 2)
        LocalDate navDate = mmf ? LocalDate.now(FundNavService.CN).minusDays(1) : h.getNavDate();
        ShareEventReason reason = cashLinked
                ? (delta.signum() > 0 ? ShareEventReason.CASH_BUY : ShareEventReason.CASH_REDEEM)
                : (mmf ? ShareEventReason.MANUAL_CORRECTION : ShareEventReason.MANUAL_EDIT);
        inTx(() -> {
            holdingMapper.writeNav(familyId, holdingId, target, unit, h.getNavMode(), navDate, null, null, FundNavService.nowSec());
            if (cashLinked) {
                BigDecimal money = delta.abs().multiply(unit).setScale(2, RoundingMode.HALF_UP);
                stockHoldingService.adjustAccountCash(familyId, h.getAccountId(), acc.getCurrency(),
                        delta.signum() > 0 ? money.negate() : money);
            }
            recordEvent(familyId, memberId, h, reason, old, target, unit, null, null);
        });
        afterHoldingChange(familyId, h.getAccountId(), memberId);
    }

    /** 截图导入命中净值行:按截图里的市值反推份额,<b>不碰单价</b>(护栏 v130-IMPORT-NAV-SAFE) */
    public void applyImportedValue(long familyId, Long memberId, StockHolding h, BigDecimal marketValue) {
        if (!h.isNavRow() || marketValue == null || marketValue.signum() < 0) return;
        boolean mmf = h.nav() == NavMode.MMF;
        BigDecimal unit = h.getManualValue() == null || h.getManualValue().signum() <= 0 ? BigDecimal.ONE : h.getManualValue();
        BigDecimal shares = mmf ? marketValue.setScale(2, RoundingMode.HALF_UP) : marketValue.divide(unit, SHARE_SCALE, RoundingMode.HALF_UP);
        BigDecimal old = h.getShares() == null ? BigDecimal.ZERO : h.getShares();
        LocalDate navDate = mmf ? LocalDate.now(FundNavService.CN).minusDays(1) : h.getNavDate();
        holdingMapper.writeNav(familyId, h.getId(), shares, unit, h.getNavMode(), navDate, null,
                mmf ? null : h.getNavDate(), FundNavService.nowSec());
        if (shares.compareTo(old) != 0) recordEvent(familyId, memberId, h, ShareEventReason.IMPORT, old, shares, unit, null, null);
    }

    // ---------- 共用 ----------

    private Account requireEligibleAccount(long familyId, long accountId) {
        Account acc = accountMapper.findById(familyId, accountId)
                .orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (!acc.getFamilyId().equals(familyId)) throw new IllegalArgumentException("无权访问账户");
        if (!accountAllowsFunds(acc)) {
            throw new IllegalArgumentException("这个账户不能添加场外基金(只支持人民币的基金 / 证券 / 理财 / 现金账户)");
        }
        return acc;
    }

    /** 手填行的当前市值(账户币种):单价 × 份额(份额缺就按 1) */
    static BigDecimal currentValue(StockHolding h) {
        BigDecimal unit = h.getManualValue() == null ? BigDecimal.ZERO : h.getManualValue();
        BigDecimal sh = h.getShares() == null ? BigDecimal.ONE : h.getShares();
        return unit.multiply(sh).setScale(2, RoundingMode.HALF_UP);
    }

    private void recordEvent(long familyId, Long memberId, StockHolding h, ShareEventReason reason,
                             BigDecimal before, BigDecimal after, BigDecimal unit, LocalDate from, LocalDate to) {
        BigDecimal b = before == null ? BigDecimal.ZERO : before;
        BigDecimal delta = after.subtract(b);
        shareMapper.insertOwned(familyId, HoldingShareEvent.builder()
                .accountId(h.getAccountId()).holdingId(h.getId())
                .periodId(periodMapper.findBalancePeriod(familyId).map(p -> p.getId()).orElse(null))
                .reason(reason.name())
                .sharesBefore(b).sharesAfter(after).sharesDelta(delta)
                .unitValue(unit).valueDelta(delta.multiply(unit).setScale(2, RoundingMode.HALF_UP))
                .dateFrom(from).dateTo(to).memberId(memberId)
                .build());
    }

    private void afterHoldingChange(long familyId, long accountId, Long memberId) {
        try {
            valuationService.refreshOneAccount(familyId, accountId,
                    AccountValuationService.TriggerKind.HOLDING_CHANGE, memberId, null);
        } catch (Exception e) {
            log.warn("基金持仓变动后估值写回失败 · account={} · {}", accountId, e.toString());
        }
    }
}
