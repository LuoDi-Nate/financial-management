/**
 * fund-stub.cjs · 在本机假扮天天基金(v1.30 · flow 44 用)
 *
 * 和 ibkr-stub 同一个道理:应用在服务器上会去请求天天基金,e2e 不能让回归依赖外网、更不能依赖「今晚净值几点出」,
 * 所以让应用(经家庭配置 fund_data_base_url,只认本机回环)去请求这个桩。用户路径仍然全部从页面走。
 *
 * 行为照 2026-10-08 在 beta 上实测的天天基金(tech-design/v1.30.md §零):
 *   · /js/fundcode_search.js          代码表(var r = [[代码,拼音缩写,名称,类型,拼音全拼], …])
 *   · /f10/lsjz?fundCode=…            不带 Referer → HTTP 200 + {"Data":"","ErrCode":-999};
 *                                      查无此码 → ErrCode 0 + 空列表;
 *                                      货币基金:DWJZ = 每万份收益、LJJZ = 七日年化、SYType = "每万份收益",每个自然日一条;
 *                                      pageSize 静默封顶 20,按 startDate / endDate + pageIndex 翻页,TotalCount 照实
 *   · /pingzhongdata/{code}.js        这里一律 404(备源不在这条 flow 的范围里,单测覆盖)
 */
const http = require('http');

const CATALOG = [
  ['002943', 'GFDYZHH', '广发多因子混合', '混合型-灵活', 'GUANGFADUOYINZIHUNHE'],
  ['270042', 'GFNSDK100ETFLJRMBQDIIA', '广发纳斯达克100ETF联接人民币(QDII)A', '指数型-海外股票', 'GUANGFANASIDAKE'],
  ['000055', 'GFNSDK100ETFLJMYQDIIA', '广发纳斯达克100ETF联接美元(QDII)A', '指数型-海外股票', 'GUANGFANASIDAKE'],
  ['000198', 'THYEBHB', '天弘余额宝货币', '货币型-普通货币', 'TIANHONGYUEBAOHUOBI'],
  ['007708', 'ZYRFFDJZXHBA', '中银瑞福浮动净值型货币A', '货币型-浮动净值', 'ZHONGYINRUIFU'],
  ['005156', 'JSLHZCPZHHA', '嘉实领航资产配置混合A', 'FOF-稳健型', 'JIASHILINGHANG'],
];

const ymd = (d) => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(d);
const addDays = (s, n) => { const d = new Date(s + 'T12:00:00+08:00'); d.setDate(d.getDate() + n); return ymd(d); };

/**
 * @returns {Promise<{server, port, hits, setNav(code, date, nav), failNav(code, mode), setMmf(code, per10k, yield7d, latest)}>}
 */
function start() {
  const hits = [];
  const today = ymd(new Date());
  const navs = {
    '002943': { date: addDays(today, -1), nav: '4.7800' },
    '270042': { date: addDays(today, -2), nav: '8.3418' },
    '005156': { date: addDays(today, -2), nav: '1.2028' },
  };
  const fails = {};                       // code → 'empty' | 'deny'
  const mmf = { '000198': { per10k: '0.2253', yield7d: '0.8300', latest: addDays(today, -1) } };

  const json = (res, obj) => { res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)); };
  const lsjzEnvelope = (list, extra, total) => ({
    Data: Object.assign({ LSJZList: list, FundType: '', SYType: null, isNewType: false, Feature: null }, extra || {}),
    ErrCode: 0, ErrMsg: null, TotalCount: total == null ? list.length : total, Expansion: null,
    PageSize: list.length, PageIndex: 1,
  });

  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      const u = new URL(req.url, 'http://x');
      hits.push({ path: u.pathname, code: u.searchParams.get('fundCode'), referer: req.headers.referer || '' });
      if (u.pathname === '/js/fundcode_search.js') {
        res.writeHead(200, { 'Content-Type': 'application/javascript; charset=utf-8' });
        res.end('﻿var r = ' + JSON.stringify(CATALOG) + ';');
        return;
      }
      if (u.pathname === '/f10/lsjz') {
        if (!req.headers.referer) return json(res, { Data: '', ErrCode: -999, ErrMsg: '', TotalCount: 0 });
        const code = u.searchParams.get('fundCode');
        const pageSize = Math.min(20, Number(u.searchParams.get('pageSize') || 20));   // 静默封顶 20
        const pageIndex = Number(u.searchParams.get('pageIndex') || 1);
        if (fails[code] === 'deny') return json(res, { Data: '', ErrCode: -999, ErrMsg: '', TotalCount: 0 });
        if (fails[code] === 'empty' || (!navs[code] && !mmf[code])) return json(res, lsjzEnvelope([], {}, 0));
        if (mmf[code]) {
          const m = mmf[code];
          const end = u.searchParams.get('endDate') || m.latest;
          const startD = u.searchParams.get('startDate') || addDays(m.latest, -40);
          const last = end < m.latest ? end : m.latest;
          const rows = [];
          for (let d = last; d >= startD; d = addDays(d, -1)) rows.push({ FSRQ: d, DWJZ: m.per10k, LJJZ: m.yield7d, SGZT: '开放申购', SHZT: '开放赎回', FHSP: '' });
          const page = rows.slice((pageIndex - 1) * pageSize, pageIndex * pageSize);
          return json(res, lsjzEnvelope(page, { FundType: '005', SYType: '每万份收益' }, rows.length));
        }
        const n = navs[code];
        return json(res, lsjzEnvelope(pageIndex === 1 ? [{ FSRQ: n.date, DWJZ: n.nav, LJJZ: n.nav, SGZT: '开放申购', SHZT: '开放赎回', FHSP: '' }] : [],
                                      { FundType: '002' }, 1));
      }
      res.writeHead(404); res.end('not found');
    });
    server.listen(0, '127.0.0.1', () => resolve({
      server, port: server.address().port, hits, today,
      setNav: (code, date, nav) => { navs[code] = { date, nav }; delete fails[code]; },
      failNav: (code, mode) => { fails[code] = mode || 'empty'; },
      setMmf: (code, per10k, yield7d, latest) => { mmf[code] = { per10k, yield7d, latest: latest || mmf[code].latest }; },
      addDays,
    }));
  });
}

/**
 * 家里已有的净值行(不在这条 flow 的账户里)先停用:刷新是全家一起刷的,桩不认识它们的代码 → 会被标成「没拉到」,
 * flow 里「刷了几只基金 / 没有点名失败」的断言也会被它们带偏。停用 = 只清 nav_mode(估值照旧按手填单价 × 份额,余额不动);
 * cleanup 把净值相关的几列逐字还回去。
 */
function parkOtherNavRows(db, fam, keepAccountIds) {
  const keep = (keepAccountIds || []).filter(Boolean);
  const rows = db.col(`SELECT CONCAT_WS('|', h.id, h.nav_mode, IFNULL(h.nav_date,''), IFNULL(h.nav_checked_at,''),
                              IFNULL(h.nav_error,''), IFNULL(h.manual_value_at,''))
                         FROM stock_holding h JOIN account a ON a.id = h.account_id
                        WHERE a.family_id=${fam} AND h.nav_mode IS NOT NULL AND h.archived_at IS NULL
                          ${keep.length ? `AND h.account_id NOT IN (${keep.join(',')})` : ''}`);
  if (rows.length) db.raw(`UPDATE stock_holding SET nav_mode = NULL WHERE id IN (${rows.map(r => r.split('|')[0]).join(',')})`);
  return rows;
}

function restoreParked(db, rows) {
  const q = (v) => v === '' ? 'NULL' : `'${v}'`;
  for (const r of rows || []) {
    const [id, mode, navDate, checked, err, at] = r.split('|');
    db.raw(`UPDATE stock_holding SET nav_mode=${q(mode)}, nav_date=${q(navDate)}, nav_checked_at=${q(checked)},
            nav_error=${q(err)}, manual_value_at=${q(at)} WHERE id=${id}`);
  }
}

module.exports = { start, addDays, ymd, parkOtherNavRows, restoreParked };
