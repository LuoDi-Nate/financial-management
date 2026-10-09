#!/usr/bin/env bash
# =========================================================
# 家庭账房 · deploy.sh 的 macOS 内部实现($HOME/finance · brew · 无 sudo)
#
# 这是内部文件(文件名下划线前缀)· 不要直接调 —— 直装唯一入口是 deploy/deploy.sh,
# 它在 macOS 上自动 exec 到本脚本。用户永远只需:bash deploy/deploy.sh
#
# 干啥:
#   首装 — brew 装 JDK21 / Maven / MySQL · 建 DB+user · ~/.finance/finance.env
#         · 跑迁移 · mvn package · 拷 jar 到 ~/finance/app.jar
#         · 输出"如何启动"说明(默认前台跑;可选 launchd auto-start)
#   迭代 — mysqldump 备份 · 备份旧 jar · 应用增量 V*.sql · 切新 jar
#
# 跟 Linux deploy.sh 的差异:
#   · 无 sudo(用户家目录,无系统级权限)
#   · 无 systemd(用 launchd 可选 / 默认前台 java -jar)
#   · 无 nginx(直接 :SERVER_PORT 访问;反代可后续手动)
#   · 路径全在 $HOME/finance/(不进 /opt/finance / /var/finance / /etc/)
#   · 不创建 finance 系统用户(当前 macOS 用户 == 应用用户)
# =========================================================
set -euo pipefail

# ---------- 颜色 + 辅助 ----------
G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; B=$'\033[1;36m'; X=$'\033[0m'
say()  { echo; echo "${B}═══ $* ═══${X}"; }
ok()   { echo "${G}✓${X} $*"; }
warn() { echo "${Y}⚠${X} $*"; }
err()  { echo "${R}✗${X} $*" >&2; }
die()  { err "$1"; exit 1; }
ask()  { local q="$1" def="${2:-}"; local ans; read -p "$q${def:+ [$def]}: " ans; echo "${ans:-$def}"; }
ask_pw() { local q="$1"; local ans; read -s -p "$q: " ans; echo >&2; echo "$ans"; }

REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

# macOS 路径(用户家目录,无 /opt /var /etc)
APP_HOME="$HOME/finance"
ENV_DIR="$HOME/.finance"
ENV_FILE="$ENV_DIR/finance.env"
SENTINEL="$APP_HOME/.prod-cleaned"

mysql_run()     { MYSQL_PWD="$DB_PASS" mysql -h127.0.0.1 -u"$DB_USER" "$DB_NAME" "$@"; }
mysqldump_run() { MYSQL_PWD="$DB_PASS" mysqldump --no-tablespaces --single-transaction --quick \
                    -h"${DB_HOST:-127.0.0.1}" -P"${DB_PORT:-3306}" -u"$DB_USER" "$DB_NAME" "$@"; }

# BSD/GNU stat 兼容:macOS 没 stat -c%s,用 -f%z
file_size() { stat -f%z "$1" 2>/dev/null || stat -c%s "$1" 2>/dev/null || echo 0; }

# ---------- 0. 预飞 ----------
say "0/12 预飞"
[[ "$(uname)" == "Darwin" ]] || die "本脚本仅 macOS · Linux 请用 deploy/deploy.sh"
[[ $EUID -eq 0 ]] && die "macOS 不要 sudo · 用当前用户即可($USER)"
[[ -d src/main/java && -d db/migration ]] || die "$(pwd) 不像本仓库根目录"
[[ -f pom.xml ]] || die "pom.xml 缺失"
ok "macOS · 用户=$USER · 仓库=$REPO_DIR"

IS_ITERATION=0
NEEDS_RESTART=0
[[ -f "$ENV_FILE" && -f "$APP_HOME/app.jar" ]] && IS_ITERATION=1
[[ "$IS_ITERATION" == "1" ]] && ok "检测到已部署 — 迭代模式" \
                             || ok "检测到首装"

# ============================================================
# A. 环境与依赖
# ============================================================

say "1/12 Homebrew"
if command -v brew >/dev/null 2>&1; then
  ok "已存在:$(brew --version | head -1)"
else
  die "未装 Homebrew · 装一下:/bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
fi

say "2/12 Java 21"
if java -version 2>&1 | head -1 | grep -qE 'version "(21|22|23|24)\.'; then
  ok "已存在:$(java -version 2>&1 | head -1)"
elif brew list --formula openjdk@21 >/dev/null 2>&1; then
  ok "brew openjdk@21 已装(可能未 link,链接一下)"
  brew link --force openjdk@21 2>/dev/null || true
else
  brew install openjdk@21
  brew link --force openjdk@21 2>/dev/null || true
  ok "已装 openjdk@21"
fi
# brew 安装后,JAVA_HOME 一般在 $(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
JAVA_HOME_GUESS="$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
[[ -d "$JAVA_HOME_GUESS" ]] && export JAVA_HOME="$JAVA_HOME_GUESS"
JAVA_BIN="$(command -v java)"
[[ -n "$JAVA_BIN" ]] || JAVA_BIN="${JAVA_HOME:-}/bin/java"
[[ -x "$JAVA_BIN" ]] || die "找不到可执行 java"

say "3/12 Maven"
if command -v mvn >/dev/null 2>&1; then
  ok "已存在:$(mvn -v 2>&1 | head -1 | awk '{print $1,$2,$3}')"
else
  brew install maven
  ok "已装"
fi

say "4/12 MySQL 8"
if pgrep -x mysqld >/dev/null 2>&1; then
  ok "mysqld 正在跑"
elif brew services list 2>/dev/null | grep -qE '^mysql\s+started'; then
  ok "brew services 标记 mysql started"
else
  if ! brew list --formula mysql >/dev/null 2>&1; then
    brew install mysql
  fi
  brew services start mysql
  # MySQL 启动后头几秒 mysqladmin 连不上,等一下
  for i in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    mysqladmin ping -h127.0.0.1 --silent >/dev/null 2>&1 && { ok "mysql 已起($i s)"; break; }
  done
fi
mysqladmin ping -h127.0.0.1 --silent >/dev/null 2>&1 || die "MySQL 没起来,检查 brew services list"

say "5/12 应用目录"
mkdir -p "$APP_HOME"/{logs,db,db/migration,uploads,backup}
mkdir -p "$ENV_DIR"
chmod 700 "$ENV_DIR" 2>/dev/null || true
ok "$APP_HOME · $ENV_DIR 就位"

say "6/12 MySQL 库 + finance 用户"
DB_NAME="${DB_NAME:-finance}"
DB_USER="${DB_USER:-finance}"

# 探测 root 鉴权方式:
#   1) 无密码(brew 默认 · 没装 mysql_secure_installation)
#   2) 有密码(用户装过 mysql_secure_installation 或用 DMG 装的 MySQL)
ROOT_PW=""
if mysql -uroot -e "SELECT 1" >/dev/null 2>&1; then
  ok "MySQL root 无密码登录(brew 默认)"
else
  warn "MySQL root 似乎设了密码(brew 默认无,装过 mysql_secure_installation 会有)"
  echo "  提示:首次装 mysql 没设密码就直接回车;有密码就输入。错了脚本不会动 DB。"
  for try in 1 2 3; do
    ROOT_PW=$(ask_pw "MySQL root@localhost 密码(回车=无密码 · 试 $try/3)")
    if [[ -z "$ROOT_PW" ]]; then
      if mysql -uroot -e "SELECT 1" >/dev/null 2>&1; then ok "无密码登录成功"; break; fi
    else
      if MYSQL_PWD="$ROOT_PW" mysql -uroot -e "SELECT 1" >/dev/null 2>&1; then ok "root 密码验证通过"; break; fi
    fi
    err "root 登录失败"
    [[ $try -eq 3 ]] && die "MySQL root 登录连续 3 次失败 · 排查方法见下方提示
  · 忘了密码:brew services stop mysql && mysqld_safe --skip-grant-tables &
              然后 mysql -uroot 进去 UPDATE mysql.user SET ... ;FLUSH PRIVILEGES;
  · 或重装:brew services stop mysql && rm -rf /opt/homebrew/var/mysql && brew services start mysql"
  done
fi
# 后续 mysql -uroot 全走这个 wrapper
root_mysql() {
  if [[ -n "$ROOT_PW" ]]; then MYSQL_PWD="$ROOT_PW" mysql -uroot "$@"
  else mysql -uroot "$@"; fi
}

if [[ -f "$ENV_FILE" ]]; then
  DB_PASS=$(grep '^DB_PASS=' "$ENV_FILE" | cut -d= -f2-)
  ok "从 $ENV_FILE 读到 DB_PASS"
else
  if root_mysql -sN -e "SHOW DATABASES" 2>/dev/null | grep -qx "$DB_NAME"; then
    DB_PASS=$(ask_pw "DB $DB_NAME 已存在,现有 ${DB_USER}@localhost 密码")
  else
    DB_PASS_GEN=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24)
    DB_PASS=$(ask_pw "新建 ${DB_USER}@localhost 的密码(回车 = 自动生成)")
    [[ -z "$DB_PASS" ]] && { DB_PASS="$DB_PASS_GEN"; warn "自动生成 → $DB_PASS  (将写入 $ENV_FILE)"; }
    root_mysql <<SQL
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\` DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
ALTER USER '${DB_USER}'@'localhost' IDENTIFIED BY '${DB_PASS}';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${DB_USER}'@'localhost';
FLUSH PRIVILEGES;
SQL
    ok "DB + 用户创建"
  fi
fi
MYSQL_PWD="$DB_PASS" mysql -h127.0.0.1 -u"$DB_USER" "$DB_NAME" -e "SELECT 1" >/dev/null 2>&1 \
  || die "${DB_USER}@localhost 登 mysql 失败 · 重新跑脚本输入正确 root 密码(或 mysql_secure_installation 设的密码)"
ok "${DB_USER}@localhost 登录验证通过"

say "7/12 $ENV_FILE"
if [[ -f "$ENV_FILE" ]]; then
  ok "已存在,跳过(重置 → 先删 $ENV_FILE)"
else
  REMEMBER_KEY=$(openssl rand -hex 32)
  SERVER_PORT=$(ask "服务监听端口" "20000")
  cat > "$ENV_FILE" <<EOF
# $ENV_FILE — deploy.sh(macOS)生成 $(date -Iseconds 2>/dev/null || date +%Y-%m-%dT%H:%M:%S)
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=${DB_NAME}
DB_USER=${DB_USER}
DB_PASS=${DB_PASS}
UPLOAD_ROOT=${APP_HOME}/uploads
REMEMBER_ME_KEY=${REMEMBER_KEY}
BACKUP_DIR=${APP_HOME}/backup
RETENTION_DAYS=56
SERVER_PORT=${SERVER_PORT}
FAMILY_ID=1
SERVER_ADDRESS=127.0.0.1
# FINANCE_LLM_QWEN_API_KEY=
# FINANCE_LLM_DEEPSEEK_API_KEY=
# FINANCE_LLM_ARK_API_KEY=
EOF
  chmod 600 "$ENV_FILE"
  ok "写入(600)"
fi
SERVER_PORT=$(grep '^SERVER_PORT=' "$ENV_FILE" | cut -d= -f2- | tr -d '"' || echo 20000)

# ============================================================
# B. 数据库迁移
# ============================================================

say "8/12 数据库迁移(schema_history 幂等)"
cp db/apply.sh "$APP_HOME/db/apply.sh"
chmod 755 "$APP_HOME/db/apply.sh"
cp db/migration/V*__*.sql "$APP_HOME/db/migration/"
# .checksum-overrides 是 dot-file,glob 默认不匹配 · 单独拷
if [[ -f db/migration/.checksum-overrides ]]; then
  cp db/migration/.checksum-overrides "$APP_HOME/db/migration/.checksum-overrides"
fi

BACKUP_FILE=""
if [[ "$IS_ITERATION" == "1" ]]; then
  TS=$(date +%Y%m%d-%H%M%S)
  BACKUP_FILE="$APP_HOME/backup/pre-deploy-${TS}.sql.gz"
  set -a; . "$ENV_FILE"; set +a
  mysqldump_run | gzip > "$BACKUP_FILE" || die "mysqldump 失败"
  [[ $(file_size "$BACKUP_FILE") -gt 1024 ]] || die "DB 备份 < 1KB"
  gunzip -t "$BACKUP_FILE" || die "DB 备份 gzip 校验失败"
  ok "DB 备份 → $BACKUP_FILE($(du -h "$BACKUP_FILE" | cut -f1))"

  PENDING=""
  for f in db/migration/V*__*.sql; do
    name=$(basename "$f")
    in_h=$(mysql_run -sN -e "SELECT 1 FROM schema_history WHERE filename='$name'" 2>/dev/null || true)
    [[ -z "$in_h" ]] && PENDING="$PENDING  → $name\n"
  done
  if [[ -n "$PENDING" ]]; then
    echo; echo "${Y}本次增量迁移:${X}"; printf "$PENDING"; echo
    if [[ -t 0 ]]; then
      read -p "${B}确认 apply? [Y/n]${X} " yn
      [[ "$yn" == "n" || "$yn" == "N" ]] && die "用户取消,旧 jar 仍在跑"
    fi
  fi
fi

set -a; . "$ENV_FILE"; set +a
MYSQL_PWD="$DB_PASS" bash "$APP_HOME/db/apply.sh"
ok "迁移完毕"

# 8b. PLACEHOLDER 密码(首装)
PLACEHOLDER_COUNT=$(mysql_run -sN -e "SELECT COUNT(*) FROM member WHERE password_hash LIKE 'PLACEHOLDER%'" 2>/dev/null || echo 0)
if [[ "${PLACEHOLDER_COUNT:-0}" -gt 0 ]]; then
  warn "$PLACEHOLDER_COUNT 个种子用户密码为 PLACEHOLDER"
  ADMIN_PW=$(ask_pw "种子用户临时密码(回车 = demo1234)")
  ADMIN_PW="${ADMIN_PW:-demo1234}"
  # macOS 默认无 htpasswd · 用 java -cp app.jar (没 jar 之前) → 退化为 brew httpd
  if command -v htpasswd >/dev/null 2>&1; then
    HASH=$(htpasswd -bnBC 10 "" "$ADMIN_PW" | tr -d ':\n')
  else
    warn "htpasswd 不存在 · 跳过 PLACEHOLDER 重写 · 登录后首次启动会被 BCryptPasswordEncoder 校验失败"
    warn "解决:brew install httpd · 然后重跑 bash deploy/deploy.sh"
    HASH=""
  fi
  if [[ -n "$HASH" ]]; then
    mysql_run -e "UPDATE member SET password_hash = '$HASH', must_change_pw = 1 WHERE password_hash LIKE 'PLACEHOLDER%';"
    ok "种子用户密码 → bcrypt(临时:$ADMIN_PW)"
  fi
fi

# 9. 清 dev 演示数据(首装才会触发 · sentinel + 真实数据双保险)
# 跟 Linux deploy.sh 第 10/15 步同款 · 干净安装时 V3-V5 + 后续灌的 demo 数据全部 TRUNCATE
# 保留 family + member 种子(diwa / wangergou),让用户登入后看到空账户
say "9/12 清 dev 演示数据"
if [[ -f "$SENTINEL" ]]; then
  ok "已清过($SENTINEL 存在)"
else
  AUDIT_COUNT=$(mysql_run -sN -e "SELECT COUNT(*) FROM audit_log WHERE actor_member_id IS NOT NULL" 2>/dev/null || echo 0)
  EXTRA_MEMBERS=$(mysql_run -sN -e "SELECT COUNT(*) FROM member WHERE id > 2" 2>/dev/null || echo 0)
  if [[ "${AUDIT_COUNT:-0}" -gt 50 || "${EXTRA_MEMBERS:-0}" -gt 0 ]]; then
    err "═══ 真实数据拦截 ═══"
    err "audit_log=${AUDIT_COUNT}(>50)/ 额外成员=${EXTRA_MEMBERS}(>0)→ 拒绝 TRUNCATE"
    err "若确实想清:mysqldump 备份 → 手动 TRUNCATE → touch $SENTINEL → 重跑"
    die "中止"
  fi
  mysql_run <<'SQL'
SET FOREIGN_KEY_CHECKS=0;
TRUNCATE TABLE cash_flow; TRUNCATE TABLE transfer; TRUNCATE TABLE period_snapshot;
TRUNCATE TABLE snapshot_todo; TRUNCATE TABLE period_member_completion;
TRUNCATE TABLE fx_rate; TRUNCATE TABLE audit_log; TRUNCATE TABLE backup_log;
TRUNCATE TABLE metrics_recompute_log; TRUNCATE TABLE period_reopen_log;
TRUNCATE TABLE period; TRUNCATE TABLE account;
-- v1.29 · issue #34 · 关账定格表也要清:迁移 V54 会给演示数据里已关账的期回填定格行,
--   只清 period / account 的话编号会被重用 → 演示账户的类型按编号挂到用户自己的账户上
--   (用户的「中国银行(现金)」在当期被当成了贷款)。新加任何按期 / 按账户定格的表,这里都要加一行。
TRUNCATE TABLE period_account_attr; TRUNCATE TABLE period_account_group; TRUNCATE TABLE holding_share_event; TRUNCATE TABLE fund_nav_snapshot; TRUNCATE TABLE principal_adjustment;
-- v0.3 引入的用户填报数据(没有 V*.sql seed · 但保险起见也清)
TRUNCATE TABLE stock_holding; TRUNCATE TABLE stock_price_snapshot;
TRUNCATE TABLE family_goal; TRUNCATE TABLE goal_ai_report;
TRUNCATE TABLE period_member_cashflow;
SET FOREIGN_KEY_CHECKS=1;
SQL
  touch "$SENTINEL"
  ok "演示数据已清(13 张交易表 TRUNCATE)· 保留 family + member 种子(diwa / wangergou)"
fi

# ============================================================
# C. 编译 + 部署 jar
# ============================================================

say "10/12 mvn package"
M2_SETTINGS="$HOME/.m2/settings.xml"
MAVEN_SRC="$REPO_DIR/deploy/maven-settings.xml"
if [[ ! -f "$MAVEN_SRC" ]]; then
  warn "deploy/maven-settings.xml 缺失,走 maven central(国内慢)"
elif [[ ! -f "$M2_SETTINGS" ]] || ! grep -q 'aliyun' "$M2_SETTINGS"; then
  mkdir -p "$(dirname "$M2_SETTINGS")"
  cp "$MAVEN_SRC" "$M2_SETTINGS"
  ok "$M2_SETTINGS ← deploy/maven-settings.xml(aliyun mirror)"
else
  ok "$M2_SETTINGS 已含 aliyun mirror"
fi

echo "  (首次构建国内下载 ~200MB 依赖,5-15 分钟)"
set +e
(cd "$REPO_DIR" && mvn -B -DskipTests package 2>&1 \
  | grep -vE '^\[INFO\] (Downloading|Downloaded from|Progress )')
MVN_RC=${PIPESTATUS[0]}
set -e
[[ $MVN_RC -eq 0 ]] || die "mvn package 失败(exit=$MVN_RC)"
[[ -f "$REPO_DIR/target/app.jar" ]] || die "target/app.jar 缺失"
ok "jar $(du -h "$REPO_DIR/target/app.jar" | cut -f1)"

say "11/12 部署 jar"
if [[ -f "$APP_HOME/app.jar" ]] && cmp -s "$REPO_DIR/target/app.jar" "$APP_HOME/app.jar"; then
  ok "jar 与本仓库构建一致,跳过覆盖"
else
  [[ -f "$APP_HOME/app.jar" ]] && cp "$APP_HOME/app.jar" "$APP_HOME/app.jar.prev" && ok "旧 jar 备份 → app.jar.prev"
  cp "$REPO_DIR/target/app.jar" "$APP_HOME/app.jar"
  ok "新 jar 写入"
  NEEDS_RESTART=1
fi

# 启动脚本(让用户 / launchd 都好用):source env + exec java
START_SH="$APP_HOME/start.sh"
cat > "$START_SH" <<EOF
#!/usr/bin/env bash
# 自动生成 · deploy.sh(macOS)· $(date +%Y-%m-%d)
set -euo pipefail
cd "$APP_HOME"
set -a; . "$ENV_FILE"; set +a
exec "$JAVA_BIN" \\
    -Xms256m -Xmx512m \\
    -Dfile.encoding=UTF-8 \\
    -Duser.timezone=Asia/Shanghai \\
    -Dspring.profiles.active=prod \\
    -jar "$APP_HOME/app.jar"
EOF
chmod 755 "$START_SH"
ok "启动脚本 → $START_SH"

# ============================================================
# D. 完成
# ============================================================

say "12/12 完成"
echo
echo "${G}═══════════════════════════════════════════════${X}"
if [[ "$IS_ITERATION" == "1" ]]; then
  echo "${G}  发版完成 · commit $(git rev-parse --short HEAD 2>/dev/null || echo n/a)${X}"
  echo "${G}═══════════════════════════════════════════════${X}"
  echo "DB 备份: $BACKUP_FILE"
  echo "旧 jar:  $APP_HOME/app.jar.prev"
  echo
  echo "如果当前有进程在跑,kill 后重启:"
  echo "  pgrep -f '$APP_HOME/app.jar' | xargs kill"
  echo "  bash $START_SH &"
else
  echo "${G}  首装完成${X}"
  echo "${G}═══════════════════════════════════════════════${X}"
  echo
  echo "启动应用(前台 · 看输出 · Ctrl+C 退出):"
  echo "  bash $START_SH"
  echo
  echo "或后台跑(日志写到 $APP_HOME/logs/app.log):"
  echo "  nohup bash $START_SH > $APP_HOME/logs/app.log 2>&1 &"
  echo
  echo "浏览器访问:  http://127.0.0.1:${SERVER_PORT}/"
  echo
  echo "默认账号:diwa / wangergou · 临时密码:demo1234(或你设的)"
  echo "首次登入会强制改密。"
  echo
  echo "下次发版迭代:git pull && bash deploy/deploy.sh"
  echo
  echo "(可选)launchd 开机自启 — 模板见 deploy/finance.macos.plist.template"
fi
echo
