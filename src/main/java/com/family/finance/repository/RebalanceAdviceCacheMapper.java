package com.family.finance.repository;

import com.family.finance.domain.allocation.RebalanceAdviceCache;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * v0.4 FR-62b · AI 调仓建议缓存 mapper · 30 天节流。
 */
@Mapper
public interface RebalanceAdviceCacheMapper {

    @Select("""
            SELECT id, family_id, anchor_code, content_json, prompt_hash, generated_at, prompt_record_id, source_json
              FROM rebalance_advice_cache
             WHERE family_id = #{familyId} AND anchor_code = #{anchorCode}
            """)
    Optional<RebalanceAdviceCache> findByFamilyAndAnchor(
        @Param("familyId") long familyId,
        @Param("anchorCode") String anchorCode);

    @Insert("""
            INSERT INTO rebalance_advice_cache (family_id, anchor_code, content_json, prompt_hash, prompt_record_id, source_json)
            VALUES (#{familyId}, #{anchorCode}, #{contentJson}, #{promptHash}, #{promptRecordId}, #{sourceJson})
            ON DUPLICATE KEY UPDATE
                content_json = VALUES(content_json),
                prompt_hash = VALUES(prompt_hash),
                prompt_record_id = VALUES(prompt_record_id),
                source_json = VALUES(source_json),
                generated_at = CURRENT_TIMESTAMP
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int upsert(RebalanceAdviceCache cache);

    @Update("DELETE FROM rebalance_advice_cache WHERE family_id = #{familyId}")
    int deleteByFamily(@Param("familyId") long familyId);
}
