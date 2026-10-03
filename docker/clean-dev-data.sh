#!/usr/bin/env bash
# 家庭账房 · 清 dev 演示数据(让全新 Docker 装好后是「空态」,触发 onboarding 引导,与 systemd deploy.sh step10 一致)
#
# ⚠ 这是全项目**唯一会 TRUNCATE 用户数据表**的脚本。它的每一条判断都必须往「不删」的方向倒。
#
# 安全前提:只由 entrypoint 在「确属全新空库」时调用(迁移前 schema_history 表不存在 = 从未迁移过的全新卷)。
#
# v1.6.26 加固(起因:用户报「更新后多了 Alice/Bob、旧账户被刷掉」)。复盘确认那次是**卷被换/删**
# 导致的全新库(V1 用裸 CREATE TABLE,决定了迁移不可能在已有库上重放),但排查时发现本脚本
# 的互锁有两个真缺陷,任何一个都可能在别的场景下真删数据:
#   ① **互锁 fail-open**:`$(... 2>/dev/null || echo 0)` —— 查询一失败就当 0,
#      而 0 表示"没有真实数据、可以清"。**一道保命的互锁,失败时选了破坏性的那一边。**
#      改成 fail-closed:任何一条判据查不出来 → 退出不清。
#   ② **信号太少**:只看 audit_log 与 member.id>2。用户"建了成员 + 改了密码"但用的是内置两个
#      账号(id 1/2)时,两条信号都不响。补上 **must_change_pw=0**(有人完成首登改密 → 一定用过)
#      与「种子成员已改名」(Alice/Bob 是 V2 种子默认名)。
#   ③ 即使判定要清,也**先 mysqldump 一份**再动手;dump 失败 → 不清。
#      误判的代价从"不可恢复"降到"可恢复"。
#
# 跳过清理的开关:FINANCE_KEEP_DEMO=1(想看演示数据填充效果的人用)
set -euo pipefail

: "${DB_HOST:=db}"; : "${DB_PORT:=3306}"; : "${DB_USER:=finance}"; : "${DB_NAME:=finance}"
: "${DB_PASS:?DB_PASS 未设置}"
export MYSQL_PWD="$DB_PASS"
q(){ mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" "$DB_NAME" "$@"; }

if [[ "${FINANCE_KEEP_DEMO:-}" == "1" ]]; then
  echo "[clean] FINANCE_KEEP_DEMO=1 → 保留演示数据,跳过清理"
  exit 0
fi

# ── 真实数据互锁 · fail-closed ──────────────────────────────────────
# 每条判据:查得出来才用;**查不出来一律当作「有真实数据」**并退出。
# 注意:probe **不能用 exit 终止**。它总是在 `$(...)` 里被调用,而 `$(...)` 是子 shell ——
# 在里面 exit 只结束子 shell,主脚本会带着"错误信息当数值"继续往下跑(v1.6.26 实测踩到:
# 打出一堆 `[[: syntax error: operand expected`,没删数据纯属运气)。
# 正确形状:probe 失败返回非零,由调用处 `|| bail_no_clean` 终止。
probe(){   # 成功:stdout = 数字,返回 0 · 失败:说明写 stderr,返回 1
  local what="$1" sql="$2" out
  if ! out="$(q -sN -e "$sql" 2>&1)"; then
    printf '[clean] ✗ 互锁判据「%s」查不出来:%s\n' "$what" "$out" >&2
    return 1
  fi
  case "$out" in
    ''|*[!0-9]*)
      printf '[clean] ✗ 互锁判据「%s」返回了非数字:%s\n' "$what" "$out" >&2
      return 1 ;;
  esac
  printf '%s' "$out"
}
bail_no_clean(){
  echo "[clean]   保命规则:互锁判据失败一律当作「库里有真实数据」→ **不清理**,数据原样保留。"
  exit 0
}

AUDIT="$(probe 'audit_log 真实操作数' "SELECT COUNT(*) FROM audit_log WHERE actor_member_id IS NOT NULL")"   || bail_no_clean
EXTRA="$(probe '额外成员(id>2)' "SELECT COUNT(*) FROM member WHERE id > 2")"                              || bail_no_clean
PWDONE="$(probe '已完成改密的成员' "SELECT COUNT(*) FROM member WHERE must_change_pw = 0")"                  || bail_no_clean
RENAMED="$(probe '种子成员已改名' "SELECT COUNT(*) FROM member WHERE id IN (1,2) AND display_name NOT IN ('Alice','Bob')")" || bail_no_clean

if [[ "$AUDIT" -gt 50 || "$EXTRA" -gt 0 || "$PWDONE" -gt 0 || "$RENAMED" -gt 0 ]]; then
  echo "[clean] 检测到真实使用痕迹(审计=${AUDIT} 额外成员=${EXTRA} 已改密=${PWDONE} 已改名=${RENAMED})"
  echo "[clean]   → 判为非全新库,**不清理**,数据原样保留。"
  exit 0
fi

# ── 动手前先备份 · dump 失败就不清 ─────────────────────────────────
# 即使上面全部判定「确属全新」,也留一份可恢复快照:把误判的代价从"不可恢复"降到"可恢复"。
BACKUP_DIR="${BACKUP_DIR:-/data/backups}"
if ! mkdir -p "$BACKUP_DIR" 2>/dev/null; then
  echo "[clean] ✗ 备份目录 ${BACKUP_DIR} 不可写 → **放弃清理**(不做不可恢复的删除)"
  exit 0
fi
PRE="${BACKUP_DIR}/pre-clean-$(date +%Y%m%d-%H%M%S).sql.gz"
if mysqldump -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" --single-transaction --quick \
     --routines --events "$DB_NAME" 2>/dev/null | gzip > "$PRE" && [[ -s "$PRE" ]]; then
  echo "[clean] ✓ 清理前快照已存:${PRE}(万一误清可从这里恢复)"
else
  rm -f "$PRE" 2>/dev/null || true
  echo "[clean] ✗ 清理前快照失败 → **放弃清理**(宁可留着演示数据,也不做不可恢复的删除)"
  echo "[clean]   想装好就是空态:在 .env 设 FINANCE_KEEP_DEMO=1 之外的做法请先自己 mysqldump。"
  exit 0
fi

echo "[clean] 全新库 → 清 dev 演示数据(保留 family/member/模板/运行配置),与 systemd step10 一致"
q <<'SQL'
SET FOREIGN_KEY_CHECKS=0;
TRUNCATE TABLE cash_flow; TRUNCATE TABLE transfer; TRUNCATE TABLE period_snapshot;
TRUNCATE TABLE snapshot_todo; TRUNCATE TABLE period_member_completion;
TRUNCATE TABLE fx_rate; TRUNCATE TABLE audit_log; TRUNCATE TABLE backup_log;
TRUNCATE TABLE metrics_recompute_log; TRUNCATE TABLE period_reopen_log;
TRUNCATE TABLE period; TRUNCATE TABLE account;
-- v1.29.1 · issue #34 · 关账定格表也要清:迁移 V54 会给演示数据里已关账的期回填定格行,
--   只清 period / account 的话编号会被重用 → 演示账户的类型按编号挂到用户自己的账户上
--   (用户的「中国银行(现金)」在当期被当成了贷款)。新加任何按期 / 按账户定格的表,这里都要加一行。
TRUNCATE TABLE period_account_attr; TRUNCATE TABLE period_account_group;
SET FOREIGN_KEY_CHECKS=1;
SQL
echo "[clean] 完成 · 全家空态,登录后走 onboarding 引导从零开始"
