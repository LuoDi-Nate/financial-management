/**
 * flow · v1.26 · issue #24 · 盈透 IBKR 只读同步(Flex 报表)
 *
 * 不打真实 IBKR:在本机起一个 HTTP 桩回放 IBKR 的两步应答(SendRequest → GetStatement),
 * 报表用 src/test/resources/ibkr/ 里的合成样例(账号、股数、价格全部编造)。
 * 取数基址是家庭配置里一个页面上不露的键,只接受 *.interactivebrokers.com 的 HTTPS 或本机回环 —— 这里指向桩。
 *
 * 全程从页面发起:管理首页点「券商同步」卡片(卡上要点名盈透);账户列表点「券商」→ 选盈透 → 还没有账户时
 * 点空状态里的链接去「管理 → 券商同步」(页头显示正在为哪个账户关联)→ 填口令 / 查询号 / 到期日 → 保存 → 测试连接
 * → 点「回该账户的券商关联」→ 下拉里选账户、两步确认、弹窗确认 → 看持仓;换一个过期的口令 → 立即同步 → 卡片与账户列表标红。
 *
 * 2026-09-27 维护者在 beta 上走这条路走不通:关联页只写「先去管理页测试连接」没有链接;管理页里也找不到 IBKR
 * (在「数据源接入」第 ④ 节,首页卡片没写哪几家)。第 1、2 段就是那条路。
 *
 * 前置:建一个空的美元证券账户(关联会先归档现有持仓,不拿真账户做实验)。cleanup 把它连同同步来的一切删干净,
 * IBKR 那几个配置键恢复原样。
 */
const fs = require('fs');
const path = require('path');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const ibkrStub = require('../lib/ibkr-stub.cjs');

const SAMPLE = fs.readFileSync(path.join(__dirname, '../../../src/test/resources/ibkr/flex-sample-two-accounts.xml'), 'utf8');
const KEYS = ['broker_ibkr_flex_token', 'broker_ibkr_flex_query_id', 'broker_ibkr_token_expires_on',
              'broker_ibkr_accounts', 'broker_ibkr_flex_base_url'];
const ACC_NAME = 'e2e · 盈透 IBKR';
const GOOD = '111122223333444455556666';
const EXPIRED = '999988887777666655554444';
const state = {};

/** 账户列表里「这个账户那一行」的文字(PC 表格;别的账户的标记不算数)*/
async function rowText(ui) {
  return ui.page.evaluate((name) => {
    const tr = [...document.querySelectorAll('tr')].find(t => t.innerText.includes(name));
    return tr ? tr.innerText.replace(/\s+/g, ' ') : '';
  }, ACC_NAME);
}

const cfg = (k) => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
// 按服务器所在时区(Asia/Shanghai)算「今天 + n 天」—— toISOString 是 UTC,凌晨时段会差一天
const plusDays = (n) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' })
  .format(new Date(Date.now() + n * 86400000));

module.exports = {
  name: '27-ibkr-flex',
  title: 'v1.26 · 盈透 IBKR:配口令 → 测试 → 关联 → 同步 → 口令过期标红',

  async run(ui, report) {
    ui.flow = this.name;
    // 体检 / 报表自动发起的 AI 请求与本 flow 无关,就地短路
    await ui.page.route(/\/checkup\/(diagnose|insight)/, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));

    // ── 前置 ──────────────────────────────────────────────────────────
    state.before = Object.fromEntries(KEYS.map(k => [k, cfg(k)]));
    const stub = await ibkrStub.start(SAMPLE, EXPIRED);
    state.srv = stub.server;
    state.hits = stub.hits;
    const port = stub.port;
    db.raw(`INSERT INTO family_runtime_config (family_id, key_name, value_text) VALUES (${fx.FAM}, 'broker_ibkr_flex_base_url', 'http://127.0.0.1:${port}/fws')
            ON DUPLICATE KEY UPDATE value_text = VALUES(value_text)`);
    db.raw(`INSERT INTO account (family_id, display_name, type, currency, display_order) VALUES (${fx.FAM}, '${ACC_NAME}', 'STOCK', 'USD', 999)`);
    state.acc = db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND display_name='${ACC_NAME}' ORDER BY id DESC LIMIT 1`);
    report.info(`前置:本机 IBKR 桩 127.0.0.1:${port} · 新建空的美元证券账户 #${state.acc}`);
    // 关联页的 IBKR 账户下拉来自「最近一次取到的报表」—— 清掉,才能走到「还没有账户」那一步
    db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='broker_ibkr_accounts'`);
    await ui.page.waitForTimeout(6000);   // 家庭配置有 5 秒缓存,等桩地址生效

    // ── 1 · 管理首页找得到 ───────────────────────────────────────────
    report.section('1 · 管理首页 →「券商同步」卡片(卡上点名三家)');
    await ui.goto('/admin');
    await ui.rendered('管理首页');
    const card = 'main a[href="/admin/broker"]';
    await ui.visible(card, '管理首页有「券商同步」卡片');
    const cardText = await ui.page.locator(card).first().innerText();
    await ui.assert(['富途', '老虎', '盈透'].every(v => cardText.includes(v)), '卡片上逐家点名富途 / 老虎 / 盈透(用户是带着这些词来找的)', cardText);
    await ui.click(`${card} >> nth=0`, '点「券商同步」卡片');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.rendered('券商同步页');
    await ui.visible('#ibkr input[name="ibkrToken"]', '盈透那一栏就在这一页上');
    await ui.seesText('只能取报表,不能交易', '口令说明写清楚了它做不了交易');
    await ui.click('aside a[href="/admin/integrations"]', '侧栏点「数据源接入」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.assert(await ui.page.locator('input[name="ibkrToken"], input[name="tigerKey"]').count() === 0,
                    '数据源接入页上不再有券商表单(只在一处能改)');
    await ui.visible('#broker a[href="/admin/broker"]', '数据源接入页留了一行指路到「券商同步」');

    // ── 2 · 关联页 → 链接 → 配口令 → 测试 → 一键回来 ─────────────────
    report.section('2 · 关联页:还没有 IBKR 账户 → 点链接去配 → 测试 → 回到关联页');
    await ui.goto('/accounts');
    const brokerLink = `a[href="/accounts/${state.acc}/broker"]`;
    await ui.click(`${brokerLink} >> nth=0`, `在账户列表点「${ACC_NAME}」的「券商」`);
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.rendered('券商关联页');
    await ui.click('#vendorPick label:has(input[value="IBKR"])', '选「盈透 IBKR」');
    await ui.visible('#ibkrEmpty', '还没有 IBKR 账户时,告诉用户下一步做什么');
    await ui.click('#ibkrEmpty a', '点「去「管理 → 券商同步」填口令并测试」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.assert(ui.page.url().includes(`/admin/broker?account=${state.acc}`), '落到券商同步页,并带着是哪个账户', ui.page.url());
    await ui.seesText(`正在为账户 ${ACC_NAME} 关联券商`, '页头显示正在为哪个账户关联');
    await ui.fill('input[name="ibkrToken"]', GOOD, '填报表口令');
    await ui.fill('input[name="ibkrQuery"]', '1045872', '填查询号');
    await ui.fill('input[name="ibkrExpires"]', plusDays(9), '填口令到期日(9 天后)');
    await ui.submit('form#broker button:has-text("保存券商配置")', '保存券商配置');
    await ui.seesText('券商同步配置已保存', '保存回执');
    await ui.seesText(`正在为账户 ${ACC_NAME} 关联券商`, '保存之后还记得是在为哪个账户关联');
    await ui.assert(cfg('broker_ibkr_flex_token') === GOOD && cfg('broker_ibkr_flex_query_id') === '1045872',
                    '真值层:口令与查询号存进去了');
    const page1 = await ui.page.content();
    await ui.assert(!page1.includes(GOOD), '页面上不回显口令');

    await ui.submit('button:has-text("测试盈透 IBKR 连接")', '点「测试盈透 IBKR 连接」');
    await ui.seesText('找到 2 个账户', '测试结果:找到了报表里的两个账户');
    await ui.seesText('U•••4521', '账号打码显示');
    await ui.notSeesText('U1234521', '页面上不出现完整账号');
    await ui.assert(state.hits.every(h => h.ua), '真值层:发往 IBKR 的每个请求都带 User-Agent(不带会被 403)',
                    JSON.stringify(state.hits));

    await ui.clickText('← 回该账户的券商关联', '测完点「回该账户的券商关联」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.assert(ui.page.url().endsWith(`/accounts/${state.acc}/broker`), '回到了刚才那个账户的关联页', ui.page.url());

    // ── 3 · 关联 ─────────────────────────────────────────────────────
    report.section('3 · 选盈透 → 下拉里选账户 → 关联并同步');
    await ui.click('#vendorPick label:has(input[value="IBKR"])', '选「盈透 IBKR」');
    await ui.notVisible('#ibkrEmpty', '测过之后空状态提示消失');
    await ui.visible('#acctIbkr select[name="brokerAccountId"]', '选盈透后出现 IBKR 账户下拉');
    await ui.notVisible('#opendFields', 'OpenD 地址这类富途专用的输入被收起');
    await ui.selectByName('brokerAccountId', 'U1234521', '#acctIbkr');
    await ui.sameSize('#vendorPick > label', '三家券商选项同尺寸');
    await ui.page.check('input[name="acknowledged"]');
    await ui.page.check('input[name="confirmed"]');
    await ui.click('#lnkBtn', '点「关联并同步」');
    await Promise.all([
      ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 90000 }).catch(() => {}),
      ui.click('#lnkConfirm', '弹窗里点「确认关联并同步」'),
    ]);
    await ui.page.waitForTimeout(800);
    await ui.seesText('券商托管 · 盈透', '关联成功,卡片写明是盈透');
    await ui.seesText('U•••4521', '关联卡片上的账号也打码');

    const rows = db.col(`SELECT CONCAT(valuation_mode,'|',IFNULL(market,''),'|',IFNULL(ticker,''),'|',IFNULL(shares,''),'|',IFNULL(currency,''),'|',IFNULL(manual_value,''))
                           FROM stock_holding WHERE account_id=${state.acc} AND archived_at IS NULL AND sync_source='IBKR' ORDER BY id`);
    report.info(`同步来的持仓:${rows.join(' ; ')}`);
    const has = (re) => rows.some(r => re.test(r));
    await ui.assert(has(/^AUTO\|US\|AAPL\|50(\.0+)?\|USD/), '真值层:美股 AAPL 50 股(LOT 明细没有重复算)');
    await ui.assert(has(/^AUTO\|HK\|00700\|200/), '真值层:港股代码补成 00700');
    await ui.assert(has(/^AUTO\|CN\|600519\|10/), '真值层:沪股通归到 A 股');
    await ui.assert(has(/^MANUAL\|\|VWRA\|30(\.0+)?\|\|131\.52/), '真值层:伦敦上市的美元 ETF 按报表收盘价估值(美元账户不折算)');
    await ui.assert(has(/^CASH\|\|\|\|USD\|3200/) && has(/^CASH\|\|\|\|HKD\|12000/), '真值层:美元 / 港币现金各一行');
    await ui.assert(!rows.some(r => r.includes('BASE_SUMMARY')) && rows.filter(r => r.startsWith('CASH')).length === 2,
                    '真值层:BASE_SUMMARY 合计行没有被当成一个币种');
    // v1.29 · 期权不再跳过:一张合约一条手动估值行,单价 = 持仓市值 ÷ 张数(美元账户不折算)
    await ui.assert(has(/^MANUAL\|\|AAPL\s+261218C00250000\|1(\.0+)?\|USD\|980/), '真值层:期权一张合约一条,市值用报表的持仓市值(v1.29)');
    await ui.assert(rows.length === 7, '真值层:股票 4 + 现金 2 + 期权 1,一共 7 行', `共 ${rows.length} 行`);

    await ui.goto(`/accounts/${state.acc}/holdings`);
    await ui.rendered('持仓页');
    const vendorShown = await ui.page.evaluate(() => {
      const b = [...document.querySelectorAll('.ls-kv b')].map(e => e.innerText.trim());
      return b.join('|');
    });
    await ui.assert(vendorShown.split('|').includes('盈透'),
                    '持仓页「券商对接」那一格写的是「盈透」,不是「老虎」(原来是 FUTU ? 富途 : 老虎 的二选一)', vendorShown);
    const strip = await ui.page.evaluate(() => {
      const el = [...document.querySelectorAll('.link-strip')].find(e => e.innerText.includes('券商对接'));
      return el ? el.innerText.replace(/\s+/g, ' ') : '';
    });
    await ui.assert(strip && !/OpenD/i.test(strip),
                    '持仓页「券商对接」那一条上没有 OpenD 地址和 OpenD 网关按钮(OpenD 只是富途的,v1.26.1)', strip);

    // ── 3 · 到期提醒 ─────────────────────────────────────────────────
    report.section('4 · 口令到期提醒(到期前 14 天起)');
    await ui.goto('/accounts');
    const r1 = await rowText(ui);
    await ui.assert(r1.includes('口令 9 天后到期'), '账户列表上【这个账户那一行】标出了「口令 9 天后到期」', r1);

    // ── 4 · 口令过期:不许静默停更 ───────────────────────────────────
    report.section('5 · 换成一个过期的口令 → 立即同步 → 标红');
    await ui.goto('/admin/broker');
    await ui.fill('input[name="ibkrToken"]', EXPIRED, '换一个(桩里设成已过期的)口令');
    await ui.submit('form#broker button:has-text("保存券商配置")', '保存');
    await ui.goto(`/accounts/${state.acc}/broker`);
    await ui.submit('form[action$="/broker/sync"] button', '点「立即同步」');
    await ui.seesText('报表口令已过期', '失败说人话:报表口令已过期');
    await ui.seesText('Token has expired.', '并且带着 IBKR 原话');
    const last = db.one(`SELECT last_status FROM broker_link WHERE account_id=${state.acc}`);
    await ui.assert(/^同步失败 · 报表口令已过期/.test(last || ''), '真值层:失败落库(卡片与账户列表读的就是它)', last);
    const kept = db.num(`SELECT COUNT(*) FROM stock_holding WHERE account_id=${state.acc} AND archived_at IS NULL AND sync_source='IBKR'`);
    await ui.assert(kept === 7, '真值层:失败时一行持仓都没被归档(失败信封没被当成空报表)', `还剩 ${kept} 行`);
    await ui.goto('/accounts');
    const r2 = await rowText(ui);
    await ui.assert(r2.includes('同步失败') && !r2.includes('天后到期'),
                    '账户列表上【这个账户那一行】标红「同步失败」(已经失败就不再重复标到期)', r2);
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
