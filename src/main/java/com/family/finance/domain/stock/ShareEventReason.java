package com.family.finance.domain.stock;

import java.util.Locale;

/**
 * v1.30 · 「持仓数量变动」的原因(PRD FR-968 · tech-design/v1.30.md 选型九)。
 *
 * <p>库里存字符串;认不出的值({@link #of} 返回 {@link #OTHER})页面写「其他」—— 回滚 / 前滚都不炸。</p>
 */
public enum ShareEventReason {
    /** 货币基金:按逐日万份收益自动结转(系统) */
    MMF_ACCRUAL("货币基金收益结转"),
    /** 基金行:用户改份额(没勾现金联动 → 份额变化计入估值) */
    MANUAL_EDIT("手动改份额"),
    /** 货币基金行:用户按 App 核对改金额(没勾现金联动) */
    MANUAL_CORRECTION("手动校正"),
    /** 份额增加、钱来自账户现金行(余额不变) */
    CASH_BUY("申购(用账户现金)"),
    /** 份额减少、钱回到账户现金行(余额不变) */
    CASH_REDEEM("赎回(到账户现金)"),
    /** 截图导入命中这一行,按最新净值反推份额 */
    IMPORT("截图导入更新"),
    /** 手填市值行改为按净值自动估值(开始按份额记) */
    CONVERT("改为按净值自动估值"),
    /** 新加的基金(开始按份额记) */
    CREATE("添加基金"),
    OTHER("其他");

    private final String label;

    ShareEventReason(String label) { this.label = label; }

    public String getLabel() { return label; }

    public static ShareEventReason of(String raw) {
        if (raw == null || raw.isBlank()) return OTHER;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }

    /**
     * 时间线上显示的来源(v1.18 「每条流水都带来源」)。holding_share_event 不单存来源列 ——
     * 来源由原因唯一决定:货基结转是系统按万份收益算的,截图导入来自截图,其余都是人在页面上点的。
     * 认不出的原因({@link #OTHER})老实写「来源未记录」,不猜成手动。
     */
    public com.family.finance.domain.ledger.LedgerSource source() {
        return switch (this) {
            case MMF_ACCRUAL -> com.family.finance.domain.ledger.LedgerSource.SYNC_FUND_NAV;
            case IMPORT -> com.family.finance.domain.ledger.LedgerSource.IMPORT_SCREENSHOT;
            case MANUAL_EDIT, MANUAL_CORRECTION, CASH_BUY, CASH_REDEEM, CONVERT, CREATE ->
                    com.family.finance.domain.ledger.LedgerSource.MANUAL;
            case OTHER -> com.family.finance.domain.ledger.LedgerSource.UNKNOWN;
        };
    }

    /** 这一类变动有没有同时改余额(联动现金的两类不改;改为自动估值只是换记法 —— 市值不变,开始按份额记) */
    public boolean movesBalance() {
        return switch (this) {
            case CASH_BUY, CASH_REDEEM, CONVERT -> false;
            default -> true;
        };
    }
}
