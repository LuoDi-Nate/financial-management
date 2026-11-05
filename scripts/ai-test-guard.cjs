#!/usr/bin/env node
'use strict';
const fs = require('fs');

async function state(base) {
  const response = await fetch(base.replace(/\/$/, '') + '/health', {signal:AbortSignal.timeout(15000)});
  if (!response.ok) throw new Error('无法确认服务端模型测试保护:健康检查失败');
  const value = (await response.json()).aiTest;
  if (!value || value.enabled !== true || !value.epoch || !Number.isFinite(value.blocked) || !Number.isFinite(value.sent))
    throw new Error('服务端“测试环境:不调大模型”未开启。请到管理 > AI 接入开启后再跑;真实调用需明确设置 E2E_REAL_AI=1 或 QA_LLM_LIVE=1。');
  if (value.sent !== 0) throw new Error('服务端测试窗口已有真实模型请求,停止回归');
  return value;
}

async function begin(base, live = false) { return live ? null : state(base); }
async function finish(base, before, live = false) {
  if (live || !before) return null;
  const after = await state(base);
  if (after.epoch !== before.epoch || after.blocked < before.blocked)
    throw new Error('模型测试保护的统计窗口中途被修改,本轮验证无效');
  const result = {blocked:after.blocked-before.blocked, sent:after.sent-before.sent};
  console.log('模型测试保护:这一轮拦下 '+result.blocked+' 次 · 发出去 '+result.sent+' 次');
  return result;
}

module.exports = {begin, finish, state};
if (require.main === module) {
  const [command, base, file] = process.argv.slice(2);
  const live = process.env.E2E_REAL_AI === '1' || process.env.QA_LLM_LIVE === '1';
  (async()=>{
    if (!base || !file || !['begin','end'].includes(command)) throw new Error('用法:ai-test-guard.cjs begin|end <base> <state-file>');
    if (command === 'begin') fs.writeFileSync(file, JSON.stringify(await begin(base,live)), {mode:0o600});
    else await finish(base, JSON.parse(fs.readFileSync(file,'utf8')),live);
  })().catch(e=>{console.error(e.message);process.exitCode=1;});
}
