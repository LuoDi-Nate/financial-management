package com.family.finance.service.goal;

import com.family.finance.domain.macro.MacroBenchmark;
import com.family.finance.factview.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetirementRateSourcesTest {
    private final LocalDate today=LocalDate.of(2026,11,5);
    private final List<AccountPeriodFact> rows=new ArrayList<>();
    private BigDecimal bd(String s){return new BigDecimal(s);}
    private FactSlice history(int snapshots,String profit) {
        var slice=mock(FactSlice.class);var ids=new ArrayList<Long>();
        for(int i=0;i<snapshots;i++) {
            long id=i+1; ids.add(id);LocalDate start=today.withDayOfMonth(1).minusMonths(snapshots-i);
            when(slice.periodStartOf(id)).thenReturn(start);
            var value=row(id,1L,bd("100"),i==0?null:bd("100"),i==0?null:bd(profit));
            when(value.periodStart()).thenReturn(start);when(value.periodEnd()).thenReturn(start.withDayOfMonth(start.lengthOfMonth()));
            rows.add(value);
        }
        when(slice.closedPeriodIds()).thenReturn(ids);when(slice.periodIds()).thenReturn(ids);when(slice.rows()).thenReturn(rows);return slice;
    }
    private AccountPeriodFact row(long period,long account,BigDecimal end,BigDecimal previous,BigDecimal profit) {
        var r=mock(AccountPeriodFact.class);when(r.periodId()).thenReturn(period);when(r.accountId()).thenReturn(account);
        when(r.endBalanceBase()).thenReturn(end);when(r.previousEndBalanceBase()).thenReturn(previous);
        when(r.previousEndBalanceOrig()).thenReturn(previous);when(r.periodPnlBase()).thenReturn(profit);return r;
    }
    private RetirementRateSources.Rate rate(FactSlice slice) {
        return RetirementRateSources.investment(slice,Set.of(1L),today,id->new CashflowBreakdown(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),r->true);
    }
    @Test void twelveSnapshotsAreOnlyElevenIntervalsAndNeverAnAnnualReturn() {
        var r=rate(history(12,"1"));assertThat(r.percent()).isNull();assertThat(r.count()).isEqualTo(11);
    }
    @Test void thirteenSnapshotsProduceTheIndependentlyCompoundedAnnualReturn() {
        var r=rate(history(13,"1"));assertThat(r.count()).isEqualTo(12);
        assertThat(r.percent().doubleValue()).isCloseTo((Math.pow(1.01,12)-1)*100,within(0.00001));
        assertThat(r.from()).isEqualTo(LocalDate.of(2025,11,1));assertThat(r.to()).isEqualTo(LocalDate.of(2026,10,31));
    }
    @Test void longerHistoryAnnualizesAndCapsAtThirtySixIntervals() {
        var r=rate(history(40,"1"));assertThat(r.count()).isEqualTo(36);
        assertThat(r.percent().doubleValue()).isCloseTo((Math.pow(1.01,12)-1)*100,within(0.00001));
    }
    @Test void balanceJumpFromExternalCapitalIsNotInvestmentProfit() {
        var slice=history(13,"0");when(rows.getLast().endBalanceBase()).thenReturn(bd("9000000"));
        var r=rate(slice);assertThat(r.percent()).isZero();assertThat(r.samples().getLast().external()).isEqualByComparingTo("8999900");
    }
    @Test void firstRecordedAccountIsOpeningCapitalNotAProfitWindfall() {
        var slice=history(13,"0");rows.add(row(13,2,bd("3000000"),null,null));
        var r=RetirementRateSources.investment(slice,Set.of(1L,2L),today,id->new CashflowBreakdown(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),f->true);
        assertThat(r.percent()).isZero();assertThat(r.samples().getLast().external()).isEqualByComparingTo("3000000");
    }
    @Test void fxTranslationIsIncludedAlongsideLocalInvestmentProfit() {
        var slice=history(13,"0");when(rows.getLast().previousEndBalanceBase()).thenReturn(bd("110"));
        when(rows.getLast().endBalanceBase()).thenReturn(bd("110"));
        assertThat(rate(slice).percent()).isEqualByComparingTo("10");
    }
    @Test void historicalRatesRepriceOriginalAmountsWithoutChangingSharedCurrencyLens() {
        var original=history(13,"0");
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);when(row.endBalanceOrig()).thenReturn(bd("100"));when(row.endBalanceBase()).thenReturn(bd("800"));
            when(row.periodPnlOrig()).thenReturn(i==0?null:BigDecimal.ZERO);
        }
        var history=RetirementRateSources.historicalSlice(original,r->r.periodId()==13L?bd("8"):bd("7"));
        var result=rate(history);
        assertThat(result.count()).isEqualTo(12);
        assertThat(result.percent().doubleValue()).isCloseTo(100d/7,within(0.00001));
        assertThat(result.samples().getLast().startCapital()).isEqualByComparingTo("700");
        assertThat(result.samples().getLast().profit()).isEqualByComparingTo("100");
        assertThat(original.rows().getFirst().endBalanceBase()).isEqualByComparingTo("800");
    }
    @Test void unallocatedHouseholdCashflowCannotBeInventedForTheSelectedSubset() {
        var r=RetirementRateSources.investment(history(13,"1"),Set.of(1L),today,id->new CashflowBreakdown(bd("10"),BigDecimal.ZERO,bd("10")),f->true);
        assertThat(r.percent()).isNull();assertThat(r.problem()).contains("未分配到账户");
    }
    @Test void gapsMissingFxAndNonpositiveCapitalRemainMissing() {
        var slice=history(13,"1");when(slice.periodStartOf(12L)).thenReturn(LocalDate.of(2026,8,1));assertThat(rate(slice).percent()).isNull();
        assertThat(RetirementRateSources.investment(slice,Set.of(1L),today,id->new CashflowBreakdown(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO),f->false).percent()).isNull();
        when(rows.get(11).endBalanceBase()).thenReturn(BigDecimal.ZERO);assertThat(rate(slice).percent()).isNull();
    }
    @Test void emptySelectionDoesNotExpandToAllAccounts() {assertThat(RetirementRateSources.investment(history(13,"1"),Set.of(),today,id->null,f->true).percent()).isNull();}
    private List<MacroBenchmark> annuals() {
        var values=new ArrayList<MacroBenchmark>();
        for(int year=2016;year<=2025;year++){var b=new MacroBenchmark();b.setYear(year);b.setCpiHeadline(bd("2"));b.setSource("official-nbs");b.setVerifiedAt(LocalDateTime.of(2026,11,5,0,0));values.add(b);}return values;
    }
    @Test void onlyTenConsecutiveVerifiedFullYearsContributeToCpi() {
        var values=annuals();assertThat(RetirementRateSources.cpi(values,today).percent()).isEqualByComparingTo("2");
        values.get(4).setVerifiedAt(null);assertThat(RetirementRateSources.cpi(values,today).percent()).isNull();
    }
    @Test void cpiUsesGeometricMeanAndRejectsSeedAndFutureYear() {
        var values=annuals();values.getLast().setCpiHeadline(BigDecimal.ZERO);
        assertThat(RetirementRateSources.cpi(values,today).percent()).isEqualByComparingTo("1.80");
        values.getLast().setSource("seed");assertThat(RetirementRateSources.cpi(values,today).percent()).isNull();
        values.getLast().setSource("manual");values.getLast().setYear(2026);assertThat(RetirementRateSources.cpi(values,today).percent()).isNull();
    }
}
