#!/usr/bin/env node
/**
 * 功能入口可见性检查 · v1.6.23
 *
 * 为什么要有这个脚本:grep 类守护只能断言「模板里有这个字符串」,抓不到「东西还在但用户找不到」。
 * v1.6 UED 批次5 把账户页 PC 行内的「券商」按钮收进了没有文字的 ⋯ 菜单,
 * 券商自动对接(富途/老虎)这套完整能力在电脑端事实上消失,而守护 v15-ENTRY-1 一直是 PASS。
 *
 * 判据(对 scripts/entry-points.json 里每条 level=obvious 的入口,逐 viewport 检查):
 *   ① 至少一个匹配链接有面积(width/height > 1)
 *   ② 该链接命中自己 —— elementFromPoint(中心点) 追到它本身或其子孙(未被别的元素遮挡)
 *   ③ 不在未展开的 <details> 内,也不在 .row-more-pop(⋯ 收纳菜单)内
 *
 * 退出码:0 = 全通过 · 1 = 有入口不可见 · 2 = 环境不具备(没 chromium / 应用没起)→ 调用方应 SKIP
 * 用法:node scripts/entry-points-check.cjs [baseUrl] [user] [pass]
 */
const fs = require('fs');
const path = require('path');

const BASE = process.argv[2] || process.env.ENTRY_CHECK_BASE || 'http://127.0.0.1:20000';
const USER = process.argv[3] || process.env.ENTRY_CHECK_USER || 'diwa';
const PASS = process.argv[4] || process.env.ENTRY_CHECK_PASS || 'demo1234';

const REG = JSON.parse(fs.readFileSync(path.join(__dirname, 'entry-points.json'), 'utf8'));

// playwright-core 可能装在 npx 缓存里(本机开发)或 node_modules 里(CI);找不到就 SKIP,不 FAIL。
function loadChromium() {
  const cands = [
    'playwright-core', 'playwright',
    '/home/finance/.npm/_npx/9833c18b2d85bc59/node_modules/playwright-core',
  ];
  for (const c of cands) {
    try { return require(c).chromium; } catch (e) { /* 试下一个 */ }
  }
  return null;
}
function findChrome() {
  const envp = process.env.ENTRY_CHECK_CHROME;
  if (envp && fs.existsSync(envp)) return envp;
  const globs = [
    '/home/finance/.cache/ms-playwright',
    path.join(process.env.HOME || '/root', '.cache/ms-playwright'),
  ];
  for (const g of globs) {
    if (!fs.existsSync(g)) continue;
    for (const d of fs.readdirSync(g)) {
      const p = path.join(g, d, 'chrome-linux64', 'chrome');
      if (fs.existsSync(p)) return p;
    }
  }
  for (const p of ['/usr/bin/chromium', '/usr/bin/chromium-browser', '/usr/bin/google-chrome']) {
    if (fs.existsSync(p)) return p;
  }
  return null;
}

// 在页面里判定可见性。注意用**布局坐标**先滚到视口内再做命中测试 ——
// elementFromPoint 是视口坐标,元素在视口外一律返回 null,会被误判成「被遮挡」。
const JUDGE = (href) => {
  const out = [];
  const links = [...document.querySelectorAll('a[href], button[data-href]')]
    .filter(a => (a.getAttribute('href') || a.getAttribute('data-href') || '').includes(href));
  for (const a of links) {
    a.scrollIntoView({ block: 'center', inline: 'nearest' });
    const r = a.getBoundingClientRect();
    const hasArea = r.width > 1 && r.height > 1;
    const collapsedDetails = !!a.closest('details:not([open])');
    const inMorePop = !!a.closest('.row-more-pop');
    let hitsSelf = false;
    if (hasArea) {
      const cx = Math.round(r.left + r.width / 2), cy = Math.round(r.top + r.height / 2);
      if (cx > 0 && cy > 0 && cx < innerWidth && cy < innerHeight) {
        let top = document.elementFromPoint(cx, cy);
        while (top && top !== a) top = top.parentElement;
        hitsSelf = top === a;
      }
    }
    out.push({
      text: (a.textContent || '').trim().slice(0, 14),
      rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
      hasArea, collapsedDetails, inMorePop, hitsSelf,
      ok: hasArea && hitsSelf && !collapsedDetails && !inMorePop,
    });
  }
  return { total: links.length, cands: out };
};

(async () => {
  const chromium = loadChromium();
  const exe = findChrome();
  if (!chromium || !exe) {
    console.log('SKIP 环境不具备:' + (!chromium ? '没找到 playwright-core' : '没找到 chromium 可执行文件'));
    process.exit(2);
  }
  let browser;
  try {
    browser = await chromium.launch({ executablePath: exe, args: ['--no-sandbox', '--disable-dev-shm-usage'] });
  } catch (e) {
    console.log('SKIP 起不了浏览器:' + e.message.split('\n')[0]);
    process.exit(2);
  }

  let fails = 0, checks = 0;
  const obvious = REG.entries.filter(e => e.level === 'obvious');

  for (const vpName of Object.keys(REG.viewports)) {
    const vpCfg = REG.viewports[vpName];
    const todo = obvious.filter(e => (e.viewports || ['pc']).includes(vpName));
    if (!todo.length) continue;
    const ctx = await browser.newContext({
      viewport: { width: vpCfg.width, height: vpCfg.height },
      isMobile: !!vpCfg.isMobile, hasTouch: !!vpCfg.isMobile,
      deviceScaleFactor: vpCfg.isMobile ? 2 : 1,
    });
    /* v1.37 · 不真调大模型(维护者 2026-10-21:回归别烧 API 账单)—— 体检 / 仪表盘页面一打开就会自动请求 AI 区块,
       这里只验入口看不看得见,AI 请求一律回空壳。拦截清单与 e2e 共用一份(scripts/e2e/lib/browser.cjs 的 AI_ROUTE)。 */
    if (process.env.E2E_REAL_AI !== '1') {
      const { AI_ROUTE } = require('./e2e/lib/browser.cjs');
      await ctx.route(AI_ROUTE, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div data-ai-stub></div>' }));
    }
    const page = await ctx.newPage();
    try {
      await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded', timeout: 20000 });
      await page.fill('input[name=username]', USER);
      await page.fill('input[name=password]', PASS);
      await Promise.all([
        page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 20000 }),
        page.click('button[type=submit]'),
      ]);
    } catch (e) {
      console.log('SKIP 登录不了(应用没起?):' + e.message.split('\n')[0]);
      await browser.close(); process.exit(2);
    }

    console.log(`\n【${vpName} ${vpCfg.width}×${vpCfg.height}】`);
    for (const e of todo) {
      checks++;
      await page.goto(BASE + e.page, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(2200);
      // pageFrom:入口在二级页时,先从父页点进去(例:账户列表 →「流水档案」→ 账户详情 → 持仓管理)。
      // 这样登记表能如实表达层级,而不是把二级页的入口硬写成一级页从而误报。
      if (e.pageFrom && e.pageFrom.clickHrefMatches) {
        const target = await page.evaluate((pat) => {
          const rx = new RegExp(pat);
          const a = [...document.querySelectorAll('a[href]')]
            .map(x => x.getAttribute('href'))
            .find(h => rx.test(h || ''));
          return a || null;
        }, e.pageFrom.clickHrefMatches);
        if (!target) {
          fails++;
          console.log(`  ✗ ${e.id.padEnd(16)} ${e.name}`);
          console.log(`      ${e.page} @${vpName} 找不到进入二级页的链接(pageFrom ${e.pageFrom.clickHrefMatches})`);
          continue;
        }
        await page.goto(BASE + target, { waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(1800);
      }
      await page.evaluate(() => { const o = document.getElementById('page-overlay'); if (o) o.classList.add('hidden'); });
      const r = await page.evaluate(JUDGE, e.hrefContains);
      const good = r.cands.filter(c => c.ok);
      if (good.length) {
        console.log(`  ✓ ${e.id.padEnd(16)} ${e.name} · ${good.length}/${r.total} 个一眼可见 · 例 rect=${good[0].rect.join(',')} 「${good[0].text}」`);
      } else {
        fails++;
        const why = r.total === 0 ? 'DOM 里根本没有这个链接'
          : r.cands.map(c => {
              const bad = [];
              if (!c.hasArea) bad.push('无面积');
              if (c.inMorePop) bad.push('被收进 ⋯ 菜单(.row-more-pop)');
              if (c.collapsedDetails) bad.push('在未展开的 details 内');
              if (c.hasArea && !c.hitsSelf) bad.push('被别的元素遮挡');
              return `「${c.text}」${bad.join('+') || '?'}`;
            }).join(' · ');
        console.log(`  ✗ ${e.id.padEnd(16)} ${e.name}`);
        console.log(`      ${e.page} @${vpName} 共 ${r.total} 个匹配,无一「一眼可见」→ ${why}`);
        if (e.note) console.log(`      登记表备注:${e.note}`);
      }
    }
    await ctx.close();
  }

  await browser.close();
  console.log(`\n合计 ${checks} 项检查 · 失败 ${fails} 项`);
  process.exit(fails ? 1 : 0);
})().catch(e => { console.error('FATAL ' + e.message); process.exit(2); });
