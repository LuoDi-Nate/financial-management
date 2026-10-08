package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.repository.StockHoldingMapper.TickerMarket;
import com.family.finance.service.config.FamilyConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 股票价格拉取定时任务 · v0.3 FR-52a / v0.4.18(详 prd/v0.4.md §22)。
 *
 * <p>v0.4.18 改动:</p>
 * <ul>
 *   <li>{@code @Scheduled} 注解删除 · 改由 {@code DynamicScheduleConfig} 用
 *       {@link org.springframework.scheduling.TaskScheduler} 注册 ·
 *       cron 时段从 DB 读 · 用户管理页改后即重排</li>
 *   <li>{@code enabled} 改为读 {@link FamilyConfigService} · 实时生效不重启</li>
 *   <li>方法 {@code fetchUsStocks() / fetchCnStocks() / fetchHkStocks()}
 *       签名保持(admin 手动触发链路不变)</li>
 * </ul>
 *
 * <p>默认 cron(代码兜底):</p>
 * <ul>
 *   <li>美股 06:05 每天</li>
 *   <li>A 股 16:10 工作日</li>
 *   <li>港股 16:30 工作日</li>
 *   <li>加密货币 06:15 每天</li>
 * </ul>
 */
@Component
@Slf4j
public class StockPriceScheduler {

    /** 单家庭模式 · 见 prd §22.3 类 A */
    private static final long FAMILY_ID = 1L;

    private final StockHoldingMapper holdingMapper;
    private final StockPriceFetcher fetcher;
    private final FamilyConfigService configService;
    /**
     * v0.4.21 fix · cron 拉完价后必须接 valuation 刷新,否则账户余额永远停留在用户最后一次手动 ↻ 时的值。
     * 这个 wire 从 v0.3 上线就遗漏 · 是 latent bug,不是新功能。
     */
    private final AccountValuationService valuationService;
    /**
     * v1.30 · 基金净值 / 货币基金结转<b>跟着每一次定时拉价跑</b>(§13 ④「跟随原有同步」,不另设 cron)。
     * 节流 30 分钟:早上 06:05 / 06:15、下午 16:10 / 16:20 / 16:30 各只真拉一次。
     */
    private final com.family.finance.service.fund.FundNavService fundNavService;

    public StockPriceScheduler(StockHoldingMapper holdingMapper,
                               StockPriceFetcher fetcher,
                               FamilyConfigService configService,
                               AccountValuationService valuationService,
                               com.family.finance.service.fund.FundNavService fundNavService) {
        this.holdingMapper = holdingMapper;
        this.fetcher = fetcher;
        this.configService = configService;
        this.valuationService = valuationService;
        this.fundNavService = fundNavService;
        log.info("StockPriceScheduler initialized · enabled-source=DB(family_runtime_config.stock_fetch_enabled)");
    }

    /** 实时读 DB 开关 · 用户在 /admin/integrations 改了立刻生效 */
    private boolean isEnabled() {
        return configService.getBoolean(FAMILY_ID, FamilyConfigService.K_STOCK_ENABLED, false);
    }

    /** 美股 · 由 DynamicScheduleConfig 按 cron(stock_cron_us)调用 · 默认每天 06:05 */
    public void fetchUsStocks() {
        if (!isEnabled()) {
            log.debug("US fetch SKIPPED · disabled");
            return;
        }
        int persisted = fetchMarket(Market.US);
        refreshValuationsAfterCron(Market.US, persisted);
    }

    /** A 股 · 默认工作日 16:10 · cron 由 stock_cron_cn 配 */
    public void fetchCnStocks() {
        if (!isEnabled()) return;
        int persisted = fetchMarket(Market.CN);
        refreshValuationsAfterCron(Market.CN, persisted);
    }

    /** 港股 · 默认工作日 16:30 · cron 由 stock_cron_hk 配 */
    public void fetchHkStocks() {
        if (!isEnabled()) return;
        int persisted = fetchMarket(Market.HK);
        refreshValuationsAfterCron(Market.HK, persisted);
    }

    /** 加密货币 · 默认每天 06:15 · cron 由 stock_cron_crypto 配 */
    public void fetchCrypto() {
        if (!isEnabled()) return;
        int persisted = fetchMarket(Market.CRYPTO);
        refreshValuationsAfterCron(Market.CRYPTO, persisted);
    }

    /** 贵金属 · 默认 SGE 收盘后一次 · cron 由 metal_cron 配(v0.14) */
    public void fetchMetal() {
        if (!isEnabled()) return;
        int persisted = fetchMarket(Market.METAL);
        refreshValuationsAfterCron(Market.METAL, persisted);
    }

    /**
     * cron 拉完价后刷账户估值 · 写 period_snapshot 新余额 + 若 |Δ|>0.01 写 stock_valuation_event。
     * 仅 cron 入口走此方法 · admin 手动入口 ({@link com.family.finance.web.stock.StockHoldingController})
     * 自己显式调 {@code refreshAllForFamily(MANUAL/HOLDING_CHANGE)} · 避免双跑。
     */
    private void refreshValuationsAfterCron(Market market, int persisted) {
        // v1.30 · 先同步基金(只改持仓行;有变化的家庭在里面顺手写回估值),再走原来的估值写回
        try {
            fundNavService.refreshAllFamilies();
        } catch (Exception e) {
            log.warn("post-cron 基金净值同步失败 · market={}: {}", market, e.toString());
        }
        try {
            int refreshed = valuationService.refreshAllForFamily(
                FAMILY_ID,
                AccountValuationService.TriggerKind.CRON,
                null);
            log.info("post-cron valuation refresh · market={} persisted={} accountsRefreshed={}",
                market, persisted, refreshed);
        } catch (Exception e) {
            log.warn("post-cron valuation refresh failed · market={}: {}", market, e.toString());
        }
    }

    /**
     * 内部统一方法 · 也用作 admin 手动触发入口(不读 enabled 开关 · 手动等于显式覆盖)。
     * 注意:本方法只持久化 price_snapshot · 估值刷新由调用方负责
     * (cron 入口走 {@link #refreshValuationsAfterCron} · admin 入口自己调 refreshAllForFamily)。
     */
    public int fetchMarket(Market market) {
        List<TickerMarket> tickers = holdingMapper.findDistinctAutoTickersByMarket(market.name());
        if (tickers.isEmpty()) {
            log.info("market={} no AUTO holdings · skip fetch", market);
            return 0;
        }
        List<String> tickerList = tickers.stream().map(TickerMarket::ticker).toList();
        log.info("market={} fetching {} tickers · {}", market, tickerList.size(), tickerList);
        return fetcher.fetchAndPersist(market, tickerList, LocalDate.now());
    }
}
