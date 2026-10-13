-- v1.32 · 固定收支(PRD v1.32 · tech-design/v1.32.md 选型一)
--
-- 两张新表,不改任何旧表:
--   fixed_flow            一条「每期都会有」的设置(收入 / 支出 / 还款 / 交保费)
--   fixed_flow_occurrence 某条在某一期的结果(确认 / 这期没有 / 就是导入的那一笔)+ 这次写了哪几笔的 id
--
-- 这两张表**不进任何金额计算** —— 钱只在确认时写进去的流水 / 划转 / 快照里(那些走的是既有写入方法)。
-- 回滚:老 jar 不读这两张表,已确认写进去的都是普通流水 / 划转 / 快照,照常算;不需要回滚 SQL。
CREATE TABLE IF NOT EXISTS fixed_flow (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  family_id          BIGINT        NOT NULL,
  name               VARCHAR(60)   NOT NULL,
  kind               VARCHAR(12)   NOT NULL COMMENT 'INCOME 收入 / EXPENSE 支出 / REPAY 还款 / PREMIUM 交保费',
  repay_kind         VARCHAR(8)    NULL     COMMENT 'REPAY 才有:LOAN 贷款月供 / CARD 信用卡账单',
  amount_mode        VARCHAR(8)    NOT NULL DEFAULT 'FIXED' COMMENT 'FIXED 固定金额 / AUTO 自动计算',
  auto_method        VARCHAR(12)   NULL     COMMENT 'AUTO 才有:LOAN_DIFF / LOAN_RATE / CARD_FULL / POLICY / AVG3',
  amount             DECIMAL(18,2) NULL     COMMENT '固定金额;LOAN_RATE 时是月供;AVG3 时是还没确认过时的起始金额(账户币种)',
  account_id         BIGINT        NOT NULL COMMENT '收入:入账账户;支出:付款账户;还款 / 交保费:从哪个账户付',
  target_account_id  BIGINT        NULL     COMMENT '还款:贷款 / 信用卡;交保费:保险账户',
  category_code      VARCHAR(40)   NULL     COMMENT '收入类目 / 支出性质(cash_flow_category.code)',
  expense_category_id BIGINT       NULL     COMMENT '消费分类(只在支出性质是 consumption 时)',
  cycle              VARCHAR(8)    NOT NULL DEFAULT 'MONTHLY' COMMENT 'MONTHLY 每月 / YEARLY 每年的某几个月',
  months             VARCHAR(40)   NULL     COMMENT 'YEARLY 才有:"3" 或 "3,9"',
  day_of_month       TINYINT       NOT NULL COMMENT '几号(1–31;当月没有就取最后一天)',
  owner_member_id    BIGINT        NULL     COMMENT '负责人;NULL = 共用',
  start_month        DATE          NOT NULL COMMENT '从哪个月开始(当月 1 日)',
  end_month          DATE          NULL     COMMENT '到哪个月为止(当月 1 日);NULL = 一直有',
  note               VARCHAR(255)  NULL,
  disabled_at        TIMESTAMP     NULL     COMMENT '停用时间;停用不影响已记的',
  disabled_reason    VARCHAR(24)   NULL     COMMENT 'MANUAL / ACCOUNT_ARCHIVED',
  created_by         BIGINT        NULL,
  created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  INDEX idx_ff_family (family_id),
  INDEX idx_ff_account (account_id),
  INDEX idx_ff_target (target_account_id),
  CONSTRAINT ck_ff_kind CHECK (kind IN ('INCOME','EXPENSE','REPAY','PREMIUM')),
  CONSTRAINT ck_ff_day CHECK (day_of_month BETWEEN 1 AND 31)
) COMMENT 'v1.32 · 固定收支:每期都会有的那几笔(设置本身,不是流水)';

CREATE TABLE IF NOT EXISTS fixed_flow_occurrence (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,
  family_id             BIGINT        NOT NULL,
  fixed_flow_id         BIGINT        NOT NULL,
  period_id             BIGINT        NOT NULL,
  due_date              DATE          NOT NULL COMMENT '这一期里该有的那天',
  status                VARCHAR(10)   NOT NULL COMMENT 'CONFIRMED 已确认 / SKIPPED 这期没有 / LINKED 就是导入(或刚记)的那一笔',
  amount                DECIMAL(18,2) NULL     COMMENT '这一期确认的金额(账户币种)',
  principal             DECIMAL(18,2) NULL     COMMENT '还款 · 贷款月供:本金',
  interest              DECIMAL(18,2) NULL     COMMENT '还款 · 贷款月供:利息(方式 ② 才有)',
  cash_flow_id          BIGINT        NULL     COMMENT '写进去的收入 / 支出 / 还贷流水;LINKED 时是被关联的那一笔',
  interest_cash_flow_id BIGINT        NULL     COMMENT '写进去的利息支出流水',
  transfer_id           BIGINT        NULL     COMMENT '写进去的划转(信用卡账单 / 交保费)',
  loan_balance_before   DECIMAL(18,2) NULL     COMMENT '确认前贷款本期余额;NULL = 没动贷款余额',
  loan_balance_after    DECIMAL(18,2) NULL     COMMENT '确认后写进去的贷款本期余额',
  formula               VARCHAR(255)  NULL     COMMENT '自动计算的算式(当时的)',
  member_id             BIGINT        NULL     COMMENT '谁点的',
  created_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ffo_flow_period (fixed_flow_id, period_id),
  INDEX idx_ffo_family_period (family_id, period_id),
  CONSTRAINT ck_ffo_status CHECK (status IN ('CONFIRMED','SKIPPED','LINKED'))
) COMMENT 'v1.32 · 固定收支每期的结果(确认 / 这期没有 / 关联),记下写了哪几笔以便撤销';
