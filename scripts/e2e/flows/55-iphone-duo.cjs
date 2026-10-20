/**
 * flow · v1.36 · iPhone Duo(折叠屏):合上、打开、分屏、折叠的那一下都不走样(维护者 2026-10-20)
 *
 * 尺寸来自第三方模拟器实测(官方没给网页尺寸,见 prd/v1.36.md §1):外屏 Safari 382×580 · 外屏主屏 App 466×650 ·
 * 分屏半边 469×589 · 内屏竖 669×890 · 内屏横 Safari 951×589 · 内屏横主屏 App 951×669。
 * 「折叠」= 页面不刷新、原地改尺寸(ui.page.setViewportSize),和真机一样。
 *
 *   1  六种姿态:从仪表盘点汉堡菜单逐个进 填报 / 账户 / 报表 / 仪表盘 —— 不横滑、按钮不截字;951 宽仍是汉堡菜单
 *        → 库:仪表盘「N 个已选」== 库里未归档账户数
 *   2  仪表盘折叠:951 读到「人赚 vs 钱赚」→ 合上 382 → 打开 951
 *        每一步:不横滑 · 每张图不超出容器 · 账户分布图标题跟着换形态(宽屏才带「资产 → 右 / 负债 → 左」)·
 *        正在看的那一节还在原处 · 变宽后筛选条看得见 · 图实例不叠层
 *        → 库:筛选条「N 个已选」== 库里未归档账户数
 *   3  报表 + 资产透视折叠:同上(画布宽度 / 不横滑 / 那一节还在 / 旭日图跟着缩)
 *        → 库:报表筛选条「N 个已选」== 库里未归档账户数
 *   4  外屏打开再展开:筛选条原来是收起的,展开后看得见(v1.35 是整条消失)
 *   5  折叠时正在填:951 在工商银行-备用金填余额(不提交)→ 合上 → 值还在 → 保存
 *        → 库:这个账户本期余额 == 填的值
 *   6  分屏:同一登录开两个 469×589 的页签,A 存余额,B 刷新后看到;B 再存一次也成功(不撞 CSRF)
 *        → 库:A、B 先后存的值都落库
 *   7  普通 iPhone 不受影响:390×844 转横 844×390 再转回,那一节回到原处、筛选条状态不变
 *        → 库:「N 个已选」== 库里未归档账户数
 *
 * 第 5、6 步会改余额 —— cleanup 用开跑前的整库快照还原并重启应用,然后重新登录(同 flow 52)。
 */
const fs = require('fs');
const { execFileSync } = require('child_process');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const { BASE, USER, PASS } = require('../lib/browser.cjs');

const A = 2;                                // 工商银行-备用金 · CASH · CNY
const DUMP = '/tmp/e2e55-pre.sql';
const MYSQL_ARGS = ['-ufinance', '-pfinance', 'finance'];
const AI_STUB = /\/(review\/insight|checkup\/(diagnose|insight))/;
const POSTURES = [['外屏竖 · Safari', 382, 580], ['外屏竖 · 主屏 App', 466, 650], ['分屏半边', 469, 589],
                  ['内屏竖', 669, 890], ['内屏横 · Safari', 951, 589], ['内屏横 · 主屏 App', 951, 669]];
const INNER = [951, 589], OUTER = [382, 580];
const WIDE_TITLE = '资产 → 右';
const state = {};

const activeAccounts = () => db.num(`SELECT COUNT(*) FROM account WHERE family_id=${fx.FAM} AND archived_at IS NULL`);
const bal = (pid) => db.one(`SELECT end_balance FROM period_snapshot WHERE account_id=${A} AND period_id=${pid}`);

/** 从顶栏汉堡菜单点进某一页(1024 以下都是汉堡菜单,951 也是) */
async function viaMenu(ui, href, label) {
  /* 等印章式启动遮罩撤掉(DOMContentLoaded 后约 1 秒)—— 遮着的时候菜单点不到,用户也一样 */
  await ui.page.waitForFunction(() => { const o = document.getElementById('page-overlay'); return !o || o.classList.contains('hidden'); },
    null, { timeout: 15000 }).catch(() => {});
  /* 951 宽时顶栏还有字号档位的下拉(也是 details)—— 按「里面有这个链接」认汉堡菜单 */
  await ui.click(`header details:has(a[href="${href}"]) > summary:visible`, '点右上角菜单');
  await Promise.all([
    ui.page.waitForURL(u => new URL(u).pathname === href, { timeout: 20000 }).catch(() => {}),
    ui.click(`header details[open] a[href="${href}"]:visible`, `菜单里点「${label}」`),
  ]);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.page.waitForTimeout(500);
}

/** 版面量一遍:横向溢出 · 截字的按钮 · 顶栏 */
const layout = (ui) => ui.page.evaluate(() => {
  const vis = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden'; };
  const clipped = [...document.querySelectorAll('.btn-ink,.btn-paper,.btn-ghost,.btn-brass,.btn-danger')].filter(vis)
    .filter(b => b.scrollWidth > b.clientWidth + 2).map(b => (b.textContent || '').trim().slice(0, 12));
  const bd = document.querySelector('header details:has(a[href="/entry"])');
  const burger = bd && bd.querySelector(':scope > summary');
  const tabs = document.querySelector('nav.nav-tabs');
  const header = document.querySelector('header');
  return { over: document.documentElement.scrollWidth - innerWidth, clipped,
           burger: !!burger && vis(burger), tabs: !!tabs && vis(tabs),
           headerH: header ? Math.round(header.getBoundingClientRect().height) : 0 };
});

/** 图表量一遍:画布不超出容器 · 每张 Chart.js 图只有一个实例 · 正在看的那一节离顶部多远 · 筛选条 */
const charts = (ui, anchorId) => ui.page.evaluate((aid) => {
  const over = [];
  for (const cv of document.querySelectorAll('canvas')) {
    const r = cv.getBoundingClientRect(); if (!r.width) continue;
    const host = cv.closest('[_echarts_instance_]') || cv.parentElement;
    const pr = host.getBoundingClientRect();
    if (r.width > pr.width + 2) over.push((cv.id || host.id || 'canvas') + ' ' + Math.round(r.width) + '>' + Math.round(pr.width));
  }
  const fc = window.financeCharts || {};
  const live = window.Chart ? [...document.querySelectorAll('canvas')].filter(c => Chart.getChart(c)).length : 0;
  const div = fc.accountDiverge;
  const fold = [...document.querySelectorAll('details.filter-fold')].map(d => {
    const kids = [...d.children].filter(c => c.tagName !== 'SUMMARY');
    return { open: d.open, h: Math.round(kids.reduce((t, c) => t + c.getBoundingClientRect().height, 0)) };
  });
  const a = aid && document.getElementById(aid);
  const fcAlive = Object.values(fc).filter(c => c && c.canvas && c.canvas.isConnected && window.Chart && Chart.getChart(c.canvas) === c).length;
  return { over, live, fcAlive,
           title: div ? String(div.options.plugins.title.text) : null,
           anchorTop: a ? Math.round(a.getBoundingClientRect().top) : null, fold,
           pageOver: document.documentElement.scrollWidth - innerWidth };
}, anchorId);

async function scrollTo(ui, id, top) {
  await ui.page.evaluate(([i, t]) => { const e = document.getElementById(i); window.scrollTo(0, e.getBoundingClientRect().top + scrollY - t); }, [id, top]);
  await ui.page.waitForTimeout(600);
}

async function resize(ui, [w, h]) {
  await ui.page.setViewportSize({ width: w, height: h });
  await ui.page.waitForTimeout(1500);           // 防抖 150ms + 重排三拍(到 420ms)+ 锚点还原(到 400ms)+ 余量
}

/** 筛选条上「N 个已选」 */
const selectedCount = (ui) => ui.page.evaluate(() => {
  const s = [...document.querySelectorAll('details.filter-fold span')].map(e => e.textContent).find(t => /\d+ 个已选/.test(t || ''));
  return s ? Number(s.match(/(\d+) 个已选/)[1]) : null;
});

async function relogin(ui) {
  await ui.page.goto(BASE + '/login', { waitUntil: 'networkidle', timeout: 60000 });
  if (await ui.page.locator('input[name=username]').count() === 0) return;
  await ui.page.fill('input[name=username]', USER);
  await ui.page.fill('input[name=password]', PASS);
  await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 60000 }).catch(() => {}),
                     ui.page.click('button[type=submit]')]);
}

/** 打开工商银行-备用金那一行(窄屏首次加载是收起的) */
async function openRow(ui) {
  const fold = `#entry-block-${A} details.entry-fold`;
  const isOpen = await ui.page.locator(fold).first().evaluate(d => d.open).catch(() => null);
  if (isOpen === false) await ui.click(`${fold} > summary`, '展开工商银行-备用金这一行');
}
const BAL_FORM = `#entry-block-${A} form[hx-post$="/entry/${A}/balance"]`;

async function saveBalance(ui, label) {
  await Promise.all([
    ui.page.waitForResponse(r => r.url().includes(`/entry/${A}/balance`), { timeout: 20000 }).catch(() => null),
    ui.click(`${BAL_FORM} button.btn-ink`, label),
  ]);
  await ui.page.waitForTimeout(1200);
}

module.exports = {
  name: '55-iphone-duo',
  title: 'v1.36 · iPhone Duo · 六种姿态不走样 · 折叠 / 展开时图表重排重画、那一节留在原处、筛选条不消失、填了一半的不丢 · 分屏两窗口',

  async run(ui, report) {
    ui.flow = this.name;
    const fd = fs.openSync(DUMP, 'w');
    execFileSync('mysqldump', ['--single-transaction', '--skip-lock-tables', ...MYSQL_ARGS], { stdio: ['ignore', fd, 'ignore'] });
    fs.closeSync(fd);
    state.dumped = true;
    report.info(`整库快照 → ${DUMP}(cleanup 用它还原)`);
    const pid = Number(fx.currentPeriod());
    const nAcc = activeAccounts();

    // ── 1 · 六种姿态 ───────────────────────────────────────────────────
    for (const [name, w, h] of POSTURES) {
      report.section(`1 · ${name} ${w}×${h}:汉堡菜单逐个进 填报 / 账户 / 报表 / 仪表盘`);
      await ui.device(w, h, async (ui) => {
        await ui.page.route(AI_STUB, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
        await ui.goto('/dashboard');
        for (const [href, label] of [['/entry', '填报'], ['/accounts', '账户'], ['/reports', '报表'], ['/dashboard', '仪表盘']]) {
          await viaMenu(ui, href, label);
          await ui.rendered(`${name} · ${label}`);
          const m = await layout(ui);
          await ui.assert(m.over <= 1, `${name} · ${label}:不横向滚动`, `多出 ${m.over}px`);
          await ui.assert(m.clipped.length === 0, `${name} · ${label}:按钮不截字`, m.clipped.join(' / '));
          if (w === 951) {
            await ui.assert(m.burger && !m.tabs, `${name} · ${label}:仍是汉堡菜单,整排导航不出现`, JSON.stringify(m));
            await ui.assert(m.headerH <= 80, `${name} · ${label}:顶栏一行不折行`, `${m.headerH}px`);
          }
        }
        const n = await selectedCount(ui);
        await ui.assert(n === nAcc, `${name} · 仪表盘「${n} 个已选」== 库里未归档账户数`, `库 ${nAcc}`);
      });
    }

    // ── 2 · 仪表盘折叠 ─────────────────────────────────────────────────
    report.section('2 · 仪表盘:951 读到「人赚 vs 钱赚」→ 合上 382 → 打开 951');
    await ui.device(...INNER, async (ui) => {
      await ui.page.route(AI_STUB, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
      await ui.goto('/dashboard');
      await scrollTo(ui, 'dash-cashflow', 60);
      const s0 = await charts(ui, 'dash-cashflow');
      await ui.assert(s0.title && s0.title.includes(WIDE_TITLE), '内屏:账户分布图是宽屏形态', s0.title);
      for (const [vp, wide, label] of [[OUTER, false, '合上(382)'], [INNER, true, '再打开(951)']]) {
        await resize(ui, vp);
        const s = await charts(ui, 'dash-cashflow');
        await ui.assert(s.pageOver <= 1, `${label}:不横向滚动`, `多出 ${s.pageOver}px`);
        await ui.assert(s.over.length === 0, `${label}:每张图都没超出容器`, s.over.join(' / '));
        await ui.assert(!!s.title && s.title.includes(WIDE_TITLE) === wide, `${label}:账户分布图换成${wide ? '宽屏' : '窄屏'}形态`, s.title);
        await ui.assert(s.anchorTop !== null && Math.abs(s.anchorTop - s0.anchorTop) <= 40, `${label}:「人赚 vs 钱赚」还在原处`, `${s0.anchorTop} → ${s.anchorTop}px`);
        await ui.assert(s.fcAlive >= 5 && s.live >= s.fcAlive, `${label}:5 张图重画后都在、各占自己的画布`, `画布上的图 ${s.live} · 仪表盘的图 ${s.fcAlive}`);
        await ui.noConsoleErrors(`${label}:重画没有报错(叠层会报 Canvas is already in use)`);
        await ui.assert(s.fold.every(f => f.open && f.h > 20), `${label}:筛选条看得见`, JSON.stringify(s.fold));
      }
      const n = await selectedCount(ui);
      await ui.assert(n === nAcc, `仪表盘筛选条「${n} 个已选」== 库里未归档账户数`, `库 ${nAcc}`);

      // ── 3 · 资产透视(仪表盘底部)────────────────────────────────────
      report.section('3a · 资产透视:滚到旭日图 → 合上 → 打开,旭日图跟着缩放');
      await ui.page.evaluate(() => document.getElementById('lens-section').scrollIntoView());
      await ui.page.waitForFunction(() => { const e = document.getElementById('sunburst'); return e && e.querySelector('canvas'); }, null, { timeout: 30000 }).catch(() => {});
      await ui.page.waitForTimeout(1200);
      for (const [vp, label] of [[OUTER, '合上(382)'], [INNER, '再打开(951)']]) {
        await resize(ui, vp);
        const s = await charts(ui, null);
        const sun = await ui.page.evaluate(() => { const e = document.getElementById('sunburst'); const c = e && e.querySelector('canvas');
          return c ? { c: Math.round(c.getBoundingClientRect().width), e: Math.round(e.getBoundingClientRect().width) } : null; });
        await ui.assert(!!sun && sun.c <= sun.e + 2, `${label}:旭日图画布跟着容器`, JSON.stringify(sun));
        await ui.assert(s.pageOver <= 1, `${label}:不横向滚动(v1.35 合上后整页被撑到 918px)`, `多出 ${s.pageOver}px`);
      }
    });

    report.section('3b · 报表:951 读到「结构」一节 → 合上 → 打开');
    await ui.device(...INNER, async (ui) => {
      await ui.page.route(AI_STUB, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
      await ui.goto('/dashboard');
      await viaMenu(ui, '/reports', '报表');
      await scrollTo(ui, 'sec-structure', 60);
      const s0 = await charts(ui, 'sec-structure');
      for (const [vp, label] of [[OUTER, '合上(382)'], [INNER, '再打开(951)']]) {
        await resize(ui, vp);
        const s = await charts(ui, 'sec-structure');
        await ui.assert(s.pageOver <= 1, `${label}:不横向滚动(v1.35 合上后整页 457px)`, `多出 ${s.pageOver}px`);
        await ui.assert(s.over.length === 0, `${label}:每张图都没超出容器`, s.over.join(' / '));
        await ui.assert(s.anchorTop !== null && Math.abs(s.anchorTop - s0.anchorTop) <= 40, `${label}:那一节还在原处`, `${s0.anchorTop} → ${s.anchorTop}px`);
      }
      const n = await selectedCount(ui);
      await ui.assert(n === nAcc, `报表筛选条「${n} 个已选」== 库里未归档账户数`, `库 ${nAcc}`);
    });

    // ── 4 · 外屏打开再展开 ─────────────────────────────────────────────
    report.section('4 · 外屏打开仪表盘(筛选条收起)→ 展开:筛选条看得见');
    await ui.device(...OUTER, async (ui) => {
      await ui.page.route(AI_STUB, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
      await ui.goto('/dashboard');
      const s0 = await charts(ui, null);
      await ui.assert(s0.fold.length > 0 && s0.fold.every(f => !f.open), '外屏:筛选条按窄屏惯例收起');
      await resize(ui, INNER);
      const s1 = await charts(ui, null);
      await ui.assert(s1.fold.every(f => f.open && f.h > 20), '展开后筛选条看得见(v1.35 是整条消失、也点不出来)', JSON.stringify(s1.fold));
      await ui.assert(!!s1.title && s1.title.includes(WIDE_TITLE), '展开后账户分布图换成宽屏形态', s1.title);
      const n = await selectedCount(ui);
      await ui.assert(n === nAcc, `展开后筛选条「${n} 个已选」== 库里未归档账户数`, `库 ${nAcc}`);
    });

    // ── 5 · 折叠时正在填 ───────────────────────────────────────────────
    report.section('5 · 内屏填工商银行-备用金余额(不提交)→ 合上 → 值还在 → 保存');
    const orig = Number(bal(pid) || 0);
    const v1 = (orig + 12.34).toFixed(2);
    await ui.device(...INNER, async (ui) => {
      await ui.goto('/dashboard');
      await viaMenu(ui, '/entry', '填报');
      await openRow(ui);
      await ui.fill(`${BAL_FORM} input[name="newBalance"]`, v1, `余额填 ${v1}(不提交)`);
      await ui.fill(`${BAL_FORM} input[name="note"]`, 'e2e55 折叠中', '备注填「e2e55 折叠中」');
      await resize(ui, OUTER);
      const kept = await ui.page.evaluate((sel) => ({ b: document.querySelector(sel + ' input[name="newBalance"]').value,
        n: document.querySelector(sel + ' input[name="note"]').value, over: document.documentElement.scrollWidth - innerWidth }), BAL_FORM);
      await ui.assert(kept.b === v1 && kept.n === 'e2e55 折叠中', '合上之后没提交的值都还在', JSON.stringify(kept));
      await ui.assert(kept.over <= 1, '合上之后填报页不横向滚动', `多出 ${kept.over}px`);
      await saveBalance(ui, '外屏上点「保存」');
    });
    await ui.assert(Math.abs(Number(bal(pid)) - Number(v1)) < 0.005, `库:本期余额 == ${v1}`, `库 ${bal(pid)}`);

    // ── 6 · 分屏两个窗口 ───────────────────────────────────────────────
    report.section('6 · 分屏:同一登录两个 469×589 窗口,A 存、B 刷新看得到;B 再存也成功');
    const v2 = (orig + 56.78).toFixed(2), v3 = (orig + 90.12).toFixed(2);
    await ui.device(469, 589, async (a) => {
      await a.goto('/dashboard');
      const b = await a.newTab();
      await b.goto('/dashboard');
      await viaMenu(b, '/entry', '填报');
      await viaMenu(a, '/entry', '填报');
      await openRow(a);
      await a.fill(`${BAL_FORM} input[name="newBalance"]`, v2, `A 窗口余额填 ${v2}`);
      await saveBalance(a, 'A 窗口点「保存」');
      await a.assert(Math.abs(Number(bal(pid)) - Number(v2)) < 0.005, `库:A 存的 ${v2} 落库`, `库 ${bal(pid)}`);
      await b.page.reload({ waitUntil: 'networkidle' });
      await b.page.waitForTimeout(600);
      await openRow(b);
      const seen = await b.page.locator(`${BAL_FORM} input[name="newBalance"]`).inputValue();
      await b.assert(Math.abs(Number(seen) - Number(v2)) < 0.005, 'B 窗口刷新后看到 A 存的值', seen);
      await b.fill(`${BAL_FORM} input[name="newBalance"]`, v3, `B 窗口余额改成 ${v3}`);
      await saveBalance(b, 'B 窗口点「保存」');
      const m = await layout(b);
      await b.assert(m.over <= 1, 'B 窗口(分屏宽度)不横向滚动', `多出 ${m.over}px`);
    });
    await ui.assert(Math.abs(Number(bal(pid)) - Number(v3)) < 0.005, `库:B 再存的 ${v3} 也落库(两个窗口共用登录、不撞 CSRF)`, `库 ${bal(pid)}`);

    // ── 7 · 普通 iPhone ────────────────────────────────────────────────
    report.section('7 · 普通 iPhone 390×844:竖 → 横 → 竖,和 v1.35 一样');
    await ui.phone(async (ui) => {
      await ui.goto('/dashboard');
      await scrollTo(ui, 'dash-trend', 60);
      const s0 = await charts(ui, 'dash-trend');
      await ui.page.setViewportSize({ width: 844, height: 390 }); await ui.page.waitForTimeout(1500);
      await ui.page.setViewportSize({ width: 390, height: 844 }); await ui.page.waitForTimeout(1500);
      const s1 = await charts(ui, 'dash-trend');
      await ui.assert(Math.abs(s1.anchorTop - s0.anchorTop) <= 40, '转回竖屏:那一节回到原处', `${s0.anchorTop} → ${s1.anchorTop}px`);
      await ui.assert(JSON.stringify(s1.fold.map(f => f.open)) === JSON.stringify(s0.fold.map(f => f.open)), '筛选条开合状态不变(手机横屏仍算窄屏)', JSON.stringify([s0.fold, s1.fold]));
      await ui.assert(s1.pageOver <= 1 && s1.over.length === 0, '不横向滚动、图不超出容器', `${s1.pageOver} · ${s1.over.join(' / ')}`);
      const n = await selectedCount(ui);
      await ui.assert(n === nAcc, `「${n} 个已选」== 库里未归档账户数`, `库 ${nAcc}`);
    });
  },

  async cleanup(ui, report) {
    if (!state.dumped || !fs.existsSync(DUMP)) { report.info('没有整库快照,跳过还原'); return; }
    const fd = fs.openSync(DUMP, 'r');
    execFileSync('mysql', MYSQL_ARGS, { stdio: [fd, 'ignore', 'ignore'] });
    fs.closeSync(fd);
    execFileSync('sudo', ['-n', '/bin/systemctl', 'restart', 'finance']);
    for (let i = 0; i < 60; i++) {
      await ui.page.waitForTimeout(3000);
      let code = '';
      try { code = execFileSync('curl', ['-s', '-m', '3', '-o', '/dev/null', '-w', '%{http_code}', `${BASE}/login`]).toString(); } catch (e) { /* 还在起 */ }
      if (code === '200') break;
    }
    await relogin(ui);
    report.info('还原:整库回到开跑前 · 应用已重启并重新登录');
  },
};
