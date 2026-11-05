'use strict';
const db=require('../lib/db.cjs');
const {AI_ROUTE,BASE}=require('../lib/browser.cjs');
const guard=require('../../ai-test-guard.cjs');
module.exports={
  name:'60-ai-test-protection',title:'真实服务端拦截 · 连接探测 · 提示词准备记录 · 持久计数',
  async run(ui){
    ui.flow=this.name;
    const before=await guard.state(BASE);
    if(!before.enabled || before.sent!==0) throw new Error('服务端测试保护必须已开启且无实际发送');
    await ui.page.context().unroute(AI_ROUTE);
    try {
      await ui.goto('/admin/ai-access');
      await ui.visible('[data-llm-test-setting]','管理页显示服务端保护设置');
      await ui.assert(await ui.page.isChecked('form[action="/admin/ai-access/test-mode"] [name="enabled"]'),'测试保护已开启');
      await ui.visible('.llm-test-banner','全站普通流提示条可见');
      const epoch=before.epoch;
      const rowBefore=db.num("SELECT COUNT(*) FROM llm_prompt_record WHERE family_id=1 AND outcome='BLOCKED'");
      await ui.click('button[form="llm-test-dashscope"]','真正点击模型连接测试,不使用浏览器桩');
      await ui.page.waitForLoadState('networkidle');
      await ui.seesText('测试环境 · 未调用','服务端明确说明未调用,不伪装连接成功');
      const after=await guard.state(BASE);
      await ui.assert(after.enabled && after.epoch===epoch && after.blocked>before.blocked && after.sent===0,'同一持久窗口新增拦截且真实发送仍为0');
      await ui.assert(db.num("SELECT COUNT(*) FROM llm_prompt_record WHERE family_id=1 AND outcome='BLOCKED'")>rowBefore,'提示词以未发送的独立状态保存');
      await ui.goto('/admin/ai-access');
      await ui.seesText('模型连接自检','入口计数使用可读名称');
      await ui.click('[data-llm-prepared] > summary','查看本家庭准备记录');
      await ui.click('[data-llm-prepared] [data-peek-btn] >> nth=0','打开本次准备的原文');
      await ui.visible('[data-peek-state="BLOCKED"]','终端面板保留未调用状态');
      await ui.assert((await ui.page.locator('[data-peek-state="BLOCKED"]').innerText()).includes('没有发给大模型'),'准备记录不冒充实际发送');
      await ui.click('form[action="/admin/ai-access/test-mode"] button[type="submit"]','重复保存已开启状态');
      const saved=await guard.state(BASE);
      await ui.assert(saved.epoch===epoch && saved.blocked>=after.blocked,'重复保存不清空已发生的拦截记录');
      await ui.noConsoleErrors();
    } finally {await require('../lib/browser.cjs').stubAi(ui.page.context());}
  },
  async cleanup(ui,report){await ui.goto('/admin/ai-access');report.info('终态:服务端保护保持开启,拦截收据和累计计数保留,未改财务事实');}
};
