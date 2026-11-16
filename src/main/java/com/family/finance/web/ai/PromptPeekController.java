package com.family.finance.web.ai;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.llmtrace.PromptPeekView;
import com.family.finance.service.llmtrace.PromptRecorder;
import com.family.finance.service.llmtrace.PromptSurface;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;

/**
 * v1.28 · 卡片头 {@code >_} 点开后拉的终端面板(PRD §3.2 ~ §3.4)。
 *
 * <p>只读已存的记录,按家庭隔离;<b>这里不引用任何提示词拼装函数</b>(护栏 {@code v128-PEEK-STORED-NOT-REBUILT})。
 * 不是你家的记录与已清理的记录一样回「已经清理掉了」,不告诉对方这里有没有东西。</p>
 */
@Controller
@RequiredArgsConstructor
public class PromptPeekController {

    private final PromptRecorder recorder;
    private final com.family.finance.repository.AskMessageMapper askMessageMapper;
    private final com.family.finance.service.llmtrace.PromptPeekLinks links;

    @GetMapping("/ai/prompt/{id}")
    public String panel(@AuthenticationPrincipal MemberPrincipal me, @PathVariable long id, Model model) {
        var row = recorder.find(me.getFamilyId(), id);
        model.addAttribute("pv", row.map(r -> PromptPeekView.of(r, recorder.legend(r)))
                .orElseGet(() -> PromptPeekView.missing(null)));
        return "fragments/_prompt-peek :: panel";
    }

    /** 超级 Agent 流式回答刚结束:这一问的面板地址(没有 / 开关关了 → peek 为空串) */
    @GetMapping("/ai/prompt/ask-latest")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, String> askLatest(@AuthenticationPrincipal MemberPrincipal me,
                                                   @RequestParam("conv") long conversationId) {
        Long id = askMessageMapper.lastUserPromptRecord(me.getFamilyId(), conversationId);
        String src = links.of(me.getFamilyId(), id);
        String source=askMessageMapper.lastUserSource(me.getFamilyId(),conversationId);
        return java.util.Map.of("peek", src == null ? "" : src,
            "sourceLabel",com.family.finance.service.llmtrace.LlmSource.label(source,""));
    }

    @GetMapping("/ai/prompt/none")
    public String none(@RequestParam(name = "for", required = false) String surface,
                       @RequestParam(name = "why", required = false) String why,
                       @RequestParam(name = "at", required = false) String at,
                       @RequestParam(name = "msg", required = false) String msg,
                       Model model) {
        PromptSurface s = PromptSurface.parse(surface);
        PromptPeekView v;
        if ("skipped".equals(why)) {
            v = PromptPeekView.skipped(s, msg == null ? null : msg.length() > 120 ? msg.substring(0, 120) : msg);
        } else {
            LocalDateTime t = null;
            try { if (at != null) t = LocalDateTime.parse(at); } catch (Exception ignored) { }
            v = PromptPeekView.legacy(s, t);
        }
        model.addAttribute("pv", v);
        return "fragments/_prompt-peek :: panel";
    }
}
