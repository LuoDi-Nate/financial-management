/**
 * ibkr-stub.cjs · 在本机假扮 IBKR Flex Web Service(v1.26 · flow 27 用)
 *
 * ── 为什么放在 lib/ 而不是写在 flow 里 ──
 *
 * flow 的规矩是「动作一律从页面元素发起,不许在 flow 里打端点」(护栏 v1230-E2E-IS-BROWSER-DRIVEN)。
 * 这个桩不是去打我们自己的端点,而是**扮演第三方**:应用在服务器上会去请求 IBKR,e2e 不能打真实 IBKR
 * (没有真实口令,也不该让回归依赖外网),所以让应用去请求它。用户路径仍然全部从页面走。
 *
 * 行为照 beta 实测过的 IBKR:失败也是 HTTP 200 + <FlexStatementResponse> 信封;不带 User-Agent 是 403。
 */
const http = require('http');

function envelope(code, msg) {
  return `<FlexStatementResponse timestamp='x'>\n<Status>Fail</Status>\n<ErrorCode>${code}</ErrorCode>\n<ErrorMessage>${msg}</ErrorMessage>\n</FlexStatementResponse>`;
}

/**
 * @param {string} reportXml     GetStatement 要回放的报表
 * @param {string} expiredToken  用这个口令发 SendRequest 时回 1012(口令过期)
 * @returns {Promise<{server, port, hits: Array<{path, ua}>, setReport: (xml: string) => void}>}
 *          setReport:换一份报表(v1.29 flow 41 用:模拟「过了几天,有一张期权到期了」)
 */
function start(reportXml, expiredToken) {
  const hits = [];
  const cur = { xml: reportXml };
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      const u = new URL(req.url, 'http://x');
      hits.push({ path: u.pathname, ua: req.headers['user-agent'] || '' });
      if (!req.headers['user-agent']) { res.writeHead(403); res.end('Error 403 - Access Denied'); return; }
      res.writeHead(200, { 'Content-Type': 'text/xml' });
      if (u.pathname.endsWith('/SendRequest')) {
        if (u.searchParams.get('t') === expiredToken) { res.end(envelope(1012, 'Token has expired.')); return; }
        res.end("<FlexStatementResponse timestamp='x'><Status>Success</Status><ReferenceCode>555001</ReferenceCode></FlexStatementResponse>");
        return;
      }
      res.end(cur.xml);
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, port: server.address().port, hits,
                                                   setReport: (xml) => { cur.xml = xml; } }));
  });
}

module.exports = { start };
