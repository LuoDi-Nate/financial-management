package com.family.finance.repository;

import com.family.finance.domain.config.FamilyRuntimeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * v0.4.18 · family_runtime_config CRUD(详 prd/v0.4.md §22)。
 */
@Mapper
public interface FamilyRuntimeConfigMapper {

    @Select("SELECT id FROM family WHERE id = #{familyId} FOR UPDATE")
    Long lockFamily(@Param("familyId") long familyId);

    /** 取单个 key 的值(原始字符串)· 配合 FamilyConfigService 做类型转换 */
    @Select("""
            SELECT value_text
              FROM family_runtime_config
             WHERE family_id = #{familyId} AND key_name = #{keyName}
            """)
    Optional<String> findValue(@Param("familyId") long familyId,
                               @Param("keyName") String keyName);

    /** 取整个 family 的全部 config(管理页一次性渲染用) */
    @Select("""
            SELECT family_id, key_name, value_text, updated_at
              FROM family_runtime_config
             WHERE family_id = #{familyId}
             ORDER BY key_name
            """)
    List<FamilyRuntimeConfig> findByFamily(@Param("familyId") long familyId);

    /** UPSERT · 改完即生效 · 见 FamilyConfigService cache invalidate */
    @Update("""
            INSERT INTO family_runtime_config (family_id, key_name, value_text)
            VALUES (#{familyId}, #{keyName}, #{valueText})
            ON DUPLICATE KEY UPDATE
              value_text = VALUES(value_text),
              updated_at = CURRENT_TIMESTAMP(3)
            """)
    int upsert(@Param("familyId") long familyId,
               @Param("keyName") String keyName,
               @Param("valueText") String valueText);
}
