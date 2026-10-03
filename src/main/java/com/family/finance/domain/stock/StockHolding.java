package com.family.finance.domain.stock;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票账户持仓明细 · v0.3 FR-52。
 *
 * <p>持仓级 AUTO/MANUAL 混合模式 · 见 {@link ValuationMode}。
 * AUTO 模式要求 ticker / market / shares / currency 非空;
 * MANUAL 模式要求 manualValue 非空。Service 层做约束校验
 * (MySQL CHECK 跨厂商性差 · 不在 schema 层强制)。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockHolding {
    private Long id;
    private Long accountId;
    private String displayName;
    private ValuationMode valuationMode;

    // AUTO 字段
    private String ticker;
    private Market market;
    private BigDecimal shares;
    private BigDecimal costBasis;
    private String currency;

    /** v0.14 · METAL 持仓计价单位:GRAM / OUNCE(其余类型为 null)· 见 {@link com.family.finance.service.stock.MetalUnit} */
    private String unit;

    /** v0.15 · 券商同步来源:FUTU / TIGER;手填持仓为 null。reconcile 只动带此标记的行,不碰手填。 */
    private String syncSource;

    /** v1.1 · 个股行业标(IndustryTag.name() · 准)· 资产透视行业维度 · NULL=未分类 */
    private String industryTag;

    /** v1.4 · 持仓级资产类型标(AssetClass.name)· 截图导入基金逐支打标 · NULL=回落账户级 */
    private String assetClassTag;
    /** v1.4 · 持仓级风险档 · NULL=回落账户级 */
    private String riskTag;
    /** v1.4 · 持仓级流动性档 · NULL=回落账户级 */
    private String liquidityTag;

    // MANUAL 字段
    private BigDecimal manualValue;
    private LocalDateTime manualValueAt;

    /** v0.5 FR-78/79 · 是否由账户现金划转买入(归档时对称按市价把现金加回) */
    private Boolean cashLinked;

    // 通用
    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** v1.5 · 穿透键:匹配到的公募基金代码(可空) */
    private String fundCode;
    /** v1.5 · PENDING/RESOLVED/MANUAL/UNPENETRATED */
    private String penetrateState;

    // v1.29 · 券商同步来的期权 / 期货 / 债券(都是 MANUAL 行:单价 × 张数,卖出张数为负)。
    // 估值不读下面这些列 —— 它们只用来把这一行写成人话、标到期;NULL = 股票 / 基金 / 现金。
    /** {@link InstrumentKind} 的 name();NULL = 普通持仓 */
    private String instrumentKind;
    private String underlying;
    /** C 看涨 / P 看跌 */
    private String putCall;
    private BigDecimal strike;
    private java.time.LocalDate expiry;
    private BigDecimal multiplier;
    /** 券商给的标记价(原币 · 每股 / 每单位);原币记在 {@link #currency} */
    private BigDecimal quotePrice;
    /** 期货名义价值(原币)· 只展示,不计入余额 */
    private BigDecimal notional;

    /** 是不是券商同步来的期权 / 期货 / 债券这一类 */
    public boolean isDerivative() {
        return instrumentKind != null && !instrumentKind.isBlank();
    }
}
