/**
 * flow · v1.37 · iPhone Duo 侧边操作栏 · 折痕避让 · 外屏横握不冻结(维护者 2026-10-22「好的 进行开发吧」)
 *
 * 苹果 HIG:外屏(合上)和内屏横向时,顶部、底部的操作挪到右侧一列;只有内屏竖向保留横向顶栏;
 * 半折时弹层离开折痕;网格优先偶数列。我们只在「主屏 App」里出侧边栏(prd/v1.37.md §3.0)。
 * 主屏 App 用 navigator.standalone 模拟(iOS 主屏 App 真实走的那一支;无头 Chromium 模拟不了 display-mode)。
 * 尺寸两组都跑:官方像素 ÷ 3(外屏 466×678 · 内屏 626×890)和第三方模拟器那组(内屏 951×669 / 669×951)。
 *
 *   1  外屏竖握(主屏 App):有侧边栏、页头不吸顶、浮钮不见、内容不被盖住、不横滑
 *        点侧边栏「填报」→ /entry;角标 → 库:本期待填数;「更多」里有「管理」和「退出」;隐私眼能切换
 *   2  外屏横握 / 帐篷(主屏 App):有侧边栏、不冻结旋转 → 库:「N 个已选」= 未归档账户数
 *   3  内屏横(主屏 App):有侧边栏 + 折痕区;3 列网格变 2 列;支出类目「新建类目」弹框开在右半边
 *        → 库:类目数不变(只开不存)
 *   4  内屏竖(主屏 App):没有侧边栏,页头照旧吸顶
 *   5  折叠往返(同一页不刷新):外屏竖 → 内屏横 → 内屏竖,侧边栏 在 / 在 / 不在,正在看的那一节不动
 *   6  同样尺寸在浏览器里:没有侧边栏;内屏横时折痕区照样生效,弹框在右半边
 *   7  普通设备:普通 iPhone / SE / Pro Max / iPad 的主屏 App、电脑都没有侧边栏和折痕区;普通 iPhone 横屏照旧冻结旋转
 *
 * 只读:不改任何库里的数据(隐私眼只是本次会话的显示状态)。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const OUTER_P = [466, 678], OUTER_L = [678, 466], INNER_L = [890, 626], INNER_P = [626, 890];
const ALT_INNER_L = [951, 669], ALT_INNER_P = [669, 951];

const activeAccounts = () => db.num(`SELECT COUNT(*) FROM account WHERE family_id=${fx.FAM} AND archived_at IS NULL`);

/** 侧边栏 / 页头 / 浮钮 / 冻结旋转的现状 */
const shape = (ui) => ui.page.evaluate(() => {
  const r = document.querySelector('.side-rail');
  const rr = r ? r.getBoundingClientRect() : null;
  const hdr = document.querySelector('header');
  const dock = document.getElementById('float-dock');
  const main = document.querySelector('main');
  const mr = main ? main.getBoundingClientRect() : null;
  const cls = document.documentElement.classList;
  return {
    rail: !!r && getComputedStyle(r).display !== 'none',
    railW: rr ? Math.round(rr.width) : 0, railRight: rr ? Math.round(rr.right) : 0,
    vw: innerWidth, vpRail: cls.contains('vp-rail'), vpFold: cls.contains('vp-fold'),
    hdrPos: hdr ? getComputedStyle(hdr).position : null,
    dock: dock ? getComputedStyle(dock).display : 'none',
    fab: (() => { const f = document.querySelector('.ask-fab'); return f ? getComputedStyle(f).display : 'none'; })(),
    mainRight: mr ? Math.round(mr.right) : null,
    over: document.documentElement.scrollWidth - innerWidth,
    freeze: getComputedStyle(document.body).position === 'fixed' && getComputedStyle(document.body).transform !== 'none',
  };
});

async function expectRail(ui, label) {
  const s = await shape(ui);
  await ui.assert(s.rail && s.vpRail && s.railRight >= s.vw - 1 && s.railW >= 48,
    `${label}:右侧有侧边操作栏`, JSON.stringify(s));
  await ui.assert(s.hdrPos !== 'sticky' && s.dock === 'none' && s.fab === 'none', `${label}:页头不再吸顶、右下浮钮和「超级 Agent」药丸都收进侧边栏`, JSON.stringify(s));
  await ui.assert(s.mainRight === null || s.mainRight <= s.vw - s.railW + 1, `${label}:内容不被侧边栏盖住`, `${s.mainRight} vs ${s.vw - s.railW}`);
  await ui.assert(s.over <= 1, `${label}:不横向滚动`, `多出 ${s.over}px`);
  return s;
}
async function expectNoRail(ui, label) {
  const s = await shape(ui);
  await ui.assert(!s.rail && !s.vpRail, `${label}:没有侧边操作栏`, JSON.stringify(s));
  return s;
}

async function viaRail(ui, label, href) {
  await Promise.all([
    ui.page.waitForURL(u => new URL(u).pathname === href, { timeout: 20000 }).catch(() => {}),
    ui.click(`.side-rail a[aria-label="${label}"]`, `点侧边栏「${label}」`),
  ]);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.page.waitForTimeout(500);
}

const selectedCount = (ui) => ui.page.evaluate(() => {
  const s = [...document.querySelectorAll('details.filter-fold span')].map(e => e.textContent).find(t => /\d+ 个已选/.test(t || ''));
  return s ? Number(s.match(/(\d+) 个已选/)[1]) : null;
});

/** 支出类目页:打开「新建类目」弹框,量它在不在右半边;关掉,不存 */
async function dialogRightHalf(ui, label) {
  const before = db.num(`SELECT COUNT(*) FROM expense_category WHERE family_id=${fx.FAM}`);
  await ui.page.waitForSelector('button[onclick^="openNewCat"]', { timeout: 15000 }).catch(() => {});
  await ui.click('button[onclick^="openNewCat"] >> nth=0', `${label}:点「新建类目」`);
  await ui.page.waitForTimeout(400);
  const d = await ui.page.evaluate(() => {
    const g = document.getElementById('newCatDlg'); const r = g.getBoundingClientRect();
    return { open: g.open, left: Math.round(r.left), right: Math.round(r.right), vw: innerWidth };
  });
  await ui.assert(d.open && d.left >= d.vw / 2 && d.right <= d.vw, `${label}:弹框开在右半边,不压中间折痕`, JSON.stringify(d));
  await ui.page.evaluate(() => document.getElementById('newCatDlg').close());
  await ui.assert(db.num(`SELECT COUNT(*) FROM expense_category WHERE family_id=${fx.FAM}`) === before, `${label} · 库:类目数不变(只开不存)`, `${before}`);
}

async function toCategories(ui) {
  const hasRail = await ui.page.evaluate(() => document.documentElement.classList.contains('vp-rail'));
  if (hasRail) await viaRail(ui, '填报', '/entry');
  else {
    await ui.click('header details:has(a[href="/entry"]) > summary:visible', '点右上角菜单');
    await Promise.all([ui.page.waitForURL(/\/entry/, { timeout: 20000 }).catch(() => {}),
                       ui.click('header details[open] a[href="/entry"]:visible', '菜单里点「填报」')]);
    await ui.page.waitForLoadState('networkidle').catch(() => {});
  }
  await Promise.all([
    ui.page.waitForURL(/\/expense\/categories/, { timeout: 20000 }).catch(() => {}),
    ui.click('a[href*="/expense/categories"] >> nth=0', '填报页点「管理分类」'),
  ]);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
}

module.exports = {
  name: '56-duo-side-rail',
  title: 'v1.37 · iPhone Duo 侧边操作栏(主屏 App · 外屏 / 内屏横)· 内屏横弹框右半边 + 偶数列 · 外屏横握不冻结 · 普通设备不变',

  async run(ui, report) {
    ui.flow = this.name;
    const nAcc = activeAccounts();
    const pid = Number(fx.currentPeriod());
    const todoPending = db.num(`SELECT COUNT(*) FROM snapshot_todo WHERE period_id=${pid} AND status='PENDING'`);
    const ffEnabled = db.num(`SELECT COUNT(*) FROM fixed_flow WHERE family_id=${fx.FAM} AND disabled_at IS NULL`);

    // ── 1 · 外屏竖握 ──────────────────────────────────────────────────
    report.section('1 · 外屏竖握(主屏 App)466×678:侧边栏 · 填报角标 · 更多菜单 · 隐私眼');
    await ui.device(...OUTER_P, async (ui) => {
      await ui.goto('/dashboard');
      await expectRail(ui, '外屏竖 · 仪表盘');
      const dashOn = await ui.page.getAttribute('.side-rail a[aria-label="仪表盘"]', 'class');
      await ui.assert(/\bon\b/.test(dashOn || ''), '当前页「仪表盘」在侧边栏里高亮', dashOn);
      await viaRail(ui, '填报', '/entry');
      await ui.assert(new URL(ui.page.url()).pathname === '/entry', '点侧边栏「填报」到了填报页', ui.page.url());
      await expectRail(ui, '外屏竖 · 填报');
      const badge = await ui.page.locator('.side-rail a[aria-label="填报"] .rail-badge').innerText().catch(() => '0');
      const okBadge = ffEnabled === 0 ? Number(badge) === todoPending : Number(badge) >= todoPending;
      await ui.assert(okBadge, `库:填报角标 ${badge} = 本期待填 ${todoPending}${ffEnabled ? ' + 到期固定收支' : ''}`, `固定收支 ${ffEnabled} 条`);
      await ui.click('.side-rail details[data-rail-more] > summary', '点侧边栏「更多」');
      await ui.page.waitForTimeout(300);
      const more = await ui.page.evaluate(() => {
        const p = document.querySelector('.side-rail .rail-more-panel'); const r = p.getBoundingClientRect();
        return { shown: getComputedStyle(p).display !== 'none' && r.width > 100, admin: !!p.querySelector('a[href="/admin"]'),
                 logout: !!p.querySelector('form[action="/logout"] button'), right: Math.round(r.right), vw: innerWidth };
      });
      await ui.assert(more.shown && more.admin && more.logout, '「更多」展开完整菜单(含管理、退出)', JSON.stringify(more));
      await ui.page.mouse.click(20, 300);
      await ui.page.waitForTimeout(300);
      const closed = await ui.page.evaluate(() => !document.querySelector('.side-rail details[data-rail-more]').open);
      await ui.assert(closed, '点菜单外面,「更多」收起');
      const p0 = await ui.page.evaluate(() => document.documentElement.classList.contains('privacy'));
      await ui.click('.side-rail [data-rail-priv]', '点侧边栏隐私眼');
      const p1 = await ui.page.evaluate(() => document.documentElement.classList.contains('privacy'));
      await ui.assert(p0 !== p1, '隐私眼切换了金额显示', `${p0} → ${p1}`);
    }, { app: true });

    // ── 2 · 外屏横握 / 帐篷 ───────────────────────────────────────────
    report.section('2 · 外屏横握 / 帐篷(主屏 App)678×466:侧边栏 · 不冻结旋转');
    await ui.device(...OUTER_L, async (ui) => {
      await ui.goto('/dashboard');
      const s = await expectRail(ui, '外屏横 · 仪表盘');
      await ui.assert(!s.freeze, '没有冻结旋转(页面正着,按横向排)', JSON.stringify(s));
      const n = await selectedCount(ui);
      await ui.assert(n === nAcc, `库:「${n} 个已选」= 未归档账户数`, `库 ${nAcc}`);
    }, { app: true });

    // ── 3 · 内屏横 ───────────────────────────────────────────────────
    for (const [w, h] of [INNER_L, ALT_INNER_L]) {
      report.section(`3 · 内屏横(主屏 App)${w}×${h}:侧边栏 + 折痕区 · 偶数列 · 弹框右半边`);
      await ui.device(w, h, async (ui) => {
        await ui.goto('/dashboard');
        const s = await expectRail(ui, `内屏横 ${w}`);
        await ui.assert(s.vpFold, `内屏横 ${w}:折痕区生效`, JSON.stringify(s));
        const cols = await ui.page.evaluate(() => [...document.querySelectorAll('.md\\:grid-cols-3, .md\\:grid-cols-5')]
          .filter(e => e.getBoundingClientRect().width > 0)
          .map(e => getComputedStyle(e).gridTemplateColumns.split(' ').length));
        await ui.assert(cols.every(c => c % 2 === 0), `内屏横 ${w}:仪表盘上的网格都是偶数列(中线落在列缝)`, JSON.stringify(cols));
        await toCategories(ui);
        await dialogRightHalf(ui, `内屏横 ${w}`);
      }, { app: true });
    }

    // ── 4 · 内屏竖 ───────────────────────────────────────────────────
    for (const [w, h] of [INNER_P, ALT_INNER_P]) {
      report.section(`4 · 内屏竖(主屏 App)${w}×${h}:保留横向顶栏`);
      await ui.device(w, h, async (ui) => {
        await ui.goto('/dashboard');
        const s = await expectNoRail(ui, `内屏竖 ${w}`);
        await ui.assert(s.hdrPos === 'sticky' && !s.vpFold, `内屏竖 ${w}:页头照旧吸顶,没有折痕区`, JSON.stringify(s));
        const n = await selectedCount(ui);
        await ui.assert(n === nAcc, `内屏竖 ${w} · 库:「${n} 个已选」= 未归档账户数`, `库 ${nAcc}`);
      }, { app: true });
    }

    // ── 5 · 折叠往返 ─────────────────────────────────────────────────
    report.section('5 · 折叠往返(主屏 App · 不刷新):外屏竖 → 内屏横 → 内屏竖');
    await ui.device(...OUTER_P, async (ui) => {
      await ui.goto('/dashboard');
      await ui.page.evaluate(() => { const e = document.getElementById('dash-cashflow'); window.scrollTo(0, e.getBoundingClientRect().top + scrollY - 60); });
      await ui.page.waitForTimeout(700);
      const top0 = await ui.page.evaluate(() => Math.round(document.getElementById('dash-cashflow').getBoundingClientRect().top));
      for (const [vp, rail, label] of [[INNER_L, true, '打开到内屏横'], [INNER_P, false, '转成内屏竖']]) {
        await ui.page.setViewportSize({ width: vp[0], height: vp[1] });
        await ui.page.waitForTimeout(1500);
        const s = await shape(ui);
        await ui.assert(s.rail === rail && s.vpRail === rail, `${label}:侧边栏${rail ? '还在右边' : '收回成顶栏'}`, JSON.stringify(s));
        await ui.assert(s.over <= 1, `${label}:不横向滚动`, `多出 ${s.over}px`);
        const top = await ui.page.evaluate(() => Math.round(document.getElementById('dash-cashflow').getBoundingClientRect().top));
        await ui.assert(Math.abs(top - top0) <= 40, `${label}:「人赚 vs 钱赚」还在原处`, `${top0} → ${top}px`);
      }
      const n = await selectedCount(ui);
      await ui.assert(n === nAcc, `库:「${n} 个已选」= 未归档账户数`, `库 ${nAcc}`);
    }, { app: true });

    // ── 6 · 同样尺寸在浏览器里 ────────────────────────────────────────
    report.section('6 · 浏览器里(不是主屏 App):不出侧边栏;内屏横折痕区照样生效');
    await ui.device(...OUTER_P, async (ui) => {
      await ui.goto('/dashboard');
      const s = await expectNoRail(ui, '浏览器 · 外屏竖');
      await ui.assert(s.hdrPos === 'sticky', '浏览器 · 外屏竖:页头照旧吸顶', JSON.stringify(s));
    });
    await ui.device(...INNER_L, async (ui) => {
      await ui.goto('/dashboard');
      const s = await expectNoRail(ui, '浏览器 · 内屏横');
      await ui.assert(s.vpFold, '浏览器 · 内屏横:折痕区照样生效', JSON.stringify(s));
      await toCategories(ui);
      await dialogRightHalf(ui, '浏览器 · 内屏横');
    });

    // ── 7 · 普通设备 ─────────────────────────────────────────────────
    report.section('7 · 普通设备:没有侧边栏和折痕区;普通 iPhone 横屏照旧冻结旋转');
    for (const [name, w, h] of [['iPhone 主屏 App', 390, 844], ['iPhone SE 主屏 App', 375, 667], ['iPhone Pro Max 主屏 App', 440, 956],
                                ['iPad 竖 主屏 App', 820, 1180], ['iPad 横 主屏 App', 1180, 820]]) {
      await ui.device(w, h, async (ui) => {
        await ui.goto('/dashboard');
        const s = await expectNoRail(ui, name);
        await ui.assert(!s.vpFold, `${name}:没有折痕区`, JSON.stringify(s));
      }, { app: true });
    }
    /* 竖着登录再转横:冻结旋转下整页被转了 90°,无头浏览器点不中被旋转的登录按钮(真人转着手机照样能点) */
    await ui.device(390, 844, async (ui) => {
      await ui.goto('/dashboard');
      await ui.page.setViewportSize({ width: 844, height: 390 });
      await ui.page.waitForTimeout(1200);
      const s = await shape(ui);
      await ui.assert(s.freeze && !s.vpRail, 'iPhone 横屏(主屏 App 844×390):照旧冻结旋转,没有侧边栏', JSON.stringify(s));
    }, { app: true });
    await ui.goto('/dashboard');
    const pc = await shape(ui);
    await ui.assert(!pc.rail && !pc.vpRail && !pc.vpFold, '电脑 1440:没有侧边栏和折痕区', JSON.stringify(pc));
    const n = await selectedCount(ui);
    await ui.assert(n === nAcc, `电脑 · 库:「${n} 个已选」= 未归档账户数`, `库 ${nAcc}`);
  },

  async cleanup() {},
};
