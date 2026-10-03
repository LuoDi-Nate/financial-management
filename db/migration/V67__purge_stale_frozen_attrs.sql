-- ============================================================================
-- V67 · v1.29.1 · issue #34 · 清掉「不可能是真定格」的定格行
--
-- 怎么来的:全新 Docker 安装时,迁移会先灌演示数据,V54 再给演示数据里已关账的期回填定格行
-- (period_account_attr.source = 'BACKFILL');随后清演示数据的脚本只 TRUNCATE 了 period / account,
-- 没清定格表。编号从 1 重新开始,于是演示账户的类型按 (期编号, 账户编号) 挂到了用户自己的账户上:
-- 提交者 10 月(还没关账)这一期里,「中国银行(现金)」被当成了贷款、一张信用卡被当成了房产。
--
-- 判据只用两条「真定格不可能满足」的事实,不碰任何正常数据:
--   ① 定格只在关账时写、重开时删 → 没关账的期不该有定格行;
--   ② 定格发生在关账那一刻 → 不可能早于这一期 / 这个账户被创建的时间。
--   另外清掉期或账户已经不存在的孤儿行。
-- beta 实测:四条判据各命中 0 行(正常库是 no-op);新装的库会命中演示残留。
--
-- 回滚:回滚 jar 即可;删掉的行本来就是错的,新代码读的那一侧也已经只认已关账的期。
-- ============================================================================

DELETE pa FROM period_account_attr pa
  JOIN period p ON p.id = pa.period_id
 WHERE p.status <> 'CLOSED';

DELETE pa FROM period_account_attr pa
  JOIN period p ON p.id = pa.period_id
 WHERE pa.sealed_at < p.created_at;

DELETE pa FROM period_account_attr pa
  JOIN account a ON a.id = pa.account_id
 WHERE pa.sealed_at < a.created_at;

DELETE pa FROM period_account_attr pa
  LEFT JOIN period p ON p.id = pa.period_id
  LEFT JOIN account a ON a.id = pa.account_id
 WHERE p.id IS NULL OR a.id IS NULL;

-- 分组定格(v1.20)同一条规则。演示数据里没有分组,这里是防以后。
DELETE pg FROM period_account_group pg
  JOIN period p ON p.id = pg.period_id
 WHERE p.status <> 'CLOSED';

DELETE pg FROM period_account_group pg
  JOIN period p ON p.id = pg.period_id
 WHERE pg.sealed_at < p.created_at;

DELETE pg FROM period_account_group pg
  JOIN account a ON a.id = pg.account_id
 WHERE pg.sealed_at < a.created_at;
