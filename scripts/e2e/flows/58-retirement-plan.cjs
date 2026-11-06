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
async function directMode(ui,on) {
  const section=ui.page.locator('#retire-returns details[data-retire-when="manual-return"]');
  if(!await section.evaluate(el=>el.open)) await ui.click('#retire-returns details[data-retire-when="manual-return"] > summary','展开实际回报选项');
  await ui.page.setChecked('[name="directRealRate"]',on);
}
async function form(ui, name) {
  await ui.click('[data-retire-tab="plan"]', '调整退休计划');
  await ui.page.check('[name="autoExpense"][value="false"]');
  await ui.choose('reserveMode','CUSTOM');await ui.choose('returnSource','MANUAL');await ui.choose('inflationSource','MANUAL');
  if(!await ui.page.locator('#retire-scope').evaluate(el=>el.open)) await ui.click('#retire-scope > summary','展开资金范围');
  await ui.choose('scopeMode','CUSTOM');
  if(!await ui.page.locator('#retire-returns details[data-retire-when="manual-return"]').evaluate(el=>el.open)) await ui.click('#retire-returns details[data-retire-when="manual-return"] > summary','展开直接实际回报');
  for (const [key,value] of Object.entries({name,targetMonth:'2036-11',monthlyExpense:'10000',
    childReserve:'400000',medicalReserve:'300000',housingReserve:'0',otherReserve:'500000',inflationPct:'0',realReturnPct:'2.5'}))
    await ui.fillByName(key,value,'#retirement-form');
  await ui.page.check('[name="directRealRate"]');
  for (const checkbox of await ui.page.locator('input[name="accountIds"]').all()) await checkbox.uncheck();
  await ui.page.check(`input[name="accountIds"][value="${state.cash}"]`);
  await ui.page.check('[name="noOverlapConfirmed"]');
  await ui.click('[data-retire-tab="overview"]','查看可选未来积累');
  if(!await ui.page.locator('.retire-future').evaluate(el=>el.open)) await ui.click('.retire-future > summary','展开未来积累');
  await ui.page.check('[name="simulateFuture"]');await ui.choose('contributionSource','MANUAL');
  await ui.fillByName('monthlyContribution','0');
  await ui.click('[data-retire-tab="plan"]','返回计划');
}
async function screenshot(ui, suffix) {
  const dir = '/tmp/finance-v139-review'; fs.mkdirSync(dir,{recursive:true});
  await ui.page.evaluate(()=>window.scrollTo({top:0,behavior:'instant'}));
  await ui.page.waitForTimeout(200);
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
  await ui.assert(await ui.page.locator('.m-cards td').evaluateAll(cells => cells.filter(el=>el.getClientRects().length)
    .every(el=>el.scrollWidth<=el.clientWidth+2)),label + ':表格长文本在单元格内换行');
}

async function settings(ui, label, reference) {
  const fields = await ui.page.locator('#retirement-form input:not([type=hidden]):not([type=checkbox]):not([type=radio]), #retirement-form select').evaluateAll(inputs => inputs.map(el => {
    const css=getComputedStyle(el), r=el.getBoundingClientRect();
    return {name:el.name, type:el.type, shown:!!el.getClientRects().length, labelled:!!el.labels?.length,
      border:[css.borderTopWidth,css.borderRightWidth,css.borderBottomWidth,css.borderLeftWidth],
      background:css.backgroundColor, font:css.fontFamily, size:parseFloat(css.fontSize), align:css.textAlign,
      unit:!!el.labels?.[0]?.querySelector('.retire-unit') || /CNY|USD|HKD|%/.test(el.labels?.[0]?.textContent || ''), fits:r.right<=innerWidth+1 && r.left>=0};
  }));
  const visible=fields.filter(f=>f.shown);
  await ui.assert(visible.every(f=>f.border.join(',')==='0px,0px,1px,0px' && f.background==='rgba(0, 0, 0, 0)'),label+':登记字段沿用账册下划线与透明底');
  await ui.assert(fields.every(f=>f.labelled),label+':每个字段都有可读标签');
  await ui.assert(visible.every(f=>f.size>=16 && f.fits),label+':输入字号不触发手机缩放且控件不出屏');
  await ui.assert(visible.filter(f=>f.type==='number').every(f=>f.align==='right' && f.unit),label+':所有数值右对齐并标明金额或百分比单位');
  if(reference && visible.some(f=>f.name==='monthlyExpense')) await ui.assert(visible.find(f=>f.name==='monthlyExpense').font===reference.font && visible.find(f=>f.name==='monthlyExpense').size===reference.size,label+':金额字体及字号与现有账户登记表一致');
  await ui.assert(await ui.page.locator('#retirement-form .field-label').evaluateAll(labels=>labels.every(el=>parseFloat(getComputedStyle(el).fontSize)>=12)),label+':中文字段名不小于12px');
  await ui.assert(await ui.page.locator('#retirement-form').evaluate(el=>el.getBoundingClientRect().width<=800),label+':表单限制填写宽度');
  if(await ui.page.locator('#float-dock').isVisible()) {
    let clear=true;
    for(const field of await ui.page.locator('#retirement-form .field-input').all()) {
      if(!await field.isVisible()) continue;
      await field.scrollIntoViewIfNeeded();
      clear=clear && await field.evaluate(el=>{
        const r=el.getBoundingClientRect(), dock=document.querySelector('#float-dock').getBoundingClientRect();
        return r.right<=dock.left || r.left>=dock.right || r.bottom<=dock.top || r.top>=dock.bottom;
      });
    }
    await ui.assert(clear,label+':系统工具与每个输入框的整个区域不重叠');
    await ui.page.locator('#priv-float').scrollIntoViewIfNeeded();
    await ui.click('#priv-float','表单末尾仍能切换金额隐私');
    await ui.assert(await ui.page.locator('html').evaluate(el=>el.classList.contains('privacy')),label+':金额隐私工具仍有效');
    await ui.click('#priv-float','恢复显示金额');
  }
  const expense=await ui.page.inputValue('[name="monthlyExpense"]');
  await ui.click('.retire-details > summary:has-text("查看月份与支出口径")','展开消费来源');
  await ui.assert(await ui.page.inputValue('[name="monthlyExpense"]')===expense,label+':查看来源保留当前输入');
  await ui.click('.retire-details > summary:has-text("查看月份与支出口径")','收起消费来源');
  await ui.click('.retire-settings-nav a[href="#retire-returns"]','按章节跳到回报设置');
  await ui.assert(await ui.page.locator('#retire-returns').evaluate(el=>{
    const r=el.getBoundingClientRect();return r.top>=64 && r.top<innerHeight-40;
  }),label+':章节定位避开顶栏');
  await ui.click('[data-retire-tab="principle"]','从设置打开公式来源');
  await ui.visible('#retire-principle','原理视图实际打开');
  await ui.click('[data-retire-tab="plan"]','回到计划保留输入');
  await ui.assert(await ui.page.inputValue('[name="monthlyExpense"]')===expense,label+':切换原理再回来保留输入');
  for(const selector of ['[data-retire-preview]','[data-retire-save]']) {
    await ui.page.locator(selector).scrollIntoViewIfNeeded();
    await ui.assert(await ui.page.locator(selector).evaluate(el=>{
      const r=el.getBoundingClientRect(), hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);
      return !!hit && (hit===el || el.contains(hit));
    }),label+':操作按钮中心未被浮钮遮挡');
  }
  await ui.sameSize('.retire-form-actions button',label+':预览与保存同宽同高');
  await ui.page.locator('#retirement-form').scrollIntoViewIfNeeded();
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
    for (const [name,type] of [['cash','CASH'],['debt','LOAN'],['home','PROPERTY'],['fx','CASH']])
      db.raw(`INSERT INTO account(family_id,display_name,type,currency) VALUES(${state.family},${q(state.prefix+'-'+name)},'${type}','${name==='fx'?'USD':'CNY'}')`);
    state.cash=db.num(`SELECT id FROM account WHERE family_id=${state.family} AND display_name=${q(state.prefix+'-cash')}`);
    for (let back=3;back>=0;back--) {
      db.raw(`INSERT INTO period(family_id,period_type,period_start,period_end,status)
        VALUES(${state.family},'MONTHLY',DATE_SUB('${current}',INTERVAL ${back} MONTH),LAST_DAY(DATE_SUB('${current}',INTERVAL ${back} MONTH)),'${back?'CLOSED':'OPEN'}')`);
      const pid=db.num(`SELECT id FROM period WHERE family_id=${state.family} AND period_start=DATE_SUB('${current}',INTERVAL ${back} MONTH)`);
      db.raw(`INSERT INTO period_snapshot(period_id,account_id,end_balance,submitted_by,source_tag)
        SELECT ${pid},id,CASE WHEN currency='USD' THEN 100000 WHEN type='CASH' THEN 6100000 WHEN type='LOAN' THEN -100000 ELSE 10000000 END,${state.member},'MANUAL'
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
    await ui.goto(`/accounts/${state.cash}/edit`);
    const reference=await ui.page.locator('[name="displayOrder"]').evaluate(el=>{
      const css=getComputedStyle(el);return {font:css.fontFamily,size:parseFloat(css.fontSize)};
    });
    await ui.goto('/goals/new/retirement');
    await settings(ui,'新建计划',reference);
    await screenshot(ui,'desktop-settings');
    await ui.assert(!await ui.page.locator('#retire-scope').evaluate(el=>el.open),'默认资金范围折叠');
    await ui.assert(await ui.page.locator('[name="monthlyIncome"],[name="budgetConfirmed"],[name="assetsConfirmed"]').count()===0,'标准配置移除持续收入和重复确认');
    await ui.assert(!await ui.page.isChecked('[name="simulateFuture"]'),'未来积累默认关闭');
    let saveRequests=0;
    ui.page.on('request',request=>{if(request.method()==='POST' && /\/retirement(?:\/new)?$/.test(new URL(request.url()).pathname)) saveRequests++;});
    await ui.page.check('[name="cityTier"][value="SECOND"]');
    await ui.page.waitForFunction(()=>document.querySelector('[data-reserve-amount="medical"]').textContent.includes('200,000.00'));
    await ui.assert((await ui.page.locator('[data-reserve-amount="education"]').innerText()).includes('待选择'),'未填家庭结构时仍先带出城市医疗推荐,教育保持未知');
    await ui.click('[data-retire-save]','未选子女人数时点击保存');
    await ui.visible('[data-retire-field-error="dependentCount"]','前端在子女人数字段旁持续提示');
    await ui.assert(saveRequests===0,'无效配置不发送保存请求,不靠服务端整页报错');
    await ui.assert(await ui.page.locator('.lsel:has(select[name="dependentCount"]) .lsel-btn').evaluate(el=>document.activeElement===el),'首错定位到自研选择器按钮');
    await ui.choose('dependentCount','1');
    await ui.assert(await ui.page.locator('.lsel:has(select[name="childPlanningYears[0]"]) .lsel-btn').isVisible(),'动态子女年数同样使用自研选择器');
    await ui.choose('childPlanningYears[0]','12');await ui.choose('housingPlan','SETTLED');
    await ui.click('[data-retire-example]','主动采用节目假设');
    await ui.page.waitForFunction(()=>document.querySelector('[data-retire-live="reserves"]').textContent.includes('172,800.00'));
    await ui.assert((await ui.page.locator('[data-retire-live="reserves"]').innerText()).includes('200,000.00'),'二线方案:教育172800、医疗200000由真实服务端计算');
    await ui.page.check('[name="cityTier"][value="FIRST"]');
    await ui.page.waitForFunction(()=>document.querySelector('[data-retire-live="reserves"]').textContent.includes('288,000.00'));
    await ui.assert((await ui.page.locator('[data-retire-live="reserves"]').innerText()).includes('300,000.00'),'一线切换实际重算教育、医疗与应急');
    await ui.visible('#retire-draft-status','动态演算明确标为未保存草稿');
    await ui.assert(db.num(`SELECT COUNT(*) FROM retirement_plan WHERE family_id=${state.family}`)===0,'城市切换与自动演算不保存计划');
    await ui.choose('returnSource','AUTO');
    await ui.page.waitForFunction(()=>/本次名义年回报\s*待补齐/.test(document.querySelector('[data-retire-live="rates"]').textContent));
    await ui.assert((await ui.page.locator('[data-retire-live="rates"]').innerText()).includes('至少需要12个'),'短历史明确缺失,不回退节目示例');
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
    await ui.notVisible('#retire-draft-status','保存后回到正式计划,草稿提示消失');
    await ui.click('[data-retire-tab="plan"]','检查已保存计划的设置');
    await settings(ui,'修改计划',reference);
    const standardSize=await ui.page.locator('[name="monthlyExpense"]').evaluate(el=>parseFloat(getComputedStyle(el).fontSize));
    await ui.click('#fs-menu summary','打开字号菜单'); await ui.click('#fs-menu [data-fs-opt="xl"]','切到特大字号');
    await ui.assert(await ui.page.locator('[name="monthlyExpense"]').evaluate(el=>parseFloat(getComputedStyle(el).fontSize))>standardSize,'设置金额随全站字号档位放大');
    await ui.assert(await ui.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'设置特大字号无横向溢出');
    await ui.click('#fs-menu summary','恢复字号菜单'); await ui.click('#fs-menu [data-fs-opt="std"]','恢复标准字号');
    await ui.goto(`/goals/${state.native}/retirement`);
    await dimensions(ui,'电脑');
    await ui.assert(await ui.page.locator('#retirement-path').evaluate(el => {
      const pixels=el.getContext('2d').getImageData(0,0,el.width,el.height).data;
      let count=0;for(let i=3;i<pixels.length;i+=4)if(pixels[i])count++;return count>1000;
    }),'三情景画布有实际像素');
    await screenshot(ui,'desktop-overview');
    for (const page of ['/dashboard','/reports']) {
      await ui.goto(page);
      await ui.visible(`a[href="/goals/${state.native}"]`,page+':实际渲染目标入口');
      await ui.assert((await ui.page.locator(`a[href="/goals/${state.native}"]`).first().innerText()).includes('100.0%'),page+':目标条沿用同一退休结果');
    }
    await ui.goto(`/goals/${state.native}/retirement`);
    for (const [ccy,target] of [['USD','$600,000.00'],['HKD','HK$5,280,000.00']]) {
      await ui.click(`.retire-currencies a[href*="ccy=${ccy}"]`,'切换显示币种 '+ccy);
      await ui.assert((await ui.page.locator('[data-retire-value="target"]').innerText()).includes(target),'本金门槛按汇率换算');
      await ui.assert((await ui.page.locator('[data-retire-value="coverage"]').innerText()).includes('100.00%'),'覆盖率不随显示币种变化');
    }
    await ui.click('[data-retire-tab="principle"]','打开原理');
    await ui.assert((await ui.page.locator('[data-retire-live="math"]').innerText()).includes('HK$5,280,000.00'),'原理页代入同一份结果与显示币种');
    await ui.visible('a[href="https://www.bilibili.com/video/BV1yUHP6rEYD/"]','原视频直达链接');
    await ui.assert(await ui.page.locator('a[href*="BV1yUHP6rEYD"]').getAttribute('referrerpolicy')==='no-referrer','来源外链不带家庭来源数据');
    await screenshot(ui,'desktop-principle');
    const foreign=db.num(`SELECT id FROM account WHERE family_id=${state.family} AND currency='USD'`);
    await ui.click('[data-retire-tab="plan"]','纳入外币账户');
    await ui.page.check(`input[name="accountIds"][value="${foreign}"]`); await ui.click('[data-retire-save]','保存外币资金范围');
    await ui.assert((await ui.page.locator('[data-retire-value="principal"]').innerText()).includes('5,600,000.00'),'10万美元按当期汇率折为80万本金');
    await ui.click('[data-retire-tab="plan"]','恢复仅现金账户'); await ui.page.uncheck(`input[name="accountIds"][value="${foreign}"]`);
    await ui.click('[data-retire-save]','恢复基准资金范围');
    await ui.click('[data-retire-tab="plan"]','核对精确名义回报模式');
    await directMode(ui,false); await ui.fillByName('inflationPct','2','#retirement-form');
    await ui.click('[data-retire-save]','保存名义4.5%和通胀2%');
    await ui.assert((await ui.page.locator('[data-retire-value="target"]').innerText()).includes('4,896,000.00'),'精确实际回报得到489.6倍,不是近似480倍');
    await ui.click('[data-retire-tab="plan"]','恢复直接实际回报');
    await directMode(ui,true); await ui.fillByName('inflationPct','0','#retirement-form');
    await ui.page.check('[name="autoExpense"][value="true"]'); await ui.click('[data-retire-save]','授权跟随定稿预算');
    db.raw(`UPDATE cash_flow c JOIN period p ON p.id=c.period_id SET c.amount=20000
      WHERE p.family_id=${state.family} AND c.kind='EXPENSE' AND p.period_start=(SELECT MIN(pp.period_start) FROM period pp WHERE pp.family_id=${state.family})`);
    await ui.goto(`/goals/${state.native}/retirement`);
    await ui.assert((await ui.page.locator('[data-retire-value="target"]').innerText()).includes('6,399,998.40'),'已授权自动预算跟随定稿纠正,月均精确到分');
    await ui.click('[data-retire-tab="plan"]','切回固定生活预算'); await ui.page.check('[name="autoExpense"][value="false"]');
    await ui.click('[data-retire-save]','保存固定预算');
    db.raw(`UPDATE cash_flow c JOIN period p ON p.id=c.period_id SET c.amount=10000
      WHERE p.family_id=${state.family} AND c.kind='EXPENSE' AND p.period_start=(SELECT MIN(pp.period_start) FROM period pp WHERE pp.family_id=${state.family})`);
    await ui.goto(`/goals/${state.native}/retirement`);
    await ui.assert((await ui.page.locator('[data-retire-value="target"]').innerText()).includes('4,800,000.00'),'未授权跟随时固定预算不被新账上建议覆盖');
    db.raw(`INSERT INTO goal_ai_report(goal_id,period_id,report_type,content,validator_status) VALUES(${state.native},0,'MONTHLY','合成历史目标报告','PASS')`);
    await ui.goto(`/goals/${state.native}/retirement`);
    await ui.seesText('旧计划或旧资金状态的历史解读','旧报告不冒充新算法结论');
    const downloadEvent=ui.page.waitForEvent('download');
    await ui.click('.retire-heading a[href$="/export"]','导出本次测算');
    const download=await downloadEvent, stream=await download.createReadStream(), chunks=[];
    for await (const chunk of stream) chunks.push(chunk);
    const exported=Buffer.concat(chunks).toString('utf8');
    await ui.assert(exported.includes('计划币种:CNY') && exported.includes('计划金额基准日:') && exported.includes('BV1yUHP6rEYD'),'导出含币种、购买力基准日与原视频来源');
    await ui.click('[data-retire-tab="plan"]','检查零回报边界');
    await ui.fillByName('realReturnPct','0','#retirement-form'); await ui.click('[data-retire-save]','保存零实际回报');
    await ui.seesText('没有有限的永久本金门槛','零回报不显示0元门槛');
    await ui.goto('/goals'); await ui.seesText('没有有限的永久本金门槛','目标列表与详情同一状态');
    await ui.goto(`/goals/${state.native}/retirement?tab=plan`); await ui.fillByName('realReturnPct','2.5','#retirement-form');
    await ui.click('[data-retire-save]','恢复正实际回报');

    for(let back=13;back>=4;back--) {
      db.raw(`INSERT INTO period(family_id,period_type,period_start,period_end,status) VALUES(${state.family},'MONTHLY',
        DATE_SUB('${current}',INTERVAL ${back} MONTH),LAST_DAY(DATE_SUB('${current}',INTERVAL ${back} MONTH)),'CLOSED')`);
      const pid=db.num(`SELECT id FROM period WHERE family_id=${state.family} AND period_start=DATE_SUB('${current}',INTERVAL ${back} MONTH)`);
      db.raw(`INSERT INTO period_snapshot(period_id,account_id,end_balance,submitted_by,source_tag) SELECT ${pid},id,
        CASE WHEN currency='USD' THEN 100000 WHEN type='CASH' THEN 6100000 WHEN type='LOAN' THEN -100000 ELSE 10000000 END,${state.member},'MANUAL' FROM account WHERE family_id=${state.family}`);
      db.raw(`INSERT INTO fx_rate(family_id,period_id,base_currency,quote_currency,rate,source) VALUES(${state.family},${pid},'CNY','USD',0.2,'MANUAL')`);
    }
    await ui.goto(`/goals/${state.native}/retirement?tab=plan`);
    if(!await ui.page.locator('#retire-scope').evaluate(el=>el.open)) await ui.click('#retire-scope > summary','展开参与账户');
    await ui.page.uncheck(`input[name="accountIds"][value="${state.cash}"]`);await ui.page.check(`input[name="accountIds"][value="${foreign}"]`);
    await ui.choose('returnSource','AUTO');
    await ui.page.waitForFunction(()=>/本次名义年回报\s*60/.test(document.querySelector('[data-retire-live="rates"]').textContent));
    await ui.click('[data-retire-live="rates"] summary','查看真实月度投资来源');
    await ui.assert((await ui.page.locator('[data-retire-live="rates"]').innerText()).includes('12 个完整月度间隔'),'13张实际月末快照给出12个收益间隔');
    await ui.assert((await ui.page.locator('[data-retire-live="rates"]').innerText()).includes('300000.00'),'同为10万美元,历史汇率5到8的30万折算变化进入投资收益');
    const gap=db.num(`SELECT id FROM period WHERE family_id=${state.family} AND period_start=DATE_SUB('${current}',INTERVAL 6 MONTH)`);
    db.raw(`DELETE FROM period_snapshot WHERE period_id=${gap} AND account_id=${foreign}`);
    await ui.choose('returnSource','MANUAL');await ui.choose('returnSource','AUTO');
    await ui.page.waitForFunction(()=>document.querySelector('[data-retire-live="rates"]').textContent.includes('实际月末快照'));
    await ui.assert((await ui.page.locator('[data-retire-live="rates"]').innerText()).includes('实际月末快照'),'缺一张快照后拒绝用延续余额凑满一年');
    db.raw(`INSERT INTO period_snapshot(period_id,account_id,end_balance,submitted_by,source_tag) VALUES(${gap},${foreign},100000,${state.member},'MANUAL')`);
    await ui.goto(`/goals/${state.native}/retirement`);

    db.raw(`UPDATE retirement_plan SET plan_json=JSON_MERGE_PATCH(plan_json,
      '{"version":1,"monthlyIncome":3000,"budgetConfirmed":true,"reservesConfirmed":true,"assetsConfirmed":true,"incomeConfirmed":true,"contributionConfirmed":true,"afterDebtConfirmed":true}')
      WHERE goal_id=${state.native} AND family_id=${state.family}`);
    const revisionOne=db.one(`SELECT plan_json FROM retirement_plan WHERE goal_id=${state.native}`);
    await ui.goto(`/goals/${state.native}/retirement`);
    await ui.assert((await ui.page.locator('[data-retire-value="target"]').innerText()).includes('3,360,000.00'),'升级前继续使用含3000月收入的原门槛');
    await ui.click('[data-retire-tab="plan"]','比较修订一与修订二');
    await ui.assert((await ui.page.locator('[data-retire-upgrade-compare]').innerText()).includes('3,360,000.00') &&
      (await ui.page.locator('[data-retire-upgrade-compare]').innerText()).includes('4,800,000.00'),'旧新门槛并排展示,移除J的影响可复核');
    await screenshot(ui,'desktop-upgrade');
    await ui.click('[data-retire-save]','未确认升级不能移除原收入');
    await ui.visible('[data-retire-field-error="revisionConfirmed"]','前端定位未确认升级');
    await ui.assert(db.one(`SELECT plan_json FROM retirement_plan WHERE goal_id=${state.native}`)===revisionOne,'未确认时原JSON完整未变');
    await ui.page.check('[name="revisionConfirmed"]');await ui.click('[data-retire-save]','确认升级修订二');
    await ui.assert(db.one(`SELECT JSON_UNQUOTE(JSON_EXTRACT(plan_json,'$.previousRevisionJson')) FROM retirement_plan WHERE goal_id=${state.native}`)===revisionOne,'升级保留完整原JSON,含非零J');
    await ui.click('[data-retire-tab="plan"]','进入升级后设置');
    await ui.click('form[action$="/restore-previous"] button','恢复升级前方案');
    await ui.assert(db.one(`SELECT plan_json FROM retirement_plan WHERE goal_id=${state.native}`)===revisionOne,'真实恢复入口还原完整旧方案');
    await ui.click('[data-retire-tab="plan"]','再次明确升级');
    await ui.page.check('[name="revisionConfirmed"]');await ui.click('[data-retire-save]','回到修订二继续验收');

    await ui.goto(`/goals/${state.legacy}`);
    await ui.click(`a[href="/goals/${state.legacy}/retirement"]`,'老目标显式比较新算法');
    await form(ui,state.prefix+'-adopted');
    await ui.click('[data-retire-save]','未确认采用不能迁移');
    await ui.visible('[data-retire-field-error="adoptionConfirmed"]','前端定位未经确认的采用');
    await screenshot(ui,'desktop-adoption');
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
      await mobile.assert(await mobile.page.locator('#float-dock').evaluate(el=>getComputedStyle(el).position==='fixed'),'手机:生活账沿用系统悬浮工具');
      await mobile.click('[data-retire-tab="plan"]','手机调整计划');
      await mobile.assert(await mobile.page.locator('[data-retire-save]').count()===1,'手机仅一份表单与保存入口');
      await settings(mobile,'手机修改计划');
      await mobile.assert(await mobile.page.locator('.retire-check, .retire-settings-nav a').evaluateAll(els=>els.filter(el=>el.getClientRects().length).every(el=>el.getBoundingClientRect().height>=44)),'手机:确认与章节入口触摸高度足够');
      for(const width of [320,390,951,390]) {
        await mobile.page.setViewportSize({width,height:844}); await mobile.page.waitForTimeout(350);
        await mobile.assert(await mobile.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'设置页宽度'+width+':折叠与展开无横向溢出');
      }
      const menu='.nav-actions > details:not(#fs-menu) > summary';
      await mobile.click(menu,'打开手机菜单');
      await mobile.click('.nav-actions .fs-opt-m[data-fs-opt="xl"]','手机切到特大字号');
      await mobile.click(menu,'收起手机菜单');
      await mobile.assert(await mobile.page.locator('[name="monthlyExpense"]').evaluate(el=>parseFloat(getComputedStyle(el).fontSize))>18,'手机:金额跟随特大字号');
      await mobile.page.setViewportSize({width:320,height:844}); await mobile.page.waitForTimeout(350);
      await mobile.assert(await mobile.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'320窄屏特大字号:目录、单位和字段不撑宽');
      await mobile.click(menu,'打开手机字号菜单');
      await mobile.click('.nav-actions .fs-opt-m[data-fs-opt="std"]','手机恢复标准字号');
      await mobile.click(menu,'收起手机字号菜单');
      await mobile.page.setViewportSize({width:390,height:844}); await mobile.page.waitForTimeout(350);
      await screenshot(mobile,'mobile-plan');
      for(const id of ['retire-life','retire-assets','retire-reserves','retire-returns','retire-save'])
        await mobile.page.locator('#'+id).screenshot({path:'/tmp/finance-v139-review/mobile-'+id+'.png'});
      await mobile.page.locator('summary').filter({hasText:'查看月份与支出口径'}).click();
      await mobile.assert(await mobile.page.locator('#retire-plan .m-cards td').evaluateAll(cells => cells.filter(el=>el.getClientRects().length)
        .every(el=>el.scrollWidth<=el.clientWidth+2)),'手机:展开来源后日期与金额不挤出单元格');
      await mobile.click('[data-retire-tab="principle"]','手机查看原理');
      await screenshot(mobile,'mobile-principle');
      await mobile.goto(`/goals/${state.legacy}/retirement?tab=plan`);
      await mobile.assert(await mobile.page.locator('.retire-compare td').evaluateAll(cells=>cells.every(el=>el.scrollWidth<=el.clientWidth+2)),'手机:采用对比的金额和长说明不挤出单元格');
      await mobile.sameSize('.retire-form-actions button','手机:采用与预览按钮同宽同高');
      await screenshot(mobile,'mobile-adoption');
      await mobile.page.locator('.retire-compare').screenshot({path:'/tmp/finance-v139-review/mobile-adoption-comparison.png'});
      await mobile.goto(`/goals/${state.native}/retirement`);
      await mobile.click('[data-retire-tab="plan"]','手机核对无有限门槛状态'); await mobile.fillByName('realReturnPct','0','#retirement-form');
      await mobile.click('[data-retire-save]','手机保存零回报'); await mobile.goto('/goals');
      await mobile.assert(await mobile.page.locator(`a[href="/goals/${state.native}"]`).evaluate(el=>el.scrollWidth<=el.clientWidth+2),'手机:目标列表的长状态不溢出');
      await mobile.goto(`/goals/${state.native}/retirement?tab=plan`); await mobile.fillByName('realReturnPct','2.5','#retirement-form');
      await mobile.click('[data-retire-save]','手机恢复正回报');
    });
    await ui.goto('/goals/retirement/new');await form(ui,state.prefix+'-pending-data');
    await ui.choose('scopeMode','FAMILY_FINANCIAL');await ui.choose('returnSource','AUTO');
    db.raw(`INSERT INTO account(family_id,display_name,type,currency) VALUES(${state.family},${q(state.prefix+'-unfilled')},'FUND','CNY')`);
    const unfilled=db.num(`SELECT id FROM account WHERE family_id=${state.family} AND display_name=${q(state.prefix+'-unfilled')}`);
    await ui.click('[data-retire-save]','余额和历史收益不足时保存新目标配置');
    const pendingGoal=db.num(`SELECT id FROM family_goal WHERE family_id=${state.family} AND name=${q(state.prefix+'-pending-data')}`);
    await ui.assert(pendingGoal>0 && await ui.page.locator('[data-retire-value="target"]').isVisible(),'新目标实际保存并打开结果,不被演算数据不足阻塞');
    await ui.assert(db.num(`SELECT COUNT(*) FROM retirement_plan WHERE family_id=${state.family} AND goal_id=${pendingGoal} AND JSON_UNQUOTE(JSON_EXTRACT(plan_json,'$.returnSource'))='AUTO'` )===1,'动态来源按配置保存,没有偷偷改成手填');
    await ui.seesText('当前账期余额未填','保存后计算结果仍如实列出缺失余额');
    await ui.goto(`/goals/${pendingGoal}/retirement?tab=plan`);await ui.choose('returnSource','MANUAL');
    await ui.click('[data-retire-save]','缺余额仍可调整回报假设');
    await ui.goto(`/goals/${pendingGoal}/retirement?tab=plan`);
    await ui.assert((await ui.page.locator('[data-retire-live="funds"]').innerText()).includes('当前账期余额未填'),'缺余额未伪填零');
    const latest=db.num(`SELECT id FROM period WHERE family_id=${state.family} ORDER BY period_start DESC LIMIT 1`);
    db.raw(`INSERT INTO period_snapshot(period_id,account_id,end_balance,submitted_by,source_tag) VALUES(${latest},${unfilled},12345,${state.member},'MANUAL')`);
    const savedConfig=db.one(`SELECT plan_json FROM retirement_plan WHERE goal_id=${pendingGoal} AND family_id=${state.family}`);
    await ui.goto(`/goals/${pendingGoal}/retirement?tab=plan`);
    await ui.assert(!(await ui.page.locator('[data-retire-live="funds"]').innerText()).includes('当前账期余额未填'),'补齐余额后直接重读数据,无需重新保存配置');
    await ui.assert(db.one(`SELECT plan_json FROM retirement_plan WHERE goal_id=${pendingGoal} AND family_id=${state.family}`)===savedConfig,'演算读取新余额不回写计划');
    await ui.choose('scopeMode','CUSTOM');
    const home=db.num(`SELECT id FROM account WHERE family_id=${state.family} AND type='PROPERTY'`);
    await ui.page.check(`[name="accountIds"][value="${home}"]`);
    await ui.assert(await ui.page.locator('.retire-realizable').count()===0,'自定义范围只选账户,不要求另填估值');
    await ui.click('[data-retire-save]','保存主动选择的账户范围');
    await ui.goto(`/goals/${pendingGoal}/retirement?tab=plan`);
    await ui.assert((await ui.page.locator('[data-retire-live="funds"]').innerText()).includes('16,100,000.00'),'自定义所选账户按最新账面值汇总');
    db.raw(`UPDATE period_snapshot SET end_balance=10010000 WHERE period_id=${latest} AND account_id=${home}`);
    await ui.goto(`/goals/${pendingGoal}/retirement?tab=plan`);
    await ui.assert((await ui.page.locator('[data-retire-live="funds"]').innerText()).includes('16,110,000.00'),'主动选入的非金融账户也实时读取余额,不冻结金额');
    await ui.phone(async mobile=>{
      await login(mobile.page,state.username);await mobile.goto('/goals/retirement/new');
      await mobile.page.check('[name="cityTier"][value="FIRST"]');
      await mobile.page.waitForFunction(()=>document.querySelector('[data-reserve-amount="medical"]').textContent.includes('300,000.00'));
      await mobile.click('[data-retire-save]','手机前端提示未填配置');
      await mobile.visible('[data-retire-field-error="dependentCount"]','手机字段旁显示错误');
      await mobile.choose('dependentCount','1');await mobile.choose('childPlanningYears[0]','6');
      await mobile.page.locator('#retire-reserves').screenshot({path:'/tmp/finance-v139-review/mobile-feedback-reserves.png'});
      await mobile.assert(await mobile.page.locator('.lsel-btn').evaluateAll(buttons=>buttons.filter(el=>el.getClientRects().length).every(el=>el.getBoundingClientRect().right<=innerWidth+1)),'手机自研下拉按钮均在屏内');
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
      DELETE FROM persistent_logins WHERE username=${q(state.username)};
      DELETE FROM member WHERE family_id=${f}; DELETE FROM family WHERE id=${f};`);
    report.info('已清理隔离家庭与全部退休样本,原 beta 家庭未改动'); state.family=null;
  }
};
