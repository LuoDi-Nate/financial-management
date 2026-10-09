-- ============================================================================
-- V68 · v1.30 · 场外基金按代码自动估值(prd/v1.30.md · tech-design/v1.30.md 三.6 · issue #25)
--
-- ① stock_holding 加五列(全可空):基金行仍是 MANUAL 行(份额 × 单价),这几列只说明
--    「单价由系统按净值写」。旧行全是 NULL = 原有行为,估值路径一行没改。
-- ② fund_nav_snapshot:公共行情缓存(同 stock_price_snapshot 的性质,不分家庭)。
--    普通基金存单位净值,货币基金存每万份收益 / 七日年化,键都是 (代码, 日期)。
-- ③ holding_share_event:「持仓数量变动」—— 只给人看,任何金额汇总都不读它。
-- ④ 账户类型加 FUND(基金账户):照 V44 放宽两处 CHECK;模板「蚂蚁财富(基金)」改成这个类型;
--    基金类的产品类目适用类型加上 FUND。改的是字典行,用户的账户 / 持仓 / 流水一行不动。
--
-- 回滚:回滚 jar 前,若已有人建了基金账户,先执行 db/rollback/v1.30.sql(把 FUND 改回 WEALTH)——
--      老 jar 的账户类型枚举不认识 FUND。deploy/rollback.sh 会自动判断并执行。
--      其余新列、新表老 jar 都不读,留着无害。
-- ============================================================================

ALTER TABLE stock_holding
  ADD COLUMN nav_mode            VARCHAR(8)  NULL COMMENT 'v1.30 · FUND = 单价按单位净值自动更新 / MMF = 货币基金按金额记、每日结转;NULL = 原有行为',
  ADD COLUMN nav_date            DATE        NULL COMMENT 'v1.30 · FUND:单价是哪天的净值;MMF:收益已结转到哪一天',
  ADD COLUMN nav_checked_at      TIMESTAMP   NULL COMMENT 'v1.30 · 系统最近一次写这一行的时间(与 manual_value_at 同时写;别处改过 → manual_value_at 更晚)',
  ADD COLUMN nav_error           VARCHAR(64) NULL COMMENT 'v1.30 · 最近一次没拿到 / 没结转的原因;NULL = 正常',
  ADD COLUMN shares_estimated_on DATE        NULL COMMENT 'v1.30 · 份额是按哪天的净值从市值反推的;用户改过份额后清空';

CREATE TABLE IF NOT EXISTS fund_nav_snapshot (
  fund_code      VARCHAR(12)   NOT NULL,
  nav_date       DATE          NOT NULL,
  unit_nav       DECIMAL(20,6) NULL COMMENT '普通基金:单位净值',
  income_per_10k DECIMAL(12,4) NULL COMMENT '货币基金:每万份收益(元)',
  yield_7d       DECIMAL(8,4)  NULL COMMENT '货币基金:七日年化(%)',
  source         VARCHAR(16)   NOT NULL COMMENT 'eastmoney-lsjz / eastmoney-pz',
  fetched_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (fund_code, nav_date),
  INDEX idx_fetched (fund_code, fetched_at)
) COMMENT 'v1.30 · 天天基金净值 / 万份收益缓存(公共行情,不分家庭)';

CREATE TABLE IF NOT EXISTS holding_share_event (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  family_id     BIGINT        NOT NULL,
  account_id    BIGINT        NOT NULL,
  holding_id    BIGINT        NOT NULL,
  period_id     BIGINT        NULL,
  reason        VARCHAR(24)   NOT NULL COMMENT 'MMF_ACCRUAL / MANUAL_EDIT / MANUAL_CORRECTION / CASH_BUY / CASH_REDEEM / IMPORT / CONVERT',
  shares_before DECIMAL(15,4) NULL,
  shares_after  DECIMAL(15,4) NULL,
  shares_delta  DECIMAL(15,4) NOT NULL,
  unit_value    DECIMAL(20,6) NULL COMMENT '当时的单价(账户币种):基金 = 单位净值,货币基金 = 1',
  value_delta   DECIMAL(15,2) NULL COMMENT '约合金额 —— 只写给人看,不进任何汇总',
  date_from     DATE          NULL COMMENT '结转类:区间起',
  date_to       DATE          NULL COMMENT '结转类:区间止',
  member_id     BIGINT        NULL COMMENT '谁操作的;系统自动 = NULL',
  created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_account_time (account_id, created_at),
  INDEX idx_family (family_id)
) COMMENT 'v1.30 · 持仓数量变动(只给人看;钱的变化只在 stock_valuation_event)';

-- ④ 基金账户
ALTER TABLE account_template DROP CHECK ck_account_template_type;
ALTER TABLE account_template
    ADD CONSTRAINT ck_account_template_type
        CHECK (type IN ('STOCK','CASH','WEALTH','CRYPTO','METAL','PROPERTY','LOAN','OTHER','INSURANCE','FUND'));

ALTER TABLE account DROP CHECK ck_account_type;
ALTER TABLE account
    ADD CONSTRAINT ck_account_type
        CHECK (type IN ('STOCK','CASH','WEALTH','CRYPTO','METAL','PROPERTY','LOAN','OTHER','INSURANCE','FUND'));

UPDATE account_template
   SET type = 'FUND', display_name = '基金账户(蚂蚁财富 / 天天基金 / 京东金融)'
 WHERE code = 'ant_fortune';

UPDATE product_category
   SET applicable_types = CONCAT(applicable_types, ',FUND')
 WHERE code IN ('MONEY_FUND', 'SHORT_BOND', 'LONG_BOND', 'MIXED_FUND', 'A_STOCK', 'HK_STOCK', 'US_STOCK', 'GOLD')
   AND FIND_IN_SET('FUND', applicable_types) = 0;
