/* 家庭账房 · 页面打开以后尺寸变了(v1.36 · iPhone Duo 折叠屏)
 *
 * 以前「打开以后宽度还会变」只有手机转屏一种情况,由 landscape.js 专门处理。
 * 折叠屏让同一页面在 382 和 951 之间来回变成了日常操作,实测三处走样(tech-design/v1.36.md §零):
 *   ① ECharts 不跟容器 → 资产透视旭日图停在展开时的 882px,整页被撑到 918px;
 *   ② 依赖宽窄形态的图(字号 / 图例 / 柱子个数)停在打开那一刻的形态;
 *   ③ 窄屏收起的筛选条,变宽后连「展开」按钮也被 CSS 藏了 → 整条消失;
 *   另外正在看的那一节会被挤走:Chromium 的滚动锚定不管视口宽度变化。
 *
 * 这里做四件事,全部只在「尺寸停止变化 150ms 后」做一次:
 *   · 重排全部图表(window.vpRelayoutCharts · landscape.js 也调它,只此一份实现)
 *   · vpNarrow(640) / vpNarrow(768) 结果翻转 → 派发 'vpchange',依赖形态的图各自订阅重画
 *     (和 layout.html 的 'fontscalechange' 同一套做法;新写的图读了 vpNarrow 就必须订阅,护栏 v136-VP-CHARTS-SUBSCRIBE)
 *   · 越过 768 变宽 → 展开收起的筛选条(变窄不收:内容不从手指底下消失)
 *   · 宽度变了 → 滚回变化前正在看的那一节
 * 手机横屏(冻结旋转 / 整页横屏)期间由 landscape.js 管滚动,这里不插手。 */
(function () {
  'use strict';

  var root = document.documentElement;
  var FREEZE_Q = '(pointer: coarse) and (orientation: landscape) and (max-height: 479px) and (min-width: 480px)';

  /* ── 图表重排 ──────────────────────────────────────────────────────
     只派发 window resize 不够可靠:
       · Chart.js 的自适应靠容器 ResizeObserver,但 Tailwind 重编译是异步的(~114ms),
         派发那一刻容器尺寸还没落定 → 它按旧宽度重算,之后就不再动了;
       · ECharts 根本不自动跟容器,必须显式 resize();
       · 归因等区块是 HTMX 懒加载的,时机更飘。
     所以显式点名重排,并在重编译落定后再补两次。
     explicit:整页横屏(body 被旋转)时 Chart.js 无参 resize() 不生效 —— 画布停在竖屏宽度,
     要显式给容器尺寸(offsetWidth/Height 是布局坐标,不受旋转影响)。普通情况走 Chart.js 自己的量法。 */
  function relayoutOnce(explicit) {
    window.dispatchEvent(new Event('resize'));
    try {
      if (window.Chart && Chart.getChart) {
        document.querySelectorAll('canvas').forEach(function (cv) {
          var ch = Chart.getChart(cv); if (!ch) return;
          var box = cv.parentElement;
          if (explicit && box && box.offsetWidth > 0) ch.resize(box.offsetWidth, box.offsetHeight);
          else ch.resize();
        });
      }
      if (window.echarts) {
        document.querySelectorAll('[_echarts_instance_]').forEach(function (el) {
          var ec = window.echarts.getInstanceByDom(el);
          if (ec && !ec.isDisposed()) ec.resize();
        });
      }
    } catch (e) { console.warn('[viewport] 图表重排异常', e); }
  }
  /* ECharts 要在尺寸变化的**当帧**就缩:它不像 Chart.js 有 ResizeObserver,画布停在旧宽度会撑出横向溢出,
     手机浏览器随即把布局视口放宽去装它(实测折叠后 innerWidth 停在 898)—— 之后再量,容器已经被撑宽、量到的还是宽的,
     vpNarrow 也跟着判成宽屏。所以 resize 事件里同步缩一次,防抖之后再按完整节奏重排。 */
  function fitECharts() {
    if (!window.echarts) return;
    try {
      document.querySelectorAll('[_echarts_instance_]').forEach(function (el) {
        var ec = window.echarts.getInstanceByDom(el);
        if (ec && !ec.isDisposed()) ec.resize();
      });
    } catch (e) { console.warn('[viewport] ECharts 当帧缩放异常', e); }
  }
  window.vpRelayoutCharts = function (opts) {
    var explicit = !!(opts && opts.explicit);
    var run = function () { relayoutOnce(explicit); };
    requestAnimationFrame(function () { requestAnimationFrame(run); });
    setTimeout(run, 180);
    setTimeout(run, 420);
  };

  /* ── 手机横屏由 landscape.js 管:它切换时打个时间戳,这里看到就让路 ── */
  function rotated() {          /* body 被旋转:整页横屏,或触屏横屏的冻结旋转 */
    return root.classList.contains('ls-wide') || !!(window.matchMedia && window.matchMedia(FREEZE_Q).matches);
  }
  function landscapeOwns() {
    return rotated() || Date.now() - (window.__lsSwitchAt || 0) < 900;
  }

  /* ── 正在看的那一节 ────────────────────────────────────────────────
     按像素还原必错:窄屏同一段内容的位置完全不同。所以记「哪个带 id 的区块」:
       · 优先找**跨过视口顶边、或刚在顶边下方 120px 内开头**的区块,取开头最靠下的那个(最内层、最贴近正在读的位置);
       · 它的开头在顶边下方 → 还原时保持同样的距离;
       · 它跨过顶边(长区块读到一半)→ 记读到了它的几分之几,还原时按新高度折算 ——
         只记开头的话,区块变窄变高以后,正在读的那段照样会被挤走。
     找区块用布局坐标(累加 offsetTop),和 landscape.js 同一个理由:body 被旋转时 rect 是屏幕坐标。 */
  var anchor = null;          /* { id, delta, frac } */
  var frozen = false;         /* 尺寸变化到还原结束之间不记(浏览器因高度变化钳制 scrollTop 也会发 scroll) */
  function layoutTop(el) {
    var y = 0;
    while (el && el !== document.body) { y += el.offsetTop || 0; el = el.offsetParent; }
    return y;
  }
  /* 冻结旋转时滚动容器从 html 换成 body(两套 scrollTop),读写都要认对容器 —— 与 landscape.js 同一套判断 */
  function bodyIsScroller() { return !!document.body && getComputedStyle(document.body).position === 'fixed'; }
  function scrollTop() { return bodyIsScroller() ? document.body.scrollTop : (window.scrollY || root.scrollTop || 0); }
  function setScrollTop(y) { if (bodyIsScroller()) document.body.scrollTop = y; else window.scrollTo(0, y); }
  function findAnchor() {
    var top = scrollTop();
    if (top < 40) return null;                           /* 在页顶:不用还原 */
    var nodes = document.querySelectorAll('main [id]');
    var best = null, bestDelta = -1e9;
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      if (!el.id || el.offsetHeight < 40) continue;      /* 跳过小锚点(输入框、图表画布这类) */
      var d = layoutTop(el) - top;
      if (d > 120 || d + el.offsetHeight <= 0) continue; /* 整个在顶边上方,或离顶边太远 */
      if (d > bestDelta) { bestDelta = d; best = el; }
    }
    if (!best) return null;
    return { id: best.id, delta: bestDelta, frac: bestDelta < 0 ? -bestDelta / best.offsetHeight : 0 };
  }
  window.vpAnchor = function () { return anchor; };      /* 只读 · e2e 诊断用 */
  var recPending = false;
  function record() {
    if (frozen || recPending) return;
    recPending = true;
    requestAnimationFrame(function () {
      recPending = false;
      if (!frozen && Date.now() - (window.__lsSwitchAt || 0) >= 900) anchor = findAnchor();
    });
  }
  /* capture:body 当滚动容器时 scroll 不冒泡到 window */
  document.addEventListener('scroll', record, { passive: true, capture: true });
  function restore(a) {
    var run = function () {
      var el = document.getElementById(a.id);
      if (!el) return;
      var y = a.delta >= 0 ? layoutTop(el) - a.delta : layoutTop(el) + a.frac * el.offsetHeight;
      setScrollTop(Math.max(0, Math.round(y)));
    };
    /* 重复几次:Tailwind 重编译(~114ms)和图表重画都会再改一次高度,写同一个 scrollTop 是幂等的 */
    requestAnimationFrame(function () { requestAnimationFrame(run); });
    setTimeout(run, 160);
    setTimeout(run, 400);
  }

  /* ── 越过 768 变宽:展开收起的筛选条 ──
     窄屏首次加载时 layout.html 把 details.filter-fold 收起;宽屏 CSS 藏掉它的 summary(PC 恒展开)。
     窄 → 宽之后它既是收起的又没有按钮 → 整条消失。 */
  function unfoldFilters() {
    document.querySelectorAll('details.filter-fold').forEach(function (d) { if (!d.open) d.open = true; });
  }

  /* ── 主循环 ── */
  var lastW = window.innerWidth, lastH = window.innerHeight;
  var n640 = vpNarrow(640), n768 = vpNarrow(768);
  var timer = null, thaw = null;
  function unfreezeLater() {
    if (thaw) clearTimeout(thaw);
    thaw = setTimeout(function () { thaw = null; frozen = false; record(); }, 480);
  }
  function settle() {
    timer = null;
    var w = window.innerWidth, h = window.innerHeight;
    if (w === lastW && h === lastH) { unfreezeLater(); return; }
    var widthChanged = w !== lastW;
    lastW = w; lastH = h;
    /* 整页横屏(用户点出来的 ls-wide)整段归 landscape.js:断点、图表、滚动都由它管,这里只同步宽窄状态 */
    if (root.classList.contains('ls-wide')) { n640 = vpNarrow(640); n768 = vpNarrow(768); unfreezeLater(); return; }
    var owned = landscapeOwns();

    var p640 = n640, p768 = n768;
    n640 = vpNarrow(640); n768 = vpNarrow(768);
    var flipped = p640 !== n640 || p768 !== n768;
    if (p768 && !n768) unfoldFilters();
    if (flipped) {
      try {
        window.dispatchEvent(new CustomEvent('vpchange', { detail: { n640: n640, n768: n768, was640: p640, was768: p768 } }));
      } catch (e) { console.warn('[viewport] vpchange 处理异常', e); }
    }
    /* 普通手机转屏(竖 ↔ 冻结横屏)宽窄不翻转 → 与 v1.35 完全一样,不重排;
       折叠屏从内屏横着合到外屏横着会翻转 → 要重排,而且 body 已被旋转,得显式给尺寸 */
    if (!owned || flipped) window.vpRelayoutCharts({ explicit: rotated() });

    /* 冻结旋转期间(普通手机转屏)滚动归 landscape.js;但宽窄翻转了(折叠屏内屏横着 ↔ 外屏横着)
       布局完全不同,它的按像素还原必错 —— 这时由这里按章节还原(时序在它之后,写同一个容器,后写者生效)。 */
    var a = anchor;
    if (widthChanged && a && (!owned || flipped)) restore(a);
    unfreezeLater();
  }
  var seenW = window.innerWidth;
  window.addEventListener('resize', function (e) {
    if (!e.isTrusted) return;                            /* 重排自己派发的 resize 不算 */
    if (window.innerWidth !== seenW) { seenW = window.innerWidth; fitECharts(); }
    frozen = true;
    if (thaw) { clearTimeout(thaw); thaw = null; }
    if (timer) clearTimeout(timer);
    timer = setTimeout(settle, 150);
  });
  document.addEventListener('DOMContentLoaded', record);
})();
