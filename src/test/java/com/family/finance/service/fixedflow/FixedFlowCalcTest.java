package com.family.finance.service.fixedflow;

import com.family.finance.service.PeriodOpener;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.32 · 五种自动计算(PRD FR-1106 · 失败模式 ② ③)。
 *
 * <p>贷款余额按库里的存法:负数。每个算不出来的情形都要给原因,不能给一个看起来合理的 0。</p>
 */
class FixedFlowCalcTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    // ── ① 按上两月差值 —— 与「接受」同一个函数(FR-1108) ──────────────────

    @Test
    void loanDiff_sameBalanceAsAcceptLoanPrediction() {
        BigDecimal prev = d("-1190000.00"), prevPrev = d("-1195600.00");
        var r = FixedFlowCalc.loanDiff("CNY", prev, prevPrev);
        assertThat(r.ok()).isTrue();
        assertThat(r.principal()).isEqualByComparingTo("5600.00");
        assertThat(r.total()).isEqualByComparingTo("5600.00");
        assertThat(r.interest()).as("方式 ① 不知道月供,利息算不出来就不记(维护者选 A)").isNull();
        // 写进贷款的余额 = 今天点「接受」写进去的那个数
        assertThat(FixedFlowCalc.loanDiffBalance(prev, prevPrev))
                .isEqualByComparingTo(PeriodOpener.predictLoanBalance(prev, prevPrev))
                .isEqualByComparingTo(prev.add(r.principal()));
        assertThat(r.formula()).contains("上月").contains("上上月");
    }

    @Test
    void loanDiff_lastPaymentClampedToZero() {
        // 还剩 3,000、上月还了 5,000 → 预测会变正 → 夹到 0:这期本金 = 3,000,不会超还
        var r = FixedFlowCalc.loanDiff("CNY", d("-3000"), d("-8000"));
        assertThat(r.principal()).isEqualByComparingTo("3000.00");
        assertThat(FixedFlowCalc.loanDiffBalance(d("-3000"), d("-8000"))).isEqualByComparingTo("0");
    }

    @Test
    void loanDiff_cannotComputeIsExplained() {
        assertThat(FixedFlowCalc.loanDiff("CNY", null, null).problem()).contains("没有上月余额");
        assertThat(FixedFlowCalc.loanDiff("CNY", d("-1000"), null).problem()).contains("只有一期");
        assertThat(FixedFlowCalc.loanDiff("CNY", d("-1000"), d("-1000")).problem()).contains("一样");
        assertThat(FixedFlowCalc.loanDiff("CNY", d("0"), d("-500")).problem()).contains("还清");
        // 欠得更多(差值为负)也不许给出「还款」
        assertThat(FixedFlowCalc.loanDiff("CNY", d("-1200"), d("-1000")).ok()).isFalse();
    }

    // ── ② 月供按年利率拆本金 / 利息 ────────────────────────────────────

    @Test
    void loanRate_splitsInterestAndPrincipal() {
        var r = FixedFlowCalc.loanRate("CNY", d("-1186700"), d("3.93"), d("6280"));
        assertThat(r.ok()).isTrue();
        // 利息 = 1,186,700 × 3.93% ÷ 12 = 3,886.44(HALF_UP 到分)
        assertThat(r.interest()).isEqualByComparingTo("3886.44");
        assertThat(r.principal()).isEqualByComparingTo("2393.56");
        assertThat(r.principal().add(r.interest())).isEqualByComparingTo(r.total()).isEqualByComparingTo("6280");
    }

    @Test
    void loanRate_wholeYearOfEqualInstallments_paysDownAndNeverNegativeInterest() {
        // 等额本息一整年:每期按上月末真实欠款重算一次,利息递减、本金递增、本金 + 利息恒等于月供
        BigDecimal owed = d("-100000.00");
        BigDecimal lastInterest = null;
        for (int m = 0; m < 12; m++) {
            var r = FixedFlowCalc.loanRate("CNY", owed, d("4.2"), d("8526.35"));
            assertThat(r.ok()).as("第 %d 期", m + 1).isTrue();
            assertThat(r.interest().signum()).isPositive();
            if (lastInterest != null) assertThat(r.interest()).isLessThan(lastInterest);
            assertThat(r.principal().add(r.interest())).isEqualByComparingTo(r.total());
            lastInterest = r.interest();
            owed = owed.add(r.principal());
        }
        assertThat(owed.abs()).as("一年后基本还清(差几分是四舍五入)").isLessThan(d("1.00"));
    }

    @Test
    void loanRate_lastPeriodOnlyPaysWhatIsLeft() {
        var r = FixedFlowCalc.loanRate("CNY", d("-1000"), d("6"), d("5000"));
        assertThat(r.principal()).isEqualByComparingTo("1000.00");
        assertThat(r.interest()).isEqualByComparingTo("5.00");
        assertThat(r.total()).isEqualByComparingTo("1005.00");
    }

    @Test
    void loanRate_refusesNonsense() {
        assertThat(FixedFlowCalc.loanRate("CNY", d("-1000000"), null, d("6000")).problem()).contains("年利率");
        assertThat(FixedFlowCalc.loanRate("CNY", d("-1000000"), d("0"), d("6000")).problem()).contains("年利率");
        assertThat(FixedFlowCalc.loanRate("CNY", d("-1000000"), d("12"), d("5000")).problem())
                .as("月供不够利息 —— 本金会是负的").contains("不够");
        assertThat(FixedFlowCalc.loanRate("CNY", d("0"), d("4"), d("100")).problem()).contains("还清");
        assertThat(FixedFlowCalc.loanRate("CNY", null, d("4"), d("100")).problem()).contains("上月余额");
    }

    // ── ③ 信用卡全额还上期 · ④ 保单 · ⑤ 近 3 期平均 ──────────────────

    @Test
    void cardFull_isLastMonthDebt() {
        assertThat(FixedFlowCalc.cardFull("CNY", d("-8642.10")).total()).isEqualByComparingTo("8642.10");
        assertThat(FixedFlowCalc.cardFull("CNY", d("0")).ok()).isFalse();
        assertThat(FixedFlowCalc.cardFull("CNY", d("12")).ok()).isFalse();
        assertThat(FixedFlowCalc.cardFull("CNY", null).ok()).isFalse();
    }

    @Test
    void policy_premiumOrExplained() {
        assertThat(FixedFlowCalc.policy("CNY", d("12000")).total()).isEqualByComparingTo("12000");
        assertThat(FixedFlowCalc.policy("CNY", null).problem()).contains("保单");
    }

    @Test
    void average_ofRecentThree_newestFirst() {
        var r = FixedFlowCalc.average("CNY", List.of(d("341"), d("288"), d("312"), d("999")), d("300"));
        assertThat(r.total()).isEqualByComparingTo(d("941").divide(d("3"), 2, RoundingMode.HALF_UP));
        assertThat(r.formula()).contains("最近 3 次");
        // 不满 3 次按几次算
        assertThat(FixedFlowCalc.average("CNY", List.of(d("100"), d("200")), d("1")).total()).isEqualByComparingTo("150");
        // 一次都没有 → 先按起始金额
        assertThat(FixedFlowCalc.average("CNY", List.of(), d("300")).total()).isEqualByComparingTo("300");
        assertThat(FixedFlowCalc.average("CNY", List.of(), null).ok()).isFalse();
    }
}
