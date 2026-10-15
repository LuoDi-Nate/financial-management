package com.family.finance.service.report;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import com.family.finance.domain.period.Period;
import com.family.finance.domain.period.PeriodStatus;
import com.family.finance.domain.period.PeriodType;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.factview.AccountPeriodFact;
import com.family.finance.factview.FactFilter;
import com.family.finance.factview.FactSlice;
import com.family.finance.factview.FactViewService;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.FamilyMapper;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.repository.SnapshotMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.34.2 · 报表封板抬头「x / y 已填 · 缺 …」:那个月结束之后才建、那个月又没有快照的账户不算「应填」
 * (维护者 2026-10-15 拍板 · prod 4 个已关账月 5 处假「缺」)。
 */
class SealedCompletenessBornAfterTest {

    private static final long FAM = 1L, PID = 8L;
    private static final LocalDate START = LocalDate.of(2026, 8, 1), END = LocalDate.of(2026, 8, 31);

    private static AccountPeriodFact row(long accountId, String name) {
        BigDecimal z = BigDecimal.ZERO;
        return new AccountPeriodFact(accountId, name, AccountType.CASH, AccountClass.ASSET, AccountLiquidity.LIQUID,
                "CNY", null, 0, PID, START, END, null, null, null, null,
                z, z, z, z, z, z, z, z, null, null, BigDecimal.ONE);
    }

    private static Account account(long id, LocalDate created) {
        return Account.builder().id(id).familyId(FAM).createdAt(created.atTime(10, 0)).build();
    }

    private static PeriodSnapshot snap(long accountId) {
        return PeriodSnapshot.builder().periodId(PID).accountId(accountId).endBalance(BigDecimal.TEN).build();
    }

    private SealedSnapshot.Completeness run(List<Account> accounts, List<PeriodSnapshot> snaps) {
        SnapshotMapper sm = mock(SnapshotMapper.class);
        AccountMapper am = mock(AccountMapper.class);
        when(sm.findByPeriod(FAM, PID)).thenReturn(snaps);
        when(am.findAllByFamily(FAM)).thenReturn(accounts);
        SealedPeriodService svc = new SealedPeriodService(mock(FactViewService.class), mock(PeriodMapper.class),
                mock(FamilyMapper.class), sm, mock(com.family.finance.service.member.MemberDirectory.class),
                null, am);
        FactSlice window = new FactSlice(
                new FactFilter(FAM, PeriodType.MONTHLY, START, END, false, null, "CNY"),
                List.of(row(1, "工资卡"), row(2, "余额宝"), row(3, "十月新卡"), row(4, "十月新卡-补过八月")),
                List.of(PID), PID);
        Period anchor = Period.builder().id(PID).familyId(FAM).periodType(PeriodType.MONTHLY)
                .periodStart(START).periodEnd(END).status(PeriodStatus.CLOSED)
                .closedAt(LocalDateTime.of(2026, 9, 2, 0, 35)).build();
        return svc.buildCompleteness(window, anchor);
    }

    @Test
    void 之后才建又没快照的账户_不算应填_也不算缺() {
        var cp = run(
                List.of(account(1, LocalDate.of(2026, 1, 5)), account(2, LocalDate.of(2026, 3, 9)),
                        account(3, LocalDate.of(2026, 10, 12)), account(4, LocalDate.of(2026, 10, 12))),
                List.of(snap(1), snap(4)));
        assertThat(cp.expected()).as("1 / 2 本来就在册;3 十月才建、八月没快照 → 不算;4 十月建但补过八月 → 算").isEqualTo(3);
        assertThat(cp.filled()).isEqualTo(2);
        assertThat(cp.missingNames()).containsExactly("余额宝").doesNotContain("十月新卡");
    }

    @Test
    void 月底当天建的账户_仍算那个月的() {
        var cp = run(List.of(account(1, LocalDate.of(2026, 1, 5)), account(2, END),
                        account(3, LocalDate.of(2026, 1, 5)), account(4, LocalDate.of(2026, 1, 5))),
                List.of(snap(1), snap(3), snap(4)));
        assertThat(cp.expected()).isEqualTo(4);
        assertThat(cp.missingNames()).containsExactly("余额宝");
    }

    @Test
    void 账户都在那个月之前建的_与原来逐字相同() {
        var cp = run(List.of(account(1, LocalDate.of(2026, 1, 5)), account(2, LocalDate.of(2026, 1, 5)),
                        account(3, LocalDate.of(2026, 1, 5)), account(4, LocalDate.of(2026, 1, 5))),
                List.of(snap(1)));
        assertThat(cp.expected()).isEqualTo(4);
        assertThat(cp.filled()).isEqualTo(1);
        assertThat(cp.missingNames()).containsExactly("余额宝", "十月新卡", "十月新卡-补过八月");
    }
}
