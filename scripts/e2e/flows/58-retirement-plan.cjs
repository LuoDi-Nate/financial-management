/* A separate synthetic family isolates the all-household debt and historical-source
 * contracts from the maintainer's beta fixtures. Historical months are seeded directly;
 * every plan action uses the actual UI. No period-close or model endpoint is called. */
const db = require('../lib/db.cjs');
const { BASE, USER, PASS } = require('../lib/browser.cjs');
const fs = require('fs');
const path = require('path');
const state = { family:null, member:null, cash:null, native:null, legacy:null, username:null, prefix:null };
const q = value => "'" + String(value).replace(/'/g,"''") + "'";

async function login(page, username) {
  await page.context().clearCookies();
  await page.goto(BASE + '/login', {waitUntil:'networkidle'});
  await page.fill('[name="username"]',username);
  await page.fill('[name="password"]',PASS);
  await page.click('button[type="submit"]');
  await page.waitForLoadState('networkidle');
}
async function form(ui, name) {
  await ui.click('[data-retire-tab="plan"]', '调整退休计划');
  for (const [key,value] of Object.entries({name,targetMonth:'2036-11',monthlyExpense:'10000',monthlyIncome:'0',
    monthlyContribution:'0',childReserve:'400000',medicalReserve:'300000',housingReserve:'0',otherReserve:'500000',inflationPct:'0',realReturnPct:'2.5'}))
    await ui.fillByName(key,value,'#retirement-form');
  await ui.page.check('[name="directRealRate"]');
  for (const key of ['budgetConfirmed','reservesConfirmed','noOverlapConfirmed','assetsConfirmed','incomeConfirmed','contributionConfirmed','afterDebtConfirmed'])
    await ui.page.check(`[name="${key}"]`);
}
async function screenshot(ui, suffix) {
  const dir = '/tmp/finance-v139-review'; fs.mkdirSync(dir,{recursive:true});
  await ui.page.screenshot({path:path.join(dir,suffix + '.png'),fullPage:true});
}
async function dimensions(ui, label) {
  await ui.assert(await ui.page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),label + ':无横向溢出');
  for (const button of await ui.page.locator('[data-retire-tab]').all()) {
    const clear = await button.evaluate(el => {
      const r=el.getBoundingClientRect(), hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
      return !!hit && (hit===el || el.contains(hit));
    });
    await ui.assert(clear,label + ':页签中心可点击');
  }
}

module.exports = {
  name:'58-retirement-plan', title:'退休透明公式 · 旧新采用/退出 · 来源同窗 · 三币种 · 双端',
  async run(ui,report) {
    ui.flow=this.name;
    state.prefix='e2e-retire139-' + Date.now(); state.username=state.prefix;
    db.raw(`INSERT INTO family(name,brand_text,base_currency,period_type,expense_entry_mode) VALUES(${q(state.prefix)},'演算样本','CNY','MONTHLY','ITEMIZED')`);
    state.family=db.num(`SELECT id FROM family WHERE name=${q(state.prefix)}`);
    db.raw(`INSERT INTO member(family_id,username,password_hash,display_name,must_change_pw)
      SELECT ${state.family},${q(state.username)},password_hash,'演算成员',0 FROM member WHERE username=${q(USER)}`);
    state.member=db.num(`SELECT id FROM member WHERE username=${q(state.username)}`);
    if (!state.member) throw new Error('隔离家庭的登录样本未创建');
    const current=db.one('SELECT DATE_FORMAT(CURDATE(),\'%Y-%m-01\')');
    for (const [name,type] of [['cash','CASH'],['debt','LOAN'],['home','PROPERTY']])
      db.raw(`INSERT INTO account(family_id,display_name,type,currency) VALUES(${state.family},${q(state.prefix+'-'+name)},'${type}','CNY')`);
    state.cash=db.num(`SELECT id FROM account WHERE family_id=${state.family} AND type='CASH'`);
    for (let back=3;back>=0;back--) {
      db.raw(`INSERT INTO period(family_id,period_type,period_start,period_end,status)
        VALUES(${state.family},'MONTHLY',DATE_SUB('${current}',INTERVAL ${back} MONTH),LAST_DAY(DATE_SUB('${current}',INTERVAL ${back} MONTH)),'${back?'CLOSED':'OPEN'}')`);
      const pid=db.num(`SELECT id FROM period WHERE family_id=${state.family} AND period_start=DATE_SUB('${current}',INTERVAL ${back} MONTH)`);
      db.raw(`INSERT INTO period_snapshot(period_id,account_id,end_balance,submitted_by,source_tag)
        SELECT ${pid},id,CASE type WHEN 'CASH' THEN 6100000 WHEN 'LOAN' THEN 100000 ELSE 10000000 END,${state.member},'MANUAL'
        FROM account WHERE family_id=${state.family}`);
      db.raw(`INSERT INTO fx_rate(family_id,period_id,base_currency,quote_currency,rate,source) VALUES
        (${state.family},${pid},'CNY','USD',0.125,'MANUAL'),(${state.family},${pid},'CNY','HKD',1.1,'MANUAL')`);
      if (back) {
        const income=(5-back)*10000;
        db.raw(`INSERT INTO cash_flow(period_id,account_id,kind,category_code,amount,submitted_by,note) VALUES
          (${pid},${state.cash},'INCOME','salary',${income},${state.member},'合成收入'),
          (${pid},${state.cash},'EXPENSE','consumption',10000,${state.member},'合成消费')`);
        if (back===3) db.raw(`INSERT INTO period_member_cashflow(family_id,period_id,member_id,total_income_input,total_expense_input)
          VALUES(${state.family},${pid},${state.member},${income},10000)`);
      }
    }
    db.raw(`INSERT INTO family_goal(family_id,goal_type,name,target_value,target_date,metric,comparator,time_mode,params_json)
      VALUES(${state.family},'RETIREMENT',${q(state.prefix+'-legacy')},123456,'2040-01-01','AMOUNT_TOTAL','GTE','OPEN',
        JSON_OBJECT('current_age',35,'retire_age',60,'monthly_expense',10000,'inflation_rate',0.025,'withdrawal_rate',0.04))`);
    state.legacy=db.num(`SELECT id FROM family_goal WHERE family_id=${state.family}`);
    const legacyBefore=db.one(`SELECT CONCAT(params_json,'|',target_date,'|',target_value) FROM family_goal WHERE id=${state.legacy}`);
    const callsBefore=db.num(`SELECT COUNT(*) FROM llm_prompt_record WHERE family_id=${state.family}`);
    await login(ui.page,state.username);
    await ui.goto('/goals');
    await ui.click('a[href="/goals/new"]','从目标页新建');
    await ui.click('a[href="/goals/new/retirement"]','选择退休目标');
    await ui.visible('#retirement-form','退休计划真实入口');
    await ui.assert(await ui.page.inputValue('[name="monthlyContribution"]') === '20000.00' ||
      Number(await ui.page.inputValue('[name="monthlyContribution"]'))===20000,'月投入建议包含旧总额和新账户收入,未停留在旧月份');
    await ui.assert(!(await ui.page.isChecked('[name="accountIds"][value="'+db.num(`SELECT id FROM account WHERE family_id=${state.family} AND type='PROPERTY'`)+'"]')),'自住房默认未纳入');
    await form(ui,state.prefix+'-native');
    await ui.click('[data-retire-preview]','保存前执行系统演算');
    await ui.page.waitForLoadState('networkidle');
    await ui.seesText('¥4,800,000.00','门槛与净生活本金均为480万');
    await ui.assert((await ui.page.locator('[data-retire-value="coverage"]').innerText()).includes('100.00%'),'负债与预留只扣一次,覆盖100%');
    await ui.assert(db.num(`SELECT COUNT(*) FROM retirement_plan WHERE family_id=${state.family}`)===0,'演算预览不保存');
    await ui.click('[data-retire-tab="plan"]','返回调整计划');
    await ui.click('[data-retire-save]','保存新退休计划');
    await ui.page.waitForLoadState('networkidle');
    state.native=db.num(`SELECT goal_id FROM retirement_plan WHERE family_id=${state.family} AND created_for_plan=1`);
    await ui.assert(!!state.native && db.num(`SELECT active FROM retirement_plan WHERE goal_id=${state.native}`)===1,'新方案已持久化并启用');
    await ui.goto(`/goals/${state.native}/retirement`);
    await dimensions(ui,'电脑');
    await ui.assert(await ui.page.locator('#retirement-path').evaluate(el => {
      const pixels=el.getContext('2d').getImageData(0,0,el.width,el.height).data;
      let count=0;for(let i=3;i<pixels.length;i+=4)if(pixels[i])count++;return count>1000;
    }),'三情景画布有实际像素');
    await screenshot(ui,'desktop-overview');
    for (const [ccy,target] of [['USD','$600,000.00'],['HKD','HK$5,280,000.00']]) {
      await ui.click(`.retire-currencies a[href*="ccy=${ccy}"]`,'切换显示币种 '+ccy);
      await ui.assert((await ui.page.locator('[data-retire-value="target"]').innerText()).includes(target),'本金门槛按汇率换算');
      await ui.assert((await ui.page.locator('[data-retire-value="coverage"]').innerText()).includes('100.00%'),'覆盖率不随显示币种变化');
    }
    await ui.click('[data-retire-tab="principle"]','打开原理');
    await ui.visible('a[href="https://www.bilibili.com/video/BV1yUHP6rEYD/"]','原视频直达链接');
    await ui.assert(await ui.page.locator('a[href*="BV1yUHP6rEYD"]').getAttribute('referrerpolicy')==='no-referrer','来源外链不带家庭来源数据');
    await screenshot(ui,'desktop-principle');
    await ui.click('[data-retire-tab="plan"]','检查零回报边界');
    await ui.fillByName('realReturnPct','0','#retirement-form'); await ui.click('[data-retire-save]','保存零实际回报');
    await ui.seesText('没有有限的永久本金门槛','零回报不显示0元门槛');
    await ui.goto('/goals'); await ui.seesText('没有有限的永久本金门槛','目标列表与详情同一状态');
    await ui.goto(`/goals/${state.native}/retirement?tab=plan`); await ui.fillByName('realReturnPct','2.5','#retirement-form');
    await ui.click('[data-retire-save]','恢复正实际回报');

    await ui.goto(`/goals/${state.legacy}`);
    await ui.click(`a[href="/goals/${state.legacy}/retirement"]`,'老目标显式比较新算法');
    await form(ui,state.prefix+'-adopted');
    await ui.click('[data-retire-save]','未确认采用不能迁移');
    await ui.seesText('请先比较旧新算法','服务端拦截未经确认的采用');
    await ui.page.check('[name="adoptionConfirmed"]'); await ui.click('[data-retire-save]','确认采用新算法');
    await ui.assert(db.one(`SELECT CONCAT(params_json,'|',target_date,'|',target_value) FROM family_goal WHERE id=${state.legacy}`)===legacyBefore,'采用不覆盖原始目标参数');
    await ui.click('[data-retire-tab="plan"]','退出新算法入口');
    await ui.click('[data-retire-revert]','恢复原算法');
    await ui.assert(db.num(`SELECT active FROM retirement_plan WHERE goal_id=${state.legacy}`)===0,'退出只关闭新方案');
    await ui.assert(db.one(`SELECT CONCAT(params_json,'|',target_date,'|',target_value) FROM family_goal WHERE id=${state.legacy}`)===legacyBefore,'退出恢复完全相同的旧参数');

    await ui.phone(async mobile => {
      await login(mobile.page,state.username);
      await mobile.goto(`/goals/${state.native}/retirement`);
      await dimensions(mobile,'手机'); await screenshot(mobile,'mobile-overview');
      await mobile.click('[data-retire-tab="plan"]','手机调整计划');
      await mobile.assert(await mobile.page.locator('[data-retire-save]').count()===1,'手机仅一份表单与保存入口');
      await screenshot(mobile,'mobile-plan');
      await mobile.click('[data-retire-tab="principle"]','手机查看原理');
      await screenshot(mobile,'mobile-principle');
    });
    await ui.goto('/admin/ai-access'); await ui.rendered('AI 接入页');
    await ui.visible('#template-status','模板状态不阻塞主页面');
    await ui.assert(db.num(`SELECT COUNT(*) FROM llm_prompt_record WHERE family_id=${state.family}`)===callsBefore,'浏览器回归未调用真实模型');
    await login(ui.page,USER);
  },
  async cleanup(ui,report) {
    if (!state.family) return;
    const f=state.family;
    db.raw(`DELETE FROM goal_ai_report WHERE goal_id IN(SELECT id FROM family_goal WHERE family_id=${f});
      DELETE FROM retirement_plan WHERE family_id=${f}; DELETE FROM family_goal WHERE family_id=${f};
      DELETE FROM audit_log WHERE family_id=${f};
      DELETE c FROM cash_flow c JOIN period p ON p.id=c.period_id WHERE p.family_id=${f};
      DELETE s FROM period_snapshot s JOIN period p ON p.id=s.period_id WHERE p.family_id=${f};
      DELETE t FROM snapshot_todo t JOIN period p ON p.id=t.period_id WHERE p.family_id=${f};
      DELETE FROM period_member_cashflow WHERE family_id=${f};
      DELETE c FROM period_member_completion c JOIN period p ON p.id=c.period_id WHERE p.family_id=${f};
      DELETE a FROM period_account_attr a JOIN period p ON p.id=a.period_id WHERE p.family_id=${f};
      DELETE g FROM period_account_group g JOIN period p ON p.id=g.period_id WHERE p.family_id=${f};
      DELETE FROM fx_rate WHERE family_id=${f}; DELETE FROM period WHERE family_id=${f};
      DELETE FROM account WHERE family_id=${f}; DELETE FROM family_runtime_config WHERE family_id=${f};
      DELETE FROM member WHERE family_id=${f}; DELETE FROM family WHERE id=${f};`);
    report.info('已清理隔离家庭与全部退休样本,原 beta 家庭未改动'); state.family=null;
  }
};
