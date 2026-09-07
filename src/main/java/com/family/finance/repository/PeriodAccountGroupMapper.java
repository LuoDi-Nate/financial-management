package com.family.finance.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * v1.20 · 该期分组定格。
 *
 * <p>与 {@link PeriodAccountAttrMapper} <b>同一条规则</b>:关账写入、重开删除,
 * 于是「重开后再关账 = 重新定格」是<b>结构上必然</b>的,不需要额外的标志位或版本号。</p>
 *
 * <p><b>为什么必须定格</b>:归因要画近 12 期趋势。如果按<b>当前</b>成员关系折叠历史,
 * 那么今天把一个账户挪出组,12 期趋势图会<b>全部改变</b> —— 每个数字自身都「算对了」,
 * 但用户会当成算错了。这类错误不报错、不降级。</p>
 *
 * <p>{@code group_name} 冗余存一份是刻意的:组改名或删除之后,历史仍显示当时的名字。</p>
 */
@Mapper
public interface PeriodAccountGroupMapper {

    /**
     * 关账时把当前成员关系定格进这一期。
     * {@code ON DUPLICATE KEY UPDATE} 让它幂等 —— 重复关账不报错,直接覆盖成最新。
     */
    @Insert("""
            INSERT INTO period_account_group (period_id, account_id, group_id, group_name)
            SELECT #{periodId}, m.account_id, m.group_id, g.name
              FROM account_group_member m
              JOIN account_group g ON g.id = m.group_id
             WHERE g.family_id = #{familyId}
            ON DUPLICATE KEY UPDATE
                group_id = VALUES(group_id), group_name = VALUES(group_name),
                sealed_at = CURRENT_TIMESTAMP(3)
            """)
    int freezeByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    /** 重开该期 → 删掉定格行(与 period_account_attr 同一处调用) */
    @Delete("DELETE FROM period_account_group WHERE period_id = #{periodId}")
    int deleteByPeriod(@Param("periodId") long periodId);

    /**
     * v1.20 · 删组时连它的历史定格一起删。
     *
     * <p>与「改成员不动历史」是两件事,分得很清楚:</p>
     * <ul>
     *   <li><b>改成员</b> = 调整 → 历史不动(FR-468)</li>
     *   <li><b>删组</b>   = 撤销这件事本身 → 历史里这个组也该消失</li>
     * </ul>
     * <p>PRD FR-463 承诺的是「删组之后……页面回到未分组的样子」。
     * 不删定格行的话,历史里会永远留着一个已经不存在的组名,而用户<b>没有任何办法退回去</b>
     * ——那不是「可逆」,是陷阱。删组是这一版唯一的退路,它必须真的退干净。</p>
     */
    @Delete("DELETE FROM period_account_group WHERE group_id = #{groupId}")
    int deleteByGroup(@Param("groupId") long groupId);

    record Row(Long accountId, Long groupId, String groupName) {}

    @Select("""
            SELECT account_id AS accountId, group_id AS groupId, group_name AS groupName
              FROM period_account_group WHERE period_id = #{periodId}
            """)
    List<Row> findByPeriod(@Param("periodId") long periodId);

    @Select("SELECT COUNT(*) FROM period_account_group WHERE period_id = #{periodId}")
    int countByPeriod(@Param("periodId") long periodId);

    /**
     * v1.20 · 已关账、但<b>还没有任何定格行</b>的期。
     *
     * <p>为什么需要它:这一版上线时,历史上每一期都是在「账户组」这个概念存在<b>之前</b>关的账,
     * 所以一条定格行都没有。放着不管的话,解析器会回落到<b>当前</b>成员关系 ——
     * 那意味着<b>每改一次组成员,12 期趋势图就跟着变一次</b>,正是 FR-468 要避免的事。</p>
     *
     * <p>所以第一次建组时对这些期补一次定格。补完它们就有行了,后续改组成员<b>不会</b>再动到 ——
     * FR-468「已关账月份的历史不因今天改组而变」从此成立。</p>
     */
    @Select("SELECT p.id FROM period p WHERE p.family_id = #{familyId} AND p.status = 'CLOSED' "
          + "AND NOT EXISTS (SELECT 1 FROM period_account_group g WHERE g.period_id = p.id)")
    List<Long> closedPeriodsWithoutFreeze(@Param("familyId") long familyId);
}
