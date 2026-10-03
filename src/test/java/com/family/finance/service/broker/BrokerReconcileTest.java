package com.family.finance.service.broker;

import com.family.finance.domain.broker.BrokerVendor;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.domain.stock.ValuationMode;
import com.family.finance.repository.BrokerLinkMapper;
import com.family.finance.repository.StockHoldingMapper;
import com.family.finance.service.stock.AccountValuationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v0.15 护栏 · 券商对账不变式:
 * <ul>
 *   <li>只动 {@code sync_source=本 vendor} 的行 —— <b>绝不碰用户手填持仓</b>;</li>
 *   <li>券商有我方无 → insert;都有 → update;我方有券商无 → archive;</li>
 *   <li>现金按币种 upsert;期权/期货计数体现在摘要。</li>
 * </ul>
 */
class BrokerReconcileTest {

    private static final long FAM = 1L;
    private static final long ACC = 10L;

    private StockHolding h(long id, ValuationMode mode, String ticker, Market market,
                           String currency, String syncSource) {
        return StockHolding.builder().id(id).accountId(ACC).valuationMode(mode)
                .ticker(ticker).market(market).currency(currency)
                .shares(BigDecimal.ONE).costBasis(BigDecimal.TEN)
                .manualValue(BigDecimal.valueOf(100)).syncSource(syncSource).build();
    }

    private BrokerSyncService svc(StockHoldingMapper holdingMapper) {
        return new BrokerSyncService(mock(BrokerLinkMapper.class), holdingMapper,
                List.of(), mock(AccountValuationService.class));
    }

    @Test
    void reconcile_upserts_and_archives_only_synced_rows() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);

        StockHolding synced_aapl = h(1, ValuationMode.AUTO, "AAPL", Market.US, "USD", "FUTU"); // 更新
        StockHolding synced_old  = h(2, ValuationMode.AUTO, "OLD",  Market.US, "USD", "FUTU"); // 券商已无 → 归档
        StockHolding manual      = h(3, ValuationMode.MANUAL, null, null, "CNY", null);        // 用户手填 → 不可碰
        StockHolding synced_cash = h(4, ValuationMode.CASH,  null, null, "USD", "FUTU");        // 现金更新

        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of(synced_aapl, synced_old, manual, synced_cash));

        BrokerDtos.Snapshot snap = new BrokerDtos.Snapshot(
                List.of(new BrokerDtos.Position("US", "AAPL", "苹果", BigDecimal.valueOf(20), BigDecimal.valueOf(95), "USD", true),
                        new BrokerDtos.Position("US", "NVDA", "英伟达", BigDecimal.valueOf(5),  BigDecimal.valueOf(92), "USD", true)),
                List.of(new BrokerDtos.Cash("USD", BigDecimal.valueOf(12300)),
                        new BrokerDtos.Cash("HKD", BigDecimal.valueOf(8600))),
                2 /* skippedNonEquity */);

        String summary = svc(hm).reconcile(FAM, ACC, BrokerVendor.FUTU, snap);

        // 新增:NVDA + HKD 现金 = 2;更新:AAPL + USD 现金 = 2;归档:OLD = 1
        verify(hm, times(2)).insertOwned(anyLong(), any());
        verify(hm).update(FAM, synced_aapl);
        verify(hm).update(FAM, synced_cash);
        verify(hm).archive(FAM, 2L);
        // 关键护栏:用户手填持仓(id=3)绝不被归档/更新
        verify(hm, never()).archive(FAM, 3L);
        assertThat(summary).contains("新增 2").contains("更新 2").contains("归档 1").contains("跳过其他品种 2");
        // AAPL 更新为券商新股数/成本
        assertThat(synced_aapl.getShares()).isEqualByComparingTo("20");
        assertThat(synced_aapl.getCostBasis()).isEqualByComparingTo("95");
        // 显示名升级:旧名是裸代码/空 → 用券商证券名(用户改过的名不覆盖,由 null→苹果 这条路径覆盖)
        assertThat(synced_aapl.getDisplayName()).isEqualTo("苹果");
    }

    @Test
    void reconcile_ignores_rows_from_other_vendor() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        // 该账户只有一条 TIGER 同步行,现在跑 FUTU 对账 → 不应碰它
        StockHolding tigerRow = h(9, ValuationMode.AUTO, "AAPL", Market.US, "USD", "TIGER");
        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of(tigerRow));

        BrokerDtos.Snapshot empty = new BrokerDtos.Snapshot(List.of(), List.of(), 0);
        svc(hm).reconcile(FAM, ACC, BrokerVendor.FUTU, empty);

        verify(hm, never()).archive(FAM, 9L);
        verify(hm, never()).update(anyLong(), any());
    }

    /**
     * v1.26 · IBKR 里拉不到价的市场(伦敦上市的美元 ETF 等)→ 手动估值行,单价 = 报表收盘价折成账户币种。
     * 手动估值行的单价语义是「账户币种」,不折算就会把美元单价当人民币。
     */
    @Test
    void reconcile_ibkr_manual_positions_are_priced_in_account_currency() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        AccountValuationService vs = mock(AccountValuationService.class);
        when(vs.fxToAccountCurrency(FAM, ACC, "USD")).thenReturn(new BigDecimal("7.10"));
        StockHolding oldLse = StockHolding.builder().id(21L).accountId(ACC).valuationMode(ValuationMode.MANUAL)
                .ticker("VWRA").shares(BigDecimal.TEN).manualValue(BigDecimal.ONE).syncSource("IBKR").build();
        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of(oldLse));

        BrokerDtos.Snapshot snap = new BrokerDtos.Snapshot(List.of(), List.of(), 0, List.of(
                new BrokerDtos.ManualPosition("VWRA", "VANGUARD FTSE ALL-WORLD", "LSEETF",
                        new BigDecimal("30"), new BigDecimal("131.52"), new BigDecimal("118.20"), "USD"),
                new BrokerDtos.ManualPosition("7203", "TOYOTA", "TSEJ",
                        new BigDecimal("100"), new BigDecimal("20.00"), null, "USD")));
        String summary = new BrokerSyncService(mock(BrokerLinkMapper.class), hm, List.of(), vs)
                .reconcile(FAM, ACC, BrokerVendor.IBKR, snap);

        // 已有的 VWRA:股数与单价都换成新报表的,单价折成账户币种(131.52 × 7.10)
        verify(hm).update(FAM, oldLse);
        assertThat(oldLse.getShares()).isEqualByComparingTo("30");
        assertThat(oldLse.getManualValue()).isEqualByComparingTo("933.792");
        assertThat(oldLse.getCostBasis()).isEqualByComparingTo("839.22");
        assertThat(oldLse.getManualValueAt()).isNotNull();
        // 新的丰田:新建一条手动估值行,标 IBKR 同步来源
        org.mockito.ArgumentCaptor<StockHolding> cap = org.mockito.ArgumentCaptor.forClass(StockHolding.class);
        verify(hm).insertOwned(org.mockito.ArgumentMatchers.eq(FAM), cap.capture());
        assertThat(cap.getValue().getValuationMode()).isEqualTo(ValuationMode.MANUAL);
        assertThat(cap.getValue().getSyncSource()).isEqualTo("IBKR");
        assertThat(cap.getValue().getDisplayName()).isEqualTo("TOYOTA · TSEJ");
        assertThat(summary).contains("按券商收盘价估值 2");
    }

    // ───────────── v1.29 · 期权 / 期货 / 债券(issue #26)─────────────

    private static BrokerDtos.Derivative opt(String symbol, String und, String pc, String strike, String qty,
                                             String mark, String value) {
        return new BrokerDtos.Derivative(com.family.finance.domain.stock.InstrumentKind.OPTION, symbol, und, pc,
                new BigDecimal(strike), java.time.LocalDate.of(2027, 4, 16), BigDecimal.valueOf(100),
                new BigDecimal(qty), new BigDecimal(mark), new BigDecimal(value), null, "USD", null);
    }

    /**
     * 期权落成手动估值行:单价 = 持仓市值 ÷ 张数(折成账户币种),张数带符号 ——
     * 单价 × 张数 必须正好还原成券商给的市值(卖出为负),估值路径不用改一行。
     */
    @Test
    void 期权落成手动估值行_单价乘张数还原券商市值_卖出为负_期货记0() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        AccountValuationService vs = mock(AccountValuationService.class);
        when(vs.fxToAccountCurrency(FAM, ACC, "USD")).thenReturn(BigDecimal.ONE);
        when(vs.accountCurrency(FAM, ACC)).thenReturn("USD");
        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of());

        BrokerDtos.Snapshot snap = new BrokerDtos.Snapshot(List.of(), List.of(), 0, List.of(), List.of(
                opt("GOOGL 270416C00360000", "GOOGL", "C", "360", "1", "28.5", "2850"),
                opt("GOOGL 270416C00400000", "GOOGL", "C", "400", "-1", "16.2", "-1620"),
                new BrokerDtos.Derivative(com.family.finance.domain.stock.InstrumentKind.FUTURE, "ESZ6", "ES", null,
                        null, java.time.LocalDate.of(2026, 12, 18), BigDecimal.valueOf(50), BigDecimal.ONE,
                        new BigDecimal("6000"), BigDecimal.ZERO, new BigDecimal("300000"), "USD", "ES 18DEC26")),
                List.of("TSLA · 看涨 · 行权价 300 · 2026-11-20 到期:报表里缺持仓市值,这一行没有同步"));
        String summary = new BrokerSyncService(mock(BrokerLinkMapper.class), hm, List.of(), vs)
                .reconcile(FAM, ACC, BrokerVendor.IBKR, snap);

        org.mockito.ArgumentCaptor<StockHolding> cap = org.mockito.ArgumentCaptor.forClass(StockHolding.class);
        verify(hm, times(3)).insertOwned(org.mockito.ArgumentMatchers.eq(FAM), cap.capture());
        var rows = cap.getAllValues();
        StockHolding longCall = rows.get(0), shortCall = rows.get(1), fut = rows.get(2);
        assertThat(longCall.getValuationMode()).isEqualTo(ValuationMode.MANUAL);
        assertThat(longCall.getInstrumentKind()).isEqualTo("OPTION");
        assertThat(longCall.getSyncSource()).isEqualTo("IBKR");
        assertThat(longCall.getDisplayName()).isEqualTo("GOOGL · 看涨 · 行权价 360 · 2027-04-16 到期");
        assertThat(longCall.getManualValue().multiply(longCall.getShares())).isEqualByComparingTo("2850");
        assertThat(shortCall.getShares()).isEqualByComparingTo("-1");
        assertThat(shortCall.getManualValue().multiply(shortCall.getShares())).isEqualByComparingTo("-1620");
        assertThat(shortCall.getCostBasis()).isNull();                 // 不给半对的盈亏
        assertThat(fut.getManualValue()).isEqualByComparingTo("0");    // 期货不计入余额
        assertThat(fut.getNotional()).isEqualByComparingTo("300000");
        assertThat(summary).contains("期权 2 笔 · 市值 USD 1,230").contains("期货 1 笔(不计入余额)")
                .contains("1 行数据对不上没同步(TSLA · 看涨 · 行权价 300 · 2026-11-20 到期:报表里缺持仓市值)");
    }

    @Test
    void 期权折算到账户币种_港币账户里的美股期权() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        AccountValuationService vs = mock(AccountValuationService.class);
        when(vs.fxToAccountCurrency(FAM, ACC, "USD")).thenReturn(new BigDecimal("7.8"));
        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of());
        BrokerDtos.Snapshot snap = new BrokerDtos.Snapshot(List.of(), List.of(), 0, List.of(), List.of(
                opt("SPY   261009P00560000", "SPY", "P", "560", "-2", "1.70", "-340")), List.of());
        new BrokerSyncService(mock(BrokerLinkMapper.class), hm, List.of(), vs).reconcile(FAM, ACC, BrokerVendor.IBKR, snap);
        org.mockito.ArgumentCaptor<StockHolding> cap = org.mockito.ArgumentCaptor.forClass(StockHolding.class);
        verify(hm).insertOwned(org.mockito.ArgumentMatchers.eq(FAM), cap.capture());
        // −340 美元 × 7.8 = −2652 港币
        assertThat(cap.getValue().getManualValue().multiply(cap.getValue().getShares())).isEqualByComparingTo("-2652");
    }

    /** 同一张合约下次同步:更新,不新建;到期 / 平仓的(报表里没了):归档;用户手填的:不碰,但提醒可能算两遍 */
    @Test
    void 期权更新_到期归档_手填行不碰但提醒可能算两遍() {
        StockHoldingMapper hm = mock(StockHoldingMapper.class);
        AccountValuationService vs = mock(AccountValuationService.class);
        when(vs.fxToAccountCurrency(FAM, ACC, "USD")).thenReturn(BigDecimal.ONE);
        StockHolding held = StockHolding.builder().id(31L).accountId(ACC).valuationMode(ValuationMode.MANUAL)
                .instrumentKind("OPTION").ticker("GOOGL 270416C00360000").displayName("我改过的名字")
                .shares(BigDecimal.ONE).manualValue(new BigDecimal("2000")).syncSource("IBKR").build();
        StockHolding expired = StockHolding.builder().id(32L).accountId(ACC).valuationMode(ValuationMode.MANUAL)
                .instrumentKind("OPTION").ticker("SPY   260918P00550000")
                .shares(BigDecimal.ONE).manualValue(BigDecimal.TEN).syncSource("IBKR").build();
        StockHolding userManual = StockHolding.builder().id(33L).accountId(ACC).valuationMode(ValuationMode.MANUAL)
                .displayName("期权(手填)").shares(BigDecimal.ONE).manualValue(new BigDecimal("900")).build();
        when(hm.findActiveByAccount(FAM, ACC)).thenReturn(List.of(held, expired, userManual));

        BrokerDtos.Snapshot snap = new BrokerDtos.Snapshot(List.of(), List.of(), 0, List.of(), List.of(
                opt("GOOGL 270416C00360000", "GOOGL", "C", "360", "1", "28.5", "2850"),
                opt("GOOGL 270416C00400000", "GOOGL", "C", "400", "-1", "16.2", "-1620")), List.of());
        String summary = new BrokerSyncService(mock(BrokerLinkMapper.class), hm, List.of(), vs)
                .reconcile(FAM, ACC, BrokerVendor.IBKR, snap);

        verify(hm).update(FAM, held);
        assertThat(held.getManualValue()).isEqualByComparingTo("2850");
        assertThat(held.getDisplayName()).isEqualTo("我改过的名字");   // 用户改过的名不覆盖
        verify(hm).archive(FAM, 32L);                                // 报表里没了 = 到期 / 平仓
        verify(hm, never()).archive(FAM, 33L);                       // 手填的绝不碰
        verify(hm, never()).update(FAM, userManual);
        assertThat(summary).contains("账户里另有 1 条手填持仓");
    }

    @Test
    void 同步结果落库前截到255字以内() {
        String longText = "同步 · " + "x".repeat(400);
        assertThat(BrokerSyncService.clip(longText)).hasSizeLessThanOrEqualTo(250);
        assertThat(BrokerSyncService.clip("短的")).isEqualTo("短的");
    }
}
