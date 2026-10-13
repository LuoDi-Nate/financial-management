/**
 * flow · issue #21 · 跨币种划转:转入方按实到金额算
 *
 * 提交者原话:人民币账户转出 1000、美元账户到账 100,两边余额都对,
 * 但美元账户「按人民币计算的变动值,提示有 900 的差额未解释」。
 *
 * 全程从页面发起:在填报页展开人民币账户 → 在搜索式下拉里【打字搜】目标账户 → 填两个金额 → 点「划转」;
 * 看美元账户那一行;展开它的本期流水、点 ✕、读确认框、确认删除;到「数据安守」页点导出、打开 CSV。
 *
 * 前置:beta 上没有美元现金账户 —— 自己建一个临时的美元现金账户(与提交者的场景一致:现金账户、美元记账)。
 *   原来借用 beta 上的「test银行卡」临时改成美元;v1.33 有了删除账户,维护者验收时把那张卡删进了回收站,
 *   这条 flow 就读不到它了(2026-10-13)。e2e 不该依赖维护者手里会动的数据。
 * cleanup 删掉临时账户和它的行、还原人民币账户的余额,并核对与跑之前逐分相同。
 */
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const FROM = 2;              // 工商银行-备用金 · CNY
const TO_NAME = 'e2e23美元现金';   // 临时建的美元现金账户(id 在 state.to)
const SENT = 1000;
const RECEIVED = 100;

const state = {};

/** 在某个元素上跑一段读取函数(读不到返回 fallback) */
async function read(ui, selector, fn, fallback = null) {
  try { return await ui.page.locator(selector).first().evaluate(fn); } catch { return fallback; }
}

/** 某个账户那一行里「转入」「未解释」两个读数 + 有没有未解释提示(从渲染后的 DOM 上抠用户看到的数) */
async function readRow(ui, id) {
  return ui.page.evaluate((id) => {
    const el = document.getElementById('entry-block-' + id);
    if (!el) return null;
    const t = el.innerText.replace(/\s+/g, ' ');
    const m = (re) => { const x = t.match(re); return x ? x[1] : null; };
    return {
      tin: Number((m(/转入 ([-\d,.]+)/) || 'NaN').replace(/,/g, '')),
      unexp: m(/未解释 (\S+)/),
      warn: /余额出现未解释变化/.test(t),
    };
  }, id).catch(() => null);
}

/** 行被收起时点它的 summary 展开 —— 用户就是这么打开的 */
async function openFold(ui, id) {
  const fold = `#entry-block-${id} details.entry-fold`;
  const isOpen = await read(ui, fold, d => d.open);
  if (isOpen === false) await ui.click(`${fold} > summary`, `展开账户 ${id} 的本期明细`);
}

module.exports = {
  name: '23-cross-currency-transfer',
  title: 'issue #21 · 跨币种划转:转入方按实到金额算,不再冒出「未解释」',

  async run(ui, report) {
    ui.flow = this.name;

    // ── 前置 ──────────────────────────────────────────────────────────
    db.raw(`INSERT INTO account (family_id, display_name, type, currency, display_order)
            VALUES (${fx.FAM}, '${TO_NAME}', 'CASH', 'USD', 995)`);
    state.to = db.num(`SELECT MAX(id) FROM account WHERE family_id=${fx.FAM} AND display_name='${TO_NAME}'`);
    const TO = state.to;
    state.maxTid = db.num(`SELECT COALESCE(MAX(id),0) FROM transfer`);
    state.pid = fx.currentPeriod();
    state.bal = {};
    for (const a of [FROM, TO]) {
      state.bal[a] = db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${a}`);
    }
    report.info(`前置:临时美元现金账户 ${TO_NAME} #${TO} · 进行期 #${state.pid}`);

    // ── 1 · 划转前的读数 ─────────────────────────────────────────────
    report.section('1 · 在填报页做一笔跨币种划转(页面操作)');
    await ui.goto('/entry');
    await ui.rendered('填报页');
    const before = await readRow(ui, TO);
    await ui.assert(before && !Number.isNaN(before.tin), `读到${TO_NAME}划转前的「转入」与「未解释」`,
                    JSON.stringify(before));
    if (!before) return;

    // ── 2 · 在人民币账户那一行里划转 ─────────────────────────────────
    await openFold(ui, FROM);
    const form = `#entry-block-${FROM} form[hx-post$="/entry/${FROM}/transfer"]`;
    // 搜索式下拉:点输入框 → 打字 → 点候选项(不是去改隐藏的原生 select)
    await ui.click(`${form} .ss-input`, '点开「转给哪个账户」的搜索框');
    await ui.page.keyboard.type(TO_NAME, { delay: 30 });
    await ui.page.waitForTimeout(300);
    await ui.click(`${form} .ss-item:has-text("${TO_NAME}")`, `在候选里点「${TO_NAME}」`);
    const picked = await read(ui, `${form} select[name="toAccountId"]`, s => s.value);
    await ui.assert(String(picked) === String(TO), '下拉选中后,表单里真的是目标账户', `实得 ${picked}`);
    await ui.fill(`${form} input[name="amount"]`, SENT, `划转金额填 ${SENT}(人民币)`);
    await ui.fill(`${form} input[name="toAmount"]`, RECEIVED, `到账额填 ${RECEIVED}(美元)`);
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes(`/entry/${FROM}/transfer`), { timeout: 20000 }).catch(() => null),
      ui.click(`${form} button:has-text("划转")`, '点「↔ 划转」'),
    ]);
    await ui.page.waitForTimeout(1200);

    const t = db.one(`SELECT CONCAT(id,'|',amount,'|',IFNULL(to_amount,'NULL')) FROM transfer
                       WHERE id > ${state.maxTid} AND from_account_id=${FROM} AND to_account_id=${TO}
                         AND deleted_at IS NULL ORDER BY id DESC LIMIT 1`);
    await ui.assert(!!t, '真值层:库里多了这笔划转', `max id before=${state.maxTid}`);
    if (!t) return;
    const [tid, amt, toAmt] = t.split('|');
    state.tid = tid;
    await ui.assert(Number(amt) === SENT && Number(toAmt) === RECEIVED,
                    '真值层:转出 1000、实到 100 都存对了', `amount=${amt} to_amount=${toAmt}`);

    // ── 3 · 用户下一次打开时,美元账户那一行 ──────────────────────────
    report.section('2 · 美元账户那一行(刷新后,用户下一次打开看到的)');
    await ui.goto('/entry');
    await ui.rendered('填报页');
    const after = await readRow(ui, TO);
    await ui.assert(Math.abs((after.tin - before.tin) - RECEIVED) < 0.005,
                    `「转入」只多了 ${RECEIVED}(美元账户实到的数;修复前多 ${SENT})`,
                    `前 ${before.tin} → 后 ${after.tin}`);
    await ui.assert(after.unexp === before.unexp,
                    '「未解释」没有被这笔划转改变(修复前多出 −900)', `前 ${before.unexp} → 后 ${after.unexp}`);
    await ui.assert(after.warn === before.warn, '没有因为这笔划转冒出「余额出现未解释变化」',
                    `前 ${before.warn} → 后 ${after.warn}`);

    // 本期流水:点开折叠,看这笔转入的金额与币种
    const ledger = `#entry-block-${TO} details:has(ul) > summary`;
    const delBtn = `#entry-block-${TO} button[hx-post="/entry/transfer/${tid}/delete"]`;
    await ui.click(ledger, `展开${TO_NAME}的本期流水`);
    await ui.visible(delBtn, '这笔划转出现在美元账户的本期流水里,带删除按钮');
    const lineText = await read(ui, delBtn, b => b.closest('li').innerText.replace(/\s+/g, ' '), '');
    await ui.assert(/\+\s*\$100(\.00)?\b/.test(lineText) && !/1,000/.test(lineText),
                    '本期流水里这笔划入显示为 +$100,不是 +1,000', lineText);

    // ── 4 · 导出:备份里要有实到金额 ─────────────────────────────────
    report.section('3 · 数据安守 → 导出 CSV 包:划转带上实到金额');
    await ui.goto('/admin/backup');
    await ui.rendered('数据安守页');
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'e2e21-'));
    try {
      const [dl] = await Promise.all([
        ui.page.waitForEvent('download', { timeout: 30000 }),
        ui.page.click('a[href$="/export.zip"]'),
      ]);
      const zip = path.join(tmp, 'export.zip');
      await dl.saveAs(zip);
      const csv = execFileSync('unzip', ['-p', zip, 'transfers.csv'], { encoding: 'utf8' });
      const [head, ...rows] = csv.trim().split(/\r?\n/);
      await ui.assert(head.split(',').includes('to_amount'), '导出的 transfers.csv 表头有 to_amount', head);
      const row = rows.find(r => r.startsWith(tid + ','));
      await ui.assert(!!row && /,100(\.00)?$/.test(row), '这笔划转那一行最后一列是实到的 100', row || '(没找到这一行)');
    } catch (e) {
      await ui.assert(false, '点「导出 CSV 包」拿到文件', String(e).split('\n')[0]);
    } finally {
      fs.rmSync(tmp, { recursive: true, force: true });
    }

    // ── 5 · 删除:确认框两边各说各的金额与币种 ──────────────────────
    report.section('4 · 在美元账户那一行删掉这笔划转');
    await ui.goto('/entry');
    await ui.click(ledger, `展开${TO_NAME}的本期流水`);
    let confirmText = '';
    // 前面的 flow(20 / 21)在同一个 page 上挂了常驻的「对话框一律确认」,这里可能拿到一个已经被确认过的对话框 ——
    //   只读它的文字;accept 失败不能抛(事件回调里抛出会直接打崩整个 runner,cleanup 都来不及跑)
    ui.page.once('dialog', d => { confirmText = d.message(); d.accept().catch(() => {}); });
    await Promise.all([
      ui.page.waitForResponse(r => r.url().includes(`/entry/transfer/${tid}/delete`), { timeout: 20000 }).catch(() => null),
      ui.click(delBtn, '点这笔划转的 ✕'),
    ]);
    await ui.page.waitForTimeout(1200);
    await ui.assert(/减少 −\$100(\.00)?/.test(confirmText) && /退回 \+¥1,000(\.00)?/.test(confirmText),
                    '确认框:美元账户减少 −$100、人民币账户退回 +¥1,000(各用各的金额与币种)', confirmText);
    const del = db.one(`SELECT deleted_at IS NOT NULL FROM transfer WHERE id=${tid}`);
    await ui.assert(del === '1', '真值层:这笔划转确实删掉了', `deleted=${del}`);

    await ui.goto('/entry');
    const back = await readRow(ui, TO);
    await ui.assert(back.tin === before.tin && back.unexp === before.unexp,
                    '删掉之后读数回到划转之前', `转入 ${back.tin}/${before.tin} · 未解释 ${back.unexp}/${before.unexp}`);
  },

  async cleanup(ui, report) {
    const TO = state.to;
    if (!TO) return;
    if (state.maxTid != null) {
      db.raw(`DELETE FROM transfer WHERE id > ${state.maxTid} AND from_account_id=${FROM} AND to_account_id=${TO}`);
    }
    if (state.bal && state.pid) {
      const now = db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${FROM}`);
      if (now !== state.bal[FROM]) {
        if (state.bal[FROM] === null) db.raw(`DELETE FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${FROM}`);
        else db.raw(`UPDATE period_snapshot SET end_balance=${state.bal[FROM]} WHERE period_id=${state.pid} AND account_id=${FROM}`);
      }
      const fin = db.one(`SELECT end_balance FROM period_snapshot WHERE period_id=${state.pid} AND account_id=${FROM}`);
      if (fin === state.bal[FROM]) report.info(`还原:账户 ${FROM} 本期余额与跑之前相同`);
      else report.fail(this.name, `还原:账户 ${FROM} 本期余额没回到原值`, `${fin} ≠ ${state.bal[FROM]}`);
    }
    for (const sql of [`DELETE FROM snapshot_todo WHERE account_id=${TO}`, `DELETE FROM period_snapshot WHERE account_id=${TO}`,
                       `DELETE FROM cash_flow WHERE account_id=${TO}`, `DELETE FROM transfer WHERE from_account_id=${TO} OR to_account_id=${TO}`,
                       `DELETE FROM account_group_member WHERE account_id=${TO}`,
                       `DELETE FROM account WHERE id=${TO} AND family_id=${fx.FAM} AND display_name='${TO_NAME}'`]) {
      try { db.raw(sql); } catch (e) { report.info(`还原跳过:${sql.slice(0, 60)} · ${String(e.message).slice(0, 80)}`); }
    }
    report.info(`还原:临时美元账户已删 · 残留 ${db.num(`SELECT COUNT(*) FROM account WHERE display_name='${TO_NAME}'`)} 个`);
  },
};
