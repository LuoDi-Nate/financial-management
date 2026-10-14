package com.family.finance.service.stock;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.stock.StockHolding;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.StockHoldingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * v1.34 · issue #40 持仓顺序 / issue #41 持仓改名的服务层规矩。
 * 顺序只收这个账户全部在用持仓的完整排列;改名只动名字这一列。名称与金额均为编的。
 */
class HoldingOrderRenameTest {

    private StockHoldingMapper holdingMapper;
    private StockHoldingService svc;

    @BeforeEach
    void setUp() {
        holdingMapper = mock(StockHoldingMapper.class);
        AccountMapper accountMapper = mock(AccountMapper.class);
        svc = new StockHoldingService(holdingMapper, accountMapper, mock(com.family.finance.service.FxService.class),
                mock(com.family.finance.repository.PeriodMapper.class), mock(StockPriceFetcher.class),
                mock(com.family.finance.repository.CashFlowMapper.class));
        when(accountMapper.findById(anyLong(), eq(10L))).thenReturn(Optional.of(
                Account.builder().id(10L).familyId(1L).type(AccountType.STOCK).currency("CNY").build()));
        when(holdingMapper.findActiveByAccount(1L, 10L)).thenReturn(List.of(h(101), h(102), h(103)));
        when(holdingMapper.updateSortOrder(anyLong(), anyLong(), anyLong(), anyInt())).thenReturn(1);
    }

    private static StockHolding h(long id) {
        return StockHolding.builder().id(id).accountId(10L).displayName("样例" + id).build();
    }

    @Test
    void saveOrder_writesOneToN_inTheGivenOrder() {
        svc.saveOrder(1L, 10L, List.of(103L, 101L, 102L));
        verify(holdingMapper).updateSortOrder(1L, 10L, 103L, 1);
        verify(holdingMapper).updateSortOrder(1L, 10L, 101L, 2);
        verify(holdingMapper).updateSortOrder(1L, 10L, 102L, 3);
    }

    @Test
    void saveOrder_onlyAcceptsTheFullPermutation_ofThisAccountsActiveHoldings() {
        for (List<Long> bad : List.of(
                List.of(103L, 101L),                 // 少一只
                List.of(103L, 101L, 102L, 104L),     // 多一只(别的账户 / 已归档的)
                List.of(103L, 101L, 101L),           // 重复
                List.of(103L, 101L, 999L))) {        // 不是这个账户的
            assertThatThrownBy(() -> svc.saveOrder(1L, 10L, bad)).as(bad.toString())
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("对不上");
        }
        assertThatThrownBy(() -> svc.saveOrder(1L, 10L, null)).isInstanceOf(IllegalArgumentException.class);
        verify(holdingMapper, never()).updateSortOrder(anyLong(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void rename_trimsAndCollapsesSpaces_andOnlyTouchesTheName() {
        when(holdingMapper.findById(1L, 101L)).thenReturn(Optional.of(h(101)));
        when(holdingMapper.updateDisplayName(1L, 10L, 101L, "样例 沪深300 ETF")).thenReturn(1);
        StockHolding out = svc.rename(1L, 10L, 101L, "  样例   沪深300 ETF ");
        assertThat(out.getDisplayName()).isEqualTo("样例 沪深300 ETF");
        verify(holdingMapper).updateDisplayName(1L, 10L, 101L, "样例 沪深300 ETF");
        verify(holdingMapper, never()).update(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rename_rejectsBlankTooLongOtherAccountAndArchived() {
        when(holdingMapper.findById(1L, 101L)).thenReturn(Optional.of(h(101)));
        assertThatThrownBy(() -> svc.rename(1L, 10L, 101L, "   ")).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> svc.rename(1L, 10L, 101L, "名".repeat(65))).hasMessageContaining("最多 64");
        assertThatThrownBy(() -> svc.rename(1L, 11L, 101L, "新名")).hasMessageContaining("持仓不存在");
        StockHolding archived = h(102);
        archived.setArchivedAt(LocalDateTime.now());
        when(holdingMapper.findById(1L, 102L)).thenReturn(Optional.of(archived));
        assertThatThrownBy(() -> svc.rename(1L, 10L, 102L, "新名")).hasMessageContaining("持仓不存在");
        verify(holdingMapper, never()).updateDisplayName(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void rename_sameName_isANoOp() {
        when(holdingMapper.findById(1L, 101L)).thenReturn(Optional.of(h(101)));
        svc.rename(1L, 10L, 101L, "样例101");
        verify(holdingMapper, never()).updateDisplayName(anyLong(), anyLong(), anyLong(), anyString());
    }
}
