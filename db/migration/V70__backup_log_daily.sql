-- =========================================================
-- V70 · backup_log 加一种类型 DAILY(Docker 备份容器每天一次)· issue #29 续 · v1.31.1
-- =========================================================
-- 背景:Docker 部署的备份由 backup 容器(docker/backup.sh)每天做一次,但它从来不写 backup_log,
--      于是「管理 → 备份」页在 Docker 下永远是「最近 0 次备份」,页面文案也写死了 systemd 的
--      「每周日 03:00 · /var/backup/finance/」(issue #29 用户 2026-10-10 反馈)。
--      现在备份容器每个周期写一行,类型记 DAILY —— 不能借用 WEEKLY,那是在页面上撒谎。
--
-- backward-compat:
--   · 只放宽 CHECK(DROP/ADD · V44 同法),不动老行
--   · BackupLog.kind 是 String,老 jar 读到 DAILY 照常显示,回滚不需要前置 SQL
-- =========================================================

ALTER TABLE backup_log DROP CHECK ck_backup_kind;
ALTER TABLE backup_log
    ADD CONSTRAINT ck_backup_kind
        CHECK (kind IN ('WEEKLY','MANUAL','DAILY'));
