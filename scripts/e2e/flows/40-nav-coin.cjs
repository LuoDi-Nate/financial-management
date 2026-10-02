/**
 * flow · v1.28.5 · issue #31 · 顶栏左上角「№ + 家庭名首字」换成古钱小图标
 *
 * 提交者以为「№ 我」是家庭名显示不下被截断了。维护者定:换成一个和钱有关的小图标,家庭名放到悬停 / 读屏里。
 * 电脑、手机各看一遍:看不到「№」,看得到图标,图标的 title / aria-label 是完整家庭名;品牌名、版本徽记不受影响。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

async function check(ui, label) {
  await ui.goto('/');
  await ui.rendered(`${label} · 首页`);
  const fam = db.one(`SELECT name FROM family WHERE id=${fx.FAM}`);
  const r = await ui.page.evaluate(() => {
    const bt = document.querySelector('header .nav-brandtext');
    const coin = bt && bt.querySelector('svg.nav-coin');
    const b = coin && coin.getBoundingClientRect();
    return bt ? { text: bt.innerText, coin: !!coin, w: b && Math.round(b.width), aria: coin && coin.getAttribute('aria-label'),
                  title: coin && coin.querySelector('title') && coin.querySelector('title').textContent,
                  ver: !!bt.querySelector('.ver-badge') } : null;
  });
  await ui.assert(!!r && !/№/.test(r.text), `${label}:左上角不再出现「№」`, JSON.stringify(r));
  await ui.assert(!!r && r.coin && r.w >= 12, `${label}:品牌名旁边是古钱小图标`, JSON.stringify(r));
  await ui.assert(!!r && r.aria === fam && r.title === fam, `${label}:悬停 / 读屏看得到完整家庭名「${fam}」`, JSON.stringify(r));
  await ui.assert(!!r && r.ver, `${label}:版本徽记还在`);
}

module.exports = {
  name: '40-nav-coin',
  title: 'v1.28.5 · issue #31 · 左上角「№ + 首字」换成古钱小图标 · 家庭名放进悬停',

  async run(ui, report) {
    ui.flow = this.name;
    report.section('1 · 电脑');
    await check(ui, '电脑');
    report.section('2 · 手机(390px)');
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await check(ui, '手机');
    await ui.page.setViewportSize({ width: 1440, height: 900 });
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui) { await ui.page.setViewportSize({ width: 1440, height: 900 }).catch(() => {}); },
};
