/**
 * flow · issue #26(续)· 券商同步完,同步进来的股票当场就有价,余额不用再手动刷一次
 *
 * @Jsonya 2026-10-05:「立即同步并没有触发价格的同步刷新,需要手动刷价一次才正常显示出真实的价格,
 * 这个会影响实际的账户净值」。根因:同步只把「代码 + 股数」写进持仓,接着就按库里**已有的**行情快照估值 ——
 * 第一次出现的代码没有快照(按 0 计、持仓页标「无价」),老代码用的是上一次拉的价(「行情自动拉取」新装默认关,
 * 那就是上一次有人手动点刷新的那天)。
 *
 * 不打真实 IBKR:本机桩回放合成报表(美元账户:现金 8,350 + AAPL 20 股 + KO 10 股;第二份报表再多一只 PEP 5 股)。
 * KO / PEP 在 beta 上没有任何持仓,前置里清掉它俩的行情快照 = 「从来没拉过价的新代码」;AAPL 有别的账户在持,
 * 它的快照不动,只核对同步后它的快照是不是今天的。价格本身走真实的新浪 / 腾讯(和用户环境一样)。
 *
 * 全程从页面发起:管理首页 →「券商同步」→ 填口令 → 测试连接 → 账户列表点「券商」→ 选盈透 → 关联并同步
 * → 账户详情 →「持仓管理」(没有「无价」、估值分解对得上);再换一份报表 → 点「立即同步」→ 新代码当场有价。
 */
const fs = require('fs');
const path = require('path');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const ibkrStub = require('../lib/ibkr-stub.cjs');

const RAW = fs.readFileSync(path.join(__dirname, '../../../src/test/resources/ibkr/flex-sample-derivatives.xml'), 'utf8');
const KEYS = ['broker_ibkr_flex_token', 'broker_ibkr_flex_query_id', 'broker_ibkr_token_expires_on',
              'broker_ibkr_accounts', 'broker_ibkr_flex_base_url'];
const ACC_NAME = 'e2e · 盈透同步刷价';
const ACCT = 'U5550043';
const GOOD = '434343434343434343434343';
const EXPIRED = '999988887777666655554444';
const FRESH = ['KO', 'PEP'];            // beta 上没人持有的代码:同步前清掉快照
const state = {};

// 报表:只留现金 + 股票(期权等不是这条的事),账号换掉,再加一只 KO
const pos = (sym, desc, qty, px) =>
  `        <OpenPosition accountId="${ACCT}" currency="USD" assetCategory="STK" symbol="${sym}" description="${desc}" listingExchange="NYSE" position="${qty}" markPrice="${px}" positionValue="${(qty * px).toFixed(2)}" costBasisPrice="${px}" side="Long" levelOfDetail="SUMMARY" />`;
const base = RAW.replace(/U5550001/g, ACCT).split('\n')
  .filter(l => !/<OpenPosition /.test(l) || /assetCategory="STK"/.test(l))
  .join('\n');
const SAMPLE = base.replace(/(\s*<\/OpenPositions>)/, `\n${pos('KO', 'COCA-COLA CO', 10, 60)}$1`);
const SAMPLE2 = SAMPLE.replace(/(\s*<\/OpenPositions>)/, `\n${pos('PEP', 'PEPSICO INC', 5, 150)}$1`);

const today = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date());
const plusDays = (n) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date(Date.now() + n * 86400000));
const cfg = (k) => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
const snapDate = (t) => db.one(`SELECT DATE_FORMAT(MAX(trade_date),'%Y-%m-%d') FROM stock_price_snapshot WHERE ticker='${t}' AND market='US'`);
const px = (t) => Number(db.one(`SELECT close_price FROM stock_price_snapshot WHERE ticker='${t}' AND market='US' ORDER BY trade_date DESC LIMIT 1`));
const shares = (t) => Number(db.one(`SELECT shares FROM stock_holding WHERE account_id=${state.acc} AND ticker='${t}' AND archived_at IS NULL`));
const balance = () => Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.cur} AND account_id=${state.acc}`));
const cash = () => Number(db.one(`SELECT COALESCE(SUM(manual_value),0) FROM stock_holding WHERE account_id=${state.acc} AND archived_at IS NULL AND valuation_mode='CASH'`));

async function openHoldings(ui) {
  await ui.goto('/accounts');
  await ui.click(`main a[href="/accounts/${state.acc}"] >> nth=0`, `账户列表点「${ACC_NAME}」`);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.click(`a[href="/accounts/${state.acc}/holdings"]`, '账户详情点「持仓管理」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.rendered('持仓页');
}

/** 余额 = 现金 + Σ 股数 × 今天的价(美元账户,不用换汇) */
async function checkValued(ui, report, tickers, label) {
  const t = today();
  for (const tk of tickers) {
    const d = snapDate(tk);
    await ui.assert(d === t, `真值层:${tk} 的行情快照是今天(${t})拉的 —— 同步当场拉了价,不是用旧价 / 没有价`, `最新快照 ${d || '无'}`);
  }
  const expect = cash() + tickers.reduce((s, tk) => s + shares(tk) * px(tk), 0);
  const end = balance();
  report.info(`${label}:现金 ${cash()} + ${tickers.map(tk => `${tk} ${shares(tk)}×${px(tk)}`).join(' + ')} = ${expect.toFixed(2)} · 余额 ${end}`);
  await ui.assert(Math.abs(end - expect) < 0.05, `真值层:账户余额 = 现金 + 各股 股数 × 今天的价(${label})`, `余额 ${end} · 应为 ${expect.toFixed(2)}`);
  return expect;
}

module.exports = {
  name: '43-broker-sync-prices',
  title: 'issue #26(续)· 券商同步(关联时 / 立即同步)当场给同步进来的股票拉价,余额不用再手动刷一次',

  async run(ui, report) {
    ui.flow = this.name;
    await ui.page.route(/\/checkup\/(diagnose|insight)/, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));

    // ── 前置 ──────────────────────────────────────────────────────────
    state.before = Object.fromEntries(KEYS.map(k => [k, cfg(k)]));
    const stub = await ibkrStub.start(SAMPLE, EXPIRED);
    state.srv = stub.server;
    db.raw(`INSERT INTO family_runtime_config (family_id, key_name, value_text) VALUES (${fx.FAM}, 'broker_ibkr_flex_base_url', 'http://127.0.0.1:${stub.port}/fws')
            ON DUPLICATE KEY UPDATE value_text = VALUES(value_text)`);
    const held = db.one(`SELECT COUNT(*) FROM stock_holding WHERE ticker IN ('KO','PEP') AND archived_at IS NULL`);
    await ui.assert(Number(held) === 0, '前置:beta 上没人持有 KO / PEP(清它俩的快照不影响任何账户)', `持有行 ${held}`);
    db.raw(`DELETE FROM stock_price_snapshot WHERE ticker IN ('KO','PEP') AND market='US'`);
    db.raw(`INSERT INTO account (family_id, display_name, type, currency, display_order) VALUES (${fx.FAM}, '${ACC_NAME}', 'STOCK', 'USD', 999)`);
    state.acc = db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND display_name='${ACC_NAME}' ORDER BY id DESC LIMIT 1`);
    state.cur = fx.currentPeriod();
    report.info(`前置:IBKR 桩 127.0.0.1:${stub.port} · 新建空的美元证券账户 #${state.acc} · KO / PEP 无快照 · AAPL 最新快照 ${snapDate('AAPL') || '无'}`);
    await ui.page.waitForTimeout(6000);   // 家庭配置有 5 秒缓存,等桩地址生效

    // ── 1 · 配口令 → 测试连接 ────────────────────────────────────────
    report.section('1 · 管理首页 →「券商同步」→ 填口令 → 测试盈透连接');
    await ui.goto('/admin');
    await ui.click('main a[href="/admin/broker"] >> nth=0', '点「券商同步」卡片');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.fill('input[name="ibkrToken"]', GOOD, '填报表口令');
    await ui.fill('input[name="ibkrQuery"]', '1045873', '填查询号');
    await ui.fill('input[name="ibkrExpires"]', plusDays(200), '填口令到期日');
    await ui.submit('form#broker button:has-text("保存券商配置")', '保存券商配置');
    await ui.submit('button:has-text("测试盈透 IBKR 连接")', '点「测试盈透 IBKR 连接」');
    await ui.seesText('2 笔股票', '测试结果:找到账户,2 笔股票(AAPL / KO)');

    // ── 2 · 关联并同步 → 当场有价 ────────────────────────────────────
    report.section('2 · 账户列表点「券商」→ 选盈透 → 关联并同步 → 同步进来的 AAPL / KO 当场按今天的价估值');
    await ui.goto('/accounts');
    await ui.click(`a[href="/accounts/${state.acc}/broker"] >> nth=0`, `在账户列表点「${ACC_NAME}」的「券商」`);
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click('#vendorPick label:has(input[value="IBKR"])', '选「盈透 IBKR」');
    await ui.selectByName('brokerAccountId', ACCT, '#acctIbkr');
    await ui.page.check('input[name="acknowledged"]');
    await ui.page.check('input[name="confirmed"]');
    await ui.click('#lnkBtn', '点「关联并同步」');
    await Promise.all([
      ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 90000 }).catch(() => {}),
      ui.click('#lnkConfirm', '弹窗里点「确认关联并同步」'),
    ]);
    await ui.page.waitForTimeout(800);
    report.info(`同步结果:${db.one(`SELECT last_status FROM broker_link WHERE account_id=${state.acc}`) || ''}`);
    const e1 = await checkValued(ui, report, ['AAPL', 'KO'], '关联并同步之后');

    // ── 3 · 持仓页:不需要再点刷新 ───────────────────────────────────
    report.section('3 · 账户详情 →「持仓管理」:没有「无价」,估值分解的「自动拉价」= 两只股票的市值');
    await openHoldings(ui);
    await ui.notSeesText('无价', '没有哪一行标「无价」(KO 是第一次出现的代码)');
    await ui.notSeesText('待补充 1', '余额下方没有「待补充」提示');
    const auto = await ui.page.evaluate(() => {
      const el = document.querySelector('#valuation-breakdown');
      const m = el && el.innerText.replace(/\s+/g, ' ').match(/自动拉价 USD ([\d,]+)/);
      return m ? Number(m[1].replace(/,/g, '')) : null;
    });
    const expectAuto = e1 - cash();
    await ui.assert(auto !== null && Math.abs(auto - Math.round(expectAuto)) <= 1, `估值分解「自动拉价」≈ ${Math.round(expectAuto)}(不是 0、不是旧价算的)`, `页面 ${auto}`);
    await ui.noConsoleErrors('持仓页控制台无报错');

    // ── 4 · 立即同步:报表里多了一只 PEP ──────────────────────────────
    report.section('4 · 过几天买了 PEP:点「立即同步」→ PEP 当场有价,余额对得上(提交者说的就是这个按钮)');
    stub.setReport(SAMPLE2);
    // 盈透的报表在服务器上缓存 10 分钟(Flex 有频率限制);「测试连接」总是取最新的 —— 与 flow 41 第 6 段同一做法
    await ui.goto('/admin');
    await ui.click('main a[href="/admin/broker"] >> nth=0', '点「券商同步」卡片');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.submit('button:has-text("测试盈透 IBKR 连接")', '点「测试盈透 IBKR 连接」(取最新报表)');
    await ui.seesText('3 笔股票', '新报表:3 笔股票(多了 PEP)');
    await ui.goto('/accounts');
    await ui.click(`a[href="/accounts/${state.acc}/broker"] >> nth=0`, `在账户列表点「${ACC_NAME}」的「券商」`);
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.submit('form[action$="/broker/sync"] button', '点「立即同步」');
    const status2 = db.one(`SELECT last_status FROM broker_link WHERE account_id=${state.acc}`) || '';
    report.info(`同步结果:${status2}`);
    await ui.assert(shares('PEP') === 5, '真值层:PEP 5 股同步进来了', `PEP ${shares('PEP')}`);
    await checkValued(ui, report, ['AAPL', 'KO', 'PEP'], '立即同步之后');
    await openHoldings(ui);
    await ui.notSeesText('无价', '持仓页没有「无价」(PEP 也是第一次出现)');
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    await ui.page.unroute(/\/checkup\/(diagnose|insight)/).catch(() => {});
    if (state.srv) state.srv.close();
    if (state.acc) {
      const a = state.acc;
      for (const sql of [
        `DELETE FROM stock_valuation_event WHERE account_id=${a}`,
        `DELETE FROM stock_holding WHERE account_id=${a}`,
        `DELETE FROM broker_link WHERE account_id=${a}`,
        `DELETE FROM snapshot_todo WHERE account_id=${a}`,
        `DELETE FROM period_account_attr WHERE account_id=${a}`,
        `DELETE FROM period_snapshot WHERE account_id=${a}`,
        `DELETE FROM cash_flow WHERE account_id=${a}`,
        `DELETE FROM account_group_member WHERE account_id=${a}`,
        `DELETE FROM account WHERE id=${a} AND family_id=${fx.FAM} AND display_name='${ACC_NAME}'`,
      ]) { try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 60)} · ${String(e.message).slice(0, 80)}`); } }
    }
    // KO / PEP 只有这条 flow 用过(前置里断言过没人持有),快照清掉回到原样
    try { db.raw(`DELETE FROM stock_price_snapshot WHERE ticker IN ('KO','PEP') AND market='US'`); } catch (e) { /* 无所谓 */ }
    for (const k of KEYS) {
      const v = state.before ? state.before[k] : null;
      if (v === null || v === undefined) db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
      else db.raw(`UPDATE family_runtime_config SET value_text='${String(v).replace(/'/g, "''")}' WHERE family_id=${fx.FAM} AND key_name='${k}'`);
    }
  },
};
