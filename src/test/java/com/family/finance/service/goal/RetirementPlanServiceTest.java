package com.family.finance.service.goal;

import com.family.finance.domain.goal.*;
import com.family.finance.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
}
