/**
 * flow · v1.30 · 补录本金(FR-973 ~ 975)+ 场外基金持仓成本价(FR-971)+ 透视里没成本的持仓写明(FR-972)
 *
 * 维护者 2026-10-09 的问题:「用户录入了 2 万成本、现在价值 4 万的基金,仪表盘和报表的各种指标都应该怎么算?」
 * 结论(tech-design/v1.30.md 选型十一 / 十二):
 *   · 成本价只回答「这一只买入以来赚了多少」—— 持仓行 + 资产透视持仓级维度用,**不进任何账户级 / 家庭级指标**;
 *   · 往已有账户里补录一只以前就有的基金,余额这期会多一笔:选「以前就有、现在才补录」→ 记一笔补录本金,
 *     和开账基线同口径 —— 算本金,不算收入、不算投资收益。不记 → 这一笔会被算成这期的投资收益。
 *
 * 用演示数据里一直在记的「蚂蚁财富-基金」(WEALTH · CNY · 有历史 · 没有现金行)。每段:页面上真点 → 看页面 → 看库里的行。
 * 首页的数字是取整显示,比较时容差 1 元。
 *
 * cleanup:这条 flow 加的持仓 / 持仓数量变动 / 估值事件 / 补录本金 / 穿透方向删掉;该账户本期余额与跑之前逐分相同;
 * 桩写进公共快照表的几只基金的快照删掉;配置键恢复原样。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const fundStub = require('../lib/fund-stub.cjs');

const ACC = 8;                        // 蚂蚁财富-基金 · WEALTH · CNY · 有历史
const FUND = '广发多因子混合';
const KEY = 'fund_data_base_url';
const CODES = ['002943', '270042', '000198', '000055', '005156', '007708'];
const state = {};

const cfg = (k) => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
const num = (s) => {
  if (s == null) return NaN;
  const t = String(s).replace(/[−–]/g, '-').replace(/[^\d.\-]/g, '');
  return t === '' || t === '-' ? NaN : Number(t);
};
const balance = () => Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.cur} AND account_id=${ACC}`));
const principals = () => db.col(`SELECT CONCAT_WS('|', id, amount, IFNULL(holding_id,''), period_id, IFNULL(deleted_at,'-'), source_tag)
                                    FROM principal_adjustment WHERE account_id=${ACC} AND id > ${state.maxPa} ORDER BY id`);
const fundRow = () => db.one(`SELECT CONCAT_WS('|', id, shares, manual_value, IFNULL(cost_basis,''), IFNULL(nav_mode,''))
                                FROM stock_holding WHERE account_id=${ACC} AND archived_at IS NULL AND display_name='${FUND}' AND id > ${state.maxH}
                               ORDER BY id DESC LIMIT 1`);

/**
 * 首页「本期怎么变的」:本期(进行中)的钱赚(投资损益)· 开账基线(没有 = 0)。
 * 这一块按本期算 —— 正是补录本金落的那一期;「本月资产收益」KPI 锚的是最近一个已定稿的期,本期的改动动不到它。
 */
async function readDash(ui) {
  await ui.click('header a[href="/dashboard"] >> nth=0', '顶部导航点「仪表盘」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.page.waitForSelector('#dash-cashflow [data-cf-qian]', { timeout: 60000 }).catch(() => {});
  const r = await ui.page.evaluate(() => ({
    qian: document.querySelector('#dash-cashflow [data-cf-qian]')?.textContent.trim() || null,
    opening: document.querySelector('#dash-cashflow [data-cf-opening]')?.textContent.trim() || null,
  }));
  return { pnl: num(r.qian), opening: r.opening == null ? 0 : num(r.opening), raw: r };
}

async function openHoldings(ui) {
  await ui.goto('/accounts');
  await ui.click(`main a[href="/accounts/${ACC}"] >> nth=0`, '账户列表点「蚂蚁财富-基金」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.click(`a[href="/accounts/${ACC}/holdings"]`, '账户详情点「持仓管理」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.rendered('持仓页');
}

/** 首页透视:行选「行业」(持仓级维度)、指标加「累计收益额」→ 读「没算 N 个持仓」 */
async function lensMissing(ui) {
  await ui.click('header a[href="/dashboard"] >> nth=0', '顶部导航点「仪表盘」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  // 透视在首页下方,滚到才开始算(和用户一样往下滚);下拉的选项是算完才填进去的,得等表出来再点
  await ui.page.evaluate(() => document.getElementById('lens-section')?.scrollIntoView());
  await ui.page.waitForSelector('#pivot table', { timeout: 60000 }).catch(() => {});
  await ui.page.waitForTimeout(500);
  await ui.click('.lsel:has(#pivotRowSel) .lsel-btn', '透视「行」下拉点开');
  await ui.page.waitForTimeout(250);
  const inWrap = await ui.page.locator('.lsel:has(#pivotRowSel) li[data-v="industry"]').count();
  await ui.click(inWrap ? '.lsel:has(#pivotRowSel) li[data-v="industry"]' : '.lsel-panel li[data-v="industry"]', '行选「行业」(持仓级维度)');
  await ui.page.waitForTimeout(800);
  const on = await ui.page.locator('#measurePills button[data-m="cumPnl"].pill-ink-active').count();
  if (!on) await ui.click('#measurePills button[data-m="cumPnl"]', '指标点「累计收益额」');
  await ui.page.waitForSelector('[data-cumpnl-missing]', { timeout: 30000 }).catch(() => {});
  const t = await ui.page.evaluate(() => document.querySelector('[data-cumpnl-missing]')?.textContent || '');
  const m = t.match(/没算 (\d+) 个持仓/);
  return { n: m ? Number(m[1]) : NaN, text: t };
}

module.exports = {
  name: '45-principal-and-cost',
  title: 'v1.30 · 补录本金(算本金不算收益)· 基金持仓成本价(只给持仓行和透视持仓级用)',

  async run(ui, report) {
    ui.flow = this.name;
    await ui.page.route(/\/checkup\/(diagnose|insight)/, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));

    // ── 前置 ──────────────────────────────────────────────────────────
    state.before = cfg(KEY);
    state.stub = await fundStub.start();
    db.raw(`INSERT INTO family_runtime_config (family_id, key_name, value_text) VALUES (${fx.FAM}, '${KEY}', 'http://127.0.0.1:${state.stub.port}')
            ON DUPLICATE KEY UPDATE value_text = VALUES(value_text)`);
    db.raw(`DELETE FROM fund_nav_snapshot WHERE fund_code IN ('${CODES.join("','")}')`);
    state.cur = fx.currentPeriod();
    state.maxPa = db.num(`SELECT COALESCE(MAX(id),0) FROM principal_adjustment`);
    state.maxH = db.num(`SELECT COALESCE(MAX(id),0) FROM stock_holding`);
    state.maxEv = db.num(`SELECT COALESCE(MAX(id),0) FROM stock_valuation_event`);
    state.maxSe = db.num(`SELECT COALESCE(MAX(id),0) FROM holding_share_event`);
    state.snap = db.one(`SELECT CONCAT_WS('|', end_balance, IFNULL(source_tag,''), IFNULL(note,'')) FROM period_snapshot
                          WHERE period_id=${state.cur} AND account_id=${ACC}`);
    state.bal0 = balance();
    state.parked = fundStub.parkOtherNavRows(db, fx.FAM, [ACC]);   // 别的账户里的净值行先停用,免得被桩刷成「没拉到」
    report.info(`前置:天天基金桩 127.0.0.1:${state.stub.port} · 账户 ${ACC} 本期余额 ${state.bal0}`);
    await ui.page.waitForTimeout(6000);   // 家庭配置有 5 秒缓存

    // ── 1 · 之前的读数 ────────────────────────────────────────────────
    report.section('1 · 补录之前:首页「本期怎么变的」里的钱赚与开账基线');
    await ui.goto('/accounts');
    const d0 = await readDash(ui);
    await ui.assert(!Number.isNaN(d0.pnl), '读到本期「钱赚(投资损益)」', JSON.stringify(d0.raw));

    // ── 2 · 已有账户里加一只以前就有的基金,选「以前就有、现在才补录」──────────
    report.section('2 · 蚂蚁财富-基金(一直在记的账户)→ 添加场外基金 1000 份 → 「这笔钱从哪来」选「以前就有、现在才补录」');
    await openHoldings(ui);
    await ui.click('a[data-add-fund]', '点「+ 添加持仓 · 场外基金」');
    await ui.rendered('添加场外基金页');
    await ui.fill('#fund-q', '002943', '搜索框输入「002943」');
    await ui.page.waitForSelector('[data-fund-results] [data-fund-code="002943"]', { timeout: 15000 }).catch(() => {});
    await ui.click('[data-fund-results] [data-fund-code="002943"]', `点搜索结果「${FUND}」`);
    await ui.page.waitForSelector('[data-quote-card]', { timeout: 15000 }).catch(() => {});
    const opts = await ui.page.evaluate(() => [...document.querySelectorAll('[data-fund-form] input[name="moneyFrom"]')].map(i => i.value));
    await ui.assert(opts.join(',') === 'PRIOR,NEW', '有历史、没有现金行的账户:钱从哪来是「以前就有、现在才补录」与「新买的,钱从别的账户转来」', opts.join(','));
    await ui.seesText('记得在填报页记一笔划转', '「新买的」那一项提醒去记划转,不然会被算成收益');
    await ui.fill('[data-fund-form] input[name="amount"]', '1000', '持有份额填 1000');
    // 不选「钱从哪来」直接点添加 → 浏览器拦下(必选),库里什么都没多
    await ui.page.click('[data-fund-form] button[type="submit"]').catch(() => {});
    await ui.page.waitForTimeout(500);
    await ui.assert(!fundRow(), '没选「钱从哪来」:提交被拦下,库里没有新持仓(必选)');
    await ui.page.check('[data-fund-form] input[name="moneyFrom"][value="PRIOR"]');
    await ui.submit('[data-fund-form] button[type="submit"]', '选「以前就有、现在才补录」→ 点「添加」');
    await ui.seesText('这笔记为补录本金,不算这期的收益', '回到持仓页,提示「记为补录本金」');
    const f = String(fundRow() || '').split('|');
    state.hid = Number(f[0]);
    await ui.assert(state.hid > 0 && Number(f[1]) === 1000 && Number(f[2]) === 4.78 && f[3] === '' && f[4] === 'FUND',
      '真值层:新持仓 1000 份 · 净值 4.78 · 没填成本价 · 按净值估值', f.join('|'));
    await ui.assert(Math.abs(balance() - state.bal0 - 4780) < 0.01, '真值层:账户本期余额 +4,780', `${state.bal0} → ${balance()}`);
    let pa = principals();
    await ui.assert(pa.length === 1 && pa[0].split('|')[1] === '4780.00' && Number(pa[0].split('|')[2]) === state.hid
      && Number(pa[0].split('|')[3]) === Number(state.cur) && pa[0].split('|')[4] === '-' && pa[0].split('|')[5] === 'MANUAL',
      '真值层:记了一笔补录本金 4,780.00 · 挂在这只持仓上 · 记在本期 · 来源「手动」', pa.join(' ; '));

    // ── 3 · 首页:开账基线 +4,780,本月资产收益不变 ───────────────────────
    report.section('3 · 首页「本期怎么变的」:4,780 进了「开账基线」,「钱赚」一分没多(不算收益)');
    const d1 = await readDash(ui);
    await ui.assert(Math.abs(d1.opening - d0.opening - 4780) <= 1, '「开账基线」多了 4,780', `${d0.opening} → ${d1.opening}`);
    await ui.assert(Math.abs(d1.pnl - d0.pnl) <= 1, '「钱赚」没变(补录的钱不算收益)', `${d0.pnl} → ${d1.pnl}`);
    await ui.page.screenshot({ path: '/tmp/e2e-45-dashboard-pc.png', fullPage: false }).catch(() => {});

    // ── 4 · 资产透视(持仓级):没成本价的持仓写明;补上成本价后少一个 ──────────
    report.section('4 · 首页透视:行选「行业」+ 指标「累计收益额」→ 写明「没算 N 个持仓」');
    const l1 = await lensMissing(ui);
    await ui.assert(l1.n > 0, '透视下面写「累计收益额里没算 N 个持仓 —— 它们没有成本价」', l1.text);

    // ── 5 · 持仓页:只改成本价(份额不动)→ 持有收益出来,余额与事件都不动 ─────
    report.section('5 · 持仓页这只基金:「改份额」里只填持仓成本价 4 → 持有收益 +780(+19.50%),余额不动、不记持仓数量变动');
    await openHoldings(ui);
    const art = `article:has(.font-display:text-is("${FUND}"))`;
    const nocost = await ui.page.textContent(`${art} [data-nav-nocost]`).catch(() => null);
    await ui.assert(/没填持仓成本价/.test(nocost || ''), '没填成本价时写「没填持仓成本价 —— 在「改份额」里补一个」', nocost);
    const bal1 = balance();
    const se0 = db.num(`SELECT COUNT(*) FROM holding_share_event WHERE holding_id=${state.hid}`);
    await ui.click(`${art} details[data-nav-edit] summary`, '点「改份额」');
    await ui.fill(`${art} details[data-nav-edit] input[name="costBasis"]`, '4', '持仓成本价填 4(份额不动)');
    await ui.submit(`${art} details[data-nav-edit] button[type="submit"]`, '保存');
    const f2 = String(fundRow() || '').split('|');
    await ui.assert(Number(f2[3]) === 4 && Number(f2[1]) === 1000, '真值层:成本价 4.0000 · 份额仍是 1000', f2.join('|'));
    await ui.assert(Math.abs(balance() - bal1) < 0.01, '真值层:余额不动', `${bal1} → ${balance()}`);
    await ui.assert(db.num(`SELECT COUNT(*) FROM holding_share_event WHERE holding_id=${state.hid}`) === se0,
      '真值层:只改成本价不记持仓数量变动');
    const pnl = await ui.page.textContent(`${art} [data-nav-pnl]`).catch(() => null);
    await ui.assert(/持仓成本价 4\.0000 · 持有收益 \+CNY 780\.00\(\+19\.50%\)/.test((pnl || '').replace(/\s+/g, ' ')),
      '持仓页写「持仓成本价 4.0000 · 持有收益 +CNY 780.00(+19.50%)· 买入以来」', pnl);
    await ui.page.screenshot({ path: '/tmp/e2e-45-holdings-pc.png', fullPage: true }).catch(() => {});
    const d2 = await readDash(ui);
    await ui.assert(Math.abs(d2.pnl - d1.pnl) <= 1 && Math.abs(d2.opening - d1.opening) <= 1,
      '首页:填了成本价,「钱赚」「开账基线」都不变(成本价不进家庭级指标)', `${d1.pnl}/${d1.opening} → ${d2.pnl}/${d2.opening}`);
    // 透视的头寸缓存是「先给旧的、后台换新」(SWR):改完立刻看可能还是旧的 —— 用户刷新一下就对,这里最多等两轮
    let l2 = await lensMissing(ui);
    for (let i = 0; i < 2 && l2.n !== l1.n - 1; i++) { await ui.page.waitForTimeout(3000); l2 = await lensMissing(ui); }
    await ui.assert(l2.n === l1.n - 1, '透视「没算 N 个持仓」少了一个(这只有成本价了)', `${l1.n} → ${l2.n}`);

    // ── 6 · 账户详情:时间线「+ 补录本金」,可单独筛;删掉 → 这 4,780 回到收益里 ─────
    report.section('6 · 账户详情:时间线有「+ 补录本金」,筛选能单看;删掉它 → 首页这 4,780 算回「钱赚」');
    await ui.goto('/accounts');
    await ui.click(`main a[href="/accounts/${ACC}"] >> nth=0`, '账户列表点「蚂蚁财富-基金」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.rendered('账户详情');
    // 时间线按月折叠,默认只展开最上面一组;本期那一组没展开就点开(用户也得点)
    const ym = db.one(`SELECT DATE_FORMAT(period_start, '%Y · %m') FROM period WHERE id=${state.cur}`);
    const grp = `main details:has(summary span:text-is("${ym}"))`;
    if (!(await ui.page.locator(grp).first().evaluate(d => d.open).catch(() => false))) {
      await ui.click(`${grp} > summary`, `点开本期(${ym})那一组`);
    }
    await ui.seesText(`补录本金 · ${FUND}`, `时间线写「补录本金 · ${FUND}」`);
    await ui.seesText('+¥4,780.00', '金额 +¥4,780.00');
    await ui.choose('type', 'PRINCIPAL', 'main form');
    await ui.submit('main form button:has-text("筛选")', '筛选选「+ 补录本金」');
    const kinds = await ui.page.evaluate(() => [...document.querySelectorAll('main details li > span:first-child')].map(s => s.textContent.trim()));
    await ui.assert(kinds.length === 1 && kinds[0] === '+ 补录本金', '筛选后只剩补录本金这一条', kinds.join(' / '));
    ui.page.once('dialog', d => d.accept().catch(() => {}));
    await ui.submit('main [data-principal-delete] button', '点这一条的 ✕ 并确认');
    await ui.seesText('已删除这笔补录本金', '提示「已删除 —— 这期余额里的这部分照常算回收益」');
    pa = principals();
    await ui.assert(pa.length === 1 && pa[0].split('|')[4] !== '-', '真值层:那一笔标了删除时间(软删,备份里还在)', pa.join(' ; '));
    await ui.assert(Math.abs(balance() - bal1) < 0.01, '真值层:删补录本金不动余额', `${bal1} → ${balance()}`);
    const d3 = await readDash(ui);
    await ui.assert(Math.abs(d3.pnl - d2.pnl - 4780) <= 1, '首页:这 4,780 算回「钱赚」', `${d2.pnl} → ${d3.pnl}`);
    await ui.assert(Math.abs(d3.opening - (d2.opening - 4780)) <= 1, '首页:「开账基线」少了 4,780', `${d2.opening} → ${d3.opening}`);

    // ── 7 · 账户详情的「补录本金」表单(任何已有历史的资产账户都能用)─────────
    report.section('7 · 账户详情 →「补录本金」:记 2,000 → 余额不动,这 2,000 从「钱赚」挪到「开账基线」');
    await ui.goto('/accounts');
    await ui.click(`main a[href="/accounts/${ACC}"] >> nth=0`, '账户列表点「蚂蚁财富-基金」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click('[data-principal-card] > summary', '展开「补录本金」');
    await ui.seesText('钱是从家里别的账户转来的,请记「划转」', '写清楚:别的账户转来的记划转,不要记在这里');
    await ui.fill('[data-principal-form] input[name="amount"]', '2000', '金额填 2000');
    await ui.fill('[data-principal-form] input[name="note"]', 'e2e · 补录早年的存款', '备注');
    await ui.submit('[data-principal-form] button[type="submit"]', '点「记一笔」');
    await ui.seesText('已记一笔补录本金', '提示「已记一笔补录本金」');
    pa = principals();
    const last = (pa[pa.length - 1] || '').split('|');
    await ui.assert(pa.length === 2 && last[1] === '2000.00' && last[2] === '' && Number(last[3]) === Number(state.cur) && last[4] === '-',
      '真值层:记了 2,000.00 · 不挂持仓 · 记在本期', pa.join(' ; '));
    await ui.assert(Math.abs(balance() - bal1) < 0.01, '真值层:余额不动(只是说明这期余额里有 2,000 是本来就有的)', `${bal1} → ${balance()}`);
    const d4 = await readDash(ui);
    await ui.assert(Math.abs(d4.pnl - (d3.pnl - 2000)) <= 1, '首页:「钱赚」少了 2,000', `${d3.pnl} → ${d4.pnl}`);
    await ui.assert(Math.abs(d4.opening - (d3.opening + 2000)) <= 1, '首页:「开账基线」多了 2,000', `${d3.opening} → ${d4.opening}`);
    // 金额不为正:浏览器拦(min=0.01);绕过前端也会被服务端拒 —— 这里只走用户路径
    await ui.noConsoleErrors('这几个页面控制台无报错');

    // ── 8 · 手机上的补录本金表单 ───────────────────────────────────────
    report.section('8 · 手机(390px):账户详情的补录本金表单不横向溢出');
    const vp = ui.page.viewportSize();
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await ui.goto(`/accounts/${ACC}`);
    await ui.click('[data-principal-card] > summary', '展开「补录本金」');
    await ui.page.waitForTimeout(300);
    const over = await ui.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    await ui.assert(over <= 1, '手机上账户详情没有横向滚动', `溢出 ${over}px`);
    await ui.page.screenshot({ path: '/tmp/e2e-45-detail-mobile.png', fullPage: false }).catch(() => {});
    await ui.page.setViewportSize(vp);
  },

  async cleanup(ui, report) {
    await ui.page.unroute(/\/checkup\/(diagnose|insight)/).catch(() => {});
    if (state.stub) state.stub.server.close();
    const hs = db.col(`SELECT id FROM stock_holding WHERE account_id=${ACC} AND id > ${state.maxH || 0}`).join(',');
    for (const sql of [
      `DELETE FROM principal_adjustment WHERE account_id=${ACC} AND id > ${state.maxPa || 0}`,
      hs ? `DELETE FROM holding_share_event WHERE holding_id IN (${hs})` : null,
      hs ? `DELETE FROM holding_allocation WHERE holding_id IN (${hs})` : null,
      hs ? `DELETE FROM stock_holding WHERE id IN (${hs})` : null,
      `DELETE FROM stock_valuation_event WHERE account_id=${ACC} AND id > ${state.maxEv || 0}`,
    ].filter(Boolean)) { try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 60)} · ${String(e.message).slice(0, 80)}`); } }
    if (state.snap) {
      const [bal, tag, note] = state.snap.split('|');
      db.raw(`UPDATE period_snapshot SET end_balance=${bal}${tag ? `, source_tag='${tag}'` : ''},
              note=${note ? `'${note.replace(/'/g, "''")}'` : 'NULL'} WHERE period_id=${state.cur} AND account_id=${ACC}`);
      await ui.assert(Math.abs(balance() - Number(bal)) < 0.005, '还原:账户本期余额与跑之前逐分相同', `${bal} / ${balance()}`);
    }
    try { db.raw(`DELETE FROM fund_nav_snapshot WHERE fund_code IN ('${CODES.join("','")}')`); } catch (e) { /* 只是缓存 */ }
    fundStub.restoreParked(db, state.parked);
    if (state.before === null || state.before === undefined) db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);
    else db.raw(`UPDATE family_runtime_config SET value_text='${String(state.before).replace(/'/g, "''")}' WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);
  },
};
