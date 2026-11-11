package com.family.finance.calc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import static org.junit.jupiter.api.Assertions.*;

class GoalCashflowCalculatorTest {
    private static final LocalDate TODAY=LocalDate.of(2026,11,10);
    private static BigDecimal n(String s){return s==null?null:new BigDecimal(s);}
    private GoalCashflowCalculator.Result calc(String a,String f,String c){return GoalCashflowCalculator.calculate(TODAY,n(a),n(f),n(c),YearMonth.of(2036,11));}
    @Test void contract125MonthsAndWishDifference(){
        var r=calc("5000000","10000000","40000");
        assertEquals(125,r.months());assertEquals(LocalDate.of(2037,4,30),r.date());
        assertEquals("10年5个月",r.durationLabel());assertEquals(5L,r.monthDelta());assertEquals(n("5000000"),r.gap());
    }
    @Test void oneCentShortNeedsOneMoreMonth(){assertEquals(126,calc("5000000","10000000","39999.99").months());}
    @ParameterizedTest @CsvSource({"100,100","101,100","0,0","1,0"})
    void achievedNeedsNoContribution(String a,String f){var r=calc(a,f,null);assertEquals(GoalCashflowCalculator.State.ACHIEVED,r.state());assertEquals(0,r.months());assertEquals(TODAY,r.date());}
    @ParameterizedTest @CsvSource({"0","-1","-5000"})
    void nonpositiveSavingsHaveNoFalseDate(String c){var r=calc("50","100",c);assertEquals(GoalCashflowCalculator.State.NO_GROWTH,r.state());assertNull(r.date());assertNull(r.months());assertEquals(n("50"),r.gap());}
    @Test void missingSavingsKeepsGap(){var r=calc("50","100",null);assertEquals(GoalCashflowCalculator.State.NEEDS_DATA,r.state());assertEquals(n("50"),r.gap());assertNull(r.date());}
    @Test void noFundingOrTargetDoesNotProduceDate(){assertNull(calc(null,"100","20").date());assertNull(calc("50",null,"20").date());}
    @Test void exactly50YearsHasDate(){assertEquals(600,calc("0","600","1").months());assertEquals(LocalDate.of(2076,11,30),calc("0","600","1").date());}
    @Test void overHorizonIsNotNever(){var r=calc("0","600.01","1");assertEquals(GoalCashflowCalculator.State.OVER_HORIZON,r.state());assertNull(r.date());assertEquals("超过50年",r.dateLabel());}
    @Test void enormousMonthCountCannotOverflow(){assertEquals(GoalCashflowCalculator.State.OVER_HORIZON,calc("-1000000000000000000","1000000000000000000","0.01").state());}
    @Test void estimateNeedsNoDeadline(){assertNotNull(GoalCashflowCalculator.calculate(TODAY,n("1"),n("2"),n("1"),null).date());}
    @Test void allCurrencyScalesPreserveMonthsAndDates(){
        var original=calc("5000000","10000000","40000");
        for(var scale:new BigDecimal[]{n("0.1388888888888889"),n("1.0869565217391304"),n("7.2")}){
            var r=GoalCashflowCalculator.calculate(TODAY,n("5000000").multiply(scale),n("10000000").multiply(scale),n("40000").multiply(scale),null);
            assertEquals(original.months(),r.months());assertEquals(original.date(),r.date());
        }
    }
    @Test void unavailableSourceDoesNotUndoAchievement(){var r=calc("100","100",null);assertEquals(r,GoalCashflowCalculator.unavailable(r,"source"));}
}
