#!/usr/bin/env node
/**
 * release-shots.cjs · 发版真机截图(beta)· v0.15 起随 release-prod skill 使用
 *
 * 用法:
 *   LD_LIBRARY_PATH=/tmp/xdmg/usr/lib/x86_64-linux-gnu \
 *   node scripts/release-shots.cjs <输出目录> [spec.json 路径]
 *
 * - 登录 beta(127.0.0.1:20000 · diwa/demo1234)后按 spec 截图
 * - PC 1440x900 + 移动 390x844 双视口;JPEG quality=82(压缩且保清晰)
 * - spec 默认 scripts/release-shots.spec.json —— 每次发版按本次核心能力改清单
 * - spec 条目可选 eval:"<js>" —— 截图前在页面里跑一段(展开折叠块 / 截短长列表)
 * - spec 条目可选 drag:{from, to} —— 拍「拖到一半」:按住 from 拖向 to,拍完原路拖回再松手(放回原位 = 不保存、不改库)
 * - spec 条目可选 clipUnion:[selA, selB, …] —— 截这几个元素合起来的那块(页面上没有现成的包裹元素时用)
 * - chromium 路径按本机 playwright 缓存(见 memory reference_headless_screenshot)
 */
const path = require('path');
const fs = require('fs');

const PW = process.env.PW_CORE || path.join(process.env.HOME, '.npm/_npx/9833c18b2d85bc59/node_modules/playwright-core');
const CHROME = process.env.PW_CHROME || path.join(process.env.HOME, '.cache/ms-playwright/chromium-1228/chrome-linux64/chrome');
const BASE = process.env.SHOT_BASE || 'http://127.0.0.1:20000';
const USER = process.env.SHOT_USER || 'diwa';
const PASS = process.env.SHOT_PASS || 'demo1234';

const outDir = process.argv[2] || '/tmp/release-shots';
const specFile = process.argv[3] || path.join(__dirname, 'release-shots.spec.json');
const spec = JSON.parse(fs.readFileSync(specFile, 'utf8'));
fs.mkdirSync(outDir, { recursive: true });

const { chromium } = require(PW);

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'] });

  // 隐私模式默认开启(SHOT_PRIVACY=0 关):data-priv 金额高斯模糊,截图可安全上公开 Release
  async function armPrivacy(ctx) {
    if (process.env.SHOT_PRIVACY === '0') return;
    await ctx.addInitScript(() => { try { sessionStorage.setItem('privacy', '1'); } catch (e) {} });
  }

  async function login(ctx) {
    const p = await ctx.newPage();
    await p.goto(BASE + '/login', { waitUntil: 'networkidle' });
    await p.fill('input[name="username"]', USER);
    await p.fill('input[name="password"]', PASS);
    /* 【不要用 waitForNavigation】—— 登录是 302 重定向链(login → dashboard),
       Promise.all 里注册的那个监听会错过它,然后一直等下一次导航直到超时
       (v1.21 发版时 networkidle 和 domcontentloaded 都卡了 30s)。
       这里只需要会话 cookie,点完等一会儿再确认落地页即可。 */
    await p.click('button[type="submit"]');
    await p.waitForTimeout(3500);
    if (/\/login/.test(p.url())) throw new Error('登录没成功,仍在 /login — 检查 SHOT_USER/SHOT_PASS');
    await p.close();
  }

  async function shoot(ctx, list, prefix) {
    const p = await ctx.newPage();
    for (const s of list) {
      try {
        /* 超时放到 60s:/reports 是全站最重的一页(十几张图 + 事实表),
           30s 默认值在 beta 这台 3.5G 的机器上会偶发超时。 */
        await p.goto(BASE + s.path, { waitUntil: 'domcontentloaded', timeout: 60000 });
        if (s.waitFor) { await p.waitForSelector(s.waitFor, { timeout: 15000 }); }  // 等内容真渲染出来(治 loading 页截早)
        // v1.18 · 截图前在页面里跑一段(展开指定的折叠块 / 截掉过长列表 / 关掉动画)。
        // 加它是因为流水时间线默认只展开【最新】那一组,而 beta 的测试数据里最新是 2041 年那批;
        // 想拍本次能力就得先把目标那一组打开。用页面自己的 DOM 操作,不改被拍的页面。
        /* v1.21 · 有些页面的核心画面需要【先上传一个文件】才存在(账单导入的确认页)。
           草稿存 session,所以必须在同一个 page 里传 —— 外面用 curl 传的这边看不到。 */
        if (s.upload) {
          await p.setInputFiles(s.upload.input, s.upload.file);
          await p.click(s.upload.submit);
          await p.waitForTimeout(s.upload.wait || 4500);
        }
        if (s.eval) { await p.evaluate(s.eval); await p.waitForTimeout(300); }
        await p.waitForTimeout(s.wait || 1200);   // 图表/字体/轮询首帧
        const f = path.join(outDir, `${prefix}_${s.name}.jpg`);
        let back = null;
        if (s.drag) {
          // v1.34 · 进场印章遮罩(#page-overlay)散开之前按下去会落在遮罩上,拖不起来
          await p.waitForSelector('#page-overlay', { state: 'hidden', timeout: 15000 }).catch(() => {});
          const a = await p.locator(s.drag.from).first().boundingBox(), b = await p.locator(s.drag.to).first().boundingBox();
          const x = a.x + Math.min(60, a.width / 4), y0 = a.y + a.height / 2, y1 = b.y + Math.min(24, b.height / 3);
          await p.mouse.move(x, y0); await p.mouse.down();
          for (let i = 1; i <= 16; i++) { await p.mouse.move(x + i, y0 + (y1 - y0) * i / 16, { steps: 2 }); await p.waitForTimeout(25); }
          await p.waitForTimeout(500);
          back = async () => {
            for (let i = 15; i >= 0; i--) { await p.mouse.move(x, y0 + (y1 - y0) * i / 16, { steps: 2 }); await p.waitForTimeout(25); }
            await p.waitForTimeout(300); await p.mouse.up(); await p.waitForTimeout(800);
          };
        }
        if (s.clipUnion) {
          const box = await p.evaluate((sels) => {
            const rs = sels.map(q => document.querySelector(q)).filter(Boolean).map(e => e.getBoundingClientRect());
            const l = Math.min(...rs.map(r => r.left)), t = Math.min(...rs.map(r => r.top));
            const r = Math.max(...rs.map(r => r.right)), b = Math.max(...rs.map(r => r.bottom));
            return { x: l - 12, y: t + window.scrollY - 12, width: r - l + 24, height: b - t + 24 };
          }, s.clipUnion);
          await p.screenshot({ path: f, type: 'jpeg', quality: 82, fullPage: true, clip: box });
        } else if (s.selector) {
          // 聚焦局部:只截该元素(裁掉导航/留白/无关区域)
          await p.locator(s.selector).first().screenshot({ path: f, type: 'jpeg', quality: 82 });
        } else {
          await p.screenshot({ path: f, type: 'jpeg', quality: 82, fullPage: s.full !== false });
        }
        if (back) await back();
        console.log('shot', f, Math.round(fs.statSync(f).size / 1024) + 'KB');
      } catch (e) { console.error('FAIL', s.path, e.message); }
    }
    await p.close();
  }

  /* v1.37 · 截图不真调大模型(维护者 2026-10-21:回归 / 截图别烧 API 账单)· 拦截清单与 e2e 共用 */
  const stubAi = async (ctx) => {
    if (process.env.E2E_REAL_AI === '1') return;
    const { AI_ROUTE } = require('./e2e/lib/browser.cjs');
    await ctx.route(AI_ROUTE, r => r.fulfill({ status: 200, contentType: 'text/html', body: '<div data-ai-stub></div>' }));
  };
  const pc = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
  await armPrivacy(pc);
  await stubAi(pc);
  await login(pc);
  await shoot(pc, spec.pc || [], 'pc');
  await pc.close();

  const mob = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  await armPrivacy(mob);
  await stubAi(mob);
  await login(mob);
  await shoot(mob, spec.mobile || [], 'mobile');
  await mob.close();

  await browser.close();
})();
