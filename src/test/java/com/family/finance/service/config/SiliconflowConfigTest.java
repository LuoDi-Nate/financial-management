package com.family.finance.service.config;

import com.family.finance.repository.FamilyRuntimeConfigMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SiliconflowConfigTest {
    @Test void explicitEnvironmentFallbackDbOverrideAndMasking() {
        var mapper=mock(FamilyRuntimeConfigMapper.class);
        when(mapper.findValue(anyLong(),anyString())).thenReturn(Optional.empty());
        try(var context=new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic",Map.of("FINANCE_LLM_SILICONFLOW_API_KEY","synthetic-env-key-123456789")));
            context.registerBean(PropertySourcesPlaceholderConfigurer.class,PropertySourcesPlaceholderConfigurer::new);
            context.registerBean(FamilyRuntimeConfigMapper.class,()->mapper);context.registerBean(FamilyConfigService.class);context.refresh();
            var config=context.getBean(FamilyConfigService.class);String key=FamilyConfigService.K_LLM_SILICONFLOW_KEY;
            assertThat(config.getString(1,key,"")).isEqualTo("synthetic-env-key-123456789");assertThat(config.isPrivateKeyConfigured(1,key)).isTrue();
            assertThat(config.maskedSecret(1,key)).doesNotContain("env-key-12345");
            config.set(1,key,"synthetic-db-key");when(mapper.findValue(1,key)).thenReturn(Optional.of("synthetic-db-key"));
            assertThat(config.getString(1,key,"")).isEqualTo("synthetic-db-key");
        }
    }
}
