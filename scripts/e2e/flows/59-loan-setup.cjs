'use strict';
const db=require('../lib/db.cjs');
const {BASE,USER,PASS}=require('../lib/browser.cjs');
const state={};
const q=v=>"'"+String(v).replace(/'/g,"''")+"'";
async function login(ui,user){await ui.page.context().clearCookies();await ui.goto('/login');await ui.fillByName('username',user);await ui.fillByName('password',PASS);await ui.click('button[type="submit"]');await ui.page.waitForLoadState('networkidle');}
module.exports={
  name:'59-loan-setup',title:'贷款常驻提醒 · 手填偏好 · 当期/下期生效 · 已还清',
  async run(ui){
    ui.flow=this.name;state.name='e2e-loan138-'+Date.now();
    db.raw(`INSERT INTO family(name,base_currency,period_type,expense_entry_mode) VALUES(${q(state.name)},'CNY','MONTHLY','ITEMIZED')`);
    const f=state.family=db.num(`SELECT id FROM family WHERE name=${q(state.name)}`);
    db.raw(`INSERT INTO member(family_id,username,password_hash,display_name,must_change_pw) SELECT ${f},${q(state.name)},password_hash,'还款测试',0 FROM member WHERE username=${q(USER)}`);
    const m=state.member=db.num(`SELECT id FROM member WHERE family_id=${f}`);
    for(const name of ['cash','carried','submitted','settled']) db.raw(`INSERT INTO account(family_id,display_name,type,currency,annual_rate_pct) VALUES(${f},${q(name)},'${name==='cash'?'CASH':'LOAN'}','CNY',${name==='carried'?'3.6':'NULL'})`);
    for(let back=2;back>=0;back--){
      db.raw(`INSERT INTO period(family_id,period_type,period_start,period_end,status) VALUES(${f},'MONTHLY',DATE_SUB(DATE_FORMAT(CURDATE(),'%Y-%m-01'),INTERVAL ${back} MONTH),LAST_DAY(DATE_SUB(CURDATE(),INTERVAL ${back} MONTH)),'${back?'CLOSED':'OPEN'}')`);
      const p=db.num(`SELECT id FROM period WHERE family_id=${f} ORDER BY period_start DESC LIMIT 1`);
      if(!back) state.current=p;
      db.raw(`INSERT INTO period_snapshot(period_id,account_id,end_balance,submitted_by,source_tag) SELECT ${p},id,CASE display_name WHEN 'cash' THEN 10000 WHEN 'settled' THEN 0 ELSE ${back===2?1000:back===1?900:800} END,${m},IF(display_name='carried' AND ${back}=0,'CARRIED_FORWARD','MANUAL') FROM account WHERE family_id=${f}`);
    }
    const carried=state.carried=db.num(`SELECT id FROM account WHERE family_id=${f} AND display_name='carried'`),submitted=db.num(`SELECT id FROM account WHERE family_id=${f} AND display_name='submitted'`);
    const cash=db.num(`SELECT id FROM account WHERE family_id=${f} AND display_name='cash'`);
    const balance=()=>db.one(`SELECT GROUP_CONCAT(CONCAT(account_id,':',end_balance) ORDER BY account_id) FROM period_snapshot WHERE period_id=${state.current}`);
    const before=balance();
    await login(ui,state.name);await ui.goto('/entry');
    const summary=ui.page.locator('[data-ff-box]');
    if(await summary.count() && !await summary.evaluate(el=>el.open)) await ui.click('[data-ff-box] > summary','展开固定收支');
    await ui.visible(`[data-loan-setup="${submitted}"]`,'人工已填本期余额仍显示未设固定还款');
    await ui.visible(`[data-loan-setup="${carried}"]`,'系统延续余额也显示配置提醒');
    await ui.click(`[data-loan-setup="${carried}"] button[type="submit"]`,'选择每月自己填');
    await ui.assert(db.num(`SELECT loan_manual_repayment FROM account WHERE id=${carried}`)===1,'手填偏好持久化');
    await ui.goto('/entry');await ui.notVisible(`[data-loan-setup="${carried}"]`,'下次进入也不重复提醒');
    await ui.assert(balance()===before,'修改提醒偏好不改任何余额');
    await ui.goto('/admin/fixed-flows');await ui.visible(`[data-loan-id="${carried}"][data-loan-state="MANUAL"]`,'管理页明确手填状态');
    await ui.click(`[data-loan-id="${carried}"] [data-loan-restore]`,'恢复配置提醒');
    await ui.assert(db.num(`SELECT loan_manual_repayment FROM account WHERE id=${carried}`)===0,'恢复提醒持久化');
    for(const [id,month] of [[carried,db.one("SELECT DATE_FORMAT(CURDATE(),'%Y-%m')")],[submitted,db.one("SELECT DATE_FORMAT(DATE_ADD(CURDATE(),INTERVAL 1 MONTH),'%Y-%m')")]]) {
      await ui.goto(`/admin/fixed-flows/new?loan=${id}`);
      await ui.rendered('固定还款表单');
      if(id===carried) await ui.assert(await ui.page.locator('[data-ffe-method]').inputValue()==='LOAN_RATE','有年利率时预填按年利率拆还款');
      await ui.choose('accountId',cash,'[data-ff-edit-form] [data-ffe-for="REPAY"]');
      if(id===carried) await ui.fill('[data-ffe-amount]','100','填写约定月供');
      await ui.click('[data-ff-edit-form] button[type="submit"]','保存固定还款');
      await ui.assert(db.one(`SELECT DATE_FORMAT(start_month,'%Y-%m') FROM fixed_flow WHERE family_id=${f} AND target_account_id=${id} AND active=1`)===month,id===carried?'系统延续余额从本期开始':'本期人工提交过则从下期开始');
    }
    await ui.assert(balance()===before,'设置还款本身不记账');
    await ui.goto('/admin/fixed-flows');await ui.visible(`[data-loan-id="${carried}"][data-loan-state="SET"]`,'配置后状态明确');
    const zero=db.num(`SELECT id FROM account WHERE family_id=${f} AND display_name='settled'`);await ui.visible(`[data-loan-id="${zero}"][data-loan-state="SETTLED"]`,'真实零余额显示已还清');
    await ui.page.setViewportSize({width:390,height:844});await ui.assert(await ui.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'手机贷款管理无横向溢出');
    await ui.page.screenshot({path:'/tmp/finance-loan-setup-mobile.png',fullPage:true});await ui.page.setViewportSize({width:1440,height:900});
    await login(ui,USER);
  },
  async cleanup(ui,report){
    if(!state.family)return;const f=state.family;
    db.raw(`DELETE FROM fixed_flow_occurrence WHERE family_id=${f};DELETE FROM fixed_flow WHERE family_id=${f};DELETE FROM audit_log WHERE family_id=${f};
      DELETE s FROM period_snapshot s JOIN period p ON p.id=s.period_id WHERE p.family_id=${f};DELETE FROM period WHERE family_id=${f};
      DELETE FROM account WHERE family_id=${f};DELETE FROM family_runtime_config WHERE family_id=${f};DELETE FROM member WHERE family_id=${f};DELETE FROM family WHERE id=${f};`);
    report.info('已清理贷款隔离家庭');state.family=null;
  }
};
