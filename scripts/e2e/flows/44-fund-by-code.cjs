/**
 * flow · v1.30 · issue #25 · 场外基金按代码自动估值 · 货币基金每日结转 · 持仓数量变动 · 基金账户 · 现金联动
 *
 * 维护者 2026-10-08 给 e2e 下的定义:「自己唤起浏览器,根据描述来操作页面,同时看页面的渲染返回和数据库对应数据行的期望状态」。
 * 所以每一段都是:按 PRD 的描述在页面上真点 → 断言页面上看到的 → 断言库里对应那几行。
 *
 * 不打真实天天基金:本机桩回放(lib/fund-stub.cjs,行为照 §零 实测:要 Referer、查无此码给空列表、货基 DWJZ 是万份收益、
 * pageSize 封顶 20)。只经家庭配置 fund_data_base_url 指过去(应用只认本机回环)。
 *
 * 两处「时间上无法自然到达」的前置(fixture 纪律 1):
 *   · 货币基金断了 7 天:把「已结转到」往回拨 7 天,再从页面点刷新,看它逐日补齐;
 *   · 刷新按钮防连点(2 分钟内拉过就不再拉):把桩数据换了之后,把快照的拉取时间往回拨,模拟「过了一阵子」。
 * 「手填转自动」那一段要一条「穿透认出了代码」的手填行:穿透是异步的、要真东财,这里直接给手填行写上代码。
 *
 * cleanup:账户连同持仓 / 事件 / 快照 / 流水删干净;桩写进公共快照表的那几只基金的快照删掉;配置键恢复原样。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const fundStub = require('../lib/fund-stub.cjs');

const ACC_NAME = 'e2e · 基金账户';
const KEY = 'fund_data_base_url';
const CODES = ['002943', '270042', '000198', '000055', '005156', '007708'];
const state = {};

const cfg = (k) => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
const num = (s) => Number(String(s || '').replace(/[^\d.\-]/g, '') || NaN);
const row = (name) => db.one(`SELECT CONCAT_WS('|', id, valuation_mode, IFNULL(nav_mode,''), shares, manual_value, IFNULL(nav_date,''),
                                                IFNULL(shares_estimated_on,''), IFNULL(nav_error,''), IFNULL(fund_code,''))
                                FROM stock_holding WHERE account_id=${state.acc} AND archived_at IS NULL AND display_name='${name}'
                               ORDER BY id DESC LIMIT 1`);
const cols = (r) => { const [id, mode, nav, shares, unit, navDate, est, err, code] = String(r || '').split('|');
                      return { id: Number(id), mode, nav, shares: Number(shares), unit: Number(unit), navDate, est, err, code }; };
const balance = () => Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.cur} AND account_id=${state.acc}`));
const cash = () => Number(db.one(`SELECT COALESCE(SUM(manual_value),0) FROM stock_holding WHERE account_id=${state.acc} AND archived_at IS NULL AND valuation_mode='CASH'`));
const events = (reason) => db.col(`SELECT CONCAT_WS('|', reason, shares_delta, IFNULL(date_from,''), IFNULL(date_to,''))
                                     FROM holding_share_event WHERE account_id=${state.acc}${reason ? ` AND reason='${reason}'` : ''} ORDER BY id`);
const lastValEvent = () => db.one(`SELECT CONCAT_WS('|', source_tag, delta) FROM stock_valuation_event WHERE account_id=${state.acc} ORDER BY id DESC LIMIT 1`);
const yday = () => fundStub.addDays(state.stub.today, -1);
const cn = (s) => { const [, m, d] = s.split('-').map(Number); return `${m} 月 ${d} 日`; };
const backdateFetch = () => db.raw(`UPDATE fund_nav_snapshot SET fetched_at = fetched_at - INTERVAL 2 HOUR WHERE fund_code IN ('${CODES.join("','")}')`);

async function openHoldings(ui) {
  await ui.goto('/accounts');
  await ui.click(`main a[href="/accounts/${state.acc}"] >> nth=0`, `账户列表点「${ACC_NAME}」`);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.click(`a[href="/accounts/${state.acc}/holdings"]`, '账户详情点「持仓管理」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.rendered('持仓页');
}

/** 持仓页上某一行(按持仓名)的几个格子 */
async function rowView(ui, name) {
  return ui.page.evaluate((n) => {
    const a = [...document.querySelectorAll('article')].find(x => x.querySelector('.font-display')?.textContent.trim() === n);
    if (!a) return null;
    const t = (s) => a.querySelector(s)?.textContent.replace(/\s+/g, ' ').trim() || null;
    return { shares: t('[data-nav-shares]'), unit: t('[data-nav-unit]'), date: t('[data-nav-date]'), value: t('[data-nav-value]'),
             kind: t('[data-nav-kind]'), badge: t('[data-nav-badge]'), late: t('[data-nav-late]'), est: t('[data-nav-estimated]'),
             problem: t('[data-nav-problem]'), accrual: t('[data-nav-accrual]'),
             manualForm: !!a.querySelector('form[action$="/update"]'), convert: !!a.querySelector('[data-convert-btn]'),
             text: a.innerText.replace(/\s+/g, ' ') };
  }, name);
}

async function pickFund(ui, query, code, label) {
  await ui.fill('#fund-q', query, `搜索框输入「${query}」`);
  await ui.page.waitForSelector(`[data-fund-results] [data-fund-code="${code}"]`, { timeout: 15000 }).catch(() => {});
  return label;
}

module.exports = {
  name: '44-fund-by-code',
  title: 'v1.30 · 基金账户 · 场外基金按代码估值 · 货基每日结转 · 持仓数量变动 · 现金联动 · 手填转自动',

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
    report.info(`前置:天天基金桩 127.0.0.1:${state.stub.port} · 今天 ${state.stub.today}`);
    await ui.page.waitForTimeout(6000);   // 家庭配置有 5 秒缓存

    // ── 1 · 用模板建一个「基金账户」──────────────────────────────────
    report.section('1 · 账户页 → 添加账户(开向导)→ 选模板「基金账户」→ 新账户类型是基金');
    await ui.goto('/accounts');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('main a:has-text("添加账户(开向导)")', '点「+ 添加账户(开向导)」')]);
    await ui.click('#account-wizard .tpl-card:has-text("基金账户")', '点模板「基金账户(蚂蚁财富 / 天天基金 / 京东金融)」');
    await ui.fill('#account-wizard input[name="displayName"]', ACC_NAME, '名称填「e2e · 基金账户」');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('#account-wizard button:has-text("+ 添加账户")', '点「+ 添加账户」')]);
    state.acc = db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND display_name='${ACC_NAME}' ORDER BY id DESC LIMIT 1`);
    const t = db.one(`SELECT CONCAT(type,'|',currency,'|',IFNULL(product_category_code,'')) FROM account WHERE id=${state.acc}`);
    await ui.assert(/^FUND\|CNY\|/.test(t || ''), '真值层:新账户类型 FUND · 人民币', t);
    const pill = await ui.page.evaluate((n) => {
      const r = [...document.querySelectorAll('tr, li, article, div')].find(x => x.querySelector('a') && x.textContent.includes(n) && x.querySelector('.pill-fund'));
      return r ? r.querySelector('.pill-fund').textContent.trim() : null;
    }, ACC_NAME);
    await ui.assert(/^基金/.test(pill || ''), '账户列表里这一行的类型标签写「基金」(基金配色)', String(pill));

    // ── 2 · 持仓页:入口按 §3.1 ─────────────────────────────────────
    report.section('2 · 持仓页:基金账户里「场外基金」排第一、不出「股票 · 自动估值」;先放一笔账户内现金 ¥10,000');
    await openHoldings(ui);
    const ops = await ui.page.evaluate(() => [...document.querySelectorAll('main section a.btn-ink, main section a.btn-paper')]
      .map(a => a.textContent.trim()).filter(s => s.startsWith('+')));
    await ui.assert(ops[0] === '+ 添加持仓 · 场外基金', '第一个添加按钮是「场外基金」(黑底)', ops.join(' / '));
    await ui.assert(!ops.some(s => s.includes('自动估值')), '基金账户里没有「股票 · 自动估值」', ops.join(' / '));
    await ui.click('a:has-text("+ 账户内现金")', '点「+ 账户内现金」');
    await ui.choose('currency', 'CNY');
    await ui.fillByName('amount', '10000');
    await ui.submit('form button[type="submit"]', '添加现金行 ¥10,000');
    await ui.assert(cash() === 10000, '真值层:现金行 10,000', `现金 ${cash()}`);
    await ui.assert(Math.abs(balance() - 10000) < 0.01, '真值层:账户余额 10,000', `余额 ${balance()}`);

    // ── 3 · 按份额添加 + 勾现金联动 ─────────────────────────────────
    report.section('3 · 添加持仓 · 场外基金:拼音「gfdy」搜到 → 先看到名称与净值 → 填 1000 份 → 勾「用账户里的现金买的」');
    await ui.click('a[data-add-fund]', '点「+ 添加持仓 · 场外基金」');
    await ui.rendered('添加场外基金页');
    await pickFund(ui, 'gfdy', '002943');
    await ui.click('[data-fund-results] [data-fund-code="002943"]', '点搜索结果「广发多因子混合」');
    await ui.page.waitForSelector('[data-quote-card]', { timeout: 15000 }).catch(() => {});
    const q1 = await ui.page.evaluate(() => document.querySelector('[data-fund-quote]').innerText.replace(/\s+/g, ' '));
    await ui.assert(q1.includes('广发多因子混合') && q1.includes('002943') && q1.includes('4.7800') && q1.includes(cn(yday())),
      `报价卡先写出名称、代码、最新净值 4.7800 和净值日期 ${cn(yday())}`, q1.slice(0, 160));
    await ui.fill('[data-fund-form] input[name="amount"]', '1000', '持有份额填 1000');
    await ui.page.waitForTimeout(300);
    const calc = await ui.page.textContent('[data-fund-calc]');
    await ui.assert(/1,000\.00 份 × 4\.7800 = ¥4,780\.00/.test(calc || ''), '实时显示「1,000.00 份 × 4.7800 = ¥4,780.00」', calc);
    await ui.page.check('[data-fund-form] input[name="cashLinked"]');
    await ui.page.screenshot({ path: '/tmp/e2e-44-new-fund-pc.png', fullPage: true }).catch(() => {});   // 给人看排版(UED 自查)
    await ui.submit('[data-fund-form] button[type="submit"]', '点「添加 · 以后按净值自动更新」');
    await ui.seesText('已添加「广发多因子混合」', '回到持仓页,提示已添加');
    let r1 = await rowView(ui, '广发多因子混合');
    await ui.assert(r1 && r1.kind === '场外基金' && r1.shares === '1,000.00' && r1.unit === '4.7800' && /4,780\.00/.test(r1.value || ''),
      '持仓页这一行:场外基金 · 份额 1,000.00 · 单位净值 4.7800 · 市值 4,780.00', JSON.stringify(r1));
    await ui.assert(r1 && !r1.manualForm, '基金行没有「股数 × 单股」手改表单(净值由系统写)');
    let g = cols(row('广发多因子混合'));
    await ui.assert(g.mode === 'MANUAL' && g.nav === 'FUND' && g.shares === 1000 && g.unit === 4.78 && g.navDate === yday() && g.code === '002943',
      '真值层:MANUAL 行 · nav_mode FUND · 份额 1000 · 单价 4.78 · 净值日期 · 代码', JSON.stringify(g));
    await ui.assert(cash() === 5220, '真值层:现金行扣了 4,780(10,000 → 5,220)', `现金 ${cash()}`);
    await ui.assert(Math.abs(balance() - 10000) < 0.01, '真值层:余额不变(钱从现金行挪进基金)', `余额 ${balance()}`);
    await ui.assert(events('CASH_BUY').length === 1, '真值层:记了一条持仓数量变动「申购(用账户现金)」', events().join(' ; '));

    // ── 4 · 按市值添加(QDII)──────────────────────────────────────
    report.section('4 · 再加一只 QDII:不知道份额,按市值填 5,000 → 系统按净值反推份额并标明估算 · 写清「晚一个交易日」');
    await ui.click('a[data-add-fund]', '点「+ 添加持仓 · 场外基金」');
    await pickFund(ui, '270042', '270042');
    await ui.click('[data-fund-results] [data-fund-code="270042"]', '点搜索结果「广发纳斯达克100ETF联接人民币(QDII)A」');
    await ui.page.waitForSelector('[data-quote-card]', { timeout: 15000 }).catch(() => {});
    await ui.seesText('晚一个交易日', '报价卡写「QDII、FOF 这类基金的净值通常比国内基金晚一个交易日」');
    await ui.page.check('[data-fund-form] input[name="by"][value="VALUE"]');
    await ui.fill('[data-fund-form] input[name="amount"]', '5000', '当前市值填 5000');
    await ui.page.waitForTimeout(300);
    const calc2 = await ui.page.textContent('[data-fund-calc]');
    await ui.assert(/估算:约 599\.39 份/.test(calc2 || ''), '实时显示「按 … 净值 8.3418 估算:约 599.39 份」', calc2);
    const bal0 = balance();
    await ui.submit('[data-fund-form] button[type="submit"]', '点「添加」');
    const q = cols(row('广发纳斯达克100ETF联接人民币(QDII)A'));
    await ui.assert(Math.abs(q.shares - 599.3910) < 0.00005 && q.est === fundStub.addDays(state.stub.today, -2),
      '真值层:份额 = 5000 ÷ 8.3418(4 位)· 标了按哪天净值估算', JSON.stringify(q));
    await ui.assert(Math.abs(balance() - bal0 - 5000) < 0.01, '真值层:没勾现金联动 → 余额 +5,000', `${bal0} → ${balance()}`);
    const r2 = await rowView(ui, '广发纳斯达克100ETF联接人民币(QDII)A');
    await ui.assert(r2 && r2.late && r2.est && r2.est.includes('估算的份额'), '持仓页这一行写「晚一个交易日」和「按 … 净值估算的份额」', JSON.stringify(r2));

    // ── 5 · 外币份额:搜得到但不让选 ─────────────────────────────────
    report.section('5 · 搜美元份额:搜得到,置灰写原因,没法选');
    await ui.click('a[data-add-fund]', '点「+ 添加持仓 · 场外基金」');
    await pickFund(ui, '000055', '000055');
    const usd = await ui.page.evaluate(() => {
      const el = document.querySelector('[data-fund-results] [data-fund-code="000055"]');
      return el ? { tag: el.tagName, why: el.getAttribute('data-unsupported') } : null;
    });
    await ui.assert(usd && usd.tag !== 'BUTTON' && /美元/.test(usd.why || ''), '美元份额不是可点的按钮,写着「暂不支持美元 / 港币份额」', JSON.stringify(usd));
    {
      const vp0 = ui.page.viewportSize();
      await ui.page.setViewportSize({ width: 390, height: 844 });
      await ui.page.waitForTimeout(400);
      const over = await ui.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      await ui.assert(over <= 1, '手机上添加基金页没有横向滚动(搜索结果里的长名称会折行)', `溢出 ${over}px`);
      await ui.page.screenshot({ path: '/tmp/e2e-44-new-fund-mobile.png', fullPage: true }).catch(() => {});
      await ui.page.setViewportSize(vp0);
    }

    // ── 6 · 货币基金:按金额记,断了 7 天点刷新逐日补齐 ─────────────
    report.section('6 · 加余额宝(货币基金)12,000 → 断了 7 天 → 点「手动刷价」→ 按万份收益逐日结转 +1.89,时间线记一条');
    await pickFund(ui, '余额宝', '000198');
    await ui.click('[data-fund-results] [data-fund-code="000198"]', '点搜索结果「天弘余额宝货币」');
    await ui.page.waitForSelector('[data-quote-card]', { timeout: 15000 }).catch(() => {});
    await ui.seesText('每万份收益', '货币基金的报价卡写「每万份收益」');
    await ui.fill('[data-fund-form] input[name="amount"]', '12000', '当前金额填 12000');
    await ui.submit('[data-fund-form] button[type="submit"]', '点「添加」');
    let m = cols(row('天弘余额宝货币'));
    await ui.assert(m.nav === 'MMF' && m.unit === 1 && m.shares === 12000 && m.navDate === yday(),
      '真值层:nav_mode MMF · 单价 1 · 份额 = 金额 12000 · 已结转到昨天(录入日的金额含截至前一天的收益)', JSON.stringify(m));
    // fixture:时间上无法自然到达「断了 7 天」→ 把「已结转到」拨回 8 天前(系统写入时两个时间同一刻)
    const back = fundStub.addDays(state.stub.today, -8);
    db.raw(`UPDATE stock_holding SET nav_date='${back}', manual_value_at=NOW(), nav_checked_at=manual_value_at WHERE id=${m.id}`);
    backdateFetch();
    await openHoldings(ui);
    await ui.submit('form[action$="/holdings/refresh"] button', '点「手动刷价」');
    m = cols(row('天弘余额宝货币'));
    await ui.assert(m.shares === 12001.89 && m.navDate === yday(),
      '真值层:逐日结转 7 天(每天 12000 × 0.2253 ÷ 10000 = 0.27)→ 12,001.89,已结转到昨天', JSON.stringify(m));
    const acc1 = events('MMF_ACCRUAL');
    await ui.assert(acc1.length === 1 && acc1[0].startsWith('MMF_ACCRUAL|1.89'),
      '真值层:记了一条「货币基金收益结转 +1.89」(区间 7 天)', acc1.join(' ; '));
    // 点按钮触发的那一次,来源照原有口径记「手动」(定时任务那次才是「自动 · 基金净值」,单测 NavRowGuardTest 钉住)
    await ui.assert(/^MANUAL\|1\.89/.test(lastValEvent() || ''), '真值层:余额 +1.89 记成一条估值事件(点按钮 → 来源「手动」)', lastValEvent());
    const rm = await rowView(ui, '天弘余额宝货币');
    await ui.assert(rm && rm.kind === '货币基金 · 按金额记' && rm.accrual === '+1.89 · 7 天' && rm.date === cn(yday()),
      '持仓页货基行:按金额记 · 最近一次结转「+1.89 · 7 天」· 已结转到昨天', JSON.stringify(rm));
    backdateFetch();
    await ui.submit('form[action$="/holdings/refresh"] button', '再点一次「手动刷价」');
    await ui.assert(cols(row('天弘余额宝货币')).shares === 12001.89 && events('MMF_ACCRUAL').length === 1,
      '真值层:同一天再刷,不再结转(只结一次)', events('MMF_ACCRUAL').join(' ; '));

    // ── 7 · 普通基金净值更新 ─────────────────────────────────────────
    report.section('7 · 今天出了新净值 4.8000 → 填报页点「刷新持仓估值」(第二个刷新入口)→ 单价与日期更新,余额 +20');
    state.stub.setNav('002943', state.stub.today, '4.8000');
    backdateFetch();
    const bal1 = balance();
    await ui.click('header a[href="/entry"] >> nth=0', '顶部导航点「填报」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click('button[hx-post="/entry/refresh-stocks"]', '点「刷新持仓估值」');
    await ui.page.waitForFunction(() => /账户/.test(document.querySelector('#refresh-toast')?.innerText || ''), null, { timeout: 60000 }).catch(() => {});
    const toast = await ui.page.evaluate(() => document.querySelector('#refresh-toast')?.innerText.replace(/\s+/g, ' ') || '');
    await ui.assert(/3 只基金/.test(toast) && !/没拉到/.test(toast), '提示写出刷了几只基金(3 只),没有点名失败', toast);
    g = cols(row('广发多因子混合'));
    await ui.assert(g.unit === 4.8 && g.navDate === state.stub.today, '真值层:单价 4.80 · 净值日期今天', JSON.stringify(g));
    await ui.assert(Math.abs(balance() - bal1 - 20) < 0.01, '真值层:余额 +20(1000 份 × 0.02)', `${bal1} → ${balance()}`);
    await openHoldings(ui);
    r1 = await rowView(ui, '广发多因子混合');
    await ui.assert(r1 && r1.unit === '4.8000' && r1.date === '· ' + cn(state.stub.today), '持仓页写 4.8000 · 今天的日期', JSON.stringify(r1));

    // ── 8 · 改份额:勾 / 不勾现金联动 ────────────────────────────────
    report.section('8 · 改份额 1000 → 1100 勾「用账户里的现金买的」(余额不变)→ 1100 → 1150 不勾(余额 +240)');
    const editShares = async (to, linked, label) => {
      const art = 'article:has(.font-display:text-is("广发多因子混合"))';
      await ui.click(`${art} details[data-nav-edit] summary`, '点「改份额」');
      await ui.fill(`${art} details[data-nav-edit] input[name="shares"]`, String(to), `份额改成 ${to}`);
      if (linked) await ui.page.check(`${art} details[data-nav-edit] input[name="cashLinked"]`);
      await ui.submit(`${art} details[data-nav-edit] button[type="submit"]`, label);
    };
    const cash0 = cash(), bal2 = balance();
    await editShares(1100, true, '保存(勾了现金联动)');
    await ui.assert(cash() === cash0 - 480 && Math.abs(balance() - bal2) < 0.01, '真值层:现金行 −480,余额不变',
      `现金 ${cash0} → ${cash()} · 余额 ${bal2} → ${balance()}`);
    await ui.assert(events('CASH_BUY').length === 2, '真值层:又记一条「申购(用账户现金)」', events('CASH_BUY').join(' ; '));
    await editShares(1150, false, '保存(不勾)');
    await ui.assert(Math.abs(balance() - bal2 - 240) < 0.01, '真值层:不勾 → 余额 +240(算估值变动)', `${bal2} → ${balance()}`);
    await ui.assert(events('MANUAL_EDIT').length === 1, '真值层:记一条「手动改份额」', events().join(' ; '));

    // ── 9 · 时间线 ──────────────────────────────────────────────────
    report.section('9 · 账户详情时间线:「# 持仓数量」一类,筛选能单看它;不进月净额');
    await ui.goto(`/accounts/${state.acc}`);
    await ui.rendered('账户详情');
    await ui.seesText('# 持仓数量', '时间线有「# 持仓数量」类型的条目');
    await ui.seesText('天弘余额宝货币 · 货币基金收益结转', '写「天弘余额宝货币 · 货币基金收益结转」');
    await ui.seesText('+1.89 份', '写「+1.89 份」');
    await ui.seesText('广发多因子混合 · 申购(用账户现金)', '写「广发多因子混合 · 申购(用账户现金)」');
    await ui.choose('type', 'SHARES', 'main form');
    await ui.submit('main form button:has-text("筛选")', '筛选选「# 持仓数量变动」');
    const kinds = await ui.page.evaluate(() => [...document.querySelectorAll('main details li > span:first-child')].map(s => s.textContent.trim()));
    await ui.assert(kinds.length >= 5 && kinds.every(k => k === '# 持仓数量'), '筛选后只剩持仓数量变动', kinds.join(' / '));
    await ui.page.screenshot({ path: '/tmp/e2e-44-timeline-pc.png', fullPage: true }).catch(() => {});

    // ── 10 · 拉不到:不清零、点名 ───────────────────────────────────
    report.section('10 · 数据源查不到 002943 → 点刷新 → 单价不动,行上标出来,刷新结果点名');
    state.stub.failNav('002943', 'empty');
    backdateFetch();
    await openHoldings(ui);
    await ui.submit('form[action$="/holdings/refresh"] button', '点「手动刷价」');
    await ui.seesText('没拉到:广发多因子混合', '刷新结果点名「广发多因子混合」');
    g = cols(row('广发多因子混合'));
    await ui.assert(g.unit === 4.8 && /查不到/.test(g.err), '真值层:单价不动 4.80 · nav_error 记了原因', JSON.stringify(g));
    r1 = await rowView(ui, '广发多因子混合');
    await ui.assert(r1 && r1.badge === '这次没拉到' && /查不到/.test(r1.problem || ''),
      '这一行标「这次没拉到」(净值是今天的,不说「停在」)并写原因', JSON.stringify(r1));
    // 净值日期早于两天、又没拉到 → 才标「净值停在 X 日」(fixture:把净值日期拨回 5 天)
    db.raw(`UPDATE stock_holding SET nav_date = nav_date - INTERVAL 5 DAY WHERE id=${g.id}`);
    await ui.goto(`/accounts/${state.acc}/holdings`);
    const r1b = await rowView(ui, '广发多因子混合');
    await ui.assert(r1b && /^净值停在 /.test(r1b.badge || ''), '净值已经旧了、又没拉到 → 标「净值停在 X 月 X 日」', JSON.stringify(r1b));
    db.raw(`UPDATE stock_holding SET nav_date = nav_date + INTERVAL 5 DAY WHERE id=${g.id}`);
    state.stub.setNav('002943', state.stub.today, '4.8000');

    // ── 11 · 手填转自动 / 改回手填 ──────────────────────────────────
    report.section('11 · 一条手填市值 ¥5,000 的「广发多因子」(穿透认出了 002943)→ 改为按净值自动估值 → 改回手填');
    await ui.click('a:has-text("+ 添加持仓 · 手填市值")', '点「+ 添加持仓 · 手填市值」');
    await ui.fillByName('displayName', '广发多因子');
    await ui.fillByName('shares', '1');
    await ui.fillByName('unitValue', '5000');
    await ui.submit('form button[type="submit"]', '添加手填持仓 1 × 5,000');
    const manualId = db.one(`SELECT id FROM stock_holding WHERE account_id=${state.acc} AND display_name='广发多因子' AND archived_at IS NULL`);
    db.raw(`UPDATE stock_holding SET fund_code='002943', penetrate_state='RESOLVED' WHERE id=${manualId}`);   // fixture:穿透认出的代码
    await openHoldings(ui);
    await ui.click(`article:has(.font-display:text-is("广发多因子")) [data-convert-btn]`, '这一行点「改为按净值自动估值」');
    await ui.rendered('确认页');
    const plan = await ui.page.evaluate(() => document.querySelector('[data-convert-card]').innerText.replace(/\s+/g, ' '));
    await ui.page.screenshot({ path: '/tmp/e2e-44-convert-pc.png', fullPage: false }).catch(() => {});
    await ui.assert(plan.includes('广发多因子混合 · 002943') && /4\.8000/.test(plan) && /1,041\.67 份/.test(plan) && plan.includes('5,000.00'),
      '确认框写出基金全名 + 代码,和「按 … 净值 4.8000,把当前市值 ¥5,000.00 折成 1,041.67 份」', plan.slice(0, 220));
    const bal3 = balance();
    await ui.submit('[data-convert-card] button[type="submit"]', '点「确认改为自动」');
    const cv = cols(db.one(`SELECT CONCAT_WS('|', id, valuation_mode, IFNULL(nav_mode,''), shares, manual_value, IFNULL(nav_date,''), IFNULL(shares_estimated_on,''), IFNULL(nav_error,''), IFNULL(fund_code,'')) FROM stock_holding WHERE id=${manualId}`));
    await ui.assert(cv.nav === 'FUND' && Math.abs(cv.shares - 1041.6667) < 0.00005 && cv.unit === 4.8, '真值层:改成 FUND · 份额 1041.6667 · 单价 4.80', JSON.stringify(cv));
    await ui.assert(Math.abs(balance() - bal3) < 0.01, '真值层:余额不跳(差 < 0.01)', `${bal3} → ${balance()}`);
    await ui.assert(events('CONVERT').length === 1, '真值层:记一条「改为按净值自动估值」', events('CONVERT').join(' ; '));
    await ui.page.once('dialog', d => d.accept());
    await ui.submit(`article:has(.font-display:text-is("广发多因子")) form[action$="/nav-off"] button`, '点「改回手填」并确认');
    const off = db.one(`SELECT CONCAT_WS('|', IFNULL(nav_mode,'-'), manual_value) FROM stock_holding WHERE id=${manualId}`);
    await ui.assert(off.startsWith('-|4.8'), '真值层:nav_mode 清空,单价停在 4.80', off);
    await ui.assert(Math.abs(balance() - bal3) < 0.01, '真值层:改回手填余额不变', `${bal3} → ${balance()}`);

    // ── 11.5 · 有基金账户时,其它页面照常 ────────────────────────────
    report.section('11.5 · 有基金账户、基金行、货基行时,首页 / 报表 / 资产体检照常渲染、控制台无报错');
    for (const [href, label] of [['/dashboard', '仪表盘'], ['/reports', '报表'], ['/checkup', '资产体检']]) {
      await ui.click(`header a[href="${href}"] >> nth=0`, `顶部导航点「${label}」`);
      await ui.page.waitForLoadState('networkidle').catch(() => {});
      await ui.rendered(label);
    }
    await ui.noConsoleErrors('这几个页面控制台无报错');

    // ── 12 · 别的账户:§3.1 ──────────────────────────────────────────
    report.section('12 · 别的账户类型:人民币证券 / 现金有「场外基金」,加密、美元证券没有');
    const pickAcc = (sql) => db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND archived_at IS NULL AND ${sql} ORDER BY id LIMIT 1`);
    for (const [sql, expect, label] of [
      ["type='CASH' AND currency='CNY'", true, '人民币现金账户'],
      ["type='STOCK' AND currency='CNY'", true, '人民币证券账户'],
      ["type='CRYPTO'", false, '加密账户'],
      ["type='STOCK' AND currency='USD'", false, '美元证券账户'],
    ]) {
      const id = pickAcc(sql);
      if (!id) { report.info(`beta 上没有${label},跳过`); continue; }
      await ui.goto(`/accounts/${id}/holdings`);
      const has = await ui.page.locator('a[data-add-fund]').count();
      await ui.assert((has > 0) === expect, `${label}(#${id})「添加持仓」里${expect ? '有' : '没有'}「场外基金」`, `找到 ${has} 个`);
    }

    // ── 13 · 手机 ───────────────────────────────────────────────────
    report.section('13 · 手机 390px:持仓页不撑出横向滚动');
    const vp = ui.page.viewportSize();
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await ui.goto(`/accounts/${state.acc}/holdings`);
    const over = await ui.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    await ui.assert(over <= 1, '手机上持仓页没有横向滚动', `溢出 ${over}px`);
    const widths = await ui.page.evaluate(() => {
      const a = [...document.querySelectorAll('article')].find(x => x.querySelector('[data-nav-row="FUND"]'));
      return a ? [...a.querySelectorAll('details[data-nav-edit] > summary, form[action$="/nav-off"] button, form[action$="/archive"] button')]
        .map(e => Math.round(e.getBoundingClientRect().width)) : [];
    });
    await ui.assert(widths.length === 3 && new Set(widths).size === 1, '手机上基金行的「改份额 / 改回手填 / 归档」三个按钮同宽', widths.join(' / '));
    await ui.page.screenshot({ path: '/tmp/e2e-44-holdings-mobile.png', fullPage: true }).catch(() => {});
    await ui.page.setViewportSize(vp);
    await ui.goto(`/accounts/${state.acc}/holdings`);
    await ui.page.screenshot({ path: '/tmp/e2e-44-holdings-pc.png', fullPage: true }).catch(() => {});
    await ui.assert(state.stub.hits.filter(h => h.path === '/f10/lsjz').every(h => h.referer), '真值层:应用请求净值接口都带了 Referer(不带会静默拿到空数据)');
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    await ui.page.unroute(/\/checkup\/(diagnose|insight)/).catch(() => {});
    if (state.stub) state.stub.server.close();
    if (state.acc) {
      const a = state.acc;
      for (const sql of [
        `DELETE FROM holding_share_event WHERE account_id=${a}`,
        `DELETE FROM holding_allocation WHERE holding_id IN (SELECT id FROM stock_holding WHERE account_id=${a})`,
        `DELETE FROM stock_valuation_event WHERE account_id=${a}`,
        `DELETE FROM stock_holding WHERE account_id=${a}`,
        `DELETE FROM snapshot_todo WHERE account_id=${a}`,
        `DELETE FROM period_account_attr WHERE account_id=${a}`,
        `DELETE FROM period_snapshot WHERE account_id=${a}`,
        `DELETE FROM cash_flow WHERE account_id=${a}`,
        `DELETE FROM account_group_member WHERE account_id=${a}`,
        `DELETE FROM account WHERE id=${a} AND family_id=${fx.FAM} AND display_name='${ACC_NAME}'`,
      ]) { try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 60)} · ${String(e.message).slice(0, 80)}`); } }
    }
    try { db.raw(`DELETE FROM fund_nav_snapshot WHERE fund_code IN ('${CODES.join("','")}')`); } catch (e) { /* 只是缓存 */ }
    if (state.before === null || state.before === undefined) db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);
    else db.raw(`UPDATE family_runtime_config SET value_text='${String(state.before).replace(/'/g, "''")}' WHERE family_id=${fx.FAM} AND key_name='${KEY}'`);
  },
};
