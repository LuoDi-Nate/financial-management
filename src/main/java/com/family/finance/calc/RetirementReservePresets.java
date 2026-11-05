package com.family.finance.calc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Product planning defaults, explicitly not city statistics or clinical cost estimates. */
public final class RetirementReservePresets {
    public static final int VERSION = 1;
    public static final LocalDate PRICE_DATE = LocalDate.of(2026,11,5);
    private RetirementReservePresets() { }
    public record Preset(String code,String label,BigDecimal educationPerMonth,BigDecimal medical,int emergencyMonths) { }
    public record Amounts(BigDecimal education,BigDecimal medical,BigDecimal emergency) { }
    public static List<Preset> all() { return List.of(
            new Preset("FIRST","一线城市",new BigDecimal("2000"),new BigDecimal("300000"),12),
            new Preset("SECOND","二线城市",new BigDecimal("1200"),new BigDecimal("200000"),6),
            new Preset("OTHER","其他城市",new BigDecimal("800"),new BigDecimal("150000"),6)); }
    public static Preset require(String code) { return all().stream().filter(p->p.code().equals(code)).findFirst()
            .orElseThrow(()->new IllegalArgumentException("请选择城市预留方案")); }
    public static Amounts calculate(Preset p,List<Integer> years,BigDecimal monthlyExpense,BigDecimal cnyToBase,BigDecimal priceFactor) {
        if(cnyToBase==null || priceFactor==null) return new Amounts(null,null,null);
        int remaining=years.stream().mapToInt(Integer::intValue).sum();
        return new Amounts(p.educationPerMonth().multiply(BigDecimal.valueOf(12L*remaining)).multiply(cnyToBase).multiply(priceFactor).setScale(2,RoundingMode.HALF_EVEN),
                p.medical().multiply(cnyToBase).multiply(priceFactor).setScale(2,RoundingMode.HALF_EVEN),
                monthlyExpense==null?null:monthlyExpense.multiply(BigDecimal.valueOf(p.emergencyMonths())).setScale(2,RoundingMode.HALF_EVEN));
    }
}
