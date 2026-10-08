package com.family.finance.repository;

import com.family.finance.domain.stock.HoldingShareEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * v1.30 · holding_share_event(持仓数量变动)。
 *
 * <p><b>只给人看</b>:这张表只被时间线读;任何金额汇总 / 报表 / fact 层都不许读它
 * (护栏 {@code v130-SHARE-EVENT-NO-MONEY})—— 钱的变化只在 stock_valuation_event。</p>
 */
@Mapper
public interface HoldingShareEventMapper {

    /** 账户必须属于这个家才真的插入;不符 = 影响行数 0,由 {@link #insertOwned} 抛出来 */
    @Insert("""
            INSERT INTO holding_share_event
                (family_id, account_id, holding_id, period_id, reason, shares_before, shares_after, shares_delta,
                 unit_value, value_delta, date_from, date_to, member_id)
            SELECT #{familyId}, #{e.accountId}, #{e.holdingId}, #{e.periodId}, #{e.reason}, #{e.sharesBefore},
                   #{e.sharesAfter}, #{e.sharesDelta}, #{e.unitValue}, #{e.valueDelta}, #{e.dateFrom}, #{e.dateTo},
                   #{e.memberId}
              FROM account a
             WHERE a.id = #{e.accountId} AND a.family_id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "e.id")
    int insert(@Param("familyId") long familyId, @Param("e") HoldingShareEvent e);

    default void insertOwned(long familyId, HoldingShareEvent e) {
        if (insert(familyId, e) != 1) {
            throw new IllegalStateException("持仓数量变动归属校验不通过:账户 " + e.getAccountId() + " 不属于家庭 " + familyId);
        }
    }

    /** 账户详情时间线用 · 倒序 · 带持仓名 */
    @Select("""
            SELECT e.id, e.family_id, e.account_id, e.holding_id, e.period_id, e.reason, e.shares_before,
                   e.shares_after, e.shares_delta, e.unit_value, e.value_delta, e.date_from, e.date_to,
                   e.member_id, e.created_at, h.display_name AS holdingName
              FROM holding_share_event e
              LEFT JOIN stock_holding h ON h.id = e.holding_id AND h.account_id = e.account_id
             WHERE e.family_id = #{familyId} AND e.account_id = #{accountId}
             ORDER BY e.created_at DESC, e.id DESC
             LIMIT #{limit}
            """)
    List<HoldingShareEvent> findRecentByAccount(@Param("familyId") long familyId,
                                                @Param("accountId") long accountId,
                                                @Param("limit") int limit);

    /** 持仓页「最近一次结转」用 */
    @Select("""
            SELECT e.id, e.family_id, e.account_id, e.holding_id, e.period_id, e.reason, e.shares_before,
                   e.shares_after, e.shares_delta, e.unit_value, e.value_delta, e.date_from, e.date_to,
                   e.member_id, e.created_at
              FROM holding_share_event e
             WHERE e.family_id = #{familyId} AND e.holding_id = #{holdingId} AND e.reason = #{reason}
             ORDER BY e.created_at DESC, e.id DESC
             LIMIT 1
            """)
    HoldingShareEvent findLatestByHolding(@Param("familyId") long familyId, @Param("holdingId") long holdingId,
                                          @Param("reason") String reason);
}
