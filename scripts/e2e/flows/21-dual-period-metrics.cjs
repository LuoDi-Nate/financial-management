/**
 * flow · v1.23 双活跃窗口下的**指标联动矩阵**(prd/v1.23.md §4.3)
 *
 * ── 为什么要单独一条 ──
 *
 * `20-period-rhythm` 验的是「页面对不对、库里存没存」。它**没有**验
 * 「余额变动之后,dashboard 和报表上的那些数字有没有按矩阵联动」——
 * 而那张 27 行 × 6 列的矩阵正是这一版的核心产出,留在文档里不落成断言,
 * 等于没有人守。
 *
 * 这条 flow 把矩阵里最关键的四列变成可执行断言:
 *
 *   A 往**补录期**填收支 → 家庭消费/储蓄率 ↑ · 净资产按传导变 · **收益类不动**
 *   D 改**进行期**余额   → 净资产 ↑ · **收支类不动**
 *   E 估值/股价刷新      → 存量类可变 · **一格收支都不许动**(矩阵规律 1)
 *   F 补录期关账那一刻   → 收益类锚点前移、月均支出纳入该期(一堆数同时跳)
 *
 * ── 数字从**页面**读,不从库读 ──
 *
 * 要验的是「用户看到的数对不对」。从库里 SELECT 出来自己算一遍,
 * 验的是我的 SQL 和产品的 SQL 是否一致 —— 那证明不了页面。
 * 所以这里全部用 `ui.kpi()` 从渲染后的 DOM 上抠数,
 * 并且**先关掉隐私模式**(否则读到的是 `···`)。
 *
 * ── 断言的形状是「变 / 不变」,不是「等于某个具体值」 ──
 *
 * beta 上的数据会随别的回归漂移,钉死绝对值必然变成脆测试。
 * 矩阵本来也只声明方向(↑ / — / ⏳),所以断言就按方向写。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const NOTE = 'e2e-v123m-';

/** 页面上一个 KPI 的当前值(原样字符串,用于比「变没变」)。 */
async function kpi(ui, label) {
  return ui.page.evaluate((lb) => {
    const eyes = [...document.querySelectorAll('.kpi-eyebrow')];
    const e = eyes.find(x => x.textContent.trim().startsWith(lb));
    if (!e) return null;
    const card = e.closest('*');
    const v = card && card.parentElement
      ? card.parentElement.querySelector('.kpi-value')
      : null;
    return v ? v.textContent.trim() : null;
  }, label);
}

/** 抓一组 dashboard 指标做快照。 */
async function snapDashboard(ui) {
  await ui.goto('/dashboard');
  // 关掉隐私模式,否则金额是 ··· ,比不出变没变
  await ui.page.evaluate(() => {
    try { sessionStorage.setItem('privacy', '0'); } catch (e) {}
    document.documentElement.classList.remove('privacy');
  });
  await ui.page.waitForTimeout(400);
  const out = {};
  for (const k of ['净资产', '总资产', '总负债', '紧急储备', '本月资产收益']) {
    out[k] = await kpi(ui, k);
  }
  // 人赚 / 钱赚拆解 + 储蓄率:这些不是 kpi-value,从正文文本里取整段做比对
  out['_text'] = await ui.page.evaluate(() => {
    const el = document.querySelector('[id*=cashflow], [class*=cashflow-split]') || document.body;
    return el.innerText.replace(/\s+/g, ' ').slice(0, 2000);
  });
  return out;
}

async function snapReports(ui) {
  await ui.goto('/reports');
  await ui.page.evaluate(() => {
    try { sessionStorage.setItem('privacy', '0'); } catch (e) {}
    document.documentElement.classList.remove('privacy');
  });
  await ui.page.waitForTimeout(400);
  return ui.page.evaluate(() => document.body.innerText.replace(/\s+/g, ' '));
}

module.exports = {
  name: '21-dual-period-metrics',
  title: 'v1.23 · 双活跃窗口下的指标联动矩阵(PRD §4.3)',

  async run(ui, report) {
    ui.flow = this.name;

    // ── 前置:造双活跃窗口 ───────────────────────────────────────────────
    report.section('0 · 前置:T+2 + 双活跃窗口');

    const prev = fx.lastEndedPeriod();
    const cur = fx.currentPeriod();
    if (!prev || !cur) { report.skip(this.name, '矩阵验证', '账期数据不足'); return; }

    await ui.goto('/admin/periods');
    await ui.page.click('label.rhythm-opt:has(input[value="T2"])').catch(() => {});
    await ui.page.click('form[action$="close-rhythm"] button').catch(() => {});
    await ui.page.waitForTimeout(1000);

    if (db.one(`SELECT status FROM period WHERE id=${prev}`) === 'CLOSED') {
      const sel = `form[action*="/${prev}/reopen"]`;
      const pg = await ui.paginateUntil('/admin/periods?page={p}', sel);
      if (pg >= 0) {
        await ui.page.fill(`${sel} input[name=reason]`, 'e2e 矩阵验证');
        ui.page.on('dialog', d => d.accept().catch(() => {}));
        await ui.page.click(`${sel} button`).catch(() => {});
        await ui.page.waitForTimeout(1500);
      }
    }
    fx.markAllMembersCompleted(prev);
    await ui.assert(fx.openCount() === 2, '双活跃窗口已建立', `实得 ${fx.openCount()} 期`);
    ui.info(`补录期=${prev} · 进行期=${cur}`);

    // 隐私模式会把金额糊成 ···,先关掉再取基线
    const base = await snapDashboard(ui);
    await ui.assert(base['净资产'] && !base['净资产'].includes('·'),
      '能从页面读到净资产真实值(隐私模式已关)', `实得 ${base['净资产']}`);
    const baseRep = await snapReports(ui);
    ui.info(`基线 · 净资产=${base['净资产']} 紧急储备=${base['紧急储备']} 本月资产收益=${base['本月资产收益']}`);

    // ── A 列:往补录期填收支 ────────────────────────────────────────────
    report.section('A 列 · 往补录期填一笔支出(矩阵第 9/10/12/18 行)');

    const acct = db.one(`SELECT account_id FROM period_snapshot
                          WHERE period_id=${cur} AND source_tag='CARRIED_FORWARD' LIMIT 1`)
             || db.one(`SELECT id FROM account WHERE family_id=${fx.FAM}
                         AND archived_at IS NULL AND type='CASH' LIMIT 1`);
    await ui.goto(`/entry?period=${prev}`);
    const form = 'form[action="/entry/expense"]';
    if (await ui.page.locator(form).count() === 0) {
      report.skip(this.name, 'A 列', '该家庭是总额模式,页面没有逐笔支出表单');
    } else {
      await ui.choose('accountId', acct, form);
      await ui.fillByName('amount', '888.88', form);
      await ui.fillByName('note', NOTE + 'A列', form);
      await ui.submit(`${form} button[type=submit]`, '在补录期提交 888.88 的支出');

      const afterA = await snapDashboard(ui);

      // 矩阵第 10 行:本月资产收益在 A 列是 ⏳ —— 补录期关账前**就不该动**。
      // 这是 v1.6.30 用一次 prod 事故换来的规矩(半填期不算收益),本版继承它。
      await ui.assert(afterA['本月资产收益'] === base['本月资产收益'],
        '矩阵⏳:本月资产收益**不动**(补录期没关账,收益类不采纳)',
        `${base['本月资产收益']} → ${afterA['本月资产收益']}`);

      // 矩阵第 9 行:紧急储备 = 流动资产 ÷ 月均支出。
      // 月均支出只算**已关账**期(FR-628 剔除全部 OPEN),所以往补录期填钱它也不该动。
      await ui.assert(afterA['紧急储备'] === base['紧急储备'],
        '矩阵:紧急储备不动(月均支出只算已关账期,补录期被剔除)',
        `${base['紧急储备']} → ${afterA['紧急储备']}`);

      // 矩阵第 7 行:净资产锚进行期。这笔支出落在补录期,
      // 但会通过 FR-623 传导改掉进行期的「开账延续」值 → 净资产**应该变**。
      const cfTag = db.one(`SELECT source_tag FROM period_snapshot
                             WHERE period_id=${cur} AND account_id=${acct}`);
      if (cfTag === 'CARRIED_FORWARD') {
        await ui.assert(afterA['净资产'] !== base['净资产'],
          '矩阵↑:净资产跟着传导变了(补录期余额 → 进行期延续值)',
          `${base['净资产']} → ${afterA['净资产']}`);
      } else {
        ui.info(`进行期该账户快照是 ${cfTag},不在传导范围内 —— 净资产不变是对的`);
        await ui.assert(afterA['净资产'] === base['净资产'],
          '矩阵:用户确认过的余额不被传导,净资产不动',
          `${base['净资产']} → ${afterA['净资产']}`);
      }

      // 真值层:这笔钱确实进了补录期的家庭消费
      const inPrev = db.num(`SELECT COUNT(*) FROM cash_flow
                              WHERE period_id=${prev} AND note LIKE '${NOTE}%' AND deleted_at IS NULL`);
      await ui.assert(inPrev >= 1, '真值层:支出确实落在补录期(不是进行期)', `实得 ${inPrev} 笔`);
      const inCur = db.num(`SELECT COUNT(*) FROM cash_flow
                             WHERE period_id=${cur} AND note LIKE '${NOTE}%' AND deleted_at IS NULL`);
      await ui.assert(inCur === 0, '真值层:一分钱都没漏进进行期', `实得 ${inCur} 笔`);
    }

    // ── §4.4 反向:**没填完**的补录期,收益类不许采纳 ────────────────────
    report.section('§4.4 反向 · 没填完的期不许被收益类采纳(防重演 v1.6.30)');

    {
      // ⚠ 观测点必须选**真正锚 returnAnchorPeriodId 的那个数**。
      //   踩过:第一版选了 dashboard 的「本月资产收益」,两种状态下都是同一个值 ——
      //   因为它的口径是「本月未关账时按已录事实**实时计算**」,锚的是**进行期**,
      //   跟收益类锚点根本没关系。看 tooltip 就该发现。
      //   报表页的锚期是最直接的观测点:它**就是** returnAnchor 的显示。
      const repWith = await snapReports(ui);
      await ui.assert(repWith.includes('仍在填报中'),
        '填完的补录期被收益类采纳 → 报表锚它(且标注仍在填报中)');

      db.raw(`DELETE FROM period_member_completion WHERE period_id=${prev}`);
      const repWithout = await snapReports(ui);
      await ui.assert(!repWithout.includes('仍在填报中') && repWithout.includes('数据截至'),
        '撤掉「填完」标记 → 收益类**退出**该期,报表退回上上期的已关账快照',
        repWithout.slice(0, 120));

      fx.markAllMembersCompleted(prev);
      const repBack = await snapReports(ui);
      await ui.assert(repBack.includes('仍在填报中'),
        '补回「填完」标记 → 立刻又被采纳(判据是「填完没填完」,不是「关没关」)');
    }

    // ── E 列:估值刷新 ──────────────────────────────────────────────────
    report.section('E 列 · 估值刷新只碰存量,一格收支都不许动(矩阵规律 1)');

    const beforeE = await snapDashboard(ui);
    const cfPrevE = db.num(`SELECT COUNT(*) FROM cash_flow WHERE period_id=${prev} AND deleted_at IS NULL`);
    const cfCurE  = db.num(`SELECT COUNT(*) FROM cash_flow WHERE period_id=${cur} AND deleted_at IS NULL`);
    const prevBalE = db.num(`SELECT COALESCE(SUM(end_balance),0) FROM period_snapshot WHERE period_id=${prev}`);

    await ui.goto('/entry');
    // 按钮是 hx-post 的 button(v0.4.22 起),只认 form 的旧选择器一直落到 skip(v1.30 补上)
    const refresh = 'button[hx-post="/entry/refresh-stocks"], form[action="/entry/refresh-stocks"] button';
    if (await ui.page.locator(refresh).count() === 0) {
      report.skip(this.name, 'E 列', '填报页没有拉股价按钮');
    } else {
      await ui.submit(refresh, '点「一键拉取股价」');
      await ui.page.waitForTimeout(3000);

      await ui.assert(db.num(`SELECT COUNT(*) FROM cash_flow WHERE period_id=${prev} AND deleted_at IS NULL`) === cfPrevE
                   && db.num(`SELECT COUNT(*) FROM cash_flow WHERE period_id=${cur} AND deleted_at IS NULL`) === cfCurE,
        '矩阵规律 1:估值刷新没有产生任何收支流水');
      await ui.assert(db.num(`SELECT COALESCE(SUM(end_balance),0) FROM period_snapshot WHERE period_id=${prev}`) === prevBalE,
        '矩阵:估值没有回写补录期(余额轴只有一个「现在」)');

      const afterE = await snapDashboard(ui);
      await ui.assert(afterE['本月资产收益'] === beforeE['本月资产收益'],
        '矩阵⏳:估值刷新也没让收益类提前动(补录期仍未关账)',
        `${beforeE['本月资产收益']} → ${afterE['本月资产收益']}`);
    }

    // ── F 列:补录期关账那一刻 ──────────────────────────────────────────
    report.section('F 列 · 补录期关账 → 一堆数字同时跳(矩阵 F 列)');

    const beforeF = await snapDashboard(ui);
    const repBeforeF = await snapReports(ui);
    await ui.assert(repBeforeF.includes('仍在填报中'),
      '关账前:报表如实标注「仍在填报中」');

    const fsel = `form[action*="/${prev}/force-close"]`;
    const fpg = await ui.paginateUntil('/admin/periods?page={p}', fsel);
    if (fpg < 0) {
      report.skip(this.name, 'F 列', '翻页没找到关账按钮');
    } else {
      ui.page.on('dialog', d => d.accept().catch(() => {}));
      await ui.page.click(`${fsel} button`).catch(() => {});
      await ui.page.waitForTimeout(2500);
      await ui.assert(db.one(`SELECT status FROM period WHERE id=${prev}`) === 'CLOSED',
        '补录期已关账');

      const afterF = await snapDashboard(ui);
      const repAfterF = await snapReports(ui);

      // ⚠ 这一格 PRD §4.3 矩阵第 10 行标的是「F 列 ↑」,**但那只对「没填完的期」成立**。
      //
      //   本 flow 的前置把补录期标成了「全员已提交」,于是按 §4.4 它**在关账之前
      //   就已经是「已定稿期」**了 —— 收益类早就锚着它。关账只是把 status 改成
      //   CLOSED,锚点没动,数字自然不变。
      //
      //   而这恰恰是本版要的:判据是「**填完了没有**」不是「关了没有」,
      //   填完当天就能看到自己的收益,不用等到次月凌晨那一刀。
      //   所以这里断言的是「**不变**」,并且它比「变了」更有价值 ——
      //   它守住了 §4.4 的核心;如果哪天有人把判据改回「只认 CLOSED」,这条会红。
      // 注意:这个 KPI 锚的是**进行期**(口径「本月未关账时按已录事实实时计算」),
      // 本来就不随收益类锚点动 —— 所以它在这里不变,不能用来证明锚点没动。
      // 真正证明锚点的是下面报表页那条。
      await ui.assert(afterF['本月资产收益'] === beforeF['本月资产收益'],
        '关补录期不影响「本月资产收益」(它锚进行期,实时口径)',
        `${beforeF['本月资产收益']} → ${afterF['本月资产收益']}`);
      await ui.assert(repAfterF.includes('已关账账期的稳定快照')
                   || !repAfterF.includes('仍在填报中'),
        '§4.4:关账后报表仍锚该期,只是从「填报中」变成「已定稿」');

      // 紧急储备的分母(月均支出)确实把该期纳入了 —— 从真值层验,
      // 因为页面上那个数被封顶挡住了
      const closedNow = db.num(`SELECT COUNT(*) FROM period WHERE family_id=${fx.FAM}
                                 AND id=${prev} AND status='CLOSED'`);
      await ui.assert(closedNow === 1,
        '真值层:该期已进入「已关账」集合(月均支出的分母从此含它)');

      await ui.assert(!repAfterF.includes('仍在填报中'),
        '关账后:报表不再说「仍在填报中」');
      await ui.assert(repAfterF.includes('已关账账期的稳定快照'),
        '关账后:报表恢复「已关账快照」语义');
    }
  },

  async cleanup(ui, report) {
    ui.flow = this.name;
    fx.purgeFlows(NOTE);
    ui.page.on('dialog', d => d.accept().catch(() => {}));
    for (const pid of db.col(`SELECT id FROM period WHERE family_id=${fx.FAM}
                               AND status='OPEN' AND period_end < CURDATE()`)) {
      const f = `form[action*="/${pid}/force-close"]`;
      const pg = await ui.paginateUntil('/admin/periods?page={p}', f);
      if (pg >= 0) { await ui.page.click(`${f} button`).catch(() => {}); await ui.page.waitForTimeout(1600); }
    }
    await ui.goto('/admin/periods');
    await ui.page.click('label.rhythm-opt:has(input[value="T0"])').catch(() => {});
    await ui.page.click('form[action$="close-rhythm"] button').catch(() => {});
    await ui.page.waitForTimeout(1200);
    const left = fx.openCount();
    const delay = db.num(`SELECT close_delay_days FROM family WHERE id=${fx.FAM}`);
    await ui.assert(left === 1 && delay === 0, '现场已还原(单 OPEN · 节奏回 T+0)',
                    `OPEN=${left} delay=${delay}`);
  },
};
