package com.family.finance.domain.stock;

import com.family.finance.domain.ledger.LedgerSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.30 · 持仓数量变动的原因 → 时间线来源 / 是否动余额 */
class ShareEventReasonTest {

    @Test
    void 来源由原因决定_货基结转是自动_截图是截图_认不出不猜成手动() {
        assertThat(ShareEventReason.MMF_ACCRUAL.source()).isEqualTo(LedgerSource.SYNC_FUND_NAV);
        assertThat(ShareEventReason.IMPORT.source()).isEqualTo(LedgerSource.IMPORT_SCREENSHOT);
        assertThat(ShareEventReason.CASH_BUY.source()).isEqualTo(LedgerSource.MANUAL);
        assertThat(ShareEventReason.of("SOMETHING_NEW").source()).isEqualTo(LedgerSource.UNKNOWN);
        for (ShareEventReason r : ShareEventReason.values()) {
            assertThat(r.source()).as(r + " 必须有来源").isNotNull();
        }
    }

    @Test
    void 现金联动与改为自动不动余额() {
        assertThat(ShareEventReason.CASH_BUY.movesBalance()).isFalse();
        assertThat(ShareEventReason.CASH_REDEEM.movesBalance()).isFalse();
        assertThat(ShareEventReason.CONVERT.movesBalance()).isFalse();
        assertThat(ShareEventReason.MMF_ACCRUAL.movesBalance()).isTrue();
        assertThat(ShareEventReason.MANUAL_EDIT.movesBalance()).isTrue();
    }
}
