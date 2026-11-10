'use strict';
const $ = id => document.getElementById(id);
const asOfMonth = 2026 * 12 + 10;
const monthRows = [
  ['2026-05',70000,40000],['2026-06',80000,40000],['2026-07',82000,42000],
  ['2026-08',90000,50000],['2026-09',85000,40000],['2026-10',100000,40000]
];
const goals = [
  {id:'retirement',name:'退休生活账',kind:'退休目标',assets:5000000,target:10000000,source:'AUTO',amount:'40000',wish:'2036-11'},
  {id:'education',name:'孩子的教育金',kind:'教育目标',assets:300000,target:1000000,source:'MANUAL',amount:'12000',wish:'2032-09'},
  {id:'emergency',name:'家庭应急储备',kind:'应急目标',assets:60000,target:120000,source:'AUTO',amount:'40000',wish:''},
  {id:'cash',name:'现金达到20万元',kind:'自定义 · 现金合计',assets:100000,target:200000,source:'AUTO',amount:'10000',wish:'2027-11',missing:true},
  {id:'return',name:'投资年化达到7%',kind:'自定义 · 年化收益率',rate:true,source:'AUTO',amount:'',wish:'2028-11'}
];
let selected = goals[0];
let activeTab = 'overview';
let sample = 'normal';
let draft = {source:selected.source,amount:selected.amount,wish:selected.wish};
const money = n => n == null ? '暂无数据' : '¥' + n.toLocaleString('zh-CN',{maximumFractionDigits:2});
const monthText = n => Math.floor(n / 12) + '年' + String(n % 12 + 1).padStart(2,'0') + '月';
const duration = n => n === 0 ? '0个月' : (Math.floor(n / 12) ? Math.floor(n / 12) + '年' : '') + (n % 12 ? n % 12 + '个月' : '');
const monthNumber = s => Number(s.slice(0,4)) * 12 + Number(s.slice(5)) - 1;
function facts(g) {
  const current = g.id === 'retirement' && ['achieved','funded-missing'].includes(sample) ? 10500000 : g.assets;
  let automatic = g.missing ? null : 40000;
  if (g.id === 'retirement') {
    if (['missing','funded-missing'].includes(sample)) automatic = null;
    if (sample === 'zero') automatic = 0;
    if (sample === 'negative') automatic = -5000;
    if (sample === 'long') automatic = 1000;
  }
  return {current,automatic,target:g.target};
}
function validAmount(value) {
  return /^-?\d+(\.\d{1,2})?$/.test(value) && Number.isFinite(Number(value)) && Math.abs(Number(value)) <= 1000000000;
}
function validWish(value) { return !value || /^\d{4}-(0[1-9]|1[0-2])$/.test(value) && Number(value.slice(0,4)) >= 1900; }
function estimate(g,settings) {
  if (g.rate) return {state:'inapplicable',date:'',remaining:'',caption:'收益率不按月结余线性累积'};
  const f = facts(g), gap = Math.max(0,f.target-f.current);
  const c = settings.source === 'AUTO' ? f.automatic : validAmount(settings.amount) ? Number(settings.amount) : null;
  const base = {assets:f.current,target:f.target,gap,c,progress:f.current/f.target*100};
  if (gap === 0) return {...base,state:'achieved',months:0,date:'已达成',remaining:'0个月',caption:'当前资金已达到门槛'};
  if (c == null) return {...base,state:'missing',date:'暂无结余参考',remaining:'暂不估算',caption:'可补齐月度收支,或手填本目标的月结余'};
  if (c <= 0) return {...base,state:'no-growth',date:'暂无达成月份',remaining:'缺口暂不缩小',caption:c === 0 ? '当前月结余为0' : '当前每月净流出 ' + money(-c)};
  // All calculations use integer cents before rounding up whole contribution months.
  const months = Math.ceil(Math.round(gap*100)/Math.round(c*100));
  if (months > 600) return {...base,state:'long',months,date:'超过50年',remaining:'超过50年',caption:'按当前结余,需超过600个月'};
  return {...base,state:'ready',months,date:monthText(asOfMonth+months),remaining:duration(months),caption:'每月 ' + money(c) + ' · 仅按结余'};
}
function isDirty() { return draft.source !== selected.source || draft.amount !== selected.amount || draft.wish !== selected.wish; }
function renderList() {
  $('goal-list').innerHTML = goals.map(g => {
    const r = estimate(g,g);
    const progress = g.rate ? '5.0%' : r.progress.toFixed(1) + '%';
    const label = g.rate ? '当前年化 · 目标7.0%' : '资金完成度';
    const values = g.rate ? '还差 2.0 个百分点' : money(r.assets) + ' / ' + money(r.target);
    const bar = g.rate ? '' : '<div class="progress-track"><span style="width:' + Math.max(0,Math.min(100,r.progress)) + '%"></span></div>';
    const eta = g.rate ? '<span class="muted">希望2028年11月达到 · 持续跟踪</span>' : '<svg class="icon"><use href="#i-calendar"/></svg><span>' + (r.state === 'ready' ? '预计 ' + r.date + ' · 还需' + r.remaining : r.date + ' · ' + r.caption) + '</span>';
    return '<a class="goal-row" href="#' + g.id + '"><div><span class="category">' + g.kind + '</span><h2>' + g.name + '</h2><p class="goal-values">' + values + '</p></div><div class="row-metrics"><div class="row-progress-heading"><span>' + label + '</span><b class="' + (g.rate?'rate':'') + '">' + progress + '</b></div>' + bar + '<div class="row-date">' + eta + '</div></div><svg class="icon"><use href="#i-arrow"/></svg></a>';
  }).join('');
}
function render() {
  renderList();
  const r = estimate(selected,draft), f = facts(selected);
  const rate = Boolean(selected.rate);
  $('goal-title').textContent = selected.name;
  $('goal-kind').textContent = selected.kind;
  $('tab-overview').textContent = selected.id === 'retirement' ? '退休生活账' : '目标概览';
  $('saved-label').textContent = isDirty() ? '时间试算未保存' : '配置已保存';
  $('draft-message').hidden = !isDirty();
  $('money-indicators').hidden = rate;
  $('rate-indicators').hidden = !rate;
  for (const id of ['financial-facts','timeline-section','contribution-section','estimate-form']) $(id).hidden = rate;
  $('rate-note').hidden = !rate;
  $('settings-not-applicable').hidden = !rate;
  $('investment-note').hidden = selected.id !== 'retirement';
  $('retirement-target-source').hidden = selected.id !== 'retirement';
  $('time-assumptions').hidden = rate;
  if (rate) {
    $('progress-equation').textContent = '当前年化5.0%,希望达到7.0%,相差2.0个百分点。';
    $('time-equation').textContent = '月结余增加本金,不能直接推出未来投资收益率达到7%的月份。';
    $('date-equation').textContent = '原有希望日期保留,不生成无依据的预计达成日期。';
    return;
  }
  $('detail-progress').textContent = r.progress.toFixed(1) + '%';
  $('detail-progress-fill').style.width = Math.max(0,Math.min(100,r.progress)) + '%';
  document.querySelector('#money-indicators [role=progressbar]').setAttribute('aria-valuenow',String(Math.max(0,Math.min(100,r.progress))));
  $('detail-date').textContent = r.date;
  $('detail-date').classList.toggle('message',!['ready','achieved'].includes(r.state));
  $('detail-duration').textContent = r.remaining;
  $('detail-duration').classList.toggle('message',!['ready','achieved'].includes(r.state));
  $('date-basis').textContent = r.caption;
  $('detail-assets').textContent = money(r.assets);
  $('detail-target').textContent = money(r.target);
  $('detail-gap').textContent = money(r.gap);
  $('wish-column').hidden = !draft.wish || !validWish(draft.wish);
  $('wish-date').textContent = validWish(draft.wish) && draft.wish ? monthText(monthNumber(draft.wish)) : '未设定';
  $('timeline-date').textContent = r.date;
  const comparison = $('deadline-comparison');
  comparison.textContent = '未设希望月份也可以估算';
  comparison.className = 'caption';
  if (validWish(draft.wish) && draft.wish) {
    if (r.state === 'ready') {
      const delta = asOfMonth+r.months-monthNumber(draft.wish);
      comparison.textContent = delta === 0 ? '预计与希望月份一致' : '比希望月份' + (delta>0?'晚':'早') + duration(Math.abs(delta));
      comparison.classList.add(delta>0?'late':'early');
    } else comparison.textContent = r.state === 'achieved' ? '当前资金已达成' : '有结余参考后再对照';
  }
  $('source-value').textContent = r.c == null ? '暂无完整月结余' : money(r.c) + ' / 月';
  $('source-label').textContent = draft.source === 'AUTO' ? '家庭近6个完整月净结余中位数' : '本目标手填月可用结余';
  $('contribution-reference-title').textContent = draft.source === 'AUTO' ? '查看月结余来源' : '查看家庭结余参考';
  $('source-assumption').textContent = draft.source === 'AUTO' ? '假设这些家庭结余全部用于本目标,未分配给其它目标。' : '只按分给本目标的这部分月结余估算。';
  $('source-warning').hidden = r.state !== 'missing' && r.state !== 'no-growth';
  $('source-warning').textContent = r.state === 'missing' ? '暂无完整结余参考,仅影响时间估算。资金完成度仍有效,目标配置已保存。' : '当前结余不能缩小资金缺口。资金完成度继续按已有资产计算。';
  $('auto-reference').textContent = f.automatic == null ? '暂无完整收支月份,不影响保存目标或选择此来源' : '近6个完整月 · 中位数 ' + money(f.automatic) + ' / 月';
  $('manual-field').hidden = draft.source !== 'MANUAL';
  const amountBad = draft.source === 'MANUAL' && !validAmount(draft.amount);
  const wishBad = !validWish(draft.wish);
  $('amount-error').hidden = !amountBad;
  $('amount-error').textContent = '请填写有效金额,最多2位小数,绝对值不超过10亿元。';
  $('monthly-amount').setAttribute('aria-invalid',String(amountBad));
  $('wish-error').hidden = !wishBad;
  $('wish-error').textContent = '请填写YYYY-MM,例如2036-11,或留空。';
  $('wish-input').setAttribute('aria-invalid',String(wishBad));
  $('estimate-form').querySelector('[type=submit]').disabled = amountBad || wishBad;
  $('settings-preview').textContent = amountBad || wishBad ? '请先修正标出的输入' : r.date + (r.state==='ready'?' · 还需':' · ') + r.remaining;
  const missing = f.automatic == null;
  $('month-rows').innerHTML = missing ? '<tr><td colspan="4">暂无完整的家庭收支月份;缺失不当作0。</td></tr>' : monthRows.map(row => '<tr><td>' + row[0] + '</td><td>' + money(row[1]) + '</td><td>' + money(row[2]) + '</td><td>' + money(row[1]-row[2]) + '</td></tr>').join('');
  if (!missing && selected.id === 'retirement' && ['zero','negative','long'].includes(sample)) {
    $('month-rows').innerHTML = monthRows.map(row => '<tr><td>' + row[0] + '</td><td>' + money(40000+f.automatic) + '</td><td>' + money(40000) + '</td><td>' + money(f.automatic) + '</td></tr>').join('');
  }
  $('progress-equation').textContent = money(r.assets) + ' ÷ ' + money(r.target) + ' = ' + r.progress.toFixed(1) + '%。';
  $('time-equation').textContent = r.state === 'ready' ? money(r.gap) + ' ÷ 每月' + money(r.c) + ',向上取整为' + r.months + '个月。' : r.state === 'achieved' ? '当前资金已达到所需资金,还需0个月;无需月结余数据。' : r.caption + '。不生成具体的达成日期。';
  $('date-equation').textContent = r.state === 'ready' ? '从2026年11月起,向后' + r.months + '个月,预计' + r.date + '末达到当前资金门槛。' : '基准日2026-11-10,不使用上一组条件的预测日期。';
}
function switchTab(name,focus=false) {
  activeTab = name;
  for (const button of document.querySelectorAll('[data-tab]')) {
    const active = button.dataset.tab === name;
    button.setAttribute('aria-selected',String(active));
    button.tabIndex = active ? 0 : -1;
    $('panel-'+button.dataset.tab).hidden = !active;
    if (active && focus) button.focus({preventScroll:true});
  }
}
function loadForm() {
  document.querySelector('[name=source][value='+draft.source+']').checked = true;
  $('monthly-amount').value = draft.amount;
  $('wish-input').value = draft.wish;
  $('save-status').textContent = '';
}
function navigate() {
  const key = location.hash.slice(1);
  const g = goals.find(g=>g.id===key);
  $('list-view').hidden = Boolean(g);
  $('detail-view').hidden = !g;
  if (g) {
    if (selected !== g) selected = g;
    draft = {source:g.source,amount:g.amount,wish:g.wish};
    loadForm();switchTab('overview');
  }
  render();
}
for (const button of document.querySelectorAll('[data-tab]')) {
  button.addEventListener('click',()=>switchTab(button.dataset.tab));
  button.addEventListener('keydown',event=>{
    if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
    event.preventDefault();
    const tabs=['overview','settings','principle'], i=tabs.indexOf(activeTab);
    const next=event.key==='Home'?0:event.key==='End'?2:(i+(event.key==='ArrowRight'?1:2))%3;
    switchTab(tabs[next],true);
  });
}
for (const button of document.querySelectorAll('[data-goto]')) button.addEventListener('click',()=>switchTab(button.dataset.goto));
for (const button of document.querySelectorAll('[data-sample]')) button.addEventListener('click',()=>{
  sample=button.dataset.sample;
  $('sample-label').textContent=button.textContent;
  for (const item of document.querySelectorAll('[data-sample]')) item.setAttribute('aria-pressed',String(item===button));
  $('sample-menu').open=false;render();
});
$('estimate-form').addEventListener('input',()=>{
  draft.source=document.querySelector('[name=source]:checked').value;
  draft.amount=$('monthly-amount').value;
  draft.wish=$('wish-input').value.trim();
  $('save-status').textContent='';render();
});
$('estimate-form').addEventListener('submit',event=>{
  event.preventDefault();
  if (draft.source==='MANUAL'&&!validAmount(draft.amount)) {$('monthly-amount').focus();return;}
  if (!validWish(draft.wish)) {$('wish-input').focus();return;}
  Object.assign(selected,draft);render();$('save-status').textContent='时间估算已保存。';
});
$('reset-draft').addEventListener('click',()=>{
  draft={source:selected.source,amount:selected.amount,wish:selected.wish};loadForm();render();
});
window.addEventListener('hashchange',navigate);
navigate();
