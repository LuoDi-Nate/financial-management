package com.family.finance.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * v0.8 FR-149/150 · 可配置指标集。
 * dashboard 与 reports 共用同一套勾选(family.metric_prefs JSON);NULL=代码默认集。
 * 必选项强制纳入;各页展示上限由调用方按目录顺序裁剪(决策 96)。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetricPrefsService {

    private final ObjectMapper objectMapper;

    /** 指标目录项 · key 稳定不变(存 JSON);label 给管理页;defaultOn 默认勾选;mandatory 不可取消。 */
    public record MetricDef(String key, String label, boolean defaultOn, boolean mandatory) {
    }

    // 账户级目录(顺序 = 展示优先级,超上限按此取前 N)
    public static final List<MetricDef> ACCOUNT = List.of(
            new MetricDef("current_value", "当前价值", true, true),
            new MetricDef("xirr", "年化收益率", true, false),
            new MetricDef("cum_pnl", "累计投资损益", true, false),
            new MetricDef("mom_delta", "余额变化(含划转,看赚亏用本期损益)", true, false),   // v1.30.1 · 原名「本期较上期Δ」紧挨本期损益,被当成赚亏
            new MetricDef("share_pct", "占家庭比重", true, false),
            new MetricDef("sparkline", "近况走势", true, false),
            new MetricDef("plan_actual", "预实分析", true, false),
            new MetricDef("net_principal", "累计净投入", false, false),
            new MetricDef("period_return", "本期损益", false, false),
            new MetricDef("return_base", "本位币收益率(含汇率)", false, false),
            new MetricDef("max_drawdown", "最大回撤", false, false),
            new MetricDef("months_held", "持有期数", false, false));
            // v0.10.4 移除 twr/yoy/risk:AccountPerformance 无账户级数据(twr 仅家庭级 · yoy/risk 未接账户级)·
            //   避免"勾了不显示"的超卖。日后接到数据再加回。

    // 家庭级 KPI 目录(只列 dashboard 真有豆腐块/头部元素的指标,保证勾选 1:1 控制显隐)
    public static final List<MetricDef> FAMILY = List.of(
            new MetricDef("net_worth", "净资产(豆腐块)", true, true),
            new MetricDef("total_assets", "总资产(豆腐块)", true, false),
            new MetricDef("total_liab", "总负债(豆腐块)", true, false),
            new MetricDef("emergency_months", "紧急储备(豆腐块)", true, false),
            new MetricDef("period_return", "本月资产收益(豆腐块)", true, false),
            new MetricDef("savings_rate", "储蓄率(头部)", true, false),
            new MetricDef("nw_mom", "净资产环比 MoM(头部)", true, false),
            new MetricDef("nw_yoy", "净资产同比 YoY(头部)", false, false));

    /** 某作用域的启用 key 集合;metricPrefs=null/解析失败 → 默认集。必选项恒纳入。 */
    public Set<String> enabled(String metricPrefsJson, String scope) {
        List<MetricDef> catalog = "family".equals(scope) ? FAMILY : ACCOUNT;
        Set<String> result = new LinkedHashSet<>();
        Set<String> chosen = parseScope(metricPrefsJson, scope);
        for (MetricDef d : catalog) {
            if (d.mandatory() || (chosen == null ? d.defaultOn() : chosen.contains(d.key()))) {
                result.add(d.key());
            }
        }
        return result;
    }

    /** 启用集按目录顺序取前 N(上限裁剪 · 决策 96);account/family 各页自定上限。 */
    public List<String> enabledCapped(String metricPrefsJson, String scope, int cap) {
        List<MetricDef> catalog = "family".equals(scope) ? FAMILY : ACCOUNT;
        Set<String> en = enabled(metricPrefsJson, scope);
        List<String> ordered = catalog.stream().map(MetricDef::key).filter(en::contains).toList();
        return ordered.size() <= cap ? ordered : ordered.subList(0, cap);
    }

    private Set<String> parseScope(String json, String scope) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, List<String>> m = objectMapper.readValue(json, Map.class);
            List<String> list = m.get(scope);
            return list == null ? null : new LinkedHashSet<>(list);
        } catch (Exception e) {
            log.warn("metric_prefs 解析失败,回落默认集: {}", e.getMessage());
            return null;
        }
    }

    /** 把管理页提交的勾选(family/account 两组)序列化成存库 JSON。 */
    public String serialize(List<String> familyKeys, List<String> accountKeys) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("family", familyKeys == null ? List.of() : familyKeys);
        m.put("account", accountKeys == null ? List.of() : accountKeys);
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("metric_prefs 序列化失败", e);
        }
    }
}
