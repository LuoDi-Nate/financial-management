package com.family.finance.repository;

import com.family.finance.domain.fx.FxRate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Mapper
public interface FxMapper {

    @Select("""
            SELECT fx.id,fx.family_id,fx.base_currency,fx.quote_currency,fx.period_id,fx.rate,fx.source,fx.fetched_at,p.period_start
              FROM fx_rate fx JOIN period p ON p.id=fx.period_id AND p.family_id=fx.family_id
             WHERE fx.family_id=#{familyId} AND p.period_start <= #{asOf}
             ORDER BY p.period_start DESC,fx.fetched_at DESC
            """)
    List<FxRate> findRatesAsOf(@Param("familyId") long familyId, @Param("asOf") java.time.LocalDate asOf);

    @Select("""
            SELECT id, family_id, base_currency, quote_currency, period_id, rate, source, fetched_at
              FROM fx_rate
             WHERE family_id = #{familyId}
             ORDER BY period_id DESC, quote_currency
             LIMIT #{limit}
            """)
    List<FxRate> findLatestByFamily(@Param("familyId") long familyId, @Param("limit") int limit);

    @Select("""
            SELECT id, family_id, base_currency, quote_currency, period_id, rate, source, fetched_at
              FROM fx_rate
             WHERE family_id = #{familyId}
             ORDER BY period_id, quote_currency
            """)
    List<FxRate> findAllByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT id, family_id, base_currency, quote_currency, period_id, rate, source, fetched_at
              FROM fx_rate
             WHERE family_id = #{familyId}
               AND base_currency = #{baseCurrency}
               AND quote_currency = #{quoteCurrency}
               AND period_id = #{periodId}
            """)
    Optional<FxRate> findOne(@Param("familyId") long familyId,
                             @Param("baseCurrency") String baseCurrency,
                             @Param("quoteCurrency") String quoteCurrency,
                             @Param("periodId") long periodId);

    /**
     * v1.12 FR-352 · 一条查出「这批账期里,哪些 (期, quote) 已经有 base→quote 的行了」。
     *
     * <p>用途只有一个:{@code FxService.ensure*} 是「补齐缺失汇率」的幂等操作,
     * 但它逐 (期 × 币种) 调 {@link #findOne} 探一次 —— 体检页实测 <b>190 次</b>。
     * 绝大多数情况汇率早就齐了,190 次全是「查到了、什么都不用做」。
     * 先用这一条把已有的捞出来,ensure 只对差集干活。</p>
     *
     * <p>过滤条件与 {@link #findOne} 逐字相同(family + base + period IN),
     * 只是不带 quote 条件、不取 rate —— 只回答「有没有」。</p>
     */
    @Select("""
            <script>
            SELECT DISTINCT period_id AS periodId, quote_currency AS quoteCurrency
              FROM fx_rate
             WHERE family_id = #{familyId}
               AND base_currency = #{baseCurrency}
               AND period_id IN
                   <foreach collection="periodIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            </script>
            """)
    List<PeriodQuote> findExistingPeriodQuotes(@Param("familyId") long familyId,
                                               @Param("baseCurrency") String baseCurrency,
                                               @Param("periodIds") java.util.Collection<Long> periodIds);

    /** {@link #findExistingPeriodQuotes} 的行:一期一币种,表示 fx_rate 里已有这一行。 */
    record PeriodQuote(Long periodId, String quoteCurrency) {}

    /** 找该家庭 base→quote 的最新一期汇率(用于 anchor 期没拿到 rate 时兜底) */
    @Select("""
            SELECT id, family_id, base_currency, quote_currency, period_id, rate, source, fetched_at
              FROM fx_rate
             WHERE family_id = #{familyId}
               AND base_currency = #{baseCurrency}
               AND quote_currency = #{quoteCurrency}
             ORDER BY period_id DESC
             LIMIT 1
            """)
    Optional<FxRate> findLatest(@Param("familyId") long familyId,
                                @Param("baseCurrency") String baseCurrency,
                                @Param("quoteCurrency") String quoteCurrency);

    /** UPSERT — 同 (familyId, base, quote, period) 触发 ON DUPLICATE KEY,覆盖 rate/source/fetched_at */
    @Insert("""
            INSERT INTO fx_rate (family_id, base_currency, quote_currency, period_id, rate, source)
            VALUES (#{familyId}, #{baseCurrency}, #{quoteCurrency}, #{periodId}, #{rate}, #{source})
            ON DUPLICATE KEY UPDATE
                rate = VALUES(rate),
                source = VALUES(source),
                fetched_at = CURRENT_TIMESTAMP(3)
            """)
    int upsert(@Param("familyId") long familyId,
               @Param("baseCurrency") String baseCurrency,
               @Param("quoteCurrency") String quoteCurrency,
               @Param("periodId") long periodId,
               @Param("rate") BigDecimal rate,
               @Param("source") String source);
}
