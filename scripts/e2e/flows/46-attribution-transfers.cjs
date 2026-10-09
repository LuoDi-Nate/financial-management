/**
 * flow · v1.30.1 · 账户间划转不许进「谁把净资产推上去 / 拉下来」—— prod 2026-09 实报
 *
 * 维护者:报表页「本期归因」把某余额宝排成「拉下来」的第一名,金额 «A» 里大半是转去新开账户的钱。v1.28.1 修过同类问题(开账基线),
 * 但报表封板这一块是 v1.10 另写的一套,一直按「期末 − 上期末」排名,没扣划转;新账户又整笔算「资本纳入」。
 *
 * 三段,每段都是:页面上看 → 库里核对:
 *  1 · 已关账月份:同一个月里补一笔「老账户 → 老账户」和一笔「老账户 → 新开账户」的划转(fixture:已关账的月份
 *      时间上无法自然回去记账,直接写库,并按划转把两边余额挪好 —— 正是 prod 那一刻的数据形状)。
 *      断言:报表「本期归因」的正负贡献与补之前**逐条相同**;新开账户不出现在「新纳入的本金」里。
 *  2 · 本期:填报页真点一笔划转 → 账户体检页写「其中账户间划转 −X,不是赚亏」。
 *  3 · 账户列表那一列叫「余额变化」(原名「本期Δ」紧挨「本期损益」,被当成赚亏)。
 * cleanup:fixture 行全部删掉,两期余额逐分还原。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const A = 2;                         // 工商银行-备用金 · CASH · CNY
const B = 7;                         // 微信-零钱通 · CASH · CNY(和 A 同在「日常周转」组 —— 第 2 段用)
const C = 9;                         // 招行理财-稳健 · WEALTH · 不在任何组(第 1 段:跨组划转才看得出毛病)
const NEW_NAME = 'e2e · 划转开户';
const AMT1 = '1234.56', AMT2 = '3000.00', AMT3 = 500;
const state = {};

const bal = (pid, acc) => db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${pid} AND account_id=${acc}`);

/** 报表页「本期归因」:正负贡献(名字 + 金额文字)+「新纳入的本金」那一行 */
async function readAttribution(ui) {
  await ui.click('header a[href="/reports"] >> nth=0', '顶部导航点「报表」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  const card = '.paper-card:has(h3:has-text("本期归因"))';
  await ui.page.waitForSelector(card, { timeout: 60000 }).catch(() => {});
  return ui.page.evaluate(() => {
    const c = [...document.querySelectorAll('.paper-card')].find(x => /本期归因/.test(x.querySelector('h3')?.textContent || ''));
    if (!c) return null;
    const rows = [...c.querySelectorAll('.biv-row')].map(r =>
      (r.querySelector('.biv-name')?.textContent.trim() || '') + ' ' + (r.querySelector('.biv-amt')?.textContent.trim() || ''));
    const opened = [...c.querySelectorAll('p')].find(p => /新纳入的本金|新增/.test(p.textContent));
    return { rows, opened: opened ? opened.textContent.replace(/\s+/g, ' ').trim() : '' };
  });
}

module.exports = {
  name: '46-attribution-transfers',
  title: 'v1.30.1 · 账户间划转不进「本期归因」· 体检写明划转 · 「余额变化」改名',

  async run(ui, report) {
    ui.flow = this.name;
    await ui.page.route(/\/checkup\/(diagnose|insight)/, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
    state.closed = db.one(`SELECT id FROM period WHERE family_id=${fx.FAM} AND status='CLOSED' AND period_start <= CURDATE()
                            ORDER BY period_start DESC LIMIT 1`);
    state.cur = fx.currentPeriod();
    state.maxT = db.num(`SELECT COALESCE(MAX(id),0) FROM transfer`);
    state.snap = { a1: bal(state.closed, A), c1: bal(state.closed, C), a2: bal(state.cur, A), b2: bal(state.cur, B) };
    report.info(`前置:最近一个已关账月 #${state.closed} · 本期 #${state.cur}`);

    // ── 1 · 已关账月份:补两笔划转,本期归因一条都不许变 ─────────────────
    report.section('1 · 报表「本期归因」:补一笔跨组的老账户之间划转 + 一笔转进新开账户的划转 → 贡献者列表与之前逐条相同');
    await ui.goto('/accounts');
    const before = await readAttribution(ui);
    await ui.assert(before && before.rows.length > 0, '读到「本期归因」的正负贡献', JSON.stringify(before));

    // fixture:已关账月份回不去记账 —— 按 prod 那一刻的数据形状直接写库(划转 + 两边余额同时挪)
    db.raw(`INSERT INTO account (family_id, display_name, type, currency, display_order)
            VALUES (${fx.FAM}, '${NEW_NAME}', 'CASH', 'CNY', 999)`);
    state.newAcc = db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND display_name='${NEW_NAME}' ORDER BY id DESC LIMIT 1`);
    const me = db.one(`SELECT id FROM member WHERE family_id=${fx.FAM} AND archived_at IS NULL ORDER BY id LIMIT 1`);
    db.raw(`INSERT INTO transfer (period_id, from_account_id, to_account_id, amount, occurred_at, submitted_by, is_draft, source_tag)
            VALUES (${state.closed}, ${A}, ${C}, ${AMT1}, CURDATE(), ${me}, 0, 'MANUAL'),
                   (${state.closed}, ${A}, ${state.newAcc}, ${AMT2}, CURDATE(), ${me}, 0, 'MANUAL')`);
    db.raw(`UPDATE period_snapshot SET end_balance = end_balance - ${AMT1} - ${AMT2} WHERE period_id=${state.closed} AND account_id=${A}`);
    db.raw(`UPDATE period_snapshot SET end_balance = end_balance + ${AMT1} WHERE period_id=${state.closed} AND account_id=${C}`);
    db.raw(`INSERT INTO period_snapshot (period_id, account_id, end_balance, submitted_by, source_tag)
            VALUES (${state.closed}, ${state.newAcc}, ${AMT2}, ${me}, 'MANUAL')`);
    await ui.assert(Number(bal(state.closed, A)) === Math.round((Number(state.snap.a1) - Number(AMT1) - Number(AMT2)) * 100) / 100,
      '真值层:老账户这个月少了两笔划转的钱(数据形状同 prod)', `${state.snap.a1} → ${bal(state.closed, A)}`);

    const after = await readAttribution(ui);
    await ui.assert(after && JSON.stringify(after.rows) === JSON.stringify(before.rows),
      '正负贡献与补划转之前逐条相同(划转只是家里挪钱)', `前 ${before.rows.join(' | ')} → 后 ${after && after.rows.join(' | ')}`);
    await ui.assert(after && !after.opened.includes(NEW_NAME),
      '全靠划转开起来的新账户不出现在「新纳入的本金」里', after && after.opened);
    await ui.page.screenshot({ path: '/tmp/e2e-46-attribution-pc.png', fullPage: false }).catch(() => {});

    // ── 2 · 体检页把划转单独说出来 ─────────────────────────────────────
    // 体检页看的是「最新一期」。最新一期就是本期(prod 的形状)→ 从填报页真点一笔划转;
    // beta 的演示数据里排着几期未来月份(最新一期回不去记账)→ 按同样的形状写库(fixture)。
    state.latest = db.one(`SELECT id FROM period WHERE family_id=${fx.FAM} ORDER BY period_start DESC LIMIT 1`);
    report.section(`2 · 工商银行-备用金 → 微信-零钱通 划 ${AMT3} → 账户体检页写「其中账户间划转 −${AMT3}.00,不是赚亏」`);
    if (String(state.latest) === String(state.cur)) {
      await ui.click('header a[href="/entry"] >> nth=0', '顶部导航点「填报」');
      await ui.page.waitForLoadState('networkidle').catch(() => {});
      const fold = `#entry-block-${A} details.entry-fold`;
      const isOpen = await ui.page.locator(fold).first().evaluate(d => d.open).catch(() => null);
      if (isOpen === false) await ui.click(`${fold} > summary`, '展开「工商银行-备用金」那一行');
      const form = `#entry-block-${A} form[hx-post$="/entry/${A}/transfer"]`;
      await ui.click(`${form} .ss-input`, '点开「转给哪个账户」的搜索框');
      await ui.page.keyboard.type('零钱通', { delay: 30 });
      await ui.page.waitForTimeout(300);
      await ui.click(`${form} .ss-item:has-text("微信-零钱通")`, '在候选里点「微信-零钱通」');
      await ui.fill(`${form} input[name="amount"]`, String(AMT3), `金额填 ${AMT3}`);
      await Promise.all([
        ui.page.waitForResponse(r => r.url().includes(`/entry/${A}/transfer`), { timeout: 20000 }).catch(() => null),
        ui.click(`${form} button:has-text("划转")`, '点「↔ 划转」'),
      ]);
      await ui.page.waitForTimeout(1200);
    } else {
      report.info(`最新一期 #${state.latest} 不是本期(beta 演示数据里有未来月份)→ 这一笔按同样形状写库`);
      state.snap.aL = bal(state.latest, A); state.snap.bL = bal(state.latest, B);
      const me = db.one(`SELECT id FROM member WHERE family_id=${fx.FAM} AND archived_at IS NULL ORDER BY id LIMIT 1`);
      db.raw(`INSERT INTO transfer (period_id, from_account_id, to_account_id, amount, occurred_at, submitted_by, is_draft, source_tag)
              VALUES (${state.latest}, ${A}, ${B}, ${AMT3}, CURDATE(), ${me}, 0, 'MANUAL')`);
      db.raw(`UPDATE period_snapshot SET end_balance = end_balance - ${AMT3} WHERE period_id=${state.latest} AND account_id=${A}`);
      db.raw(`UPDATE period_snapshot SET end_balance = end_balance + ${AMT3} WHERE period_id=${state.latest} AND account_id=${B}`);
    }
    state.uiT = db.one(`SELECT id FROM transfer WHERE id > ${state.maxT} AND period_id=${state.latest} AND from_account_id=${A} AND to_account_id=${B} AND deleted_at IS NULL`);
    await ui.assert(!!state.uiT, '真值层:最新一期多了这笔划转');
    await ui.goto('/accounts');
    await ui.click(`main a[href="/accounts/${A}"] >> nth=0`, '账户列表点「工商银行-备用金」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click(`a[href="/checkup?account=${A}"]`, '账户详情点「看资产体检」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.rendered('账户体检页');
    const note = await ui.page.textContent('[data-period-transfer]').catch(() => null);
    await ui.assert(note && /其中账户间划转\s*-?−?500\.00/.test(note.replace(/\s+/g, ' ')) && /不是赚亏/.test(note),
      `体检页「余额较上期」后面写「其中账户间划转 −${AMT3}.00,不是赚亏」`, note);

    // ── 3 · 账户列表那一列改名 ───────────────────────────────────────
    report.section('3 · 仪表盘账户列表:那一列叫「余额变化」,悬停写清含划转、看赚亏看本期损益');
    await ui.click('header a[href="/dashboard"] >> nth=0', '顶部导航点「仪表盘」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const th = await ui.page.evaluate(() => {
      const h = document.querySelector('th[data-mcol="mom_delta"]');
      return h ? { text: h.textContent.trim(), title: h.getAttribute('title') || '' } : null;
    });
    await ui.assert(th && th.text === '余额变化' && /含账户间划转/.test(th.title) && /本期损益/.test(th.title),
      '列名「余额变化」· 悬停说明含划转、看赚亏看「本期损益」', JSON.stringify(th));
    await ui.assert(await ui.page.locator('text=本期Δ').count() === 0, '页面上不再有「本期Δ」');
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    await ui.page.unroute(/\/checkup\/(diagnose|insight)/).catch(() => {});
    for (const sql of [
      `DELETE FROM transfer WHERE id > ${state.maxT || 0} AND (from_account_id=${A} OR to_account_id=${A} OR to_account_id=${C})`,
      state.newAcc ? `DELETE FROM period_snapshot WHERE account_id=${state.newAcc}` : null,
      state.newAcc ? `DELETE FROM snapshot_todo WHERE account_id=${state.newAcc}` : null,
      state.newAcc ? `DELETE FROM account WHERE id=${state.newAcc} AND display_name='${NEW_NAME}'` : null,
    ].filter(Boolean)) { try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 60)} · ${String(e.message).slice(0, 80)}`); } }
    const set = (pid, acc, v) => { if (pid && v !== undefined && v !== null) db.raw(`UPDATE period_snapshot SET end_balance=${v} WHERE period_id=${pid} AND account_id=${acc}`); };
    if (state.snap) {
      set(state.closed, A, state.snap.a1); set(state.closed, C, state.snap.c1);
      set(state.cur, A, state.snap.a2); set(state.cur, B, state.snap.b2);
      if (state.snap.aL !== undefined) { set(state.latest, A, state.snap.aL); set(state.latest, B, state.snap.bL); }
      const ok = bal(state.closed, A) === state.snap.a1 && bal(state.closed, C) === state.snap.c1
              && bal(state.cur, A) === state.snap.a2 && bal(state.cur, B) === state.snap.b2;
      if (ok) report.info('还原:两期余额与跑之前逐分相同');
      else report.fail(this.name, '还原:余额没回到原值', JSON.stringify(state.snap));
    }
  },
};
