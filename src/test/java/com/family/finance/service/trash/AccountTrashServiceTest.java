package com.family.finance.service.trash;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.ledger.PrincipalAdjustment;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.trash.AccountTrash;
import com.family.finance.repository.AccountTrashMapper;
import com.family.finance.repository.PrincipalAdjustmentMapper;
import com.family.finance.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.33 · 删除 / 恢复(失败模式 ② 改记方向 · ⑤ 删一半 · ④ 恢复不完整 · FR-1232 放不回的列出来)。
 */
class AccountTrashServiceTest {

    static final long FAM = 1L, ME = 7L, X = 50L, WAGE = 2L, YEB = 6L, FRESH = 9L;
    final ObjectMapper json = new ObjectMapper();

    private static Account acc(long id, String name) {
        return Account.builder().id(id).familyId(FAM).displayName(name).type(AccountType.CASH).currency("CNY").build();
    }

    private static Period period(long id, String start) {
        LocalDate s = LocalDate.parse(start);
        return Period.builder().id(id).familyId(FAM).periodStart(s).periodEnd(s.plusMonths(1).minusDays(1)).status(PeriodStatus.CLOSED).build();
    }

    private static AccountDeletePlanner.DeletePlan plan() {
        var impacts = List.of(
                new AccountDeletePlanner.TransferImpact(11L, period(101, "2025-01-01"), acc(WAGE, "工资卡"), true, new BigDecimal("111.00"), AccountDeletePlanner.PA_IN, "", ""),
                new AccountDeletePlanner.TransferImpact(12L, period(102, "2025-02-01"), acc(YEB, "余额宝"), false, new BigDecimal("112.00"), AccountDeletePlanner.PA_OUT, "", ""),
                new AccountDeletePlanner.TransferImpact(13L, period(106, "2025-06-01"), acc(FRESH, "招行理财"), true, new BigDecimal("50000.00"), AccountDeletePlanner.OPENING, "", ""));
        return new AccountDeletePlanner.DeletePlan(acc(X, "测试账户"), List.of(period(101, "2025-01-01"), period(102, "2025-02-01"), period(106, "2025-06-01")),
                2, "2025-01 至 2025-06 共 3 个账期", 3, 0, "¥0.00", 0, "¥0.00", 0, 0, 0, 3, impacts, 3, List.of(), List.of(), List.of(), "x");
    }

    @Test
    void delete_rewritesEachTransferOnTheCounterpartWithTheRightSign_andMovesEveryTable() throws Exception {
        AccountTrashMapper trash = mock(AccountTrashMapper.class);
        PrincipalAdjustmentMapper pa = mock(PrincipalAdjustmentMapper.class);
        when(trash.columnsOf(anyString())).thenReturn(List.of("id", "account_id"));
        when(trash.selectRows(anyString(), anyString(), eq(FAM), eq(X))).thenReturn(List.of());
        when(trash.selectRows(eq("cash_flow"), anyString(), eq(FAM), eq(X))).thenReturn(List.of(new LinkedHashMap<>(Map.of("id", 900L, "account_id", X))));
        when(trash.deleteRows(anyString(), anyString(), eq(FAM), eq(X))).thenReturn(1);
        long[] seq = {800};
        when(pa.insertSystem(eq(FAM), any())).thenAnswer(inv -> { ((PrincipalAdjustment) inv.getArgument(1)).setId(++seq[0]); return 1; });
        when(trash.loansPayingFrom(FAM, X)).thenReturn(List.of(77L));
        when(trash.todoRefsToTransfersOf(FAM, X)).thenReturn(List.of("31:11"));
        when(trash.insert(eq(FAM), any())).thenReturn(1);
        var svc = new AccountTrashService(trash, pa, mock(AuditLogService.class), mock(ApplicationEventPublisher.class), json);

        svc.delete(FAM, ME, plan(), "/x/dump-manual-1.sql.gz");

        ArgumentCaptor<PrincipalAdjustment> rows = ArgumentCaptor.forClass(PrincipalAdjustment.class);
        verify(pa, org.mockito.Mockito.times(2)).insertSystem(eq(FAM), rows.capture());
        assertThat(rows.getAllValues()).extracting(PrincipalAdjustment::getAccountId).containsExactly(WAGE, YEB);
        assertThat(rows.getAllValues().get(0).getAmount()).as("工资卡收到它转来的 → 补录本金 +").isEqualByComparingTo("111.00");
        assertThat(rows.getAllValues().get(1).getAmount()).as("余额宝转给它的 → 本金移出 −").isEqualByComparingTo("-112.00");
        assertThat(rows.getAllValues()).extracting(PrincipalAdjustment::getPeriodId).containsExactly(101L, 102L);
        // 别处的引用置空、AI 存稿清掉、每张表都删了
        verify(trash).setPaymentSource(FAM, 77L, null);
        verify(trash).setTodoTransfer(FAM, 31L, null);
        verify(trash).clearReviewCache(FAM, List.of(101L, 102L, 106L));
        for (AccountTrashSpec.Table t : AccountTrashSpec.TABLES) verify(trash).deleteRows(t.table(), t.where(), FAM, X);
        ArgumentCaptor<AccountTrash> saved = ArgumentCaptor.forClass(AccountTrash.class);
        verify(trash).insertOwned(eq(FAM), saved.capture());   // default 方法在 mock 上也被替身,桩要打在它本身
        assertThat(saved.getValue().getPeriodIds()).isEqualTo("101,102,106");
        assertThat(saved.getValue().getPayload()).contains("\"cash_flow\":[{\"id\":900");
        assertThat(saved.getValue().getRewrites()).contains("\"transferId\":11").contains("\"77\":50").contains("\"31\":11");
    }

    @Test
    void delete_accountRowNotDeleted_abortsWholeThing() {
        AccountTrashMapper trash = mock(AccountTrashMapper.class);
        PrincipalAdjustmentMapper pa = mock(PrincipalAdjustmentMapper.class);
        when(trash.columnsOf(anyString())).thenReturn(List.of("id"));
        when(trash.selectRows(anyString(), anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(pa.insertSystem(eq(FAM), any())).thenAnswer(inv -> { ((PrincipalAdjustment) inv.getArgument(1)).setId(1L); return 1; });
        when(trash.deleteRows(anyString(), anyString(), anyLong(), anyLong())).thenReturn(0);   // 账户行没删掉
        var svc = new AccountTrashService(trash, pa, mock(AuditLogService.class), mock(ApplicationEventPublisher.class), json);
        assertThatThrownBy(() -> svc.delete(FAM, ME, plan(), "f")).hasMessageContaining("整个删除已撤回");
        verify(trash, never()).insertOwned(anyLong(), any());
    }

    @Test
    void restore_putsRowsBack_dropsTheRewrites_andListsWhatCouldNotComeBack() throws Exception {
        AccountTrashMapper trash = mock(AccountTrashMapper.class);
        PrincipalAdjustmentMapper pa = mock(PrincipalAdjustmentMapper.class);
        Map<String, List<Map<String, Object>>> payload = new LinkedHashMap<>();
        payload.put("transfer", List.of(
                new LinkedHashMap<>(Map.of("id", 11, "period_id", 101, "from_account_id", (int) X, "to_account_id", (int) WAGE, "amount", "111.00")),
                new LinkedHashMap<>(Map.of("id", 12, "period_id", 102, "from_account_id", (int) YEB, "to_account_id", (int) X, "amount", "112.00"))));
        payload.put("goal_account", List.of(new LinkedHashMap<>(Map.of("goal_id", 5, "account_id", (int) X))));
        payload.put("account", List.of(new LinkedHashMap<>(Map.of("id", (int) X, "family_id", 1, "display_name", "测试账户"))));
        var rewrites = new AccountTrashService.Rewrites(
                List.of(new AccountTrashService.PaRewrite(801, 11, WAGE, "工资卡"), new AccountTrashService.PaRewrite(802, 12, YEB, "余额宝")),
                Map.of(77L, X), Map.of(31L, 11L));
        when(trash.findFull(FAM, 3L)).thenReturn(Optional.of(AccountTrash.builder().id(3L).familyId(FAM).accountId(X).accountName("测试账户")
                .payload(json.writeValueAsString(payload)).rewrites(json.writeValueAsString(rewrites)).deletedAt(LocalDateTime.now()).build()));
        when(trash.accountExists(FAM, X)).thenReturn(0);
        when(trash.accountExists(FAM, WAGE)).thenReturn(1);
        when(trash.accountExists(FAM, YEB)).thenReturn(0);           // 余额宝后来被删了
        when(trash.accountExists(FAM, 77L)).thenReturn(1);
        when(trash.periodExists(eq(FAM), anyLong())).thenReturn(1);
        when(trash.goalExists(FAM, 5L)).thenReturn(0);               // 目标后来被删了
        when(trash.columnsOf(anyString())).thenAnswer(inv -> new ArrayList<>(List.of("id", "period_id", "from_account_id", "to_account_id",
                "amount", "goal_id", "account_id", "family_id", "display_name")));
        when(trash.markRestored(eq(FAM), eq(3L), eq(ME), any())).thenReturn(1);
        var svc = new AccountTrashService(trash, pa, mock(AuditLogService.class), mock(ApplicationEventPublisher.class), json);

        var r = svc.restore(FAM, ME, 3L);

        verify(trash).insertRow(eq("account"), anyList(), anyList());
        verify(trash, atLeastOnce()).insertRow(eq("transfer"), anyList(), anyList());
        assertThat(r.rows()).as("账户 + 工资卡那笔划转").isEqualTo(2);
        verify(pa).deleteSystem(FAM, 801L);                          // 工资卡那笔划转回来了 → 改记拿掉
        verify(pa, never()).deleteSystem(FAM, 802L);                  // 余额宝没了 → 改记保留(对方收益仍不变)
        verify(trash).restorePaymentSource(FAM, 77L, X);
        verify(trash).setTodoTransfer(FAM, 31L, 11L);
        assertThat(r.notes()).anyMatch(n -> n.contains("余额宝") && n.contains("没还原"));
        assertThat(r.notes()).anyMatch(n -> n.contains("目标已经删了"));
    }

    @Test
    void plainValue_keepsWhatMySqlCanReadBack() {
        assertThat(AccountTrashService.plainValue(LocalDateTime.of(2026, 10, 12, 21, 40, 2))).isEqualTo("2026-10-12 21:40:02.000000");
        assertThat(AccountTrashService.plainValue(LocalDate.of(2026, 10, 1))).isEqualTo("2026-10-01");
        assertThat(AccountTrashService.plainValue(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(AccountTrashService.plainValue(Boolean.TRUE)).isEqualTo(true);
        assertThat(AccountTrashService.plainValue(null)).isNull();
    }
}
