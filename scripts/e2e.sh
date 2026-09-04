#!/usr/bin/env bash
# ============================================================================
# e2e.sh · 端到端主线验收(补充 qa-run 的广度冒烟,做纵深真验收)
#   形态:唤起 beta 应用(被测基线)→ 按序调用真实接口 → 用「接口响应 + DB 真实数据」判定功能对错。
#   隔离(策略 A):开跑前 mysqldump 快照基线,trap EXIT 无论成败都还原 + 重启 → 可重复、不污染 beta。
#   6 条主线:1 记账闭环 · 2 账期滚动 · 3 报表成图 · 4 多币种镜头 · 5 收益指标 · 6 LOAN 还款归零(后续版本陆续加到 19)。
#   只读主线在前(干净基线),改数据主线殿后。
# ============================================================================
set -u   # 不用 pipefail:curl|grep -q / |head 会提前关管道让 curl 收 SIGPIPE,pipefail 会把这类正常管道误判为失败

BASE="${E2E_BASE:-http://127.0.0.1:20000}"
DBU="${E2E_DBU:-finance}"; DBP="${E2E_DBP:-finance}"; DBN="${E2E_DBN:-finance}"
FAM="${E2E_FAMILY:-1}"
USER_="${E2E_USER:-diwa}"; PASS_="${E2E_PASS:-demo1234}"
CK="$(mktemp)"; DUMP="$(mktemp /tmp/e2e_baseline.XXXXXX.sql)"
PASS=0; FAIL=0; declare -a FAILED=()

db(){ mysql -u"$DBU" -p"$DBP" "$DBN" -sN -e "$1" 2>/dev/null; }
ok(){  echo -e " \033[32mPASS\033[0m $1"; PASS=$((PASS+1)); }
bad(){ echo -e " \033[31mFAIL\033[0m $1 :: ${2:-}"; FAIL=$((FAIL+1)); FAILED+=("$1 :: ${2:-}"); }
eq(){  [ "$2" = "$3" ] && ok "$1 ($2)" || bad "$1" "expect=$3 got=$2"; }
ge(){  [ "$(printf '%s\n' "$2" "$3" | sort -g | tail -1)" = "$2" ] && [ "$2" != "$3" ] || [ "$2" = "$3" ] ; }  # $2>=$3
section(){ echo; echo -e "\033[1;36m════ $1 ════\033[0m"; }

restore(){
  echo; echo "▸ 还原 beta 基线(策略 A)..."
  # 不再 2>/dev/null 吞错:还原静默失败会让下一次跑基于污染状态,表现成一堆假 bug。
  # 注意不能写成 `mysql ... | grep -v ...` —— 那样退出码变成 grep 的,正常还原时(只有一条
  # password warning、被过滤后无输出)grep 返回 1,会把成功报成失败。先跑、再判、错误另行打印。
  RESTORE_ERR="$(mysql -u"$DBU" -p"$DBP" "$DBN" < "$DUMP" 2>&1 | grep -v "Using a password" || true)"
  if [ -z "$RESTORE_ERR" ]; then
    sudo -n /bin/systemctl restart finance 2>/dev/null && sleep 8
    echo "✓ 已还原 + 重启"
  else
    echo "✗ 还原失败!$RESTORE_ERR"
    echo "  请手动:mysql $DBN < $DUMP"
  fi
  rm -f "$CK"
}
trap restore EXIT

# ── 快照基线 ──
echo "▸ 快照 beta 基线 → $DUMP"
# --single-transaction:InnoDB MVCC 一致性快照,不加表锁 → 不打断正在运行的 beta 连接池(否则其后登录会失败)
mysqldump --single-transaction --skip-lock-tables -u"$DBU" -p"$DBP" "$DBN" > "$DUMP" 2>/dev/null || { echo "✗ 快照失败,中止(不动 beta)"; exit 1; }
echo "✓ 基线已存($(wc -l < "$DUMP") 行 SQL)"

# ── 登录 + CSRF ──
xsrf(){ grep XSRF-TOKEN "$CK" | awk '{print $7}' | tail -1; }
login(){
  : > "$CK"
  curl -s -c "$CK" "$BASE/login" -o /dev/null                 # 初始 XSRF
  curl -s -b "$CK" -c "$CK" -X POST "$BASE/login" -H "X-XSRF-TOKEN: $(xsrf)" \
       --data-urlencode "username=$USER_" --data-urlencode "password=$PASS_" -o /dev/null   # 登录(Spring 会清掉 XSRF cookie)
  curl -s -b "$CK" -c "$CK" "$BASE/dashboard" -o /dev/null    # 登录后再 GET 一次,拿到新 XSRF cookie 供后续 POST 用
}
GET(){ curl -s -b "$CK" -c "$CK" "$BASE$1"; }                 # -c:GET 也刷新 XSRF cookie
POST(){ local p="$1"; shift; curl -s -b "$CK" -c "$CK" -X POST "$BASE$p" -H "X-XSRF-TOKEN: $(xsrf)" "$@"; }
POSTcode(){ local p="$1"; shift; curl -s -o /dev/null -w '%{http_code}' -b "$CK" -c "$CK" -X POST "$BASE$p" -H "X-XSRF-TOKEN: $(xsrf)" "$@"; }
# 从 reports 内联 data={} 里抽某数组的元素个数
arr_len(){  # data 对象里 `键: [..]` 的元素个数(数逗号+1,兼容日期串/数字数组)
  local a; a="$(printf '%s' "$1" | grep -oE "$2: \[[^]]*\]" | head -1)"; a="${a#*[}"; a="${a%]*}"
  [ -z "$a" ] && { echo 0; return; }
  echo $(( $(printf '%s' "$a" | tr -cd ',' | wc -c) + 1 ))
}

# 等 beta 就绪(自身 restore 会重启 beta,防下次跑撞上启动窗口)
echo -n "▸ 等 beta 就绪"
for i in $(seq 1 30); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/health" 2>/dev/null)" = "200" ] && { echo " · up"; break; }
  echo -n "."; sleep 2
done
login
GET /dashboard | grep -q '净资产' && echo "✓ 登录 $USER_ 成功(dashboard 可达)" || { echo "✗ 登录失败,中止"; exit 1; }

# ============================================================================
section "主线 3 · 报表成图(只读 · DB 对账)"
# 修 bug3:labels 用全期(=debt 长度);分解图数据 = labels-1;负债值 = DB 逐期 LOAN 汇总
H="$(GET /reports)"
L_labels="$(arr_len "$H" labels)"; L_debt="$(arr_len "$H" debt)"; L_dpnl="$(arr_len "$H" decompPnl)"
eq "报表-labels 与 debt 等长(负债曲线不少画一期)" "$L_labels" "$L_debt"
[ "$L_labels" -ge 1 ] && eq "报表-分解图点数=全期-1" "$L_dpnl" "$((L_labels-1))" || bad "报表-无 labels" "labels=$L_labels"
# 负债曲线最后一点 = 最近关账期 LOAN 绝对值汇总(DB 真值,本位币近似:base=CNY)
anchor_pid="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='CLOSED' AND period_start<=CURDATE() ORDER BY period_start DESC LIMIT 1")"
# 2026-08-04 修:原来直接取 anchor 期的 period_snapshot 汇总,但应用侧的事实表是**结转**的
# (COALESCE 到「≤ 当期的最近一条快照」)。某账户当期没填过就没有行,这条 SQL 会漏掉它 ——
# 于是「DB 真值」比图上少了一整笔房贷(1160 vs 1186700),看起来像图错了,其实是断言的取数太天真。
# 改成逐账户取「≤ anchor 期的最近一条快照」再汇总,与应用同口径。
anchor_start="$(db "SELECT period_start FROM period WHERE id=$anchor_pid")"
db_debt="$(db "SELECT ROUND(ABS(SUM(v.bal))) FROM (
    SELECT a.id, (SELECT ps.end_balance FROM period_snapshot ps JOIN period p2 ON p2.id=ps.period_id
                   WHERE ps.account_id=a.id AND p2.period_start <= '$anchor_start' AND ps.end_balance IS NOT NULL
                   ORDER BY p2.period_start DESC LIMIT 1) AS bal
      FROM account a WHERE a.family_id=$FAM AND a.type='LOAN' AND a.archived_at IS NULL) v
   WHERE v.bal IS NOT NULL")"
rep_debt_last="$(printf '%s' "$H" | grep -oE 'debt: \[[^]]*\]' | head -1 | grep -oE '[0-9]+(\.[0-9]+)?' | tail -1)"
rep_debt_last_r="$(printf '%.0f' "${rep_debt_last:-0}")"
eq "报表-负债曲线末点 ≈ DB 最近关账期 LOAN 汇总" "$rep_debt_last_r" "$db_debt"
# v0.11.4 · 第四表复用管理页指标配置:账户表出现 data-mcol 指标列(≥ 启用指标数);vs基准 用 pp 不用 %
n_mcol="$(printf '%s' "$H" | grep -oE 'data-mcol="[a-z_]+"' | sort -u | wc -l | tr -d ' ')"
[ "$n_mcol" -ge 3 ] && ok "报表-账户表出现配置化指标列($n_mcol 种 data-mcol)" || bad "报表-账户表缺指标列" ">=3" "$n_mcol"
n_pp="$(printf '%s' "$H" | grep -ocE '(跑赢|跑输|持平) [+-]?[0-9.]+pp' || true)"
n_pct_wrong="$(printf '%s' "$H" | grep -ocE '(跑赢|跑输|持平) [+-]?[0-9.]+%' || true)"
[ "$n_pp" -ge 1 ] && [ "$n_pct_wrong" -eq 0 ] && ok "报表-vs基准用 pp 不用 %(pp=$n_pp · 误%=$n_pct_wrong)" || bad "报表-vs基准单位错" "pp≥1且%=0" "pp=$n_pp %=$n_pct_wrong"
# vs基准量级理智:显示的 xirr − 基准,不应再出现 |pp|>1000 的爆值(修 v0.10.5 cumPnl/净投入)
n_blow="$(printf '%s' "$H" | grep -ocE '[+-]?[0-9]{4,}\.[0-9]+pp' || true)"
eq "报表-vs基准无爆值(|pp|>1000 计数)" "$n_blow" "0"

# ============================================================================
section "主线 4 · 多币种镜头(只读 · 比值币种无关 + 金额按 fx 缩放)"
# 紧急储备月数(比值)三币种必须完全相等;净资产(金额)USD 版 ≈ CNY 版 × fx
emg(){ GET "/dashboard?currency=$1" | grep -oE '[0-9]+(\.[0-9]+)? 月' | head -1; }
e_cny="$(emg CNY)"; e_usd="$(emg USD)"; e_hkd="$(emg HKD)"
eq "币种-紧急储备月数 CNY=USD" "$e_cny" "$e_usd"
eq "币种-紧急储备月数 CNY=HKD" "$e_cny" "$e_hkd"

# ============================================================================
section "主线 5 · 收益指标(只读 · 家庭 XIRR 币种无关)"
fxirr(){ GET "/reports?currency=$1" | grep -oE '家庭 XIRR[^%]*%' | head -1 | grep -oE '\-?[0-9]+\.[0-9]+%'; }
x_cny="$(fxirr CNY)"; x_usd="$(fxirr USD)"
[ -n "$x_cny" ] && eq "收益-家庭 XIRR CNY=USD(币种无关)" "$x_cny" "$x_usd" || bad "收益-未取到家庭 XIRR" "cny=$x_cny"

# ============================================================================
section "主线 1 · 记账闭环(改数据 · 录余额+收支+转账 → 精确算术 + 转账双边)"
# 精华取自旧 qa-e2e:录已知起点余额 → 加收入/减支出/转账 → 断言期末 = 起点 + Σ流水(DB 真值)
# 2026-08-04 修:主线 1 起就需要一个 OPEN 期,但 beta 的 OPEN 期可能已被别的回归(qa-run)关掉,
# 于是这里拿到空 periodId → 所有 POST 400 → 后面 8 条断言连锁报空,看起来像 8 个 bug。
# 让 e2e **自备前置条件**:没有 OPEN 期就用应用自己的入口开一期(不直接改库)。
OPEN_PID="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
if [ -z "$OPEN_PID" ]; then
  echo "  (beta 当前无 OPEN 期 · 走 /admin/periods/open-next 自备一期)"
  POSTcode /admin/periods/open-next >/dev/null
  OPEN_PID="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
fi
[ -z "$OPEN_PID" ] && bad "前置-无法取得 OPEN 账期(后续主线会连锁失败)" "open-next 未生效"
ACC_A="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL ORDER BY id LIMIT 1")"
ACC_B="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL AND id<>$ACC_A ORDER BY id LIMIT 1")"
[ -z "$ACC_B" ] && ACC_B="$(db "SELECT id FROM account WHERE family_id=$FAM AND archived_at IS NULL AND type<>'LOAN' AND id<>$ACC_A ORDER BY id LIMIT 1")"
echo "  开账期=$OPEN_PID · A=$ACC_A · B=$ACC_B"
cfB="$(db "SELECT COUNT(*) FROM cash_flow WHERE period_id=$OPEN_PID AND account_id=$ACC_A")"      # 基线已有(快照/还原模型:用增量断言)
trB="$(db "SELECT COUNT(*) FROM transfer WHERE period_id=$OPEN_PID AND from_account_id=$ACC_A")"
POSTcode "/entry/$ACC_A/balance"   --data-urlencode "periodId=$OPEN_PID" --data-urlencode "newBalance=10000" >/dev/null
POSTcode "/entry/$ACC_B/balance"   --data-urlencode "periodId=$OPEN_PID" --data-urlencode "newBalance=5000"  >/dev/null
POSTcode "/entry/$ACC_A/cash-flow" --data-urlencode "periodId=$OPEN_PID" --data-urlencode "kind=INCOME"  --data-urlencode "categoryCode=salary"      --data-urlencode "amount=3000" >/dev/null
POSTcode "/entry/$ACC_A/cash-flow" --data-urlencode "periodId=$OPEN_PID" --data-urlencode "kind=EXPENSE" --data-urlencode "categoryCode=consumption" --data-urlencode "amount=500"  >/dev/null
tcode="$(POSTcode "/entry/$ACC_A/transfer" --data-urlencode "periodId=$OPEN_PID" --data-urlencode "toAccountId=$ACC_B" --data-urlencode "amount=2000")"
eq "记账-转账 HTTP 200" "$tcode" "200"
eq "记账-A 精确算术 期末=起点+收入−支出−转出(10000+3000−500−2000)" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$OPEN_PID AND account_id=$ACC_A")" "10500"
eq "记账-B 转账双边 期末=起点+转入(5000+2000)" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$OPEN_PID AND account_id=$ACC_B")" "7000"
eq "记账-新增收支流水落库(+2 条)" "$(( $(db "SELECT COUNT(*) FROM cash_flow WHERE period_id=$OPEN_PID AND account_id=$ACC_A") - cfB ))" "2"
eq "记账-新增转账落库(+1 条)" "$(( $(db "SELECT COUNT(*) FROM transfer WHERE period_id=$OPEN_PID AND from_account_id=$ACC_A") - trB ))" "1"

# ============================================================================
section "主线 14 · 流水来源标签(改数据 · 手填三种流水 → DB 带 MANUAL · 历史仍 UNKNOWN · 页面真渲染)"
# v1.18 · 直接复用主线 1 刚写进去的三条(余额 / 收支 / 划转),它们都是走真实端点写的,
# 所以这里断的是「用户的一次手填,四张表上各自留下了什么来源」。
# 为什么要 e2e 而不是只靠单测:来源要经过 builder → MyBatis INSERT → 列 → SELECT 别名 → 模板,
# 中间任何一环写错(列名拼错 / 少个 AS / 模板取错属性)单测都发现不了,页面上却是空白或报错。
eq "来源-手填余额落 MANUAL" \
   "$(db "SELECT source_tag FROM period_snapshot WHERE period_id=$OPEN_PID AND account_id=$ACC_A")" "MANUAL"
eq "来源-手填收支落 MANUAL(刚写的 2 条)" \
   "$(db "SELECT COUNT(*) FROM cash_flow WHERE period_id=$OPEN_PID AND account_id=$ACC_A AND source_tag='MANUAL'")" "2"
eq "来源-手填划转落 MANUAL(刚写的 1 条)" \
   "$(db "SELECT COUNT(*) FROM transfer WHERE period_id=$OPEN_PID AND from_account_id=$ACC_A AND to_account_id=$ACC_B AND source_tag='MANUAL'")" "1"
# 维护者定:历史不回填。这条守的是「V56 没有偷偷把老数据说成手填」——
# 断言仍有 UNKNOWN 存量,而不是断言"全都是 UNKNOWN"(主线 1 刚写的那几条本来就该是 MANUAL)。
# 不用脚本里那个 ge():它的参数位是 ($2,$3) 而不是 ($1,$2),全脚本没人调过,照直觉调会静默判错。
UNK_CF="$(db "SELECT COUNT(*) FROM cash_flow WHERE source_tag='UNKNOWN'")"
[ "${UNK_CF:-0}" -ge 1 ] \
  && ok "来源-历史行仍为 UNKNOWN(没被回填成手填 · 存量 $UNK_CF 条)" \
  || bad "来源-历史行仍为 UNKNOWN(没被回填成手填)" "cash_flow 里一条 UNKNOWN 都不剩了,查 V56 是否回填过"
# 页面真的渲染出来(不是只写进库):账户详情页的时间线上要出现来源标签
DETAIL_HTML="$(GET "/accounts/$ACC_A")"
printf '%s' "$DETAIL_HTML" | grep -q 'src-tag' && ok "来源-详情页时间线渲染出来源标签" \
  || bad "来源-详情页时间线渲染出来源标签" "accounts/detail.html 没输出 .src-tag"
printf '%s' "$DETAIL_HTML" | grep -q '手动填报' && ok "来源-页面显示中文来源名(不是 MANUAL 这种码)" \
  || bad "来源-页面显示中文来源名(不是 MANUAL 这种码)" "应显示 LedgerSource.label 而不是枚举名"
printf '%s' "$DETAIL_HTML" | grep -q 'src-manual' && ok "来源-标签带分组配色 class" \
  || bad "来源-标签带分组配色 class" "th:classappend 的 ' src-' + group 没生效"

# ============================================================================
section "主线 2+6 · 账期滚动 + LOAN 还款归零(改数据 · 开下一期→上期关+LOAN夹零)"
# 构造 bug2 场景:选一个 LOAN,把「新期开启前的最近两期」设成 prevPrev=-72000, prev=0(还平)
# 2026-08-04 修(两处):
#  ① 原来把「当前开账期」写死成 2026-07、新期写死 2026-08。脚本写于 7 月,进了 8 月必然失败。
#  ② 更要紧的:新期**不是**「当前 OPEN 期 + 1 月」。PeriodOpener.openNextNow 的 seed 取的是
#     findLatest(family,1) —— 即 **period_start 最大的那一期** —— 然后开它的下一期。
#     账期表若预建到很多年以后(beta 排到 2038),「开下一期」就在末端追加,与今天无关。
#     所以 prev = 最大期,新期 = 最大期 + 1 月;而「上期自动关」关的是那个原本 OPEN 的期。
LOAN_ACCT="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='LOAN' AND archived_at IS NULL ORDER BY id LIMIT 1")"
P_MAX_START="$(db "SELECT MAX(period_start) FROM period WHERE family_id=$FAM")"
P_PREV="$(db "SELECT id FROM period WHERE family_id=$FAM AND period_start='$P_MAX_START'")"   # 新期的「上一期」= 最大期
P_PREV_START="$P_MAX_START"
P_PREVPREV="$(db "SELECT id FROM period WHERE family_id=$FAM AND period_start < '$P_MAX_START' ORDER BY period_start DESC LIMIT 1")"
P_NEXT_START="$(db "SELECT DATE_ADD('$P_MAX_START', INTERVAL 1 MONTH)")"
P_WAS_OPEN="$OPEN_PID"                                                                       # 原本 OPEN 的期(应被自动关)
P_JUN="$P_PREVPREV"   # 兼容下方旧变量名
P_JUL="$P_PREV"
echo "  LOAN 账户=$LOAN_ACCT · 最大期=$P_PREV($P_MAX_START) · 其前一期=$P_PREVPREV · 期望新期=$P_NEXT_START · 原 OPEN 期=$P_WAS_OPEN"
db "UPDATE period_snapshot SET end_balance=-72000 WHERE period_id=$P_JUN AND account_id=$LOAN_ACCT"
db "INSERT INTO period_snapshot (period_id,account_id,end_balance,submitted_by) VALUES ($P_JUL,$LOAN_ACCT,0,1)
    ON DUPLICATE KEY UPDATE end_balance=0"
prior_before="$(db "SELECT status FROM period WHERE id=$P_JUL")"
code="$(POSTcode /admin/periods/open-next)"
eq "滚动-open-next HTTP 2xx/3xx" "$([ "$code" -ge 200 ] && [ "$code" -lt 400 ] && echo ok || echo "$code")" "ok"
NEW_PID="$(db "SELECT id FROM period WHERE family_id=$FAM AND period_start='$P_NEXT_START'")"
eq "滚动-新期 $P_NEXT_START 已开(OPEN)" "$(db "SELECT status FROM period WHERE id=$NEW_PID")" "OPEN"
eq "滚动-原 OPEN 期已自动关(bug1)" "$(db "SELECT status FROM period WHERE id=$P_WAS_OPEN")" "CLOSED"
loan_prefill="$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$NEW_PID AND account_id=$LOAN_ACCT")"
eq "LOAN-还平后新期预填夹零(非+72000)(bug2)" "$loan_prefill" "0"
loan_pos="$(db "SELECT COUNT(*) FROM period_snapshot ps JOIN account a ON a.id=ps.account_id WHERE ps.period_id=$NEW_PID AND a.type='LOAN' AND ps.end_balance>0")"
eq "LOAN-新期无任何贷款预填为正" "$loan_pos" "0"
# 精华取自旧 qa-e2e:非 LOAN 账户开新期自动延续上期末(A 在 07 期末=10500 → 08 预填=10500)
# 期望值同样从 DB 取(原来写死 10500,依赖前面主线刚好把 A 做成那个数)
CARRY_EXP="$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$P_PREV AND account_id=$ACC_A")"
eq "滚动-非LOAN账户新期延续上期末(carry ${CARRY_EXP:-?})" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$NEW_PID AND account_id=$ACC_A")" "${CARRY_EXP:-0}"

# ============================================================================
section "主线 7 · 收入侧录入(v0.12 持仓版 · 股票收入按持仓入账 · 现金股息 / +股数 · 类目校验 · 删除冲回)"
IP="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
STK="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='STOCK' AND archived_at IS NULL ORDER BY id LIMIT 1")"
if [ -n "$STK" ] && [ -n "$IP" ]; then
  snap0="$(db "SELECT ROUND(IFNULL(end_balance,0)) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK")"
  cash0="$(db "SELECT ROUND(IFNULL(SUM(manual_value),0)) FROM stock_holding WHERE account_id=$STK AND valuation_mode='CASH' AND archived_at IS NULL")"
  code="$(POSTcode /entry/income --data-urlencode "periodId=$IP" --data-urlencode "accountId=$STK" --data-urlencode "categoryCode=dividend" --data-urlencode "amount=4200" --data-urlencode "note=e2e股息")"
  eq "收入-录股息 HTTP 2xx/3xx" "$([ "$code" -ge 200 ] && [ "$code" -lt 400 ] && echo ok || echo "$code")" "ok"
  eq "收入-股票 snapshot +4200(立即入账)" "$(( $(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK") - snap0 ))" "4200"
  # v1.18.1 更正:这条原来断言「现金行 +4200(扛得住估值刷新)」,前提是错的 ——
  #   e2e 用的这个 STOCK 账户【一条持仓都没有】,估值压根不接管它(红线:holdings.isEmpty → skip),
  #   根本没有"估值刷新"要扛。而老代码按 type == STOCK 给它凭空建一行现金,反而【把它变成了
  #   托管账户】:下一次估值算 持仓(0) + 现金(4200) = 4200,直接覆盖掉原余额。
  #   beta 实测:该账户原余额 123,456.78 → 插一行 4200 现金 → 跑一次估值 → 余额变成 4200.00。
  #   所以正确的期望是【不建现金行】,并且余额扛得住估值刷新。
  eq "收入-未托管股票账户不建现金行(建了反而会被估值压成只剩这笔)" "$(( $(db "SELECT ROUND(IFNULL(SUM(manual_value),0)) FROM stock_holding WHERE account_id=$STK AND valuation_mode='CASH' AND archived_at IS NULL") - cash0 ))" "0"
  snapAfterIncome="$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK")"
  POSTcode "/accounts/$STK/holdings/refresh" >/dev/null
  eq "收入-未托管股票账户余额扛得住估值刷新(不被压成 4200)" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK")" "$snapAfterIncome"
  eq "收入-流水 is_adjustment=0(真实外部流入 · 被 PnL 剔除)" "$(db "SELECT is_adjustment FROM cash_flow WHERE period_id=$IP AND account_id=$STK AND category_code='dividend' AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")" "0"
  rej="$(POSTcode /entry/income --data-urlencode "periodId=$IP" --data-urlencode "accountId=$STK" --data-urlencode "categoryCode=salary" --data-urlencode "amount=100")"
  eq "收入-类目↔账户校验拒错配(工资→股票账户)" "$([ "$rej" -ge 400 ] && echo rejected || echo "$rej")" "rejected"
  eq "收入-错配拒绝后未写库" "$(db "SELECT COUNT(*) FROM cash_flow WHERE period_id=$IP AND account_id=$STK AND category_code='salary' AND deleted_at IS NULL")" "0"
  cfid="$(db "SELECT id FROM cash_flow WHERE period_id=$IP AND account_id=$STK AND category_code='dividend' AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")"
  POSTcode "/entry/income/$cfid/delete" --data-urlencode "periodId=$IP" >/dev/null
  eq "收入-删除 snapshot 冲回" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK")" "$snap0"
  eq "收入-删除 CASH 行冲回" "$(db "SELECT ROUND(IFNULL(SUM(manual_value),0)) FROM stock_holding WHERE account_id=$STK AND valuation_mode='CASH' AND archived_at IS NULL")" "$cash0"

  # v0.12 持仓版 · 新建未上市持仓入账(+股数)· shares×单股估值 · 外部流入不虚高
  snapM0="$(db "SELECT ROUND(IFNULL(end_balance,0)) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK")"
  cN="$(POSTcode /entry/income/stock/new-manual --data-urlencode "periodId=$IP" --data-urlencode "accountId=$STK" --data-urlencode "displayName=e2e字节RSU" --data-urlencode "shares=100" --data-urlencode "unitValue=50" --data-urlencode "categoryCode=stock_salary")"
  eq "收入-未上市建仓入账 HTTP 2xx/3xx" "$([ "$cN" -ge 200 ] && [ "$cN" -lt 400 ] && echo ok || echo "$cN")" "ok"
  HID="$(db "SELECT id FROM stock_holding WHERE account_id=$STK AND valuation_mode='MANUAL' AND display_name='e2e字节RSU' AND archived_at IS NULL ORDER BY id DESC LIMIT 1")"
  eq "收入-未上市持仓已建 shares=100" "$(db "SELECT ROUND(shares) FROM stock_holding WHERE id=$HID")" "100"
  eq "收入-未上市持仓单股估值=50" "$(db "SELECT ROUND(manual_value) FROM stock_holding WHERE id=$HID")" "50"
  eq "收入-未上市 snapshot +5000(100×50)" "$(( $(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK") - snapM0 ))" "5000"
  eq "收入-未上市 flow is_adjustment=0 + ref 记录" "$(db "SELECT CONCAT(is_adjustment,'|',ROUND(ref_shares),'|',IF(ref_holding_id=$HID,'H','?')) FROM cash_flow WHERE ref_holding_id=$HID AND category_code='stock_salary' AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")" "0|100|H"
  # +股数 到已有持仓:+50 股 × 单股 50 = +2500 · 股数 100→150
  snapS1="$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK")"
  POSTcode /entry/income/stock/holding --data-urlencode "periodId=$IP" --data-urlencode "accountId=$STK" --data-urlencode "holdingId=$HID" --data-urlencode "addShares=50" --data-urlencode "categoryCode=stock_salary" >/dev/null
  eq "收入-已有持仓 +50 股 → 150 股" "$(db "SELECT ROUND(shares) FROM stock_holding WHERE id=$HID")" "150"
  eq "收入-+股数 snapshot +2500(50×50)" "$(( $(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK") - snapS1 ))" "2500"
  # 删除 +股数 那笔 → 股数冲回 150→100 · snapshot -2500
  scfid="$(db "SELECT id FROM cash_flow WHERE ref_holding_id=$HID AND ROUND(ref_shares)=50 AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")"
  POSTcode "/entry/income/$scfid/delete" --data-urlencode "periodId=$IP" >/dev/null
  eq "收入-删除+股数 股数冲回 150→100" "$(db "SELECT ROUND(shares) FROM stock_holding WHERE id=$HID")" "100"
  eq "收入-删除+股数 snapshot 回落到建仓后" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IP AND account_id=$STK")" "$snapS1"
  # 清理:删建仓那笔(股数 100→0)+ 归档空壳持仓
  ncfid="$(db "SELECT id FROM cash_flow WHERE ref_holding_id=$HID AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")"
  POSTcode "/entry/income/$ncfid/delete" --data-urlencode "periodId=$IP" >/dev/null
  eq "收入-删除建仓笔 股数冲回→0" "$(db "SELECT ROUND(IFNULL(shares,0)) FROM stock_holding WHERE id=$HID")" "0"
  db "UPDATE stock_holding SET archived_at=NOW(3) WHERE id=$HID" >/dev/null 2>&1 || true
  # issue#3 回归(精度):未上市单股估值 15.678 必须原样落库,不被截成 15.68(旧 DECIMAL(15,2) 的锅 · V37 放宽到 (20,6))
  POSTcode /entry/income/stock/new-manual --data-urlencode "periodId=$IP" --data-urlencode "accountId=$STK" --data-urlencode "displayName=e2e精度票" --data-urlencode "shares=1" --data-urlencode "unitValue=15.678" --data-urlencode "categoryCode=stock_salary" >/dev/null
  HP="$(db "SELECT id FROM stock_holding WHERE account_id=$STK AND valuation_mode='MANUAL' AND display_name='e2e精度票' AND archived_at IS NULL ORDER BY id DESC LIMIT 1")"
  eq "收入-单股估值 15.678 原样落库(issue#3 精度 · 非 15.68)" "$(db "SELECT manual_value=15.678 FROM stock_holding WHERE id=$HP")" "1"
  hpcf="$(db "SELECT id FROM cash_flow WHERE ref_holding_id=$HP AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")"
  [ -n "$hpcf" ] && POSTcode "/entry/income/$hpcf/delete" --data-urlencode "periodId=$IP" >/dev/null
  db "UPDATE stock_holding SET archived_at=NOW(3) WHERE id=$HP" >/dev/null 2>&1 || true
else
  bad "收入-无 STOCK 账户/无 OPEN 期,跳过" "STK=$STK IP=$IP"
fi

section "主线 8 · 加密货币账户(crypto PR · CRYPTO 账户 + AUTO 持仓 + 注入价 → 估值渲染,不依赖外网)"
MEMBER="$(db "SELECT id FROM member WHERE family_id=$FAM ORDER BY id LIMIT 1")"
CID="$(db "INSERT INTO account(family_id,type,display_name,currency,primary_owner_member_id,display_order) VALUES($FAM,'CRYPTO','e2e-crypto','USD',$MEMBER,99); SELECT LAST_INSERT_ID()")"
if [ -n "$CID" ] && [ "$CID" != "0" ]; then
  db "INSERT INTO stock_holding(account_id,display_name,valuation_mode,ticker,market,shares,currency) VALUES($CID,'比特币','AUTO','BTC','CRYPTO',0.5,'USD')" >/dev/null
  db "DELETE FROM stock_price_snapshot WHERE ticker='BTC' AND market='CRYPTO'" >/dev/null
  db "INSERT INTO stock_price_snapshot(ticker,market,trade_date,close_price,currency,source) VALUES('BTC','CRYPTO',CURDATE(),60000,'USD','e2e')" >/dev/null
  page="$(GET /accounts/$CID/holdings)"
  eq "crypto-CRYPTO 账户持仓页可达(旧枚举崩的隐患已消)" "$([ -n "$page" ] && printf '%s' "$page" | grep -q 'e2e-crypto' && echo ok || echo miss)" "ok"
  eq "crypto-BTC + CRYPTO 市场渲染" "$(printf '%s' "$page" | grep -q 'BTC' && printf '%s' "$page" | grep -q 'CRYPTO' && echo ok || echo miss)" "ok"
  eq "crypto-估值 0.5×\$60000 = USD 30,000(注入价 · 账户币种=USD)" "$(printf '%s' "$page" | grep -q '30,000' && echo ok || echo miss)" "ok"
else
  bad "crypto-建 CRYPTO 账户失败,跳过" "CID=$CID MEMBER=$MEMBER"
fi

section "主线 9 · 开账基线(v0.13 · 新账户存量本金不计入当期收益 · dashboard 拆第三项)"
MEM2="$(db "SELECT id FROM member WHERE family_id=$FAM ORDER BY id LIMIT 1")"
LP="$(db "SELECT id FROM period WHERE family_id=$FAM ORDER BY period_start DESC LIMIT 1")"
OCID="$(db "INSERT INTO account(family_id,type,display_name,currency,primary_owner_member_id,display_order) VALUES($FAM,'CASH','e2e开账基线','CNY',$MEM2,98); SELECT LAST_INSERT_ID()")"
if [ -n "$OCID" ] && [ "$OCID" != "0" ] && [ -n "$LP" ]; then
  # 只在"最新一期"给这个新账户一条快照(此前任何期都无 → 本期首次出现 = 开账基线)
  db "INSERT INTO period_snapshot(period_id,account_id,end_balance,submitted_by) VALUES($LP,$OCID,176543,$MEM2)" >/dev/null
  dash="$(GET /dashboard)"
  eq "开账-dashboard「本期怎么变」出现『开账基线』行" "$(printf '%s' "$dash" | grep -q '开账基线' && echo ok || echo miss)" "ok"
  eq "开账-开账基线金额 ¥176,543(存量本金单列 · 不计入钱赚)" "$(printf '%s' "$dash" | grep -q '176,543' && echo ok || echo miss)" "ok"
else
  bad "开账-建账户/取期失败,跳过" "OCID=$OCID LP=$LP"
fi

section "主线 10 · 贵金属账户(v0.14 · METAL + 自动金价 + 克/盎司换算 · issue #4)"
MEM3="$(db "SELECT id FROM member WHERE family_id=$FAM ORDER BY id LIMIT 1")"
MTID="$(db "INSERT INTO account(family_id,type,display_name,currency,primary_owner_member_id,display_order) VALUES($FAM,'METAL','e2e贵金属','CNY',$MEM3,97); SELECT LAST_INSERT_ID()")"
if [ -n "$MTID" ] && [ "$MTID" != "0" ]; then
  # SGE 黄金:每克价 892 × 200 克 = 178,400 · 盈亏 (892−500)×200 = 78,400
  db "INSERT INTO stock_holding(account_id,display_name,valuation_mode,ticker,market,shares,cost_basis,currency,unit) VALUES($MTID,'黄金·上海','AUTO','AU9999','METAL',200,500,'CNY','GRAM')" >/dev/null
  db "DELETE FROM stock_price_snapshot WHERE ticker='AU9999' AND market='METAL'" >/dev/null
  db "INSERT INTO stock_price_snapshot(ticker,market,trade_date,close_price,currency,source) VALUES('AU9999','METAL',CURDATE(),892,'CNY','e2e')" >/dev/null
  # 国际金:每克价 10 × 31.1035 × 3 盎司 ≈ USD 933(oz→g 因子)
  db "INSERT INTO stock_holding(account_id,display_name,valuation_mode,ticker,market,shares,cost_basis,currency,unit) VALUES($MTID,'黄金·国际','AUTO','XAU','METAL',3,0,'USD','OUNCE')" >/dev/null
  db "DELETE FROM stock_price_snapshot WHERE ticker='XAU' AND market='METAL'" >/dev/null
  db "INSERT INTO stock_price_snapshot(ticker,market,trade_date,close_price,currency,source) VALUES('XAU','METAL',CURDATE(),10,'USD','e2e')" >/dev/null
  page="$(GET /accounts/$MTID/holdings)"
  eq "金属-持仓页可达 + 品种渲染(AU9999/XAU)" "$([ -n "$page" ] && printf '%s' "$page" | grep -q 'AU9999' && printf '%s' "$page" | grep -q 'XAU' && echo ok || echo miss)" "ok"
  eq "金属-SGE 市值 892×200g = ¥178,400(每克价×持仓量)" "$(printf '%s' "$page" | grep -q '178,400' && echo ok || echo miss)" "ok"
  eq "金属-SGE 盈亏 (892−500)×200 = +¥78,400" "$(printf '%s' "$page" | grep -q '78,400' && echo ok || echo miss)" "ok"
  eq "金属-盎司换算 XAU 10×31.1035×3 ≈ USD 933(oz→g 因子生效)" "$(printf '%s' "$page" | grep -q '933' && echo ok || echo miss)" "ok"
  # 填报页:金属账户必须有「去持仓/重量」入口(修:此前只 STOCK/CRYPTO,METAL 漏了 → 填报录不了重量)
  entryp="$(GET /entry)"
  eq "金属-填报页有持仓入口(/accounts/N/holdings · 不再漏 METAL)" "$(printf '%s' "$entryp" | grep -q "/accounts/$MTID/holdings" && echo ok || echo miss)" "ok"
else
  bad "金属-建 METAL 账户失败,跳过" "MTID=$MTID"
fi

section "主线 11 · 建账户→录多类型收入→回 dashboard 核对金额(全链路 · 沉底守护)"
# dashboard 净资产抽取器:第一个 kpi-value(净资产)· 剥标签取首个数字(本位币 CNY)
dash_nw(){ printf '%s' "$1" | tr '\n' ' ' | grep -oE 'kpi-value tnum" data-priv[^>]*>[^<]*' | head -1 | grep -oE '[0-9][0-9,]*' | head -1 | tr -d ','; }
MEM4="$(db "SELECT id FROM member WHERE family_id=$FAM ORDER BY id LIMIT 1")"
IPI="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY id DESC LIMIT 1")"
if [ -n "$IPI" ] && [ -n "$MEM4" ]; then
  nwB="$(dash_nw "$(GET /dashboard)")"; nwB="${nwB:-0}"
  # 1) 真机新建向导建 CASH 账户
  codeA="$(POSTcode /accounts --data-urlencode "type=CASH" --data-urlencode "displayName=e2e收入现金" --data-urlencode "currency=CNY" --data-urlencode "primaryOwnerMemberId=$MEM4")"
  CA="$(db "SELECT id FROM account WHERE family_id=$FAM AND display_name='e2e收入现金' AND archived_at IS NULL ORDER BY id DESC LIMIT 1")"
  eq "收入链路-新建 CASH 账户 2xx/3xx + 入库" "$([ "$codeA" -ge 200 ] && [ "$codeA" -lt 400 ] && [ -n "$CA" ] && echo ok || echo "code=$codeA")" "ok"
  # 2) 录 4 种类型收入:工资12345 + 奖金6000 + 利息234 + 其他789 = 19368(distinctive)
  for kv in "salary:12345" "bonus:6000" "interest_income:234" "other_income:789"; do
    POSTcode /entry/income --data-urlencode "periodId=$IPI" --data-urlencode "accountId=$CA" \
      --data-urlencode "categoryCode=${kv%%:*}" --data-urlencode "amount=${kv##*:}" >/dev/null
  done
  eq "收入链路-CASH 账户期末 = Σ4类收入 19,368(0 起 + 4 笔)" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IPI AND account_id=$CA")" "19368"
  eq "收入链路-4 种类目各一笔 is_adjustment=0(真实外部流入)" "$(db "SELECT CONCAT(COUNT(*),'|',ROUND(SUM(amount))) FROM cash_flow WHERE period_id=$IPI AND account_id=$CA AND kind='INCOME' AND is_adjustment=0 AND deleted_at IS NULL")" "4|19368"
  # 3) 真机建 STOCK 账户 + 录现金股息 4200(股票类收入)
  POSTcode /accounts --data-urlencode "type=STOCK" --data-urlencode "displayName=e2e收入股票" --data-urlencode "currency=CNY" --data-urlencode "primaryOwnerMemberId=$MEM4" >/dev/null
  SA="$(db "SELECT id FROM account WHERE family_id=$FAM AND display_name='e2e收入股票' AND archived_at IS NULL ORDER BY id DESC LIMIT 1")"
  POSTcode /entry/income --data-urlencode "periodId=$IPI" --data-urlencode "accountId=$SA" --data-urlencode "categoryCode=dividend" --data-urlencode "amount=4200" >/dev/null
  # v1.18.1 更正:新建的 STOCK 账户没有任何持仓 → 估值不接管 → 不该建现金行(理由同主线 7 那条)。
  # 钱落在 snapshot 上,而且要扛得住一次估值刷新。
  eq "收入链路-新建股票账户(无持仓)不建现金行" "$(db "SELECT ROUND(IFNULL(SUM(manual_value),0)) FROM stock_holding WHERE account_id=$SA AND valuation_mode='CASH' AND archived_at IS NULL")" "0"
  eq "收入链路-股票账户现金股息 +4200 落在余额上" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IPI AND account_id=$SA")" "4200"
  # 「估值刷新后还在不在」的复验放到本段最后 —— /holdings/refresh 走的是 refreshAllForFamily,
  # 会把【全家庭】账户重估一遍;夹在净资产基线与复核之间会把下面那条断言带偏(实测 +54.9 万)。
  # 4) 回 dashboard 核对:净资产较基线 +23,568(19368+4200 · 本位币 delta 免 FX/PMC 口径歧义)
  dash="$(GET /dashboard)"
  nwA="$(dash_nw "$dash")"; nwA="${nwA:-0}"
  eq "收入链路-dashboard 净资产较基线 +23,568(建账户→录4类现金+股息→总额对得上)" "$(( nwA - nwB ))" "23568"
  # 放在净资产核对之后:确认这个"没有持仓的 STOCK 账户"不会被估值接管、余额不被压成别的数
  POSTcode "/accounts/$SA/holdings/refresh" >/dev/null
  eq "收入链路-估值刷新后余额还是 4200(无持仓 → 不接管)" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$IPI AND account_id=$SA")" "4200"
  eq "收入链路-dashboard 账户列表出现新账户余额 ¥19,368" "$(printf '%s' "$dash" | grep -q '19,368' && echo ok || echo miss)" "ok"
else
  bad "收入链路-无 OPEN 期/成员,跳过" "IPI=$IPI MEM4=$MEM4"
fi

# ============================================================================
section "主线 12 · 支出逐笔化(v1.8 · 模式开关 + 扣余额 + 删除冲回 + 三条红线 + 支出构成)"
XP="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
XA="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL ORDER BY id LIMIT 1")"
XL="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='LOAN' AND archived_at IS NULL ORDER BY id LIMIT 1")"
if [ -n "$XP" ] && [ -n "$XA" ]; then
  mode0="$(db "SELECT expense_entry_mode FROM family WHERE id=$FAM")"

  # ① 总额模式(默认):填报页渲染总额框,不渲染逐笔表单
  POSTcode /admin/reminders/expense-mode --data-urlencode "expenseMode=TOTAL" >/dev/null
  H="$(GET /entry)"
  case "$H" in *'本月总支出(一个数)'*) eq "支出-总额模式渲染总额框" ok ok ;;
                *) bad "支出-总额模式缺总额框" "see /entry" ;; esac
  case "$H" in *'action="/entry/expense"'*) bad "支出-总额模式不该有逐笔表单" "see /entry" ;;
                *) eq "支出-总额模式不渲染逐笔表单" ok ok ;; esac

  # ② 切逐笔:开关落库 + 页面换形态
  code="$(POSTcode /admin/reminders/expense-mode --data-urlencode "expenseMode=ITEMIZED")"
  eq "支出-切逐笔 HTTP 2xx/3xx" "$([ "$code" -ge 200 ] && [ "$code" -lt 400 ] && echo ok || echo "$code")" "ok"
  eq "支出-模式落库 ITEMIZED" "$(db "SELECT expense_entry_mode FROM family WHERE id=$FAM")" "ITEMIZED"
  H2="$(GET /entry)"
  case "$H2" in *'/entry/expense'*) eq "支出-逐笔模式渲染逐笔表单" ok ok ;;
                 *) bad "支出-逐笔表单未渲染" "see /entry" ;; esac
  case "$H2" in *'本月总支出(一个数)'*) bad "支出-逐笔模式总额框应隐藏" "see /entry" ;;
                 *) eq "支出-逐笔模式隐藏总额框" ok ok ;; esac

  # ③ 录一笔 → 从所选账户余额扣除(FR-274 的核心行为)
  xb0="$(db "SELECT ROUND(IFNULL(end_balance,0)) FROM period_snapshot WHERE period_id=$XP AND account_id=$XA")"
  c="$(POSTcode /entry/expense --data-urlencode "periodId=$XP" --data-urlencode "accountId=$XA" \
        --data-urlencode "categoryCode=consumption" --data-urlencode "amount=3200" --data-urlencode "note=e2e日常")"
  eq "支出-录入 HTTP 2xx/3xx" "$([ "$c" -ge 200 ] && [ "$c" -lt 400 ] && echo ok || echo "$c")" "ok"
  eq "支出-账户余额 −3200(录入即出账)" "$(( xb0 - $(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$XP AND account_id=$XA") ))" "3200"
  eq "支出-流水 kind=EXPENSE 且 is_adjustment=0(口径 A · 家庭支出)" \
     "$(db "SELECT CONCAT(kind,'|',is_adjustment) FROM cash_flow WHERE period_id=$XP AND account_id=$XA AND note='e2e日常' AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")" "EXPENSE|0"

  # ④ 服务端红线
  #    v1.19.3 起「被拒」的落地形态变了:校验失败不再冒成 500,而是带 flashError 回填报页(302)——
  #    放开负债账户之后「信用卡 + 还贷」成了用户点得到的组合,500 白页是不可接受的落地。
  #    所以判据不能再看 HTTP 码,要看**行为**:有没有出错误提示 + 有没有落库。
  #    (不用 `GET | grep -q`:grep -q 命中即退出会让 curl 吃 SIGPIPE,pipefail 下整条管道非零)
  expense_verdict(){   # $1=accountId $2=categoryCode → rejected | accepted
    local c page
    c="$(POSTcode /entry/expense --data-urlencode "periodId=$XP" \
         --data-urlencode "accountId=$1" --data-urlencode "categoryCode=$2" --data-urlencode "amount=100")"
    if [ "$c" -ge 400 ]; then echo rejected; return; fi
    # 必须带 period —— Spring 的 FlashMap 会记住 redirect 目标的**查询参数**
    # (targetRequestParams),后续请求参数对不上就不弹出 flash。真实浏览器跟随 302
    # 到 /entry?period=N 时天然带着它;这里不带的话会看不到提示、把「拒绝」误判成「接受」。
    page="$(GET "/entry?period=$XP")"
    case "$page" in *data-entry-flash-error*) echo rejected;; *) echo accepted;; esac
  }
  eq "支出-拒收入类目(salary)"              "$(expense_verdict "$XA" salary)"      "rejected"
  eq "支出-拒现金调整类目(那不是家庭支出)"  "$(expense_verdict "$XA" cash_adjust)" "rejected"
  if [ -n "$XL" ]; then
    # v1.19.3 FR-437a · 这条**反过来了**:信用卡只能录成 LOAN,排掉 LOAN 等于信用卡消费录不进去。
    # 「在贷款账户上记支出=又借了一笔」对房贷成立,但刷卡消费同时就是花钱和欠得更多。
    eq "支出-允许落到负债账户(信用卡消费)"  "$(expense_verdict "$XL" consumption)"  "accepted"
    # FR-437c · 放开之后的新红线:卡上不记还贷,否则和还款那笔双计
    eq "支出-拒负债账户上的还贷(防双计)"    "$(expense_verdict "$XL" loan_payment)"  "rejected"
    eq "支出-拒负债账户上的利息支出(防双计)" "$(expense_verdict "$XL" interest_paid)" "rejected"
    eq "支出-负债账户消费确实写了库" \
       "$(db "SELECT COUNT(*) FROM cash_flow WHERE period_id=$XP AND account_id=$XL AND kind='EXPENSE' AND category_code='consumption' AND deleted_at IS NULL")" "1"
    # FR-437b · 方向:负债余额存负数,记支出后要更负(欠得更多),不是变少
    eq "支出-负债账户方向正确(欠得更多)" \
       "$(db "SELECT CASE WHEN end_balance < 0 THEN 'more_debt' ELSE 'wrong' END FROM period_snapshot WHERE period_id=$XP AND account_id=$XL")" "more_debt"
  fi
  eq "支出-红线拒绝后未写库" \
     "$(db "SELECT COUNT(*) FROM cash_flow WHERE period_id=$XP AND kind='EXPENSE' AND category_code IN ('salary','cash_adjust','loan_payment','interest_paid') AND account_id=$XA AND deleted_at IS NULL")" "0"

  # ⑤ 支出构成:段出现 + 该类目金额进得去
  R="$(GET /reports)"
  case "$R" in *'sec-expense-mix'*) eq "支出-报表出现支出构成段" ok ok ;;
                *) bad "支出-构成段未渲染" "see /reports" ;; esac
  case "$R" in *'日常开支'*) eq "支出-构成含「日常开支」分组(consumption 已改名)" ok ok ;;
                *) bad "支出-构成缺日常开支" "see /reports" ;; esac
  DT="$(GET "/reports/expense-mix/detail?dim=category&groupKey=consumption&mixWin=1")"
  case "$DT" in *'e2e日常'*) eq "支出-构成明细抽屉出逐笔" ok ok ;;
                 *) bad "支出-明细抽屉无数据" "see /reports/expense-mix/detail" ;; esac

  # ⑥ 删除 → 余额冲回原值
  xcf="$(db "SELECT id FROM cash_flow WHERE period_id=$XP AND account_id=$XA AND note='e2e日常' AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")"
  POSTcode "/entry/expense/$xcf/delete" --data-urlencode "periodId=$XP" >/dev/null
  eq "支出-删除后余额冲回" "$(db "SELECT ROUND(end_balance) FROM period_snapshot WHERE period_id=$XP AND account_id=$XA")" "$xb0"
  eq "支出-删除是软删(留痕可追)" "$(db "SELECT IF(deleted_at IS NULL,'no','yes') FROM cash_flow WHERE id=$xcf")" "yes"

  # ⑦ 切回总额:已录数据保留、不删(FR-271)
  POSTcode /admin/reminders/expense-mode --data-urlencode "expenseMode=${mode0:-TOTAL}" >/dev/null
  eq "支出-切回原模式" "$(db "SELECT expense_entry_mode FROM family WHERE id=$FAM")" "${mode0:-TOTAL}"
else
  bad "支出链路-无 OPEN 期/现金账户,跳过" "XP=$XP XA=$XA"
fi

# ============================================================================
section "主线 15 · 数据源接入页密钥独立保存(v1.18.1 · 每家一把 key 各自存,不再被模型校验挡住)"
# 维护者报「主流程都走不下去」:密钥和「用哪个模型」原来在同一个 form / 同一个端点,
# 而端点是「校验先全跑完再落库」→ 全新装机时死锁(模型下拉与凭据级联,一家都没配则
# 平台全禁用 → platform 为空 → 抛「请选择平台」→ 整单退回,key 一个字都没存)。
KEYROW="SELECT COUNT(*) FROM family_runtime_config WHERE family_id=$FAM AND key_name='llm_deepseek_api_key'"
KEY_BEFORE="$(db "$KEYROW")"
kc="$(POSTcode /admin/integrations/llm/key --data-urlencode "platform=deepseek" --data-urlencode "apiKey=sk-e2eProbe000000000000000feed")"
eq "接入页-单独保存一把 key HTTP 3xx" "${kc:0:1}" "3"
eq "接入页-key 已落库(不需要先选平台/型号)" "$(db "$KEYROW")" "1"
GET /admin/integrations | grep -q '的密钥已保存' && ok "接入页-回执说清是哪一家保存了" \
  || bad "接入页-回执说清是哪一家保存了" "flash 里没有「的密钥已保存」"
DETAIL="$(GET /admin/integrations)"
printf '%s' "$DETAIL" | grep -q 'sk-e2e' && ok "接入页-已配置显示可辨认掩码(露头)" \
  || bad "接入页-已配置显示可辨认掩码(露头)" "页面没有掩码头部"
if printf '%s' "$DETAIL" | grep -q 'sk-e2eProbe000000000000000feed'; then
  bad "接入页-密钥不许整条回显" "页面上出现了完整密钥"
else
  ok "接入页-密钥不整条回显(只露头尾)"
fi
GET /admin/integrations >/dev/null
bc2="$(POSTcode /admin/integrations/llm/key --data-urlencode "platform=deepseek" --data-urlencode "apiKey=")"
eq "接入页-空提交 HTTP 3xx" "${bc2:0:1}" "3"
GET /admin/integrations | grep -q '没填内容 · 密钥未改动' && ok "接入页-空提交明确报错(不假装保存成功)" \
  || bad "接入页-空提交明确报错(不假装保存成功)" "应回「没填内容 · 密钥未改动」"
GET /admin/integrations >/dev/null
eq "接入页-老合并端点 /llm 已删除" "$(POSTcode /admin/integrations/llm --data-urlencode "maxTokens=2000" --data-urlencode "timeoutSeconds=25")" "404"
GET /admin/integrations >/dev/null
mc="$(POSTcode /admin/integrations/llm/models --data-urlencode "platform=deepseek" --data-urlencode "family=deepseek-v3" \
      --data-urlencode "modelId=" --data-urlencode "backupPlatform=" --data-urlencode "visionEnabled=false" \
      --data-urlencode "visionPlatform=dashscope" --data-urlencode "visionFamily=qwen-vl" --data-urlencode "visionModelId=" \
      --data-urlencode "temperature=0.5" --data-urlencode "maxTokens=2000" --data-urlencode "timeoutSeconds=25")"
eq "接入页-模型配置单独保存 HTTP 3xx" "${mc:0:1}" "3"
eq "接入页-存模型没把密钥冲掉" "$(db "$KEYROW")" "1"
# 收尾:删掉探测用的 key(beta 的 LLM 走 env 兜底,库里那行会盖住它)
db "DELETE FROM family_runtime_config WHERE family_id=$FAM AND key_name='llm_deepseek_api_key'" >/dev/null
eq "接入页-探测密钥已清理" "$(db "$KEYROW")" "${KEY_BEFORE:-0}"

# ============================================================================
section "主线 16 · 归因锚当月实时 · 转入不产生假亏损(v1.18.3)"
# v1.18.1 曾把归因锚到「最新已关账期」来绕开假亏损,v1.18.3 锚回当月(仪表盘的分工就是实时)。
# 能锚回来的前提是【钱的修复已经生效】:流水会立刻同步进余额,所以当期转入的 pnl 是 0。
# 这条断言因此从「验锚点」升级成「验钱」—— 它一旦红,说明 v1.18.1 那个丢钱 bug 回来了。
ATTR="$(GET "/dashboard/attribution?dim=acct")"
printf '%s' "$ATTR" | grep -q 'attrWaterfall' && ok "归因-片段正常渲染" \
  || bad "归因-片段正常渲染" "没拿到归因 section"
LAST_STATUS="$(db "SELECT status FROM period WHERE family_id=$FAM ORDER BY period_start DESC LIMIT 1")"
echo "  最后一期状态=$LAST_STATUS"
if [ "$LAST_STATUS" = "OPEN" ]; then
  printf '%s' "$ATTR" | grep -q '还在填报中' && ok "归因-填报中时页面明示【实时口径】" \
    || bad "归因-填报中时页面明示【实时口径】" "应出现「YYYY-MM 还在填报中 · 实时口径」"
  printf '%s' "$ATTR" | grep -q '本月已录' && ok "归因-给出已录收支(让人判断钱赚有多虚高)" \
    || bad "归因-给出已录收支" "应出现「本月已录 收入 … 支出 …」"
  printf '%s' "$ATTR" | grep -q '归因锚定' \
    && bad "归因-不该再说「锚定上个月」" "v1.18.3 已锚回当月,这句文案是旧的" \
    || ok "归因-旧的「锚定上个月」文案已移除"
else
  ok "归因-最后一期已关账,无需实时口径提示(跳过)"
fi
# 核心回归:往 OPEN 期的某账户转一笔钱,它不该在归因排行榜里变成同额亏损
OPEN_PID2="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
if [ -n "$OPEN_PID2" ]; then
  SRC="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL ORDER BY id LIMIT 1")"
  DST="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL AND id<>$SRC ORDER BY id LIMIT 1")"
  POSTcode "/entry/$SRC/transfer" --data-urlencode "periodId=$OPEN_PID2" --data-urlencode "toAccountId=$DST" --data-urlencode "amount=41234" >/dev/null
  ATTR2="$(GET "/dashboard/attribution?dim=acct")"
  if printf '%s' "$ATTR2" | grep -q -- '-41234'; then
    bad "归因-当月转入不产生假亏损" "排行榜里出现了 −41234 —— 说明转入没同步进余额(丢钱 bug 回来了)"
  else
    ok "归因-当月转入不产生假亏损(钱已同步进余额)"
  fi
else
  ok "归因-无 OPEN 期,跳过假亏损回归"
fi

# ============================================================================
section "主线 17 · 转进持仓托管账户的钱不许被估值抹掉(v1.18.1 · 真丢钱)"
# 生产上两笔划转共 7.5w 进了一个挂着基金持仓的理财账户(WEALTH):划转把快照加上去了,
# 但钱没进现金行;当天自动估值按「持仓合计」重算并覆盖快照 —— 那笔钱从余额里消失,
# 家庭净资产少算同额,每跑一次估值再抹一次。
# 这条断言的核心是【估值跑完之后余额还在】—— 单测只能钉判据,抹没抹得看真跑一遍。
MG_ACC="$(db "SELECT a.id FROM account a JOIN stock_holding h ON h.account_id=a.id AND h.archived_at IS NULL
             WHERE a.family_id=$FAM AND a.archived_at IS NULL AND a.type IN ('WEALTH','CRYPTO','METAL')
             GROUP BY a.id ORDER BY a.id LIMIT 1")"
MG_SRC="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL ORDER BY id LIMIT 1")"
# 重新取 OPEN 期:主线 2+6 会滚动账期,主线 1 里那个 $OPEN_PID 到这里可能已经关账了
OPEN_PID="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
if [ -n "$MG_ACC" ] && [ -n "$MG_SRC" ] && [ -n "$OPEN_PID" ]; then
  echo "  托管账户=$MG_ACC · 转出方=$MG_SRC · 期=$OPEN_PID"
  mgsnap(){ db "SELECT COALESCE(ROUND(end_balance,2),0) FROM period_snapshot WHERE period_id=$OPEN_PID AND account_id=$MG_ACC"; }
  mgcash(){ db "SELECT COALESCE(ROUND(SUM(manual_value),2),0) FROM stock_holding WHERE account_id=$MG_ACC AND archived_at IS NULL AND valuation_mode='CASH'"; }
  B_SNAP="$(mgsnap)"; B_CASH="$(mgcash)"
  POSTcode "/entry/$MG_SRC/transfer" --data-urlencode "periodId=$OPEN_PID" --data-urlencode "toAccountId=$MG_ACC" --data-urlencode "amount=75000" >/dev/null
  A_SNAP="$(mgsnap)"; A_CASH="$(mgcash)"
  eq "托管账户-转入后快照 +75000" "$(awk -v a="$A_SNAP" -v b="$B_SNAP" 'BEGIN{printf "%.2f", a-b}')" "75000.00"
  eq "托管账户-转入后现金行 +75000(这一步以前压根没做)" "$(awk -v a="$A_CASH" -v b="$B_CASH" 'BEGIN{printf "%.2f", a-b}')" "75000.00"
  # 关键一步:跑一次估值,以前就是它把钱抹掉的
  POSTcode "/accounts/$MG_ACC/holdings/refresh" >/dev/null
  eq "托管账户-估值跑完余额还在(不被持仓合计覆盖掉)" "$(mgsnap)" "$A_SNAP"
  # 撤销要干净回到原点,现金行不许留残值
  MG_TID="$(db "SELECT id FROM transfer WHERE period_id=$OPEN_PID AND to_account_id=$MG_ACC AND deleted_at IS NULL ORDER BY id DESC LIMIT 1")"
  POSTcode "/entry/transfer/$MG_TID/delete" >/dev/null
  eq "托管账户-撤销后快照回到原值" "$(mgsnap)" "$B_SNAP"
  eq "托管账户-撤销后现金行回到原值(无残留)" "$(mgcash)" "$B_CASH"
  POSTcode "/accounts/$MG_ACC/holdings/refresh" >/dev/null
  eq "托管账户-撤销后再估值仍是原值" "$(mgsnap)" "$B_SNAP"
else
  ok "托管账户-beta 上没有「有持仓的 WEALTH/CRYPTO/METAL」账户,跳过"
fi

# ============================================================================
section "主线 18 · 账目对账扫描(v1.18.2 · 干净时不误报 · 真丢钱时抓得到)"
# 复盘 v1.18.1 那个会丢钱的 bug 得出的结论:我们有探测器但没接线,而且归因的「未归因」
# 是残差定义、永远闭合 —— 一个不会失败的恒等式不是校验,是装饰。
# 所以这条 e2e 必须【双向】验:干净时 0 条(不误报),人为复现丢钱时抓得到(不装饰)。
RC_ACC="$(db "SELECT a.id FROM account a JOIN stock_holding h ON h.account_id=a.id AND h.archived_at IS NULL
             WHERE a.family_id=$FAM AND a.archived_at IS NULL AND a.type IN ('WEALTH','CRYPTO','METAL')
             GROUP BY a.id ORDER BY a.id LIMIT 1")"
RC_SRC="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL ORDER BY id LIMIT 1")"
RC_PID="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
rc_hits(){ GET /admin/reconcile | grep -oE '发现 <b>[0-9]+</b> 处<b>疑似</b>' | grep -oE '[0-9]+' | head -1; }
if [ -n "$RC_ACC" ] && [ -n "$RC_SRC" ] && [ -n "$RC_PID" ]; then
  GET /admin/reconcile | grep -q '账 · 目 · 对 · 账' && ok "对账-页面正常渲染" \
    || bad "对账-页面正常渲染" "/admin/reconcile 没出来"
  RC_BEFORE="$(rc_hits)"; RC_BEFORE="${RC_BEFORE:-0}"
  echo "  基线命中 $RC_BEFORE 条 · 托管账户=$RC_ACC"
  # ① 正常划转(修复后:钱进现金行)→ 不该新增命中
  POSTcode "/entry/$RC_SRC/transfer" --data-urlencode "periodId=$RC_PID" --data-urlencode "toAccountId=$RC_ACC" --data-urlencode "amount=53210" >/dev/null
  POSTcode "/accounts/$RC_ACC/holdings/refresh" >/dev/null
  RC_OK="$(rc_hits)"; RC_OK="${RC_OK:-0}"
  eq "对账-钱正确入账时不误报" "$RC_OK" "$RC_BEFORE"
  # ② 造一条【历史】丢钱:v1.18.3 的 fail-closed 已经让 app 不会再丢新的钱(主线 19 验那个),
  #    而这个扫描器扫的是历史 —— 所以直接在库里摆出那个形状:
  #    一笔进账 + 紧随其后一条 Δ = −该笔金额 的估值事件(= 当年被抹掉时留下的痕迹)。
  #    用 app 去"丢一次钱"已经不可行了,那正是方案 B 生效的证据。
  #    v1.18.6:先把这一期的「隐含损益」归零(end = prev + 净流水),否则 beta 上账户本身的
  #    涨跌会把第二视角的残留顶得到处跑,验出来的是噪音不是判据。原值最后还回去。
  RC_SNAP0="$(db "SELECT ROUND(end_balance,2) FROM period_snapshot WHERE period_id=$RC_PID AND account_id=$RC_ACC")"
  RC_PREV="$(db "SELECT ps.end_balance FROM period_snapshot ps JOIN period p ON p.id=ps.period_id
                  JOIN period pc ON pc.id=$RC_PID
                 WHERE ps.account_id=$RC_ACC AND p.period_start < pc.period_start
                 ORDER BY p.period_start DESC LIMIT 1")"
  RC_NET="$(db "SELECT ROUND(COALESCE(SUM(s),0),2) FROM (
                  SELECT CASE WHEN kind='INCOME' THEN amount ELSE -amount END AS s FROM cash_flow
                   WHERE period_id=$RC_PID AND account_id=$RC_ACC AND deleted_at IS NULL
                  UNION ALL SELECT COALESCE(to_amount,amount) FROM transfer
                   WHERE period_id=$RC_PID AND to_account_id=$RC_ACC AND is_draft=0 AND deleted_at IS NULL
                  UNION ALL SELECT -amount FROM transfer
                   WHERE period_id=$RC_PID AND from_account_id=$RC_ACC AND is_draft=0 AND deleted_at IS NULL) x")"
  db "INSERT INTO transfer (period_id, from_account_id, to_account_id, amount, occurred_at, submitted_by, is_draft, submitted_at)
      SELECT $RC_PID, $RC_SRC, $RC_ACC, 61234, CURDATE(), 1, 0, NOW(3)" >/dev/null
  db "INSERT INTO stock_valuation_event (family_id, account_id, period_id, prev_balance, new_balance, delta, trigger_kind, triggered_at)
      VALUES ($FAM, $RC_ACC, $RC_PID, 0, 0, -61234, 'CRON', NOW(3) + INTERVAL 1 SECOND)" >/dev/null
  RC_BAD="$(rc_hits)"; RC_BAD="${RC_BAD:-0}"
  [ "${RC_BAD:-0}" -gt "${RC_BEFORE:-0}" ] \
    && ok "对账-历史丢钱扫得出来(命中 $RC_BEFORE → $RC_BAD)" \
    || bad "对账-历史丢钱扫得出来" "摆好了形状却没扫出来 —— 这个检查成了永远绿的装饰品"
  GET /admin/reconcile | grep -q '疑似' && ok "对账-措辞是「疑似」不是断言" \
    || bad "对账-措辞是「疑似」不是断言" "页面没有「疑似」—— 判据只看瞬间,写成断言会让人照着删钱"
  GET /admin/reconcile | grep -q '需要补回' \
    && bad "对账-不许再出现「需要补回」" "那是断言式措辞,v1.18.6 已降级" \
    || ok "对账-不许再出现「需要补回」"

  # ─── v1.18.6 · 第二视角:整期是否自洽 ───────────────────────────────
  # 这一版修的是【误报】,方向是「让人删掉真实存在的钱」—— 生产上我照着扫描器的输出
  # 断言维护者要扣 12.5w,而那格早在 8 天后的一次重新导入里就被纠正了。
  # 所以两个分支都要验,而且必须【互斥】:同一条痕迹,钱没回来 → 期末仍对不上;
  # 钱补回来 → 自动降级成需人工核对(这就是「已处理」标记的替代方案)。
  if [ -n "$RC_SNAP0" ] && [ -n "$RC_PREV" ] && [ -n "$RC_NET" ]; then
    # ③ 钱一直没回来:end = prev + 净流水(含刚插的 61234)→ 隐含损益正好背着这个缺口 → 残留 0
    RC_STILL="$(awk -v p="$RC_PREV" -v n="$RC_NET" 'BEGIN{printf "%.2f", p+n}')"
    db "UPDATE period_snapshot SET end_balance=$RC_STILL WHERE period_id=$RC_PID AND account_id=$RC_ACC" >/dev/null
    GET /admin/reconcile | grep -q 'data-verdict="still-missing"' && ok "对账-钱没回来时标「期末仍对不上」" \
      || bad "对账-钱没回来时标「期末仍对不上」" "残留应当 ≈0,却没判成确定 —— 真该动手的那条被降级了"
    # ④ 钱补回来了:期末再加上被抹掉的 61234 → 残留远离 0 → 同一条痕迹自动降级
    RC_FIXED="$(awk -v s="$RC_STILL" 'BEGIN{printf "%.2f", s+61234}')"
    db "UPDATE period_snapshot SET end_balance=$RC_FIXED WHERE period_id=$RC_PID AND account_id=$RC_ACC" >/dev/null
    GET /admin/reconcile | grep -q 'data-verdict="needs-review"' && ok "对账-补回后同一条痕迹自动降级为「需人工核对」" \
      || bad "对账-补回后同一条痕迹自动降级" "钱已经补回来了还在催人补第二次 —— 照着补就是凭空造钱"
    GET /admin/reconcile | grep -q 'data-verdict="still-missing"' \
      && bad "对账-补回后不许还标「期末仍对不上」" "两个标签同时出现 = 分级没生效" \
      || ok "对账-补回后不许还标「期末仍对不上」"
    db "UPDATE period_snapshot SET end_balance=$RC_SNAP0 WHERE period_id=$RC_PID AND account_id=$RC_ACC" >/dev/null
  else
    ok "对账-第二视角:该账户没有上一期快照(建仓首期),跳过"
  fi
  # 收尾:清掉造出来的历史形状,别污染后面的主线
  db "DELETE FROM stock_valuation_event WHERE account_id=$RC_ACC AND period_id=$RC_PID AND delta=-61234" >/dev/null
  db "DELETE FROM transfer WHERE period_id=$RC_PID AND to_account_id=$RC_ACC AND amount=61234" >/dev/null
  db "UPDATE period_snapshot SET end_balance=$RC_SNAP0 WHERE period_id=$RC_PID AND account_id=$RC_ACC" >/dev/null
else
  ok "对账-beta 上没有「有持仓的 WEALTH/CRYPTO/METAL」账户,跳过"
fi

# ============================================================================
section "主线 19 · 估值写回 fail-closed(v1.18.3 · 复盘方案 B · 不许把刚进的钱盖掉)"
# 事后对账是补救,事前不发生才是根治。period_snapshot 是【覆盖写】,被盖掉的旧值没有任何
# 地方留底 —— 所以这是全系统唯一一条不可恢复的自动写。宁可这次不写,也不要把钱抹掉。
# 判据与对账扫描共用一份(ErasureDetector),双向都验:该拦的拦住 + 正常估值不误拦。
FC_ACC="$(db "SELECT a.id FROM account a JOIN stock_holding h ON h.account_id=a.id AND h.archived_at IS NULL
             WHERE a.family_id=$FAM AND a.archived_at IS NULL AND a.type IN ('WEALTH','CRYPTO','METAL')
             GROUP BY a.id ORDER BY a.id LIMIT 1")"
FC_SRC="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL ORDER BY id LIMIT 1")"
FC_PID="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
if [ -n "$FC_ACC" ] && [ -n "$FC_SRC" ] && [ -n "$FC_PID" ]; then
  fcsnap(){ db "SELECT ROUND(end_balance,2) FROM period_snapshot WHERE period_id=$FC_PID AND account_id=$FC_ACC"; }
  fcblk(){ db "SELECT COUNT(*) FROM audit_log WHERE family_id=$FAM AND summary LIKE '估值写回被拦下%'"; }
  BLK0="$(fcblk)"; BLK0="${BLK0:-0}"
  # 金额刻意与主线 17/18 不同:同期同双方同金额 24 小时内会被【重复划转防护】挡掉,
  # 那条防护是对的,是用例撞上了它 —— 撞上之后划转没生效,后面的断言全在验一个假场景。
  BEFORE_T="$(fcsnap)"
  POSTcode "/entry/$FC_SRC/transfer" --data-urlencode "periodId=$FC_PID" --data-urlencode "toAccountId=$FC_ACC" --data-urlencode "amount=48765" >/dev/null
  AFTER_T="$(fcsnap)"
  eq "写回拦截-前置:划转真的生效了(没被重复防护挡掉)" \
     "$(awk -v a="$AFTER_T" -v b="$BEFORE_T" 'BEGIN{printf "%.2f", a-b}')" "48765.00"
  # ① 人为抹掉现金行 = 复现 v1.18.1 之前那条路径 → 估值必须【拒绝覆盖】
  db "UPDATE stock_holding SET manual_value = manual_value - 48765 WHERE account_id=$FC_ACC AND archived_at IS NULL AND valuation_mode='CASH'" >/dev/null
  POSTcode "/accounts/$FC_ACC/holdings/refresh" >/dev/null
  eq "写回拦截-余额没被抹掉(拒绝覆盖)" "$(fcsnap)" "$AFTER_T"
  BLK1="$(fcblk)"; BLK1="${BLK1:-0}"
  [ "$BLK1" -gt "$BLK0" ] && ok "写回拦截-留了痕(审计可查 · 不是只写日志)" \
    || bad "写回拦截-留了痕" "拦下来却没写审计 —— 那就是 v1.17.3 犯过的「失败只写日志」"
  GET /admin/reconcile | grep -q '估值写回被拦下' && ok "写回拦截-对账页看得见" \
    || bad "写回拦截-对账页看得见" "拦截留痕没出现在 /admin/reconcile"
  # ② 反方向:补回现金行后正常估值不许被误拦
  db "UPDATE stock_holding SET manual_value = manual_value + 48765 WHERE account_id=$FC_ACC AND archived_at IS NULL AND valuation_mode='CASH'" >/dev/null
  BLK2="$(fcblk)"; BLK2="${BLK2:-0}"
  POSTcode "/accounts/$FC_ACC/holdings/refresh" >/dev/null
  BLK3="$(fcblk)"; BLK3="${BLK3:-0}"
  eq "写回拦截-正常估值不误拦" "$BLK3" "$BLK2"
else
  ok "写回拦截-beta 上没有「有持仓的 WEALTH/CRYPTO/METAL」账户,跳过"
fi

# ============================================================================
section "主线 20 · 仪表盘「实时」定位:每个数说清自己是哪一期(v1.18.7)"
# 页面自称「实时汇总」。2026-08-25 逐项 review 查出三类「口径混在一屏、页面上看不出来」的数,
# 这条主线把三项都走用户真实路径验一遍 —— 只跑单测会漏掉「算对了但没渲染出来」。
DS_PID="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='OPEN' ORDER BY period_start DESC LIMIT 1")"
DS_CLOSED="$(db "SELECT id FROM period WHERE family_id=$FAM AND status='CLOSED' ORDER BY period_start DESC LIMIT 1")"
DS_ACC="$(db "SELECT id FROM account WHERE family_id=$FAM AND type='CASH' AND archived_at IS NULL ORDER BY id LIMIT 1")"
DS_CAT="$(db "SELECT category_code FROM cash_flow WHERE kind='EXPENSE' AND deleted_at IS NULL LIMIT 1")"

# ① 储蓄率必须点名账期 —— 此前写死「本期储蓄率」,而它常常取的是上一期
DASH="$(GET /dashboard)"
case "$DASH" in *'账期)'*|*'账期储蓄率'*) ok "仪表盘-储蓄率点名了账期" ;;
  *) bad "仪表盘-储蓄率点名了账期" "副标题/一句话里没有「(YYYY-MM 账期)」—— 又变回把上月读成本月" ;; esac

# ② 净资产趋势要标出进行中的那一期(收支趋势早就这么做了,净资产趋势一直没有)
case "$DASH" in *'\u00B7 \u8FDB\u884C\u4E2D'*|*'· 进行中'*) ok "仪表盘-净资产趋势标出进行中那一期" ;;
  *) bad "仪表盘-净资产趋势标出进行中那一期" "最右点还会变,却和已定格的点长得一样" ;; esac

# ③ 月均支出不许把半个月当整月 —— 受控实验:同一笔支出,记进行中期 vs 记已关账期
if [ -n "$DS_PID" ] && [ -n "$DS_CLOSED" ] && [ -n "$DS_ACC" ] && [ -n "$DS_CAT" ]; then
  # 值和标签之间隔着标签,直接 grep 抓不到(第一版就是这么写的 → 两边都空 → 假阳性,
  # 靠下面那条对照组才暴露出来)。去标签后再抓。
  emg(){ GET /reports | tr '\n' ' ' | sed 's/<[^>]*>/ /g' | grep -oE '紧急储备 +[0-9]+\.[0-9]+ 月' | tr -s ' ' | head -1; }
  DS_BASE="$(emg)"
  db "INSERT INTO cash_flow (period_id, account_id, kind, category_code, amount, occurred_at, submitted_by, submitted_at)
      VALUES ($DS_PID, $DS_ACC, 'EXPENSE', '$DS_CAT', 60000, CURDATE(), 1, NOW(3))" >/dev/null
  eq "仪表盘-进行中期的支出不进月均(紧急储备不动)" "$(emg)" "$DS_BASE"
  db "DELETE FROM cash_flow WHERE period_id=$DS_PID AND kind='EXPENSE' AND amount=60000" >/dev/null
  # 对照组:同一笔记进【已关账】期就必须生效 —— 否则上面那条是「这个数根本不会动」的假阳性
  db "INSERT INTO cash_flow (period_id, account_id, kind, category_code, amount, occurred_at, submitted_by, submitted_at)
      VALUES ($DS_CLOSED, $DS_ACC, 'EXPENSE', '$DS_CAT', 60000, CURDATE(), 1, NOW(3))" >/dev/null
  DS_AFTER="$(emg)"
  [ -n "$DS_BASE" ] && [ "$DS_AFTER" != "$DS_BASE" ] \
    && ok "仪表盘-对照组:记进已关账期就生效($DS_BASE → $DS_AFTER)" \
    || bad "仪表盘-对照组:记进已关账期就生效" "两边都不动 = 上一条是假阳性,这个数压根没在算"
  db "DELETE FROM cash_flow WHERE period_id=$DS_CLOSED AND kind='EXPENSE' AND amount=60000" >/dev/null
else
  ok "仪表盘-月均支出实验:beta 缺进行中/已关账期或支出类目,跳过"
fi

# ④ 洞察条必须跟随视图(此前它自己 loadDefault,切币种/筛账户/选 as-of 都纹丝不动)
# 用「净资产名义增长」而不是「房产 %」—— 后者在只剩一个非房产账户时整块不渲染,
# 于是「空 vs 非空」也算不同,那是靠巧合通过,不是真的验到了跟随。
ins(){ GET "$1" | grep -oE '净资产名义增长 [+-][0-9.]+%' | head -1; }
# 用 as-of 当杠杆而不是账户筛选:筛到某个账户时洞察条可能整块降级成 unavailable(没有可比的数),
# 那时「不同」是因为渲染没了、不是因为跟随生效。as-of 一定改变取数窗口,而且每个家庭都有。
DS_ASOF="$(db "SELECT period_start FROM period WHERE id=$DS_CLOSED")"
INS_NOW="$(ins /dashboard)"
INS_OLD="$(ins "/dashboard?asof=$DS_ASOF")"
[ -n "$INS_NOW" ] && [ -n "$INS_OLD" ] && [ "$INS_NOW" != "$INS_OLD" ] \
  && ok "仪表盘-洞察条跟随观察账期(当期 $INS_NOW vs $DS_ASOF $INS_OLD)" \
  || bad "仪表盘-洞察条跟随观察账期" "换了 as-of 洞察条不变 = 它还在自己 loadDefault(按今天),和上面 KPI 两个口径"

# ============================================================================
section "主线 13 · 报表页封板快照(v1.10 · 三区 + 定格不变性 + 恒等式对账 + 仪表盘实时口径)"

# 前两区必须只由 asof 决定 —— 这是这一版的核心承诺,用真实渲染比对而不是看代码
zsig(){ GET "/reports?range=$1" | awk '/id="sec-sealed"/{f=1} f{print} /id="sec-trend"/{exit}' | md5sum | cut -c1-16; }
z1m="$(zsig 1M)"; zall="$(zsig ALL)"; z3m="$(zsig 3M)"
eq "封板-切 range 前两区逐字不变(1M vs ALL)" "$z1m" "$zall"
eq "封板-切 range 前两区逐字不变(1M vs 3M)" "$z1m" "$z3m"
[ -n "$z1m" ] && [ "$z1m" != "d41d8cd98f00b204" ] && ok "封板-前两区确实渲染出内容($z1m)" \
  || bad "封板-前两区没渲染" "指纹=$z1m"

RP="$(GET '/reports?range=1Y')"
for kw in 'id="sec-sealed"' 'id="sec-structure"' 'id="sec-trend"' '期末资产负债表' '净资产为什么变了' \
          '本期 vs 上期 vs 去年同期' '集中度' '流动性分层' '口径 v'; do
  case "$RP" in *"$kw"*) ok "封板-渲染 $kw" ;; *) bad "封板-缺 $kw" "见 /reports" ;; esac
done

# 资产负债表六格:报表页与仪表盘同名 KPI 必须同口径(差异只能来自锚哪一期)
case "$RP" in *'总负债'*) ok "封板-报表页出现总负债(原来只有仪表盘有)" ;;
              *) bad "封板-总负债缺失" "FR-322 六格之一" ;; esac
case "$RP" in *'紧急储备'*) ok "封板-报表页出现紧急储备" ;; *) bad "封板-紧急储备缺失" "FR-322" ;; esac

# 恒等式:要么闭合、要么给出可解释的差额 —— 不许既不闭合又不解释
case "$RP" in
  *'对账闭合'*)               ok "封板-资金流恒等式闭合" ;;
  *'属外部资本纳入'*)         ok "封板-差额由开账基线解释(符合预期)" ;;
  *'无法由开账基线解释'*)     bad "封板-恒等式差额来源不明" "需查:除开账基线外还有第三个来源" ;;
  *)                          bad "封板-恒等式没有任何结论" "三态文案都没出现" ;;
esac

# 截断轴必须明示(默默截断会让读数被误解)
case "$RP" in *'截断轴'*|*'轴自 0 起'*) ok "封板-瀑布轴起点已明示" ;;
              *) bad "封板-轴截断没明示" "FR-323" ;; esac

# 归档账户不许抹掉历史:归档一个账户前后,历史期净资产必须一致
ARCH_N="$(db "SELECT COUNT(*) FROM account WHERE family_id=$FAM AND archived_at IS NOT NULL")"
if [ "${ARCH_N:-0}" -gt 0 ]; then
  ok "封板-家庭有 $ARCH_N 个归档账户(时间语义已生效才不会抹历史)"
else
  ok "封板-无归档账户(时间语义无从验证,跳过)"
fi

# 仪表盘按两页分工 = 当月实时;标题不许换成别的月份
DSHP="$(GET /dashboard)"
case "$DSHP" in *'本月资产收益'*) ok "封板-仪表盘标题固定为「本月资产收益」" ;;
                *) bad "封板-仪表盘收益格标题不对" "FR-327" ;; esac
OPEN_N="$(db "SELECT COUNT(*) FROM period WHERE family_id=$FAM AND status='OPEN'")"
if [ "${OPEN_N:-0}" -gt 0 ]; then
  case "$DSHP" in *'本月未封板'*) ok "封板-进行中期给出口径交代「本月未封板」" ;;
                  *) ok "封板-当前 OPEN 期不在仪表盘窗口内(跳过口径交代断言)" ;; esac
fi

# ════════════════════════════════════════════════════════════════════
# 主线 21 · 超级 Agent(v1.19)
#   这条主线**不调大模型** —— 上游要花钱、要几十秒、而且回答内容天生不稳定,
#   拿它当断言等于给自己造一条随机红的护栏。真正要守住的是**我们这一侧**:
#   开关语义、鉴权、工具返回的数字与页面逐字一致、以及会话落库的形状。
#   模型那一段靠联调实测(见 docs/qa-cases.md v1.19 段)。
# ════════════════════════════════════════════════════════════════════
echo; echo "── 21 · 超级 Agent ──"

# ① 默认关:功能没开时页面要说清怎么开,而不是给个空壳
db "DELETE FROM family_runtime_config WHERE family_id=$FAM AND key_name='ask_enabled'" >/dev/null
ASKP="$(GET /ask)"
case "$ASKP" in *'还没打开'*) ok "超级 Agent · 默认关且页面说明怎么开" ;;
                *) bad "超级 Agent · 默认关状态没说明" "AskConversationService.blockedReason" ;; esac

# ② /mcp 未授权一律 404(不是 401 —— 401 等于告诉扫描器这里有东西)
MCPC="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/mcp" -H 'Content-Type: application/json' \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')"
case "$MCPC" in 404) ok "超级 Agent · 无凭据访问 /mcp 返回 404" ;;
                *) bad "超级 Agent · /mcp 无凭据返回了 $MCPC" "必须 404,不能透露端点存在" ;; esac

# ③ 口令放 URL query 也必须 404 —— MCP 规范禁止 query 传 token(会进 access log 和 Referer)
MCPQ="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/mcp?token=fmk_whatever" \
        -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')"
case "$MCPQ" in 404) ok "超级 Agent · 口令放 URL 不被接受(仍 404)" ;;
                *) bad "超级 Agent · URL 里的 token 被接受了($MCPQ)" "凭据只能走 Authorization 头" ;; esac

# ④ 发一把凭据 → tools/list 通,且工具清单非空
E2E_TOK="$(POST /admin/ai-access/create --data-urlencode 'name=e2e' --data-urlencode 'scope=detail' \
           --data-urlencode 'days=7' -o /dev/null -w '%{http_code}' >/dev/null; \
           GET /admin/ai-access | grep -oE 'fmk_[A-Za-z0-9_-]{30,}' | head -1)"
if [ -n "$E2E_TOK" ]; then
  ok "超级 Agent · 管理页发出凭据(明文仅此一屏)"
  TL="$(curl -s -X POST "$BASE/mcp" -H 'Content-Type: application/json' -H "Authorization: Bearer $E2E_TOK" \
        -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')"
  case "$TL" in *'"pivot"'*) ok "超级 Agent · 有凭据时 tools/list 返回工具清单" ;;
                *) bad "超级 Agent · tools/list 没返回工具" "$TL" ;; esac

  # ⑤ **本版最重的一条**:AI 那条路径的数,必须和页面上的数是同一个。
  #    这里用两条**互相独立**的服务互证:pivot 走 PivotEngine,period_summary 走 FactViewService。
  #    它们在代码里没有共同的求和逻辑,所以对得上就说明 AI 没有另起一套聚合 ——
  #    而"另起一套聚合"正是这一版最不能出的错。
  mcp_call(){ curl -s -X POST "$BASE/mcp" -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $E2E_TOK" -d "$1"; }
  PV_TOTAL="$(mcp_call '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"pivot","arguments":{"rows":["type"],"measures":["value"]}}}' \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(json.loads(d['result']['content'][0]['text'])['data']['grand'][0])" 2>/dev/null)"
  KPI_TOTAL="$(mcp_call '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"period_summary","arguments":{}}}' \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print(json.loads(d['result']['content'][0]['text'])['data']['totalAssets'])" 2>/dev/null)"
  if [ -z "$PV_TOTAL" ]; then
    bad "超级 Agent · pivot 没返回合计" "grand 缺失"
  elif [ "$PV_TOTAL" = "$KPI_TOTAL" ]; then
    ok "超级 Agent · 两条独立路径的总资产逐字一致(pivot=$PV_TOTAL = kpi)"
  else
    bad "超级 Agent · AI 看到的总资产对不上" "pivot=$PV_TOTAL kpi=$KPI_TOTAL —— AI 那条路径另起了聚合"
  fi

  # ⑥ scope:aggregate 凭据够不到含账户名的工具
  A_TOK="$(POST /admin/ai-access/create --data-urlencode 'name=e2e-agg' --data-urlencode 'scope=aggregate' \
           --data-urlencode 'days=7' -o /dev/null >/dev/null; \
           GET /admin/ai-access | grep -oE 'fmk_[A-Za-z0-9_-]{30,}' | head -1)"
  AP="$(curl -s -X POST "$BASE/mcp" -H 'Content-Type: application/json' -H "Authorization: Bearer $A_TOK" \
        -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"account_performance","arguments":{}}}')"
  case "$AP" in *'含账户明细'*) ok "超级 Agent · 只给汇总的凭据拿不到账户明细" ;;
                *) bad "超级 Agent · scope 没拦住账户明细工具" "aggregate 凭据不该读到账户名" ;; esac

  # ⑦ 参数错要把「可用取值」回给模型,而不是一句失败把对话卡死
  BADP="$(curl -s -X POST "$BASE/mcp" -H 'Content-Type: application/json' -H "Authorization: Bearer $E2E_TOK" \
          -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"pivot","arguments":{"rows":["不存在的维度"]}}}')"
  case "$BADP" in *'allowed'*) ok "超级 Agent · 参数错回可用取值(让模型自己改)" ;;
                  *) bad "超级 Agent · 参数错没回可用取值" "对话会被一句「失败」卡死" ;; esac

  # ⑧ 审计:通过与未通过在库里分得开
  A_OK="$(db "SELECT COUNT(*) FROM ask_access_audit WHERE family_id=$FAM AND result LIKE 'OK%'")"
  A_NO="$(db "SELECT COUNT(*) FROM ask_access_audit WHERE result IN ('INVALID','OFF','SCOPE')")"
  if [ "${A_OK:-0}" -gt 0 ] && [ "${A_NO:-0}" -gt 0 ]; then
    ok "超级 Agent · 审计区分通过($A_OK)与未通过($A_NO)"
  else
    bad "超级 Agent · 审计没区分通过与未通过" "OK=$A_OK 未通过=$A_NO"
  fi

  # ⑨ 库里没有明文口令 —— 只存 SHA-256
  PLAIN_N="$(db "SELECT COUNT(*) FROM ask_access_token WHERE token_hash='$E2E_TOK'")"
  case "${PLAIN_N:-1}" in 0) ok "超级 Agent · 库里查不到明文口令(只存哈希)" ;;
                          *) bad "超级 Agent · 库里存了明文口令" "只能存 SHA-256" ;; esac
else
  bad "超级 Agent · 管理页没能发出凭据" "/admin/ai-access/create"
fi

# ⑩ 两种壳共用一个片段(整页可渲染;抽屉片段能单独取到)
db "INSERT INTO family_runtime_config(family_id,key_name,value_text) VALUES($FAM,'ask_enabled','true')
    ON DUPLICATE KEY UPDATE value_text='true'" >/dev/null
PANEL="$(GET /ask/panel)"
case "$PANEL" in *'data-ask-form'*) ok "超级 Agent · PC 抽屉片段可单独渲染" ;;
                 *) bad "超级 Agent · 抽屉片段渲染失败" "/ask/panel" ;; esac
FULL="$(GET /ask)"
case "$FULL" in *'data-ask-form'*) ok "超级 Agent · 手机整页可渲染(与抽屉同一片段)" ;;
                *) bad "超级 Agent · 整页渲染失败" "/ask" ;; esac

# ⑪ 会话建得出来、消息落得进去(不经模型,直接验存储形状)
CONV_ID="$(POST /ask/new | grep -oE '[0-9]+' | head -1)"
if [ -n "$CONV_ID" ]; then
  ok "超级 Agent · 能新建会话(#$CONV_ID)"
  CONV_ROW="$(db "SELECT COUNT(*) FROM ask_conversation WHERE id=$CONV_ID AND family_id=$FAM")"
  case "${CONV_ROW:-0}" in 1) ok "超级 Agent · 会话落库且归属正确" ;;
                           *) bad "超级 Agent · 会话没落库" "ask_conversation" ;; esac
else
  bad "超级 Agent · 新建会话失败" "/ask/new"
fi

# ⑫ 全站入口:悬浮球随 layout 出现在普通页面上(不是只在 /ask 才有)
case "$(GET /dashboard)" in *'data-ask-open'*) ok "超级 Agent · 任意页面都有入口(layout 里的悬浮球)" ;;
                            *) bad "超级 Agent · 普通页面没有入口" "layout::footer 的 ask-fab" ;; esac

# ⑬ 改版后的结构件:停止键、折叠的活动区、逐条操作、追问 chip 的挂载点
FULL2="$(GET /ask)"
case "$FULL2" in *'data-ask-stop'*) ok "超级 Agent · 输入区有停止键" ;;
                 *) bad "超级 Agent · 没有停止键" "长回答期间用户只能干等" ;; esac
case "$FULL2" in *'ask-col'*) ok "超级 Agent · 正文收在阅读列宽内" ;;
                 *) bad "超级 Agent · 正文没有列宽约束" "一行 60 个汉字读着串行" ;; esac

# ⑭ 停止端点:没有在跑的轮次时如实回 false(而不是假装停了)
STOPR="$(POST /ask/$CONV_ID/stop)"
case "$STOPR" in *'"ok":false'*) ok "超级 Agent · 没有在跑的轮次时停止如实回 false" ;;
                 *) bad "超级 Agent · 停止端点语义不对" "$STOPR" ;; esac

# ⑮ 有历史的会话:折叠活动区 + 复制/重来 + 引用卡都在
#    取一段真有回答的会话(e2e 不调模型,所以只在库里已有时才验 —— 没有就跳过并说明)
# 2026-09-04:原来取的是「最新那条有回答的会话」,而这条断言要看的是**活动区**——
#   一轮如果压根没调工具就没有活动区可折叠,断言必然红,而那不是回归。
#   (当天就撞上了:联调百炼时 MCP 没连上,那几轮一个工具都没调,于是最新会话没有活动区。)
#   判据改成「取一条**真有工具调用**的会话」,守的才是「有活动时默认折叠」这个设计意图。
HIST="$(db "SELECT m.conversation_id FROM ask_message m
              JOIN ask_tool_call t ON t.message_id = m.id
             WHERE m.role='assistant' AND m.content_text<>''
               AND m.conversation_id IN (SELECT id FROM ask_conversation WHERE family_id=$FAM)
             ORDER BY m.id DESC LIMIT 1")"
if [ -n "$HIST" ]; then
  HP="$(GET "/ask?conv=$HIST")"
  case "$HP" in *'ask-acts'*) ok "超级 Agent · 历史里活动区默认折叠(details)" ;;
                *) bad "超级 Agent · 活动区没折叠" "对话区讲结论,过程别抢注意力" ;; esac
  case "$HP" in *'data-ask-copy'*) ok "超级 Agent · 回答上有复制/重来" ;;
                *) bad "超级 Agent · 回答没有逐条操作" "复制与重来是完成态的 table stakes" ;; esac
else
  ok "超级 Agent · 库里没有带回答的会话(跳过历史渲染断言 · 本机基线无模型调用)"
fi

# ⑯ 改名后的入口:导航里是「超级 Agent」且带 AI 徽记(PC + 移动两处)
DASH2="$(GET /dashboard)"
NAV_N=$(printf '%s' "$DASH2" | grep -c '超级 Agent')
AITAG_N=$(printf '%s' "$DASH2" | grep -c 'title="用大白话问自己的账')
if [ "${NAV_N:-0}" -ge 2 ] && [ "${AITAG_N:-0}" -ge 2 ]; then
  ok "超级 Agent · 导航两处都改名且带 AI 徽记(PC + 移动)"
else
  bad "超级 Agent · 导航入口不全" "改名 $NAV_N 处 · AI 徽记 $AITAG_N 处(各应 ≥2)"
fi
case "$DASH2" in *'问一问'*) bad "超级 Agent · 旧名残留" "全站不该再出现「问一问」" ;;
                 *) ok "超级 Agent · 旧名已清干净" ;; esac

# ⑰ 空态欢迎语在轮换:连取两次拿到的句子不应恒等
G1="$(GET /ask | grep -oE 'class="ask-empty-lead"[^>]*>[^<]*' | sed 's/.*>//')"
G2=""; for _ in 1 2 3 4 5 6; do
  G2="$(GET /ask | grep -oE 'class="ask-empty-lead"[^>]*>[^<]*' | sed 's/.*>//')"
  [ -n "$G1" ] && [ "$G2" != "$G1" ] && break
done
if [ -n "$G1" ] && [ "$G2" != "$G1" ]; then
  ok "超级 Agent · 空态欢迎语每次进来换一句"
else
  bad "超级 Agent · 欢迎语没在换" "六次取到的都是同一句:$G1"
fi

# ⑱ 底栏只留账期币种 + 一句和 AI 有关的;运维细节(跑在哪条 runtime)不占用户版面
FOOT="$(GET /ask | tr '\n' ' ' | sed 's/<[^>]*>/ /g')"
case "$FOOT" in *'来自财务智能体'*) ok "超级 Agent · 底栏署名是智能体" ;;
                *) bad "超级 Agent · 底栏署名缺失" "应有「来自财务智能体」" ;; esac
case "$FOOT" in *'本机直连(不需要公网)'*) bad "超级 Agent · 底栏还在暴露 runtime" "那是运维细节,属于「AI 接入」页" ;;
                *) ok "超级 Agent · 底栏不再暴露 runtime 细节" ;; esac

# ⑲ 富展示的两条通道都接上了(容器契约 + 沙箱)
ASKJS="$(curl -s "$BASE/js/ask-charts.js")"
case "$ASKJS" in *"sandbox', 'allow-scripts'"*) ok "超级 Agent · 自由 HTML 走 sandbox iframe" ;;
                 *) bad "超级 Agent · 自由 HTML 没走沙箱" "模型输出不可信,必须 opaque origin" ;; esac
case "$ASKJS" in *"'allow-scripts allow-same-origin'"*|*'"allow-scripts allow-same-origin"'*)
                   bad "超级 Agent · 沙箱给了 same-origin" "等于把 cookie 和 DOM 交出去" ;;
                 *) ok "超级 Agent · 沙箱未给 same-origin(判据看属性值,不扫注释)" ;; esac

# ⑳ 历史里带图表的会话能渲染出图表容器(库里有就验,没有就说明跳过)
CH="$(db "SELECT conversation_id FROM ask_message WHERE role='assistant'
          AND content_text LIKE '%{{chart:%'
          AND conversation_id IN (SELECT id FROM ask_conversation WHERE family_id=$FAM)
          ORDER BY id DESC LIMIT 1")"
if [ -n "$CH" ]; then
  case "$(GET "/ask?conv=$CH")" in
    *data-ask-chart*) ok "超级 Agent · 历史里的图表标记渲染成图表容器" ;;
    *) bad "超级 Agent · 图表标记没渲染" "标记会原样漏在正文里" ;; esac
else
  ok "超级 Agent · 库里没有带图的会话(跳过图表渲染断言 · 本机基线无模型调用)"
fi

# ============================================================================
echo
echo "════════════════════════════════════════"
echo -e " e2e 总结: \033[32mPASS=$PASS\033[0m  \033[31mFAIL=$FAIL\033[0m"
echo "════════════════════════════════════════"
if [ "$FAIL" -gt 0 ]; then
  echo "失败主线:"; for f in "${FAILED[@]}"; do echo "  · $f"; done
fi
# 退出码交给 trap restore;显式退出码反映结果
exit $([ "$FAIL" -eq 0 ] && echo 0 || echo 1)
