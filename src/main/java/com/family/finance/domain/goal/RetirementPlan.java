package com.family.finance.domain.goal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 计划金额均以 baseCurrency/priceDate 的购买力保存,不覆盖旧目标参数。 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RetirementPlan {
    private int version = 1;
    private String name = "退休生活账";
    private String baseCurrency;
    private LocalDate priceDate;
    private String targetMonth;
    private BigDecimal monthlyExpense;
    private BigDecimal monthlyIncome = BigDecimal.ZERO;
    private BigDecimal monthlyContribution;
    private BigDecimal childReserve;
    private BigDecimal medicalReserve;
    private BigDecimal housingReserve;
    private BigDecimal otherReserve;
    private Long educationGoalId;
    private boolean autoExpense;
    private boolean excludeOneOff;
    private Set<Long> excludedCategoryIds = new LinkedHashSet<>();
    private Set<Long> accountIds = new LinkedHashSet<>();
    private Map<Long, BigDecimal> realizableAmounts = new LinkedHashMap<>();
    private boolean directRealRate;
    private BigDecimal nominalReturnPct = new BigDecimal("4.5");
    private BigDecimal inflationPct = new BigDecimal("2");
    private BigDecimal realReturnPct = new BigDecimal("2.5");
    private BigDecimal lowReturnPct = new BigDecimal("2");
    private BigDecimal middleReturnPct = new BigDecimal("4.5");
    private BigDecimal highReturnPct = new BigDecimal("6");
    private boolean contributionFollowsInflation;
    private boolean budgetConfirmed;
    private boolean reservesConfirmed;
    private boolean noOverlapConfirmed;
    private boolean assetsConfirmed;
    private boolean incomeConfirmed;
    private boolean contributionConfirmed;
    private boolean afterDebtConfirmed;
    private boolean educationConfirmed;
    private String budgetFingerprint;
    private String scopeMode = "CUSTOM";
    private String returnSource = "MANUAL";
    private String inflationSource = "MANUAL";
    private String reserveMode = "CUSTOM";
    private String cityTier;
    private Integer dependentCount;
    private java.util.List<Integer> childPlanningYears = new java.util.ArrayList<>();
    private String housingPlan;
    private int presetVersion = 1;
    private boolean simulateFuture;
    private String contributionSource = "MANUAL";
    private boolean advancedScenarios;
    private BigDecimal priceInflationPct;
    private String previousRevisionJson;
}
