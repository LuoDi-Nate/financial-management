package com.family.finance.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * v1.30 · fund_nav_snapshot:天天基金净值 / 万份收益缓存。
 *
 * <p><b>公共行情,不分家庭</b>(同 stock_price_snapshot 的性质):只有「基金代码 → 某天的净值」,
 * 不含任何金额、份额或能指回某个家庭的东西,所以不在 family_id 隔离的范围里。</p>
 */
@Mapper
public interface FundNavSnapshotMapper {

    record Row(String fundCode, LocalDate navDate, BigDecimal unitNav, BigDecimal incomePer10k,
               BigDecimal yield7d, String source, LocalDateTime fetchedAt) {}

    @Insert("""
            INSERT INTO fund_nav_snapshot (fund_code, nav_date, unit_nav, income_per_10k, yield_7d, source, fetched_at)
            VALUES (#{r.fundCode}, #{r.navDate}, #{r.unitNav}, #{r.incomePer10k}, #{r.yield7d}, #{r.source}, NOW())
            ON DUPLICATE KEY UPDATE unit_nav = VALUES(unit_nav), income_per_10k = VALUES(income_per_10k),
                                    yield_7d = VALUES(yield_7d), source = VALUES(source), fetched_at = NOW()
            """)
    int upsert(@Param("r") Row r);

    /** 普通基金:最新一条单位净值 */
    @Select("""
            SELECT fund_code, nav_date, unit_nav, income_per_10k, yield_7d, source, fetched_at
              FROM fund_nav_snapshot
             WHERE fund_code = #{code} AND unit_nav IS NOT NULL
             ORDER BY nav_date DESC LIMIT 1
            """)
    Row findLatestNav(@Param("code") String code);

    /** 货币基金:(from, to] 区间的逐日万份收益,按日期升序 */
    @Select("""
            SELECT fund_code, nav_date, unit_nav, income_per_10k, yield_7d, source, fetched_at
              FROM fund_nav_snapshot
             WHERE fund_code = #{code} AND income_per_10k IS NOT NULL
               AND nav_date > #{from} AND nav_date <= #{to}
             ORDER BY nav_date
            """)
    List<Row> findIncome(@Param("code") String code, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 货币基金:最新一条(七日年化 / 万份收益展示用) */
    @Select("""
            SELECT fund_code, nav_date, unit_nav, income_per_10k, yield_7d, source, fetched_at
              FROM fund_nav_snapshot
             WHERE fund_code = #{code} AND income_per_10k IS NOT NULL
             ORDER BY nav_date DESC LIMIT 1
            """)
    Row findLatestIncome(@Param("code") String code);

    /** 节流用:这只基金最近一次真去拉是什么时候 */
    @Select("SELECT MAX(fetched_at) FROM fund_nav_snapshot WHERE fund_code = #{code}")
    LocalDateTime lastFetchedAt(@Param("code") String code);
}
