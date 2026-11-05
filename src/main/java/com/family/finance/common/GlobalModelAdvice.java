package com.family.finance.common;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.service.NavService;
import com.family.finance.service.NavState;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.Instant;

/**
 * 把 me + nav + buildVersion + appVersion 自动注入到所有 controller 的 model。
 * 未登录(/login 等)时 me/nav 为 null。
 * buildVersion 来自 Spring Boot build-info(maven build-info goal 生成 META-INF/build-info.properties)。
 * 用于模板中 vendor / css 静态资源 ?v=... 失效缓存。
 * appVersion 来自 application.yml app.version(发布语义版本,nav logo 下展示;发版随 tag 同步)。
 */
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {
    @org.springframework.beans.factory.annotation.Autowired
    private com.family.finance.service.checkup.llm.LlmTestGate llmTestGate;

    @ModelAttribute("llmTestMode")
    public boolean llmTestMode(@AuthenticationPrincipal MemberPrincipal me) {
        return me != null && llmTestGate != null && llmTestGate.enabled();
    }

    private final NavService navService;
    /** v1.9 · 只调 cached()(内存字段),绝不在这里查库/出网 */
    private final com.family.finance.service.update.UpdateCheckService updateCheckService;
    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    /** 发布语义版本(nav logo 下展示)· application.yml app.version · 发版随 tag 同步 */
    @Value("${app.version:dev}")
    private String appVersion;

    @ModelAttribute("appVersion")
    public String appVersion() {
        return appVersion;
    }

    /**
     * v1.12 FR-353 · 比率失真时的降级文案 + 补录入口(常量,不查库不出网)。
     *
     * <p>为什么走全局注入:这条降级要作用在仪表盘、报表储蓄区、封板对照表三处,
     * 文案写在模板里就是三份字面量。护栏 {@code v112-RATIO-INSUFFICIENT} 盯着
     * 「模板里没有硬编码的降级文案」。</p>
     */
    @ModelAttribute("ratioNote")
    public MetricDisplay.Note ratioNote() {
        return MetricDisplay.NOTE;
    }

    /**
     * v1.9 · 版本检查结果 —— nav 徽记的圆点看它。
     *
     * <p><b>这里每个请求都会跑,所以只允许一次内存字段读</b>:不查库、不出网。
     * {@code UpdateCheckService.cached()} 返回的是进程内 volatile 字段
     * (启动时预热 + 每次检查后刷新)。守护 {@code v19-UPD-NO-IO-IN-ADVICE} 盯着这条。</p>
     *
     * <p>未登录时返回 null —— 落地页不显示任何更新信息(PRD 验收 11)。</p>
     */
    @ModelAttribute("updateInfo")
    public com.family.finance.service.update.UpdateCheckService.UpdateInfo updateInfo(
            @AuthenticationPrincipal MemberPrincipal me) {
        return me == null ? null : updateCheckService.cached(me.getFamilyId());
    }

    @ModelAttribute("me")
    public MemberPrincipal me(@AuthenticationPrincipal MemberPrincipal me) {
        return me;
    }

    @ModelAttribute("nav")
    public NavState nav(@AuthenticationPrincipal MemberPrincipal me) {
        return me == null ? null : navService.load(me);
    }

    @ModelAttribute("buildVersion")
    public String buildVersion() {
        BuildProperties bp = buildPropertiesProvider.getIfAvailable();
        if (bp != null) {
            Instant t = bp.getTime();
            // 用构建毫秒时间戳的 base36 表达,短而单调
            long ms = t == null ? 0L : t.toEpochMilli();
            return bp.getVersion() + "-" + Long.toString(ms, 36);
        }
        // build-info 未生成时(开发模式 mvn spring-boot:run 等):用应用启动时间作 fallback
        return "dev-" + Long.toString(System.currentTimeMillis(), 36);
    }
}
