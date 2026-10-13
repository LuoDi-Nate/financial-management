/**
 * flow · v1.33 · 删除账户 · 真实用例(维护者 2026-10-13:「自己构建一个真实的 case,认真执行」)
 *
 * flow 49 的历史是 SQL 造的;这条全程从页面走,和一个真用户补历史账、再删账户的路径一模一样:
 *
 *   S0  记一遍:仪表盘(2026-08 / 09 / 10 三期)· 报表(08 / 09 两个已关账期)· 账户簿 · 两个对方账户的详情
 *   1   账户页开向导建「验证·删除用储蓄卡」(N)
 *   2   管理 → 周期 → 重新打开 08 → 填报页切到 08:工商银行-备用金(A)转 1,000 给 N · 收入里给 N 记一笔工资 200
 *       → N 的 08 余额 1,200 → 管理 → 周期 →「关闭账期」
 *   3   重新打开 09 → N 转 300 给支付宝-余额宝(B)· N 记一笔日常开支 100 → N 的 09 余额 800 → 关闭账期
 *   4   开向导建「验证·新开户」(M)→ 本期(10)N 转 500 给 M → N 300 · M 500(M 的第一期)
 *   S1  再记一遍(同上 + M 的详情)· 断言补历史的联动:N 的余额 / 收支进了 08、09
 *   5   账户簿 ⋯ → 删除 N:面板写的改写区间、三笔划转的改记方式与库对得上;「删前」与仪表盘 / 报表是同一个数 → 先备份再删
 *   S2  再记一遍,逐项断言删除的口径(PRD §6):
 *         · 净资产:每一期 = 删前 − N 那一期的余额(08 −1,200 · 09 −800 · 10 −300)
 *         · 钱赚(投资损益):09 / 10 不变 —— N 是现金,没有赚亏;对方的划转改记成本金进出,收益一分不变
 *           08 是 N 的第一期:删后钱赚、人赚回到建 N 之前(S0),开账基线 = S0 − 1,000(A 的「本金移出」)
 *           (S1 的 08 钱赚比 S0 少 200 是已知口径:新账户首期记的收入既进人赚又进开账基线 —— 补历史时就有,与删除无关)
 *         · 人赚:08 少了 N 的工资 200;09 少了 N 的开支 100(人赚 +100);10 不变
 *         · 开账基线:09 +300(B 的「补录本金」)· 10 +500(M 那 500 变成开户余额)
 *         · ΔNW = 人赚 + 钱赚 + 开账基线 每一期都闭合
 *         · 报表 08 / 09:期末净资产、期初、收入、支出、投资损益、已填 x/y 按上面变;抬头留痕「删除账户「N」后重算」
 *         · A / B / M 每个账户自己的本期损益删前删后逐分相同;A 详情出现「本金移出」、B 出现「补录本金」
 *   6   管理 → 回收站 → 恢复
 *   S3  再记一遍:每个数都回到 S1(报表抬头多一条「恢复账户…后重算」)
 *
 * 每个状态的页面文字与截图存到 E2E50_OUT(默认 /tmp/e2e50/S0 … S3),summary.md 是四个状态的数字对照。
 * 这条会重开 / 关闭历史账期、动对方账户的历史余额 —— cleanup 用开跑前的整库快照还原并重启应用,然后重新登录。
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const { BASE, USER, PASS } = require('../lib/browser.cjs');

const A = 2;                                // 工商银行-备用金 · CASH · CNY
const B = 6;                                // 支付宝-余额宝 · CASH · CNY
const N_NAME = '验证·删除用储蓄卡';
const M_NAME = '验证·新开户';
const OUT = process.env.E2E50_OUT || '/tmp/e2e50';
const DUMP = '/tmp/e2e50-pre.sql';
const MYSQL_ARGS = ['-ufinance', '-pfinance', 'finance'];
const AI_STUB = /\/(review\/insight|checkup\/(diagnose|insight))/;   // 这条不验 AI 文案,别花 token
const state = { snap: {} };

const money = (s) => {
  if (s == null) return null;
  const t = String(s).replace(/[−–]/g, '-').replace(/[^\d.\-]/g, '');
  return t === '' || t === '-' ? null : Number(t);
};
const close1 = (a, b) => a != null && b != null && Math.abs(a - b) <= 1;   // 页面金额取整到元

/** 页面上所有看得见的字,一个文本节点一行(和人眼读到的顺序一致) */
async function pageText(ui) {
  return ui.page.evaluate(() => {
    const out = [];
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => (n.parentElement && /^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)$/.test(n.parentElement.tagName))
        ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT,
    });
    let n;
    while ((n = w.nextNode())) { const t = n.textContent.replace(/\s+/g, ' ').trim(); if (t) out.push(t); }
    return out.join('\n');
  });
}

function parseDash(t) {
  const nw = t.match(/总资产 ¥([\d,]+) − 总负债 ¥([\d,]+) = ¥(-?[\d,]+)/);
  const d = t.match(/本期净资产变化 ΔNW\n(\S+)\n/);
  const out = { netWorth: nw ? money(nw[3]) : null, assets: nw ? money(nw[1]) : null, dNW: d ? money(d[1]) : null,
                ren: null, qian: null, opening: null, income: null, expense: null };
  const blk = t.match(/\n人赚\n(\S+)\n钱赚\n(\S+)\n(?:开账基线\n(\S+)\n)?= ΔNW\n/);
  if (blk) {
    Object.assign(out, { ren: money(blk[1]), qian: money(blk[2]), opening: blk[3] ? money(blk[3]) : 0 });
    const ie = t.match(/本期收入\n¥([\d,]+)\n− 支出\n¥([\d,]+)/);
    if (ie) Object.assign(out, { income: money(ie[1]), expense: money(ie[2]) });
  } else {
    // 本期没填收支时的版式:「投资侧不受影响:钱赚(投资损益)」+「开账基线(…)」,人赚 = 0
    const q = t.match(/钱赚[((]投资损益[))]\n(\S+)\n/);
    const o = t.match(/\n开账基线[((]本来就有[^\n]*\n(\S+)\n/);
    if (q) Object.assign(out, { ren: 0, qian: money(q[1]), opening: o ? money(o[1]) : 0, income: 0, expense: 0 });
  }
  return out;
}

function parseReport(t) {
  const end = t.match(/期末净资产\n(¥[^\n]+)\n资产 − 负债/);
  const wf = t.match(/\n(¥[\d,]+)\n期初净资产\n\d{4}-\d{2}\n([^\n]+)\n\+ 收入\n([^\n]+)\n− 支出\n([^\n]+)\n± 投资损益\n(¥[\d,]+)\n期末净资产/);
  const filled = t.match(/(\d+) \/ (\d+) 已填/);
  const attr = (() => {
    const i = t.indexOf('本期归因 · 谁把净资产推上去 / 拉下来');
    const j = t.indexOf('结构与风险', i);
    return i < 0 ? [] : t.slice(i, j < 0 ? i + 800 : j).split('\n').slice(3, -1);
  })();
  return {
    endNW: end ? money(end[1]) : null,
    startNW: wf ? money(wf[1]) : null, income: wf ? money(wf[2]) : null, expense: wf ? money(wf[3]) : null,
    pnl: wf ? money(wf[4]) : null,
    filled: filled ? `${filled[1]}/${filled[2]}` : null,
    traces: t.split('\n').filter(l => /后重算/.test(l)),
    attribution: attr,
  };
}

async function save(tag, name, ui, text) {
  const dir = path.join(OUT, tag);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `${name}.txt`), text);
  await ui.page.screenshot({ path: path.join(dir, `${name}.jpg`), fullPage: true, type: 'jpeg', quality: 60 }).catch(() => {});
}

/** 顶栏 → 仪表盘 → 账期下拉切到这一期 → 等归因复盘加载完 */
async function readDashboard(ui, tag, periodStart, label) {
  await ui.click('header a[href="/dashboard"] >> nth=0', `顶栏点「仪表盘」(${label})`);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                     ui.page.selectOption('select[onchange*="asof"]', periodStart)]);
  // 归因复盘是 hx-trigger="revealed":滚到它那里才加载 —— 和人往下翻一样
  const lazy = ui.page.locator('text=归因复盘加载中').first();
  if (await lazy.count() > 0) {
    await lazy.scrollIntoViewIfNeeded().catch(() => {});
    await ui.page.waitForFunction(() => !document.body.innerText.includes('归因复盘加载中'), null, { timeout: 30000 }).catch(() => {});
  }
  await ui.page.waitForTimeout(600);
  const t = await pageText(ui);
  await save(tag, `dashboard_${label.replace(/\s+/g, '')}`, ui, t);
  return parseDash(t);
}

/** 顶栏 → 报表 → 封板账期下拉切到这一期 */
async function readReport(ui, tag, periodStart, label) {
  await ui.click('header a[href="/reports"] >> nth=0', `顶栏点「报表」(${label})`);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                     ui.page.selectOption('select[data-base]', periodStart)]);
  await ui.page.waitForTimeout(800);
  const t = await pageText(ui);
  await save(tag, `reports_${label.replace(/\s+/g, '')}`, ui, t);
  return parseReport(t);
}

/** 顶栏 → 账户 →(点这个账户 → 详情页)全文 */
async function readAccount(ui, tag, id, label) {
  await ui.click('header a[href="/accounts"] >> nth=0', `顶栏点「账户」(${label})`);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  if (!id) { const t = await pageText(ui); await save(tag, 'accounts', ui, t); return t; }
  await ui.click(`main a[href="/accounts/${id}"] >> nth=0`, `账户簿点「${label}」`);
  await ui.page.waitForLoadState('networkidle').catch(() => {});
  const t = await pageText(ui);
  await save(tag, `account_${id}`, ui, t);
  return t;
}

/** 某账户某一期的本期损益(事实层同一个式子):余额变化 − 收入 + 支出 − 净划转 − 补录本金 */
function pnl(acc, pid) {
  const start = db.one(`SELECT period_start FROM period WHERE id=${pid}`);
  const end = Number(db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${pid} AND account_id=${acc}`) || 0);
  const prevRaw = db.one(`SELECT ps.end_balance FROM period_snapshot ps JOIN period p ON p.id=ps.period_id
                               WHERE ps.account_id=${acc} AND p.period_start < '${start}' ORDER BY p.period_start DESC LIMIT 1`);
  if (prevRaw == null || prevRaw === '') return 0;   // 账户第一期:整笔是开账基线,本期损益恒为 0(FactProjector)
  const prev = Number(prevRaw);
  const inc = Number(db.one(`SELECT COALESCE(SUM(amount),0) FROM cash_flow WHERE account_id=${acc} AND period_id=${pid} AND kind='INCOME' AND deleted_at IS NULL`));
  const exp = Number(db.one(`SELECT COALESCE(SUM(amount),0) FROM cash_flow WHERE account_id=${acc} AND period_id=${pid} AND kind='EXPENSE' AND deleted_at IS NULL AND affects_balance=1`));
  const tin = Number(db.one(`SELECT COALESCE(SUM(COALESCE(to_amount,amount)),0) FROM transfer WHERE to_account_id=${acc} AND period_id=${pid} AND deleted_at IS NULL AND is_draft=0`));
  const tout = Number(db.one(`SELECT COALESCE(SUM(amount),0) FROM transfer WHERE from_account_id=${acc} AND period_id=${pid} AND deleted_at IS NULL AND is_draft=0`));
  const pa = Number(db.one(`SELECT COALESCE(SUM(amount),0) FROM principal_adjustment WHERE account_id=${acc} AND period_id=${pid} AND deleted_at IS NULL`));
  return Math.round((end - prev - inc + exp - (tin - tout) - pa) * 100) / 100;
}
const bal = (acc, pid) => db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${pid} AND account_id=${acc}`);

/** 记一遍某个状态:仪表盘三期 + 报表两期 + 账户簿 + 对方账户详情 + 各账户本期损益(库) */
async function capture(ui, report, tag) {
  state.step = `记录 ${tag}`;
  report.section(`${tag} · 记录:仪表盘 08 / 09 / 10 · 报表 08 / 09 · 账户簿 · 对方账户详情`);
  const s = { dash: {}, rep: {}, acct: {}, pnl: {} };
  for (const [k, pid] of [['08', state.p8], ['09', state.p9], ['10', state.p10]]) {
    s.dash[k] = await readDashboard(ui, tag, state.start[pid], `仪表盘 ${k}`);
    const d = s.dash[k];
    await ui.assert(d.netWorth != null && d.dNW != null && d.qian != null,
      `${tag} 仪表盘 ${k}:读到净资产 / ΔNW / 人赚 / 钱赚 / 开账基线`, JSON.stringify(d));
    if (d.dNW != null) {
      const sum = d.ren + d.qian + d.opening;
      await ui.assert(Math.abs(sum - d.dNW) <= 2, `${tag} 仪表盘 ${k}:ΔNW = 人赚 + 钱赚 + 开账基线(闭合)`,
        `${d.ren} + ${d.qian} + ${d.opening} = ${sum} vs ${d.dNW}`);
    }
  }
  for (const [k, pid] of [['08', state.p8], ['09', state.p9]]) {
    s.rep[k] = await readReport(ui, tag, state.start[pid], `报表 ${k}`);
    await ui.assert(s.rep[k].endNW != null && s.rep[k].pnl != null, `${tag} 报表 ${k}:读到期末净资产 / 收入 / 支出 / 投资损益`, JSON.stringify(s.rep[k]));
  }
  s.acct.list = await readAccount(ui, tag, 0, '账户簿');
  s.acct[A] = await readAccount(ui, tag, A, '工商银行-备用金');
  s.acct[B] = await readAccount(ui, tag, B, '支付宝-余额宝');
  if (state.m) s.acct[state.m] = await readAccount(ui, tag, state.m, M_NAME);
  s.pnl = { A8: pnl(A, state.p8), A9: pnl(A, state.p9), A10: pnl(A, state.p10), B9: pnl(B, state.p9), B10: pnl(B, state.p10),
            M10: state.m ? pnl(state.m, state.p10) : null };
  state.snap[tag] = s;
  return s;
}

/** 管理 → 周期 → 翻到这一期 → 填理由「重新打开」/「关闭账期」 */
async function periodAction(ui, pid, action, label) {
  state.step = `${action} ${label}`;
  await ui.click('header a[href="/admin"] >> nth=0', '顶栏点「管理」');
  const sel = action === 'reopen' ? `form[action*="/${pid}/reopen"]` : `form[action*="/${pid}/force-close"]`;
  const pg = await ui.paginateUntil('/admin/periods?page={p}', sel);
  await ui.assert(pg >= 0, `周期管理里翻到 ${label} 这一期`, `page=${pg}`);
  if (action === 'reopen') {
    await ui.fill(`${sel} input[name=reason]`, 'e2e50 真实用例:补这张卡的历史', '填「必填理由」');
    await ui.submit(`${sel} button`, `点「重新打开」${label}`);
    await ui.assert(db.one(`SELECT status FROM period WHERE id=${pid}`) === 'OPEN', `真值层:${label} 变成 OPEN`);
  } else {
    await ui.submit(`${sel} button`, `点「关闭账期」${label}`);
    await ui.assert(db.one(`SELECT status FROM period WHERE id=${pid}`) === 'CLOSED', `真值层:${label} 关回 CLOSED`);
  }
}

/** 顶栏 → 填报 → 账期下拉选这一期 */
async function entryFor(ui, pid, label) {
  state.step = `填报页切到 ${label}`;
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

/** 在 from 那一行的「账户间划转」里搜对方、填金额、点划转 */
async function transfer(ui, from, toName, search, amt) {
  state.step = `划转 ${from} → ${toName} ${amt}`;
  await openRow(ui, from);
  const form = `#entry-block-${from} form[hx-post$="/entry/${from}/transfer"]`;
  await ui.click(`${form} .ss-input`, '点开「转给哪个账户」');
  await ui.page.keyboard.type(search, { delay: 30 });
  await ui.page.waitForTimeout(300);
  await ui.click(`${form} .ss-item:has-text("${toName}")`, `候选里点「${toName}」`);
  await ui.fill(`${form} input[name="amount"]`, amt, `金额 ${amt}`);
  await Promise.all([
    ui.page.waitForResponse(r => r.url().includes(`/entry/${from}/transfer`), { timeout: 20000 }).catch(() => null),
    ui.click(`${form} button:has-text("划转")`, '点「↔ 划转」'),
  ]);
  await ui.page.waitForTimeout(1200);
}

async function createAccount(ui, name) {
  await ui.click('header a[href="/accounts"] >> nth=0', '顶栏点「账户」');
  await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                     ui.click('main a:has-text("添加账户(开向导)")', '点「+ 添加账户(开向导)」')]);
  await ui.click('#account-wizard .tpl-card:has-text("招商银行")', '模板选「招商银行」');
  await ui.fill('#account-wizard input[name="displayName"]', name, `起名「${name}」`);
  await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                     ui.click('#account-wizard button:has-text("+ 添加账户")', '点「+ 添加账户」')]);
  const id = db.num(`SELECT COALESCE(MAX(id),0) FROM account WHERE family_id=${fx.FAM} AND display_name='${name}'`);
  await ui.assert(id > 0, `真值层:多了账户「${name}」`, `id=${id}`);
  return id;
}

async function relogin(ui) {
  await ui.page.goto(BASE + '/login', { waitUntil: 'networkidle', timeout: 60000 });
  if (await ui.page.locator('input[name=username]').count() === 0) return;   // 还记着登录(记住我)
  await ui.page.fill('input[name=username]', USER);
  await ui.page.fill('input[name=password]', PASS);
  await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 60000 }).catch(() => {}),
                     ui.page.click('button[type=submit]')]);
}

module.exports = {
  name: '50-account-delete-real-case',
  title: 'v1.33 · 删除账户真实用例:页面上补两期历史 + 划转 + 关账 → 删除 → 仪表盘 / 报表逐项核对 → 恢复',

  async run(ui, report) {
    ui.flow = this.name;
    ui.page.on('dialog', d => d.accept().catch(() => {}));
    state.consoleErrors = [];
    state.step = '开跑';
    ui.page.on('console', m => { if (m.type() === 'error') state.consoleErrors.push(`[${state.step}] ${ui.page.url().replace(BASE, '')} · ${m.text().slice(0, 160)}`); });
    ui.page.on('pageerror', e => state.consoleErrors.push(`[${state.step}] ${ui.page.url().replace(BASE, '')} · ${String(e).slice(0, 160)}`));
    await ui.page.route(AI_STUB, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
    fs.rmSync(OUT, { recursive: true, force: true });
    const fd = fs.openSync(DUMP, 'w');
    execFileSync('mysqldump', ['--single-transaction', '--skip-lock-tables', ...MYSQL_ARGS], { stdio: ['ignore', fd, 'ignore'] });
    fs.closeSync(fd);
    state.dumped = true;
    report.info(`整库快照 → ${DUMP}(cleanup 用它还原)`);

    state.p10 = Number(fx.currentPeriod());
    const ended = db.col(`SELECT id FROM period WHERE family_id=${fx.FAM} AND period_end < CURDATE() AND status='CLOSED'
                            ORDER BY period_start DESC LIMIT 2`).map(Number);
    [state.p9, state.p8] = ended;
    state.start = {};
    for (const p of [state.p8, state.p9, state.p10]) state.start[p] = db.one(`SELECT period_start FROM period WHERE id=${p}`);
    state.maxBackup = db.num(`SELECT COALESCE(MAX(id),0) FROM backup_log`);
    report.info(`账期:08 #${state.p8} · 09 #${state.p9} · 10 #${state.p10}(本期)`);

    await ui.goto('/dashboard');
    const s0 = await capture(ui, report, 'S0');

    // ── 1 · 建 N ─────────────────────────────────────────────────────────
    report.section(`1 · 账户页开向导建「${N_NAME}」`);
    state.n = await createAccount(ui, N_NAME);
    const N = state.n;
    if (!N) return;
    const a8before = Number(bal(A, state.p8));

    // ── 2 · 08:重开 → A 转 1,000 给 N · 收入里给 N 记工资 200 → 关账 ──────────
    report.section('2 · 管理 → 周期 → 重开 08 → 填报 08:A → N 1,000 · N 收入 200(工资)→ 关闭账期');
    await periodAction(ui, state.p8, 'reopen', '2026-08');
    await entryFor(ui, state.p8, '2026-08');
    await transfer(ui, A, N_NAME, '删除用', 1000);
    state.step = '08 收入 200';
    await ui.fill('form[action$="/entry/income"] input[name="amount"]', 200, '收入金额 200');
    await ui.choose('categoryCode', 'salary', 'form[action$="/entry/income"]');
    await ui.choose('accountId', N, 'form[action$="/entry/income"]');
    await ui.submit('form[action$="/entry/income"] button[type=submit]', '点「+ 加一笔」');
    await ui.assert(Number(bal(N, state.p8)) === 1200, '真值层:N 的 08 余额 = 1,000(转入)+ 200(工资)= 1,200', bal(N, state.p8));
    await ui.assert(Number(bal(A, state.p8)) === a8before - 1000, '真值层:A 的 08 余额跟着少了 1,000(划转联动)', `${a8before} → ${bal(A, state.p8)}`);
    await ui.assert(db.num(`SELECT COUNT(*) FROM cash_flow WHERE account_id=${N} AND period_id=${state.p8} AND kind='INCOME' AND amount=200 AND deleted_at IS NULL`) === 1,
      '真值层:N 在 08 有一笔工资收入 200');
    await periodAction(ui, state.p8, 'close', '2026-08');

    // ── 3 · 09:重开 → N 转 300 给 B · N 开支 100 → 关账 ─────────────────────
    report.section('3 · 重开 09 → 填报 09:N → B 300 · N 日常开支 100 → 关闭账期');
    const b9before = Number(bal(B, state.p9));
    await periodAction(ui, state.p9, 'reopen', '2026-09');
    await entryFor(ui, state.p9, '2026-09');
    await transfer(ui, N, '支付宝-余额宝', '余额宝', 300);
    await openRow(ui, N);
    state.step = '09 N 快捷支出 100';
    const exp = `#entry-block-${N} form[hx-post$="/entry/${N}/cash-flow"]`;
    await ui.fill(`${exp} input[name="amount"]`, 100, '快捷支出 100');
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes(`/entry/${N}/cash-flow`), { timeout: 20000 }).catch(() => null),
      ui.click(`${exp} button`, '点「− 支出」'),
    ]);
    await ui.page.waitForTimeout(1200);
    await ui.assert(Number(bal(N, state.p9)) === 800, '真值层:N 的 09 余额 = 1,200 − 300 − 100 = 800', bal(N, state.p9));
    await ui.assert(Number(bal(B, state.p9)) === b9before + 300, '真值层:B 的 09 余额跟着多了 300', `${b9before} → ${bal(B, state.p9)}`);
    await periodAction(ui, state.p9, 'close', '2026-09');

    // ── 4 · 10:建 M → N 转 500 给 M ───────────────────────────────────────
    report.section(`4 · 建「${M_NAME}」→ 本期 N → M 500`);
    state.m = await createAccount(ui, M_NAME);
    await entryFor(ui, state.p10, '2026-10(本期)');
    await transfer(ui, N, M_NAME, '新开户', 500);
    await ui.assert(Number(bal(N, state.p10)) === 300 && Number(bal(state.m, state.p10)) === 500,
      '真值层:本期 N 300 · M 500(M 的第一期)', `N=${bal(N, state.p10)} M=${bal(state.m, state.p10)}`);
    await ui.assert(db.num(`SELECT COUNT(*) FROM period WHERE family_id=${fx.FAM} AND status='OPEN'`) === 1, '真值层:又只剩本期一期 OPEN');

    const s1 = await capture(ui, report, 'S1');
    report.section('S1 · 补历史的联动(相对 S0)');
    await ui.assert(close1(s1.dash['08'].income - s0.dash['08'].income, 200), '仪表盘 08 收入多了 N 的工资 200',
      `${s0.dash['08'].income} → ${s1.dash['08'].income}`);
    await ui.assert(close1(s1.dash['09'].expense - s0.dash['09'].expense, 100), '仪表盘 09 支出多了 N 的开支 100',
      `${s0.dash['09'].expense} → ${s1.dash['09'].expense}`);
    report.info(`S1 相对 S0:报表 08 期末净资产 ${s0.rep['08'].endNW} → ${s1.rep['08'].endNW} · 09 ${s0.rep['09'].endNW} → ${s1.rep['09'].endNW}`);

    // ── 5 · 删除 N ───────────────────────────────────────────────────────
    report.section(`5 · 账户簿 ⋯ → 删除「${N_NAME}」:面板与库对得上 → 先备份再删`);
    await ui.click('header a[href="/accounts"] >> nth=0', '顶栏点「账户」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const row = `tr:has(a[href="/accounts/${N}/delete"])`;
    await ui.click(`${row} details.row-more > summary`, `「${N_NAME}」那一行点 ⋯`);
    await ui.click(`${row} details.row-more[open] a[data-delete-account]`, '菜单里点「删除账户」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const panel = await pageText(ui);
    await save('S1', 'delete_panel', ui, panel);
    const range = (await ui.page.textContent('[data-adel-range]').catch(() => '')).replace(/\s+/g, ' ');
    await ui.assert(range.includes('2026-08 至 2026-10 共 3 个账期') && /已关账 2 期/.test(range),
      '面板:会改写 2026-08 至 2026-10 共 3 个账期(已关账 2 期)', range);
    const modes = await ui.page.evaluate(() => [...document.querySelectorAll('[data-adel-transfer]')]
      .map(e => e.getAttribute('data-mode') + '|' + e.textContent.replace(/\s+/g, ' ').trim()));
    await ui.assert(modes.length === 3
        && modes[0].startsWith('PA_OUT|') && modes[0].includes('工商银行-备用金') && modes[0].includes('本金移出') && modes[0].includes('1,000.00')
        && modes[1].startsWith('PA_IN|') && modes[1].includes('支付宝-余额宝') && modes[1].includes('补录本金') && modes[1].includes('300.00')
        && modes[2].startsWith('OPENING|') && modes[2].includes(M_NAME) && modes[2].includes('开账基线'),
      '面板 · 逐笔:08 A 转给它 → A 本金移出 −1,000 · 09 B 收到 → B 补录本金 +300 · 10 转去 M 开户 → 并进 M 的开账基线', modes.join(' ‖ '));
    const pv = await ui.page.evaluate(() => [...document.querySelectorAll('[data-adel-preview]')].map(e => e.textContent.replace(/\s+/g, ' ').trim()));
    const pvNums = pv.map(l => (l.match(/净资产 (\S+) → (\S+)/) || []).slice(1).map(money));
    await ui.assert(pv.length === 2 && close1(pvNums[0][0] - pvNums[0][1], 800) && close1(pvNums[1][0] - pvNums[1][1], 300),
      '面板 · 删前 → 删后:09(最近已关账)净资产 −800 · 本期 −300', pv.join(' ‖ '));
    await ui.assert(pvNums[0] && close1(pvNums[0][0], s1.rep['09'].endNW) && close1(pvNums[1][0], s1.dash['10'].netWorth),
      '面板上的「删前」与页面同一个数:09 = 报表 09 期末净资产 · 本期 = 仪表盘本期净资产',
      `${pvNums[0] && pvNums[0][0]} vs ${s1.rep['09'].endNW} · ${pvNums[1] && pvNums[1][0]} vs ${s1.dash['10'].netWorth}`);
    const pvPnl = pv[0] ? (pv[0].match(/钱赚 (\S+) → (\S+)/) || []).slice(1).map(money) : [];
    await ui.assert(close1(pvPnl[0], s1.dash['09'].qian) && close1(pvPnl[1], s1.dash['09'].qian),
      '面板上 09 的「钱赚」= 仪表盘 09 的钱赚,删后不变', `${pv[0]} · 仪表盘 ${s1.dash['09'].qian}`);
    const phrase = (await ui.page.textContent('[data-adel-phrase]')).trim();
    await ui.assert(phrase.split('\n').length === 4 && phrase.endsWith('元也会受到影响。'), '确认语四行、句号结尾', phrase);
    await ui.fill('[data-adel-input]', phrase, '照着输入确认语');
    await ui.page.locator('[data-adel-input]').dispatchEvent('input');
    await ui.submit('[data-adel-submit]', '点「删除(先备份)」');
    await ui.seesText(`已删除「${N_NAME}」`, '回到账户簿:已删除 · 可在回收站恢复 · 备份在 …');
    await ui.assert(db.one(`SELECT CONCAT_WS('|',kind,status) FROM backup_log WHERE id > ${state.maxBackup} ORDER BY id DESC LIMIT 1`) === 'MANUAL|SUCCESS',
      '真值层:删之前做了一份成功的手动备份');
    await ui.assert(db.num(`SELECT COUNT(*) FROM account WHERE id=${N}`) === 0, '真值层:N 不在 account 表里了');
    state.trashId = db.num(`SELECT MAX(id) FROM account_trash WHERE account_id=${N}`);
    const pas = db.col(`SELECT CONCAT_WS('|', account_id, period_id, amount) FROM principal_adjustment WHERE source_tag='ACCOUNT_DELETE' AND deleted_at IS NULL AND period_id IN (${state.p8},${state.p9},${state.p10}) ORDER BY period_id`);
    await ui.assert(pas.length === 2 && pas[0] === `${A}|${state.p8}|-1000.00` && pas[1] === `${B}|${state.p9}|300.00`,
      '真值层:A 08 本金移出 −1,000 · B 09 补录本金 +300 · M 不写', pas.join(' ; '));

    // ── S2 · 删后逐项核对 ─────────────────────────────────────────────────
    const s2 = await capture(ui, report, 'S2');
    report.section('S2 · 删后:仪表盘每一期');
    const balN = { '08': 1200, '09': 800, '10': 300 };
    const dRen = { '08': -200, '09': 100, '10': 0 };
    const dOpen = { '08': -1000, '09': 300, '10': 500 };
    for (const k of ['08', '09', '10']) {
      const a = s1.dash[k], b = s2.dash[k];
      await ui.assert(close1(a.netWorth - b.netWorth, balN[k]), `仪表盘 ${k}:净资产少了 N 那一期的余额 ${balN[k]}`, `${a.netWorth} → ${b.netWorth}`);
      await ui.assert(close1(b.ren - a.ren, dRen[k]), `仪表盘 ${k}:人赚 ${dRen[k] >= 0 ? '+' : ''}${dRen[k]}`, `${a.ren} → ${b.ren}`);
      await ui.assert(close1(b.dNW, b.ren + b.qian + b.opening), `仪表盘 ${k}:删后 ΔNW = 人赚 + 钱赚 + 开账基线`, JSON.stringify(b));
      if (k === '08') continue;   // 08 是 N 的第一期,单独核(见下)
      await ui.assert(close1(a.qian, b.qian), `仪表盘 ${k}:钱赚(投资损益)不变`, `${a.qian} → ${b.qian}`);
      await ui.assert(close1(b.opening - a.opening, dOpen[k]), `仪表盘 ${k}:开账基线 ${dOpen[k] >= 0 ? '+' : ''}${dOpen[k]}`, `${a.opening} → ${b.opening}`);
    }
    // 08 是 N 的第一期。删后 08 应当回到「家里从来没有 N」的样子:钱赚、人赚与 S0 相同,
    // 开账基线 = S0 再加上 A 的「本金移出」−1,000(那 1,000 现在算流出家庭的本金)。
    // S1 的 08 钱赚比 S0 少了 N 首期那笔工资 —— 已知口径:新账户第一期记的收入既进人赚又进开账基线(openingOf 只扣转入,见 v1.28.1 注释),
    // 那是补历史时就有的,与删除无关;删掉 N 之后这一笔自然消失。
    const s0d = s0.dash['08'], s2d = s2.dash['08'];
    await ui.assert(close1(s2d.qian, s0d.qian) && close1(s2d.ren, s0d.ren), '仪表盘 08:删后钱赚、人赚回到建 N 之前(S0)',
      `钱赚 S0 ${s0d.qian} · S1 ${s1.dash['08'].qian} · S2 ${s2d.qian} ‖ 人赚 S0 ${s0d.ren} · S2 ${s2d.ren}`);
    await ui.assert(close1(s2d.opening - s0d.opening, -1000), '仪表盘 08:开账基线 = S0 − 1,000(A 的本金移出)', `S0 ${s0d.opening} → S2 ${s2d.opening}`);
    report.info(`已知口径(与删除无关):S1 的 08 钱赚 ${s1.dash['08'].qian} 比 S0 ${s0d.qian} 少 ${s0d.qian - s1.dash['08'].qian} —— 新账户首期的工资同时算进了人赚和开账基线`);
    report.section('S2 · 删后:报表 08 / 09');
    const r1 = s1.rep, r2 = s2.rep;
    await ui.assert(close1(r1['08'].endNW - r2['08'].endNW, 1200) && close1(r1['09'].endNW - r2['09'].endNW, 800),
      '报表期末净资产:08 −1,200 · 09 −800', `08 ${r1['08'].endNW}→${r2['08'].endNW} · 09 ${r1['09'].endNW}→${r2['09'].endNW}`);
    await ui.assert(close1(r1['08'].startNW, r2['08'].startNW) && close1(r1['09'].startNW - r2['09'].startNW, 1200),
      '报表期初净资产:08 不变(07 没有 N)· 09 −1,200(= 08 期末)', `08 ${r1['08'].startNW}→${r2['08'].startNW} · 09 ${r1['09'].startNW}→${r2['09'].startNW}`);
    await ui.assert(close1(r1['08'].income - r2['08'].income, 200) && close1(Math.abs(r1['09'].expense) - Math.abs(r2['09'].expense), 100)
        && close1(r1['08'].expense, r2['08'].expense) && close1(r1['09'].income, r2['09'].income),
      '报表收支:08 收入 −200 · 09 支出 −100 · 其余不变',
      JSON.stringify({ s1: [r1['08'].income, r1['08'].expense, r1['09'].income, r1['09'].expense], s2: [r2['08'].income, r2['08'].expense, r2['09'].income, r2['09'].expense] }));
    await ui.assert(close1(r1['09'].pnl, r2['09'].pnl) && close1(s0.rep['08'].pnl, r2['08'].pnl),
      '报表投资损益(钱赚):09 不变 · 08 回到建 N 之前(S0)', `08 S0 ${s0.rep['08'].pnl} · S1 ${r1['08'].pnl} · S2 ${r2['08'].pnl} · 09 ${r1['09'].pnl}→${r2['09'].pnl}`);
    for (const k of ['08', '09']) {
      const [f1, t1] = (r1[k].filled || '0/0').split('/').map(Number);
      const [f2, t2] = (r2[k].filled || '0/0').split('/').map(Number);
      await ui.assert(f2 === f1 - 1 && t2 === t1 - 1, `报表 ${k}:「已填」分子分母各少 1(N 不在了)`, `${r1[k].filled} → ${r2[k].filled}`);
      await ui.assert(r2[k].traces.some(l => l.includes(`删除账户「${N_NAME}」后重算`)) && !r1[k].traces.some(l => l.includes(N_NAME)),
        `报表 ${k}:抬头留痕「删除账户「${N_NAME}」后重算」`, r2[k].traces.join(' / '));
    }
    report.section('S2 · 删后:对方账户');
    for (const [key, label] of [['A8', 'A 08'], ['A9', 'A 09'], ['A10', 'A 10'], ['B9', 'B 09'], ['B10', 'B 10'], ['M10', 'M 10']]) {
      await ui.assert(s1.pnl[key] === s2.pnl[key], `${label} 本期损益删前删后逐分相同`, `${s1.pnl[key]} → ${s2.pnl[key]}`);
    }
    await ui.assert(/本金移出/.test(s2.acct[A]) && /删除账户时由划转改记/.test(s2.acct[A]) && !/本金移出/.test(s1.acct[A]),
      'A 的详情页:08 那笔划转变成「本金移出 · 删除账户时由划转改记」');
    await ui.assert(/补录本金 · 删除账户时由划转改记/.test(s2.acct[B]), 'B 的详情页:09 那笔划转变成「补录本金 · 删除账户时由划转改记」');
    await ui.assert(!s2.acct.list.includes(N_NAME) && s2.acct.list.includes(M_NAME), '账户簿:N 不见了,M 还在');

    // ── 6 · 恢复 ─────────────────────────────────────────────────────────
    report.section('6 · 管理 → 回收站 → 恢复');
    await ui.click('header a[href="/admin"] >> nth=0', '顶栏点「管理」');
    await ui.click('main a.group[href="/admin/trash"]', '总览「系统」组点「回收站」');
    const tr = `tr[data-trash-row="${state.trashId}"]`;
    await ui.click(`${tr} [data-trash-restore-box] > summary`, '这一行点「恢复」');
    await ui.submit(`${tr} [data-trash-restore] button`, '点「恢复「…」」');
    await ui.seesText(`已恢复「${N_NAME}」`, '提示「已恢复」');
    const s3 = await capture(ui, report, 'S3');
    report.section('S3 · 恢复后:每个数回到删除前');
    for (const k of ['08', '09', '10']) {
      await ui.assert(JSON.stringify(s3.dash[k]) === JSON.stringify(s1.dash[k]), `仪表盘 ${k}:与删除前逐项相同`,
        `${JSON.stringify(s1.dash[k])} vs ${JSON.stringify(s3.dash[k])}`);
    }
    for (const k of ['08', '09']) {
      const strip = (r) => JSON.stringify({ ...r, traces: undefined });
      await ui.assert(strip(s3.rep[k]) === strip(s1.rep[k]), `报表 ${k}:数字与删除前逐项相同`, `${strip(s1.rep[k])} vs ${strip(s3.rep[k])}`);
      await ui.assert(s3.rep[k].traces.some(l => l.includes(`恢复账户「${N_NAME}」后重算`)), `报表 ${k}:抬头多一条「恢复账户…后重算」`, s3.rep[k].traces.join(' / '));
    }
    await ui.assert(JSON.stringify(s3.pnl) === JSON.stringify(s1.pnl), '对方账户每期损益与删除前相同', `${JSON.stringify(s1.pnl)} vs ${JSON.stringify(s3.pnl)}`);
    await ui.assert(db.num(`SELECT COUNT(*) FROM principal_adjustment WHERE source_tag='ACCOUNT_DELETE' AND deleted_at IS NULL AND period_id IN (${state.p8},${state.p9})`) === 0,
      '真值层:改记的本金进出都拿掉了');

    // ── summary ──────────────────────────────────────────────────────────
    const rows = [];
    const T = (label, f) => rows.push(`| ${label} | ${['S0', 'S1', 'S2', 'S3'].map(t => {
      const s = state.snap[t]; let v = null; try { v = s ? f(s) : null; } catch (e) { v = null; }
      return v == null ? '—' : Number(v).toLocaleString('en-US');
    }).join(' | ')} |`);
    for (const k of ['08', '09', '10']) {
      T(`仪表盘 ${k} 净资产`, s => s.dash[k].netWorth); T(`仪表盘 ${k} ΔNW`, s => s.dash[k].dNW);
      T(`仪表盘 ${k} 人赚`, s => s.dash[k].ren); T(`仪表盘 ${k} 钱赚`, s => s.dash[k].qian); T(`仪表盘 ${k} 开账基线`, s => s.dash[k].opening);
    }
    for (const k of ['08', '09']) {
      T(`报表 ${k} 期初净资产`, s => s.rep[k].startNW); T(`报表 ${k} 收入`, s => s.rep[k].income); T(`报表 ${k} 支出`, s => s.rep[k].expense);
      T(`报表 ${k} 投资损益`, s => s.rep[k].pnl); T(`报表 ${k} 期末净资产`, s => s.rep[k].endNW);
    }
    for (const key of ['A8', 'A9', 'A10', 'B9', 'B10', 'M10']) T(`账户损益 ${key}`, s => s.pnl[key]);
    fs.writeFileSync(path.join(OUT, 'summary.md'), `| 指标 | S0 删前原样 | S1 补完历史 | S2 删除后 | S3 恢复后 |\n|---|---|---|---|---|\n${rows.join('\n')}\n`);
    report.info(`四个状态的对照表:${path.join(OUT, 'summary.md')}`);
    for (const e of [...new Set(state.consoleErrors)]) report.info(`控制台:${e}`);
    await ui.noConsoleErrors('这些页面控制台无报错');
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
    await ui.assert(db.num(`SELECT COUNT(*) FROM account WHERE display_name IN ('${N_NAME}','${M_NAME}')`) === 0
        && db.num(`SELECT COUNT(*) FROM period WHERE family_id=${fx.FAM} AND status='OPEN'`) === 1,
      '还原:整库回到开跑前(两个验证账户都不在 · 只有本期 OPEN)· 应用已重启并重新登录');
  },
};
