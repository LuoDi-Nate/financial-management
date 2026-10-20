/**
 * flow · v1.35.1 · 品牌名旁的小图标可以自己选(维护者 2026-10-20:「允许那个存钱罐的小 icon 被用户定制,入口放在和网页 icon 的入口里」)
 *
 *   1  顶栏「管理」→ 侧栏「家庭设置」→「品牌标识」那一块里有小图标选择格(47 个 · 分组 · 同尺寸),当前选中的是存钱罐
 *        → 库:family.brand_mark 是 NULL(从没选过 = 存钱罐)
 *   2  点「猫」→ 预览和顶栏立刻换成猫、提示「已换成「猫」」;刷新后还是猫;点顶栏「仪表盘」过去还是猫,悬停仍是家庭名
 *        → 库:brand_mark = 'cat'
 *      顶栏里猫的竖直偏移 = 它自己量出来的 -1.5px(不是存钱罐的 -2px)
 *   3  再点「存钱罐」→ 换回来 → 库:brand_mark 回到 NULL
 *
 * cleanup:brand_mark 置回 NULL(终态 = 存钱罐)。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const mark = () => db.one(`SELECT IFNULL(brand_mark, 'NULL') FROM family WHERE id=${fx.FAM}`);

async function navMark(ui) {
  return ui.page.evaluate(() => {
    const svg = document.querySelector('header svg.nav-coin');
    if (!svg) return null;
    const t = svg.querySelector('title');
    return { code: svg.getAttribute('data-brand-mark'), dy: new DOMMatrix(getComputedStyle(svg).transform).f,
             title: t && t.textContent, paths: svg.querySelectorAll('path,circle,rect,line,polyline,ellipse').length };
  });
}

module.exports = {
  name: '54-brand-mark',
  title: 'v1.35.1 · 品牌名旁的小图标可以自己选(家庭设置 · 品牌标识)',

  async run(ui, report) {
    ui.flow = this.name;
    const fam = db.one(`SELECT name FROM family WHERE id=${fx.FAM}`);
    db.raw(`UPDATE family SET brand_mark=NULL WHERE id=${fx.FAM}`);   // 前置:从没选过

    report.section('1 · 管理 → 家庭设置 → 品牌标识:选择格');
    await ui.click('header a[href="/admin"] >> nth=0', '顶栏点「管理」');
    await ui.click('a[href="/admin/family"] >> nth=0', '侧栏点「家庭设置」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.visible('[data-brand-mark-picker]', '品牌标识里有「品牌名旁的小图标」');
    const n = await ui.page.locator('[data-brand-mark-picker] .bm-opt').count();
    await ui.assert(n >= 20 && n <= 50, `可选图标 ${n} 个(20–50)`, String(n));
    await ui.sameSize('[data-brand-mark-picker] .bm-opt', '选择格同尺寸');
    await ui.assert(await ui.page.locator('[data-brand-mark-picker] .bm-opt[aria-pressed="true"]').getAttribute('data-mark') === 'piggy-bank',
      '当前选中的是存钱罐');
    await ui.assert(mark() === 'NULL', '真值层:family.brand_mark 是 NULL(没选过)');

    report.section('2 · 点「猫」→ 顶栏立刻换 · 刷新还在 · 换页还在');
    await ui.click('[data-brand-mark-picker] .bm-opt[data-mark="cat"]', '点「猫」');
    await ui.page.waitForFunction(() => /已换成/.test(document.querySelector('[data-bm-status]').textContent), null, { timeout: 8000 }).catch(() => {});
    await ui.seesText('已换成「猫」', '提示已换成猫');
    let m = await navMark(ui);
    await ui.assert(m && m.code === 'cat' && m.dy === -1.5 && m.title === fam, '顶栏立刻换成猫 · 偏移 -1.5px · 悬停仍是家庭名', JSON.stringify(m));
    await ui.assert(mark() === 'cat', '真值层:brand_mark = cat', mark());
    await ui.page.reload({ waitUntil: 'networkidle' });
    m = await navMark(ui);
    await ui.assert(m && m.code === 'cat' && m.paths > 0, '刷新后顶栏还是猫(服务端按库渲染)', JSON.stringify(m));
    await ui.assert(await ui.page.locator('[data-brand-mark-picker] .bm-opt[aria-pressed="true"]').getAttribute('data-mark') === 'cat', '选择格里选中的是猫');
    await ui.click('header a[href="/dashboard"] >> nth=0', '顶栏点「仪表盘」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    m = await navMark(ui);
    await ui.assert(m && m.code === 'cat' && m.title === fam, '换到仪表盘,顶栏还是猫', JSON.stringify(m));

    report.section('3 · 换回存钱罐');
    await ui.click('header a[href="/admin"] >> nth=0', '顶栏点「管理」');
    await ui.click('a[href="/admin/family"] >> nth=0', '侧栏点「家庭设置」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click('[data-brand-mark-picker] .bm-opt[data-mark="piggy-bank"]', '点「存钱罐」');
    await ui.page.waitForFunction(() => /存钱罐/.test(document.querySelector('[data-bm-status]').textContent), null, { timeout: 8000 }).catch(() => {});
    m = await navMark(ui);
    await ui.assert(m && m.code === 'piggy-bank' && m.dy === -2, '顶栏换回存钱罐 · 偏移 -2px', JSON.stringify(m));
    await ui.assert(mark() === 'NULL', '真值层:选回默认存的是 NULL(和没选过一样)', mark());
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    db.raw(`UPDATE family SET brand_mark=NULL WHERE id=${fx.FAM}`);
    await ui.assert(mark() === 'NULL', '还原:brand_mark 回到 NULL(存钱罐)');
  },
};
