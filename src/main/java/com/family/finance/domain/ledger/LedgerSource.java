package com.family.finance.domain.ledger;

/**
 * 流水来源(v1.18)—— 这一笔变动是<b>谁写进来的</b>。
 *
 * <p>和 {@code Kind}(收入/支出/划转/估值/校准)是两个维度:{@code Kind} 说"这是什么性质的变动",
 * 来源说"它怎么进到系统里的"。以前只有前者,于是用户在流水时间线上看到一笔估值变动,
 * 分不出是定时拉价、还是富途同步、还是自己截图导入的。</p>
 *
 * <p><b>UNKNOWN 的意思是"当时没记",不是"手动"</b> —— v1.18 之前的历史数据一律是它。
 * 把历史回填成 MANUAL 会让统计得出"过去全是手填"的错误结论,而事实上其中有一部分是自动来的。</p>
 */
public enum LedgerSource {

    /** 人在页面上填的(填报 / 划转 / 手工校准) */
    MANUAL("手动填报", "manual"),

    /** 定时拉股票价格后自动估值(A股 / 美股 / 港股) */
    SYNC_STOCK_API("自动 · 股价", "auto"),

    /** 定时拉贵金属价格后自动估值(上海金 / 国际现货) */
    SYNC_METAL_API("自动 · 金价", "auto"),

    /** 定时拉加密货币价格后自动估值 */
    SYNC_CRYPTO_API("自动 · 币价", "auto"),

    /** v1.30 · 场外基金净值更新 / 货币基金收益结转后自动估值 */
    SYNC_FUND_NAV("自动 · 基金净值", "auto"),

    /** 富途 OpenD 同步持仓后引起的变动 */
    SYNC_BROKER_FUTU("自动 · 富途", "broker"),

    /** 老虎证券同步持仓后引起的变动 */
    SYNC_BROKER_TIGER("自动 · 老虎", "broker"),

    /** 盈透 IBKR(Flex 报表)同步持仓后引起的变动 · v1.26 */
    SYNC_BROKER_IBKR("自动 · 盈透", "broker"),

    /** AI 截图导入持仓后引起的变动 */
    IMPORT_SCREENSHOT("截图导入", "import"),

    /** 开账时延续上期末余额(系统代填,没有人确认过) */
    CARRIED_FORWARD("开账延续", "system"),

    /** 系统联动改的(如股票买卖联动扣/加账户现金) */
    SYSTEM_ADJUST("系统联动", "system"),

    /** 来源当时没有记录 —— v1.18 之前的历史数据 */
    UNKNOWN("来源未记录", "unknown");

    private final String label;
    /** 归类:manual / auto / broker / import / system / unknown —— 页面按它上色 */
    private final String group;

    LedgerSource(String label, String group) {
        this.label = label;
        this.group = group;
    }

    public String getLabel() { return label; }
    public String getGroup() { return group; }

    /** 是不是"自动来的"(页面上可能只想区分手动 vs 自动)。 */
    public boolean isAutomatic() {
        return this == SYNC_STOCK_API || this == SYNC_METAL_API || this == SYNC_CRYPTO_API
                || this == SYNC_FUND_NAV || "broker".equals(group);
    }

    /**
     * 从库里的字符串解析 —— 认不出一律 {@link #UNKNOWN}。
     *
     * <p>不抛异常是有意的:这一列是<b>展示用的元信息</b>,不参与任何金额计算。
     * 将来加了新来源、用户又回滚到老版本,老代码读到不认识的值应该显示"来源未记录",
     * 而不是让整个流水页 500。</p>
     */
    public static LedgerSource parse(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /**
     * 券商 vendor → 对应来源(同步持仓引起的变动用它)。
     *
     * <p>v1.26 · 改成对 {@link com.family.finance.domain.broker.BrokerVendor} 的<b>穷尽 switch</b>(不留 default)。
     * 原来是字符串 switch + {@code default -> UNKNOWN}:加第三家券商时编译器一声不吭,
     * 它同步引起的每一笔估值变动都会被记成「来源未记录」。现在加券商不在这里写一行就编译不过。</p>
     */
    public static LedgerSource ofBroker(String vendorName) {
        if (vendorName == null) return UNKNOWN;
        com.family.finance.domain.broker.BrokerVendor v;
        try {
            v = com.family.finance.domain.broker.BrokerVendor.valueOf(vendorName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
        return forVendor(v);
    }

    public static LedgerSource forVendor(com.family.finance.domain.broker.BrokerVendor v) {
        return switch (v) {
            case FUTU -> SYNC_BROKER_FUTU;
            case TIGER -> SYNC_BROKER_TIGER;
            case IBKR -> SYNC_BROKER_IBKR;
        };
    }
}
