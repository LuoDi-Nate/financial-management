/**
 * flow · v1.31 · 手机上的设置页:「管理 › 当前页 · 切换」+ 周期表一期一卡
 *
 * 维护者 2026-10-10:「移动端 管理-周期管理 进去以后 上面有一大屏幕 是目录??」「表格/换行/tag 都惨不忍睹」。
 * 原来 19 项设置菜单在手机上竖排压在页面顶上,标题在第 843px;周期表 5 列挤进 390,区间折 4 行、
 * 「CLOSED · 已结束」折 3 行、「必填理由 + 重新打开」被挤出卡片。
 *
 * 全程在**真手机**里跑(390×844 · 触屏,见 ui.phone):
 *  1 · 从仪表盘的汉堡菜单点「管理」→ 总览卡片点「周期」(用户的真实路径,不 goto 功能页)
 *  2 · 首屏看得到标题;顶上只有一行切换条;页面不横向滚动
 *  3 · 切换抽屉里的条目 ⊇ 管理总览页的全部卡片;从抽屉跳到别的设置页再跳回来
 *  4 · 周期表是一期一卡:表头不显示、区间 / 关闭时间 / 状态标签都一行、理由框 + 按钮在卡片里
 *  5 · 在卡片里填理由、点「重新打开」→ 页面提示 + 库里这一期变 OPEN;再点「关闭账期」→ 库里回到 CLOSED
 * cleanup:万一中途断了,这一期还开着就从 PC 页面把它关回去。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const REASON = 'e2e · v1.31 手机卡片重开';
const state = {};

const status = (pid) => db.one(`SELECT status FROM period WHERE id=${pid} AND family_id=${fx.FAM}`);

module.exports = {
  name: '47-mobile-admin-nav',
  title: 'v1.31 · 手机上管理子页首屏即内容 · 切换抽屉 ⊇ 总览 · 周期表一期一卡 · 卡片里重开 / 关账真落库',

  async run(ui, report) {
    ui.flow = this.name;
    // 回调里的 ui 是**手机那一个**(另开的触屏 context),故意遮住外层 PC 的 ui —— 这一段每个动作都该在手机上发生
    await ui.phone(async (ui) => {
      // ── 1 · 用户路径 ────────────────────────────────────────────────
      report.section('1 · 手机:仪表盘 → 汉堡菜单「管理」→ 总览卡片「周期」');
      await ui.goto('/dashboard');
      await ui.click('header details > summary:visible', '点右上角菜单');
      await ui.click('header details[open] a[href="/admin"]:visible', '菜单里点「管理」');
      await ui.page.waitForLoadState('networkidle').catch(() => {});
      const hub = await ui.page.evaluate(() =>
        [...new Set([...document.querySelectorAll('main a.group[href]')].map(a => a.getAttribute('href')))]);
      await ui.assert(hub.length >= 20, '管理总览页的设置卡片都在', `${hub.length} 张`);
      await ui.click('main a.group[href="/admin/periods"]', '总览里点「周期管理」');
      await ui.page.waitForLoadState('networkidle').catch(() => {});
      await ui.rendered('周期管理');

      // ── 2 · 首屏 ────────────────────────────────────────────────────
      report.section('2 · 首屏就是这页:标题在第一屏 · 顶上只占一行 · 不横向滚动');
      const top = await ui.page.evaluate(() => {
        const h1 = document.querySelector('main h1');
        const bar = document.querySelector('.adm-bar');
        const nav = document.querySelector('.adm-nav');
        return {
          h1: h1 ? Math.round(h1.getBoundingClientRect().bottom + scrollY) : null,
          bar: bar ? { h: Math.round(bar.getBoundingClientRect().height), text: bar.textContent.replace(/\s+/g, ' ').trim() } : null,
          navShown: !!(nav && nav.offsetParent),
          docW: document.documentElement.scrollWidth, vw: innerWidth,
        };
      });
      await ui.assert(top.h1 !== null && top.h1 < 844, '标题「所有周期」在第一屏内', `标题底边 ${top.h1}px(改前 843px 才开始)`);
      await ui.assert(top.bar && top.bar.h <= 56 && /管理.*周期.*切换/.test(top.bar.text), '顶上是一行「管理 › 周期 · 切换」', JSON.stringify(top.bar));
      await ui.assert(!top.navShown, '19 项竖排菜单在手机上不再铺开');
      await ui.assert(top.docW <= top.vw, '页面不横向滚动', `${top.docW} > ${top.vw}`);

      // ── 3 · 切换抽屉 ────────────────────────────────────────────────
      report.section('3 · 点「切换」:抽屉里的设置项 ⊇ 管理总览页 · 能跳过去再跳回来');
      await ui.click('[data-adm-open]', '点「切换」');
      await ui.page.waitForTimeout(400);
      const sheet = await ui.page.evaluate(() => {
        const s = document.getElementById('adm-sheet');
        const r = s.getBoundingClientRect();
        return { open: s.classList.contains('open'), inView: r.top < innerHeight && r.bottom <= innerHeight + 1,
                 links: [...s.querySelectorAll('a[href]')].map(a => a.getAttribute('href')),
                 on: (s.querySelector('a.on') || {}).textContent || '' };
      });
      await ui.assert(sheet.open && sheet.inView, '抽屉从底部弹出、完整在屏内');
      const missing = hub.filter(h => !sheet.links.includes(h));
      await ui.assert(missing.length === 0, '抽屉里有总览页的每一项', `缺:${missing.join(' ')}`);
      await ui.assert(/周期/.test(sheet.on), '当前页「周期」在抽屉里高亮', sheet.on);
      await ui.click('#adm-sheet a[href="/admin/fx"]', '抽屉里点「汇率」');
      await ui.page.waitForURL(/\/admin\/fx/, { timeout: 15000 }).catch(() => {});
      await ui.page.waitForLoadState('networkidle').catch(() => {});
      await ui.assert(/管理.*汇率/.test((await ui.page.textContent('.adm-bar')).replace(/\s+/g, ' ')), '到了汇率页,切换条跟着变成「管理 › 汇率」');
      await ui.click('[data-adm-open]', '再点「切换」');
      await ui.click('#adm-sheet a[href="/admin/periods"]', '抽屉里点回「周期」');
      await ui.page.waitForURL(/\/admin\/periods/, { timeout: 15000 }).catch(() => {});
      await ui.page.waitForLoadState('networkidle').catch(() => {});

      // ── 4 · 一期一卡 ────────────────────────────────────────────────
      report.section('4 · 周期表:一期一卡,区间 / 关闭时间 / 状态都一行,理由框和按钮在卡片里');
      await ui.sameSize('.rhythm-opt', '账期节奏四张卡同尺寸(手机 2 × 2)');
      const row = 'table.m-cards tbody tr:has(form[action*="/reopen"])';
      const card = await ui.page.evaluate((sel) => {
        const tr = document.querySelector(sel);
        if (!tr) return null;
        const thead = document.querySelector('table.m-cards thead');
        const r = tr.getBoundingClientRect();
        const cell = (q) => { const e = tr.querySelector(q); return e ? Math.round(e.getBoundingClientRect().height) : -1; };
        const inside = (e) => { const b = e.getBoundingClientRect(); return b.left >= r.left - 1 && b.right <= r.right + 1 && b.right <= innerWidth; };
        const action = tr.querySelector('form[action*="/reopen"]').getAttribute('action');
        return {
          pid: (action.match(/periods\/(\d+)\/reopen/) || [])[1],
          theadShown: !!(thead && thead.offsetParent),
          range: cell('td[data-label="区间"]'), closed: cell('td[data-label="关闭"]'), pill: cell('.pill'),
          input: inside(tr.querySelector('input[name=reason]')), btn: inside(tr.querySelector('form[action*="/reopen"] button')),
          lh: parseFloat(getComputedStyle(tr.querySelector('td[data-label="区间"]')).lineHeight) || 20,
        };
      }, row);
      await ui.assert(!!card && !!card.pid, '找到一张已关账的卡片', JSON.stringify(card));
      if (!card || !card.pid) return;
      state.pid = card.pid;
      await ui.assert(!card.theadShown, '表头在手机上不显示(每张卡自带字段名)');
      await ui.assert(card.range > 0 && card.range < card.lh * 1.6, '「区间 02-01 → 02-28」一行', `高 ${card.range}px(改前折 4 行)`);
      await ui.assert(card.closed > 0 && card.closed < card.lh * 1.6, '「关闭 2026-09-18 17:02」一行', `高 ${card.closed}px`);
      await ui.assert(card.pill > 0 && card.pill < 30, '「CLOSED · 已结束」标签一行', `高 ${card.pill}px(改前折 3 行)`);
      await ui.assert(card.input && card.btn, '「必填理由」和「重新打开」都在卡片里、屏幕内');

      // ── 5 · 卡片里重开 / 关账,真落库 ────────────────────────────────
      report.section('5 · 卡片里填理由 → 重新打开 → 库里 OPEN;再点「关闭账期」→ 库里 CLOSED');
      const before = status(state.pid);
      await ui.assert(before === 'CLOSED', `前置:period #${state.pid} 是 CLOSED`, before);
      await ui.fill(`form[action*="/${state.pid}/reopen"] input[name=reason]`, REASON, '卡片里填「必填理由」');
      ui.page.once('dialog', d => d.accept().catch(() => {}));
      await ui.submit(`form[action*="/${state.pid}/reopen"] button`, '卡片里点「重新打开」');
      await ui.page.waitForLoadState('networkidle').catch(() => {});
      await ui.seesText('周期已重新打开', '页面提示「周期已重新打开」');
      state.reopened = true;
      await ui.assert(status(state.pid) === 'OPEN', `库里 period #${state.pid} 变成 OPEN`, status(state.pid));
      const audit = db.one(`SELECT COUNT(*) FROM audit_log WHERE family_id=${fx.FAM} AND type='PERIOD_REOPEN' AND target_id=${state.pid} AND summary LIKE '%${REASON}%'`);
      await ui.assert(Number(audit) >= 1, '重开理由写进了审计日志', `命中 ${audit} 条`);

      const closeSel = `form[action*="/${state.pid}/force-close"]`;
      await ui.assert(await ui.page.locator(`${closeSel} button`).isVisible().catch(() => false), '这一期的卡片底部换成了「关闭账期」');
      ui.page.once('dialog', d => d.accept().catch(() => {}));
      await ui.submit(`${closeSel} button`, '卡片里点「关闭账期」');
      await ui.page.waitForLoadState('networkidle').catch(() => {});
      await ui.seesText('已强制关账', '页面提示「已强制关账」');
      await ui.assert(status(state.pid) === 'CLOSED', `库里 period #${state.pid} 回到 CLOSED`, status(state.pid));
      await ui.noConsoleErrors('手机上全程浏览器控制台无报错');
    });
  },

  async cleanup(ui, report) {
    ui.flow = this.name;
    if (!state.pid || status(state.pid) === 'CLOSED') { report.info('这一期本来就关着,不用还原'); return; }
    const f = `form[action*="/${state.pid}/force-close"]`;
    ui.page.on('dialog', d => d.accept().catch(() => {}));
    const pg = await ui.paginateUntil('/admin/periods?page={p}', f);
    if (pg >= 0) { await ui.page.click(`${f} button`).catch(() => {}); await ui.page.waitForTimeout(1600); }
    await ui.assert(status(state.pid) === 'CLOSED', `还原:period #${state.pid} 关回去了`, status(state.pid));
  },
};
