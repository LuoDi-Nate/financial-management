/**
 * flow · v1.34 · 持仓调整顺序(issue #40)+ 持仓改名(issue #41)
 *
 *   1  账户 → 富途证券-港美股 → 持仓管理:有「调整顺序」;顺序 = 添加先后(没排过)
 *   2  调整顺序:第 3 只「置顶」、第 2 只「↓」→ 提示「顺序改了」→ 保存 → 持仓页按新顺序 · 库里 sort_order = 1..n
 *   3  填报页「股票收入」选这个账户:联动出来的持仓同一个顺序
 *   4  管理 → 资产打标:这个账户下的持仓同一个顺序
 *   5  再加一只手填持仓 → 排在最后,其余不动(库里新的那只 sort_order 为空)
 *   6  卡片上「改名」→ 页面、库、填报页股票收入都是新名字;其它列一个不动
 *   7  手机(390 · 触屏):调整顺序页不横向溢出,↑ ↓ 按钮够大
 *
 * cleanup:删掉新加的那只,三只原有持仓的名字与 sort_order 还原(sort_order 回到空 = 没排过)。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const ACC = 4;                         // 富途证券-港美股 · STOCK
const NEW_NAME = 'e2e51 新持仓';
const RENAMED = '苹果(e2e51 改名)';
const state = {};

const activeIds = () => db.col(`SELECT id FROM stock_holding WHERE account_id=${ACC} AND archived_at IS NULL ORDER BY id`).map(Number);
const nameOf = (id) => db.one(`SELECT display_name FROM stock_holding WHERE id=${id}`);
/** 持仓页上卡片的顺序(按名字) */
async function cardNames(ui) {
  return ui.page.evaluate(() => [...document.querySelectorAll('main article')]
    .map(a => (a.querySelector('.font-display.text-xl')?.textContent || '').replace(/\s+/g, ' ').trim()));
}

module.exports = {
  name: '51-holding-order-rename',
  title: 'v1.34 · 持仓调整顺序(#40)· 持仓改名(#41)',

  async run(ui, report) {
    ui.flow = this.name;
    state.ids = activeIds();
    state.names = Object.fromEntries(state.ids.map(id => [id, nameOf(id)]));
    state.cols = Object.fromEntries(state.ids.map(id => [id, db.one(`SELECT CONCAT_WS('|', shares, manual_value, valuation_mode, ticker, industry_tag) FROM stock_holding WHERE id=${id}`)]));
    state.maxId = db.num(`SELECT COALESCE(MAX(id),0) FROM stock_holding`);
    // 加一只手填持仓会触发估值刷新,改到这个账户本期的余额 —— 记下来,cleanup 原样放回
    state.pid = Number(fx.currentPeriod());
    state.bal = db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${ACC}`);
    state.maxSve = db.num(`SELECT COALESCE(MAX(id),0) FROM stock_valuation_event`);
    state.maxHse = db.num(`SELECT COALESCE(MAX(id),0) FROM holding_share_event`);
    await ui.assert(state.ids.length >= 3, `前置:账户 #${ACC} 至少 3 只在用持仓`, state.ids.join(','));
    if (state.ids.length < 3) return;
    const [a, b, c] = state.ids;
    report.info(`持仓(按添加先后):${state.ids.map(id => state.names[id]).join(' / ')}`);

    // ── 1 · 持仓页 ───────────────────────────────────────────────────────
    report.section('1 · 账户 → 持仓管理:有「调整顺序」,没排过时按添加先后');
    await ui.goto('/dashboard');
    await ui.click('header a[href="/accounts"] >> nth=0', '顶栏点「账户」');
    await ui.click(`main a[href="/accounts/${ACC}"] >> nth=0`, '账户簿点「富途证券-港美股」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click(`a[href="/accounts/${ACC}/holdings"] >> nth=0`, '账户详情点「持仓管理」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const before = await cardNames(ui);
    await ui.assert(JSON.stringify(before.slice(0, 3)) === JSON.stringify([a, b, c].map(id => state.names[id])),
      '没排过:按添加先后', before.join(' / '));
    await ui.visible('[data-holding-order]', '操作栏有「调整顺序」');

    // ── 2 · 调整顺序 ─────────────────────────────────────────────────────
    report.section('2 · 调整顺序:第 3 只置顶 · 第 2 只下移 → 保存');
    await ui.click('[data-holding-order]', '点「调整顺序」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.rendered('调整顺序页');
    await ui.seesText('还没动过', '一开始提示「还没动过」');
    await ui.click(`[data-ho-row][data-id="${c}"] [data-ho-top]`, `「${state.names[c]}」点「置顶」`);
    await ui.click(`[data-ho-row][data-id="${a}"] [data-ho-down]`, `「${state.names[a]}」点「↓」`);
    const shown = await ui.page.evaluate(() => [...document.querySelectorAll('[data-ho-row]')].map(r => Number(r.dataset.id)));
    const want = [c, b, a, ...state.ids.slice(3)];
    await ui.assert(JSON.stringify(shown) === JSON.stringify(want), '页面上的顺序:第 3 只在最前、原第 1 只到第 3', shown.join(','));
    await ui.seesText('顺序改了', '提示「顺序改了 · 点「保存顺序」才生效」');
    await ui.submit('[data-ho-save]', '点「保存顺序」');
    await ui.seesText('已保存顺序', '回到持仓页:「已保存顺序 —— 填报页、截图导入也按这个顺序列」');
    const after = await cardNames(ui);
    await ui.assert(JSON.stringify(after.slice(0, 3)) === JSON.stringify([c, b, a].map(id => state.names[id])), '持仓页按新顺序', after.join(' / '));
    const so = state.ids.map(id => `${id}:${db.one(`SELECT sort_order FROM stock_holding WHERE id=${id}`)}`);
    await ui.assert(db.one(`SELECT sort_order FROM stock_holding WHERE id=${c}`) === '1' && db.one(`SELECT sort_order FROM stock_holding WHERE id=${b}`) === '2'
        && db.one(`SELECT sort_order FROM stock_holding WHERE id=${a}`) === '3', '真值层:sort_order 依次 1 / 2 / 3', so.join(' '));

    // ── 3 · 填报页股票收入 ───────────────────────────────────────────────
    report.section('3 · 填报页「股票收入」选这个账户:同一个顺序');
    await ui.click('header a[href="/entry"] >> nth=0', '顶栏点「填报」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click('#income-cash-block .tab-stock', '点「股票收入」');
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes('/entry/income/stock/holdings'), { timeout: 20000 }).catch(() => null),
      ui.choose('accountId', ACC, '#income-stock-block'),
    ]);
    await ui.page.waitForTimeout(800);
    const inc = await ui.page.evaluate(() => [...document.querySelectorAll('#stock-holdings-target .font-display')].map(e => e.textContent.trim()));
    const incOrder = inc.filter(n => [b, a].map(id => state.names[id]).includes(n));
    await ui.assert(JSON.stringify(incOrder) === JSON.stringify([b, a].map(id => state.names[id])), '股票收入联动出的持仓:同一个顺序', inc.join(' / '));

    // ── 4 · 资产打标 ─────────────────────────────────────────────────────
    report.section('4 · 管理 → 资产打标:这个账户下的持仓同一个顺序');
    await ui.click('header a[href="/admin"] >> nth=0', '顶栏点「管理」');
    await ui.click('main a[href="/lens/tags"] >> nth=0', '总览点「资产打标」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const tagIds = await ui.page.evaluate(() => [...document.querySelectorAll('select[name^="hold_industry_"]')].map(s => Number(s.name.replace('hold_industry_', ''))));
    const tagOrder = tagIds.filter(id => [a, b, c].includes(id));
    const wantTag = [c, b, a].filter(id => tagIds.includes(id));
    await ui.assert(tagOrder.length >= 2 && JSON.stringify(tagOrder) === JSON.stringify(wantTag), '资产打标页:同一个顺序(现金行在上面单列,不参与)', tagOrder.join(','));

    // ── 5 · 新加一只排在最后 ─────────────────────────────────────────────
    report.section('5 · 再加一只手填持仓 → 排在最后,其余不动');
    await ui.goto(`/accounts/${ACC}/holdings`);
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('main a[href$="/holdings/new-manual"]', '点「+ 添加持仓 · 手填市值」')]);
    await ui.fill('input[name="displayName"]', NEW_NAME, `名称「${NEW_NAME}」`);
    await ui.fill('input[name="shares"]', 1, '股数 1');
    await ui.fill('input[name="unitValue"]', 100, '单股 100');
    await ui.submit('form[action$="/holdings/new-manual"] button[type=submit]', '点「添加」');
    state.newId = db.num(`SELECT COALESCE(MAX(id),0) FROM stock_holding WHERE account_id=${ACC} AND display_name='${NEW_NAME}'`);
    const after5 = await cardNames(ui);
    await ui.assert(after5[after5.length - 1] === NEW_NAME && JSON.stringify(after5.slice(0, 3)) === JSON.stringify([c, b, a].map(id => state.names[id])),
      '新加的在最后,排好的三只不动', after5.join(' / '));
    await ui.assert(db.one(`SELECT COALESCE(sort_order,'NULL') FROM stock_holding WHERE id=${state.newId}`) === 'NULL', '真值层:新的那只 sort_order 为空');

    // ── 6 · 改名 ─────────────────────────────────────────────────────────
    report.section('6 · 卡片上「改名」→ 页面 / 库 / 填报页都是新名字,其它列不动');
    const card = `main article:has(form[action$="/holdings/${b}/rename"])`;
    await ui.click(`${card} details[data-hold-rename] > summary`, `「${state.names[b]}」点「改名」`);
    await ui.fill(`${card} [data-hold-rename-input]`, `  ${RENAMED}  `, `输入新名字(前后带空格)`);
    await ui.submit(`${card} form[action$="/rename"] button[type=submit]`, '点「保存」');
    await ui.seesText(`已改名:「${state.names[b]}」→「${RENAMED}」`, '提示「已改名:旧 → 新」');
    await ui.assert(nameOf(b) === RENAMED, '真值层:display_name 改了(前后空格去掉)', nameOf(b));
    await ui.assert(db.one(`SELECT CONCAT_WS('|', shares, manual_value, valuation_mode, ticker, industry_tag) FROM stock_holding WHERE id=${b}`) === state.cols[b],
      '真值层:份额 / 估值 / 代码 / 行业标一列没动');
    await ui.assert((await cardNames(ui))[1] === RENAMED, '持仓页卡片上是新名字,位置不变');
    await ui.click('header a[href="/entry"] >> nth=0', '顶栏点「填报」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click('#income-cash-block .tab-stock', '点「股票收入」');
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes('/entry/income/stock/holdings'), { timeout: 20000 }).catch(() => null),
      ui.choose('accountId', ACC, '#income-stock-block'),
    ]);
    await ui.page.waitForTimeout(800);
    await ui.seesText(RENAMED, '填报页股票收入里也是新名字');

    // ── 7 · 手机 ─────────────────────────────────────────────────────────
    report.section('7 · 手机(390 · 触屏):调整顺序页不横向溢出,↑ ↓ 按钮够大');
    await ui.phone(async (m) => {
      await m.goto(`/accounts/${ACC}/holdings`);
      await m.click('[data-holding-order]', '手机持仓页点「调整顺序」');
      await m.page.waitForLoadState('networkidle').catch(() => {});
      const over = await m.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      await m.assert(over <= 1, '调整顺序页没有横向溢出', `${over}px`);
      const sz = await m.page.locator('[data-ho-up]').nth(1).evaluate(e => { const r = e.getBoundingClientRect(); return [r.width, r.height]; });
      await m.assert(sz[0] >= 40 && sz[1] >= 40, '↑ 按钮 ≥ 40×40', sz.join('×'));
      await m.page.screenshot({ path: '/tmp/e2e-51-order-mobile.png', fullPage: false }).catch(() => {});
    });
    await ui.noConsoleErrors('这些页面控制台无报错');
  },

  async cleanup(ui, report) {
    if (state.newId) {
      for (const sql of [`DELETE FROM holding_share_event WHERE holding_id=${state.newId}`,
                         `DELETE FROM stock_holding WHERE id=${state.newId} AND account_id=${ACC} AND display_name='${NEW_NAME}'`]) {
        try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 60)} · ${String(e.message).slice(0, 80)}`); }
      }
    }
    if (state.maxSve != null) db.raw(`DELETE FROM stock_valuation_event WHERE id > ${state.maxSve} AND account_id=${ACC}`);
    if (state.maxHse != null) db.raw(`DELETE FROM holding_share_event WHERE id > ${state.maxHse} AND account_id=${ACC}`);
    if (state.pid) {
      if (state.bal == null || state.bal === '') db.raw(`DELETE FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${ACC}`);
      else db.raw(`UPDATE period_snapshot SET end_balance=${state.bal} WHERE period_id=${state.pid} AND account_id=${ACC}`);
    }
    for (const [id, n] of Object.entries(state.names || {})) {
      db.raw(`UPDATE stock_holding SET display_name='${n.replace(/'/g, "''")}', sort_order=NULL WHERE id=${id} AND account_id=${ACC}`);
    }
    // 截图导入 / 估值刷新可能记了这只新持仓的流水 —— 只删本 flow 造的那只的
    const left = db.num(`SELECT COUNT(*) FROM stock_holding WHERE account_id=${ACC} AND display_name='${NEW_NAME}'`);
    const so = db.num(`SELECT COUNT(*) FROM stock_holding WHERE account_id=${ACC} AND sort_order IS NOT NULL`);
    const bal = db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${ACC}`);
    await ui.assert(left === 0 && so === 0 && bal === state.bal, '还原:新加的删掉 · 名字与顺序回到没排过 · 本期余额回到原值',
      `残留 ${left} · 有顺序 ${so} · 余额 ${bal} / ${state.bal}`);
  },
};
