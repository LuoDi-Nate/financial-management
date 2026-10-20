/**
 * flow · v1.28.5 · issue #31 · 顶栏左上角「№ + 家庭名首字」换成古钱小图标
 *
 * 提交者以为「№ 我」是家庭名显示不下被截断了。维护者定:换成一个和钱有关的小图标,家庭名放到悬停 / 读屏里。
 * 电脑、手机各看一遍:看不到「№」,看得到图标,图标的 title / aria-label 是完整家庭名;品牌名、版本徽记不受影响。
 * v1.35 · 古钱换成存钱罐(维护者 2026-10-19:「好丑,没对齐」):图标是存钱罐的路径、18px、上移 2px 与「账房」字身中线对齐。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

/* v1.35.1 起小图标可以在家庭设置里换。这条守的是「默认是存钱罐」,所以开跑前先置回默认,
   cleanup 再还原成开跑前的选择 —— 不能把维护者在 beta 上自己选的图标冲掉(v1.36 全量跑时撞上:beta 选了钱包,这里红了)。 */
const state = {};
const markNow = () => db.one(`SELECT IFNULL(brand_mark, 'NULL') FROM family WHERE id=${fx.FAM}`);

async function check(ui, label) {
  await ui.goto('/');
  await ui.rendered(`${label} · 首页`);
  const fam = db.one(`SELECT name FROM family WHERE id=${fx.FAM}`);
  const r = await ui.page.evaluate(() => {
    const bt = document.querySelector('header .nav-brandtext');
    const coin = bt && bt.querySelector('svg.nav-coin');
    const b = coin && coin.getBoundingClientRect();
    return bt ? { text: bt.innerText, coin: !!coin, w: b && Math.round(b.width), aria: coin && coin.getAttribute('aria-label'),
                  pig: !!(coin && coin.querySelector('path[d^="M19 5c-1.5 0-2.8 1.4-3 2"]')) && !(coin && coin.querySelector('rect')),
                  dy: coin && new DOMMatrix(getComputedStyle(coin).transform).f,
                  title: coin && coin.querySelector('title') && coin.querySelector('title').textContent,
                  ver: !!bt.querySelector('.ver-badge') } : null;
  });
  await ui.assert(!!r && !/№/.test(r.text), `${label}:左上角不再出现「№」`, JSON.stringify(r));
  await ui.assert(!!r && r.coin && r.w >= 12, `${label}:品牌名旁边有小图标`, JSON.stringify(r));
  await ui.assert(!!r && r.pig && r.w === 18 && r.dy === -2, `${label}:图标是存钱罐(不再是古钱)· 18px · 上移 2px 与字身中线对齐`, JSON.stringify(r));
  await ui.assert(!!r && r.aria === fam && r.title === fam, `${label}:悬停 / 读屏看得到完整家庭名「${fam}」`, JSON.stringify(r));
  await ui.assert(!!r && r.ver, `${label}:版本徽记还在`);
}

module.exports = {
  name: '40-nav-coin',
  title: 'v1.28.5 · issue #31 · 左上角「№ + 首字」换成小图标(v1.35 起是存钱罐)· 家庭名放进悬停',

  async run(ui, report) {
    ui.flow = this.name;
    state.mark = markNow();
    if (state.mark !== 'NULL') {
      db.raw(`UPDATE family SET brand_mark=NULL WHERE id=${fx.FAM}`);
      report.info(`beta 上选的是「${state.mark}」· 先置回默认(存钱罐),cleanup 还原`);
    }
    report.section('1 · 电脑');
    await check(ui, '电脑');
    report.section('2 · 手机(390px)');
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await check(ui, '手机');
    await ui.page.setViewportSize({ width: 1440, height: 900 });
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    await ui.page.setViewportSize({ width: 1440, height: 900 }).catch(() => {});
    if (state.mark && state.mark !== 'NULL') {
      db.raw(`UPDATE family SET brand_mark='${state.mark.replace(/[^a-z0-9_-]/gi, '')}' WHERE id=${fx.FAM}`);
      report.info(`还原:小图标回到开跑前的「${state.mark}」`);
    }
  },
};
