/**
 * flow · v1.35 · UI 精修(维护者 2026-10-19)
 *
 *   1  顶栏「填报」→ 华泰证券-A股那张账户卡:账户名、「本月已校准 · MM-dd」标签、类型字样三者的
 *      竖直中线对齐(原来按基线对齐,带框的 9px 标签比 15px 的账户名低 3px)
 *        → 库:这个账户本期确实有已提交的余额(标签该出现)
 *   2  从顶栏逐个点进 仪表盘 / 填报 / 账户 / 报表 / 管理 → 周期:页面上每个按钮(btn-*)实测
 *      不大写、不加字距、不是等宽字体、圆角 6px、高度落在 28 / 32 两档(填报页写了 h-9 的输入行按钮 36)
 *        → 库:管理 → 周期页「关闭 / 重新打开」按钮数 == 库里的账期行数里有操作的那些(危险样式 btn-danger)
 *   3  账户 → 富途 test → 持仓管理:顶部可见按钮 ≤3(主按钮 +「添加其他 ▾」+ 手动刷价);
 *      点「添加其他」→ 菜单出现 → 点「手填市值」→ 落到手填市值添加页(不提交);
 *      每张持仓卡的操作按钮排在一行、同高
 *        → 库:页面上的持仓卡张数 == 库里这个账户未归档的持仓数
 *   4  真手机(390 触屏):持仓页按钮默认 ≥36、小档 ≥32,不撑出横向滚动(同样对库里持仓数)
 *
 * 只读,不动数据。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const ACC = 3;                              // 华泰证券-A股 · STOCK
const HOLD_ACC = 20;                        // 富途 test · STOCK · CNY(有两只场外基金)

/** 页面上所有看得见的按钮:实测样式 */
async function btnSpecs(ui) {
  return ui.page.evaluate(() => [...document.querySelectorAll('.btn-ink,.btn-paper,.btn-ghost,.btn-brass,.btn-danger')]
    .filter(e => { const r = e.getBoundingClientRect(); const cs = getComputedStyle(e); return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden'; })
    .map(e => { const cs = getComputedStyle(e); const r = e.getBoundingClientRect();
      return { t: (e.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 14), h: Math.round(r.height), tt: cs.textTransform,
               ls: parseFloat(cs.letterSpacing) || 0, ff: cs.fontFamily.split(',')[0], rad: cs.borderTopLeftRadius }; }));
}

module.exports = {
  name: '53-ui-refresh',
  title: 'v1.35 · UI 精修 · 填报卡账户名与「已校准」标签竖直对齐',

  async run(ui, report) {
    ui.flow = this.name;
    const pid = fx.currentPeriod();
    const submitted = db.one(`SELECT DATE_FORMAT(s.submitted_at, '%m-%d') FROM period_snapshot s JOIN period p ON p.id=s.period_id
      WHERE p.family_id=${fx.FAM} AND s.period_id=${pid} AND s.account_id=${ACC} AND s.submitted_at IS NOT NULL`);
    await ui.assert(!!submitted, '前置:华泰证券-A股本期有已提交的余额(卡上该出现「本月已校准」)', `period=${pid}`);

    await ui.click('header a[href="/entry"] >> nth=0', '顶栏点「填报」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const head = `#entry-block-${ACC} .acct-head`;
    await ui.visible(`${head} [data-calibrated]`, '华泰证券-A股卡上有「本月已校准」标签');
    await ui.seesText(`本月已校准 · ${submitted}`, '标签写的日期 == 库里的提交日期');

    const mids = await ui.page.locator(`${head} > *`).evaluateAll(els => els
      .filter(e => e.getBoundingClientRect().height > 0)
      .map(e => { const r = e.getBoundingClientRect(); return { t: e.textContent.trim().slice(0, 8), mid: (r.top + r.bottom) / 2 }; }));
    const spread = Math.max(...mids.map(m => m.mid)) - Math.min(...mids.map(m => m.mid));
    await ui.assert(mids.length >= 3 && spread <= 1.5, '账户名 / 已校准标签 / 类型 三者竖直居中对齐(差 ≤1.5px)',
      mids.map(m => `${m.t}@${m.mid.toFixed(1)}`).join(' · '));

    // ── 2 · 各页按钮实测 ─────────────────────────────────────────────
    report.section('2 · 顶栏逐页点进去:按钮不大写 · 不加字距 · 无衬线 · 圆角 6 · 高度 28–36(默认 32)');
    for (const [href, label] of [['/dashboard', '仪表盘'], ['/entry', '填报'], ['/accounts', '账户'], ['/reports', '报表'], ['/admin', '管理']]) {
      await ui.click(`header a[href="${href}"] >> nth=0`, `顶栏点「${label}」`);
      await ui.page.waitForLoadState('networkidle').catch(() => {});
      const b = await btnSpecs(ui);
      const bad = b.filter(x => x.tt !== 'none' || x.ls > 0.5 || /Mono/.test(x.ff) || x.rad !== '6px');
      // 档位:小档 28 · 默认 32;模板里给按钮写了 h-9 / py-* 的(填报页输入行要和输入框齐高)在 28–36 之间。
      // 卡住的是回退:旧规格是 42px,任何 >37 的都算
      const badH = b.filter(x => x.h < 27 || x.h > 37);
      const plain = await ui.page.evaluate(() => [...document.querySelectorAll('.btn-ink, .btn-paper')]
        .filter(e => e.getBoundingClientRect().height > 0 && !/(^| )(text-|h-|py-|p-|btn-lg|btn-sm|w-full)/.test(e.className))
        .map(e => Math.round(e.getBoundingClientRect().height)));
      await ui.assert(b.length > 0 && bad.length === 0, `${label}页 ${b.length} 个按钮:不大写 · 不加字距 · 无衬线 · 圆角 6px`,
        bad.slice(0, 4).map(x => `${x.t}[${x.tt}/${x.ls}/${x.ff}/${x.rad}]`).join(' · ') || `${b.length} 个`);
      await ui.assert(badH.length === 0, `${label}页按钮高度都在 28–36 之间(旧规格是 42)`,
        badH.slice(0, 4).map(x => `${x.t}=${x.h}`).join(' · ') || [...new Set(b.map(x => x.h))].sort().join('/'));
      await ui.assert(plain.every(h => h === 32), `${label}页没加尺寸修饰的按钮都是默认 32px`, plain.join('/') || '无');
    }
    await ui.click('a[href="/admin/periods"] >> nth=0', '管理页侧栏点「周期」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const dangerOnPage = await ui.page.locator('form[action$="/reopen"] button.btn-danger, form[action$="/force-close"] button.btn-danger').count();
    const plainOnPage = await ui.page.locator('form[action$="/reopen"] button:not(.btn-danger), form[action$="/force-close"] button:not(.btn-danger)').count();
    const closedOrOpen = db.num(`SELECT COUNT(*) FROM period WHERE family_id=${fx.FAM} AND status IN ('OPEN','CLOSED')`);
    await ui.assert(dangerOnPage > 0 && plainOnPage === 0, '周期页「重新打开 / 关闭账期」都是危险样式(btn-danger)', `危险 ${dangerOnPage} · 普通 ${plainOnPage}`);
    await ui.assert(closedOrOpen >= dangerOnPage, '真值层:危险按钮数不超过库里可开关的账期数', `按钮 ${dangerOnPage} · 账期 ${closedOrOpen}`);

    // ── 3 · 持仓页:按钮收纳 ──────────────────────────────────────────
    report.section('3 · 持仓页:顶部 ≤3 个按钮 ·「添加其他 ▾」菜单 · 持仓卡按钮一排');
    await ui.click('header a[href="/accounts"] >> nth=0', '顶栏点「账户」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.goto(`/accounts/${HOLD_ACC}/holdings`);
    const top = await ui.page.locator('section.hold-actions > a, section.hold-actions > details > summary, section.hold-actions > form > button')
      .evaluateAll(els => els.filter(e => e.getBoundingClientRect().height > 0).map(e => e.textContent.trim().replace(/\s+/g, ' ')));
    await ui.assert(top.length <= 3, '持仓页顶部可见按钮 ≤3', top.join(' / '));
    await ui.visible('details[data-add-more] > summary', '有「添加其他」');
    await ui.click('details[data-add-more] > summary', '点「添加其他」');
    await ui.visible('details[data-add-more] .row-more-pop', '菜单展开');
    await ui.seesText('手填市值', '菜单里有「手填市值」');
    await ui.page.mouse.click(5, 300);
    await ui.page.waitForTimeout(200);
    await ui.assert(!(await ui.page.locator('details[data-add-more]').evaluate(d => d.open)), '点菜单外面,菜单收起');
    await ui.click('details[data-add-more] > summary', '再点「添加其他」');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('details[data-add-more] a[href$="/holdings/new-manual"]', '菜单里点「手填市值」')]);
    await ui.assert(/\/holdings\/new-manual$/.test(ui.page.url()), '落到「手填市值」添加页', ui.page.url());
    await ui.visible(`form[action$="/holdings/new-manual"]`, '添加表单在');
    await ui.goto(`/accounts/${HOLD_ACC}/holdings`);
    const cards = await ui.page.locator('main article.paper-card').count();
    const inDb = db.num(`SELECT COUNT(*) FROM stock_holding WHERE account_id=${HOLD_ACC} AND archived_at IS NULL`);
    await ui.assert(cards === inDb, '真值层:持仓卡张数 == 库里这个账户未归档的持仓数', `页面 ${cards} · 库 ${inDb}`);
    const rows = await ui.page.locator('main article.paper-card .hold-ops').evaluateAll(ops => ops.map(o =>
      [...o.querySelectorAll(':scope > details > summary, :scope > form > button, :scope > a')]
        .filter(e => e.getBoundingClientRect().height > 0)
        .map(e => { const r = e.getBoundingClientRect(); return [Math.round(r.top), Math.round(r.height)]; })));
    const oneRow = rows.every(r => r.length === 0 || (new Set(r.map(x => x[0])).size === 1 && new Set(r.map(x => x[1])).size === 1));
    await ui.assert(rows.length > 0 && oneRow, '每张持仓卡的操作按钮排在一行、同高', JSON.stringify(rows.slice(0, 2)));
    await ui.noConsoleErrors('控制台无报错');

    // ── 4 · 真手机:点按区域升一档 ───────────────────────────────────
    report.section('4 · 真手机 390px 触屏:持仓页按钮 默认 ≥36 · 小档 ≥32 · 不撑出横向滚动');
    await ui.phone(async (m) => {
      await m.goto('/dashboard');
      await m.click('header details > summary:visible', '点右上角菜单');
      await m.click('header details[open] a[href="/accounts"]:visible', '菜单里点「账户」');
      await m.page.waitForLoadState('networkidle').catch(() => {});
      await m.goto(`/accounts/${HOLD_ACC}/holdings`);
      const hs = await m.page.evaluate(() => [...document.querySelectorAll('main .btn-ink, main .btn-paper, main .btn-danger, main .btn-ghost')]
        .filter(e => e.getBoundingClientRect().height > 0)
        .map(e => ({ small: /(^| )(text-xs|text-\[1[012]px\])( |$)/.test(e.className), h: Math.round(e.getBoundingClientRect().height) })));
      const low = hs.filter(x => x.h < (x.small ? 32 : 36));
      await m.assert(hs.length > 0 && low.length === 0, `手机上持仓页 ${hs.length} 个按钮:默认 ≥36 · 小档 ≥32`, low.map(x => x.h).join('/') || '全部达标');
      const over = await m.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      await m.assert(over <= 1, '手机上持仓页没有横向滚动', `溢出 ${over}px`);
      const n = db.num(`SELECT COUNT(*) FROM stock_holding WHERE account_id=${HOLD_ACC} AND archived_at IS NULL`);
      await m.assert(await m.page.locator('main article.paper-card').count() === n, '真值层:手机上持仓卡张数 == 库里未归档持仓数', String(n));
    });
  },

  async cleanup(ui, report) {
    report.info('只读 flow:没有改任何数据,终态 = 开跑前');
  },
};
