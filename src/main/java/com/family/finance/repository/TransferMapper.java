package com.family.finance.repository;

import com.family.finance.domain.transfer.Transfer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * v1.24 · 家庭隔离:{@code transfer} 没有 {@code family_id} 列,归属沿
 * {@code transfer → period.family_id} 取。
 *
 * <p>走 {@code period} 而不是两端账户:一笔转账有 from / to 两个账户,
 * 按账户挂条件要写两遍、而且<b>改账户的语句里那个条件会先被自己改掉</b>。
 * 账期是这笔转账的唯一归属锚点,一个条件管住全部语句。</p>
 */
@Mapper
public interface TransferMapper {

    String COLS = " t.id, t.period_id, t.from_account_id, t.to_account_id, t.amount, t.to_amount,"
            + " t.occurred_at, t.note, t.submitted_by, t.submitted_at, t.is_draft AS draft,"
            + " t.backfill_mode AS backfillMode ";

    @Select("SELECT" + COLS + "FROM transfer t"
          + " JOIN period p ON p.id = t.period_id"
          + " WHERE p.family_id = #{familyId}"
          + " AND t.period_id = #{periodId}"
          + " AND (t.from_account_id = #{accountId} OR t.to_account_id = #{accountId})"
          + " AND t.deleted_at IS NULL"
          + " ORDER BY t.submitted_at, t.id")
    List<Transfer> findByPeriodAndAccount(@Param("familyId") long familyId,
                                          @Param("periodId") long periodId,
                                          @Param("accountId") long accountId);

    @Select("SELECT" + COLS + "FROM transfer t"
          + " JOIN period p ON p.id = t.period_id"
          + " WHERE p.family_id = #{familyId} AND t.id = #{id} AND t.deleted_at IS NULL")
    Optional<Transfer> findById(@Param("familyId") long familyId, @Param("id") long id);

    @Select("""
            SELECT t.id, t.period_id, t.from_account_id, t.to_account_id, t.amount, t.to_amount,
                   t.occurred_at, t.note, t.submitted_by, t.submitted_at, t.is_draft AS draft,
                   t.source_tag AS sourceTag
              FROM transfer t
              JOIN period p ON p.id = t.period_id
             WHERE p.family_id = #{familyId}
               AND t.deleted_at IS NULL
             ORDER BY t.period_id, t.id
            """)
    List<Transfer> findAllByFamily(@Param("familyId") long familyId);

    @Select("SELECT" + COLS + "FROM transfer t"
          + " JOIN period p ON p.id = t.period_id"
          + " WHERE p.family_id = #{familyId}"
          + " AND t.period_id = #{periodId}"
          + " AND (t.from_account_id = #{accountId} OR t.to_account_id = #{accountId})"
          + " AND t.is_draft = 0 AND t.deleted_at IS NULL"
          + " ORDER BY t.submitted_at, t.id")
    List<Transfer> findCommittedByPeriodAndAccount(@Param("familyId") long familyId,
                                                   @Param("periodId") long periodId,
                                                   @Param("accountId") long accountId);

    @Select("""
            SELECT COUNT(*)
              FROM transfer t
              JOIN period p ON p.id = t.period_id
             WHERE p.family_id = #{familyId}
               AND t.period_id = #{periodId}
               AND t.from_account_id = #{fromAccountId}
               AND t.to_account_id = #{toAccountId}
               AND t.amount = #{amount}
               AND t.is_draft = 0
               AND t.deleted_at IS NULL
               AND t.submitted_at >= NOW(3) - INTERVAL 24 HOUR
            """)
    int countRecentDuplicate(@Param("familyId") long familyId,
                             @Param("periodId") long periodId,
                             @Param("fromAccountId") long fromAccountId,
                             @Param("toAccountId") long toAccountId,
                             @Param("amount") java.math.BigDecimal amount);

    /**
     * 落一笔转账。
     *
     * <p>账期与<b>两端账户</b>必须同属这个家才真的插入 —— 转账是唯一能把钱
     * 「搬到」另一个账户的写操作,收款端漏校验等于给了一条往外搬钱的路。
     * 不符 = 影响行数 0,由 {@link #insertOwned} 抛出来。</p>
     */
    @Insert("""
            INSERT INTO transfer (
                period_id, from_account_id, to_account_id, amount, to_amount,
                occurred_at, note, submitted_by, is_draft, source_tag, backfill_mode
            )
            SELECT #{t.periodId}, #{t.fromAccountId}, #{t.toAccountId}, #{t.amount}, #{t.toAmount},
                   #{t.occurredAt}, #{t.note}, #{t.submittedBy}, #{t.draft}, COALESCE(#{t.sourceTag}, 'UNKNOWN'),
                   #{t.backfillMode}
              FROM period p
              JOIN account af ON af.id = #{t.fromAccountId}
              JOIN account at2 ON at2.id = #{t.toAccountId}
             WHERE p.id = #{t.periodId}
               AND p.family_id  = #{familyId}
               AND af.family_id = #{familyId}
               AND at2.family_id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "t.id")
    int insert(@Param("familyId") long familyId, @Param("t") Transfer transfer);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, Transfer transfer) {
        if (insert(familyId, transfer) != 1) {
            throw new IllegalStateException("转账归属校验不通过:账期 " + transfer.getPeriodId()
                    + " / 转出 " + transfer.getFromAccountId() + " / 转入 " + transfer.getToAccountId()
                    + " 不全属于家庭 " + familyId);
        }
    }

    @Update("""
            UPDATE transfer t
              JOIN period p ON p.id = t.period_id
               SET t.amount = #{t.amount},
                   t.to_amount = #{t.toAmount},
                   t.occurred_at = #{t.occurredAt},
                   t.note = #{t.note},
                   t.submitted_by = #{t.submittedBy},
                   t.submitted_at = NOW(3),
                   t.is_draft = #{t.draft}
             WHERE p.family_id = #{familyId}
               AND t.id = #{t.id}
            """)
    int updateAmountAndDraft(@Param("familyId") long familyId, @Param("t") Transfer transfer);

    /** v0.2 FR-32 · 软删:UPDATE deleted_at = NOW(3) */
    @Update("""
            UPDATE transfer t
              JOIN period p ON p.id = t.period_id
               SET t.deleted_at = NOW(3)
             WHERE p.family_id = #{familyId}
               AND t.id = #{id}
               AND t.deleted_at IS NULL
            """)
    int softDelete(@Param("familyId") long familyId, @Param("id") long id);
}
