package com.family.finance.factview;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.domain.account.AccountLiquidity;
import com.family.finance.domain.account.AccountType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.34.2 · 新账户<b>第一期</b>挂在它身上的收入 / 支出不进开账基线(维护者 2026-10-15 拍板)。
 *
 * <p>这些流水已经在期末余额里、又各自记进了「人赚」;留在开账基线里,「钱赚」就少算(收入)/ 虚高(支出)
 * 同样的数。由来:v1.33 删除账户真实用例(e2e flow 50)—— 08 的钱赚比建 N 之前少 200。
 * <b>下面的金额全是编的。</b></p>
 */
class OpeningBaselineFlowTest {

    private static final BigDecimal Z = BigDecimal.ZERO;

    private static AccountPeriodFact fact(AccountType type, String end, String in, String income, String expense) {
        BigDecimal e = new BigDecimal(end);
        return new AccountPeriodFact(
                24L, "新账户", type, type.isLiability() ? AccountClass.LIABILITY : AccountClass.ASSET,
                AccountLiquidity.LIQUID, "CNY", null, 0, 5L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                null, e, null, e,
                new BigDecimal(income), new BigDecimal(income), new BigDecimal(expense), new BigDecimal(expense),
                new BigDecimal(in), new BigDecimal(in), Z, Z,
                null, null, BigDecimal.ONE);
    }

    @Test
    void 转进1000加200工资_期末1200_开账基线是0() {
        // flow 50 的原样:A → N 1,000、N 工资 200。原来 = 1200 − 1000 = 200(工资既进人赚又当开账基线)
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.CASH, "1200", "1000", "200", "0")))
                .isEqualByComparingTo("0");
    }

    @Test
    void 期初本有500_这期花了100_期末400_开账基线是500() {
        // 原来 = 期末 400(没有转入就原样)→ 这 100 的支出被当成「钱赚」虚高 100
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.CASH, "400", "0", "0", "100")))
                .isEqualByComparingTo("500");
    }

    @Test
    void 只有挂账户的收入_没有转入_也扣() {
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.CASH, "5200", "0", "200", "0")))
                .isEqualByComparingTo("5000");
    }

    @Test
    void 扣出来是负数_说明这期亏了_资产类不出现负的存量() {
        // 记了 200 工资、期末只剩 150 → 那 50 是亏的,留在钱赚里
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.STOCK, "150", "0", "200", "0")))
                .isEqualByComparingTo("0");
    }

    @Test
    void 负债_首期刷卡消费_带进来的是刷之前的欠款() {
        // 新纳入一张信用卡,上面本来欠 3000,这期又刷了 500 → 期末 −3500;带进来的是 −3000
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.LOAN, "-3500", "0", "0", "500")))
                .isEqualByComparingTo("-3000");
    }

    @Test
    void 既没有转入也没有挂账户收支_与原来逐字相同() {
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.WEALTH, "654321.00", "0", "0", "0")))
                .isEqualByComparingTo("654321.00");
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.WEALTH, "-2468.10", "0", "0", "0")))
                .as("怪数据(资产期末为负)也按原样 —— 不借这次改动顺手改口径").isEqualByComparingTo("-2468.10");
    }

    @Test
    void 收支正好相抵_净额为0_与原来相同() {
        assertThat(FactViewServiceImpl.openingOf(fact(AccountType.CASH, "8000", "0", "300", "300")))
                .isEqualByComparingTo("8000");
    }
}
