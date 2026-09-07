package com.family.finance.calc.lens;

import java.math.BigDecimal;

/**
 * v1.1 · 资产透视的事实单元(头寸)· tech-design v1.1 决策 1。
 *
 * <p>手填账户 = 1 头寸(快照余额);持仓账户 = N 头寸(每个持仓一笔)。
 * 各维度字段存<b>最终展示中文标签</b>(null = 未分类),由 LensQueryService 组装时算好,
 * 引擎与注册表只做取值 + 分组,不再查库(纯函数可测)。</p>
 *
 * <p>账户级度量(latestPnl/cumPnl/netPrincipal)在同账户所有头寸上<b>重复携带</b>,
 * 引擎按 accountId 去重聚合;持仓级持有收益 holdingCumPnl = 市值 − 成本(现汇近似),
 * 手填账户该值 = 账户累计收益(整笔即账户本身)。见 {@link PivotEngine} 的归因规则。</p>
 */
public record Position(
        long accountId,
        Long holdingId,
        String label,
        String accountName,
        /** 本位币现值(必有 · LOAN 不进 lens) */
        BigDecimal valueBase,
        // ---- 维度标签(null = 未分类)----
        String type,
        String risk,
        String liquidity,
        String currency,
        String owner,
        String assetClass,
        String platform,
        String industry,
        String region,
        String purpose,
        /** v1.19 · 托管形式(派生:谁在替你做决定)· null = 未知 */
        String custody,
        // ---- 度量 ----
        BigDecimal acctLatestPnl,
        BigDecimal acctCumPnl,
        BigDecimal acctNetPrincipal,
        BigDecimal holdingCumPnl,
        /** 账户级期初市值(= 期末 − 较上期变化 momAmount)· 本期收益率分母;null = 不可算 */
        BigDecimal acctOpenValue,
        /**
         * v1.20 · 账户组维值 —— 已分组=组名,未分组=账户名。
         *
         * <p>透视里<b>账户本来就不是一个维度</b>(只有 10 个属性维度),所以这一版不是
         * 「把账户折叠成组」,而是<b>新增一个「账户组」维度</b> —— 那才是让用户在透视里
         * 按组切的正确形态。取值由 {@code AccountGroupingResolver} 统一给,
         * 零组态时它就是账户名,与 v1.19.16 逐字一致。</p>
         */
        String group
) {
    public boolean isHolding() { return holdingId != null; }
}
