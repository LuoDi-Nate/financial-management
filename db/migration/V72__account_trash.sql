-- v1.33 · 删除账户 → 回收站(PRD v1.33 · tech-design/v1.33.md 选型一 / 二)
--
-- ① account_trash:一个被删账户一行。payload = 「表名 → 该账户在这张表里的全部原行」的 JSON(SELECT * 原样),
--    原表里真删 —— 读路径一行不用改;恢复时按同一份搬运清单把行原样插回(带原 id)。
--    彻底清除只清 payload,元数据留着(报表「删除账户后重算」的留痕要用)。
-- ② principal_adjustment 放宽 CHECK:删除账户时,对方「转出给它」的那笔改记成负的补录本金(本金移出)。
--    用户的补录本金表单仍只收正数(服务端校验)。
--
-- 回滚:老 jar 不读回收站(删掉的账户在老 jar 下就是没有了,升回来才能恢复);
--       负数补录本金老 jar 照常相加,对方收益不变。不需要回滚 SQL。
CREATE TABLE IF NOT EXISTS account_trash (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  family_id        BIGINT        NOT NULL,
  account_id       BIGINT        NOT NULL COMMENT '被删账户原来的 id(恢复时原样放回)',
  account_name     VARCHAR(80)   NOT NULL,
  account_type     VARCHAR(16)   NOT NULL,
  period_ids       VARCHAR(2000) NULL     COMMENT '被改写的期(逗号分隔)—— 报表留痕按它找',
  range_label      VARCHAR(80)   NULL     COMMENT '「2025-01 至 2026-09 共 21 个账期」',
  closed_count     INT           NOT NULL DEFAULT 0,
  transfer_count   INT           NOT NULL DEFAULT 0,
  counterpart_count INT          NOT NULL DEFAULT 0,
  summary          VARCHAR(500)  NULL     COMMENT '移走了什么(给列表与审计看)',
  payload          LONGTEXT      NULL     COMMENT '各表原行;彻底清除后为 NULL',
  rewrites         LONGTEXT      NULL     COMMENT '改记写的补录本金 id、置空的引用(恢复时还原)',
  backup_file      VARCHAR(255)  NULL,
  deleted_by       BIGINT        NULL,
  deleted_at       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  restored_by      BIGINT        NULL,
  restored_at      DATETIME(3)   NULL,
  restore_notes    VARCHAR(1000) NULL     COMMENT '恢复时没能放回的项',
  purged_by        BIGINT        NULL,
  purged_at        DATETIME(3)   NULL,
  PRIMARY KEY (id),
  INDEX idx_trash_family (family_id, deleted_at)
) COMMENT 'v1.33 · 回收站:被删除的账户与它的全部数据(整块挪走、整块放回)';

ALTER TABLE principal_adjustment DROP CHECK ck_pa_amount;
ALTER TABLE principal_adjustment ADD CONSTRAINT ck_pa_amount CHECK (amount <> 0);
