package com.family.finance.domain.account;

/**
 * 账户类型。
 *
 * <h3>v1.18.5 · 为什么把「是不是负债 / 是不是投资 / 余额该不该被流水解释」做成枚举上的具名谓词</h3>
 *
 * <p>此前这些判断散在各处,写成裸的 {@code getType() == AccountType.XXX}。问题不是不好看,
 * 是<b>加一个新类型时,远处那些判断不会跟着变,而编译器一句话都不说</b>。已经栽过两次:</p>
 * <ul>
 *   <li><b>v1.4</b> 放开 {@code supportsHoldings} 让 WEALTH/CRYPTO/METAL 也能挂持仓,
 *       但录入侧「余额变动要不要落现金行」还写着 {@code type == STOCK} ——
 *       生产上一笔 7.5w 划转被自动估值抹掉(v1.18.1 才修)。</li>
 *   <li><b>v0.14</b> 加了 METAL,而资产体检的「投资类账户」判据写着 STOCK/WEALTH/CRYPTO ——
 *       <b>贵金属账户被三条投资类体检规则静默跳过</b>,一直到 v1.18.5 复盘时才发现。</li>
 * </ul>
 *
 * <p>做成具名谓词之后,加类型时要么显式落进某一类、要么显式落在外面 ——
 * 而且有一条<b>结构性单测</b>遍历所有枚举值,逼着新类型必须被分类过
 * (见 {@code AccountTypeSemanticsTest})。谓词不是万能药,但它把
 * 「散落在 10 个文件里的隐式约定」变成了「一处可读、可测的定义」。</p>
 */
public enum AccountType {
    STOCK("股票"),
    CASH("现金"),
    WEALTH("理财"),
    /**
     * v1.30 · 基金账户(issue #25 · 维护者 2026-10-08 裁定)。支付宝 / 天天基金 / 银行 App 里那一揽子场外基金。
     * 以前只能放进「理财」,而理财的默认分类按银行理财算(资产类别 = 债券理财、类目 R2、基准 3%)——
     * 偏股基金账户整个被当成固收。默认分类见 tech-design/v1.30.md 选型十。
     *
     * <p><b>回滚注意</b>:老 jar 的这个枚举里没有 FUND,读到就抛异常。回滚到 v1.30 之前先跑
     * {@code db/rollback/v1.30.sql}(deploy/rollback.sh 会按版本自动执行)。</p>
     */
    FUND("基金"),
    CRYPTO("加密"),
    METAL("贵金属"),
    PROPERTY("房产"),
    LOAN("贷款"),
    OTHER("其他"),
    INSURANCE("保险");

    private final String label;

    AccountType(String label) { this.label = label; }

    public String getLabel() { return label; }

    /**
     * 负债类:余额语义是「欠多少」,进净资产要取负、不进资产透视、有还款/利率这些概念。
     *
     * <p>今天只有 LOAN。写成谓词是因为将来若再加一种负债(比如把信用卡独立成类型),
     * 那些 {@code == LOAN} 的地方本该<b>全部</b>跟着变 —— 而它们散在
     * 净资产汇总、透视、填报、洞察里,靠人记必漏。</p>
     */
    public boolean isLiability() {
        return this == LOAN;
    }

    /**
     * 投资类:会涨会跌、有持有期与收益率可谈,资产体检的投资类规则对它生效。
     *
     * <p><b>METAL 曾经漏在这里</b>(v0.14 加的类型,判据没跟上)—— 贵金属账户因此
     * 被「持有期 / 收益 / 回撤」三条体检规则静默跳过。这正是本谓词存在的理由。</p>
     *
     * <p>PROPERTY 不算:房产在本项目里是<b>存量登记</b>,不做收益率与回撤(它的估值来自
     * 用户手填,谈年化没有意义)。INSURANCE 同理(现金价值按合同走)。</p>
     */
    public boolean isInvestment() {
        return this == STOCK || this == WEALTH || this == CRYPTO || this == METAL || this == FUND;
    }

    /**
     * 余额变化<b>应当能被流水解释</b>的账户 —— 填报页对它们的「未解释差额」给提示。
     *
     * <p>只有现金与负债成立:现金账户的每一分变动都该有收入/支出/划转对应,
     * 贷款的变动该有还款对应。而<b>房产升值、保险现金价值增长、投资涨跌本来就"无法解释"</b>,
     * 对它们提示只会变成天天误报,然后被人忽略 —— 那样连真的异常也一起看不见了。</p>
     */
    public boolean expectsFlowsToExplainBalance() {
        return this == CASH || this == LOAN;
    }

    /**
     * v1.30 · 这类账户里能不能放场外基金 / 货币基金(PRD v1.30 §3.1;币种另判,只认人民币)。
     *
     * <p>写成穷尽 switch:再加类型时编译器会逼着表态,而不是悄悄落进「不能」。</p>
     */
    public boolean holdsOtcFunds() {
        return switch (this) {
            case FUND, STOCK, WEALTH, CASH -> true;   // 基金平台 / 券商场外 / 银行理财 / 余额宝那类现金
            case CRYPTO, METAL, PROPERTY, LOAN, INSURANCE, OTHER -> false;
        };
    }
}
