#!/usr/bin/env bash
# 家庭账房 · v0.7 备份 sidecar:每日 mysqldump 到 backups 卷 + 按保留天数清理。
# 复用 app 镜像(自带 mysql client),compose 里覆盖 entrypoint 跑这个。
#
# v1.28.3 · issue #29(拆自 #25 · Docker 用户反馈:备份容器一直 starting / unhealthy,日志里「失败(库未就绪?稍后重试)」)
#   ① 「稍后重试」是假话:失败之后照样 sleep 24 小时 —— 一次没备上就是一整天没有备份。
#      现在失败后每 5 分钟重试,最多 12 次,还不行才等下一个周期。
#   ② 失败原因被 2>/dev/null 吞掉了,日志里只能猜「库未就绪?」。现在打出 mysqldump 自己那句报错(不含密码)。
#   ③ 「成功」只看管道退出码 —— 和 v1.6.28 deploy/backup-now.sh 踩过的坑一样:文件生成了不等于备份能用。
#      现在要能解压、里面有建表语句才算数,否则删掉、按失败处理;日志写真实字节数和表数
#      (原来写 du -h,在一些文件系统上显示的是占用的块,比如「512」,看不出备份是不是空的)。
#   ④ 成功时刷新 ${BACKUP_DIR}/.last-ok —— compose 里备份容器的健康检查看它(原来继承 app 镜像的
#      curl :20000/health,备份容器里没跑 app,所以永远 unhealthy)。
#
# v1.31.1 · issue #29 续(用户 2026-10-10:容器正常了,但「管理 → 备份」页一直是「最近 0 次备份」)
#   ⑤ 这个容器从来不写 backup_log —— 那张表只有 systemd 的 deploy/backup.sh 在写,备份页只读它。
#      现在每个周期写一行(类型 DAILY · V70):成功写文件路径 + 字节数;重试用完还失败写一行 FAILED + 原因。
#      **每个周期一行,不是每次重试一行**(失败时一天最多重试 12 次,逐次写会把页面刷满)。
#      备份是整库的,所以给库里每个家庭各写一行 —— 每个家庭的备份页都该看得到。
#      写不进去(比如库挂了,这也正是备份失败的常见原因)就算了,不影响备份本身。
#   ⑥ BACKUP_ONCE=1:只跑一个周期就退出(自测用;以后「立即备份」按钮也走它)。
set -uo pipefail

: "${DB_HOST:=db}"
: "${DB_PORT:=3306}"
: "${DB_USER:=finance}"
: "${DB_NAME:=finance}"
: "${DB_PASS:?DB_PASS 未设置}"
: "${RETENTION_DAYS:=56}"
: "${BACKUP_DIR:=/data/backups}"
: "${BACKUP_INTERVAL:=86400}"     # 两次备份之间隔多久(秒)
: "${RETRY_INTERVAL:=300}"        # 失败后多久重试(秒)
: "${MAX_RETRIES:=12}"            # 一个周期里最多重试几次

mkdir -p "$BACKUP_DIR"

# 写一行 backup_log(⑤)。参数:状态 SUCCESS|FAILED · 开始时间 · 文件路径(可空)· 字节数(可空)· 错误(可空)
log_row() {
  local status="$1" started="$2" file="${3:-}" bytes="${4:-}" err="${5:-}"
  local q_file="NULL" q_bytes="NULL" q_err="NULL" done
  done=$(date '+%Y-%m-%d %H:%M:%S')   # 开始 / 结束都用容器时钟(compose 给了 TZ),别一半容器一半库
  [ -n "$file" ]  && q_file="'$(printf '%s' "$file" | sed -e 's/\\/\\\\/g' -e "s/'/''/g")'"
  [ -n "$bytes" ] && q_bytes="$bytes"
  [ -n "$err" ]   && q_err="'$(printf '%s' "$err" | sed -e 's/\\/\\\\/g' -e "s/'/''/g")'"
  MYSQL_PWD="$DB_PASS" mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" --default-character-set=utf8mb4 "$DB_NAME" -e \
    "INSERT INTO backup_log (family_id, kind, status, size_bytes, location_local, error_message, started_at, completed_at)
     SELECT id, 'DAILY', '${status}', ${q_bytes}, ${q_file}, ${q_err}, '${started}', '${done}' FROM family" 2>/dev/null \
    || echo "[backup] (备份记录没写进 backup_log —— 不影响备份文件本身)"
}
echo "[backup] sidecar 启动 · 每 $((BACKUP_INTERVAL / 3600))h dump 一次 · 失败每 $((RETRY_INTERVAL / 60)) 分钟重试(最多 ${MAX_RETRIES} 次)· 保留 ${RETENTION_DAYS} 天 → ${BACKUP_DIR}"

# 备一份;成功返回 0 并打印一行结果,失败返回 1 并打印原因
dump_once() {
  local ts f err tables bytes
  ts=$(date +%Y%m%d-%H%M%S)
  f="${BACKUP_DIR}/finance-${ts}.sql.gz"
  err=$(mktemp)
  if ! MYSQL_PWD="$DB_PASS" mysqldump --no-tablespaces --single-transaction --quick \
         -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" 2>"$err" | gzip > "$f"; then
    LAST_ERR="$(grep -v '^\s*$' "$err" | head -1 | cut -c1-200)"
    echo "[backup] 失败 ${ts} · ${LAST_ERR}"
    rm -f "$f" "$err"; return 1
  fi
  rm -f "$err"
  # 能解压 + 真是 SQL dump(有建表语句)才算备份成功
  tables=$(gunzip -c "$f" 2>/dev/null | grep -c '^CREATE TABLE' || true)
  if ! gunzip -t "$f" 2>/dev/null || [ "${tables:-0}" -eq 0 ]; then
    LAST_ERR="生成的文件解不开或没有建表语句,已删掉(不留一个假备份)"
    echo "[backup] 失败 ${ts} · ${LAST_ERR}"
    rm -f "$f"; return 1
  fi
  bytes=$(wc -c < "$f")
  echo "[backup] ✓ ${f} · ${bytes} 字节 · ${tables} 张表"
  touch "${BACKUP_DIR}/.last-ok"
  LAST_FILE="$f"; LAST_BYTES="$bytes"
  return 0
}

while true; do
  tries=0; ok=0; LAST_FILE=""; LAST_BYTES=""; LAST_ERR=""
  started=$(date '+%Y-%m-%d %H:%M:%S')
  until dump_once; do
    tries=$((tries + 1))
    if [ "$tries" -gt "$MAX_RETRIES" ]; then
      echo "[backup] 连续 $((tries)) 次没备上,等下一个周期再试"
      break
    fi
    sleep "$RETRY_INTERVAL"
  done
  [ -n "$LAST_FILE" ] && ok=1
  if [ "$ok" = 1 ]; then
    log_row SUCCESS "$started" "$LAST_FILE" "$LAST_BYTES" ""
  else
    log_row FAILED "$started" "" "" "连续 ${tries} 次没备上 · 最后一次:${LAST_ERR}"
  fi
  find "$BACKUP_DIR" -name 'finance-*.sql.gz' -mtime +"$RETENTION_DAYS" -delete 2>/dev/null || true
  [ "${BACKUP_ONCE:-0}" = 1 ] && exit $((1 - ok))
  sleep "$BACKUP_INTERVAL"
done
