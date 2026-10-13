package com.family.finance.repository;

import com.family.finance.domain.fixedflow.FixedFlow;
import com.family.finance.domain.fixedflow.FixedFlowOccurrence;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * v1.32 · fixed_flow(固定收支设置)+ fixed_flow_occurrence(每期的结果)。
 *
 * <p>两张表都带 {@code family_id},每条 SQL 都在过滤位置用它(L17)。插入写成
 * {@code INSERT … SELECT … WHERE 父表.family_id = #{familyId}}:账户 / 期不属于这个家 → 影响行数 0,
 * 调用方经 {@link #insertOwned} / {@link #insertOccurrenceOwned} 断言。</p>
 *
 * <p><b>这里没有任何写 cash_flow / transfer / period_snapshot 的方法</b> —— 那些只经 EntryService
 * (护栏 v132-ONE-WRITE-PATH)。下面几个读 cash_flow 的查询只用于提示(导入去重、信用卡判断)。</p>
 */
@Mapper
public interface FixedFlowMapper {

    String COLS = """
            id, family_id, name, kind, repay_kind, amount_mode, auto_method, amount, account_id, target_account_id,
            category_code, expense_category_id, cycle, months, day_of_month, owner_member_id, start_month, end_month,
            note, disabled_at, disabled_reason, created_by, created_at, updated_at
            """;

    // ── fixed_flow ──────────────────────────────────────────────────────────

    /** 付款 / 入账账户必须属于这个家;不符 = 影响行数 0 */
    @Insert("""
            INSERT INTO fixed_flow (family_id, name, kind, repay_kind, amount_mode, auto_method, amount, account_id,
                                    target_account_id, category_code, expense_category_id, cycle, months, day_of_month,
                                    owner_member_id, start_month, end_month, note, created_by)
            SELECT #{familyId}, #{f.name}, #{f.kind}, #{f.repayKind}, #{f.amountMode}, #{f.autoMethod}, #{f.amount}, a.id,
                   #{f.targetAccountId}, #{f.categoryCode}, #{f.expenseCategoryId}, #{f.cycle}, #{f.months}, #{f.dayOfMonth},
                   #{f.ownerMemberId}, #{f.startMonth}, #{f.endMonth}, #{f.note}, #{f.createdBy}
              FROM account a
             WHERE a.id = #{f.accountId} AND a.family_id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "f.id")
    int insert(@Param("familyId") long familyId, @Param("f") FixedFlow f);

    default void insertOwned(long familyId, FixedFlow f) {
        if (insert(familyId, f) != 1) {
            throw new IllegalStateException("固定收支归属校验不通过:账户 " + f.getAccountId() + " 不属于家庭 " + familyId);
        }
    }

    @Update("""
            UPDATE fixed_flow f
              JOIN account a ON a.id = #{f.accountId} AND a.family_id = #{familyId}
               SET f.name = #{f.name}, f.kind = #{f.kind}, f.repay_kind = #{f.repayKind}, f.amount_mode = #{f.amountMode},
                   f.auto_method = #{f.autoMethod}, f.amount = #{f.amount}, f.account_id = #{f.accountId},
                   f.target_account_id = #{f.targetAccountId}, f.category_code = #{f.categoryCode},
                   f.expense_category_id = #{f.expenseCategoryId}, f.cycle = #{f.cycle}, f.months = #{f.months},
                   f.day_of_month = #{f.dayOfMonth}, f.owner_member_id = #{f.ownerMemberId},
                   f.end_month = #{f.endMonth}, f.note = #{f.note}
             WHERE f.id = #{f.id} AND f.family_id = #{familyId}
            """)
    int update(@Param("familyId") long familyId, @Param("f") FixedFlow f);

    /** 当场改金额后选了「以后都按新金额」(FR-1113) */
    @Update("UPDATE fixed_flow SET amount = #{amount} WHERE id = #{id} AND family_id = #{familyId}")
    int updateAmount(@Param("familyId") long familyId, @Param("id") long id, @Param("amount") BigDecimal amount);

    @Select("SELECT " + COLS + " FROM fixed_flow WHERE id = #{id} AND family_id = #{familyId}")
    Optional<FixedFlow> findById(@Param("familyId") long familyId, @Param("id") long id);

    /** 管理页 · 含停用的 */
    @Select("SELECT " + COLS + " FROM fixed_flow WHERE family_id = #{familyId} ORDER BY disabled_at IS NOT NULL, kind, day_of_month, id")
    List<FixedFlow> findAllByFamily(@Param("familyId") long familyId);

    @Select("SELECT " + COLS + " FROM fixed_flow WHERE family_id = #{familyId} AND disabled_at IS NULL ORDER BY day_of_month, id")
    List<FixedFlow> findActiveByFamily(@Param("familyId") long familyId);

    /** 账户详情(FR-1133):指着这笔贷款 / 这个保险账户的启用中固定收支 */
    @Select("SELECT " + COLS + " FROM fixed_flow WHERE family_id = #{familyId} AND target_account_id = #{accountId} AND disabled_at IS NULL ORDER BY id")
    List<FixedFlow> findActiveByTarget(@Param("familyId") long familyId, @Param("accountId") long accountId);

    @Update("""
            UPDATE fixed_flow SET disabled_at = NOW(), disabled_reason = #{reason}
             WHERE id = #{id} AND family_id = #{familyId} AND disabled_at IS NULL
            """)
    int disable(@Param("familyId") long familyId, @Param("id") long id, @Param("reason") String reason);

    @Update("""
            UPDATE fixed_flow SET disabled_at = NULL, disabled_reason = NULL
             WHERE id = #{id} AND family_id = #{familyId}
            """)
    int enable(@Param("familyId") long familyId, @Param("id") long id);

    /** 账户归档 → 用到它的固定收支一起停用(FR-1134);返回停了几条 */
    @Update("""
            UPDATE fixed_flow SET disabled_at = NOW(), disabled_reason = 'ACCOUNT_ARCHIVED'
             WHERE family_id = #{familyId} AND disabled_at IS NULL
               AND (account_id = #{accountId} OR target_account_id = #{accountId})
            """)
    int disableByAccount(@Param("familyId") long familyId, @Param("accountId") long accountId);

    // ── fixed_flow_occurrence ───────────────────────────────────────────────

    /**
     * 先占位(唯一键 (fixed_flow_id, period_id) 挡住重复确认),写完流水再 {@link #fillOccurrence}。
     * 固定收支与期都必须属于这个家,期必须开着。
     */
    @Insert("""
            INSERT INTO fixed_flow_occurrence (family_id, fixed_flow_id, period_id, due_date, status, amount, principal,
                                               interest, cash_flow_id, formula, member_id)
            SELECT #{familyId}, f.id, p.id, #{o.dueDate}, #{o.status}, #{o.amount}, #{o.principal}, #{o.interest},
                   #{o.cashFlowId}, #{o.formula}, #{o.memberId}
              FROM fixed_flow f
              JOIN period p ON p.id = #{o.periodId} AND p.family_id = #{familyId} AND p.status = 'OPEN'
             WHERE f.id = #{o.fixedFlowId} AND f.family_id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "o.id")
    int insertOccurrence(@Param("familyId") long familyId, @Param("o") FixedFlowOccurrence o);

    default void insertOccurrenceOwned(long familyId, FixedFlowOccurrence o) {
        if (insertOccurrence(familyId, o) != 1) {
            throw new IllegalStateException("这一期已经关账,或这条固定收支不属于这个家");
        }
    }

    @Update("""
            UPDATE fixed_flow_occurrence
               SET amount = #{o.amount}, principal = #{o.principal}, interest = #{o.interest},
                   cash_flow_id = #{o.cashFlowId}, interest_cash_flow_id = #{o.interestCashFlowId},
                   transfer_id = #{o.transferId}, loan_balance_before = #{o.loanBalanceBefore},
                   loan_balance_after = #{o.loanBalanceAfter}, formula = #{o.formula}
             WHERE id = #{o.id} AND family_id = #{familyId}
            """)
    int fillOccurrence(@Param("familyId") long familyId, @Param("o") FixedFlowOccurrence o);

    @Select("""
            SELECT id, family_id, fixed_flow_id, period_id, due_date, status, amount, principal, interest, cash_flow_id,
                   interest_cash_flow_id, transfer_id, loan_balance_before, loan_balance_after, formula, member_id, created_at
              FROM fixed_flow_occurrence WHERE family_id = #{familyId} AND period_id = #{periodId}
            """)
    List<FixedFlowOccurrence> findOccurrencesByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    @Select("""
            SELECT id, family_id, fixed_flow_id, period_id, due_date, status, amount, principal, interest, cash_flow_id,
                   interest_cash_flow_id, transfer_id, loan_balance_before, loan_balance_after, formula, member_id, created_at
              FROM fixed_flow_occurrence WHERE id = #{id} AND family_id = #{familyId}
            """)
    Optional<FixedFlowOccurrence> findOccurrenceById(@Param("familyId") long familyId, @Param("id") long id);

    /** 数据导出 */
    @Select("""
            SELECT id, family_id, fixed_flow_id, period_id, due_date, status, amount, principal, interest, cash_flow_id,
                   interest_cash_flow_id, transfer_id, loan_balance_before, loan_balance_after, formula, member_id, created_at
              FROM fixed_flow_occurrence WHERE family_id = #{familyId} ORDER BY id
            """)
    List<FixedFlowOccurrence> findAllOccurrencesByFamily(@Param("familyId") long familyId);

    @Delete("DELETE FROM fixed_flow_occurrence WHERE id = #{id} AND family_id = #{familyId}")
    int deleteOccurrence(@Param("familyId") long familyId, @Param("id") long id);

    /**
     * 这一条最近几次确认的金额,新的在前(方式 ⑤ 近 3 期平均、FR-1113「比上次多 / 少」)。
     * 只看早于这一期的,这一期自己确认过的不算进它自己的平均。
     */
    @Select("""
            SELECT o.amount
              FROM fixed_flow_occurrence o
              JOIN period p ON p.id = o.period_id AND p.family_id = #{familyId}
              JOIN period cur ON cur.id = #{periodId} AND cur.family_id = #{familyId}
             WHERE o.family_id = #{familyId} AND o.fixed_flow_id = #{flowId}
               AND o.status IN ('CONFIRMED','LINKED') AND o.amount IS NOT NULL
               AND p.period_start < cur.period_start
             ORDER BY p.period_start DESC
             LIMIT #{limit}
            """)
    List<BigDecimal> recentAmountsBefore(@Param("familyId") long familyId, @Param("flowId") long flowId,
                                         @Param("periodId") long periodId, @Param("limit") int limit);

    /** 某一期有确认记录的固定收支 id(上期关账后「上期 N 笔没确认」用) */
    @Select("SELECT fixed_flow_id FROM fixed_flow_occurrence WHERE family_id = #{familyId} AND period_id = #{periodId}")
    List<Long> flowIdsWithOccurrence(@Param("familyId") long familyId, @Param("periodId") long periodId);

    // ── 只读提示用:导入去重 / 信用卡判断 ───────────────────────────────────

    /**
     * FR-1151 · 这一期导入进来的、同账户同金额的支出流水(还没被别的固定收支关联过的)。
     * 「先导入、后确认」时摘要里提示「导入的账单里有一笔 ¥X 可能就是它」。
     */
    @Select("""
            SELECT cf.id
              FROM cash_flow cf
              JOIN period p ON p.id = cf.period_id AND p.family_id = #{familyId}
             WHERE cf.period_id = #{periodId} AND cf.account_id = #{accountId}
               AND cf.kind = 'EXPENSE' AND cf.amount = #{amount}
               AND cf.import_batch_id IS NOT NULL AND cf.deleted_at IS NULL
               AND cf.id NOT IN (SELECT o.cash_flow_id FROM fixed_flow_occurrence o
                                  WHERE o.family_id = #{familyId} AND o.cash_flow_id IS NOT NULL)
             ORDER BY cf.id
             LIMIT 1
            """)
    Optional<Long> findImportedMatch(@Param("familyId") long familyId, @Param("periodId") long periodId,
                                     @Param("accountId") long accountId, @Param("amount") BigDecimal amount);

    /** FR-1150 · 本期已确认 / 已关联的固定支出:(账户, 金额, 名称),给导入预览去重 */
    @Select("""
            SELECT f.account_id AS accountId, o.amount AS amount, f.name AS name
              FROM fixed_flow_occurrence o
              JOIN fixed_flow f ON f.id = o.fixed_flow_id AND f.family_id = #{familyId}
             WHERE o.family_id = #{familyId} AND o.period_id = #{periodId}
               AND o.status IN ('CONFIRMED','LINKED') AND f.kind = 'EXPENSE' AND o.amount IS NOT NULL
            """)
    List<ConfirmedExpense> confirmedExpenses(@Param("familyId") long familyId, @Param("periodId") long periodId);

    record ConfirmedExpense(Long accountId, BigDecimal amount, String name) {
    }

    /** 撤销前确认那笔流水还在(人可能已经在流水列表里删过它 —— 再删一次会把余额冲回两遍) */
    @Select("""
            SELECT COUNT(*) FROM cash_flow cf JOIN period p ON p.id = cf.period_id AND p.family_id = #{familyId}
             WHERE cf.id = #{id} AND cf.deleted_at IS NULL
            """)
    int countLiveCashFlow(@Param("familyId") long familyId, @Param("id") long id);

    @Select("""
            SELECT COUNT(*) FROM transfer t JOIN period p ON p.id = t.period_id AND p.family_id = #{familyId}
             WHERE t.id = #{id} AND t.deleted_at IS NULL
            """)
    int countLiveTransfer(@Param("familyId") long familyId, @Param("id") long id);

    /** 「就是它」:那笔必须是这一期、这个账户、导入进来的、还在的支出 */
    @Select("""
            SELECT COUNT(*) FROM cash_flow cf JOIN period p ON p.id = cf.period_id AND p.family_id = #{familyId}
             WHERE cf.id = #{id} AND cf.period_id = #{periodId} AND cf.account_id = #{accountId}
               AND cf.kind = 'EXPENSE' AND cf.import_batch_id IS NOT NULL AND cf.deleted_at IS NULL
            """)
    int countLinkableImport(@Param("familyId") long familyId, @Param("id") long id,
                            @Param("periodId") long periodId, @Param("accountId") long accountId);

    /** 流水列表上标「固定」:这一期哪些流水是固定收支确认 / 关联来的 */
    @Select("""
            SELECT cash_flow_id FROM fixed_flow_occurrence
             WHERE family_id = #{familyId} AND period_id = #{periodId} AND cash_flow_id IS NOT NULL
            UNION
            SELECT interest_cash_flow_id FROM fixed_flow_occurrence
             WHERE family_id = #{familyId} AND period_id = #{periodId} AND interest_cash_flow_id IS NOT NULL
            """)
    List<Long> cashFlowIdsOfPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    /** FR-1104 · 这张卡上记过日常开支没有(刷卡消费记在卡上 → 还款是「信用卡账单」) */
    @Select("""
            SELECT COUNT(*)
              FROM cash_flow cf
              JOIN account a ON a.id = cf.account_id AND a.family_id = #{familyId}
             WHERE cf.account_id = #{accountId} AND cf.kind = 'EXPENSE'
               AND cf.category_code = 'consumption' AND cf.deleted_at IS NULL
            """)
    int countConsumptionOn(@Param("familyId") long familyId, @Param("accountId") long accountId);

    /** FR-1104 · 建户模板的 code(信用卡模板是 credit_card) */
    @Select("""
            SELECT t.code FROM account a JOIN account_template t ON t.id = a.template_id
             WHERE a.id = #{accountId} AND a.family_id = #{familyId}
            """)
    Optional<String> templateCodeOf(@Param("familyId") long familyId, @Param("accountId") long accountId);
}
