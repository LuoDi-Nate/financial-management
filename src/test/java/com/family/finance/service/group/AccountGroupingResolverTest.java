package com.family.finance.service.group;

import com.family.finance.domain.account.Account;
import com.family.finance.repository.AccountGroupMapper;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.PeriodAccountGroupMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.20 · 账户维值解析器 —— 四条聚合路径的唯一入口。
 *
 * <p>这里钉住的每一条,猜错了都<b>不会报错</b>:页面照常渲染、数字自身也「算对了」,
 * 只是聚合成了另一回事。</p>
 */
class AccountGroupingResolverTest {

    private AccountMapper accounts;
    private AccountGroupMapper groups;
    private PeriodAccountGroupMapper frozen;
    private AccountGroupingResolver resolver;

    private static Account acc(long id, String name) {
        Account a = new Account();
        a.setId(id);
        a.setDisplayName(name);
        return a;
    }

    @BeforeEach
    void setUp() {
        accounts = mock(AccountMapper.class);
        groups = mock(AccountGroupMapper.class);
        frozen = mock(PeriodAccountGroupMapper.class);
        resolver = new AccountGroupingResolver(accounts, groups, frozen);
        when(accounts.findAllByFamily(1L)).thenReturn(List.of(
                acc(1, "招行储蓄卡"), acc(2, "支付宝"), acc(3, "华泰证券")));
    }

    @Test
    @DisplayName("零组态:每个账户的维值就是自己的账户名 —— 这让「不建组则逐字一致」成为结构上必然")
    void zeroGroupsMeansAccountNames() {
        when(frozen.findByPeriod(anyLong())).thenReturn(List.of());
        when(groups.findByFamily(1L)).thenReturn(List.of());
        when(groups.findMembersByFamily(1L)).thenReturn(List.of());

        var v = resolver.valuesFor(1L, 100L, null);

        assertThat(v).containsEntry(1L, "招行储蓄卡")
                     .containsEntry(2L, "支付宝")
                     .containsEntry(3L, "华泰证券");
    }

    @Test
    @DisplayName("已分组的账户折叠成组名;未分组的仍是账户名,与组并列 —— 不会被吞掉")
    void groupedFoldsAndUngroupedStaysBeside() {
        when(frozen.findByPeriod(100L)).thenReturn(List.of(
                new PeriodAccountGroupMapper.Row(1L, 9L, "随时可取"),
                new PeriodAccountGroupMapper.Row(2L, 9L, "随时可取")));

        var v = resolver.valuesFor(1L, 100L, null);

        assertThat(v).containsEntry(1L, "随时可取")
                     .containsEntry(2L, "随时可取")
                     .containsEntry(3L, "华泰证券");   // 未分组:仍以自己的身份出现
    }

    @Test
    @DisplayName("下钻:被点开的组回落成账户名,其它组不受影响")
    void expandFallsBackToAccountNames() {
        when(frozen.findByPeriod(100L)).thenReturn(List.of(
                new PeriodAccountGroupMapper.Row(1L, 9L, "随时可取"),
                new PeriodAccountGroupMapper.Row(2L, 9L, "随时可取"),
                new PeriodAccountGroupMapper.Row(3L, 8L, "投资组合")));

        var v = resolver.valuesFor(1L, 100L, 9L);

        assertThat(v).containsEntry(1L, "招行储蓄卡")     // 点开的组 → 展开
                     .containsEntry(2L, "支付宝")
                     .containsEntry(3L, "投资组合");      // 另一个组 → 仍折叠
    }

    @Test
    @DisplayName("有定格行就必须读定格,不许读当前成员关系 —— 否则改一次组,12 期趋势图全变")
    void frozenWinsOverCurrent() {
        when(frozen.findByPeriod(100L)).thenReturn(List.of(
                new PeriodAccountGroupMapper.Row(1L, 9L, "当时叫这个")));
        // 当前关系完全不同 —— 一旦被读到就会污染历史
        when(groups.findByFamily(1L)).thenReturn(List.of());
        when(groups.findMembersByFamily(1L)).thenReturn(List.of(new AccountGroupMapper.Member(7L, 3L)));

        var v = resolver.valuesFor(1L, 100L, null);

        assertThat(v).containsEntry(1L, "当时叫这个");
        assertThat(v).containsEntry(3L, "华泰证券");   // 当前关系没有被读进来
    }

    @Test
    @DisplayName("没有定格行(进行中的期)才回落到当前成员关系")
    void fallsBackToCurrentWhenNoFreeze() {
        when(frozen.findByPeriod(200L)).thenReturn(List.of());
        when(groups.findByFamily(1L)).thenReturn(List.of(
                com.family.finance.domain.group.AccountGroup.builder().id(7L).name("日常周转").build()));
        when(groups.findMembersByFamily(1L)).thenReturn(List.of(new AccountGroupMapper.Member(7L, 3L)));

        var v = resolver.valuesFor(1L, 200L, null);

        assertThat(v).containsEntry(3L, "日常周转");
    }

    @Test
    @DisplayName("定格里出现已删除的账户时不能抛 —— 历史快照本来就会引用现在不存在的东西")
    void unknownAccountInFreezeIsIgnored() {
        when(frozen.findByPeriod(100L)).thenReturn(List.of(
                new PeriodAccountGroupMapper.Row(999L, 9L, "早就删掉的账户")));

        var v = resolver.valuesFor(1L, 100L, null);

        assertThat(v).hasSize(3).doesNotContainKey(999L);
    }
}
