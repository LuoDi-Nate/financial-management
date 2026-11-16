package com.family.finance.repository;

import com.family.finance.domain.ask.AskMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * v1.19 · 消息表。
 *
 * <p>v1.24 · 家庭隔离:本表没有 {@code family_id} 列,归属沿
 * {@code ask_message → ask_conversation.family_id} 取,每条语句自己走完这条 JOIN。</p>
 */
@Mapper
public interface AskMessageMapper {

    String COLS = " m.id, m.conversation_id AS conversationId, m.role, m.content_text AS contentText,"
                + " m.seq, m.created_at AS createdAt, m.prompt_record_id AS promptRecordId, m.source_json AS sourceJson ";

    @Update("UPDATE ask_message m JOIN ask_conversation v ON v.id=m.conversation_id"
        + " SET m.source_json=#{sourceJson} WHERE v.family_id=#{familyId} AND m.id=#{id}")
    int updateSource(@Param("familyId") long familyId, @Param("id") long id, @Param("sourceJson") String sourceJson);

    @Insert("INSERT INTO ask_message (conversation_id, role, content_text, seq)"
          + " SELECT #{m.conversationId}, #{m.role}, #{m.contentText}, #{m.seq}"
          + " FROM ask_conversation v"
          + " WHERE v.id = #{m.conversationId} AND v.family_id = #{familyId}")
    @Options(useGeneratedKeys = true, keyProperty = "m.id")
    int insert(@Param("familyId") long familyId, @Param("m") AskMessage m);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, AskMessage m) {
        if (insert(familyId, m) != 1) {
            throw new IllegalStateException("消息归属校验不通过:会话 " + m.getConversationId()
                    + " 不属于家庭 " + familyId);
        }
    }

    @Select("SELECT" + COLS + "FROM ask_message m"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid} ORDER BY m.seq")
    List<AskMessage> byConversation(@Param("familyId") long familyId, @Param("cid") long conversationId);

    /** 下一个序号 —— 单家庭单机,COALESCE(MAX)+1 够用,不引锁 */
    @Select("SELECT COALESCE(MAX(m.seq), 0) + 1 FROM ask_message m"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid}")
    int nextSeq(@Param("familyId") long familyId, @Param("cid") long conversationId);

    /** 送进模型上下文的历史:只取 user/assistant,旁白不算(它是给人看的) */
    @Select("SELECT" + COLS + "FROM ask_message m"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid}"
          + " AND m.role IN ('user','assistant')"
          + " ORDER BY m.seq DESC LIMIT #{limit}")
    List<AskMessage> recentForContext(@Param("familyId") long familyId,
                                      @Param("cid") long conversationId, @Param("limit") int limit);

    @Update("UPDATE ask_message m JOIN ask_conversation v ON v.id = m.conversation_id"
          + " SET m.content_text = #{contentText}"
          + " WHERE v.family_id = #{familyId} AND m.id = #{id}")
    void updateContent(@Param("familyId") long familyId,
                       @Param("id") long id, @Param("contentText") String contentText);

    /** v1.27 · 按 id 取一条(带家庭归属)—— 确认卡处理完要改写那条回答里的标记 */
    @Select("SELECT" + COLS + "FROM ask_message m"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.id = #{id}")
    AskMessage findOwned(@Param("familyId") long familyId, @Param("id") long id);

    /** v1.27 · 这段对话里最后一条含某段文字的助手回答(刚流完的那条,前端不知道它的 id) */
    @Select("SELECT m.id FROM ask_message m"
          + " JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid} AND m.role = 'assistant'"
          + " AND INSTR(m.content_text, #{needle}) > 0 ORDER BY m.seq DESC LIMIT 1")
    Long lastAssistantContaining(@Param("familyId") long familyId, @Param("cid") long conversationId,
                                 @Param("needle") String needle);

    /**
     * v1.27 · 这一轮带了什么分析上下文(偏好几条 · 哪个模板 · 哪个范围)、托管模式下百炼的回显确认没有。
     * 只写在用户那条消息上;查问题时一眼看得出「这一问 AI 到底收没收到偏好」(PRD §9 ③)。
     */
    @Update("UPDATE ask_message m JOIN ask_conversation v ON v.id = m.conversation_id"
          + " SET m.ctx_note = #{note}"
          + " WHERE v.family_id = #{familyId} AND m.id = #{id}")
    void updateCtxNote(@Param("familyId") long familyId, @Param("id") long id, @Param("note") String note);

    /** v1.28 · 这一问发出去的内容(llm_prompt_record.id)· 记在提问那条消息上(PRD FR-919) */
    @Update("UPDATE ask_message m JOIN ask_conversation v ON v.id = m.conversation_id"
          + " SET m.prompt_record_id = #{recordId}"
          + " WHERE v.family_id = #{familyId} AND m.id = #{id}")
    void updatePromptRecord(@Param("familyId") long familyId, @Param("id") long id, @Param("recordId") long recordId);

    /** v1.28 · 这段对话里最近一问发出去的内容(流式回答刚结束时,前端来要 >_ 的地址) */
    @Select("SELECT m.prompt_record_id FROM ask_message m JOIN ask_conversation v ON v.id = m.conversation_id"
          + " WHERE v.family_id = #{familyId} AND m.conversation_id = #{cid} AND m.role = 'user'"
          + " AND m.prompt_record_id IS NOT NULL ORDER BY m.seq DESC LIMIT 1")
    Long lastUserPromptRecord(@Param("familyId") long familyId, @Param("cid") long conversationId);
}
