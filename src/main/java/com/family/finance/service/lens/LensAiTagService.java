package com.family.finance.service.lens;

import com.family.finance.domain.lens.AssetClass;
import com.family.finance.domain.lens.IndustryTag;
import com.family.finance.service.checkup.llm.LlmRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * v1.1 · AI 推荐打标(prd v1.1 FR-1b · 已拍板 D4)。
 *
 * <p><b>只做分类,不做数学</b>(承 feedback_llm_no_math 允许域):把账户名 / 持仓名交给现有
 * LLM 接入,推荐 平台 / 行业 / 大类。<b>白名单校验</b>:industry / assetClass 必须是枚举
 * name(),枚举外值直接丢弃(OutputValidator 同风格);platform 自由文本截断 40。
 * 结果仅作<b>待确认预填</b>,用户显式保存才落库(承贷款预填「显式接受」教训)。
 * 没有可用候选 → {@link #available(long)} false,入口降级隐藏,手动打标不受影响。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LensAiTagService {

    private final LlmRouter llmRouter;
    private final ObjectMapper objectMapper;

    public boolean available(long familyId) {
        return llmRouter.available(familyId);
    }

    /** 单条建议(枚举 name 或 null) */
    public record Tags(String platform, String industry, String assetClass) {}

    /**
     * 名称 → 建议标签。任一 client 成功即返回;全失败返回空 map(页面提示稍后再试)。
     */
    public Map<String, Tags> suggest(long familyId, List<String> names) {
        return suggestPrepared(familyId,names,null);
    }

    public Map<String, Tags> suggestPrepared(long familyId, List<String> names, List<com.family.finance.service.checkup.llm.LlmInvocation> planned) {
        if (names == null || names.isEmpty()) return Map.of();
        String industries = java.util.Arrays.stream(IndustryTag.values())
                .map(t -> t.name() + "=" + t.getLabel()).collect(Collectors.joining(", "));
        String classes = java.util.Arrays.stream(AssetClass.values())
                .map(c -> c.name() + "=" + c.getLabel()).collect(Collectors.joining(", "));
        String system = """
                你是家庭记账软件的资产分类助手。只做分类,不做任何计算。对每个资产名称推断三个标签:

                1. platform(平台/机构):从名称中提取钱所在的机构中文名(如 招商银行/支付宝 · 蚂蚁财富/富途证券/天天基金)。
                   名称里认不出机构就给 null,不要猜。
                2. industry(行业/投向):指这笔钱**底层投向**的行业主题,不是"产品属于什么行业"。
                   必须取枚举 code(%s)。判定规则:
                   - 个股 → 该公司所在行业(如 宁德时代→NEW_ENERGY,贵州茅台→CONSUMER,腾讯→TECH_INTERNET,阿里巴巴→ECOMMERCE_RETAIL);
                   - 行业/主题基金 → 对应行业;宽基指数(沪深300/标普500等)→ BROAD_INDEX;
                   - 纯债基金/银行固收理财 → FIXED_BOND;固收+/偏债混合/FOF/目标日期养老 → MIXED_ALLOC;
                   - 红利策略/公用事业(长江电力/中证红利/水电燃气)→ DIVIDEND_UTIL;
                   - 货币基金/余额宝/零钱通/活期存款/储蓄卡 → MONEY_CASH(货币现金 · 现金管理类的专属投向);
                   - FINANCE 仅指投向银行/券商/保险公司股票的持仓(如 招商银行股票/中信证券),"金融产品"本身不算;
                   - ESTATE_CONSTRUCTION 指投向房地产/建筑公司股票的持仓(如 万科);
                   - 判断不了就 null,宁缺勿滥。
                3. assetClass(资产类型):必须取枚举 code(%s)。
                   货币基金/余额宝/存款/储蓄卡 → CASH_EQ;银行理财/债基 → FIXED_INCOME;股票/股基/指数基金 → EQUITY;
                   黄金/加密 → ALTERNATIVE;房产 → REAL_ESTATE;储蓄型保险 → INSURANCE;判断不了 → null。

                示例(严格照此风格输出):
                {"萝卜-余额宝":{"platform":"支付宝 · 蚂蚁财富","industry":"MONEY_CASH","assetClass":"CASH_EQ"},
                 "招行储蓄卡-工资":{"platform":"招商银行","industry":"MONEY_CASH","assetClass":"CASH_EQ"},
                 "宁德时代":{"platform":null,"industry":"NEW_ENERGY","assetClass":"EQUITY"},
                 "招商银行A股":{"platform":null,"industry":"FINANCE","assetClass":"EQUITY"},
                 "天天基金-沪深300ETF":{"platform":"天天基金","industry":"BROAD_INDEX","assetClass":"EQUITY"},
                 "工行-稳健理财R2":{"platform":"工商银行","industry":"FIXED_BOND","assetClass":"FIXED_INCOME"}}

                只输出一个严格 JSON 对象,键=输入名称原文,值={"platform":...,"industry":...,"assetClass":...}。
                不要输出任何其它文字、注释或 markdown。""".formatted(industries, classes);
        String user;
        try {
            user = objectMapper.writeValueAsString(names);
        } catch (Exception e) {
            return Map.of();
        }
        // v1.13 · 走 LlmRouter:主备顺序统一由配置决定。这里必须用 Handler 形态而不是简单 invoke ——
        //   「解析出来一条白名单内的标签都没有」和「调用失败」对用户是同一件事(打标页空手而归),
        //   所以解析空要当成「这个候选没给出可用答案」继续试下一个,而不是就此收工。
        LlmRouter.Handler<Map<String, Tags>> handler = (inv, raw, ms) -> {
            Map<String, Tags> out = parseAndWhitelist(raw, names);
            if (out.isEmpty()) {
                log.warn("AI 打标 {} 输出解析后无可用标签 · 试下一个候选", inv.badge());
                return null;
            }
            return out;
        };
        var trace = com.family.finance.service.llmtrace.PromptTrace.of(com.family.finance.service.llmtrace.PromptSurface.OTHER_TEXT);
        Map<String, Tags> routed = planned == null ? llmRouter.invoke(familyId,trace,system,user,handler)
            : llmRouter.invokePrepared(familyId,trace,system,user,handler,planned);
        return routed == null ? Map.of() : routed;
    }

    /** 提取首个 {...} → 解析 → 白名单过滤(枚举外丢弃 · platform 截 40) */
    Map<String, Tags> parseAndWhitelist(String raw, List<String> names) {
        if (raw == null) return Map.of();
        int s = raw.indexOf('{'), e = raw.lastIndexOf('}');
        if (s < 0 || e <= s) return Map.of();
        Map<String, Tags> out = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(raw.substring(s, e + 1));
            for (String name : names) {
                JsonNode n = root.get(name);
                if (n == null || !n.isObject()) continue;
                String platform = text(n, "platform");
                if (platform != null && platform.length() > 40) platform = platform.substring(0, 40);
                String industry = text(n, "industry");
                if (IndustryTag.fromName(industry) == null) industry = null;   // 白名单外丢弃
                String assetClass = text(n, "assetClass");
                if (AssetClass.fromName(assetClass) == null) assetClass = null;
                if (platform != null || industry != null || assetClass != null) {
                    out.put(name, new Tags(platform,
                            industry == null ? null : industry.trim().toUpperCase(),
                            assetClass == null ? null : assetClass.trim().toUpperCase()));
                }
            }
        } catch (Exception ex) {
            return Map.of();
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText();
        return s == null || s.isBlank() || "null".equalsIgnoreCase(s) ? null : s.trim();
    }
}
