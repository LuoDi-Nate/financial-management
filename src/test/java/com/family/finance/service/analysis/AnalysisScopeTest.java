package com.family.finance.service.analysis;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.config.FamilyRuntimeConfig;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.repository.FamilyRuntimeConfigMapper;
import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.27 · 分析范围(PRD FR-820 ~ FR-826 · tech-design v1.27 选型二)。
 */
class AnalysisScopeTest {

    // ─── 账户 ───
    static Account acc(long id, String name, AccountType t, boolean marked, boolean archived) {
        Account a = new Account();
        a.setId(id); a.setFamilyId(1L); a.setType(t); a.setDisplayName(name); a.setCurrency("CNY");
        a.setAnalysisExcluded(marked);
        if (archived) a.setArchivedAt(java.time.LocalDateTime.now());
        return a;
    }

    static AccountPeriodFact fact(long accId, String name, AccountType t, long periodId, String end) {
        LocalDate ps = LocalDate.of(2026, 9, 1);
        BigDecimal e = new BigDecimal(end);
        BigDecimal z = BigDecimal.ZERO;
        AccountClass cls = t == AccountType.LOAN ? AccountClass.LIABILITY : AccountClass.ASSET;
        return new AccountPeriodFact(accId, name, t, cls, AccountLiquidity.NA, "CNY", null, 0,
                periodId, ps, ps, null, e, null, e, z, z, z, z, z, z, z, z, z, z, BigDecimal.ONE);
    }

    static FactSlice slice(AccountPeriodFact... rows) {
        FactFilter f = new FactFilter(1L, PeriodType.MONTHLY, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 1),
                false, null, "CNY");
        return new FactSlice(f, List.of(rows), List.of(9L), 9L);
    }

    /** issue 里的例子:自住房 1000 万 + 车 20 万 + 现金 30 / 股票 40 / 理财 30 万 */
    static List<Account> issueFamily(boolean markHouseAndCar) {
        return List.of(acc(1, "自住房", AccountType.PROPERTY, markHouseAndCar, false),
                acc(2, "家用车", AccountType.OTHER, markHouseAndCar, false),
                acc(3, "工资卡", AccountType.CASH, false, false),
                acc(4, "券商", AccountType.STOCK, false, false),
                acc(5, "理财", AccountType.WEALTH, false, false),
                acc(6, "房贷", AccountType.LOAN, false, false));
    }

    static FactSlice issueSlice() {
        return slice(fact(1, "自住房", AccountType.PROPERTY, 9, "10000000"),
                fact(2, "家用车", AccountType.OTHER, 9, "200000"),
                fact(3, "工资卡", AccountType.CASH, 9, "300000"),
                fact(4, "券商", AccountType.STOCK, 9, "400000"),
                fact(5, "理财", AccountType.WEALTH, 9, "300000"),
                fact(6, "房贷", AccountType.LOAN, 9, "-500000"));
    }

    @Test
    void adjustableScopeExcludesMarkedAndComputesShare() {
        var s = AnalysisScopeService.build(ScopeKind.ADJUSTABLE, issueFamily(true), issueSlice());
        assertThat(s.excludedIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(s.excludedNames()).containsExactly("自住房", "家用车");     // 按余额从大到小
        assertThat(s.excludedSharePct()).isEqualByComparingTo("91.1");         // 1020 / 1120
        assertThat(s.optionSubtitle()).isEqualTo("不含 自住房、家用车 · 占总资产 91%");
        assertThat(s.excludedLabel()).isEqualTo("自住房 · 家用车");
        assertThat(s.empty()).isFalse();
    }

    @Test
    void financialScopeExcludesPropertyAndOtherTypes() {
        var s = AnalysisScopeService.build(ScopeKind.FINANCIAL, issueFamily(false), issueSlice());
        assertThat(s.excludedIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(s.excludedTypes()).containsExactly("房产类", "其他类");
        assertThat(s.optionSubtitle()).startsWith("不含 房产类、其他类 · 占总资产");
    }

    /** FR-820 · 只显示与「全部资产」有区别的选项 */
    @Test
    void onlyDifferingOptionsAreAvailable() {
        var noMarks = issueFamily(false);
        assertThat(AnalysisScopeService.available(ScopeKind.ADJUSTABLE, noMarks)).isFalse();
        assertThat(AnalysisScopeService.available(ScopeKind.FINANCIAL, noMarks)).isTrue();
        var financialOnly = List.of(acc(3, "工资卡", AccountType.CASH, false, false),
                acc(4, "券商", AccountType.STOCK, false, false));
        assertThat(AnalysisScopeService.available(ScopeKind.FINANCIAL, financialOnly)).isFalse();
        assertThat(AnalysisScopeService.available(ScopeKind.ADJUSTABLE, financialOnly)).isFalse();
        // 归档的账户不算「家里有」;贷款标了也不算
        var archivedHouse = List.of(acc(1, "旧房", AccountType.PROPERTY, true, true),
                acc(6, "房贷", AccountType.LOAN, true, false));
        assertThat(AnalysisScopeService.available(ScopeKind.FINANCIAL, archivedHouse)).isFalse();
        assertThat(AnalysisScopeService.available(ScopeKind.ADJUSTABLE, archivedHouse)).isFalse();
    }

    /** FR-826 · 全都标了:范围为空,不许退回全部资产(PRD §9 ④) */
    @Test
    void everythingMarkedIsEmptyNotAll() {
        List<Account> all = List.of(acc(3, "工资卡", AccountType.CASH, true, false),
                acc(4, "券商", AccountType.STOCK, true, false));
        FactSlice full = slice(fact(3, "工资卡", AccountType.CASH, 9, "300000"),
                fact(4, "券商", AccountType.STOCK, 9, "400000"));
        var s = AnalysisScopeService.build(ScopeKind.ADJUSTABLE, all, full);
        assertThat(s.empty()).isTrue();
        assertThat(s.isAll()).isFalse();
        // 落到切片上是真的空 —— 不是「空列表 = 不筛选」
        assertThat(s.apply(full).rows()).isEmpty();
        assertThat(s.apply(full).lastPeriodId()).isEqualTo(9L);
    }

    @Test
    void excludingAccountsKeepsPeriodsAndDropsOnlyThoseRows() {
        FactSlice full = issueSlice();
        FactSlice cut = full.excludingAccounts(Set.of(1L, 2L));
        assertThat(cut.rows()).extracting(AccountPeriodFact::accountId).containsExactly(3L, 4L, 5L, 6L);
        assertThat(cut.periodIds()).isEqualTo(full.periodIds());
        assertThat(full.excludingAccounts(Set.of())).isSameAs(full);
    }

    /** FR-822 · 家庭默认:存过且仍可选 → 它;否则有标记 → 可调整,没有 → 全部 */
    @Test
    void familyDefaultFollowsSavedValueThenDerives() {
        Map<String, String> store = new HashMap<>();
        FamilyConfigService cfg = new FamilyConfigService(new FamilyRuntimeConfigMapper() {
            @Override public Long lockFamily(long familyId) { throw new UnsupportedOperationException(); }
            @Override public Optional<String> findValue(long familyId, String keyName) { return Optional.ofNullable(store.get(keyName)); }
            @Override public List<FamilyRuntimeConfig> findByFamily(long familyId) { return List.of(); }
            @Override public int upsert(long familyId, String keyName, String valueText) { store.put(keyName, valueText); return 1; }
        });
        var svc = new AnalysisScopeService(null, null, cfg);
        assertThat(svc.familyDefault(1L, issueFamily(false))).isEqualTo(ScopeKind.ALL);
        assertThat(svc.familyDefault(1L, issueFamily(true))).isEqualTo(ScopeKind.ADJUSTABLE);
        svc.setFamilyDefault(1L, ScopeKind.ALL);                        // 明确选了「全部」→ 不再被推导覆盖
        assertThat(svc.familyDefault(1L, issueFamily(true))).isEqualTo(ScopeKind.ALL);
        store.put(FamilyConfigService.K_ANALYSIS_SCOPE_DEFAULT, "ADJUSTABLE");
        assertThat(svc.familyDefault(1L, issueFamily(false))).isEqualTo(ScopeKind.ALL);   // 存的不可选 → 回落推导
    }

    @Test
    void fingerprintChangesWithMarkedSet() {
        var a = AnalysisScopeService.build(ScopeKind.ADJUSTABLE, issueFamily(true), issueSlice());
        var only = List.of(acc(1, "自住房", AccountType.PROPERTY, true, false), acc(2, "家用车", AccountType.OTHER, false, false),
                acc(3, "工资卡", AccountType.CASH, false, false));
        var b = AnalysisScopeService.build(ScopeKind.ADJUSTABLE, only, issueSlice());
        assertThat(a.fingerprint()).isNotEqualTo(b.fingerprint());
        assertThat(AnalysisScope.all().fingerprint()).isEqualTo("ALL:[]");
    }
}
