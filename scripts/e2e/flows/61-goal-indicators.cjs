/* Historical monthly fixtures need a separate synthetic family; all target configuration
 * and preference changes use real page controls. No close-period or model action. */
const db=require('../lib/db.cjs');
const {BASE,USER,PASS}=require('../lib/browser.cjs');
const fs=require('fs');
const state={family:null,username:null,goal:null,cash:null,member:null};
const q=s=>"'"+String(s).replace(/'/g,"''")+"'";
async function login(page,user){await page.context().clearCookies();await page.goto(BASE+'/login');await page.fill('[name=username]',user);await page.fill('[name=password]',PASS);await page.click('button[type=submit]');await page.waitForLoadState('networkidle');}
async function eta(ui,text){await ui.page.waitForFunction(s=>document.querySelector('[data-estimate-date]')?.textContent.trim()===s,text);await ui.assert((await ui.page.locator('[data-estimate-date]').innerText()).trim()===text,'时间指标:'+text);}
async function save(ui){await ui.click('[data-estimate-save]','保存本目标月结余');await ui.page.waitForFunction(()=>document.querySelector('[data-estimate-status]').textContent.includes('已保存'));}
async function shot(ui,name){fs.mkdirSync('/tmp/finance-v140-review',{recursive:true});await ui.page.screenshot({path:'/tmp/finance-v140-review/'+name+'.png',fullPage:true});}
module.exports={name:'61-goal-indicators',title:'目标多指标、家庭结余与独立保存',async run(ui){
  state.username='e2e-goals140-'+Date.now();
  db.raw(`INSERT INTO family(name,base_currency,period_type)VALUES(${q(state.username)},'CNY','MONTHLY')`);
  state.family=db.num(`SELECT id FROM family WHERE name=${q(state.username)}`);
  db.raw(`INSERT INTO member(family_id,username,password_hash,display_name,must_change_pw)SELECT ${state.family},${q(state.username)},password_hash,'目标验收成员',0 FROM member WHERE username=${q(USER)}`);
  state.member=db.num(`SELECT id FROM member WHERE username=${q(state.username)}`);
  if(!state.member)throw Error('合成成员未建立');
  db.raw(`INSERT INTO account(family_id,display_name,type,currency)VALUES(${state.family},'合成目标现金','CASH','CNY'),(${state.family},'合成目标贷款','LOAN','CNY')`);
  state.cash=db.num(`SELECT id FROM account WHERE family_id=${state.family} AND type='CASH'`);
  const current=db.one("SELECT DATE_FORMAT(CURDATE(),'%Y-%m-01')");
  for(let back=6;back>=0;back--){
    db.raw(`INSERT INTO period(family_id,period_type,period_start,period_end,status)VALUES(${state.family},'MONTHLY',DATE_SUB('${current}',INTERVAL ${back} MONTH),LAST_DAY(DATE_SUB('${current}',INTERVAL ${back} MONTH)),'${back?'CLOSED':'OPEN'}')`);
    const pid=db.num(`SELECT id FROM period WHERE family_id=${state.family} AND period_start=DATE_SUB('${current}',INTERVAL ${back} MONTH)`);
    db.raw(`INSERT INTO period_snapshot(period_id,account_id,end_balance,submitted_by,source_tag)SELECT ${pid},id,IF(type='LOAN',-2000000,5000000),${state.member},'MANUAL' FROM account WHERE family_id=${state.family}`);
    db.raw(`INSERT INTO fx_rate(family_id,period_id,base_currency,quote_currency,rate,source)VALUES(${state.family},${pid},'CNY','USD',0.125,'MANUAL'),(${state.family},${pid},'CNY','HKD',1.1,'MANUAL')`);
    if(back)db.raw(`INSERT INTO period_member_cashflow(family_id,period_id,member_id,total_income_input,total_expense_input)VALUES(${state.family},${pid},${state.member},80000,40000)`);
  }
  await login(ui.page,state.username);await ui.goto('/goals');await ui.click('a[href="/goals/new/retirement"]','从空目标页新建退休');
  await ui.page.check('[name=autoExpense][value=false]');await ui.choose('reserveMode','CUSTOM');await ui.choose('returnSource','MANUAL');await ui.choose('inflationSource','MANUAL');
  const details=ui.page.locator('#retire-returns details[data-retire-when="manual-return"]');if(!await details.evaluate(e=>e.open))await details.locator('summary').first().click();
  await ui.page.check('[name=directRealRate]');
  const y=Number(current.slice(0,4)),m=Number(current.slice(5,7));
  const dateAfter=n=>{const d=new Date(y,m-1+n,1);return d.getFullYear()+'年'+String(d.getMonth()+1).padStart(2,'0')+'月';};
  for(const [name,value]of Object.entries({name:'合成财务自由目标',targetMonth:(y+10)+'-'+String(m).padStart(2,'0'),monthlyExpense:'15000',childReserve:'400000',medicalReserve:'400000',housingReserve:'0',otherReserve:'0',inflationPct:'0',realReturnPct:'2.5'}))await ui.fillByName(name,value,'#retirement-form');
  await ui.page.check('[name=noOverlapConfirmed]');await ui.click('[data-retire-save]','保存合法退休目标');await ui.page.waitForLoadState('networkidle');
  state.goal=db.num(`SELECT goal_id FROM retirement_plan WHERE family_id=${state.family} AND active=1`);
  await ui.assert(Boolean(state.goal),'新退休计划已保存');
  await ui.assert(db.num(`SELECT forecast_wish_override FROM family_goal WHERE id=${state.goal}`)===0,'未改估算月份时继承主计划');
  await ui.assert((await ui.page.locator('[data-retire-value=coverage]').innerText()).includes('50'),'主资金仍为500万/1000万=50%');
  await eta(ui,dateAfter(125));await ui.assert((await ui.page.locator('[data-estimate-duration]').innerText()).includes('10年5个月'),'月结余4万还需125个月');
  const original=db.one(`SELECT plan_json FROM retirement_plan WHERE goal_id=${state.goal} AND family_id=${state.family}`);
  await shot(ui,'desktop-retirement');
  await ui.click('[data-estimate-edit]','从时间指标调整估算');await ui.page.check('[name=estimateSource][value=MANUAL]');await ui.page.fill('[name=estimateMonthlyAmount]','20000');
  await eta(ui,dateAfter(250));await ui.assert(await ui.page.locator('[data-estimate-draft]').isVisible(),'未保存估算有明确提示');
  await ui.page.fill('[name=estimateMonthlyAmount]','');await ui.assert(await ui.page.locator('[data-estimate-save]').isDisabled(),'非法手填前端拦截');await ui.assert(await ui.page.locator('[data-estimate-error=estimateMonthlyAmount]').isVisible(),'错误在金额字段旁');
  await ui.page.fill('[name=estimateMonthlyAmount]','20000');await save(ui);
  await ui.assert(db.num(`SELECT forecast_manual_amount FROM family_goal WHERE id=${state.goal} AND family_id=${state.family}`)===20000,'独立结余已持久化');
  await ui.assert(db.one(`SELECT plan_json FROM retirement_plan WHERE goal_id=${state.goal}`)===original,'保存估算不重写原退休JSON');
  await ui.fillByName('monthlyExpense','17000','#retirement-form');
  const changedWish=(y+11)+'-'+String(m).padStart(2,'0');await ui.fillByName('targetMonth',changedWish,'#retirement-form');
  await ui.page.waitForTimeout(650);
  await ui.assert(await ui.page.inputValue('[name=estimateWishMonth]')===changedWish,'未覆盖的估算希望月份随主草稿更新');
  await ui.page.fill('[name=estimateMonthlyAmount]','30000');await save(ui);
  await ui.assert(await ui.page.inputValue('[name=monthlyExpense]')==='17000','保存估算保留未保存的生活费草稿');
  await ui.assert(db.one(`SELECT plan_json FROM retirement_plan WHERE goal_id=${state.goal}`)===original,'主草稿没有被估算保存偷偷写入');
  await ui.assert(db.num(`SELECT forecast_wish_override FROM family_goal WHERE id=${state.goal}`)===0,'只保存结余不冻结希望月份');
  await ui.goto('/goals');await ui.assert((await ui.page.locator(`a[href="/goals/${state.goal}"] [data-estimate-list]`).innerText()).includes(dateAfter(167)),'列表日期使用已保存资金与结余');
  await shot(ui,'desktop-list');await ui.goto(`/goals/${state.goal}/retirement?ccy=USD`);await eta(ui,dateAfter(167));
  await ui.assert((await ui.page.locator('.retire-currencies [aria-current=true]').innerText()).trim()==='USD','确实显示美元,没有回退本位币');
  await ui.assert((await ui.page.locator('[data-estimate-contribution]').innerText()).includes('3,750.00'),'美元月结余按汇率换算');
  await ui.assert((await ui.page.locator('[data-retire-value=coverage]').innerText()).includes('50'),'币种切换不改变完成度');
  await ui.goto(`/goals/${state.goal}/retirement?ccy=HKD`);await eta(ui,dateAfter(167));
  await ui.assert((await ui.page.locator('.retire-currencies [aria-current=true]').innerText()).trim()==='HKD','确实显示港元');
  await ui.assert((await ui.page.locator('[data-estimate-contribution]').innerText()).includes('33,000.00'),'港元月结余按汇率换算');
  await ui.click('[data-estimate-edit]','回到估算设置');await ui.page.fill('[name=estimateWishMonth]','');await ui.page.fill('[name=estimateMonthlyAmount]','0');await save(ui);await eta(ui,'暂无达成月份');
  await ui.assert(db.num(`SELECT forecast_wish_override FROM family_goal WHERE id=${state.goal}`)===1&&db.one(`SELECT forecast_wish_month FROM family_goal WHERE id=${state.goal}`)==='NULL','明确不设希望月份与继承分开');
  await ui.page.fill('[name=estimateMonthlyAmount]','-5000');await save(ui);await eta(ui,'暂无达成月份');
  await ui.assert((await ui.page.locator('[data-retire-value=coverage]').innerText()).includes('50'),'零和负结余不清空资金进度');
  db.raw(`DELETE FROM period_member_cashflow WHERE family_id=${state.family}`);
  await ui.page.check('[name=estimateSource][value=AUTO]');await save(ui);await eta(ui,'暂不估算');
  await ui.assert((await ui.page.locator('[data-retire-value=coverage]').innerText()).includes('50'),'缺收支只影响时间');
  await ui.assert(db.num(`SELECT COUNT(*) FROM family_goal WHERE family_id=${state.family}`)===1,'来源不足不删除或阻止合法目标');
  db.raw(`UPDATE period_snapshot s JOIN period p ON p.id=s.period_id SET s.end_balance=11000000 WHERE p.family_id=${state.family} AND s.account_id=${state.cash} AND p.status='OPEN'`);
  await ui.goto(`/goals/${state.goal}/retirement`);await eta(ui,'已达成');await ui.assert((await ui.page.locator('[data-estimate-duration]').innerText())==='0个月','已达成不需要结余来源');
  await ui.phone(async mobile=>{
    await login(mobile.page,state.username);await mobile.goto(`/goals/${state.goal}/retirement`);await eta(mobile,'已达成');
    await mobile.click('[data-estimate-edit]','手机进入时间估算');await mobile.page.check('[name=estimateSource][value=MANUAL]');await mobile.page.fill('[name=estimateMonthlyAmount]','40000');await save(mobile);
    await mobile.assert(await mobile.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'手机无横向溢出');
    const value=await mobile.page.inputValue('[name=estimateMonthlyAmount]');
    for(const tab of ['overview','principle','plan'])await mobile.click(`[data-retire-tab=${tab}]`,'手机切换页签');
    await mobile.assert(await mobile.page.inputValue('[name=estimateMonthlyAmount]')===value,'三页签保留结余输入');await shot(mobile,'mobile-settings');
  });
  await ui.goto('/goals/new/custom');await ui.page.fill('[name=name]','合成收益率目标');await ui.page.check('[name=metric][value=RETURN_XIRR]');await ui.page.fill('[name=targetValue]','7');await ui.page.locator('button[type=submit]').click();await ui.page.waitForLoadState('networkidle');
  await ui.assert(await ui.page.locator('[data-goal-estimate-result]').count()===0,'收益率目标不伪造结余达成日期');
  await ui.assert(db.num(`SELECT COUNT(*) FROM llm_prompt_record WHERE family_id=${state.family}`)===0,'本流程没有模型准备/调用');
  await login(ui.page,USER);
},async cleanup(ui,report){
  if(!state.family)return;const f=state.family;
  db.raw(`DELETE FROM goal_ai_report WHERE goal_id IN(SELECT id FROM family_goal WHERE family_id=${f});DELETE FROM goal_account WHERE goal_id IN(SELECT id FROM family_goal WHERE family_id=${f});DELETE FROM retirement_plan WHERE family_id=${f};DELETE FROM family_goal WHERE family_id=${f};DELETE FROM audit_log WHERE family_id=${f};
    DELETE c FROM cash_flow c JOIN period p ON p.id=c.period_id WHERE p.family_id=${f};DELETE s FROM period_snapshot s JOIN period p ON p.id=s.period_id WHERE p.family_id=${f};DELETE t FROM snapshot_todo t JOIN period p ON p.id=t.period_id WHERE p.family_id=${f};DELETE FROM period_member_cashflow WHERE family_id=${f};DELETE c FROM period_member_completion c JOIN period p ON p.id=c.period_id WHERE p.family_id=${f};DELETE a FROM period_account_attr a JOIN period p ON p.id=a.period_id WHERE p.family_id=${f};DELETE g FROM period_account_group g JOIN period p ON p.id=g.period_id WHERE p.family_id=${f};DELETE FROM fx_rate WHERE family_id=${f};DELETE FROM period WHERE family_id=${f};DELETE FROM account WHERE family_id=${f};DELETE FROM family_runtime_config WHERE family_id=${f};DELETE FROM persistent_logins WHERE username=${q(state.username)};DELETE FROM member WHERE family_id=${f};DELETE FROM family WHERE id=${f}`);
  report.info('目标隔离家庭与全部样本已清理');state.family=null;
}};
