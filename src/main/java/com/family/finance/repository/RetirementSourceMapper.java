package com.family.finance.repository;

import org.apache.ibatis.annotations.*;
import java.util.Collection;
import java.util.List;

/** 这里只查有无来源,金额仍经事实/支出服务,不产生另一套金额聚合。 */
@Mapper
public interface RetirementSourceMapper {
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
