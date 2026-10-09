package com.family.finance.service.ledger;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.PrincipalAdjustmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** v1.30 · 补录本金能不能记(FR-973):只给已有历史的资产账户、还开着的期 */
class PrincipalAdjustmentServiceTest {

    private static final long FAM = 1L, ACC = 10L, PID = 99L;
    private PrincipalAdjustmentMapper mapper;
    private AccountMapper accounts;
    private PeriodMapper periods;
    private PrincipalAdjustmentService svc;

    @BeforeEach
    void setUp() {
        mapper = mock(PrincipalAdjustmentMapper.class);
        accounts = mock(AccountMapper.class);
        periods = mock(PeriodMapper.class);
        svc = new PrincipalAdjustmentService(mapper, accounts, periods, mock(ApplicationEventPublisher.class));
        account(AccountType.WEALTH, null);
        when(periods.findById(FAM, PID)).thenReturn(Optional.of(Period.builder().id(PID).familyId(FAM)
                .status(PeriodStatus.OPEN).periodStart(LocalDate.of(2026, 10, 1)).build()));
        when(mapper.findPreviousEndBalance(FAM, ACC, LocalDate.of(2026, 10, 1))).thenReturn(new BigDecimal("100"));
        when(mapper.insert(eq(FAM), any())).thenReturn(1);
    }

    private void account(AccountType t, LocalDateTime archived) {
        when(accounts.findById(FAM, ACC)).thenReturn(Optional.of(Account.builder().id(ACC).familyId(FAM).type(t)
                .currency("CNY").archivedAt(archived).build()));
    }

    @Test
    void 已有历史的资产账户_开着的期_能记() {
        assertThat(svc.check(FAM, ACC, PID).ok()).isTrue();
        svc.record(FAM, 3L, ACC, PID, new BigDecimal("40000"), null, "补录");
        verify(mapper).insert(eq(FAM), argThat(pa -> pa.getAmount().compareTo(new BigDecimal("40000")) == 0
                && "MANUAL".equals(pa.getSourceTag())));
    }

    @Test
    void 账户第一期_不能记_第一期余额本来就整笔算本金() {
        when(mapper.findPreviousEndBalance(FAM, ACC, LocalDate.of(2026, 10, 1))).thenReturn(null);
        var e = svc.check(FAM, ACC, PID);
        assertThat(e.ok()).isFalse();
        assertThat(e.reason()).contains("第一期");
        assertThatThrownBy(() -> svc.record(FAM, 3L, ACC, PID, BigDecimal.TEN, null, null)).hasMessageContaining("第一期");
        verify(mapper, never()).insert(anyLong(), any());
    }

    @Test
    void 负债_归档_关账_金额不为正_都不能记() {
        account(AccountType.LOAN, null);
        assertThat(svc.check(FAM, ACC, PID).reason()).contains("负债");
        account(AccountType.WEALTH, LocalDateTime.now());
        assertThat(svc.check(FAM, ACC, PID).reason()).contains("归档");
        account(AccountType.WEALTH, null);
        when(periods.findById(FAM, PID)).thenReturn(Optional.of(Period.builder().id(PID).familyId(FAM)
                .status(PeriodStatus.CLOSED).periodStart(LocalDate.of(2026, 10, 1)).build()));
        assertThat(svc.check(FAM, ACC, PID).reason()).contains("关账");
        assertThatThrownBy(() -> svc.record(FAM, 3L, ACC, PID, BigDecimal.ZERO, null, null)).hasMessageContaining("大于 0");
    }

    @Test
    void 别家的账户_不能记() {
        when(accounts.findById(FAM, ACC)).thenReturn(Optional.of(Account.builder().id(ACC).familyId(2L)
                .type(AccountType.WEALTH).currency("CNY").build()));
        assertThat(svc.check(FAM, ACC, PID).ok()).isFalse();
    }

    @Test
    void 删除_只认开着的期_删不了要说清() {
        when(mapper.softDelete(FAM, ACC, 5L)).thenReturn(0);
        assertThatThrownBy(() -> svc.delete(FAM, ACC, 5L)).hasMessageContaining("关账");
    }
}
