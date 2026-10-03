/**
 * flow · v1.29 · issue #34(续)· 定格表里的残留行不许影响还没关账的那一期
 *
 * 提交者 2026-10-02 贴的查询把根因照了出来:10 月(还没关账)这一期在 period_account_attr 里有定格行,
 * 「中国银行(现金)」定格成了 LOAN、一张信用卡定格成了 PROPERTY —— 正是演示数据里 5 号、10 号账户的类型。
 * 全新 Docker 安装:迁移先灌演示数据,V54 给演示里已关账的期回填定格行;清演示数据只 TRUNCATE 了 period / account,
 * 编号从 1 重来 → 残留的定格行按 (期编号, 账户编号) 挂到了用户自己的账户上。
 * 已在一个临时 MySQL 上照全新安装的步骤逐条复现(提交者导出的那四行逐字一样)。
 *
 * 前置(时间上无法自然到达:要一台刚装好的 Docker):往当前这一期塞一条「残留」定格行 ——
 * 把一个有余额的现金账户定格成贷款(source = BACKFILL,定格时间早于这一期被创建)。cleanup 删掉。
 * 动作从页面发起:首页「总负债」卡与它的「怎么算的」。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const num = s => Number(String(s || '').replace(/[−–]/g, '-').replace(/[^\d.\-]/g, '') || NaN);
const state = {};

async function readLiab(ui) {
  await ui.goto('/');
  await ui.page.waitForSelector('a.kpi-card', { timeout: 60000 }).catch(() => {});
  return ui.page.evaluate(() => {
    const card = [...document.querySelectorAll('a.kpi-card')].find(a => (a.querySelector('.kpi-delta')?.textContent || '').trim() === '仅 LOAN');
    if (!card) return null;
    const txt = card.textContent.replace(/\s+/g, ' ');
    return { value: card.querySelector('.kpi-value').textContent.trim(), txt: txt.slice(0, 300) };
  });
}

module.exports = {
  name: '42-stale-frozen-type',
  title: 'v1.29 · issue #34 · 定格表的残留行不影响没关账的那一期(现金账户不会被当成贷款)',

  async run(ui, report) {
    ui.flow = this.name;
    state.pid = fx.currentPeriod();
    state.cash = db.one(`SELECT a.id FROM account a JOIN period_snapshot ps ON ps.account_id=a.id AND ps.period_id=${state.pid}
                          WHERE a.family_id=${fx.FAM} AND a.type='CASH' AND a.archived_at IS NULL AND ps.end_balance > 1000
                          ORDER BY ps.end_balance DESC LIMIT 1`);
    if (!state.cash) { report.skip(this.name, '残留定格', '当前期没有有余额的现金账户'); return; }
    state.name = db.one(`SELECT display_name FROM account WHERE id=${state.cash}`);

    report.section('1 · 首页「总负债」:塞残留行之前');
    const before = await readLiab(ui);
    await ui.assert(!!before, '首页有「总负债」卡', JSON.stringify(before));

    // 前置:模拟全新安装留下的残留 —— 当前(未关账)期里,一个现金账户被「定格」成贷款
    db.raw(`INSERT INTO period_account_attr (period_id, account_id, account_type, source, sealed_at)
            VALUES (${state.pid}, ${state.cash}, 'LOAN', 'BACKFILL', '2026-01-01 00:00:00.000')`);
    state.inserted = true;
    report.info(`前置:当前期 #${state.pid} 塞一条残留定格行 —— 现金账户 #${state.cash} 定格成 LOAN(提交者那台机器上的形状)`);

    report.section('2 · 首页「总负债」:残留行不起作用');
    const after = await readLiab(ui);
    await ui.assert(after && after.value === before.value,
                    `总负债不变(${before && before.value} → ${after && after.value}),现金账户没有被当成贷款`, after && after.txt);
    await ui.assert(after && !after.txt.includes(state.name), `「怎么算的」里没有列出现金账户「${state.name}」`, after && after.txt);
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    if (state.inserted) {
      db.raw(`DELETE FROM period_account_attr WHERE period_id=${state.pid} AND account_id=${state.cash} AND source='BACKFILL'
                AND sealed_at='2026-01-01 00:00:00.000'`);
      report.info('还原:塞进去的残留定格行已删除');
    }
  },
};
