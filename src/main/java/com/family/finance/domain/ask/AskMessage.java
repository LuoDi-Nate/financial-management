package com.family.finance.domain.ask;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * v1.19 · 一条消息。
 *
 * <p><b>{@code contentText} 里存的是带 {@code {{cite:c1}}} 标记的原文,不是渲染后的数字。</b>
 * 这是「历史对话跟着口径走」的前提:今天口径改了名(比如「人赚」改叫别的),
 * 昨天那条回答重新打开时显示的仍是新说法,而不是一个已经作废的旧词。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AskMessage {
    private String sourceJson;

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    /** 系统旁白:换账期、换币种、上游出错等 —— 不参与模型上下文,只给人看 */
    public static final String ROLE_NOTE = "system_note";

    private Long id;
    private Long conversationId;
    private String role;
    private String contentText;
    private Integer seq;
    private LocalDateTime createdAt;
    /** v1.28 · 提问那条:这一问发出去的内容(llm_prompt_record.id)· 回答那条为空 */
    private Long promptRecordId;

    /** 渲染期装配:这条消息用到的引用块 */
    @Builder.Default
    private List<AskCitation> citations = new ArrayList<>();
    /** 渲染期装配:这条消息背后跑过哪些工具 */
    @Builder.Default
    private List<AskToolCall> toolCalls = new ArrayList<>();

    public boolean fromUser() { return ROLE_USER.equals(role); }
    public boolean isNote() { return ROLE_NOTE.equals(role); }
}
