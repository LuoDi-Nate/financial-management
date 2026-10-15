/**
 * flow · v1.34.2 · 修五个老问题里能从页面走的三条(维护者 2026-10-15 拍板)
 *
 *   1  补记到「之后已确认过余额」的历史期(第 2 条 · 每次问,默认 A):
 *        管理 → 周期 → 重新打开上个月 → 填报页切到上个月 → 工商银行-备用金那一行「− 支出」100
 *        → 弹框问「当时算进余额了吗」,默认选「算进去了 —— 余额不动」→ 点「记上」
 *        → 库:多一笔支出(backfill_mode=KEEP)· 这一期和之后各期的余额一分不动
 *   2  再记一笔 50,这回选「没算进去 —— 这一期和之后每个月一起改」
 *        → 库:这一期 −50 · 之后每一期 −50(之后各期逐期损益不变)· backfill_mode=SHIFT
 *   3  点开「本期 N 笔流水」删掉那笔 50(按当初的 B 处理)→ 这一期和之后各期 +50 回到原样
 *      删掉那笔 100(当初是 A)→ 余额不动
 *   4  弹框点「取消」→ 什么都没发出去,库里不多一笔
 *   5  关回上个月 → 顶栏「报表」:封板抬头「x / y 已填 · 缺 …」不再把那个月之后才建、那个月又没快照的账户算进来(第 3 条)
 *   6  隐私模式下固定收支「算不出来」的原因糊掉(第 4 条 · 页面上有这类卡时)
 *
 * 这条会重开 / 关闭历史账期、动历史余额 —— cleanup 用开跑前的整库快照还原并重启应用,然后重新登录。
 */
const fs = require('fs');
const { execFileSync } = require('child_process');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const { BASE, USER, PASS } = require('../lib/browser.cjs');

const A = 2;                                // 工商银行-备用金 · CASH · CNY
const DUMP = '/tmp/e2e52-pre.sql';
const MYSQL_ARGS = ['-ufinance', '-pfinance', 'finance'];
const AI_STUB = /\/(review\/insight|checkup\/(diagnose|insight))/;
const state = {};

const bal = (acc, pid) => db.one(`SELECT end_balance FROM period_snapshot WHERE account_id=${acc} AND period_id=${pid}`);
/** 之后各期(period_start 晚于这一期)这个账户的余额:「期id:余额」列表 */
const laterBals = (acc, start) => db.col(`SELECT CONCAT(s.period_id, ':', s.end_balance) FROM period_snapshot s
  JOIN period p ON p.id=s.period_id WHERE p.family_id=${fx.FAM} AND s.account_id=${acc} AND p.period_start > '${start}'
  ORDER BY p.period_start`);
const shifted = (before, after, delta) => before.length === after.length && before.length > 0 && before.every((kv, i) => {
  const [p0, b0] = kv.split(':'); const [p1, b1] = after[i].split(':');
  return p0 === p1 && Math.abs(Number(b1) - Number(b0) - delta) < 0.005;
});

async function periodAction(ui, pid, action, label) {
  await ui.click('header a[href="/admin"] >> nth=0', '顶栏点「管理」');
  const sel = action === 'reopen' ? `form[action*="/${pid}/reopen"]` : `form[action*="/${pid}/force-close"]`;
  const pg = await ui.paginateUntil('/admin/periods?page={p}', sel);
  await ui.assert(pg >= 0, `周期管理里翻到 ${label}`, `page=${pg}`);
  if (action === 'reopen') {
    await ui.fill(`${sel} input[name=reason]`, 'e2e52 补记一笔历史账', '填「必填理由」');
    await ui.submit(`${sel} button`, `点「重新打开」${label}`);
    await ui.assert(db.one(`SELECT status FROM period WHERE id=${pid}`) === 'OPEN', `真值层:${label} 变成 OPEN`);
  } else {
    await ui.submit(`${sel} button`, `点「关闭账期」${label}`);
    await ui.assert(db.one(`SELECT status FROM period WHERE id=${pid}`) === 'CLOSED', `真值层:${label} 关回 CLOSED`);
  }
}

async function entryFor(ui, pid, label) {
  await ui.click('header a[href="/entry"] >> nth=0', '顶栏点「填报」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  const wrap = '.ss-wrap:has(select[onchange*="/entry?period="])';
  const cur = await ui.page.locator(`${wrap} select`).first().inputValue().catch(() => '');
  if (String(cur) !== String(pid)) {
    const text = (await ui.page.locator(`${wrap} select option[value="${pid}"]`).first().textContent()).trim();
    await ui.click(`${wrap} input.ss-input`, '点开账期下拉');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click(`${wrap} .ss-item:has-text("${text}")`, `选「${text}」`)]);
    await ui.page.waitForTimeout(500);
  }
  const now = await ui.page.locator(`${wrap} select`).first().inputValue().catch(() => '');
  await ui.assert(String(now) === String(pid), `填报页切到 ${label}`, ui.page.url());
}

async function openRow(ui, acc) {
  const fold = `#entry-block-${acc} details.entry-fold`;
  const isOpen = await ui.page.locator(fold).first().evaluate(d => d.open).catch(() => null);
  if (isOpen === false) await ui.click(`${fold} > summary`, '展开这一行');
}

/** 那一行「− 支出」→ 弹框 → 选 A / B(或取消)。返回发没发出请求 */
async function quickExpense(ui, amt, choice) {
  await openRow(ui, A);
  const form = `#entry-block-${A} form[hx-post$="/entry/${A}/cash-flow"]`;
  await ui.fill(`${form} input[name="amount"]`, amt, `快捷支出 ${amt}`);
  await ui.click(`${form} button`, '点「− 支出」');
  const dlg = 'dialog[data-backfill-dialog][open]';
  await ui.page.waitForSelector(dlg, { timeout: 5000 }).catch(() => {});
  await ui.visible(dlg, '弹框问「这笔当时算进余额里了吗」');
  return dlg;
}

async function relogin(ui) {
  await ui.page.goto(BASE + '/login', { waitUntil: 'networkidle', timeout: 60000 });
  if (await ui.page.locator('input[name=username]').count() === 0) return;
  await ui.page.fill('input[name=username]', USER);
  await ui.page.fill('input[name=password]', PASS);
  await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 60000 }).catch(() => {}),
                     ui.page.click('button[type=submit]')]);
}

module.exports = {
  name: '52-old-fixes',
  title: 'v1.34.2 · 补记到历史期按 A / B · 封板完整度不算之后才建的账户 · 固定收支原因打码',

  async run(ui, report) {
    ui.flow = this.name;
    ui.page.on('dialog', d => d.accept().catch(() => {}));   // 删流水的浏览器确认框
    await ui.page.route(AI_STUB, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
    const fd = fs.openSync(DUMP, 'w');
    execFileSync('mysqldump', ['--single-transaction', '--skip-lock-tables', ...MYSQL_ARGS], { stdio: ['ignore', fd, 'ignore'] });
    fs.closeSync(fd);
    state.dumped = true;
    report.info(`整库快照 → ${DUMP}(cleanup 用它还原)`);
    state.cfMax = db.num(`SELECT COALESCE(MAX(id),0) FROM cash_flow`);

    const cur = Number(fx.currentPeriod());
    state.prev = db.num(`SELECT p.id FROM period p JOIN period c ON c.id=${cur}
      WHERE p.family_id=${fx.FAM} AND p.period_start = DATE_SUB(c.period_start, INTERVAL 1 MONTH)`);
    state.prevStart = db.one(`SELECT period_start FROM period WHERE id=${state.prev}`);
    const label = state.prevStart.slice(0, 7);
    await ui.assert(db.one(`SELECT status FROM period WHERE id=${state.prev}`) === 'CLOSED', `前置:上个月 ${label} 已关账`);
    await ui.assert(['MANUAL', 'UNKNOWN', 'IMPORT', 'AUTO_VALUATION'].includes(db.one(`SELECT source_tag FROM period_snapshot WHERE account_id=${A} AND period_id=${state.prev}`))
        && db.num(`SELECT COUNT(*) FROM period_snapshot s JOIN period p ON p.id=s.period_id WHERE p.family_id=${fx.FAM}
             AND s.account_id=${A} AND p.period_start > '${state.prevStart}' AND p.period_start <= CURDATE()
             AND s.source_tag <> 'CARRIED_FORWARD'`) > 0,
      `前置:工商银行-备用金 ${label} 的余额是确认过的,之后(已经开始的期里)也有确认过的余额`);

    // ── 1 · 默认 A ─────────────────────────────────────────────────────
    report.section(`1 · 重开 ${label} → 填报页「− 支出」100 → 弹框默认「余额不动」→ 记上`);
    await periodAction(ui, state.prev, 'reopen', label);
    await entryFor(ui, state.prev, label);
    const ids = await ui.page.locator('main[data-backfill-ids]').getAttribute('data-backfill-ids');
    await ui.assert(String(ids).split(',').includes(String(A)), '页面知道这个账户补记时要问', ids);
    const b0 = bal(A, state.prev), later0 = laterBals(A, state.prevStart);
    const cf0 = db.num(`SELECT COALESCE(MAX(id),0) FROM cash_flow`);
    let dlg = await quickExpense(ui, 100, 'KEEP');
    await ui.assert(await ui.page.locator(`${dlg} [data-bf-keep]`).isChecked(), '默认选中「算进去了 —— 只补记这笔,余额不动」');
    const dtext = await ui.page.locator(dlg).innerText();
    await ui.assert(dtext.includes(label) && dtext.includes('工商银行-备用金'), '弹框写明是哪一期、哪个账户', dtext.slice(0, 120));
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes(`/entry/${A}/cash-flow`), { timeout: 20000 }).catch(() => null),
      ui.click(`${dlg} [data-bf-ok]`, '点「记上」'),
    ]);
    await ui.page.waitForTimeout(1200);
    state.cfKeep = db.num(`SELECT COALESCE(MAX(id),0) FROM cash_flow WHERE account_id=${A} AND period_id=${state.prev} AND amount=100 AND id>${cf0}`);
    await ui.assert(state.cfKeep > 0 && db.one(`SELECT backfill_mode FROM cash_flow WHERE id=${state.cfKeep}`) === 'KEEP',
      '真值层:多了一笔 100 的支出,记的是「余额不动」(KEEP)');
    await ui.assert(bal(A, state.prev) === b0 && JSON.stringify(laterBals(A, state.prevStart)) === JSON.stringify(later0),
      '真值层:这一期和之后各期的余额一分不动', `${b0} → ${bal(A, state.prev)}`);

    // ── 2 · 选 B ───────────────────────────────────────────────────────
    report.section('2 · 再记 50,选「没算进去 —— 这一期和之后每个月一起改」');
    dlg = await quickExpense(ui, 50, 'SHIFT');
    await ui.click(`${dlg} [data-bf-shift]`, '选「没算进去」');
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes(`/entry/${A}/cash-flow`), { timeout: 20000 }).catch(() => null),
      ui.click(`${dlg} [data-bf-ok]`, '点「记上」'),
    ]);
    await ui.page.waitForTimeout(1200);
    state.cfShift = db.num(`SELECT COALESCE(MAX(id),0) FROM cash_flow WHERE account_id=${A} AND period_id=${state.prev} AND amount=50`);
    await ui.assert(state.cfShift > 0 && db.one(`SELECT backfill_mode FROM cash_flow WHERE id=${state.cfShift}`) === 'SHIFT',
      '真值层:多了一笔 50 的支出,记的是「一起改」(SHIFT)');
    await ui.assert(Math.abs(Number(bal(A, state.prev)) - (Number(b0) - 50)) < 0.005, `真值层:${label} 余额 −50`, `${b0} → ${bal(A, state.prev)}`);
    await ui.assert(shifted(later0, laterBals(A, state.prevStart), -50),
      `真值层:之后每一期(${later0.length} 期)都 −50 —— 之后各期逐期损益不变`);

    // ── 3 · 删 ─────────────────────────────────────────────────────────
    report.section('3 · 点开「本期 N 笔流水」删掉那笔 50(当初是 B → 各期改回去)· 再删那笔 100(当初是 A → 余额不动)');
    const ledger = `#entry-block-${A} details:has(summary:has-text("笔流水"))`;
    const openLedger = async () => {
      const o = await ui.page.locator(ledger).first().evaluate(d => d.open).catch(() => null);
      if (o === false) await ui.click(`${ledger} > summary`, '点开「本期 N 笔流水」');
    };
    await openLedger();
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes(`/entry/cash-flow/${state.cfShift}/delete`), { timeout: 20000 }).catch(() => null),
      ui.click(`button[hx-post="/entry/cash-flow/${state.cfShift}/delete"]`, '那笔 50 点 ✕(浏览器确认框自动点确定)'),
    ]);
    await ui.page.waitForTimeout(1200);
    await ui.assert(bal(A, state.prev) === b0 && JSON.stringify(laterBals(A, state.prevStart)) === JSON.stringify(later0),
      '真值层:删掉 B 记的那笔 → 这一期和之后各期都改回原样');
    await openLedger();
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes(`/entry/cash-flow/${state.cfKeep}/delete`), { timeout: 20000 }).catch(() => null),
      ui.click(`button[hx-post="/entry/cash-flow/${state.cfKeep}/delete"]`, '那笔 100 点 ✕'),
    ]);
    await ui.page.waitForTimeout(1200);
    await ui.assert(db.one(`SELECT deleted_at IS NOT NULL FROM cash_flow WHERE id=${state.cfKeep}`) === '1'
        && bal(A, state.prev) === b0 && JSON.stringify(laterBals(A, state.prevStart)) === JSON.stringify(later0),
      '真值层:删掉 A 记的那笔 → 记录没了,余额不动');

    // ── 4 · 取消 ───────────────────────────────────────────────────────
    report.section('4 · 弹框点「取消」→ 什么都没发出去');
    const n0 = db.num(`SELECT COUNT(*) FROM cash_flow WHERE account_id=${A} AND period_id=${state.prev} AND deleted_at IS NULL`);
    dlg = await quickExpense(ui, 77, 'CANCEL');
    await ui.click(`${dlg} [data-bf-cancel]`, '点「取消」');
    await ui.page.waitForTimeout(1500);
    await ui.assert(db.num(`SELECT COUNT(*) FROM cash_flow WHERE account_id=${A} AND period_id=${state.prev} AND deleted_at IS NULL`) === n0
        && bal(A, state.prev) === b0, '真值层:没多一笔、余额没动');

    // ── 5 · 封板完整度 ───────────────────────────────────────────────
    report.section(`5 · 关回 ${label} → 报表封板抬头:之后才建、那个月又没快照的账户不算「缺」`);
    await periodAction(ui, state.prev, 'close', label);
    await ui.click('header a[href="/reports"] >> nth=0', '顶栏点「报表」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const head = (await ui.page.locator('main').first().innerText()).match(/(\d+) \/ (\d+) (?:个账户已填|已填[^\n]*)/);
    await ui.assert(!!head, '读到封板抬头「x / y 已填」', head ? head[0] : '');
    // 封板锚期 = 抬头那一期;从页面上的「YYYY-MM 月度账册」取
    const anchorMonth = ((await ui.page.locator('main').first().innerText()).match(/(\d{4}-\d{2}) 月度账册/) || [])[1];
    const anchorEnd = db.one(`SELECT period_end FROM period WHERE family_id=${fx.FAM} AND DATE_FORMAT(period_start,'%Y-%m')='${anchorMonth}'`);
    const anchorId = db.num(`SELECT id FROM period WHERE family_id=${fx.FAM} AND DATE_FORMAT(period_start,'%Y-%m')='${anchorMonth}'`);
    const bornAfter = db.col(`SELECT display_name FROM account a WHERE family_id=${fx.FAM}
      AND (archived_at IS NULL OR archived_at > '${anchorEnd}') AND DATE(created_at) > '${anchorEnd}'
      AND NOT EXISTS (SELECT 1 FROM period_snapshot s WHERE s.period_id=${anchorId} AND s.account_id=a.id)`);
    const expected = db.num(`SELECT COUNT(*) FROM account a WHERE family_id=${fx.FAM}
      AND (archived_at IS NULL OR archived_at > '${anchorEnd}')
      AND NOT (DATE(created_at) > '${anchorEnd}' AND NOT EXISTS (SELECT 1 FROM period_snapshot s WHERE s.period_id=${anchorId} AND s.account_id=a.id))`);
    await ui.assert(head && Number(head[2]) === expected, `「应填」= ${expected}(在册且不是那个月之后才建的)`, head ? head[0] : '');
    await ui.seesText(`/ ${expected}`, `页面上封板抬头写的是「… / ${expected}」`);
    await ui.assert(bornAfter.every(n => !head[0].includes(n)),
      `那个月之后才建的 ${bornAfter.length} 个账户不在「缺」里`, bornAfter.join('、') || '(这次没有)');

    // ── 6 · 固定收支原因打码 ─────────────────────────────────────────
    report.section('6 · 隐私模式:固定收支「算不出来」的原因糊掉');
    await ui.click('header a[href="/entry"] >> nth=0', '顶栏点「填报」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const probs = ui.page.locator('.ff-problem');
    const np = await probs.count();
    if (np === 0) {
      report.info('这期固定收支里没有「算不出来」的卡 —— 只能靠护栏 v1342-FF-PROBLEM-PRIV 守模板');
    } else {
      await ui.assert(await probs.evaluateAll(els => els.every(e => e.hasAttribute('data-priv'))), `${np} 条「算不出来」的原因都挂了打码标记`);
    }
    const tips = await ui.page.locator('.ff-calc').evaluateAll(els => els.map(e => e.getAttribute('title') || ''));
    await ui.assert(tips.every(t => !/[¥$]|\d{3}/.test(t)), '「算」角标的悬停提示不带金额', tips.slice(0, 3).join(' | '));
  },

  async cleanup(ui, report) {
    await ui.page.unroute(AI_STUB).catch(() => {});
    if (!state.dumped || !fs.existsSync(DUMP)) { report.info('没有整库快照,跳过还原'); return; }
    const fd = fs.openSync(DUMP, 'r');
    execFileSync('mysql', MYSQL_ARGS, { stdio: [fd, 'ignore', 'ignore'] });
    fs.closeSync(fd);
    execFileSync('sudo', ['-n', '/bin/systemctl', 'restart', 'finance']);
    for (let i = 0; i < 60; i++) {
      await ui.page.waitForTimeout(3000);
      let code = '';
      try { code = execFileSync('curl', ['-s', '-m', '3', '-o', '/dev/null', '-w', '%{http_code}', `${BASE}/login`]).toString(); } catch (e) { /* 还在起 */ }
      if (code === '200') break;
    }
    await relogin(ui);
    await ui.assert(db.num(`SELECT COUNT(*) FROM period WHERE family_id=${fx.FAM} AND status='OPEN'`) === 1
        && db.num(`SELECT COUNT(*) FROM cash_flow WHERE id > ${state.cfMax || 0}`) === 0,
      '还原:整库回到开跑前(只有本期 OPEN · 这条记的流水都不在了)· 应用已重启并重新登录');
  },
};
