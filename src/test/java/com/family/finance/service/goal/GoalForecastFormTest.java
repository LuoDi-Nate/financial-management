package com.family.finance.service.goal;

import com.family.finance.domain.goal.GoalForecastForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GoalForecastFormTest {
    private GoalForecastForm form(String source,String amount,String wish){var f=new GoalForecastForm();f.setEstimateSource(source);f.setEstimateMonthlyAmount(amount);f.setEstimateWishMonth(wish);return f;}
    @Test void autoDoesNotRequireDataOrHiddenAmount(){assertTrue(form("AUTO",null,null).errors().isEmpty());assertTrue(form("AUTO","invalid","").errors().isEmpty());}
    @ParameterizedTest @ValueSource(strings={"0","-5000","40000.01","1000000000","-1000000000"})
    void validManualValues(String amount){assertTrue(form("MANUAL",amount,"2037-04").errors().isEmpty());}
    @ParameterizedTest @ValueSource(strings={"","NaN","1e4","1.001","1000000000.01","--1"})
    void invalidAmountsGetFieldError(String amount){assertTrue(form("MANUAL",amount,null).errors().containsKey("estimateMonthlyAmount"));}
    @ParameterizedTest @ValueSource(strings={"2030-13","2030-1","2030","<script>","99999-01"})
    void invalidMonthsGetFieldError(String wish){assertTrue(form("AUTO",null,wish).errors().containsKey("estimateWishMonth"));}
}
