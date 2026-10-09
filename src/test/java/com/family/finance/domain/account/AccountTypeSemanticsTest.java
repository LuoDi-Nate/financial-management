package com.family.finance.domain.account;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.18.5 · 账户类型的<b>语义分类</b>护栏 —— 加一个新类型时,逼着它被分类过。
 *
 * <h3>这条测试要拦的是什么</h3>
 * <p>不是「谓词算得对不对」,是<b>「加了新类型却没人去更新远处的判断」</b>。
 * 这个形状已经栽过两次,而且两次编译器都一声不吭:</p>
 * <ul>
 *   <li><b>v0.14 加 METAL</b> → 资产体检的「投资类账户」判据仍写着 STOCK/WEALTH/CRYPTO,
 *       <b>贵金属账户被三条投资类规则静默跳过</b>,直到 v1.18.5 复盘才发现。</li>
 *   <li><b>v1.4 放开 supportsHoldings</b> → 录入侧「余额变动要不要落现金行」仍写着
 *       {@code type == STOCK},生产上一笔 7.5w 被自动估值抹掉。</li>
 * </ul>
 *
 * <p>所以下面那条 {@link #每个类型都必须被显式分类过()} 是本文件的核心:它把
 * 「分类」从口头约定变成<b>加类型时必然会红的测试</b>。要加新类型,就得回来这里表态。</p>
 */
class AccountTypeSemanticsTest {

    /**
     * <b>既不是负债、也不是投资</b>的类型 —— 必须在这里显式列出来。
     *
     * <p>加了新类型却没分类时,下面那条测试会红在这里,逼你回答:
     * 它是负债吗?是投资吗?都不是的话,为什么?(把理由写进 AccountType 的 javadoc。)</p>
     */
    private static final Set<AccountType> NEITHER = EnumSet.of(
            AccountType.CASH,        // 现金:不涨不跌,余额就是余额
            AccountType.PROPERTY,    // 房产:存量登记,估值靠手填,谈年化/回撤没有意义
            AccountType.INSURANCE,   // 保险:现金价值按合同走,不是市场标的
            AccountType.OTHER        // 其他:兜底类型,语义上什么都不承诺
    );

    @Test
    void 每个类型都必须被显式分类过() {
        for (AccountType t : AccountType.values()) {
            boolean classified = t.isLiability() || t.isInvestment() || NEITHER.contains(t);
            assertThat(classified)
                    .as("新增账户类型 %s 还没有被分类 —— 它是负债?是投资?都不是的话把它加进 NEITHER "
                        + "并在 AccountType 的 javadoc 里写明理由。"
                        + "(v0.14 加 METAL 时正是漏了这一步,贵金属账户被体检规则静默跳过)", t)
                    .isTrue();
        }
    }

    /** 三类互斥:一个类型不可能既是负债又是投资 —— 那说明分类本身出了问题。 */
    @Test
    void 负债与投资互斥() {
        for (AccountType t : AccountType.values()) {
            assertThat(t.isLiability() && t.isInvestment()).as(t + " 同时是负债和投资?").isFalse();
            assertThat(NEITHER.contains(t) && (t.isLiability() || t.isInvestment()))
                    .as(t + " 既在 NEITHER 里、又被判成负债/投资").isFalse();
        }
    }

    /** METAL 必须算投资 —— 这一条是为那次真实遗漏立的碑,不许再掉出去。 */
    @Test
    void 贵金属算投资_这是v0_14漏掉的那一格() {
        assertThat(AccountType.METAL.isInvestment())
                .as("贵金属会涨会跌、有持有期与收益率可谈,体检的投资类规则必须覆盖它").isTrue();
        assertThat(AccountType.STOCK.isInvestment()).isTrue();
        assertThat(AccountType.WEALTH.isInvestment()).isTrue();
        assertThat(AccountType.CRYPTO.isInvestment()).isTrue();
    }

    /**
     * v1.30 · 基金账户(第三次加账户类型)。逐个判断点的取值见 tech-design/v1.30.md 选型十;
     * 这里钉住「远处的判断都认它」—— 漏一处不报错,只是基金账户在那一块静默消失。
     */
    @Test
    void 基金账户_每个判断点都显式归过类() {
        AccountType f = AccountType.FUND;
        assertThat(f.isInvestment()).as("基金会涨会跌,体检的投资类规则必须覆盖它").isTrue();
        assertThat(f.isLiability()).isFalse();
        assertThat(f.expectsFlowsToExplainBalance()).as("余额变动多是净值涨跌,提示未解释差额只会天天误报").isFalse();
        assertThat(com.family.finance.service.stock.StockHoldingService.supportsHoldings(f))
                .as("基金账户就是为挂基金持仓加的").isTrue();
        assertThat(com.family.finance.domain.lens.AssetClass.defaultFor(f, null))
                .as("默认类目混合型基金 → 股票股权(不能落成 null 被透视漏掉)")
                .isEqualTo(com.family.finance.domain.lens.AssetClass.EQUITY);
        assertThat(com.family.finance.domain.lens.AssetClass.defaultFor(f, "MONEY_FUND"))
                .isEqualTo(com.family.finance.domain.lens.AssetClass.CASH_EQ);
        assertThat(com.family.finance.domain.lens.AssetClass.defaultFor(f, "SHORT_BOND"))
                .isEqualTo(com.family.finance.domain.lens.AssetClass.FIXED_INCOME);
        assertThat(com.family.finance.calc.BenchmarkAggregator.benchmarkForAccount(null, null, "FUND"))
                .as("没设类目时的兜底基准不能是 0").isEqualByComparingTo("6.00");
        assertThat(f.holdsOtcFunds()).isTrue();
    }

    /** v1.30 PRD §3.1:能放场外基金的只有这四类(币种另判);逐个类型表过态 */
    @Test
    void 能放场外基金的只有基金证券理财现金() {
        var yes = java.util.EnumSet.of(AccountType.FUND, AccountType.STOCK, AccountType.WEALTH, AccountType.CASH);
        for (AccountType t : AccountType.values()) {
            assertThat(t.holdsOtcFunds()).as(t + " 能不能放场外基金").isEqualTo(yes.contains(t));
        }
    }

    @Test
    void 只有贷款是负债() {
        assertThat(AccountType.LOAN.isLiability()).isTrue();
        for (AccountType t : AccountType.values()) {
            if (t != AccountType.LOAN) {
                assertThat(t.isLiability()).as(t + " 不该被判成负债").isFalse();
            }
        }
    }

    /**
     * 「余额变化该被流水解释」只对现金与负债成立。
     *
     * <p>范围<b>刻意窄</b>:房产升值、保险现金价值增长、投资涨跌本来就"无法解释",
     * 对它们提示只会天天误报 —— 然后被人忽略,连真的异常也一起看不见。
     * (v1.18.2 复盘里那条「误报会让告警被关掉,等于没做」是同一个道理。)</p>
     */
    @Test
    void 余额该被流水解释的只有现金与负债() {
        assertThat(AccountType.CASH.expectsFlowsToExplainBalance()).isTrue();
        assertThat(AccountType.LOAN.expectsFlowsToExplainBalance()).isTrue();
        for (AccountType t : AccountType.values()) {
            if (t != AccountType.CASH && t != AccountType.LOAN) {
                assertThat(t.expectsFlowsToExplainBalance())
                        .as(t + " 的余额变化本来就未必有流水对应,提示它等于制造噪音").isFalse();
            }
        }
    }
}
