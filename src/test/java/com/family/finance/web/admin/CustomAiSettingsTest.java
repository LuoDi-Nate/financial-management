package com.family.finance.web.admin;

import com.family.finance.auth.MemberPrincipal;
import com.family.finance.domain.member.Member;
import com.family.finance.repository.FamilyRuntimeConfigMapper;
import com.family.finance.service.AuditLogService;
import com.family.finance.service.checkup.llm.*;
import com.family.finance.service.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CustomAiSettingsTest {
    final Map<String,String> values=new HashMap<>();
    FamilyConfigService config;
    FamilyRuntimeConfigMapper mapper;
    CustomLlmConfigService custom;
    LlmTargetResolver targets;
    LlmRouter router;
    LlmSettingsView view;
    AiModelController controller;
    LlmTestGate gate;
    MemberPrincipal me(long fid) { var m=new Member();m.setId(1L);m.setFamilyId(fid);m.setUsername("synthetic");return new MemberPrincipal(m); }
    @BeforeEach void setup() {
        config=mock(FamilyConfigService.class);mapper=mock(FamilyRuntimeConfigMapper.class);
        when(config.getAll(anyLong())).thenAnswer(a -> a.<Long>getArgument(0)==1L ? new HashMap<>(values) : new HashMap<String,String>());
        when(config.llmSnapshot(anyLong())).thenAnswer(a -> a.<Long>getArgument(0)==1L ? new HashMap<>(values) : new HashMap<String,String>());
        when(config.getString(anyLong(),anyString(),anyString())).thenAnswer(a -> values.getOrDefault(a.getArgument(1),a.getArgument(2)));
        when(config.getInt(anyLong(),anyString(),anyInt())).thenAnswer(a -> a.getArgument(2));
        when(config.getBoolean(anyLong(),anyString(),anyBoolean())).thenAnswer(a -> Boolean.parseBoolean(values.getOrDefault(a.getArgument(1),a.getArgument(2).toString())));
        when(mapper.lockFamily(1)).thenReturn(1L);
        when(mapper.upsert(eq(1L),anyString(),anyString())).thenAnswer(a -> {values.put(a.getArgument(1),a.getArgument(2));return 1;});
        doAnswer(a -> {values.put(a.getArgument(1),a.getArgument(2));return null;}).when(config).set(eq(1L),anyString(),anyString());
        custom=new CustomLlmConfigService(mapper,config,new CustomEndpointPolicy(),new ObjectMapper(),"");
        targets=new LlmTargetResolver(config,custom);router=mock(LlmRouter.class);gate=mock(LlmTestGate.class);
        view=new LlmSettingsView(config,new ObjectMapper());
        ReflectionTestUtils.setField(view,"custom",custom);ReflectionTestUtils.setField(view,"targets",targets);
        controller=new AiModelController(config,mock(AuditLogService.class),router,view);
        ReflectionTestUtils.setField(controller,"custom",custom);ReflectionTestUtils.setField(controller,"targets",targets);
        ReflectionTestUtils.setField(controller,"runtimeConfig",mapper);ReflectionTestUtils.setField(controller,"testGate",gate);
    }
    RedirectAttributesModelMap service(String url,String mode,String key,String edit) {
        var flash=new RedirectAttributesModelMap();controller.saveCustom(me(1),"合成网关",url,mode,key,false,edit,flash);return flash;
    }
    void initial() { service("https://fixture.example.com/prefix/v1","BEARER","synthetic-secret-marker",""); }
    RedirectAttributesModelMap models(String model, String revision, String backup, boolean vision) {
        var flash=new RedirectAttributesModelMap();
        controller.saveLlmModels(me(1),"custom","custom",model,backup==null?"":"custom",backup==null?"":"custom",backup,
            vision,"custom","custom-vision",vision?"Org/Vision":"bad draft",0.5,2000,25,revision,flash);return flash;
    }
    @Test void firstServiceSaveDoesNotNeedOrWriteModelAndNeverEchoesKey() {
        var flash=service("https://fixture.example.com/prefix/v1","BEARER","synthetic-secret-marker","");
        assertThat(flash.getFlashAttributes()).containsKey("flash").doesNotContainKey("flashError");
        assertThat(values).containsOnlyKeys(CustomLlmConfigService.KEY);assertThat(flash.getFlashAttributes().toString()).doesNotContain("synthetic-secret-marker");
        var model=new ExtendedModelMap();view.addLlmAttributes(1,model);
        assertThat(model.get("llmKeyCount")).isEqualTo(1L);assertThat(model.get("visionCapableReady")).isEqualTo(true);
        assertThat(model.get("llmCatalogJson").toString()).doesNotContain("fixture.example.com","synthetic-secret-marker");
        assertThat(model.get("llmCustomProbe").toString()).contains("选择");
    }
    @Test void noAuthIsConfiguredForBothTextAndVisionWithoutAKey() {
        service("https://fixture.example.com/v1","NONE","ignored","");
        assertThat(custom.load(1).apiKey()).isEmpty();assertThat(targets.configured(1,"custom")).isTrue();
        assertThat(models("Org/Text",custom.card(1).revision(),null,true).getFlashAttributes()).doesNotContainKey("flashError");
        assertThat(LlmSettings.from(values).vision().model()).isEqualTo("Org/Vision");
    }
    @Test void changeAddressRequiresNewKeyAndRetainsSafeNonSecretDraft() {
        initial();var before=new HashMap<>(values);
        var flash=service("https://other.example.com/v1","BEARER","",custom.card(1).editToken());
        assertThat(values).isEqualTo(before);assertThat(flash.getFlashAttributes()).containsKey("customDraft");
        assertThat(flash.getFlashAttributes().toString()).doesNotContain("synthetic-secret-marker");
    }
    @Test void oldKeyEndpointCannotCorruptServiceEvenWithUppercasePlatform() {
        initial();var before=new HashMap<>(values);
        for(String p:List.of("custom","CUSTOM")) {
            var flash=new RedirectAttributesModelMap();controller.saveLlmKey(me(1),p,"synthetic-new-key",flash);
            assertThat(flash.getFlashAttributes()).containsKey("flashError");assertThat(values).isEqualTo(before);
        }
    }
    @Test void customModelIsExplicitAndLiteralAutoIsPreserved() {
        initial();String rev=custom.card(1).revision();var before=new HashMap<>(values);
        assertThat(models("",rev,null,false).getFlashAttributes()).containsKey("flashError");assertThat(values).isEqualTo(before);
        assertThat(models("auto",rev,null,false).getFlashAttributes()).doesNotContainKey("flashError");
        assertThat(LlmSettings.from(values).primary().model()).isEqualTo("auto");
        assertThat(models("Org/Text",rev,"Org/Text",false).getFlashAttributes()).containsKey("flashError");
        assertThat(models("Org/Text",rev,"Org/Other",false).getFlashAttributes()).doesNotContainKey("flashError");
    }
    @Test void staleModelFormCannotApplyToChangedService() {
        initial();String revision=custom.card(1).revision();
        service("https://other.example.com/v1","BEARER","new-key",custom.card(1).editToken());
        var before=new HashMap<>(values);
        assertThat(models("Org/Text",revision,null,false).getFlashAttributes()).containsKey("flashError");assertThat(values).isEqualTo(before);
        var upper=new RedirectAttributesModelMap();
        controller.saveLlmModels(me(1),"CUSTOM","custom","Org/Text","","","",false,"","","",0.5,2000,25,revision,upper);
        assertThat(upper.getFlashAttributes().get("flashError").toString()).contains("配置已更新");assertThat(values).isEqualTo(before);
        when(mapper.lockFamily(1)).thenReturn(null);
        assertThat(models("Org/Text",custom.card(1).revision(),null,false).getFlashAttributes()).containsKey("flashError");
        assertThat(values).isEqualTo(before);
    }
    @Test void probeHasNoDefaultAndUsesOnlySavedTargetNotAnotherModel() {
        initial();LlmClient wire=mock(LlmClient.class);when(router.clientFor("custom")).thenReturn(Optional.of(wire));
        var first=new RedirectAttributesModelMap();controller.testLlm(me(1),"custom",first);
        assertThat(first.getFlashAttributes()).containsKey("flashError");verify(wire,never()).chat(any(),anyString(),anyString());
        models("Org/Saved",custom.card(1).revision(),null,false);
        when(wire.chat(any(),anyString(),anyString())).thenAnswer(a -> {LlmInvocation inv=a.getArgument(0);
            assertThat(inv.model()).isEqualTo("Org/Saved");assertThat(inv.target().apiKey()).isEqualTo("synthetic-secret-marker");return "ok";});
        var second=new RedirectAttributesModelMap();controller.testLlm(me(1),"custom",second);
        assertThat(second.getFlashAttributes().get("flash").toString()).contains("合成网关","Org/Saved","只验证文字").doesNotContain("synthetic-secret-marker");
    }
    @Test void protectionPreventsProbeBeforeLookingUpAnyClient() {
        when(gate.block(anyLong(),any(),anyString(),anyString())).thenReturn(true);
        var flash=new RedirectAttributesModelMap();controller.testLlm(me(1),"custom",flash);
        assertThat(flash.getFlashAttributes().get("flash")).isEqualTo(LlmTestGate.MESSAGE);verifyNoInteractions(router);
    }
    @Test void databaseFailurePreservesOldConfigAndHasNoSensitiveExceptionInFlash() {
        initial();String edit=custom.card(1).editToken();var before=new HashMap<>(values);
        when(mapper.upsert(eq(1L),anyString(),anyString())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic-secret-marker"));
        var flash=service("https://fixture.example.com/prefix/v1","BEARER","new-key",edit);
        assertThat(values).isEqualTo(before);assertThat(flash.getFlashAttributes()).containsKey("flashError");
        assertThat(flash.getFlashAttributes().get("flashError").toString()).contains("保存失败");
        assertThat(flash.getFlashAttributes().toString()).doesNotContain("synthetic-secret-marker","DataAccess");
    }
}
