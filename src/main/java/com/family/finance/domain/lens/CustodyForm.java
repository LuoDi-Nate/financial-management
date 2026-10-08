package com.family.finance.domain.lens;

import com.family.finance.domain.account.AccountType;

/**
 * v1.19 · 托管形式 —— 「这笔钱由谁替你做决定」。
 *
 * <h3>为什么是派生视图而不是第 11 个可打标维度</h3>
 * <p>做成可打标维度意味着用户要<b>逐个账户打标</b> —— 直接违背「每月 10 分钟」的硬约束。
 * 而这四类<b>完全能由已有字段推导</b>:账户类型 + 是不是现金行 + 有没有穿透结果。
 * 所以它是运行期派生的,<b>不落库、不回填、零新增录入</b>。</p>
 *
 * <h3>它和「资产分布」问的不是同一件事</h3>
 * <p>大类/风险/行业回答「钱是什么」,平台/主理人回答「钱在谁那儿」;
 * 托管形式回答的是<b>「谁在替你做决定,数字由谁产生」</b> ——
 * 自己盯的股票和交给基金经理的钱,即使同属「股票」大类,决策权也完全不同。</p>
 */
public enum CustodyForm {

    /** 自己盯:标的是你自己建的,买卖你自己定 */
    SELF("自己盯"),
    /** 交给产品:基金 / 理财 —— 有穿透结果的走穿透后成分 */
    DELEGATED("交给产品"),
    /** 不动:存量登记,估值靠手填 */
    PARKED("不动"),
    /** 随时可取:现金与货基类 */
    LIQUID("随时可取");

    private final String label;

    CustodyForm(String label) { this.label = label; }

    public String getLabel() { return label; }

    /**
     * 判定一笔头寸的托管形式。
     *
     * @param type        账户类型
     * @param cashRow     是不是账户里的现金行(券商/交易账户里的现金)
     * @param liquidTag   该账户/持仓是否被标为流动性高(货基等)
     */
    public static CustodyForm of(AccountType type, boolean cashRow, boolean liquidTag) {
        if (type == null) return PARKED;
        // 现金行无论挂在哪种账户下,性质都是「随时可取」
        if (cashRow) return LIQUID;
        return switch (type) {
            case CASH -> LIQUID;
            case STOCK, CRYPTO, METAL -> SELF;
            case WEALTH, FUND -> liquidTag ? LIQUID : DELEGATED;   // 货基算随时可取,其余算交给产品(v1.30 基金账户同理财)
            case PROPERTY, INSURANCE -> PARKED;
            case OTHER -> PARKED;                            // 兜底类型:语义上什么都不承诺
            case LOAN -> PARKED;                             // 负债本就不进资产透视,这里只为穷尽
        };
    }

    /** 给透视引擎用的标签(null 安全) */
    public static String labelOf(AccountType type, boolean cashRow, boolean liquidTag) {
        return of(type, cashRow, liquidTag).getLabel();
    }
}
