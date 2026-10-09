package com.family.finance.service.fund;

import com.family.finance.domain.stock.HoldingShareEvent;
import com.family.finance.domain.stock.NavMode;
import com.family.finance.domain.stock.ShareEventReason;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.FundNavSnapshotMapper;
import com.family.finance.repository.HoldingShareEventMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.penetration.EastMoneyFundClient;
import com.family.finance.service.stock.AccountValuationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v1.30 · 净值行的同步(PRD FR-962 / 963 / 964 · tech-design/v1.30.md 选型二~六)。
 *
 * <p>两件事,都只改<b>持仓行</b>,不碰余额 —— 余额由调用方随后调 {@code AccountValuationService} 按
 * MANUAL 支(单价 × 份额)重算,估值路径一行没改:</p>
 * <ul>
 *   <li><b>普通基金</b>:单价 = 最新单位净值(公共快照 {@code fund_nav_snapshot},按代码去重拉)。</li>
 *   <li><b>货币基金</b>:按逐日万份收益把收益结转成份额({@link MmfAccrual}),每次结转记一条持仓数量变动。
 *       结转在行锁里做 —— 定时 + 按钮同时到,第二个读到的已是新日期,什么都不结。</li>
 * </ul>
 *
 * <p>触发者只有两个:{@code ValuationRefreshService}(两个刷新按钮)与 {@code StockPriceScheduler}
 * 的每一次定时拉价(§13 ④「跟随原有同步」,不另设 cron)。</p>
 */
@Slf4j
@Service
public class FundNavService {

    static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    /** 定时任务:同一只基金 30 分钟内不重复真去拉(早上 06:05 / 06:15 两个 cron 只拉一次) */
    public static final Duration CRON_THROTTLE = Duration.ofMinutes(30);
    /** 按钮:用户点了就是想要新的,只防连点 */
    public static final Duration BUTTON_THROTTLE = Duration.ofMinutes(2);
    /** 净值日期早于今天这么多天 → 标「已经两周没有新净值」(选型四) */
    public static final int STALE_DAYS = 14;
    /** 别处(回滚期间的老代码 / 其它路径)改过这一行 → 暂停自动更新,请用户核对 */
    public static final String EDITED_ELSEWHERE = "EDITED_ELSEWHERE";

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "fund-nav-fetch");
        t.setDaemon(true);
        return t;
    });

    private final EastMoneyFundClient client;
    private final FundNavSnapshotMapper snapMapper;
    private final StockHoldingMapper holdingMapper;
    private final HoldingShareEventMapper shareMapper;
    private final PeriodMapper periodMapper;
    private final FamilyMapper familyMapper;
    private final AccountValuationService valuationService;
    private final TransactionTemplate tx;

    public FundNavService(EastMoneyFundClient client, FundNavSnapshotMapper snapMapper, StockHoldingMapper holdingMapper,
                          HoldingShareEventMapper shareMapper, PeriodMapper periodMapper, FamilyMapper familyMapper,
                          AccountValuationService valuationService, PlatformTransactionManager tm) {
        this.client = client;
        this.snapMapper = snapMapper;
        this.holdingMapper = holdingMapper;
        this.shareMapper = shareMapper;
        this.periodMapper = periodMapper;
        this.familyMapper = familyMapper;
        this.valuationService = valuationService;
        this.tx = tm == null ? null : new TransactionTemplate(tm);
    }

    /** 一次同步的结果:刷新按钮的提示语要点名失败的基金(FR-963) */
    public record Report(int navRows, int navUpdated, List<String> failed, int mmfAccrued) {
        public static Report empty() { return new Report(0, 0, List.of(), 0); }
        public boolean any() { return navRows > 0; }
    }

    // ---------- 入口 ----------

    /** 定时任务用:逐个家庭同步;哪家的持仓变了,就顺手把那家的估值写回(单家庭部署下就是 1 号家庭) */
    public void refreshAllFamilies() {
        for (var f : familyMapper.findAll()) {
            try {
                Report r = refresh(f.getId(), CRON_THROTTLE);
                if (r.navUpdated() > 0 || r.mmfAccrued() > 0) {
                    valuationService.refreshAllForFamily(f.getId(), AccountValuationService.TriggerKind.CRON, null);
                }
            } catch (Exception e) {
                log.warn("基金净值定时同步失败 · family={} · {}", f.getId(), e.toString());
            }
        }
    }

    /**
     * 同步某家庭全部净值行。只改持仓行;余额由调用方随后估值写回。
     *
     * @param throttle 同一只基金在这段时间里拉过就直接用快照
     */
    public Report refresh(long familyId, Duration throttle) {
        List<StockHolding> rows = holdingMapper.findActiveNavRowsByFamily(familyId);
        if (rows.isEmpty()) return Report.empty();
        LocalDateTime now = nowSec();
        LocalDate today = LocalDate.now(CN);

        // 1) 普通基金:按代码去重,并发拉(主源 lsjz、备源 pingzhongdata),拿到的写进公共快照
        Set<String> navCodes = new LinkedHashSet<>();
        for (StockHolding h : rows) if (h.nav() == NavMode.FUND && h.getFundCode() != null) navCodes.add(h.getFundCode());
        Map<String, EastMoneyFundClient.NavFetch> fetched = fetchNavs(familyId, navCodes, throttle);

        int updated = 0, accrued = 0;
        List<String> failed = new ArrayList<>();
        for (StockHolding h : rows) {
            try {
                if (editedElsewhere(h)) {
                    holdingMapper.markNavError(familyId, h.getId(), EDITED_ELSEWHERE, now);
                    failed.add(h.getDisplayName() + ":在别处被改过,已暂停自动更新");
                    continue;
                }
                if (h.nav() == NavMode.FUND) {
                    Outcome o = applyNav(familyId, h, fetched.get(h.getFundCode()), now);
                    if (o.changed) updated++;
                    if (o.problem != null) failed.add(h.getDisplayName() + ":" + o.problem);
                } else if (h.nav() == NavMode.MMF) {
                    Outcome o = accrueMmf(familyId, h.getId(), today, throttle, now);
                    if (o.changed) accrued++;
                    if (o.problem != null) failed.add(h.getDisplayName() + ":" + o.problem);
                }
            } catch (Exception e) {
                log.warn("净值行同步失败 · holding={} · {}", h.getId(), e.toString());
                failed.add(h.getDisplayName() + ":同步出错");
            }
        }
        log.info("基金净值同步 · family={} · 净值行 {} · 更新 {} · 货基结转 {} · 没成功 {}",
                familyId, rows.size(), updated, accrued, failed.size());
        return new Report(rows.size(), updated, failed, accrued);
    }

    // ---------- 普通基金 ----------

    private record Outcome(boolean changed, String problem) {}

    private Map<String, EastMoneyFundClient.NavFetch> fetchNavs(long familyId, Set<String> codes, Duration throttle) {
        Map<String, CompletableFuture<EastMoneyFundClient.NavFetch>> futures = new LinkedHashMap<>();
        for (String code : codes) {
            if (fetchedWithin(code, throttle)) continue;
            futures.put(code, CompletableFuture.supplyAsync(() -> client.latestNav(familyId, code), POOL));
        }
        Map<String, EastMoneyFundClient.NavFetch> out = new LinkedHashMap<>();
        for (var e : futures.entrySet()) {
            EastMoneyFundClient.NavFetch f;
            try {
                f = e.getValue().get(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ex) {
                f = new EastMoneyFundClient.NavFetch(false, null, null, null, "数据源超时");
            }
            if (f.ok()) {
                snapMapper.upsert(new FundNavSnapshotMapper.Row(e.getKey(), f.navDate(), f.unitNav(), null, null, f.source(), null));
            }
            out.put(e.getKey(), f);
        }
        return out;
    }

    private boolean fetchedWithin(String code, Duration throttle) {
        LocalDateTime last = snapMapper.lastFetchedAt(code);
        return last != null && throttle != null && last.isAfter(LocalDateTime.now().minus(throttle));
    }

    /**
     * 把最新快照写进这一行。这次没拉到(fetch 失败)但快照里有以前的 → 单价照用快照里最新的,并记下原因;
     * 什么都没有 → 单价不动,只记原因。<b>绝不清零</b>。
     */
    private Outcome applyNav(long familyId, StockHolding h, EastMoneyFundClient.NavFetch fetch, LocalDateTime now) {
        FundNavSnapshotMapper.Row snap = snapMapper.findLatestNav(h.getFundCode());
        String problem = fetch != null && !fetch.ok() ? "这次没拉到 · " + fetch.error() : null;
        boolean newer = snap != null && (h.getNavDate() == null || snap.navDate().isAfter(h.getNavDate())
                || (snap.navDate().equals(h.getNavDate()) && h.getManualValue() != null
                    && snap.unitNav().compareTo(h.getManualValue()) != 0));
        if (newer) {
            holdingMapper.writeNav(familyId, h.getId(), h.getShares(), snap.unitNav(), NavMode.FUND.name(),
                    snap.navDate(), problem, h.getSharesEstimatedOn(), now);
            return new Outcome(true, problem);
        }
        if (problem == null && snap != null && snap.navDate() != null
                && ChronoUnit.DAYS.between(snap.navDate(), LocalDate.now(CN)) > STALE_DAYS) {
            problem = "已经两周没有新净值";
        }
        holdingMapper.markNavError(familyId, h.getId(), problem, now);
        return new Outcome(false, problem);
    }

    // ---------- 货币基金 ----------

    private Outcome accrueMmf(long familyId, long holdingId, LocalDate today, Duration throttle, LocalDateTime now) {
        StockHolding peek = holdingMapper.findById(familyId, holdingId).orElse(null);
        if (peek == null || peek.getFundCode() == null) return new Outcome(false, null);
        LocalDate from = peek.getNavDate();
        if (from == null) {
            holdingMapper.markNavError(familyId, holdingId, "不知道收益结转到了哪一天", now);
            return new Outcome(false, "不知道收益结转到了哪一天");
        }
        if (!today.isAfter(from)) return new Outcome(false, null);
        if (ChronoUnit.DAYS.between(from, today) > MmfAccrual.MAX_DAYS) {
            String why = "超过 " + MmfAccrual.MAX_DAYS + " 天没同步,请先核对金额";
            holdingMapper.markNavError(familyId, holdingId, why, now);
            return new Outcome(false, why);
        }
        // 网络在锁外:先把 (from, today] 的逐日万份收益拿进公共快照
        String fetchProblem = null;
        if (!fetchedWithin(peek.getFundCode(), throttle)) {
            var inc = client.mmfIncome(familyId, peek.getFundCode(), from, today);
            if (inc.ok()) {
                for (var d : inc.days()) {
                    snapMapper.upsert(new FundNavSnapshotMapper.Row(peek.getFundCode(), d.date(), null, d.per10k(),
                            d.yield7d(), "eastmoney-lsjz", null));
                }
            } else {
                fetchProblem = "这次没拉到 · " + inc.error();
            }
        }
        final String fp = fetchProblem;
        Outcome[] out = new Outcome[1];
        Runnable work = () -> {
            StockHolding h = holdingMapper.lockById(familyId, holdingId).orElse(null);
            if (h == null || h.nav() != NavMode.MMF || h.getNavDate() == null) { out[0] = new Outcome(false, null); return; }
            List<MmfAccrual.Day> days = snapMapper.findIncome(h.getFundCode(), h.getNavDate(), today).stream()
                    .map(r -> new MmfAccrual.Day(r.navDate(), r.incomePer10k())).toList();
            MmfAccrual.Result r = MmfAccrual.accrue(h.getShares(), h.getNavDate(), days);
            String problem = r.error() != null ? r.error() : fp;
            if (!r.changed()) {
                holdingMapper.markNavError(familyId, holdingId, problem, now);
                out[0] = new Outcome(false, problem);
                return;
            }
            holdingMapper.writeNav(familyId, holdingId, r.sharesAfter(), BigDecimal.ONE, NavMode.MMF.name(),
                    r.accruedTo(), problem, null, now);
            shareMapper.insertOwned(familyId, HoldingShareEvent.builder()
                    .accountId(h.getAccountId()).holdingId(holdingId)
                    .periodId(periodMapper.findBalancePeriod(familyId).map(p -> p.getId()).orElse(null))
                    .reason(ShareEventReason.MMF_ACCRUAL.name())
                    .sharesBefore(r.sharesBefore()).sharesAfter(r.sharesAfter()).sharesDelta(r.delta())
                    .unitValue(BigDecimal.ONE).valueDelta(r.delta().setScale(2, RoundingMode.HALF_UP))
                    .dateFrom(r.accruedFrom()).dateTo(r.accruedTo())
                    .build());
            out[0] = new Outcome(true, problem);
        };
        if (tx == null) work.run(); else tx.executeWithoutResult(s -> work.run());
        return out[0];
    }

    // ---------- 共用 ----------

    /**
     * 我们每次写净值行都把 {@code manual_value_at} 与 {@code nav_checked_at} 写成同一时刻;
     * {@code manual_value_at} 更晚 = 我们上次写之后有别处改过这一行(回滚期间的老代码手改 / 截图导入)。
     */
    static boolean editedElsewhere(StockHolding h) {
        if (EDITED_ELSEWHERE.equals(h.getNavError())) return true;
        return h.getManualValueAt() != null && h.getNavCheckedAt() != null
                && h.getManualValueAt().isAfter(h.getNavCheckedAt());
    }

    /** 截到秒:{@code manual_value_at} / {@code nav_checked_at} 都是 TIMESTAMP(秒),比较才不会被毫秒骗 */
    public static LocalDateTime nowSec() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
    }
}
