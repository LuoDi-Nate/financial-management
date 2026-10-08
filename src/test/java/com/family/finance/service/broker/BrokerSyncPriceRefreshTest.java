package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerLink;
import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.BrokerLinkMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.stock.AccountValuationService;
import com.family.finance.service.stock.StockPriceFetcher;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * issue #26(续)· 「立即同步」之后同步进来的股票要当场有价。
 *
 * <p>@Jsonya 2026-10-05:同步完要再手动刷一次价余额才对。根因是同步后直接估值,读的是库里已有的行情快照 ——
 * 第一次出现的代码没有快照(按 0 计),老代码是上一次拉价那天的价。修法:同步落库之后、估值之前,
 * 给这个账户的上市持仓按市场拉一次价。e2e flow 43 走真实页面复验。</p>
 */
class BrokerSyncPriceRefreshTest {

    private static final long FAM = 1L, ACC = 7L;

    private static StockHolding auto(String ticker, Market m) {
        return StockHolding.builder().id((long) ticker.hashCode()).accountId(ACC).displayName(ticker)
                .valuationMode(ValuationMode.AUTO).ticker(ticker).market(m).shares(BigDecimal.TEN).currency("USD").build();
    }

    private static StockHolding manual(String name) {
        return StockHolding.builder().id((long) name.hashCode()).accountId(ACC).displayName(name)
                .valuationMode(ValuationMode.MANUAL).shares(BigDecimal.ONE).manualValue(BigDecimal.TEN).build();
    }

    private record Fixture(BrokerSyncService svc, StockPriceFetcher fetcher, AccountValuationService valuation) {}

    private static Fixture fixture(boolean withFetcher) {
        BrokerLinkMapper linkMapper = mock(BrokerLinkMapper.class);
        BrokerLink link = new BrokerLink();
        link.setVendor(BrokerVendor.IBKR);
        link.setEnabled(true);
        when(linkMapper.findByAccount(FAM, ACC)).thenReturn(Optional.of(link));
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of(
                auto("AAPL", Market.US), auto("KO", Market.US), auto("AAPL", Market.US),   // 同一代码两行只拉一次
                auto("00700", Market.HK), manual("某未上市"), auto("", Market.US)));
        BrokerClient client = new BrokerClient() {
            public BrokerVendor vendor() { return BrokerVendor.IBKR; }
            public BrokerDtos.TestReport testConnection(long f, BrokerLink l) { return null; }
            public BrokerDtos.Snapshot fetch(long f, BrokerLink l) {
                return new BrokerDtos.Snapshot(List.of(), List.of(), 0, List.of(), List.of(), List.of());
            }
        };
        AccountValuationService valuation = mock(AccountValuationService.class);
        BrokerSyncService svc = new BrokerSyncService(linkMapper, hm, List.of(client), valuation);
        StockPriceFetcher fetcher = mock(StockPriceFetcher.class);
        if (withFetcher) svc.setPriceFetcher(fetcher);
        return new Fixture(svc, fetcher, valuation);
    }

    @Test
    void sync_fetches_prices_for_the_accounts_listed_holdings_before_valuing() {
        Fixture f = fixture(true);
        f.svc().sync(FAM, ACC, 3L);

        InOrder order = inOrder(f.fetcher(), f.valuation());
        order.verify(f.fetcher()).fetchAndPersist(eq(Market.US), eq(List.of("AAPL", "KO")), eq(LocalDate.now()));
        order.verify(f.valuation()).refreshAllForFamily(eq(FAM), eq(AccountValuationService.TriggerKind.HOLDING_CHANGE),
                eq(3L), any());
        verify(f.fetcher()).fetchAndPersist(eq(Market.HK), eq(List.of("00700")), eq(LocalDate.now()));
        // 手填行、空代码不拉;只按这个账户的代码拉,不拉整个市场
        verify(f.fetcher(), times(2)).fetchAndPersist(any(), anyList(), any());
    }

    @Test
    void price_fetch_failure_does_not_fail_the_sync() {
        Fixture f = fixture(true);
        when(f.fetcher().fetchAndPersist(eq(Market.US), anyList(), any())).thenThrow(new RuntimeException("sina down"));

        String summary = f.svc().sync(FAM, ACC, null);

        assertThat(summary).startsWith("同步");
        verify(f.fetcher()).fetchAndPersist(eq(Market.HK), anyList(), any());   // 一个市场挂了,别的市场照拉
        verify(f.valuation()).refreshAllForFamily(eq(FAM), any(), any(), any());   // 估值照旧(回落到最近已知价)
    }

    @Test
    void without_fetcher_sync_still_values() {
        Fixture f = fixture(false);
        f.svc().sync(FAM, ACC, null);
        verifyNoInteractions(f.fetcher());
        verify(f.valuation()).refreshAllForFamily(eq(FAM), any(), any(), any());
    }
}
