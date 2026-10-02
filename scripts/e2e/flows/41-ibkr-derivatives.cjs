/**
 * flow · v1.29 · issue #26 · 盈透同步带上期权 / 期货 / 债券:账户余额和盈透对得上
 *
 * 不打真实 IBKR:本机 HTTP 桩回放合成报表 src/test/resources/ibkr/flex-sample-derivatives.xml
 * (期权行的形状照 #26 里 @Jsonya 贴的真实报表:买入 position=1、卖出 position=-1,positionValue 已乘乘数、卖出为负;数全是编的)。
 * 卖出的那张 SPY 看跌的到期日在这里换成「三天后」,用来看「7 天内到期」。
 *
 * 维护者 2026-10-03 定的口径(PRD §13):
 *   ② 新账户第一次同步 = 开账(这个月不算收益);已经在记的账户,期权等第一次被算进来那一跳 = 当月收益。
 *   ③ 期权跟着账户类型走,不单列一类。
 *
 * 全程从页面发起:管理首页 →「券商同步」卡片 → 填口令 → 测试连接(看到期权几笔)→ 账户列表点「券商」→ 选盈透 → 关联并同步
 * → 账户列表 → 账户详情 →「持仓管理」看人话行 / 估值分解 → 首页账户表「本期损益」;
 * 再换一份报表(那张 SPY 看跌到期了)→ 测试连接 → 立即同步 → 那一行被归档。
 *
 * 账户用人民币记账(报表是美元)—— 顺带走一遍「期权市值折成账户币种」;首页看的也是人民币,不用再换汇率核对。
 *
 * 前置:建一个空的人民币证券账户。「已经在记的账户」那一段要一个上个月就有余额的账户:
 * 时间上无法自然到达(要等一个月),所以在同步之后往上一期补一条快照 = 「上个月只有现金 + 股票」(fixture 纪律 1)。
 * cleanup 把账户连同同步来的一切删干净,IBKR 配置键恢复原样。
 */
const fs = require('fs');
const path = require('path');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const ibkrStub = require('../lib/ibkr-stub.cjs');

const RAW = fs.readFileSync(path.join(__dirname, '../../../src/test/resources/ibkr/flex-sample-derivatives.xml'), 'utf8');
const KEYS = ['broker_ibkr_flex_token', 'broker_ibkr_flex_query_id', 'broker_ibkr_token_expires_on',
              'broker_ibkr_accounts', 'broker_ibkr_flex_base_url'];
const ACC_NAME = 'e2e · 盈透期权';
const GOOD = '222233334444555566667777';
const EXPIRED = '999988887777666655554444';
const state = {};

// 按服务器所在时区(Asia/Shanghai)算「今天 + n 天」
const plusDays = (n) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' })
  .format(new Date(Date.now() + n * 86400000));
const SOON = plusDays(3);                                   // 2026-10-06 这种
const SAMPLE = RAW.replace('expiry="20261009"', `expiry="${SOON.replace(/-/g, '')}"`);
// 第二份报表:SPY 那张看跌到期了(报表里没了)
const SAMPLE2 = SAMPLE.split('\n').filter(l => !l.includes('SPY   261009P00560000')).join('\n');

const cfg = (k) => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
const derivRows = () => db.col(`SELECT CONCAT(instrument_kind,'|',ticker,'|',shares,'|',manual_value,'|',IFNULL(notional,''))
                                  FROM stock_holding WHERE account_id=${state.acc} AND archived_at IS NULL
                                   AND instrument_kind IS NOT NULL ORDER BY id`);
/** 期权等在账户币种里的合计(单价 × 张数)—— 估值写回余额用的就是这个数 */
const derivTotal = () => Number(db.one(`SELECT COALESCE(SUM(manual_value * shares),0) FROM stock_holding
                                          WHERE account_id=${state.acc} AND archived_at IS NULL AND instrument_kind IS NOT NULL`));

/** 首页账户表里这个账户的「本期损益」(data-val 是原值,不受千分位 / 隐私模式影响) */
async function periodPnl(ui) {
  await ui.goto('/');
  await ui.page.waitForSelector('td.acct-sticky', { timeout: 60000 }).catch(() => {});
  return ui.page.evaluate((name) => {
    const tr = [...document.querySelectorAll('tr')].find(r => r.querySelector('td.acct-sticky')?.textContent.includes(name));
    if (!tr) return { row: false };
    const td = tr.querySelector('td[data-mcol="period_return"]');
    return { row: true, col: !!td, val: td ? td.getAttribute('data-val') : null, text: td ? td.textContent.trim() : null };
  }, ACC_NAME);
}

async function openHoldings(ui) {
  await ui.goto('/accounts');
  await ui.click(`main a[href="/accounts/${state.acc}"] >> nth=0`, `账户列表点「${ACC_NAME}」`);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.click(`a[href="/accounts/${state.acc}/holdings"]`, '账户详情点「持仓管理」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.rendered('持仓页');
}

module.exports = {
  name: '41-ibkr-derivatives',
  title: 'v1.29 · 盈透同步带上期权 / 期货 / 债券:余额对得上 · 卖出为负 · 到期归档 · 新账户算开账 / 老账户算收益',

  async run(ui, report) {
    ui.flow = this.name;
    await ui.page.route(/\/checkup\/(diagnose|insight)/, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));

    // ── 前置 ──────────────────────────────────────────────────────────
    state.before = Object.fromEntries(KEYS.map(k => [k, cfg(k)]));
    const stub = await ibkrStub.start(SAMPLE, EXPIRED);
    state.srv = stub.server;
    state.stub = stub;
    db.raw(`INSERT INTO family_runtime_config (family_id, key_name, value_text) VALUES (${fx.FAM}, 'broker_ibkr_flex_base_url', 'http://127.0.0.1:${stub.port}/fws')
            ON DUPLICATE KEY UPDATE value_text = VALUES(value_text)`);
    db.raw(`INSERT INTO account (family_id, display_name, type, currency, display_order) VALUES (${fx.FAM}, '${ACC_NAME}', 'STOCK', 'CNY', 999)`);
    state.acc = db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND display_name='${ACC_NAME}' ORDER BY id DESC LIMIT 1`);
    state.prev = fx.lastEndedPeriod();
    state.cur = fx.currentPeriod();
    report.info(`前置:本机 IBKR 桩 127.0.0.1:${stub.port} · 新建空的人民币证券账户 #${state.acc} · SPY 看跌到期日换成 ${SOON}`);
    await ui.page.waitForTimeout(6000);   // 家庭配置有 5 秒缓存,等桩地址生效

    // ── 1 · 配口令 → 测试连接:看得到期权几笔 ─────────────────────────
    report.section('1 · 管理首页 →「券商同步」→ 填口令 → 测试连接(期权 / 期货 / 债券各几笔,对不上的几行)');
    await ui.goto('/admin');
    await ui.click('main a[href="/admin/broker"] >> nth=0', '点「券商同步」卡片');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.fill('input[name="ibkrToken"]', GOOD, '填报表口令');
    await ui.fill('input[name="ibkrQuery"]', '1045873', '填查询号');
    await ui.fill('input[name="ibkrExpires"]', plusDays(200), '填口令到期日');
    await ui.submit('form#broker button:has-text("保存券商配置")', '保存券商配置');
    await ui.submit('button:has-text("测试盈透 IBKR 连接")', '点「测试盈透 IBKR 连接」');
    await ui.seesText('期权 3 笔', '测试结果:期权 3 笔(一买一卖的价差 + 一张卖出的看跌)');
    await ui.seesText('期货 1 笔', '测试结果:期货 1 笔');
    await ui.seesText('债券 1 笔', '测试结果:债券 1 笔');
    await ui.seesText('2 行数据对不上、不会同步', '测试结果:缺市值 / 没乘乘数的两行,事先就说不会同步');
    await ui.notSeesText('期权 / 期货 / 债券不同步', '旧文案「期权 / 期货 / 债券不同步」不再出现');

    // ── 2 · 关联并同步 ───────────────────────────────────────────────
    report.section('2 · 账户列表点「券商」→ 选盈透 → 关联并同步 → 同步结果');
    await ui.goto('/accounts');
    await ui.click(`a[href="/accounts/${state.acc}/broker"] >> nth=0`, `在账户列表点「${ACC_NAME}」的「券商」`);
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click('#vendorPick label:has(input[value="IBKR"])', '选「盈透 IBKR」');
    await ui.selectByName('brokerAccountId', 'U5550001', '#acctIbkr');
    await ui.page.check('input[name="acknowledged"]');
    await ui.page.check('input[name="confirmed"]');
    await ui.click('#lnkBtn', '点「关联并同步」');
    await Promise.all([
      ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 90000 }).catch(() => {}),
      ui.click('#lnkConfirm', '弹窗里点「确认关联并同步」'),
    ]);
    await ui.page.waitForTimeout(800);
    const status = db.one(`SELECT last_status FROM broker_link WHERE account_id=${state.acc}`) || '';
    report.info(`同步结果:${status}`);
    await ui.assert(/期权 3 笔 · 市值 CNY/.test(status), '同步结果写「期权 3 笔 · 市值 …」(不再说跳过期权)', status);
    await ui.assert(status.includes('期货 1 笔(不计入余额)'), '同步结果写明期货不计入余额', status);
    await ui.assert(status.includes('债券 1 笔'), '同步结果写债券', status);
    await ui.assert(status.includes('跳过其他品种 1'), '差价合约仍跳过计数,改叫「其他品种」', status);
    await ui.assert(/2 行数据对不上没同步\(TSLA · 看涨 · 行权价 300/.test(status), '对不上的行点名(TSLA 缺持仓市值)', status);

    // 真值层
    const rows = derivRows();
    report.info(`期权等:${rows.join(' ; ')}`);
    await ui.assert(rows.length === 5, '真值层:期权 3 + 期货 1 + 债券 1 = 5 行(TSLA / NVDA 两行没进来)', `共 ${rows.length} 行`);
    await ui.assert(rows.some(r => /^OPTION\|SPY\s+261009P00560000\|-2\.0+\|/.test(r)), '真值层:卖出的 SPY 看跌张数是 -2');
    await ui.assert(rows.some(r => /^FUTURE\|ESZ6\|1\.0+\|0\.0+\|300000/.test(r)), '真值层:期货单价 0、名义价值 300000 只存不计');
    await ui.assert(!rows.some(r => /TSLA|NVDA/.test(r)), '真值层:数据对不上的两行没有同步');
    const usdcny = 1 / Number(db.one(`SELECT rate FROM fx_rate WHERE family_id=${fx.FAM} AND period_id=${state.cur} AND base_currency='CNY' AND quote_currency='USD' ORDER BY id DESC LIMIT 1`));
    const D = derivTotal();
    const expectD = (2850 - 1620 - 340 + 9850) * usdcny;     // 期权 890 + 债券 9850(美元),期货 0
    await ui.assert(Math.abs(D - expectD) < 0.5, `真值层:期权等合计 = (890 + 9850) 美元 × 汇率 ≈ ${expectD.toFixed(2)} 元`, `实际 ${D.toFixed(2)}`);
    const end = Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.cur} AND account_id=${state.acc}`));
    const parts = Number(db.one(`SELECT COALESCE(SUM(CASE WHEN valuation_mode='MANUAL' THEN manual_value*shares ELSE 0 END),0) FROM stock_holding WHERE account_id=${state.acc} AND archived_at IS NULL`));
    await ui.assert(end > D && parts > 0, '真值层:余额写回了(现金 + 股票 + 期权等)', `余额 ${end} · 期权等 ${D.toFixed(2)}`);

    // ── 3 · 持仓页 ───────────────────────────────────────────────────
    report.section('3 · 账户列表 → 详情 →「持仓管理」:期权一行写人话,卖出赭色,7 天内到期标出来');
    await openHoldings(ui);
    await ui.seesText('GOOGL · 看涨 · 行权价 360 · 2027-04-16 到期', '期权标题写人话(标的 · 看涨 · 行权价 · 到期日)');
    await ui.seesText('GOOGL · 看涨 · 行权价 400 · 2027-04-16 到期', '价差的卖出那条腿也在');
    await ui.seesText(`SPY · 看跌 · 行权价 560 · ${SOON} 到期`, '卖出的看跌');
    await ui.seesText('ES · 期货', '期货一行');
    await ui.seesText('T 4 1/4 11/15/34 · 债券', '债券一行');
    const card = await ui.page.evaluate(() => [...document.querySelectorAll('article')].map(a => ({
      title: a.querySelector('.font-display')?.textContent.trim(),
      kind: a.querySelector('[data-deriv-kind]')?.textContent.trim() || null,
      due: a.querySelector('[data-deriv-due]')?.textContent.trim() || null,
      side: a.querySelector('[data-deriv-row] .tnum')?.textContent.trim() || null,
      sideRust: !!a.querySelector('[data-deriv-row] .tnum.text-rust'),
      manualPill: [...a.querySelectorAll('.pill')].some(p => p.textContent.trim() === '手填市值'),
      updateForm: !!a.querySelector('form[action$="/update"]'),
      text: a.innerText.replace(/\s+/g, ' '),
    })));
    const by = (re) => card.find(c => re.test(c.title || ''));
    const spy = by(/^SPY/), lc = by(/行权价 360/), sc = by(/行权价 400/), es = by(/^ES/);
    await ui.assert(spy && spy.kind === '期权' && spy.due === '7 天内到期', 'SPY 那张标「期权」+「7 天内到期」', JSON.stringify(spy));
    await ui.assert(lc && lc.side === '买入 1 张' && !lc.sideRust, '买入的写「买入 1 张」', JSON.stringify(lc));
    await ui.assert(sc && sc.side === '卖出 1 张' && sc.sideRust, '卖出的写「卖出 1 张」并用赭色', JSON.stringify(sc));
    await ui.assert(spy && spy.text.includes('卖出的从余额里减去'), '卖出的那行说明「从余额里减去」');
    await ui.assert(es && es.text.includes('名义价值(不计入余额)') && es.text.includes('USD 300,000'), '期货写名义价值、不计入余额', es && es.text);
    await ui.assert(card.filter(c => c.kind).every(c => !c.manualPill && !c.updateForm),
                    '期权等不挂「手填市值」徽章、没有手改表单(券商同步行,下次同步会覆盖)');
    const bd = await ui.page.evaluate(() => {
      const el = document.querySelector('#valuation-breakdown');
      return el ? el.innerText.replace(/\s+/g, ' ') : '';
    });
    await ui.assert(/期权等\(券商报表\)/.test(bd), '估值分解单列「期权等(券商报表)」一格', bd);
    const shown = Number((bd.match(/期权等\(券商报表\) CNY ([\d,\-]+)/) || [])[1]?.replace(/,/g, ''));
    await ui.assert(Math.abs(shown - Math.round(D)) <= 1, `估值分解里期权等 = 真值 ${Math.round(D)}`, `页面 ${shown}`);
    await ui.noConsoleErrors('持仓页控制台无报错');

    // 手机:标题换行、不撑出横向滚动
    const vp = ui.page.viewportSize();
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await ui.page.reload({ waitUntil: 'networkidle' });
    const mob = await ui.page.evaluate(() => ({
      over: document.documentElement.scrollWidth - window.innerWidth,
      titles: [...document.querySelectorAll('article .font-display')].filter(e => e.getBoundingClientRect().right > window.innerWidth + 1).length,
    }));
    await ui.assert(mob.over <= 1 && mob.titles === 0, '手机 390px:期权标题换行,不撑出横向滚动', JSON.stringify(mob));
    await ui.page.screenshot({ path: '/tmp/e2e-41-holdings-mobile.png', fullPage: true }).catch(() => {});   // 双端截图给人看排版(UED 自查)
    await ui.page.setViewportSize(vp);
    await ui.page.reload({ waitUntil: 'networkidle' });
    await ui.page.screenshot({ path: '/tmp/e2e-41-holdings-pc.png', fullPage: true }).catch(() => {});

    // ── 4 · 新账户第一次同步 = 开账 ──────────────────────────────────
    report.section('4 · 首页账户表:新账户第一次同步算开账,这个月「本期损益」为 0');
    const p1 = await periodPnl(ui);
    await ui.assert(p1.row && p1.col, '首页账户表里有这个账户、有「本期损益」列', JSON.stringify(p1));
    await ui.assert(Math.abs(Number(p1.val || 0)) < 0.01, '新账户第一次同步:期权等都算开账时就有的,本期损益 = 0', JSON.stringify(p1));

    // 给维护者在 beta 上留一份可点的现场(E2E_KEEP=1 E2E_41_DEMO=1):停在「新账户刚同步完」,不往上一期补快照、不归档
    if (process.env.E2E_41_DEMO === '1') { report.info('演示模式:停在第 4 段,留现场'); return; }

    // ── 5 · 已经在记的账户:那一跳算当月收益 ─────────────────────────
    report.section('5 · 已经在记的账户:上个月只有现金 + 股票 → 这个月期权等第一次被算进来 → 计入本期损益');
    const S = Math.round((end - D) * 100) / 100;
    const submitter = db.one(`SELECT id FROM member WHERE family_id=${fx.FAM} AND archived_at IS NULL ORDER BY id LIMIT 1`);
    db.raw(`INSERT INTO period_snapshot (period_id, account_id, end_balance, submitted_by, note)
            VALUES (${state.prev}, ${state.acc}, ${S}, ${submitter}, 'e2e-41 · 上个月只有现金 + 股票')`);
    report.info(`前置:上一期(#${state.prev})补一条快照 ${S} 元 = 本期余额 ${end} − 期权等 ${D.toFixed(2)}`);
    const p2 = await periodPnl(ui);
    await ui.assert(Math.abs(Number(p2.val) - (end - S)) < 0.05,
                    `已经在记的账户:本期损益 = 期权等第一次计入的那一跳 ≈ ${(end - S).toFixed(2)}`, JSON.stringify(p2));

    // ── 6 · 到期 → 下次同步归档 ─────────────────────────────────────
    report.section('6 · 过了几天 SPY 那张到期了:测试连接(取新报表)→ 立即同步 → 那一行被归档');
    stub.setReport(SAMPLE2);
    await ui.goto('/admin');
    await ui.click('main a[href="/admin/broker"] >> nth=0', '点「券商同步」卡片');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.submit('button:has-text("测试盈透 IBKR 连接")', '点「测试盈透 IBKR 连接」(取最新报表)');
    await ui.seesText('期权 2 笔', '新报表里期权只剩 2 笔');
    await ui.goto('/accounts');
    await ui.click(`a[href="/accounts/${state.acc}/broker"] >> nth=0`, `在账户列表点「${ACC_NAME}」的「券商」`);
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.submit('form[action$="/broker/sync"] button', '点「立即同步」');
    const status2 = db.one(`SELECT last_status FROM broker_link WHERE account_id=${state.acc}`) || '';
    await ui.assert(/归档 1/.test(status2) && /期权 2 笔/.test(status2), '同步结果:归档 1 · 期权 2 笔', status2);
    await ui.assert(!derivRows().some(r => r.includes('SPY')), '真值层:到期的 SPY 那一行不在活动持仓里了(已归档,不留残行)');
    await openHoldings(ui);
    await ui.notSeesText('SPY · 看跌', '持仓页上没有那张到期的看跌了');
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
    for (const k of KEYS) {
      const v = state.before ? state.before[k] : null;
      if (v === null || v === undefined) db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
      else db.raw(`UPDATE family_runtime_config SET value_text='${String(v).replace(/'/g, "''")}' WHERE family_id=${fx.FAM} AND key_name='${k}'`);
    }
    const left = db.num(`SELECT COUNT(*) FROM account WHERE family_id=${fx.FAM} AND display_name='${ACC_NAME}'`);
    const keysLeft = KEYS.filter(k => (cfg(k) || null) !== ((state.before || {})[k] || null));
    if (left === 0 && keysLeft.length === 0) report.info('还原:测试账户及同步来的持仓已删除 · IBKR 配置键恢复原样');
    else report.fail(this.name, '还原不完整', `账户残留 ${left} · 配置键不一致 ${keysLeft.join(',')}`);
  },
};
