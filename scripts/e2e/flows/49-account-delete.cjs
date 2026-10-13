/**
 * flow · v1.33 · 删除账户(issue #39)—— prd/v1.33.md §10 验收 1 ~ 10
 *
 *   1  账户簿 ⋯ →「删除账户」→ 命令行面板:改写的期、移走的行、逐笔划转(收到 / 转出 / 转去开户)、确认语,与库里一致
 *   2  确认语没对上 → 按钮暗、提示还差几行;「复制这段话」→ 粘进框 → 按钮亮
 *   3  删除(先备份):能写 → 先备份再删;备份目录写不了 → 不删、写原因并指路「不备份,直接删除」、库里一行不少,再点它删
 *   4  删完:挂账户的表里都没有它、回收站有;对方那头的本金进出方向对;对方那一期的损益逐分不变;报表留痕
 *   5  管理 → 回收站 → 恢复:逐表行数与删前相同、改记拿掉、报表留痕「恢复后重算」
 *   6  编辑页「危险操作」再删一次,这次点「不备份,直接删除」(不多备份、回收站行没有备份文件、审计写「未备份」)→ 回收站「彻底清除」要打字 → 清除后 payload 清空
 *   7  手机(390 · 触屏):面板不横向溢出、复制按钮整行
 *
 * 前置用 SQL 造一个带历史的测试账户(过去的已关账月份没法从页面上造出来,fixture.cjs 的纪律 ①):
 *   8 月它 → 工资卡(工商银行)111 · 9 月余额宝 → 它 112 · 10 月它 → 一个本月才开的新账户 300(新账户第一期)
 * cleanup:两个测试账户在所有表里的行、本 flow 写的补录本金 / 回收站 / 手动备份记录全部删掉。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const { BASE } = require('../lib/browser.cjs');

const WAGE = 2;   // 工商银行-备用金
const YEB = 6;    // 支付宝-余额宝
const NAME = 'e2e49测试账户';
const FRESH_NAME = 'e2e49新账户';
const state = {};

// 与 AccountTrashSpec 同一份清单(表 → 「属于这个账户」的条件)—— 库里逐表数行用
const SPEC = (a) => ({
  period_snapshot: `account_id=${a}`, snapshot_todo: `account_id=${a}`, cash_flow: `account_id=${a}`,
  transfer: `(from_account_id=${a} OR to_account_id=${a})`, principal_adjustment: `account_id=${a}`,
  period_account_attr: `account_id=${a}`, period_account_group: `account_id=${a}`, stock_holding: `account_id=${a}`,
  holding_share_event: `account_id=${a}`, stock_valuation_event: `account_id=${a}`, fixed_flow: `(account_id=${a} OR target_account_id=${a})`,
  goal_account: `account_id=${a}`, rebalance_plan_item: `(from_account_id=${a} OR to_account_id=${a})`,
  account_group_member: `account_id=${a}`, account_insurance_policy: `account_id=${a}`, broker_link: `account_id=${a}`,
  holding_import: `account_id=${a}`, expense_account_rule: `account_id=${a}`, account: `id=${a}`,
});
const counts = (a) => Object.fromEntries(Object.entries(SPEC(a)).map(([t, w]) => [t, db.num(`SELECT COUNT(*) FROM ${t} WHERE ${w}`)]));

/** 某账户某一期的本期损益(与事实层同一个式子):余额变化 − 收入 + 支出 − 净划转 − 补录本金 */
function pnl(acc, pid) {
  const start = db.one(`SELECT period_start FROM period WHERE id=${pid}`);
  const end = Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${pid} AND account_id=${acc}`) || 0);
  const prev = Number(db.one(`SELECT ps.end_balance FROM period_snapshot ps JOIN period p ON p.id=ps.period_id
                               WHERE ps.account_id=${acc} AND p.period_start < '${start}' ORDER BY p.period_start DESC LIMIT 1`) || 0);
  const inc = Number(db.one(`SELECT COALESCE(SUM(amount),0) FROM cash_flow WHERE account_id=${acc} AND period_id=${pid} AND kind='INCOME' AND deleted_at IS NULL`));
  const exp = Number(db.one(`SELECT COALESCE(SUM(amount),0) FROM cash_flow WHERE account_id=${acc} AND period_id=${pid} AND kind='EXPENSE' AND deleted_at IS NULL AND affects_balance=1`));
  const tin = Number(db.one(`SELECT COALESCE(SUM(COALESCE(to_amount,amount)),0) FROM transfer WHERE to_account_id=${acc} AND period_id=${pid} AND deleted_at IS NULL AND is_draft=0`));
  const tout = Number(db.one(`SELECT COALESCE(SUM(amount),0) FROM transfer WHERE from_account_id=${acc} AND period_id=${pid} AND deleted_at IS NULL AND is_draft=0`));
  const pa = Number(db.one(`SELECT COALESCE(SUM(amount),0) FROM principal_adjustment WHERE account_id=${acc} AND period_id=${pid} AND deleted_at IS NULL`));
  return Math.round((end - prev - inc + exp - (tin - tout) - pa) * 100) / 100;
}

/** 从账户簿把这个账户的 ⋯ 菜单点开,点「删除账户」 */
async function openDeletePanel(ui, accountId, label) {
  await ui.click('header a[href="/accounts"] >> nth=0', '顶栏点「账户」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  const row = `tr:has(a[href="/accounts/${accountId}/delete"])`;
  await ui.click(`${row} details.row-more > summary`, `${label} 那一行点 ⋯`);
  await ui.click(`${row} details.row-more[open] a[data-delete-account]`, '菜单里点「删除账户」');
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await ui.rendered('删除面板');
}

module.exports = {
  name: '49-account-delete',
  title: 'v1.33 · 删除账户(预演面板 · 打字确认 · 先备份 · 回收站恢复 / 彻底清除)',

  async run(ui, report) {
    ui.flow = this.name;
    ui.page.on('dialog', d => d.accept().catch(() => {}));
    await ui.page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE }).catch(() => {});

    // ── 前置:造账户与历史 ────────────────────────────────────────────────
    state.cur = Number(fx.currentPeriod());
    const ended = db.col(`SELECT id FROM period WHERE family_id=${fx.FAM} AND period_end < CURDATE() AND status='CLOSED'
                            ORDER BY period_start DESC LIMIT 2`).map(Number);
    [state.p2, state.p1] = ended;                     // p1 = 上上期(8 月),p2 = 上期(9 月)
    for (const k of ['principal_adjustment', 'account_trash', 'backup_log', 'audit_log']) state['max_' + k] = db.num(`SELECT COALESCE(MAX(id),0) FROM ${k}`);
    const me = db.num(`SELECT id FROM member WHERE family_id=${fx.FAM} ORDER BY id LIMIT 1`);
    db.raw(`INSERT INTO account (family_id, display_name, type, currency, display_order) VALUES (${fx.FAM}, '${NAME}', 'CASH', 'CNY', 990)`);
    state.x = db.num(`SELECT MAX(id) FROM account WHERE display_name='${NAME}'`);
    db.raw(`INSERT INTO account (family_id, display_name, type, currency, display_order) VALUES (${fx.FAM}, '${FRESH_NAME}', 'CASH', 'CNY', 991)`);
    state.n = db.num(`SELECT MAX(id) FROM account WHERE display_name='${FRESH_NAME}'`);
    const X = state.x, N = state.n;
    db.raw(`INSERT INTO period_snapshot (period_id, account_id, end_balance, submitted_by, source_tag) VALUES
            (${state.p1}, ${X}, 1000.00, ${me}, 'MANUAL'), (${state.p2}, ${X}, 1612.00, ${me}, 'MANUAL'),
            (${state.cur}, ${X}, 1312.00, ${me}, 'MANUAL'), (${state.cur}, ${N}, 300.00, ${me}, 'MANUAL')`);
    db.raw(`INSERT INTO cash_flow (period_id, account_id, kind, category_code, amount, occurred_at, submitted_by, source_tag)
            VALUES (${state.p2}, ${X}, 'INCOME', 'salary', 500.00, CURDATE(), ${me}, 'MANUAL')`);
    db.raw(`INSERT INTO transfer (period_id, from_account_id, to_account_id, amount, submitted_by, source_tag) VALUES
            (${state.p1}, ${X}, ${WAGE}, 111.00, ${me}, 'MANUAL'), (${state.p2}, ${YEB}, ${X}, 112.00, ${me}, 'MANUAL'),
            (${state.cur}, ${X}, ${N}, 300.00, ${me}, 'MANUAL')`);
    db.raw(`INSERT INTO snapshot_todo (period_id, account_id, status) VALUES (${state.cur}, ${X}, 'PENDING')`);
    state.before = counts(X);
    state.pnlWage = pnl(WAGE, state.p1);
    state.pnlYeb = pnl(YEB, state.p2);
    const ym = (pid) => db.one(`SELECT DATE_FORMAT(period_start,'%Y%m') FROM period WHERE id=${pid}`);
    const ymd = (pid) => db.one(`SELECT DATE_FORMAT(period_start,'%Y-%m') FROM period WHERE id=${pid}`);
    const wageName = db.one(`SELECT display_name FROM account WHERE id=${WAGE}`);
    const yebName = db.one(`SELECT display_name FROM account WHERE id=${YEB}`);
    state.phrase = `我知晓删除「${NAME}」会改写 ${ymd(state.p1)} 至 ${ymd(state.cur)} 共 3 个账期,\n`
      + `${ym(state.p1)} 账期 ${wageName} 的转账 111.00 元也会受到影响,\n`
      + `${ym(state.p2)} 账期 ${yebName} 的转账 112.00 元也会受到影响,\n`
      + `${ym(state.cur)} 账期 ${FRESH_NAME} 的转账 300.00 元也会受到影响。`;
    report.info(`前置:测试账户 #${X}(8 月 / 9 月已关账 + 本期)· 新账户 #${N} · 删前 ${JSON.stringify(state.before)}`);

    // ── 1 · 面板 ──────────────────────────────────────────────────────────
    report.section('1 · 账户簿 ⋯ →「删除账户」→ 面板内容与库里一致');
    await ui.goto('/dashboard');
    await openDeletePanel(ui, X, NAME);
    await ui.seesText(`account delete "${NAME}" --dry-run`, '第一行是命令 account delete "…" --dry-run');
    const range = await ui.page.textContent('[data-adel-range]').catch(() => '');
    await ui.assert(range.includes(`${ymd(state.p1)} 至 ${ymd(state.cur)} 共 3 个账期`) && /已关账 2 期/.test(range.replace(/\s+/g, ' ')),
      '改写的账期:8 月至本期共 3 期(已关账 2 期)', range);
    const moved = await ui.page.textContent('[data-adel-moved]').catch(() => '');
    await ui.assert(/余额 3 期/.test(moved) && /收入 1 笔 ¥500\.00/.test(moved), '移走:余额 3 期 · 收入 1 笔 ¥500.00', moved);
    const tr = await ui.page.evaluate(() => [...document.querySelectorAll('[data-adel-transfer]')]
      .map(e => e.getAttribute('data-mode') + '|' + e.textContent.replace(/\s+/g, ' ').trim()));
    await ui.assert(tr.length === 3 && tr[0].startsWith('PA_IN|') && tr[0].includes(wageName) && tr[0].includes('收到它转来 ¥111.00')
        && tr[1].startsWith('PA_OUT|') && tr[1].includes(yebName) && tr[1].includes('转给它 ¥112.00') && tr[1].includes('本金移出')
        && tr[2].startsWith('OPENING|') && tr[2].includes(FRESH_NAME) && tr[2].includes('开账基线'),
      '牵连 · 逐笔:工资卡收到 → 补录本金 · 余额宝转出 → 本金移出 · 转去开户 → 并进开账基线', tr.join(' ‖ '));
    const phrase = await ui.page.textContent('[data-adel-phrase]').catch(() => '');
    await ui.assert(phrase.trim() === state.phrase, '确认语逐笔明细,与库里的划转一一对应', phrase);
    await ui.seesText('「删除(先备份)」先做一次立即备份', '面板写明两个删除按钮的区别(先备份 / 不备份)');
    await ui.page.screenshot({ path: '/tmp/e2e-49-panel-pc.png', fullPage: true }).catch(() => {});

    // ── 2 · 确认语 ────────────────────────────────────────────────────────
    report.section('2 · 没对上 → 按钮暗;「复制这段话」→ 粘进框 → 按钮亮');
    await ui.fill('[data-adel-input]', state.phrase.split('\n').slice(0, 2).join('\n'), '只输入前两行');
    await ui.assert(await ui.page.isDisabled('[data-adel-submit]') && await ui.page.isDisabled('[data-adel-submit-nobackup]'),
      '没对上:「删除(先备份)」「不备份,直接删除」都是暗的');
    await ui.seesText('还差 2 行没对上', '提示「还差 2 行没对上」');
    await ui.click('[data-adel-copy]', '点「复制这段话」');
    await ui.page.fill('[data-adel-input]', '');
    let pasted = false;
    try {
      await ui.page.focus('[data-adel-input]');
      await ui.page.keyboard.press('Control+V');
      await ui.page.waitForTimeout(300);
      pasted = (await ui.page.inputValue('[data-adel-input]')).trim() === state.phrase;
    } catch (e) { /* 剪贴板权限不在就退回手输 */ }
    if (!pasted) await ui.fill('[data-adel-input]', state.phrase + '   \n', '(剪贴板读不到)照着输一遍,行尾多打了空格和回车');
    else report.info('从剪贴板粘贴成功');
    await ui.page.locator('[data-adel-input]').dispatchEvent('input');
    await ui.assert(!(await ui.page.isDisabled('[data-adel-submit]')) && !(await ui.page.isDisabled('[data-adel-submit-nobackup]')),
      '对上之后两个删除按钮都亮了(行尾空格 / 回车不算差别)');
    await ui.seesText('✓ 一字不差', '提示「✓ 一字不差」');

    // ── 3 · 删除(先备份)──────────────────────────────────────────────────
    report.section('3 · 删除(先备份):备份成功才删;备份目录写不了 → 不删、指路「不备份,直接删除」');
    const writable = await ui.page.locator('[data-backup-dir-warn]').count() === 0;
    state.backedUp = writable;
    await ui.submit('[data-adel-submit]', '点「删除(先备份)」');
    if (!writable) {
      await ui.seesText('没有删除任何东西', '备份目录写不了:面板写「删除前的备份失败 … 没有删除任何东西」');
      await ui.seesText('不备份,直接删除', '报错里指路「不备份,直接删除」');
      await ui.assert(db.num(`SELECT COUNT(*) FROM account WHERE id=${X}`) === 1, '真值层:账户还在');
      await ui.assert(db.num(`SELECT COUNT(*) FROM account_trash WHERE id > ${state.max_account_trash}`) === 0, '真值层:回收站没多东西');
      await ui.assert(JSON.stringify(counts(X)) === JSON.stringify(state.before), '真值层:所有表一行不少');
      report.info('beta 的备份目录写不了(sudo chown finance:finance /var/backup/finance 之后走「先备份」那一支)· 这一轮改用「不备份,直接删除」继续');
      await ui.fill('[data-adel-input]', state.phrase, '重新输入确认语');
      await ui.page.locator('[data-adel-input]').dispatchEvent('input');
      await ui.submit('[data-adel-submit-nobackup]', '点「不备份,直接删除」');
      await ui.seesText('这次没有备份', '回到账户簿,提示里写「这次没有备份」');
    } else {
      await ui.seesText('删除前的备份在', '回到账户簿,提示里写备份文件在哪');
      const bk = db.one(`SELECT CONCAT_WS('|', kind, status) FROM backup_log WHERE id > ${state.max_backup_log} ORDER BY id DESC LIMIT 1`);
      await ui.assert(bk === 'MANUAL|SUCCESS', '真值层:删之前有一份成功的手动备份', bk);
    }
    await ui.seesText(`已删除「${NAME}」`, '回到账户簿,顶部「已删除「…」· 可在回收站恢复」');
    const bkFile = db.one(`SELECT COALESCE(backup_file, 'NULL') FROM account_trash WHERE account_name='${NAME}' ORDER BY id DESC LIMIT 1`);
    await ui.assert(writable ? bkFile !== 'NULL' : bkFile === 'NULL', '真值层:回收站这一行记着用的哪份备份(没备份就是空)', bkFile);

    // ── 4 · 删完 ─────────────────────────────────────────────────────────
    report.section('4 · 删完:挂账户的表里都没有它、回收站有;对方本金进出方向对、损益逐分不变;报表留痕');
    const after = counts(X);
    await ui.assert(Object.values(after).every(v => v === 0), '真值层:挂账户的表里一行都没有它了', JSON.stringify(after));
    const trash = db.one(`SELECT CONCAT_WS('|', id, account_id, transfer_count, counterpart_count, payload IS NOT NULL) FROM account_trash WHERE account_name='${NAME}' ORDER BY id DESC LIMIT 1`);
    await ui.assert(trash && trash.split('|')[1] === String(X) && trash.split('|')[2] === '3' && trash.split('|')[4] === '1',
      '真值层:回收站一行 · 3 笔划转 · 原行都存下了', trash);
    state.trashId = Number(trash.split('|')[0]);
    const pas = db.col(`SELECT CONCAT_WS('|', account_id, period_id, amount, source_tag) FROM principal_adjustment WHERE id > ${state.max_principal_adjustment} ORDER BY id`);
    await ui.assert(pas.length === 2 && pas[0] === `${WAGE}|${state.p1}|111.00|ACCOUNT_DELETE` && pas[1] === `${YEB}|${state.p2}|-112.00|ACCOUNT_DELETE`,
      '真值层:工资卡 8 月补录本金 +111 · 余额宝 9 月本金移出 −112 · 新账户(第一期)不写', pas.join(' ; '));
    await ui.assert(pnl(WAGE, state.p1) === state.pnlWage && pnl(YEB, state.p2) === state.pnlYeb,
      '真值层:工资卡 8 月、余额宝 9 月的本期损益与删前逐分相同', `${state.pnlWage}→${pnl(WAGE, state.p1)} · ${state.pnlYeb}→${pnl(YEB, state.p2)}`);
    await ui.click('header a[href="/reports"] >> nth=0', '顶栏点「报表」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const aug = db.one(`SELECT period_start FROM period WHERE id=${state.p1}`);
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}), ui.page.selectOption('select[data-base]', aug)]);
    await ui.page.waitForTimeout(800);
    const trace = await ui.page.evaluate(() => [...document.querySelectorAll('[data-trash-trace]')].map(e => e.textContent.trim()));
    await ui.assert(trace.some(t => t.includes(`删除账户「${NAME}」后重算`)), '报表 8 月封板抬头:「· 日期 删除账户「…」后重算」', trace.join(' / '));

    // ── 5 · 回收站恢复 ───────────────────────────────────────────────────
    report.section('5 · 管理 → 回收站 → 恢复:逐表行数与删前相同、改记拿掉、报表写「恢复后重算」');
    await ui.click('header a[href="/admin"] >> nth=0', '顶栏点「管理」');
    await ui.click('main a.group[href="/admin/trash"]', '总览「系统」组点「回收站」');
    await ui.rendered('回收站');
    const r = `tr[data-trash-row="${state.trashId}"]`;
    await ui.click(`${r} [data-trash-restore-box] > summary`, '这一行点「恢复」');
    await ui.seesText('笔划转从本金进出还原成划转', '轻量确认:写清会放回什么');
    await ui.submit(`${r} [data-trash-restore] button`, '点「恢复「…」」');
    await ui.seesText(`已恢复「${NAME}」`, '提示「已恢复」');
    const restored = counts(X);
    await ui.assert(JSON.stringify(restored) === JSON.stringify(state.before), '真值层:逐表行数与删前相同', `${JSON.stringify(state.before)} vs ${JSON.stringify(restored)}`);
    await ui.assert(db.num(`SELECT COUNT(*) FROM principal_adjustment WHERE id > ${state.max_principal_adjustment}`) === 0, '真值层:改记写的本金进出拿掉了');
    await ui.assert(pnl(WAGE, state.p1) === state.pnlWage && pnl(YEB, state.p2) === state.pnlYeb, '真值层:对方损益仍与删前相同');
    await ui.goto(`/reports?asof=${aug}`);
    const trace2 = await ui.page.evaluate(() => [...document.querySelectorAll('[data-trash-trace]')].map(e => e.textContent.trim()));
    await ui.assert(trace2.some(t => t.includes(`恢复账户「${NAME}」后重算`)), '报表 8 月:「· 日期 恢复账户「…」后重算」', trace2.join(' / '));

    // ── 6 · 编辑页删除 + 彻底清除 ─────────────────────────────────────────
    report.section('6 · 编辑页「危险操作」→ 删除 → 回收站「彻底清除」(要打字)');
    await ui.click('header a[href="/accounts"] >> nth=0', '顶栏点「账户」');
    await ui.click(`main a[href="/accounts/${X}"] >> nth=0`, `账户簿点「${NAME}」`);
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.click(`a[href="/accounts/${X}/edit"] >> nth=0`, '详情页点「编辑」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.visible('[data-danger-zone]', '编辑页底部有「危险操作」');
    // v1.34 · 维护者验收指出「按钮丑、没对齐」—— 并列的两行:按钮同宽同高、说明从同一列起、按钮右缘对齐
    await ui.sameSize('[data-danger-zone] [data-dz-btn]', '危险操作:「归档」「删除」两个按钮同宽同高');
    const dz = await ui.page.evaluate(() => ({
      textX: [...document.querySelectorAll('[data-danger-zone] .danger-zone-text')].map(e => Math.round(e.getBoundingClientRect().left)),
      btnR: [...document.querySelectorAll('[data-danger-zone] [data-dz-btn]')].map(e => Math.round(e.getBoundingClientRect().right)),
    }));
    await ui.assert(new Set(dz.textX).size === 1 && new Set(dz.btnR).size === 1, '危险操作:说明文字从同一列起、按钮右缘对齐', JSON.stringify(dz));
    await ui.click('[data-danger-zone] [data-delete-account]', '点「删除账户」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const ph2 = (await ui.page.textContent('[data-adel-phrase]')).trim();
    await ui.fill('[data-adel-input]', ph2, '照确认语输入');
    await ui.page.locator('[data-adel-input]').dispatchEvent('input');
    const bkBefore = db.num(`SELECT COALESCE(MAX(id), 0) FROM backup_log`);
    await ui.submit('[data-adel-submit-nobackup]', '这次点「不备份,直接删除」');
    await ui.seesText(`已删除「${NAME}」`, '删除成功');
    await ui.seesText('这次没有备份', '提示里写「这次没有备份」');
    state.trashId2 = db.num(`SELECT MAX(id) FROM account_trash WHERE account_name='${NAME}'`);
    await ui.assert(db.num(`SELECT COUNT(*) FROM backup_log WHERE id > ${bkBefore}`) === 0, '真值层:没有做备份(backup_log 没多行)');
    await ui.assert(db.one(`SELECT COALESCE(backup_file, 'NULL') FROM account_trash WHERE id=${state.trashId2}`) === 'NULL', '真值层:回收站这一行没有备份文件');
    const aud = db.one(`SELECT summary FROM audit_log WHERE type='ACCOUNT_DELETE' ORDER BY id DESC LIMIT 1`);
    await ui.assert(/未备份/.test(aud || ''), '真值层:审计日志写明「未备份」', aud);
    await ui.goto('/admin/trash');
    const r2 = `tr[data-trash-row="${state.trashId2}"]`;
    await ui.click(`${r2} [data-trash-purge-box] > summary`, '点「彻底清除」');
    await ui.fill(`${r2} [data-trash-purge-input]`, '彻底清除', '只输一半');
    await ui.submit(`${r2} [data-trash-purge] button`, '点「彻底清除」');
    await ui.seesText('没有清除', '没对上:不清除、写明要输什么');
    await ui.click(`${r2} [data-trash-purge-box] > summary`, '再点开');
    await ui.fill(`${r2} [data-trash-purge-input]`, `彻底清除「${NAME}」`, '原样输入');
    await ui.submit(`${r2} [data-trash-purge] button`, '点「彻底清除」');
    await ui.seesText(`已彻底清除「${NAME}」`, '提示「已彻底清除」');
    await ui.assert(db.one(`SELECT CONCAT_WS('|', payload IS NULL, purged_at IS NOT NULL) FROM account_trash WHERE id=${state.trashId2}`) === '1|1',
      '真值层:payload 清空、记下清除时间(元数据留着给报表留痕)');

    // ── 7 · 手机 ─────────────────────────────────────────────────────────
    report.section('7 · 手机(390 · 触屏):删除面板全屏、不横向溢出、复制按钮整行');
    await ui.phone(async (m) => {
      await m.goto('/accounts');
      await m.click(`a[data-delete-account][href="/accounts/${N}/delete"]:visible`, `手机账户簿「${FRESH_NAME}」那一行点「删除」`);
      await m.page.waitForLoadState('networkidle').catch(() => {});
      const over = await m.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      await m.assert(over <= 1, '删除面板没有横向溢出', `${over}px`);
      const copy = await m.page.locator('[data-adel-copy]').evaluate(e => e.getBoundingClientRect().width).catch(() => 0);
      await m.assert(copy >= 300, '「复制这段话」整行(≥300px)', `${copy}px`);
      await m.page.screenshot({ path: '/tmp/e2e-49-panel-mobile.png', fullPage: true }).catch(() => {});
    });
    await ui.noConsoleErrors('这几个页面控制台无报错');
  },

  async cleanup(ui, report) {
    const ids = [state.x, state.n].filter(Boolean);
    const sqls = [];
    for (const a of ids) {
      for (const [t, w] of Object.entries(SPEC(a))) if (t !== 'account') sqls.push(`DELETE FROM ${t} WHERE ${w}`);
    }
    sqls.push(`DELETE FROM principal_adjustment WHERE id > ${state.max_principal_adjustment || 0}`);
    sqls.push(`DELETE FROM account_trash WHERE id > ${state.max_account_trash || 0}`);
    sqls.push(`DELETE FROM backup_log WHERE id > ${state.max_backup_log || 0} AND kind='MANUAL'`);
    for (const a of ids) sqls.push(`DELETE FROM account WHERE id=${a}`);
    for (const sql of sqls) { try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 70)} · ${String(e.message).slice(0, 80)}`); } }
    await ui.assert(db.num(`SELECT COUNT(*) FROM account WHERE display_name IN ('${NAME}','${FRESH_NAME}')`) === 0, '还原:测试账户都清掉了');
  },
};
