/**
 * flow · v1.27 · issue #23 · 分析范围:从体检那张一直亮着的卡去标、切三个范围、全都标了
 *
 * PRD 验收 #2 #3 #13:「单一类型过半」命中房产 → 按钮直达勾选项 → 勾上保存 → 体检只按「可调整的资产」算占比,
 * 净资产一分不少;切到「全部资产」、设为家里的默认;全都标了 → 空态,不退回按全部算,AI 不调用。
 *
 * 造前提的一处例外(fixture 纪律):要让「房产过半」在 beta 上成立,得先把现金 / 理财 / 股票账户标成不参与 ——
 * 页面上逐个进十几个账户的编辑页去勾,和这条 flow 要测的动作(从体检卡片点进去标房产)无关、只是慢,所以这几个用库造;
 * **房产那一个必须从页面上点**。跑完按「声明终态」还原:所有账户的标记回到跑之前的样子,默认范围回到没设。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const state = {};
const STUB = r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' });

const scopePills = ui => ui.page.locator('#analysis-scope a.scope-pill')
  .evaluateAll(as => as.map(a => ({ kind: a.getAttribute('data-scope'), on: a.classList.contains('on') })));
const allocLabels = ui => ui.page.locator('#allocation .font-display')
  .evaluateAll(es => es.map(e => e.textContent.replace(/\s+/g, ' ').trim()));
const netWorth = ui => ui.page.locator('#checkup-overview .kpi-card').first().locator('.kpi-value').innerText();
const cfg = () => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='analysis_scope_default'`);

module.exports = {
  name: '30-analysis-scope',
  title: 'v1.27 · 分析范围:从体检卡片标房产 → 可调整的资产 → 全部资产 / 设为默认 → 全都标了是空态',

  async run(ui, report) {
    ui.flow = this.name;
    await ui.page.route(/\/checkup\/(diagnose|insight)/, STUB);   // 这条不测 AI 文案,别花 token
    state.marks = db.raw(`SELECT id, analysis_excluded FROM account WHERE family_id=${fx.FAM}`);
    state.cfg = cfg();
    db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='analysis_scope_default'`);
    db.raw(`UPDATE account SET analysis_excluded=0 WHERE family_id=${fx.FAM}`);
    await ui.page.waitForTimeout(5500);   // 应用的家庭配置有 5 秒缓存:库里刚删的值,等它过期再开页面
    const house = db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND type='PROPERTY' AND archived_at IS NULL ORDER BY id LIMIT 1`);
    const houseName = db.one(`SELECT display_name FROM account WHERE id=${house}`);
    if (!house) { report.skip(this.name, '全部', 'beta 上没有房产账户'); return; }

    // ── 1 · 没标记:只有「金融资产 · 全部资产」两个选项,默认全部资产 ─────────────
    report.section('1 · 没标记的家庭');
    await ui.goto('/');
    await ui.click('nav a:has-text("资产体检")', '顶部导航点「资产体检」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    await ui.rendered('体检页');
    const nw0 = await netWorth(ui);
    let pills = await scopePills(ui);
    await ui.assert(pills.map(p => p.kind).join(',') === 'FINANCIAL,ALL', '没标记:范围选项只有「金融资产 · 全部资产」(没有可调整)', JSON.stringify(pills));
    await ui.assert(pills.find(p => p.kind === 'ALL')?.on, '默认是「全部资产」(与 v1.26 一样)');
    await ui.assert((await allocLabels(ui)).some(l => l.startsWith('房产')), '资产配置卡里有房产');

    // ── 2 · 「单一类型过半」命中房产 → 按钮直达勾选项 → 勾上保存 ─────────────
    report.section('2 · 从体检卡片去标房产(PRD FR-804 主入口)');
    db.raw(`UPDATE account SET analysis_excluded=1 WHERE family_id=${fx.FAM} AND type IN ('CASH','WEALTH','STOCK')`);
    await ui.page.reload({ waitUntil: 'networkidle' });
    const cta = ui.page.locator('#checkup-advice article[data-rule="FAM-CON-1"] a[data-advice-cta]');
    await ui.visible('#checkup-advice article[data-rule="FAM-CON-1"]', '「类目集中度偏高」这张卡亮着(房产过半)');
    const ctaText = (await cta.innerText().catch(() => '')).trim();
    const ctaHref = await cta.getAttribute('href').catch(() => null);
    await ui.assert(ctaText === '标成不参与配置分析 →', '卡片上的行动按钮是「标成不参与配置分析 →」', ctaText);
    await ui.assert(ctaHref === `/accounts/${house}/edit#analysis-excluded`, '按钮直达这套房的编辑页勾选项', ctaHref);
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('#checkup-advice article[data-rule="FAM-CON-1"] a[data-advice-cta]', '点「标成不参与配置分析 →」')]);
    await ui.page.waitForTimeout(600);
    const box = await ui.page.evaluate(() => {
      const el = document.getElementById('analysis-excluded');
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { top: Math.round(r.top), vh: innerHeight, path: location.pathname };
    });
    await ui.assert(box && box.path === `/accounts/${house}/edit` && box.top >= 0 && box.top < box.vh,
      '落在编辑页,勾选项就在眼前', JSON.stringify(box));
    await ui.seesText('它仍然计入净资产', '勾选项说明写明「它仍然计入净资产」');
    await ui.page.check('#analysis-excluded input[name="analysisExcluded"]');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('button:has-text("保存对账户的修改")', '点「保存对账户的修改」')]);
    await ui.visible(`a[data-excluded-badge][href="/accounts/${house}/edit#analysis-excluded"]`, '账户列表里这套房旁边出现「不参与分析」');
    await ui.assert(db.num(`SELECT analysis_excluded FROM account WHERE id=${house}`) === 1, '真值层:account.analysis_excluded = 1');
    await ui.assert(db.num(`SELECT COUNT(*) FROM audit_log WHERE family_id=${fx.FAM} AND summary LIKE '标成不参与配置分析%'
                             AND created_at > NOW() - INTERVAL 2 MINUTE`) >= 1, '真值层:审计里记了一笔「标成不参与配置分析」');

    // 贷款不出现这一项
    const loan = db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND type='LOAN' AND archived_at IS NULL LIMIT 1`);
    if (loan) {
      await ui.goto(`/accounts/${loan}/edit`);
      await ui.notVisible('[data-analysis-excluded-box]', '贷款账户的编辑页没有「不参与配置分析」');
    }

    // ── 3 · 回到体检:默认变成「可调整的资产」,占比不含房产,净资产一分不少 ─────────
    report.section('3 · 可调整的资产');
    await ui.goto('/checkup');
    pills = await scopePills(ui);
    await ui.assert(pills.map(p => p.kind).join(',') === 'ADJUSTABLE,FINANCIAL,ALL', '三个选项都出现了', JSON.stringify(pills));
    await ui.assert(pills.find(p => p.kind === 'ADJUSTABLE')?.on, '有标记 → 家里的默认自动是「可调整的资产」');
    await ui.sameSize('#analysis-scope a.scope-pill', '三个范围选项同尺寸');
    const labels = await allocLabels(ui);
    await ui.assert(!labels.some(l => l.startsWith('房产')), '资产配置卡不再有房产', labels.join(' | '));
    const note = (await ui.page.locator('#allocation [data-scope-note]').innerText().catch(() => '')).trim();
    await ui.assert(note.startsWith('不含:') && note.includes(houseName), '配置卡标题旁写明「不含:」拿掉了谁', note);
    await ui.visible('#risk [data-scope-note]', '风险敞口卡也写明「不含」');
    await ui.assert((await netWorth(ui)) === nw0, '净资产一分不少(FR-801)', `${nw0} → ${await netWorth(ui)}`);
    await ui.seesText('净资产、收益、流动性始终按全部资产算。', '切换下方说明一次');

    await ui.goto(`/checkup?account=${house}`);
    await ui.visible('[data-account-excluded-note]', '这套房的账户体检页头写着「不参与家庭配置分析 · 净资产照常计入 · 改」');
    const acctRules = await ui.page.locator('#checkup-advice article.advice-card').evaluateAll(es => es.map(e => e.getAttribute('data-rule')));
    await ui.assert(!acctRules.includes('RISK-1'), '被标记的账户不再报「集中度告警 → 划转」', acctRules.join(','));

    // 手机:三个选项上下排、同尺寸、不溢出
    await ui.page.setViewportSize({ width: 390, height: 844 });
    await ui.goto('/checkup');
    await ui.sameSize('#analysis-scope a.scope-pill', '手机上三个范围选项仍然同尺寸');
    const ov = await ui.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    await ui.assert(ov <= 2, '手机无横向溢出', `溢出 ${ov}px`);
    await ui.page.setViewportSize({ width: 1440, height: 900 });

    // ── 4 · 临时切到「全部资产」→ 设为家里的默认 ─────────────────────────────
    report.section('4 · 临时切换 + 设为家里的默认');
    await ui.goto('/checkup');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('#analysis-scope a.scope-pill[data-scope="ALL"]', '点「全部资产」')]);
    await ui.assert(new URL(ui.page.url()).searchParams.get('scope') === 'ALL', '临时切换记在网址上(?scope=ALL)', ui.page.url());
    await ui.assert((await allocLabels(ui)).some(l => l.startsWith('房产')), '全部资产:房产回到配置卡');
    await ui.assert(cfg() === null, '真值层:只是临时切换,家里的默认没变');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('[data-scope-set-default]', '点「把「全部资产」设为家里的默认」')]);
    await ui.assert(cfg() === 'ALL', '真值层:家里的默认范围 = ALL', String(cfg()));
    pills = await scopePills(ui);
    await ui.assert(pills.find(p => p.kind === 'ALL')?.on, '回到体检页,默认就是「全部资产」');

    // ── 5 · 全都标了:空态,不退回全部资产,AI 不调用 ─────────────────────────
    report.section('5 · 全都标了(PRD FR-826)');
    db.raw(`UPDATE account SET analysis_excluded=1 WHERE family_id=${fx.FAM} AND type<>'LOAN'`);
    await ui.page.unroute(/\/checkup\/(diagnose|insight)/).catch(() => {});
    /* v1.37 · harness 在整个浏览器上下文默认拦 AI;这一步要的是服务端真实回应 ——
       全都标了「不参与」时服务端走「这次不分析」早返回,不会调模型,所以这里放行,验完再拦回去 */
    const { AI_ROUTE, stubAi } = require('../lib/browser.cjs');
    await ui.page.context().unroute(AI_ROUTE).catch(() => {});
    const INSIGHT = /\/checkup\/insight/;   // 资产洞察没有「不分析」早返回,照旧拦着,只放行诊断
    await ui.page.route(INSIGHT, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div></div>' }));
    await ui.goto('/checkup?scope=ADJUSTABLE');
    await ui.visible('#allocation [data-scope-empty]', '资产配置卡是空态「所有资产都标成了不参与配置分析」');
    const famRules = await ui.page.locator('#checkup-advice article.advice-card').evaluateAll(es => es.map(e => e.getAttribute('data-rule')));
    await ui.assert(!famRules.some(r => /^FAM-(CON|ALC|RISK)|^LENS-CON/.test(r)), '配置类规则一条都不亮(没有退回按全部资产算)', famRules.join(','));
    await ui.page.waitForSelector('#ai-diagnose-panel[data-vendor]', { timeout: 60000 }).catch(() => {});
    const vendor = await ui.page.locator('#ai-diagnose-panel').getAttribute('data-vendor').catch(() => null);
    await ui.assert(vendor === 'skipped', 'AI 综合诊断没有调用模型(「这次不分析」)', String(vendor));
    await stubAi(ui.page.context());
    await ui.page.unroute(INSIGHT).catch(() => {});
    await ui.goto('/reports?scope=ADJUSTABLE');
    await ui.visible('#allocation-diff [data-scope-empty]', '报表配置锚对照同样是空态');
    await ui.noConsoleErrors('体检 / 报表控制台无报错');
  },

  async cleanup(ui) {
    await ui.page.unroute(/\/checkup\/(diagnose|insight)/).catch(() => {});
    // 声明终态:每个账户的标记回到跑之前;默认范围回到跑之前(没有就删掉)
    for (const line of String(state.marks || '').split('\n').filter(Boolean)) {
      const [id, v] = line.split('\t');
      db.raw(`UPDATE account SET analysis_excluded=${Number(v) ? 1 : 0} WHERE id=${Number(id)} AND family_id=${fx.FAM}`);
    }
    db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='analysis_scope_default'`);
    if (state.cfg) {
      db.raw(`INSERT INTO family_runtime_config(family_id, key_name, value_text) VALUES (${fx.FAM}, 'analysis_scope_default', '${state.cfg}')`);
    }
  },
};
