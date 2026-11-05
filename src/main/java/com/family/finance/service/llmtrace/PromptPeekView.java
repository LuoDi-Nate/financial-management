package com.family.finance.service.llmtrace;

import com.family.finance.repository.PromptRecordMapper;
import com.family.finance.service.analysis.AnalysisPromptBlocks;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * v1.28 · 终端面板的视图(PRD §3.2 ~ §3.4)。
 *
 * <p><b>只由一条已存的记录构造</b>(或一个「没有记录」的原因)—— 这个类不认识任何提示词拼装函数,
 * 也不许认识(护栏 {@code v128-PEEK-STORED-NOT-REBUILT})。</p>
 *
 * <p>「你家的数据」按行切段:以 {@link AnalysisPromptBlocks} 的四个标题(和超级 Agent 的「[分析上下文」)
 * 开头的段落是<b>你的设置带来的</b>,标色并写来源;段落在空行或下一个「## 」标题处结束。</p>
 */
public record PromptPeekView(
        String surfaceLabel,
        String state,
        List<String> warnLines,
        String vendor,
        String sentAt,
        String settingsNote,
        int systemChars,
        int userChars,
        List<Seg> systemSegs,
        List<Seg> dataSegs,
        List<String> legendLines,
        List<String> tailNotes,
        String rawAll,
        String rawData,
        int mineCount) {

    /** 一段:PLAIN = 普通材料;MINE = 你的设置带来的(带来源) */
    public record Seg(boolean mine, String source, List<String> lines) {}

    public static final String AGENT_CONTEXT_MARK = "[分析上下文";

    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M 月 d 日");

    public boolean hasContent() { return !systemSegs.isEmpty() || !dataSegs.isEmpty(); }

    /** 规矩里也带了你的设置(本机模式的超级 Agent 把分析上下文拼在系统提示词后)→ 默认展开 */
    public boolean systemHasMine() { return systemSegs.stream().anyMatch(Seg::mine); }

    public String command() { return "ai-prompt show --for \"" + surfaceLabel + "\""; }

    /** 从一条记录构造 */
    public static PromptPeekView of(PromptRecordMapper.Row r, Map<String, String> legend) {
        PromptSurface s = PromptSurface.parse(r.surface);
        String label = s == null ? r.surface : s.getLabel();
        String system = r.systemText == null ? "" : r.systemText;
        String user = r.userText == null ? "" : r.userText;
        List<String> warns = new ArrayList<>();
        String state = r.outcome == null ? PromptRecorder.OK : r.outcome;
        switch (state) {
            case PromptRecorder.BLOCKED -> {
                warns.add("# 测试环境 · 未调用");
                warns.add("# 以下内容已准备并记录,没有发给大模型。");
            }
            case PromptRecorder.FAILED -> {
                warns.add("# 发出去了,AI 那边没回:");
                warns.add("#   " + (r.outcomeNote == null ? "(没有收到任何说明)" : r.outcomeNote));
                warns.add("# (上游原话,原样照录;发出去的内容照常在下面)");
            }
            case PromptRecorder.REJECTED -> {
                warns.add("# 发出去了,回答没采用:" + (r.outcomeNote == null ? "没通过校验" : r.outcomeNote) + "。");
                warns.add("# 页面上显示的是规则引擎的结论(或上一份结果)。");
            }
            case PromptRecorder.SENT -> {
                if (r.outcomeNote != null) warns.add("# " + r.outcomeNote);
            }
            default -> { }
        }
        List<Seg> segs = segments(user);
        List<Seg> sysSegs = segments(system);
        int mine = (int) (segs.stream().filter(Seg::mine).count() + sysSegs.stream().filter(Seg::mine).count());
        List<String> legendLines = new ArrayList<>();
        if (legend != null && !legend.isEmpty()) {
            legendLines.add("# 发出去之前,成员名和账户名换成了代号(这张表只在这里显示,没发给 AI):");
            StringBuilder line = new StringBuilder("#  ");
            for (Map.Entry<String, String> e : legend.entrySet()) {
                String item = " " + e.getKey() + " = " + e.getValue();
                if (line.length() + item.length() > 72) {
                    legendLines.add(line.toString());
                    line = new StringBuilder("#  ");
                }
                line.append(item).append(" ·");
            }
            String last = line.toString();
            legendLines.add(last.endsWith(" ·") ? last.substring(0, last.length() - 2) : last);
        }
        List<String> tail = new ArrayList<>();
        if (s == PromptSurface.ASK_TURN) {
            tail.add("# 前面几轮对话也一起发了 · 工具查到的数据见这条回答上方的「思考过程」。");
            tail.add("# 工具返回里带账户名(超级 Agent 要按名字回答你的问题)。");
        } else {
            tail.add("# 金额、备注、家庭名、你自己写的偏好和补充要求是原样发的;成员名和账户名换成了代号。");
        }
        String rawAll = "[system]\n" + system + "\n\n[user]\n" + user;
        return new PromptPeekView(label, state, warns, r.vendor,
                r.createdAt == null ? null : r.createdAt.format(AT), r.settingsNote,
                system.length(), user.length(), sysSegs, segs, legendLines, tail, rawAll, user, mine);
    }

    /** 本版之前生成、没有记录的结果 */
    public static PromptPeekView legacy(PromptSurface s, LocalDateTime generatedAt) {
        List<String> w = new ArrayList<>();
        w.add(generatedAt == null ? "# 这份结果生成时还没开始记录。"
                : "# 这份结果生成于 " + generatedAt.format(DAY) + ",那时还没开始记录。");
        w.add("# 点「刷新」重新生成后就能看。");
        w.add("# (不拿现在的数据拼一段冒充 —— 这期间数字可能变过,拼出来的和这份结果对不上)");
        return empty(s, "LEGACY", w);
    }

    /** 这次没有调用 AI */
    public static PromptPeekView skipped(PromptSurface s, String why) {
        List<String> w = new ArrayList<>();
        w.add("# 这次没有调用 AI" + (why == null || why.isBlank() ? "。" : ":" + why));
        w.add("# 发出去的内容:无");
        return empty(s, "SKIPPED", w);
    }

    /** 记录找不到(超过保留期被清掉 / 不是你家的) */
    public static PromptPeekView missing(PromptSurface s) {
        List<String> w = new ArrayList<>();
        w.add("# 这条记录已经清理掉了(只留 2 ~ 90 天,见管理 → AI 接入)。");
        w.add("# 点「刷新」重新生成后就能看。");
        return empty(s, "MISSING", w);
    }

    private static PromptPeekView empty(PromptSurface s, String state, List<String> warns) {
        return new PromptPeekView(s == null ? "AI 分析" : s.getLabel(), state, warns, null, null, null, 0, 0,
                List.of(), List.of(), List.of(), List.of(), "", "", 0);
    }

    static List<String> lines(String text) {
        if (text == null || text.isEmpty()) return List.of();
        return List.of(text.split("\n", -1));
    }

    /** 把「你家的数据」切成段:你的设置带来的那几段单独成段 */
    static List<Seg> segments(String user) {
        List<Seg> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        String curSource = null;
        for (String line : lines(user)) {
            String src = sourceOf(line);
            boolean endsMine = curSource != null
                    && (line.isBlank() || line.startsWith("---") || (line.startsWith("## ") && src == null));
            if (src != null || endsMine) {
                if (!cur.isEmpty()) out.add(new Seg(curSource != null, curSource, cur));
                cur = new ArrayList<>();
                curSource = src;
            }
            cur.add(line);
        }
        if (!cur.isEmpty()) out.add(new Seg(curSource != null, curSource, cur));
        return out;
    }

    static String sourceOf(String line) {
        if (line == null) return null;
        if (line.startsWith(AnalysisPromptBlocks.H_SCOPE)) return "← 你的分析范围";
        if (line.startsWith(AnalysisPromptBlocks.H_TEMPLATE)) return "← 你选的分析模板(分析设置 ②)";
        if (line.startsWith(AnalysisPromptBlocks.H_EXTRA)) return "← 模板里的补充要求";
        if (line.startsWith(AnalysisPromptBlocks.H_PREFS)) return "← 分析设置 ⑤ · 分析偏好";
        if (line.startsWith(AGENT_CONTEXT_MARK)) return "← 分析设置(每一问都带)";
        return null;
    }
}
