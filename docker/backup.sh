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
echo "[backup] sidecar 启动 · 每 $((BACKUP_INTERVAL / 3600))h dump 一次 · 失败每 $((RETRY_INTERVAL / 60)) 分钟重试(最多 ${MAX_RETRIES} 次)· 保留 ${RETENTION_DAYS} 天 → ${BACKUP_DIR}"

# 备一份;成功返回 0 并打印一行结果,失败返回 1 并打印原因
dump_once() {
  local ts f err tables bytes
  ts=$(date +%Y%m%d-%H%M%S)
  f="${BACKUP_DIR}/finance-${ts}.sql.gz"
  err=$(mktemp)
  if ! MYSQL_PWD="$DB_PASS" mysqldump --no-tablespaces --single-transaction --quick \
         -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" 2>"$err" | gzip > "$f"; then
    echo "[backup] 失败 ${ts} · $(grep -v '^\s*$' "$err" | head -1 | cut -c1-200)"
    rm -f "$f" "$err"; return 1
  fi
  rm -f "$err"
  # 能解压 + 真是 SQL dump(有建表语句)才算备份成功
  tables=$(gunzip -c "$f" 2>/dev/null | grep -c '^CREATE TABLE' || true)
  if ! gunzip -t "$f" 2>/dev/null || [ "${tables:-0}" -eq 0 ]; then
    echo "[backup] 失败 ${ts} · 生成的文件解不开或没有建表语句,已删掉(不留一个假备份)"
    rm -f "$f"; return 1
  fi
  bytes=$(wc -c < "$f")
  echo "[backup] ✓ ${f} · ${bytes} 字节 · ${tables} 张表"
  touch "${BACKUP_DIR}/.last-ok"
  return 0
}

while true; do
  tries=0
  until dump_once; do
    tries=$((tries + 1))
    if [ "$tries" -gt "$MAX_RETRIES" ]; then
      echo "[backup] 连续 $((tries)) 次没备上,等下一个周期再试"
      break
    fi
    sleep "$RETRY_INTERVAL"
  done
  find "$BACKUP_DIR" -name 'finance-*.sql.gz' -mtime +"$RETENTION_DAYS" -delete 2>/dev/null || true
  sleep "$BACKUP_INTERVAL"
done
