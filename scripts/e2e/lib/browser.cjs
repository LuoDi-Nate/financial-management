/**
 * browser.cjs · 真 e2e 的浏览器层
 *
 * ── 为什么要有这一层 ──
 *
 * 旧的 scripts/e2e.sh 用 curl 直接打端点 + mysql 查真值。它验的是
 * 「**接口通了、库里对了**」—— 那是数据层回归,不是 e2e。
 * 它跳过了用户与系统之间的全部东西:
 *
 *   · 表单字段名对不对(curl 里写 `newBalance`,页面上可能叫别的)
 *   · 按钮在不在、点不点得到、被不被遮挡
 *   · JS 有没有拦截提交、有没有绑错作用域(v1.21.2 踩过:分桶页签在 form 之外,
 *     `form.querySelector` 拿到 null,整段绑定静默跳过,页面长得完全正常)
 *   · 渲染有没有在中途炸掉(v1.19 踩过:chunked 响应被截断,curl 照报 200,
 *     浏览器里是半张白页)
 *
 * 所以这一层的铁律是:**动作必须从页面元素发起**。
 * 想写一笔支出,就得找到那个输入框、填进去、点保存 —— 不许调端点。
 *
 * ── 断言分两层 ──
 *
 *   · seesText / notSeesText / visible —— 用户**看得见**什么
 *   · db.one(...)                      —— 数据**真的**对不对(见 db.cjs)
 *
 * 两层都要。只看页面会漏掉「显示对了但没存进去」;只查库会漏掉
 * 「存对了但用户看不到」—— 后者在这个项目里出现过不止一次。
 *
 * ── 失败必留证据 ──
 *
 * 每个断言失败自动截图到 scripts/e2e/shots/。浏览器 e2e 相对 curl 的最大优势
 * 就是红了有图:不用靠复现去猜当时页面长什么样。
 */
const path = require('path');
const fs = require('fs');

const PW_CORE = process.env.PW_CORE
  || path.join(process.env.HOME, '.npm/_npx/9833c18b2d85bc59/node_modules/playwright-core');
const CHROME = process.env.PW_CHROME
  || path.join(process.env.HOME, '.cache/ms-playwright/chromium-1228/chrome-linux64/chrome');
const BASE = process.env.E2E_BASE || 'http://127.0.0.1:20000';
/* 走前门(E2E_BASE=https://beta.dixi-token.top)时,静态资源要穿过 prod → beta 的反代,
   首屏实测约 21 秒(本机直连不到 1 秒)。超时写死 30/45 秒会在登录页就起不来。 */
const NAV_TIMEOUT = Number(process.env.E2E_NAV_TIMEOUT || 45000);
const USER = process.env.E2E_USER || 'diwa';
const PASS = process.env.E2E_PASS || 'demo1234';
const SHOTS = path.join(__dirname, '..', 'shots');

const { chromium } = require(PW_CORE);

class Ui {
  constructor(page, report) {
    this.page = page;
    this.report = report;
    this.flow = '(未命名)';
  }

  // ── 导航 ────────────────────────────────────────────────────────────────

  /** 打开页面。等 networkidle —— 本项目大量用 HTMX,DOMContentLoaded 之后内容还在换。 */
  async goto(p) {
    await this.page.goto(BASE + p, { waitUntil: 'networkidle', timeout: NAV_TIMEOUT });
    await this.page.waitForTimeout(350);
    return this;
  }

  /**
   * 页面有没有完整渲染到底。
   *
   * 专门守 v1.19 那类事故:Thymeleaf 在渲染中途抛异常,而响应已经 chunked 发出去了 ——
   * HTTP 状态码仍然是 200,`/error` 页面也接管不了,用户看到半张页面。
   * curl 完全看不出来,只有真渲染才知道。
   */
  async rendered(label) {
    const ok = await this.page.evaluate(() =>
      !!document.body && document.body.innerText.trim().length > 50
         && !!document.querySelector('footer, [data-page-end], main'));
    return this.assert(ok, `${label} · 页面完整渲染(非中途截断)`);
  }

  // ── 用户动作(一律从元素发起)──────────────────────────────────────────

  async click(selector, label) {
    try {
      await this.page.click(selector, { timeout: 12000 });
      await this.page.waitForTimeout(500);
      return this.assert(true, label || `点击 ${selector}`);
    } catch (e) {
      return this.assert(false, label || `点击 ${selector}`, `点不到:${e.message.split('\n')[0]}`);
    }
  }

  /** 点一个**看得见的文字**(用户就是这么找按钮的,不是靠 CSS 选择器)。 */
  async clickText(text, label) {
    return this.click(`text=${text}`, label || `点击「${text}」`);
  }

  async fill(selector, value, label) {
    try {
      await this.page.fill(selector, String(value), { timeout: 12000 });
      return this.assert(true, label || `填写 ${selector} = ${value}`);
    } catch (e) {
      return this.assert(false, label || `填写 ${selector}`, `填不进:${e.message.split('\n')[0]}`);
    }
  }

  /** 按表单字段的 name 填 —— 字段名写错的话这里就红,而 curl 只会默默 400。 */
  async fillByName(name, value, scope) {
    const sel = `${scope ? scope + ' ' : ''}[name="${name}"]`;
    return this.fill(sel, value, `填写字段 ${name} = ${value}`);
  }

  async selectByName(name, value, scope) {
    const sel = `${scope ? scope + ' ' : ''}select[name="${name}"]`;
    try {
      await this.page.selectOption(sel, String(value), { timeout: 12000 });
      return this.assert(true, `下拉 ${name} 选 ${value}`);
    } catch (e) {
      return this.assert(false, `下拉 ${name} 选 ${value}`, e.message.split('\n')[0]);
    }
  }

  /**
   * 选自研下拉(`<select data-lsel>` + lens-select.js)。
   *
   * ── 为什么不能用 selectOption ──
   *
   * 组件把原生 select **隐藏**掉,另外造一个 `.lsel-btn` + `.lsel-panel` 顶上去。
   * Playwright 的 selectOption 要求元素可见 → 直接超时。
   * 而用 `{force:true}` 去操作隐藏的原生 select 虽然能过,但那**跳过了组件本身** ——
   * 用户根本不是那么选的,测出来的绿灯不代表他点得动。
   *
   * 所以这里走真实路径:点按钮展开 → 点列表项 → **再回头确认原生 select 的 value 真的变了**。
   * 最后那一步是关键:v1.21 踩过「组件赋值 .value 但没 dispatch change」,
   * 表现是下拉看起来选中了、提交上去还是旧值 —— 只验「点得动」抓不到它。
   */
  async pickLsel(name, value, scope) {
    const wrap = `${scope ? scope + ' ' : ''}.lsel:has(select[name="${name}"])`;
    const native = `${scope ? scope + ' ' : ''}select[name="${name}"]`;
    try {
      await this.page.click(`${wrap} .lsel-btn`, { timeout: 12000 });
      await this.page.waitForTimeout(250);
      // panel 可能被 portal 到 body 上(避开 overflow 裁剪),所以两处都找
      const item = `li[data-v="${value}"]`;
      const inWrap = await this.page.locator(`${wrap} ${item}`).count();
      await this.page.click(inWrap > 0 ? `${wrap} ${item}` : `.lsel-panel ${item}`, { timeout: 8000 });
      await this.page.waitForTimeout(250);
      const got = await this.page.locator(native).inputValue();
      return this.assert(String(got) === String(value),
        `自研下拉 ${name} 选中 ${value}(并回写到原生 select)`,
        `点完之后原生值是 ${got} —— 组件可能没 dispatch change`);
    } catch (e) {
      return this.assert(false, `自研下拉 ${name} 选 ${value}`, e.message.split('\n')[0]);
    }
  }

  /**
   * 选可搜索下拉(`<select data-searchable>` + searchable-select.js · v1.30 加进来)。
   *
   * 组件把原生 select 藏起来,换成一个输入框 + `.ss-dropdown` 里的 `.ss-item`(mousedown 选中)。
   * 和 pickLsel 一样走真实路径:点输入框展开 → 点那一项(按选项文字找)→ 回头确认原生 select 的值真的变了。
   */
  async pickSearchable(name, value, scope) {
    const wrap = `${scope ? scope + ' ' : ''}.ss-wrap:has(select[name="${name}"])`;
    const native = `${wrap} select[name="${name}"]`;
    try {
      const text = await this.page.locator(`${native} option[value="${value}"]`).first().textContent();
      await this.page.click(`${wrap} input.ss-input`, { timeout: 12000 });
      await this.page.waitForTimeout(250);
      await this.page.click(`${wrap} .ss-item:has-text("${String(text).trim()}")`, { timeout: 8000 });
      await this.page.waitForTimeout(250);
      const got = await this.page.locator(native).inputValue();
      return this.assert(String(got) === String(value), `可搜索下拉 ${name} 选中「${String(text).trim()}」(回写到原生 select)`,
        `点完之后原生值是 ${got}`);
    } catch (e) {
      return this.assert(false, `可搜索下拉 ${name} 选 ${value}`, e.message.split('\n')[0]);
    }
  }

  /** 表单里的下拉可能是原生的,也可能被 lens-select / searchable-select 接管了 —— 自动分流。 */
  async choose(name, value, scope) {
    const wrap = `${scope ? scope + ' ' : ''}.lsel:has(select[name="${name}"])`;
    if (await this.page.locator(wrap).count() > 0) return this.pickLsel(name, value, scope);
    const ss = `${scope ? scope + ' ' : ''}.ss-wrap:has(select[name="${name}"])`;
    if (await this.page.locator(ss).count() > 0) return this.pickSearchable(name, value, scope);
    return this.selectByName(name, value, scope);
  }

  /** 提交表单并等页面稳定(本项目表单大多是整页 POST-redirect-GET)。 */
  async submit(selector, label) {
    try {
      await Promise.all([
        this.page.waitForLoadState('networkidle', { timeout: 30000 }).catch(() => {}),
        this.page.click(selector, { timeout: 12000 }),
      ]);
      await this.page.waitForTimeout(900);
      return this.assert(true, label || `提交 ${selector}`);
    } catch (e) {
      return this.assert(false, label || `提交 ${selector}`, e.message.split('\n')[0]);
    }
  }

  // ── 看得见层断言 ────────────────────────────────────────────────────────

  async text() {
    return this.page.evaluate(() => document.body.innerText);
  }

  async seesText(s, label) {
    const t = await this.text();
    return this.assert(t.includes(s), label || `页面上看得到「${s}」`);
  }

  async notSeesText(s, label) {
    const t = await this.text();
    return this.assert(!t.includes(s), label || `页面上看不到「${s}」`);
  }

  async visible(selector, label) {
    const v = await this.page.isVisible(selector).catch(() => false);
    return this.assert(v, label || `${selector} 可见`);
  }

  async notVisible(selector, label) {
    const v = await this.page.isVisible(selector).catch(() => false);
    return this.assert(!v, label || `${selector} 不可见`);
  }

  async count(selector, n, label) {
    const c = await this.page.locator(selector).count();
    return this.assert(c === n, label || `${selector} 有 ${n} 个`, `实得 ${c} 个`);
  }

  /**
   * 并列同类元素必须逐像素同尺寸(feedback_sibling_uniform_selfcheck)。
   *
   * 这条断言只有浏览器做得到 —— 它要的是**渲染之后的真实盒子**,
   * 读源码和查库都验不了。v1.23 实测抓到两个期 pill 差 2px、
   * 四个档位的分隔线差 15px,都是「加粗边框 + 减 padding」凑尺寸凑出来的。
   */
  async sameSize(selector, label) {
    const boxes = await this.page.locator(selector).evaluateAll(els =>
      els.map(e => { const r = e.getBoundingClientRect();
                     return Math.round(r.width) + 'x' + Math.round(r.height); }));
    const uniq = [...new Set(boxes)];
    return this.assert(boxes.length > 1 && uniq.length === 1,
      label || `${selector} 并列元素尺寸一致`,
      `实得 ${boxes.length} 个:${boxes.join(' / ')}`);
  }

  /** 并列元素的某个内部锚点要落在同一水平线(比如四张卡的分隔线)。 */
  async alignedTops(selector, label, tolerance = 2) {
    const tops = await this.page.locator(selector).evaluateAll(els =>
      els.map(e => Math.round(e.getBoundingClientRect().top)));
    const ok = tops.length > 1 && (Math.max(...tops) - Math.min(...tops)) <= tolerance;
    return this.assert(ok, label || `${selector} 顶边对齐`, `实得 ${tops.join(' / ')}`);
  }

  /** 控制台有没有报错 —— JS 炸了页面可能照样「看起来正常」。 */
  async noConsoleErrors(label) {
    const errs = (this._consoleErrors || []).filter(e =>
      !/favicon|net::ERR_|Failed to load resource/.test(e));
    return this.assert(errs.length === 0, label || '浏览器控制台无报错',
      errs.slice(0, 3).join(' | '));
  }

  // ── 结果记录 ────────────────────────────────────────────────────────────

  async assert(ok, label, detail) {
    if (ok) { this.report.pass(this.flow, label); return true; }
    const shot = path.join(SHOTS, `${this.flow}-${this.report.failCount() + 1}.jpg`
      .replace(/[^\w.\-一-龥]/g, '_'));
    try {
      fs.mkdirSync(SHOTS, { recursive: true });
      await this.page.screenshot({ path: shot, type: 'jpeg', quality: 70, fullPage: false });
    } catch { /* 截图失败不该掩盖原断言的失败 */ }
    this.report.fail(this.flow, label, detail, shot);
    return false;
  }

  /**
   * 在分页列表里翻到含某个选择器的那一页。
   *
   * 不能假设目标就在第一页 —— beta 的账期表预建到了 2041,
   * 而周期管理是 `period_start DESC` 分页的,前 24 项全是未来期,
   * 真正要操作的当期在第二页往后。用户也是这么翻的。
   *
   * @returns 找到返回页码,没找到返回 -1
   */
  async paginateUntil(pathTpl, selector, maxPages = 12) {
    for (let i = 0; i < maxPages; i++) {
      await this.goto(pathTpl.replace('{p}', String(i)));
      if (await this.page.locator(selector).count() > 0) return i;
    }
    return -1;
  }

  info(msg) { this.report.info(msg); }
}

/** 开浏览器 + 登录。viewport 给 PC;移动端断言用 withMobile。 */
async function open(report, { width = 1440, height = 900 } = {}) {
  const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
  const ctx = await browser.newContext({ viewport: { width, height }, deviceScaleFactor: 1 });
  const page = await ctx.newPage();
  const ui = new Ui(page, report);
  ui._consoleErrors = [];
  page.on('console', m => { if (m.type() === 'error') ui._consoleErrors.push(m.text()); });
  page.on('pageerror', e => ui._consoleErrors.push(String(e)));

  await page.goto(BASE + '/login', { waitUntil: 'networkidle', timeout: NAV_TIMEOUT });
  await page.fill('input[name=username]', USER);
  await page.fill('input[name=password]', PASS);
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle', timeout: NAV_TIMEOUT }).catch(() => {}),
    page.click('button[type=submit]'),
  ]);
  ui._close = async () => { await ctx.close(); await browser.close(); };
  return ui;
}

module.exports = { open, BASE, SHOTS, USER, PASS, NAV_TIMEOUT };
