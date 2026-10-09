package com.family.finance.service.checkup;

import com.family.finance.domain.account.AccountType;

import java.util.function.Function;

/**
 * 家庭风险分布里,一个账户算几级风险。
 *
 * <h3>为什么有这个类(2026-09-24)</h3>
 *
 * <p>体检与报表的「风险等级分布」原来不看账户的产品类目,而是按账户类型写死一张表:
 * 股票 → 4、理财 / 房产 → 2、现金 → 1,其余一律落进 {@code default -> 0}「无风险」。
 * 于是 v0.14 之后加的<b>加密、贵金属、保险全成了「无风险」</b>;而且没有任何类型映射到 5 级以上,
 * {@code FAM-RISK-1}(高风险 ≥ 40%)<b>从来触发不了</b>。报表页那张图的副标题还写着
 * 「按产品类目风险等级聚合 · 数据口径与资产体检一致」—— 两边一致地错。</p>
 *
 * <p>那张写死的表用的是字符串 {@code switch} 加 {@code default},所以加枚举值时编译器一声不吭
 * (同型教训:加 METAL 那次连踩三处)。这里改成<b>对枚举的 switch 表达式,不留 default</b> ——
 * 以后再加账户类型,不在这里写一行就编译不过。</p>
 *
 * <h3>取数顺序</h3>
 * <ol>
 *   <li>账户上手工改过的风险等级(与账户页、透视同一个判据:{@code > 0} 才算改过)</li>
 *   <li>账户的产品类目的风险等级</li>
 *   <li>都没有 → 按账户类型的<b>默认类目</b>取它的风险等级。默认类目与 V11 给老账户回填的那张表一致,
 *       V11 之后才有的三个类型补上最贴近的类目。等级本身仍从类目表读,<b>这里不写任何等级数字</b></li>
 * </ol>
 *
 * <p>第 3 步是估算,所以单独记来源 —— 页面据此提示「有 N 个账户没设产品类目,按账户类型估算」。</p>
 */
public final class RiskLevels {

    private RiskLevels() {}

    public enum Source { OVERRIDE, CATEGORY, TYPE_DEFAULT, UNKNOWN }

    public record Resolved(int level, Source source) {
        public boolean estimated() { return source == Source.TYPE_DEFAULT || source == Source.UNKNOWN; }
    }

    /** 没选产品类目的账户,按类型用哪个类目估算。前六项与 {@code V11__step_v02_product_category.sql} 的回填一致。 */
    public static String defaultCategoryCode(AccountType type) {
        return switch (type) {
            case CASH -> "CASH_DEPOSIT";
            case STOCK -> "A_STOCK";
            case WEALTH -> "BANK_WEALTH";
            case FUND -> "MIXED_FUND";             // v1.30 · 与 ProductCategoryService 的默认类目一致
            case LOAN -> "LIABILITY";
            case PROPERTY -> "PROPERTY_RES";
            case OTHER -> "OTHER";
            case CRYPTO -> "CRYPTO";               // v0.14 起才有的类型,V11 没回填过
            case METAL -> "PRECIOUS_METAL";
            case INSURANCE -> "SAVINGS_INSURANCE";
        };
    }

    /**
     * @param override        账户上的 {@code risk_level_override}(可空)
     * @param categoryCode    账户的产品类目(可空)
     * @param type            账户类型
     * @param riskOfCategory  类目 → 风险等级(查不到返回 null)
     */
    public static Resolved resolve(Integer override, String categoryCode, AccountType type,
                                   Function<String, Integer> riskOfCategory) {
        if (override != null && override > 0) return new Resolved(override, Source.OVERRIDE);
        if (categoryCode != null && !categoryCode.isBlank()) {
            Integer lv = riskOfCategory.apply(categoryCode);
            if (lv != null) return new Resolved(lv, Source.CATEGORY);
        }
        if (type != null) {
            Integer lv = riskOfCategory.apply(defaultCategoryCode(type));
            if (lv != null) return new Resolved(lv, Source.TYPE_DEFAULT);
        }
        return new Resolved(0, Source.UNKNOWN);
    }
}
