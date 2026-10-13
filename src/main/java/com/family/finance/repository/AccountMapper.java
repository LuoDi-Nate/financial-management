package com.family.finance.repository;

import com.family.finance.domain.account.Account;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AccountMapper {

    @Select("""
            SELECT id, family_id, template_id, display_name, type, currency,
                   primary_owner_member_id, default_payment_source_account_id,
                   display_order, archived_at, created_at, updated_at,
                   product_category_code, risk_level_override, loan_kind, annual_rate_pct, expected_return_pct,
                   asset_class, platform_tag, industry_tag, purpose_tag, analysis_excluded
              FROM account
             WHERE family_id = #{familyId}
               AND id = #{id}
            """)
    Optional<Account> findById(@Param("familyId") long familyId, @Param("id") long id);

    @Select("""
            SELECT id, family_id, template_id, display_name, type, currency,
                   primary_owner_member_id, default_payment_source_account_id,
                   display_order, archived_at, created_at, updated_at,
                   product_category_code, risk_level_override, loan_kind, annual_rate_pct, expected_return_pct,
                   asset_class, platform_tag, industry_tag, purpose_tag, analysis_excluded
              FROM account
             WHERE family_id = #{familyId}
             ORDER BY archived_at IS NOT NULL, display_order, id
            """)
    List<Account> findAllByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT id, family_id, template_id, display_name, type, currency,
                   primary_owner_member_id, default_payment_source_account_id,
                   display_order, archived_at, created_at, updated_at,
                   product_category_code, risk_level_override, loan_kind, annual_rate_pct, expected_return_pct,
                   asset_class, platform_tag, industry_tag, purpose_tag, analysis_excluded
              FROM account
             WHERE family_id = #{familyId}
               AND archived_at IS NULL
             ORDER BY display_order, id
            """)
    List<Account> findActiveByFamily(@Param("familyId") long familyId);

    @Select("""
            SELECT COUNT(*)
              FROM account
             WHERE family_id = #{familyId}
               AND archived_at IS NULL
            """)
    int countActiveByFamily(@Param("familyId") long familyId);

    @Insert("""
            INSERT INTO account (
                family_id, template_id, display_name, type, currency,
                primary_owner_member_id, default_payment_source_account_id, display_order,
                product_category_code, risk_level_override, loan_kind, annual_rate_pct, expected_return_pct,
                   asset_class, platform_tag, industry_tag, purpose_tag
            ) VALUES (
                #{familyId}, #{templateId}, #{displayName}, #{type}, #{currency},
                #{primaryOwnerMemberId}, #{defaultPaymentSourceAccountId}, #{displayOrder},
                #{productCategoryCode}, #{riskLevelOverride}, #{loanKind}, #{annualRatePct}, #{expectedReturnPct},
                #{assetClass}, #{platformTag}, #{industryTag}, #{purposeTag}
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Account account);

    @Update("""
            UPDATE account
               SET template_id = #{templateId},
                   display_name = #{displayName},
                   type = #{type},
                   currency = #{currency},
                   primary_owner_member_id = #{primaryOwnerMemberId},
                   default_payment_source_account_id = #{defaultPaymentSourceAccountId},
                   display_order = #{displayOrder},
                   product_category_code = #{productCategoryCode},
                   risk_level_override = #{riskLevelOverride},
                   loan_kind = #{loanKind},
                   annual_rate_pct = #{annualRatePct},
                   expected_return_pct = #{expectedReturnPct},
                   asset_class = #{assetClass},
                   platform_tag = #{platformTag},
                   industry_tag = #{industryTag},
                   purpose_tag = #{purposeTag}
             WHERE id = #{id}
               AND family_id = #{familyId}
            """)
    int update(Account account);

    /** v1.1 · 只更三个透视标签(打标页批量保存 · 不触碰其它列) */
    @Update("""
            UPDATE account
               SET asset_class = #{assetClass},
                   platform_tag = #{platformTag},
                   industry_tag = #{industryTag},
                   purpose_tag = #{purposeTag}
             WHERE id = #{id}
               AND family_id = #{familyId}
            """)
    int updateLensTags(@Param("familyId") long familyId, @Param("id") long id,
                       @Param("assetClass") String assetClass,
                       @Param("platformTag") String platformTag,
                       @Param("industryTag") String industryTag,
                       @Param("purposeTag") String purposeTag);

    /**
     * v1.27 · 只改「不参与配置分析」一列(PRD FR-800)。
     *
     * <p>单独一条而不是并进 {@link #update}:那条是编辑页整表单覆盖写,别的入口(建户向导、打标页)
     * 调它时手里没有这个值 —— 并进去会把用户勾过的标记静默清掉。贷款账户在 SQL 里就挡掉。</p>
     */
    @Update("""
            UPDATE account
               SET analysis_excluded = #{excluded}
             WHERE id = #{id}
               AND family_id = #{familyId}
               AND (type <> 'LOAN' OR #{excluded} = FALSE)
            """)
    int updateAnalysisExcluded(@Param("familyId") long familyId, @Param("id") long id,
                               @Param("excluded") boolean excluded);

    @Update("""
            UPDATE account
               SET archived_at = NOW(3)
             WHERE id = #{id}
               AND family_id = #{familyId}
               AND archived_at IS NULL
            """)
    int archive(@Param("familyId") long familyId, @Param("id") long id);

    @Update("""
            UPDATE account
               SET archived_at = NULL
             WHERE id = #{id}
               AND family_id = #{familyId}
            """)
    int restore(@Param("familyId") long familyId, @Param("id") long id);

    /** v1.34 · 账户簿「调整顺序」:只写显示顺序这一列(按家庭过滤) */
    @org.apache.ibatis.annotations.Update("UPDATE account SET display_order = #{order} WHERE family_id = #{familyId} AND id = #{id} AND archived_at IS NULL")
    int updateDisplayOrder(@org.apache.ibatis.annotations.Param("familyId") long familyId,
                           @org.apache.ibatis.annotations.Param("id") long id,
                           @org.apache.ibatis.annotations.Param("order") int order);
}
