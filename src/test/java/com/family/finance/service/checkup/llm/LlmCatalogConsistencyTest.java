package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * v1.13 FR-364 · <b>目录 ↔ 实现</b>对齐护栏。
 *
 * <p>{@link LlmCatalog} 是「有哪些平台可选」的唯一一份,它直接喂给管理页的下拉。
 * 但目录是纯数据 —— 往里加一个平台不会有任何编译错误,而用户在页面上选中它的那一刻
 * 就会撞上「没有对应 client」:表现成保存成功、按钮亮着、一调用就失败。反过来,
 * 写了 client 却忘了进目录,则是这个平台<b>永远选不到</b>,同样静默。</p>
 *
 * <p>所以这里不写死平台清单,而是<b>扫包</b>:把 {@code LlmClient} 的具体实现全找出来,
 * 与目录做双向比对。加平台漏一半 → 这里红。</p>
 */
class LlmCatalogConsistencyTest {

    /**
     * 扫 {@code LlmClient} 所在包下的全部具体实现(不写死类名 —— 写死了就等于每次加平台都要记得改这里)。
     *
     * <p>目录从 {@code LlmClient} 自己的 code source 推:不能用 {@code getResource("")},
     * 那个在 surefire 下会先命中 {@code test-classes} 里的同名包,扫出一堆测试类、一个实现都没有。</p>
     */
    private static List<Class<?>> clientClasses() throws Exception {
        URL codeSource = LlmClient.class.getProtectionDomain().getCodeSource().getLocation();
        assertThat(codeSource).as("拿不到 LlmClient 的 code source —— 打包形态变了?").isNotNull();
        File dir = new File(new File(codeSource.toURI()),
                LlmClient.class.getPackageName().replace('.', '/'));
        String prefix = LlmClient.class.getPackageName() + ".";
        List<Class<?>> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".class") && !n.contains("$"));
        assertThat(files).as("包目录里没有 class 文件").isNotEmpty();
        for (File f : files) {
            Class<?> c = Class.forName(prefix + f.getName().substring(0, f.getName().length() - 6));
            if (LlmClient.class.isAssignableFrom(c) && !c.isInterface()
                    && !Modifier.isAbstract(c.getModifiers())) {
                out.add(c);
            }
        }
        return out;
    }

    /** 只为读 {@code platform()}:构造函数不出网,拿一个 mock 配置即可 */
    private static LlmClient instantiate(Class<?> c) throws Exception {
        var constructor = c.getConstructors()[0];
        Object[] args = java.util.Arrays.stream(constructor.getParameterTypes())
            .map(t -> t == RestTemplateBuilder.class ? new RestTemplateBuilder() : mock(t)).toArray();
        return (LlmClient) constructor.newInstance(args);
    }

    @Test
    void everyPlatformInCatalog_hasExactlyOneClientImpl() throws Exception {
        List<String> impls = new ArrayList<>();
        for (Class<?> c : clientClasses()) impls.add(instantiate(c).platform());

        for (LlmCatalog.Platform p : LlmCatalog.PLATFORMS) {
            assertThat(impls)
                    .as("目录里有「%s」(%s),但没有任何 LlmClient 实现认领它 —— "
                        + "用户在管理页选中即失败。加平台 = 改目录 + 写 client,两件事要一起做。",
                        p.label(), p.code())
                    .contains(p.code());
            assertThat(impls.stream().filter(p.code()::equals).count())
                    .as("平台 %s 有多个 client 实现 · 路由会随注入顺序挑一个,行为不确定", p.code())
                    .isEqualTo(1);
        }
    }

    @Test
    void everyClientImpl_isInCatalog_andIsASpringBean() throws Exception {
        for (Class<?> c : clientClasses()) {
            String platform = instantiate(c).platform();
            assertThat(LlmCatalog.platform(platform))
                    .as("%s 声称自己是平台「%s」,但目录里没有 —— 这个平台永远选不到,代码是死的",
                        c.getSimpleName(), platform)
                    .isPresent();
            assertThat(c.getAnnotation(Component.class))
                    .as("%s 没标 @Component · 不会被注入进 LlmRouter,等于没写", c.getSimpleName())
                    .isNotNull();
        }
    }

    @Test
    void platformKeys_areDistinct_andEndpointsAreHttps() {
        List<String> keys = LlmCatalog.PLATFORMS.stream().map(LlmCatalog.Platform::keyName).toList();
        assertThat(keys)
                .as("两个平台共用一把 key 配置项 · 改一个会把另一个也改掉")
                .doesNotHaveDuplicates();
        for (LlmCatalog.Platform p : LlmCatalog.PLATFORMS) {
            if (p.code().equals(LlmCatalog.P_CUSTOM)) {
                assertThat(p.baseUrl()).isEmpty();
                org.assertj.core.api.Assertions.assertThatThrownBy(p::chatEndpoint).isInstanceOf(CustomLlmFailure.class);
                assertThat(p.families()).allMatch(LlmCatalog.Family::requiresExplicitModel);
                continue;
            }
            assertThat(p.baseUrl()).as("%s 的端点必须是 https(key 要随请求头出网)", p.code())
                    .startsWith("https://");
            assertThat(p.baseUrl()).as("%s 的 baseUrl 带了尾斜杠 · 拼出来会是 //chat/completions", p.code())
                    .doesNotEndWith("/");
            assertThat(p.chatEndpoint()).endsWith("/chat/completions");
        }
    }

    @Test
    void familyDefaults_areSelfConsistent() {
        for (LlmCatalog.Platform p : LlmCatalog.PLATFORMS) {
            assertThat(p.families()).as("%s 一个模型系列都没有", p.code()).isNotEmpty();
            assertThat(p.families(LlmCatalog.Modality.TEXT))
                    .as("%s 没有文本系列 —— 主备编排只用文本,这个平台配上也调不到", p.code())
                    .isNotEmpty();
            for (LlmCatalog.Family f : p.families()) {
                if (f.defaultModel() == null) {
                    // 「必须手工填」的系列不该同时给推荐清单:给了就说明其实能预置,那就该有默认值
                    assertThat(f.models())
                            .as("%s/%s 没有默认型号却给了推荐清单 · 语义自相矛盾", p.code(), f.code())
                            .isEmpty();
                    assertThat(f.requiresExplicitModel()).isTrue();
                } else {
                    assertThat(LlmCatalog.inRecommended(f, f.defaultModel()))
                            .as("%s/%s 的默认型号 %s 不在自己的推荐清单里 · 页面下拉选不到当前生效值",
                                p.code(), f.code(), f.defaultModel())
                            .isTrue();
                    assertThat(f.requiresExplicitModel()).isFalse();
                }
                for (LlmCatalog.Model m : f.models()) {
                    assertThat(LlmCatalog.validModel(m.id()))
                            .as("%s/%s 的预置型号 %s 自己都过不了格式校验", p.code(), f.code(), m.id())
                            .isTrue();
                }
            }
            assertThat(p.families().stream().map(LlmCatalog.Family::code).toList())
                    .as("%s 内部系列 code 重复 · family(code) 会取到第一个", p.code())
                    .doesNotHaveDuplicates();
        }
        assertThat(LlmCatalog.PLATFORMS.stream().map(LlmCatalog.Platform::code).toList())
                .doesNotHaveDuplicates();
    }

    /**
     * 视觉形态至少要有一个平台撑着 —— 否则截图导入(v1.4 起的能力)在管理页上无从配置,
     * 而入口还在,用户点进去只会看到「未配置」。
     */
    @Test
    void atLeastOnePlatform_supportsVision() {
        assertThat(LlmCatalog.PLATFORMS.stream()
                .filter(p -> !p.families(LlmCatalog.Modality.VISION).isEmpty()).toList())
                .as("没有任何平台提供视觉系列 · 截图导入将无从配置")
                .isNotEmpty();
    }
}
