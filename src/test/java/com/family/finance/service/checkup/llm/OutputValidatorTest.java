package com.family.finance.service.checkup.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 综合诊断 OutputValidator 单测 · v0.4.7 放宽(2026-05-14)
 *
 * 校验策略:
 *   1. 长度 150-700 中文字符
 *   2. 担保性话术拒绝(保证 / 稳赚 / 一定能 / 必然 / 零风险 / 包赚 ...)— 合规底线
 *   3. 具体产品名 / 股票代码拒绝(余额宝 / 510300 / 茅台 / 6 位数代码)+ 账户名白名单
 *   4. 真名泄露拒绝(LLM 输出含 length ≥ 3 的原始成员真名)
 *   5. 至少一个金融术语
 *
 * v0.4.7 放宽:删「古典中式词」+ 删「过度客套(您 > 2 次)」· 真名 length ≥ 2 → ≥ 3
 */
class OutputValidatorTest {

    private static final Set<String> NO_NAMES = Set.of();
    private static final Set<String> NAMES_2 = Set.of("Alice", "Bob");

    /** 一段健康综合诊断模板,长度足、含金融术语、无禁词,用于"基础通过"测试 */
    private static final String VALID_DIAGNOSE =
            "整体配置偏稳健:全家流动性月数充裕,但权益类年化跑输基准约 0.5pp,集中度方面单账户占比偏高。" +
            "结合本期命中的 LIQ-2 与 RET-3 两条规则看,问题在于流动性配置过厚而风险敞口未充分利用。" +
            "建议把超额流动性的部分本金,通过分批方式再配置至跟踪基准的指数型仓位,降低主动选股偏差," +
            "同时关注短期回撤恢复节奏,逐步把加权年化拉回基准水平。后续可结合再平衡周期评估调整。";

    @Test
    void acceptsValidDiagnose() {
        var r = OutputValidator.check(VALID_DIAGNOSE, NO_NAMES);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void rejectsEmpty() {
        var r = OutputValidator.check("", NO_NAMES);
        assertThat(r.accepted()).isFalse();
    }

    @Test
    void rejectsBlank() {
        var r = OutputValidator.check("    \n  ", NO_NAMES);
        assertThat(r.accepted()).isFalse();
    }

    @Test
    void rejectsTooShort() {
        var r = OutputValidator.check("整体配置偏稳健,流动性月数较高,建议再平衡。", NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("过短");
    }

    @Test
    void rejectsTooLong() {
        // v0.4.7 起 MAX_LEN 1500 · 100 次 21 字 = 2116 字 · 妥妥超过
        String tooLong = "整体配置偏稳健,流动性配置充裕。" + "建议适度再平衡风险敞口降低主动选股偏差。".repeat(100);
        var r = OutputValidator.check(tooLong, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("过长");
    }

    @Test
    void rejectsGuaranteePhrase() {
        String bad = VALID_DIAGNOSE.replace("逐步把加权年化拉回基准水平", "保证年化收益率达 8%");
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("担保性");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "历史收益和物价变化不保证未来结果。",
            "本测算仅供参考，不保证按期退休。",
            "无法保证如期达成目标"
    })
    void onlyGoalMonthlyReportAcceptsCompleteNegativeRiskNotes(String note) {
        String report = VALID_DIAGNOSE + note;
        assertThat(OutputValidator.checkGoalMonthlyReport(report, NO_NAMES).accepted()).isTrue();
        assertThat(OutputValidator.check(report, NO_NAMES).reason()).contains("担保性");
        assertThat(OutputValidator.checkInsight(report, NO_NAMES).reason()).contains("担保性");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "保证未来收益。",
            "并非不保证未来收益。",
            "不能不保证如期退休。",
            "不能保证未来收益低于预期。",
            "历史收益不保证未来回报，但本金有保证。",
            "保证按期退休。",
            "本金保底。"
    })
    void goalMonthlyReportRejectsPromisesEvenBesideARiskNote(String claim) {
        String report = "本报告不保证未来收益。\n" + VALID_DIAGNOSE + claim;
        assertThat(OutputValidator.checkGoalMonthlyReport(report, NO_NAMES).reason()).contains("担保性");
    }

    @Test
    void goalMonthlyReportStillChecksEntireOriginalTextForOtherViolations() {
        String report = VALID_DIAGNOSE + "历史收益不保证未来收益。";
        assertThat(OutputValidator.checkGoalMonthlyReport(report + "推荐余额宝。", NO_NAMES).reason()).contains("产品名");
        assertThat(OutputValidator.checkGoalMonthlyReport(report + "向Alice说明。", NAMES_2).reason()).contains("真名");
        assertThat(OutputValidator.checkGoalMonthlyReport("不保证未来收益。", NO_NAMES).reason()).contains("过短");
    }

    @Test
    void allowsArchaicPhrase_v047() {
        // v0.4.7:古典中式词扫描已删 · 「师傅」「家底」不再 reject
        String text = VALID_DIAGNOSE.replace("整体配置偏稳健", "整体看,师傅这个家底配置偏稳健");
        var r = OutputValidator.check(text, NO_NAMES);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void rejectsProductName() {
        String bad = VALID_DIAGNOSE.replace("跟踪基准的指数型仓位", "余额宝或者 510300 这种 ETF");
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("产品名");
    }

    @Test
    void rejectsAStockCode() {
        // 600519 是茅台的代码,纯 6 位数字必拦
        String bad = VALID_DIAGNOSE.replace("跟踪基准的指数型仓位", "可以考虑 600519 这只标的");
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("产品名");
    }

    @Test
    void rejectsRealNameLeak() {
        // LLM 输出含真名(成员代号映射理论上避免,但作防御深度)
        String bad = VALID_DIAGNOSE.replace("结合本期命中", "建议Alice把多余现金调到货币基金,结合本期命中");
        var r = OutputValidator.check(bad, NAMES_2);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("真名");
    }

    @Test
    void allowsCodenameMembers() {
        // 输出含「成员A」是预期的,不该拦
        String good = VALID_DIAGNOSE.replace("结合本期命中", "建议成员A 把多余现金再配置,结合本期命中");
        var r = OutputValidator.check(good, NAMES_2);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void allowsExcessiveFormality_v047() {
        // v0.4.7:过度客套(您 > 2 次)扫描已删 · 不再 reject
        String text = VALID_DIAGNOSE.replace("整体配置偏稳健", "您好,您家整体配置偏稳健,请您注意");
        var r = OutputValidator.check(text, NO_NAMES);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void rejectsNoFinanceTerm() {
        // 一段够长(>= 150 字)但不含任何金融术语 — 模拟 LLM 跑题成"心灵鸡汤"
        String bad = "生活总有起起落落,心情平静最重要。重要的不是赚多少,而是心安。" +
                "每个人都该过自己想要的生活,不必和别人去比较攀比，按自己的节奏过日子最实在。" +
                "保持初心,简单生活,顺其自然。今天的天气真好,适合出门散步,放松心情。" +
                "工作之余多陪陪家人，多看看孩子，多听听父母，这才是人生最大的精神财富，" +
                "千万不要因为忙碌而忽视身边的美好瞬间和点点滴滴的温暖。";
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("金融术语");
    }

    @Test
    void acceptsTwoYouOrLess() {
        // "您"出现 2 次以下应通过(不算过度客套)
        String ok = VALID_DIAGNOSE.replace("整体配置偏稳健", "整体配置偏稳健,您可参考");
        var r = OutputValidator.check(ok, NO_NAMES);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void shortNameNotConsideredLeak() {
        // v0.4.7:< 3 字真名不挡 · 防「张三/李四/萝卜」常用词组合误杀(prod 反馈)
        // 「萝卜」单独 2 字 · 即使在 realNames 集合,VALID_DIAGNOSE 含「萝卜」也不该拦
        Set<String> twoCharName = Set.of("萝卜");
        String withCarrot = VALID_DIAGNOSE.replace("超额流动性的部分本金", "超额流动性的萝卜白菜消费");
        var r = OutputValidator.check(withCarrot, twoCharName);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void rejectsLongRealName_v047() {
        // v0.4.7:length ≥ 3 的真名仍然 reject(如「张志强」「王萝卜」3 字以上)
        Set<String> longName = Set.of("王萝卜");
        String bad = VALID_DIAGNOSE.replace("结合本期命中", "建议王萝卜把多余现金调到货币基金,结合本期命中");
        var r = OutputValidator.check(bad, longName);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("真名");
    }

    @Test
    void emptyRealNamesSkipsScan_v047() {
        // v0.4.7:rebalance 路径 prompt 端不传真名,caller 应传空集合完全跳过真名扫描
        // 即使输出包含任何字符串,只要 realNames=空 就不扫描
        Set<String> empty = Set.of();
        String withName = VALID_DIAGNOSE.replace("结合本期命中", "建议王萝卜把多余现金,结合本期命中");
        var r = OutputValidator.check(withName, empty);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void amountNotMisreadAsStockCode_v049() {
        // v0.4.9:¥120526 / 2026 年这类 6 位数字不该被当 A 股代码
        String text = VALID_DIAGNOSE.replace("跟踪基准的指数型仓位",
                "建议每年¥120526 转入指数型仓位");
        var r = OutputValidator.check(text, NO_NAMES);
        assertThat(r.accepted()).isTrue();
    }

    @Test
    void stillRejectsStandaloneStockCode_v049() {
        // 真正 A 股代码(无 ¥ 前缀 · 无单位后缀)仍 reject · 合规底线
        String bad = VALID_DIAGNOSE.replace("跟踪基准的指数型仓位", "可以考虑 600519 这个标的");
        var r = OutputValidator.check(bad, NO_NAMES);
        assertThat(r.accepted()).isFalse();
        assertThat(r.reason()).contains("产品名");
    }

    @Test
    void accountWhitelistAllowsOwnAccountReference_v047() {
        // v0.4.7:用户已有「支付宝-余额宝」账户时,「余额宝」不再被 PRODUCT_NAME_PATTERN reject
        Set<String> accountWhitelist = Set.of("支付宝-余额宝");
        String text = VALID_DIAGNOSE.replace("跟踪基准的指数型仓位", "余额宝里的部分资金");
        // 旧 2 参签名 = 没白名单 · 应 reject
        var oldApi = OutputValidator.check(text, NO_NAMES);
        assertThat(oldApi.accepted()).isFalse();
        // 新 3 参签名 + whitelist · 应 accept
        var newApi = OutputValidator.check(text, NO_NAMES, accountWhitelist);
        assertThat(newApi.accepted()).isTrue();
    }
}
