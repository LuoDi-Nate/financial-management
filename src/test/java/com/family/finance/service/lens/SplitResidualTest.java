package com.family.finance.service.lens;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.20 · 穿透拆分的「余数归位」。
 *
 * <p>它修的不是「差一分钱不好看」,而是<b>两条聚合路径给出不同的总资产</b>:
 * 透视按拆分后的份额加、KPI 按账户原值加,逐份四舍五入之后两者对不上。
 * 差多少<b>跟数据分布走</b>(有没有拆、拆几份、余数落在哪),所以历史上时红时绿
 * (见过 0.01 / 0.03 / 0.06),很容易被当成偶发放过。</p>
 */
class SplitResidualTest {

    private static List<BigDecimal> parts(String... v) {
        List<BigDecimal> l = new ArrayList<>();
        for (String s : v) l.add(s == null ? null : new BigDecimal(s));
        return l;
    }

    private static BigDecimal sum(List<BigDecimal> l) {
        return l.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("拆三份逐份进位后多了 1 分 → 补回去,求和精确等于原值")
    void residualIsGivenBack() {
        var p = parts("33.34", "33.33", "33.34");     // 和 = 100.01
        LensQueryService.residualToLargest(p, new BigDecimal("100.00"));
        assertThat(sum(p)).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("残差补给份额最大的那一份 —— 相对误差最小,也最不显眼")
    void residualGoesToLargestPart() {
        var p = parts("10.00", "80.00", "10.01");     // 和 = 100.01,最大的是 80.00
        LensQueryService.residualToLargest(p, new BigDecimal("100.00"));
        assertThat(p).isEqualTo(Arrays.asList(
                new BigDecimal("10.00"), new BigDecimal("79.99"), new BigDecimal("10.01")));
    }

    @Test
    @DisplayName("本来就闭合时一分不动")
    void noOpWhenAlreadyExact() {
        var p = parts("60.00", "40.00");
        LensQueryService.residualToLargest(p, new BigDecimal("100.00"));
        assertThat(p).isEqualTo(Arrays.asList(new BigDecimal("60.00"), new BigDecimal("40.00")));
    }

    @Test
    @DisplayName("负值(亏损)同样闭合 —— 取绝对值找最大份,别把符号弄丢")
    void worksForNegatives() {
        var p = parts("-33.34", "-33.33", "-33.34");
        LensQueryService.residualToLargest(p, new BigDecimal("-100.00"));
        assertThat(sum(p)).isEqualByComparingTo("-100.00");
    }

    @Test
    @DisplayName("有一份算不出来(null)时整列不动 —— 把 null 当 0 补是编数据")
    void skipsWhenAnyPartIsNull() {
        var p = parts("50.00", null);
        LensQueryService.residualToLargest(p, new BigDecimal("100.00"));
        assertThat(p.get(0)).isEqualByComparingTo("50.00");
        assertThat(p.get(1)).isNull();
    }

    @Test
    @DisplayName("总值不可算(null)时不动")
    void skipsWhenTotalIsNull() {
        var p = parts("1.00");
        LensQueryService.residualToLargest(p, null);
        assertThat(p.get(0)).isEqualByComparingTo("1.00");
    }
}
