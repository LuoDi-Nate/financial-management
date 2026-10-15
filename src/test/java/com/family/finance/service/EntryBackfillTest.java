package com.family.finance.service;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.flow.CashFlow;
import com.family.finance.domain.flow.CashFlowKind;
import com.family.finance.domain.ledger.LedgerSource;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.domain.transfer.Transfer;
import com.family.finance.repository.*;
import com.family.finance.service.backfill.BackfillContext;
import com.family.finance.service.config.FamilyConfigService;
import com.family.finance.service.stock.StockHoldingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * v1.34.2 · 往「之后已有人确认过余额」的账期里补记(维护者 2026-10-15 拍板:每次问,默认 A)。
 *
 * <p>只有「这一期这个账户的余额确认过 + 之后也有确认过的余额」时才按 A / B 处理;
 * A = 只补记、余额不动;B = 这一期和之后每一期一起挪;其余情况与原来逐字相同。<b>金额全是编的。</b></p>
 */
class EntryBackfillTest {

    private static final long FAM = 1L, MEM = 7L, PID = 100L, A = 10L, B = 11L;
    private static final LocalDate START = LocalDate.of(2026, 8, 1);

    private AccountMapper accountMapper;
    private SnapshotMapper snapshotMapper;
    private CashFlowMapper cashFlowMapper;
    private TransferMapper transferMapper;
    private EntryService svc;

    @BeforeEach
    void setUp() {
        accountMapper = mock(AccountMapper.class);
        PeriodMapper periodMapper = mock(PeriodMapper.class);
        snapshotMapper = mock(SnapshotMapper.class);
        cashFlowMapper = mock(CashFlowMapper.class);
        transferMapper = mock(TransferMapper.class);
        svc = new EntryService(
                accountMapper, mock(com.family.finance.service.member.MemberDirectory.class), periodMapper, snapshotMapper,
                mock(SnapshotTodoMapper.class), mock(org.springframework.context.ApplicationEventPublisher.class),
                cashFlowMapper, transferMapper, mock(AuditLogService.class),
                mock(FamilyConfigService.class), mock(StockValuationEventMapper.class),
                mock(CashFlowCategoryMapper.class), mock(StockHoldingService.class),
                mock(com.family.finance.service.stock.AccountValuationService.class),
                mock(com.family.finance.service.expense.ExpenseCategoryService.class),
                mock(com.family.finance.repository.PrincipalAdjustmentMapper.class));
        Period p = Period.builder().id(PID).familyId(FAM).status(PeriodStatus.OPEN)
                .periodStart(START).periodEnd(LocalDate.of(2026, 8, 31)).build();
        when(periodMapper.findById(anyLong(), eq(PID))).thenReturn(Optional.of(p));
        for (long id : new long[]{A, B}) {
            when(accountMapper.findById(anyLong(), eq(id))).thenReturn(Optional.of(
                    Account.builder().id(id).familyId(FAM).type(AccountType.CASH).currency("CNY").displayName("卡" + id).build()));
        }
    }

    @AfterEach
    void tearDown() { BackfillContext.clear(); }

    /** 这一期这个账户的快照(来源 tag)+ 之后有没有确认过的余额 */
    private void state(long account, String sourceTag, String end, boolean confirmedLater) {
        when(snapshotMapper.findByPeriodAndAccount(FAM, PID, account)).thenReturn(sourceTag == null ? Optional.empty()
                : Optional.of(PeriodSnapshot.builder().periodId(PID).accountId(account)
                        .endBalance(new BigDecimal(end)).sourceTag(sourceTag).build()));
        when(snapshotMapper.countConfirmedAfter(FAM, account, START)).thenReturn(confirmedLater ? 2 : 0);
    }

    private CashFlow insertedCashFlow() {
        ArgumentCaptor<CashFlow> cap = ArgumentCaptor.forClass(CashFlow.class);
        verify(cashFlowMapper).insertOwned(eq(FAM), cap.capture());
        return cap.getValue();
    }

    private BigDecimal upsertedEnd(long account) {
        ArgumentCaptor<PeriodSnapshot> cap = ArgumentCaptor.forClass(PeriodSnapshot.class);
        verify(snapshotMapper, atLeastOnce()).upsertOwned(eq(FAM), cap.capture());
        return cap.getAllValues().stream().filter(s -> s.getAccountId() == account)
                .reduce((x, y) -> y).map(PeriodSnapshot::getEndBalance).orElse(null);
    }

    @Test
    void 之后没有确认过的余额_与原来相同_改这一期_不挪不问() {
        state(A, LedgerSource.MANUAL.name(), "5000", false);
        svc.addCashFlow(FAM, MEM, PID, A, CashFlowKind.EXPENSE, "consumption", new BigDecimal("100"), null);
        assertThat(upsertedEnd(A)).isEqualByComparingTo("4900");
        verify(snapshotMapper, never()).shiftAfter(anyLong(), anyLong(), any(), any(), any());
        assertThat(insertedCashFlow().getBackfillMode()).isNull();
    }

    @Test
    void 两头都确认过_没选_默认A_只补记余额不动() {
        state(A, LedgerSource.MANUAL.name(), "5000", true);
        svc.addCashFlow(FAM, MEM, PID, A, CashFlowKind.EXPENSE, "consumption", new BigDecimal("100"), null);
        verify(snapshotMapper, never()).upsertOwned(anyLong(), any());
        verify(snapshotMapper, never()).shiftAfter(anyLong(), anyLong(), any(), any(), any());
        assertThat(insertedCashFlow().getBackfillMode()).isEqualTo("KEEP");
    }

    @Test
    void 两头都确认过_选B_这一期和之后每期一起挪() {
        state(A, LedgerSource.MANUAL.name(), "5000", true);
        BackfillContext.request("SHIFT");
        svc.addCashFlow(FAM, MEM, PID, A, CashFlowKind.EXPENSE, "consumption", new BigDecimal("100"), null);
        assertThat(upsertedEnd(A)).isEqualByComparingTo("4900");
        verify(snapshotMapper).shiftAfter(eq(FAM), eq(A), eq(START), argThat(d -> d.compareTo(new BigDecimal("-100")) == 0), anyString());
        assertThat(insertedCashFlow().getBackfillMode()).isEqualTo("SHIFT");
    }

    @Test
    void 这一期是系统代填的延续值_不问_照旧改这一期() {
        // 之后有确认过的余额,但这一期的余额不是「当时看到的数」→ 加上去才对,之后的真实观察本来就包含它
        state(A, LedgerSource.CARRIED_FORWARD.name(), "5000", true);
        svc.addCashFlow(FAM, MEM, PID, A, CashFlowKind.INCOME, "salary", new BigDecimal("200"), null);
        assertThat(upsertedEnd(A)).isEqualByComparingTo("5200");
        verify(snapshotMapper, never()).shiftAfter(anyLong(), anyLong(), any(), any(), any());
        assertThat(insertedCashFlow().getBackfillMode()).isNull();
    }

    @Test
    void 这一期还没有快照_不问_照旧() {
        state(A, null, null, true);
        svc.addCashFlow(FAM, MEM, PID, A, CashFlowKind.INCOME, "salary", new BigDecimal("200"), null);
        assertThat(upsertedEnd(A)).isEqualByComparingTo("200");
        assertThat(insertedCashFlow().getBackfillMode()).isNull();
    }

    @Test
    void 删一笔按B记的_之后各期挪回去() {
        state(A, LedgerSource.MANUAL.name(), "4900", true);
        when(cashFlowMapper.findById(FAM, 55L)).thenReturn(Optional.of(CashFlow.builder().id(55L).periodId(PID).accountId(A)
                .kind(CashFlowKind.EXPENSE).amount(new BigDecimal("100")).backfillMode("SHIFT").build()));
        svc.softDeleteCashFlow(FAM, MEM, 55L);
        assertThat(upsertedEnd(A)).isEqualByComparingTo("5000");
        verify(snapshotMapper).shiftAfter(eq(FAM), eq(A), eq(START), argThat(d -> d.compareTo(new BigDecimal("100")) == 0), anyString());
    }

    @Test
    void 删一笔普通记的_现在两头都确认过_默认A_不动确认过的余额() {
        state(A, LedgerSource.MANUAL.name(), "4900", true);
        when(cashFlowMapper.findById(FAM, 56L)).thenReturn(Optional.of(CashFlow.builder().id(56L).periodId(PID).accountId(A)
                .kind(CashFlowKind.EXPENSE).amount(new BigDecimal("100")).build()));
        svc.softDeleteCashFlow(FAM, MEM, 56L);
        verify(snapshotMapper, never()).upsertOwned(anyLong(), any());
        verify(snapshotMapper, never()).shiftAfter(anyLong(), anyLong(), any(), any(), any());
        verify(cashFlowMapper).softDelete(FAM, 56L);
    }

    @Test
    void 划转只有一端两头都确认过_那一端按A_另一端照旧() {
        state(A, LedgerSource.MANUAL.name(), "5000", true);      // 老账户:当时的余额已经包含了这笔转出
        state(B, null, null, false);                             // 新账户:这一期还没有余额
        svc.addTransfer(FAM, MEM, PID, A, B, new BigDecimal("1000"), null, null, false);
        assertThat(upsertedEnd(B)).isEqualByComparingTo("1000");
        ArgumentCaptor<PeriodSnapshot> cap = ArgumentCaptor.forClass(PeriodSnapshot.class);
        verify(snapshotMapper, atLeastOnce()).upsertOwned(eq(FAM), cap.capture());
        assertThat(cap.getAllValues()).noneMatch(s -> s.getAccountId() == A);
        ArgumentCaptor<Transfer> t = ArgumentCaptor.forClass(Transfer.class);
        verify(transferMapper).insertOwned(eq(FAM), t.capture());
        assertThat(t.getValue().getBackfillMode()).isEqualTo("KEEP");
    }

    @Test
    void 一次请求记两笔_第二笔不沾第一笔的标记() {
        state(A, LedgerSource.MANUAL.name(), "5000", true);
        state(B, LedgerSource.MANUAL.name(), "800", false);
        svc.addCashFlow(FAM, MEM, PID, A, CashFlowKind.EXPENSE, "consumption", new BigDecimal("100"), null);
        svc.addCashFlow(FAM, MEM, PID, B, CashFlowKind.EXPENSE, "consumption", new BigDecimal("50"), null);
        ArgumentCaptor<CashFlow> cap = ArgumentCaptor.forClass(CashFlow.class);
        verify(cashFlowMapper, times(2)).insertOwned(eq(FAM), cap.capture());
        assertThat(cap.getAllValues().get(0).getBackfillMode()).isEqualTo("KEEP");
        assertThat(cap.getAllValues().get(1).getBackfillMode()).isNull();
    }

    @Test
    void 参数乱填当没选() {
        assertThat(BackfillContext.parse("shift")).isEqualTo(BackfillContext.Mode.SHIFT);
        assertThat(BackfillContext.parse("bogus")).isNull();
        assertThat(BackfillContext.parse(null)).isNull();
    }
}
