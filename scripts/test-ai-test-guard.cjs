'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const guard=require('./ai-test-guard.cjs');

test('server protection preflight and postflight fail closed',async t=>{
  let value={enabled:true,epoch:'local-window',blocked:5,sent:0};
  const server=http.createServer((req,res)=>{assert.equal(req.url,'/health');res.setHeader('Content-Type','application/json');res.end(JSON.stringify({aiTest:value}));});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base='http://127.0.0.1:'+server.address().port;
  try {
    const before=await guard.begin(base);
    await t.test('counts newly blocked requests',async()=>{value.blocked=8;assert.deepEqual(await guard.finish(base,before),{blocked:3,sent:0});});
    await t.test('rejects any actual transmission',async()=>{value.sent=1;await assert.rejects(guard.state(base),/真实模型请求/);value.sent=0;});
    await t.test('rejects a changed counting window',async()=>{value.epoch='changed';await assert.rejects(guard.finish(base,before),/统计窗口/);value.epoch=before.epoch;});
    await t.test('rejects dropped counters',async()=>{value.blocked=4;await assert.rejects(guard.finish(base,before),/统计窗口/);value.blocked=8;});
    await t.test('rejects disabled or missing protection',async()=>{value.enabled=false;await assert.rejects(guard.begin(base),/未开启/);value={};await assert.rejects(guard.begin(base),/未开启/);});
  } finally {server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});
