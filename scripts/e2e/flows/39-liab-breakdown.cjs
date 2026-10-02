/**
 * flow · v1.28.4 · issue #34 · 仪表盘「总负债」旁的「怎么算的」明细,加起来必须等于合计
 *
 * 提交者:总负债显示 ¥19,128,点开「怎么算的」只列了两张信用卡(3,231 和 121),合计却写 19,128 —— 对不上。
 * 根因(复现):账户的类型是按期定格的(已关账期用关账那一刻的类型)。KPI 按【锚期那一行】的类型归类,
 *   明细却按账户在窗口里【最早那一行】的类型归类 —— 账户改过类型,两边就分家:合计算了它、明细没列它(或反过来)。
 *
 * 前置:把一张信用卡的类型改成现金,模拟「九月关账之后改了账户类型」(cleanup 改回)。
 * 动作全部从页面发起:首页 → 总负债卡的 ⓘ;再从期选择看已关账的九月。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const num = s => Number(String(s || '').replace(/[−–]/g, '-').replace(/[^\d.\-]/g, '') || NaN);
const state = {};

/** 读首页「总负债」卡:主数字 + ⓘ 里列出的每个账户金额 + 写着的合计 */
async function readLiab(ui) {
  return ui.page.evaluate(() => {
    const card = [...document.querySelectorAll('a.kpi-card')].find(a => (a.querySelector('.kpi-delta')?.textContent || '').trim() === '仅 LOAN');
    if (!card) return null;
    const txt = card.textContent.replace(/\s+/g, ' ');
    const m = txt.match(/绝对值合计。(.*?)合计 = (¥[\d,.]+)/);
    const items = m ? [...m[1].matchAll(/¥([\d,.]+)/g)].map(x => Number(x[1].replace(/,/g, ''))) : [];
    return { value: card.querySelector('.kpi-value').textContent.trim(), items, stated: m ? m[2] : null, gap: /没能逐个列出/.test(txt), txt: txt.slice(0, 260) };
  });
}

module.exports = {
  name: '39-liab-breakdown',
  title: 'v1.28.4 · issue #34 · 总负债的明细加起来等于合计(账户改过类型也一样)',

  async run(ui, report) {
    ui.flow = this.name;
    state.card = db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND type='LOAN' AND archived_at IS NULL
                           AND display_name LIKE '%信用卡%' ORDER BY id LIMIT 1`);
    if (!state.card) { report.skip(this.name, '总负债明细', '这台机器上没有信用卡账户'); return; }
    // 关账期里这张卡定格为 LOAN 才能复现「两边分家」
    const closed = db.one(`SELECT p.period_start FROM period p JOIN period_account_attr paa ON paa.period_id=p.id
                            WHERE p.family_id=${fx.FAM} AND p.status='CLOSED' AND paa.account_id=${state.card} AND paa.account_type='LOAN'
                              AND p.period_start <= CURDATE() ORDER BY p.period_start DESC LIMIT 1`);
    db.raw(`UPDATE account SET type='CASH' WHERE id=${state.card} AND family_id=${fx.FAM}`);
    report.info(`前置:账户 ${state.card}(信用卡)类型临时改成现金,模拟「关账之后改了类型」· 已关账期 ${closed}`);

    report.section('1 · 首页(当前期):总负债的明细加起来等于合计');
    await ui.goto('/');
    await ui.rendered('首页');
    let r = await readLiab(ui);
    await ui.assert(!!r && r.items.length >= 1, '总负债卡的「怎么算的」列出了账户', JSON.stringify(r));
    if (r) {
      const sum = r.items.reduce((a, b) => a + b, 0);
      await ui.assert(Math.abs(sum - num(r.value)) <= 1 && num(r.stated) === num(r.value),
        `明细加起来(${sum})= 卡上的总负债(${r.value})`, r.txt);
      await ui.assert(!r.gap, '没有「没能逐个列出」的差额', r.txt);
    }

    if (closed) {
      report.section('2 · 看已关账的那一期:这张卡按关账时的类型(贷款)算,明细里也列它');
      // 用户就是在首页顶上的「期」下拉里换月份
      const sel = 'select[onchange*="asof="]';
      await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                         ui.page.selectOption(sel, closed).catch(() => null)]);
      await ui.page.waitForTimeout(500);
      await ui.assert(ui.page.url().includes(`asof=${closed}`), `在首页「期」下拉里选了已关账的 ${closed}`, ui.page.url());
      r = await readLiab(ui);
      if (r) {
        const sum = r.items.reduce((a, b) => a + b, 0);
        await ui.assert(Math.abs(sum - num(r.value)) <= 1, `已关账期:明细加起来(${sum})= 总负债(${r.value})`, r.txt);
      }
    }
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    if (!state.card) return;
    db.raw(`UPDATE account SET type='LOAN' WHERE id=${state.card} AND family_id=${fx.FAM}`);
    report.info(`还原:账户 ${state.card} 类型改回贷款`);
  },
};
