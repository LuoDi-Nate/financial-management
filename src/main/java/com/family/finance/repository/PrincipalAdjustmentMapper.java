package com.family.finance.repository;

import com.family.finance.domain.ledger.PrincipalAdjustment;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * v1.30 · principal_adjustment(补录本金)。
 *
 * <p>进指标只走事实层(FactMapper.xml 的 {@code pa} 子查询);这里的 {@link #sumByPeriodAndAccount}
 * 只给填报页的「未解释差额」当已知流入用(同一笔钱在本账户视角下的解释),不是第二条口径。</p>
 */
@Mapper
public interface PrincipalAdjustmentMapper {

    /** 账户与期都必须属于这个家、期必须还开着;不符 = 影响行数 0 */
    @Insert("""
            INSERT INTO principal_adjustment
                (family_id, account_id, period_id, amount, holding_id, note, source_tag, member_id)
            SELECT #{familyId}, a.id, p.id, #{e.amount}, #{e.holdingId}, #{e.note},
                   COALESCE(#{e.sourceTag}, 'MANUAL'), #{e.memberId}
              FROM account a
              JOIN period p ON p.id = #{e.periodId} AND p.family_id = #{familyId} AND p.status = 'OPEN'
             WHERE a.id = #{e.accountId} AND a.family_id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "e.id")
    int insert(@Param("familyId") long familyId, @Param("e") PrincipalAdjustment e);

    /** 账户详情时间线 · 未删除的 · 带持仓名 */
    @Select("""
            SELECT pa.id, pa.family_id, pa.account_id, pa.period_id, pa.amount, pa.holding_id, pa.note,
                   pa.source_tag, pa.member_id, pa.created_at, pa.deleted_at, h.display_name AS holdingName
              FROM principal_adjustment pa
              LEFT JOIN stock_holding h ON h.id = pa.holding_id AND h.account_id = pa.account_id
             WHERE pa.family_id = #{familyId} AND pa.account_id = #{accountId} AND pa.deleted_at IS NULL
             ORDER BY pa.created_at DESC, pa.id DESC
            """)
    List<PrincipalAdjustment> findByAccount(@Param("familyId") long familyId, @Param("accountId") long accountId);

    /** 数据导出 · 含已删除(带 deleted_at,备份要完整) */
    @Select("""
            SELECT id, family_id, account_id, period_id, amount, holding_id, note, source_tag, member_id,
                   created_at, deleted_at
              FROM principal_adjustment
             WHERE family_id = #{familyId}
             ORDER BY id
            """)
    List<PrincipalAdjustment> findAllByFamily(@Param("familyId") long familyId);

    /** 填报页「未解释差额」:这一期这个账户补录了多少本金(已知流入) */
    @Select("""
            SELECT COALESCE(SUM(amount), 0)
              FROM principal_adjustment
             WHERE family_id = #{familyId} AND period_id = #{periodId} AND account_id = #{accountId}
               AND deleted_at IS NULL
            """)
    BigDecimal sumByPeriodAndAccount(@Param("familyId") long familyId, @Param("periodId") long periodId,
                                     @Param("accountId") long accountId);

    /** 删除(软删)· 只有还开着的期能删 */
    @Update("""
            UPDATE principal_adjustment pa
              JOIN period p ON p.id = pa.period_id AND p.family_id = pa.family_id
               SET pa.deleted_at = NOW()
             WHERE pa.id = #{id} AND pa.family_id = #{familyId} AND pa.account_id = #{accountId}
               AND pa.deleted_at IS NULL AND p.status = 'OPEN'
            """)
    int softDelete(@Param("familyId") long familyId, @Param("accountId") long accountId, @Param("id") long id);

    /**
     * 这一期之前,这个账户最近一次的期末余额(与 FactMapper 的 previous_end_balance 同一个判法)。
     * null = 这一期是它的第一期 —— 第一期的余额整笔算开账基线,不需要、也不能再补录。
     */
    @Select("""
            SELECT ps.end_balance
              FROM period_snapshot ps
              JOIN period p ON p.id = ps.period_id
             WHERE p.family_id = #{familyId} AND ps.account_id = #{accountId}
               AND p.period_start < #{periodStart}
             ORDER BY p.period_start DESC
             LIMIT 1
            """)
    BigDecimal findPreviousEndBalance(@Param("familyId") long familyId, @Param("accountId") long accountId,
                                      @Param("periodStart") LocalDate periodStart);
}
