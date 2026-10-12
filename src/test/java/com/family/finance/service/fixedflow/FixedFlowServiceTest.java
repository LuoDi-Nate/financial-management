package com.family.finance.service.fixedflow;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.fixedflow.FixedFlow;
import com.family.finance.domain.flow.CashFlowCategory;
import com.family.finance.domain.insurance.InsurancePolicy;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.CashFlowCategoryMapper;
import com.family.finance.repository.FixedFlowMapper;
import com.family.finance.repository.InsurancePolicyMapper;
import com.family.finance.repository.MemberMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.expense.ExpenseCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** v1.32 · 设置的校验:种类 × 账户类型 × 金额方式(FR-1100 ~ 1106),以及保单 / 贷款的一键预填 */
class FixedFlowServiceTest {

    static final long FAM = 1L;
    FixedFlowMapper mapper = mock(FixedFlowMapper.class);
    AccountMapper accounts = mock(AccountMapper.class);
    CashFlowCategoryMapper cats = mock(CashFlowCategoryMapper.class);
    InsurancePolicyMapper policies = mock(InsurancePolicyMapper.class);
    FixedFlowService svc;

    @BeforeEach
    void setUp() {
        svc = new FixedFlowService(mapper, accounts, cats, policies, mock(MemberMapper.class), mock(PeriodMapper.class),
                mock(ExpenseCategoryService.class), mock(AuditLogService.class));
        acc(11, "工资卡", AccountType.CASH, "CNY", null);
        acc(12, "美元户", AccountType.CASH, "USD", null);
        acc(13, "富途", AccountType.STOCK, "HKD", null);
        acc(21, "房贷", AccountType.LOAN, "CNY", null);
        acc(22, "车贷", AccountType.LOAN, "CNY", new BigDecimal("4.2"));
        acc(41, "年金险", AccountType.INSURANCE, "CNY", null);
        when(cats.findByCode("salary")).thenReturn(Optional.of(cat("salary", "工资", "INCOME", "CASH")));
        when(cats.findByCode("dividend")).thenReturn(Optional.of(cat("dividend", "股息", "INCOME", "STOCK")));
    }

    private void acc(long id, String name, AccountType t, String ccy, BigDecimal rate) {
        when(accounts.findById(FAM, id)).thenReturn(Optional.of(Account.builder().id(id).familyId(FAM).displayName(name)
                .type(t).currency(ccy).annualRatePct(rate).build()));
    }

    private static CashFlowCategory cat(String code, String name, String kind, String type) {
        CashFlowCategory c = new CashFlowCategory();
        c.setCode(code); c.setDisplayName(name); c.setKind(kind); c.setAccountType(type);
        return c;
    }

    private static FixedFlowService.Form form(String kind, Long acc, Long target, String mode, String method, String amount) {
        FixedFlowService.Form f = new FixedFlowService.Form();
        f.setKind(kind); f.setAccountId(acc); f.setTargetAccountId(target);
        f.setAmountMode(mode); f.setAutoMethod(method); f.setAmount(amount == null ? null : new BigDecimal(amount));
        f.setDayOfMonth(5); f.setCycle("MONTHLY"); f.setCategoryCode("INCOME".equals(kind) ? "salary" : "consumption");
        return f;
    }

    @Test
    void expense_fixed_ok_andRepaymentNaturesRedirectedToRepay() {
        FixedFlow f = svc.validate(FAM, form("EXPENSE", 11L, null, "FIXED", null, "480"));
        assertThat(f.getAmount()).isEqualByComparingTo("480.00");
        assertThat(f.getName()).as("没填名称 → 用账户名兜底,不留空").isEqualTo("工资卡");
        FixedFlowService.Form bad = form("EXPENSE", 11L, null, "FIXED", null, "480");
        bad.setCategoryCode("loan_payment");
        assertThatThrownBy(() -> svc.validate(FAM, bad)).hasMessageContaining("还款");
    }

    @Test
    void income_rejectsLiabilityAndStockAccounts_andMismatchedCategory() {
        assertThatThrownBy(() -> svc.validate(FAM, form("INCOME", 21L, null, "FIXED", null, "1"))).hasMessageContaining("不能记收入");
        assertThatThrownBy(() -> svc.validate(FAM, form("INCOME", 13L, null, "FIXED", null, "1"))).hasMessageContaining("股票");
        FixedFlowService.Form f = form("INCOME", 11L, null, "FIXED", null, "1");
        f.setCategoryCode("dividend");
        assertThatThrownBy(() -> svc.validate(FAM, f)).hasMessageContaining("股息");
    }

    @Test
    void autoMethods_onlyThoseThatFitTheKind() {
        assertThat(svc.validate(FAM, form("EXPENSE", 11L, null, "AUTO", "AVG3", "300")).getAutoMethod()).isEqualTo("AVG3");
        assertThatThrownBy(() -> svc.validate(FAM, form("EXPENSE", 11L, null, "AUTO", "LOAN_DIFF", null)))
                .hasMessageContaining("能用的自动计算");
        FixedFlowService.Form card = form("REPAY", 11L, 21L, "AUTO", "LOAN_DIFF", null);
        card.setRepayKind("CARD");
        assertThatThrownBy(() -> svc.validate(FAM, card)).hasMessageContaining("全额还上期");
    }

    @Test
    void loanRate_needsRateOnTheLoan_andAMonthlyPayment() {
        FixedFlowService.Form f = form("REPAY", 11L, 21L, "AUTO", "LOAN_RATE", "6280");
        assertThatThrownBy(() -> svc.validate(FAM, f)).hasMessageContaining("年利率");
        FixedFlowService.Form ok = form("REPAY", 11L, 22L, "AUTO", "LOAN_RATE", "6280");
        assertThat(svc.validate(FAM, ok).getAmount()).isEqualByComparingTo("6280.00");
        FixedFlowService.Form noPay = form("REPAY", 11L, 22L, "AUTO", "LOAN_RATE", null);
        assertThatThrownBy(() -> svc.validate(FAM, noPay)).hasMessageContaining("月供");
        // 按差值不要金额:就算表单带了也不存(防止以后被误当成固定金额)
        assertThat(svc.validate(FAM, form("REPAY", 11L, 21L, "AUTO", "LOAN_DIFF", "999")).getAmount()).isNull();
    }

    @Test
    void repay_payerMustBeRealMoney_sameCurrency_notTheLoanItself() {
        assertThatThrownBy(() -> svc.validate(FAM, form("REPAY", 22L, 21L, "AUTO", "LOAN_DIFF", null))).hasMessageContaining("钱实际流出");
        assertThatThrownBy(() -> svc.validate(FAM, form("REPAY", 12L, 21L, "AUTO", "LOAN_DIFF", null))).hasMessageContaining("币种不同");
        assertThatThrownBy(() -> svc.validate(FAM, form("REPAY", 11L, 11L, "AUTO", "LOAN_DIFF", null))).hasMessageContaining("贷款或信用卡");
    }

    @Test
    void premium_policyNeedsPremium_andTargetMustBeInsurance() {
        assertThatThrownBy(() -> svc.validate(FAM, form("PREMIUM", 11L, 41L, "AUTO", "POLICY", null))).hasMessageContaining("每期保费");
        when(policies.findByAccount(FAM, 41L)).thenReturn(Optional.of(InsurancePolicy.builder().accountId(41L)
                .premiumAmount(new BigDecimal("12000")).premiumFrequency("ANNUAL").build()));
        assertThat(svc.validate(FAM, form("PREMIUM", 11L, 41L, "AUTO", "POLICY", null)).getAutoMethod()).isEqualTo("POLICY");
        assertThatThrownBy(() -> svc.validate(FAM, form("PREMIUM", 11L, 21L, "FIXED", null, "100"))).hasMessageContaining("保险账户");
    }

    @Test
    void yearlyNeedsMonths_endMonthParsed() {
        FixedFlowService.Form f = form("EXPENSE", 11L, null, "FIXED", null, "100");
        f.setCycle("YEARLY");
        assertThatThrownBy(() -> svc.validate(FAM, f)).hasMessageContaining("哪几个月");
        f.setMonths(List.of(9, 3));
        f.setEndMonth("2030-06");
        FixedFlow out = svc.validate(FAM, f);
        assertThat(out.getMonths()).isEqualTo("3,9");
        assertThat(out.getEndMonth()).isEqualTo(LocalDate.of(2030, 6, 1));
        f.setEndMonth("明年");
        assertThatThrownBy(() -> svc.validate(FAM, f)).hasMessageContaining("2030-06");
    }

    @Test
    void policyPrefill_annual_monthIsEffectiveMonth_stopsAfterTerms() {
        when(policies.findByAccount(FAM, 41L)).thenReturn(Optional.of(InsurancePolicy.builder().accountId(41L)
                .premiumAmount(new BigDecimal("12000")).premiumFrequency("ANNUAL")
                .policyEffectiveDate(LocalDate.of(2024, 3, 15)).premiumTermsTotal(10).build()));
        FixedFlowService.Form f = svc.prefillForPolicy(FAM, 41L, LocalDate.of(2026, 10, 12));
        assertThat(f.getKind()).isEqualTo("PREMIUM");
        assertThat(f.getAutoMethod()).isEqualTo("POLICY");
        assertThat(f.getCycle()).isEqualTo("YEARLY");
        assertThat(f.getMonths()).containsExactly(3);
        assertThat(f.getDayOfMonth()).isEqualTo(15);
        assertThat(f.getEndMonth()).as("10 期年缴:2024-03 … 2033-03").isEqualTo("2033-03");
    }

    @Test
    void loanPrefill_creditCardTemplate_meansCardBill() {
        when(mapper.templateCodeOf(FAM, 21L)).thenReturn(Optional.of("credit_card"));
        FixedFlowService.Form f = svc.prefillForLoan(FAM, 21L, LocalDate.of(2026, 10, 12));
        assertThat(f.getRepayKind()).isEqualTo("CARD");
        assertThat(f.getAutoMethod()).isEqualTo("CARD_FULL");
        when(mapper.templateCodeOf(FAM, 22L)).thenReturn(Optional.of("car_loan"));
        FixedFlowService.Form g = svc.prefillForLoan(FAM, 22L, LocalDate.of(2026, 10, 12));
        assertThat(g.getRepayKind()).isEqualTo("LOAN");
        assertThat(g.getAutoMethod()).as("默认就是原来贷款那套").isEqualTo("LOAN_DIFF");
    }
}
