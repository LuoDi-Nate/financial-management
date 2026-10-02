-- ============================================================================
-- V66 · v1.29 · 券商同步带上期权 / 期货 / 债券(prd/v1.29.md · tech-design/v1.29.md 三.6)
--
-- 只加不改:
--   ① ticker 加宽 16 → 48:盈透的期权代码是 21 个字符(「GOOGL 270416C00360000」),
--      富途的美股期权代码 17 个字符(「AAPL260116C250000」)—— 后者在 v1.28 以前就会让富途同步整次失败
--      (STRICT_TRANS_TABLES 下 Data too long),不是只少算一行。
--   ② 衍生品 / 债券的说明列,全部可空。旧行全是 NULL = 股票 / 基金 / 现金,行为不变。
--      估值不读这些列:期权等仍是「手动估值」行(单价 × 张数,张数卖出为负),估值路径一行没改。
--
-- 回滚:回滚 jar 即可。老代码不读新列;同步来的期权行是 MANUAL + sync_source,
--      老代码下一次同步时按「券商那边没有」把它们归档,余额回到「现金 + 股票」。
-- ============================================================================

ALTER TABLE stock_holding
  MODIFY COLUMN ticker VARCHAR(48) NULL
             COMMENT 'AUTO 时必填 · BABA / 600519 / 00700;券商同步的期权等存券商原代码(v1.29 加宽到 48)',
  ADD COLUMN instrument_kind VARCHAR(12) NULL
             COMMENT 'v1.29 · OPTION / WARRANT / FUTURE / BOND;NULL = 股票 / 基金 / 现金等' AFTER penetrate_state,
  ADD COLUMN underlying      VARCHAR(32) NULL
             COMMENT 'v1.29 · 标的代码(期权 / 期货)' AFTER instrument_kind,
  ADD COLUMN put_call        CHAR(1) NULL
             COMMENT 'v1.29 · C 看涨 / P 看跌' AFTER underlying,
  ADD COLUMN strike          DECIMAL(20,6) NULL
             COMMENT 'v1.29 · 行权价(原币)' AFTER put_call,
  ADD COLUMN expiry          DATE NULL
             COMMENT 'v1.29 · 到期日' AFTER strike,
  ADD COLUMN multiplier      DECIMAL(20,6) NULL
             COMMENT 'v1.29 · 合约乘数(美股期权通常 100)' AFTER expiry,
  ADD COLUMN quote_price     DECIMAL(24,8) NULL
             COMMENT 'v1.29 · 券商给的标记价(原币 · 每股 / 每单位)· 只展示' AFTER multiplier,
  ADD COLUMN notional        DECIMAL(24,4) NULL
             COMMENT 'v1.29 · 期货名义价值(原币)· 只展示,不计入余额' AFTER quote_price;
