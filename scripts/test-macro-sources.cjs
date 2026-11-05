'use strict';
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const db=require('./e2e/lib/db.cjs');

// A connection-local temporary table shadows the public table only in this
// connection. The actual migration runs unchanged; household data is untouched.
const migration=fs.readFileSync(path.join(__dirname,'../db/migration/V79__verified_macro_sources.sql'),'utf8');
const result=db.raw(`CREATE TEMPORARY TABLE macro_benchmark(
  year INT PRIMARY KEY,cpi_headline DECIMAL(6,2),m2_growth DECIMAL(6,2),source VARCHAR(40),fetched_at DATETIME(3));
  INSERT INTO macro_benchmark VALUES
  (2016,2.00,8.00,'seed','2026-01-01'),(2017,99.99,8.00,'seed','2026-01-01'),
  (2018,2.10,8.00,'legacy','2026-01-01'),(2023,0.35,8.00,'manual','2025-02-01'),
  (2024,0.20,8.00,'seed','2026-01-01'),(2025,0.30,8.00,'seed','2026-01-01');
  ${migration}
  SELECT JSON_ARRAYAGG(JSON_OBJECT('year',year,'cpi',cpi_headline,'m2',m2_growth,'source',source,
    'url',source_url,'verified',verified_at)) FROM macro_benchmark;
  DROP TEMPORARY TABLE macro_benchmark;`);
const rows=new Map(JSON.parse(result).map(r=>[r.year,r]));
assert.equal(rows.get(2025).cpi,0);assert.equal(rows.get(2025).source,'official-nbs');
assert.equal(rows.get(2016).cpi,2);assert.match(rows.get(2016).url,/^https:\/\/www.stats.gov.cn\//);
assert.equal(rows.get(2017).cpi,99.99);assert.equal(rows.get(2017).source,'seed');assert.equal(rows.get(2017).verified,null);
assert.equal(rows.get(2018).source,'legacy');assert.equal(rows.get(2018).verified,null);
assert.equal(rows.get(2023).cpi,0.35);assert.equal(rows.get(2023).source,'manual');assert.match(rows.get(2023).verified,/^2025-02-01/);
assert.ok([...rows.values()].every(r=>r.m2===8));
console.log('PASS: V79已知种子更正、异常种子保留、其他来源保留、人工校正保留、核验出处及M2不变');
