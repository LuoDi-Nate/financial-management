/*
 * menus.js · v1.35 · 下拉菜单 / 浮层(<details>)的通用收起行为
 *
 * 持仓页「添加其他 ▾」、持仓卡「改份额」浮层都是原生 <details>:点 summary 开关,不需要 JS。
 * 但原生 details 点外面不会收起 —— 浮层盖在别的内容上时,用户得回去再点一次按钮才能关,很别扭。
 * 这里补两件事:点菜单外面收起;按 Esc 收起。只管带 data-menu-close 的,或 class 含 row-more.menu-btn / nav-edit 的。
 * 账户簿的 ⋯ 菜单有自己的定位脚本(accounts/index.html),这里不碰它。
 */
(function () {
  'use strict';
  var SEL = 'details.row-more.menu-btn[open], details.nav-edit[open], details[data-menu-close][open]';
  document.addEventListener('click', function (e) {
    document.querySelectorAll(SEL).forEach(function (d) {
      if (!d.contains(e.target)) d.open = false;
    });
  });
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    document.querySelectorAll(SEL).forEach(function (d) { d.open = false; });
  });
})();
