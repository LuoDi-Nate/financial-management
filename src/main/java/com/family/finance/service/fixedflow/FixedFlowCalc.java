package com.family.finance.service.fixedflow;

import com.family.finance.service.MoneyFormat;
import com.family.finance.service.PeriodOpener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * v1.32 · 固定收支的五种「自动计算」(PRD FR-1106)—— 纯函数,不碰库。
 *
 * <p>算出来的数<b>只填在卡片上</b>,写不写仍由人点「确认」(FR-1107)。所以这里每个方法除了数,
 * 还要给出<b>算式</b>(卡片上「按上月 A 与上上月 B 的差值」那句)和<b>算不出来的原因</b>——
 * 算不出来时宁可让卡片上写「算不出来,因为 …」,也不许给一个看起来合理的 0。</p>
 *
 * <p>贷款余额一律按库里的存法:<b>负数</b>(欠 100 万 = −1,000,000)。</p>
 */
public final class FixedFlowCalc {

    private FixedFlowCalc() {
    }

    /**
     * 一次计算的结果。
     *
     * @param total     这一笔一共多少钱(卡片上的金额;划转 / 收入 / 支出就是它)
     * @param principal 还款里的本金部分(只有「还款 · 贷款月供」有;其余为 null)
     * @param interest  还款里的利息部分(只有方式 ② 有;方式 ① 算不出利息 → null)
     * @param formula   卡片上「算」点开看到的算式
     * @param problem   算不出来的原因;非 null 时卡片上不给「确认」
     */
    public record Result(BigDecimal total, BigDecimal principal, BigDecimal interest,
                         String formula, String problem) {
        public boolean ok() { return problem == null; }

        static Result fail(String why) { return new Result(null, null, null, null, why); }
    }

    /**
     * ① 按上月和上上月的差值(还款 · 贷款月供的默认)—— <b>就是今天贷款提示那套</b>。
     *
     * <p>本金 = 预计本期末欠款 − 上月末欠款,其中「预计本期末欠款」直接调
     * {@link PeriodOpener#predictLoanBalance}(不另写一份,FR-1108:与点「接受」逐分相同)。
     * 只记本金(维护者 2026-10-12 选 A):这个方式不知道月供,利息算不出来就不记。</p>
     */
    public static Result loanDiff(String ccy, BigDecimal prev, BigDecimal prevPrev) {
        if (prev == null) return Result.fail("这笔贷款还没有上月余额,算不出来");
        BigDecimal predicted = PeriodOpener.predictLoanBalance(prev, prevPrev);
        BigDecimal principal = predicted.subtract(prev).setScale(2, RoundingMode.HALF_EVEN);
        if (prevPrev == null) {
            return Result.fail("只有一期余额,看不出每月还多少 —— 下个月就能算,或者改成固定金额");
        }
        if (principal.signum() <= 0) {
            return Result.fail(prev.signum() >= 0
                    ? "这笔贷款已经还清了"
                    : "上月和上上月欠款一样(" + MoneyFormat.format2(ccy, prev) + "),看不出每月还多少");
        }
        String formula = "按上月 " + MoneyFormat.format2(ccy, prev) + " 与上上月 "
                + MoneyFormat.format2(ccy, prevPrev) + " 的差值,本月预计还到 " + MoneyFormat.format2(ccy, predicted);
        return new Result(principal, principal, null, formula, null);
    }

    /** ① 的「预计本期末欠款」—— 确认时写进贷款余额的就是它(与 acceptLoanPrediction 同一个数)。 */
    public static BigDecimal loanDiffBalance(BigDecimal prev, BigDecimal prevPrev) {
        return PeriodOpener.predictLoanBalance(prev, prevPrev);
    }

    /**
     * ② 月供固定,按年利率拆本金 / 利息。
     *
     * <p>利息 = 上月末欠款 × 年利率 ÷ 12(四舍五入到分 —— 银行算利息的习惯是 HALF_UP),
     * 本金 = 月供 − 利息。最后一期欠款不够一个本金时,本金 = 剩下的欠款,这一期总额 = 本金 + 利息。
     * 等额本息 / 等额本金都适用:每期都按「上月末真实欠款」重算一次,不依赖合同里的还款计划表。</p>
     */
    public static Result loanRate(String ccy, BigDecimal prev, BigDecimal annualRatePct, BigDecimal payment) {
        if (annualRatePct == null || annualRatePct.signum() <= 0) {
            return Result.fail("贷款账户上没填年利率 —— 去账户编辑页填上,或改用「按上两月差值」");
        }
        if (payment == null || payment.signum() <= 0) return Result.fail("没填月供");
        if (prev == null) return Result.fail("这笔贷款还没有上月余额,算不出利息");
        BigDecimal owed = prev.negate();                 // 欠款(正数)
        if (owed.signum() <= 0) return Result.fail("这笔贷款已经还清了");
        BigDecimal interest = owed.multiply(annualRatePct)
                .divide(new BigDecimal("1200"), 2, RoundingMode.HALF_UP);
        BigDecimal principal = payment.subtract(interest).setScale(2, RoundingMode.HALF_EVEN);
        if (principal.signum() <= 0) {
            return Result.fail("月供 " + MoneyFormat.format2(ccy, payment) + " 还不够这个月的利息 "
                    + MoneyFormat.format2(ccy, interest) + " —— 检查一下月供或年利率");
        }
        BigDecimal total = payment.setScale(2, RoundingMode.HALF_EVEN);
        if (principal.compareTo(owed) > 0) {             // 最后一期
            principal = owed.setScale(2, RoundingMode.HALF_EVEN);
            total = principal.add(interest);
        }
        String formula = "月供 " + MoneyFormat.format2(ccy, payment) + " · 利息 = 上月欠款 "
                + MoneyFormat.format2(ccy, owed) + " × " + annualRatePct.stripTrailingZeros().toPlainString()
                + "% ÷ 12 = " + MoneyFormat.format2(ccy, interest) + " · 本金 = " + MoneyFormat.format2(ccy, principal);
        return new Result(total, principal, interest, formula, null);
    }

    /** ③ 全额还上期账单(还款 · 信用卡账单):金额 = 信用卡上月末欠款。 */
    public static Result cardFull(String ccy, BigDecimal prev) {
        if (prev == null) return Result.fail("这张卡还没有上月余额");
        if (prev.signum() >= 0) return Result.fail("上月末这张卡不欠钱,这期不用还");
        BigDecimal owed = prev.negate().setScale(2, RoundingMode.HALF_EVEN);
        return new Result(owed, null, null, "信用卡上月末欠款 " + MoneyFormat.format2(ccy, owed), null);
    }

    /** ④ 按保单每期保费(交保费)。 */
    public static Result policy(String ccy, BigDecimal premium) {
        if (premium == null || premium.signum() <= 0) {
            return Result.fail("保单登记里没填每期保费 —— 去保险账户的保单登记里补上,或改成固定金额");
        }
        BigDecimal p = premium.setScale(2, RoundingMode.HALF_EVEN);
        return new Result(p, null, null, "保单每期保费 " + MoneyFormat.format2(ccy, p), null);
    }

    /**
     * ⑤ 近 3 期平均(收入 / 支出):这一条<b>最近 3 次确认</b>的平均;不满 3 次按几次算;
     * 一次都没有 → 先按固定金额({@code fallback})。
     *
     * @param recent 最近确认的金额,新的在前;只取前 3 个
     */
    public static Result average(String ccy, List<BigDecimal> recent, BigDecimal fallback) {
        List<BigDecimal> xs = recent == null ? List.of()
                : recent.stream().filter(java.util.Objects::nonNull).limit(3).toList();
        if (xs.isEmpty()) {
            if (fallback == null || fallback.signum() <= 0) return Result.fail("还没确认过,也没填起始金额");
            BigDecimal f = fallback.setScale(2, RoundingMode.HALF_EVEN);
            return new Result(f, null, null, "还没确认过 · 先按 " + MoneyFormat.format2(ccy, f), null);
        }
        BigDecimal sum = xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avg = sum.divide(BigDecimal.valueOf(xs.size()), 2, RoundingMode.HALF_UP);
        StringBuilder f = new StringBuilder("最近 ").append(xs.size()).append(" 次平均(");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) f.append(" / ");
            f.append(MoneyFormat.format2(ccy, xs.get(i)));
        }
        f.append(")");
        return new Result(avg, null, null, f.toString(), null);
    }
}
