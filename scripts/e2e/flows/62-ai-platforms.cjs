'use strict';
const db=require('../lib/db.cjs');
const {AI_ROUTE,BASE,stubAi}=require('../lib/browser.cjs');
const guard=require('../../ai-test-guard.cjs');
const platforms=[['dashscope','qwen'],['deepseek','deepseek'],['ark','doubao'],['siliconflow','deepseek']];
const keyNames=['llm_qwen_api_key','llm_deepseek_api_key','llm_ark_api_key','llm_siliconflow_api_key'];
const settingNames=['llm_platform','llm_family','llm_model_id','llm_backup_platform','llm_backup_family','llm_backup_model_id',
  'llm_vision_platform','llm_vision_family','llm_vision_model_id','llm_vision_enabled','llm_temperature','llm_max_tokens','llm_timeout_seconds'];
const keys=[...keyNames,...settingNames];
const where="family_id=1 AND key_name IN ("+keys.map(k=>"'"+k+"'").join(',')+")";
let baseline;
async function saveModels(ui){await ui.click('#llm-models button[type=submit]','保存模型配置');await ui.page.waitForLoadState('networkidle');}
async function pick(ui,name,value){if(!await ui.pickLsel(name,value))throw new Error('选择器不可操作:'+name);}
async function center(ui,selector,label){const el=ui.page.locator(selector);await ui.page.evaluate(()=>document.activeElement.blur());await el.scrollIntoViewIfNeeded();await ui.page.waitForTimeout(350);await ui.assert(await el.evaluate(n=>{const r=n.getBoundingClientRect();const top=document.elementsFromPoint(r.x+r.width/2,r.y+r.height/2)[0];return top===n||n.contains(top);}),label);}
module.exports={
  name:'62-ai-platforms',title:'四平台凭据与模型同规则 · 完整ID · 服务端保护 · 手机实点',
  async run(ui){
    ui.flow=this.name;const before=await guard.state(BASE);
    if(!before.enabled||before.sent!==0)throw new Error('必须保持服务端保护开启且实际发送0');
    // 只在进程内暂存待还原的私密配置,不写文件、不打印值;所有业务动作从页面发起。
    baseline=db.raw('SELECT key_name,HEX(value_text) FROM family_runtime_config WHERE '+where).split('\n').filter(Boolean).map(row=>row.split('\t'));
    const modelBefore=db.raw("SELECT key_name,HEX(value_text) FROM family_runtime_config WHERE family_id=1 AND key_name IN ('llm_platform','llm_family','llm_model_id') ORDER BY key_name");
    await ui.goto('/admin');await ui.click('a[href="/admin/ai-access"]','从管理首页进入AI接入');
    await ui.assert(await ui.page.locator('[data-llm-key]').count()===platforms.length,'目录每个平台都有一张凭据卡');
    for(let i=0;i<platforms.length;i++){
      const p=platforms[i][0],scope='[data-llm-key="'+p+'"]';
      await ui.click(scope+' button[type=submit]:not([form])','空密钥保存给字段提示');
      await ui.assert((await ui.page.locator(scope+' [data-llm-error]').innerText()).includes('未改动'),'空密钥不报成功 '+p);
      const value='e2e-synthetic-'+p+'-key-123456789';
      await ui.fill(scope+' input[name=apiKey]',value,'填写合成密钥 '+p);
      await ui.click(scope+' button[type=submit]:not([form])','独立保存密钥 '+p);await ui.page.waitForLoadState('networkidle');
      await ui.assert(db.one("SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='"+keyNames[i]+"'")===value,'独立凭据已落库 '+p);
      await ui.assert(await ui.page.locator(scope+' input[name=apiKey]').inputValue()==='','密码输入不回显 '+p);
      await ui.assert(!(await ui.page.content()).includes(value),'原密钥不进入HTML '+p);
      await ui.click(scope+' .help-fold summary','打开获取帮助 '+p);await ui.visible(scope+' .help-body a','获取密钥入口可见 '+p);
    }
    await ui.assert(db.raw("SELECT key_name,HEX(value_text) FROM family_runtime_config WHERE family_id=1 AND key_name IN ('llm_platform','llm_family','llm_model_id') ORDER BY key_name")===modelBefore,'保存四把密钥没有改选型');
    await pick(ui,'backupPlatform','');await ui.page.locator('#visionEnabled').uncheck();
    for(const [p,f] of platforms){
      await pick(ui,'platform',p);await pick(ui,'family',f);
      const model=p==='siliconflow'?'Pro/Org/CaseSensitive-Model':'Custom-'+p+'-Case';
      await ui.fill('#primaryModelId',model);await saveModels(ui);
      await ui.assert(db.one("SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='llm_model_id'")===model,'完整型号原样落库 '+p);
      await ui.assert(db.one("SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='llm_platform'")===p,'平台归属正确 '+p);
      await ui.assert(await ui.page.locator('#primaryModelId').inputValue()===model,'刷新后完整型号回读 '+p);
      await ui.fill('#primaryModelId','https://invalid.example/model');await saveModels(ui);
      await ui.assert(await ui.page.evaluate(()=>document.activeElement.id==='primaryModelId'),'首错定位 '+p);
      await ui.assert(db.one("SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='llm_model_id'")===model,'无效草稿未改有效配置 '+p);
      await ui.fill('#primaryModelId','');await saveModels(ui);
      await ui.assert(db.one("SELECT LENGTH(value_text) FROM family_runtime_config WHERE family_id=1 AND key_name='llm_model_id'")==='0','留空自动可保存 '+p);
    }
    await pick(ui,'family','other');await saveModels(ui);
    await ui.assert((await ui.page.locator('[data-llm-triple=primary] [data-llm-error]').innerText()).includes('没有默认'),'无默认系列明确必填');
    await pick(ui,'family','deepseek');await ui.fill('#primaryModelId','Pro/Org/CaseSensitive-Model');
    await ui.page.locator('#visionEnabled').check();await pick(ui,'visionPlatform','siliconflow');await pick(ui,'visionFamily','kimi');
    await ui.fill('#visionModelId','Org/VisionCase');await saveModels(ui);
    await ui.assert(await ui.page.locator('#visionModelId').inputValue()==='Org/VisionCase','视觉独立配置回读');
    await ui.page.context().unroute(AI_ROUTE);
    try{for(const [p] of platforms){await ui.click('button[form="llm-test-'+p+'"]','真实点击连接检查 '+p);await ui.page.waitForLoadState('networkidle');await ui.seesText('测试环境 · 未调用','服务端保护如实反馈 '+p);}}
    finally{await stubAi(ui.page.context());}
    await ui.page.locator('#llm').screenshot({path:'/tmp/issue-42-beta-desktop.png'});
    for(const width of [390,320])await ui.device(width,844,async mobile=>{
      await mobile.goto('/admin/ai-access');
      await mobile.assert(await mobile.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'手机'+width+'无横向溢出');
      await pick(mobile,'platform','siliconflow');await mobile.fill('#primaryModelId','bad model');await saveModels(mobile);
      await mobile.assert(await mobile.page.evaluate(()=>document.activeElement.id==='primaryModelId'),'手机'+width+'首错定位');
      await center(mobile,'[data-llm-key=siliconflow] button:not([form])','手机'+width+'保存密钥中心命中');
      await center(mobile,'button[form=llm-test-siliconflow]','手机'+width+'连接按钮中心命中');
      await mobile.page.locator('[data-llm-key=siliconflow]').screenshot({path:'/tmp/issue-42-beta-mobile-'+width+'-key.png'});
      await center(mobile,'#llm-models button[type=submit]','手机'+width+'模型保存中心命中');
      await mobile.page.locator('#llm-models').screenshot({path:'/tmp/issue-42-beta-mobile-'+width+'-models.png'});
      await mobile.noConsoleErrors();
    });
    await ui.noConsoleErrors();const after=await guard.state(BASE);
    await ui.assert(after.enabled&&after.epoch===before.epoch&&after.sent===0,'同一保护窗口实际发送0');
  },
  async cleanup(ui,report){
    if(baseline){
      db.raw('DELETE FROM family_runtime_config WHERE '+where);
      for(const [key,hex] of baseline)db.raw("INSERT INTO family_runtime_config(family_id,key_name,value_text) VALUES (1,'"+key+"',CONVERT(UNHEX('"+(hex||'')+"') USING utf8mb4))");
      baseline=null;await new Promise(r=>setTimeout(r,5300));
    }
    await ui.goto('/admin/ai-access');report.info('原密钥/选型/参数已还原;保护epoch与累计计数保留,未改财务事实');
  }
};
