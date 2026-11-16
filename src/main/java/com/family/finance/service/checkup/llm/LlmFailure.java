package com.family.finance.service.checkup.llm;

import org.springframework.web.client.RestClientResponseException;
import java.util.Locale;

/** 仅输出固定安全文案,上游原文只用于分类,不能传到页面或日志。 */
public final class LlmFailure {
    private LlmFailure() {}

    public static String describe(String platform, Throwable failure) {
        StringBuilder raw = new StringBuilder();
        int status = 0;
        Throwable cause = failure;
        for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause()) {
            if (cause instanceof CustomLlmFailure custom) return custom.getMessage();
            if (cause instanceof LlmTestGate.Blocked) return LlmTestGate.MESSAGE;
            if (cause instanceof RestClientResponseException response) {
                status = response.getStatusCode().value();
                raw.append(' ').append(response.getResponseBodyAsString());
            }
            raw.append(' ').append(cause.getClass().getSimpleName()).append(' ').append(cause.getMessage());
        }
        return describe(platform, status, raw.toString());
    }

    public static String describe(String platform, int status, String raw) {
        String m = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (m.contains("未配置") || m.contains("not configured")) return "Key 未配置,请先保存密钥";
        if (m.contains("modelnotopen") || m.contains("not activated") || m.contains("未开通"))
            return "该型号尚未开通或无权使用,请到本平台核对";
        if (status == 404 || m.contains("model not found") || m.contains("modelnotfound")
                || m.contains("invalidendpointormodel") || m.contains("does not exist")) {
            String hint = LlmCatalog.P_ARK.equals(platform)
                    ? ";带日期型号可能已失效,可改用不带日期的 "
                        + LlmCatalog.ARK.family("doubao").map(LlmCatalog.Family::defaultModel).orElse("")
                        + ",或到方舟模型广场复制当前Model ID;也可填写ep-接入点ID"
                    : "";
            return "型号不存在或无权使用,请从本平台复制完整型号" + hint;
        }
        if (m.contains("realname") || m.contains("real name") || m.contains("实名"))
            return "平台账号需要完成实名认证,请到本平台核对";
        if (status == 402 || m.contains("insufficient") || m.contains("balance") || m.contains("arrearage")
                || m.contains("欠费") || m.contains("billoverdue") || m.contains("overdue")) {
            if (LlmCatalog.P_DASHSCOPE.equals(platform) && (m.contains("quota") || m.contains("allocationquota")))
                return "免费额度已用尽,请检查百炼额度或更换可用型号";
            return "平台账户余额或权限不足,请到本平台核对";
        }
        if (status == 429 || status == 503 || m.contains("ratelimit") || m.contains("rate limit") || m.contains("too many"))
            return "平台限流或繁忙,请稍后重试";
        if (status == 401 || m.contains("invalid token") || m.contains("invalid api key") || m.contains("unauthor"))
            return "Key 无效,请核对并重新保存密钥";
        if (status == 403 || m.contains("forbidden")) return "平台拒绝访问,请核对账户权限与可用额度";
        if (m.contains("timeout") || m.contains("timed out") || m.contains("resourceaccess")
                || m.contains("connectexception") || m.contains("unknownhost")) return "网络不通或超时,请稍后重试";
        if (m.contains("image") || m.contains("vision") || m.contains("图片")) return "请核对所选型号是否支持图片输入";
        if (m.contains("tool") || m.contains("function")) return "请核对所选型号是否支持工具调用";
        return status > 0 ? "调用失败(已脱敏 · HTTP " + status + ")" : "调用失败(已脱敏),请稍后重试或检查模型配置";
    }
}
