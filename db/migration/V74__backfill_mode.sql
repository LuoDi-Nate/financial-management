-- v1.34.2 · 往「之后已有人确认过余额」的账期里补记收支 / 划转时,用户选的是哪种(维护者 2026-10-15 拍板 · 默认 A)
--   NULL  = 普通记账(之后没有确认过的余额 —— 照旧:改这一期 + 传导到下一个系统代填的期)
--   KEEP  = A · 只补记这笔,余额不动(当时填的余额已经包含了它)
--   SHIFT = B · 当时的余额还没算上它:这一期和之后每一期这个账户的余额一起挪
-- 记下来是为了删这一笔时按同一种方式撤回(B 记的,删时把之后各期挪回去)。
-- 可空新列:老 jar 不读,回滚无需 SQL。
ALTER TABLE cash_flow
  ADD COLUMN backfill_mode VARCHAR(8) NULL COMMENT 'v1.34.2 · 历史期补记方式:NULL 普通 / KEEP 余额不动 / SHIFT 之后各期一起挪';
ALTER TABLE transfer
  ADD COLUMN backfill_mode VARCHAR(8) NULL COMMENT 'v1.34.2 · 历史期补记方式:NULL 普通 / KEEP 余额不动 / SHIFT 之后各期一起挪';
