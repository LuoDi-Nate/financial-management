(function () {
  'use strict';
  const root = document.querySelector('.retirement-page');
  if (!root) return;
  function tab(name) {
    if (!['overview', 'plan', 'principle'].includes(name)) name = 'overview';
    root.querySelectorAll('[data-retire-panel]').forEach(el => { el.hidden = el.dataset.retirePanel !== name; });
    root.querySelectorAll('[data-retire-tab]').forEach(el => el.setAttribute('aria-selected', String(el.dataset.retireTab === name)));
    if (name === 'overview') draw();
  }
  root.querySelectorAll('[data-retire-tab]').forEach(el => el.addEventListener('click', () => tab(el.dataset.retireTab)));
  let chart;
  function draw() {
    const el = document.getElementById('retirement-path');
    const data = window.retirementChartData;
    if (!el || !data || !data.scenarios.length || typeof Chart === 'undefined') return;
    if (chart) chart.destroy();
    const labels = data.scenarios[0].path.map((_, index) => {
      const start = new Date(data.date + 'T12:00:00');
      const month = new Date(start.getFullYear(), start.getMonth() + index, 1);
      return month.getFullYear() + '-' + String(month.getMonth() + 1).padStart(2, '0');
    });
    const colors = ['#a34d40', '#3e7157', '#427b91'];
    const datasets = data.scenarios.map((s, i) => ({label:s.label, data:s.path.map(v => Number(v) * Number(data.factor)),
      borderColor:colors[i], pointRadius:0, borderWidth:1.7, tension:0}));
    datasets.push({label:'生活本金门槛', data:labels.map(() => Number(data.target) * Number(data.factor)),
      borderColor:'#a18544', borderDash:[5,4], borderWidth:1.3, pointRadius:0});
    chart = new Chart(el, {type:'line', data:{labels, datasets},
      options:{responsive:true, maintainAspectRatio:false, animation:false,
        scales:{x:{ticks:{maxTicksLimit:6,maxRotation:0}},y:{ticks:{callback:v => new Intl.NumberFormat('zh-CN',{notation:'compact'}).format(v)}}},
        plugins:{legend:{position:'bottom',labels:{boxWidth:12,font:{size:11}}},
          tooltip:{callbacks:{label:ctx => ctx.dataset.label + ': ' + data.currency + ' ' + Number(ctx.raw).toLocaleString('zh-CN',{maximumFractionDigits:0})}},
          datalabels:{display:ctx => ctx.datasetIndex === 1 && ctx.dataIndex === labels.length - 1,
            formatter:v => new Intl.NumberFormat('zh-CN',{notation:'compact'}).format(v), clamp:true, align:225, color:'#3e7157',font:{size:10}}}},
      plugins:typeof ChartDataLabels === 'undefined' ? [] : [ChartDataLabels]});
  }
  window.addEventListener('vpchange', () => { if (!document.getElementById('retire-overview').hidden) draw(); });
  tab(root.dataset.tab || 'overview');
})();
