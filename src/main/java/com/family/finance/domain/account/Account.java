package com.family.finance.domain.account;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    private Long id;
    private Long familyId;
    private Long templateId;
    private String displayName;
    private AccountType type;
    private String currency;
    private Long primaryOwnerMemberId;
    private Long defaultPaymentSourceAccountId;
    private Integer displayOrder;
    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** v0.2 · 产品类目 code(关联 product_category.code)· FR-40d */
    private String productCategoryCode;
    /** v0.2 · 用户覆盖类目默认风险等级(NULL = 沿用类目)· FR-40d */
    private Integer riskLevelOverride;
    /** v0.8 · 账户预期年化收益率 %(NULL = 回落品类 benchmark_pct)· 预实分析 FR-152 */
    private BigDecimal expectedReturnPct;

    /** v1.1 · 大类资产(AssetClass.name() · NULL=按类型派生默认)· 资产透视维度 */
    private String assetClass;
    /** v1.1 · 平台/机构标(自由文本 · 如 招商银行/支付宝/富途)· 资产透视维度 */
    private String platformTag;
    /** v1.1 · 行业/主题粗标(IndustryTag.name() · 账户级近似)· 资产透视维度 · NULL=未分类 */
    private String industryTag;
    /** v1.1 · 资金用途(PurposeTag.name() · 应急金/教育金/养老/购房/增值/日常)· 纯手标 · NULL=未分类 */
    private String purposeTag;

    /** v0.6 · 负债类型(MORTGAGE/CONSUMER/CREDIT_CARD/BORROW · 仅 LOAN · NULL=未填)· FR-103 */
    private String loanKind;
    /** v0.6 · 负债年利率 %(仅 LOAN · NULL=未填则资产负债表利率对照降级)· FR-103 */
    private BigDecimal annualRatePct;

    /**
     * v1.27 · 「不参与配置分析」(issue #23 · PRD FR-800)。
     *
     * <p>只影响「钱怎么分」的占比类分析(体检配置 / 风险卡、配置类规则、配置锚、AI 的配置结论);
     * 净资产、总资产、收益、流动性、报表、导出、目标里<b>一分不少</b>。不定格:改了之后所有账期立即按新值看。
     * 贷款类账户不可标(负债本来就不进配置分母)。</p>
     */
    private boolean analysisExcluded;

    public boolean isArchived() {
        return archivedAt != null;
    }

    public AccountClass getAccountClass() {
        return type == AccountType.LOAN ? AccountClass.LIABILITY : AccountClass.ASSET;
    }

    public AccountLiquidity getLiquidity() {
        if (type == null) {
            return AccountLiquidity.NA;
        }
        return switch (type) {
            case CASH -> AccountLiquidity.LIQUID;
            case WEALTH, STOCK, CRYPTO, METAL, INSURANCE, FUND -> AccountLiquidity.SEMI_LIQUID;   // v1.30 FUND:赎回 T+1~T+3,同理财
            case PROPERTY -> AccountLiquidity.ILLIQUID;
            case LOAN, OTHER -> AccountLiquidity.NA;
        };
    }

    /**
     * v0.3.3 · 精细化流动性 · 优先 product_category.liquidityClass,fallback {@link #getLiquidity()}。
     *
     * <p>例:WEALTH 账户 + product=MONEY_FUND → LIQUID(否则被误判 SEMI_LIQUID)。</p>
     */
    public AccountLiquidity getLiquidity(String pcLiquidityClass) {
        if (pcLiquidityClass != null && !pcLiquidityClass.isBlank()) {
            try {
                return AccountLiquidity.valueOf(pcLiquidityClass.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 数据脏 · 走兜底
            }
        }
        return getLiquidity();
    }
}
