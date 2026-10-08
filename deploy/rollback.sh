#!/usr/bin/env bash
# =========================================================
# 家庭账房 · 回滚到上一版 jar(运行于生产服务器)
#
# 用法:
#   sudo bash deploy/rollback.sh
#
# 干啥:
#   1. 把 /opt/finance/app.jar.prev 还原到 /opt/finance/app.jar
#   2. systemctl restart finance
#   3. /health 检查
#
# 不动 DB(因为多数迁移 backward-compat,老 jar 兼容新 schema)。
# 若 DB 也要回滚,看 /var/backup/finance/pre-deploy-*.sql.gz,手动 gunzip + mysql。
# =========================================================
set -euo pipefail

G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; X=$'\033[0m'
ok()  { echo "${G}✓${X} $*"; }
err() { echo "${R}✗${X} $*" >&2; }
die() { err "$1"; exit 1; }

[[ $EUID -eq 0 ]] || die "必须 sudo 跑"
[[ -f /opt/finance/app.jar ]] || die "/opt/finance/app.jar 不存在,服务可能从未上线"
[[ -f /opt/finance/app.jar.prev ]] || die "/opt/finance/app.jar.prev 不存在,没有上一版可回滚"

# 检查 prev 和当前不一样,否则白回滚
if cmp -s /opt/finance/app.jar /opt/finance/app.jar.prev; then
  echo "${Y}⚠${X} app.jar 与 app.jar.prev 内容相同,回滚无意义"
  exit 0
fi

SERVER_PORT=$(grep '^SERVER_PORT=' /etc/finance.env 2>/dev/null | cut -d= -f2- | tr -d '"' || echo 20000)

# ── v1.30 起 · 跨版本回滚的前置 SQL(db/rollback/vX.Y.sql)─────────────────────────
# 多数迁移老 jar 兼容,但「加账户类型」这种不兼容:v1.30 加了 FUND,老 jar 读到就炸。
# 规则:要换上的 jar 版本 < X.Y,就先执行 db/rollback/vX.Y.sql(幂等)。版本从 jar 里的 application.yml 读。
REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
jar_yml() {   # 机器上不一定装了 unzip,装不了就退而用 python3 读 jar(jar 就是 zip)
  unzip -p "$1" BOOT-INF/classes/application.yml 2>/dev/null \
    || python3 -c 'import sys,zipfile;sys.stdout.write(zipfile.ZipFile(sys.argv[1]).read("BOOT-INF/classes/application.yml").decode())' "$1" 2>/dev/null
}
PREV_VER=$(jar_yml /opt/finance/app.jar.prev | grep -oE 'APP_VERSION:[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1 | cut -d: -f2 || true)
ver_lt() { [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | head -1)" == "$1" ]]; }
if [[ -n "$PREV_VER" ]]; then
  echo "═══ 回滚前置 SQL(上一版 jar = v$PREV_VER)═══"
  DB_NAME="${DB_NAME:-finance}"; DB_USER="${DB_USER:-finance}"
  DB_PASS=$(grep '^DB_PASS=' /etc/finance.env 2>/dev/null | cut -d= -f2- || true)
  for f in "$REPO_DIR"/db/rollback/v*.sql; do
    [[ -f "$f" ]] || continue
    v=$(basename "$f" .sql); v=${v#v}
    if ver_lt "$PREV_VER" "$v"; then
      MYSQL_PWD="$DB_PASS" mysql -h127.0.0.1 -u"$DB_USER" "$DB_NAME" < "$f" \
        || die "执行 $(basename "$f") 失败 —— 没换 jar,服务还是当前版本"
      ok "已执行 $(basename "$f")(回到 v$v 之前的版本前必须先跑)"
    fi
  done
else
  echo "${Y}⚠${X} 读不出 app.jar.prev 的版本号 —— 若它早于 v1.30 且库里有基金账户,先手动执行 db/rollback/v1.30.sql"
fi

echo "═══ 回滚 jar ═══"
# 当前 jar 暂存到 .reverted,以便万一回滚也挂了能再切回
cp /opt/finance/app.jar /opt/finance/app.jar.reverted-$(date +%s)
cp /opt/finance/app.jar.prev /opt/finance/app.jar
chown finance:finance /opt/finance/app.jar
ok "app.jar.prev → app.jar(原 jar 备份到 app.jar.reverted-* 以防万一)"

echo "═══ 重启 finance ═══"
systemctl restart finance

# /health 等 30 秒
for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  sleep 2
  curl -sf "http://127.0.0.1:${SERVER_PORT}/health" >/dev/null 2>&1 && { ok "/health 200($((i*2))s)"; break; }
  [[ $i -eq 15 ]] && {
    journalctl -u finance --no-pager -n 30
    die "回滚后服务 30s 未起来,看 journalctl"
  }
done

echo
echo "${G}═══════════════════════════════════════${X}"
echo "${G}  回滚完成 · 现跑的是 app.jar.prev${X}"
echo "${G}═══════════════════════════════════════${X}"
echo
echo "若 DB 也需回滚(多数迁移 backward-compat 不用):"
echo "  ls /var/backup/finance/    # 找最近的 pre-deploy-*.sql.gz"
echo "  gunzip < /var/backup/finance/pre-deploy-XXX.sql.gz | mysql -ufinance -p\$PASS finance"
echo
echo "状态:sudo systemctl status finance"
echo "日志:sudo journalctl -u finance -f"
