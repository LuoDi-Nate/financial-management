package com.family.finance.service.checkup.llm;

/** Only fixed, safe messages may cross the custom transport boundary. */
public final class CustomLlmFailure extends IllegalStateException {
    public enum Kind { CONFIG, SAVE_FAILED, URL_POLICY, DNS, CONNECT, TLS, AUTH, PERMISSION, MODEL, RATE_LIMIT,
        TIMEOUT, PROTOCOL, RESPONSE_TOO_LARGE, CONFIG_CHANGED, CANCELLED }
    private final Kind kind;
    public CustomLlmFailure(Kind kind) { super(message(kind)); this.kind = kind; }
    public Kind kind() { return kind; }
    public boolean stopPlan() { return kind == Kind.CONFIG_CHANGED || kind == Kind.CANCELLED; }
    public boolean accountFatal() { return kind == Kind.AUTH || kind == Kind.PERMISSION; }
    private static String message(Kind kind) {
        return switch (kind) {
            case CONFIG -> "自有服务配置不完整,请到AI接入检查服务及型号";
            case SAVE_FAILED -> "服务配置保存失败,原配置未改动,请稍后重试";
            case URL_POLICY -> "服务地址不符合访问规则,请核对基础地址及内网许可";
            case DNS -> "无法解析服务地址,请检查应用服务器的网络与DNS";
            case CONNECT -> "无法连接自有服务,请检查地址、端口与服务器网络";
            case TLS -> "服务证书校验失败,请检查证书有效期、域名与信任链";
            case AUTH -> "自有服务密钥无效,请核对后重新保存";
            case PERMISSION -> "自有服务拒绝访问,请检查权限与可用额度";
            case MODEL -> "请求路径或型号不可用,请核对Base URL前缀及完整型号";
            case RATE_LIMIT -> "自有服务限流或繁忙,请稍后重试";
            case TIMEOUT -> "自有服务请求超时,请检查网络或稍后重试";
            case PROTOCOL -> "服务响应不兼容,请确认支持所选模型及Chat Completions请求";
            case RESPONSE_TOO_LARGE -> "服务响应过大,本次调用已停止";
            case CONFIG_CHANGED -> "模型配置已更新,本次后续请求已停止,请重新发起";
            case CANCELLED -> "请求已停止";
        };
    }
}
