/**
 * flow · v1.32 · 固定收支(issue #38)+ 立即备份(issue #29)—— prd/v1.32.md §10 验收 1 ~ 14
 *
 * 每一段:页面上真点(从顶栏 / 卡片 / 摘要行点进去,不拼 URL)→ 看页面 → 看库里的行。
 *
 *   1  填报页「本月收支」标题行「固定收支」→ 管理页 → 加一条支出 → 回填报页摘要「1 笔待确认」(FR-1130 / 1110)
 *   2  导航「填报 ·N」与仪表盘「未结账」条都算进去(FR-1140)
 *   3  展开 → 改金额 → 勾「以后都按新金额」→ 确认:库里多一笔 FIXED_FLOW 支出、余额扣了、流水标「固定」(FR-1112 ~ 1115)
 *   4  撤销 → 流水软删、余额回去、确认记录没了(FR-1114);「这期没有」只记结果不记钱
 *   5  记一笔支出时勾「每月都有,设成固定」→ 一条设置 + 本期这笔直接算关联,不再作为待确认出现(FR-1131)
 *   6  没设固定还款的贷款:摘要里出「建议」,「接受」= 原来那个方法(FR-1120)
 *   7  贷款详情「设固定还款」(按上两月差值)→ 确认:贷款余额与「接受」逐分相同 + 付款账户多一笔「还贷」(FR-1108 / 1121)
 *   8  信用卡账单:只有一笔划转,没有支出(FR-1104)· 保险详情「设成固定交保费」→ 划转(FR-1105)
 *   9  导入去重两种顺序(FR-1150 / 1151)
 *  10  两期同时开着:在补录期的摘要确认 → 流水落在补录期(失败模式 ④)
 *  11  支出按「每人一个总数」时固定支出只列清单 + 合计提示(FR-1160)
 *  12  管理 → 备份 → 立即备份(FR-1170 ~ 1173):成功一条「手动」/ 写不了目录时如实失败;连点只多一条
 *  13  手机:摘要一行、展开后按钮整行点得中、不横向溢出(FR-1118)
 *
 * 动到的 beta 数据在 cleanup 里按「声明终态」还原:本 flow 建的设置 / 结果 / 流水 / 划转 / 导入批次删掉,
 * 涉及账户的余额快照、贷款历史、待填状态、补录期状态、支出录入方式恢复成跑之前的值。
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const CASH = 2;          // 工商银行-备用金
const ALI = 6;           // 支付宝-余额宝
const LOAN = 11;         // 房贷-招行
const CARD = 5;          // 招行信用卡(模板 credit_card)
const INS = 24;          // 保险账户(保单每期保费 12000)
const SEP = () => db.one(`SELECT id FROM period WHERE family_id=${fx.FAM} AND period_end < CURDATE() ORDER BY period_start DESC LIMIT 1`);
const TAG = 'e2e48';
const state = {};

const num = (s) => {
  if (s == null) return NaN;
  const t = String(s).replace(/[−–]/g, '-').replace(/[^\d.\-]/g, '');
  return t === '' || t === '-' ? NaN : Number(t);
};
const bal = (pid, acc) => Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${pid} AND account_id=${acc}`));
const flowId = (name) => db.one(`SELECT id FROM fixed_flow WHERE family_id=${fx.FAM} AND name='${name}' AND id > ${state.maxFf} ORDER BY id DESC LIMIT 1`);
const occ = (fid, pid) => db.one(`SELECT CONCAT_WS('|', id, status, IFNULL(amount,''), IFNULL(cash_flow_id,''), IFNULL(interest_cash_flow_id,''),
                                         IFNULL(transfer_id,''), IFNULL(loan_balance_before,''), IFNULL(loan_balance_after,''))
                                    FROM fixed_flow_occurrence WHERE fixed_flow_id=${fid} AND period_id=${pid}`);
const cf = (id) => db.one(`SELECT CONCAT_WS('|', period_id, account_id, kind, category_code, amount, source_tag, IFNULL(deleted_at,'-'), IFNULL(note,''))
                             FROM cash_flow WHERE id=${id}`);

/** 回到填报页:顶栏点「填报」(不拼 URL) */
async function toEntry(ui) {
  await ui.click('header a[href="/entry"] >> nth=0', '顶栏点「填报」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.page.waitForTimeout(400);
}

/** 摘要行展开(收起时点一下) */
async function openSummary(ui) {
  const open = await ui.page.locator('[data-ff-box]').evaluate(d => d.open).catch(() => null);
  if (open === false) await ui.click('[data-ff-box] > summary .ff-nm', '点开「固定收支」那一行');
}

/** 管理页表单:按种类填一条并保存 */
async function addFlow(ui, f) {
  await ui.click('[data-ff-new]', '管理页点「+ 加一条」');
  await ui.rendered('加一条固定收支');
  // 分段按钮的 radio 是藏起来的(和用户一样点那个字)
  await ui.click(`[data-ff-edit-form] label:has([data-ffe-kind][value="${f.kind}"]) span`, `「是什么」点「${{INCOME:'收入',EXPENSE:'支出',REPAY:'还款',PREMIUM:'交保费'}[f.kind]}」`);
  await ui.fill('[data-ff-edit-form] input[name="name"]', f.name, `名称填「${f.name}」`);
  const scope = `[data-ff-edit-form] [data-ffe-for="${f.kind}"]`;
  if (f.target) await ui.selectByName('targetAccountId', f.target, scope);
  await ui.selectByName('accountId', f.account, scope);
  if (f.category) await ui.selectByName('categoryCode', f.category, scope);
  if (f.repayKind) await ui.click(`[data-ff-edit-form] label:has([data-ffe-repay][value="${f.repayKind}"]) span`, `「还的是」点 ${f.repayKind}`);
  await ui.page.check(`[data-ffe-mode][value="${f.method ? 'AUTO' : 'FIXED'}"]`);
  await ui.page.waitForTimeout(150);
  if (f.method) await ui.selectByName('autoMethod', f.method, '[data-ff-edit-form]');
  if (f.amount != null) await ui.fill('[data-ffe-amount]', String(f.amount), `金额填 ${f.amount}`);
  await ui.fill('#ffe-day', String(f.day), `几号填 ${f.day}`);
  await ui.submit('[data-ffe-save]', `保存「${f.name}」`);
}

async function confirmItem(ui, fid, label, edit) {
  await openSummary(ui);
  const row = `[data-ff-item][data-ff-flow="${fid}"]`;
  if (edit) {
    await ui.fill(`${row} [data-ff-amount]`, String(edit.amount), `当场把金额改成 ${edit.amount}`);
    await ui.page.locator(`${row} [data-ff-amount]`).dispatchEvent('input');
    if (edit.remember) {
      await ui.visible(`${row} [data-ff-remember]`, '改了金额 → 出现「以后都按 ¥新金额?」');
      await ui.page.check(`${row} [data-ff-remember] input`);
    }
  }
  await ui.submit(`${row} [data-ff-confirm]`, label);
}

module.exports = {
  name: '48-fixed-flows',
  title: 'v1.32 · 固定收支(设一次 · 到日子一键确认 · 贷款试算并入)+ 立即备份',

  async run(ui, report) {
    ui.flow = this.name;
    await ui.page.route(/\/checkup\/(diagnose|insight)/, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
    ui.page.on('dialog', d => d.accept().catch(() => {}));

    // ── 前置:记下要还原的东西 ─────────────────────────────────────────
    state.cur = Number(fx.currentPeriod());
    state.sep = Number(SEP());
    for (const k of ['fixed_flow', 'fixed_flow_occurrence', 'cash_flow', 'transfer', 'expense_import_batch', 'backup_log']) {
      state['max_' + k] = db.num(`SELECT COALESCE(MAX(id),0) FROM ${k}`);
    }
    state.maxFf = state.max_fixed_flow;
    state.snaps = db.col(`SELECT CONCAT_WS('|', period_id, account_id, end_balance, IFNULL(source_tag,''))
                            FROM period_snapshot WHERE account_id IN (${CASH},${ALI},${LOAN},${CARD},${INS})
                             AND period_id IN (${state.cur},${state.sep})`);
    state.todos = db.col(`SELECT CONCAT_WS('|', period_id, account_id, status, IFNULL(done_by_member_id,''))
                            FROM snapshot_todo WHERE account_id IN (${CASH},${ALI},${LOAN},${CARD},${INS})
                             AND period_id IN (${state.cur},${state.sep})`);
    state.sepStatus = db.one(`SELECT status FROM period WHERE id=${state.sep}`);
    state.mode = db.one(`SELECT expense_entry_mode FROM family WHERE id=${fx.FAM}`);
    db.raw(`UPDATE family SET expense_entry_mode='ITEMIZED' WHERE id=${fx.FAM}`);
    // 贷款要有「上月 ≠ 上上月」的历史、本期还没人动过 —— 才有按差值的东西可算(beta 的这笔房贷几个月没变)
    state.loanPrev = Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.sep} AND account_id=${LOAN}`));
    state.loanPrevPrev = Number(db.one(`SELECT ps.end_balance FROM period_snapshot ps JOIN period p ON p.id=ps.period_id
                                         WHERE ps.account_id=${LOAN} AND p.period_start < (SELECT period_start FROM period WHERE id=${state.sep})
                                         ORDER BY p.period_start DESC LIMIT 1`));
    const prev = state.loanPrevPrev + 5540;
    db.raw(`UPDATE period_snapshot SET end_balance=${prev} WHERE period_id=${state.sep} AND account_id=${LOAN}`);
    db.raw(`UPDATE period_snapshot SET end_balance=${prev}, source_tag='CARRIED_FORWARD' WHERE period_id=${state.cur} AND account_id=${LOAN}`);
    db.raw(`UPDATE snapshot_todo SET status='PENDING', done_by_member_id=NULL WHERE period_id=${state.cur} AND account_id=${LOAN}`);
    state.loanPrevSet = prev;
    state.expectLoan = prev + (prev - state.loanPrevPrev);       // predictLoanBalance(prev, prevPrev)(还到 0 之前不夹)
    report.info(`前置:本期 ${state.cur} · 补录期候选 ${state.sep} · 贷款上月 ${prev} 上上月 ${state.loanPrevPrev} → 预计 ${state.expectLoan}`);

    // ── 1 · 入口 → 管理页 → 加一条 → 回填报页 ────────────────────────────
    report.section('1 · 填报页「本月收支」标题行「固定收支」→ 管理页加一条支出 → 回到填报页,摘要「1 笔待确认」');
    await ui.goto('/dashboard');
    const navBefore = num(await ui.page.textContent('header a[href="/entry"] span.text-rust').catch(() => '0')) || 0;
    await toEntry(ui);
    await ui.visible('[data-ff-entry]', '「本月收支」标题行有「固定收支」按钮');
    await ui.click('[data-ff-entry]', '点标题行「固定收支」');
    await ui.rendered('固定收支管理页');
    await ui.seesText('一键确认', '管理页说明这是什么');
    await addFlow(ui, { kind: 'EXPENSE', name: `${TAG}物业费`, account: CASH, category: 'consumption', amount: 480, day: 1 });
    state.f1 = flowId(`${TAG}物业费`);
    const f1 = db.one(`SELECT CONCAT_WS('|', kind, amount_mode, amount, account_id, category_code, cycle, day_of_month, DATE_FORMAT(start_month,'%Y-%m'))
                         FROM fixed_flow WHERE id=${state.f1}`);
    await ui.assert(f1 === `EXPENSE|FIXED|480.00|${CASH}|consumption|MONTHLY|1|${db.one(`SELECT DATE_FORMAT(period_start,'%Y-%m') FROM period WHERE id=${state.cur}`)}`,
      '真值层:fixed_flow 一行 · 支出 · 固定 480 · 工商银行 · 日常开支 · 每月 1 号 · 从本期起', f1);
    await ui.assert(/\/entry/.test(ui.page.url()), '保存后回到了填报页(从哪来回哪去)', ui.page.url());
    await ui.seesText('已保存固定收支', '摘要那一块提示「已保存」');
    await ui.visible('[data-ff-pending]', '摘要行写「N 笔待确认」');

    // ── 2 · 提醒:导航徽标 + 仪表盘 ───────────────────────────────────────
    report.section('2 · 导航「填报 ·N」多了 1;仪表盘「未结账」条写「· 1 笔固定收支待确认」(当天起算)');
    await ui.click('header a[href="/dashboard"] >> nth=0', '顶栏点「仪表盘」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const navAfter = num(await ui.page.textContent('header a[href="/entry"] span.text-rust').catch(() => '0')) || 0;
    await ui.assert(navAfter === navBefore + 1, '导航「填报 ·N」= 没填的账户 + 待确认的固定收支(多了 1)', `${navBefore} → ${navAfter}`);
    const dash = await ui.page.textContent('[data-dash-fixed-pending]').catch(() => null);
    await ui.assert(/1 笔固定收支待确认/.test(dash || ''), '仪表盘「未结账」条:1 笔固定收支待确认', dash);

    // ── 3 · 确认(改金额 + 以后都按新金额)────────────────────────────────
    report.section('3 · 展开 → 金额改 520 + 勾「以后都按」→ 确认:库里一笔 FIXED_FLOW 支出、余额扣 520、设置改成 520');
    await toEntry(ui);
    const cash0 = bal(state.cur, CASH);
    await confirmItem(ui, state.f1, '点「确认」', { amount: 520, remember: true });
    let o1 = String(occ(state.f1, state.cur) || '').split('|');
    await ui.assert(o1[1] === 'CONFIRMED' && o1[2] === '520.00' && Number(o1[3]) > 0, '真值层:本期确认记录 CONFIRMED · 520.00 · 指向一笔流水', o1.join('|'));
    const c1 = String(cf(o1[3]) || '').split('|');
    await ui.assert(Number(c1[0]) === state.cur && Number(c1[1]) === CASH && c1[2] === 'EXPENSE' && c1[3] === 'consumption'
        && c1[4] === '520.00' && c1[5] === 'FIXED_FLOW' && c1[6] === '-' && c1[7] === `${TAG}物业费`,
      '真值层:cash_flow 一笔 · 本期 · 工商银行 · 支出 · 日常开支 · 520.00 · 来源 FIXED_FLOW · 备注=名称', c1.join('|'));
    await ui.assert(Math.abs(bal(state.cur, CASH) - (cash0 - 520)) < 0.005, '真值层:工商银行本期余额 −520(和手动记一笔一样)', `${cash0} → ${bal(state.cur, CASH)}`);
    await ui.assert(db.one(`SELECT amount FROM fixed_flow WHERE id=${state.f1}`) === '520.00', '真值层:「以后都按 ¥520」→ 设置里的固定金额改成 520');
    await ui.seesText(`「${TAG}物业费」已记支出`, '摘要那一块说「记成了什么」');
    // 列表可能分页(≥12 笔时只露第一页),按 DOM 数,不按可见
    await ui.assert(await ui.page.locator(`[data-flow-list="expense"] .pill-brass:text-is("固定")`).count() >= 1, '支出流水列表上这一笔标「固定」');

    // ── 4 · 撤销 / 这期没有 ──────────────────────────────────────────────
    report.section('4 · 已确认里点「撤销」→ 流水软删、余额回去、确认记录没了;「这期没有」只记结果');
    await openSummary(ui);
    await ui.click('[data-ff-done] > summary', '点开「已确认 N 笔」');
    await ui.submit(`[data-ff-done] [data-ff-flow="${state.f1}"] [data-ff-undo] button`, '点「撤销」并确认');
    await ui.assert(!occ(state.f1, state.cur), '真值层:确认记录删掉了');
    await ui.assert(String(cf(o1[3])).split('|')[6] !== '-', '真值层:那笔流水软删了(备份里还在)');
    await ui.assert(Math.abs(bal(state.cur, CASH) - cash0) < 0.005, '真值层:余额回到确认之前', `${cash0} / ${bal(state.cur, CASH)}`);
    await openSummary(ui);
    await ui.submit(`[data-ff-item][data-ff-flow="${state.f1}"] [data-ff-skip]`, '点「这期没有」');
    o1 = String(occ(state.f1, state.cur) || '').split('|');
    await ui.assert(o1[1] === 'SKIPPED' && o1[3] === '' && o1[5] === '', '真值层:只记了 SKIPPED,没有流水、没有划转', o1.join('|'));
    await ui.assert(Math.abs(bal(state.cur, CASH) - cash0) < 0.005, '真值层:这期没有 → 余额不动');

    // ── 5 · 记一笔时顺手设 ────────────────────────────────────────────────
    report.section('5 · 记一笔支出时勾「每月都有,设成固定」→ 一条设置 + 本期这笔算已关联,不再作为待确认出现');
    await ui.fill('form[action="/entry/expense"] input[name="amount"]', '66.6', '支出金额填 66.6');
    await ui.choose('accountId', ALI, 'form[action="/entry/expense"]');
    await ui.fill('form[action="/entry/expense"] input[name="note"]', `${TAG}会员费`, '备注填会员费');
    await ui.page.check('form[action="/entry/expense"] [data-make-fixed] input');
    await ui.submit('form[action="/entry/expense"] button[type="submit"]', '点「+ 加一笔」');
    state.f2 = flowId(`${TAG}会员费`);
    const today = new Date().getDate();
    const f2 = db.one(`SELECT CONCAT_WS('|', kind, amount, account_id, day_of_month) FROM fixed_flow WHERE id=${state.f2}`);
    await ui.assert(f2 === `EXPENSE|66.60|${ALI}|${today}`, '真值层:建了一条固定支出 · 66.60 · 余额宝 · 每月今天几号', f2);
    const o2 = String(occ(state.f2, state.cur) || '').split('|');
    const c2 = String(cf(o2[3]) || '').split('|');
    await ui.assert(o2[1] === 'LINKED' && c2[4] === '66.60' && c2[5] === 'MANUAL',
      '真值层:本期确认记录 LINKED → 指向刚记的那笔(来源仍是手动)', `${o2.join('|')} · ${c2.join('|')}`);
    await ui.notVisible(`[data-ff-box] > [data-ff-item][data-ff-flow="${state.f2}"]`, '它不会再作为「待确认」出现一次');

    // ── 6 · 没设固定还款的贷款:建议 + 接受(= 原来那个方法)───────────────
    report.section('6 · 没设固定还款的贷款 → 摘要里一条「建议 · 按上两月差值」,贷款行只剩一行指路;「接受」= 原来的结果');
    await toEntry(ui);
    await openSummary(ui);
    await ui.visible(`[data-ff-item][data-ff-loan="${LOAN}"][data-ff-state="SUGGEST"]`, '摘要里有这笔贷款的建议');
    await ui.visible(`#entry-block-${LOAN} [data-ff-loan-hint]`, '贷款行里只剩「本期还款 · 待确认 ↑」');
    await ui.assert(await ui.page.locator(`#entry-block-${LOAN} form[hx-post*="accept-loan-prediction"]`).count() === 0,
      '贷款行内不再有「接受」表单(挪进了摘要,FR-1117)');
    await ui.submit(`[data-ff-item][data-ff-loan="${LOAN}"] [data-ff-accept] button`, '点「接受」');
    const accepted = bal(state.cur, LOAN);
    await ui.assert(Math.abs(accepted - state.expectLoan) < 0.005, '真值层:贷款本期余额 = 上月 + (上月 − 上上月)(与旧版「接受」同一个函数)',
      `${accepted} vs ${state.expectLoan}`);
    // 还原成「没人动过」,下面用固定还款再走一次
    db.raw(`UPDATE period_snapshot SET end_balance=${state.loanPrevSet}, source_tag='CARRIED_FORWARD' WHERE period_id=${state.cur} AND account_id=${LOAN}`);
    db.raw(`UPDATE snapshot_todo SET status='PENDING', done_by_member_id=NULL WHERE period_id=${state.cur} AND account_id=${LOAN}`);
    db.raw(`DELETE FROM transfer WHERE id > ${state.max_transfer} AND to_account_id=${LOAN} AND is_draft=1`);

    // ── 7 · 贷款详情「设固定还款」→ 确认 ───────────────────────────────────
    report.section('7 · 账户 → 房贷详情「设固定还款」(按上两月差值)→ 确认:贷款余额与「接受」逐分相同 + 工商银行多一笔「还贷」');
    await ui.click('header a[href="/accounts"] >> nth=0', '顶栏点「账户」');
    await ui.click(`main a[href="/accounts/${LOAN}"] >> nth=0`, '账户簿点「房贷-招行」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.visible('[data-fixed-lines]', '贷款详情有「固定收支」那一行');
    await ui.click('[data-fixed-make]', '点「设固定还款」');
    await ui.rendered('还款表单(预填)');
    const pre = await ui.page.evaluate(() => ({
      kind: document.querySelector('[data-ffe-kind]:checked')?.value,
      repay: document.querySelector('[data-ffe-repay]:checked')?.value,
      mode: document.querySelector('[data-ffe-mode]:checked')?.value,
      method: document.querySelector('[data-ffe-method]')?.value }));
    await ui.assert(pre.kind === 'REPAY' && pre.repay === 'LOAN' && pre.mode === 'AUTO' && pre.method === 'LOAN_DIFF',
      '预填:还款 · 贷款月供 · 自动 · 按上月和上上月的差值(就是原来贷款那套)', JSON.stringify(pre));
    await ui.selectByName('accountId', CASH, '[data-ff-edit-form] [data-ffe-for="REPAY"]');
    await ui.fill('#ffe-day', '1', '几号填 1');
    await ui.submit('[data-ffe-save]', '保存');
    state.f3 = db.one(`SELECT id FROM fixed_flow WHERE family_id=${fx.FAM} AND target_account_id=${LOAN} AND id > ${state.maxFf} ORDER BY id DESC LIMIT 1`);
    await ui.assert(/\/accounts\/11/.test(ui.page.url()), '保存后回到房贷详情', ui.page.url());
    await ui.seesText('每期还款 · 自动 · 按上两月差值 · 从 工商银行-备用金', '详情上写「每期还款 · 自动 · 按上两月差值 · 从 …」');
    await toEntry(ui);
    await openSummary(ui);
    await ui.assert(await ui.page.locator(`[data-ff-item][data-ff-loan="${LOAN}"][data-ff-state="SUGGEST"]`).count() === 0,
      '设了固定还款 → 这笔贷款不再单独出建议(一个贷款只出一行,FR-1121)');
    const cash1 = bal(state.cur, CASH);
    await confirmItem(ui, state.f3, '房贷那一行点「确认」');
    const o3 = String(occ(state.f3, state.cur) || '').split('|');
    await ui.assert(Math.abs(bal(state.cur, LOAN) - accepted) < 0.005, '真值层:贷款余额与上面「接受」逐分相同', `${bal(state.cur, LOAN)} vs ${accepted}`);
    const c3 = String(cf(o3[3]) || '').split('|');
    await ui.assert(Number(c3[1]) === CASH && c3[3] === 'loan_payment' && Number(c3[4]) === 5540 && c3[5] === 'FIXED_FLOW' && o3[4] === '',
      '真值层:工商银行一笔「还贷」5,540(本金)· 方式 ① 不记利息', c3.join('|'));
    await ui.assert(Math.abs(bal(state.cur, CASH) - (cash1 - 5540)) < 0.005, '真值层:工商银行余额 −5,540');
    await ui.assert(Number(o3[6]) === state.loanPrevSet && Math.abs(Number(o3[7]) - accepted) < 0.005,
      '真值层:确认记录存下贷款余额写前 / 写后(撤销时按它还原)', o3.join('|'));

    // ── 8 · 信用卡账单 / 交保费 → 只记划转 ─────────────────────────────────
    report.section('8 · 信用卡账单(全额还上期)只有一笔划转、没有支出;保险详情「设成固定交保费」→ 划转');
    await ui.click('[data-ff-manage]', '摘要行点「管理」');
    await addFlow(ui, { kind: 'REPAY', name: `${TAG}还信用卡`, target: CARD, account: CASH, repayKind: 'CARD', method: 'CARD_FULL', day: 1 });
    state.f4 = flowId(`${TAG}还信用卡`);
    const cardPrev = Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.sep} AND account_id=${CARD}`));
    const card0 = bal(state.cur, CARD);
    await confirmItem(ui, state.f4, '信用卡那一行点「确认」');
    const o4 = String(occ(state.f4, state.cur) || '').split('|');
    const t4 = db.one(`SELECT CONCAT_WS('|', from_account_id, to_account_id, amount, source_tag, is_draft) FROM transfer WHERE id=${o4[5] || 0}`);
    await ui.assert(o4[3] === '' && t4 === `${CASH}|${CARD}|${(-cardPrev).toFixed(2)}|FIXED_FLOW|0`,
      '真值层:只有一笔划转 工商银行 → 信用卡 · 金额 = 上月末欠款 · 没有支出流水', `${o4.join('|')} · ${t4}`);
    await ui.assert(Math.abs(bal(state.cur, CARD) - (card0 - cardPrev)) < 0.005, '真值层:信用卡欠款少了这么多');
    await ui.click('header a[href="/accounts"] >> nth=0', '顶栏点「账户」');
    await ui.click(`main a[href="/accounts/${INS}"] >> nth=0`, '账户簿点保险账户');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click('[data-fixed-make]', '点「设成固定交保费」');
    const preIns = await ui.page.evaluate(() => ({ kind: document.querySelector('[data-ffe-kind]:checked')?.value,
      method: document.querySelector('[data-ffe-method]')?.value, mode: document.querySelector('[data-ffe-mode]:checked')?.value }));
    await ui.assert(preIns.kind === 'PREMIUM' && preIns.mode === 'AUTO' && preIns.method === 'POLICY', '预填:交保费 · 按保单每期保费', JSON.stringify(preIns));
    await ui.selectByName('accountId', CASH, '[data-ff-edit-form] [data-ffe-for="PREMIUM"]');
    await ui.fill('#ffe-day', '1', '几号填 1');
    await ui.submit('[data-ffe-save]', '保存');
    state.f5 = db.one(`SELECT id FROM fixed_flow WHERE family_id=${fx.FAM} AND target_account_id=${INS} AND id > ${state.maxFf} ORDER BY id DESC LIMIT 1`);
    await toEntry(ui);
    await confirmItem(ui, state.f5, '交保费那一行点「确认」');
    const o5 = String(occ(state.f5, state.cur) || '').split('|');
    const t5 = db.one(`SELECT CONCAT_WS('|', from_account_id, to_account_id, amount) FROM transfer WHERE id=${o5[5] || 0}`);
    const premium = db.one(`SELECT premium_amount FROM account_insurance_policy WHERE account_id=${INS}`);
    await ui.assert(o5[3] === '' && t5 === `${CASH}|${INS}|${premium}`, '真值层:交保费 = 一笔划转 工商银行 → 保险账户 · 保单每期保费 · 不算支出', `${o5.join('|')} · ${t5}`);

    // ── 9 · 导入去重 ────────────────────────────────────────────────────
    report.section('9 · 先确认后导入:账单里同账户同金额那行默认不勾、写原因;先导入后确认:「就是它」不另记');
    await ui.click('[data-ff-manage]', '摘要行点「管理」');
    await addFlow(ui, { kind: 'EXPENSE', name: `${TAG}车位月保`, account: ALI, category: 'consumption', amount: 300, day: 1 });
    state.f6 = flowId(`${TAG}车位月保`);
    await confirmItem(ui, state.f6, '车位月保点「确认」');
    await ui.click('[data-ff-manage]', '摘要行点「管理」');
    await addFlow(ui, { kind: 'EXPENSE', name: `${TAG}水费`, account: ALI, category: 'consumption', amount: 77.7, day: 1 });
    state.f7 = flowId(`${TAG}水费`);
    const csv = path.join(os.tmpdir(), `${TAG}-alipay.csv`);
    const day = db.one(`SELECT DATE_FORMAT(period_start,'%Y-%m-02') FROM period WHERE id=${state.cur}`);
    fs.writeFileSync(csv, ['支付宝交易明细', '说明',
      '交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注',
      `${day} 09:00:00,爱车养车,停车场,p@p.com,月卡,支出,300.00,余额宝,交易成功,${TAG}T1,${TAG}M1,`,
      `${day} 10:00:00,生活缴费,自来水,w@w.com,水费,支出,77.70,余额宝,交易成功,${TAG}T2,${TAG}M2,`,
      '------------------------------------'].join('\n'));
    await toEntry(ui);
    await ui.click('a[href*="/expense/import"]:has-text("批量导入")', '支出区点「批量导入」');
    await ui.rendered('导入页');
    await ui.page.setInputFiles('#csvFile', csv);
    await ui.choose('defaultAccountId', ALI);
    await ui.submit('form[action="/expense/import/file"] button[type="submit"]', '选文件 → 点「解析」');
    const rows = await ui.page.evaluate(() => [...document.querySelectorAll('tr[data-row]')].map(r => ({
      m: r.getAttribute('data-merchant'), amt: r.getAttribute('data-amt'),
      checked: r.querySelector('input[name=include]')?.checked, reason: r.textContent.includes('已作为固定支出记过') })));
    const r300 = rows.find(r => Number(r.amt) === 300), r77 = rows.find(r => Number(r.amt) === 77.7);
    await ui.assert(r300 && !r300.checked && r300.reason, '账单里 ¥300 那行默认不勾、写「已作为固定支出记过 · 车位月保」(还能勾回来)', JSON.stringify(r300));
    await ui.assert(r77 && r77.checked, '¥77.70 那行照常默认勾上', JSON.stringify(r77));
    await ui.submit('form[data-bill-form] button.btn-ink[type="submit"]', '点「确认导入」(只导勾上的)');
    state.batch = db.one(`SELECT MAX(id) FROM expense_import_batch WHERE id > ${state.max_expense_import_batch}`);
    const imported = db.col(`SELECT CONCAT_WS('|', id, amount) FROM cash_flow WHERE import_batch_id=${state.batch || 0} AND deleted_at IS NULL`);
    await ui.assert(imported.length === 1 && imported[0].split('|')[1] === '77.70', '真值层:只导进了 77.70 那一笔(300 没重复记)', imported.join(' ; '));
    await toEntry(ui);
    await openSummary(ui);
    await ui.visible(`[data-ff-item][data-ff-flow="${state.f7}"] + [data-ff-link], [data-ff-item][data-ff-flow="${state.f7}"] [data-ff-link]`,
      '水费那一行提示「导入的账单里有一笔 ¥77.70 可能就是它」');
    await ui.submit(`[data-ff-item][data-ff-flow="${state.f7}"] [data-ff-link] button`, '点「就是它」');
    const o7 = String(occ(state.f7, state.cur) || '').split('|');
    await ui.assert(o7[1] === 'LINKED' && o7[3] === imported[0].split('|')[0], '真值层:确认记录 LINKED → 指向导入的那一笔', o7.join('|'));
    await ui.assert(db.num(`SELECT COUNT(*) FROM cash_flow WHERE id > ${state.max_cash_flow} AND note='${TAG}水费' AND deleted_at IS NULL`) === 0,
      '真值层:没有另记一笔水费');

    // ── 10 · 两期同时开着 ───────────────────────────────────────────────
    report.section('10 · 两期同时开着:在补录期的摘要里确认 → 流水落在补录期,不是进行期');
    db.raw(`UPDATE period SET status='OPEN' WHERE id=${state.sep}`);
    await ui.click('[data-ff-manage]', '摘要行点「管理」');
    await addFlow(ui, { kind: 'EXPENSE', name: `${TAG}补录房租`, account: CASH, category: 'consumption', amount: 1234, day: 3 });
    state.f8 = flowId(`${TAG}补录房租`);
    await toEntry(ui);
    await ui.click(`.entry-perpill[href*="period=${state.sep}"]`, '点上面的补录期 pill');
    await confirmItem(ui, state.f8, '补录期摘要里点「确认」');
    const o8 = String(occ(state.f8, state.sep) || '').split('|');
    const c8 = String(cf(o8[3] || 0) || '').split('|');
    await ui.assert(o8[1] === 'CONFIRMED' && Number(c8[0]) === state.sep, '真值层:确认记录与流水都在补录期', `${o8.join('|')} · ${c8.join('|')}`);
    await ui.assert(!occ(state.f8, state.cur), '真值层:进行期没有多出这一笔');
    db.raw(`UPDATE period SET status='${state.sepStatus}' WHERE id=${state.sep}`);

    // ── 11 · 总数模式 ───────────────────────────────────────────────────
    report.section('11 · 支出按「每人一个总数」:固定支出只列清单(没有确认按钮),总数框旁写本期固定支出合计');
    db.raw(`UPDATE family SET expense_entry_mode='TOTAL' WHERE id=${fx.FAM}`);
    await ui.click('[data-ff-manage]', '摘要行点「管理」');
    await addFlow(ui, { kind: 'EXPENSE', name: `${TAG}网费`, account: CASH, category: 'consumption', amount: 99, day: 1 });
    state.f9 = flowId(`${TAG}网费`);
    await toEntry(ui);
    await openSummary(ui);
    await ui.assert(await ui.page.locator(`[data-ff-item][data-ff-flow="${state.f9}"][data-ff-state="LIST"]`).count() === 1
        && await ui.page.locator(`[data-ff-item][data-ff-flow="${state.f9}"] [data-ff-confirm]`).count() === 0,
      '网费只列清单,没有「确认」按钮');
    const hint = await ui.page.textContent('[data-ff-total-hint]').catch(() => null);
    await ui.assert(/本期固定支出/.test(hint || '') && /网费/.test(hint || ''), '总数框旁:「本期固定支出 ¥…(…网费 ¥99)—— 填总数时别漏了」', hint);
    db.raw(`UPDATE family SET expense_entry_mode='ITEMIZED' WHERE id=${fx.FAM}`);

    // ── 12 · 立即备份 ───────────────────────────────────────────────────
    report.section('12 · 管理 → 备份 → 立即备份:一条「手动」记录(成功写文件 / 写不了目录时如实失败);连点只多一条');
    await ui.click('header a[href="/admin"] >> nth=0', '顶栏点「管理」');
    await ui.click('main a.group[href="/admin/backup"]', '总览点「备份」');
    await ui.rendered('备份页');
    const warn = await ui.page.locator('[data-backup-dir-warn]').count();
    await ui.submit('[data-backup-now] button', '点「立即备份」');
    const bk = db.col(`SELECT CONCAT_WS('|', kind, status, IFNULL(size_bytes,''), IFNULL(location_local,''), IFNULL(error_message,''))
                         FROM backup_log WHERE id > ${state.max_backup_log}`);
    await ui.assert(bk.length === 1 && bk[0].startsWith('MANUAL|'), '真值层:backup_log 多了一行 MANUAL', bk.join(' ; '));
    const [, status, size, loc, err] = String(bk[0] || '').split('|');
    if (warn === 0) {
      await ui.assert(status === 'SUCCESS' && Number(size) > 0 && fs.existsSync(loc), '目录可写:成功 · 文件在 · 有大小', bk[0]);
      await ui.seesText('已备份', '页面提示「已备份 · 文件 · 大小」');
    } else {
      await ui.assert(status === 'FAILED' && /写权限/.test(err), '目录没写权限:记 FAILED 并写真实原因(不吞报错)', bk[0]);
      await ui.seesText('备份失败', '页面提示「备份失败:…没有写权限」');
      report.info('beta 的 /var/backup/finance 属主还是 root —— 维护者跑一次 sudo chown finance:finance /var/backup/finance 后,这一段会走成功分支');
    }
    await ui.submit('[data-backup-now] button', '马上再点一次「立即备份」');
    await ui.seesText('1 分钟', '提示「刚备份过,1 分钟后再点」');
    await ui.assert(db.num(`SELECT COUNT(*) FROM backup_log WHERE id > ${state.max_backup_log}`) === 1, '真值层:连点只多了一行');

    // ── 13 · 手机 ───────────────────────────────────────────────────────
    report.section('13 · 手机(390 · 触屏):摘要一行,展开后按钮整行、点得中;不横向溢出');
    await ui.phone(async (m) => {
      await m.goto('/dashboard');
      await m.click('header details > summary:visible', '点右上角菜单');
      await m.click('header details[open] a[href="/entry"]:visible', '菜单里点「填报」');
      await m.page.waitForLoadState('networkidle').catch(() => {});
      const sumH = await m.page.locator('[data-ff-box] > summary').evaluate(e => e.getBoundingClientRect().height).catch(() => 999);
      await m.assert(sumH < 100, '摘要默认收起:一行字 + 一个整行的「全部确认」(高度 < 100px)', `${sumH}px`);
      await m.click('[data-ff-box] > summary .ff-nm', '点开摘要');
      const btn = await m.page.locator('[data-ff-item] [data-ff-skip]').first().evaluate(e => {
        e.scrollIntoView({ block: 'center' });
        const r = e.getBoundingClientRect(); const top = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
        return { h: r.height, w: r.width, hit: top === e || e.contains(top) };
      }).catch(() => null);
      await m.assert(btn && btn.h >= 36 && btn.w >= 120 && btn.hit, '展开后按钮整行、≥36px 高、点得中(不被遮)', JSON.stringify(btn));
      const over = await m.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      await m.assert(over <= 1, '填报页没有横向溢出', `${over}px`);
      await m.page.screenshot({ path: '/tmp/e2e-48-entry-mobile.png', fullPage: false }).catch(() => {});
    });
    await ui.noConsoleErrors('这几个页面控制台无报错');
  },

  async cleanup(ui, report) {
    await ui.page.unroute(/\/checkup\/(diagnose|insight)/).catch(() => {});
    const sqls = [
      `UPDATE family SET expense_entry_mode='${state.mode || 'ITEMIZED'}' WHERE id=${fx.FAM}`,
      state.sep && state.sepStatus ? `UPDATE period SET status='${state.sepStatus}' WHERE id=${state.sep}` : null,
      `DELETE FROM fixed_flow_occurrence WHERE id > ${state.max_fixed_flow_occurrence || 0}`,
      `DELETE FROM fixed_flow WHERE id > ${state.max_fixed_flow || 0}`,
      `DELETE FROM cash_flow WHERE id > ${state.max_cash_flow || 0} AND account_id IN (${CASH},${ALI},${LOAN},${CARD},${INS})`,
      `DELETE FROM transfer WHERE id > ${state.max_transfer || 0} AND (from_account_id IN (${CASH},${ALI}) OR to_account_id IN (${LOAN},${CARD},${INS}))`,
      `DELETE FROM expense_import_batch WHERE id > ${state.max_expense_import_batch || 0}`,
      `DELETE FROM backup_log WHERE id > ${state.max_backup_log || 0} AND kind='MANUAL'`,
      // 本 flow 改过 / 写过的快照逐个恢复成跑之前的值
      ...(state.snaps || []).map(s => { const [p, a, b, t] = s.split('|');
        return `UPDATE period_snapshot SET end_balance=${b}, source_tag=${t ? `'${t}'` : 'NULL'} WHERE period_id=${p} AND account_id=${a}`; }),
      ...(state.todos || []).map(s => { const [p, a, st, by] = s.split('|');
        return `UPDATE snapshot_todo SET status='${st}', done_by_member_id=${by || 'NULL'} WHERE period_id=${p} AND account_id=${a}`; }),
    ].filter(Boolean);
    for (const sql of sqls) { try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 70)} · ${String(e.message).slice(0, 80)}`); } }
    if (state.loanPrev !== undefined) {
      const now = Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.sep} AND account_id=${LOAN}`));
      await ui.assert(Math.abs(now - state.loanPrev) < 0.005, '还原:贷款上月余额回到跑之前', `${state.loanPrev} / ${now}`);
    }
  },
};
