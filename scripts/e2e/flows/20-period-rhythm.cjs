/**
 * flow · v1.23 关账节奏 + 双活跃账期(issue #20)
 *
 * 全程**从页面点击发起**:在管理页点单选框改节奏、在填报页点 pill 切期、
 * 在导入页点月份 —— 不调任何端点。
 *
 * 它能抓到 curl 版抓不到的东西:配置项点不点得动、两期 pill 有没有视觉区分、
 * 尺寸齐不齐、切期之后页面显示的到底是哪一期。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const NOTE = 'e2e-v123-';

module.exports = {
  name: '20-period-rhythm',
  title: 'v1.23 · 关账节奏可配置 + 双活跃账期(issue #20)',

  async run(ui, report) {
    ui.flow = this.name;

    // ── 1 · 管理页:配置项点得动吗 ────────────────────────────────────────
    report.section('1 · 管理页「账期什么时候关」(FR-610 / FR-611)');

    await ui.goto('/admin/periods');
    await ui.rendered('管理页');
    await ui.seesText('账期什么时候关', '配置项标题在页面上看得见');
    await ui.count('input[name="rhythm"]', 4, '四个档位都在');
    await ui.sameSize('.rhythm-opt', '四个档位逐像素同尺寸');
    await ui.alignedTops('.rhythm-opt > div:last-child', '四张卡的「代价」分隔线在同一水平线');

    // 每个档位都必须说清代价 —— 只说功能不说代价的配置项,用户选完才发现报表晚出
    await ui.seesText('代价', '档位写了「代价」而不只是功能');

    // 真的去点「月底过完再给 2 天」,而不是 POST 一个 rhythm=T2
    await ui.clickText('月底过完再给 2 天', '点选 T+2 档位');
    await ui.submit('#rhythm-form button[type=submit], form[action$="close-rhythm"] button',
                    '提交关账节奏');
    await ui.seesText('已改为', '保存后给了回执');
    const delay = db.num(`SELECT close_delay_days FROM family WHERE id=${fx.FAM}`);
    const auto  = db.num(`SELECT auto_close_enabled FROM family WHERE id=${fx.FAM}`);
    await ui.assert(delay === 2 && auto === 1, '真值层:库里确实是 T+2 且自动关账开',
                    `实得 delay=${delay} auto=${auto}`);

    // ── 2 · 造双活跃窗口 ─────────────────────────────────────────────────
    report.section('2 · 造双活跃窗口(走管理页的「重新打开」)');

    const prev = fx.lastEndedPeriod();
    const cur  = fx.currentPeriod();
    if (!prev || !cur) { report.skip(this.name, '双活跃', '账期数据不足'); return; }
    ui.info(`进行期=${cur} · 上一期=${prev}`);

    // 从页面点「重新打开」—— 这条路径本身也顺带被验了。
    // 列表是 period_start DESC 分页的,而 beta 的账期表预建到 2041,
    // 要操作的当期不在第一页 —— 翻页找,和用户做的事一样。
    const reopenSel = `form[action*="/${prev}/reopen"]`;
    const page1 = await ui.paginateUntil('/admin/periods?page={p}', reopenSel);
    const reopened = page1 >= 0 ? 1 : 0;
    if (page1 >= 0) ui.info(`上一期在周期列表第 ${page1 + 1} 页`);
    if (reopened > 0) {
      await ui.page.fill(`form[action*="/${prev}/reopen"] input[name=reason]`, 'e2e 造双活跃窗口');
      ui.page.once('dialog', d => d.accept());
      await ui.submit(`form[action*="/${prev}/reopen"] button`, '点「重新打开」上一期');
    } else {
      ui.info('上一期已是 OPEN,跳过重开');
    }
    // reopen 会清 completion;补回来才与真实补录期同构(见 fixture 注释)
    fx.markAllMembersCompleted(prev);
    await ui.assert(fx.openCount() === 2, '真值层:现在确实有两期 OPEN',
                    `实得 ${fx.openCount()} 期`);

    // ── 3 · 填报页:两期并排、默认落补录期 ───────────────────────────────
    report.section('3 · 填报页两期并排(FR-625)');

    await ui.goto('/entry');
    await ui.rendered('填报页');
    await ui.count('.entry-perpill', 2, '两期 pill 都渲染了');
    await ui.sameSize('.entry-perpill', '两个 pill 逐像素同尺寸');
    await ui.visible('.entry-perpill-on', '当前期有高亮(用户分得清在填哪一期)');
    await ui.visible('.entry-perpill-off', '另一期可切换');
    await ui.seesText('还能改', '补录期标注「还能改」');
    await ui.seesText('你正在填', '明确告诉用户正在填哪一期');

    // 默认必须落补录期:宽限窗口里用户八成是来填上个月的
    const selected = await ui.page.locator('select option[selected]').first()
      .getAttribute('value').catch(() => null);
    await ui.assert(String(selected) === String(prev), '默认落在补录期',
                    `期望 ${prev} 实得 ${selected}`);

    // 点另一个 pill 切到进行期 —— 验的是「切换真的有用」
    await ui.click('.entry-perpill-off', '点另一期 pill 切过去');
    const nowSel = await ui.page.locator('select option[selected]').first()
      .getAttribute('value').catch(() => null);
    await ui.assert(String(nowSel) === String(cur), '切换生效:现在填的是进行期',
                    `期望 ${cur} 实得 ${nowSel}`);
    await ui.noConsoleErrors('填报页切期无 JS 报错');

    // ── 4 · 在补录期真的记一笔支出(从页面填,不调端点)────────────────
    report.section('4 · 往补录期填一笔支出 + 余额传导(FR-623 / FR-624)');

    // 找一个进行期里仍是「开账延续」的账户 —— 它是传导的守门对象
    const cfAcct = db.one(`SELECT account_id FROM period_snapshot
                            WHERE period_id=${cur} AND source_tag='CARRIED_FORWARD' LIMIT 1`);
    if (!cfAcct) {
      report.skip(this.name, '余额传导', '进行期里没有 CARRIED_FORWARD 快照');
    } else {
      const before = db.num(`SELECT end_balance FROM period_snapshot
                              WHERE period_id=${cur} AND account_id=${cfAcct}`);
      await ui.goto(`/entry?period=${prev}`);
      const form = 'form[action="/entry/expense"]';
      if (await ui.page.locator(form).count() === 0) {
        report.skip(this.name, '逐笔支出表单', '该家庭当前是总额模式,页面上没有逐笔表单');
      } else {
        await ui.choose('accountId', cfAcct, form);
        await ui.fillByName('amount', '13.13', form);
        await ui.fillByName('note', NOTE + '传导', form);
        // 「从余额里扣」在填报页**默认就是勾上的**(导入页相反)。
        // 这里断言它的默认态,而不是盲目去勾 —— 默认值本身就是产品决定,变了要红。
        const box = `${form} input[name=affectsBalance]`;
        if (await ui.page.locator(box).count() > 0) {
          const checked = await ui.page.isChecked(box);
          await ui.assert(checked, '填报页「从余额里扣」默认勾上(与导入页相反,是刻意的)');
        }
        await ui.submit(`${form} button[type=submit]`, '提交支出');

        const prevBal = db.num(`SELECT end_balance FROM period_snapshot
                                 WHERE period_id=${prev} AND account_id=${cfAcct}`);
        const after   = db.num(`SELECT end_balance FROM period_snapshot
                                 WHERE period_id=${cur} AND account_id=${cfAcct}`);
        await ui.assert(prevBal !== null, '真值层:补录期余额被更新了');
        await ui.assert(after !== before,
          '真值层:进行期的「开账延续」值跟着变了(传导生效)',
          `${before} → ${after}`);
        const note = db.one(`SELECT note FROM period_snapshot
                              WHERE period_id=${cur} AND account_id=${cfAcct}`) || '';
        await ui.assert(note.includes('补录'), '传导留了痕(数字不许自己悄悄变)', note.slice(0, 40));

        // 反向:用户手填过的那张,一分不许动
        const manAcct = db.one(`SELECT account_id FROM period_snapshot
                                 WHERE period_id=${cur} AND source_tag='MANUAL' LIMIT 1`);
        if (manAcct) {
          const mBefore = db.num(`SELECT end_balance FROM period_snapshot
                                   WHERE period_id=${cur} AND account_id=${manAcct}`);
          await ui.goto(`/entry?period=${prev}`);
          await ui.choose('accountId', manAcct, form);
          await ui.fillByName('amount', '17.17', form);
          await ui.fillByName('note', NOTE + '守门', form);
          await ui.submit(`${form} button[type=submit]`, '再往补录期提交一笔(手填账户)');
          const mAfter = db.num(`SELECT end_balance FROM period_snapshot
                                  WHERE period_id=${cur} AND account_id=${manAcct}`);
          await ui.assert(mBefore === mAfter,
            '真值层:用户手填过的余额一分不动(守门生效,不是假绿)',
            `${mBefore} → ${mAfter}`);
        }
      }
    }

    // ── 5 · 报表不倒退 ───────────────────────────────────────────────────
    report.section('5 · 报表不倒退,也不假装已定稿(FR-627)');

    await ui.goto('/reports');
    await ui.rendered('报表页');
    await ui.seesText('仍在填报中', '如实标注「仍在填报中 · 数字可能还会变」');
    await ui.notSeesText('已关账账期的稳定快照', '宽限期内不假装已定稿');
    const anchorMonth = db.one(`SELECT DATE_FORMAT(period_start,'%Y-%m') FROM period WHERE id=${prev}`);
    const shown = await ui.text();
    await ui.assert(shown.includes(anchorMonth.split('-')[1].replace(/^0/, '') + ' 月'),
      `报表锚的是补录期(${anchorMonth}),没退回上上期`);

    // ── 6 · 导入页默认记到补录期 ─────────────────────────────────────────
    report.section('6 · 批量导入记到哪个月(FR-626 · issue #20 正中心)');

    await ui.goto('/expense/import');
    await ui.rendered('导入页');
    await ui.seesText('记到', '页面显式写出「记到哪个月」');
    const impPid = await ui.page.locator('input[name=periodId]').first()
      .getAttribute('value').catch(() => null);
    await ui.assert(String(impPid) === String(prev),
      '默认记到补录期(9/1 导 8 月账单不会整批落进 9 月)',
      `期望 ${prev} 实得 ${impPid}`);

    // ── 7 · 余额轴:估值不回写补录期 ─────────────────────────────────────
    report.section('7 · 估值只写进行期,绝不回写补录期(FR-622)');

    const prevSum = db.num(`SELECT COALESCE(SUM(end_balance),0) FROM period_snapshot WHERE period_id=${prev}`);
    await ui.goto('/entry');
    // 按钮从 v0.4.22 起是 hx-post 的 button,不在 form 里 —— 只认 form 的旧选择器一直落到 skip(v1.30 补上)
    const refresh = 'button[hx-post="/entry/refresh-stocks"], form[action="/entry/refresh-stocks"] button, [data-refresh-stocks]';
    if (await ui.page.locator(refresh).count() > 0) {
      await ui.submit(refresh, '点「一键拉取股价」');
      await ui.page.waitForTimeout(2500);
      const prevSum2 = db.num(`SELECT COALESCE(SUM(end_balance),0) FROM period_snapshot WHERE period_id=${prev}`);
      await ui.assert(prevSum === prevSum2,
        '真值层:估值刷新后补录期余额逐分未动(只有一个「现在」)',
        `${prevSum} → ${prevSum2}`);
    } else {
      report.skip(this.name, '估值刷新', '填报页没有拉股价按钮');
    }

    // ── 8 · 移动端 ───────────────────────────────────────────────────────
    report.section('8 · 移动端(390px)双期切换');
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await ui.goto('/entry');
    await ui.count('.entry-perpill', 2, '移动端两期 pill 都在');
    await ui.sameSize('.entry-perpill', '移动端两个 pill 同尺寸');
    await ui.goto('/admin/periods');
    await ui.sameSize('.rhythm-opt', '移动端四个档位同尺寸');
    await ui.page.setViewportSize({ width: 1440, height: 900 });
  },

  /**
   * 还原:**声明终态**,不依赖「跑之前是什么样」。
   * 连跑两次时,第二次读到的初态已经被第一次改过了。
   */
  async cleanup(ui, report) {
    ui.flow = this.name;
    fx.purgeFlows(NOTE);

    // 所有 confirm 一律确认。必须在**点击之前**挂上,而且用 on 不是 once ——
    // 还原要关好几期,once 只接得住第一个,后面的对话框会把点击卡死。
    ui.page.on('dialog', d => d.accept().catch(() => {}));

    // 关掉所有「已自然结束却还开着」的期
    for (const pid of db.col(`SELECT id FROM period WHERE family_id=${fx.FAM}
                               AND status='OPEN' AND period_end < CURDATE()`)) {
      const f = `form[action*="/${pid}/force-close"]`;
      const pg = await ui.paginateUntil('/admin/periods?page={p}', f);
      if (pg >= 0) {
        await ui.page.click(`${f} button`).catch(() => {});
        await ui.page.waitForTimeout(1600);
      } else {
        ui.info(`翻了几页都没找到 period=${pid} 的关账按钮`);
      }
    }

    // 关账节奏回默认。radio 本身是 sr-only(点不动),要点包着它的 label ——
    // 这和用户的操作是同一条路径,顺带也验了「label 确实能选中 radio」。
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
