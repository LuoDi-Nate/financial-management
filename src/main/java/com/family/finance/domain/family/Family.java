package com.family.finance.domain.family;

import com.family.finance.domain.period.PeriodType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Family {
    private Long id;
    private String name;
    private String brandText;
    private String logoPath;
    /** v0.2 FR-1/FR-34:预设图标 code(icon1..icon4),默认 icon2。驱动 iOS apple-touch-icon + PWA manifest;web favicon/nav 在 logo_path 为空时用 */
    private String logoPreset;
    /** v1.35.1 · 品牌名旁小图标的 code(见 {@link BrandMark});NULL = 默认存钱罐 */
    private String brandMark;
    private String baseCurrency;
    private PeriodType periodType;
    /** v0.4 FR-61a · 通胀对照线假设值(% · 默认 2.00 = 2%) */
    private java.math.BigDecimal cpiAssumption;
    /** v0.4 FR-62a · 配置锚 code · 关联 allocation_anchor.code · 默认 SP_4321 · CUSTOM 走 allocationAnchorCustom */
    private String allocationAnchor;
    /** v0.4 FR-62a · 自定义锚 JSON {"cash":10,"invest":30,"property":40,"insurance":20} */
    private String allocationAnchorCustom;
    /** v0.4 FR-62b · 家庭风险偏好 · CONSERVATIVE / MODERATE / AGGRESSIVE · LLM 调仓 prompt 输入 */
    private String riskAppetite;
    /** v0.4.14 FR-63a · 家庭级填报模板 code · 见 ReportingTemplate · 默认 T1 */
    private String reportingTemplate;
    /** v1.8 · 支出录入方式 · TOTAL(默认 · 现状)/ ITEMIZED(逐笔落账户)· 见 {@link ExpenseEntryMode} */
    private String expenseEntryMode;
    /** v0.4.14 FR-63c · 距填报截止前几天开始强提醒 · 默认 2 */
    private Integer reportRemindLeadDays;

    /**
     * v1.23 FR-610 · 关账宽限天数:账期自然结束后再保持 OPEN 几天(0 / 2 / 5)。
     *
     * <p>默认 0 = v1.22 及以前的行为(新期起始日即关上期)。&gt;0 时会出现
     * 「上期与新期同时 OPEN」的<b>双活跃窗口</b> —— 那不是异常状态,是一等公民,
     * 全系统语义见 prd/v1.23.md §4。上限 5 由 schema CHECK 锁死(不能跨到第三期)。</p>
     */
    private Integer closeDelayDays;

    /** v1.23 FR-610 · false = 手动关账(仍受 FR-612 兜底:下下期一开强制关)。 */
    private Boolean autoCloseEnabled;

    /** 宽限天数 · null(老数据 / 未配置)按 0 算,即现行为。 */
    public int closeDelayDaysOrZero() {
        return closeDelayDays == null ? 0 : closeDelayDays;
    }

    /** 是否自动关账 · null 按 true 算,即现行为。 */
    public boolean autoCloseOrDefault() {
        return autoCloseEnabled == null || autoCloseEnabled;
    }
    /** v0.8 FR-149 · 指标勾选配置 JSON {"family":[...],"account":[...]};NULL=代码默认集 */
    private String metricPrefs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
