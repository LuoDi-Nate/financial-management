package com.family.finance.repository;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface RetirementPlanMapper {
    record Row(long goalId, long familyId, String planJson, boolean active, boolean createdForPlan, LocalDateTime updatedAt) { }

    @Select("SELECT goal_id,family_id,plan_json,active,created_for_plan,updated_at FROM retirement_plan WHERE family_id=#{familyId} AND goal_id=#{goalId}")
    Optional<Row> find(@Param("familyId") long familyId, @Param("goalId") long goalId);

    @Select("SELECT goal_id,family_id,plan_json,active,created_for_plan,updated_at FROM retirement_plan WHERE family_id=#{familyId}")
    List<Row> findAll(@Param("familyId") long familyId);

    @Insert("""
        INSERT INTO retirement_plan(goal_id,family_id,plan_json,active,created_for_plan)
        SELECT g.id,g.family_id,#{json},#{active},#{created}
          FROM family_goal g
         WHERE g.family_id=#{familyId} AND g.id=#{goalId} AND g.goal_type='RETIREMENT' AND g.archived_at IS NULL
        ON DUPLICATE KEY UPDATE plan_json=VALUES(plan_json),active=VALUES(active),updated_at=CURRENT_TIMESTAMP(3)
        """)
    int save(@Param("familyId") long familyId, @Param("goalId") long goalId,
             @Param("json") String json, @Param("active") boolean active, @Param("created") boolean created);

    @Update("UPDATE retirement_plan SET active=FALSE,updated_at=CURRENT_TIMESTAMP(3) WHERE family_id=#{familyId} AND goal_id=#{goalId} AND created_for_plan=FALSE")
    int deactivate(@Param("familyId") long familyId, @Param("goalId") long goalId);
}
