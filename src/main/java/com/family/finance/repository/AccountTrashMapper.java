package com.family.finance.repository;

import com.family.finance.domain.trash.AccountTrash;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v1.33 · 回收站 + 通用搬运(tech-design v1.33 选型四)。
 *
 * <p>前三个方法是<b>通用搬运</b>:{@code table} 与 {@code where} 只来自 {@code AccountTrashSpec} 里的代码常量,
 * 从不来自请求;每条 where 都在过滤位置带 {@code #{familyId}}(单测 {@code AccountTrashSpecTest} 钉住)。
 * 家庭隔离审计按 SQL 原文扫不到 {@code ${}} 里的表名,所以这一条由单测兜。</p>
 */
@Mapper
public interface AccountTrashMapper {

    // ── 通用搬运 ────────────────────────────────────────────────────────────

    @Select("SELECT t.* FROM ${table} t WHERE ${where}")
    List<Map<String, Object>> selectRows(@Param("table") String table, @Param("where") String where,
                                         @Param("familyId") long familyId, @Param("accountId") long accountId);

    @Select("SELECT COUNT(*) FROM ${table} t WHERE ${where}")
    int countRows(@Param("table") String table, @Param("where") String where,
                  @Param("familyId") long familyId, @Param("accountId") long accountId);

    @Delete("DELETE t FROM ${table} t WHERE ${where}")
    int deleteRows(@Param("table") String table, @Param("where") String where,
                   @Param("familyId") long familyId, @Param("accountId") long accountId);

    /** 恢复:一行原样插回(列名已按当前表结构过滤过) */
    @Insert("""
            <script>
            INSERT INTO ${table} (<foreach collection='cols' item='c' separator=','>`${c}`</foreach>)
            VALUES (<foreach collection='vals' item='v' separator=','>#{v}</foreach>)
            </script>
            """)
    int insertRow(@Param("table") String table, @Param("cols") List<String> cols, @Param("vals") List<Object> vals);

    /** 当前库里这张表有哪些列(恢复时按它过滤:表后来少了的列丢掉,多了的用默认值) */
    @Select("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = #{table} ORDER BY ORDINAL_POSITION")
    List<String> columnsOf(@Param("table") String table);

    // ── 恢复前的依赖检查(都带家庭)───────────────────────────────────────

    @Select("SELECT COUNT(*) FROM period WHERE id = #{id} AND family_id = #{familyId}")
    int periodExists(@Param("familyId") long familyId, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM account WHERE id = #{id} AND family_id = #{familyId}")
    int accountExists(@Param("familyId") long familyId, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM family_goal WHERE id = #{id} AND family_id = #{familyId}")
    int goalExists(@Param("familyId") long familyId, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM rebalance_plan WHERE id = #{id} AND family_id = #{familyId}")
    int planExists(@Param("familyId") long familyId, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM account_group WHERE id = #{id} AND family_id = #{familyId}")
    int groupExists(@Param("familyId") long familyId, @Param("id") long id);

    @Select("""
            SELECT COUNT(*) FROM transfer t JOIN period p ON p.id = t.period_id AND p.family_id = #{familyId}
             WHERE t.id = #{id}
            """)
    int transferExists(@Param("familyId") long familyId, @Param("id") long id);

    // ── 删除时顺带改的别处 ──────────────────────────────────────────────────

    /** 别的贷款把它设成了默认还款来源(D9) */
    @Select("SELECT id FROM account WHERE family_id = #{familyId} AND default_payment_source_account_id = #{accountId} AND id <> #{accountId}")
    List<Long> loansPayingFrom(@Param("familyId") long familyId, @Param("accountId") long accountId);

    @Update("UPDATE account SET default_payment_source_account_id = #{value} WHERE id = #{id} AND family_id = #{familyId}")
    int setPaymentSource(@Param("familyId") long familyId, @Param("id") long id, @Param("value") Long value);

    /** 恢复时只还原「还空着」的(期间有人另设了就不覆盖) */
    @Update("UPDATE account SET default_payment_source_account_id = #{value} WHERE id = #{id} AND family_id = #{familyId} AND default_payment_source_account_id IS NULL")
    int restorePaymentSource(@Param("familyId") long familyId, @Param("id") long id, @Param("value") long value);

    /** 别的账户的待填行指向它的划转(外键)—— 删前置空 */
    @Select("""
            SELECT CONCAT(td.id, ':', td.prefilled_transfer_id)
              FROM snapshot_todo td JOIN period p ON p.id = td.period_id AND p.family_id = #{familyId}
              JOIN transfer t ON t.id = td.prefilled_transfer_id
             WHERE (t.from_account_id = #{accountId} OR t.to_account_id = #{accountId}) AND td.account_id <> #{accountId}
            """)
    List<String> todoRefsToTransfersOf(@Param("familyId") long familyId, @Param("accountId") long accountId);

    @Update("""
            UPDATE snapshot_todo td JOIN period p ON p.id = td.period_id AND p.family_id = #{familyId}
               SET td.prefilled_transfer_id = #{transferId}
             WHERE td.id = #{todoId}
            """)
    int setTodoTransfer(@Param("familyId") long familyId, @Param("todoId") long todoId, @Param("transferId") Long transferId);

    /** AI 月度复盘存稿(涉及的期)· 调仓建议存稿 —— 删完重新生成(D6) */
    @Delete("""
            <script>
            DELETE FROM review_ai_cache WHERE family_id = #{familyId} AND period_id IN
            <foreach collection='periodIds' item='p' open='(' separator=',' close=')'>#{p}</foreach>
            </script>
            """)
    int clearReviewCache(@Param("familyId") long familyId, @Param("periodIds") List<Long> periodIds);

    @Delete("DELETE FROM rebalance_advice_cache WHERE family_id = #{familyId}")
    int clearAdviceCache(@Param("familyId") long familyId);

    // ── 预演用的读 ───────────────────────────────────────────────────────────

    /** 改写的期:它有余额 / 流水 / 划转 / 补录本金的那几期 */
    @Select("""
            SELECT DISTINCT x.period_id FROM (
              SELECT ps.period_id FROM period_snapshot ps WHERE ps.account_id = #{accountId}
              UNION SELECT cf.period_id FROM cash_flow cf WHERE cf.account_id = #{accountId} AND cf.deleted_at IS NULL
              UNION SELECT t.period_id FROM transfer t WHERE (t.from_account_id = #{accountId} OR t.to_account_id = #{accountId})
                       AND t.deleted_at IS NULL AND t.is_draft = 0
              UNION SELECT pa.period_id FROM principal_adjustment pa WHERE pa.family_id = #{familyId} AND pa.account_id = #{accountId} AND pa.deleted_at IS NULL
            ) x JOIN period p ON p.id = x.period_id AND p.family_id = #{familyId}
            """)
    List<Long> affectedPeriodIds(@Param("familyId") long familyId, @Param("accountId") long accountId);

    /** 收入 / 支出:笔数与合计(账户币种,不含已删的) */
    @Select("""
            SELECT COUNT(*) AS n, COALESCE(SUM(cf.amount), 0) AS total
              FROM cash_flow cf JOIN period p ON p.id = cf.period_id AND p.family_id = #{familyId}
             WHERE cf.account_id = #{accountId} AND cf.kind = #{kind} AND cf.deleted_at IS NULL
            """)
    Map<String, Object> flowTotals(@Param("familyId") long familyId, @Param("accountId") long accountId, @Param("kind") String kind);

    /** 和别的账户之间、真实生效的划转(不含已删、不含草稿),按期、按 id */
    @Select("""
            SELECT t.id, t.period_id AS periodId, t.from_account_id AS fromAccountId, t.to_account_id AS toAccountId,
                   t.amount, t.to_amount AS toAmount
              FROM transfer t JOIN period p ON p.id = t.period_id AND p.family_id = #{familyId}
             WHERE (t.from_account_id = #{accountId} OR t.to_account_id = #{accountId})
               AND t.deleted_at IS NULL AND t.is_draft = 0
             ORDER BY p.period_start, t.id
            """)
    List<TransferRow> liveTransfers(@Param("familyId") long familyId, @Param("accountId") long accountId);

    record TransferRow(Long id, Long periodId, Long fromAccountId, Long toAccountId, BigDecimal amount, BigDecimal toAmount) {
        public BigDecimal received() { return toAmount != null ? toAmount : amount; }
    }

    @Select("""
            SELECT g.name FROM goal_account ga JOIN family_goal g ON g.id = ga.goal_id AND g.family_id = #{familyId}
             WHERE ga.account_id = #{accountId}
            """)
    List<String> goalNames(@Param("familyId") long familyId, @Param("accountId") long accountId);

    // ── 回收站表 ─────────────────────────────────────────────────────────────

    @Insert("""
            INSERT INTO account_trash (family_id, account_id, account_name, account_type, period_ids, range_label, closed_count,
                                       transfer_count, counterpart_count, summary, payload, rewrites, backup_file, deleted_by)
            SELECT f.id, #{e.accountId}, #{e.accountName}, #{e.accountType}, #{e.periodIds}, #{e.rangeLabel}, #{e.closedCount},
                   #{e.transferCount}, #{e.counterpartCount}, #{e.summary}, #{e.payload}, #{e.rewrites}, #{e.backupFile}, #{e.deletedBy}
              FROM family f WHERE f.id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "e.id")
    int insert(@Param("familyId") long familyId, @Param("e") AccountTrash e);

    default void insertOwned(long familyId, AccountTrash e) {
        if (insert(familyId, e) != 1) throw new IllegalStateException("家庭 " + familyId + " 不存在");
    }

    String META = """
            id, family_id, account_id, account_name, account_type, period_ids, range_label, closed_count, transfer_count,
            counterpart_count, summary, backup_file, deleted_by, deleted_at, restored_by, restored_at, restore_notes,
            purged_by, purged_at
            """;

    /** 列表(不带 payload —— 可能有几 MB) */
    @Select("SELECT " + META + " FROM account_trash WHERE family_id = #{familyId} ORDER BY deleted_at DESC, id DESC")
    List<AccountTrash> list(@Param("familyId") long familyId);

    @Select("SELECT " + META + ", payload, rewrites FROM account_trash WHERE id = #{id} AND family_id = #{familyId}")
    Optional<AccountTrash> findFull(@Param("familyId") long familyId, @Param("id") long id);

    @Update("""
            UPDATE account_trash SET restored_by = #{memberId}, restored_at = NOW(3), restore_notes = #{notes}
             WHERE id = #{id} AND family_id = #{familyId} AND restored_at IS NULL AND purged_at IS NULL
            """)
    int markRestored(@Param("familyId") long familyId, @Param("id") long id, @Param("memberId") long memberId,
                     @Param("notes") String notes);

    @Update("""
            UPDATE account_trash SET purged_by = #{memberId}, purged_at = NOW(3), payload = NULL, rewrites = NULL
             WHERE id = #{id} AND family_id = #{familyId} AND restored_at IS NULL AND purged_at IS NULL
            """)
    int markPurged(@Param("familyId") long familyId, @Param("id") long id, @Param("memberId") long memberId);

    /** 报表留痕(FR-1240):改写范围含这一期的删除 / 恢复 */
    @Select("""
            SELECT account_name AS accountName, deleted_at AS deletedAt, restored_at AS restoredAt
              FROM account_trash
             WHERE family_id = #{familyId} AND FIND_IN_SET(#{periodId}, period_ids) > 0
             ORDER BY deleted_at
            """)
    List<TraceRow> tracesForPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    record TraceRow(String accountName, java.time.LocalDateTime deletedAt, java.time.LocalDateTime restoredAt) {
    }
}
