package com.family.finance.service.checkup.llm;

/**
 * LLM 平台客户端 · 一个平台一个实现(v1.13 起)。
 *
 * <p>v1.12 及之前这里是 {@code vendor()} + {@code chat(system, user)},型号由客户端自己去
 * 读配置猜(用 {@code startsWith} 判断「这个型号是不是我家的」)。火山方舟推翻了这个前提:
 * 同一个平台上有豆包也有 DeepSeek,型号还可能是控制台生成的 {@code ep-} 接入点 ID,
 * 前缀判断必错。现在<b>调用坐标由路由给全</b>({@link LlmInvocation}),客户端只负责
 * 「拿这个坐标去出网」,不再自己读型号配置。</p>
 *
 * <p>实现类不要自己被注入到业务代码里 —— 唯一合法的注入点是 {@link LlmRouter}
 * (护栏 {@code v113-LLM-ROUTER-SINGLE-PATH})。绕开路由就意味着绕开主备编排和审计。</p>
 */
public interface LlmClient {

    /** 平台 code · 与 {@link LlmCatalog} 一致 */
    String platform();

    /** 凭据已配 且 未处于熔断冷却 */
    boolean available();

    /** 工具流与普通文本共享平台冷却,流式协议仍由 Agent runtime 负责。 */
    default void recordExternalSuccess() { }
    default void recordExternalFailure() { }

    /**
     * 发起一次对话调用。
     *
     * @param invocation 调用坐标(平台/系列/型号)· {@code model} 为 null 表示「自动」:
     *                   支持轮询的平台走轮询,其余用系列默认型号
     * @return 模型返回的 content(非空)
     * @throws RuntimeException 调用失败 · 由 {@link LlmRouter} 决定是否切备选
     */
    String chat(LlmInvocation invocation, String systemPrompt, String userPrompt);
}
