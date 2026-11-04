package com.family.finance.service.expense;

import com.family.finance.domain.family.ExpenseEntryMode;
import com.family.finance.repository.CashFlowMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMemberCashflowMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.8 · 家庭支出的**唯一口径入口**。
 *
 * <h3>为什么必须只有一个入口</h3>
 * v1.6.29 踩过一次:同一屏上「家庭 XIRR」只读逐账户流水、「人赚」走成员手填收支,
 * 两个 KPI 互相矛盾,用户来报障。根因就是**口径判断散落在各调用点**。
 * 所以本版把支出口径收敛到这里,10 个调用方都必须经过它,守护 {@code v18-EXPENSE-ONE-SOURCE}
 * 断言那些文件里不再直接出现 {@code totalExpense()}。
 *
 * <h3>优先级 —— <b>必须受 expense_entry_mode 约束</b></h3>
 * <pre>
 * ITEMIZED 模式:逐笔 &gt; 0 → 取逐笔;否则 PMC &gt; 0 → 取总额;否则 NONE
 * TOTAL    模式:PMC &gt; 0  → 取总额;否则 NONE(**不用逐笔兜底** · 由调用方回落原路径)
 * </pre>
 * <b>两者永不相加</b>。判据用「合计 &gt; 0」而不是「有行」——
 * 一行金额为 0 的逐笔不应该压掉用户手填的总额。
 *
 * <h3>⚠ 为什么优先级不能无条件「逐笔优先」(开发中被拦下的 bug)</h3>
 * PRD FR-273 初稿写的是无条件逐笔优先。第 3 步做完跑「总额模式逐位比对」时被拦下:
 * <ul>
 *   <li>beta:紧急储备 8.0 月 → 1.9 月(月均支出凭空涨 4 倍)</li>
 *   <li>prod 复核:2026-06 的 PMC 总额 ¥32,797,而该期逐笔只有 ¥3,000
 *       —— 无条件逐笔优先会让当月支出变成 3,000,<b>少算 89%</b>,
 *       连带污染储蓄率 / 月均支出 / 紧急储备 / 人赚钱赚 / XIRR / 应急目标基线。</li>
 * </ul>
 * 根因:{@code cash_flow} 里本来就有 EXPENSE 行 —— 它们来自**账户级流水**那条老路径,
 * 而不是「用户主动选择逐笔录入」。把它们当成"用户的逐笔支出"是误读。
 * 所以逐笔优先只在家庭<b>显式切到 ITEMIZED</b> 后才生效;TOTAL 模式下行为与 v1.8 之前逐位相同。
 *
 * <p>连带影响一:TOTAL 模式下**取期集合也必须与旧行为一致**(只看 PMC 有记录的期),
 * 不能并上「只有逐笔的期」—— 否则月均支出的分母变了,即使每期取值都对,均值也会不同。</p>
 *
 * <p>连带影响二:**PMC 的取数来源也不能随手换**。按期取走 {@code findFamilyAggregateForPeriod(s)}
 * (原 netInflowExpense 用的),近 N 期走 {@code findFamilyAggregateRecent}(原 averageExpense 用的)。
 * 两者并不等价 —— 后者带 {@code HAVING filledMembers > 0} 和 {@code LIMIT}。
 * 开发中一度把单期也改走 Recent,4 条既有单测立刻挂掉。
 * v1.12 FR-352 把按期取数从逐期点查换成 {@code findFamilyAggregateForPeriods} 的一条 IN 批量 ——
 * 那条 SQL 的过滤条件与点查逐字相同,换的是**条数不是口径**。</p>
 *
 * <h3>⚠ 与收入侧优先级相反</h3>
 * 收入侧是「PMC 手填 &gt; 0 则用之,否则用 cash_flow 汇总」,
 * 支出侧是「逐笔 &gt; 0 则用之,否则用 PMC」。
 * <b>方向是反的</b>,这是历史造成的:收入的历史事实存在 PMC 里,而支出的更细事实存在逐笔里。
 * 这条反直觉的不一致必须同时出现在四处:本类注释、{@code netInflowIncome/Expense} 的方法注释、
 * 页面 tooltip、{@code docs/how-to-record.md}。少写一处,下一个人就会靠猜。
 *
 * <h3>口径 A vs 口径 B</h3>
 * 本类返回的是**口径 A · 家庭支出**(排除 {@code is_adjustment=1} 的现金调整)。
 * 账户级 PnL / NAV / 回撤用的是**口径 B · 账户外部流出**(含调整,见 {@code FactMapper.queryBase}
 * 的 {@code expense_orig}),那条一行不动。两者不可互换。
 *
 * <p>本服务**只依赖 mapper、不依赖任何 service** —— 这样 FactViewServiceImpl / GoalService /
 * HouseholdCashflowService / CheckupController 都能安全注入而不会成环。</p>
 *
 * <p>返回值一律**本位币**。视图币种由调用方乘 baseToViewFactor(与 PMC 现行做法一致),
 * 不在本服务里掺币种逻辑。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseLedgerService {

    private final CashFlowMapper cashFlowMapper;
    private final PeriodMemberCashflowMapper pmcMapper;
    /** v1.8 · 读家庭的 expense_entry_mode —— 优先级必须受它约束,见类注释「为什么优先级不能无条件」 */
    private final FamilyMapper familyMapper;
    /** v1.8 · 只用来取「进行中账期」,判定近 N 期的上边界 —— 见 latestRecordableStart */
    private final com.family.finance.repository.PeriodMapper periodMapper;
    /** 只在新的退休参考视图使用;性质仍经全站唯一判据,无反向依赖。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExpenseNatureService retirementExpenseNature;

    /**
     * 一期支出的口径结果。source 让页面能说清「用的是哪一种」,itemCount 供文案显示「逐笔 · N 笔」。
     *
     * <p>{@code periodStart} 只有 {@link #recent} 会填(它本来就握着日期);
     * {@link #byPeriod}/{@link #byPeriods} 是按 id 点查,不额外为了日期再查一次表 —— 那里是 null。
     * 需要按时间排序/打标签的调用方请用 {@code recent}。</p>
     */
    public record PeriodExpense(Long periodId, java.time.LocalDate periodStart,
                                BigDecimal amountBase, Source source, int itemCount) {
        public enum Source { ITEMIZED, TOTAL, NONE }

        public boolean filled() { return source != Source.NONE; }

        public static PeriodExpense none(Long periodId) {
            return new PeriodExpense(periodId, null, BigDecimal.ZERO, Source.NONE, 0);
        }

        PeriodExpense withStart(java.time.LocalDate start) {
            return new PeriodExpense(periodId, start, amountBase, source, itemCount);
        }
    }

    /** 该家庭当前的支出录入方式(脏值/缺失兜底 TOTAL)。 */
    public ExpenseEntryMode modeOf(long familyId) {
        return familyMapper.findById(familyId)
                .map(f -> ExpenseEntryMode.fromCode(f.getExpenseEntryMode()))
                .orElse(ExpenseEntryMode.TOTAL);
    }

    /** 批量取,避免 N+1。返回 periodId → 口径结果;传入的每个 periodId 都会有一项(可能是 NONE)。 */
    public Map<Long, PeriodExpense> byPeriods(long familyId, Collection<Long> periodIds) {
        Map<Long, PeriodExpense> out = new LinkedHashMap<>();
        if (periodIds == null || periodIds.isEmpty()) return out;
        boolean itemizedFirst = modeOf(familyId) == ExpenseEntryMode.ITEMIZED;

        Map<Long, CashFlowMapper.RealExpenseSum> itemized = new HashMap<>();
        for (CashFlowMapper.RealExpenseSum r : cashFlowMapper.sumRealExpenseByPeriod(familyId, periodIds)) {
            if (r.periodId() != null) itemized.put(r.periodId(), r);
        }
        // v1.12 FR-352 · PMC 从「逐期点查」改成一条 IN 批量。取数口径不变 ——
        // findFamilyAggregateForPeriods 与 findFamilyAggregateForPeriod 的过滤条件逐字相同
        // (仍然**不是** findFamilyAggregateRecent:那条带 HAVING filledMembers > 0,是另一个口径)。
        // 唯一的差别:没有手填行的期在批量结果里**不出现**,而点查会返回一行 NULL 合计。
        // 下面 totals.get(pid) 对这两种情况都得到 null,decide() 里等价于「没有总额」。
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (var a : pmcMapper.findFamilyAggregateForPeriods(familyId, periodIds)) {
            if (a.periodId() != null) totals.put(a.periodId(), a.totalExpense());
        }
        for (Long pid : periodIds) {
            out.put(pid, decide(pid, itemized.get(pid), totals.get(pid), itemizedFirst));
        }
        return out;
    }

    /** 已经握着 PMC 聚合结果时用这个,避免再点查一遍。 */
    private Map<Long, PeriodExpense> decideWith(long familyId, List<Long> periodIds,
                                                Map<Long, BigDecimal> totals, boolean itemizedFirst) {
        Map<Long, CashFlowMapper.RealExpenseSum> itemized = new HashMap<>();
        for (var r : cashFlowMapper.sumRealExpenseByPeriod(familyId, periodIds)) {
            if (r.periodId() != null) itemized.put(r.periodId(), r);
        }
        Map<Long, PeriodExpense> out = new LinkedHashMap<>();
        for (Long pid : periodIds) {
            out.put(pid, decide(pid, itemized.get(pid), totals.get(pid), itemizedFirst));
        }
        return out;
    }

    public PeriodExpense byPeriod(long familyId, long periodId) {
        return byPeriods(familyId, List.of(periodId)).getOrDefault(periodId, PeriodExpense.none(periodId));
    }

    /** 退休预算只是在既有支出来源上筛选消费,不改变任何全家庭指标。 */
    public record RetirementBudget(BigDecimal amount, BigDecimal removed, String source, boolean totalOnly) { }

    public Map<Long, RetirementBudget> retirementBudget(long familyId, Collection<Long> ids,
                                                       java.util.Set<Long> excludedCategories, boolean excludeOneOff,
                                                       java.util.Set<Long> explicitTotals) {
        Map<Long, RetirementBudget> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        var ledger = byPeriods(familyId, ids);
        var grouped = cashFlowMapper.sumExpenseByPeriodCategoryOneOff(familyId, ids).stream()
                .collect(java.util.stream.Collectors.groupingBy(CashFlowMapper.CatOneOffSum::periodId));
        var natureMap = excludeOneOff ? retirementExpenseNature.natureMap(familyId) : java.util.Map.<Long, com.family.finance.domain.expense.ExpenseNature>of();
        var excluded = excludedCategories.isEmpty() ? excludedCategories : retirementExpenseNature.expandCategories(familyId, excludedCategories);
        for (Long id : ids) {
            PeriodExpense source = ledger.get(id);
            boolean total = source.source() == PeriodExpense.Source.TOTAL
                    || (source.source() == PeriodExpense.Source.NONE && explicitTotals.contains(id));
            if (total) {
                out.put(id, new RetirementBudget(source.amountBase(), BigDecimal.ZERO, "历史家庭总额(无法拆消费/还款)", true));
                continue;
            }
            var rows = grouped.getOrDefault(id, List.of());
            if (rows.isEmpty()) continue;
            BigDecimal amount = BigDecimal.ZERO, removed = BigDecimal.ZERO;
            for (var row : rows) {
                if (!"consumption".equals(row.categoryCode())) continue;
                BigDecimal value = row.amountBase() == null ? BigDecimal.ZERO : row.amountBase();
                if ((excludeOneOff && retirementExpenseNature.natureOf(row.oneOff(), row.expenseCategoryId(), natureMap)
                        == com.family.finance.domain.expense.ExpenseNature.ONE_OFF)
                        || (row.expenseCategoryId() != null && excluded.contains(row.expenseCategoryId())))
                    removed = removed.add(value);
                else amount = amount.add(value);
            }
            out.put(id, new RetirementBudget(amount, removed, "逐笔消费(不含还款等资金性质)", false));
        }
        return out;
    }

    /**
     * 近 N 期(按 period_start 倒序 → 结果按倒序返回),供月均 / 中位数 / 已填月数用。
     *
     * <p><b>取期集合按模式区分</b>:</p>
     * <ul>
     *   <li><b>TOTAL</b>:只看 PMC 有记录的期 —— 与 v1.8 之前<b>逐位一致</b>(分母不能变)</li>
     *   <li><b>ITEMIZED</b>:PMC 期 ∪ 有逐笔支出的期 —— 否则「只录逐笔、没填总额」的月份整个漏掉,
     *       月均支出偏小、已填月数偏少</li>
     * </ul>
     */
    /**
     * v1.18.7 · 近 N 期,但<b>剔除进行中的那一期</b> —— 只给「月均 / 均值类」指标用。
     *
     * <h3>为什么要分出这个方法</h3>
     * <p>月中打开页面时,进行中账期的支出<b>只录了一部分</b>,却按<b>一整月</b>参与 12 期均值 →
     * 月均支出偏低。而月均支出是分母,后果是连锁的:</p>
     * <ul>
     *   <li><b>紧急储备</b> = 流动资产 ÷ 月均支出 → <b>虚高</b></li>
     *   <li><b>「应急金充足 + 超额闲置」banner</b> 的「实际需求」= 月均支出 × N 月 → 算小 →
     *       超额算大 → 更容易弹出,并给出「建议转货币基金」这种<b>行动建议</b></li>
     * </ul>
     * <p>这是「实时分子 ÷ 均值分母」的混搭,方向明确偏乐观,而页面上看不出来。
     * 本质与 v1.10 FR-327 处理「本月资产收益」时是同一个病:<b>半填的期不能当整期用</b>。
     * 那边的解法是显示实时值 + 讲清口径(因为用户就是要看本月);这边不同 ——
     * 「月均」本来就不该包含半个月,所以直接剔除。</p>
     *
     * <p><b>刻意只换两个调用点</b>({@code FactViewServiceImpl.averageExpense} 与
     * {@code HouseholdCashflowService.avgMonthlyExpense}),其余三处保持用 {@link #recent}:</p>
     * <ul>
     *   <li>{@code recentSeries} —— 收支趋势图<b>要</b>那个进行中的点(它自己标了浅色 + 「进行中」)</li>
     *   <li>{@code filledMonthRatio} —— 「已填 N/12 月」问的是填报完整度,本月填了就该算填了</li>
     *   <li>{@code GoalService} 的支出窗口 —— 另一个题目,不顺带改</li>
     * </ul>
     *
     * <p>剔除后一期都不剩时<b>返回空</b>(不退回含进行中的版本):那说明这个家庭还没有任何
     * 完整月份的支出记录,「月均支出」这个概念此时就是没有的 —— 给个由半个月推出来的数,
     * 比显示「—」更糟,因为页面上看不出它是编的。</p>
     */
    public List<PeriodExpense> recentClosed(long familyId, int limit) {
        // v1.23 FR-628 · 剔除**全部** OPEN 期,不是一个。
        //
        //   原来取 findCurrentOpen(单个 id)。双活跃窗口下有两期 OPEN,而它只返回最新那个 ——
        //   于是**半填的补录期被当成已关账算进月均支出**,月均支出突然变低,
        //   而 FIRE 目标、紧急储备(流动资产 ÷ 月均支出)全都跟着它走。
        //   这一类错的共性是「把非最新的 OPEN 期当成已关账」,不报错,只给一个偏低的数。
        java.util.Set<Long> openIds = periodMapper.findRecordableOpen(familyId).stream()
                .map(com.family.finance.domain.period.Period::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (openIds.isEmpty()) return recent(familyId, limit);
        // 多取几期再过滤 —— 否则剔掉 OPEN 那些之后只剩 limit−N 期,窗口无声地缩了几格
        return recent(familyId, limit + openIds.size()).stream()
                .filter(pe -> !openIds.contains(pe.periodId()))
                .limit(limit)
                .toList();
    }

    public List<PeriodExpense> recent(long familyId, int limit) {
        boolean itemizedFirst = modeOf(familyId) == ExpenseEntryMode.ITEMIZED;

        // ① PMC 侧有记录的期 —— 取数来源与 v1.8 之前的 averageExpense / medianMonthlySavings 相同。
        //    多取一些(limit×3)是因为 ITEMIZED 模式下要与逐笔期合并后重新截断。
        var pmcRecent = pmcMapper.findFamilyAggregateRecent(familyId, Math.max(limit * 3, limit));
        Map<Long, java.time.LocalDate> startById = new LinkedHashMap<>();
        Map<Long, BigDecimal> totals = new HashMap<>();
        for (var a : pmcRecent) {
            startById.put(a.periodId(), a.periodStart());
            if (a.totalExpense() != null) totals.put(a.periodId(), a.totalExpense());
        }

        // ② 逐笔独有的期 · 仅 ITEMIZED 模式并入(TOTAL 模式并进来会改掉月均的分母)
        //    并且**不并入未来账期**:账期表可能预建到很多年以后(beta 排到了 2038),
        //    「近 12 期」若把 2029 年的一笔算进来,月均支出/紧急储备就不是「近 12 个月」了。
        //    边界取「今天」与「进行中账期的起始」的较晚者 —— 家庭可以提前开下一期并开始录,
        //    那一期是用户心里的当期,不能因为 period_start 在未来就被剔掉。
        //    只约束这条新增路径 —— PMC 那条查询一行不动,总额模式才能逐位不变。
        if (itemizedFirst) {
            java.time.LocalDate today = latestRecordableStart(familyId);
            for (var r : cashFlowMapper.sumRealExpenseByPeriod(familyId, null)) {
                if (r.periodId() == null) continue;
                if (r.periodStart() != null && r.periodStart().isAfter(today)) continue;
                startById.putIfAbsent(r.periodId(), r.periodStart());
            }
        }
        if (startById.isEmpty()) return List.of();

        List<Long> ordered = new ArrayList<>(startById.keySet());
        ordered.sort((x, y) -> {
            var sx = startById.get(x);
            var sy = startById.get(y);
            if (sx == null && sy == null) return Long.compare(y, x);   // 都没日期 → 用 id 倒序兜底
            if (sx == null) return 1;
            if (sy == null) return -1;
            return sy.compareTo(sx);
        });

        Map<Long, PeriodExpense> all = decideWith(familyId, ordered, totals, itemizedFirst);
        List<PeriodExpense> out = new ArrayList<>();
        for (Long pid : ordered) {
            PeriodExpense pe = all.get(pid);
            if (pe == null || !pe.filled()) continue;      // 与现行语义一致:没填的期不参与均值/中位
            // TOTAL 分支的日期来自 PMC 聚合,ITEMIZED 分支的来自逐笔查询 —— 两边都补齐,
            // 否则调用方拿不到日期只能退化成按 id 排序/打标签(图表 x 轴会乱序)。
            out.add(pe.periodStart() == null ? pe.withStart(startById.get(pid)) : pe);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** v1.8 FR-272 · 支出构成的一个可切维度。参数不进 SQL 结构,只用白名单枚举。 */
    public enum Dim {
        CATEGORY("category", "按类目", "刚性支出(还贷 + 利息)vs 日常开支 —— 刚性压不下去,日常才是可调的"),
        ACCOUNT("account", "按账户", "钱主要从哪张卡出去"),
        MEMBER("member", "按成员", "家庭内部对账用");

        private final String code;
        private final String displayName;
        private final String hintText;
        Dim(String code, String displayName, String hintText) {
            this.code = code; this.displayName = displayName; this.hintText = hintText;
        }
        public String code() { return code; }
        public String displayName() { return displayName; }
        public String hintText() { return hintText; }
        public static Dim fromCode(String c) {
            if (c != null) for (Dim d : values()) if (d.code.equalsIgnoreCase(c.trim())) return d;
            return CATEGORY;
        }
    }

    /**
     * v1.8 FR-272 · 支出构成。
     *
     * @param periodIds 参与统计的账期(调用方按「本期 / 近 6 期 / 近 12 期」给)
     * @return 分组 + 占比 + 「只填了总数、没有构成」的账期清单(不隐藏、不假装没有)
     */
    public Composition composition(long familyId, Collection<Long> periodIds, Dim dim) {
        if (periodIds == null || periodIds.isEmpty()) {
            return new Composition(dim, List.of(), BigDecimal.ZERO, List.of());
        }
        var groups = cashFlowMapper.expenseBreakdown(familyId, periodIds, dim.code());
        BigDecimal total = BigDecimal.ZERO;
        for (var g : groups) if (g.amountBase() != null) total = total.add(g.amountBase());

        /* v1.22 · 支出可以是负数了(退款冲正原样记),于是一组的合计可能 ≤ 0。
         *
         * 【饼图画不了负扇形】。三条路只有一条是诚实的:
         *   ✗ 取绝对值凑扇形 —— 把一笔 −499 的退款画成一笔 499 的消费,用户完全看不出来
         *   ✗ 把负组藏掉并从总额里扣走 —— 「各组加起来 ≠ 总额」,对账时对不上
         *   ✓ 负组【不进扇形,但进总额】,在图例下方单列一行说明
         *
         * 所以 Slice 多一个 inChart:占比只对 > 0 的组算,负组的 pct 固定为 0
         * 并且不参与扇形。分母用【正组之和】而不是总额 —— 否则正组占比会 > 100%
         * (总额被负组拉小了),那个数字会让人以为算错了。 */
        BigDecimal positiveTotal = BigDecimal.ZERO;
        for (var g : groups) {
            BigDecimal a = g.amountBase() == null ? BigDecimal.ZERO : g.amountBase();
            if (a.signum() > 0) positiveTotal = positiveTotal.add(a);
        }
        List<Slice> slices = new ArrayList<>();
        for (var g : groups) {
            BigDecimal amt = g.amountBase() == null ? BigDecimal.ZERO : g.amountBase();
            boolean inChart = amt.signum() > 0;
            BigDecimal pct = (!inChart || positiveTotal.signum() == 0) ? BigDecimal.ZERO
                    : amt.multiply(new BigDecimal("100")).divide(positiveTotal, 1, RoundingMode.HALF_EVEN);
            slices.add(new Slice(g.groupKey(), g.groupLabel(),
                    amt.setScale(2, RoundingMode.HALF_EVEN), pct, g.itemCount(), inChart));
        }

        // 哪些账期只有总额、没有逐笔 —— 页面要如实标出来,否则用户会以为构成覆盖了全部支出
        var byPeriod = byPeriods(familyId, periodIds);
        List<Long> totalOnly = new ArrayList<>();
        for (Long pid : periodIds) {
            var pe = byPeriod.get(pid);
            if (pe != null && pe.source() == PeriodExpense.Source.TOTAL) totalOnly.add(pid);
        }
        return new Composition(dim, slices, total.setScale(2, RoundingMode.HALF_EVEN), totalOnly);
    }

    /**
     * 一格构成。pct 是 0–100 的百分数(图上直接标数字,不靠 hover)。
     *
     * @param inChart 进不进饼图。v1.22 起合计 ≤ 0 的组(退款多于消费)<b>不进扇形</b> ——
     *                扇形没有负面积,而取绝对值会把一笔退款画成一笔消费。
     *                它仍然<b>进总额</b>,并在图例下方单列一行说明。
     */
    public record Slice(String groupKey, String label, BigDecimal amountBase,
                        BigDecimal pct, int itemCount, boolean inChart) {}

    public record Composition(Dim dim, List<Slice> slices, BigDecimal totalBase, List<Long> totalOnlyPeriodIds) {
        /**
         * 有没有数据 —— 看<b>有没有行</b>,不看金额符号。
         *
         * <p>v1.22 之前这里是 {@code totalBase.signum() > 0}。放开负数之后那是个陷阱:
         * 某个月退款多于消费 → 总额 ≤ 0 → <b>整个「支出构成」段被判成「没数据」直接隐藏</b>,
         * 不报错、不解释,用户看到的是一片空白。而那个月明明有几十笔支出。</p>
         *
         * <p>0 和负数都是<b>合法且有含义</b>的值,不是「没有」。</p>
         */
        public boolean hasData() { return !slices.isEmpty(); }

        /** 有没有「退款多于消费」的组 —— 页面要在图例下面单列出来 */
        public List<Slice> negativeSlices() {
            return slices.stream().filter(sl -> !sl.inChart()).toList();
        }

        /**
         * 进饼图的那些组。
         *
         * <p>模板里<b>不要</b>直接用 {@link #slices()} 喂 Chart.js —— 扇形没有负面积,
         * 负值传进去 Chart.js 会画出一个看起来正常、但比例完全错的图。</p>
         */
        public List<Slice> chartSlices() {
            return slices.stream().filter(Slice::inChart).toList();
        }
        public boolean hasTotalOnlyPeriods() { return !totalOnlyPeriodIds.isEmpty(); }
    }

    /**
     * 「可计入的最晚账期起始」= 今天与**进行中账期起始**的较晚者。
     * 家庭可以提前开下一期并开始录(8 月就把 9 月开出来),那一期是用户心里的当期,
     * 不能因为 period_start 落在未来就被「不超过今天」剔掉。
     */
    private java.time.LocalDate latestRecordableStart(long familyId) {
        java.time.LocalDate today = java.time.LocalDate.now();
        // v1.23 · 取**最晚**的可写期(名字里的 latest 就是这个意思)。
        //   双活跃窗口下有两期 OPEN,这里要的是靠后那个 —— 补录期的 start 更早,
        //   拿它当上界会把进行期整个排除在窗口外。
        java.util.List<com.family.finance.domain.period.Period> open =
                periodMapper.findRecordableOpen(familyId);
        return (open.isEmpty() ? java.util.Optional.<com.family.finance.domain.period.Period>empty()
                               : java.util.Optional.of(open.getLast()))
                .map(p -> p.getPeriodStart())
                .filter(start -> start.isAfter(today))
                .orElse(today);
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private PeriodExpense decide(Long periodId, CashFlowMapper.RealExpenseSum itemized,
                                 BigDecimal total, boolean itemizedFirst) {
        /* v1.22 · 【这一处故意保留 signum() > 0】,和 hasData() 那里的结论相反。
         *
         * 一度想改成看 itemCount —— 理由是「0 和负数都是合法值,不该当成没有」。
         * 但这里判的不是「有没有数据」,而是<b>逐笔和手填总额谁说了算</b>,
         * 而单测 `金额为0的逐笔不得压掉用户手填的总额` 钉的正是这个:
         * 一行 0 元把用户手填的总额顶掉,<b>支出会凭空变 0</b> —— 那是灾难级的错。
         *
         * 为什么 v1.22 放开负数之后它仍然安全:冲突只发生在「既有逐笔又有手填总额」时,
         * 而 ITEMIZED 模式下总额框是隐藏的,用户几乎不会两边都有。真两边都有时,
         * 保守地用总额比让支出凭空归零好得多。
         *
         * 与 hasData() 的区别:那里的问题是「整段构成图消失」(展示层,可恢复),
         * 这里的问题是「支出金额本身错了」(口径层,会串到储蓄率和净流入)。 */
        boolean hasItem = itemized != null && itemized.amount() != null && itemized.amount().signum() > 0;
        boolean hasTotal = total != null && total.signum() > 0;
        PeriodExpense item = hasItem
                ? new PeriodExpense(periodId, itemized.periodStart(), itemized.amount(),
                                    PeriodExpense.Source.ITEMIZED, itemized.itemCount())
                : null;
        PeriodExpense tot = hasTotal
                ? new PeriodExpense(periodId, null, total, PeriodExpense.Source.TOTAL, 0)
                : null;
        if (itemizedFirst) {
            if (item != null) return item;
            if (tot != null) return tot;
        } else if (tot != null) {
            return tot;
        }
        // TOTAL 模式且 PMC 缺失 → **故意**返回 NONE,不用逐笔兜底。
        // 让调用方回落到它自己原来的路径(periodExpense / avgFromCashFlow —— 都读事实切片),
        // 这样「总额模式逐位不变」是结构上成立的,而不是靠我把 SQL 逐条对齐事实表的过滤与汇率。
        // 开发中先试过在这里兜底,结果 beta 家庭 XIRR 从 −56.19% 漂到 −50.60%(归档账户 + 汇率两处不一致)。
        return PeriodExpense.none(periodId);
    }
}
