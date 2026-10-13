package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.repository.AccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * v1.34 · 账户簿「调整顺序」(拖拽):只收全部在用账户的完整排列,写 10 / 20 / 30 …(留间隔给编辑页手填)。
 */
class AccountOrderTest {

    private AccountMapper accounts;
    private AccountService svc;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountMapper.class);
        svc = new AccountService(accounts, mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(com.family.finance.service.member.MemberDirectory.class), mock(com.family.finance.repository.PeriodMapper.class),
                mock(com.family.finance.repository.SnapshotMapper.class), mock(AuditLogService.class),
                mock(com.family.finance.service.fixedflow.FixedFlowService.class), mock(ProductCategoryService.class),
                mock(com.family.finance.repository.FxMapper.class), mock(com.family.finance.repository.FamilyMapper.class));
        when(accounts.findActiveByFamily(1L)).thenReturn(List.of(
                Account.builder().id(5L).familyId(1L).build(), Account.builder().id(6L).familyId(1L).build(),
                Account.builder().id(7L).familyId(1L).build()));
        when(accounts.updateDisplayOrder(anyLong(), anyLong(), anyInt())).thenReturn(1);
    }

    @Test
    void writesTensInTheGivenOrder() {
        svc.saveOrder(1L, List.of(7L, 5L, 6L));
        verify(accounts).updateDisplayOrder(1L, 7L, 10);
        verify(accounts).updateDisplayOrder(1L, 5L, 20);
        verify(accounts).updateDisplayOrder(1L, 6L, 30);
    }

    @Test
    void onlyTheFullPermutationOfActiveAccounts() {
        for (List<Long> bad : List.of(List.of(7L, 5L), List.of(7L, 5L, 6L, 8L), List.of(7L, 7L, 6L), List.of(7L, 5L, 99L))) {
            assertThatThrownBy(() -> svc.saveOrder(1L, bad)).as(bad.toString()).hasMessageContaining("对不上");
        }
        verify(accounts, never()).updateDisplayOrder(anyLong(), anyLong(), anyInt());
    }
}
