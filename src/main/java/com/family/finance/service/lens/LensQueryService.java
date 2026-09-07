package com.family.finance.service.lens;

import com.family.finance.calc.lens.Position;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.domain.lens.AssetClass;
import com.family.finance.domain.lens.IndustryTag;
import com.family.finance.domain.member.Member;
import com.family.finance.domain.stock.Market;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.service.ProductCategoryService;
import com.family.finance.service.stock.AccountValuationService;
import com.family.finance.service.stock.StockHoldingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * v1.1 · 资产透视头寸组装(tech-design v1.1 决策 1 · 实时组装,不物化)。
 *
 * <p>手填账户 = 1 头寸(factview 账户现值 · 本位币);持仓账户 = N 头寸
 * (perHoldingLines 账户币种现值 × 统一因子缩放到本位币,Σ头寸 ≡ 账户现值,口径与仪表盘一致)。
 * LOAN 排除(负债不进资产分母);未填报(无现值)账户跳过。维度标签在此算好,引擎只分组。</p>
 */
@Service
@RequiredArgsConstructor
public class LensQueryService {

    private final AccountMapper accountMapper;
    /** v1.20 · 账户组维值的唯一来源 */
    private final com.family.finance.service.group.AccountGroupingResolver groupingResolver;
    /** v1.15 FR-382 · 名字映射走名录(含已归档)—— 归档一个人,不该让历史数据里的他变成无名氏 */
    private final MemberDirectory memberDirectory;
    private final ProductCategoryService productCategoryService;
    private final FactViewService factViewService;
    private final AccountValuationService valuationService;
    /** v1.5 · 持仓方向(穿透)· 有则按权重把一持仓拆成多头寸 */
    private final com.family.finance.repository.HoldingAllocationMapper allocMapper;

    /** 头寸快照缓存 · v1.1.1 重设计(prod 实测根因:60s TTL 让用户每分钟踩一次同步冷组装):
     *  ① TTL 12h,仅作兜底 —— 写路径已全覆盖失效({@link LensStaleEvent}:填报/转账/估值刷新/账户增改档/打标);
     *  ② stale-while-revalidate:过期不阻塞,先返回旧数据,后台单飞异步重组装 —— 用户任何时刻都不等组装;
     *  ③ 启动预热(ApplicationReady 异步组装)—— 重启后首个用户也不冷;
     *  ④ per-family 锁 + 双检:并发首载只组装一遍。 */
    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1000L;
    /**
     * {@code anchorPeriodId} 是这批头寸<b>取自哪一期</b>。
     *
     * <p>v1.19 补:透视页自己不显示账期(它就在当期上下文里),但 AI 那条路径必须知道 ——
     * 一个说不清是哪一期的数字,对 agent 来说是可疑数据。实测过后果:pivot 返回的
     * period 为空,模型专门多花一轮去确认「这是哪一期的」,还把疑问写进了回答里。</p>
     */
    private record CacheEntry(List<Position> positions, long at, Long anchorPeriodId) {}
    private final java.util.concurrent.ConcurrentHashMap<Long, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> refreshing = new java.util.concurrent.ConcurrentHashMap<>();
    /** 后台刷新单线程(守护)· 不依赖 @EnableAsync 配置 */
    private final java.util.concurrent.ExecutorService refresher =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lens-refresh");
                t.setDaemon(true);
                return t;
            });

    public List<Position> positions(long familyId) {
        return entry(familyId).positions();
    }

    /** 这批头寸取自哪一期。与 {@link #positions} 同一个缓存项 —— 不会各算各的 */
    public Long anchorPeriodId(long familyId) {
        return entry(familyId).anchorPeriodId();
    }

    private CacheEntry entry(long familyId) {
        CacheEntry e = cache.get(familyId);
        if (e != null) {
            if (isStale(e)) triggerRefresh(familyId);   // 过期:先用旧值,后台刷(SWR)
            return e;
        }
        synchronized (locks.computeIfAbsent(familyId, k -> new Object())) {
            e = cache.get(familyId);
            if (e != null) return e;
            e = build(familyId);
            cache.put(familyId, e);
            return e;
        }
    }

    private CacheEntry build(long familyId) {
        FactSlice slice = factViewService.loadDefault(familyId);
        return new CacheEntry(List.copyOf(assemble(familyId, slice)),
                System.currentTimeMillis(), slice.lastPeriodId());
    }

    /** 数据变更(填报/转账/估值/账户增改档/打标)→ 不清缓存,后台重组装换新 —— 期间读旧值,不产生阻塞窗口 */
    public void evict(long familyId) {
        triggerRefresh(familyId);
    }

    /** AFTER_COMMIT:发布方多为 @Transactional 写路径,必须等事务提交后再重组装,
     *  否则后台线程读到未提交旧数据、缓存被换成旧值(beta 实测踩过);
     *  fallbackExecution=true 兼容无事务上下文的发布方。 */
    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onStale(LensStaleEvent ev) {
        triggerRefresh(ev.familyId());
    }

    /** 启动预热 · 单家庭模式 family=1(与 FamilyRules 同假设);失败静默,首个请求走同步组装兜底 */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmUp() {
        triggerRefresh(1L);
    }

    private void triggerRefresh(long familyId) {
        if (refreshing.putIfAbsent(familyId, Boolean.TRUE) != null) return;   // 单飞
        refresher.submit(() -> {
            try {
                cache.put(familyId, build(familyId));
            } catch (Exception ignored) {
                // 组装失败保留旧缓存 · 下次触发再试
            } finally {
                refreshing.remove(familyId);
            }
        });
    }

    private static boolean isStale(CacheEntry e) {
        return System.currentTimeMillis() - e.at() >= CACHE_TTL_MS;
    }

    /**
     * v1.20 · 把「原值 − 各份之和」的残差补给份额最大的那一份,让拆分求和<b>精确等于</b>原值。
     *
     * <p>不这样做的后果不是「差一分钱不好看」,而是<b>两条聚合路径给出不同的总资产</b> ——
     * 按拆分份额加的(透视)和按账户原值加的(KPI)对不上,而且差多少<b>跟数据分布走</b>,
     * 时红时绿,很容易被当成偶发。</p>
     *
     * <p>{@code null} 元素(不可算的度量)整列跳过 —— 不能把 null 当 0 补,那是编数据。</p>
     */
    static void residualToLargest(List<BigDecimal> parts, BigDecimal total) {
        if (total == null || parts.isEmpty()) return;
        BigDecimal sum = BigDecimal.ZERO;
        int largest = -1;
        BigDecimal largestAbs = null;
        for (int i = 0; i < parts.size(); i++) {
            BigDecimal v = parts.get(i);
            if (v == null) return;                    // 有一份算不出来 → 整列不动
            sum = sum.add(v);
            if (largestAbs == null || v.abs().compareTo(largestAbs) > 0) { largestAbs = v.abs(); largest = i; }
        }
        BigDecimal residual = total.setScale(2, RoundingMode.HALF_EVEN).subtract(sum);
        if (residual.signum() != 0 && largest >= 0) {
            parts.set(largest, parts.get(largest).add(residual));
        }
    }

    private List<Position> assemble(long familyId, FactSlice slice) {
        // v1.20 · 一次取回整张账户→维值映射。**不能在循环里问解析器**,那是 N+1(v1200-NO-N-PLUS-1)
        Map<Long, String> acctGroupValue =
                groupingResolver.valuesFor(familyId, slice.lastPeriodId(), null);
        Map<Long, String> memberName = memberDirectory.listAll(familyId).stream()
                .collect(Collectors.toMap(Member::getId, Member::getDisplayName));
        Map<Long, AccountPerformance> perf = factViewService
                .accountPerformance(slice).stream()
                .collect(Collectors.toMap(AccountPerformance::accountId, Function.identity(), (a, b) -> a));

        List<Position> out = new ArrayList<>();
        for (Account acc : accountMapper.findActiveByFamily(familyId)) {
            if (acc.getType().isLiability()) continue;            // 负债不进资产透视
            AccountPerformance p = perf.get(acc.getId());
            if (p == null || p.currentValue() == null) continue;  // 未填报 · 无现值
            // v1.20 · 账户组维值。零组态时 = 账户名 → 透视输出与 v1.19.16 逐字一致
            String groupValue = acctGroupValue.getOrDefault(acc.getId(), acc.getDisplayName());
            // 账户级期初市值 = 期末 − 较上期变化(momAmount)· 本期收益率分母;momAmount 缺则不可算
            BigDecimal openVal = p.momAmount() == null ? null : p.currentValue().subtract(p.momAmount());

            ProductCategory cat = productCategoryService.findByCode(acc.getProductCategoryCode()).orElse(null);
            String risk = riskLabel(acc.getRiskLevelOverride() != null
                    ? acc.getRiskLevelOverride()
                    : (cat == null ? null : cat.getRiskLevel()));
            String liquidity = liquidityLabel(acc, cat);
            String owner = acc.getPrimaryOwnerMemberId() == null ? "共同"
                    : memberName.getOrDefault(acc.getPrimaryOwnerMemberId(), "成员#" + acc.getPrimaryOwnerMemberId());
            String assetClass = assetClassLabel(acc);
            String platform = blankToNull(acc.getPlatformTag());
            String acctIndustry = nullIfEmpty(IndustryTag.labelOf(acc.getIndustryTag()));
            String purpose = nullIfEmpty(com.family.finance.domain.lens.PurposeTag.labelOf(acc.getPurposeTag()));
            String typeLabel = acc.getType().getLabel();

            boolean split = false;
            if (StockHoldingService.supportsHoldings(acc.getType())) {
                List<AccountValuationService.HoldingLine> lines = valuationService.perHoldingLines(acc);
                BigDecimal sumAcctCcy = lines.stream().map(AccountValuationService.HoldingLine::valueAcctCcy)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (!lines.isEmpty() && sumAcctCcy.signum() > 0 && p.currentValue().signum() > 0) {
                    // 统一 FX 因子:Σ头寸(本位币)≡ factview 账户现值,与仪表盘同源
                    BigDecimal factor = p.currentValue().divide(sumAcctCcy, MathContext.DECIMAL64);
                    for (AccountValuationService.HoldingLine line : lines) {
                        StockHolding h = line.holding();
                        BigDecimal valueBase = line.valueAcctCcy().multiply(factor).setScale(2, RoundingMode.HALF_EVEN);
                        BigDecimal costBase = line.costAcctCcy() == null ? null
                                : line.costAcctCcy().multiply(factor).setScale(2, RoundingMode.HALF_EVEN);
                        BigDecimal holdingCumPnl = costBase == null ? null : valueBase.subtract(costBase);
                        boolean cashRow = h.getValuationMode() != null && "CASH".equals(h.getValuationMode().name());
                        /* 券商/交易账户里的现金部分(2026-07-17 修遗漏):不是股票股权、不是高风险 ——
                           语义系统定死:现金活钱 · 货币基金/存款 · 低风险 · 灵活取用(打标页只读展示,不可改) */
                        // v1.5 · 穿透:非现金持仓若有持仓方向 → 按权重拆成多头寸(旭日/透视自然出真实分布)
                        java.util.List<com.family.finance.domain.penetration.HoldingAllocation> allocs =
                                cashRow ? null : allocMapper.findByHolding(h.getId());
                        if (allocs != null && !allocs.isEmpty()) {
                            /* v1.20 · 【余数归位】把逐份四舍五入的零头补回去。
                             *
                             * 权重本身是精确的(weight_bp 合计恒为 10000),但把一笔市值拆成 9~10 份、
                             * 每份各自 setScale(2) 之后,**求和会与原值差几分** ——
                             * 这正是 e2e「pivot 与 period_summary 总资产差 0.01」那条断言的根因:
                             * 两条路径没有共用求和逻辑,一条按拆分后的份额加、一条按账户原值加。
                             *
                             * 它是**跟数据分布走**的:有没有穿透拆分、拆几份、余数落在哪,都影响结果,
                             * 所以历史上时红时绿(见过 0.01 / 0.03 / 0.06),很容易被当成偶发而放过。
                             *
                             * 修法:把「原值 − 各份之和」的残差补给**份额最大**的那一份。
                             * 选最大份是为了让相对误差最小,也让补偿落在最不显眼的地方。 */
                            java.util.List<BigDecimal> splitVals = new ArrayList<>();
                            java.util.List<BigDecimal> splitCums = new ArrayList<>();
                            for (var al : allocs) {
                                BigDecimal w = BigDecimal.valueOf(al.getWeightBp())
                                        .divide(BigDecimal.valueOf(10000), MathContext.DECIMAL64);
                                splitVals.add(valueBase.multiply(w).setScale(2, RoundingMode.HALF_EVEN));
                                splitCums.add(holdingCumPnl == null ? null
                                        : holdingCumPnl.multiply(w).setScale(2, RoundingMode.HALF_EVEN));
                            }
                            residualToLargest(splitVals, valueBase);
                            residualToLargest(splitCums, holdingCumPnl);
                            int splitIdx = -1;
                            for (var al : allocs) {
                                splitIdx++;
                                BigDecimal vb = splitVals.get(splitIdx);
                                BigDecimal cumSplit = splitCums.get(splitIdx);
                                String acLbl = al.getAssetClass() != null ? nullIfEmpty(AssetClass.labelOf(al.getAssetClass()))
                                        : assetClassOf(h, assetClass);
                                String indLbl = "OTHER".equals(al.getKind()) ? "其他持仓"
                                        : nullIfEmpty(IndustryTag.labelOf(al.getIndustry()));
                                out.add(new Position(
                                        acc.getId(), h.getId(), h.getDisplayName(), acc.getDisplayName(), vb,
                                        typeLabel, risk, liquidity, acc.getCurrency(), owner,
                                        acLbl, platform,
                                        indLbl,
                                        regionLabel(h.getMarket()), purpose,
                                        custodyOf(acc, false, liquidity),
                                        p.latestPnl(), p.cumPnl(), p.netPrincipal(), cumSplit, openVal,
                                        groupValue));
                            }
                            continue;   // 已按方向拆,跳过单头寸
                        }
                        out.add(new Position(
                                acc.getId(), h.getId(), h.getDisplayName(), acc.getDisplayName(), valueBase,
                                typeLabel,
                                cashRow ? "低风险" : risk,
                                cashRow ? "灵活取用" : liquidity,
                                acc.getCurrency(), owner,
                                cashRow ? AssetClass.CASH_EQ.getLabel() : assetClassOf(h, assetClass),
                                platform,
                                cashRow ? IndustryTag.MONEY_CASH.getLabel() : nullIfEmpty(IndustryTag.labelOf(h.getIndustryTag())),
                                cashRow ? null : regionLabel(h.getMarket()),
                                purpose,
                                custodyOf(acc, cashRow, liquidity),
                                p.latestPnl(), p.cumPnl(), p.netPrincipal(), holdingCumPnl, openVal,
                                groupValue));
                    }
                    split = true;
                }
            }
            if (!split) {
                out.add(new Position(
                        acc.getId(), null, acc.getDisplayName(), acc.getDisplayName(), p.currentValue(),
                        typeLabel, risk, liquidity, acc.getCurrency(), owner,
                        assetClass, platform, acctIndustry, null, purpose,
                        custodyOf(acc, false, liquidity),
                        p.latestPnl(), p.cumPnl(), p.netPrincipal(), p.cumPnl(), openVal,
                        groupValue));
            }
        }
        return out;
    }

    // ---------- 标签口径 ----------

    /**
     * v1.19 · 托管形式标签(派生 · 零新增录入)。
     *
     * <p>{@code liquidity} 是已经算好的流动性标签 —— 用它而不是再查一次
     * {@code product_category},保证与「流动性」那一维<b>同源</b>:
     * 货基在流动性维度里是「灵活取用」,在托管形式里就必须是「随时可取」,不能两处判据分家。</p>
     */
    private static String custodyOf(Account acc, boolean cashRow, String liquidity) {
        boolean liquidTag = "灵活取用".equals(liquidity);
        return com.family.finance.domain.lens.CustodyForm.labelOf(acc.getType(), cashRow, liquidTag);
    }


    /** 风险等级 1-6 → 三档(与预览口径一致);未评级 → null(未分类) */
    static String riskLabel(Integer level) {
        if (level == null || level <= 0) return null;
        if (level <= 2) return "低风险";
        if (level <= 4) return "中风险";
        return "高风险";
    }

    private static String liquidityLabel(Account acc, ProductCategory cat) {
        var liq = acc.getLiquidity(cat == null ? null : cat.getLiquidityClass());
        return switch (liq) {
            case LIQUID -> "灵活取用";
            case SEMI_LIQUID -> "半灵活";
            case ILLIQUID -> "低流动";
            case NA -> null;
        };
    }

    /** v1.4 · 持仓级资产类型标(截图导入的基金逐支)· 空则回落账户级 */
    private static String assetClassOf(StockHolding h, String acctDefault) {
        if (h.getAssetClassTag() != null && !h.getAssetClassTag().isBlank()) {
            String lbl = AssetClass.labelOf(h.getAssetClassTag());
            if (lbl != null) return lbl;
        }
        return acctDefault;
    }

    private static String assetClassLabel(Account acc) {
        AssetClass c = AssetClass.fromName(acc.getAssetClass());
        if (c == null) c = AssetClass.defaultFor(acc.getType(), acc.getProductCategoryCode());
        return c == null ? null : c.getLabel();
    }

    static String regionLabel(Market m) {
        if (m == null) return null;
        return switch (m) {
            case CN -> "A股";
            case US -> "美股";
            case HK -> "港股";
            case CRYPTO -> "加密";
            case METAL -> "贵金属";
        };
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String nullIfEmpty(String s) { return s == null || s.isEmpty() ? null : s; }
}
