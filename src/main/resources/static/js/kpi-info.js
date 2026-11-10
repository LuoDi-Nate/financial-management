/**
 * KPI 口径说明 tooltip · v0.4.13
 *
 * 交互:点击 [data-kpi-info] 按钮 → toggle 同层 .kpi-info-panel 显隐
 * 手机端 tap toggle · 桌面端也是 click(不用 hover · 保持一致)
 * 点击页面其它位置 → 关闭所有 panel
 *
 * 事件委托 · HTMX afterSettle 不需要重绑
 */
(function () {
  function placeGoalSummary(panel) {
    if (!panel.hasAttribute('data-goal-progress-summary')) return;
    panel.style.marginLeft = '';
    panel.style.top = '';
    panel.style.bottom = '';
    panel.style.maxHeight = '';
    if (getComputedStyle(panel).position === 'fixed') {
      var header = document.querySelector('header.sticky');
      var top = Math.max(16, header ? header.getBoundingClientRect().bottom + 8 : 80);
      panel.style.top = top + 'px';
      panel.style.maxHeight = Math.max(96, innerHeight - top - 16) + 'px';
      return;
    }
    var rect = panel.getBoundingClientRect();
    var width = document.documentElement.clientWidth;
    var shift = Math.max(8 - rect.left, Math.min(0, width - 8 - rect.right));
    panel.style.marginLeft = shift + 'px';
    var button = panel.previousElementSibling.getBoundingClientRect();
    var below = innerHeight - button.bottom - 8, above = button.top - 8;
    var useAbove = rect.height > below && above > below;
    if (useAbove) { panel.style.top = 'auto'; panel.style.bottom = 'calc(100% + 4px)'; }
    panel.style.maxHeight = Math.max(96, useAbove ? above : below) + 'px';
  }
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-kpi-info]');
    if (btn) {
      e.preventDefault();
      e.stopPropagation();
      var panel = btn.nextElementSibling;
      if (!panel || !panel.classList.contains('kpi-info-panel')) return;
      var wasHidden = panel.classList.contains('hidden');
      // 先关闭所有已开的
      document.querySelectorAll('.kpi-info-panel:not(.hidden)').forEach(function (p) {
        p.classList.add('hidden');
      });
      // toggle 当前
      if (wasHidden) { panel.classList.remove('hidden'); placeGoalSummary(panel); }
    } else if (e.target.closest('[data-goal-progress-summary]')) {
      e.preventDefault();
      e.stopPropagation();
    } else {
      // 点击其它区域 → 关闭所有
      document.querySelectorAll('.kpi-info-panel:not(.hidden)').forEach(function (p) {
        p.classList.add('hidden');
      });
    }
  });
  window.addEventListener('resize', function () {
    document.querySelectorAll('[data-goal-progress-summary]:not(.hidden)').forEach(placeGoalSummary);
  });
})();
