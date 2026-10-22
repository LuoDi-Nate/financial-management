#!/usr/bin/env node
/**
 * run.cjs · 真 e2e 入口
 *
 * ── 这套东西和旧 scripts/e2e.sh 的区别 ──
 *
 *   旧:curl 打端点 + mysql 查真值 → 验的是「**接口通了、库里对了**」
 *   新:浏览器点页面 + mysql 查真值 → 验的是「**用户点得到、页面对了、库里也对了**」
 *
 * 中间那一大段(表单字段名、按钮可达性、JS 绑定、渲染完整性、并列元素尺寸)
 * 是旧版结构性验不到的 —— 而这个项目的事故恰恰大量出在那一段:
 * 分桶页签绑错作用域点了没反应、chunked 渲染中途炸掉 curl 照报 200、
 * 账户名被 max-w 压成 2px、两个 pill 差 2px。
 *
 * 旧 e2e.sh 没有被删,它被**正名**为 scripts/regression-data.sh:
 * 那 119 处 SQL 断言验的是**计算口径**(XIRR 币种无关、精确算术、归因锚点),
 * 那些本来就没有 UI 路径,硬塞进浏览器没有意义。两者职责不同,都要跑。
 *
 * ── 用法 ──
 *
 *   node scripts/e2e/run.cjs              # 全部 flow
 *   node scripts/e2e/run.cjs 20           # 只跑名字含 "20" 的 flow
 *   E2E_KEEP=1 node scripts/e2e/run.cjs   # 跑完不还原(留现场给人看)
 *
 * ── beta 是 3.5G 小内存 ──
 *
 * 全程**单个浏览器实例、flow 串行**。并发开 context 会把内存打穿
 * (2026-07-16 叠加操作 thrash 过一次整机死机)。
 */
const fs = require('fs');
const path = require('path');
const { Report } = require('./lib/report.cjs');
const { open, BASE } = require('./lib/browser.cjs');

const FLOW_DIR = path.join(__dirname, 'flows');
const filter = process.argv[2];
const KEEP = process.env.E2E_KEEP === '1';

(async () => {
  const flows = fs.readdirSync(FLOW_DIR)
    .filter(f => f.endsWith('.cjs'))
    .sort()
    .map(f => require(path.join(FLOW_DIR, f)))
    .filter(f => !filter || f.name.includes(filter));
  /* v1.37 · 要真调大模型的 flow 默认跳过(维护者 2026-10-21:回归别烧 API 账单)· E2E_REAL_AI=1 才跑,此时整轮不拦 AI */
  const realAi = flows.filter(f => f.realAi);
  if (realAi.length && process.env.E2E_REAL_AI !== '1') {
    console.log(`\x1b[33m跳过 ${realAi.length} 条要真调大模型的 flow:${realAi.map(f => f.name).join(' · ')}(要跑设 E2E_REAL_AI=1)\x1b[0m`);
    flows.splice(0, flows.length, ...flows.filter(f => !f.realAi));
  }

  if (!flows.length) { console.error('没有匹配的 flow'); process.exit(2); }

  console.log(`\x1b[1m真 e2e · ${BASE} · ${flows.length} 个 flow\x1b[0m`);
  console.log('\x1b[2m动作一律从页面元素发起;断言分「看得见层」+「真值层」\x1b[0m');

  const report = new Report();
  let ui;
  try {
    ui = await open(report);
  } catch (e) {
    console.error('浏览器起不来:', e.message);
    console.error('检查 PW_CORE / PW_CHROME,以及 LD_LIBRARY_PATH(本机需要 /tmp/xdmg 里的 libXdamage)');
    process.exit(2);
  }

  for (const flow of flows) {
    report.section(`FLOW ${flow.name} · ${flow.title}`);
    try {
      await flow.run(ui, report);
    } catch (e) {
      report.fail(flow.name, 'flow 抛异常中断', String(e).split('\n')[0]);
    }
    if (flow.cleanup && !KEEP) {
      try {
        report.section(`还原现场 · ${flow.name}`);
        await flow.cleanup(ui, report);
      } catch (e) {
        report.fail(flow.name, '还原失败(会污染下一次运行)', String(e).split('\n')[0]);
      }
    } else if (flow.cleanup && KEEP) {
      report.info(`E2E_KEEP=1 · 保留 ${flow.name} 的现场,没有还原`);
    }
  }

  await ui._close();
  process.exit(report.summary() > 0 ? 1 : 0);
})();
