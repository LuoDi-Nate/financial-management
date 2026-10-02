package com.family.finance.service.explain;

import com.family.finance.domain.account.AccountClass;
import com.family.finance.factview.AccountPerformance;
import com.family.finance.factview.AllocationSlice;
import com.family.finance.factview.FactProjector;
import com.family.finance.factview.KpiSnapshot;
import com.family.finance.service.checkup.FamilyDiagnose;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 计算指标透明化 · v0.5.3。
 *
 * <p>把"计算型 KPI"(净资产 / 紧急储备 / 本月资产收益 / 月均收支 / 人赚钱赚 …)的
 * <b>真实中间数值</b>拼成一行说明,塞进 {@code _kpi-info} tooltip 的「口径」下方。
 * 用户点 ⓘ 不再只看到公式文字,而能看到「流动资产 ¥X ÷ 月均支出 ¥Y = Z 月」这样的实算。</p>
 *
 * <p>设计取舍:</p>
 * <ul>
 *   <li><b>纯展示层</b> · 只负责把已算好的 {@link BigDecimal} 格式化成串,不做任何业务计算 ——
 *       计算仍在 FactView / HouseholdCashflow 等服务里完成,本类只读结果。这样口径数值与
 *       页面 KPI 必然一致(同一份来源)。</li>
 *   <li><b>币种由调用方决定</b> · dashboard / reports KPI 区走 viewCurrency;checkup 与
 *       reports 储蓄区走家庭本位币(模板原本就硬编码 ¥)。避免一侧 view 一侧 base 漂移。</li>
 *   <li><b>不在 Thymeleaf 里做算术</b> · 历史上 SpEL 沙箱 ban 过 {@code T(BigDecimal)};
 *       一律服务端算好成串再传模板(见 [[feedback_thymeleaf_diagnosis]])。</li>
 * </ul>
 *
 * <p>XIRR / TWR 是迭代/几何解、无法写成单条四则算式;这类只展示<b>真实输入端点 + 解得值</b>,
 * 不伪造算术步骤(诚实原则)。</p>
 */
@Service
public class MetricExplainService {

    // ============================ 通用格式化(public · 可被 controller / 单测复用) ============================

    public String symbol(String ccy) {
        if (ccy == null) return "¥";
        return switch (ccy) {
            case "USD" -> "$";
            case "HKD" -> "HK$";
            default -> "¥";
        };
    }

    /** "¥1,234"(无小数 · 千分位)· null → "—" */
    public String money(String ccy, BigDecimal v) {
        if (v == null) return "—";
        return symbol(ccy) + new DecimalFormat("#,##0").format(v.setScale(0, RoundingMode.HALF_UP));
    }

    /** "+¥1,234" / "−¥1,234" / "¥0" · null → "—" */
    public String signedMoney(String ccy, BigDecimal v) {
        if (v == null) return "—";
        String body = symbol(ccy) + new DecimalFormat("#,##0").format(v.abs().setScale(0, RoundingMode.HALF_UP));
        if (v.signum() < 0) return "−" + body;
        if (v.signum() > 0) return "+" + body;
        return body;
    }

    /** 小数比率 ×100 → "+1.23%"(强制带符号 · 2 位)· null → "—" */
    public String pct2Signed(BigDecimal decimalRatio) {
        if (decimalRatio == null) return "—";
        return String.format(Locale.ROOT, "%+.2f%%", decimalRatio.doubleValue() * 100);
    }

    /** 已是百分点单位的值(如基准 5.4)→ "5.4%"(dp 位 · 不强制符号)· null → "—" */
    public String pctUnits(BigDecimal percentValue, int dp) {
        if (percentValue == null) return "—";
        return percentValue.setScale(dp, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 小数比率 ×100 → "48.0%"(dp 位 · 不强制符号)· null → "—" */
    public String pctFromRatio(BigDecimal decimalRatio, int dp) {
        if (decimalRatio == null) return "—";
        return decimalRatio.multiply(new BigDecimal("100")).setScale(dp, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 月数 "3.2" · null → "—" */
    public String months(BigDecimal v) {
        if (v == null) return "—";
        return v.setScale(1, RoundingMode.HALF_EVEN).toPlainString();
    }

    // ============================ dashboard(viewCurrency) ============================

    public Map<String, String> dashboard(KpiSnapshot k, List<AllocationSlice> allocation,
                                          List<AccountPerformance> accountRows, String ccy) {
        Map<String, String> m = new LinkedHashMap<>();
        if (k == null) return m;
        m.put("netWorth", netWorthCalc(ccy, k.totalAssets(), k.totalLiabilities(), k.netWorth()));
        m.put("totalAssets", totalAssetsCalc(ccy, allocation, k.totalAssets()));
        m.put("totalLiabilities", totalLiabilitiesCalc(ccy, accountRows, k.totalLiabilities()));
        m.put("emergency", emergencyCalc(ccy, k.liquidAssets(), k.avgExpense(), k.emergencyFundMonths()));
        // v1.10 FR-327 · 仪表盘那格已改成**实时本月**口径(锚当前期),所以 tooltip 必须跟着改 ——
        //   否则会出现"卡上显示 +46.02%、tooltip 解释 +4.51%"这种自相矛盾。
        //   checkup 页仍用锚已关账期那套(见下面的 checkup(...)),两页各说各的口径。
        m.put("monthlyPnl", liveMonthlyPnlCalc(ccy, k));
        return m;
    }

    // ============================ checkup(家庭本位币) ============================

    public Map<String, String> checkup(FamilyDiagnose d, String ccy) {
        Map<String, String> m = new LinkedHashMap<>();
        if (d == null || d.kpi() == null) return m;
        KpiSnapshot k = d.kpi();
        m.put("netWorth", netWorthCalc(ccy, k.totalAssets(), k.totalLiabilities(), k.netWorth()));
        m.put("totalAssets", totalAssetsCalc(ccy, d.allocation(), k.totalAssets()));
        m.put("totalLiabilities",
                "仅 LOAN 类型账户期末余额绝对值合计 = " + money(ccy, k.totalLiabilities()));
        // v1.6.30 · 期末取收益锚点净资产(非最后一期),并补齐 reports 页早就有的三件事:
        //   与「人赚」同口径 / 参与求解的期数 / 年化还是累计。同一指标两页两种解释是 v1.6.29 漏掉的。
        m.put("familyXirr", familyXirrCalc(ccy, k.returnAnchorNetWorth(), d.familyXirr(),
                k.returnPeriodCount(), k.returnAnchorMonth(), k.filingInProgress()));
        m.put("familyTwr", familyTwrCalc(d.familyTwr()));
        // 紧急储备:三项全取 KpiSnapshot 同源(d.emergencyMonths() == kpi.emergencyFundMonths()),
        // 保证「分子 ÷ 分母 = 结果」自洽且与 KPI 卡完全一致(勿引第二份 avgExpense 源,否则漂移)
        m.put("emergency", emergencyCalc(ccy, k.liquidAssets(), k.avgExpense(), k.emergencyFundMonths()));
        m.put("liquidAssets",
                "LIQUID 类目(CASH + 货币基金等)期末合计 = " + money(ccy, d.liquidAssets()));
        m.put("monthlyPnl", monthlyPnlCalc(ccy, k.returnAnchorNetWorth(), k.prevNetWorth(), k.lastNetInflow(),
                k.monthlyInvestReturnPct(), k.returnAnchorMonth(), k.filingInProgress()));
        m.put("ytdPnl",
                "本年逐月 (净资产变化 − 净流入) 累加 = " + signedMoney(ccy, d.cumulativeYtdPnl()));
        return m;
    }

    // ============================ reports(KPI 区 viewCurrency · 储蓄区 baseCurrency) ============================

    /**
     * @param viewCcy            报表 KPI 区币种(人赚/钱赚/XIRR/基准/TWR)
     * @param baseCcy            储蓄区币种(月均收支按本位币 PMC 存)
     * @param firstNetWorth      区间起始期净资产(基准点 · viewCcy)
     * @param lastNetWorth       区间期末净资产(viewCcy)
     * @param firstLabel         起始期标签
     * @param lastLabel          期末期标签
     * @param periodCount        区间总期数
     * @param decompCount        计入分解的期数(= 总期数 − 1 · 基准期不计)
     * @param familyXirr         家庭含收入 XIRR(小数)
     * @param familyTwr          家庭剔收入 TWR(小数)
     * @param cumulativeNetInflow 区间累计净流入(人赚 · viewCcy)
     * @param cumulativePnl       区间累计投资 PnL(钱赚 · viewCcy)
     * @param familyBenchmarkPct  家庭加权基准(百分点单位)
     * @param benchmarkAccountCount 计入加权的账户数
     * @param benchmarkTotalBalance 计入加权的总余额(viewCcy)
     * @param savingsAvailable   是否有储蓄填报数据
     * @param filledCount        近 12 月实际填报的月份数
     * @param totalMonths        回看窗口期数(通常 12)
     * @param sumIncome          已填月份收入合计(baseCcy)
     * @param sumExpense         已填月份支出合计(baseCcy)
     * @param avgIncome          月均收入(baseCcy)
     * @param avgExpense         月均支出(baseCcy)
     * @param latestIncome       最近一期收入(baseCcy)
     * @param latestExpense      最近一期支出(baseCcy)
     * @param savingsRate        最近一期储蓄率(小数)
     * @param savingsMedian      月储蓄能力中位(baseCcy)
     */
    public record ReportsMetricInputs(
            String viewCcy, String baseCcy,
            BigDecimal firstNetWorth, BigDecimal lastNetWorth, String firstLabel, String lastLabel,
            int periodCount, int decompCount,
            BigDecimal familyXirr, BigDecimal familyTwr,
            BigDecimal cumulativeNetInflow, BigDecimal cumulativePnl,
            BigDecimal cumulativeOpeningBaseline,
            BigDecimal familyBenchmarkPct, int benchmarkAccountCount, BigDecimal benchmarkTotalBalance,
            boolean savingsAvailable, int filledCount, int totalMonths,
            BigDecimal sumIncome, BigDecimal sumExpense, BigDecimal avgIncome, BigDecimal avgExpense,
            BigDecimal latestIncome, BigDecimal latestExpense, BigDecimal savingsRate, BigDecimal savingsMedian
    ) {}

    public Map<String, String> reports(ReportsMetricInputs in) {
        Map<String, String> m = new LinkedHashMap<>();
        if (in == null) return m;
        String v = in.viewCcy();

        // 家庭 XIRR(含收入)· 迭代解 → 展示真实现金流端点 + 解得值
        // v1.6.29 修 · 这两个端点原先取自 netWorthTrendExOpening(剔除开账基线的趋势,给财富水位用),
        //   其首点按构造恒为 0 → tooltip 长年显示「期初净资产 −¥0」,末点也不是真实净资产。
        //   一个号称"给你看真实中间数值"的 tooltip 显示另一套数,比没有更糟:用户据此判断指标算错了。
        //   现在与 familyXirr 同源,取真实 netWorth;开账基线单列说明(它确实计入外部流入)。
        m.put("familyXirr",
                "资金流序列:期初净资产 −" + money(v, in.firstNetWorth())
                        + (in.firstLabel() != null ? "(" + in.firstLabel() + ")" : "")
                        + " → 各期外部净流入(工资等 · 与「人赚」同口径"
                        + (in.cumulativeOpeningBaseline() != null && in.cumulativeOpeningBaseline().signum() != 0
                           ? ",含中途纳入的存量账户本金 " + money(v, in.cumulativeOpeningBaseline()) : "")
                        + ") → 期末净资产 +" + money(v, in.lastNetWorth())
                        + (in.lastLabel() != null ? "(" + in.lastLabel() + ")" : "")
                        + ",按 " + in.periodCount() + " 期数值求解 = " + pct2Signed(in.familyXirr())
                        + (in.periodCount() < 12 ? "(不满 12 期 · 累计口径非年化)" : "(年化)"));

        // vs 基准(余额加权)
        m.put("benchmark",
                "按 " + in.benchmarkAccountCount() + " 个账户期末余额(合计 " + money(v, in.benchmarkTotalBalance())
                        + ")对各自类目长期基准加权 = " + pctUnits(in.familyBenchmarkPct(), 1)
                        + " · 非实时行情");

        // 资产年化 TWR(剔收入 · 几何)
        m.put("familyTwr", familyTwrCalc(in.familyTwr())
                + " · 本区间 " + in.decompCount() + " 期连乘");

        // 人赚 · 净流入(累计)
        m.put("netInflow",
                "自起始期" + (in.firstLabel() != null ? "(" + in.firstLabel() + ")" : "")
                        + "之后逐期(收入 − 支出)累加 = " + signedMoney(v, in.cumulativeNetInflow())
                        + " · 共 " + in.decompCount() + " 期计入(起始期为基准、不重复计)");

        // 钱赚 · 投资 PnL = ΔNetWorth − 净流入
        m.put("pnl",
                "(期末净资产 " + money(v, in.lastNetWorth()) + " − 起始净资产 " + money(v, in.firstNetWorth())
                        + ") − 净流入 " + signedMoney(v, in.cumulativeNetInflow())
                        + " = " + signedMoney(v, in.cumulativePnl()));

        // ---- 储蓄区(本位币 ¥)----
        if (in.savingsAvailable()) {
            String b = in.baseCcy();
            int n = in.filledCount();
            m.put("avgIncome",
                    "近 " + in.totalMonths() + " 月有填 " + n + " 个月 · 收入合计 " + money(b, in.sumIncome())
                            + " ÷ " + n + " = " + money(b, in.avgIncome()));
            m.put("avgExpense",
                    "近 " + in.totalMonths() + " 月有填 " + n + " 个月 · 支出合计 " + money(b, in.sumExpense())
                            + " ÷ " + n + " = " + money(b, in.avgExpense()));
            m.put("savingsRate",
                    "最近一期:(收入 " + money(b, in.latestIncome()) + " − 支出 " + money(b, in.latestExpense())
                            + ") ÷ 收入 " + money(b, in.latestIncome()) + " = " + pctFromRatio(in.savingsRate(), 1));
            m.put("savingsMedian",
                    "近 " + n + " 个填报月每月(收入 − 支出)排序取中位 = " + signedMoney(b, in.savingsMedian()));
            m.put("filledMonths",
                    "近 " + in.totalMonths() + " 期中实际填过收入/支出的有 " + n + " 期"
                    + " · 支出口径:逐笔模式取逐笔之和,总额模式取每人手填的总数,两者永不相加"
                    + "(注意与收入侧方向相反 —— 收入是手填优先、否则取流水汇总)");
        }
        return m;
    }

    // ============================ 共用 builder ============================

    private String netWorthCalc(String ccy, BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth) {
        return "总资产 " + money(ccy, assets) + " − 总负债 " + money(ccy, liabilities)
                + " = " + money(ccy, netWorth);
    }

    private String totalAssetsCalc(String ccy, List<AllocationSlice> allocation, BigDecimal total) {
        if (allocation == null || allocation.isEmpty()) {
            return "本期资产类账户期末余额合计 = " + money(ccy, total);
        }
        String items = allocation.stream()
                .filter(s -> s.value() != null && s.value().signum() != 0)
                .map(s -> typeShort(s.label()) + " " + money(ccy, s.value()))
                .collect(Collectors.joining(" · "));
        if (items.isEmpty()) {
            return "本期资产类账户期末余额合计 = " + money(ccy, total);
        }
        return items + "\n合计 = " + money(ccy, total);
    }

    private String totalLiabilitiesCalc(String ccy, List<AccountPerformance> accountRows, BigDecimal total) {
        if (accountRows != null && !accountRows.isEmpty()) {
            List<AccountPerformance> liab = accountRows.stream()
                    .filter(a -> a.accountType() != null
                            && FactProjector.classOf(a.accountType()) == AccountClass.LIABILITY
                            && a.currentValue() != null && a.currentValue().signum() != 0)
                    .toList();
            String items = liab.stream()
                    .map(a -> a.accountName() + " " + money(ccy, a.currentValue().abs()))
                    .collect(Collectors.joining(" · "));
            if (!items.isEmpty()) {
                // v1.28.4 · issue #34 · 明细加起来必须等于合计;对不上就把差额照实写出来,不让用户自己去猜
                BigDecimal listed = liab.stream().map(a -> a.currentValue().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
                String gap = total == null || total.subtract(listed).abs().compareTo(BigDecimal.ONE) < 0 ? ""
                        : "\n另有 " + money(ccy, total.subtract(listed).abs()) + " 没能逐个列出(请反馈)";
                return items + gap + "\n合计 = " + money(ccy, total);
            }
        }
        return "仅 LOAN 类型账户期末余额绝对值合计 = " + money(ccy, total);
    }

    private String emergencyCalc(String ccy, BigDecimal liquid, BigDecimal avgExpense, BigDecimal months) {
        return emergencyCalc(ccy, liquid, avgExpense, months, null);
    }

    /**
     * v1.24 FR-650 · 紧急储备 tooltip 里<b>并列</b>给出「按常态月均」的读数。
     *
     * <p><b>豆腐块上的主数字不变</b> —— 仍然是按月均支出算的那个。
     * 换主数字会让所有现存用户的紧急储备月数一夜之间变大,而他们什么都没做。
     * 并列增加、由用户自己判断哪个更贴近他家的情况,比我们替他决定诚实。</p>
     *
     * <p>{@code normalExpense} 为 null = 支出分析关着 / 这个家没有逐笔数据
     * → 这一行整个不出现。级联靠 {@code NormalExpenseService} 返回 Optional.empty(),
     * 不是在这里判开关(否则三处回流读数各判一遍,总有一处会忘)。</p>
     */
    private String emergencyCalc(String ccy, BigDecimal liquid, BigDecimal avgExpense,
                                 BigDecimal months, BigDecimal normalExpense) {
        if (avgExpense == null || avgExpense.signum() <= 0) {
            return "月均支出为 0,暂无法计算紧急储备(先在填报页填月支出)";
        }
        String base = "流动资产 " + money(ccy, liquid) + " ÷ 月均支出 " + money(ccy, avgExpense)
                + " = " + months(months) + " 个月";
        if (normalExpense == null || normalExpense.signum() <= 0
                || normalExpense.compareTo(avgExpense) == 0) {
            return base;
        }
        BigDecimal byNormal = liquid.divide(normalExpense, 1, java.math.RoundingMode.HALF_UP);
        return base + "\n按常态月均(剔掉一次性支出)" + money(ccy, normalExpense)
                + " = " + months(byNormal) + " 个月 —— 两个都给,你自己判断哪个更贴近你家的情况。";
    }

    /** v1.24 FR-650 · 给调用方用的带常态月均版本 */
    public String emergencyWithNormal(String ccy, BigDecimal liquid, BigDecimal avgExpense,
                                      BigDecimal months, BigDecimal normalExpense) {
        return emergencyCalc(ccy, liquid, avgExpense, months, normalExpense);
    }

    /**
     * v1.6.30 · 加 anchorMonth / filingInProgress 两个入参。
     *
     * <p>收益类指标锚「最新已关账期」而非最后一期,所以 tooltip 必须说清算的是哪一期 ——
     * 否则用户看到卡片上是 8 月的净资产、收益率却是 7 月的,无从判断哪个对。</p>
     */
    /**
     * v1.10 FR-327 · 仪表盘的「本月资产收益」tooltip —— **实时本月**口径。
     *
     * <p>与卡片上显示的数字同源(都取 live* 字段)。未关账时明说是按已录事实算的,
     * 并点出偏差方向:还没录的收入会被算进投资损益 → 数值可能虚高。
     * 这是维护者拍板的口径(显示真实值 + 讲清口径,而不是藏起来)。</p>
     */
    private String liveMonthlyPnlCalc(String ccy, KpiSnapshot k) {
        // live 口径缺失(兼容构造器 / 老调用路径)→ **原样委托回锚已关账期的实现**。
        //   不能拿 liveIncome−liveExpense 当净流入硬算:那两个字段为 null 时会算出 ¥0,
        //   而百分比是用另一个净流入得出的 → tooltip 自相矛盾(发布预检的 mvn test 抓到过这一条)。
        if (k.liveMonthlyInvestReturnPct() == null) {
            return monthlyPnlCalc(ccy, k.returnAnchorNetWorth(), k.prevNetWorth(), k.lastNetInflow(),
                    k.monthlyInvestReturnPct(), k.returnAnchorMonth(), k.filingInProgress());
        }
        BigDecimal pct = k.liveMonthlyInvestReturnPct();
        if (k.netWorthDelta() == null) {
            return "上期净资产缺失或为 0,暂无法计算本月资产收益率";
        }
        BigDecimal open = k.netWorth().subtract(k.netWorthDelta());
        BigDecimal closeExOb = k.netWorth().subtract(
                k.openingBaselineLast() == null ? BigDecimal.ZERO : k.openingBaselineLast());
        BigDecimal inflow = (k.liveIncome() == null ? BigDecimal.ZERO : k.liveIncome())
                .subtract(k.liveExpense() == null ? BigDecimal.ZERO : k.liveExpense());
        String head = k.filingInProgress()
                ? "本月尚未关账 · 按已录事实实时计算(还没录的收入会被算进投资损益,数值可能虚高)· "
                : "本月已关账 · 数值已定格 · ";
        return head + "(期末净资产 " + money(ccy, closeExOb) + " − 期初 " + money(ccy, open)
                + " − 本月净流入 " + signedMoney(ccy, inflow) + ") ÷ 期初 " + money(ccy, open)
                + " = " + pct2Signed(pct);
    }

    private String monthlyPnlCalc(String ccy, BigDecimal netWorth, BigDecimal prevNetWorth,
                                  BigDecimal netInflow, BigDecimal pctDecimal,
                                  java.time.LocalDate anchorMonth, boolean filingInProgress) {
        if (prevNetWorth == null || prevNetWorth.signum() <= 0) {
            return "上期净资产缺失或为 0,暂无法计算本月资产收益率";
        }
        String head = anchorMonth == null ? "" : ("口径期 " + anchorMonth.toString().substring(0, 7)
                + (filingInProgress ? "(最新已关账期;当前月仍在填报,收支未录齐故不参与收益计算)" : "") + " · ");
        return head + "(期末净资产 " + money(ccy, netWorth) + " − 期初 " + money(ccy, prevNetWorth)
                + " − 本月净流入 " + signedMoney(ccy, netInflow) + ") ÷ 期初 " + money(ccy, prevNetWorth)
                + " = " + pct2Signed(pctDecimal);
    }

    private String familyXirrCalc(String ccy, BigDecimal netWorth, BigDecimal xirr,
                                 Integer periodCount, java.time.LocalDate anchorMonth,
                                 boolean filingInProgress) {
        StringBuilder sb = new StringBuilder("含工资的资金加权 IRR:期初投入与各期外部净流入")
                .append("(工资等 · 与「人赚」同口径)视为现金流出、期末净资产 ")
                .append(money(ccy, netWorth));
        if (anchorMonth != null) {
            sb.append('(').append(anchorMonth.toString(), 0, 7).append(')');
        }
        sb.append(" 视为流入,");
        if (periodCount != null) {
            sb.append("按 ").append(periodCount).append(" 期数值求解 = ").append(pct2Signed(xirr))
              .append(periodCount < 12 ? "(不满 12 期 · 累计口径非年化)" : "(年化)");
        } else {
            sb.append("数值求解 = ").append(pct2Signed(xirr));
        }
        if (filingInProgress) {
            sb.append(" · 当前月仍在填报,未参与计算");
        }
        return sb.toString();
    }

    private String familyTwrCalc(BigDecimal twr) {
        return "逐月「(期末 − 净流入) ÷ 期初」回报连乘后开 N 次方 − 1(剔除工资)= " + pct2Signed(twr);
    }

    /** "现金\n(CASH)" → "现金" */
    private String typeShort(String label) {
        if (label == null) return "";
        int nl = label.indexOf('\n');
        return nl < 0 ? label : label.substring(0, nl);
    }
}
