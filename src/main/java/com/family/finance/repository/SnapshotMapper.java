package com.family.finance.repository;

import com.family.finance.domain.snapshot.PeriodSnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Mapper
public interface SnapshotMapper {

    /**
     * v0.3 FR-50c · 应急储备 PV · 按账户 type 过滤求 end_balance 之和。
     * 假设 CASH 类账户币种 = 家庭本位币(若混合多币种,后续 v0.4 需走 fx 换算)。
     */
    @Select("""
            SELECT COALESCE(SUM(ps.end_balance), 0)
              FROM period_snapshot ps
              JOIN account a ON a.id = ps.account_id
             WHERE ps.period_id = #{periodId}
               AND a.family_id = #{familyId}
               AND a.type = #{accountType}
               AND a.archived_at IS NULL
            """)
    Optional<BigDecimal> sumEndBalanceByAccountType(@Param("familyId") long familyId,
                                                    @Param("periodId") long periodId,
                                                    @Param("accountType") String accountType);

    /**
     * v0.13 · 「开账基线」检测:在 periodId **首次出现**的账户 id
     * = 该期有快照、且**此前任何一期都没有快照**的账户。
     * 用于把"中途新增账户的存量本金"从当期投资收益里剔除(它是外部资本纳入,非当期赚)。
     */
    @Select("""
            SELECT DISTINCT ps.account_id
              FROM period_snapshot ps
             WHERE ps.period_id = #{periodId}
               AND ps.account_id NOT IN (
                   SELECT ps2.account_id
                     FROM period_snapshot ps2
                     JOIN period p2 ON p2.id = ps2.period_id
                    WHERE p2.family_id = #{familyId}
                      AND p2.period_start < (SELECT period_start FROM period WHERE id = #{periodId}))
            """)
    List<Long> firstAppearingAccountIds(@Param("familyId") long familyId,
                                        @Param("periodId") long periodId);

    /**
     * v1.11 · 一次查出**全家庭**「每个账户首次出现在哪一期」。
     *
     * <p>上面那条是按期查的,而调用它的地方全是 per-period 循环
     * ({@code openingBaseline} / {@code periodFlows} / {@code netWorthTrendExOpening} /
     * {@code accountPerformance}),一个 12 期窗口就打 12+ 次,报表页实测一次请求 881 条 SQL。
     * 而「首次出现」是**账户的属性**、与查哪一期无关 —— 一次查完在内存里分组即可。</p>
     *
     * <p>返回 {@code account_id → 首次出现的 period_id}。口径与上面那条**完全等价**:
     * 都以 {@code period_start} 升序取该账户最早有快照的那一期。</p>
     */
    // v1.11 · **一次扫完**:窗口函数按账户分组取 period_start 最早的那行。
    //   第一版写成了相关子查询(对 period_snapshot 每行再查一次 MIN)—— 3600 行 × 全表扫,
    //   O(n²),实测把报表页从 1.25s 拖到 9.3s。教训:「一条 SQL」不等于「一次扫描」,
    //   合并查询的时候必须看执行计划,不能只数条数。
    @Select("""
            SELECT t.account_id AS accountId, t.period_id AS periodId
              FROM (SELECT ps.account_id, ps.period_id,
                           ROW_NUMBER() OVER (PARTITION BY ps.account_id ORDER BY p.period_start) AS rn
                      FROM period_snapshot ps
                      JOIN period p ON p.id = ps.period_id
                     WHERE p.family_id = #{familyId}) t
             WHERE t.rn = 1
            """)
    List<FirstAppearance> firstAppearanceByAccount(@Param("familyId") long familyId);

    /** account → 首次出现的 period(v1.11 批量口径) */
    record FirstAppearance(Long accountId, Long periodId) {
    }


    /**
     * v1.23 · 必须带 {@code source_tag}。
     *
     * <p>踩过:FR-623 的余额传导用 {@code source_tag = CARRIED_FORWARD} 当守门判据
     * (只覆盖系统代填、没人确认过的那张快照)。这条 SQL 原来不查这一列 →
     * {@code getSourceTag()} 恒为 null → 判据恒不成立 → <b>传导永远不发生</b>,
     * 而且**不报错**:页面正常、日志干净,只是进行期的延续值一直是旧的。
     * 更坏的是反向断言(「用户手填过的不许被覆盖」)会**假绿** —— 什么都没传导,当然没被覆盖。</p>
     *
     * <p>判据靠某个字段时,先确认那个字段真的被查出来了。</p>
     */
    @Select("""
            SELECT ps.id, ps.period_id, ps.account_id, ps.end_balance, ps.submitted_by, ps.submitted_at,
                   ps.note, ps.source_tag
              FROM period_snapshot ps
              JOIN period p ON p.id = ps.period_id
             WHERE p.family_id = #{familyId}
               AND ps.period_id = #{periodId}
               AND ps.account_id = #{accountId}
            """)
    Optional<PeriodSnapshot> findByPeriodAndAccount(@Param("familyId") long familyId,
                                                    @Param("periodId") long periodId,
                                                    @Param("accountId") long accountId);

    @Select("""
            SELECT ps.id, ps.period_id, ps.account_id, ps.end_balance, ps.submitted_by, ps.submitted_at, ps.note
              FROM period_snapshot ps
              JOIN period p ON p.id = ps.period_id
             WHERE p.family_id = #{familyId}
               AND ps.period_id = #{periodId}
            """)
    List<PeriodSnapshot> findByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    @Select("""
            SELECT ps.id, ps.period_id, ps.account_id, ps.end_balance, ps.submitted_by, ps.submitted_at, ps.note,
                   ps.source_tag AS sourceTag
              FROM period_snapshot ps
              JOIN period p ON p.id = ps.period_id
             WHERE p.family_id = #{familyId}
             ORDER BY ps.period_id, ps.account_id
            """)
    List<PeriodSnapshot> findAllByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT ps.id, ps.period_id, ps.account_id, ps.end_balance, ps.submitted_by, ps.submitted_at, ps.note
              FROM period_snapshot ps
              JOIN period p ON p.id = ps.period_id
             WHERE p.family_id = #{familyId}
               AND ps.account_id = #{accountId}
               AND p.period_start < #{before}
             ORDER BY p.period_start DESC
             LIMIT #{limit}
            """)
    List<PeriodSnapshot> findLatestBefore(@Param("familyId") long familyId,
                                          @Param("accountId") long accountId,
                                          @Param("before") LocalDate before,
                                          @Param("limit") int limit);

    /**
     * 写这一期这个账户的期末余额。
     *
     * <p>v1.24 · 家庭隔离:账期与账户必须<b>同时</b>属于这个家才真的写入。
     * 这是全站「钱的真值」落地的那一条语句 —— 归属写错不是少一行,是把一个数字
     * 落进别人家的资产负债表里。</p>
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} 让影响行数有三种取值:
     * 0=没写(归属不符或无变化)、1=新插、2=更新。所以 {@link #upsertOwned} 判的是
     * {@code < 1} 而不是 {@code != 1}。</p>
     */
    @Insert("""
            INSERT INTO period_snapshot (period_id, account_id, end_balance, submitted_by, note, source_tag)
            SELECT #{s.periodId}, #{s.accountId}, #{s.endBalance}, #{s.submittedBy}, #{s.note},
                   COALESCE(#{s.sourceTag}, 'UNKNOWN')
              FROM period p
              JOIN account a ON a.id = #{s.accountId}
             WHERE p.id = #{s.periodId}
               AND p.family_id = #{familyId}
               AND a.family_id = #{familyId}
            ON DUPLICATE KEY UPDATE
                end_balance = VALUES(end_balance),
                submitted_by = VALUES(submitted_by),
                submitted_at = NOW(3),
                note = VALUES(note),
                source_tag = VALUES(source_tag)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "s.id")
    int upsert(@Param("familyId") long familyId, @Param("s") PeriodSnapshot snapshot);

    /** 带归属断言的写入 —— 业务代码一律用这个 */
    default void upsertOwned(long familyId, PeriodSnapshot snapshot) {
        if (upsert(familyId, snapshot) < 1) {
            throw new IllegalStateException("快照归属校验不通过:账期 " + snapshot.getPeriodId()
                    + " / 账户 " + snapshot.getAccountId() + " 不属于家庭 " + familyId);
        }
    }

    // ── v1.34.2 · 往「之后已有人确认过余额」的账期里补记(维护者 2026-10-15 拍板:每次问,默认 A)──────────
    //   「确认过」= 不是系统代填的延续值(CARRIED_FORWARD)。系统代填的照旧走传导,不算断点。

    /** 这个账户在 {@code after} 之后的期里,有没有有人确认过的余额 */
    @Select("""
            SELECT COUNT(*)
              FROM period_snapshot s
              JOIN period p ON p.id = s.period_id
             WHERE p.family_id = #{familyId}
               AND s.account_id = #{accountId}
               AND p.period_start > #{after}
               AND s.source_tag <> 'CARRIED_FORWARD'
            """)
    int countConfirmedAfter(@Param("familyId") long familyId,
                            @Param("accountId") long accountId,
                            @Param("after") LocalDate after);

    /**
     * 填报页用:补记时要问 A / B 的账户 = 这一期的余额确认过、之后的期里也有确认过的余额
     * (与 {@code EntryService.backfillAsks} 同一个判据,改一处要改两处 · 护栏 v1342-BACKFILL-JUDGE-ALIGNED)
     */
    @Select("""
            SELECT DISTINCT cur.account_id
              FROM period_snapshot cur
              JOIN period pc ON pc.id = cur.period_id
             WHERE pc.family_id = #{familyId}
               AND pc.id = #{periodId}
               AND cur.source_tag <> 'CARRIED_FORWARD'
               AND EXISTS (SELECT 1 FROM period_snapshot s
                             JOIN period p ON p.id = s.period_id
                            WHERE p.family_id = #{familyId}
                              AND s.account_id = cur.account_id
                              AND p.period_start > pc.period_start
                              AND s.source_tag <> 'CARRIED_FORWARD')
            """)
    List<Long> accountsAskingBackfill(@Param("familyId") long familyId, @Param("periodId") long periodId);

    /**
     * B · 「当时的余额还没算上它」:把 {@code after} 之后<b>每一期</b>(含已关账)这个账户的余额挪 {@code delta}。
     * 之后各期的逐期损益因此不变 —— 对方账户不再凭空出现一笔等额收益 / 亏损。来源标记不动。
     */
    @org.apache.ibatis.annotations.Update("""
            UPDATE period_snapshot s
              JOIN period p ON p.id = s.period_id
               SET s.end_balance = s.end_balance + #{delta},
                   s.note = LEFT(CONCAT(#{note}, IFNULL(CONCAT(' · ', s.note), '')), 255)
             WHERE p.family_id = #{familyId}
               AND s.account_id = #{accountId}
               AND p.period_start > #{after}
            """)
    int shiftAfter(@Param("familyId") long familyId,
                   @Param("accountId") long accountId,
                   @Param("after") LocalDate after,
                   @Param("delta") BigDecimal delta,
                   @Param("note") String note);
}
