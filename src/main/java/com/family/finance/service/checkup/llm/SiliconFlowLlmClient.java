package com.family.finance.service.checkup.llm;

import com.family.finance.service.config.FamilyConfigService;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;

@Component
public class SiliconFlowLlmClient extends AbstractOpenAiCompatibleClient {
    public SiliconFlowLlmClient(FamilyConfigService config, RestTemplateBuilder builder) {
        super(config, builder);
    }

    @Override
    protected LlmCatalog.Platform platformDef() { return LlmCatalog.SILICONFLOW; }
}
