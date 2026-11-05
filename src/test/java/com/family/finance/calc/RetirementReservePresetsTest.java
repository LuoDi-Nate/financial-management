package com.family.finance.calc;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class RetirementReservePresetsTest {
    @Test void presetsUseRemainingEducationAndActualLivingCostWithoutDoubleInflatingEmergency() {
        var r=RetirementReservePresets.calculate(RetirementReservePresets.require("SECOND"),List.of(12,3),new BigDecimal("10000"),BigDecimal.ONE,BigDecimal.ONE);
        assertThat(r.education()).isEqualByComparingTo("216000");assertThat(r.medical()).isEqualByComparingTo("200000");assertThat(r.emergency()).isEqualByComparingTo("60000");
    }
    @Test void cnyPricesConvertToBaseAndConfirmedNoChildrenIsZero() {
        var r=RetirementReservePresets.calculate(RetirementReservePresets.require("FIRST"),List.of(),new BigDecimal("1000"),new BigDecimal("0.125"),new BigDecimal("1.02"));
        assertThat(r.education()).isZero();assertThat(r.medical()).isEqualByComparingTo("38250");assertThat(r.emergency()).isEqualByComparingTo("12000");
    }
    @Test void unknownCurrencyNeverFallsBackToOne() {
        assertThat(RetirementReservePresets.calculate(RetirementReservePresets.require("OTHER"),List.of(6),BigDecimal.TEN,null,BigDecimal.ONE).medical()).isNull();
    }
}
