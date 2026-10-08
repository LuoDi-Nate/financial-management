package com.family.finance.service.fund;

import com.family.finance.domain.account.AccountType;
import com.family.finance.service.penetration.EastMoneyFundClient.FundInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.30 · 搜索排序 + 分类(护栏 v130-NO-FOREIGN-SHARECLASS)+ 哪些账户能加场外基金(v130-ENTRY-BY-TYPE)。
 */
class FundCatalogTest {

    private static FundInfo f(String code, String abbr, String name, String type) {
        return new FundInfo(code, abbr, name, type, abbr);
    }

    private static final List<FundInfo> ALL = List.of(
            f("002943", "GFDYZHH", "广发多因子混合", "混合型-灵活"),
            f("270042", "GFNSDK100ETFLJRMBQDIIA", "广发纳斯达克100ETF联接人民币(QDII)A", "指数型-海外股票"),
            f("000055", "GFNSDK100ETFLJMYQDIIA", "广发纳斯达克100ETF联接美元(QDII)A", "指数型-海外股票"),
            f("000198", "THYEBHB", "天弘余额宝货币", "货币型-普通货币"),
            f("007708", "ZYRFFDJZXHBA", "中银瑞福浮动净值型货币A", "货币型-浮动净值"),
            f("005156", "JSLHZCPZHHA", "嘉实领航资产配置混合A", "FOF-稳健型"),
            f("000044", "JSMGCZGPMYXH", "嘉实美国成长股票美元现汇", "QDII-普通股票"),
            f("002944", "XX", "某基金 C", "债券型-中短债"));

    @Test
    void 分类_货基走结转_浮动净值货基与外币份额不支持() {
        assertThat(FundCatalog.classify(ALL.get(0)).kind()).isEqualTo(FundCatalog.Kind.NAV);
        assertThat(FundCatalog.classify(ALL.get(3)).kind()).isEqualTo(FundCatalog.Kind.MMF);
        var floating = FundCatalog.classify(ALL.get(4));
        assertThat(floating.supported()).isFalse();
        assertThat(floating.reason()).contains("浮动净值");
        var usd = FundCatalog.classify(ALL.get(2));
        assertThat(usd.supported()).as("名称里有「美元」:接口没有币种字段,只能靠名字认").isFalse();
        assertThat(usd.reason()).contains("美元");
        assertThat(FundCatalog.classify(ALL.get(6)).supported()).isFalse();
        assertThat(FundCatalog.classify(f("1", "", "某港币份额", "QDII-普通股票")).supported()).isFalse();
    }

    @Test
    void 晚一个交易日_覆盖QDII与FOF() {
        assertThat(FundCatalog.classify(ALL.get(1)).lateNav()).as("名称带 QDII").isTrue();
        assertThat(FundCatalog.classify(ALL.get(5)).lateNav()).as("FOF 实测也晚一天").isTrue();
        assertThat(FundCatalog.classify(ALL.get(0)).lateNav()).isFalse();
    }

    @Test
    void 搜索_代码前缀_名称_拼音缩写() {
        assertThat(FundCatalog.rank(ALL, "002943")).extracting(FundInfo::code).containsExactly("002943");
        assertThat(FundCatalog.rank(ALL, "00294")).extracting(FundInfo::code).containsExactly("002943", "002944");
        assertThat(FundCatalog.rank(ALL, "广发")).extracting(FundInfo::code).startsWith("002943");
        assertThat(FundCatalog.rank(ALL, "gfdy")).extracting(FundInfo::code).containsExactly("002943");
        assertThat(FundCatalog.rank(ALL, "余额宝")).extracting(FundInfo::code).containsExactly("000198");
    }

    @Test
    void 搜索_最多20条() {
        List<FundInfo> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) many.add(f(String.format("1%05d", i), "A", "基金" + i, "混合型-灵活"));
        assertThat(FundCatalog.rank(many, "1")).hasSize(FundCatalog.SEARCH_LIMIT);
    }

    /** PRD §3.1 的表,逐个账户类型 × 币种 */
    @Test
    void 哪些账户的添加持仓里有场外基金() {
        for (AccountType t : AccountType.values()) {
            boolean expect = t == AccountType.FUND || t == AccountType.STOCK || t == AccountType.WEALTH || t == AccountType.CASH;
            assertThat(FundHoldingService.accountAllowsFunds(t, "CNY")).as(t + " · CNY").isEqualTo(expect);
            assertThat(FundHoldingService.accountAllowsFunds(t, "USD")).as(t + " · USD(本版只做人民币)").isFalse();
        }
    }
}
