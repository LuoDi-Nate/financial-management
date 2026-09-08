package com.family.finance.service.report;

import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.factview.PeriodFlow;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import com.family.finance.service.member.MemberDirectory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * v1.10 · 报表页「本期封板」与「结构与风险」两区的**单一数据入口**。
 *
 * <h3>为什么要这个类</h3>
 * <p>报表页承诺「封板期的指标不会再二次变动」。要兑现它,前两区的每个数字必须**只由
 * 「哪一期」决定**,不能由「看多长的窗口」决定。原来指标散在控制器里逐个调
 * {@code factViewService.xxx(pageSlice)} —— 那个 pageSlice 的 rangeStart 由 {@code range} 决定,
 * 于是「紧急储备 N 月」这类指标会随 range 变(tech-design v1.10 §2.2 ②)。</p>
 *
 * <p>所以这里收口:<b>方法签名里没有 range</b>,切片由本服务自己按 asof 加载,
 * 外部也不能塞切片进来 —— 后人想把 range 传进来没有地方放。守护
 * {@code v110-SEALED-SINGLE-ENTRY} 盯着这一点。</p>
 *
 * <h3>为什么不落库</h3>
 * <p>见 tech-design v1.10 §2:落库要么加指标就得 ALTER TABLE、要么口径一改要回填全部历史,
 * 而回填只能用**今天**的账户属性、拿不回封板当时的属性 —— 那样的"定格值"是假的。
 * 所以 v1.10 全实时算,同时修掉两个会改写历史的漂移源(归档语义、支出窗口),
 * 并把口径版本号显式亮出来({@link MetricFormulaVersion})。剩下修不了的漂移
 * (账户类型/类目可改会改写历史分类)由 {@code driftNotes} 如实交代,不假装不存在。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SealedPeriodService {

    private final FactViewService factViewService;
    private final PeriodMapper periodMapper;
    private final FamilyMapper familyMapper;
    private final SnapshotMapper snapshotMapper;
    /** v1.15 FR-382 · 名字映射走名录(含已归档)—— 归档一个人,不该让历史数据里的他变成无名氏 */
    private final MemberDirectory memberDirectory;
    private final com.family.finance.service.group.AccountGroupingResolver groupingResolver;  // v1.20 FR-484

    /**
     * 载入一个封板期的完整快照。
     *
     * @param anchor         已解析好的锚期(由 {@code ReportsAnchorResolver} 决定,不在这里重复解析)
     * @param closedSnapshot anchor 是否是已关账期;false = 外壳态,返回空壳
     * @param viewCurrency   视图币种(显示镜头)
     */
    public SealedSnapshot load(long familyId, Period anchor, boolean closedSnapshot, String viewCurrency) {
        if (anchor == null || !closedSnapshot) {
            return SealedSnapshot.unavailable(anchor);
        }
        Family family = familyMapper.findById(familyId).orElse(null);
        if (family == null) {
            return SealedSnapshot.unavailable(anchor);
        }

        // 已关账期升序 —— 上期 / 去年同期都从这里找,**不用「asof 减一个月」**(可能跳期)
        List<Period> closed = periodMapper.findAllByFamily(familyId).stream()
                .filter(p -> p.getStatus() == PeriodStatus.CLOSED && p.getPeriodStart() != null)
                .sorted(Comparator.comparing(Period::getPeriodStart))
                .toList();
        int idx = indexOf(closed, anchor.getPeriodStart());
        if (idx < 0) {
            return SealedSnapshot.unavailable(anchor);
        }
        Period prev = idx > 0 ? closed.get(idx - 1) : null;
        Period yoy = closed.stream()
                .filter(p -> p.getPeriodStart().equals(anchor.getPeriodStart().minusYears(1)))
                .findFirst().orElse(null);

        // ── 窗口切片:asof 往前 EXPENSE_WINDOW_PERIODS−1 期(已关账),用于
        //    ① 资产负债表(月均支出窗口固定,不随 range 变)② 本期与上期的逐期分解
        int back = Math.min(MetricFormulaVersion.EXPENSE_WINDOW_PERIODS - 1, idx);
        LocalDate windowStart = closed.get(idx - back).getPeriodStart();
        FactSlice window = factViewService.load(new FactFilter(
                familyId, family.getPeriodType(), windowStart, anchor.getPeriodStart(),
                false, null, viewCurrency));
        KpiSnapshot kpi = factViewService.kpis(window);

        SealedSnapshot.BalanceSheet balanceSheet = new SealedSnapshot.BalanceSheet(
                kpi.netWorth(), kpi.totalAssets(), kpi.totalLiabilities(),
                kpi.debtToAssetRatio(), kpi.liquidAssets(),
                kpi.emergencyFundMonths(), kpi.avgExpense(),
                back + 1);

        List<PeriodFlow> flows = factViewService.periodFlows(window);
        PeriodFlow anchorFlow = flowOf(flows, anchor.getId()).orElse(null);
        var anchorCf = factViewService.cashflowBreakdown(window, anchor.getId());

        SealedSnapshot.Waterfall waterfall = buildWaterfall(anchorFlow, anchorCf);
        SealedSnapshot.Comparison comparison = buildComparison(
                familyId, family, viewCurrency, window, flows, anchor, prev, yoy, closed);
        SealedSnapshot.Concentration concentration = buildConcentration(window, anchor.getId(),
                prev == null ? null : buildConcentration(window, prev.getId(), null));
        SealedSnapshot.LiquidityTiers liquidity = buildLiquidity(window, anchor.getId(), kpi.avgExpense());
        SealedSnapshot.Attribution attribution = buildAttribution(familyId, window, anchor.getId(), prev, anchorFlow);

        return new SealedSnapshot(
                anchor, true,
                buildCompleteness(window, anchor),
                balanceSheet, waterfall, comparison, concentration, liquidity, attribution,
                buildDistribution(familyId, window, anchor.getId()),
                MetricFormulaVersion.CURRENT,
                driftNotes());
    }

    /** 本期的逐期分解(人赚 / 开账基线 / 钱赚 / ΔNW)· 找不到返回 empty。 */
    Optional<PeriodFlow> flowOf(List<PeriodFlow> flows, Long periodId) {
        return flows.stream().filter(f -> f.periodId().equals(periodId)).findFirst();
    }

    // ── FR-321 · 数据完整度 ───────────────────────────────────────────

    /**
     * 这份快照可信不可信的交代。
     *
     * <p>「应填」取自事实切片里该期的账户行数 —— queryBase 是 account × period 全交叉,
     * 所以"该期有行"恰好等价于"该期时这个账户在册"(归档语义已带时间,见 FactMapper v1.10 注释)。
     * 「已填」查 period_snapshot 实际提交的行。两者之差就是没填的。</p>
     */
    SealedSnapshot.Completeness buildCompleteness(FactSlice window, Period anchor) {
        var expectedRows = window.rows().stream()
                .filter(r -> anchor.getId().equals(r.periodId()))
                .toList();
        java.util.Map<Long, String> nameById = expectedRows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.family.finance.factview.AccountPeriodFact::accountId,
                        com.family.finance.factview.AccountPeriodFact::accountName, (a, b) -> a));
        java.util.Set<Long> filledIds = snapshotMapper.findByPeriod(anchor.getId()).stream()
                .map(com.family.finance.domain.snapshot.PeriodSnapshot::getAccountId)
                .collect(java.util.stream.Collectors.toSet());
        List<String> missing = nameById.entrySet().stream()
                .filter(e -> !filledIds.contains(e.getKey()))
                .map(java.util.Map.Entry::getValue)
                .sorted()
                .limit(3)
                .toList();
        // 估值来源:该期有持仓估值写回的算「自动拉价」,其余算手填。
        //   用事实里的 periodPnlBase 判不准(那是算出来的),所以按账户类型近似:
        //   STOCK/CRYPTO/METAL 三类才有自动拉价链路。
        int auto = (int) expectedRows.stream()
                .filter(r -> switch (r.accountType()) {
                    case STOCK, CRYPTO, METAL -> true;
                    default -> false;
                })
                .count();
        return new SealedSnapshot.Completeness(
                (int) nameById.keySet().stream().filter(filledIds::contains).count(),
                nameById.size(), missing,
                auto, Math.max(0, nameById.size() - auto),
                anchor.getPeriodEnd(), anchor.getClosedAt());
    }

    // ── FR-323 · 瀑布 ─────────────────────────────────────────────────

    /**
     * 期初 → +收入 → −支出 → ±投资损益 → 期末。
     *
     * <p>恒等式 {@code ΔNW = (收入−支出) + 钱赚 + 开账基线} 由 {@link PeriodFlow} 的构造保证,
     * 所以 {@code identityDiff} 正常情况下**恰好等于开账基线**。我们仍然显式算差额并显示 ——
     * 万一哪天有第三个来源(如归档移出),它会自己冒出来,而不是被吞掉。</p>
     */
    SealedSnapshot.Waterfall buildWaterfall(PeriodFlow flow, com.family.finance.factview.CashflowBreakdown cf) {
        if (flow == null) {
            return null;
        }
        BigDecimal income = nz(cf == null ? null : cf.income());
        BigDecimal expense = nz(cf == null ? null : cf.expense());
        BigDecimal open = nz(flow.prevNetWorth());
        BigDecimal close = nz(flow.netWorth());
        BigDecimal pnl = nz(flow.pnl());
        BigDecimal diff = close.subtract(open).subtract(income.subtract(expense)).subtract(pnl);

        // 截断轴:月度**流量**(收入几万)与**存量**(净资产几百万)差两个数量级,
        // 全量轴下中间三段会细成一条线 → 必须按拐点 min-max ± 余量取轴,并在页面明示截断。
        BigDecimal p1 = open;
        BigDecimal p2 = open.add(income);
        BigDecimal p3 = p2.subtract(expense);
        List<BigDecimal> pts = List.of(p1, p2, p3, close);
        BigDecimal lo = pts.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal hi = pts.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        BigDecimal pad = hi.subtract(lo).multiply(new BigDecimal("0.15"));
        if (pad.compareTo(BigDecimal.ONE) < 0) {
            pad = BigDecimal.ONE;           // 四点全等时不能除零
        }
        BigDecimal axisLo = lo.subtract(pad);
        if (axisLo.signum() < 0 && lo.signum() >= 0) {
            axisLo = BigDecimal.ZERO;       // 本来全是正数就别把轴拉到负数
        }
        return new SealedSnapshot.Waterfall(open, income, expense, pnl, close,
                diff.setScale(2, java.math.RoundingMode.HALF_EVEN),
                nz(flow.openingBaseline()),
                axisLo.setScale(2, java.math.RoundingMode.HALF_EVEN),
                hi.add(pad).setScale(2, java.math.RoundingMode.HALF_EVEN));
    }

    // ── FR-324 · 三列对照 ─────────────────────────────────────────────

    SealedSnapshot.Comparison buildComparison(long familyId, Family family, String viewCurrency,
                                              FactSlice window, List<PeriodFlow> flows,
                                              Period anchor, Period prev, Period yoy, List<Period> closed) {
        Row cur = rowOf(window, flows, anchor);
        Row pr = prev == null ? null : rowOf(window, flows, prev);
        // 去年同期在窗口外 → 单独载一个 [yoy 的上一已关账期, yoy] 两期切片,
        // 这样 periodFlows 能算出 yoy 期的钱赚(它需要上一期净资产)
        Row yr = null;
        if (yoy != null) {
            int yi = indexOf(closed, yoy.getPeriodStart());
            LocalDate start = yi > 0 ? closed.get(yi - 1).getPeriodStart() : yoy.getPeriodStart();
            FactSlice ys = factViewService.load(new FactFilter(
                    familyId, family.getPeriodType(), start, yoy.getPeriodStart(), false, null, viewCurrency));
            yr = rowOf(ys, factViewService.periodFlows(ys), yoy);
        }
        List<SealedSnapshot.ComparisonRow> rows = new ArrayList<>();
        rows.add(row("净资产", cur.netWorth, pr == null ? null : pr.netWorth, yr == null ? null : yr.netWorth, false, false));
        rows.add(row("净流入(人赚)", cur.netInflow, pr == null ? null : pr.netInflow, yr == null ? null : yr.netInflow, false, false));
        rows.add(row("投资损益(钱赚)", cur.pnl, pr == null ? null : pr.pnl, yr == null ? null : yr.pnl, false, false));
        rows.add(row("支出", cur.expense, pr == null ? null : pr.expense, yr == null ? null : yr.expense, false, true));
        rows.add(row("储蓄率", cur.savingsRate, pr == null ? null : pr.savingsRate, yr == null ? null : yr.savingsRate, true, false));
        rows.add(row("资产负债率", cur.debtRatio, pr == null ? null : pr.debtRatio, yr == null ? null : yr.debtRatio, true, true));
        return new SealedSnapshot.Comparison(prev, yoy, rows);
    }

    private static SealedSnapshot.ComparisonRow row(String label, BigDecimal c, BigDecimal p, BigDecimal y,
                                                    boolean ratio, boolean lowerIsBetter) {
        return new SealedSnapshot.ComparisonRow(label, c, p, y, ratio, lowerIsBetter);
    }

    /** 一期的六个对照值。存量走 balanceAt,流量走 cashflowBreakdown,钱赚走 periodFlows —— 都不另写口径。 */
    private Row rowOf(FactSlice slice, List<PeriodFlow> flows, Period p) {
        var bal = factViewService.balanceAt(slice, p.getId());
        var cf = factViewService.cashflowBreakdown(slice, p.getId());
        PeriodFlow f = flowOf(flows, p.getId()).orElse(null);
        BigDecimal savings = nz(cf.income()).signum() == 0 ? null
                : nz(cf.income()).subtract(nz(cf.expense()))
                        .divide(nz(cf.income()), 6, java.math.RoundingMode.HALF_EVEN);
        return new Row(bal.netWorth(), nz(cf.netInflow()), f == null ? null : f.pnl(),
                nz(cf.expense()), savings, bal.debtToAssetRatio());
    }

    private record Row(BigDecimal netWorth, BigDecimal netInflow, BigDecimal pnl,
                       BigDecimal expense, BigDecimal savingsRate, BigDecimal debtRatio) {
    }

    // ── FR-325a · 集中度 ─────────────────────────────────────────────

    /**
     * <p><b>分母用绝对值。</b>直接拿净值求和的话,一笔大额房贷会把分母压到接近 0,
     * HHI 当场爆到 1 —— 集中度就成了"有没有房贷"的函数。取绝对值后它衡量的是
     * "资产/负债规模的分散度",与直觉一致。</p>
     */
    SealedSnapshot.Concentration buildConcentration(FactSlice slice, Long periodId,
                                                    SealedSnapshot.Concentration prev) {
        var rows = slice.rows().stream()
                .filter(r -> periodId.equals(r.periodId()))
                .filter(r -> r.endBalanceBase() != null)
                .toList();
        BigDecimal total = rows.stream()
                .map(r -> r.endBalanceBase().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (rows.isEmpty() || total.signum() == 0) {
            return null;
        }
        record Share(String name, BigDecimal pct) {
        }
        List<Share> shares = rows.stream()
                .map(r -> new Share(r.accountName(), r.endBalanceBase().abs()
                        .divide(total, 8, java.math.RoundingMode.HALF_EVEN)))
                .sorted((x, y) -> y.pct().compareTo(x.pct()))
                .toList();
        BigDecimal hhi = shares.stream()
                .map(x -> x.pct().multiply(x.pct()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, java.math.RoundingMode.HALF_EVEN);
        BigDecimal top1 = pct(shares.get(0).pct());
        BigDecimal top3 = pct(shares.stream().limit(3).map(Share::pct)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        // 平台维度:用账户类型兜底(lens platform 需要 lens 维度值,这里取事实里现成的 type)
        var byType = rows.stream().collect(java.util.stream.Collectors.groupingBy(
                r -> r.accountType().getLabel(),
                java.util.stream.Collectors.reducing(BigDecimal.ZERO,
                        r -> r.endBalanceBase().abs(), BigDecimal::add)));
        var topType = byType.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).orElse(null);
        return new SealedSnapshot.Concentration(top1, top3, hhi,
                topType == null ? null : pct(topType.getValue().divide(total, 8, java.math.RoundingMode.HALF_EVEN)),
                shares.get(0).name(), topType == null ? null : topType.getKey(), prev);
    }

    // ── FR-325b · 流动性分层 ────────────────────────────────────────

    SealedSnapshot.LiquidityTiers buildLiquidity(FactSlice slice, Long periodId, BigDecimal avgExpense) {
        var rows = slice.rows().stream()
                .filter(r -> periodId.equals(r.periodId()))
                .filter(r -> r.endBalanceBase() != null)
                .toList();
        if (rows.isEmpty()) {
            return null;
        }
        BigDecimal liquid = tier(rows, com.family.finance.domain.account.AccountLiquidity.LIQUID);
        BigDecimal semi = tier(rows, com.family.finance.domain.account.AccountLiquidity.SEMI_LIQUID);
        // NA 档并入「不可动」—— 贷款/其他归到这里,页面 tooltip 说明
        BigDecimal illiquid = tier(rows, com.family.finance.domain.account.AccountLiquidity.ILLIQUID)
                .add(tier(rows, com.family.finance.domain.account.AccountLiquidity.NA));
        BigDecimal total = liquid.add(semi).add(illiquid);
        if (total.signum() == 0) {
            return null;
        }
        BigDecimal cover = nz(avgExpense).signum() == 0 ? null
                : liquid.divide(avgExpense, 1, java.math.RoundingMode.HALF_EVEN);
        return new SealedSnapshot.LiquidityTiers(liquid, semi, illiquid,
                pct(liquid.divide(total, 8, java.math.RoundingMode.HALF_EVEN)),
                pct(semi.divide(total, 8, java.math.RoundingMode.HALF_EVEN)),
                pct(illiquid.divide(total, 8, java.math.RoundingMode.HALF_EVEN)),
                cover);
    }

    private static BigDecimal tier(List<com.family.finance.factview.AccountPeriodFact> rows,
                                   com.family.finance.domain.account.AccountLiquidity t) {
        return rows.stream()
                .filter(r -> r.accountLiquidity() == t)
                .map(r -> r.endBalanceBase().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, java.math.RoundingMode.HALF_EVEN);
    }

    // ── FR-328 · 封板期分布(成员 / 资产大类)──────────────────────

    SealedSnapshot.Distribution buildDistribution(long familyId, FactSlice slice, Long periodId) {
        var rows = slice.rows().stream()
                .filter(r -> periodId.equals(r.periodId()))
                .filter(r -> r.endBalanceBase() != null)
                // 只计资产 —— 与仪表盘同口径(LOAN 不入分布,否则「谁名下多少钱」会被房贷带成负数)
                .filter(r -> r.accountClass() == com.family.finance.domain.account.AccountClass.ASSET)
                .toList();
        if (rows.isEmpty()) {
            return null;
        }
        BigDecimal total = rows.stream().map(com.family.finance.factview.AccountPeriodFact::endBalanceBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            return null;
        }
        // Collectors.toMap 的 value 为 null 会 NPE(不是返回 null 那种"温和"失败)——
        // displayName 理论上非空,但一个展示用的名字没必要让整页 500。手工填 map,null 用兜底名。
        java.util.Map<Long, String> memberName = new java.util.HashMap<>();
        for (var m : memberDirectory.listAll(familyId)) {
            if (m.getId() == null) {
                continue;
            }
            memberName.put(m.getId(),
                    m.getDisplayName() == null || m.getDisplayName().isBlank()
                            ? "成员#" + m.getId() : m.getDisplayName());
        }
        java.util.LinkedHashMap<String, BigDecimal> byMember = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, BigDecimal> byType = new java.util.LinkedHashMap<>();
        for (var r : rows) {
            Long owner = r.ownerId();
            String who = owner == null ? "共同" : memberName.getOrDefault(owner, "成员#" + owner);
            byMember.merge(who, r.endBalanceBase(), BigDecimal::add);
            // accountType 理论上必有,但仪表盘那份同类代码也显式跳 null —— 照着来,别让一行脏数据打掉整页
            if (r.accountType() != null) {
                byType.merge(r.accountType().getLabel(), r.endBalanceBase(), BigDecimal::add);
            }
        }
        return new SealedSnapshot.Distribution(toSlices(byMember, total), toSlices(byType, total));
    }

    private static List<SealedSnapshot.Distribution.Slice> toSlices(
            java.util.Map<String, BigDecimal> m, BigDecimal total) {
        return m.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .map(e -> new SealedSnapshot.Distribution.Slice(e.getKey(),
                        e.getValue().setScale(2, java.math.RoundingMode.HALF_EVEN),
                        pct(e.getValue().divide(total, 8, java.math.RoundingMode.HALF_EVEN))))
                .toList();
    }

    // ── FR-326 · 本期归因 ──────────────────────────────────────────

    /**
     * 正/负贡献 Top3。
     *
     * <p><b>本期首次出现的账户不进正贡献</b> —— 否则"补录一个存量账户"会显示成本月大赚。
     * 同理本期归档的不进负贡献。两类单列。</p>
     */
    /** 账户余额按维值标签汇总(未分组的标签就是账户名 → 零组态下逐字一致) */
    private static java.util.Map<String, BigDecimal> foldByLabel(
            java.util.Map<Long, BigDecimal> byAccount, java.util.function.Function<Long, String> labelOf) {
        java.util.Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        byAccount.forEach((id, v) -> out.merge(labelOf.apply(id), nz(v), BigDecimal::add));
        return out;
    }

    SealedSnapshot.Attribution buildAttribution(long familyId, FactSlice slice, Long periodId,
                                                Period prev, PeriodFlow flow) {
        if (prev == null || flow == null) {
            return null;
        }
        java.util.Map<Long, BigDecimal> curAcct = endByAccount(slice, periodId);
        java.util.Map<Long, BigDecimal> beforeAcct = endByAccount(slice, prev.getId());
        java.util.Map<Long, String> names = slice.rows().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.family.finance.factview.AccountPeriodFact::accountId,
                        com.family.finance.factview.AccountPeriodFact::accountName, (a, b) -> a));

        /* v1.20 FR-484 · 贡献者按【组】折叠 —— 组内划转在这里自动抵消,正是 issue #17 要的效果。
         *
         * 两个必须做对的细节:
         *  ① 【本期】和【上期】都要用本期的分组关系折叠。用各自期的关系会让「改了分组」
         *     长得和「钱动了」一模一样 —— 差额凭空冒出来又说不清是哪来的。
         *  ② periodId 传给 resolver = 已关账期读 period_account_group 的【该期定格】。
         *     读当前成员关系的话,今天挪一个账户,历史封板页的贡献者列表会跟着变。
         *
         * 折叠放在【逐账户算完 delta 之前】:先把余额按标签汇总,再走原来那套 isNew/isGone 判定,
         * 于是「组是不是本期新开的」自然等于「组里所有成员上期都没余额」—— 一行额外逻辑都不用写。 */
        java.util.Map<Long, com.family.finance.service.group.AccountGroupingResolver.Label> labels =
                groupingResolver.labelsFor(familyId, periodId, null);
        java.util.function.Function<Long, String> labelOf = id -> {
            var lb = labels.get(id);
            return lb != null ? lb.value() : names.getOrDefault(id, "#" + id);
        };
        java.util.Map<String, BigDecimal> cur = foldByLabel(curAcct, labelOf);
        java.util.Map<String, BigDecimal> before = foldByLabel(beforeAcct, labelOf);

        BigDecimal delta = nz(flow.nwDelta());
        List<SealedSnapshot.Contribution> pos = new ArrayList<>();
        List<SealedSnapshot.Contribution> neg = new ArrayList<>();
        List<SealedSnapshot.Contribution> opened = new ArrayList<>();
        List<SealedSnapshot.Contribution> archived = new ArrayList<>();
        for (String id : new java.util.TreeSet<>(union(cur.keySet(), before.keySet()))) {
            boolean isNew = !before.containsKey(id) && cur.containsKey(id);
            boolean isGone = before.containsKey(id) && !cur.containsKey(id);
            BigDecimal d = nz(cur.get(id)).subtract(nz(before.get(id)));
            var c = new SealedSnapshot.Contribution(id,
                    d.setScale(2, java.math.RoundingMode.HALF_EVEN), share(d, delta));
            if (isNew) {
                opened.add(c);
            } else if (isGone) {
                archived.add(c);
            } else if (d.signum() > 0) {
                pos.add(c);
            } else if (d.signum() < 0) {
                neg.add(c);
            }
        }
        pos.sort((a, b) -> b.amount().compareTo(a.amount()));
        neg.sort(java.util.Comparator.comparing(SealedSnapshot.Contribution::amount));
        return new SealedSnapshot.Attribution(
                pos.stream().limit(3).toList(), neg.stream().limit(3).toList(),
                opened, archived, delta.setScale(2, java.math.RoundingMode.HALF_EVEN));
    }

    /* v1.20 · 泛型化:贡献者折叠之后键从 accountId(Long)变成维值标签(String) */
    private static <T> java.util.Set<T> union(java.util.Set<T> a, java.util.Set<T> b) {
        java.util.Set<T> s = new java.util.HashSet<>(a);
        s.addAll(b);
        return s;
    }

    private static java.util.Map<Long, BigDecimal> endByAccount(FactSlice slice, Long periodId) {
        return slice.rows().stream()
                .filter(r -> periodId.equals(r.periodId()))
                .filter(r -> r.endBalanceBase() != null)
                .collect(java.util.stream.Collectors.toMap(
                        com.family.finance.factview.AccountPeriodFact::accountId,
                        com.family.finance.factview.AccountPeriodFact::endBalanceBase, (a, b) -> a));
    }

    private static BigDecimal share(BigDecimal d, BigDecimal delta) {
        if (delta == null || delta.signum() == 0) {
            return null;
        }
        return d.multiply(new BigDecimal("100")).divide(delta.abs(), 1, java.math.RoundingMode.HALF_EVEN);
    }

    private static BigDecimal pct(BigDecimal ratio) {
        return ratio.multiply(new BigDecimal("100")).setScale(2, java.math.RoundingMode.HALF_EVEN);
    }

    private static int indexOf(List<Period> periods, LocalDate start) {
        for (int i = 0; i < periods.size(); i++) {
            if (periods.get(i).getPeriodStart().equals(start)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 「已关账的月份还会不会变」—— 页面如实交代。
     *
     * <p>v1.10 修掉两个漂移源(归档语义、支出窗口),v1.12 FR-350 修掉第三个:关账时把账户的
     * 5 个分类属性定格进 {@code period_account_attr},之后改账户设置只动当期,
     * 不再改写历史期的集中度 / 流动性分层 / 大类分布 / 基准 / 预实。</p>
     *
     * <p>所以这段文案必须从「按当前属性归类」改成「从此不再变」。但只说前半句是不诚实的,
     * 还有三件事会让历史数字变,页面一并说出来:</p>
     * <ul>
     *   <li><b>回填的期不是真定格</b>:v1.12 上线前已关账的月份,属性是按上线当天回填的
     *       ({@code period_account_attr.source = BACKFILL})——「当时是什么类目」这个信息
     *       从来没被记录过,<b>追不回来</b>。之前发生过的漂移已经发生了,这一版只能保证以后不再发生。</li>
     *   <li><b>当期还没定格</b>:填报中的期按当前设置归类,关账那一刻才定下来 —— 这是刻意的
     *       (仪表盘 = 实时 / 报表 = 封板 的两页分工)。</li>
     *   <li><b>口径本身改了会重算历史</b> —— 这是故意保留的(PRD v1.12 §2):这一版只冻结分类<b>输入</b>,
     *       不冻结指标<b>输出</b>。冻输出会把口径 bug 一起冻住,以后修口径 = 历史全废
     *       (v1.9.4 那种「一改就全对」的修复靠的就是它)。</li>
     * </ul>
     */
    private List<String> driftNotes() {
        List<String> notes = new ArrayList<>();
        notes.add("已关账的月份,账户的类型 / 产品类目 / 流动性档 / 基准 / 预期年化 在关账那一刻<b>定格</b>,"
                + "以后改这些设置<b>不会</b>再改写历史月份的分类(集中度、流动性分层、大类分布、预实)。");
        notes.add("例外:升级到本版<b>之前</b>就已关账的月份,是按升级当天的设置补记的 ——"
                + "「当时是什么类目」以前没被记录过,追不回来。");
        notes.add("另外:填报中的当期按当前设置归类(关账时才定);指标算法本身修正时会重算全部历史 ——"
                + "这是故意的,口径修一次就全部对,而不是把算错的口径连历史一起冻住。");
        return notes;
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
