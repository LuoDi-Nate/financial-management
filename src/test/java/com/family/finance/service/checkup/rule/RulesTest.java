package com.family.finance.service.checkup.rule;

import com.family.finance.calc.BenchmarkComparator;
import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.config.FamilyRuntimeConfig;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.repository.FamilyRuntimeConfigMapper;
import com.family.finance.service.checkup.AccountDiagnose;
import com.family.finance.service.checkup.FamilyDiagnose;
import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RulesTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** v0.4.18 · 测试桩 · DB 永远空 → 业务代码默认值生效 · 等价于 v0.4.17 之前的行为 */
    private static final FamilyConfigService STUB_CONFIG = new FamilyConfigService(new FamilyRuntimeConfigMapper() {
        @Override public Long lockFamily(long familyId) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> findValue(long familyId, String keyName) { return Optional.empty(); }
        @Override public List<FamilyRuntimeConfig> findByFamily(long familyId) { return List.of(); }
        @Override public int upsert(long familyId, String keyName, String valueText) { return 0; }
    });

    // ─── RET-1 长期负收益 ───
    @Test
    void ret1HitsWhenLongHoldNegativeReturn() {
        var d = diag(stock(), 12, "100000", "-0.15", null);
        assertThat(new AccountRules.Ret1LongTermLoss().evaluate(accCtx(d, family())))
                .isPresent().get().extracting(Advice::severity).isEqualTo(Advice.Severity.DANGER);
    }

    @Test
    void ret1MissesWhenReturnAboveThreshold() {
        var d = diag(stock(), 12, "100000", "-0.05", null);
        assertThat(new AccountRules.Ret1LongTermLoss().evaluate(accCtx(d, family()))).isEmpty();
    }

    @Test
    void ret1MissesForCashAccount() {
        var d = diag(cash(), 12, "100000", "-0.5", null);
        assertThat(new AccountRules.Ret1LongTermLoss().evaluate(accCtx(d, family()))).isEmpty();
    }

    @Test
    void ret2HitsWhenOutperformBy3Plus() {
        var b = compared("8.00", "12.00", "4.00");
        var d = diag(stock(), 12, "100000", "0.12", b);
        assertThat(new AccountRules.Ret2OutperformBenchmark().evaluate(accCtx(d, family())))
                .isPresent().get().extracting(Advice::severity).isEqualTo(Advice.Severity.OK);
    }

    @Test
    void ret2MissesWhenDiffSmall() {
        var b = compared("8.00", "9.50", "1.50");
        var d = diag(stock(), 12, "100000", "0.095", b);
        assertThat(new AccountRules.Ret2OutperformBenchmark().evaluate(accCtx(d, family()))).isEmpty();
    }

    @Test
    void ret3HitsWhenUnderperformBy3Plus() {
        var b = compared("8.00", "4.00", "-4.00");
        var d = diag(stock(), 12, "100000", "0.04", b);
        assertThat(new AccountRules.Ret3UnderperformBenchmark().evaluate(accCtx(d, family())))
                .isPresent().get().extracting(Advice::severity).isEqualTo(Advice.Severity.WARN);
    }

    @Test
    void risk1HitsWhenAccountIsOver30PctOfTotal() {
        var d = diag(stock(), 12, "400000", null, null);
        var f = familyWithTotal("1000000");
        var hit = new AccountRules.Risk1SingleAccountOverlimit().evaluate(accCtx(d, f));
        assertThat(hit).isPresent();
        assertThat(hit.get().severity()).isEqualTo(Advice.Severity.DANGER);
        assertThat(hit.get().rawTitle()).contains("40%");
    }

    @Test
    void risk1MissesWhenUnder30Pct() {
        var d = diag(stock(), 12, "100000", null, null);
        var f = familyWithTotal("1000000");
        assertThat(new AccountRules.Risk1SingleAccountOverlimit().evaluate(accCtx(d, f))).isEmpty();
    }

    @Test
    void prg1HitsForLoanWithRepayment() {
        var d = diagLoan(new BigDecimal("-50000"), new BigDecimal("8000"));
        assertThat(new AccountRules.Prg1Paydown().evaluate(accCtx(d, family())))
                .isPresent().get().extracting(Advice::severity).isEqualTo(Advice.Severity.OK);
    }

    @Test
    void prg2HitsForLoanNoPayment() {
        var d = diagLoan(new BigDecimal("-50000"), ZERO);
        assertThat(new AccountRules.Prg2NoPaydown().evaluate(accCtx(d, family())))
                .isPresent().get().extracting(Advice::severity).isEqualTo(Advice.Severity.WARN);
    }

    @Test
    void liq1HitsForCashLessThanHalfMonth() {
        var d = diag(cash(), 6, "2000", null, null);
        var ctx = new RuleContext(d, family(), List.of(), new BigDecimal("8000"));
        assertThat(new AccountRules.Liq1CashTooThin().evaluate(ctx)).isPresent();
    }

    @Test
    void liq1MissesForAdequateBalance() {
        var d = diag(cash(), 6, "50000", null, null);
        var ctx = new RuleContext(d, family(), List.of(), new BigDecimal("8000"));
        assertThat(new AccountRules.Liq1CashTooThin().evaluate(ctx)).isEmpty();
    }

    @Test
    void liq2HitsForCashOver12Months() {
        var d = diag(cash(), 6, "150000", null, null);
        var ctx = new RuleContext(d, family(), List.of(), new BigDecimal("10000"));
        assertThat(new AccountRules.Liq2CashIdleTooLong().evaluate(ctx)).isPresent();
    }

    @Test
    void famLiq1HitsForLowEmergency() {
        var f = familyWithEmergency(new BigDecimal("1.5"));
        assertThat(new FamilyRules.FamLiq1EmergencyShort().evaluate(RuleContext.forFamily(f, List.of(), null)))
                .isPresent().get().extracting(Advice::severity).isEqualTo(Advice.Severity.DANGER);
    }

    @Test
    void famLiq1MissesWhenBufferAdequate() {
        var f = familyWithEmergency(new BigDecimal("4.5"));
        assertThat(new FamilyRules.FamLiq1EmergencyShort().evaluate(RuleContext.forFamily(f, List.of(), null))).isEmpty();
    }

    @Test
    void famCon1HitsForOverweightType() {
        var slices = List.of(
                slice("STOCK", "股票\n(STOCK)", "600000", "0.60"),
                slice("CASH", "现金\n(CASH)", "400000", "0.40"));
        var f = new FamilyDiagnose(zeroKpi(), slices, List.of(), null, null, null, null, null, 2, 0);
        assertThat(new FamilyRules.FamCon1TypeOverweight().evaluate(RuleContext.forFamily(f, List.of(), null)))
                .isPresent().get().extracting(Advice::severity).isEqualTo(Advice.Severity.WARN);
    }

    @Test
    void famCon2HitsForSingleType() {
        var slices = List.of(slice("CASH", "现金", "500000", "1.00"));
        var f = new FamilyDiagnose(zeroKpi(), slices, List.of(), null, null, null, null, null, 1, 0);
        assertThat(new FamilyRules.FamCon2SingleType().evaluate(RuleContext.forFamily(f, List.of(), null)))
                .isPresent();
    }

    @Test
    void famRisk1HitsForHighRiskOverweight() {
        var buckets = List.of(
                new FamilyDiagnose.RiskBucket(5, "中高", new BigDecimal("450000"), new BigDecimal("0.45")),
                new FamilyDiagnose.RiskBucket(2, "低", new BigDecimal("550000"), new BigDecimal("0.55")));
        var f = new FamilyDiagnose(zeroKpi(), List.of(), buckets, null, null, null, null, null, 2, 0);
        assertThat(new FamilyRules.FamRisk1HighRiskOver40(STUB_CONFIG).evaluate(RuleContext.forFamily(f, List.of(), null)))
                .isPresent();
    }

    @Test
    void famAlc1HitsForBalancedAllocation() {
        var slices = List.of(
                slice("STOCK", "股票", "300000", "0.30"),
                slice("WEALTH", "理财", "400000", "0.40"),
                slice("CASH", "现金", "300000", "0.30"));
        var f = new FamilyDiagnose(zeroKpi(), slices, List.of(), null, null, null, null, null, 3, 0);
        assertThat(new FamilyRules.FamAlc1HealthyAllocation(STUB_CONFIG).evaluate(RuleContext.forFamily(f, List.of(), null)))
                .isPresent().get().extracting(Advice::severity).isEqualTo(Advice.Severity.OK);
    }

    // ─── helpers ───
    private static AccountDiagnose diag(Account a, int monthsHeld, String balance,
                                        String annualized, BenchmarkComparator.Result b) {
        return new AccountDiagnose(
                a, null,
                balance == null ? null : new BigDecimal(balance),
                null, null, monthsHeld,
                ZERO, ZERO, ZERO, ZERO, ZERO, ZERO,
                annualized == null ? null : new BigDecimal(annualized),
                null,
                b == null ? BenchmarkComparator.Result.noBenchmark() : b,
                4, false, List.of()
        );
    }

    private static AccountDiagnose diagLoan(BigDecimal balance, BigDecimal repayIn) {
        Account a = new Account();
        a.setId(99L); a.setFamilyId(1L); a.setType(AccountType.LOAN);
        a.setCurrency("CNY"); a.setDisplayName("贷款");
        return new AccountDiagnose(
                a, null, balance, balance, ZERO, 12,
                ZERO, ZERO, repayIn, ZERO, ZERO, ZERO, null, null,
                BenchmarkComparator.Result.noBenchmark(), 0, false, List.of());
    }

    private static Account stock() {
        Account a = new Account();
        a.setId(11L); a.setFamilyId(1L); a.setType(AccountType.STOCK);
        a.setCurrency("CNY"); a.setDisplayName("股票账户");
        return a;
    }

    private static Account cash() {
        Account a = new Account();
        a.setId(22L); a.setFamilyId(1L); a.setType(AccountType.CASH);
        a.setCurrency("CNY"); a.setDisplayName("活期");
        return a;
    }

    private static FamilyDiagnose family() {
        return new FamilyDiagnose(zeroKpi(), List.of(), List.of(), null, null, null, null, null, 1, 0);
    }

    private static FamilyDiagnose familyWithTotal(String total) {
        var k = new KpiSnapshot(new BigDecimal(total), new BigDecimal(total), ZERO, null, null, null, null);
        return new FamilyDiagnose(k, List.of(), List.of(), null, null, null, null, null, 1, 0);
    }

    private static FamilyDiagnose familyWithEmergency(BigDecimal months) {
        var k = new KpiSnapshot(ZERO, ZERO, ZERO, months, null, null, null);
        return new FamilyDiagnose(k, List.of(), List.of(), null, months, null, null, null, 1, 0);
    }

    private static KpiSnapshot zeroKpi() {
        return new KpiSnapshot(ZERO, ZERO, ZERO, null, null, null, null);
    }

    private static RuleContext accCtx(AccountDiagnose d, FamilyDiagnose f) {
        return new RuleContext(d, f, List.of(), new BigDecimal("8000"));
    }

    private static AllocationSlice slice(String type, String label, String value, String ratio) {
        return new AllocationSlice(type, label, new BigDecimal(value), new BigDecimal(ratio));
    }

    private static BenchmarkComparator.Result compared(String bench, String acct, String diff) {
        return new BenchmarkComparator.Result(
                BenchmarkComparator.Status.COMPARED,
                new BigDecimal(bench), new BigDecimal(acct), new BigDecimal(diff));
    }
}
