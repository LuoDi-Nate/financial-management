/*
 * backfill-ask.js · v1.34.2 · 往「之后已有人确认过余额」的账期里补记时问一句(维护者 2026-10-15 拍板:每次问,默认 A)
 *
 * 由来:重开历史期补一笔划转 / 收支,原来只改那一期的余额,后面确认过的期不跟 →
 * 对方账户下一期凭空多出一笔等额收益 / 亏损(v1.33 真实用例:A 的 09 +1,000)。
 *
 * 结构(模板里写):
 *   <main data-backfill-ids="12,15" data-backfill-period="2026-08">   服务端算好:这一期余额确认过、之后也有确认过余额的账户
 *   <form data-backfill-check [data-account-ids="12"]>                  要检查的写表单;账户从 data-account-ids
 *                                                                       和表单里的 accountId / toAccountId / fromAccountId 取
 *   <form data-backfill-collect>                                        「全部确认」:账户取页面上各张可确认卡的并集
 *   <button data-no-backfill>                                           不动余额的按钮(「这期没有」)不问
 *   <dialog data-backfill-dialog>                                       问的那个框(A 默认选中)
 *
 * 选的结果作为 backfillMode=KEEP|SHIFT 随表单提交;服务端没收到就按 A(BackfillContext),
 * 所以这里只是「问」,不是「拦」—— 不开 JS 也不会把余额改坏。
 */
(function () {
  'use strict';

  var root = document.querySelector('[data-backfill-ids]');
  if (!root) return;
  var ids = {};
  (root.getAttribute('data-backfill-ids') || '').split(',').forEach(function (x) { if (x) ids[x.trim()] = true; });
  if (!Object.keys(ids).length) return;
  var dialog = document.querySelector('[data-backfill-dialog]');
  if (!dialog || typeof dialog.showModal !== 'function') return;   // 太老的浏览器:不问,服务端按 A

  function accountsOf(form) {
    var out = [];
    (form.getAttribute('data-account-ids') || '').split(',').forEach(function (x) { if (x) out.push(x.trim()); });
    form.querySelectorAll('[name="accountId"],[name="toAccountId"],[name="fromAccountId"]').forEach(function (el) {
      if (el.value) out.push(String(el.value));
    });
    if (form.hasAttribute('data-backfill-collect')) {
      document.querySelectorAll('form[data-ff-form]').forEach(function (f) {
        if (!f.querySelector('[data-ff-confirm]')) return;
        (f.getAttribute('data-account-ids') || '').split(',').forEach(function (x) { if (x) out.push(x.trim()); });
      });
    }
    return out;
  }

  function hit(form) {
    var names = [];
    accountsOf(form).forEach(function (id) {
      if (!ids[id]) return;
      var label = id;
      var row = document.getElementById('entry-block-' + id);
      var nm = row && row.querySelector('.acct-name');
      if (nm && nm.textContent) label = nm.textContent.trim().split('\n')[0];
      if (names.indexOf(label) < 0) names.push(label);
    });
    return names;
  }

  function needs(form, submitter) {
    if (!form || !form.hasAttribute || !form.hasAttribute('data-backfill-check')) return null;
    if (submitter && submitter.hasAttribute && submitter.hasAttribute('data-no-backfill')) return null;
    // 支出表单「动余额」没勾 = 只记构成,不碰余额 → 不用问
    var ab = form.querySelector('input[name="affectsBalance"]');
    if (ab && ab.type === 'checkbox' && !ab.checked) return null;
    var names = hit(form);
    return names.length ? names : null;
  }

  function setMode(form, mode) {
    var input = form.querySelector('input[name="backfillMode"]');
    if (!input) {
      input = document.createElement('input');
      input.type = 'hidden';
      input.name = 'backfillMode';
      form.appendChild(input);
    }
    input.value = mode;
  }

  function ask(names, done) {
    var p = dialog.querySelector('[data-bf-period]');
    if (p) p.textContent = root.getAttribute('data-backfill-period') || '这一期';
    var n = dialog.querySelector('[data-bf-names]');
    if (n) n.textContent = names.length === 1 ? '「' + names[0] + '」' : '「' + names.join('」「') + '」';
    dialog.querySelector('[data-bf-keep]').checked = true;   // 每次都默认 A
    dialog.returnValue = '';
    dialog.addEventListener('close', function onClose() {
      dialog.removeEventListener('close', onClose);
      if (dialog.returnValue !== 'ok') return;
      var picked = dialog.querySelector('input[name="bf-mode"]:checked');
      done(picked ? picked.value : 'KEEP');
    });
    dialog.showModal();
  }

  // HTMX 表单(填报行的快捷支出 / 划转):在发请求之前问
  document.body.addEventListener('htmx:confirm', function (e) {
    var el = e.target;
    var form = el && el.closest ? el.closest('form') : null;
    if (!form || form.__bfPassed) return;
    var names = needs(form, null);
    if (!names) return;
    e.preventDefault();
    ask(names, function (mode) {
      setMode(form, mode);
      form.__bfPassed = true;
      try { e.detail.issueRequest(true); } finally { setTimeout(function () { form.__bfPassed = false; }, 0); }
    });
  });

  // 普通表单(收入 / 支出 / 固定收支确认):拦下提交,问完再按原来的按钮提交(保留 formaction)
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || form.hasAttribute('hx-post') || form.hasAttribute('data-hx-post')) return;
    if (form.__bfPassed) return;
    var names = needs(form, e.submitter);
    if (!names) return;
    e.preventDefault();
    var submitter = e.submitter;
    ask(names, function (mode) {
      setMode(form, mode);
      form.__bfPassed = true;
      if (typeof form.requestSubmit === 'function') form.requestSubmit(submitter || undefined);
      else form.submit();
    });
  }, true);
})();
