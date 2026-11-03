package com.family.finance.service.checkup;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.family.Family;
import com.family.finance.domain.period.Period;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.ProductCategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 全家体检主聚合 · v0.2 · FR-40a
 *
 * 用 {@link FactViewService} 已有方法取 KPI / Allocation,补充风险分布与家庭加权年化。
 */
@Service
@RequiredArgsConstructor
public class FamilyDiagnoseService {

    private final FactViewService factViewService;
    private final ProductCategoryService productCategoryService;
    private final FamilyMapper familyMapper;
    private final PeriodMapper periodMapper;
    private final com.family.finance.repository.AccountMapper accountMapper;   // 2026-09-24 · 风险等级读账户自己的类目
    /** v1.27 · 分析范围:配置 / 风险两张卡的分母里有谁 */
    private final com.family.finance.service.analysis.AnalysisScopeService scopeService;

    /**
     * v1.6 UED review A2 · 与 {@code DashboardController.resolveAsOf} 完全同口径的 anchor 选取:
     * ① 优先进行中(OPEN)周期 → ② 否则「不晚于今天」的最新期(排除未来测试期)→ ③ 兜底最新期。
     * <p>public 是为了让 CheckupController 把这一期回显到页面("数据截至 X"),
     * 使用户能看出体检与仪表盘是否同期 —— 此前 checkup 完全不显示账期,数值不一致时无从分辨。
     */
    public Period resolveAnchor(long familyId) {
        // v1.23 · 体检看的是**存量**(净资产 / 配置 / 风险分布),锚「当前余额所在期」= 最新 OPEN。
        //   双活跃窗口下这里取进行期是对的:补录期的余额是上月末的事实,不是「现在」。
        Period open = periodMapper.findBalancePeriod(familyId).orElse(null);
        if (open != null) return open;
        List<Period> all = periodMapper.findAllByFamily(familyId);
        java.time.LocalDate today = java.time.LocalDate.now();
        return all.stream()
                .filter(p -> p.getPeriodStart() != null && !p.getPeriodStart().isAfter(today))
                .max(java.util.Comparator.comparing(Period::getPeriodStart))
                .orElseGet(() -> all.stream().max(java.util.Comparator.comparing(Period::getPeriodStart))
                        .orElseThrow(() -> new IllegalStateException("尚未创建周期")));
    }

    /** 按「全部资产」诊断(v1.26 行为)。历史报表应调用 riskDistribution 传入观察账期切片。 */
    public FamilyDiagnose diagnose(long familyId) {
        return diagnose(familyId, slice -> com.family.finance.service.analysis.AnalysisScope.all(), false);
    }

    /**
     * v1.27 · 按分析范围诊断(PRD FR-824):<b>只有</b>资产配置、风险分布两项吃范围切片;
     * KPI、流动性、收益、账户数永远吃全量切片。两张切片来自同一次加载({@code FactSlice.excludingAccounts}),
     * 不会锚到两个不同的期。
     *
     * @param requestedScope 页面上的 {@code ?scope=};空 = 家庭默认
     */
    public FamilyDiagnose diagnose(long familyId, String requestedScope) {
        return diagnose(familyId, slice -> scopeService.resolve(familyId, requestedScope, slice), true);
    }

    /**
     * v1.27 · 模板固定了范围(如「投资组合体检」= 金融资产)时用:就按这个种类算,不回落家庭默认。
     * AI 的配置 / 风险两节必须和它用的范围同源 —— 所以 AI 那边按模板的范围重算一次诊断,而不是借页面上那份。
     */
    public FamilyDiagnose diagnoseExactly(long familyId, com.family.finance.service.analysis.ScopeKind kind) {
        return diagnose(familyId, slice -> scopeService.exactly(familyId, kind, slice), true);
    }

    private FamilyDiagnose diagnose(long familyId,
                                    java.util.function.Function<FactSlice, com.family.finance.service.analysis.AnalysisScope> scopeOf,
                                    boolean withOptions) {
        // v0.2 bug 修(2026-05-10): 旧实现 factViewService.loadDefault 用 LocalDate.now() 作 end,
        // 当用户测试期间生成了未来期(>当前日期),那些期会被排除,与 /dashboard 不一致。
        //
        // v1.6 UED review A2(2026-07-26)· 上面那次修错了 dashboard 的口径:
        //   dashboard 的 resolveAsOf 是「优先进行中(OPEN)→ 否则不晚于今天的最新期 → 兜底最新期」,
        //   会**排除未来期**;而这里写成了裸 findLatest(含未来期)。
        //   beta/测试库里存在未来账期(如 2029-09)时,两页 anchor 落到不同账期 →
        //   净资产/总资产/紧急储备等同名指标数值不同(实测差 119 万、紧急储备 7.2 月 vs 723 月),
        //   用户无从分辨谁对,直接击穿对全部数字的信任。
        //   现改为与 dashboard 完全同规则,并把 anchor 期回传给页面显示(见 CheckupController)。
        Family family = familyMapper.findById(familyId)
                .orElseThrow(() -> new IllegalArgumentException("家庭不存在: " + familyId));
        Period anchor = resolveAnchor(familyId);
        java.time.LocalDate end = anchor.getPeriodStart();
        // v1.6.30 · 锚点期若还在填报(OPEN),minusMonths(11) 的窗口里只剩 11 个已关账期 →
        //   收益类落到「不满 12 期」的累计口径,而 reports(窗口锚最新已关账期)拿得到 12 个 → 走年化。
        //   同一个「家庭 XIRR」两页给出 −0.21% 与 +1.11%。多回一个月,让两处取到同一批已关账期。
        boolean anchorOpen = anchor.getStatus() != null && !"CLOSED".equals(anchor.getStatus().name());
        java.time.LocalDate start = end.minusMonths(anchorOpen ? 12 : 11);
        FactSlice slice = factViewService.load(new FactFilter(
                familyId, family.getPeriodType(), start, end, false, null, family.getBaseCurrency()));
        KpiSnapshot kpi = factViewService.kpis(slice);
        // v1.27 · 占比类吃范围切片(空范围 = 空行,不会退回全量 —— 见 FactSlice.excludingAccounts)
        com.family.finance.service.analysis.AnalysisScope scope = scopeOf.apply(slice);
        FactSlice scoped = scope.apply(slice);
        List<AllocationSlice> allocation = scope.empty() ? List.of()
                : factViewService.allocationByType(scoped, scoped.lastPeriodId());
        BigDecimal familyXirr = factViewService.familyXirr(slice);
        BigDecimal familyTwr = factViewService.familyTwr(slice);

        var risk = riskSummary(familyId, scoped);

        BigDecimal liquidAssets = slice.rows().stream()
                .filter(r -> Objects.equals(r.periodId(), slice.lastPeriodId()))
                .filter(r -> r.accountLiquidity() == AccountLiquidity.LIQUID)
                .map(AccountPeriodFact::endBalanceBase)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);

        BigDecimal cumulativeYtdPnl = slice.rows().stream()
                .filter(r -> r.periodStart() != null
                        && r.periodStart().getYear() == java.time.LocalDate.now().getYear())
                .map(AccountPeriodFact::periodPnlBase)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);

        int accountCount = (int) slice.rows().stream()
                .filter(r -> Objects.equals(r.periodId(), slice.lastPeriodId()))
                .map(AccountPeriodFact::accountId)
                .distinct()
                .count();

        return new FamilyDiagnose(
                kpi,
                allocation,
                risk.buckets(),
                liquidAssets,
                kpi.emergencyFundMonths(),
                familyXirr,
                familyTwr,
                cumulativeYtdPnl,
                accountCount,
                0,  // pending TODO 接入 SnapshotTodoMapper(此值仅 banner 用,现阶段不阻塞)
                risk.estimatedCount(),
                scope,
                withOptions ? scopeService.options(familyId, slice) : List.of()
        );
    }

    /** 使用调用方的账期、账户范围与视图币种,不重新加载「现在」的余额。 */
    public List<FamilyDiagnose.RiskBucket> riskDistribution(long familyId, FactSlice slice) {
        return riskSummary(familyId, slice).buckets();
    }

    private RiskSummary riskSummary(long familyId, FactSlice slice) {
        Map<String, ProductCategory> categories = productCategoryService.listAll().stream()
                .collect(java.util.stream.Collectors.toMap(ProductCategory::getCode, java.util.function.Function.identity()));
        Map<Long, com.family.finance.domain.account.Account> accounts = new java.util.HashMap<>();
        for (var account : accountMapper.findAllByFamily(familyId)) accounts.put(account.getId(), account);
        java.util.function.Function<String, Integer> riskOfCategory = code -> {
            var category = categories.get(code);
            return category == null ? null : category.getRiskLevel();
        };
        Map<Integer, BigDecimal> amounts = new java.util.TreeMap<>();
        java.util.Set<Long> estimated = new java.util.HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var row : slice.rows()) {
            if (!Objects.equals(row.periodId(), slice.lastPeriodId())
                    || row.accountClass() != AccountClass.ASSET || row.endBalanceBase() == null) continue;
            BigDecimal value = row.endBalanceBase();
            var account = accounts.get(row.accountId());
            var risk = RiskLevels.resolve(account == null ? null : account.getRiskLevelOverride(),
                    account == null ? null : account.getProductCategoryCode(), row.accountType(), riskOfCategory);
            if (risk.estimated() && value.signum() != 0) estimated.add(row.accountId());
            amounts.merge(risk.level(), value, BigDecimal::add);
            total = total.add(value);
        }
        List<FamilyDiagnose.RiskBucket> buckets = new ArrayList<>();
        for (var entry : amounts.entrySet()) {
            BigDecimal value = entry.getValue().setScale(2, RoundingMode.HALF_EVEN);
            if (value.signum() == 0) continue;
            BigDecimal ratio = total.signum() == 0 ? BigDecimal.ZERO : value.divide(total, 6, RoundingMode.HALF_EVEN);
            buckets.add(new FamilyDiagnose.RiskBucket(entry.getKey(), riskLabel(entry.getKey()), value, ratio));
        }
        return new RiskSummary(buckets, estimated.size());
    }

    private record RiskSummary(List<FamilyDiagnose.RiskBucket> buckets, int estimatedCount) {}

    private static String riskLabel(int level) {
        return switch (level) {
            case 0 -> "未评级";
            case 1 -> "极低";
            case 2 -> "低";
            case 3 -> "中低";
            case 4 -> "中";
            case 5 -> "中高";
            case 6 -> "极高";
            default -> "未知";
        };
    }
}
