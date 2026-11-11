package com.family.finance.service.checkup.llm;

import com.family.finance.service.holdingimport.VisionLlmClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v1.13 tech-design §4 · <b>口径不变</b>的逐字节护栏。
 *
 * <p>这一版只动「调用谁」,不动「问什么」。但它动的是<b>六个提示词就在旁边</b>的文件
 * (五处业务调用点 + 视觉客户端整类改名),顺手改一个字、少一个换行都不会有任何报错,
 * 只会让模型的回答悄悄换一副样子 —— 而回答本来就是不确定的,没人能靠肉眼发现。
 * 所以这里对 prompt 文本做<b>哈希级</b>比对,基线取自已发布的 v1.12.1。</p>
 *
 * <p>比对对象是源文件里的<b>文本块字面量</b>({@code """..."""}):这些文件里的文本块
 * 就是 prompt 本身。改 prompt 是允许的 —— 但必须是<b>有意</b>改,连带把这里的基线换掉,
 * 在 diff 里留下「我知道我在改口径」的痕迹。</p>
 *
 * <p>为什么不是「构造 ViewModel 调 PromptBuilder 比对成品字符串」:那样要先搭一整套
 * FamilyDiagnose 假数据,测的东西反而变成了假数据本身;而且拼装逻辑改了、文本没改时
 * 也会红。这里要钉的就是<b>文本</b>。</p>
 */
class PromptUnchangedTest {

    /** 源文件 → 其全部文本块拼接后的 SHA-256(基线:v1.12.1) */
    private static final Map<String, String> BASELINE = Map.of(
            "src/main/java/com/family/finance/service/checkup/llm/PromptBuilder.java",
            "2149f84d1d10d87f692f0e38976a24e1330c275e759fe526b8a9d43abe6ae889",
            "src/main/java/com/family/finance/service/lens/LensInsightService.java",
            "fc64d3d28bf5e500873c2073f94cdb55295fd2a8ecd6a6d823a71e41f4fd2b28",
            "src/main/java/com/family/finance/service/lens/LensAiTagService.java",
            "5efffb3abd29e773a85134e45a2aaa20181d56914209a5ff155c87faa6ab1593",
            "src/main/java/com/family/finance/service/review/ReviewInsightService.java",
            "ae62212f40e9773c1f74a65b7b2d42cf7adfbc625b37ce1a41a012cee1921aa2",
            // v1.28 有意改口径(PRD FR-920):调仓的账户清单改成代号,规矩第 3 条跟着说「原样用给定的账户名(代号)」
            "src/main/java/com/family/finance/service/allocation/RebalanceAdvisorService.java",
            "b35a1fcbccd5cf795c523525188db8fb763f11e530064f938ee61012d444e82b",
            // v1.40.1: explicitly forbid inventing arithmetic/dates or monthly changes in goal insights.
            "src/main/java/com/family/finance/service/goal/GoalLlmService.java",
            "8e712d9d199bb0f1cbe935b9162b39b814065f2c947d8c494ce52e28e253b7f3");

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String textBlockDigest(Path file) throws Exception {
        String src = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = TEXT_BLOCK.matcher(src);
        List<String> blocks = new ArrayList<>();
        while (m.find()) blocks.add(m.group(1));
        assertThat(blocks).as("%s 里一个文本块都没有 —— prompt 被搬走了?", file).isNotEmpty();
        return sha256(String.join("|", blocks));
    }

    @Test
    void promptTextBlocks_unchangedSinceV1121() throws Exception {
        for (Map.Entry<String, String> e : BASELINE.entrySet()) {
            Path f = Path.of(e.getKey());
            assertThat(Files.exists(f)).as("%s 不存在", f).isTrue();
            assertThat(textBlockDigest(f))
                    .as("%s 的 prompt 文本变了 —— v1.13 只该改「调用谁」,不该改「问什么」。"
                        + "如果确实是有意改口径,请连同这里的基线一起更新。", f)
                    .isEqualTo(e.getValue());
        }
    }

    /**
     * 视觉提示词单独钉:它所在的类在 v1.13 整个改了名
     * ({@code QwenVisionClient} → {@link VisionLlmClient}),是最容易在搬运中被顺手改掉的一处。
     * 这两段还额外承载 memory {@code feedback_llm_no_math} 的约束(「绝不计算、推导」),
     * 掉了就等于放开模型自己算钱。
     */
    @Test
    void visionPrompts_unchangedSinceV1121() throws Exception {
        assertThat(constant("SYS")).isEqualTo(
                "你是持仓截图转写器。只转写图中肉眼可见的持仓,绝不计算、推导、反推或编造任何数值。"
                + "跳过分类汇总行(如「基金」「多宝理财」这种把下面几支加总的标题行),只要真正的单支持仓。输出严格 JSON,不要 markdown 围栏。");
        assertThat(constant("PROMPT")).isEqualTo(
                "这是一张理财/基金/券商 app 的持仓列表截图。逐支持仓转写,只读屏幕上肉眼可见的文字和数字。"
                + "输出一个 JSON 数组,每个元素:{\"name\":\"持仓名称(原样)\",\"code\":\"基金代码(6位数字,没有则 null)\","
                + "\"marketValue\":\"持仓市值/金额(原样字符串;读不到填 null)\",\"confidence\":\"high 或 low(名称/数字被遮挡或模糊则 low)\"}。"
                + "不要计算、不要合计、不要补全看不到的值。只输出 JSON 数组本身。");
    }

    private static String constant(String name) throws Exception {
        Field f = VisionLlmClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(null);
    }
}
