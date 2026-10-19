/**
 * flow · v1.35 · UI 精修(维护者 2026-10-19)
 *
 *   1  顶栏「填报」→ 华泰证券-A股那张账户卡:账户名、「本月已校准 · MM-dd」标签、类型字样三者的
 *      竖直中线对齐(原来按基线对齐,带框的 9px 标签比 15px 的账户名低 3px)
 *        → 库:这个账户本期确实有已提交的余额(标签该出现)
 *
 * 只读,不动数据。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const ACC = 3;                              // 华泰证券-A股 · STOCK

module.exports = {
  name: '53-ui-refresh',
  title: 'v1.35 · UI 精修 · 填报卡账户名与「已校准」标签竖直对齐',

  async run(ui, report) {
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
  },
};
