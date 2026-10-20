package com.family.finance.repository;

import com.family.finance.domain.family.Family;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Mapper
public interface FamilyMapper {

    @Select("""
            SELECT id, name, brand_text, logo_path, logo_preset, brand_mark, base_currency, period_type,
                   cpi_assumption, allocation_anchor, allocation_anchor_custom, risk_appetite,
                   reporting_template, expense_entry_mode, report_remind_lead_days, metric_prefs,
                   close_delay_days, auto_close_enabled,
                   created_at, updated_at
              FROM family
             ORDER BY id
            """)
    List<Family> findAll();

    @Select("""
            SELECT id, name, brand_text, logo_path, logo_preset, brand_mark, base_currency, period_type,
                   cpi_assumption, allocation_anchor, allocation_anchor_custom, risk_appetite,
                   reporting_template, expense_entry_mode, report_remind_lead_days, metric_prefs,
                   close_delay_days, auto_close_enabled,
                   created_at, updated_at
              FROM family
             WHERE id = #{id}
            """)
    Optional<Family> findById(@Param("id") long id);

    @Update("""
            UPDATE family
               SET name = #{name},
                   brand_text = #{brandText},
                   base_currency = #{baseCurrency},
                   period_type = #{periodType}
             WHERE id = #{id}
            """)
    int update(Family family);

    @Update("UPDATE family SET logo_path = #{logoPath} WHERE id = #{familyId}")
    int updateLogoPath(@Param("familyId") long familyId, @Param("logoPath") String logoPath);

    /** v0.8 FR-149 · 保存指标勾选配置 JSON */
    @Update("UPDATE family SET metric_prefs = #{json} WHERE id = #{familyId}")
    int updateMetricPrefs(@Param("familyId") long familyId, @Param("json") String json);

    /**
     * v0.2 FR-1/FR-34:点击预设按钮 = 切预设 + 一并清空自定义 logo_path,
     * 这样 web favicon / iOS apple-touch / PWA manifest 三处全用同一张预设(预设赢一切统一)。
     */
    @Update("UPDATE family SET logo_preset = #{preset}, logo_path = NULL WHERE id = #{familyId}")
    int updateLogoPreset(@Param("familyId") long familyId, @Param("preset") String preset);

    /** v1.35.1 · 品牌名旁的小图标(BrandMark code;NULL = 默认存钱罐)。只动这一列,不碰 logo。 */
    @Update("UPDATE family SET brand_mark = #{mark} WHERE id = #{familyId}")
    int updateBrandMark(@Param("familyId") long familyId, @Param("mark") String mark);

    // ---------- v0.4 决策辅助字段 ----------

    /** FR-61a · 切换通胀对照线假设值 */
    @Update("UPDATE family SET cpi_assumption = #{cpi} WHERE id = #{familyId}")
    int updateCpiAssumption(@Param("familyId") long familyId, @Param("cpi") BigDecimal cpi);

    /** FR-62a · 切换配置锚(预置 4 选 1 或 CUSTOM) */
    @Update("UPDATE family SET allocation_anchor = #{anchor} WHERE id = #{familyId}")
    int updateAllocationAnchor(@Param("familyId") long familyId, @Param("anchor") String anchor);

    /** FR-62a · 自定义锚 JSON · 配合 allocation_anchor='CUSTOM' 使用 */
    @Update("UPDATE family SET allocation_anchor = 'CUSTOM', allocation_anchor_custom = #{customJson} WHERE id = #{familyId}")
    int updateAllocationAnchorCustom(@Param("familyId") long familyId, @Param("customJson") String customJson);

    /** FR-62b · 风险偏好(LLM 调仓 prompt 输入) */
    @Update("UPDATE family SET risk_appetite = #{appetite} WHERE id = #{familyId}")
    int updateRiskAppetite(@Param("familyId") long familyId, @Param("appetite") String appetite);

    // ---------- v0.4.14 FR-63 填报规范化 ----------

    /** FR-63a · 切换家庭级填报模板(全家统一 · 见 ReportingTemplate) */
    /** v1.8 · 支出录入方式开关(家庭级)· 值域见 ExpenseEntryMode */
    @Update("UPDATE family SET expense_entry_mode = #{mode} WHERE id = #{familyId}")
    int updateExpenseEntryMode(@Param("familyId") long familyId, @Param("mode") String mode);

    @Update("UPDATE family SET reporting_template = #{template} WHERE id = #{familyId}")
    int updateReportingTemplate(@Param("familyId") long familyId, @Param("template") String template);

    /** FR-63c · 距填报截止前几天开始强提醒 */
    @Update("UPDATE family SET report_remind_lead_days = #{leadDays} WHERE id = #{familyId}")
    int updateRemindLeadDays(@Param("familyId") long familyId, @Param("leadDays") int leadDays);

    /**
     * v1.23 FR-610 · 关账节奏(宽限天数 + 自动关账开关)。
     *
     * <p>两个值一起写:它们在管理页是同一个配置项的两个面(「立即 / T+2 / T+5 / 我自己关」),
     * 分开写会出现「选了手动但宽限还是 2」这种读不出意图的中间态。</p>
     */
    @Update("UPDATE family SET close_delay_days = #{delayDays}, auto_close_enabled = #{autoClose} WHERE id = #{familyId}")
    int updateCloseRhythm(@Param("familyId") long familyId,
                          @Param("delayDays") int delayDays,
                          @Param("autoClose") boolean autoClose);
}
