package com.family.finance.calc.lens;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.1 · Pivot 引擎纯函数测试(v11-LENS 护栏)。
 * 覆盖:单/双维分组 · 小计=总计恒等 · 未分类沉底 · 筛选 · 收益归因诚实降级(持仓级维度)·
 * 占比/收益率币种不变性 + 金额按 fx 缩放(承 CurrencyInvariance 属性护栏)。
 */
class PivotEngineTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    /** k = 视图币种因子(金额×k 模拟另一币种视图;比值必须不变) */
    private static List<Position> fixture(BigDecimal k) {
        // 账户1 现金(手填·低风险·招行·迪娃):100000
        Position cash = new Position(1L, null, "招行储蓄卡", "招行储蓄卡", bd("100000").multiply(k),
                "现金", "低风险", "灵活取用", "CNY", "迪娃",
                "现金及等价", "招商银行", null, null, "应急金", "随时可取",
                bd("0").multiply(k), bd("0").multiply(k), bd("100000").multiply(k), bd("0").multiply(k), bd("100000").multiply(k),
                "招行储蓄卡");   // v1.20 · 零组态:账户组维值 = 账户名
        // 账户2 股票(持仓×2·高风险·富途·迪娃):宁德 60000(新能源) + 茅台 40000(消费)
        Position h1 = new Position(2L, 21L, "宁德时代", "富途证券账户", bd("60000").multiply(k),
                "股票", "高风险", "半灵活", "CNY", "迪娃",
                "权益", "富途证券", "新能源电力", "A股", "长期增值", "自己盯",
                bd("5000").multiply(k), bd("20000").multiply(k), bd("80000").multiply(k), bd("15000").multiply(k), bd("95000").multiply(k),
                "富途证券账户");
        Position h2 = new Position(2L, 22L, "贵州茅台", "富途证券账户", bd("40000").multiply(k),
                "股票", "高风险", "半灵活", "CNY", "迪娃",
                "权益", "富途证券", "白酒消费", "A股", "长期增值", "自己盯",
                bd("5000").multiply(k), bd("20000").multiply(k), bd("80000").multiply(k), bd("5000").multiply(k), bd("95000").multiply(k),
                "富途证券账户");
        // 账户3 理财(手填·未打标行业·支付宝·妻子):100000
        Position wealth = new Position(3L, null, "蚂蚁理财", "蚂蚁理财", bd("100000").multiply(k),
                "理财", "中风险", "半灵活", "CNY", "妻子",
                "固收", "支付宝 · 蚂蚁财富", null, null, "教育金", "交给产品",
                bd("1000").multiply(k), bd("3000").multiply(k), bd("97000").multiply(k), bd("3000").multiply(k), bd("99000").multiply(k),
                "蚂蚁理财");
        return List.of(cash, h1, h2, wealth);
    }

    @Test
    void singleDim_groupsAndSharesSumTo100() {
        var r = PivotEngine.pivot(fixture(BigDecimal.ONE),
                new LensQuery(List.of("risk"), List.of(), List.of("value", "share"), Map.of()));
        assertThat(r.grand().get(0)).isEqualByComparingTo("300000");
        // 高 100000 / 低 100000 / 中 100000 → 各 33.33%,按金额降序
        BigDecimal shareSum = r.rowTotals().stream().map(t -> t.get(1)).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(shareSum.doubleValue()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void crossDim_rowTotalsEqualGrand() {
        var r = PivotEngine.pivot(fixture(BigDecimal.ONE),
                new LensQuery(List.of("risk"), List.of("assetClass"), List.of("value"), Map.of()));
        BigDecimal rowSum = r.rowTotals().stream().map(t -> t.get(0)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal colSum = r.colTotals().stream().map(t -> t.get(0)).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(rowSum).isEqualByComparingTo(r.grand().get(0));   // 行小计和 = 总计
        assertThat(colSum).isEqualByComparingTo(r.grand().get(0));   // 列小计和 = 总计
    }

    @Test
    void unclassifiedSinksToBottom() {
        var r = PivotEngine.pivot(fixture(BigDecimal.ONE),
                new LensQuery(List.of("industry"), List.of(), List.of("value"), Map.of()));
        var keys = r.rowKeys().stream().map(x -> x.get(0)).toList();
        assertThat(keys.get(keys.size() - 1)).isEqualTo(PivotEngine.UNCLASSIFIED);  // 未分类沉底
        assertThat(r.holdingLevelSplit()).isTrue();                                  // 行业=持仓级维度
    }

    @Test
    void filterNarrowsScope_andShareIsWithinScope() {
        var r = PivotEngine.pivot(fixture(BigDecimal.ONE),
                new LensQuery(List.of("industry"), List.of(), List.of("value", "share"),
                        Map.of("assetClass", List.of("权益"))));
        assertThat(r.grand().get(0)).isEqualByComparingTo("100000");   // 只剩股票账户两持仓
        // 新能源 60000 → 占权益 60%
        assertThat(r.rowKeys().get(0).get(0)).isEqualTo("新能源电力");
        assertThat(r.rowTotals().get(0).get(1)).isEqualByComparingTo("60.00");
    }

    @Test
    void accountLevelDims_pnlIsExactAndDeduped() {
        // owner 维度(账户级):迪娃 = 账户1+账户2 · 账户2 两头寸只计一次 latestPnl=0+5000
        var r = PivotEngine.pivot(fixture(BigDecimal.ONE),
                new LensQuery(List.of("owner"), List.of(), List.of("value", "latestPnl", "cumPnl", "cumReturn"), Map.of()));
        assertThat(r.holdingLevelSplit()).isFalse();
        int diwa = r.rowKeys().indexOf(List.of("迪娃"));
        assertThat(r.rowTotals().get(diwa).get(1)).isEqualByComparingTo("5000");    // 去重后精确
        assertThat(r.rowTotals().get(diwa).get(2)).isEqualByComparingTo("20000");   // cumPnl 去重
        // 累计收益率 = 20000 / (100000+80000) = 11.11%
        assertThat(r.rowTotals().get(diwa).get(3)).isEqualByComparingTo("11.11");
    }

    @Test
    void holdingLevelDims_pnlDegradesHonestly() {
        // 行业维度拆开账户2:latestPnl/cumReturn → null;cumPnl 用持有口径(市值−成本)
        var r = PivotEngine.pivot(fixture(BigDecimal.ONE),
                new LensQuery(List.of("industry"), List.of(), List.of("value", "latestPnl", "cumPnl", "cumReturn"), Map.of()));
        int ne = r.rowKeys().indexOf(List.of("新能源电力"));
        assertThat(r.rowTotals().get(ne).get(1)).isNull();                          // 不可归因 → null,不假分摊
        assertThat(r.rowTotals().get(ne).get(2)).isEqualByComparingTo("15000");     // 持有口径
        assertThat(r.rowTotals().get(ne).get(3)).isNull();
    }

    @Test
    void currencyInvariance_ratiosEqual_amountsScale() {
        BigDecimal k = bd("6.774");
        var cny = PivotEngine.pivot(fixture(BigDecimal.ONE),
                new LensQuery(List.of("risk"), List.of("assetClass"), List.of("value", "share", "cumReturn"), Map.of()));
        var usd = PivotEngine.pivot(fixture(k),
                new LensQuery(List.of("risk"), List.of("assetClass"), List.of("value", "share", "cumReturn"), Map.of()));
        // 比值类:占比 / 累计收益率 三币种完全相等
        for (int i = 0; i < cny.rowTotals().size(); i++) {
            assertThat(usd.rowTotals().get(i).get(1)).as("占比币种无关")
                    .isEqualByComparingTo(cny.rowTotals().get(i).get(1));
            BigDecimal c = cny.rowTotals().get(i).get(2), u = usd.rowTotals().get(i).get(2);
            if (c == null) assertThat(u).isNull();
            else assertThat(u).as("累计收益率币种无关").isEqualByComparingTo(c);
        }
        // 金额类:按 fx 因子精确缩放
        assertThat(usd.grand().get(0))
                .isEqualByComparingTo(cny.grand().get(0).multiply(k).setScale(2, java.math.RoundingMode.HALF_EVEN));
    }

    @Test
    void registryCompleteness_everyDimensionExtractsAndLabels() {
        Position p = fixture(BigDecimal.ONE).get(1);
        LensRegistry.DIMENSIONS.values().forEach(d -> {
            assertThat(d.label()).isNotBlank();
            d.extract().apply(p);   // 不抛即可(null=未分类合法)
        });
        assertThat(LensRegistry.MEASURES).hasSize(7);
        assertThat(LensRegistry.MEASURES).containsKeys("value", "netPrincipal", "share",
                "latestPnl", "latestReturn", "cumPnl", "cumReturn");   // v1.3 · 新增 净投入 / 本期收益率
        assertThat(LensRegistry.DIMENSIONS).hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void v13NewMeasures_netPrincipalSums_latestReturnAggregatesByOpenValue() {
        // 按风险聚合:净投入 = Σ账户净投入 · 本期收益率 = Σ本期收益 / Σ期初市值(账户级去重)
        var r = PivotEngine.pivot(fixture(BigDecimal.ONE),
                new LensQuery(List.of("owner"), List.of(), List.of("netPrincipal", "latestReturn"), Map.of()));
        int diwa = r.rowKeys().indexOf(List.of("迪娃"));   // 账户1(净投入10万·期初10万·本期0)+ 账户2(净投入8万·期初9.5万·本期0.5万)
        assertThat(r.rowTotals().get(diwa).get(0)).isEqualByComparingTo("180000");   // 净投入 10万+8万
        // 本期收益率 = (0 + 5000) / (100000 + 95000) × 100 = 2.56%
        assertThat(r.rowTotals().get(diwa).get(1)).isEqualByComparingTo("2.56");
    }
}
