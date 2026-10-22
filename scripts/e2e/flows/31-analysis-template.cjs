/**
 * flow · v1.27 · issue #23 · 分析模板:选内置模板 → 从 AI 结果页脚「定制这个模板」→ 改立场 → 保存 → 回到体检用新模板当场重跑
 *
 * PRD 验收 #17 #18 #20 · FR-845 / FR-848 / FR-880 ~ FR-884:
 *   模板选择行、切模板记在网址上、页脚写明这次用的模板;定制页预填、「AI 会收到的要求」随设置实时变;
 *   存的是完整副本(来源 key / 版本只作记录);首次提示按人记;分析设置里设默认、删除。
 *
 * AI 区块用真的(不 stub):页脚、请求带的模板都要从真的面板里读。模型挂了页脚照样渲染,所以断言不依赖模型输出。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const state = {};
const NAME = '我家的稳健守护';
const EXTRA = 'e2e · 重点看港股仓位和汇率风险';
const cfg = k => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);

async function waitFooter(ui, panel) {
  await ui.page.waitForSelector(`${panel} [data-analysis-foot]`, { timeout: 120000 }).catch(() => {});
  return (await ui.page.locator(`${panel} [data-analysis-label]`).innerText().catch(() => '')).trim();
}

module.exports = {
  realAi: true,   // v1.37 · 这条要真调大模型(AI 区块用真的),默认跳过,E2E_REAL_AI=1 才跑
  name: '31-analysis-template',
  title: 'v1.27 · 分析模板:内置模板 → 基于它定制 → 保存并当场重跑 · 首次提示按人记 · 设为默认 / 删除',

  async run(ui, report) {
    ui.flow = this.name;
    state.maxId = db.num(`SELECT COALESCE(MAX(id),0) FROM analysis_template`);
    state.tplDefault = cfg('analysis_template_default');
    state.hint = cfg('analysis_hint_dismissed');
    db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name IN ('analysis_template_default','analysis_hint_dismissed')`);
    await ui.page.waitForTimeout(5500);   // 应用的家庭配置有 5 秒缓存:库里刚删的值,等它过期再开页面
    const me = db.one(`SELECT id FROM member WHERE family_id=${fx.FAM} AND username='${process.env.E2E_USER || 'diwa'}'`);
    const aiReqs = [];
    ui.page.on('request', r => { if (/\/checkup\/(diagnose|insight)/.test(r.url())) aiReqs.push(r.url()); });

    // ── 1 · 模板选择行 ───────────────────────────────────────────────
    report.section('1 · 体检页 AI 区顶部的模板选择');
    await ui.goto('/checkup');
    await ui.rendered('体检页');
    await ui.visible('#analysis-templates', '有「分析模板」一行');
    const chips = await ui.page.locator('#analysis-templates a[data-tpl-chip]').evaluateAll(as => as.map(a => a.getAttribute('data-tpl')));
    await ui.assert(['GENERAL', 'STEADY', 'GROWTH', 'PORTFOLIO', 'DEBT'].every(k => chips.includes(k)), '内置 5 个模板都在', chips.join(','));
    await ui.visible('#analysis-templates a[data-tpl-customize]', '最后一项「+ 基于当前模板定制…」');
    await ui.visible('[data-analysis-hint]', '第一次看到 AI 分析:下面有一句可关闭的提示');
    let label = await waitFooter(ui, '#ai-diagnose-panel');
    await ui.assert(label.startsWith('按「综合体检」'), 'AI 页脚写明这次按「综合体检」(家里的默认)', label);

    // ── 2 · 换成「稳健守护」(记在网址上)───────────────────────────────
    report.section('2 · 换模板');
    aiReqs.length = 0;
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('#analysis-templates a[data-tpl="STEADY"]', '点「稳健守护」')]);
    await ui.assert(new URL(ui.page.url()).searchParams.get('tpl') === 'STEADY', '换模板记在网址上(?tpl=STEADY)', ui.page.url());
    await ui.seesText('适合求稳、临近退休', '下面一句「适合谁」');
    label = await waitFooter(ui, '#ai-diagnose-panel');
    await ui.assert(label.startsWith('按「稳健守护」'), 'AI 综合诊断页脚:按「稳健守护」', label);
    await ui.assert(aiReqs.some(u => u.includes('/checkup/diagnose') && u.includes('tpl=STEADY')), 'AI 区块确实带着这个模板去请求', aiReqs.join(' | '));
    await ui.assert(cfg('analysis_template_default') === null, '真值层:只是临时换,家里的默认模板没变');

    // ── 3 · 页脚「定制这个模板 →」→ 定制页 ─────────────────────────────
    report.section('3 · 基于它定制(FR-843 / FR-883)');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('#ai-diagnose-panel a[data-analysis-customize]', '点 AI 页脚「定制这个模板 →」')]);
    await ui.seesText('基于「稳健守护」定制', '定制页标题');
    await ui.assert((await ui.page.inputValue('[data-tpl-name]')) === NAME, '名字预填「我家的稳健守护」');
    await ui.assert(await ui.page.isChecked('input[name=stance][value=CONSERVATIVE]'), '立场预填「稳健」');
    const focus = await ui.page.locator('input[name=focus]:checked').evaluateAll(es => es.map(e => e.value));
    await ui.assert(focus.join(',') === 'RISK,LIQUIDITY,CONCENTRATION', '侧重预填(风险 · 流动性 · 集中度)', focus.join(','));
    await ui.assert((await ui.page.locator('#tpl-preview').innerText()).includes('立场:稳健'), '右侧「AI 会收到的要求」按预填显示');
    await ui.page.check('input[name=stance][value=BALANCED]');
    await ui.page.waitForFunction(() => document.querySelector('#tpl-preview').innerText.includes('立场:平衡'), null, { timeout: 15000 }).catch(() => {});
    await ui.assert((await ui.page.locator('#tpl-preview').innerText()).includes('立场:平衡'), '改成「平衡」,右侧要求跟着变');
    await ui.page.click('[data-tpl-advanced] summary');
    await ui.fill('[data-tpl-extra]', EXTRA, '高级 · 补充要求');
    await ui.page.waitForFunction(t => document.querySelector('#tpl-preview').innerText.includes(t), EXTRA, { timeout: 15000 }).catch(() => {});
    await ui.assert((await ui.page.locator('#tpl-preview').innerText()).includes(EXTRA), '补充要求出现在「AI 会收到的要求」里');

    // v1.27.1 · 「范围」不再是三个名词的下拉:每个选项先说人话、再说「什么情况选它」、再说「对你家去掉了谁」
    const scopeCards = await ui.page.locator('[data-tpl-scope] [data-tpl-scope-option]').evaluateAll(ls => ls.map(l => l.getAttribute('data-tpl-scope-option')));
    await ui.assert(scopeCards.join(',') === ',ALL,FINANCIAL,ADJUSTABLE', '范围四张说明卡:跟默认 / 全都算 / 不看房子和车 / 去掉标过的(从宽到窄)', scopeCards.join(','));
    await ui.notVisible('select[name=scope]', '不再是只写三个名词的下拉');
    await ui.seesText('不想看不动产的分布', '「不看房子和车」那张直接写了什么时候选它');
    const hasProperty = db.num(`SELECT COUNT(*) FROM account WHERE family_id=${fx.FAM} AND type IN ('PROPERTY','OTHER') AND archived_at IS NULL`) > 0;
    if (hasProperty) {
      const fact = (await ui.page.locator('[data-tpl-scope-option="FINANCIAL"] [data-scope-fact]').innerText()).trim();
      await ui.assert(/^对你家:不含 .+ · 占总资产 \d+%$/.test(fact), '写明对你家去掉了谁、占多少', fact);
    }
    await ui.click('[data-tpl-scope-option="FINANCIAL"]', '点「不看房子和车」那张卡(点卡片任意处都算选中)');
    await ui.assert(await ui.page.isChecked('input[name=scope][value=FINANCIAL]'), '选中了「金融资产」');
    const anchorCards = await ui.page.locator('[data-tpl-anchor] [data-tpl-anchor-option]').count();
    await ui.assert(anchorCards >= 3, '配置锚也是说明卡(跟家里的 / 各套锚 / 自定义),各写着目标比例', String(anchorCards));
    await ui.seesText('现金 10% · 投资 30% · 房产 40% · 保险 20%', '标普 4321 那张直接写出四个目标数');

    aiReqs.length = 0;
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('[data-tpl-save]', '点「保存并重新分析」')]);
    const row = db.raw(`SELECT id, source_key, stance, focus, extra, scope FROM analysis_template
                          WHERE family_id=${fx.FAM} AND id > ${state.maxId} AND name='${NAME}' ORDER BY id DESC LIMIT 1`).split('\t');
    const newKey = `custom:${row[0]}`;
    await ui.assert(row[1] === 'STEADY' && row[2] === 'BALANCED' && row[3] === 'RISK,LIQUIDITY,CONCENTRATION' && (row[4] || '').includes('港股'),
      '真值层:存了一份完整副本(来源 STEADY · 立场 BALANCED · 侧重三项 · 补充要求)', row.join(' | '));
    await ui.assert(row[5] === 'FINANCIAL', '真值层:范围存成 FINANCIAL(点卡片就是选中)', row[5]);
    await ui.assert(new URL(ui.page.url()).pathname === '/checkup' && new URL(ui.page.url()).searchParams.get('tpl') === newKey,
      '保存后回到体检页,并换上新模板', ui.page.url());
    await ui.visible('[data-template-flash]', '顶上一句「已按「我家的稳健守护」重新分析」');
    label = await waitFooter(ui, '#ai-diagnose-panel');
    await ui.assert(label.startsWith(`按「${NAME}」`), 'AI 综合诊断当场按新模板跑了', label);
    await ui.assert(aiReqs.some(u => u.includes('/checkup/diagnose') && decodeURIComponent(u).includes(`tpl=${newKey}`)), '请求带的是新模板', aiReqs.join(' | '));
    const insightLabel = await waitFooter(ui, '#ai-insight-panel');
    await ui.assert(insightLabel.startsWith(`按「${NAME}」`), 'AI 资产洞察也按新模板', insightLabel);

    // ── 4 · 首次提示:关掉就不再出现,只对关掉的这个人(FR-881)──────────────
    report.section('4 · 首次提示按人记');
    await ui.click('[data-analysis-hint-close]', '点提示上的「关掉 ×」');
    await ui.page.waitForTimeout(800);
    await ui.notVisible('[data-analysis-hint]', '提示消失了');
    const dismissed = (cfg('analysis_hint_dismissed') || '').split(',');
    await ui.assert(dismissed.includes(String(me)) && dismissed.length === 1, '真值层:只记了这个成员', dismissed.join(','));
    await ui.page.reload({ waitUntil: 'networkidle' });
    await ui.notVisible('[data-analysis-hint]', '刷新后仍然不出现');

    // ── 5 · 管理 → 分析设置:设为默认、删除 ─────────────────────────────
    report.section('5 · 分析设置里管理模板(FR-884 / FR-890)');
    await ui.goto('/admin');
    const card = (await ui.page.locator('a[data-admin-card="analysis"]').innerText().catch(() => ''));
    await ui.assert(['分析范围', '分析模板', '不参与分析的资产', '配置锚', '分析偏好'].every(w => card.includes(w)),
      '管理首页「分析设置」卡片点名五块', card.replace(/\s+/g, ' '));
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('a[data-admin-card="analysis"]', '点「分析设置」卡片')]);
    await ui.visible(`tr[data-template="${newKey}"]`, '模板列表里有这份「我的模板」');
    await ui.assert((await ui.page.locator(`tr[data-template="${newKey}"]`).innerText()).includes('基于「稳健守护」'), '写明基于哪个模板、谁改的');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click(`tr[data-template="${newKey}"] button[data-set-default-template]`, '点「设为默认」')]);
    await ui.assert(cfg('analysis_template_default') === newKey, '真值层:家里的默认模板 = 这一份', String(cfg('analysis_template_default')));
    await ui.goto('/checkup');
    const onChip = await ui.page.locator('#analysis-templates a.tpl-chip.on').getAttribute('data-tpl').catch(() => null);
    await ui.assert(onChip === newKey, '回到体检不带参数,选中的就是家里的默认', String(onChip));
    await ui.goto('/admin/analysis');
    ui.page.once('dialog', d => d.accept().catch(() => {}));   // 前面的 flow 可能已在同一个页面上挂了自动确认,这里重复确认会抛错
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click(`tr[data-template="${newKey}"] button:has-text("删除")`, '点「删除」')]);
    await ui.assert(db.num(`SELECT COUNT(*) FROM analysis_template WHERE id=${row[0]}`) === 0, '真值层:删掉了');
    await ui.assert(cfg('analysis_template_default') === 'GENERAL', '删掉的是默认 → 默认回到「综合体检」', String(cfg('analysis_template_default')));
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup() {
    db.raw(`DELETE FROM analysis_template WHERE family_id=${fx.FAM} AND id > ${Number(state.maxId || 0)}`);
    for (const [k, v] of [['analysis_template_default', state.tplDefault], ['analysis_hint_dismissed', state.hint]]) {
      db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
      if (v) db.raw(`INSERT INTO family_runtime_config(family_id, key_name, value_text) VALUES (${fx.FAM}, '${k}', '${v}')`);
    }
  },
};
