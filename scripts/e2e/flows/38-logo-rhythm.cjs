/**
 * flow · v1.28.3 · issue #27 + #28(拆自 #25)· 品牌 logo 上传 + 关账节奏选中态
 *
 * ① 提交者:png / webp / jpg 都试了,经常「上传失败 400 must be image/webp」,或者上传成功却变回以前设过的那张;
 *    换浏览器还是 400。
 *    根因两处:Safari 和 iPhone / iPad 上所有浏览器(都是 WebKit)不会把 Canvas 编码成 WebP,toBlob 悄悄给 PNG,
 *    后端只收 WebP;以及文件名永远是 logo.webp、/uploads 又缓存 7 天 —— 换了图浏览器照样显示旧的。
 *    这里另开一个会话,把 Canvas 的 WebP 编码换成「给 PNG」(就是 Safari 的行为),从「管理 → 家庭设置」真的选文件上传;
 *    再用正常 Chromium 上传一张,确认 WebP 那条路没坏;两次地址不同、旧文件已删;最后点「移除 logo」还原。
 * ② 「管理 → 周期 → 账期什么时候关」:点了别的档位没有任何变化,要保存后才高亮。
 *    这里点一张没选的卡:它高亮、原来那张不再高亮、旁边写「已选「…」· 还没保存」;点回原来那张,提示消失。不保存。
 */
const fs = require('fs');
const path = require('path');
const db = require('../lib/db.cjs');
const fx = require('../lib/fixture.cjs');
const { BASE, USER, PASS } = require('../lib/browser.cjs');

const IMG = n => fs.readFileSync(path.join(__dirname, '../../../src/main/resources/static/img/presets', n));
const state = {};

/** 管理首页点「家庭设置」卡片,选一张图上传,等页面刷新;返回顶栏 logo 的地址 */
async function upload(ui, page, file, label) {
  await page.goto(BASE + '/', { waitUntil: 'networkidle' });
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}), page.click('nav a:has-text("管理") >> visible=true')]);
  await Promise.all([page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}), page.click('main a[href="/admin/family"]:has(h3) >> nth=0')]);
  let alertMsg = null;
  page.once('dialog', d => { alertMsg = d.message(); d.accept().catch(() => {}); });
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle', timeout: 20000 }).catch(() => {}),
    page.setInputFiles('#logoFile', { name: file, mimeType: 'image/png', buffer: IMG(file) }),
  ]);
  await page.waitForTimeout(800);
  await ui.assert(alertMsg === null, `${label}:上传没有报错`, alertMsg || '');
  return page.getAttribute('header img[alt="logo"]', 'src').catch(() => null);
}

module.exports = {
  name: '38-logo-rhythm',
  title: 'v1.28.3 · #27 · #28 · Safari / iPhone 上传 logo 不再 400、换图不再显示旧图 · 关账节奏点了就看得出选中',

  async run(ui, report) {
    ui.flow = this.name;
    state.fam = db.one(`SELECT CONCAT(IFNULL(logo_path,''),'|',IFNULL(logo_preset,'')) FROM family WHERE id=${fx.FAM}`);

    // ── ① 像 Safari 那样:Canvas 编不出 WebP ─────────────────────────────
    report.section('1 · 管理 → 家庭设置:在「编不出 WebP」的浏览器上传(Safari / iPhone 的行为)');
    const ctx = await ui.page.context().browser().newContext({ viewport: { width: 1440, height: 900 } });
    try {
      await ctx.addInitScript(() => {
        const orig = HTMLCanvasElement.prototype.toBlob;
        HTMLCanvasElement.prototype.toBlob = function (cb, type, q) {
          return orig.call(this, cb, type === 'image/webp' ? 'image/png' : type, q);   // WebKit:悄悄给 PNG
        };
      });
      const pg = await ctx.newPage();
      await pg.goto(BASE + '/login', { waitUntil: 'networkidle' });
      await pg.fill('input[name=username]', USER); await pg.fill('input[name=password]', PASS);
      await Promise.all([pg.waitForNavigation({ waitUntil: 'networkidle' }), pg.click('button[type=submit]')]);

      const src1 = await upload(ui, pg, 'icon1-512.png', 'Safari 式上传 PNG');
      await ui.assert(/\/uploads\/family-\d+\/logo-[0-9a-f]{10}\.png$/.test(src1 || ''), '顶栏 logo 换成了刚传的这张(PNG,文件名带指纹)', src1);
      const p1 = db.one(`SELECT logo_path FROM family WHERE id=${fx.FAM}`);
      await ui.assert(src1 && src1.endsWith(p1), '真值层:库里 logo_path 就是这张', p1);
      const r1 = await pg.request.get(BASE + src1);
      await ui.assert(r1.status() === 200 && /image\/png/.test(r1.headers()['content-type'] || ''), '这张图取得到,类型是 image/png', `${r1.status()} ${r1.headers()['content-type']}`);

      const src2 = await upload(ui, pg, 'icon3-512.png', 'Safari 式再换一张');
      await ui.assert(src2 && src2 !== src1, '换一张图之后地址变了(不会再显示缓存里的旧图)', `${src1} → ${src2}`);
      const old = await pg.request.get(BASE + src1);
      await ui.assert(old.status() === 404, '上一张的文件已删掉', `旧地址 ${old.status()}`);
    } finally { await ctx.close(); }

    // ── 正常 Chromium:WebP 那条路没坏 ───────────────────────────────────
    report.section('2 · 正常浏览器(能编 WebP)上传');
    const src3 = await upload(ui, ui.page, 'icon2-512.png', 'Chromium 上传');
    await ui.assert(/\/uploads\/family-\d+\/logo-[0-9a-f]{10}\.webp$/.test(src3 || ''), '能编 WebP 的浏览器照旧存成 WebP', src3);

    // 移除 logo(用户就是这么还原的)
    ui.page.once('dialog', d => d.accept().catch(() => {}));
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}),
                       ui.click('#logoRemoveBtn', '点「移除 logo」')]);
    await ui.assert(db.one(`SELECT logo_path IS NULL FROM family WHERE id=${fx.FAM}`) === '1', '真值层:logo_path 清空,回到预设图标');
    const gone = await ui.page.request.get(BASE + src3);
    await ui.assert(gone.status() === 404, '移除之后文件也删了', `${gone.status()}`);

    // ── ② 关账节奏:点了就看得出 ──────────────────────────────────────
    report.section('3 · 管理 → 周期 →「账期什么时候关」:点一张卡就看得出选中了');
    await ui.goto('/');
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}), ui.click('nav a:has-text("管理") >> visible=true', '顶部导航点「管理」')]);
    await Promise.all([ui.page.waitForNavigation({ waitUntil: 'networkidle' }).catch(() => {}), ui.click('main a[href="/admin/periods"]:has(h3) >> nth=0', '管理首页点「周期」卡片')]);
    const look = () => ui.page.evaluate(() => {
      const cards = [...document.querySelectorAll('#rhythm-form .rhythm-opt')];
      const base = getComputedStyle(cards.find(c => !c.querySelector('input').checked)).borderTopColor;
      return {
        on: cards.map((c, i) => getComputedStyle(c).borderTopColor !== base ? i : -1).filter(i => i >= 0),
        checked: cards.findIndex(c => c.querySelector('input').checked),
        tip: (() => { const t = document.getElementById('rhythm-dirty'); return t && !t.hidden ? t.textContent.replace(/\s+/g, ' ').trim() : null; })(),
      };
    });
    const before = await look();
    const target = before.checked === 1 ? 2 : 1;
    await ui.click(`#rhythm-form .rhythm-opt >> nth=${target}`, '点一张没选中的档位卡');
    await ui.page.mouse.move(5, 5);   // 移开鼠标,排除 hover 的描边
    await ui.page.waitForTimeout(300);
    const after = await look();
    await ui.assert(after.checked === target && after.on.length === 1 && after.on[0] === target,
      '点完就只有这一张高亮(原来那张不再高亮)', JSON.stringify({ before, after }));
    await ui.assert(!!after.tip && /还没保存/.test(after.tip), '旁边写「已选「…」· 还没保存」', after.tip || '(没有提示)');
    await ui.click(`#rhythm-form .rhythm-opt >> nth=${before.checked}`, '点回原来那张');
    await ui.page.mouse.move(5, 5);
    await ui.page.waitForTimeout(300);
    const back = await look();
    await ui.assert(back.tip === null && back.on.length === 1 && back.on[0] === before.checked, '点回原来那张:提示消失', JSON.stringify(back));
    await ui.noConsoleErrors('控制台无报错');
  },

  async cleanup(ui, report) {
    if (!state.fam) return;
    const [p, preset] = state.fam.split('|');
    db.raw(`UPDATE family SET logo_path=${p ? `'${p}'` : 'NULL'}, logo_preset=${preset ? `'${preset}'` : 'NULL'} WHERE id=${fx.FAM}`);
    report.info(`还原:logo_path=${p || 'NULL'} · 预设=${preset || 'NULL'}`);
  },
};
