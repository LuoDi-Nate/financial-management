package com.family.finance.service.entry;

import com.family.finance.domain.account.Account;
import com.family.finance.domain.snapshot.PeriodSnapshot;
import com.family.finance.repository.AccountMapper;
import com.family.finance.repository.SnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v1.20 FR-450~453 · 改动之后那句提示。
 *
 * <p><b>正反两面都要钉</b>:该出现的要出现,不该出现的<b>绝不能</b>出现 ——
 * 提示一多就会被当噪声划过去,那这一版就白做了(TDD §九 失败模式 ⑤)。</p>
 */
class BalanceGuardServiceTest {

    private SnapshotMapper snapshots;
    private AccountMapper accounts;
    private BalanceGuardService guard;

    private static PeriodSnapshot snap(String balance, LocalDateTime submittedAt) {
        PeriodSnapshot s = new PeriodSnapshot();
        s.setEndBalance(new BigDecimal(balance));
        s.setSubmittedAt(submittedAt);
        return s;
    }

    @BeforeEach
    void setUp() {
        snapshots = mock(SnapshotMapper.class);
        accounts = mock(AccountMapper.class);
        Account a = new Account();
        a.setId(1L);
        a.setDisplayName("支付宝-余额宝");
        when(accounts.findById(1L)).thenReturn(Optional.of(a));
        guard = new BalanceGuardService(snapshots, accounts);
    }

    @Test
    @DisplayName("已校准 + 余额真的变了 → 说清校准时间与前后值")
    void warnsWhenCalibratedBalanceChanged() {
        when(snapshots.findByPeriodAndAccount(9L, 1L))
                .thenReturn(Optional.of(snap("1000", LocalDateTime.of(2026, 9, 2, 10, 0))));
        var before = guard.snapshot(1L, 9L);
        when(snapshots.findByPeriodAndAccount(9L, 1L))
                .thenReturn(Optional.of(snap("1777", LocalDateTime.of(2026, 9, 2, 10, 0))));

        String note = guard.afterNote(before, 9L);

        assertThat(note).contains("支付宝-余额宝").contains("09-02").contains("校准过");
        assertThat(note).contains("1,000").contains("1,777");
    }

    @Test
    @DisplayName("已校准但余额没变 → 不吭声。改分类、改备注这类不该弹提示")
    void silentWhenBalanceUnchanged() {
        when(snapshots.findByPeriodAndAccount(9L, 1L))
                .thenReturn(Optional.of(snap("1000", LocalDateTime.of(2026, 9, 2, 10, 0))));
        var before = guard.snapshot(1L, 9L);

        assertThat(guard.afterNote(before, 9L)).isNull();
    }

    @Test
    @DisplayName("本月没填余额 → 换成「这笔改动不进本期净资产变化」—— 这是提交者困惑的另一半")
    void explainsWhenAccountNotFilledThisPeriod() {
        when(snapshots.findByPeriodAndAccount(9L, 1L)).thenReturn(Optional.empty());
        var before = guard.snapshot(1L, 9L);

        String note = guard.afterNote(before, 9L);

        assertThat(note).contains("还没填余额").contains("本期净资产变化");
    }

    @Test
    @DisplayName("拿不到账户时宁可不提示 —— 提示错的账户比不提示更糟")
    void silentWhenAccountUnknown() {
        assertThat(guard.snapshot(null, 9L)).isNull();
        assertThat(guard.afterNote(null, 9L)).isNull();
    }

    @Test
    @DisplayName("文案是纯文本 flash,不许带 markdown 记号")
    void noMarkdownInUserFacingText() {
        when(snapshots.findByPeriodAndAccount(9L, 1L))
                .thenReturn(Optional.of(snap("1000", LocalDateTime.of(2026, 9, 2, 10, 0))));
        var before = guard.snapshot(1L, 9L);
        when(snapshots.findByPeriodAndAccount(9L, 1L))
                .thenReturn(Optional.of(snap("2000", LocalDateTime.of(2026, 9, 2, 10, 0))));

        assertThat(guard.afterNote(before, 9L)).doesNotContain("**").doesNotContain("<b>");
    }
}
