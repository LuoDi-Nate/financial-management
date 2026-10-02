package com.family.finance.web.stock;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.BrokerLinkMapper;
import com.family.finance.repository.StockPriceSnapshotMapper;
import com.family.finance.service.NavService;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.stock.AccountValuationService;
import com.family.finance.service.stock.MetalUnit;
import com.family.finance.service.stock.StockHoldingService;
import com.family.finance.service.stock.StockPriceScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 股票持仓管理 · v0.3 FR-52。
 *
 * <p>路由清单:</p>
 * <ul>
 *   <li>GET  /accounts/{id}/holdings                  · 持仓列表 + 估值结果</li>
 *   <li>GET  /accounts/{id}/holdings/new-auto         · 添加 AUTO 持仓表单</li>
 *   <li>POST /accounts/{id}/holdings/new-auto         · 创建</li>
 *   <li>GET  /accounts/{id}/holdings/new-manual       · 添加 MANUAL 持仓表单</li>
 *   <li>POST /accounts/{id}/holdings/new-manual       · 创建</li>
 *   <li>GET  /accounts/{id}/holdings/new-cash         · 添加 CASH 现金行表单(v0.3 FR-52e)</li>
 *   <li>POST /accounts/{id}/holdings/new-cash         · 创建</li>
 *   <li>POST /accounts/{id}/holdings/{hid}/update     · 更新 MANUAL 市值</li>
 *   <li>POST /accounts/{id}/holdings/{hid}/update-cash · 更新 CASH 现金金额</li>
 *   <li>POST /accounts/{id}/holdings/{hid}/to-manual  · AUTO→MANUAL 转换</li>
 *   <li>POST /accounts/{id}/holdings/{hid}/archive    · 软删</li>
 *   <li>POST /accounts/{id}/holdings/refresh          · 手动刷价(单账户)</li>
 * </ul>
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class StockHoldingController {

    private final StockHoldingService holdingService;
    private final AccountValuationService valuationService;
    private final StockPriceScheduler scheduler;
    private final StockPriceSnapshotMapper priceMapper;
    private final BrokerLinkMapper brokerLinkMapper;   // v1.6.24 · 持仓页展示本账户的券商对接状态(1:1)
    private final AccountMapper accountMapper;
    private final NavService navService;
    private final FamilyConfigService configService;
    private final com.family.finance.service.lens.LensQueryService lensQueryService; // v1.1 行业标改后失效透视缓存

    @GetMapping("/accounts/{accountId}/holdings")
    public String list(@AuthenticationPrincipal MemberPrincipal me,
                       @PathVariable long accountId,
                       Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        List<StockHolding> active = holdingService.findActiveByAccount(me.getFamilyId(), accountId);
        AccountValuationService.ValuationResult valuation = valuationService.valuate(me.getFamilyId(), accountId);

        // 为每个持仓附加"最新已知价"(给 UI 显示陈旧天数)
        Map<Long, Map<String, Object>> latestPrices = new HashMap<>();
        for (StockHolding h : active) {
            if (h.getValuationMode() == ValuationMode.AUTO && h.getTicker() != null) {
                priceMapper.findLatest(h.getTicker(), h.getMarket().name()).ifPresent(p -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("closePrice", p.getClosePrice());
                    info.put("tradeDate", p.getTradeDate());
                    info.put("source", p.getSource());
                    info.put("staleDays", java.time.temporal.ChronoUnit.DAYS.between(p.getTradeDate(), java.time.LocalDate.now()));
                    latestPrices.put(h.getId(), info);
                });
            }
        }

        // v0.14 · METAL 持仓展示信息(每持仓单位价 / 市值 / 盈亏 · 原币种)
        Map<Long, Map<String, Object>> metalInfo = new HashMap<>();
        for (StockHolding h : active) {
            if (h.getMarket() == Market.METAL && h.getValuationMode() == ValuationMode.AUTO) {
                Map<String, Object> mi = new HashMap<>();
                mi.put("metalLabel", MetalUnit.metalLabel(h.getTicker()));
                mi.put("unitLabel", MetalUnit.unitLabel(h.getUnit()));
                priceMapper.findLatest(h.getTicker(), h.getMarket().name()).ifPresent(p -> {
                    if (p.getClosePrice() != null && h.getShares() != null) {
                        BigDecimal perUnit = MetalUnit.perHoldingUnit(h.getUnit(), p.getClosePrice());
                        mi.put("perUnitPrice", perUnit);
                        mi.put("marketValue", perUnit.multiply(h.getShares()));
                        if (h.getCostBasis() != null) {
                            mi.put("pnl", perUnit.subtract(h.getCostBasis()).multiply(h.getShares()));
                        }
                    }
                });
                metalInfo.put(h.getId(), mi);
            }
        }

        // v1.29 · 券商同步来的期权 / 期货 / 债券:一行写人话(张数、标记价 × 乘数、到期提示),
        //   估值分解里单列一格 —— 它们在库里是手动估值行,不单列的话会被算进「手填市值」,读起来像是用户自己填的。
        Map<Long, Map<String, Object>> derivInfo = new HashMap<>();
        BigDecimal derivBase = BigDecimal.ZERO;
        java.time.LocalDate today = java.time.LocalDate.now();
        for (StockHolding h : active) {
            if (!h.isDerivative()) continue;
            var kind = com.family.finance.domain.stock.InstrumentKind.of(h.getInstrumentKind());
            BigDecimal qty = h.getShares() == null ? BigDecimal.ZERO : h.getShares();
            BigDecimal value = h.getManualValue() == null ? BigDecimal.ZERO : h.getManualValue().multiply(qty);
            derivBase = derivBase.add(value);
            Map<String, Object> di = new HashMap<>();
            di.put("kindLabel", kind == null ? h.getInstrumentKind() : kind.getLabel());
            di.put("counts", kind == null || kind.countsInBalance());
            di.put("sideText", com.family.finance.domain.stock.InstrumentKind.sideText(kind, qty));
            di.put("qtyLabel", kind == null || kind.countsContracts() ? "张数" : "面值");
            di.put("short", qty.signum() < 0);
            di.put("value", value);
            di.put("badge", com.family.finance.domain.stock.InstrumentKind.expiryBadge(h.getExpiry(), today));
            String ccy = h.getCurrency() == null ? account.getCurrency() : h.getCurrency();
            if (h.getQuotePrice() != null) {
                di.put("markText", ccy + " " + h.getQuotePrice().stripTrailingZeros().toPlainString()
                        + (h.getMultiplier() != null && kind != com.family.finance.domain.stock.InstrumentKind.BOND
                            ? " × " + h.getMultiplier().stripTrailingZeros().toPlainString() : ""));
            }
            if (h.getNotional() != null) di.put("notionalText", ccy + " " + String.format("%,.0f", h.getNotional().abs()));
            derivInfo.put(h.getId(), di);
        }

        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        model.addAttribute("holdings", active);
        model.addAttribute("valuation", valuation);
        model.addAttribute("derivInfo", derivInfo);
        model.addAttribute("derivBase", derivBase);
        model.addAttribute("latestPrices", latestPrices);
        model.addAttribute("industryTags", com.family.finance.domain.lens.IndustryTag.values()); // v1.1 行业标下拉
        // v1.6.24 · 券商对接状态条:一个账房账户 ↔ 一个券商交易账户(broker_link 上有 UNIQUE(account_id))。
        // 用户路径是「填报 → 持仓管理」,到这儿要能看到对接状况并一步去重配,而不用绕回账户页。
        model.addAttribute("brokerLink", brokerLinkMapper.findByAccount(me.getFamilyId(), accountId).orElse(null));
        model.addAttribute("metalInfo", metalInfo);
        model.addAttribute("priceSourceLabel", switch (account.getType()) {
            case CRYPTO -> "数据源 · Binance(主) + CoinGecko/Coinbase(备)";
            case METAL -> "数据源 · 新浪贵金属(上海 SGE / 国际现货)";
            default -> "数据源 · 新浪(主) + 腾讯(备)";
        });
        return "stock/holdings";
    }

    @GetMapping("/accounts/{accountId}/holdings/new-auto")
    public String newAutoForm(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable long accountId, Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        model.addAttribute("markets", supportedMarkets(account.getType()));
        return "stock/holding-new-auto";
    }

    @PostMapping("/accounts/{accountId}/holdings/new-auto")
    public String createAuto(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable long accountId,
                             @RequestParam(required = false) String displayName,
                             @RequestParam String ticker,
                             @RequestParam String market,
                             @RequestParam BigDecimal shares,
                             @RequestParam(required = false) BigDecimal costBasis,
                             @RequestParam(required = false) String currency,
                             @RequestParam(value = "deductCash", defaultValue = "false") boolean deductCash) {
        Market mk = parseMarket(market);
        holdingService.createAuto(me.getFamilyId(), accountId, displayName, ticker, mk, shares, costBasis, currency, deductCash);
        // 立即刷一次价(让用户看到当前估值)· 失败容忍
        try {
            scheduler.fetchMarket(mk);
            // v0.4.1 · 持仓增改 → trigger=HOLDING_CHANGE · 用户感知此次估值因 holding 变动
            valuationService.refreshAllForFamily(me.getFamilyId(),
                AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId());
        } catch (Exception e) {
            log.warn("post-create refresh failed: {}", e.toString());
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @GetMapping("/accounts/{accountId}/holdings/new-manual")
    public String newManualForm(@AuthenticationPrincipal MemberPrincipal me,
                                @PathVariable long accountId, Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        return "stock/holding-new-manual";
    }

    @PostMapping("/accounts/{accountId}/holdings/new-manual")
    public String createManual(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long accountId,
                               @RequestParam String displayName,
                               @RequestParam BigDecimal shares,
                               @RequestParam BigDecimal unitValue) {
        holdingService.createManual(me.getFamilyId(), accountId, displayName, shares, unitValue);
        try {
            // v0.4.1 · 持仓增改 → trigger=HOLDING_CHANGE · 用户感知此次估值因 holding 变动
            valuationService.refreshAllForFamily(me.getFamilyId(),
                AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId());
        } catch (Exception e) {
            log.warn("post-create refresh failed: {}", e.toString());
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/update")
    public String updateManual(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long accountId,
                               @PathVariable long hid,
                               @RequestParam(required = false) BigDecimal shares,
                               @RequestParam(required = false) BigDecimal unitValue) {
        holdingService.updateManual(me.getFamilyId(), hid, shares, unitValue);
        try { valuationService.refreshAllForFamily(me.getFamilyId(),
            AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId()); } catch (Exception ignored) {}
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    /** v1.1 · 行内改个股行业标(资产透视维度 · 空串=清标回未分类) */
    @PostMapping("/accounts/{accountId}/holdings/{hid}/industry")
    public String updateIndustry(@AuthenticationPrincipal MemberPrincipal me,
                                 @PathVariable long accountId,
                                 @PathVariable long hid,
                                 @RequestParam(required = false) String industryTag) {
        holdingService.updateIndustry(me.getFamilyId(), hid,
                com.family.finance.domain.lens.IndustryTag.fromName(industryTag) == null
                        ? null : industryTag.trim().toUpperCase());
        lensQueryService.evict(me.getFamilyId());   // 行业标即时生效(透视缓存失效)
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    // ---------- v0.14 · METAL 贵金属持仓(issue #4)----------

    @GetMapping("/accounts/{accountId}/holdings/new-metal")
    public String newMetalForm(@AuthenticationPrincipal MemberPrincipal me,
                               @PathVariable long accountId, Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        // 默认价格源(接入源页配)· 决定新建持仓默认源与单位
        String defaultSource = "intl".equalsIgnoreCase(
                configService.getString(me.getFamilyId(), FamilyConfigService.K_METAL_PRICE_SOURCE, "sge"))
                ? "intl" : "sge";
        model.addAttribute("defaultSource", defaultSource);
        return "stock/holding-new-metal";
    }

    @PostMapping("/accounts/{accountId}/holdings/new-metal")
    public String createMetal(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable long accountId,
                              @RequestParam String metal,
                              @RequestParam String source,
                              @RequestParam BigDecimal shares,
                              @RequestParam String unit,
                              @RequestParam(required = false) BigDecimal costBasis) {
        holdingService.createMetal(me.getFamilyId(), accountId, metal, source, shares, unit, costBasis);
        try {
            scheduler.fetchMarket(Market.METAL);
            valuationService.refreshAllForFamily(me.getFamilyId(),
                AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId());
        } catch (Exception e) {
            log.warn("post-create metal refresh failed: {}", e.toString());
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    // ---------- v0.3 FR-52e · CASH 现金行(账户内某币种闲置资金)----------

    @GetMapping("/accounts/{accountId}/holdings/new-cash")
    public String newCashForm(@AuthenticationPrincipal MemberPrincipal me,
                              @PathVariable long accountId, Model model) {
        Account account = requireAccount(me.getFamilyId(), accountId);
        model.addAttribute("me", me);
        model.addAttribute("nav", navService.load(me));
        model.addAttribute("account", account);
        // 常用币种 · 顺序按账户币种优先
        java.util.LinkedHashSet<String> currencies = new java.util.LinkedHashSet<>();
        if (account.getCurrency() != null) currencies.add(account.getCurrency());
        currencies.addAll(List.of("CNY", "USD", "HKD", "JPY", "EUR", "GBP"));
        model.addAttribute("currencies", currencies);
        return "stock/holding-new-cash";
    }

    @PostMapping("/accounts/{accountId}/holdings/new-cash")
    public String createCash(@AuthenticationPrincipal MemberPrincipal me,
                             @PathVariable long accountId,
                             @RequestParam(required = false) String displayName,
                             @RequestParam String currency,
                             @RequestParam BigDecimal amount) {
        holdingService.createCash(me.getFamilyId(), accountId, displayName, currency, amount);
        try { valuationService.refreshAllForFamily(me.getFamilyId(),
            AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId()); } catch (Exception ignored) {}
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/update-cash")
    public String updateCashAmount(@AuthenticationPrincipal MemberPrincipal me,
                                   @PathVariable long accountId,
                                   @PathVariable long hid,
                                   @RequestParam BigDecimal amount) {
        holdingService.updateCashAmount(me.getFamilyId(), hid, amount);
        try { valuationService.refreshAllForFamily(me.getFamilyId(),
            AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId()); } catch (Exception ignored) {}
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/to-manual")
    public String convertToManual(@AuthenticationPrincipal MemberPrincipal me,
                                  @PathVariable long accountId,
                                  @PathVariable long hid) {
        // 把当前估值传入 · 作 MANUAL 起始值
        AccountValuationService.ValuationResult cur = valuationService.valuate(me.getFamilyId(), accountId);
        // 简化:把整个账户 AUTO 部分平均下到该 holding · 实际中由 holding 自身的最新价 × shares 算最准
        // 这里给个保守值 0 让用户重新填(更安全)· 用户在 UI 上看到当前估值后再填
        holdingService.convertToManual(me.getFamilyId(), hid, BigDecimal.ZERO);
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/{hid}/archive")
    public String archive(@AuthenticationPrincipal MemberPrincipal me,
                          @PathVariable long accountId,
                          @PathVariable long hid) {
        holdingService.archive(me.getFamilyId(), hid);
        try { valuationService.refreshAllForFamily(me.getFamilyId(),
            AccountValuationService.TriggerKind.HOLDING_CHANGE, me.getMemberId()); } catch (Exception ignored) {}
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    @PostMapping("/accounts/{accountId}/holdings/refresh")
    public String refresh(@AuthenticationPrincipal MemberPrincipal me,
                          @PathVariable long accountId) {
        try {
            // 全市场都拉一次 · 简单粗暴
            scheduler.fetchMarket(Market.US);
            scheduler.fetchMarket(Market.CN);
            scheduler.fetchMarket(Market.HK);
            scheduler.fetchMarket(Market.CRYPTO);
            scheduler.fetchMarket(Market.METAL);
            // v0.4.1 · 用户主动 click → trigger=MANUAL · 写 valuation event 含用户 ID
            valuationService.refreshAllForFamily(me.getFamilyId(),
                AccountValuationService.TriggerKind.MANUAL, me.getMemberId());
        } catch (Exception e) {
            log.warn("refresh failed: {}", e.toString());
        }
        return "redirect:/accounts/" + accountId + "/holdings";
    }

    // ---------- helpers ----------

    private Account requireAccount(long familyId, long accountId) {
        Account acc = accountMapper.findById(familyId, accountId)
            .orElseThrow(() -> new IllegalArgumentException("账户不存在"));
        if (!acc.getFamilyId().equals(familyId)) {
            throw new IllegalArgumentException("无权访问账户");
        }
        if (!StockHoldingService.supportsHoldings(acc.getType())) {
            throw new IllegalArgumentException("该账户类型不支持持仓管理(支持:股票 / 加密 / 贵金属 / 理财 / 现金)· 当前 " + acc.getType());
        }
        return acc;
    }

    private List<Market> supportedMarkets(AccountType accountType) {
        if (accountType == AccountType.CRYPTO) {
            return List.of(Market.CRYPTO);
        }
        return List.of(Market.US, Market.CN, Market.HK);
    }

    private Market parseMarket(String raw) {
        try {
            return Market.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 market: " + raw);
        }
    }
}
