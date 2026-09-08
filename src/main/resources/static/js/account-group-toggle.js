/*
 * v1.20 FR-483b · 账户列表里「组行」的展开 / 收起。
 *
 * 为什么不往返服务器:成员行已经渲染在 DOM 里(只是 display:none)——
 * 它们的数据本来就在手上。这比归因区的下钻更轻,那边点开要重算整段归因。
 *
 * 为什么用事件委托绑在 document 上:账户表在指标 chip 切换时会整块重绘,
 * 而 dashboard / reports 两页都用这张表。直接绑按钮的话,重绘一次就全掉了。
 *
 * 为什么放在共享文件里而不是各页内联:两页的表是同一套范式,
 * 内联意味着两份实现,而两份实现一定会漂 —— 改一处忘另一处,还没有测试会红。
 */
(function () {
  if (window.__acctGroupToggleBound) return;
  window.__acctGroupToggleBound = true;

  document.addEventListener('click', function (e) {
    var btn = e.target.closest && e.target.closest('.acct-toggle');
    if (!btn) return;
    e.preventDefault();
    var gid = btn.getAttribute('data-toggle-group');
    if (!gid) return;

    var rows = document.querySelectorAll('[data-member-of="' + gid + '"]');
    if (!rows.length) return;
    var opening = rows[0].style.display === 'none';
    Array.prototype.forEach.call(rows, function (r) {
      r.style.display = opening ? '' : 'none';
    });

    // 箭头跟着翻。PC 表和手机卡片可能同时存在(响应式各渲一份),所以是 querySelectorAll。
    Array.prototype.forEach.call(
      document.querySelectorAll('.acct-toggle[data-toggle-group="' + gid + '"]'),
      function (b) {
        var cap = b.querySelector('span:last-child');
        if (cap) cap.textContent = cap.textContent.replace(/[▾▴]/, opening ? '▴' : '▾');
      }
    );
  });
})();
