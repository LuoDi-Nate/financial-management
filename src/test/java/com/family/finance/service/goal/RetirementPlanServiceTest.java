package com.family.finance.service.goal;

import com.family.finance.domain.goal.*;
import com.family.finance.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetirementPlanServiceTest {
    private final RetirementPlanMapper mapper = mock(RetirementPlanMapper.class);
    private final GoalMapper goalMapper = mock(GoalMapper.class);
    private final GoalService goals = mock(GoalService.class);
    private final RetirementDataService data = mock(RetirementDataService.class);
    private final RetirementPlanService service = new RetirementPlanService(mapper, goalMapper, mock(FamilyMapper.class), goals,
            data, new ObjectMapper().findAndRegisterModules());

    private RetirementPlan plan() {
        var p = new RetirementPlan(); p.setBaseCurrency("CNY"); p.setPriceDate(LocalDate.now()); p.setTargetMonth(YearMonth.now().plusYears(10).toString());
        p.setMonthlyExpense(new BigDecimal("10000")); p.setMonthlyContribution(BigDecimal.ZERO);
        p.setChildReserve(BigDecimal.ZERO); p.setMedicalReserve(BigDecimal.ZERO); p.setHousingReserve(BigDecimal.ZERO); p.setOtherReserve(BigDecimal.ZERO);
        p.setBudgetConfirmed(true); p.setReservesConfirmed(true); p.setNoOverlapConfirmed(true); p.setAssetsConfirmed(true);
        p.setIncomeConfirmed(true); p.setContributionConfirmed(true); return p;
    }
    private Goal legacy() {
        var g = Goal.builder().id(9L).familyId(1L).goalType(GoalType.RETIREMENT).name("original")
                .paramsJson("{\"retire_age\":60,\"current_age\":35}").targetValue(new BigDecimal("123456"))
                .targetDate(LocalDate.of(2040,1,1)).build();
        when(goals.require(1,9)).thenReturn(g); when(mapper.find(1,9)).thenReturn(Optional.empty());
        when(data.load(eq(1L), any(), any())).thenReturn(new RetirementDataService.Data("CNY",LocalDate.now(),List.of(),List.of(),
                new BigDecimal("3000000"),BigDecimal.ZERO,null,null,"test",List.of(),List.of(),"CPI参考"));
        when(mapper.save(eq(1L),eq(9L),anyString(),eq(true),eq(false))).thenReturn(1);
        return g;
    }
    private void mockRateSources(String investmentPercent, String inflationPercent) {
        var from=LocalDate.now().minusYears(1);var to=LocalDate.now().withDayOfMonth(1).minusDays(1);
        var investment=investmentPercent==null ? RetirementRateSources.Rate.missing("历史样本不足")
                : new RetirementRateSources.Rate(new BigDecimal(investmentPercent),from,to,List.of(),null,"已通过来源校验的历史年回报");
        var inflation=inflationPercent==null ? RetirementRateSources.Rate.missing("无已核验CPI")
                : new RetirementRateSources.Rate(new BigDecimal(inflationPercent),from,to,List.of(),null,"已核验年度CPI");
        var sources=new RetirementDataService.Sources(new BigDecimal("3000000"),java.util.Set.of(),BigDecimal.ONE,
                null,null,null,null,List.of(),investment,inflation);
        when(data.load(eq(1L),any(),any())).thenReturn(new RetirementDataService.Data("CNY",LocalDate.now(),List.of(),List.of(),
                new BigDecimal("3000000"),BigDecimal.ZERO,null,null,"rates",List.of(),List.of(),"CPI参考",sources));
    }
    @Test void adoptionRequiresExplicitConfirmationAndDoesNotChangeLegacyRow() {
        var g = legacy(); var p = plan();
        assertThatThrownBy(() -> service.save(1,9L,p,false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("比较");
        service.save(1,9L,p,true);
        assertThat(g.getParamsJson()).isEqualTo("{\"retire_age\":60,\"current_age\":35}");
        assertThat(g.getTargetDate()).isEqualTo(LocalDate.of(2040,1,1));
        assertThat(g.getTargetValue()).isEqualByComparingTo("123456");
        verify(goalMapper,never()).update(any());
        when(mapper.find(1,9)).thenReturn(Optional.of(new RetirementPlanMapper.Row(9,1,service.encode(p),true,false,null)));
        p.setBaseCurrency("USD");
        assertThatThrownBy(() -> service.save(1,9L,p,true)).hasMessageContaining("不能将旧金额直接当作新币种");
    }
    @Test void revertOnlyDeactivatesPlanWithoutReconstructingLegacyParameters() {
        legacy(); when(mapper.find(1,9)).thenReturn(Optional.of(new RetirementPlanMapper.Row(9,1,"{}",true,false,null)));
        service.revert(1,9); verify(mapper).deactivate(1,9); verify(goalMapper,never()).update(any());
    }
    @Test void nativeNewGoalCannotPretendToHaveLegacySnapshot() {
        legacy(); when(mapper.find(1,9)).thenReturn(Optional.of(new RetirementPlanMapper.Row(9,1,"{}",true,true,null)));
        assertThatThrownBy(() -> service.revert(1,9)).hasMessageContaining("没有旧算法");
        verify(mapper,never()).deactivate(anyLong(),anyLong());
    }
    @Test void configurationAllowsZeroAndNegativeContributionButRejectsMissingReserve() {
        var p = plan(); p.setMonthlyContribution(new BigDecimal("-100")); assertThatCode(() -> service.validate(p)).doesNotThrowAnyException();
        p.setMedicalReserve(null); assertThatThrownBy(() -> service.validate(p)).hasMessageContaining("医疗预留");
    }
    @Test void priceRebasePreservesFixedNominalContributionAndChangesIndexedAmounts() {
        var p = plan(); p.setPriceDate(LocalDate.now().minusYears(1)); p.setMonthlyContribution(new BigDecimal("1000"));
        service.rebase(p, LocalDate.now());
        assertThat(p.getMonthlyExpense()).isGreaterThan(new BigDecimal("10000"));
        assertThat(p.getMonthlyContribution()).isEqualByComparingTo("1000");
        assertThat(p.getPriceDate()).isEqualTo(LocalDate.now());
    }
    @Test void plansAreForwardCompatibleAndOtherGoalsNeverLoadRetirementState() {
        assertThat(service.parse("{\"version\":1,\"future_field\":true}").getVersion()).isEqualTo(1);
        assertThat(service.active(1,Goal.builder().id(8L).goalType(GoalType.EDUCATION).build())).isEmpty();
        verifyNoInteractions(mapper,data);
    }
    @Test void planLookupIsBatchedWithinOneRequestAndRefreshedNextRequest() {
        var goal = Goal.builder().id(9L).goalType(GoalType.RETIREMENT).build();
        try {
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
            service.active(1,goal); service.active(1,goal);
            verify(mapper,times(1)).findAll(1);
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(new org.springframework.mock.web.MockHttpServletRequest()));
            service.active(1,goal); verify(mapper,times(2)).findAll(1);
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }
    @Test void rebasedEditInputsProduceExactlyTheSamePublishedResult() {
        var p = plan(); p.setPriceDate(LocalDate.now().minusYears(1)); p.setMonthlyExpense(new BigDecimal("10000.37"));
        p.setMonthlyIncome(new BigDecimal("123.45")); p.setChildReserve(new BigDecimal("400000.37"));
        p.setMedicalReserve(new BigDecimal("30000.42")); p.setHousingReserve(new BigDecimal("3.22"));
        p.setOtherReserve(new BigDecimal("1000.61"));
        var before = com.family.finance.calc.RetirementCalculator.calculate(p,LocalDate.now(),new BigDecimal("6000000"),BigDecimal.ZERO,null,List.of());
        service.rebase(p,LocalDate.now());
        var after = com.family.finance.calc.RetirementCalculator.calculate(p,LocalDate.now(),new BigDecimal("6000000"),BigDecimal.ZERO,null,List.of());
        assertThat(after.target()).isEqualByComparingTo(before.target());
        assertThat(after.principal()).isEqualByComparingTo(before.principal());
        assertThat(after.coverage()).isEqualByComparingTo(before.coverage());
    }
    @Test void revisionTwoKeepsBasicThresholdIndependentOfOptionalContributionAndOldIncome() {
        legacy();var p=plan();p.setVersion(2);p.setMonthlyIncome(new BigDecimal("9999"));p.setMonthlyContribution(null);
        p.setDirectRealRate(true);p.setContributionConfirmed(false);p.setBudgetConfirmed(false);p.setReservesConfirmed(false);p.setAssetsConfirmed(false);p.setIncomeConfirmed(false);
        var view=service.evaluate(1,p,null,false,false);
        assertThat(view.result().target()).isEqualByComparingTo("4800000");assertThat(view.result().monthlyIncome()).isZero();assertThat(view.result().problems()).isEmpty();
        assertThat(view.result().scenarios()).isEmpty();assertThatCode(()->service.validate(p)).doesNotThrowAnyException();
        p.setSimulateFuture(true);p.setMonthlyContribution(new BigDecimal("10000"));
        var projected=service.evaluate(1,p,null,false,false).result();
        assertThat(projected.target()).isEqualByComparingTo(view.result().target());assertThat(projected.scenarios()).hasSize(1);
    }
    @Test void explicitUpgradePreservesCompleteRevisionOneAndRequiresSeparateConfirmation() {
        legacy();var old=plan();old.setMonthlyIncome(new BigDecimal("3000"));String raw=service.encode(old);
        when(mapper.find(1,9)).thenReturn(Optional.of(new RetirementPlanMapper.Row(9,1,raw,true,false,null)));
        var revised=service.draft(1,goals.require(1,9));assertThat(revised.getVersion()).isEqualTo(2);assertThat(revised.getMonthlyIncome()).isZero();
        assertThat(service.active(1,goals.require(1,9)).orElseThrow().result().monthlyIncome()).isEqualByComparingTo("3000");
        assertThatThrownBy(()->service.save(1,9L,revised,true,false,null)).hasMessageContaining("确认升级");
        service.save(1,9L,revised,true,true,null);assertThat(revised.getPreviousRevisionJson()).isEqualTo(raw);
        when(mapper.find(1,9)).thenReturn(Optional.of(new RetirementPlanMapper.Row(9,1,service.encode(revised),true,false,null)));
        service.restorePrevious(1,9,1L);
        verify(mapper).save(1,9,raw,true,false);
    }
    @Test void automaticReturnUsesPlanningAssumptionWithoutChangingStoredChoices() {
        legacy();var p=plan();p.setVersion(2);p.setReturnSource("AUTO");p.setInflationSource("AUTO");
        p.setRealReturnPct(new BigDecimal("1.25"));String storedBefore=service.encode(p);
        var view=service.evaluate(1,p,null,false,false);
        assertThat(view.effective().isDirectRealRate()).isTrue();assertThat(view.effective().getRealReturnPct()).isEqualByComparingTo("2.5");
        assertThat(view.effective().getNominalReturnPct()).isNull();assertThat(view.effective().getInflationPct()).isNull();
        assertThat(view.result().realRate()).isEqualByComparingTo("0.025");assertThat(view.result().target()).isEqualByComparingTo("4800000");
        assertThat(view.result().assets()).isEqualByComparingTo("3000000");assertThat(view.result().coveragePct()).isEqualByComparingTo("62.5");
        assertThat(view.result().ready()).isTrue();assertThat(view.configurationComplete()).isTrue();
        assertThat(view.usesPlanningReturn()).isTrue();assertThat(view.returnBasis()).contains("规划假设","小Lin","2.5%");
        assertThat(service.encode(p)).isEqualTo(storedBefore);
        assertThat(p.getNominalReturnPct()).isEqualByComparingTo("4.5");assertThat(p.getInflationPct()).isEqualByComparingTo("2");
        assertThatCode(()->service.save(1,9L,p,true)).doesNotThrowAnyException();
        var savedJson=ArgumentCaptor.forClass(String.class);
        verify(mapper).save(eq(1L),eq(9L),savedJson.capture(),eq(true),eq(false));
        var saved=service.parse(savedJson.getValue());
        assertThat(saved.getReturnSource()).isEqualTo("AUTO");assertThat(saved.getInflationSource()).isEqualTo("AUTO");
        assertThat(saved.isDirectRealRate()).isFalse();assertThat(saved.getRealReturnPct()).isEqualByComparingTo("1.25");
        assertThat(saved.getNominalReturnPct()).isEqualByComparingTo("4.5");assertThat(saved.getInflationPct()).isEqualByComparingTo("2");
        var reevaluated=service.evaluate(1,saved,null,false,true);
        assertThat(reevaluated.result().target()).isEqualByComparingTo("4800000");assertThat(reevaluated.usesPlanningReturn()).isTrue();
    }
    @Test void availableZeroAndNegativeHistoricalReturnsNeverUsePlanningAssumption() {
        legacy();var p=plan();p.setVersion(2);p.setReturnSource("AUTO");p.setInflationSource("AUTO");
        p.setDirectRealRate(true);p.setRealReturnPct(new BigDecimal("7"));
        for(String percent:List.of("0","-1")) {
            mockRateSources(percent,"0");
            var view=service.evaluate(1,p,null,false,true);
            assertThat(view.usesPlanningReturn()).isFalse();assertThat(view.returnBasis()).doesNotContain("规划假设");
            assertThat(view.effective().isDirectRealRate()).isFalse();
            assertThat(view.effective().getNominalReturnPct()).isEqualByComparingTo(percent);
            assertThat(view.result().realRate()).isEqualByComparingTo(new BigDecimal(percent).movePointLeft(2));
            assertThat(view.result().state()).isEqualTo(com.family.finance.calc.RetirementCalculator.State.NO_FINITE_TARGET);
            assertThat(view.result().target()).isNull();assertThat(view.result().coverage()).isNull();
        }
        assertThat(p.getReturnSource()).isEqualTo("AUTO");assertThat(p.isDirectRealRate()).isTrue();
        assertThat(p.getRealReturnPct()).isEqualByComparingTo("7");assertThat(p.getNominalReturnPct()).isEqualByComparingTo("4.5");
    }
    @Test void availablePositiveHistoryTakesPrecedenceEvenWhenInflationIsMissing() {
        legacy();var p=plan();p.setVersion(2);p.setReturnSource("AUTO");p.setInflationSource("AUTO");
        mockRateSources("8","2");
        var known=service.evaluate(1,p,null,false,true);
        assertThat(known.usesPlanningReturn()).isFalse();assertThat(known.returnBasis()).doesNotContain("规划假设");
        assertThat(known.effective().isDirectRealRate()).isFalse();assertThat(known.effective().getNominalReturnPct()).isEqualByComparingTo("8");
        assertThat(known.result().target()).isEqualByComparingTo("2040000");assertThat(known.result().ready()).isTrue();
        mockRateSources("8",null);
        var missingInflation=service.evaluate(1,p,null,false,true);
        assertThat(missingInflation.usesPlanningReturn()).isFalse();assertThat(missingInflation.effective().isDirectRealRate()).isFalse();
        assertThat(missingInflation.effective().getNominalReturnPct()).isEqualByComparingTo("8");
        assertThat(missingInflation.effective().getInflationPct()).isNull();assertThat(missingInflation.result().realRate()).isNull();
        assertThat(missingInflation.result().target()).isNull();
        assertThat(p.getNominalReturnPct()).isEqualByComparingTo("4.5");assertThat(p.getInflationPct()).isEqualByComparingTo("2");
    }
    @Test void planningReturnDerivesNominalFromAvailableInflationAndLabelsItsSource() {
        legacy();var p=plan();p.setVersion(2);p.setReturnSource("AUTO");p.setInflationSource("AUTO");
        mockRateSources(null,"3");
        var view=service.evaluate(1,p,null,false,true);
        assertThat(view.usesPlanningReturn()).isTrue();assertThat(view.returnBasis()).contains("规划假设","小Lin","2.5%");
        assertThat(view.data().sources().investment().percent()).isNull();
        assertThat(view.effective().isDirectRealRate()).isTrue();assertThat(view.effective().getRealReturnPct()).isEqualByComparingTo("2.5");
        assertThat(view.effective().getInflationPct()).isEqualByComparingTo("3");
        assertThat(view.effective().getNominalReturnPct()).isEqualByComparingTo("5.575");
        assertThat(view.effective().getMiddleReturnPct()).isEqualByComparingTo("5.575");
        assertThat(view.result().target()).isEqualByComparingTo("4800000");assertThat(view.result().coveragePct()).isEqualByComparingTo("62.5");
        assertThat(p.getReturnSource()).isEqualTo("AUTO");assertThat(p.getInflationSource()).isEqualTo("AUTO");
        assertThat(p.getNominalReturnPct()).isEqualByComparingTo("4.5");assertThat(p.getInflationPct()).isEqualByComparingTo("2");
        p.setReturnSource("MANUAL");
        var manual=service.evaluate(1,p,null,false,true);
        assertThat(manual.usesPlanningReturn()).isFalse();assertThat(manual.effective().isDirectRealRate()).isFalse();
        assertThat(manual.effective().getNominalReturnPct()).isEqualByComparingTo("4.5");
    }
    @Test void unknownAccountDoesNotHideRecordedTotalOrBlockSavingSelection() {
        legacy();var p=plan();p.setVersion(2);p.getAccountIds().addAll(List.of(12L,13L));
        String reference="合成基金:尚未登记余额,当前按已有账面金额汇总";
        var asset=new RetirementDataService.Asset(12L,"合成基金",com.family.finance.domain.account.AccountType.FUND,
                "CNY",null,null,BigDecimal.ONE,null,true,true,false,"可选择用于退休");
        var known=new RetirementDataService.Asset(13L,"合成现金",com.family.finance.domain.account.AccountType.CASH,
                "CNY",new BigDecimal("3000000"),new BigDecimal("3000000"),BigDecimal.ONE,null,true,true,false,"可选择用于退休");
        when(data.load(eq(1L),any(),any())).thenReturn(new RetirementDataService.Data("CNY",LocalDate.now(),List.of(asset,known),List.of(),
                new BigDecimal("3000000"),BigDecimal.ZERO,null,null,"partial",List.of(reference),List.of(),"CPI参考"));
        assertThatCode(()->service.save(1,9L,p,true)).doesNotThrowAnyException();
        var view=service.evaluate(1,p,null,false,true);var result=view.result();
        assertThat(result.assets()).isEqualByComparingTo("3000000");assertThat(result.debt()).isZero();
        assertThat(result.target()).isEqualByComparingTo("4896000");
        assertThat(result.totalAssetNeed()).isEqualByComparingTo("4896000");
        assertThat(result.gap()).isEqualByComparingTo("1896000");assertThat(result.coveragePct()).isEqualByComparingTo("61.27");
        assertThat(result.ready()).isTrue();assertThat(view.configurationComplete()).isTrue();
        assertThat(view.data().assets().getFirst().original()).isNull();assertThat(view.data().assets().getFirst().amount()).isNull();
        assertThat(view.data().selectedAssets()).isEqualByComparingTo("3000000");
        assertThat(result.problems()).contains(reference);assertThat(view.dataReferences()).contains(reference);
        p.getAccountIds().add(999L);
        assertThatThrownBy(()->service.save(1,9L,p,true)).hasMessageContaining("不属于本家庭");
    }
    @Test void cityPreviewShowsKnownAmountsBeforeChildrenAndHousingAreComplete() {
        legacy();var p=plan();p.setVersion(2);p.setReserveMode("CITY");p.setCityTier("SECOND");
        var traced=new RetirementDataService.Sources(null,java.util.Set.of(),BigDecimal.ONE,null,null,null,null,List.of(),
                RetirementRateSources.Rate.missing("无历史"),RetirementRateSources.Rate.missing("无CPI"));
        when(data.load(eq(1L),any(),any())).thenReturn(new RetirementDataService.Data("CNY",LocalDate.now(),List.of(),List.of(),
                BigDecimal.ZERO,BigDecimal.ZERO,null,null,"partial",List.of(),List.of(),"CPI参考",traced));
        var view=service.evaluate(1,p,null,false,false);
        assertThat(view.effective().getMedicalReserve()).isEqualByComparingTo("200000");
        assertThat(view.effective().getOtherReserve()).isEqualByComparingTo("60000");
        assertThat(view.effective().getChildReserve()).isNull();assertThat(view.effective().getHousingReserve()).isNull();
        assertThat(view.result().reserves()).isNull();
        p.setDependentCount(0);p.setHousingPlan("SETTLED");
        assertThat(service.evaluate(1,p,null,false,false).result().reserves()).isEqualByComparingTo("260000");
    }
    @Test void cityModeRequiresExplicitChildrenAndHousingInsteadOfAssumingZero() {
        var p=plan();p.setVersion(2);p.setReserveMode("CITY");p.setCityTier("SECOND");
        assertThatThrownBy(()->service.validate(p)).hasMessageContaining("子女人数");
        p.setDependentCount(0);assertThatThrownBy(()->service.validate(p)).hasMessageContaining("住房安排");
        p.setHousingPlan("SETTLED");assertThatCode(()->service.validate(p)).doesNotThrowAnyException();
        p.setDependentCount(1);p.setChildPlanningYears(List.of(31));assertThatThrownBy(()->service.validate(p)).hasMessageContaining("剩余规划年数");
    }
    @Test void configurationCompletenessDependsOnChoicesRatherThanAutomaticSourceAvailability() {
        legacy();var p=plan();p.setVersion(2);p.setReturnSource("AUTO");p.setInflationSource("AUTO");p.setAutoExpense(true);
        var automatic=service.evaluate(1,p,null,false,true);
        assertThat(automatic.configurationComplete()).isTrue();
        assertThat(automatic.result().target()).isNull();
        p.setReturnSource("MANUAL");p.setNominalReturnPct(null);
        var manual=service.evaluate(1,p,null,false,true);
        assertThat(manual.configurationComplete()).isFalse();
        assertThat(manual.configurationProblems()).anyMatch(s->s.contains("回报率"));
        assertThatThrownBy(()->service.save(1,9L,p,true)).hasMessageContaining("回报率");
        p.setReturnSource("AUTO");p.setSimulateFuture(true);p.setContributionSource("AUTO");
        assertThat(service.evaluate(1,p,null,false,true).configurationComplete()).isTrue();
    }
    @Test void unrecordedDebtKeepsRecordedTotalsAndDataReference() {
        legacy();var p=plan();p.setVersion(2);p.getAccountIds().add(13L);
        String reference="合成贷款:尚未登记余额,当前按已有账面金额汇总";
        var loan=new RetirementDataService.Asset(12L,"合成贷款",com.family.finance.domain.account.AccountType.LOAN,
                "CNY",null,null,BigDecimal.ONE,null,false,false,true,"全家庭负债");
        var known=new RetirementDataService.Asset(13L,"合成现金",com.family.finance.domain.account.AccountType.CASH,
                "CNY",new BigDecimal("3000000"),new BigDecimal("3000000"),BigDecimal.ONE,null,true,true,false,"可选择用于退休");
        when(data.load(eq(1L),any(),any())).thenReturn(new RetirementDataService.Data("CNY",LocalDate.now(),List.of(loan,known),List.of(),
                new BigDecimal("3000000"),BigDecimal.ZERO,null,null,"missing-debt",List.of(reference),List.of(),"CPI参考"));
        var view=service.evaluate(1,p,null,false,true);
        assertThat(view.configurationComplete()).isTrue();
        assertThat(view.result().assets()).isEqualByComparingTo("3000000");
        assertThat(view.result().debt()).isZero();assertThat(view.result().principal()).isEqualByComparingTo("3000000");
        assertThat(view.result().totalAssetNeed()).isEqualByComparingTo("4896000");
        assertThat(view.result().coveragePct()).isEqualByComparingTo("61.27");assertThat(view.result().ready()).isTrue();
        assertThat(view.data().assets().getFirst().amount()).isNull();
        assertThat(view.result().problems()).contains(reference);assertThat(view.dataReferences()).contains(reference);
        verifyNoInteractions(mapper,goalMapper);
    }
    @Test void sharedGoalProgressUsesSelectedAssetsOverTotalRequiredFunds() {
        var goal=legacy();var p=plan();p.setVersion(2);p.setDirectRealRate(true);
        p.setChildReserve(new BigDecimal("4200000"));
        when(data.load(eq(1L),any(),any())).thenReturn(new RetirementDataService.Data("CNY",LocalDate.now(),List.of(),List.of(),
                new BigDecimal("5000000"),new BigDecimal("1000000"),null,null,"progress-ratio",List.of(),List.of(),"CPI参考"));
        when(mapper.find(1,9)).thenReturn(Optional.of(new RetirementPlanMapper.Row(9,1,service.encode(p),true,false,null)));
        var shared=new GoalProgressService(goals,mock(com.family.finance.factview.FactViewService.class),mock(PeriodMapper.class),
                mock(AccountMapper.class),mock(SnapshotMapper.class),mock(PeriodMemberCashflowMapper.class),
                mock(GoalMetricEvaluator.class),mock(GoalAccountMapper.class),mock(com.family.finance.service.expense.ExpenseLedgerService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(shared,"retirementPlans",service);
        var progress=shared.compute(1,goal);
        assertThat(progress.pv()).isEqualByComparingTo("5000000");
        assertThat(progress.target()).isEqualByComparingTo("10000000");
        assertThat(progress.progressPct()).isEqualByComparingTo("50.00");
        assertThat(progress.progressLabel()).isEqualTo("50.0%");
        assertThat(progress.configurationLabel()).isEqualTo("配置已完成");
        assertThat(progress.progressAvailable()).isTrue();assertThat(progress.targetReached()).isFalse();
    }
}
