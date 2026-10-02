package com.family.finance.service.broker;

import com.family.finance.domain.stock.InstrumentKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 券商只读拉取的中性 DTO(与具体 SDK 解耦)· v0.15。 */
public final class BrokerDtos {
    private BrokerDtos() {}

    /** 一笔持仓(归一到我们的 Market + 纯 ticker;name=券商侧证券名(可空);equity 恒为 true,期权等走 {@link Derivative})。 */
    public record Position(String market, String ticker, String name, BigDecimal shares,
                           BigDecimal costPrice, String currency, boolean equity) {}

    /** 某币种现金。 */
    public record Cash(String currency, BigDecimal amount) {}

    /**
     * 拉不到价的股票(v1.26 · IBKR 的伦敦 / 东京 / 新加坡等市场):按券商给的收盘价做「手动估值」持仓。
     * {@code unitPrice} / {@code costPrice} 是持仓币种的单价;折成账户币种在对账时做(那里才知道账户币种)。
     */
    public record ManualPosition(String symbol, String name, String exchange, BigDecimal shares,
                                 BigDecimal unitPrice, BigDecimal costPrice, String currency) {}

    /**
     * v1.29 · 期权 / 权证 / 期货 / 债券一笔(issue #26)。
     *
     * <p>{@code quantity} 带符号:卖出(做空)为负。{@code marketValue} 是<b>计入余额的那个数</b>(原币、带符号):
     * 期权 / 权证 / 债券 = 券商给的持仓市值(已含乘数);期货 = 0(盈亏每天结算进现金,见 {@link InstrumentKind#FUTURE})。
     * 期货的名义价值另放 {@code notional},只展示。</p>
     *
     * <p>{@code symbol} 是券商原代码(盈透 {@code GOOGL 270416C00360000},富途 {@code AAPL260116C250000}),
     * 对账时用它认「是不是同一张合约」。</p>
     */
    public record Derivative(InstrumentKind kind, String symbol, String underlying, String putCall,
                             BigDecimal strike, LocalDate expiry, BigDecimal multiplier,
                             BigDecimal quantity, BigDecimal markPrice, BigDecimal marketValue,
                             BigDecimal notional, String currency, String description) {}

    /**
     * 一次拉取快照(v1.29 扩):
     * <ul>
     *   <li>{@code positions} 股票 / ETF(系统拉价)· {@code manualPositions} 拉不到价、按券商收盘价估值的股票(v1.26)</li>
     *   <li>{@code derivatives} 期权 / 权证 / 期货 / 债券(v1.29)</li>
     *   <li>{@code skippedNonEquity} 仍不同步的品种笔数(差价合约、外汇头寸、未支持市场 …)</li>
     *   <li>{@code rejected} 数据不全或自相矛盾、<b>没同步</b>的行,一行一句人话(FR-950:不拿猜的数顶上)</li>
     * </ul>
     */
    public record Snapshot(List<Position> positions, List<Cash> cash, int skippedNonEquity,
                           List<ManualPosition> manualPositions, List<Derivative> derivatives,
                           List<String> rejected) {
        public Snapshot {
            positions = positions == null ? List.of() : positions;
            cash = cash == null ? List.of() : cash;
            manualPositions = manualPositions == null ? List.of() : manualPositions;
            derivatives = derivatives == null ? List.of() : derivatives;
            rejected = rejected == null ? List.of() : rejected;
        }

        /** v1.26 签名:没有期权等 */
        public Snapshot(List<Position> positions, List<Cash> cash, int skippedNonEquity,
                        List<ManualPosition> manualPositions) {
            this(positions, cash, skippedNonEquity, manualPositions, List.of(), List.of());
        }

        /** 最早的签名:只有股票和现金 */
        public Snapshot(List<Position> positions, List<Cash> cash, int skippedNonEquity) {
            this(positions, cash, skippedNonEquity, List.of(), List.of(), List.of());
        }
    }

    /**
     * 测试连接报告(富卡片呈现)· v0.15.x:让用户一眼看到连的是哪个户、开了什么市场、里面有什么。
     * 字段可空/可空集合(持仓数取不到时 positionCount=-1 表示未知)。
     */
    public record TestReport(String summary, String accountMasked, String accountType,
                             List<String> markets, int positionCount,
                             java.util.Map<String, BigDecimal> cashByCcy) {}
}
