package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.member.Member;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.checkup.llm.*;
import com.family.finance.service.config.FamilyConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import java.util.*;
import java.util.stream.Stream;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class AiPlatformV141Test {
    static Stream<LlmCatalog.Platform> platforms() { return LlmCatalog.PLATFORMS.stream().filter(p -> !p.code().equals(LlmCatalog.P_CUSTOM)); }
    private final Map<String,String> values=new HashMap<>();
    private final FamilyConfigService cfg=mock(FamilyConfigService.class);
    private final AuditLogService audit=mock(AuditLogService.class);
    private final LlmRouter router=mock(LlmRouter.class);
    private final LlmSettingsView view=new LlmSettingsView(cfg,new ObjectMapper());
    private final AiModelController controller=new AiModelController(cfg,audit,router,view);
    AiPlatformV141Test() {
        when(cfg.getString(anyLong(),anyString(),anyString())).thenAnswer(a->values.getOrDefault(a.getArgument(1),a.getArgument(2)));
        when(cfg.getInt(anyLong(),anyString(),anyInt())).thenAnswer(a->Integer.parseInt(values.getOrDefault(a.getArgument(1),a.getArgument(2).toString())));
        when(cfg.getBoolean(anyLong(),anyString(),anyBoolean())).thenAnswer(a->Boolean.parseBoolean(values.getOrDefault(a.getArgument(1),a.getArgument(2).toString())));
        when(cfg.isPrivateKeyConfigured(anyLong(),anyString())).thenAnswer(a->!values.getOrDefault(a.getArgument(1),"").isBlank());
        when(cfg.maskedSecret(anyLong(),anyString())).thenAnswer(a->FamilyConfigService.maskSecret(values.get(a.getArgument(1))));
        doAnswer(a->{values.put(a.getArgument(1),a.getArgument(2));return null;}).when(cfg).set(anyLong(),anyString(),anyString());
    }
    private MemberPrincipal me() {var m=new Member();m.setId(1L);m.setFamilyId(1L);m.setUsername("synthetic");return new MemberPrincipal(m);}
    private RedirectAttributesModelMap save(String p,String f,String model,boolean vision,String vp,String vf,String vm) {
        var flash=new RedirectAttributesModelMap();controller.saveLlmModels(me(),p,f,model,null,null,null,vision,vp,vf,vm,0.5,2000,25,flash);return flash;
    }
    @ParameterizedTest @MethodSource("platforms")
    void firstKeySaveDoesNotNeedOrChangeModelsAndDoesNotEchoSecret(LlmCatalog.Platform p) {
        var flash=new RedirectAttributesModelMap();controller.saveLlmKey(me(),p.code(),"synthetic-private-key-123456789",flash);
        assertThat(values).containsOnlyKeys(p.keyName());assertThat(flash.getFlashAttributes().toString()).doesNotContain("123456789");
        var model=new ExtendedModelMap();view.addLlmAttributes(1,model);
        var cards=(List<LlmSettingsView.CredentialCard>)model.get("llmCredentialCards");
        assertThat(cards).hasSize((int)platforms().count());
        assertThat(cards.stream().filter(LlmSettingsView.CredentialCard::ready).toList()).extracting(LlmSettingsView.CredentialCard::code).containsExactly(p.code());
        assertThat(model.get("llmKeyCount")).isEqualTo(1L);
    }
    @ParameterizedTest @MethodSource("platforms")
    void emptyKeyNeverChangesExistingValues(LlmCatalog.Platform p) {
        values.put(p.keyName(),"old-key");var flash=new RedirectAttributesModelMap();controller.saveLlmKey(me(),p.code()," ",flash);
        assertThat(flash.getFlashAttributes().get("flashError")).asString().contains("未改动");assertThat(values.get(p.keyName())).isEqualTo("old-key");
    }
    @ParameterizedTest @MethodSource("platforms")
    void explicitCompleteAndAutomaticModelRoundtrip(LlmCatalog.Platform p) {
        values.put(p.keyName(),"synthetic-key");var family=p.firstFamily(LlmCatalog.Modality.TEXT).orElseThrow();
        var ok=save(p.code(),family.code(),"Pro/Org/CaseSensitive",false,"","","");
        assertThat(ok.getFlashAttributes()).doesNotContainKey("flashError");
        assertThat(LlmSettings.load(cfg,1).primary().model()).isEqualTo("Pro/Org/CaseSensitive");
        assertThat(values.get(p.keyName())).isEqualTo("synthetic-key");
        assertThat(save(p.code(),family.code(),"auto",false,"","","").getFlashAttributes()).doesNotContainKey("flashError");
        assertThat(LlmSettings.load(cfg,1).primary().auto()).isTrue();
    }
    @Test void invalidSelectionHasNoPartialWriteAndDraftContainsNoPastedKey() {
        values.put("llm_siliconflow_api_key","synthetic-key");var before=new HashMap<>(values);
        var bad=save("siliconflow","other","sk-SECRET WITH SPACE",false,"","","");
        assertThat(values).isEqualTo(before);assertThat(bad.getFlashAttributes()).containsKey("llmDraft");
        assertThat(bad.getFlashAttributes().toString()).doesNotContain("SECRET");
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status=mock(org.springframework.transaction.TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(status);
        var interceptor=new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory=new org.springframework.aop.framework.ProxyFactory(controller);factory.addAdvice(interceptor);
        var proxied=(AiModelController)factory.getProxy();
        doAnswer(a->{throw new IllegalStateException("synthetic-write-failure");}).when(cfg).set(eq(1L),eq("llm_family"),anyString());
        assertThatThrownBy(()->proxied.saveLlmModels(me(),"siliconflow","deepseek","Org/Model",null,null,null,false,"","","",0.5,2000,25,new RedirectAttributesModelMap()))
                .hasMessage("synthetic-write-failure");
        verify(manager).rollback(status);verify(manager,never()).commit(any());
    }
    @Test void disabledVisionPreservesPreviousSettingAndDoesNotBlockTextOnly() {
        values.put("llm_deepseek_api_key","synthetic-key");values.put("llm_vision_model_id","Previous/Vision");
        assertThat(save("deepseek","deepseek","",false,"siliconflow","other-vision","bad model").getFlashAttributes()).doesNotContainKey("flashError");
        assertThat(values.get("llm_vision_model_id")).isEqualTo("Previous/Vision");
        var model=new ExtendedModelMap();view.addLlmAttributes(1,model);assertThat(model.get("visionCapableReady")).isEqualTo(false);
        values.put("llm_vision_enabled","true");
        var draft=new ExtendedModelMap();draft.addAttribute("llmDraft",Map.of("llmVisionEnabled",false));view.addLlmAttributes(1,draft);
        assertThat(draft.get("llmVisionEnabled")).isEqualTo(false);
        assertThat(draft.get("llmVisionEffectiveDisplay")).isEqualTo(LlmSettings.load(cfg,1).vision().display());
    }
    @Test void siliconflowVisionIsAvailableWithoutAnyOriginalPlatformKey() {
        values.put("llm_siliconflow_api_key","synthetic-key");
        assertThat(save("siliconflow","deepseek","",true,"siliconflow","kimi","Org/Vision").getFlashAttributes()).doesNotContainKey("flashError");
        assertThat(LlmSettings.load(cfg,1).vision().model()).isEqualTo("Org/Vision");
    }
}
