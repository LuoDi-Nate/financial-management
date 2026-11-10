(function () {
  'use strict';
  const root = document.querySelector('.retirement-page');
  if (!root) return;
  const form = document.getElementById('retirement-form');
  let chartData = JSON.parse(document.getElementById('retirement-chart-data').textContent);
  function value(name) { return form.elements.namedItem(name)?.value; }
  function checked(name) { return !!form.elements.namedItem(name)?.checked; }
  function enhanceSelectors() {
    window.LensSelect.enhanceAll(root);
    root.querySelectorAll('select[data-lsel]').forEach(sel => {
      const button = sel.closest('.lsel').querySelector('.lsel-btn');
      button.classList.add('field-input');
      if (sel.labels?.[0]) {
        const label = sel.labels[0];
        if (!label.id) label.id = sel.id + '-label';
        button.setAttribute('aria-labelledby', label.id);
        if (!label.dataset.retireSelectLabel) {
          label.dataset.retireSelectLabel = 'true';
          label.addEventListener('click', event => { event.preventDefault(); button.focus(); });
        }
        const search = sel.closest('.lsel').querySelector('.lsel-q');
        if (search && !search.id) {
          search.id = sel.id + '-search';
          const searchLabel = document.createElement('label');
          searchLabel.htmlFor = search.id; searchLabel.className = 'sr-only';
          searchLabel.textContent = '搜索' + label.textContent.trim();
          search.before(searchLabel);
        }
      }
    });
  }
  function modes() {
    const conditions = {
      'manual-expense': value('autoExpense') !== 'true', 'custom-scope': value('scopeMode') === 'CUSTOM',
      'city-reserves': value('reserveMode') === 'CITY', 'custom-reserves': value('reserveMode') === 'CUSTOM',
      housing: value('reserveMode') === 'CUSTOM' || value('housingPlan') === 'CHANGE',
      'manual-return': value('returnSource') === 'MANUAL', 'manual-inflation': value('inflationSource') === 'MANUAL',
      'nominal-return': value('returnSource') === 'MANUAL' && !checked('directRealRate'),
      'manual-contribution': value('contributionSource') === 'MANUAL', advanced: checked('advancedScenarios')
    };
    root.querySelectorAll('[data-retire-when]').forEach(el => { el.hidden = !conditions[el.dataset.retireWhen]; });
  }
  function children() {
    const box = document.getElementById('retire-child-years');
    const old = [...box.querySelectorAll('select')].map(el => el.value);
    const count = Number(value('dependentCount')) || 0;
    box.replaceChildren();
    for (let i = 0; i < Math.min(8, count); i++) {
      const row = document.createElement('div'), label = document.createElement('label'), select = document.createElement('select');
      label.className = 'field-label'; label.htmlFor = 'child-years-' + i; label.textContent = '子女 ' + (i + 1) + ' · 剩余规划年数';
      select.id = label.htmlFor; select.name = 'childPlanningYears[' + i + ']'; select.className = 'field-input';
      select.setAttribute('data-lsel', '');
      select.add(new Option('请选择剩余年数', ''));
      for (let y = 0; y <= 30; y++) select.add(new Option(y + ' 年' + ({18:' · 幼儿参考',12:' · 小学参考',6:' · 中学参考',3:' · 大学参考'}[y] || ''), String(y)));
      select.value = old[i] ?? ''; row.append(label, select); box.append(row);
    }
    enhanceSelectors();
  }
  const touched = new Set();
  let submitted = false;
  form.noValidate = true;
  function validateConfiguration(showAll) {
    const errors = new Map();
    function fail(name, message) { if (!errors.has(name)) errors.set(name, message); }
    function required(name, message) { if (!String(value(name) ?? '').trim()) fail(name, message); }
    function amount(name, label, min = 0, max = 1000000000000, cents = true) {
      const raw = String(value(name) ?? '').trim();
      const number = Number(raw);
      if (!raw || !Number.isFinite(number) || number < min || number > max) fail(name, label + '请填写' + min + '至' + max + '之间的数值');
      else if (cents && !/^-?\d+(?:\.\d{1,2})?$/.test(raw)) fail(name, label + '最多保留两位小数');
    }
    required('name', '请填写目标名称');
    if (String(value('name') || '').length > 64) fail('name', '目标名称最多64字');
    const month = String(value('targetMonth') || ''), parts = month.split('-').map(Number), now = new Date();
    if (!/^\d{4}-\d{2}$/.test(month) || parts[0] < 1 || parts[1] < 1 || parts[1] > 12) fail('targetMonth', '请选择有效退休年月');
    else if ((parts[0] - now.getFullYear()) * 12 + parts[1] - now.getMonth() - 1 > 600) fail('targetMonth', '目标年月须在未来50年内');
    if (value('autoExpense') !== 'true') amount('monthlyExpense', '每月生活费');
    if (value('reserveMode') === 'CITY') {
      required('cityTier', '请选择城市方案');
      required('dependentCount', '请选择需要额外教育预留的子女人数,无需预留请选择0位');
      const count = Number(value('dependentCount')) || 0;
      for (let i = 0; i < count; i++) required('childPlanningYears[' + i + ']', '请选择子女 ' + (i + 1) + ' 的剩余规划年数');
      required('housingPlan', '请选择住房安排');
      if (value('housingPlan') === 'CHANGE') amount('housingReserve', '住房净新增现金需求');
    } else {
      [['childReserve','额外教育预留'],['medicalReserve','医疗现金储备'],['housingReserve','住房净新增现金需求'],['otherReserve','应急及其他预留']]
        .forEach(([name,label]) => amount(name,label));
      if (value('educationGoalId') && !checked('educationConfirmed')) fail('educationConfirmed', '请核对关联教育目标的币值日期与剩余费用');
    }
    if (!checked('noOverlapConfirmed')) fail('noOverlapConfirmed', '请确认生活费与大额支出准备金没有重复');
    if (form.elements.namedItem('adoptionConfirmed') && !checked('adoptionConfirmed')) fail('adoptionConfirmed', '请先比较旧新算法,并确认采用新方案');
    if (form.elements.namedItem('revisionConfirmed') && !checked('revisionConfirmed')) fail('revisionConfirmed', '请确认升级修订二,原方案会完整保留');
    if (value('inflationSource') === 'MANUAL') amount('inflationPct', '通胀率', -20, 50, false);
    if (value('returnSource') === 'MANUAL') amount(checked('directRealRate') ? 'realReturnPct' : 'nominalReturnPct', '回报率', -99, 100, false);
    if (checked('simulateFuture')) {
      if (value('contributionSource') === 'MANUAL') amount('monthlyContribution', '每月结余', -1000000000, 1000000000);
      if (checked('advancedScenarios')) {
        ['lowReturnPct','middleReturnPct','highReturnPct'].forEach(name => amount(name,'情景回报',-99,100,false));
        if (Number(value('lowReturnPct')) > Number(value('middleReturnPct')) || Number(value('middleReturnPct')) > Number(value('highReturnPct')))
          fail('middleReturnPct', '情景回报应满足低≤中≤高');
      }
    }
    root.querySelectorAll('[data-retire-field-error]').forEach(el => el.remove());
    root.querySelectorAll('[aria-invalid="true"]').forEach(el => { el.removeAttribute('aria-invalid'); el.removeAttribute('aria-describedby'); });
    errors.forEach((message,name) => {
      if (!showAll && !submitted && !touched.has(name)) return;
      const control = form.elements.namedItem(name);
      const input = control instanceof RadioNodeList ? control[0] : control;
      if (!input) return;
      const visible = input.closest('.lsel')?.querySelector('.lsel-btn') || input;
      const text = document.createElement('p');
      text.id = 'retire-error-' + name.replace(/[^a-zA-Z0-9]/g,'-');
      text.className = 'retire-field-error'; text.dataset.retireFieldError = name; text.textContent = message;
      visible.setAttribute('aria-invalid','true'); visible.setAttribute('aria-describedby',text.id);
      const container = input.closest('.lsel,.retire-check') || input;
      container.insertAdjacentElement('afterend',text);
    });
    return errors;
  }
  let timer, request, generation = 0;
  const draftStatus = document.getElementById('retire-draft-status');
  async function recalculate() {
    const current = ++generation;
    if (request) request.abort();
    request = new AbortController();
    const status = document.getElementById('retire-live-status');
    status.textContent = '正在更新演算…';
    try {
      const body = new FormData(form); body.set('ccy', chartData.currency);
      if(window.goalEstimateData)for(const [name,value]of Object.entries(window.goalEstimateData()))body.set(name,value);
      const response = await fetch(form.action + '/preview', {method:'POST',body,signal:request.signal,headers:{'X-Retirement-Preview':'true'}});
      if (!response.ok) throw new Error('preview');
      const page = new DOMParser().parseFromString(await response.text(), 'text/html');
      const json = page.getElementById('retirement-chart-data');
      if (current !== generation) return;
      if (!json) throw new Error('preview result missing');
      const data = JSON.parse(json.textContent);
      root.querySelectorAll('[data-retire-live]').forEach(el => {
        const next = page.querySelector('[data-retire-live="' + el.dataset.retireLive + '"]');
        if (!next) return;
        const open = [...el.querySelectorAll('details')].map(d => d.open);
        el.innerHTML = next.innerHTML;
        el.querySelectorAll('details').forEach((d,i) => { if (open[i] !== undefined) d.open = open[i]; });
      });
      chartData = data; if (root.dataset.tab === 'overview') draw();
      document.dispatchEvent(new CustomEvent('goal-estimate-rendered'));
      status.textContent = '演算已更新,尚未保存。';
      draftStatus.hidden = false; draftStatus.textContent = '这是尚未保存的测算。';
    } catch (error) {
      if (error.name !== 'AbortError') {
        status.textContent = '自动演算未完成,可点击演算预览重试。';
        draftStatus.hidden = false; draftStatus.textContent = '计划调整尚未保存,演算未完成。';
      }
    }
  }
  function changed(event) {
    if (!event.target.form || event.target.form.id !== form.id) return;
    draftStatus.hidden = false; draftStatus.textContent = '计划调整尚未保存。';
    if (event.target.name === 'dependentCount') children();
    if (event.target.name) touched.add(event.target.name);
    modes(); validateConfiguration(false); clearTimeout(timer); timer = setTimeout(recalculate, 450);
  }
  root.addEventListener('change', changed);
  root.addEventListener('input', event => { if (event.target.type === 'number') changed(event); });
  root.addEventListener('focusout', event => {
    if (event.target.form === form && event.target.name) { touched.add(event.target.name); validateConfiguration(false); }
  });
  root.querySelector('[data-retire-example]').addEventListener('click', () => {
    form.elements.returnSource.value = 'MANUAL'; form.elements.inflationSource.value = 'MANUAL';
    form.elements.nominalReturnPct.value = '4.5'; form.elements.inflationPct.value = '2'; form.elements.directRealRate.checked = false;
    modes(); recalculate();
  });
  form.addEventListener('submit', event => {
    if (!event.submitter?.hasAttribute('data-retire-preview')) {
      submitted = true;
      const errors = validateConfiguration(true);
      if (errors.size) {
        event.preventDefault();
        const control = form.elements.namedItem(errors.keys().next().value);
        const input = control instanceof RadioNodeList ? control[0] : control;
        tab(input.closest('[data-retire-panel]')?.dataset.retirePanel || 'plan');
        for (let parent = input.parentElement; parent; parent = parent.parentElement) if (parent.tagName === 'DETAILS') parent.open = true;
        const focus = input.closest('.lsel')?.querySelector('.lsel-btn') || input;
        focus.focus(); focus.scrollIntoView({block:'center'});
        return;
      }
    }
    clearTimeout(timer); generation++; if(request) request.abort();
  });
  function tab(name) {
    if (!['overview', 'plan', 'principle'].includes(name)) name = 'overview';
    root.dataset.tab = name;
    root.querySelectorAll('[data-retire-panel]').forEach(el => { el.hidden = el.dataset.retirePanel !== name; });
    root.querySelectorAll('[data-retire-tab]').forEach(el => el.setAttribute('aria-selected', String(el.dataset.retireTab === name)));
    if (name === 'overview') draw();
  }
  root.querySelectorAll('[data-retire-tab]').forEach(el => el.addEventListener('click', () => tab(el.dataset.retireTab)));
  root.querySelectorAll('[data-retire-goto]').forEach(el => el.addEventListener('click', () => {
    tab(el.dataset.retireGoto);
    root.querySelector('.retire-tabs').scrollIntoView({block:'start'});
  }));
  root.querySelectorAll('#retirement-form input[type=number]').forEach(el => el.addEventListener('focus', () => el.select()));
  let chart;
  function draw() {
    const el = document.getElementById('retirement-path');
    const data = chartData;
    if (chart) { chart.destroy(); chart = null; }
    if (!el || !data || !data.scenarios.length || typeof Chart === 'undefined') return;
    const targetParts = String(data.retirementDate).split('-').map(Number);
    const startDate = new Date(data.date + 'T12:00:00');
    const targetIndex = Math.max(0,(targetParts[0]-startDate.getFullYear())*12+targetParts[1]-startDate.getMonth()-1);
    const neutral = data.neutralDate;
    const neutralParts = String(neutral || '').split('-').map(Number);
    const reachedIndex = neutralParts.length > 1 ? Math.max(0,(neutralParts[0]-startDate.getFullYear())*12+neutralParts[1]-startDate.getMonth()-1) : 0;
    const months = Math.min(600,Math.max(120,targetIndex+60,reachedIndex+12));
    const labels = data.scenarios[0].path.slice(0,months+1).map((_, index) => {
      const start = new Date(data.date + 'T12:00:00');
      const month = new Date(start.getFullYear(), start.getMonth() + index, 1);
      return month.getFullYear() + '-' + String(month.getMonth() + 1).padStart(2, '0');
    });
    const colors = ['#a34d40', '#3e7157', '#427b91'];
    const datasets = data.scenarios.map((s, i) => ({label:s.label, data:s.path.slice(0,months+1).map(v => Number(v) * Number(data.factor)),
      borderColor:data.scenarios.length === 1 ? colors[1] : colors[i], pointRadius:0, borderWidth:1.7, tension:0}));
    datasets.push({label:'生活本金门槛', data:labels.map(() => Number(data.target) * Number(data.factor)),
      borderColor:'#a18544', borderDash:[5,4], borderWidth:1.3, pointRadius:0});
    const targetMarker = {id:'retirementTarget',afterDatasetsDraw(c) {
      if (targetIndex >= labels.length) return;
      const x=c.scales.x.getPixelForValue(targetIndex), ctx=c.ctx, area=c.chartArea;
      ctx.save();ctx.strokeStyle='#87744f';ctx.setLineDash([3,4]);ctx.beginPath();ctx.moveTo(x,area.top);ctx.lineTo(x,area.bottom);ctx.stroke();
      ctx.setLineDash([]);ctx.fillStyle='#655c4d';ctx.font='11px sans-serif';ctx.textAlign=x>area.right-100?'right':'left';
      ctx.fillText('目标 '+labels[targetIndex],x+(x>area.right-100?-5:5),area.top+14);ctx.restore();
    }};
    chart = new Chart(el, {type:'line', data:{labels, datasets},
      options:{responsive:true, maintainAspectRatio:false, animation:false,
        scales:{x:{ticks:{maxTicksLimit:6,maxRotation:0}},y:{ticks:{callback:v => new Intl.NumberFormat('zh-CN',{notation:'compact'}).format(v)}}},
        plugins:{legend:{position:'bottom',labels:{boxWidth:12,font:{size:11}}},
          tooltip:{callbacks:{label:ctx => ctx.dataset.label + ': ' + data.currency + ' ' + Number(ctx.raw).toLocaleString('zh-CN',{maximumFractionDigits:0})}},
          datalabels:{display:ctx => ctx.datasetIndex === (data.scenarios.length === 1 ? 0 : 1) && ctx.dataIndex === labels.length - 1,
            formatter:v => new Intl.NumberFormat('zh-CN',{notation:'compact'}).format(v), clamp:true, align:225, color:'#3e7157',font:{size:10}}}},
      plugins:typeof ChartDataLabels === 'undefined' ? [targetMarker] : [ChartDataLabels,targetMarker]});
  }
  window.addEventListener('vpchange', () => { if (!document.getElementById('retire-overview').hidden) draw(); });
  root.querySelector('.retire-future').addEventListener('toggle', event => { if(event.target.open) draw(); });
  children(); modes();
  tab(root.dataset.tab || 'overview');
})();
