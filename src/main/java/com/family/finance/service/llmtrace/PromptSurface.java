package com.family.finance.service.llmtrace;

/**
 * v1.28 · 发给 AI 的内容用在哪(PRD §3.5 · FR-919)。
 *
 * <p>{@code shortLived} = 结果只在内存 / 浏览器里(体检三处、透视、目标推荐参数),
 * 进程一重启结果就没了 —— 记录留 2 天就够;其余跟着库里的结果走,最长 90 天(FR-915)。</p>
 */
public enum PromptSurface {
    DIAGNOSE_FAMILY("体检 · AI 综合诊断", true),
    DIAGNOSE_ACCOUNT("单账户体检 · AI 诊断", true),
    ASSET_INSIGHT("体检 · AI 资产洞察", true),
    REBALANCE("报表 · AI 调仓建议", false),
    REVIEW("仪表盘 · AI 月度复盘", false),
    LENS_INSIGHT("资产透视 · AI 解读", true),
    GOAL_REPORT("目标 · AI 月报", false),
    GOAL_ALERT("目标 · AI 预警建议", false),
    GOAL_PARAMS("新建目标 · AI 推荐参数", true),
    MERCHANT_CLASSIFY("支出导入 · 商户分类", true),
    ASK_TURN("超级 Agent · 这一问", false),
    /** 托管模式:「更新 Agent / 创建 Agent」时推给百炼的系统提示词(不是一次问答) */
    ASK_AGENT_SYSTEM("超级 Agent · 推给百炼的规矩", false);

    private final String label;
    private final boolean shortLived;

    PromptSurface(String label, boolean shortLived) {
        this.label = label;
        this.shortLived = shortLived;
    }

    public String getLabel() { return label; }

    public boolean isShortLived() { return shortLived; }

    /** 永不抛:脏值返回 null */
    public static PromptSurface parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (PromptSurface s : values()) if (s.name().equalsIgnoreCase(raw.trim())) return s;
        return null;
    }
}
