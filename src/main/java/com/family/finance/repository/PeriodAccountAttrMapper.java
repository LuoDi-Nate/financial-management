package com.family.finance.repository;

import com.family.finance.domain.period.PeriodAccountAttr;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * v1.12 FR-350 · 已关账期的账户分类属性定格。
 *
 * <p>写入点只有两个,都在 {@code PeriodService} 的私有漏斗里:关账 {@link #freezeByPeriod}、
 * 重开 {@link #deleteByPeriod}。这是刻意的 —— tech-design v1.12 §2 否决了「跟着
 * period_snapshot upsert 走」(6 处调用点,漏一处 = 该账户该期静默漏保护)和
 * 「读时惰性写」(定格值取决于谁先打开页面,不可复现)。</p>
 */
@Mapper
public interface PeriodAccountAttrMapper {

    /**
     * 把该期所有在册账户的当前分类属性定格下来。
     *
     * <p>账户范围与 {@code FactMapper.queryBase} 的归档过滤<b>逐字一致</b>
     * (v1.10 的 {@code v110-ARCHIVED-TIME}:归档之前的期照常计入,归档之后的期不计入)。
     * 两边不一致 → 定格的行集 ≠ 读取的行集 → 一部分账户静默回落当前属性。
     * 护栏 {@code v112-ATTR-BACKFILL-SCOPE} 钉住这一条。</p>
     *
     * <p>{@code ON DUPLICATE KEY UPDATE} 而不是 {@code INSERT IGNORE}:重开会删掉定格行
     * (所以正常路径下这里不会撞主键),但万一撞上,语义应当是「这次关账<b>重新</b>定格」——
     * 与 PRD 第三条规则(重开后再关账 = 重新定格)一致,顺带把这条做成幂等自愈的。</p>
     */
    @Insert("""
            INSERT INTO period_account_attr
              (period_id, account_id, account_type, product_category_code, product_category_name,
               liquidity_class, benchmark_pct, expected_return_pct, source)
            SELECT p.id, a.id, a.type, a.product_category_code, pc.display_name,
                   pc.liquidity_class, pc.benchmark_pct, a.expected_return_pct, 'CLOSE'
              FROM account a
              JOIN period p
                ON p.id = #{periodId}
               AND p.family_id = a.family_id
              LEFT JOIN product_category pc
                ON pc.code = a.product_category_code
             WHERE a.archived_at IS NULL OR a.archived_at > p.period_end
            ON DUPLICATE KEY UPDATE
                   account_type          = VALUES(account_type),
                   product_category_code = VALUES(product_category_code),
                   product_category_name = VALUES(product_category_name),
                   liquidity_class       = VALUES(liquidity_class),
                   benchmark_pct         = VALUES(benchmark_pct),
                   expected_return_pct   = VALUES(expected_return_pct),
                   source                = VALUES(source),
                   sealed_at             = CURRENT_TIMESTAMP(3)
            """)
    int freezeByPeriod(@Param("periodId") long periodId);

    /**
     * 重开该期 → 删掉定格行。
     *
     * <p>这样「重开后再关账 = 重新定格」是<b>结构上必然</b>的,不需要额外的标志位、
     * 版本号或「是否已定格」判断。少一个状态就少一类不一致。</p>
     */
    @Delete("DELETE pa FROM period_account_attr pa JOIN period p ON p.id = pa.period_id"
          + " WHERE p.family_id = #{familyId} AND pa.period_id = #{periodId}")
    int deleteByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    /**
     * 取某一期的全部定格属性 —— 供三区(随 range 的趋势区)按「锚期」读取基准 % 与预期 %。
     *
     * <p>封板侧(集中度 / 分层 / 大类分布)不走这个方法,走 {@code FactMapper.queryBase} 里的
     * {@code LEFT JOIN period_account_attr} + {@code COALESCE} —— 那样所有吃 {@code FactBaseRow}
     * 的消费者(SealedPeriodService / AllocationService / 各 lens)自动受益,不用逐个改调用点。</p>
     *
     * <p>v1.29.1 · 只认已关账的期(issue #34 · 理由见 {@code FactMapper.queryBase} 那条 join 的注释)。</p>
     *
     * <p>列顺序与 {@link PeriodAccountAttr} 的记录组件<b>逐一对齐</b>:这是 record 唯一规范构造器的
     * 位置映射,{@code benchmark_pct} 与 {@code expected_return_pct} 都是 {@code DECIMAL} ——
     * 两列相邻且同型,顺序错了不会报错,只会静默错位。改这里必须同步改 record。</p>
     */
    @Select("""
            SELECT pa.period_id, pa.account_id, pa.account_type, pa.product_category_code,
                   pa.product_category_name, pa.liquidity_class, pa.benchmark_pct,
                   pa.expected_return_pct, pa.source
              FROM period_account_attr pa
              JOIN period p ON p.id = pa.period_id
             WHERE p.family_id = #{familyId}
               AND pa.period_id = #{periodId}
               AND p.status = 'CLOSED'
            """)
    List<PeriodAccountAttr> findByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    /** 计数 · 给护栏与运维自检用(某期定格了多少个账户)。 */
    @Select("SELECT COUNT(*) FROM period_account_attr pa JOIN period p ON p.id = pa.period_id"
          + " WHERE p.family_id = #{familyId} AND pa.period_id = #{periodId}")
    int countByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);
}
