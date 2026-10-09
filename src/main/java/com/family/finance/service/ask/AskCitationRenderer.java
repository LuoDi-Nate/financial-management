package com.family.finance.service.ask;

import com.family.finance.domain.ask.AskCitation;
import com.family.finance.repository.PeriodMapper;
import com.family.finance.domain.period.Period;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * v1.19 · 把 {@code {{cite:c1}}} 标记渲染成引用块。
 *
 * <h3>为什么正文里存的是标记而不是数字</h3>
 * <p>模型没有机会碰那个数字 —— 它只写标记,数值从 {@link AskCitation} 取,
 * 而那是工具返回的原值。「模型把 760 万说成 706 万」这类错误在结构上不可能发生。</p>
 *
 * <h3>口径文案是渲染期现取的</h3>
 * <p>数值是<b>当时</b>的(存在库里),口径说法是<b>今天</b>的(在代码里)。
 * 于是三个月前那条回答重新打开时,数字仍是当时那个,但「这个数怎么算的」跟着最新说法走 ——
 * 不会出现一个已经改过名的旧指标名孤零零地留在历史里。</p>
 *
 * <p><b>取舍说明</b>:口径文案这里给的是<b>静态一句话</b>,不是页面 tooltip 里那种
 * 「A − B = C」的实算展开 —— 后者要重跑整条 KPI 管线才拿得到,为了渲染一条三个月前的
 * 历史消息重算一遍全家资产不划算。想看实算的,点引用块回原页,那里是活的。</p>
 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AskCitationRenderer {

    private static final Pattern CITE = Pattern.compile("\\{\\{cite:([A-Za-z0-9_]{1,14})}}");
    /** 追问标记 · FR-424b。与 cite 同一套形状,渲染期抽出来变成 chip */
    private static final Pattern NEXT = Pattern.compile("\\{\\{next:([^}\\n]{1,40})}}");
    /**
     * v1.27 · 「提议记住」标记(FR-854)。和 next 同形状:正文里不显示,单独渲染成确认卡
     * (见 {@link com.family.finance.service.ask.AskRememberParser})。
     */
    public static final Pattern REMEMBER = Pattern.compile("\\{\\{remember:([^}\\n]{1,320})}}");
    /** 确认卡处理过(记住 / 切了模板 / 不用)之后,标记改写成这个 —— 回看这段对话时不再出卡,也不显示 */
    public static final Pattern REMEMBER_DONE = Pattern.compile("\\{\\{remember-done:([^}\\n]{1,320})}}");
    /** 一轮最多给几条追问 —— 再多就成了一屏按钮,用户反而不知道点哪个 */
    private static final int MAX_NEXT = 3;
    /** 「- xxx」/「1. xxx」列表项 */
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*·]|\\d{1,2}[.)])\\s+(.*)$");
    /**
     * 图表标记。整行一个,内容是一小段 JSON。
     *
     * <p>数据点用 {@code cite} 引用工具返回的数字,<b>不许模型自己填数</b> ——
     * 图和正文必须是同一份数,否则「数字保真」只保住了正文那一半。</p>
     */
    private static final Pattern CHART = Pattern.compile("^\\{\\{chart:(.+)}}$", Pattern.DOTALL);
    private static final String CHART_OPEN = "{{chart:";
    /** 复制/纯文本时用来剥掉整段标记(渲染走的是 CHART / 围栏那条路,不用这两个) */
    private static final Pattern CHART_ANY =
            Pattern.compile("\\{\\{chart:.*?}}}", Pattern.DOTALL);
    private static final Pattern ARTIFACT_ANY =
            Pattern.compile("```artifact.*?```", Pattern.DOTALL);
    /** 一张图的 JSON 再长也不该超过这么多行;收不齐就当普通文本,别把正文吞掉 */
    private static final int CHART_MAX_LINES = 40;
    /** 自由 HTML 的围栏。用 fenced block 而不是 {{}} —— HTML 里出现 }} 太常见了 */
    private static final String ARTIFACT_OPEN = "```artifact";
    private static final String FENCE = "```";
    /** 正文里出现的裸金额 —— 用来判定「模型没听话,自己抄数字了」 */
    private static final Pattern BARE_MONEY =
            Pattern.compile("(?<![\\d.])\\d{1,3}(,\\d{3})+(\\.\\d+)?(?![\\d.])|(?<![\\d.])\\d{5,}(\\.\\d+)?(?![\\d.])");

    private final PeriodMapper periodMapper;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * metricKey → 这个数字叫什么、怎么来的、点回哪、那一页叫什么。
     *
     * <p>{@code go} 是<b>去向短标签</b>,不是口径说明。卡片右下角那一行每张卡都会出现,
     * 放整句口径的话四张卡就是四遍同样的话 —— 手机上尤其吵。完整口径进 tooltip,
     * 那儿是用户主动问「这怎么算的」时才看的。</p>
     */
    private record Meta(String label, String explain, String href, String go) {}

    private static final Map<String, Meta> METAS = new LinkedHashMap<>();
    static {
        METAS.put("kpi.netWorth", new Meta("净资产",
                "总资产减去总负债。房子按你填的估值算,不含未来要还的部分。", "/dashboard", "→ 仪表盘"));
        METAS.put("kpi.totalAssets", new Meta("总资产",
                "所有资产类账户的期末余额合计,按视图币种折算。", "/dashboard", "→ 仪表盘"));
        METAS.put("kpi.totalLiabilities", new Meta("总负债",
                "所有负债类账户的期末余额绝对值合计。", "/dashboard", "→ 仪表盘"));
        METAS.put("kpi.netWorthDelta", new Meta("净资产变化",
                "本期净资产减上期净资产。它同时包含「你存下的」和「投资赚的」。", "/dashboard#dash-cashflow", "→ 仪表盘 · 本期怎么变的"));
        METAS.put("kpi.humanEarned", new Meta("人赚(你存下的)",
                "本期净流入 —— 收入减支出。跟市场涨跌无关,是你自己攒下来的部分。",
                "/dashboard#dash-cashflow", "→ 仪表盘 · 本期怎么变的"));
        METAS.put("kpi.openingBaseline", new Meta("开账基线",
                "本来就有、这期才开始记的存量(新账户第一期的余额,以及已有账户记的「补录本金」),不算在任何一期的收益里 —— 否则会凭空多出一大笔。",
                "/dashboard#dash-cashflow", "→ 仪表盘 · 本期怎么变的"));
        METAS.put("kpi.emergencyMonths", new Meta("紧急储备月数",
                "随时能取用的钱,够覆盖几个月的平均支出。", "/checkup#liquidity", "→ 资产体检 · 流动性"));
        METAS.put("lens.pivot", new Meta("资产分布",
                "按你选的维度把账户余额归堆,合计等于总资产。", "/lens", "→ 资产透视"));
        METAS.put("factview.accountPerformance", new Meta("账户表现",
                "单个账户的现值、累计投入与收益率。满 12 期才是年化,不足 12 期是累计。",
                "/reports#rep-accounts", "→ 报表 · 账户表现"));
    }

    /**
     * 补上 label / explain,给模板用。
     *
     * <p><b>label 优先用落库那一份</b> —— 它常常是数据派生的(「支付宝 · 总资产」里的行名
     * 来自用户自己的账户),口径表里推不出来。只有它缺失时才退回口径表的通名。</p>
     */
    public AskCitation decorate(AskCitation c) {
        Meta m = lookup(c.getMetricKey());
        if (c.getLabel() == null || c.getLabel().isBlank()) {
            c.setLabel(m == null ? c.getMetricKey() : m.label());
        }
        c.setExplain(m == null ? "这个数来自系统的既有口径,与页面上同名指标一致。" : m.explain());
        if ((c.getTargetHref() == null || c.getTargetHref().isBlank()) && m != null) {
            c.setTargetHref(m.href());
        }
        return c;
    }

    /**
     * 按最长前缀找口径。
     *
     * <p>工具发出的 metricKey 常常带度量后缀({@code lens.pivot.value} / {@code lens.pivot.share}),
     * 精确匹配会全部落到兜底文案上 —— 界面上就是四张卡片写着同一句废话。
     * 逐段回退到 {@code lens.pivot} 就能拿到真正的口径说明。</p>
     */
    private static Meta lookup(String metricKey) {
        if (metricKey == null) return null;
        String k = metricKey;
        while (true) {
            Meta m = METAS.get(k);
            if (m != null) return m;
            int dot = k.lastIndexOf('.');
            if (dot < 0) return null;
            k = k.substring(0, dot);
        }
    }

    public String toolLabel(String toolName) {
        return switch (toolName) {
            case "capabilities" -> "看看能查什么";
            case "pivot" -> "资产分布";
            case "period_summary" -> "账期全貌";
            case "account_performance" -> "账户收益";
            case "report_unmet" -> "记一笔够不着";
            default -> toolName;
        };
    }

    public String periodLabel(long familyId, Long periodId) {
        if (periodId == null) return null;
        return periodMapper.findById(familyId, periodId)
                .map(Period::getPeriodStart)
                .map(d -> d.toString().substring(0, 7))
                .orElse(null);
    }

    // ──────────────────────── 渲染 ────────────────────────

    /**
     * 正文 → HTML。
     *
     * <p>先转义再替换标记 —— 顺序反了就等于把模型的输出当 HTML 执行。
     * 模型的输出是不可信输入,哪怕它是我们自己配的模型:提示词注入可以从用户的账户名里来。</p>
     */
    /**
     * 正文里的追问建议。
     *
     * <p>存在正文里(而不是单开一张表)是刻意的:它和 {@code {{cite:}}} 同一套形状,
     * 落库、回放、导出都走同一条路,不需要为三个短句再加一张表和一套装配逻辑。</p>
     */
    public List<String> nextQuestions(String body) {
        if (body == null) return List.of();
        List<String> out = new java.util.ArrayList<>();
        Matcher m = NEXT.matcher(body);
        while (m.find() && out.size() < MAX_NEXT) {
            String q = m.group(1).trim();
            if (!q.isEmpty() && !out.contains(q)) out.add(q);
        }
        return out;
    }

    /**
     * 供「复制」用的纯文本:引用标记换成真实数值,追问标记去掉。
     *
     * <p>复制出去的东西里<b>不能留 {@code {{cite:c1}}}</b> —— 粘到微信里对方看到一串花括号,
     * 而这条回答的价值恰恰在那几个数字上。</p>
     */
    public String plainText(String body, List<AskCitation> citations) {
        if (body == null) return "";
        Map<String, String> vals = new LinkedHashMap<>();
        for (AskCitation c : citations) {
            AskCitation d = decorate(c);
            vals.put(d.getCiteKey(), d.getLabel() + " " + d.getValueText()
                    + (d.isInProgress() ? "(未关账)" : ""));
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = CITE.matcher(body);
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    vals.getOrDefault(m.group(1), "")));
        }
        m.appendTail(sb);
        // 图表与自由 HTML 换成一个占位词:复制出去的东西里留着 {{chart:{…}}} 或一整段 HTML,
        // 粘到微信里对方看到的是一堆花括号,而这条回答的价值在那几个数字上。
        String t = sb.toString();
        t = CHART_ANY.matcher(t).replaceAll("[图]");
        t = ARTIFACT_ANY.matcher(t).replaceAll("[图]");
        t = REMEMBER.matcher(t).replaceAll("");   // v1.27 · 确认卡不进复制出去的文字
        t = REMEMBER_DONE.matcher(t).replaceAll("");
        return NEXT.matcher(t).replaceAll("").replaceAll("\\n{3,}", "\\n\\n").trim();
    }

    /**
     * 正文 → HTML。
     *
     * <p><b>先按块切,再逐块渲染。</b>图表 JSON 与自由 HTML 都必须在<b>转义之前</b>取出来 ——
     * 转义会把 {@code "} 变成实体,JSON 解析当场失败;而自由 HTML 整块要原样进 iframe,
     * 被逐行的段落/列表逻辑拆开就没法用了。第一版把这两样和普通文本混在一个循环里,
     * 图表一个都渲染不出来。</p>
     */
    public String renderHtml(long familyId, String body, List<AskCitation> citations) {
        if (body == null) return "";
        body = NEXT.matcher(body).replaceAll("");   // 追问单独渲染成 chip,不进正文
        body = REMEMBER.matcher(body).replaceAll("");   // v1.27 · 「提议记住」单独渲染成确认卡,不进正文
        body = REMEMBER_DONE.matcher(body).replaceAll("");
        Map<String, AskCitation> byKey = new LinkedHashMap<>();
        for (AskCitation c : citations) byKey.put(c.getCiteKey(), decorate(c));

        StringBuilder out = new StringBuilder();
        for (Seg seg : segment(body)) {
            switch (seg.kind()) {
                case ARTIFACT -> out.append(artifact(seg.text(), byKey));
                case CHART -> out.append(chart(seg.text(), byKey));
                default -> out.append(prose(familyId, seg.text(), byKey));
            }
        }
        return out.toString();
    }

    private enum Kind { TEXT, CHART, ARTIFACT }
    private record Seg(Kind kind, String text) {}

    /** 把正文切成「普通文本 / 图表 / 自由 HTML」三种块 */
    private static List<Seg> segment(String body) {
        List<Seg> segs = new java.util.ArrayList<>();
        StringBuilder text = new StringBuilder();
        String[] lines = body.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].trim();

            if (t.startsWith(ARTIFACT_OPEN)) {
                int close = -1;
                for (int j = i + 1; j < lines.length; j++) {
                    if (lines[j].trim().equals(FENCE)) { close = j; break; }
                }
                // 围栏没闭合(多半是被叫停在半截)—— 当普通文本走,别把半截 HTML 塞进 iframe
                if (close > 0) {
                    flush(segs, text);
                    segs.add(new Seg(Kind.ARTIFACT, String.join("\n",
                            java.util.Arrays.copyOfRange(lines, i + 1, close))));
                    i = close;
                    continue;
                }
            }

            // 图表标记**可能跨多行** —— 模型习惯把 JSON 排版成多行,实测就是这样。
            // 第一版只认单行,结果它画的图一张都没渲染出来(标记原样留在正文里)。
            if (t.startsWith(CHART_OPEN)) {
                StringBuilder buf = new StringBuilder(lines[i]);
                int end = i;
                while (!buf.toString().trim().endsWith("}}") && end + 1 < lines.length
                       && end - i < CHART_MAX_LINES) {
                    end++;
                    buf.append('\n').append(lines[end]);
                }
                Matcher ch = CHART.matcher(buf.toString().trim());
                if (ch.matches()) {
                    flush(segs, text);
                    segs.add(new Seg(Kind.CHART, ch.group(1)));
                    i = end;
                    continue;
                }
                // 收不齐(还在流 / 写坏了)→ 当普通文本走
            }

            text.append(lines[i]).append('\n');
        }
        flush(segs, text);
        return segs;
    }

    private static void flush(List<Seg> segs, StringBuilder text) {
        if (!text.isEmpty()) {
            segs.add(new Seg(Kind.TEXT, text.toString()));
            text.setLength(0);
        }
    }

    /** 普通文本块:段落 / 列表 / 独立成行的引用卡 */
    private String prose(long familyId, String body, Map<String, AskCitation> byKey) {
        StringBuilder out = new StringBuilder();
        boolean inList = false;
        for (String block : escape(body).split("\n{2,}")) {
            if (block.isBlank()) continue;
            for (String line : block.split("\n")) {
                if (line.isBlank()) continue;
                String t = line.trim();

                Matcher only = CITE.matcher(t);
                // 整行只有一个标记 → 独立的引用卡(审过的预览就是这个形态)
                if (only.matches()) {
                    if (inList) { out.append("</ul>"); inList = false; }
                    AskCitation c = byKey.get(only.group(1));
                    if (c != null) out.append(card(familyId, c));
                    continue;
                }

                // 「- xxx」列表项。不认它的话每条会变成独立段落,间距撑得像三段话,
                // 而且行首那个裸的短横线会原样显示出来。
                Matcher li = LIST_ITEM.matcher(t);
                if (li.matches()) {
                    if (!inList) { out.append("<ul>"); inList = true; }
                    out.append("<li>").append(inline(li.group(1), byKey)).append("</li>");
                    continue;
                }

                if (inList) { out.append("</ul>"); inList = false; }
                out.append("<p>").append(inline(t, byKey)).append("</p>");
            }
        }
        if (inList) out.append("</ul>");
        return out.toString();
    }

    // ──────────────────────── 富展示 ────────────────────────

    /**
     * 图表。
     *
     * <p><b>数据点只能用 {@code cite} 引用工具返回的数字</b>,不许模型自己填数 ——
     * 否则「数字保真」只保住了正文那一半,图上画的还是它编的。引用不到的点直接丢掉,
     * 一个都引不到就整张不画:宁可没有图,也不要一张看着像真的的假图。</p>
     *
     * <p>这里只输出一个带 data 的容器,真正画图在前端(ask-charts.js)——
     * 服务端渲染历史消息和客户端流式渲染共用同一个容器契约,
     * 于是「流完刷新一下样子会变」这类问题不会发生。</p>
     */
    /**
     * 补回模型少写的右花括号。
     *
     * <p>标记的结束符是 {@code }}},而 JSON 对象的结尾也是 {@code }} —— 两者天然撞车。
     * 模型很容易把 {@code ...]}}}} 写成 {@code ...]}}},也就是拿标记的一个括号
     * 当成了 JSON 的收尾。2026-09-22 在 beta 上实测到:一张六项的资产类型饼图
     * 就是这么丢的 —— JSON 差一个括号,图<b>静默消失</b>,而正文里
     * 「钱主要分在六类里……:」那个冒号<b>还留在页面上</b>,后面空空如也。</p>
     *
     * <p>最多补两个:再多就不是少写括号,是输出真的坏了,那时候宁可不画。</p>
     */
    static JsonNode parseChartJson(String json) {   // 包级可见:判据要能穷举(见 AskChartMarkerTest)
        for (int extra = 0; extra <= 2; extra++) {
            try {
                JsonNode n = JSON.readTree(json + "}".repeat(extra));
                // 【必须是个对象】—— Jackson 对空串不抛异常,返回的是 MissingNode,
                // 只判 null 会把「什么都没有」当成解析成功,然后在下面 path("items") 上
                // 静默得到空图。判「是不是对象」才是这里真正要的。
                if (n != null && n.isObject()) return n;
            } catch (Exception ignored) { /* 再补一个试试 */ }
        }
        return null;
    }

    private String chart(String json, Map<String, AskCitation> byKey) {
        try {
            JsonNode n = parseChartJson(json);
            if (n == null) {
                // 【别静默】—— 图没画出来时正文里那句引导语还在,页面上会是一个悬空的冒号。
                // 日志里留一行,至少下次有人问起时查得到。
                log.warn("超级 Agent · 图表标记解析失败(补括号也救不回来)· 长度={} 开头={}",
                        json.length(), json.substring(0, Math.min(80, json.length())));
                return "";
            }
            String type = n.path("type").asText("pie");
            String title = n.path("title").asText("");

            List<Map<String, Object>> pts = new java.util.ArrayList<>();
            for (JsonNode it : n.path("items")) {
                String key = it.path("cite").asText(null);
                AskCitation c = key == null ? null : byKey.get(key);
                if (c == null) continue;                      // 引用不到 → 丢掉这个点
                Double v = numeric(c.getValueText());
                if (v == null) continue;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("label", it.path("label").asText(c.getLabel()));
                p.put("value", v);
                p.put("text", c.getValueText());              // 图上显示的还是格式化后那一份
                if (it.hasNonNull("kind")) p.put("kind", it.get("kind").asText());
                pts.add(p);
            }
            if (pts.isEmpty()) return "";                     // 一个点都没有 → 不画

            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", type);
            spec.put("title", title);
            spec.put("points", pts);
            boolean money = pts.stream().anyMatch(x -> looksMoney(String.valueOf(x.get("text"))));
            return "<div class=\"ask-chart\"" + (money ? " data-priv-chart" : "")
                 + " data-ask-chart=\"" + escape(JSON.writeValueAsString(spec)) + "\"></div>";
        } catch (Exception e) {
            return "";                                        // JSON 不合法 → 静默跳过,不把报错甩给用户
        }
    }

    /**
     * 自由 HTML。
     *
     * <h3>为什么敢让模型写 HTML</h3>
     * <p>因为它跑在 {@code sandbox} 且<b>不给 {@code allow-same-origin}</b> 的 iframe 里 ——
     * 那是一个 opaque origin:脚本能跑,但读不到我们的 cookie、DOM、localStorage,
     * 也发不出带凭据的请求。这与 Claude Artifacts 是同一个隔离手法。</p>
     *
     * <p><b>数字仍然走引用</b>:注入前把 {@code {{cite:cN}}} 换成真实数值,
     * 所以图里的数和正文里的是同一份。模型在这里能自由发挥的是<b>形式</b>,不是数据。</p>
     *
     * <p>顺带注入本地 Chart.js 与一套纸感基础样式:让模型不必写 CDN 链接
     * (自托管用户很多在墙内,外链图表库会直接白屏),也不必每次重复描述配色。</p>
     */
    private String artifact(String html, Map<String, AskCitation> byKey) {
        // 只吐**容器**,iframe 由 ask-charts.js 组装 —— 它同时服务流式渲染那条路径。
        // 两边各拼一次 srcdoc 的话,注进去的样式与脚本迟早漂移,
        // 表现就是「流完刷新一下,图变了个样」。脚手架只能有一处。
        return "<figure class=\"ask-artifact\" data-ask-artifact=\""
             + escape(substituteCites(html, byKey)) + "\"></figure>";
    }

    /** 把 {{cite:cN}} 换成真实数值(自由 HTML 与复制都用它) */
    private String substituteCites(String text, Map<String, AskCitation> byKey) {
        Matcher m = CITE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            AskCitation c = byKey.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(c == null ? "" : c.getValueText()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 从格式化后的数值里取回数字(「¥1,234,568」→ 1234568)。
     *
     * <p>为什么不另存一列原始数值:图表只需要相对大小,四舍五入到元完全够用;
     * 而加一列要动表、动 mapper、动落库路径,为一个纯展示的需求不值得。
     * 解析失败就返回 null,那个点被丢掉 —— 失败是安全的方向。</p>
     */
    static Double numeric(String valueText) {
        if (valueText == null) return null;
        String t = valueText.replaceAll("[^0-9.\\-]", "");
        if (t.isEmpty() || t.equals("-") || t.equals(".")) return null;
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    /** 行内残留的标记:退成一个紧凑 chip,不至于整句话中间插一张卡 */
    private String inline(String line, Map<String, AskCitation> byKey) {
        StringBuilder sb = new StringBuilder();
        Matcher m = CITE.matcher(line);
        while (m.find()) {
            AskCitation c = byKey.get(m.group(1));
            String rep = c == null ? "" :
                    "<a class=\"ask-chip\"" + (looksMoney(c.getValueText()) ? " data-priv" : "")
                  + " href=\"" + escape(href(c)) + "\" title=\""
                  + escape(c.getExplain()) + "\">" + escape(c.getValueText())
                  + (c.isInProgress() ? "<i>未关账</i>" : "") + "</a>";
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        // 极简 markdown:模型输出里常见的就 **粗体**,不为它引一个解析器
        return sb.toString().replaceAll("\\*\\*([^*]{1,80})\\*\\*", "<strong>$1</strong>");
    }

    /**
     * 一张引用卡:指标名 + 数值 + 账期与关账状态 + 点回原页。
     *
     * <p>四样缺一不可。缺指标名,用户不知道这是什么;缺账期,数字说不清自己是哪一期的;
     * 缺关账状态,进行中的期会被当成定论;缺链接,用户没法自己核 —— 而「能自己核」
     * 是这个功能敢让 AI 碰资产数据的前提。</p>
     */
    private String card(long familyId, AskCitation c) {
        String per = periodLabel(familyId, c.getPeriodId());
        StringBuilder s = new StringBuilder();
        // 完整口径进 title —— 用户想知道「这怎么算的」时才看,不占卡片版面
        s.append("<a class=\"ask-cite\" href=\"").append(escape(href(c)))
         .append("\" title=\"").append(escape(c.getExplain())).append("\">");
        s.append("<span class=\"ask-cite-top\"><span class=\"ask-cite-k\">")
         .append(escape(c.getLabel())).append("</span><span class=\"ask-cite-v\"")
         .append(looksMoney(c.getValueText()) ? " data-priv" : "").append(">")
         .append(escape(c.getValueText())).append("</span></span>");
        s.append("<span class=\"ask-cite-meta\"><span class=\"ask-cite-per\">");
        if (per != null) s.append(escape(per));
        if (c.isInProgress()) s.append(per == null ? "未关账" : " · 未关账");
        s.append("</span><span class=\"ask-cite-go\">").append(escape(goOf(c)))
         .append("</span></span>");
        s.append("</a>");
        return s.toString();
    }

    /** 去向短标签;口径表里没有就只写「去核对」——总比空着强 */
    private static String goOf(AskCitation c) {
        Meta m = lookup(c.getMetricKey());
        return m == null || m.go() == null ? "→ 去核对" : m.go();
    }

    /**
     * 这个值是不是金额。
     *
     * <p>隐私模式要糊的是<b>金额</b>,不是所有数字。百分比、月数、条数都不敏感 ——
     * 一刀切糊掉的后果实测很难看:饼图上的百分比清清楚楚,而正文里同一批百分比是糊的,
     * 自己跟自己矛盾。</p>
     */
    public boolean isMoney(String v) { return looksMoney(v); }

    static boolean looksMoney(String valueText) {
        return valueText != null && valueText.matches(".*[¥$€£].*");
    }

    private static String href(AskCitation c) {
        return c.getTargetHref() == null || c.getTargetHref().isBlank() ? "#" : c.getTargetHref();
    }

    /**
     * 正文里有没有裸金额 —— 有就说明模型没按规矩用引用块。
     *
     * <p>不拦截、不重写,只标灰并提示「这个数没有出处,去页面上核一下」。
     * 拦下来重问一次成本翻倍,而且它多半还会再犯;标出来让用户知道哪个数可信、
     * 哪个数要自己核,比假装没发生有用。护栏 {@code v119-ASK-NO-BARE-NUMBER} 盯着这条。</p>
     */
    public boolean hasBareNumber(String body) {
        if (body == null || body.isBlank()) return false;
        String withoutCites = CITE.matcher(body).replaceAll("");
        return BARE_MONEY.matcher(withoutCites).find();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
