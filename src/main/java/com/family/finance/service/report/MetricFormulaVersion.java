package com.family.finance.service.report;

/**
 * v1.10 · 封板指标的**口径版本号**。
 *
 * <p>报表页承诺「指标不会再二次变动」。v1.10 选的实现是**指标不落库、每次实时算**
 * (选型理由见 {@code tech-design/v1.10.md} §2),代价就是:我们**改代码**的时候,
 * 历史封板期的数字会跟着变。这不是 bug —— 口径修好了本来就该对全部历史生效
 * (v1.9.4 修财富水位就是靠这个特性一改全对)—— 但用户得能分辨
 * 「我上个月看到的那个数,和现在这个数,是不是同一套口径算的」。</p>
 *
 * <p>所以封板抬头上显示口径版本。**任何影响封板指标数值的口径改动都必须 +1**,
 * 并在下面的变更表里记一行。守护 {@code v110-FORMULA-VERSION}。</p>
 *
 * <p>它同时是 v1.11 的前置条件:等真要把指标落库(长瘦 KV 快照 + 双轨读)时,
 * 这个版本号就是快照行的 {@code formula_version},不用临时再造一个。</p>
 */
public final class MetricFormulaVersion {

    /**
     * 当前口径版本。
     *
     * <table>
     *   <tr><th>版本</th><th>时间</th><th>改了什么</th></tr>
     *   <tr><td>1</td><td>2026-08-11 · v1.10.0</td>
     *       <td>封板快照首版。含:归档账户过滤加时间语义(归档不再抹掉历史)、
     *           紧急储备的月均支出窗口固定为「asof 往前 N 期已关账」(不再随 range 变)</td></tr>
     *   <tr><td>2</td><td>2026-10-09 · v1.30.1</td>
     *       <td>「本期归因」扣掉账户间划转与补录本金(原来按余额变化排名,转出方成了「拉下来」的、
     *           转入的新账户整笔算成「资本纳入」);新账户的资本纳入改用与家庭开账基线同一个函数。
     *           <b>只有这一块的数字变</b>,其余封板指标逐字不变</td></tr>
     * </table>
     */
    public static final int CURRENT = 2;

    /**
     * 月均支出窗口长度(期)· 紧急储备月数的分母。
     *
     * <p>取 12 是**必须**的:{@code FactViewServiceImpl.kpis} 里是 {@code averageExpense(slice, 12)},
     * 而仪表盘默认切片就是 12 期 —— 报表页窗口若取别的长度,同名 KPI 两页就不一致
     * (违反 prd v1.10 FR-322 验收 1)。这个常量的作用是把窗口**固定**下来,不是改长度。</p>
     *
     * <p>固定成常量而不是跟着 {@code range} 走 —— 原来它在页面主切片上算,
     * 所以同一个封板期选 3M 和选 1Y 会看到不同的「紧急储备 N 月」
     * (tech-design §2.2 ②)。仪表盘与报表页共用这个常量,避免两页不一致。</p>
     */
    public static final int EXPENSE_WINDOW_PERIODS = 12;

    private MetricFormulaVersion() {
    }
}
