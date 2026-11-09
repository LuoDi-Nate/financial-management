package com.family.finance.repository;

import org.apache.ibatis.annotations.*;
import java.util.Collection;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 这里只查有无来源,金额仍经事实/支出服务,不产生另一套金额聚合。 */
@Mapper
public interface RetirementSourceMapper {
    record BalanceSource(Long accountId, LocalDate periodStart, LocalDateTime submittedAt, String sourceTag) { }
    @Select("""
        SELECT accountId,periodStart,submittedAt,sourceTag FROM (
          SELECT s.account_id AS accountId,p.period_start AS periodStart,s.submitted_at AS submittedAt,
                 s.source_tag AS sourceTag,
                 ROW_NUMBER() OVER (PARTITION BY s.account_id ORDER BY p.period_start DESC) AS rn
            FROM period_snapshot s JOIN period p ON p.id=s.period_id
            JOIN account a ON a.id=s.account_id AND a.family_id=p.family_id
           WHERE p.family_id=#{familyId} AND p.period_start <= #{asOf}
             AND s.end_balance IS NOT NULL
        ) balances WHERE rn=1
        """)
    List<BalanceSource> balanceSources(@Param("familyId") long familyId, @Param("asOf") LocalDate asOf);
    record SnapshotPoint(Long periodId,Long accountId) { }
    @Select("""
        <script>
        SELECT s.period_id AS periodId,s.account_id AS accountId
          FROM period_snapshot s JOIN period p ON p.id=s.period_id
          JOIN account a ON a.id=s.account_id AND a.family_id=p.family_id
         WHERE p.family_id=#{familyId} AND s.end_balance IS NOT NULL AND s.period_id IN
           <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<SnapshotPoint> snapshots(@Param("familyId") long familyId,@Param("ids") Collection<Long> ids);
    record Presence(Long periodId, int incomeFields, int expenseFields, int incomeRows, int completedMembers, int requiredMembers) { }
    @Select("""
        <script>
        SELECT p.id AS periodId,
          (SELECT COUNT(*) FROM period_member_cashflow m WHERE m.period_id=p.id AND m.family_id=p.family_id AND m.total_income_input IS NOT NULL) AS incomeFields,
          (SELECT COUNT(*) FROM period_member_cashflow m WHERE m.period_id=p.id AND m.family_id=p.family_id AND m.total_expense_input IS NOT NULL) AS expenseFields,
          (SELECT COUNT(*) FROM cash_flow c JOIN account a ON a.id=c.account_id
            WHERE c.period_id=p.id AND a.family_id=p.family_id AND c.kind='INCOME' AND c.deleted_at IS NULL AND c.is_adjustment=0) AS incomeRows,
          (SELECT COUNT(*) FROM period_member_completion c WHERE c.period_id=p.id) AS completedMembers,
          (SELECT COUNT(*) FROM member m WHERE m.family_id=p.family_id AND m.archived_at IS NULL) AS requiredMembers
        FROM period p WHERE p.family_id=#{familyId} AND p.id IN
          <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<Presence> presence(@Param("familyId") long familyId, @Param("ids") Collection<Long> ids);
}
