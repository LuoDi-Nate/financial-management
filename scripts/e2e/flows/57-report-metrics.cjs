/* Historical closed months cannot be entered through today's forms. These synthetic
 * fixtures exercise legacy totals, later ledger entries and newly included capital.
 * No close endpoint is used; cleanup removes every fixture row. */
const db = require('../lib/db.cjs');
const { FAM } = require('../lib/fixture.cjs');
const state = { periods: [], accounts: [] };
const PREFIX = 'e2e-report-1371';
const ASOF = '1999-03-01';

async function selectMonth(ui) {
  await ui.goto('/accounts');
  if (await ui.page.locator('header a[href="/reports"]').first().isVisible()) {
    await ui.click('header a[href="/reports"] >> nth=0', '顶栏进入报表');
  } else {
    await ui.click('header details:has(.nav-drawer) > summary', '打开手机导航');
    await ui.click('header .nav-drawer a[href="/reports"]', '手机导航进入报表');
  }
  await ui.page.selectOption('select[data-base]', ASOF);
  await ui.page.waitForLoadState('networkidle');
  await ui.click('#sec-trend a[hx-get*="range=3M"] >> nth=0', '趋势切到三个月');
  await ui.page.waitForTimeout(700);
}

async function savingsValues(ui) {
  return ui.page.locator('#sec-savings .grid-hairline > div').evaluateAll(cards =>
    cards.map(card => card.innerText.replace(/\s+/g, ' ').trim()));
}

module.exports = {
  name: '57-report-metrics',
  title: '封板储蓄同月同源 · 开账本金不算人赚 · 支出上下文 · 币种联动',
  async run(ui, report) {
    ui.flow = this.name;
    if (db.num(`SELECT COUNT(*) FROM period WHERE family_id=${FAM} AND period_start BETWEEN '1999-01-01' AND '1999-04-01'`)) {
      throw new Error('合成月份已有数据,不覆盖');
    }
    if (db.one(`SELECT expense_entry_mode FROM family WHERE id=${FAM}`) !== 'ITEMIZED') {
      throw new Error('该回归需要逐笔模式,不改家庭设置');
    }
    const member = db.one(`SELECT MIN(id) FROM member WHERE family_id=${FAM} AND archived_at IS NULL`);
    for (let month = 1; month <= 4; month++) {
      const start = `1999-0${month}-01`;
      db.raw(`INSERT INTO period(family_id,period_type,period_start,period_end,status,closed_at)
              VALUES(${FAM},'MONTHLY','${start}',LAST_DAY('${start}'),'CLOSED',NOW())`);
      const pid = db.one(`SELECT id FROM period WHERE family_id=${FAM} AND period_start='${start}'`);
      state.periods.push(pid);
      // Synthetic quotes prevent historical fixtures from fetching public FX data.
      db.raw(`INSERT IGNORE INTO fx_rate(family_id,period_id,base_currency,quote_currency,rate,source)
              SELECT ${FAM},${pid},'CNY',currency,CASE currency WHEN 'USD' THEN 0.125 WHEN 'HKD' THEN 1.2 ELSE 1 END,'MANUAL'
              FROM (SELECT DISTINCT currency FROM account WHERE family_id=${FAM}
                    UNION SELECT 'USD' UNION SELECT 'HKD') currencies WHERE currency<>'CNY'`);
    }
    for (const [suffix, level] of [['cash', 1], ['capital', 5]]) {
      db.raw(`INSERT INTO account(family_id,display_name,type,currency,risk_level_override)
              VALUES(${FAM},'${PREFIX}-${suffix}','CASH','CNY',${level})`);
      state.accounts.push(db.one(`SELECT id FROM account WHERE family_id=${FAM} AND display_name='${PREFIX}-${suffix}'`));
    }
    const [jan, feb, mar, apr] = state.periods;
    const [cash, capital] = state.accounts;
    db.raw(`INSERT INTO period_snapshot(period_id,account_id,end_balance,submitted_by) VALUES
            (${jan},${cash},10000,${member}),(${feb},${cash},20000,${member}),
            (${mar},${cash},25000,${member}),(${mar},${capital},50000,${member}),
            (${apr},${cash},99999,${member})`);
    db.raw(`INSERT INTO period_member_cashflow(family_id,period_id,member_id,total_income_input,total_expense_input)
            VALUES(${FAM},${jan},${member},20000,1000)`);
    db.raw(`UPDATE period_snapshot SET source_tag='MANUAL' WHERE period_id=${mar} AND account_id=${cash}`);
    db.raw(`UPDATE period_snapshot SET source_tag='SYNC_FUND_NAV' WHERE period_id=${mar} AND account_id=${capital}`);
    db.raw(`INSERT INTO cash_flow(period_id,account_id,kind,category_code,amount,submitted_by,note) VALUES
            (${feb},${cash},'INCOME','salary',20000,${member},'${PREFIX}'),
            (${feb},${cash},'EXPENSE','consumption',5000,${member},'${PREFIX}'),
            (${mar},${cash},'INCOME','salary',10000,${member},'${PREFIX}'),
            (${mar},${cash},'EXPENSE','consumption',4000,${member},'${PREFIX}'),
            (${apr},${cash},'EXPENSE','consumption',9999,${member},'${PREFIX}')`);

    await selectMonth(ui);
    const sealed = await ui.page.locator('#sec-sealed').innerText();
    await ui.assert(/自动\s+1/.test(sealed) && /手填\s+1/.test(sealed) && /其他\/未记录\s+0/.test(sealed),
      '封板按实际余额来源统计,现金账户的净值同步也算自动');
    const cards = await savingsValues(ui);
    await ui.assert(cards[2].includes('1999-03') && cards[2].includes('60%'), '储蓄率取观察月,不回退一月的 95%', cards[2]);
    await ui.assert(cards[0].includes('16,667') && cards[3].includes('15,000'), '均值与中位数同时包含后续逐笔月份', cards.join(' | '));
    await ui.assert(db.num(`SELECT SUM(amount) FROM cash_flow WHERE period_id=${mar} AND kind='INCOME'`) === 10000,
      '真值层:观察月收入来自逐笔,总额记录只在一月');
    const human = await ui.page.locator('#reports-region .kpi-card').filter({ hasText: '人赚' }).first().innerText();
    await ui.assert(human.includes('21,000') && !human.includes('71,000'), '人赚排除新纳入的本金', human);
    const mix = await ui.page.locator('#sec-expense-mix').innerText();
    await ui.assert(mix.includes('9,000.00') && !mix.includes('18,999'), '支出构成截至三月,不含四月', mix.slice(-500));

    await ui.click('#sec-expense-mix a[hx-get*="mix=account"]', '切换支出维度');
    await ui.assert(new URL(ui.page.url()).searchParams.get('asof') === ASOF &&
      new URL(ui.page.url()).searchParams.get('range') === '3M', '维度切换保留账期和范围');
    await ui.click(`#sec-expense-mix [hx-get*="expense-mix/detail"][hx-get*="groupKey=${cash}"]`, '展开逐笔明细');
    await ui.page.waitForSelector(`#mix-detail-${cash} [data-priv]`);
    const drawer = await ui.page.locator(`#mix-detail-${cash}`).innerText();
    await ui.assert(drawer.includes('5,000') && drawer.includes('4,000') && !drawer.includes('9,999'), '抽屉和汇总属于同一组三个月', drawer);

    const filter = ui.page.locator('#reports-region details').first();
    await filter.evaluate(el => el.open = true);
    await ui.click('#reports-region a[hx-get*="currency=USD"] >> nth=0', '显示币种切到美元');
    await ui.page.waitForTimeout(700);
    await ui.visible('#reports-main #sec-savings', '整页刷新后仍有完整报告');
    const values = await ui.page.locator('#sec-wealth [data-priv]').allTextContents();
    await ui.assert(values.length === 2 && values.every(t => t.includes('$') && !t.includes('¥')),
      '币种切换同步更新财富水位金额', values.join(' | '));
    await ui.assert((await ui.page.locator('#sec-savings').innerText()).includes('CNY'), '收支区明确仍按家庭本位币统计');
    const charts = await ui.page.evaluate(() => {
      const wealth = Chart.getChart(document.getElementById('waterLevelChart'));
      const bars = Chart.getChart(document.getElementById('savings-bars'));
      return { small: wealth?.options.scales.y.ticks.callback(1250),
        headroom: bars && bars.scales.y.max > Math.max(...bars.data.datasets.flatMap(d => d.data).filter(v => v != null)) };
    });
    await ui.assert(charts.small === '$1,250', '小额财富曲线保留金额,不显示成 0 万', charts.small);
    await ui.assert(charts.headroom, '收支柱顶为金额标签留出空间');
    await ui.page.locator('#sec-savings').screenshot({ path: '/tmp/e2e-57-savings-pc.png' });
    await ui.page.locator('#sec-wealth').screenshot({ path: '/tmp/e2e-57-wealth-pc.png' });

    await ui.phone(async phone => {
      await selectMonth(phone);
      const mobile = await savingsValues(phone);
      await phone.assert(mobile[2].includes('60%'), '手机观察月储蓄率正确');
      const overflow = await phone.page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2);
      await phone.assert(!overflow, '手机页面无横向溢出');
      await phone.page.locator('#sec-savings').screenshot({ path: '/tmp/e2e-57-savings-phone.png' });
    });

    db.raw(`UPDATE cash_flow SET amount=0 WHERE period_id=${mar} AND kind='INCOME'`);
    await selectMonth(ui);
    const zero = await savingsValues(ui);
    await ui.assert(zero[2].includes('—') && !zero[2].includes('95%'), '零收入显示无法计算,不借用旧比率', zero[2]);
    db.raw(`UPDATE cash_flow SET amount=10000 WHERE period_id=${mar} AND kind='INCOME'`);
    db.raw(`UPDATE cash_flow SET amount=-4000 WHERE period_id=${mar} AND kind='EXPENSE'`);
    await selectMonth(ui);
    const refund = await savingsValues(ui);
    await ui.assert(refund[2].includes('140%') && refund[1].includes('667'), '退款保留符号并参与储蓄计算', refund.join(' | '));
  },
  async cleanup(ui, report) {
    if (state.periods.length) {
      const ids = state.periods.join(',');
      for (const table of ['cash_flow','period_snapshot','period_member_cashflow','period_account_attr',
                           'period_member_completion','snapshot_todo','fx_rate']) {
        db.raw(`DELETE FROM ${table} WHERE period_id IN (${ids})`);
      }
      db.raw(`DELETE FROM period WHERE id IN (${ids})`);
    }
    if (state.accounts.length) db.raw(`DELETE FROM account WHERE id IN (${state.accounts.join(',')})`);
    report.info('合成历史月份、收支、余额、汇率和账户已删除');
    await ui.goto('/dashboard');
  },
};
