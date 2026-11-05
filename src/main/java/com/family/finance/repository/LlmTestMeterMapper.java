package com.family.finance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LlmTestMeterMapper {
    record Counter(String surface, long blocked, long sent, LocalDateTime lastBlockedAt, LocalDateTime lastSentAt) {
        public String label() {
            var known = com.family.finance.service.llmtrace.PromptSurface.parse(surface);
            if (known != null) return known.getLabel();
            return switch (surface) {
                case "ASK_TURN_LOCAL", "LOCAL_STREAM" -> "超级 Agent · 本机直连";
                case "ASK_TURN_MANAGED", "MANAGED_EVENT", "MANAGED_STREAM" -> "超级 Agent · 托管";
                case "VISION_CLIENT" -> "持仓截图识别";
                case "EXPENSE_VISION_CLIENT" -> "支出截图识别";
                default -> "文本模型调用";
            };
        }
    }

    @Insert("""
            INSERT INTO llm_test_meter (family_id, epoch, surface, blocked_count, sent_count, last_blocked_at, last_sent_at)
            SELECT id, #{epoch}, #{surface}, IF(#{blocked}, 1, 0), IF(#{blocked}, 0, 1),
                   IF(#{blocked}, CURRENT_TIMESTAMP(3), NULL), IF(#{blocked}, NULL, CURRENT_TIMESTAMP(3))
              FROM family WHERE id = #{familyId}
            ON DUPLICATE KEY UPDATE blocked_count = blocked_count + IF(#{blocked}, 1, 0),
                sent_count = sent_count + IF(#{blocked}, 0, 1),
                last_blocked_at = IF(#{blocked}, CURRENT_TIMESTAMP(3), last_blocked_at),
                last_sent_at = IF(#{blocked}, last_sent_at, CURRENT_TIMESTAMP(3))
            """)
    int upsertOwned(@Param("familyId") long familyId, @Param("epoch") String epoch,
                    @Param("surface") String surface, @Param("blocked") boolean blocked);

    @Select("""
            SELECT surface, blocked_count AS blocked, sent_count AS sent,
                   last_blocked_at AS lastBlockedAt, last_sent_at AS lastSentAt
              FROM llm_test_meter WHERE family_id = #{familyId} AND epoch = #{epoch} ORDER BY surface
            """)
    List<Counter> find(@Param("familyId") long familyId, @Param("epoch") String epoch);
}
