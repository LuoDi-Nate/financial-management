package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerLink;
import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.domain.stock.InstrumentKind;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.BrokerLinkMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.stock.AccountValuationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 券商只读同步 · reconcile · v0.15。
 *
 * <p><b>只动带 sync_source=本 vendor 的持仓行,绝不碰用户手填持仓</b>:
 * 券商有我方无→建 AUTO;都有→更 shares/cost;我方有券商无→软归档;现金按币种 upsert CASH。
 * 持仓 currency 落 native,估值层按账户币种 FX 折算(不在此强转)。</p>
 *
 * <p>v1.29 · 期权 / 权证 / 期货 / 债券也同步进来(issue #26),落成手动估值行 —— 见 {@link #reconcile}。</p>
 */
@Service
@Slf4j
public class BrokerSyncService {

    private final BrokerLinkMapper linkMapper;
    private final StockHoldingMapper holdingMapper;
    private final List<BrokerClient> clients;
    private final AccountValuationService valuationService;

    public BrokerSyncService(BrokerLinkMapper linkMapper, StockHoldingMapper holdingMapper,
                             List<BrokerClient> clients, AccountValuationService valuationService) {
        this.linkMapper = linkMapper;
        this.holdingMapper = holdingMapper;
        this.clients = clients;
        this.valuationService = valuationService;
    }

    public BrokerClient clientFor(BrokerVendor vendor) {
        return clients.stream().filter(c -> c.vendor() == vendor).findFirst()
                .orElseThrow(() -> new IllegalStateException("无券商客户端:" + vendor));
    }

    /** 对账 + 记成功在同一个事务里;失败记录在事务之外(见 sync 注释)。Spring 注入,单测里为 null → 直接跑。 */
    private org.springframework.transaction.support.TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(org.springframework.transaction.PlatformTransactionManager tm) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(tm);
    }

    /**
     * 同步单个已关联账户;返回状态摘要。
     *
     * <p><b>v1.26 修:失败记录不许跟着回滚</b>。原来整个方法是 {@code @Transactional},失败时先
     * {@code markFailed} 再把异常抛出去 —— 事务一回滚,刚写的失败记录也没了。于是:</p>
     * <ul>
     *   <li>从页面点「立即同步」(经 Spring 代理 → 有事务)失败时,卡片上<b>仍是上一次成功</b>的消息 ——
     *       v1.17.3 要修的正是这件事,它只在定时任务那条路上生效(那条路是类内自调用,没有事务);</li>
     *   <li>IBKR 报表口令过期,用户点「立即同步」看到了弹出的失败提示,但卡片和账户列表都不标红。</li>
     * </ul>
     * <p>现在:取数不写库;对账 + 记成功放进一个事务;失败(取数失败或对账失败回滚之后)在事务之外记,一定落库。
     * 2026-09-24 e2e flow 27 抓到(查库真值:点完「立即同步」,last_status 还是上次成功的摘要)。</p>
     */
    public String sync(long familyId, long accountId, Long memberId) {
        BrokerLink link = linkMapper.findByAccount(familyId, accountId)
                .orElseThrow(() -> new IllegalStateException("该账户未关联券商"));
        if (!link.isEnabled()) throw new IllegalStateException("该账户券商同步已停用");
        String summary;
        try {
            BrokerDtos.Snapshot snap = clientFor(link.getVendor()).fetch(familyId, link);
            java.util.function.Supplier<String> apply = () -> {
                String s = reconcile(familyId, accountId, link.getVendor(), snap);
                linkMapper.markSynced(familyId, accountId, clip(s));
                return s;
            };
            summary = tx == null ? apply.get() : tx.execute(st -> apply.get());
        } catch (RuntimeException e) {
            // v1.17.3 · 失败也要落库:在此之前失败只写日志,页面上会一直挂着【上一次成功】的消息 ——
            // 生产上富途断了两天,页面还显示「新增 0 · 更新 7」。不动 last_synced_at(那是"最后成功"的语义)。
            // v1.26 · 这一行在事务之外,不会被回滚掉(见方法注释)。
            linkMapper.markFailed(familyId, accountId, failureNote(e));
            throw e;
        }
        try {
            // v1.18 · 明确告诉估值服务"这次是券商同步引起的",流水里才分得出富途/老虎
            valuationService.refreshAllForFamily(familyId,
                    AccountValuationService.TriggerKind.HOLDING_CHANGE, memberId,
                    com.family.finance.domain.ledger.LedgerSource.ofBroker(link.getVendor().name()));
        } catch (Exception e) {
            log.warn("post-broker-sync valuation refresh failed: {}", e.toString());
        }
        return summary;
    }

    /** cron 用:同步所有 enabled 的关联账户。 */
    public int syncAllEnabled(long familyId, Long memberId) {
        int ok = 0;
        for (BrokerLink link : linkMapper.findEnabledByFamily(familyId)) {
            try { sync(familyId, link.getAccountId(), memberId); ok++; }
            catch (Exception e) {
                // sync() 里已经把失败落库了,这里只记日志(别重复写,免得覆盖更具体的那条)
                log.warn("broker-sync fail · account={}: {}", link.getAccountId(), e.toString());
            }
        }
        return ok;
    }

    /**
     * 失败摘要 —— 这条会直接显示在券商卡片上,所以要说人话、要短、且不能带凭据类细节。
     *
     * <p>带上时间是有意的:用户看到「同步失败(08-19 16:45)」才知道这是<b>最近一次尝试</b>失败了,
     * 而不是历史上某次。</p>
     */
    static String failureNote(Exception e) {
        String raw = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String hint;
        // v1.26 · IBKR 的失败已经是「人话 + IBKR 原话」,原样用 —— 下面那几条是给 OpenD 写的,套在 IBKR 上会把原话吞掉
        if (e instanceof com.family.finance.service.broker.ibkr.IbkrFlexException ie) {
            String m = ie.getMessage();
            if (m.length() > 200) m = m.substring(0, 200) + "…";   // last_status 是 VARCHAR(255)
            return "同步失败 · " + m + "("
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) + ")";
        }
        String low = raw.toLowerCase(java.util.Locale.ROOT);
        if (low.contains("connection refused") || low.contains("无法发起") || low.contains("connect")) {
            hint = "连不上 OpenD 网关(它没在跑?)";
        } else if (low.contains("未配置")) {
            hint = "券商连接未配置完整";
        } else if (low.contains("timeout") || low.contains("超时")) {
            hint = "网关无响应(超时)";
        } else if (low.contains("登录") || low.contains("login")) {
            hint = "网关未登录 / 登录已失效";
        } else {
            hint = raw.length() > 60 ? raw.substring(0, 60) + "…" : raw;
        }
        return "同步失败 · " + hint + "("
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) + ")";
    }

    /** last_status 是 VARCHAR(255);同步结果写全给页面看,落库时截一下 */
    static String clip(String s) {
        return s == null || s.length() <= 250 ? s : s.substring(0, 249) + "…";
    }

    /**
     * 对账:只动 sync_source=vendor 的行。返回摘要。包可见供单测。
     */
    String reconcile(long familyId, long accountId, BrokerVendor vendor, BrokerDtos.Snapshot snap) {
        String src = vendor.name();
        List<StockHolding> all = holdingMapper.findActiveByAccount(familyId, accountId);
        List<StockHolding> existing = all.stream()
                .filter(h -> src.equals(h.getSyncSource())).toList();

        Set<Long> keepIds = new HashSet<>();
        int created = 0, updated = 0;

        // 持仓(AUTO)
        for (BrokerDtos.Position p : snap.positions()) {
            StockHolding match = existing.stream()
                    .filter(h -> h.getValuationMode() == ValuationMode.AUTO
                            && p.ticker().equalsIgnoreCase(h.getTicker())
                            && h.getMarket() != null && p.market().equals(h.getMarket().name()))
                    .findFirst().orElse(null);
            if (match != null) {
                match.setShares(p.shares());
                match.setCostBasis(p.costPrice());
                match.setCurrency(p.currency());
                // 显示名升级:旧名还是裸代码时,用券商给的证券名(不覆盖用户自己改过的名)
                if (p.name() != null && !p.name().isBlank()
                        && (match.getDisplayName() == null || match.getDisplayName().equalsIgnoreCase(p.ticker()))) {
                    match.setDisplayName(p.name());
                }
                holdingMapper.update(familyId, match);
                keepIds.add(match.getId());
                updated++;
            } else {
                StockHolding h = StockHolding.builder()
                        .accountId(accountId)
                        .displayName(p.name() != null && !p.name().isBlank() ? p.name() : p.ticker())
                        .valuationMode(ValuationMode.AUTO)
                        .ticker(p.ticker()).market(Market.valueOf(p.market()))
                        .shares(p.shares()).costBasis(p.costPrice()).currency(p.currency())
                        .syncSource(src).cashLinked(false).build();
                holdingMapper.insertOwned(familyId, h);
                keepIds.add(h.getId());
                created++;
            }
        }
        // 现金(CASH · 按币种)
        for (BrokerDtos.Cash csh : snap.cash()) {
            StockHolding match = existing.stream()
                    .filter(h -> h.getValuationMode() == ValuationMode.CASH
                            && csh.currency().equalsIgnoreCase(h.getCurrency()))
                    .findFirst().orElse(null);
            if (match != null) {
                match.setManualValue(csh.amount());
                holdingMapper.update(familyId, match);
                keepIds.add(match.getId());
                updated++;
            } else {
                StockHolding h = StockHolding.builder()
                        .accountId(accountId).displayName(csh.currency() + " 现金")
                        .valuationMode(ValuationMode.CASH)
                        .currency(csh.currency()).manualValue(csh.amount())
                        .syncSource(src).cashLinked(false).build();
                holdingMapper.insertOwned(familyId, h);
                keepIds.add(h.getId());
                created++;
            }
        }
        // v1.26 · 拉不到价的市场(IBKR 的伦敦 / 东京 / 新加坡 …)→ 手动估值行,单价 = 券商报表收盘价(折成账户币种)
        //   手动估值行的单价语义是「账户币种」(见 ValuationMode),所以这里折算;每次同步都用新报表价覆盖。
        int priced = 0;
        for (BrokerDtos.ManualPosition mp : snap.manualPositions()) {
            BigDecimal fx = mp.currency() == null ? BigDecimal.ONE
                    : valuationService.fxToAccountCurrency(familyId, accountId, mp.currency());
            if (fx == null) {
                throw new IllegalStateException("缺 " + mp.currency() + " → 账户币种的汇率,无法给 " + mp.symbol() + " 估值");
            }
            BigDecimal unit = mp.unitPrice() == null ? null : mp.unitPrice().multiply(fx).setScale(6, java.math.RoundingMode.HALF_EVEN);
            BigDecimal cost = mp.costPrice() == null ? null : mp.costPrice().multiply(fx).setScale(6, java.math.RoundingMode.HALF_EVEN);
            StockHolding match = existing.stream()
                    .filter(h -> h.getValuationMode() == ValuationMode.MANUAL && !h.isDerivative()
                            && h.getTicker() != null && mp.symbol().equalsIgnoreCase(h.getTicker()))
                    .findFirst().orElse(null);
            if (match != null) {
                match.setShares(mp.shares());
                match.setManualValue(unit);
                match.setManualValueAt(java.time.LocalDateTime.now());
                match.setCostBasis(cost);
                holdingMapper.update(familyId, match);
                keepIds.add(match.getId());
                updated++;
            } else {
                String label = (mp.name() != null ? mp.name() : mp.symbol())
                        + (mp.exchange() != null ? " · " + mp.exchange() : "");
                StockHolding h = StockHolding.builder()
                        .accountId(accountId).displayName(label)
                        .valuationMode(ValuationMode.MANUAL)
                        .ticker(mp.symbol()).shares(mp.shares())
                        .manualValue(unit).manualValueAt(java.time.LocalDateTime.now()).costBasis(cost)
                        .syncSource(src).cashLinked(false).build();
                holdingMapper.insertOwned(familyId, h);
                keepIds.add(h.getId());
                created++;
            }
            priced++;
        }
        // v1.29 · 期权 / 权证 / 期货 / 债券(issue #26)→ 手动估值行:单价 = 持仓市值 ÷ 张数(折成账户币种),
        //   张数带符号 —— 卖出的期权张数为负、单价为正,相乘就是负的市值,从余额里减去;期货单价 0(不计入余额)。
        //   估值仍是「单价 × 张数」那一条路,没有另起一条求和路径。
        //   已到期 / 平仓的:报表里没有了 → 走下面的「券商已无 → 软归档」,不留残行(FR-947)。
        int optRows = 0, futRows = 0, bondRows = 0, derivCreated = 0;
        BigDecimal optValue = BigDecimal.ZERO, bondValue = BigDecimal.ZERO;
        for (BrokerDtos.Derivative d : snap.derivatives()) {
            BigDecimal fx = d.currency() == null ? BigDecimal.ONE
                    : valuationService.fxToAccountCurrency(familyId, accountId, d.currency());
            if (fx == null) {
                throw new IllegalStateException("缺 " + d.currency() + " → 账户币种的汇率,无法给 " + d.symbol() + " 估值");
            }
            BigDecimal unit = d.marketValue().signum() == 0 ? BigDecimal.ZERO
                    : d.marketValue().divide(d.quantity(), 12, RoundingMode.HALF_EVEN)
                            .multiply(fx).setScale(6, RoundingMode.HALF_EVEN);
            StockHolding match = existing.stream()
                    .filter(h -> h.isDerivative() && d.symbol().equalsIgnoreCase(h.getTicker()))
                    .findFirst().orElse(null);
            StockHolding h = match != null ? match : StockHolding.builder()
                    .accountId(accountId).valuationMode(ValuationMode.MANUAL)
                    .ticker(d.symbol()).syncSource(src).cashLinked(false).build();
            String title = InstrumentKind.title(d.kind(), d.underlying(), d.symbol(), d.putCall(),
                    d.strike(), d.expiry(), d.description());
            if (h.getDisplayName() == null || h.getDisplayName().isBlank()
                    || h.getDisplayName().equalsIgnoreCase(d.symbol())) {
                h.setDisplayName(title);
            }
            h.setInstrumentKind(d.kind().name());
            h.setUnderlying(d.underlying());
            h.setPutCall(d.putCall() == null ? null : d.putCall().trim().substring(0, 1).toUpperCase(java.util.Locale.ROOT));
            h.setStrike(d.strike());
            h.setExpiry(d.expiry());
            h.setMultiplier(d.multiplier());
            h.setQuotePrice(d.markPrice());
            h.setNotional(d.notional());
            h.setCurrency(d.currency());
            h.setShares(d.quantity());
            h.setManualValue(unit);
            h.setManualValueAt(java.time.LocalDateTime.now());
            h.setCostBasis(null);   // 期权的成本口径各家不一(每股 / 每张),不显示盈亏,别给半对的数
            if (match != null) {
                holdingMapper.update(familyId, h);
                updated++;
            } else {
                holdingMapper.insertOwned(familyId, h);
                created++;
                derivCreated++;
            }
            keepIds.add(h.getId());
            BigDecimal valueAcct = unit.multiply(d.quantity());
            switch (d.kind()) {
                case OPTION, WARRANT -> { optRows++; optValue = optValue.add(valueAcct); }
                case FUTURE -> futRows++;
                case BOND -> { bondRows++; bondValue = bondValue.add(valueAcct); }
            }
        }
        // 券商已无 → 软归档
        int archived = 0;
        for (StockHolding h : existing) {
            if (!keepIds.contains(h.getId())) { holdingMapper.archive(familyId, h.getId()); archived++; }
        }
        String ccy = valuationService.accountCurrency(familyId, accountId);
        // 第一次带进期权这类时,账户里若还有手填持仓,多半是以前为了凑数手动补的那一条 —— 提醒一句,免得算两遍
        long manualRows = derivCreated == 0 ? 0 : all.stream()
                .filter(h -> h.getSyncSource() == null && h.getValuationMode() == ValuationMode.MANUAL).count();
        String summary = "同步 · 新增 " + created + " · 更新 " + updated + " · 归档 " + archived
                + (priced > 0 ? " · 按券商收盘价估值 " + priced : "")
                + (optRows > 0 ? " · 期权 " + optRows + " 笔 · 市值 " + money(ccy, optValue) : "")
                + (futRows > 0 ? " · 期货 " + futRows + " 笔(不计入余额)" : "")
                + (bondRows > 0 ? " · 债券 " + bondRows + " 笔 · 市值 " + money(ccy, bondValue) : "")
                + (snap.skippedNonEquity() > 0 ? " · 跳过其他品种 " + snap.skippedNonEquity() : "")
                + DerivativeRows.rejectSummary(snap.rejected())
                + (manualRows > 0 ? " · 账户里另有 " + manualRows + " 条手填持仓:若是以前为期权补录的,请归档,免得算两遍" : "");
        log.info("broker reconcile · account={} vendor={} {}", accountId, vendor, summary);
        if (!snap.rejected().isEmpty()) {
            log.warn("broker reconcile · account={} vendor={} 没同步的行:{}", accountId, vendor, snap.rejected());
        }
        return summary;
    }

    private static String money(String ccy, BigDecimal v) {
        return (ccy == null ? "" : ccy + " ") + String.format("%,.0f", v.setScale(0, RoundingMode.HALF_EVEN));
    }
}
