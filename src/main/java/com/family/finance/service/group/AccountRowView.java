package com.family.finance.service.group;

import com.family.finance.domain.account.AccountType;
import com.family.finance.factview.AccountPerformance;

import java.math.BigDecimal;
import java.util.List;

/**
 * v1.20 FR-483 · 账户列表的一行 —— 可能是<b>一个账户</b>,也可能是<b>一个组</b>。
 *
 * <h3>为什么不直接复用 {@link AccountPerformance}</h3>
 *
 * <p>因为组行有两件账户行没有的事:<b>它可以展开</b>(members),以及
 * <b>它的一半指标必须是 null</b>。后者不是偷懒,是这一版最重要的口径判断,详见下面。</p>
 *
 * <p>字段名<b>刻意与 {@code AccountPerformance} 保持一致</b>({@code accountName / xirr / cumPnl}…),
 * 这样 Thymeleaf 模板里既有的 {@code ${account.xxx}} 表达式一个都不用改 ——
 * 模板只在「账户名」那一格多问一句 {@code grouped}。</p>
 *
 * <h3>组行的指标:能合的合,不能合的给 null</h3>
 *
 * <table>
 *   <tr><th>可加</th><td>currentValue · cumPnl · netPrincipal · latestPnl · momAmount · sharePct
 *       —— 都是同一本位币下的金额</td></tr>
 *   <tr><th>可派生</th><td>momPct = 合并Δ ÷ 合并后的上期余额</td></tr>
 *   <tr><th>可重算</th><td>sparkline 按账期对齐相加,再走<b>同一个</b>{@code Sparkline} 归一化</td></tr>
 *   <tr><th>取最大</th><td>monthsHeld —— 组从最早那个成员算起</td></tr>
 *   <tr><th><b>null</b></th><td><b>xirr · returnBase · maxDrawdownPct · expectedReturnPct ·
 *       planActualDiffPct</b></td></tr>
 * </table>
 *
 * <h3>为什么宁可给「—」也不给一个近似值</h3>
 *
 * <ul>
 *   <li><b>XIRR 是资金加权年化</b>,不能相加,按市值加权平均也<b>没有数学意义</b>。
 *       正确算法是把组内所有现金流合并后重跑 XIRR,而那需要 {@code AccountPeriodFact} 级的流水,
 *       不在这一层。硬凑一个数会得到「看起来合理、实际是错的」—— 最坏的一种错。</li>
 *   <li><b>不能拿「累计损益 ÷ 累计净投入」顶替</b>:那是<b>累计收益率</b>,
 *       与 XIRR 的<b>年化资金加权</b>不是一个口径。填进「收益率」列 = 口径悄悄换了而页面一个字不说 ——
 *       正是 {@code feedback_metric_refactor_baseline} 那条教训的形状。</li>
 *   <li><b>最大回撤</b>数学上能在合并时序上重算,但成员<b>开户 / 关户</b>会在合并曲线上造出跳变,
 *       回撤算法会把它当波动,得到一个<b>凭空出现的回撤</b>。</li>
 * </ul>
 *
 * <p>代价是真实的:组内各账户的收益率在列表里看不到了。所以 {@code members} 必须能展开 ——
 * 展开后是成员账户<b>原样</b>的行,指标齐全、账户名仍可点进详情。</p>
 */
public record AccountRowView(
        Long accountId,               // 组行 = null(组不是账户,点不进详情)
        String accountName,           // 组行 = 组名
        AccountType accountType,      // 组行:成员同型则该型,混合则 null
        String accountCurrency,       // 组行:成员同币则该币,混合则 null
        BigDecimal currentValue,
        BigDecimal xirr,              // 组行 = null
        BigDecimal cumPnl,
        BigDecimal netPrincipal,
        BigDecimal latestPnl,
        BigDecimal momAmount,
        BigDecimal momPct,
        BigDecimal sharePct,
        BigDecimal maxDrawdownPct,    // 组行 = null
        Integer monthsHeld,
        String sparklinePoints,
        String sparklineTrend,
        BigDecimal returnBase,        // 组行 = null
        BigDecimal expectedReturnPct, // 组行 = null
        BigDecimal planActualDiffPct, // 组行 = null
        boolean grouped,
        Long groupId,
        List<AccountPerformance> members,  // 组行的成员(展开用);账户行为空表
        Long memberOf                      // 这一行是某个组的成员(展开后才显示);顶层行为 null
) {

    /** 未分组账户:原样搬过来,<b>一个字段都不加工</b> —— 零组态逐字一致靠的就是这里什么都不做。 */
    public static AccountRowView of(AccountPerformance p) {
        return new AccountRowView(
                p.accountId(), p.accountName(), p.accountType(), p.accountCurrency(),
                p.currentValue(), p.xirr(), p.cumPnl(), p.netPrincipal(), p.latestPnl(),
                p.momAmount(), p.momPct(), p.sharePct(), p.maxDrawdownPct(), p.monthsHeld(),
                p.sparklinePoints(), p.sparklineTrend(), p.returnBase(),
                p.expectedReturnPct(), p.planActualDiffPct(),
                false, null, List.of(), null);
    }

    /** 同上,但标记成「某个组的成员行」—— 扁平列表里跟在组行后面,默认隐藏。 */
    public static AccountRowView memberOf(AccountPerformance p, Long groupId) {
        AccountRowView v = of(p);
        return new AccountRowView(
                v.accountId(), v.accountName(), v.accountType(), v.accountCurrency(),
                v.currentValue(), v.xirr(), v.cumPnl(), v.netPrincipal(), v.latestPnl(),
                v.momAmount(), v.momPct(), v.sharePct(), v.maxDrawdownPct(), v.monthsHeld(),
                v.sparklinePoints(), v.sparklineTrend(), v.returnBase(),
                v.expectedReturnPct(), v.planActualDiffPct(),
                false, null, List.of(), groupId);
    }

    /** 组行有几个成员 —— 模板上显示「日常周转 · 5 个账户」 */
    public int memberCount() {
        return members == null ? 0 : members.size();
    }
}
