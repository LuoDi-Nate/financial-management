'use strict';
const db=require('../lib/db.cjs');
const {BASE,AI_ROUTE,stubAi}=require('../lib/browser.cjs');
const guard=require('../../ai-test-guard.cjs');
const {createHash}=require('node:crypto');
const settingKeys=['llm_custom_service_secret','llm_qwen_api_key','llm_deepseek_api_key','llm_ark_api_key','llm_siliconflow_api_key',
  'llm_platform','llm_family','llm_model_id','llm_backup_platform','llm_backup_family','llm_backup_model_id',
  'llm_vision_platform','llm_vision_family','llm_vision_model_id','llm_vision_enabled','llm_temperature','llm_max_tokens','llm_timeout_seconds'];
const where="family_id=1 AND (key_name IN ("+settingKeys.map(k=>"'"+k+"'").join(',')+") OR key_name LIKE 'ask_%')";
const read=()=>db.raw('SELECT key_name,HEX(value_text) FROM family_runtime_config WHERE '+where+' ORDER BY key_name');
const digest=s=>createHash('sha256').update(s).digest('hex');
const own=()=>JSON.parse(db.one("SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='llm_custom_service_secret'"));
let baseline,baselineHash,ownedImport,ownedAccount,questionPrefix;
async function pick(ui,name,value){if(!await ui.pickLsel(name,value))throw new Error('选择器不可操作:'+name);}
async function saveService(ui){await ui.click('#llm-custom-service button:not([form])','保存服务配置');await ui.page.waitForLoadState('networkidle');}
async function saveModels(ui){await ui.click('#llm-models button[type=submit]','保存模型配置');await ui.page.waitForLoadState('networkidle');}
async function center(ui,selector,label){const e=ui.page.locator(selector);await e.evaluate(n=>n.scrollIntoView({block:'center'}));await ui.page.waitForTimeout(250);
  await ui.assert(await e.evaluate(n=>{const r=n.getBoundingClientRect(),top=document.elementsFromPoint(r.x+r.width/2,r.y+r.height/2)[0];return top===n||n.contains(top);}),label);}
async function protectedRun(ui,run){await ui.page.context().unroute(AI_ROUTE);try{return await run();}finally{await stubAi(ui.page.context());}}
async function waitDb(sql,expected){for(let i=0;i<24;i++){if(db.one(sql)===expected)return;await new Promise(r=>setTimeout(r,250));}throw new Error('合成任务状态未按预期完成');}
module.exports={
  name:'63-custom-llm',title:'自有服务真实表单 · 原子配置 · 主备/视觉 · 多入口保护 · 双端交互',
  async run(ui){
    ui.flow=this.name;const start=await guard.state(BASE);if(!start.enabled||start.sent!==0)throw new Error('必须保持服务端保护且实际发送0');
    // Original private values stay in process memory, never in a fixture file or report.
    const original=read();baseline=original.split('\n').filter(Boolean).map(r=>r.split('\t'));baselineHash=digest(original);
    const modelBefore=db.raw("SELECT key_name,HEX(value_text) FROM family_runtime_config WHERE family_id=1 AND key_name IN ('llm_platform','llm_family','llm_model_id') ORDER BY key_name");
    await ui.goto('/admin');await ui.click('a[href="/admin/ai-access"]','管理首页进入AI接入');
    await ui.visible('#llm-custom-service','自有服务与原平台在同页');
    const ready=(await ui.page.locator('#llm-custom-service').getAttribute('data-ready'))==='true';
    await ui.assert((await ui.page.locator('#primaryPlatform option[value=custom]').isDisabled())===!ready,'自研选择器的自有平台状态与服务完整性一致');
    await ui.fill('#customBaseUrl','','清空基础地址验证首错');await saveService(ui);
    await ui.assert(await ui.page.evaluate(()=>document.activeElement.id==='customBaseUrl'),'首次服务配置定位地址首错');
    await ui.fill('#customName','合成自有服务','填写合成服务名称');await ui.fill('#customBaseUrl','https://fixture.example.com/prefix/v1/','填写合成基础地址');
    await pick(ui,'authMode','BEARER');await ui.fill('#customKey','e2e-v142-synthetic-secret','填写合成密钥');await saveService(ui);
    await ui.assert(own().baseUrl==='https://fixture.example.com/prefix/v1','服务保留前缀并去除尾斜杠');
    await ui.assert(own().apiKey==='e2e-v142-synthetic-secret','独立服务凭据落库');
    await ui.assert(await ui.page.locator('#customKey').inputValue()===''&&!(await ui.page.content()).includes('e2e-v142-synthetic-secret'),'密钥输入及HTML不回显明文');
    await ui.assert(db.raw("SELECT key_name,HEX(value_text) FROM family_runtime_config WHERE family_id=1 AND key_name IN ('llm_platform','llm_family','llm_model_id') ORDER BY key_name")===modelBefore,'保存服务不改变模型选型');
    const revision=own().revision;await ui.fill('#customName','合成自有服务改名');await saveService(ui);
    await ui.assert(own().revision===revision,'只改名称不使模型连接身份失效');
    const saved=JSON.stringify(own());await ui.fill('#customBaseUrl','https://other.example.com/v1');await saveService(ui);
    await ui.assert(await ui.page.evaluate(()=>document.activeElement.id==='customKey'),'更改地址时定位重新填密钥');
    await ui.assert(JSON.stringify(own())===saved,'缺新密钥时地址与认证完全不改');
    await ui.fill('#customBaseUrl','https://user:synthetic@fixture.example.com/v1');await saveService(ui);
    await ui.assert(await ui.page.evaluate(()=>document.activeElement.id==='customBaseUrl'),'URL夹带认证被字段拦截');
    await ui.fill('#customBaseUrl','https://fixture.example.com/v1/chat/completions');await saveService(ui);
    await ui.assert(await ui.page.evaluate(()=>document.activeElement.id==='customBaseUrl'),'完整端点不能被当作基础地址');
    await ui.fill('#customBaseUrl','http://127.0.0.1:11434/prefix/v1');await pick(ui,'authMode','NONE');
    await ui.page.locator('#llm-custom-service [name=allowLocalHttp]').uncheck();await saveService(ui);
    await ui.assert(await ui.page.evaluate(()=>document.activeElement.name==='allowLocalHttp'),'HTTP服务需要明确许可');
    await ui.page.locator('#llm-custom-service [name=allowLocalHttp]').check();await saveService(ui);
    await ui.assert(own().authMode==='NONE'&&own().apiKey===''&&own().allowLocalHttp===true,'显式免认证清除旧Key且保留内网许可');
    // Replace all visible masks with synthetic keys before taking any evidence screenshots.
    for(const p of ['dashscope','deepseek','ark','siliconflow']){
      await ui.fill('[data-llm-key='+p+'] [name=apiKey]','e2e-v142-synthetic-'+p,'填写原平台合成凭据 '+p);
      await ui.click('[data-llm-key='+p+'] button:not([form])','独立保存原平台 '+p);await ui.page.waitForLoadState('networkidle');
    }
    await pick(ui,'platform','custom');await pick(ui,'family','custom');await pick(ui,'backupPlatform','');
    await ui.page.locator('#visionEnabled').check();await pick(ui,'visionPlatform','custom');await pick(ui,'visionFamily','custom-vision');
    await ui.fill('#primaryModelId','Org/CaseSensitive:version');await ui.fill('#visionModelId','Org/IndependentVision:version');await saveModels(ui);
    await ui.assert(db.one("SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='llm_model_id'")==='Org/CaseSensitive:version','完整文字型号落库');
    await ui.assert(db.one("SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='llm_vision_model_id'")==='Org/IndependentVision:version','视觉型号独立落库');
    await pick(ui,'backupPlatform','custom');await pick(ui,'backupFamily','custom');await ui.fill('#backupModelId','Org/CaseSensitive:version');await saveModels(ui);
    await ui.assert((await ui.page.locator('[data-llm-triple=backup] [data-llm-error]').innerText()).includes('完全相同'),'同服务同型号备选被拒绝');
    await ui.fill('#backupModelId','Org/Backup');await saveModels(ui);
    await ui.fill('#primaryModelId','auto');await saveModels(ui);
    await ui.assert(db.one("SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='llm_model_id'")==='auto','字面auto保存为显式型号');
    await ui.fill('#primaryModelId','');await saveModels(ui);
    await ui.assert(await ui.page.evaluate(()=>document.activeElement.id==='primaryModelId'),'自有服务空型号首错定位');
    await ui.fill('#primaryModelId','Org/Text');await ui.fill('#visionModelId','Org/DraftVision');
    await ui.page.locator('#visionEnabled').uncheck();await ui.page.locator('#visionEnabled').check();
    await ui.assert(await ui.page.locator('#visionModelId').inputValue()==='Org/DraftVision','关闭再开启截图不丢草稿');await saveModels(ui);
    await protectedRun(ui,async()=>{await ui.click('button[form=llm-test-custom]','实际点击已保存自有模型测试');await ui.page.waitForLoadState('networkidle');await ui.seesText('测试环境 · 未调用','连接测试由服务端保护拦截');});
    // A stale second tab must not overwrite a newer saved service.
    const stale=await ui.page.context().newPage();
    try{
      await stale.goto(BASE+'/admin/ai-access',{waitUntil:'networkidle'});
      await ui.fill('#customName','新页合成名称');await saveService(ui);
      await stale.fill('#customName','旧页合成名称');await stale.locator('#llm-custom-service button:not([form])').click();await stale.waitForLoadState('networkidle');
      await ui.assert((await stale.locator('body').innerText()).includes('配置已更新')&&own().displayName==='新页合成名称','旧页提交不覆盖新配置');
    }finally{await stale.close();}
    const longModel='Org/'+'Case'.repeat(63);
    for(const width of [1440,390,320]){
      const verify=async m=>{
        await m.goto('/admin/ai-access');await pick(m,'authMode','BEARER');
        await m.fill('#customName','SyntheticCustomServiceNameForLayoutValidation012345');
        await m.fill('#customBaseUrl','https://fixture.example.com/'+ 'prefix'.repeat(24)+'/v1');
        await m.fill('#customKey','e2e-v142-layout-synthetic-key');await saveService(m);
        await m.fill('#primaryModelId',longModel);await saveModels(m);
        await m.assert(await m.page.evaluate(w=>innerWidth===w&&document.documentElement.scrollWidth<=w,width),'实际视口'+width+'无横向溢出或缩放');
        await center(m,'#llm-custom-service button:not([form])','视口'+width+'服务保存中心命中');
        await center(m,'button[form=llm-test-custom]','视口'+width+'连接测试中心命中');
        await center(m,'#llm-models button[type=submit]','视口'+width+'模型保存中心命中');
        await m.fill('#primaryModelId','bad model');await saveModels(m);
        await m.assert(await m.page.evaluate(()=>document.activeElement.id==='primaryModelId'),'视口'+width+'型号首错定位');
        await m.fill('#primaryModelId',longModel);await saveModels(m);
        await m.page.locator('#llm-custom-service').screenshot({path:'/tmp/issue-43-beta-'+width+'-service.png'});
        await m.page.locator('#llm-models').screenshot({path:'/tmp/issue-43-beta-'+width+'-models.png'});
        await m.noConsoleErrors();
      };
      if(width===1440)await verify(ui);else await ui.device(width,844,verify);
    }
    await ui.goto('/admin/ai-access');
    await protectedRun(ui,async()=>{
      await ui.click('a[href="/checkup"]:visible >> nth=0','从顶栏进入体检');
      await ui.page.waitForTimeout(800);await ui.click('#ai-diagnose-panel button[hx-get]','真实点击文字分析刷新');
      await ui.page.waitForTimeout(800);await ui.seesText('测试环境 · 未调用','文字分析真实入口保持拦截');
    });
    // Use an account with no unfinished import; no existing task or image is touched.
    await ui.click('a[href="/entry"]:visible >> nth=0','从顶栏进入填报');
    const candidates=ui.page.locator('a[href^="/entry/import/"]');
    for(let i=0;i<await candidates.count();i++){
      const href=await candidates.nth(i).getAttribute('href'),id=Number(href.match(/\/entry\/import\/(\d+)/)[1]);
      if(db.num("SELECT COUNT(*) FROM holding_import WHERE family_id=1 AND account_id="+id+" AND status IN ('UPLOADING','SCANNING','REVIEW','SCAN_ERROR')")===0){
        ownedAccount=id;await candidates.nth(i).click();await ui.page.waitForLoadState('networkidle');break;
      }
    }
    if(!ownedAccount)throw new Error('没有可供合成导入验收的空闲账户');
    ownedImport=Number(await ui.page.locator('#importSection').getAttribute('data-import'));
    const chooser=ui.page.waitForEvent('filechooser');await ui.click('#dropZone','选择合成图片');
    await (await chooser).setFiles({name:'v142-synthetic.png',mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1cAAAAASUVORK5CYII=','base64')});
    await ui.page.waitForFunction(()=>!document.getElementById('scanBtn').disabled);
    await ui.seesText('以服务账单为准','自有服务不套用内置平台的费用');
    await protectedRun(ui,async()=>{await ui.click('#scanBtn','真实点击截图识别');await waitDb('SELECT status FROM holding_import WHERE id='+ownedImport,'SCAN_ERROR');await ui.page.waitForTimeout(1800);});
    await ui.page.reload({waitUntil:'networkidle'});await ui.seesText('测试环境 · 未调用','截图异步任务由服务端保护拦截');
    await ui.assert(db.num('SELECT COUNT(*) FROM holding_import_item WHERE import_id='+ownedImport)===0,'保护不生成空识别或伪卖出比对行');
    await ui.visible('[data-peek-btn]','截图任务保留准备内容来源');
    await ui.goto('/admin/ai-access');
    const ask='form[action="/admin/ai-access/ask-settings"]';
    await ui.page.locator(ask+' [name=enabled]').check();await ui.page.locator(ask+' [name=runtime][value=local]').check();
    for(const input of await ui.page.locator(ask+' input[type=text], '+ask+' input[type=url]').all())await input.fill('');
    await ui.click(ask+' button[type=submit]','临时启用本地Agent作保护验收');await ui.page.waitForLoadState('networkidle');
    await ui.click('a[href="/ask"]:visible >> nth=0','从管理卡片进入Agent');
    questionPrefix='qa142-'+Date.now();await ui.fill('[data-ask-input]',questionPrefix+' 合成保护检查','填写合成问题');
    await protectedRun(ui,async()=>{await ui.click('[data-ask-send]','真实发送本地Agent提问');await ui.page.waitForTimeout(1200);await ui.seesText('测试环境 · 未调用','Agent真实入口不发送模型或执行工具');});
    const finish=await guard.state(BASE);await ui.assert(finish.enabled&&finish.epoch===start.epoch&&finish.sent===0&&finish.blocked>start.blocked,'同epoch所有实际模型发送为0');
    await ui.noConsoleErrors();
  },
  async cleanup(ui,report){
    let issue;
    try{
      if(ownedImport){
        await ui.goto('/entry/import/'+ownedAccount+'?from=/entry');
        while(await ui.page.locator('.js-gallery .grm').count()){
          ui.page.once('dialog',d=>d.accept());await ui.click('.js-gallery .grm >> nth=0','删除本轮合成图片');await ui.page.waitForTimeout(500);
        }
        if(db.num('SELECT img_count FROM holding_import WHERE id='+ownedImport)!==0)throw new Error('合成图片未清理');
        db.raw('DELETE FROM holding_import_item WHERE import_id='+ownedImport+' AND EXISTS (SELECT 1 FROM holding_import WHERE id='+ownedImport+' AND family_id=1)');
        db.raw('DELETE FROM holding_import WHERE id='+ownedImport+' AND family_id=1 AND account_id='+ownedAccount+' AND img_count=0');
      }
      if(questionPrefix)db.raw("DELETE FROM ask_conversation WHERE family_id=1 AND title LIKE '"+questionPrefix+"%'");
    }catch(e){issue=e;}
    finally{
      if(baseline){
        db.raw('DELETE FROM family_runtime_config WHERE '+where);
        for(const [key,hex] of baseline)db.raw("INSERT INTO family_runtime_config(family_id,key_name,value_text) VALUES(1,CONVERT(UNHEX('"+Buffer.from(key).toString('hex')+"') USING utf8mb4),CONVERT(UNHEX('"+(hex||'')+"') USING utf8mb4))");
        if(digest(read())!==baselineHash)throw new Error('原配置还原校验失败');baseline=null;
        await new Promise(r=>setTimeout(r,5300));
      }
      await ui.goto('/admin/ai-access');report.info('原服务/凭据/选型/Agent设置已按内存基线还原;保护开关、epoch与累计计数保留');
    }
    if(issue)throw issue;
  }
};
