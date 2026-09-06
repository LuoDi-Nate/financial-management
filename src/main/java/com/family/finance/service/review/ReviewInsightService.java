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

    public boolean available(long familyId) {
        return llmRouter.available(familyId);
    }

    public record Review(String text, String vendor, boolean cached) {}

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
        if (periodClosed && !force) {
            ReviewAiCacheMapper.Row hit = cacheMapper.find(familyId, periodId, dim);
            if (hit != null) return new Review(hit.text(), hit.vendor(), true);
        }
        String facts = anonymize(familyId, buildFactsAndSignals(periodLabel, attr, grouped));
        String system = """
                你是家庭月度资产复盘助手。下面是**已经算好**的本期归因事实与系统判定的异常信号。
                规则(必须遵守):
                1. 严禁做任何计算,只引用给出的数字;
                2. 输出 3-4 条,每条一行以「· 」开头,≤55 字,大白话;
                3. 先回答"这个月钱为什么变了、主要来自哪几块"(基于信号,就事论事不责怪),再给一条「值得做的一件事」收尾;
                4. 信号为空时如实说本期结构无显著异常,给一句观察即可;
                5. 不推荐任何具体产品、不预测涨跌、不用黑话。
                只输出要点行,不要标题、开场白、markdown。""";
        return llmRouter.invoke(familyId, system, facts, (inv, raw, ms) -> {
            String out = raw.trim();
            // 只有已关账的期才落缓存 —— 进行中的期数据还在动,存下来必然过期
            if (periodClosed) cacheMapper.upsert(familyId, periodId, dim, out, inv.badge());
            return new Review(out, inv.badge(), false);
        });
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
