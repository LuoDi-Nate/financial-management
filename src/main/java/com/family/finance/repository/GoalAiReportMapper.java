package com.family.finance.repository;

import com.family.finance.domain.goal.GoalAiReport;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * goal_ai_report 表 Mapper · v0.3 FR-53b/c。
 */
@Mapper
public interface GoalAiReportMapper {

    String COLS = " r.id, r.goal_id, r.period_id, r.report_type, r.content,"
            + " r.validator_status, r.generated_at, r.dismissed_at, r.prompt_record_id, r.context_fingerprint ";

    /** 归属链:goal_ai_report → family_goal.family_id */
    String OWNED = " JOIN family_goal g ON g.id = r.goal_id";

    @Select("SELECT" + COLS + "FROM goal_ai_report r" + OWNED
          + " WHERE g.family_id = #{familyId} AND r.goal_id = #{goalId}"
          + " AND r.report_type = #{reportType}"
          + " ORDER BY r.generated_at DESC LIMIT 1")
    Optional<GoalAiReport> findLatestByGoalAndType(@Param("familyId") long familyId,
                                                   @Param("goalId") long goalId,
                                                   @Param("reportType") String reportType);

    @Select("SELECT" + COLS + "FROM goal_ai_report r" + OWNED
          + " WHERE g.family_id = #{familyId} AND r.goal_id = #{goalId}"
          + " AND r.period_id = #{periodId} AND r.report_type = #{reportType}")
    Optional<GoalAiReport> findByGoalPeriodType(@Param("familyId") long familyId,
                                                @Param("goalId") long goalId,
                                                @Param("periodId") long periodId,
                                                @Param("reportType") String reportType);

    /**
     * 90 天内是否有过 ALERT(FR-53c 节流)。
     */
    @Select("SELECT COUNT(*) FROM goal_ai_report r" + OWNED
          + " WHERE g.family_id = #{familyId} AND r.goal_id = #{goalId}"
          + " AND r.report_type = 'ALERT'"
          + " AND r.generated_at > DATE_SUB(NOW(), INTERVAL 90 DAY)")
    int countRecentAlerts(@Param("familyId") long familyId, @Param("goalId") long goalId);

    @Insert("""
            INSERT INTO goal_ai_report (goal_id, period_id, report_type, content, validator_status, prompt_record_id, context_fingerprint)
            SELECT #{r.goalId}, #{r.periodId}, #{r.reportType}, #{r.content}, #{r.validatorStatus}, #{r.promptRecordId}, #{r.contextFingerprint}
              FROM family_goal g
             WHERE g.id = #{r.goalId} AND g.family_id = #{familyId}
            ON DUPLICATE KEY UPDATE
                content = VALUES(content),
                validator_status = VALUES(validator_status),
                prompt_record_id = VALUES(prompt_record_id),
                context_fingerprint = VALUES(context_fingerprint),
                generated_at = CURRENT_TIMESTAMP,
                dismissed_at = NULL
            """)
    @Options(useGeneratedKeys = true, keyProperty = "r.id")
    int upsert(@Param("familyId") long familyId, @Param("r") GoalAiReport report);

    /** 带归属断言的写入 —— 业务代码一律用这个(ON DUPLICATE → 影响行数 0/1/2) */
    default void upsertOwned(long familyId, GoalAiReport report) {
        if (upsert(familyId, report) < 1) {
            throw new IllegalStateException("目标复盘归属校验不通过:目标 " + report.getGoalId()
                    + " 不属于家庭 " + familyId);
        }
    }

    @Update("UPDATE goal_ai_report r" + OWNED
          + " SET r.dismissed_at = NOW(3)"
          + " WHERE g.family_id = #{familyId} AND r.id = #{id}")
    int dismiss(@Param("familyId") long familyId, @Param("id") long id);

    @Select("SELECT" + COLS + "FROM goal_ai_report r" + OWNED
          + " WHERE g.family_id = #{familyId} AND r.goal_id = #{goalId}"
          + " ORDER BY r.generated_at DESC LIMIT #{limit}")
    List<GoalAiReport> findRecentByGoal(@Param("familyId") long familyId,
                                        @Param("goalId") long goalId, @Param("limit") int limit);
}
