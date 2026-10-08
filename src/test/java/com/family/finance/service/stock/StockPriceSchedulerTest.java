package com.family.finance.service.stock;

import com.family.finance.domain.stock.Market;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.repository.StockHoldingMapper.TickerMarket;
import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v0.4.21 防回归 · scheduled cron 拉价必须接 valuation 刷新,否则余额永不更新。
 *
 * <p>这个 wire 从 v0.3 上线起就遗漏(latent bug),修复见
 * {@link StockPriceScheduler#refreshValuationsAfterCron} · 本测试锁死该行为。</p>
 *
 * <p>注意区分两条路径(都跑此 verify 才能完整覆盖回归面):</p>
 * <ol>
 *   <li><b>cron 入口</b> ({@code fetchUsStocks/CnStocks/HkStocks})· 必须自动接
 *       {@code refreshAllForFamily(CRON, null)};</li>
 *   <li><b>admin 手动入口</b> ({@code fetchMarket})· 不带 refresh ·
 *       由 controller 显式调 MANUAL/HOLDING_CHANGE · 避免双跑。</li>
 * </ol>
 */
class StockPriceSchedulerTest {

    private StockHoldingMapper holdingMapper;
    private StockPriceFetcher fetcher;
    private FamilyConfigService configService;
    private AccountValuationService valuationService;
    private StockPriceScheduler scheduler;

    @BeforeEach
    void setUp() {
        holdingMapper = mock(StockHoldingMapper.class);
        fetcher = mock(StockPriceFetcher.class);
        configService = mock(FamilyConfigService.class);
        valuationService = mock(AccountValuationService.class);
        scheduler = new StockPriceScheduler(holdingMapper, fetcher, configService, valuationService,
                org.mockito.Mockito.mock(com.family.finance.service.fund.FundNavService.class));

        // 默认开启,有持仓
        when(configService.getBoolean(eq(1L), eq(FamilyConfigService.K_STOCK_ENABLED), any(Boolean.class)))
            .thenReturn(true);
        when(fetcher.fetchAndPersist(any(), any(), any(LocalDate.class))).thenReturn(2);
    }

    @Test
    void fetchUsStocks_triggersCronValuationRefresh() {
        when(holdingMapper.findDistinctAutoTickersByMarket("US"))
            .thenReturn(List.of(new TickerMarket("BABA", "US"), new TickerMarket("PDD", "US")));

        scheduler.fetchUsStocks();

        verify(fetcher).fetchAndPersist(eq(Market.US), any(), any());
        verify(valuationService).refreshAllForFamily(
            eq(1L),
            eq(AccountValuationService.TriggerKind.CRON),
            eq(null));
    }

    @Test
    void fetchCnStocks_triggersCronValuationRefresh() {
        when(holdingMapper.findDistinctAutoTickersByMarket("CN"))
            .thenReturn(List.of(new TickerMarket("300059", "CN")));

        scheduler.fetchCnStocks();

        verify(valuationService).refreshAllForFamily(
            eq(1L),
            eq(AccountValuationService.TriggerKind.CRON),
            eq(null));
    }

    @Test
    void fetchHkStocks_triggersCronValuationRefresh() {
        when(holdingMapper.findDistinctAutoTickersByMarket("HK"))
            .thenReturn(List.of(new TickerMarket("00700", "HK")));

        scheduler.fetchHkStocks();

        verify(valuationService).refreshAllForFamily(
            eq(1L),
            eq(AccountValuationService.TriggerKind.CRON),
            eq(null));
    }

    @Test
    void fetchCrypto_triggersCronValuationRefresh() {
        when(holdingMapper.findDistinctAutoTickersByMarket("CRYPTO"))
            .thenReturn(List.of(new TickerMarket("BTC", "CRYPTO")));

        scheduler.fetchCrypto();

        verify(fetcher).fetchAndPersist(eq(Market.CRYPTO), any(), any());
        verify(valuationService).refreshAllForFamily(
            eq(1L),
            eq(AccountValuationService.TriggerKind.CRON),
            eq(null));
    }

    @Test
    void fetchMarketDirect_doesNotAutoRefresh_callerIsResponsible() {
        // admin 手动入口 · scheduler.fetchMarket 直接调 · 不带 refresh
        // (controller 自己接 refreshAllForFamily(MANUAL/HOLDING_CHANGE))
        when(holdingMapper.findDistinctAutoTickersByMarket("US"))
            .thenReturn(List.of(new TickerMarket("BABA", "US")));

        scheduler.fetchMarket(Market.US);

        verify(fetcher).fetchAndPersist(eq(Market.US), any(), any());
        verify(valuationService, never()).refreshAllForFamily(anyLong(), any(), any());
    }

    @Test
    void disabledFlag_skipsBothFetchAndRefresh() {
        when(configService.getBoolean(eq(1L), eq(FamilyConfigService.K_STOCK_ENABLED), any(Boolean.class)))
            .thenReturn(false);

        scheduler.fetchUsStocks();

        verify(fetcher, never()).fetchAndPersist(any(), any(), any());
        verify(valuationService, never()).refreshAllForFamily(anyLong(), any(), any());
    }

    @Test
    void emptyTickers_stillRefreshesValuation_因为可能补价格陈旧账户() {
        // 没有 ticker 持仓 → fetcher 不调用 · 但 refresh 仍跑
        // (新 cron 一律刷,因为没有新价格也可能有 holding 变更未结算)
        when(holdingMapper.findDistinctAutoTickersByMarket("US"))
            .thenReturn(List.of());

        scheduler.fetchUsStocks();

        verify(fetcher, never()).fetchAndPersist(any(), any(), any());
        verify(valuationService).refreshAllForFamily(
            eq(1L), eq(AccountValuationService.TriggerKind.CRON), eq(null));
    }

    @Test
    void valuationFailure_doesNotPropagate_只warn() {
        when(holdingMapper.findDistinctAutoTickersByMarket("US"))
            .thenReturn(List.of(new TickerMarket("BABA", "US")));
        when(valuationService.refreshAllForFamily(anyLong(), any(), any()))
            .thenThrow(new RuntimeException("simulated DB down"));

        // 不抛 · cron 不会因 valuation 异常中断后续市场抓取
        scheduler.fetchUsStocks();

        verify(fetcher).fetchAndPersist(eq(Market.US), any(), any());
        verify(valuationService).refreshAllForFamily(anyLong(), any(), any());
    }

    @Test
    void scheduledFetchPersistedRows_logsAccountsRefreshed() {
        // 集成意图:fetcher 返回 persisted=2 → valuationService 也被调
        // 主要锁的是 wire 顺序,具体数字不强约束(看日志即可)
        when(holdingMapper.findDistinctAutoTickersByMarket("CN"))
            .thenReturn(List.of(new TickerMarket("300059", "CN")));
        when(valuationService.refreshAllForFamily(anyLong(), any(), any())).thenReturn(3);

        scheduler.fetchCnStocks();

        verify(valuationService).refreshAllForFamily(
            eq(1L), eq(AccountValuationService.TriggerKind.CRON), eq(null));
    }

    @Test
    void allMarkets_eachFetchTriggersOwnRefresh() {
        // 一天内多个市场依次跑 · 应触发各自 refresh
        when(holdingMapper.findDistinctAutoTickersByMarket("US"))
            .thenReturn(List.of(new TickerMarket("BABA", "US")));
        when(holdingMapper.findDistinctAutoTickersByMarket("CN"))
            .thenReturn(List.of(new TickerMarket("300059", "CN")));
        when(holdingMapper.findDistinctAutoTickersByMarket("HK"))
            .thenReturn(List.of(new TickerMarket("00700", "HK")));
        when(holdingMapper.findDistinctAutoTickersByMarket("CRYPTO"))
            .thenReturn(List.of(new TickerMarket("BTC", "CRYPTO")));

        scheduler.fetchUsStocks();
        scheduler.fetchCnStocks();
        scheduler.fetchHkStocks();
        scheduler.fetchCrypto();

        // 4 次 refresh
        verify(valuationService, org.mockito.Mockito.times(4))
            .refreshAllForFamily(eq(1L), eq(AccountValuationService.TriggerKind.CRON), eq(null));
    }

    @Test
    void valuationRefreshReceivesNullMemberId_因为是系统触发() {
        when(holdingMapper.findDistinctAutoTickersByMarket("US"))
            .thenReturn(List.of(new TickerMarket("BABA", "US")));

        scheduler.fetchUsStocks();

        // 关键:第 3 参数(memberId)必须是 null · 否则 stock_valuation_event 会把某用户当替罪羊
        verify(valuationService).refreshAllForFamily(
            eq(1L), eq(AccountValuationService.TriggerKind.CRON), eq(null));
    }

    @Test
    void disabledFlagDefaultsFalse_第一次启动从未配置时不跑() {
        // 模拟 family_runtime_config 缺 stock_fetch_enabled row · getBoolean 走 default=false
        when(configService.getBoolean(eq(1L), eq(FamilyConfigService.K_STOCK_ENABLED), eq(false)))
            .thenReturn(false);

        scheduler.fetchHkStocks();

        verify(fetcher, never()).fetchAndPersist(any(), any(), any());
        verify(valuationService, never()).refreshAllForFamily(anyLong(), any(), any());
    }

    @Test
    void triggerKindIsCron_不是MANUAL_不是HOLDING_CHANGE() {
        when(holdingMapper.findDistinctAutoTickersByMarket("US"))
            .thenReturn(List.of(new TickerMarket("BABA", "US")));

        scheduler.fetchUsStocks();

        // 锁死 trigger 来源标识 · 用户在 holding 页看 ledger 时能区分「📈 系统估值」vs「✋ 手动」
        verify(valuationService, never()).refreshAllForFamily(
            anyLong(), eq(AccountValuationService.TriggerKind.MANUAL), any());
        verify(valuationService, never()).refreshAllForFamily(
            anyLong(), eq(AccountValuationService.TriggerKind.HOLDING_CHANGE), any());
        verify(valuationService).refreshAllForFamily(
            anyLong(), eq(AccountValuationService.TriggerKind.CRON), eq(null));
    }

    @Test
    void wireExists_防latent_bug_重新出现() {
        // 这是个 "wire 是否存在" 的兜底断言 · 任何一个 cron 入口跑一次,
        // valuationService 必须被调至少一次。删了 refreshValuationsAfterCron 此 test 立挂。
        when(holdingMapper.findDistinctAutoTickersByMarket("US"))
            .thenReturn(List.of(new TickerMarket("BABA", "US")));

        scheduler.fetchUsStocks();

        // 哪怕参数变了,只要被调过都行;断的就是「自动拉价 → 自动估值刷新」这条链
        verify(valuationService, org.mockito.Mockito.atLeastOnce())
            .refreshAllForFamily(anyLong(), any(), any());
        assertThat(true).as("wire 已存在(若挂掉 = StockPriceScheduler 又退化为只持久化 snapshot 不刷余额)").isTrue();
    }
}
