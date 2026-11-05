package com.family.finance.service.llmtrace;

import com.family.finance.repository.PromptRecordMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** v1.28 · 终端面板:切段、四种说实话的状态、代号对照(PRD §3.2 ~ §3.4) */
class PromptPeekViewTest {
    @Test void blockedContentNeverClaimsItWasSentOrToolsWereExecuted() {
        var v=PromptPeekView.of(row(PromptRecorder.BLOCKED,"test","prepared"),Map.of("账户A","样本账户"));
        assertThat(v.warnLines()).anyMatch(s->s.contains("没有发给大模型"));
        assertThat(v.tailNotes()).allMatch(s->!s.contains("原样发的"));
        assertThat(v.legendLines()).anyMatch(s->s.contains("没有发送"));
        assertThat(v.rawData()).isEqualTo("prepared");
    }

    private static PromptRecordMapper.Row row(String outcome, String note, String user) {
        PromptRecordMapper.Row r = new PromptRecordMapper.Row();
        r.id = 7L;
        r.surface = "DIAGNOSE_FAMILY";
        r.systemText = "你是顾问。\n## 数字纪律\n- 不许算数";
        r.userText = user;
        r.vendor = "deepseek · deepseek-chat";
        r.outcome = outcome;
        r.outcomeNote = note;
        r.createdAt = LocalDateTime.of(2026, 9, 29, 14, 2, 31);
        return r;
    }

    private static final String USER = """
            # 家庭综合体检上下文
            - 净资产: ¥5000000
            ## 5. 规则
            - [WARN] FAM-CON-1
            ## 分析范围:金融资产
            这个家庭只让你看金融资产
            - 不要提房产

            ## 分析模板:稳健守护
            立场:稳健

            ## 家里人交代的分析偏好(1 条)
            1. 房子是自住的
            ---
            请输出 200-500 字""";

    @Test
    void 你的设置带来的三段单独成段_带来源() {
        var v = PromptPeekView.of(row("OK", null, USER), Map.of());
        var mine = v.dataSegs().stream().filter(PromptPeekView.Seg::mine).toList();
        assertThat(mine).hasSize(3);
        assertThat(mine.get(0).lines().get(0)).startsWith("## 分析范围:");
        assertThat(mine.get(0).source()).contains("分析范围");
        assertThat(mine.get(1).source()).contains("分析模板");
        assertThat(mine.get(2).source()).contains("分析偏好");
        assertThat(mine.get(2).lines()).as("偏好段到「---」之前结束,不吞后面的要求").doesNotContain("---");
        assertThat(v.mineCount()).isEqualTo(3);
        assertThat(v.state()).isEqualTo("OK");
        assertThat(v.warnLines()).isEmpty();
        assertThat(v.command()).isEqualTo("ai-prompt show --for \"体检 · AI 综合诊断\"");
    }

    @Test
    void 切段不丢字_拼回来与原文逐字相同() {
        var v = PromptPeekView.of(row("OK", null, USER), Map.of());
        String joined = String.join("\n", v.dataSegs().stream().flatMap(s -> s.lines().stream()).toList());
        assertThat(joined).isEqualTo(USER);
        assertThat(v.rawData()).isEqualTo(USER);
        assertThat(v.rawAll()).contains("[system]\n你是顾问。").contains("[user]\n" + USER);
    }

    @Test
    void 基线组合没有你的设置() {
        var v = PromptPeekView.of(row("OK", null, "# 上下文\n- 净资产: ¥1"), Map.of());
        assertThat(v.mineCount()).isZero();
        assertThat(v.systemHasMine()).isFalse();
    }

    @Test
    void 失败写上游原话_没采用写原因() {
        var failed = PromptPeekView.of(row("FAILED", "Arrearage: Access denied", USER), Map.of());
        assertThat(String.join("\n", failed.warnLines())).contains("AI 那边没回").contains("Arrearage: Access denied");
        assertThat(failed.hasContent()).as("失败了也照样能看发出去的内容").isTrue();
        var rejected = PromptPeekView.of(row("REJECTED", "出现了材料里没有的金额", USER), Map.of());
        assertThat(String.join("\n", rejected.warnLines())).contains("回答没采用").contains("出现了材料里没有的金额");
    }

    @Test
    void 老结果与没调用照实说_不给内容() {
        var legacy = PromptPeekView.legacy(PromptSurface.REBALANCE, LocalDateTime.of(2026, 9, 12, 10, 0));
        assertThat(legacy.state()).isEqualTo("LEGACY");
        assertThat(legacy.hasContent()).isFalse();
        assertThat(String.join("\n", legacy.warnLines())).contains("9 月 12 日").contains("还没开始记录").contains("刷新");
        var skipped = PromptPeekView.skipped(PromptSurface.DIAGNOSE_FAMILY, "范围里没有资产");
        assertThat(String.join("\n", skipped.warnLines())).contains("没有调用 AI").contains("范围里没有资产");
    }

    @Test
    void 代号对照只作注释_面板正文不反换真名() {
        Map<String, String> legend = new LinkedHashMap<>();
        legend.put("成员A", "爸爸");
        legend.put("账户A", "招行工资卡");
        var v = PromptPeekView.of(row("OK", null, "- 【账户A】 主理人=成员A"), legend);
        assertThat(v.legendLines().get(0)).startsWith("#").contains("代号");
        assertThat(String.join(" ", v.legendLines())).contains("成员A = 爸爸").contains("账户A = 招行工资卡");
        String body = String.join("\n", v.dataSegs().stream().flatMap(s -> s.lines().stream()).toList());
        assertThat(body).contains("【账户A】").contains("成员A").doesNotContain("爸爸").doesNotContain("招行工资卡");
    }

    @Test
    void 超级Agent本机模式_规矩里带了分析上下文就默认展开() {
        PromptRecordMapper.Row r = row("SENT", null, "今年收益怎么样");
        r.surface = "ASK_TURN";
        r.systemText = "你是家庭账本助手。\n\n[分析上下文 · 系统每轮附带,不是用户说的话]\n- 家里人交代的分析偏好:\n  1. 偏保守";
        var v = PromptPeekView.of(r, Map.of());
        assertThat(v.systemHasMine()).isTrue();
        assertThat(String.join("\n", v.tailNotes())).contains("思考过程");
    }
}
