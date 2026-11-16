/*
 * v1.19 · 超级 Agent · 对话流客户端
 *
 * 一份脚本同时服务 PC 抽屉与手机整页 —— 它只认 data-ask-* 钩子,不认外面那层容器长什么样。
 *
 * 七个状态(取自 Claude / ChatGPT / Cursor 已经收敛的那套):
 *   排队 → 思考 → 流式 → 完成 → 出错 → 已停止 → 重来
 * 每个都有明确形态。**排队不给假进度条**(没有进度可报,画一个是骗人);
 * **流式必须有光标**(否则模型思考的停顿会被读成「已经结束了」);
 * **已停止要留住半截**并给「继续 / 重来」。
 *
 * 滚动(护栏 v119-ASK-NO-AUTOSCROLL):只有用户本来就在底部时才跟着滚。
 *   无条件 scrollIntoView 会在用户往回翻看上一条回答时把他弹回底部 ——
 *   这是流式界面最招人烦的一件事,而且长回答期间会反复发生。
 *
 * 安全:模型输出是**不可信输入**(提示词注入可以从账户名里来)。一律用
 *   createElement + textContent 建节点,**不拼 HTML 字符串**,转义漏一处的可能性为零。
 */
(function () {
  'use strict';

  var BOTTOM_SLACK = 96;   // 距底不足这么多像素就算「在底部」(规范给的是 100)
  var CITE_G = /\{\{cite:([A-Za-z0-9_]{1,14})\}\}/g;
  var CITE_ONLY = /^\{\{cite:([A-Za-z0-9_]{1,14})\}\}$/;
  var NEXT_G = /\{\{next:([^}\n]{1,40})\}\}/g;
  /* v1.27 · 「提议记住」标记:正文里不显示,流完之后向服务端要一张确认卡(与历史消息同一个片段) */
  var REMEMBER_G = /\{\{remember(?:-done)?:([^}\n]{1,320})\}\}/g;
  var BOLD_G = /\*\*([^*]{1,80})\*\*/g;
  /* 「- xxx」/「1. xxx」列表项 —— 服务端 AskCitationRenderer 有等价的一份,两边必须同形态 */
  var LIST_ITEM = /^(?:[-*·]|\d{1,2}[.)])\s+(.*)$/;
  var CHART_MULTI = /^\{\{chart:([\s\S]+)\}\}$/;
  var ARTIFACT_OPEN = '```artifact', FENCE = '```';

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function svg(pathD, extra) {
    var s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('viewBox', '0 0 24 24');
    s.setAttribute('aria-hidden', 'true');
    pathD.forEach(function (d) {
      var p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      p.setAttribute('d', d);
      s.appendChild(p);
    });
    if (extra) extra(s);
    return s;
  }

  /* ── 渲染 ──
     服务端有一份等价实现(AskCitationRenderer),历史消息走那边。
     这里这份只服务「流式进行中」的那几秒 —— 那时候还没落库,服务端渲不了。
     两份必须同形态,否则流完刷新一下页面样子会变。 */
  /* 只有**金额**受隐私模式管;百分比/月数/条数不敏感。与服务端 looksMoney 同解 */
  function looksMoney(v) { return v != null && /[¥$€£]/.test(String(v)); }

  function citeCard(c) {
    var a = el('a', 'ask-cite');
    a.href = c.href || '#';
    var top = el('span', 'ask-cite-top');
    top.appendChild(el('span', 'ask-cite-k', c.label));
    var v = el('span', 'ask-cite-v', c.value);
    if (looksMoney(c.value)) v.setAttribute('data-priv', '');
    top.appendChild(v);
    a.appendChild(top);
    var meta = (c.period || '') + (c.inProgress ? ((c.period ? ' · ' : '') + '未关账') : '');
    if (meta) {
      var m = el('span', 'ask-cite-meta');
      m.appendChild(el('span', 'ask-cite-per', meta));
      a.appendChild(m);
    }
    return a;
  }

  function citeChip(c) {
    var a = el('a', 'ask-chip', c.value);
    if (looksMoney(c.value)) a.setAttribute('data-priv', '');
    a.href = c.href || '#';
    if (c.inProgress) a.appendChild(el('i', null, '未关账'));
    return a;
  }

  /**
   * 按正则把一段文本切成「命中段」与「间隔段」,分别交给两个回调。
   * 抽出来是因为引用标记、追问标记、**粗体** 是同一种活,写三遍必然漂移一处。
   */
  function split(text, re, onMatch, onPlain) {
    var last = 0;
    for (var m of text.matchAll(re)) {
      if (m.index > last) onPlain(text.slice(last, m.index));
      onMatch(m);
      last = m.index + m[0].length;
    }
    if (last < text.length) onPlain(text.slice(last));
  }

  /** 模型输出里常见的 markdown 就 **粗体** 这一样,不为它引一个解析器 */
  function emphasize(text, into) {
    split(text, BOLD_G,
      function (m) { into.appendChild(el('strong', null, m[1])); },
      function (s) { into.appendChild(document.createTextNode(s)); });
  }

  function inlineNodes(line, cites, into) {
    split(line, CITE_G,
      function (m) {
        var c = cites[m[1]];
        if (c) into.appendChild(citeChip(c));
      },
      function (s) { emphasize(s, into); });
  }

  /** 抽出追问建议;正文里不再显示它们 */
  function pullNexts(raw) {
    var out = [], m;
    for (m of raw.matchAll(NEXT_G)) {
      var q = m[1].trim();
      if (q && out.indexOf(q) < 0 && out.length < 3) out.push(q);
    }
    return out;
  }

  /** 从格式化数值里取数字(与服务端 AskCitationRenderer.numeric 同解) */
  function numeric(text) {
    if (text == null) return null;
    var t = String(text).replace(/[^0-9.\-]/g, '');
    if (!t || t === '-' || t === '.') return null;
    var v = parseFloat(t);
    return isNaN(v) ? null : v;
  }

  /** 图表容器 —— 与服务端吐的**同一个契约**,交给 ask-charts.js 画 */
  function chartNode(json, cites) {
    var spec;
    try { spec = JSON.parse(json); } catch (e) { return null; }
    var pts = [];
    (spec.items || []).forEach(function (it) {
      var c = cites[it.cite];
      if (!c) return;                                  // 引用不到就丢掉这个点
      var v = numeric(c.value);
      if (v == null) return;
      var p = { label: it.label || c.label, value: v, text: c.value };
      if (it.kind) p.kind = it.kind;
      pts.push(p);
    });
    if (!pts.length) return null;                      // 一个点都没有 → 不画
    var host = el('div', 'ask-chart');
    if (pts.some(function (x) { return looksMoney(x.text); })) host.setAttribute('data-priv-chart', '');
    host.dataset.askChart = JSON.stringify(
      { type: spec.type || 'pie', title: spec.title || '', points: pts });
    return host;
  }

  /** 自由 HTML —— sandbox iframe;数字先换成引用值,模型只决定形式 */
  function artifactNode(html, cites) {
    var resolved = html.replace(CITE_G, function (m, k) {
      var c = cites[k];
      return c ? c.value : '';
    });
    // 只建容器,iframe 交给 ask-charts.js 组装 —— 与服务端渲染共用那一份脚手架
    var fig = el('figure', 'ask-artifact');
    fig.dataset.askArtifact = resolved;
    return fig;
  }

  function render(raw, cites) {
    var frag = document.createDocumentFragment();
    var ul = null;
    var closeList = function () { ul = null; };
    var lines = raw.replace(NEXT_G, '').replace(REMEMBER_G, '').split('\n');

    for (var i = 0; i < lines.length; i++) {
      var t = lines[i].trim();

      // 自由 HTML:**围栏闭合之前不渲染**。半截 HTML 塞进 iframe 会反复重排闪烁,
      // 而且多半是坏的 —— 规范里对代码块也是这条(闭合前当纯文本)。
      if (t.indexOf(ARTIFACT_OPEN) === 0) {
        var close = -1;
        for (var j = i + 1; j < lines.length; j++) {
          if (lines[j].trim() === FENCE) { close = j; break; }
        }
        closeList();
        if (close > 0) {
          frag.appendChild(artifactNode(lines.slice(i + 1, close).join('\n'), cites));
          i = close;
        } else {
          frag.appendChild(el('div', 'ask-wait', '正在画图'));
          i = lines.length;                            // 后面还没到,先停在这儿
        }
        continue;
      }

      if (!t) continue;

      // 图表标记可能跨多行(模型习惯把 JSON 排版开)—— 往后收到 }} 为止
      if (t.indexOf('{{chart:') === 0) {
        var buf = lines[i], e = i;
        while (buf.trim().slice(-2) !== '}}' && e + 1 < lines.length && e - i < 40) {
          e++; buf += '\n' + lines[e];
        }
        var ch = buf.trim().match(CHART_MULTI);
        if (ch) {
          closeList();
          var node = chartNode(ch[1], cites);
          if (node) frag.appendChild(node);
          i = e;
          continue;
        }
        // 还没收齐(正在流)→ 占位,别把半截 JSON 当正文显示出来
        closeList();
        frag.appendChild(el('div', 'ask-wait', '正在画图'));
        i = lines.length;
        continue;
      }

      var only = t.match(CITE_ONLY);
      if (only) {
        closeList();
        if (cites[only[1]]) frag.appendChild(citeCard(cites[only[1]]));
        continue;
      }

      var li = t.match(LIST_ITEM);
      if (li) {
        if (!ul) { ul = el('ul'); frag.appendChild(ul); }
        var item = el('li');
        inlineNodes(li[1], cites, item);
        ul.appendChild(item);
        continue;
      }

      closeList();
      var p = el('p');
      inlineNodes(t, cites, p);
      frag.appendChild(p);
    }
    return frag;
  }

  /** 复制用的纯文本:引用换成数值,追问去掉 */
  function plainText(raw, cites) {
    var out = raw.replace(NEXT_G, '').replace(REMEMBER_G, '').replace(CITE_G, function (m, k) {
      var c = cites[k];
      return c ? (c.label + ' ' + c.value + (c.inProgress ? '(未关账)' : '')) : '';
    });
    return out.replace(/\n{3,}/g, '\n\n').trim();
  }

  // ──────────────────────── 单个对话流 ────────────────────────

  function init(root) {
    var form = root.querySelector('[data-ask-form]');
    if (!form || form.dataset.askBound) return;
    form.dataset.askBound = '1';

    var input = root.querySelector('[data-ask-input]');
    var sendBtn = root.querySelector('[data-ask-send]');
    var stopBtn = root.querySelector('[data-ask-stop]');
    var msgs = root.querySelector('[data-ask-msgs]');
    var live = root.querySelector('[data-ask-live]');
    var busy = false;
    var current = null;          // 正在跑的那一轮

    if (input) {
      input.addEventListener('input', autoGrow);
      // 回车发送,Shift+回车换行;手机上不拦 —— 虚拟键盘的回车就该是换行
      input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey && !isTouch()) {
          e.preventDefault();
          form.requestSubmit();
        }
      });
    }
    function autoGrow() {
      if (!input) return;
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 112) + 'px';
    }

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var q = (input && input.value || '').trim();
      if (q) ask(q);
    });

    if (stopBtn) stopBtn.addEventListener('click', function () {
      if (current) current.stop();
    });

    // 预置问题、追问 chip、复制、重来 —— 都可能是流式期间新插进来的,所以用事件委托
    root.addEventListener('click', function (e) {
      var t = e.target.closest('[data-ask-preset]');
      if (t) { ask(t.dataset.askPreset); return; }
      var c = e.target.closest('[data-ask-copy]');
      if (c) { doCopy(c); return; }
      var r = e.target.closest('[data-ask-regen]');
      if (r) { ask(null, 'regen'); return; }
      var g = e.target.closest('[data-ask-continue]');
      if (g) { ask(null, 'continue'); return; }
      var n = e.target.closest('[data-ask-new]');
      if (n) { newConversation(); }
    });

    function doCopy(btn) {
      var text = btn.dataset.text || '';
      var done = function (ok) {
        var span = btn.lastChild;
        var old = span.textContent;
        span.textContent = ok ? '已复制' : '请手动选中';
        btn.classList.toggle('ok', ok);
        setTimeout(function () { span.textContent = old; btn.classList.remove('ok'); }, 1600);
      };
      // 自托管常见:没挂 TLS,http 下 clipboard API 直接不可用 → 老实说「请手动选中」
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { done(true); },
                                                 function () { done(false); });
      } else { done(false); }
    }

    function isTouch() {
      return window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
    }

    /* ── 用户有没有在「看」这一轮 ──
       思考过程跑完会自动折叠;但如果用户中途在读它,折叠就是把他正在看的东西抢走。
       判据是**主动动作**,不是 scroll 事件 —— 我们自己的 keepBottom 也会触发 scroll,
       用它当判据的话每一轮都会被判成「用户在看」,自动折叠就永远不生效。
       收的这几种:滚轮 / 触摸滑动 / 点击 / 键盘 / 选中文本 / 鼠标停在思考区上。 */
    var engaged = false;
    function markEngaged() { engaged = true; }

    ['wheel', 'touchmove', 'pointerdown', 'keydown'].forEach(function (ev) {
      root.addEventListener(ev, markEngaged, { passive: true });
    });
    document.addEventListener('selectionchange', function () {
      var sel = document.getSelection();
      if (sel && !sel.isCollapsed && sel.anchorNode && root.contains(sel.anchorNode)) markEngaged();
    });
    function atBottom() {
      if (!msgs) return true;
      return msgs.scrollHeight - msgs.scrollTop - msgs.clientHeight < BOTTOM_SLACK;
    }
    function keepBottom(was) { if (was && msgs) msgs.scrollTop = msgs.scrollHeight; }

    function setBusy(on) {
      busy = on;
      if (sendBtn) { sendBtn.hidden = on; sendBtn.disabled = on; }
      if (stopBtn) stopBtn.hidden = !on;
    }

    function newConversation() {
      post('/ask/new', {}).then(function (r) {
        if (root.closest('[data-ask-drawer]')) {
          openPanel(r.id);
        } else {
          var url = new URL(window.location.href);
          url.searchParams.set('conv', r.id);
          window.location.href = url.toString();
        }
      });
    }

    function ensureConversation() {
      var id = root.dataset.conv;
      if (id) return Promise.resolve(id);
      return post('/ask/new', {}).then(function (r) {
        root.dataset.conv = String(r.id);
        return String(r.id);
      });
    }

    /** mode: 'new'(默认)/ 'regen' / 'continue' */
    function ask(q, mode) {
      if (busy) return;
      mode = mode || 'new';
      setBusy(true);
      if (mode === 'new' && input) { input.value = ''; autoGrow(); }

      engaged = false;              // 新一轮重新判断:上一轮读过不代表这一轮也在读
      var empty = root.querySelector('.ask-empty');
      if (empty) empty.remove();

      // 用户这句先上屏 —— 等服务端回声会让人觉得没发出去
      if (mode === 'new') {
        var mine = el('div', 'ask-me');
        mine.appendChild(el('div', null, q));
        live.parentNode.insertBefore(mine, live);
      }

      var turn = el('div', 'ask-turn');
      var acts = buildActs();
      var wait = el('div', 'ask-wait', mode === 'continue' ? '接着上面继续…' : '正在看你的账');
      var bodyBox = el('div', 'ask-body');
      turn.appendChild(acts.host);
      turn.appendChild(wait);
      turn.appendChild(bodyBox);
      live.parentNode.insertBefore(turn, live);
      keepBottom(true);

      var raw = '', cites = {}, es = null, stopping = false;

      current = {
        stop: function () {
          if (stopping) return;
          stopping = true;
          if (stopBtn) stopBtn.disabled = true;
          // 先告诉服务端(它才能把半截落库),再关本地连接
          post('/ask/' + root.dataset.conv + '/stop', {}).catch(function () {});
        }
      };

      ensureConversation().then(function (convId) {
        var url = '/ask/' + convId + '/stream?mode=' + mode
                + (mode === 'new' ? '&q=' + encodeURIComponent(q) : '');
        es = new EventSource(url);

        es.addEventListener('tool', function (ev) {
          var d = JSON.parse(ev.data), was = atBottom();
          acts.upsert(d);
          keepBottom(was);
        });

        es.addEventListener('cite', function (ev) {
          var d = JSON.parse(ev.data);
          cites[d.key] = d;
        });

        // 那段文字是调工具前的旁白 —— 它是**思考**,不是答案:从正文挪进思考过程。
        // 触发时机是「确认这一轮要调工具的那一刻」,所以看起来是话说完就转成了思考记录。
        es.addEventListener('rollback', function (ev) {
          var t = JSON.parse(ev.data).t, was = atBottom();
          if (raw.endsWith(t)) raw = raw.slice(0, raw.length - t.length);
          paint();
          acts.say(t.trim());
          keepBottom(was);
        });

        es.addEventListener('delta', function (ev) {
          var was = atBottom();
          if (wait.parentNode) wait.remove();
          raw += JSON.parse(ev.data).t;
          paint();
          keepBottom(was);
        });

        es.addEventListener('done', function () { finish('done'); });
        es.addEventListener('stopped', function () { finish('stopped'); });

        es.addEventListener('failed', function (ev) {
          var d = JSON.parse(ev.data);
          finish('failed', d.message);
        });

        es.onerror = function () {
          if (busy) finish(raw ? 'stopped' : 'failed', raw ? null : '连接断了。重试一下。');
        };

        function paint() {
          bodyBox.replaceChildren(render(raw, cites));
          if (busy) bodyBox.appendChild(el('span', 'ask-caret-live'));
          if (window.askRenderVisuals) window.askRenderVisuals(bodyBox);
        }

        function finish(state, msg) {
          try { if (es) es.close(); } catch (e) { /* 已经关了 */ }
          if (wait.parentNode) wait.remove();
          acts.settle();
          setBusy(false);
          current = null;
          bodyBox.replaceChildren(render(raw, cites));   // 去掉光标
          if (window.askRenderVisuals) window.askRenderVisuals(bodyBox);

          if (state === 'failed' && msg) {
            turn.appendChild(el('div', 'ask-note').appendChild(el('span', null, msg)).parentNode);
          }
          if (state === 'stopped') {
            var note = el('div', 'ask-note');
            note.appendChild(el('span', null, raw ? '你叫停了 · 上面是已经说到的部分' : '已停止'));
            turn.appendChild(note);
          }

          // 追问 chip
          var nexts = pullNexts(raw);
          if (nexts.length) {
            var box = el('div', 'ask-nexts');
            nexts.forEach(function (n) {
              var b = el('button', 'ask-next', n);
              b.type = 'button';
              b.dataset.askPreset = n;
              box.appendChild(b);
            });
            turn.appendChild(box);
          }

          // v1.27 · 「提议记住」:服务端渲染确认卡(模板 key、账户名都在那边核对过)
          var rm = raw ? /\{\{remember:([^}\n]{1,320})\}\}/.exec(raw) : null;
          if (rm && state === 'done') {
            var slot = el('div', null);
            turn.appendChild(slot);
            var convEl = turn.closest('[data-conv]');
            fetch('/ask/remember/card?m=' + encodeURIComponent(rm[1])
                  + '&conv=' + encodeURIComponent(convEl ? convEl.getAttribute('data-conv') : ''), { credentials: 'same-origin' })
              .then(function (r) { return r.ok ? r.text() : ''; })
              .then(function (html) { if (html && html.trim()) slot.outerHTML = html; else slot.remove(); })
              .catch(function () { slot.remove(); });
          }

          // 逐条操作
          if (raw) {
            var row = el('div', 'ask-tools-row');
            row.appendChild(mini('复制', ['M9 9h11v11H9z', 'M5 15V5a2 2 0 0 1 2-2h10'],
              function (b) { b.dataset.askCopy = ''; b.dataset.text = plainText(raw, cites); }));
            if (state === 'stopped') {
              row.appendChild(mini('继续', ['M8 5l10 7-10 7z'],
                function (b) { b.dataset.askContinue = ''; }));
            }
            row.appendChild(mini('重来', ['M21 12a9 9 0 1 1-3-6.7', 'M21 3v6h-6'],
              function (b) { b.dataset.askRegen = ''; }));
            turn.appendChild(row);
            // v1.28 · 这一问发出去的内容:>_ 排在操作行最前(与历史消息同一位置)
            var convHost = turn.closest('[data-conv]');
            var cid = convHost ? convHost.getAttribute('data-conv') : '';
            if (cid) {
              row.setAttribute('data-peek-host', '');
              fetch('/ai/prompt/ask-latest?conv=' + encodeURIComponent(cid), { credentials: 'same-origin' })
                .then(function (r) { return r.ok ? r.json() : {}; })
                .then(function (j) {
                  if (j && j.peek && window.peekButton) row.insertBefore(window.peekButton(j.peek), row.firstChild);
                  if (j && j.sourceLabel) {
                    var source = document.createElement('p'); source.className = 'ask-source text-xs text-ink-soft break-words mb-2';
                    source.textContent = j.sourceLabel; row.parentNode.insertBefore(source, row);
                  }
                })
                .catch(function () { });
            }
          }

          if (input) input.focus();
        }
      }).catch(function () {
        wait.textContent = '没开成对话,刷新页面再试。';
        setBusy(false);
        current = null;
      });
    }

    function mini(label, paths, tag) {
      var b = el('button', 'ask-mini');
      b.type = 'button';
      b.appendChild(svg(paths));
      b.appendChild(document.createTextNode(label));
      tag(b);
      return b;
    }

    /**
     * 思考过程。与历史消息里那份**同一套 DOM**(护栏靠 class 名对齐),
     * 区别只在:进行中默认展开、跑完按 engaged 决定收不收。
     */
    function buildActs() {
      var host = el('details', 'ask-acts live');
      host.open = true;                         // 进行中默认展开 —— 用户想看的正是这个
      var sum = el('summary');
      sum.appendChild(el('span', 'ask-caret', '▸'));
      var label = el('span', null, '正在想…');
      sum.appendChild(label);
      var body = el('div', 'ask-acts-body');
      host.appendChild(sum);
      host.appendChild(body);
      host.hidden = true;                       // 还没有任何一步时不占位
      // 手动展开/收起本身就是「在看」
      host.addEventListener('toggle', markEngaged);
      var steps = 0;

      function show() { host.hidden = false; }

      return {
        host: host,

        /** 模型调工具前说的那句话 —— 它是思考,不是答案 */
        say: function (t) {
          show();
          body.appendChild(el('div', 'ask-act-say', t));
          steps++;
          label.textContent = '思考过程 · ' + steps + ' 步';
        },

        upsert: function (d) {
          show();
          var row = null;
          for (var i = 0; i < body.children.length; i++) {
            if (body.children[i].dataset && body.children[i].dataset.k === d.tool) {
              row = body.children[i]; break;
            }
          }
          if (!row) {
            row = el('div', 'ask-act');
            row.dataset.k = d.tool;
            var head = el('div', 'ask-act-head');
            head.appendChild(el('span', 'ask-dot'));
            head.appendChild(el('span', 'ask-act-name', d.label));
            head.appendChild(el('span', 'ask-act-ms', ''));
            row.appendChild(head);
            body.appendChild(row);
            steps++;
          }
          var head = row.firstChild;
          var dot = head.firstChild, ms = head.lastChild;

          if (d.phase === 'start') {
            dot.classList.add('run');
            ms.textContent = '查询中';
            if (d.args) {
              var a = row.querySelector('.ask-act-args');
              if (!a) { a = el('div', 'ask-act-args'); row.appendChild(a); }
              a.textContent = d.args;
            }
          } else {
            dot.classList.remove('run');
            if (!d.ok) dot.classList.add('bad');
            ms.textContent = d.ok ? (d.ms + ' ms') : '没查到';
            if (d.summary) {
              var sm = row.querySelector('.ask-act-sum');
              if (!sm) { sm = el('div', 'ask-act-sum'); row.appendChild(sm); }
              sm.textContent = d.summary;
              // 摘要里可能有「合计 ¥…」—— 含货币符号才挂,免得把「11 个维度」也糊掉
              if (looksMoney(d.summary)) sm.setAttribute('data-priv', '');
              else sm.removeAttribute('data-priv');
            }
          }
          label.textContent = '思考过程 · ' + steps + ' 步';
        },

        /**
         * 这一轮结束。**用户没参与才收起** —— 他在读的时候收起,
         * 等于把正在看的东西从眼前拿走,比不收起烦人得多。
         */
        settle: function () {
          host.classList.remove('live');
          if (host.hidden) return;
          label.textContent = '思考过程 · ' + steps + ' 步';
          if (!engaged) {
            host.open = false;
            host.classList.add('folded');       // 闪一下,让人知道它收起来了、还能点开
            setTimeout(function () { host.classList.remove('folded'); }, 1200);
          }
        }
      };
    }
  }

  function post(url, data) {
    var h = { 'Content-Type': 'application/x-www-form-urlencoded' };
    var tok = document.querySelector('meta[name="_csrf"]');
    var hdr = document.querySelector('meta[name="_csrf_header"]');
    if (tok && hdr && tok.content) h[hdr.content] = tok.content;
    return fetch(url, {
      method: 'POST', headers: h, credentials: 'same-origin',
      body: new URLSearchParams(data).toString()
    }).then(function (r) { return r.json(); });
  }

  // ──────────────────────── PC 抽屉 ────────────────────────

  /* 片段加载走项目已有的 htmx —— 全站局部替换都用它,这里没理由自己再写一套 */
  function openPanel(convId) {
    var drawer = document.querySelector('[data-ask-drawer]');
    if (!drawer || !window.htmx) return;
    var body = drawer.querySelector('[data-ask-drawer-body]');
    htmx.ajax('GET', '/ask/panel' + (convId ? ('?conv=' + convId) : ''),
              { target: body, swap: 'innerHTML' })
      .then(function () {
        drawer.classList.add('open');
        document.documentElement.classList.add('ask-open');
        drawer.querySelectorAll('.ask-stream').forEach(init);
        drawer.querySelectorAll('[data-ask-close]').forEach(function (b) {
          b.addEventListener('click', closePanel);
        });
        var inp = drawer.querySelector('[data-ask-input]');
        if (inp) inp.focus();
      });
  }

  function closePanel() {
    var drawer = document.querySelector('[data-ask-drawer]');
    if (!drawer) return;
    drawer.classList.remove('open');
    document.documentElement.classList.remove('ask-open');
  }

  function boot() {
    document.querySelectorAll('.ask-stream').forEach(init);
    document.querySelectorAll('[data-ask-open]').forEach(function (b) {
      if (b.dataset.askBound) return;
      b.dataset.askBound = '1';
      b.addEventListener('click', function (e) { e.preventDefault(); openPanel(null); });
    });
  }

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') closePanel();
  });

  /* v1.27 · 确认卡上的动作:「记住」「切到某个模板」异步提交,整张卡换成「已记住」那一行;「不用」直接收起。
     只有这一下点击会存东西 —— AI 自己存不了(PRD FR-854)。 */
  document.addEventListener('submit', function (e) {
    var f = e.target.closest && e.target.closest('[data-remember-form]');
    if (!f) return;
    e.preventDefault();
    var card = f.closest('[data-remember-card]');
    var h = { 'Content-Type': 'application/x-www-form-urlencoded' };
    var tok = document.querySelector('meta[name="_csrf"]');
    var hdr = document.querySelector('meta[name="_csrf_header"]');
    if (tok && hdr && tok.content) h[hdr.content] = tok.content;
    fetch(f.getAttribute('action'), {
      method: 'POST', headers: h, credentials: 'same-origin',
      body: new URLSearchParams(new FormData(f)).toString()
    }).then(function (r) { return r.text(); })
      .then(function (html) { if (card && html) card.outerHTML = html; });
  });
  document.addEventListener('click', function (e) {
    var b = e.target.closest && e.target.closest('[data-remember-dismiss]');
    if (!b) return;
    var card = b.closest('[data-remember-card]');
    if (!card) return;
    // 「不用」也记下来:回看这段对话时不再出这张卡
    var mid = card.getAttribute('data-mid');
    if (mid) {
      var h = { 'Content-Type': 'application/x-www-form-urlencoded' };
      var tok = document.querySelector('meta[name="_csrf"]');
      var hdr = document.querySelector('meta[name="_csrf_header"]');
      if (tok && hdr && tok.content) h[hdr.content] = tok.content;
      fetch('/ask/remember/dismiss', { method: 'POST', headers: h, credentials: 'same-origin',
        body: new URLSearchParams({ mid: mid }).toString() }).catch(function () {});
    }
    card.remove();
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
  document.addEventListener('htmx:afterSwap', boot);
})();
