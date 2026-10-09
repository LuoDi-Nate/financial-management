-- v1.30 · 补录本金(PRD v1.30 FR-973 ~ 975 · tech-design/v1.30.md 选型十二)
--
-- 一笔「以前就有、这期才补录进来」的钱:计入本金(累计净投入 / 开账基线),既不算收入、也不算投资收益。
-- 只给**已有历史**的账户用 —— 新账户第一期的余额本来就整笔算开账基线,不需要它。
--
-- 回滚:老 jar 不认识这张表,直接忽略 —— 补录过的那几笔会退回「算成那一期的收益」(与 v1.30 之前一致),
-- 不报错、不丢数据;再升级回来又按本金算。所以 db/rollback 不需要为它写任何东西。
CREATE TABLE IF NOT EXISTS principal_adjustment (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  family_id   BIGINT        NOT NULL,
  account_id  BIGINT        NOT NULL,
  period_id   BIGINT        NOT NULL COMMENT '记在哪一期(那一期的余额里含这笔)',
  amount      DECIMAL(18,2) NOT NULL COMMENT '账户币种',
  holding_id  BIGINT        NULL     COMMENT '添加基金 / 改份额时顺带记的,指向那只持仓;账户详情里手记的为 NULL',
  note        VARCHAR(255)  NULL,
  source_tag  VARCHAR(32)   NOT NULL DEFAULT 'MANUAL' COMMENT 'v1.18 流水来源(只有人会记它)',
  member_id   BIGINT        NULL,
  created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  deleted_at  TIMESTAMP     NULL,
  PRIMARY KEY (id),
  INDEX idx_pa_family_period (family_id, period_id),
  INDEX idx_pa_account (account_id),
  CONSTRAINT ck_pa_amount CHECK (amount > 0)
) COMMENT 'v1.30 · 补录本金:以前就有、这期才补录进来的钱(算本金,不算收入也不算收益)';
