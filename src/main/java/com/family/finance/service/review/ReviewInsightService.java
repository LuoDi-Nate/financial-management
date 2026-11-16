package com.family.finance.service.review;

import com.family.finance.calc.review.AttributionEngine;
import com.family.finance.service.member.MemberDirectory;
import com.family.finance.repository.ReviewAiCacheMapper;
import com.family.finance.service.checkup.llm.LlmRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.2 · AI 月度复盘(tech-design v1.2 §2 · 照 LensInsightService 模式)。
 *
 * <p><b>信号驱动 + LLM 禁算</b>:归因结果(AttributionEngine 已算好)→ 工程规则判信号
 * (亏损集中占比 / 汇率占比 / 入不敷出 / 无异常如实说)→ LLM 只解读 + 一条最优先动作。
 * <b>真名脱敏</b>:成员名替换「成员A/B」。<b>缓存落库</b>(D5):UNIQUE(family,period,dim) 覆盖写,
 * 关账期结果不可变可长期回看;force=true 重新生成。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewInsightService {

    private final LlmRouter llmRouter;
    /**
     * v1.15 FR-382 · 脱敏映射必须**含已归档成员**。
     * 只拿活跃列表的话,已归档成员的真名不在映射表里 → 替换不掉 → 真名原样进 LLM prompt。
     * 这不是"名字显示不全"那种体感问题,是隐私红线({@code PrivacyIsolationTest} 守着)。
     */
    private final MemberDirectory memberDirectory;
    private final ReviewAiCacheMapper cacheMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.llmtrace.LlmSource sources;
    private final com.family.finance.service.expense.NormalExpenseService normalExpenseService; // v1.24 FR-653

    /**
     * v1.27 · 分析偏好(FR-851)· 字段注入且可缺:老的构造器调用(测试)不用跟着改,缺了就当没有偏好。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.service.analysis.AnalysisPreferenceService preferenceService;

    /** v1.28 · 按账户切时账户名换代号(FR-920)· 字段注入且可缺(同上) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.family.finance.repository.AccountMapper accountMapper;

    public boolean available(long familyId) {
        return llmRouter.available(familyId);
    }

    /**
     * @param promptRecordId v1.28 · 生成这份复盘的那次调用(null = 本版之前的老缓存 / 没调用)
     * @param generatedAt    v1.28 · 生成时刻(老缓存在面板上说「生成于 X,那时还没开始记录」)
     */
    public record Review(String text, String vendor, boolean cached, Long promptRecordId, java.time.LocalDateTime generatedAt,
                         String sourceJson, boolean historical) {
        public Review(String text, String vendor, boolean cached, Long id, java.time.LocalDateTime at) {
            this(text, vendor, cached, id, at, null, false);
        }
        public Review(String text, String vendor, boolean cached) {
            this(text, vendor, cached, null, null);
        }
    }

    /**
     * @param periodClosed 这一期是否已关账。<b>没关账就完全不碰缓存</b> —— 既不读也不写。
     *
     * <p>v1.19.16 · 以前不分状态一律缓存,于是有两种「数字变了、解读没变」:</p>
     * <ol>
     *   <li><b>重开改数据</b> —— 缓存没人清(见 {@code ReviewAiCacheMapper.deleteByPeriod} 的注释);</li>
     *   <li><b>进行中的期</b> —— 月中点一次复盘就把当时的结论存住了,之后每天录入,
     *       再点还是那句话。更糟的是这一期关账之后,那份<b>月中生成</b>的解读会被当成
     *       「本期定论」端出来。</li>
     * </ol>
     *
     * <p>不写的代价是进行中的期每点一次就调一次模型 —— 可接受:它是用户主动点的,
     * 而且他点的本意就是「按现在的数给我讲讲」。</p>
     */
    public Review review(long familyId, long periodId, String periodLabel, String dim,
                         AttributionEngine.Result attr, LinkedHashMap<String, BigDecimal> grouped,
                         boolean periodClosed, boolean force) {
        return review(familyId, periodId, periodLabel, dim, attr, grouped, periodClosed, force, null);
    }

    /**
     * v1.27 · {@code viewContext} = 页面上的账户筛选 + 视图币种(调用方拼好,如「12,15|USD」;不筛选、本位币传 null)。
     *
     * <p>PRD §13 ⑩:原来缓存键只有「家庭 + 账期 + 维度」—— 筛了账户、换了币种,拿到的还是另一种看法下的那段解读,
     * 读起来完全通顺。现在键里带上它和分析偏好的指纹;都没有时仍是纯维度,老缓存照常命中。
     * 编码进现有 {@code dim} 列(VARCHAR(20):最长维度 10 字 + 「|」+ 8 位指纹),不改表、不动唯一键。</p>
     */
    public Review review(long familyId, long periodId, String periodLabel, String dim,
                         AttributionEngine.Result attr, LinkedHashMap<String, BigDecimal> grouped,
                         boolean periodClosed, boolean force, String viewContext) {
        List<String> prefs = preferenceService == null ? List.of() : preferenceService.enabledTexts(familyId);
        String cacheDim = cacheDim(dim, viewContext, prefs);
        if (periodClosed && !force) {
            ReviewAiCacheMapper.Row hit = cacheMapper.find(familyId, periodId, cacheDim);
            if (hit != null) return new Review(hit.text(), com.family.finance.service.llmtrace.LlmSource.label(hit.sourceJson(),hit.vendor()),
                true, hit.promptRecordId(), hit.createdAt(), hit.sourceJson(), sources != null && !sources.current(familyId,hit.sourceJson()));
        }
        // v1.28 FR-920 · 按账户切时,分组名里的账户名换代号(账户组名不是账户,原样)
        var codes = com.family.finance.service.llmtrace.AccountCodenames.of(
                accountMapper == null ? List.of() : accountMapper.findAllByFamily(familyId));
        LinkedHashMap<String, BigDecimal> shown = grouped;
        if ("acct".equals(dim) && grouped != null) {
            shown = new LinkedHashMap<>();
            for (var e : grouped.entrySet()) shown.merge(codes.codeForName(e.getKey()), e.getValue(), BigDecimal::add);
        }
        // 偏好在材料之后;和材料一起过同一层真名替换(PRD §8)
        String prefBlock = com.family.finance.service.analysis.AnalysisPromptBlocks.preferencesOnly(prefs, null);
        String facts = anonymize(familyId,
                buildFactsAndSignals(periodLabel, attr, shown) + expenseStructure(familyId)
                        + (prefBlock.isBlank() ? "" : "\n" + prefBlock + "\n"));
        String system = """
                你是家庭月度资产复盘助手。下面是**已经算好**的本期归因事实与系统判定的异常信号。
                规则(必须遵守):
                1. 严禁做任何计算,只引用给出的数字;
                2. 输出 3-4 条,每条一行以「· 」开头,≤55 字,大白话;
                3. 先回答"这个月钱为什么变了、主要来自哪几块"(基于信号,就事论事不责怪),再给一条「值得做的一件事」收尾;
                4. 信号为空时如实说本期结构无显著异常,给一句观察即可;
                5. 不推荐任何具体产品、不预测涨跌、不用黑话。
                只输出要点行,不要标题、开场白、markdown。""";
        Map<String, String> legend = new LinkedHashMap<>(memberLegend(familyId));
        legend.putAll(codes.codeToReal());
        var trace = com.family.finance.service.llmtrace.PromptTrace
                .of(com.family.finance.service.llmtrace.PromptSurface.REVIEW).legend(legend)
                .settings(prefs.isEmpty() ? null : "分析偏好 " + prefs.size() + " 条");
        // v1.28 · 回答里的代号(成员 / 账户)展示前换回真名
        Review r = llmRouter.invoke(familyId, trace, system, facts, (inv, raw, ms) -> new Review(
                com.family.finance.service.checkup.llm.PromptBuilder.reverseMapping(raw.trim(), legend), inv.badge(), false));
        if (r == null) return null;
        // 只有已关账的期才落缓存 —— 进行中的期数据还在动,存下来必然过期
        if (periodClosed) cacheMapper.upsert(familyId, periodId, cacheDim, r.text(),
            com.family.finance.service.llmtrace.LlmSource.legacyVendor(r.vendor()), trace.recordId(), trace.sourceJson());
        return new Review(r.text(), com.family.finance.service.llmtrace.LlmSource.label(trace.sourceJson(),r.vendor()), false,
            trace.recordId(), java.time.LocalDateTime.now(), trace.sourceJson(), false);
    }

    /**
     * v1.24 FR-653 · 支出结构事实 —— 三分占比 / 刚性占比 / 常态月均。
     *
     * <h3>所有数字工程算好再填进 prompt</h3>
     *
     * <p>这不是风格偏好,是这个项目的硬纪律:<b>LLM 严禁做数学运算</b>。
     * 给它「刚性 4,700、总额 12,400」让它自己算占比,它会给出一个看起来合理的错数,
     * 而且每次还不一样。所以这里给的是已经算好的百分比,它只负责把这些数字
     * 写成一句人话。</p>
     *
     * <h3>拿不到就整段不出现</h3>
     *
     * <p>开关关着 / 没有逐笔数据 → 返回空串,月报的其余部分<b>一个字都不受影响</b>
     * (FR-653 明确要求)。不是给一段「支出结构:暂无数据」—— 那会让模型
     * 花一条要点去说「你还没记支出」,挤掉真正有用的那条。</p>
     */
    private String expenseStructure(long familyId) {
        var splitOpt = normalExpenseService.normal(familyId)
                .flatMap(n -> normalExpenseService.split(familyId, n.periodIds())
                        .map(sp -> new Object[]{n, sp}));
        if (splitOpt.isEmpty()) return "";
        var n = (com.family.finance.service.expense.NormalExpenseService.Normal) splitOpt.get()[0];
        var sp = (com.family.finance.service.expense.NormalExpenseService.Split) splitOpt.get()[1];

        StringBuilder sb = new StringBuilder();
        sb.append("近 12 期支出结构(占比已算好 · 只引用不计算):\n");
        for (var part : sp.parts()) {
            sb.append("  ").append(part.nature().getLabel()).append(": ")
              .append(part.pct()).append("%\n");
        }
        sb.append("  刚性占比: ").append(sp.rigidPct()).append("%")
          .append("(占比越高,现金流的可压缩空间越小 —— 这是口径说明,不是好坏评价)\n");
        if (n.differs()) {
            sb.append("  常态月均(剔掉 ").append(n.oneOffCount()).append(" 笔一次性支出后): ")
              .append(money(n.normalBase())).append(" · 月均支出: ").append(money(n.averageBase()))
              .append('\n');
        }
        return sb.toString();
    }

    /** 归因事实 + 工程信号(全部数字算好) */
    String buildFactsAndSignals(String periodLabel, AttributionEngine.Result attr,
                                LinkedHashMap<String, BigDecimal> grouped) {
        StringBuilder sb = new StringBuilder();
        sb.append("账期: ").append(periodLabel).append('\n');
        sb.append("净资产变化 ΔNW: ").append(money(attr.delta())).append('\n');
        sb.append("人赚(收入−支出): ").append(money(attr.humanEarned())).append('\n');
        sb.append("钱赚(投资损益合计): ").append(money(attr.moneyEarnedTotal())).append('\n');
        sb.append("开账基线(新纳入存量): ").append(money(attr.opening())).append('\n');
        if (attr.fxTotal().abs().compareTo(BigDecimal.ONE) > 0) {
            sb.append("其中汇率重估: ").append(money(attr.fxTotal())).append('\n');
        }
        sb.append("钱赚分组(当前维度):\n");
        grouped.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(money(v)).append('\n'));

        List<String> signals = new ArrayList<>();
        BigDecimal lossTotal = grouped.values().stream().filter(v -> v.signum() < 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (lossTotal.signum() < 0) {
            Map.Entry<String, BigDecimal> worst = grouped.entrySet().stream()
                    .filter(e -> e.getValue().signum() < 0)
                    .min(Map.Entry.comparingByValue()).orElse(null);
            if (worst != null) {
                BigDecimal share = worst.getValue().multiply(BigDecimal.valueOf(100))
                        .divide(lossTotal, 0, RoundingMode.HALF_UP);
                if (share.intValue() >= 50) {
                    signals.add("亏损集中: 「" + worst.getKey() + "」占了亏损侧的 " + share + "%(" + money(worst.getValue()) + ")");
                }
            }
        }
        if (attr.moneyEarnedTotal().signum() != 0
                && attr.fxTotal().abs().multiply(BigDecimal.valueOf(100))
                        .divide(attr.moneyEarnedTotal().abs(), 0, RoundingMode.HALF_UP).intValue() >= 20) {
            signals.add("汇率影响显著: 汇率重估 " + money(attr.fxTotal()) + ",占钱赚绝对值 ≥20%,涨跌里有一块是折算而非标的本身");
        }
        if (attr.humanEarned().signum() < 0) {
            signals.add("入不敷出: 本期支出大于收入,人赚为 " + money(attr.humanEarned()));
        }
        if (attr.unattributed().abs().compareTo(BigDecimal.valueOf(100)) > 0) {
            signals.add("口径缺口: 有 " + money(attr.unattributed()) + " 未归因(数据可能不全,提醒补填报)");
        }
        sb.append("异常信号(系统判定,共 ").append(signals.size()).append(" 条):\n");
        if (signals.isEmpty()) sb.append("  (无 · 本期无显著异常)\n");
        else signals.forEach(sig -> sb.append("  - ").append(sig).append('\n'));
        return sb.toString();
    }

    /** 缓存维度键:没筛选、本位币、没偏好 → 纯维度(与 v1.26 同一行);否则「维度|8 位指纹」 */
    static String cacheDim(String dim, String viewContext, List<String> prefs) {
        boolean plain = (viewContext == null || viewContext.isBlank()) && (prefs == null || prefs.isEmpty());
        if (plain) return dim;
        String fp = (viewContext == null ? "" : viewContext) + "|"
                + com.family.finance.service.analysis.AnalysisPreferenceService.fingerprint(prefs);
        try {
            String hex = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(fp.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return dim + "|" + hex.substring(0, 8);
        } catch (Exception e) {
            return dim + "|" + String.format("%08x", fp.hashCode());
        }
    }

    /** 与 {@link #anonymize} 同一个顺序的「代号 → 真名」 */
    private Map<String, String> memberLegend(long familyId) {
        Map<String, String> m = new LinkedHashMap<>();
        char c = 'A';
        for (var mem : memberDirectory.listAll(familyId)) {
            if (mem.getDisplayName() != null && !mem.getDisplayName().isBlank()) m.put("成员" + c++, mem.getDisplayName());
        }
        return m;
    }

    private String anonymize(long familyId, String text) {
        char c = 'A';
        for (var m : memberDirectory.listAll(familyId)) {
            if (m.getDisplayName() != null && !m.getDisplayName().isBlank()) {
                text = text.replace(m.getDisplayName(), "成员" + c++);
            }
        }
        return text;
    }

    private static String money(BigDecimal v) {
        return "¥" + (v == null ? "0" : v.setScale(0, RoundingMode.HALF_UP).toPlainString());
    }
}
