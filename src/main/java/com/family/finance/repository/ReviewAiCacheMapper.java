package com.family.finance.repository;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** v1.2 · AI 月度复盘缓存(V48 · UNIQUE(family,period,dim) 覆盖写) */
@Mapper
public interface ReviewAiCacheMapper {

    /** v1.28 · 多带「生成这份复盘的那次调用」与生成时刻(老缓存 promptRecordId 为空) */
    record Row(Long id, Long familyId, Long periodId, String dim, String text, String vendor,
               Long promptRecordId, java.time.LocalDateTime createdAt, String sourceJson) {
        public Row(Long id, Long familyId, Long periodId, String dim, String text, String vendor, Long recordId, java.time.LocalDateTime at) {
            this(id, familyId, periodId, dim, text, vendor, recordId, at, null);
        }
        public Row(Long id, Long familyId, Long periodId, String dim, String text, String vendor) {
            this(id, familyId, periodId, dim, text, vendor, null, null);
        }
    }

    @Select("SELECT id, family_id AS familyId, period_id AS periodId, dim, text, vendor, prompt_record_id AS promptRecordId, created_at AS createdAt, source_json AS sourceJson FROM review_ai_cache WHERE family_id=#{familyId} AND period_id=#{periodId} AND dim=#{dim}")
    Row find(@Param("familyId") long familyId, @Param("periodId") long periodId, @Param("dim") String dim);

    /**
     * v1.19.16 · 这一期的复盘缓存全清(所有维度)。
     *
     * <p><b>这个方法以前不存在</b> —— 全仓没有任何地方失效过这张表。
     * 后果是线上用户报的那条:重开账期、改完数据、重新关账之后,「AI 月度复盘」
     * 返回的还是重开<b>之前</b>那份结论,而页面上写着「关账后结果缓存可回看」,
     * 读起来就是本期的定论。数字全变了,解读没变,而且不报错。</p>
     */
    @Delete("DELETE FROM review_ai_cache WHERE family_id=#{familyId} AND period_id=#{periodId}")
    int deleteByPeriod(@Param("familyId") long familyId, @Param("periodId") long periodId);

    @Insert("""
            INSERT INTO review_ai_cache (family_id, period_id, dim, text, vendor, prompt_record_id, source_json)
            VALUES (#{familyId}, #{periodId}, #{dim}, #{text}, #{vendor}, #{promptRecordId}, #{sourceJson})
            ON DUPLICATE KEY UPDATE text=VALUES(text), vendor=VALUES(vendor), prompt_record_id=VALUES(prompt_record_id),
                                    source_json=VALUES(source_json), created_at=CURRENT_TIMESTAMP
            """)
    int upsert(@Param("familyId") long familyId, @Param("periodId") long periodId,
               @Param("dim") String dim, @Param("text") String text, @Param("vendor") String vendor,
               @Param("promptRecordId") Long promptRecordId, @Param("sourceJson") String sourceJson);
    default int upsert(long familyId, long periodId, String dim, String text, String vendor, Long recordId) {
        return upsert(familyId, periodId, dim, text, vendor, recordId, null);
    }
}
