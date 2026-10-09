package com.family.finance.service.penetration;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.30 · 天天基金接口的解析(护栏 v130-NAV-FETCH-LOUD / v130-MMF-PAGED / v130-CATALOG-PARITY)。
 *
 * <p>报文都照 2026-10-08 在 beta 上实测的原样(tech-design/v1.30.md §零 / §四.3),只截掉无关字段。
 * 要钉住的是四种「HTTP 200 但没数据 / 数据换了含义」—— 它们读起来完全通顺,不判成失败就会静默算错。</p>
 */
class FundNavParseTest {

    // ---------- ⑤ 单位净值 ----------

    @Test
    void 正常的单位净值() {
        var f = EastMoneyFundClient.parseLsjzLatest("""
                {"Data":{"LSJZList":[{"FSRQ":"2026-09-30","DWJZ":"4.7800","LJJZ":"5.0203","SGZT":"开放申购","SHZT":"开放赎回","FHSP":""}],
                 "FundType":"002","SYType":null,"isNewType":false,"Feature":"215"},"ErrCode":0,"ErrMsg":null,"TotalCount":2368}""");
        assertThat(f.ok()).isTrue();
        assertThat(f.navDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(f.unitNav()).isEqualByComparingTo("4.78");
        assertThat(f.source()).isEqualTo("eastmoney-lsjz");
    }

    @Test
    void 不带Referer_ErrCode负999_判失败() {
        var f = EastMoneyFundClient.parseLsjzLatest("{\"Data\":\"\",\"ErrCode\":-999,\"ErrMsg\":\"\",\"TotalCount\":0}");
        assertThat(f.ok()).isFalse();
        assertThat(f.error()).contains("拒绝").contains("-999");
    }

    @Test
    void 查无此码_ErrCode0加空列表_判失败() {
        var f = EastMoneyFundClient.parseLsjzLatest("""
                {"Data":{"LSJZList":[],"FundType":"","SYType":null,"isNewType":false,"Feature":null},"ErrCode":0,"ErrMsg":null,"TotalCount":0}""");
        assertThat(f.ok()).isFalse();
        assertThat(f.error()).contains("查不到");
    }

    @Test
    void 货币基金的DWJZ是万份收益_绝不当单位净值() {
        // 余额宝 000198:DWJZ = 0.2252 其实是每万份收益。当净值用,10 万元会被算成 2.25 万元
        var f = EastMoneyFundClient.parseLsjzLatest("""
                {"Data":{"LSJZList":[{"FSRQ":"2026-10-07","DWJZ":"0.2252","LJJZ":"0.8260"}],"FundType":"005","SYType":"每万份收益"},
                 "ErrCode":0,"TotalCount":4877}""");
        assertThat(f.ok()).isFalse();
        assertThat(f.error()).contains("每万份收益");
    }

    @Test
    void 连不上_或格式看不懂_判失败() {
        assertThat(EastMoneyFundClient.parseLsjzLatest(null).ok()).isFalse();
        assertThat(EastMoneyFundClient.parseLsjzLatest("<!doctype html><title>页面未找到</title>").ok()).isFalse();
        assertThat(EastMoneyFundClient.parseLsjzLatest("""
                {"Data":{"LSJZList":[{"FSRQ":"2026-09-30","DWJZ":"0"}],"SYType":null},"ErrCode":0}""").ok()).isFalse();
    }

    @Test
    void pingzhongdata备源_取最后一点_日期按东八区() {
        String js = "/*2026-10-08 12:17:31*/var ishb=false;var fS_name = \"广发多因子混合\";"
                + "var Data_netWorthTrend = [{\"x\":1371139200000,\"y\":1.0,\"equityReturn\":0,\"unitMoney\":\"\"},"
                + "{\"x\":1790697600000,\"y\":4.78,\"equityReturn\":0.08,\"unitMoney\":\"\"}];var Data_ACWorthTrend = [];";
        var f = EastMoneyFundClient.parsePzLatest(js);
        assertThat(f.ok()).isTrue();
        assertThat(f.navDate()).as("1790697600000 = 2026-09-30 00:00 CST").isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(f.unitNav()).isEqualByComparingTo("4.78");
        assertThat(f.source()).isEqualTo("eastmoney-pz");
    }

    @Test
    void pingzhongdata货币基金_不认() {
        assertThat(EastMoneyFundClient.parsePzLatest("var ishb=true;var Data_millionCopiesIncome = [];").ok()).isFalse();
        assertThat(EastMoneyFundClient.parsePzLatest("").ok()).isFalse();
    }

    // ---------- ⑥ 货币基金逐日万份收益 ----------

    @Test
    void 万份收益一页_带TotalCount() {
        var p = EastMoneyFundClient.parseLsjzIncomePage("""
                {"Data":{"LSJZList":[{"FSRQ":"2026-10-07","DWJZ":"0.2252","LJJZ":"0.8260"},{"FSRQ":"2026-10-06","DWJZ":"0.2252","LJJZ":"0.8290"}],
                 "FundType":"005","SYType":"每万份收益"},"ErrCode":0,"TotalCount":37}""");
        assertThat(p.error()).isNull();
        assertThat(p.total()).as("pageSize 静默封顶 20:总数要靠 TotalCount 核").isEqualTo(37);
        assertThat(p.days()).hasSize(2);
        assertThat(p.days().get(0).per10k()).isEqualByComparingTo("0.2252");
        assertThat(p.days().get(0).yield7d()).isEqualByComparingTo("0.8260");
    }

    @Test
    void 不是万份收益的基金_不能按货基结转() {
        var p = EastMoneyFundClient.parseLsjzIncomePage("""
                {"Data":{"LSJZList":[{"FSRQ":"2026-09-30","DWJZ":"4.7800","LJJZ":"5.0203"}],"SYType":null},"ErrCode":0,"TotalCount":1}""");
        assertThat(p.error()).contains("不是每万份收益");
        var denied = EastMoneyFundClient.parseLsjzIncomePage("{\"Data\":\"\",\"ErrCode\":-999}");
        assertThat(denied.error()).contains("-999");
    }

    // ---------- 测试基址只认回环 ----------

    @Test
    void e2e基址只认本机回环() {
        assertThat(EastMoneyFundClient.normalizeBase("http://127.0.0.1:39999/")).isEqualTo("http://127.0.0.1:39999");
        assertThat(EastMoneyFundClient.normalizeBase("http://localhost:8080")).isEqualTo("http://localhost:8080");
        assertThat(EastMoneyFundClient.normalizeBase("https://evil.example.com")).isEmpty();
        assertThat(EastMoneyFundClient.normalizeBase("http://10.0.0.5")).isEmpty();
        assertThat(EastMoneyFundClient.normalizeBase(null)).isEmpty();
    }

    // ---------- ① 代码表:收口后名↔码匹配与 v1.5 逐条一致(v130-CATALOG-PARITY)----------

    private static final String CATALOG_SAMPLE = "var r = ["
            + "[\"000001\",\"HXCZHH\",\"华夏成长混合\",\"混合型-灵活\",\"HUAXIACHENGZHANGHUNHE\"],"
            + "[\"000002\",\"HXCZHH\",\"华夏成长混合(后端)\",\"混合型-灵活\",\"HUAXIACHENGZHANGHUNHE\"],"
            + "[\"002943\",\"GFDYZHH\",\"广发多因子混合\",\"混合型-灵活\",\"GUANGFADUOYINZIHUNHE\"],"
            + "[\"270042\",\"GFNSDK100ETFLJRMBQDIIA\",\"广发纳斯达克100ETF联接人民币(QDII)A\",\"指数型-海外股票\",\"GUANGFA\"],"
            + "[\"000055\",\"GFNSDK100ETFLJMYQDIIA\",\"广发纳斯达克100ETF联接美元(QDII)A\",\"指数型-海外股票\",\"GUANGFA\"],"
            + "[\"000198\",\"THYEBHB\",\"天弘余额宝货币\",\"货币型-普通货币\",\"TIANHONGYUEBAOHUOBI\"],"
            + "[\"007708\",\"ZYRFFDJZXHBA\",\"中银瑞福浮动净值型货币A\",\"货币型-浮动净值\",\"ZHONGYIN\"],"
            + "[\"005156\",\"JSLHZCPZHHA\",\"嘉实领航资产配置混合A\",\"FOF-稳健型\",\"JIASHI\"],"
            + "[\"110020\",\"YFDHS300ETFLJA\",\"易方达沪深300ETF联接A\",\"指数型-股票\",\"YIFANGDA\"],"
            + "[\"000084\",\"BSAYZQA\",\"博时安盈债券A\",\"债券型-中短债\",\"BOSHI\"],"
            + "[\"000085\",\"BSAYZQC\",\"博时安盈债券C\",\"债券型-中短债\",\"BOSHI\"]"
            + "];";

    /** v1.5 的原实现(照抄):正则只取代码与名称,按顺序 putIfAbsent */
    private static Map<String, String> v15NameIndex(String js) {
        Map<String, String> m = new java.util.HashMap<>();
        int a = js.indexOf('['), z = js.lastIndexOf(']');
        Matcher em = Pattern.compile("\\[\"(\\d{6})\",\"[^\"]*\",\"([^\"]+)\",\"([^\"]*)\"").matcher(js.substring(a, z));
        while (em.find()) m.putIfAbsent(EastMoneyFundClient.normName(em.group(2)), em.group(1));
        return m;
    }

    @Test
    void 代码表收口后_名到码索引与v15逐条相同() {
        List<EastMoneyFundClient.FundInfo> list = EastMoneyFundClient.parseCatalog(CATALOG_SAMPLE);
        assertThat(list).hasSize(11);
        assertThat(list.get(5)).isEqualTo(new EastMoneyFundClient.FundInfo("000198", "THYEBHB", "天弘余额宝货币", "货币型-普通货币", "TIANHONGYUEBAOHUOBI"));
        assertThat(EastMoneyFundClient.buildNameIndex(list)).isEqualTo(v15NameIndex(CATALOG_SAMPLE));
    }
}
