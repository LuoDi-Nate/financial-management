package com.family.finance.service.checkup;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.category.ProductCategory;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.service.ProductCategoryService;
import com.family.finance.service.analysis.AnalysisScopeService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReportRiskDistributionTest {
    private final FactViewService facts = mock(FactViewService.class);
    private final ProductCategoryService categories = mock(ProductCategoryService.class);
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final PeriodMapper periods = mock(PeriodMapper.class);
    private final FamilyDiagnoseService service = new FamilyDiagnoseService(facts, categories,
            mock(FamilyMapper.class), periods, accounts, mock(AnalysisScopeService.class));

    private AccountPeriodFact row(long account, long period, String amount, AccountClass kind) {
        var row = mock(AccountPeriodFact.class);
        when(row.accountId()).thenReturn(account);
        when(row.periodId()).thenReturn(period);
        when(row.accountClass()).thenReturn(kind);
        when(row.accountType()).thenReturn(AccountType.CASH);
        when(row.endBalanceBase()).thenReturn(new BigDecimal(amount));
        return row;
    }

    private void ratings() {
        var low = new ProductCategory();
        low.setCode("CASH_DEPOSIT");
        low.setRiskLevel(1);
        var a = new Account();
        a.setId(1L);
        a.setProductCategoryCode("CASH_DEPOSIT");
        var b = new Account();
        b.setId(2L);
        b.setRiskLevelOverride(5);
        when(accounts.findAllByFamily(1L)).thenReturn(List.of(a, b));
        when(categories.listAll()).thenReturn(List.of(low));
    }

    @Test
    void reportUsesSelectedPeriodAmountsAndAlreadyConvertedViewCurrency() {
        ratings();
        var slice = new FactSlice(null, List.of(
                row(1, 6, "100", AccountClass.ASSET), row(2, 6, "300", AccountClass.ASSET),
                row(1, 7, "9000", AccountClass.ASSET), row(3, 6, "500", AccountClass.LIABILITY)),
                List.of(6L), 6L);

        var result = service.riskDistribution(1L, slice);

        assertThat(result).extracting(FamilyDiagnose.RiskBucket::level).containsExactly(1, 5);
        assertThat(result.get(0).amount()).isEqualByComparingTo("100");
        assertThat(result.get(0).ratio()).isEqualByComparingTo("0.25");
        assertThat(result.get(1).amount()).isEqualByComparingTo("300");
        verifyNoInteractions(facts, periods);
    }

    @Test
    void filteredOrEmptyAccountRangeDoesNotReloadWholeFamily() {
        ratings();
        var slice = new FactSlice(null, List.of(row(1, 6, "100", AccountClass.ASSET),
                row(2, 6, "300", AccountClass.ASSET)), List.of(6L), 6L);
        var filtered = service.riskDistribution(1L, slice.excludingAccounts(Set.of(2L)));
        assertThat(filtered).hasSize(1);
        assertThat(filtered.getFirst().ratio()).isEqualByComparingTo("1");
        assertThat(service.riskDistribution(1L, slice.excludingAccounts(Set.of(1L, 2L)))).isEmpty();
        verifyNoInteractions(facts, periods);
    }
}
