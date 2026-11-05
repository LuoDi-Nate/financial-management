package com.family.finance.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** v1.28 · 发给 AI 的内容(V65)· 每条 SQL 都带 family_id */
@Mapper
public interface PromptRecordMapper {

    class Row {
        public Long id;
        public Long familyId;
        public String surface;
        public String systemHash;
        public String systemText;
        public String userText;
        public String vendor;
        public String outcome;
        public String outcomeNote;
        public String settingsNote;
        public String legendJson;
        public LocalDateTime createdAt;
    }

    /** 规矩那一段按 (家庭, hash) 去重:已存在就什么都不做 */
    @Insert("INSERT IGNORE INTO llm_system_prompt (family_id, hash, text) VALUES (#{familyId}, #{hash}, #{text})")
    int insertSystem(@Param("familyId") long familyId, @Param("hash") String hash, @Param("text") String text);

    @Insert("""
            INSERT INTO llm_prompt_record
                   (family_id, surface, system_hash, user_text, vendor, outcome, outcome_note, settings_note, legend_json)
            VALUES (#{familyId}, #{surface}, #{systemHash}, #{userText}, #{vendor}, #{outcome}, #{outcomeNote},
                    #{settingsNote}, #{legendJson})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Row row);

    @Select("""
            SELECT r.id, r.family_id AS familyId, r.surface, r.system_hash AS systemHash, s.text AS systemText,
                   r.user_text AS userText, r.vendor, r.outcome, r.outcome_note AS outcomeNote,
                   r.settings_note AS settingsNote, r.legend_json AS legendJson, r.created_at AS createdAt
              FROM llm_prompt_record r
              LEFT JOIN llm_system_prompt s ON s.family_id = r.family_id AND s.hash = r.system_hash
             WHERE r.id = #{id} AND r.family_id = #{familyId}
            """)
    Row find(@Param("familyId") long familyId, @Param("id") long id);

    @Select("""
            SELECT id, family_id AS familyId, surface, created_at AS createdAt
              FROM llm_prompt_record
             WHERE family_id=#{familyId} AND outcome='BLOCKED' AND created_at>=#{since}
             ORDER BY id DESC LIMIT 20
            """)
    List<Row> recentPrepared(@Param("familyId") long familyId,@Param("since") LocalDateTime since);

    @Update("UPDATE llm_prompt_record SET outcome = #{outcome}, outcome_note = #{note} WHERE id = #{id} AND family_id = #{familyId}")
    int updateOutcome(@Param("familyId") long familyId, @Param("id") long id,
                      @Param("outcome") String outcome, @Param("note") String note);

    @Delete("DELETE FROM llm_prompt_record WHERE id = #{id} AND family_id = #{familyId}")
    int delete(@Param("familyId") long familyId, @Param("id") long id);

    // ── 每天清理(按家庭逐个清,不跨家庭一把删;家庭清单取自 FamilyMapper)──────

    @Delete("""
            <script>
            DELETE FROM llm_prompt_record
             WHERE family_id = #{familyId} AND created_at &lt; #{before}
               AND surface IN <foreach collection="surfaces" item="s" open="(" separator="," close=")">#{s}</foreach>
            </script>
            """)
    int deleteOlderThan(@Param("familyId") long familyId, @Param("before") LocalDateTime before,
                        @Param("surfaces") List<String> surfaces);

    /** 没有任何记录再引用的规矩文本 */
    @Delete("""
            DELETE s FROM llm_system_prompt s
              LEFT JOIN llm_prompt_record r ON r.family_id = s.family_id AND r.system_hash = s.hash
             WHERE s.family_id = #{familyId} AND r.id IS NULL
            """)
    int deleteOrphanSystems(@Param("familyId") long familyId);
}
