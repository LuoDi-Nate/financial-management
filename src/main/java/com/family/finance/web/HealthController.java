package com.family.finance.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 极简 liveness 检查,Nginx/外部监控可探活。
 */
@RestController
public class HealthController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.family.finance.service.checkup.llm.LlmTestGate llmTestGate;

    /** 发布语义版本 · application.yml app.version(与 nav 徽记同源)*/
    @org.springframework.beans.factory.annotation.Value("${app.version:dev}")
    private String appVersion;


    /**
     * v1.6.25 · 带上版本号。
     * 起因:用户拉了新代码、重跑 docker-up.sh,却不知道自己到底跑的是哪一版 ——
     * 而版本徽记只在**登录后**的 nav 里,/health 只有 {"status":"UP"},落地页也没有。
     * 结果是"静默拿到旧版本"无法自查。这里暴露出来,用户和部署脚本都能不登录确认。
     * 只暴露语义版本(与 nav 徽记同源 app.version),不含构建号/主机/路径等运维细节。
     */
    @GetMapping(value = "/health", produces = "application/json")
    public Map<String, Object> health() {
        var out = new java.util.LinkedHashMap<String, Object>();
        out.put("status", "UP"); out.put("version", appVersion);
        if (llmTestGate != null) {
            var status = llmTestGate.status();
            out.put("aiTest", Map.of("enabled", status.enabled(), "epoch", status.epoch() == null ? "" : status.epoch(),
                    "blocked", status.blocked(), "sent", status.sent()));
        }
        return out;
    }
}
