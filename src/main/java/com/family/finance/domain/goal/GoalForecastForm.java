package com.family.finance.domain.goal;

import lombok.Data;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class GoalForecastForm {
    private String estimateSource;
    private String estimateMonthlyAmount;
    private String estimateWishMonth;
    private Boolean estimateWishOverride;

    public Map<String,String> errors() {
        Map<String,String> errors = new LinkedHashMap<>();
        if (!"AUTO".equals(estimateSource) && !"MANUAL".equals(estimateSource)) errors.put("estimateSource", "请选择结余来源");
        if ("MANUAL".equals(estimateSource)) {
            try {
                if (estimateMonthlyAmount == null || !estimateMonthlyAmount.matches("-?\\d+(?:\\.\\d{1,2})?")) throw new IllegalArgumentException();
                if (amount().abs().compareTo(new BigDecimal("1000000000")) > 0) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { errors.put("estimateMonthlyAmount", "请填写有效月结余,最多2位小数,绝对值不超过10亿元"); }
        }
        if (wishOverride() && estimateWishMonth != null && !estimateWishMonth.isBlank()) {
            try { if (!estimateWishMonth.matches("\\d{4}-(?:0[1-9]|1[0-2])")) throw new IllegalArgumentException(); YearMonth.parse(estimateWishMonth); }
            catch (RuntimeException e) { errors.put("estimateWishMonth", "请填写有效的YYYY-MM,或留空"); }
        }
        return errors;
    }
    public BigDecimal amount() { return estimateMonthlyAmount == null || estimateMonthlyAmount.isBlank() ? null : new BigDecimal(estimateMonthlyAmount); }
    public YearMonth wish() { return estimateWishMonth == null || estimateWishMonth.isBlank() ? null : YearMonth.parse(estimateWishMonth); }
    public boolean wishOverride() { return !Boolean.FALSE.equals(estimateWishOverride); }
    public GoalForecastForm copy() {
        GoalForecastForm f = new GoalForecastForm(); f.setEstimateSource(estimateSource);
        f.setEstimateMonthlyAmount(estimateMonthlyAmount); f.setEstimateWishMonth(estimateWishMonth);
        f.setEstimateWishOverride(estimateWishOverride); return f;
    }
}
