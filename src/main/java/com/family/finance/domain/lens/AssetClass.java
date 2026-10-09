package com.family.finance.domain.lens;

import com.family.finance.domain.account.AccountType;

/**
 * v1.1 · 大类资产维度(资产透视)· prd/v1.1 FR-1。
 *
 * <p>与 {@code loanKind}/{@code InsuranceSubType} 同构:Java 枚举给下拉 + 中文 label,
 * 持久化存 {@link #name()} 到 {@code account.asset_class}(DB 不加 CHECK,加值免迁移)。
 * NULL = 按 {@link #defaultFor} 派生默认;LOAN 是负债不进资产大类(lens 不计入分母)。</p>
 */
public enum AssetClass {
    CASH_EQ("现金活钱", "xian jin huo qian"),
    FIXED_INCOME("债券理财", "zhai quan li cai"),
    EQUITY("股票股权", "gu piao gu quan"),
    ALTERNATIVE("黄金加密", "huang jin jia mi"),
    REAL_ESTATE("房产", "fang chan"),
    INSURANCE("保险", "bao xian");

    private final String label;
    private final String pinyin;   // 全拼(空格分节)· 自研下拉搜索用

    AssetClass(String label, String pinyin) { this.label = label; this.pinyin = pinyin; }

    public String getLabel() { return label; }

    public String getPinyin() { return pinyin; }

    /** 安全解析 · 非法/空返回 null(脏值不抛) */
    public static AssetClass fromName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** name → 中文 label · 模板/JSON 用,避免裸露 code */
    public static String labelOf(String name) {
        AssetClass c = fromName(name);
        return c == null ? "" : c.getLabel();
    }

    /**
     * 按账户类型(+ 产品类目)派生默认大类 · 用户未打标时生效,可被 account.asset_class 覆盖。
     * WEALTH 细分:货币基金 → 现金及等价;其余理财默认固收(银行理财主流形态)。
     * LOAN / 未知 → null(负债不进大类;OTHER 落「未分类」,不装懂)。
     */
    public static AssetClass defaultFor(AccountType type, String productCategoryCode) {
        if (type == null) return null;
        return switch (type) {
            case CASH -> CASH_EQ;
            case STOCK -> EQUITY;
            case CRYPTO, METAL -> ALTERNATIVE;
            case PROPERTY -> REAL_ESTATE;
            case INSURANCE -> INSURANCE;
            case WEALTH -> "MONEY_FUND".equals(productCategoryCode) ? CASH_EQ : FIXED_INCOME;
            // v1.30 · 基金账户按产品类目:货基 → 现金;债基 → 债券;其余(混合 / 股票类基金,默认 MIXED_FUND)→ 股票股权。
            //   账户级只是兜底 —— 有持仓时由穿透给出真实的股 / 债 / 现金分布(tech-design/v1.30.md 选型十)
            case FUND -> switch (productCategoryCode == null ? "" : productCategoryCode) {
                case "MONEY_FUND" -> CASH_EQ;
                case "SHORT_BOND", "LONG_BOND" -> FIXED_INCOME;
                case "GOLD" -> ALTERNATIVE;
                default -> EQUITY;
            };
            case LOAN, OTHER -> null;
        };
    }
}
