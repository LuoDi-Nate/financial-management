package com.family.finance.service.group;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.group.AccountGroup;
import com.family.finance.repository.AccountGroupMapper;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodAccountGroupMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.20 · 建组时的两类冲突。
 *
 * <p>这两条都是维护者验收当场撞出来的:</p>
 * <ol>
 *   <li><b>组名重复 → 500 白页。</b>{@code Duplicate entry '1-组A' for key 'uk_group_family_name'}
 *       一路冒到错误页。用户既不知道哪里错了,也不知道该改什么。</li>
 *   <li><b>账户被两个组选中 → 悄悄搬走。</b>第一版用 {@code ON DUPLICATE KEY UPDATE} 实现成了
 *       搬家语义,于是在新组里勾一个已属别组的账户,系统会把它从原组挪走而<b>页面一个字不说</b> ——
 *       原组的收益口径当场变了,用户不知道。<b>静默地改掉用户没打算改的东西,比报错更糟。</b></li>
 * </ol>
 */
class AccountGroupConflictTest {

    private AccountGroupMapper groups;
    private AccountMapper accounts;
    private AccountGroupService svc;

    @BeforeEach
    void setUp() {
        groups = mock(AccountGroupMapper.class);
        accounts = mock(AccountMapper.class);
        svc = new AccountGroupService(groups, accounts, mock(PeriodAccountGroupMapper.class));
        Account a = new Account();
        a.setId(3L);
        a.setDisplayName("华泰证券-A股");
        when(accounts.findById(3L)).thenReturn(Optional.of(a));
    }

    private void existingGroup(long id, String name, Long... members) {
        when(groups.findByFamily(1L)).thenReturn(List.of(
                AccountGroup.builder().id(id).familyId(1L).name(name).build()));
        when(groups.findMembersByFamily(1L)).thenReturn(
                java.util.Arrays.stream(members).map(m -> new AccountGroupMapper.Member(id, m)).toList());
    }

    @Test
    @DisplayName("组名重复 → 业务异常,不是 SQL 约束冒成 500")
    void duplicateNameIsABusinessError() {
        existingGroup(9L, "组A", 3L);

        assertThatThrownBy(() -> svc.create(1L, 1L, "组A", null, List.of(5L)))
                .isInstanceOf(AccountGroupService.GroupConflictException.class)
                .hasMessageContaining("已经有一个叫「组A」的分组");

        verify(groups, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("账户已属别的组 → 拒绝,并说清被谁占了、怎么挪 —— 绝不悄悄搬走")
    void occupiedAccountIsRejectedNotStolen() {
        existingGroup(9L, "组A", 3L);

        assertThatThrownBy(() -> svc.create(1L, 1L, "组B", null, List.of(3L)))
                .isInstanceOf(AccountGroupService.GroupConflictException.class)
                .hasMessageContaining("华泰证券-A股")
                .hasMessageContaining("组A")
                .hasMessageContaining("取消勾选");

        // 关键:一行都不能写。第一版会把账户从组A搬进新组,而且不吭声
        verify(groups, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(groups, never()).addMember(anyLong(), anyLong());
    }

    @Test
    @DisplayName("编辑某个组时,它自己的成员不算「被占用」")
    void ownMembersAreNotOccupied() {
        existingGroup(9L, "组A", 3L, 4L);

        assertThat(svc.occupiedBy(1L, 9L)).isEmpty();
        assertThat(svc.occupiedBy(1L, null)).containsEntry(3L, "组A").containsEntry(4L, "组A");
    }

    @Test
    @DisplayName("改名成另一个已存在的名字也要拦")
    void renameToExistingNameIsRejected() {
        when(groups.findByFamily(1L)).thenReturn(List.of(
                AccountGroup.builder().id(9L).familyId(1L).name("组A").build(),
                AccountGroup.builder().id(10L).familyId(1L).name("组B").build()));
        when(groups.findMembersByFamily(1L)).thenReturn(List.of());
        when(groups.findById(1L, 10L)).thenReturn(AccountGroup.builder().id(10L).familyId(1L).name("组B").build());

        assertThatThrownBy(() -> svc.updateMembers(1L, 10L, "组A", List.of()))
                .isInstanceOf(AccountGroupService.GroupConflictException.class);
    }

    @Test
    @DisplayName("改自己原来的名字不算重名")
    void keepingOwnNameIsFine() {
        when(groups.findByFamily(1L)).thenReturn(List.of(
                AccountGroup.builder().id(9L).familyId(1L).name("组A").build()));
        when(groups.findMembersByFamily(1L)).thenReturn(List.of());
        when(groups.findById(1L, 9L)).thenReturn(AccountGroup.builder().id(9L).familyId(1L).name("组A").build());

        svc.updateMembers(1L, 9L, "组A", List.of());   // 不抛
        verify(groups).clearMembers(9L);
    }
}
