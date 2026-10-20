/* 家庭账房 · 方向控制(v1.6.15 · 原地切断点,不再重载页面)
 *
 * ══ 为什么去掉 iframe ══════════════════════════════════════════
 * v1.6.6–v1.6.14 用「同源 iframe + 独立 viewport」实现整页横屏。它确实解决了
 * 「453 处响应式断点在 844×390 下集体切换」的问题,但代价被用户一眼指出:
 *   点一下横屏 = 一次完整的 /dashboard 后端渲染 + 12 个脚本重跑。
 *   实测 beta(本机、热缓存)**1362ms**;真机走网络更久 —— 用户原话「一段很长的卡顿」。
 * 而且 iframe 是新文档 → 滚动位置必然丢(用户反馈:在旭日章节切完回到顶部)。
 *
 * ══ 现在的做法:原地换断点 ═════════════════════════════════════
 * 本项目的 Tailwind 是 **Play CDN 运行时编译器**,重新赋值 `tailwind.config`
 * 会触发它就地重编译。实测 **114ms**,`md:` 当场从 off 翻到 on。
 * 所以横屏模式 =
 *   ① 把 sm/md 换成「永远匹配」的 raw 查询(lg/xl 仍按宽度 → 与旧 iframe 的 844 档观感一致)
 *   ② body 锁成「长边 × 短边」并在设备竖屏时转 90°(视口单位交换 · 纯 CSS · 见 style.css)
 *   ③ 交回滚动位置(滚动容器从 html 变 body,两套 scrollTop)
 * 零网络请求、零重新渲染、滚动位置保住。
 *
 * ══ 断点基线只有一处 ══════════════════════════════════════════
 * 基线在 layout.html 的 `window.TW_SCREENS_BASE`(含「短边 ≥480」判据),
 * extend(颜色/字体)在 `window.TW_EXTEND_BASE`。这里只换 screens、原样带上 extend,
 * 退出时换回基线 —— 不复制一份配置,避免两处漂移。
 */
(function () {
  'use strict';

  var root = document.documentElement;
  var lastY = 0;          /* 最近一次已知滚动位置(两种滚动容器共用) */
  var exitBtn = null;
  var oriTimer = null;

  /* ── 滚动位置:两种容器的 scrollTop 是两套值,切换时必须手动交接 ── */
  function curY() {
    return Math.max(document.body ? document.body.scrollTop : 0, root.scrollTop || 0, window.scrollY || 0);
  }
  function trackY() { var y = curY(); if (y > 0) lastY = y; }
  /* capture 阶段才能收到元素级滚动(body 当滚动容器时 scroll 不冒泡) */
  document.addEventListener('scroll', trackY, true);

  function restoreY(y) {
    if (!y) return;
    requestAnimationFrame(function () {
      requestAnimationFrame(function () {
        if (document.body && getComputedStyle(document.body).position === 'fixed') document.body.scrollTop = y;
        else window.scrollTo(0, y);
      });
    });
  }

  /* ── 切换后按**章节**还原,而不是按像素 ────────────────────────────
     横屏把布局宽度从短边换成长边,同一段内容的像素位置本来就不一样(实测 2928 → 3630),
     照像素还原必然错位。用户要的是"还在旭日章节",所以记住当前顶部那个带 id 的章节。

     两个坑,都实测踩过:
       ① **不能用 getBoundingClientRect().top 找章节** —— 横屏时 body 被 rotate(90°),
          rect 是屏幕坐标,阅读流的"上"映射到屏幕的"右";照它挑会挑到毫不相干的元素
          (实测退出后章节漂到 top=3125)。要在**布局坐标系**里算:累加 offsetTop。
       ② **不能用 scrollIntoView** —— 同样有视觉坐标语义问题;直接写 scrollTop 最确定。 */
  function layoutTop(el) {
    var y = 0;
    while (el && el !== document.body) { y += el.offsetTop || 0; el = el.offsetParent; }
    return y;
  }
  function bodyIsScroller() {
    return !!document.body && getComputedStyle(document.body).position === 'fixed';
  }
  function currentAnchor() {
    var top = bodyIsScroller() ? document.body.scrollTop : (window.scrollY || root.scrollTop || 0);
    var nodes = document.querySelectorAll('main [id]');
    var best = null, bestDelta = -1e9;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!el.id || el.offsetHeight < 40) continue;      /* 跳过小锚点 */
      var d = layoutTop(el) - top;
      if (d <= 90 && d > bestDelta) { bestDelta = d; best = el.id; }
    }
    return best;
  }
  function setScrollTop(y) {
    if (bodyIsScroller()) document.body.scrollTop = y;
    else window.scrollTo(0, y);
  }
  /* 还原要**重复几次**:Tailwind 的重编译是异步的(实测约 114ms),rAF×2 早于它落定 ——
     只做一次会被随后的重排冲掉。写同一个 scrollTop 是幂等的,多做几次没有副作用。 */
  function restoreAnchor(id, fallbackY) {
    var run = function () {
      var el = id && document.getElementById(id);
      setScrollTop(el ? Math.max(0, layoutTop(el) - 8) : (fallbackY || 0));
    };
    requestAnimationFrame(function () { requestAnimationFrame(run); });
    setTimeout(run, 160);
    setTimeout(run, 400);
  }

  /* ── 图表重排 ── v1.36 起实现搬到 viewport.js(window.vpRelayoutCharts · 全站只此一份)。
     横屏时 body 被旋转,Chart.js 无参 resize() 量到的是屏幕坐标 → 必须显式给容器尺寸(explicit)。
     切换时打时间戳:viewport.js 看到就不碰滚动位置,滚动由这里的 restoreAnchor / restoreY 管。 */
  function relayoutCharts() {
    if (window.vpRelayoutCharts) window.vpRelayoutCharts({ explicit: true });
  }
  function markSwitch() { window.__lsSwitchAt = Date.now(); }

  /* ── 断点切换 ── */
  function setScreens(screens) {
    if (!window.tailwind || !screens) return false;
    window.tailwind.config = { theme: { screens: screens, extend: window.TW_EXTEND_BASE || {} } };
    return true;
  }
  /* 横屏档在 layout.html 的 window.TW_SCREENS_WIDE(单一出处)——
     那里必须在**首次编译前**就能用上它,才能做到跨页面保持横屏时不闪一下竖屏。 */
  function wideScreens() { return window.TW_SCREENS_WIDE; }


  function svgIcon(d, size) {
    var NS = 'http://www.w3.org/2000/svg';
    var s = document.createElementNS(NS, 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('width', size); s.setAttribute('height', size);
    s.setAttribute('fill', 'none'); s.setAttribute('stroke', 'currentColor');
    s.setAttribute('stroke-width', '2');
    s.setAttribute('stroke-linecap', 'round'); s.setAttribute('stroke-linejoin', 'round');
    s.setAttribute('aria-hidden', 'true');
    var pa = document.createElementNS(NS, 'path'); pa.setAttribute('d', d); s.appendChild(pa);
    return s;
  }

  function syncBtn() {
    var on = root.classList.contains('ls-wide');
    document.querySelectorAll('[data-orientation-toggle]').forEach(function (b) {
      b.setAttribute('aria-pressed', on ? 'true' : 'false');
      if (b !== exitBtn) b.title = on ? '退回竖屏' : '整页横屏查看(适合宽表格)';
    });
  }

  /* 横屏时把「本页目录」钮搬进导航行的 flex 流(结构上不可能与隐私钮重叠) */
  function tocIntoNav(into) {
    var fab = document.querySelector('.toc-fab');
    var acts = document.querySelector('.nav-actions');
    if (!fab || !acts) return;
    if (into) {
      if (fab.parentElement !== acts) { fab.setAttribute('data-toc-moved', '1'); acts.insertBefore(fab, acts.firstChild); }
    } else if (fab.getAttribute('data-toc-moved') && fab.parentElement === acts) {
      var dock = document.getElementById('float-dock');
      (dock || document.body).appendChild(fab); fab.removeAttribute('data-toc-moved');
    }
  }

  /* 退出钮必须**放进导航行**,不能用 position:fixed ————————————————————
     body 被 transform 后,里面 position:fixed 的元素包含块变成 body,
     于是它跟着内容一起滚走(实测 rect 3413,730 —— 滚到屏外)。
     放进 .nav-actions:随 sticky 导航常驻、不会被 iPhone 圆角切、也不用给它预留边距。 */
  function ensureExit() {
    if (exitBtn && exitBtn.isConnected) return exitBtn;
    exitBtn = document.createElement('button');
    exitBtn.type = 'button';
    exitBtn.className = 'ls-exit';
    exitBtn.setAttribute('data-orientation-toggle', '');
    exitBtn.setAttribute('title', '退回竖屏');
    exitBtn.appendChild(svgIcon('M18 6 6 18M6 6l12 12', 12));
    var t = document.createElement('span'); t.textContent = '退出横屏';
    exitBtn.appendChild(t);
    var acts = document.querySelector('.nav-actions');
    (acts || document.body).appendChild(exitBtn);
    return exitBtn;
  }

  function enter() {
    if (root.classList.contains('ls-wide')) return;
    markSwitch();
    var y = curY() || lastY, anchor = currentAnchor();
    if (!setScreens(wideScreens())) return;      /* 运行时编译器不在 → 不做半套 */
    root.classList.add('ls-wide');
    try { sessionStorage.setItem('lsWide', '1'); } catch (e) {}   /* 跨页面保持 */
    ensureExit();
    tocIntoNav(true);
    relayoutCharts();
    restoreAnchor(anchor, y);
    syncBtn();
  }

  function exit() {
    if (!root.classList.contains('ls-wide')) return;
    markSwitch();
    var y = curY() || lastY, anchor = currentAnchor();
    root.classList.remove('ls-wide');
    try { sessionStorage.removeItem('lsWide'); } catch (e) {}
    setScreens(window.TW_SCREENS_BASE);
    tocIntoNav(false);
    if (exitBtn) { exitBtn.remove(); exitBtn = null; }
    dockFloats();
    relayoutCharts();
    restoreAnchor(anchor, y);
    syncBtn();
  }

  window.toggleOrientation = function () {
    if (root.classList.contains('ls-wide')) exit(); else enter();
  };

  document.addEventListener('click', function (e) {
    if (!e.target.closest) return;
    var b = e.target.closest('[data-orientation-toggle]');
    if (b) { e.preventDefault(); window.toggleOrientation(); }
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && root.classList.contains('ls-wide')) exit();
  });

  /* ── 系统浮钮进 flex dock(v1.6.17)──────────────────────────────────
     原来三个浮钮各写死 bottom 偏移(隐私 18 / 目录 +48 / 方向 +96)。
     哪个页面缺一个(如打标页没有目录钮)就在栈里留一个空洞 —— 用户反馈④a。
     改成塞进一个 flex 列容器:有几个排几个,不可能留空位;
     dock 的 z-index 高于首屏遮罩 → 系统能力不被页面加载进度绑住(反馈①/④b)。 */
  function dockFloats() {
    if (root.classList.contains('ls-wide')) return;      /* 横屏态浮钮另有安排 */
    var dock = document.getElementById('float-dock');
    if (!dock) {
      dock = document.createElement('div');
      dock.id = 'float-dock';
      document.body.appendChild(dock);
    }
    /* 顺序:方向 → 目录 → 超级 Agent → 隐私(从上到下,最常用的隐私眼离拇指最近)
       v1.19.6 把 AI 入口插在隐私眼**之上**:隐私眼的位置不动(那是已经形成的肌肉记忆),
       而 AI 进到这一组里,从「汉堡→展开→点」两步变成一步。
       注意:必须**每次都按序 append 全部三个**,不能加「已在 dock 里就跳过」的守卫。
       横屏时 tocIntoNav 把目录钮搬进了导航栏,切回竖屏时它不在 dock 里 —— 带守卫的话
       它被 append 到末尾、另两个原位不动,顺序就变成 方向/隐私/目录(用户报的 bug)。
       appendChild 对已在容器内的节点等于「移到末尾」,按序全 append 一遍即得声明顺序,幂等。 */
    ['#ori-float', '.toc-fab', '#ask-float', '#priv-float'].forEach(function (sel) {
      var el = document.querySelector(sel);
      if (el) dock.appendChild(el);
    });
    return dock;
  }

  /* 跨页面保持横屏(用户:点账户详情跳过去就变回竖屏了)。
     head 里已据 sessionStorage 贴好 html.ls-wide 并让 tailwind 首次编译就用宽屏档,
     这里只补齐 JS 侧那几件:退出钮 / 目录钮进导航 / 图表按新宽度重排 / 按钮态。 */
  function adoptWideOnLoad() {
    if (!root.classList.contains('ls-wide')) return;
    ensureExit();
    tocIntoNav(true);
    relayoutCharts();
  }
  document.addEventListener('DOMContentLoaded', function () { syncBtn(); dockFloats(); adoptWideOnLoad(); });
  dockFloats();
  adoptWideOnLoad();
  syncBtn();

  /* ══ 普通模式(未进横屏)· 把内容钉在设备坐标系 ══════════════════════
     样式在 style.css 的冻结块。这里只做两件 CSS 做不到的事:
       ① 旋转符号:screen.orientation.angle 是 viewport 相对自然方向**顺时针**转过的角度,
          抵消就是转 -angle → angle 270(设备顺时针)挂 html.ori-cw 取 +90°。
          iOS 16.4+ 有 screen.orientation,更老回落 window.orientation。
       ② 滚动位置交接 + 旋转遮帘(iOS 自己的旋转动画抹不掉,只能盖成纯纸面)。 */
  var FREEZE_Q = '(pointer: coarse) and (orientation: landscape) and (max-height: 479px) and (min-width: 480px)';

  function syncAngle() {
    var a = null;
    if (window.screen && window.screen.orientation && typeof window.screen.orientation.angle === 'number') {
      a = window.screen.orientation.angle;
    } else if (typeof window.orientation === 'number') {
      a = window.orientation < 0 ? 270 : window.orientation;
    }
    root.classList.toggle('ori-cw', a === 270);
  }
  function oriCurtain() {
    if (root.classList.contains('ls-wide')) return;
    root.classList.add('ori-turning');
    if (oriTimer) clearTimeout(oriTimer);
    oriTimer = setTimeout(function () { oriTimer = null; root.classList.remove('ori-turning'); }, 460);
  }
  /* v1.36 · 只有冻结旋转参与的转向(进 / 出)才归这里管滚动、打时间戳让 viewport.js 让路。
     折叠屏从内屏横着合到外屏竖着也会触发转向事件,但那不涉及冻结 —— 由 viewport.js 按章节还原。 */
  var freezeMq = window.matchMedia ? window.matchMedia(FREEZE_Q) : null;
  var lastFrozen = !!(freezeMq && freezeMq.matches);
  function onTurn() {
    var fz = !!(freezeMq && freezeMq.matches);
    if (fz || lastFrozen) markSwitch();
    lastFrozen = fz;
    syncAngle(); oriCurtain(); restoreY(lastY);
  }

  syncAngle();
  if (freezeMq) {
    if (freezeMq.addEventListener) freezeMq.addEventListener('change', onTurn);
    else if (freezeMq.addListener) freezeMq.addListener(onTurn);
  }
  window.addEventListener('orientationchange', onTurn);
  if (window.screen && window.screen.orientation && window.screen.orientation.addEventListener) {
    window.screen.orientation.addEventListener('change', onTurn);
  }
})();
