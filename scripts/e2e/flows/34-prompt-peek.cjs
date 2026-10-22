/**
 * flow · v1.28 · 看 AI 收到了什么:卡片头 >_ → 终端面板逐字显示发出去的内容
 *
 * PRD 验收 #1 ~ #12 · FR-900 ~ FR-921:
 *   从首页点「资产体检」→ AI 综合诊断卡头的 >_ → 面板;面板里的「你家的数据」与库里记下的原文逐字相同,
 *   账户名换成了代号、对照只在注释里;命令既能点也能敲;设了分析偏好 → 那一段标色写来源;
 *   隐私模式下金额糊、复制置灰;报表调仓建议的 >_ 指向生成它的那条记录,老建议照实说「那时还没开始记录」;
 *   管理 → AI 接入 关掉开关 → 图标不见;超级 Agent 每一问的回答旁也有 >_。
 *
 * AI 区块用真的(不 stub):面板读的是真的调用记下来的原文。模型挂了也有记录(FAILED + 上游原话),断言不依赖模型输出。
 */
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');

const state = {};
const PREF = 'e2e-v128 · 房子是自住的,别建议卖';
const cfg = k => db.one(`SELECT value_text FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='${k}'`);
const esc = s => String(s).replace(/'/g, "''");

async function openPeek(ui, panelSel, label) {
  await ui.page.waitForSelector(`${panelSel} [data-peek-btn]`, { timeout: 150000 }).catch(() => {});
  await ui.click(`${panelSel} [data-peek-btn]`, label);
  await ui.page.waitForSelector(`${panelSel} [data-peek]:not([hidden])`, { timeout: 20000 }).catch(() => {});
  await ui.page.waitForTimeout(800);   // 第一行命令逐字打出来(≤ 0.6 秒)
}

const raw = (ui, sel) => ui.page.locator(sel).first().inputValue().catch(() => '');

module.exports = {
  realAi: true,   // v1.37 · 这条要真调大模型(AI 区块用真的),默认跳过,E2E_REAL_AI=1 才跑
  name: '34-prompt-peek',
  title: 'v1.28 · 看 AI 收到了什么:>_ 终端面板 · 逐字 = 库里的原文 · 账户代号 · 命令 · 你的设置标色 · 隐私模式 · 调仓老建议 · 开关 · 超级 Agent',

  async run(ui, report) {
    ui.flow = this.name;
    state.maxRec = db.num(`SELECT COALESCE(MAX(id),0) FROM llm_prompt_record`);
    state.maxPref = db.num(`SELECT COALESCE(MAX(id),0) FROM analysis_preference`);
    state.maxConv = db.num(`SELECT COALESCE(MAX(id),0) FROM ask_conversation WHERE family_id=${fx.FAM}`);
    state.peekCfg = cfg('ai_prompt_peek');

    // ── 1 · 从前门进:首页 → 资产体检 → AI 综合诊断卡头的 >_ ─────────────────
    report.section('1 · 入口:AI 综合诊断卡头的 >_(FR-900)');
    await ui.goto('/');
    await ui.click('nav a:has-text("资产体检")', '顶部导航点「资产体检」');
    await ui.rendered('体检页');
    // v1.28.1 · 维护者:只有一个 >_ 太隐蔽 → 写明「查看 prompt」;和右边的刷新按钮一样高(两张 AI 卡都看)
    for (const [sel, name] of [['#ai-diagnose-panel', 'AI 综合诊断'], ['#ai-insight-panel', 'AI 资产洞察']]) {
      await ui.page.waitForSelector(`${sel} [data-peek-btn]`, { timeout: 150000 }).catch(() => {});
      const lab = (await ui.page.locator(`${sel} [data-peek-btn]`).first().innerText().catch(() => '')).trim();
      await ui.assert(lab.includes('查看 prompt'), `${name}卡头的入口写着「查看 prompt」(图标保留)`, lab);
      const hs = await ui.page.locator(`${sel} [data-peek-host]`).first().evaluate(h =>
        [...h.querySelectorAll('button')].filter(b => b.offsetParent)
          .map(b => b.textContent.trim().replace(/\s+/g, ' ') + '=' + b.getBoundingClientRect().height.toFixed(1))).catch(() => []);
      const vals = hs.map(s => s.split('=').pop());
      await ui.assert(hs.length >= 2 && new Set(vals).size === 1, `${name}:「查看 prompt」和右边的刷新按钮一样高`, hs.join(' / '));
    }
    await openPeek(ui, '#ai-diagnose-panel', '点 AI 综合诊断卡头的 >_');
    await ui.visible('#ai-diagnose-panel [data-peek]', '卡片里原地展开一个终端面板');
    const st = await ui.page.getAttribute('#ai-diagnose-panel [data-peek]', 'data-peek-state');
    if (!['OK', 'FAILED', 'REJECTED'].includes(st)) {
      report.skip(this.name, 'AI 综合诊断的面板', `这台机器上这次没有调用 AI(state=${st})—— 面板照实说没发`);
    } else {
      const cmd = (await ui.page.locator('#ai-diagnose-panel [data-peek-type]').innerText()).trim();
      await ui.assert(cmd.startsWith('ai-prompt show --for'), '第一行是一条「命令」', cmd);
      // ── 2 · 逐字 = 库里记下的原文(FR-909)──────────────────────────
      report.section('2 · 面板里的就是记下来的原文,逐字(FR-909)');
      const src = await ui.page.getAttribute('#ai-diagnose-panel [data-peek-btn]', 'data-peek-src');
      const rid = Number((src || '').replace('/ai/prompt/', ''));
      // 整套串跑时前面的 flow 可能已经按同一份材料问过 → 这里命中内存缓存,指向的是「生成这份结果的那一次」(更早)
      await ui.assert(rid > 0 && db.num(`SELECT COUNT(*) FROM llm_prompt_record WHERE id=${rid} AND family_id=${fx.FAM}`) === 1,
        '>_ 指向生成这份结果的那条记录(缓存命中时是更早那一次,不是现在重拼)', src);
      const shown = await raw(ui, '#ai-diagnose-panel [data-peek-raw="data"]');
      const len = db.num(`SELECT CHAR_LENGTH(user_text) FROM llm_prompt_record WHERE id=${rid} AND family_id=${fx.FAM}`);
      const same = db.num(`SELECT user_text = '${esc(shown)}' FROM llm_prompt_record WHERE id=${rid} AND family_id=${fx.FAM}`);
      await ui.assert(same === 1, '真值层:面板的「你家的数据」= 库里记下的原文(逐字比)', `面板 ${shown.length} 字 · 库 ${len} 字`);
      await ui.assert(db.one(`SELECT surface FROM llm_prompt_record WHERE id=${rid}`) === 'DIAGNOSE_FAMILY', '真值层:记在「体检 · AI 综合诊断」名下');
      // ── 3 · 账户名换了代号,对照只在注释里(FR-920 / FR-907)──────────────
      report.section('3 · 账户名换代号 · 对照只在注释里(FR-920)');
      const acct = db.one(`SELECT display_name FROM account WHERE family_id=${fx.FAM} AND archived_at IS NULL
                            AND CHAR_LENGTH(display_name) >= 4 AND analysis_excluded=0 AND type NOT IN ('PROPERTY','OTHER','LOAN') ORDER BY id LIMIT 1`);
      await ui.assert(/【账户[A-Z]{1,2}】/.test(shown), '账户清单里写的是「账户A」这样的代号', shown.slice(0, 80));
      if (acct) {
        await ui.assert(!shown.includes(`【${acct}】`), `账户真名「${acct}」没有进账户清单`);
        const legend = (await ui.page.locator('#ai-diagnose-panel .ln.cmt').allInnerTexts()).join('\n');
        await ui.assert(legend.includes(`= ${acct}`), '代号对照在面板末尾的注释行里(只在这里显示,没发给 AI)', legend.slice(0, 120));
      }
      // ── 4 · 命令:点按钮 = 敲命令(FR-916 / FR-917)────────────────────
      report.section('4 · 命令:点按钮与敲命令等价(FR-916 / FR-917)');
      await ui.click('#ai-diagnose-panel [data-peek-run="help"]', '点「$ help」');
      await ui.seesText('能用的命令', 'help 列出能用的命令');
      await ui.fill('#ai-diagnose-panel [data-peek-in]', 'rules', '敲 rules');
      await ui.page.press('#ai-diagnose-panel [data-peek-in]', 'Enter');
      const rulesOpen = await ui.page.locator('#ai-diagnose-panel [data-sec="rules"]').evaluate(e => e.classList.contains('open'));
      await ui.assert(rulesOpen, '敲 rules → 「规矩」展开了');
      await ui.fill('#ai-diagnose-panel [data-peek-in]', 'hello', '敲一条不存在的命令');
      await ui.page.press('#ai-diagnose-panel [data-peek-in]', 'Enter');
      await ui.seesText('没有「hello」这条命令', '敲错了不报红,给 help');
      await ui.page.keyboard.press('Escape');
      await ui.page.waitForTimeout(300);
      await ui.notVisible('#ai-diagnose-panel [data-peek]', 'Esc 收起');
    }

    // ── 5 · 你的设置带来的段落标色、写来源(FR-906)──────────────────────
    report.section('5 · 设了分析偏好 → 那一段标色写来源(FR-906)');
    db.raw(`INSERT INTO analysis_preference(family_id, content, enabled, source) VALUES (${fx.FAM}, '${esc(PREF)}', 1, 'SETTINGS')`);
    const before = await ui.page.getAttribute('#ai-diagnose-panel [data-peek-btn]', 'data-peek-src').catch(() => null);
    await ui.click('#ai-diagnose-panel button[hx-target="#ai-diagnose-panel"]', '点 AI 综合诊断的「↻ 刷新」(按新偏好重跑)');
    // 刷新是整块换掉(htmx outerHTML)· 等新的一份到了再点 —— 否则点到的是旧卡上的 >_
    await ui.page.waitForFunction(old => {
      const b = document.querySelector('#ai-diagnose-panel [data-peek-btn]');
      return b && b.getAttribute('data-peek-src') !== old;
    }, before, { timeout: 150000 }).catch(() => {});
    await openPeek(ui, '#ai-diagnose-panel', '再点 >_');
    const st2 = await ui.page.getAttribute('#ai-diagnose-panel [data-peek]', 'data-peek-state').catch(() => null);
    if (!['OK', 'FAILED', 'REJECTED'].includes(st2)) {
      report.skip(this.name, '偏好标色', `这次没有调用 AI(state=${st2})`);
    } else {
      const mine = (await ui.page.locator('#ai-diagnose-panel [data-peek-mine]').allInnerTexts()).join('\n');
      await ui.assert(mine.includes(PREF), '偏好原文在标色的那一段里', mine.slice(0, 120));
      await ui.assert(mine.includes('分析偏好'), '旁边写明来源「← 分析设置 ⑤ · 分析偏好」');
      await ui.click('#ai-diagnose-panel [data-peek-run="mine"]', '点「$ mine」');
      await ui.seesText('你的设置带来了', 'mine:只看你的设置带来的段落');

      // ── 6 · 隐私模式:面板里金额糊,复制置灰(FR-912)──────────────────
      report.section('6 · 隐私模式(FR-912)');
      await ui.page.evaluate(() => window.togglePrivacy && window.togglePrivacy());
      await ui.page.waitForTimeout(400);
      const blurred = await ui.page.locator('#ai-diagnose-panel [data-peek] [data-priv]')
        .evaluateAll(els => els.length > 0 && els.every(e => getComputedStyle(e).filter.includes('blur')));
      await ui.assert(blurred, '面板里的金额和页面其他金额一样糊');
      const aiBodyPriv = await ui.page.locator('#ai-diagnose-panel p [data-priv], #ai-diagnose-panel li [data-priv]').count();
      report.info(`AI 正文里包了 data-priv 的金额:${aiBodyPriv} 处(FR-913 · 取决于这次回答里有没有金额)`);
      await ui.click('#ai-diagnose-panel [data-peek-run="copy"]', '隐私模式下点「$ copy」');
      await ui.seesText('先关隐私模式再复制', '隐私模式开着不给复制');
      await ui.page.evaluate(() => window.togglePrivacy && window.togglePrivacy());
    }

    // ── 7 · 报表调仓建议:>_ 指向生成它的那条;老建议照实说(FR-909 / FR-910)──────
    report.section('7 · 报表 · AI 调仓建议(FR-909 / FR-910)');
    await ui.goto('/');
    await ui.click('nav a:has-text("报表")', '顶部导航点「报表」');
    await ui.page.waitForLoadState('networkidle').catch(() => {});
    const adviseBtn = await ui.page.isVisible('#allocation-diff form[action*="rebalance/advise"] button').catch(() => false);
    if (adviseBtn) {
      await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle', timeout: 150000 }).catch(() => {}),
                         ui.click('#allocation-diff form[action*="rebalance/advise"] button', '点「让 AI 给出具体调仓步骤 / 刷新」')]);
    }
    const hasCard = await ui.page.isVisible('#ai-rebalance [data-peek-btn]').catch(() => false);
    const failed = await ui.page.isVisible('[data-rebalance-fail] [data-peek-btn]').catch(() => false);
    if (!hasCard && failed) {
      // 没生成出来(AI 回了但没通过校验 / 调用失败)—— 也能看这一次发出去了什么(FR-911)
      await ui.click('[data-rebalance-fail] [data-peek-btn]', '没生成出建议:点提示条上的 >_');
      await ui.page.waitForSelector('[data-rebalance-fail] + [data-peek-slot] [data-peek]', { timeout: 20000 }).catch(() => {});
      const why = (await ui.page.locator('[data-rebalance-fail] + [data-peek-slot] .ln.warn').allInnerTexts().catch(() => [])).join(' ');
      await ui.assert(why.length > 0, '没生成出来也能看发出去了什么,并写明原因', why.slice(0, 100));
    }
    if (!hasCard) {
      report.skip(this.name, '调仓建议的 >_', '这次没有生成出调仓建议(AI 不可用或对照不成立)');
    } else {
      const rsrc = await ui.page.getAttribute('#ai-rebalance [data-peek-btn]', 'data-peek-src');
      // 缓存按「锚 | 偏好 / 模板 / 范围的指纹」分行存 —— 取页面上正在展示的那一行(按卡上「生成于」的时刻对),
      //   不能取「最新一行」:别的指纹下更晚生成过一份时,最新一行不是这张卡(v1.28.1 复验时撞上)
      const shownAt = ((await ui.page.locator('#ai-rebalance span:has-text("生成于")').first().innerText().catch(() => ''))
        .match(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}/) || [''])[0];
      const [cacheRow, cacheRec] = (db.one(`SELECT CONCAT(id,'|',IFNULL(prompt_record_id,'')) FROM rebalance_advice_cache WHERE family_id=${fx.FAM}
                                 AND DATE_FORMAT(generated_at, '%Y-%m-%d %H:%i')='${shownAt}' ORDER BY generated_at DESC LIMIT 1`) || '|').split('|');
      await ui.assert(rsrc === `/ai/prompt/${cacheRec}`, '真值层:>_ 指向缓存行上记的那条记录(不是按现在的数据重拼)', `${rsrc} vs ${cacheRec}`);
      // v1.28.1 · 「查看 prompt」和「↻ 刷新」同高
      const rh = await ui.page.locator('#ai-rebalance [data-peek-host]').first().evaluate(h =>
        [...h.querySelectorAll('button')].map(b => b.getBoundingClientRect().height.toFixed(1))).catch(() => []);
      await ui.assert(rh.length === 2 && rh[0] === rh[1], '调仓建议卡头:「查看 prompt」和「↻ 刷新」一样高', rh.join(' / '));
      await openPeek(ui, '#ai-rebalance', '点调仓建议卡头的 >_');
      const rdata = await raw(ui, '#ai-rebalance [data-peek-raw="data"]');
      await ui.assert(/账户[A-Z]{1,2} \(/.test(rdata), '调仓的账户清单写的是代号');
      const actions = (await ui.page.locator('#ai-rebalance label b').allInnerTexts()).join(' ');
      await ui.assert(!/账户[A-Z]{1,2}/.test(actions), '建议条目里的代号已换回真名', actions.slice(0, 80));
      // 老建议:缓存行上没有记录编号 → 照实说
      state.rebalanceRec = cacheRec; state.rebalanceRow = cacheRow;
      db.raw(`UPDATE rebalance_advice_cache SET prompt_record_id=NULL WHERE family_id=${fx.FAM} AND id=${cacheRow}`);
      await ui.page.reload({ waitUntil: 'networkidle' }).catch(() => {});
      await openPeek(ui, '#ai-rebalance', '老建议:点 >_');
      await ui.seesText('那时还没开始记录', '老建议照实说「那时还没开始记录」,不拿现在的数据拼');
      // 只还原这一行 —— 按「prompt_record_id IS NULL」还原会把 V65 之前的老缓存行也写上编号
      db.raw(`UPDATE rebalance_advice_cache SET prompt_record_id=${cacheRec} WHERE family_id=${fx.FAM} AND id=${cacheRow}`);
      state.rebalanceRec = null;
    }

    // ── 8 · 开关:管理 → AI 接入 关掉 → 图标不见(FR-914)──────────────────
    report.section('8 · 管理 → AI 接入 · 开关(FR-914)');
    await ui.goto('/');
    await ui.click('nav a:has-text("管理")', '顶部导航点「管理」');
    await ui.click('a[href="/admin/ai-access"]', '点「AI 接入」');
    await ui.visible('#peek[data-prompt-peek-setting]', '「看 AI 收到了什么」这一块在');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('[data-prompt-peek-toggle]', '点「关掉」')]);
    await ui.assert(cfg('ai_prompt_peek') === 'false', '真值层:开关存成 false');
    await ui.page.waitForTimeout(5500);   // 家庭配置 5 秒缓存
    await ui.goto('/checkup');
    await ui.page.waitForSelector('#ai-diagnose-panel[data-vendor]', { timeout: 150000 }).catch(() => {});
    await ui.assert(await ui.page.locator('#ai-diagnose-panel [data-peek-btn]').count() === 0, '关掉之后 AI 卡片头不再有 >_');
    await ui.goto('/admin/ai-access#peek');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('[data-prompt-peek-toggle]', '点「打开」')]);
    await ui.assert(cfg('ai_prompt_peek') === 'true', '真值层:又打开了');
    await ui.page.waitForTimeout(5500);

    // ── 9 · 超级 Agent:每一问的回答旁也有 >_(FR-919)─────────────────────
    report.section('9 · 超级 Agent 每一问(FR-919)');
    await ui.goto('/');
    await ui.click('nav a:has-text("超级")', '顶部导航点「超级 Agent」');
    const blocked = await ui.page.isVisible('.ask-composer-wrap.is-off').catch(() => false);
    if (blocked) {
      report.skip(this.name, '超级 Agent', '这台机器上超级 Agent 没开 / 没配模型');
    } else {
      await ui.fill('[data-ask-input]', 'e2e-v128 · 一句话说说我们家的钱主要在哪', '问一句');
      await ui.click('[data-ask-send]', '点发送');
      await ui.page.waitForFunction(() => {
        const stop = document.querySelector('[data-ask-stop]');
        return document.querySelector('[data-ask-live] .ask-tools-row') && stop && stop.hidden;
      }, null, { timeout: 180000 }).catch(() => {});
      // 流完之后前端去要这一问的面板地址,再把 >_ 插到操作行最前(与历史消息同一位置)
      await ui.page.waitForSelector(`.ask-tools-row [data-peek-btn]`, { timeout: 20000 }).catch(() => {});
      const btns = ui.page.locator('.ask-tools-row [data-peek-btn]');
      const has = await btns.count();
      await ui.assert(has >= 1, '回答下面的操作行里有 >_', String(has));
      if (has >= 1) {
        await btns.last().click();
        await ui.page.waitForSelector('[data-peek]:not([hidden]) [data-peek-raw="data"]', { timeout: 20000 }).catch(() => {});
        const askData = await raw(ui, '[data-peek]:not([hidden]) [data-peek-raw="data"]');
        await ui.assert(askData.includes('一句话说说我们家的钱主要在哪'), '「这一问」里就是刚才问的原话', askData.slice(-60));
        await ui.assert(await ui.page.getAttribute('[data-peek]:not([hidden])', 'data-peek-state') === 'SENT', '面板写「这一问发出去的就是下面这段」');
        report.info(`这台机器上的规矩来源:${(await ui.page.locator('[data-peek]:not([hidden]) .ln.warn').allInnerTexts()).join(' ') || '本机模式 · 随请求发出'}`);
      }
    }
    await ui.noConsoleErrors('体检 / 报表 / 管理 / 超级 Agent 控制台无报错');
  },

  async cleanup() {
    db.raw(`DELETE FROM analysis_preference WHERE family_id=${fx.FAM} AND id > ${Number(state.maxPref || 0)}`);
    db.raw(`DELETE FROM family_runtime_config WHERE family_id=${fx.FAM} AND key_name='ai_prompt_peek'`);
    if (state.peekCfg) db.raw(`INSERT INTO family_runtime_config(family_id, key_name, value_text) VALUES (${fx.FAM}, 'ai_prompt_peek', '${state.peekCfg}')`);
    if (state.rebalanceRec) {
      db.raw(`UPDATE rebalance_advice_cache SET prompt_record_id=${state.rebalanceRec} WHERE family_id=${fx.FAM} AND id=${state.rebalanceRow}`);
    }
    const mine = `SELECT m.id FROM ask_message m JOIN ask_conversation c ON c.id=m.conversation_id
                   WHERE c.family_id=${fx.FAM} AND c.id > ${Number(state.maxConv || 0)}`;
    db.raw(`DELETE FROM ask_citation WHERE message_id IN (${mine})`);
    db.raw(`DELETE FROM ask_tool_call WHERE message_id IN (${mine})`);
    db.raw(`DELETE m FROM ask_message m JOIN ask_conversation c ON c.id=m.conversation_id
             WHERE c.family_id=${fx.FAM} AND c.id > ${Number(state.maxConv || 0)}`);
    db.raw(`DELETE FROM ask_conversation WHERE family_id=${fx.FAM} AND id > ${Number(state.maxConv || 0)}`);
  },
};
