/*
 * sort-board.js · v1.34 · 拖拽排序的共用件(持仓顺序 / 账户顺序)
 *
 * 维护者 2026-10-13 验收:「↑ ↓ 按钮的交互过于丑陋 —— 要支持拖拽来互换位置,PC 鼠标可以拖出卡片放到合适的位置,
 * 其他卡片要给它让路;手机端也要。」
 *
 * 结构(模板里写):
 *   <div data-sort-board data-save-url="…">            一块排序板;保存地址收 ids=…&ids=…(按页面顺序)
 *     <div data-sort-list>                              一个可拖的列表(账户按成员分组时有好几个,组间不能互拖)
 *       <div data-sort-id="12" data-sort-name="…" data-sort-value="…" tabindex="0">…</div>
 *     <button data-sort-by="value|name|type|id">        一键排法(作用于每个列表各自;type 读 data-sort-type)
 *   <div data-sort-toast>                               底部提示(已保存 · 撤销)
 *
 * 交互:
 *   · PC:按住卡片拖(鼠标变成抓手),卡片跟着鼠标浮起来,原位置留一个虚线空位,其它卡片滑开让路;松手就保存。
 *   · 手机:长按 0.18 秒拿起(轻点一下 / 快速滑动是正常滚动页面),拖到边缘会自动滚动。
 *   · 键盘:卡片上按 Alt + ↑ / ↓ 移一格(无障碍)。
 *   · 每次放下都立刻保存;提示条上有「撤销」回到上一步。
 * 依赖 /vendor/Sortable.min.js(SortableJS 1.15 · MIT)。
 */
(function () {
  'use strict';

  function csrf() {
    var t = document.querySelector('meta[name="_csrf"]');
    var h = document.querySelector('meta[name="_csrf_header"]');
    var o = {};
    if (t && t.content) o[(h && h.content) || 'X-XSRF-TOKEN'] = t.content;
    return o;
  }

  function init(board) {
    if (!window.Sortable || board.__sortReady) return;
    board.__sortReady = true;
    var lists = Array.prototype.slice.call(board.querySelectorAll('[data-sort-list]'));
    var toast = document.querySelector('[data-sort-toast]');
    var toastText = toast && toast.querySelector('[data-sort-toast-text]');
    var undoBtn = toast && toast.querySelector('[data-sort-undo]');
    var hideTimer = null;
    var lastOrder = null;   // 上一次保存成功之前的顺序(撤销用)

    function order() {
      var ids = [];
      lists.forEach(function (l) {
        l.querySelectorAll('[data-sort-id]').forEach(function (c) { ids.push(c.getAttribute('data-sort-id')); });
      });
      return ids;
    }
    function renumber() {
      lists.forEach(function (l) {
        l.querySelectorAll('[data-sort-id]').forEach(function (c, i) {
          var n = c.querySelector('[data-sort-index]');
          if (n) n.textContent = i + 1;
        });
      });
    }
    function say(msg, canUndo, bad) {
      if (!toast) return;
      toastText.textContent = msg;
      toast.classList.toggle('bad', !!bad);
      if (undoBtn) undoBtn.hidden = !canUndo;
      toast.classList.add('show');
      clearTimeout(hideTimer);
      hideTimer = setTimeout(function () { toast.classList.remove('show'); }, canUndo ? 6000 : 3500);
    }
    function apply(ids) {
      // 按给定顺序把卡片放回各自的列表(只在本列表内重排)
      lists.forEach(function (l) {
        var cards = {};
        l.querySelectorAll('[data-sort-id]').forEach(function (c) { cards[c.getAttribute('data-sort-id')] = c; });
        ids.forEach(function (id) { if (cards[id]) l.appendChild(cards[id]); });
      });
      renumber();
    }
    function save(prev, label) {
      var ids = order();
      if (prev && prev.join(',') === ids.join(',')) return;   // 放回原位 = 没动
      var body = new URLSearchParams();
      ids.forEach(function (id) { body.append('ids', id); });
      board.classList.add('saving');
      fetch(board.getAttribute('data-save-url'), {
        method: 'POST', credentials: 'same-origin',
        headers: Object.assign({ 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'application/json' }, csrf()),
        body: body.toString()
      }).then(function (r) { return r.json().catch(function () { return { ok: false, message: '保存失败(' + r.status + ')' }; }); })
        .then(function (res) {
          board.classList.remove('saving');
          if (res && res.ok) {
            lastOrder = prev;
            board.setAttribute('data-sort-saved', ids.join(','));
            say(label || res.message || '已保存', !!prev, false);
          } else {
            if (prev) apply(prev);
            say((res && res.message) || '没保存上,已放回原样', false, true);
          }
        }).catch(function () {
          board.classList.remove('saving');
          if (prev) apply(prev);
          say('网络断了,没保存上,已放回原样', false, true);
        });
    }

    var before = null;
    lists.forEach(function (list) {
      window.Sortable.create(list, {
        group: { name: 'g' + Math.random(), pull: false, put: false },   // 组间不互拖(账户的成员分组)
        animation: 220,                // 其它卡片滑开让路
        easing: 'cubic-bezier(.2,.8,.2,1)',
        forceFallback: true,           // 统一用「卡片跟着鼠标浮起来」的效果(不用浏览器原生拖影)
        fallbackOnBody: true,
        fallbackTolerance: 4,
        delay: 180, delayOnTouchOnly: true, touchStartThreshold: 6,   // 手机长按才拿起,快速滑动照常滚动
        ghostClass: 'sort-ghost', chosenClass: 'sort-chosen', dragClass: 'sort-drag', fallbackClass: 'sort-drag',
        scroll: true, scrollSensitivity: 90, scrollSpeed: 14, bubbleScroll: true,
        filter: 'a, button, input, select, textarea', preventOnFilter: false,
        onChoose: function () {
          before = order();
          board.classList.add('dragging');
          if (navigator.vibrate) { try { navigator.vibrate(12); } catch (e) { /* 不支持就算了 */ } }
        },
        onUnchoose: function () { board.classList.remove('dragging'); },
        onEnd: function (e) {
          board.classList.remove('dragging');
          renumber();
          if (e.item) { e.item.classList.add('sort-dropped'); setTimeout(function () { e.item.classList.remove('sort-dropped'); }, 700); }
          save(before, '已保存顺序');
        }
      });
    });

    // 键盘:Alt + ↑ / ↓
    board.addEventListener('keydown', function (e) {
      if (!e.altKey || (e.key !== 'ArrowUp' && e.key !== 'ArrowDown')) return;
      var card = e.target.closest && e.target.closest('[data-sort-id]');
      if (!card) return;
      e.preventDefault();
      var prev = order();
      var sib = e.key === 'ArrowUp' ? card.previousElementSibling : card.nextElementSibling;
      if (!sib) return;
      if (e.key === 'ArrowUp') card.parentNode.insertBefore(card, sib); else card.parentNode.insertBefore(sib, card);
      card.focus();
      renumber();
      save(prev, '已保存顺序');
    });

    // 一键排法:每个列表各自排
    board.querySelectorAll('[data-sort-by]').forEach(function (b) {
      b.addEventListener('click', function () {
        var key = b.getAttribute('data-sort-by');
        var prev = order();
        lists.forEach(function (l) {
          var cards = Array.prototype.slice.call(l.querySelectorAll('[data-sort-id]'));
          cards.sort(function (a, c) {
            var ia = Number(a.getAttribute('data-sort-id')), ic = Number(c.getAttribute('data-sort-id'));
            if (key === 'value') return Number(c.getAttribute('data-sort-value')) - Number(a.getAttribute('data-sort-value')) || ia - ic;
            if (key === 'name') return a.getAttribute('data-sort-name').localeCompare(c.getAttribute('data-sort-name'), 'zh-CN') || ia - ic;
            if (key === 'type') return Number(a.getAttribute('data-sort-type')) - Number(c.getAttribute('data-sort-type')) || ia - ic;
            return ia - ic;
          });
          cards.forEach(function (c) { l.appendChild(c); });
        });
        renumber();
        save(prev, '已按「' + b.textContent.trim() + '」排好并保存');
      });
    });

    if (undoBtn) undoBtn.addEventListener('click', function () {
      if (!lastOrder) return;
      var cur = order();
      apply(lastOrder);
      lastOrder = null;
      save(cur, '已撤销');
    });
    board.setAttribute('data-sort-saved', order().join(','));
    renumber();
  }

  function boot() { document.querySelectorAll('[data-sort-board]').forEach(init); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot); else boot();
})();
