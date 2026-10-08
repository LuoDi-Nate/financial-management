package com.family.finance.repository;

import com.family.finance.domain.stock.StockHolding;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * stock_holding 表 Mapper · v0.3 FR-52。
 *
 * <p>v1.24 · 家庭隔离:本表没有 {@code family_id} 列,归属沿
 * {@code stock_holding → account.family_id} 取。每条语句自己 JOIN 一次 ——
 * 「调用方传进来的 accountId 一定是自家的」是推论不是约束,调用方会变,SQL 不会跟着变。</p>
 */
@Mapper
public interface StockHoldingMapper {

    /** 前后各留空格:注解 SQL 靠拼接,少一个空格就粘成 {@code SELECTh.id} */
    String COLS = " h.id, h.account_id, h.display_name, h.valuation_mode, h.ticker, h.market, h.shares,"
            + " h.cost_basis, h.currency, h.unit, h.sync_source AS syncSource, h.industry_tag AS industryTag,"
            + " h.asset_class_tag AS assetClassTag, h.risk_tag AS riskTag, h.liquidity_tag AS liquidityTag,"
            + " h.manual_value, h.manual_value_at, h.cash_linked AS cashLinked, h.fund_code AS fundCode,"
            + " h.penetrate_state AS penetrateState, h.archived_at, h.created_at, h.updated_at,"
            // v1.29 · 券商同步来的期权 / 期货 / 债券的说明列(估值不读)
            + " h.instrument_kind AS instrumentKind, h.underlying, h.put_call AS putCall, h.strike, h.expiry,"
            + " h.multiplier, h.quote_price AS quotePrice, h.notional,"
            // v1.30 · 净值行(场外基金 / 货币基金)· 见 NavMode
            + " h.nav_mode AS navMode, h.nav_date AS navDate, h.nav_checked_at AS navCheckedAt,"
            + " h.nav_error AS navError, h.shares_estimated_on AS sharesEstimatedOn ";

    @Select("SELECT" + COLS + "FROM stock_holding h"
          + " JOIN account a ON a.id = h.account_id"
          + " WHERE a.family_id = #{familyId} AND h.id = #{id}")
    Optional<StockHolding> findById(@Param("familyId") long familyId, @Param("id") long id);

    @Select("SELECT" + COLS + "FROM stock_holding h"
          + " JOIN account a ON a.id = h.account_id"
          + " WHERE a.family_id = #{familyId} AND h.account_id = #{accountId}"
          + " AND h.archived_at IS NULL ORDER BY h.id")
    List<StockHolding> findActiveByAccount(@Param("familyId") long familyId,
                                           @Param("accountId") long accountId);

    @Select("SELECT" + COLS + "FROM stock_holding h"
          + " JOIN account a ON a.id = h.account_id"
          + " WHERE a.family_id = #{familyId} AND h.account_id = #{accountId}"
          + " ORDER BY h.archived_at IS NULL DESC, h.id")
    List<StockHolding> findAllByAccount(@Param("familyId") long familyId,
                                        @Param("accountId") long accountId);

    /**
     * 跨所有家庭找全部 AUTO 持仓的不重复 (market, ticker) · 拉价 cron 用。
     *
     * <p><b>family_id 隔离的合法例外</b>(v1.24 普查登记):这条<b>故意</b>跨家庭 ——
     * 它回答的是「今天要去拉哪些股票的价」,返回值只有 {@code (ticker, market)} 这对
     * <b>公开行情键</b>,不含金额、不含持仓量、不含任何能指回某个家庭的东西。
     * 按家庭拆开跑只会把同一只票拉很多遍。
     * 例外清单由护栏 {@code v1240-FAMILY-ISOLATION} 钉死,新增例外必须同时改护栏。</p>
     */
    @Select("""
            SELECT DISTINCT ticker, market
              FROM stock_holding
             WHERE valuation_mode = 'AUTO'
               AND market = #{market}
               AND archived_at IS NULL
               AND ticker IS NOT NULL
            """)
    List<TickerMarket> findDistinctAutoTickersByMarket(@Param("market") String market);

    /** 账户必须属于这个家才真的插入;不符 = 影响行数 0,由 {@link #insertOwned} 抛出来 */
    @Insert("""
            INSERT INTO stock_holding (account_id, display_name, valuation_mode, ticker, market, shares,
                                       cost_basis, currency, unit, sync_source, industry_tag,
                                       asset_class_tag, risk_tag, liquidity_tag, manual_value, manual_value_at, cash_linked,
                                       instrument_kind, underlying, put_call, strike, expiry, multiplier, quote_price, notional,
                                       fund_code, penetrate_state, nav_mode, nav_date, nav_checked_at, nav_error, shares_estimated_on)
            SELECT #{h.accountId}, #{h.displayName}, #{h.valuationMode}, #{h.ticker}, #{h.market}, #{h.shares},
                   #{h.costBasis}, #{h.currency}, #{h.unit}, #{h.syncSource}, #{h.industryTag},
                   #{h.assetClassTag}, #{h.riskTag}, #{h.liquidityTag}, #{h.manualValue}, #{h.manualValueAt}, #{h.cashLinked},
                   #{h.instrumentKind}, #{h.underlying}, #{h.putCall}, #{h.strike}, #{h.expiry}, #{h.multiplier},
                   #{h.quotePrice}, #{h.notional},
                   #{h.fundCode}, #{h.penetrateState}, #{h.navMode}, #{h.navDate}, #{h.navCheckedAt}, #{h.navError},
                   #{h.sharesEstimatedOn}
              FROM account a
             WHERE a.id = #{h.accountId} AND a.family_id = #{familyId}
            """)
    @Options(useGeneratedKeys = true, keyProperty = "h.id")
    int insert(@Param("familyId") long familyId, @Param("h") StockHolding holding);

    /** 带归属断言的插入 —— 业务代码一律用这个 */
    default void insertOwned(long familyId, StockHolding holding) {
        if (insert(familyId, holding) != 1) {
            throw new IllegalStateException("持仓归属校验不通过:账户 " + holding.getAccountId()
                    + " 不属于家庭 " + familyId);
        }
    }

    @Update("""
            UPDATE stock_holding h
              JOIN account a ON a.id = h.account_id
               SET h.display_name = #{h.displayName},
                   h.valuation_mode = #{h.valuationMode},
                   h.ticker = #{h.ticker},
                   h.market = #{h.market},
                   h.shares = #{h.shares},
                   h.cost_basis = #{h.costBasis},
                   h.currency = #{h.currency},
                   h.unit = #{h.unit},
                   h.sync_source = #{h.syncSource},
                   h.industry_tag = #{h.industryTag},
                   h.asset_class_tag = #{h.assetClassTag},
                   h.risk_tag = #{h.riskTag},
                   h.liquidity_tag = #{h.liquidityTag},
                   h.manual_value = #{h.manualValue},
                   h.manual_value_at = #{h.manualValueAt},
                   h.cash_linked = #{h.cashLinked},
                   h.instrument_kind = #{h.instrumentKind},
                   h.underlying = #{h.underlying},
                   h.put_call = #{h.putCall},
                   h.strike = #{h.strike},
                   h.expiry = #{h.expiry},
                   h.multiplier = #{h.multiplier},
                   h.quote_price = #{h.quotePrice},
                   h.notional = #{h.notional}
             WHERE a.family_id = #{familyId}
               AND h.id = #{h.id}
               AND h.archived_at IS NULL
            """)
    int update(@Param("familyId") long familyId, @Param("h") StockHolding holding);

    @Update("UPDATE stock_holding h JOIN account a ON a.id = h.account_id"
          + " SET h.archived_at = NOW(3)"
          + " WHERE a.family_id = #{familyId} AND h.id = #{id} AND h.archived_at IS NULL")
    int archive(@Param("familyId") long familyId, @Param("id") long id);

    /** v0.15 · 解绑券商:把该账户所有 sync_source 持仓清为普通持仓(保留可手动维护) */
    @Update("UPDATE stock_holding h JOIN account a ON a.id = h.account_id"
          + " SET h.sync_source = NULL"
          + " WHERE a.family_id = #{familyId} AND h.account_id = #{accountId}"
          + " AND h.sync_source IS NOT NULL")
    int clearSyncSource(@Param("familyId") long familyId, @Param("accountId") long accountId);

    @Update("UPDATE stock_holding h JOIN account a ON a.id = h.account_id"
          + " SET h.archived_at = NULL"
          + " WHERE a.family_id = #{familyId} AND h.id = #{id} AND h.archived_at IS NOT NULL")
    int restore(@Param("familyId") long familyId, @Param("id") long id);

    /** v1.1 · 单改行业标(持仓页行内下拉 · 资产透视维度) */
    @Update("UPDATE stock_holding h JOIN account a ON a.id = h.account_id"
          + " SET h.industry_tag = #{industryTag}"
          + " WHERE a.family_id = #{familyId} AND h.id = #{id}")
    int updateIndustry(@Param("familyId") long familyId,
                       @Param("id") long id, @Param("industryTag") String industryTag);

    /**
     * v1.30 · 净值行的系统写入:份额 / 单价 / 净值日期 / 原因一次写完,并把 {@code manual_value_at} 与
     * {@code nav_checked_at} 写成<b>同一时刻</b>(调用方传 {@code at},截到秒)。之后若 {@code manual_value_at}
     * 比 {@code nav_checked_at} 晚,说明是别处(回滚期间的老代码 / 其它路径)改过这一行 —— 见 FundNavService。
     * 只动未归档的净值行或要变成净值行的 MANUAL 行。
     */
    @Update("""
            UPDATE stock_holding h
              JOIN account a ON a.id = h.account_id
               SET h.shares = #{shares},
                   h.manual_value = #{unitValue},
                   h.nav_mode = #{navMode},
                   h.nav_date = #{navDate},
                   h.nav_error = #{navError},
                   h.shares_estimated_on = #{sharesEstimatedOn},
                   h.manual_value_at = #{at},
                   h.nav_checked_at = #{at}
             WHERE a.family_id = #{familyId}
               AND h.id = #{id}
               AND h.archived_at IS NULL
               AND h.valuation_mode = 'MANUAL'
            """)
    int writeNav(@Param("familyId") long familyId, @Param("id") long id,
                 @Param("shares") java.math.BigDecimal shares, @Param("unitValue") java.math.BigDecimal unitValue,
                 @Param("navMode") String navMode, @Param("navDate") java.time.LocalDate navDate,
                 @Param("navError") String navError, @Param("sharesEstimatedOn") java.time.LocalDate sharesEstimatedOn,
                 @Param("at") java.time.LocalDateTime at);

    /** v1.30 · 只记「这次没拿到 / 没结转」的原因(单价、份额不动,余额不清零) */
    @Update("UPDATE stock_holding h JOIN account a ON a.id = h.account_id"
          + " SET h.nav_error = #{navError}, h.nav_checked_at = #{at}"
          + " WHERE a.family_id = #{familyId} AND h.id = #{id} AND h.nav_mode IS NOT NULL")
    int markNavError(@Param("familyId") long familyId, @Param("id") long id,
                     @Param("navError") String navError, @Param("at") java.time.LocalDateTime at);

    /** v1.30 · 改回手填:去掉净值标记,单价停在最后一次净值、份额不动 → 余额不变 */
    @Update("UPDATE stock_holding h JOIN account a ON a.id = h.account_id"
          + " SET h.nav_mode = NULL, h.nav_error = NULL, h.shares_estimated_on = NULL, h.manual_value_at = NOW()"
          + " WHERE a.family_id = #{familyId} AND h.id = #{id} AND h.nav_mode IS NOT NULL")
    int clearNavMode(@Param("familyId") long familyId, @Param("id") long id);

    /** v1.30 · 某家庭全部活的净值行(同步 / 结转用) */
    @Select("SELECT" + COLS + "FROM stock_holding h"
          + " JOIN account a ON a.id = h.account_id"
          + " WHERE a.family_id = #{familyId} AND h.archived_at IS NULL AND h.nav_mode IS NOT NULL"
          + " ORDER BY h.account_id, h.id")
    List<StockHolding> findActiveNavRowsByFamily(@Param("familyId") long familyId);

    /** v1.30 · 行锁:货币基金结转在这把锁里做,定时 + 按钮同时到也只结一次 */
    @Select("SELECT" + COLS + "FROM stock_holding h"
          + " JOIN account a ON a.id = h.account_id"
          + " WHERE a.family_id = #{familyId} AND h.id = #{id} FOR UPDATE")
    Optional<StockHolding> lockById(@Param("familyId") long familyId, @Param("id") long id);

    /** v1.5 · 穿透后回写代码 + 状态 */
    @Update("UPDATE stock_holding h JOIN account a ON a.id = h.account_id"
          + " SET h.fund_code = #{fundCode}, h.penetrate_state = #{state}"
          + " WHERE a.family_id = #{familyId} AND h.id = #{id}")
    int updatePenetrate(@Param("familyId") long familyId, @Param("id") long id,
                        @Param("fundCode") String fundCode, @Param("state") String state);

    /** v1.5 · 某家庭全部活持仓(穿透批量拉取用)· 关联账户过滤 family */
    @Select("""
            SELECT h.id FROM stock_holding h JOIN account a ON a.id = h.account_id
            WHERE a.family_id = #{familyId} AND h.archived_at IS NULL
            """)
    List<Long> findActiveHoldingIdsByFamily(@Param("familyId") long familyId);

    /**
     * v1.5 · 家庭活的「基金」持仓(MANUAL 估值 = 截图导入的基金/理财,穿透候选;个股/现金不进)· 流式穿透用。
     * v1.29 · 券商同步来的期权 / 期货 / 债券也是 MANUAL 行,但不是基金 —— 不进候选(否则拿
     * 「AAPL · 看涨 · …」去天天基金搜名字,白发请求还把它标成「穿透不了」)。
     */
    @Select("""
            SELECT h.id FROM stock_holding h JOIN account a ON a.id = h.account_id
            WHERE a.family_id = #{familyId} AND h.archived_at IS NULL
              AND h.valuation_mode = 'MANUAL'
              AND h.instrument_kind IS NULL
            ORDER BY h.account_id, h.id
            """)
    List<Long> findActiveFundHoldingIdsByFamily(@Param("familyId") long familyId);

    /**
     * 轻量值对象 · 仅给 fetcher cron 用。
     */
    record TickerMarket(String ticker, String market) {}
}
