package com.family.finance.service.checkup;

import com.family.finance.calc.BenchmarkComparator;
import com.family.finance.service.analysis.AnalysisPromptBlocks;
import com.family.finance.service.analysis.PromptFixtures;
import com.family.finance.service.checkup.llm.PromptBuilder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.30.1 · 账户体检的 AI 提示词:「本期变化」是余额差、含划转 —— 有划转时必须单独说出来,
 * 不然模型会把一笔转出当成亏损(prod 2026-09 实例)。金额全是编的。
 */
class AccountPromptTransferTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static String prompt(AccountDiagnose a) {
        return PromptBuilder.userPromptForAccount("我们家", PromptFixtures.family(), a, List.of(),
                PromptFixtures.mapping(), "成员B",
                AnalysisPromptBlocks.preferencesOnly(List.of(), PromptFixtures.mapping()), "账户A");
    }

    @Test
    void 有划转时_本期变化下面写出划转净额与本期投资损益() {
        var base = PromptFixtures.stockAccount();
        var withTransfer = new AccountDiagnose(base.account(), base.category(), d("600"), d("900"), d("-300"), 12,
                d("0"), d("0"), d("0"), d("250"), d("100"), d("10"), d("0.05"), null,
                BenchmarkComparator.Result.noBenchmark(), 4, false, List.of(), d("600"), d("-250"), d("-50"));
        String user = prompt(withTransfer);
        assertThat(user).contains("- 本期变化: ¥-300")
                .contains("其中账户间划转 / 补录本金净额: ¥-250")
                .contains("不是赚亏")
                .contains("本期投资损益(已剔除收支与划转): ¥-50");
    }

    @Test
    void 没有划转时_不多写_与金样本同形() {
        assertThat(prompt(PromptFixtures.stockAccount())).doesNotContain("其中账户间划转");
    }
}
