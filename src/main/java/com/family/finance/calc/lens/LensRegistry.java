package com.family.finance.calc.lens;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * v1.1 · 维度 / 度量注册表 · tech-design v1.1 决策 2。
 *
 * <p><b>加维度 / 度量只在这里登记一处</b>,网关、旭日、透视表、自定义构建器、看板全部自动支持
 * (承 feedback_enum_add_sweep「一处网住整类」)。维度取值 = Position 字段 getter(标签已由
 * LensQueryService 算好);holdingLevel=true 的维度会把持仓账户拆开 → 账户级收益度量在该类
 * 维度下不可精确归因(见 {@link PivotEngine} 规则)。</p>
 */
public final class LensRegistry {
    private LensRegistry() {}

    /** 维度定义:key(spec 用)· 中文 label · 是否持仓级 · 取值 */
    public record Dimension(String key, String label, boolean holdingLevel, Function<Position, String> extract) {}

    /** 度量定义:key · 中文 label · 语义由 PivotEngine 按 key 实现(金额求和 / 占比派生 / 收益归因规则) */
    public record Measure(String key, String label) {}

    public static final Map<String, Dimension> DIMENSIONS = new LinkedHashMap<>();
    public static final Map<String, Measure> MEASURES = new LinkedHashMap<>();

    static {
        /* v1.6.17 · 注册顺序 = 前端「下一层按」下拉与透视行/列下拉的排列顺序(LinkedHashMap)。
           用户反馈「风险作为第一个不是很合适」。重排的依据:下钻是"再切一刀看结构",
           最常问的是**结构性**问题(是什么 / 在哪 / 投向哪 / 谁的 / 为什么);
           而风险 / 流动性这类是**属性判断**,本身已有专门看板(风险总览 / 流动性),
           随手下钻的需求低。账户类型是记账口径、最技术,放最后。
           ── 结构类 ───────────────────────────── */
        dim("assetClass", "资产类型", false, Position::assetClass);
        dim("platform",   "平台",   false, Position::platform);
        dim("industry",   "行业",   true,  Position::industry);
        dim("owner",      "主理人", false, Position::owner);
        /* v1.20 · 账户组。透视里账户本来就不是维度,所以这不是「折叠账户」而是【新增一维】——
           已分组的账户按组名归并,未分组的按账户名各占一行。
           没建任何组时,每个账户的取值就是自己的名字 → 输出与 v1.19.16 逐字一致。 */
        dim("group",      "账户组", false, Position::group);
        dim("purpose",    "用途",   false, Position::purpose);
        /* v1.19 · 托管形式:「谁在替你做决定,数字由谁产生」。
           派生自账户类型 + 是否现金行 + 流动性标记,【零新增录入】——
           做成可打标维度就得让用户逐个账户打标,直接违背「每月 10 分钟」的硬约束。
           它和其它维度问的不是同一件事:大类/风险答「钱是什么」,平台/主理人答「钱在谁那儿」,
           而这一维答「决策权在谁手里」——自己盯的股票和交给基金经理的钱,同属股票大类,
           决策权却完全不同。 */
        dim("custody",    "托管形式", false, Position::custody);
        /* ── 属性类 ───────────────────────────── */
        dim("risk",       "风险",   false, Position::risk);
        dim("liquidity",  "流动性", false, Position::liquidity);
        dim("currency",   "币种",   false, Position::currency);
        dim("region",     "地域",   true,  Position::region);
        dim("type",       "账户类型", false, Position::type);

        measure("value",       "总资产");
        measure("netPrincipal","净投入");
        measure("share",       "占比");
        measure("latestPnl",   "本期收益额");
        measure("latestReturn","本期收益率");
        measure("cumPnl",      "累计收益额");
        measure("cumReturn",   "累计收益率");
    }

    private static void dim(String key, String label, boolean holdingLevel, Function<Position, String> f) {
        DIMENSIONS.put(key, new Dimension(key, label, holdingLevel, f));
    }

    private static void measure(String key, String label) {
        MEASURES.put(key, new Measure(key, label));
    }

    public static Dimension requireDim(String key) {
        Dimension d = DIMENSIONS.get(key);
        if (d == null) throw new IllegalArgumentException("未知维度: " + key);
        return d;
    }

    public static Measure requireMeasure(String key) {
        Measure m = MEASURES.get(key);
        if (m == null) throw new IllegalArgumentException("未知度量: " + key);
        return m;
    }
}
