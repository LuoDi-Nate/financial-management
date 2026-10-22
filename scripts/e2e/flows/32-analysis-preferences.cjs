/**
 * flow · v1.27 · issue #23 · 分析偏好:在分析设置里写一条 → 超级 Agent 下一句话就带上(托管模式也一样)→ 对话里「提议记住」只有点了才存
 *
 * PRD 验收 #8 #9 #10 · FR-850 ~ FR-857 · FR-849:
 *   · 分析设置 ⑤ 加 / 停用 / 启用一条偏好,谁在什么时候加的
 *   · 超级 Agent 真问一句:这一轮的用户消息上记着「偏好 N 条 · …」;托管模式下是百炼的回显确认过的(不用去点「更新 Agent」)
 *   · 确认卡:原文可改 → 「记住」→ 库里多一条来源 AGENT 的偏好;「切到「稳健守护」」→ 家里的默认模板变了;
 *     说「别算某个资产」时卡片给出那个账户的「标成不参与配置分析 →」
 *
 * 造前提的一处例外(fixture 纪律):模型会不会在回答里写「提议记住」的标记是随机的,
 * 确认卡的渲染与保存用两条构造的助手回答驱动(直接插进这段对话);卡片上的每个动作都从页面点。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const state = {};
const PREF = 'e2e偏好 · 房子是自住的,别建议卖';
const cfg = k => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
const esc = s => s.replace(/\\/g, '\\\\').replace(/'/g, "\\'");

module.exports = {
  realAi: true,   // v1.37 · 这条要真调大模型(AI 区块用真的),默认跳过,E2E_REAL_AI=1 才跑
  name: '32-analysis-preferences',
  title: 'v1.27 · 分析偏好:设置页写一条 → 超级 Agent 下一句带上(回显确认)→ 确认卡只有点了才存',

  async run(ui, report) {
    ui.flow = this.name;
    state.maxPref = db.num(`SELECT COALESCE(MAX(id),0) FROM analysis_preference`);
    state.maxConv = db.num(`SELECT COALESCE(MAX(id),0) FROM ask_conversation`);
    state.tplDefault = cfg('analysis_template_default');

    // ── 1 · 分析设置 ⑤ ───────────────────────────────────────────────
    report.section('1 · 分析设置里写一条分析偏好');
    await ui.goto('/admin');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('a[data-admin-card="analysis"]', '管理首页点「分析设置」')]);
    await ui.rendered('分析设置页');
    await ui.visible('#preferences', '有「⑤ 分析偏好」');
    await ui.seesText('会原样发给你配置的大模型,别写账号、身份证这类信息', '写明会发给大模型');
    await ui.fill('[data-pref-form] textarea[name=content]', PREF, '写一条偏好');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('[data-pref-form] button[type=submit]', '点「+ 加一条」')]);
    const pid = db.num(`SELECT id FROM analysis_preference WHERE family_id=${fx.FAM} AND id > ${state.maxPref} AND content='${esc(PREF)}'`);
    await ui.assert(pid > 0, '真值层:库里多了这一条(来源 SETTINGS · 启用)',
      db.raw(`SELECT source, enabled FROM analysis_preference WHERE id=${pid || 0}`));
    await ui.seesText(PREF, '列表里看得到这一条');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click(`form[action="/admin/analysis/preferences/${pid}/toggle"] button`, '点「停用」')]);
    await ui.assert(db.num(`SELECT enabled FROM analysis_preference WHERE id=${pid}`) === 0, '真值层:停用了');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click(`form[action="/admin/analysis/preferences/${pid}/toggle"] button`, '点「启用」')]);
    await ui.assert(db.num(`SELECT enabled FROM analysis_preference WHERE id=${pid}`) === 1, '真值层:又启用了');
    const enabledCnt = db.num(`SELECT COUNT(*) FROM analysis_preference WHERE family_id=${fx.FAM} AND enabled=1`);

    // ── 2 · 超级 Agent 下一句话就带上 ──────────────────────────────────
    report.section('2 · 超级 Agent:下一句话就带上偏好(FR-853)');
    await ui.goto('/ask');
    const blocked = await ui.page.isVisible('.ask-composer-wrap.is-off').catch(() => false);
    let convId = null;
    if (blocked) {
      report.skip(this.name, '超级 Agent 真问一句', '这台机器上超级 Agent 没开 / 没配模型');
    } else {
      const runtime = cfg('ask_runtime') || 'managed';
      await ui.fill('[data-ask-input]', 'e2e · 用一句话说说我们家的钱主要放在哪几处', '问一句');
      await ui.click('[data-ask-send]', '点发送');
      await ui.page.waitForFunction(() => {
        const stop = document.querySelector('[data-ask-stop]');
        return document.querySelector('[data-ask-live] .ask-tools-row, [data-ask-live] .ask-note') && stop && stop.hidden;
      }, null, { timeout: 180000 }).catch(() => {});
      convId = db.num(`SELECT MAX(id) FROM ask_conversation WHERE family_id=${fx.FAM}`);
      const note = db.one(`SELECT m.ctx_note FROM ask_message m WHERE m.conversation_id=${convId} AND m.role='user' ORDER BY m.seq DESC LIMIT 1`) || '';
      await ui.assert(note.startsWith(`偏好 ${enabledCnt} 条`), '真值层:这一问带上了分析偏好(ask_message.ctx_note)', note);
      if (runtime === 'managed') {
        await ui.assert(note.endsWith('百炼回显确认'), '托管模式:百炼的回显里确实有这段上下文 —— 不用去点「更新 Agent」', note);
      } else {
        await ui.assert(note.includes('本机'), '本机模式:随系统提示词发出', note);
      }
    }

    // ── 3 · 确认卡:只有点了才存(FR-854 / FR-849)─────────────────────
    report.section('3 · 「记住这条分析偏好?」确认卡');
    if (!convId) {
      convId = db.num(`SELECT MAX(id) FROM ask_conversation WHERE family_id=${fx.FAM} AND id > ${state.maxConv}`) || null;
    }
    if (!convId) {
      // 超级 Agent 没开的机器上问不了 —— 这一段只验确认卡,会话和下面的回答一样是垫进去的数据
      db.raw(`INSERT INTO ask_conversation(family_id, title) VALUES (${fx.FAM}, 'e2e · 确认卡')`);
      convId = db.num(`SELECT MAX(id) FROM ask_conversation WHERE family_id=${fx.FAM}`);
    }
    const house = db.one(`SELECT display_name FROM account WHERE family_id=${fx.FAM} AND type='PROPERTY' AND archived_at IS NULL ORDER BY id LIMIT 1`);
    const houseId = db.one(`SELECT id FROM account WHERE family_id=${fx.FAM} AND type='PROPERTY' AND archived_at IS NULL ORDER BY id LIMIT 1`);
    const seq = db.num(`SELECT COALESCE(MAX(seq),0) FROM ask_message WHERE conversation_id=${convId}`);
    db.raw(`INSERT INTO ask_message(conversation_id, role, content_text, seq) VALUES
      (${convId}, 'assistant', '好的。\\n{{remember:e2e记住 · 我们家的车不打算卖||exclude=${esc(house || '')}}}', ${seq + 1}),
      (${convId}, 'assistant', '明白。\\n{{remember:e2e记住 · 以后都按保守的来|STEADY}}', ${seq + 2})`);
    await ui.goto(`/ask?conv=${convId}`);
    await ui.count('[data-remember-card]', 2, '两张确认卡都渲染出来了');
    const first = ui.page.locator('[data-remember-card]').first();
    const exHref = await first.locator('a[data-remember-exclude]').getAttribute('href').catch(() => null);
    await ui.assert(exHref === `/accounts/${houseId}/edit#analysis-excluded`, `卡片给出「把「${house}」标成不参与配置分析 →」直达勾选项`, String(exHref));
    await first.locator('textarea[name=text]').fill('e2e记住 · 我们家的车不打算卖,别建议处置');
    await ui.click('[data-remember-card] >> nth=0 >> [data-remember-save]', '在第一张卡上改了原文,点「记住」');
    await ui.page.waitForSelector('[data-remember-done]', { timeout: 15000 }).catch(() => {});
    await ui.visible('[data-remember-done]', '整张卡换成「已记住 · 在分析设置里管理 →」');
    await ui.assert(db.num(`SELECT COUNT(*) FROM analysis_preference WHERE family_id=${fx.FAM} AND source='AGENT'
                             AND content='e2e记住 · 我们家的车不打算卖,别建议处置'`) === 1, '真值层:存的是改过的原文,来源 AGENT');

    await ui.visible('[data-remember-card] [data-remember-template]', '第二张卡(说的是立场)有「切到「稳健守护」」');
    await ui.visible('[data-remember-card] [data-remember-customize]', '还有「基于当前模板定制 →」');
    await ui.click('[data-remember-card] [data-remember-template]', '点「切到「稳健守护」」');
    await ui.page.waitForTimeout(1200);
    await ui.assert(cfg('analysis_template_default') === 'STEADY', '真值层:家里的默认模板 = 稳健守护', String(cfg('analysis_template_default')));
    await ui.assert(db.num(`SELECT COUNT(*) FROM analysis_preference WHERE family_id=${fx.FAM} AND content='e2e记住 · 以后都按保守的来'`) === 0,
      '没点「记住」的那一句没有存(AI 自己存不了)');

    // 回看这段对话:处理过的卡不再出(标记在那条回答里改写成「处理过」)
    await ui.page.reload({ waitUntil: 'networkidle' });
    await ui.count('[data-remember-card]', 0, '处理过的两张卡,回看这段对话时都不再出');
    await ui.assert(db.num(`SELECT COUNT(*) FROM ask_message WHERE conversation_id=${convId} AND role='assistant'
                             AND content_text LIKE '%{{remember-done:e2e记住%'`) === 2, '真值层:两条回答里的标记都改写成了「处理过」');
    await ui.notSeesText('{{remember', '正文里看不到标记本身');
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup() {
    db.raw(`DELETE FROM analysis_preference WHERE family_id=${fx.FAM} AND id > ${Number(state.maxPref || 0)}`);
    const mine = `SELECT m.id FROM ask_message m JOIN ask_conversation c ON c.id=m.conversation_id
                   WHERE c.family_id=${fx.FAM} AND c.id > ${Number(state.maxConv || 0)}`;
    db.raw(`DELETE FROM ask_citation WHERE message_id IN (${mine})`);
    db.raw(`DELETE FROM ask_tool_call WHERE message_id IN (${mine})`);
    db.raw(`DELETE m FROM ask_message m JOIN ask_conversation c ON c.id=m.conversation_id
             WHERE c.family_id=${fx.FAM} AND c.id > ${Number(state.maxConv || 0)}`);
    db.raw(`DELETE FROM ask_conversation WHERE family_id=${fx.FAM} AND id > ${Number(state.maxConv || 0)}`);
    db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='analysis_template_default'`);
    if (state.tplDefault) {
      db.raw(`INSERT INTO family_runtime_config(family_id, key_name, value_text) VALUES (${fx.FAM}, 'analysis_template_default', '${state.tplDefault}')`);
    }
  },
};
