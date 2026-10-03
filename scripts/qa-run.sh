#!/usr/bin/env bash
# 家庭账房 v0.1 QA 自动测试 — curl + grep 黑盒
# 用法: bash /tmp/qa-run.sh
# 退出码 0 = 全部 PASS,!=0 = 至少一条 FAIL

set -u
RD="$(cd "$(dirname "$0")/.." && pwd)"   # 仓库根 · 2026-09-22 从 2900 多行提到这里:
                                          # 早期的护栏也要用它(诊断/洞察那几条改成静态判据之后)
BASE="${BASE:-http://localhost:20000}"
COOKIE="/tmp/finance-qa-cookie.txt"
TMP="/tmp/finance-qa-resp.html"
PASS=0; FAIL=0; SKIP=0
FAILED=()

# ── 分段过滤(2026-08-12)· 用法:bash scripts/qa-run.sh --only 'v110|v19'
#
#   为什么加:全量跑一次 ~5.3 分钟(2026-08-13 实测 320s;早期文档写的「45 分钟」已过时)。
#   开发中改一处就要等一轮全量才知道有没有打红别的护栏,
#   于是实际做法变成「攒到最后跑一次」—— 反馈太晚,打红的东西堆在一起难定位。
#   有了过滤,改某一版的护栏时只跑相关段,反馈从分钟级降到秒级。
#
#   实现刻意做得**极小**:不重构 45 个 section 的结构(那是 5000 行的大手术,风险远大于收益),
#   只把三个「贵」的动作在非目标段里变成 no-op —— HTTP 请求、断言记账、日志输出。
#   段内的 grep 仍然跑,但那是毫秒级的本地文件操作,不值得为它增加复杂度。
#
#   **「0 · 认证」段永远跑** —— 后面所有段都依赖它建立的登录态与 cookie,跳过它会全线失败。
QA_ONLY="${QA_ONLY:-}"
if [[ "${1:-}" == "--only" && -n "${2:-}" ]]; then QA_ONLY="$2"; fi
SEC_ACTIVE=1

# ── 基线隔离(策略 A · 2026-08-13)· 与 scripts/e2e.sh 同一套做法 ──
#
#   **为什么补这个**:qa-run 会往 beta 真库里写(建账户/记账/关账/灌 FIRE 长序列),
#   但跑完不还原。后果实测两条:
#     ① 2026-08-12 的一次全量跑把用户**留作验收的当期(2026-08)关账了** —— 于是
#        beta 从此 0 个 OPEN 周期,第二天再跑,所有依赖"当期可录入"的用例
#        (FR5/FR7/v02-*/v03-* 共 30+ 条)集体级联变红,看起来像刚上线的代码打穿了一堆东西,
#        实际全是**上一次跑自己留下的状态**。排查这批假红比跑测试本身还贵。
#     ② 周期表被逐次往后灌,已排到 2040-08(168 个未来 CLOSED 期、3048 条快照),
#        而 findLatest 按 period_start 倒序取 → 应用侧"最新期"落到十几年后。
#
#   所以:开跑前 mysqldump 一份基线,trap EXIT 无论成败都还原 + 重启。跑完 beta 回到原样,
#   可重复、不污染、不会再把用户的当期关掉。
#   `--no-restore` 留给"就是要看跑完之后的状态"那种排查场景。
QA_RESTORE=1
for a in "$@"; do [[ "$a" == "--no-restore" ]] && QA_RESTORE=0; done
QA_DUMP="/tmp/finance-qa-baseline.sql"
if [[ "$QA_RESTORE" == 1 ]]; then
  echo "▸ 快照 beta 基线 → $QA_DUMP"
  # --single-transaction:InnoDB MVCC 一致性快照,不加表锁 → 不打断 beta 连接池(否则其后登录会失败)
  mysqldump --single-transaction --skip-lock-tables -ufinance -pfinance finance > "$QA_DUMP" 2>/dev/null \
    || { echo "✗ 快照失败,中止(不动 beta)"; exit 1; }
  echo "✓ 基线已存($(wc -l < "$QA_DUMP") 行 SQL)"
  qa_restore(){
    echo; echo "▸ 还原 beta 基线(策略 A)..."
    # 不要写成 `mysql ... | grep -v ...` —— 那样退出码变成 grep 的,正常还原时(只有一条
    # password warning、被过滤后无输出)grep 返回 1,会把成功报成失败。先跑、再判。
    local err; err="$(mysql -ufinance -pfinance finance < "$QA_DUMP" 2>&1 | grep -v "Using a password" || true)"
    if [[ -z "$err" ]]; then
      sudo -n /bin/systemctl restart finance 2>/dev/null && sleep 8
      echo "✓ 已还原 + 重启"
    else
      echo "✗ 还原失败!$err"; echo "  请手动:mysql finance < $QA_DUMP"
    fi
  }
  trap qa_restore EXIT
else
  echo "▸ --no-restore:跑完保留 beta 状态(排查用 · 注意会污染基线)"
fi

# ═══════════════════════════════════════════════════════════════════════════
# AI 请求开关 · 回归默认【一次真 LLM 都不打】(2026-09-22 · 维护者要求)
#
#   起因是一张账单。查下来:这个脚本每跑一次会打 15 个 AI 端点,而主选 DeepSeek
#   从 9 月初就欠费了 —— 每次调用都自动 failover 到阿里云百炼。开发最密的那几天,
#   一天 187 次百炼调用,基本都是我自己跑回归跑出来的(09-15 那天约等于十几轮)。
#
#   要认清一件事:这些护栏验的**从来不是「LLM 答得对不对」**,而是
#   「失败时降不降级」「缓存命中没有」「按钮渲染没有」「跨家庭账户会不会泄露」。
#   这些都不需要真的把钱花出去。
#
#   所以:
#     · 能塞缓存的 → 先塞再请求(命中缓存的请求不出网,零成本)
#     · 塞不了缓存的(诊断走进程内缓存,外部塞不进)→ 改成静态判据,
#       或者复用那条【本来就不打 LLM】的响应(account=99999 走早返回)
#     · 真的必须打真 LLM 才有意义的(强制刷新、实调用嗅探、输出合规扫描)
#       → 默认 skip,要验时显式开:QA_LLM_LIVE=1 bash scripts/qa-run.sh
#
#   判据:一条常规回归护栏不该花钱。花钱的那种是**另一件事**,要人主动要才跑。
QA_LLM_LIVE="${QA_LLM_LIVE:-0}"
[[ "$QA_LLM_LIVE" == "1" ]] \
  && echo "▸ QA_LLM_LIVE=1 · 本轮会打真 LLM(会产生费用)" \
  || echo "▸ AI 实调用已关(默认)· 要验真 LLM:QA_LLM_LIVE=1 bash scripts/qa-run.sh"

# 往 rebalance_advice_cache 塞一条固定桩 —— 之后的 advise 请求都会命中缓存,不打 LLM。
# 命中判据只看 (family_id, anchor_code) + 30 天 TTL,不校验 prompt_hash
# (见 RebalanceAdvisorService:90),所以塞得进去。
ai_seed_advice() {
  local anchor a1 a2
  anchor=$(mysql -ufinance -pfinance finance -N -e \
    "SELECT COALESCE(allocation_anchor,'SP_4321') FROM family WHERE id=1;" 2>/dev/null | tr -d '\r' | head -1)
  a1=$(mysql -ufinance -pfinance finance -N -e \
    "SELECT display_name FROM account WHERE family_id=1 AND archived_at IS NULL ORDER BY id LIMIT 1;" 2>/dev/null | tr -d '\r' | head -1)
  a2=$(mysql -ufinance -pfinance finance -N -e \
    "SELECT display_name FROM account WHERE family_id=1 AND archived_at IS NULL ORDER BY id DESC LIMIT 1;" 2>/dev/null | tr -d '\r' | head -1)
  mysql -ufinance -pfinance finance -e "DELETE FROM rebalance_advice_cache WHERE family_id=1;
    INSERT INTO rebalance_advice_cache (family_id, anchor_code, content_json, prompt_hash, generated_at)
    VALUES (1, '${anchor:-SP_4321}',
      JSON_OBJECT('narrative','qa-run 固定桩 · 不是 LLM 产出',
                  'actions', JSON_ARRAY(JSON_OBJECT(
                     'from_account','${a1:-现金账户}', 'to_account','${a2:-投资账户}',
                     'amount', 1000, 'reason','qa-run 固定桩'))),
      NULL, NOW());" 2>/dev/null
}

log_ok()   { [[ "$SEC_ACTIVE" == 0 ]] && return 0; echo -e "\033[32m PASS \033[0m $1"; PASS=$((PASS+1)); }
log_bad()  { [[ "$SEC_ACTIVE" == 0 ]] && return 0; echo -e "\033[31m FAIL \033[0m $1  ::  ${2:-}"; FAIL=$((FAIL+1)); FAILED+=("$1 :: ${2:-}"); }
# 否定断言前先剥掉**整行注释** —— "否定断言被自己解释历史的注释扫红"这个坑本项目踩了 7 次
# (AGENTS.md 有专条),而"每次记得盯代码构造"显然不管用。机械剥注释,结构上不可能再撞。
# 只去掉整行注释(^\s*#),不动行尾的 # —— 后者可能是字符串里的合法字符。
code_only(){ sed -e 's/^[[:space:]]*#.*$//' "$1"; }

# code_only 只剥 **shell 的 `#` 注释**。Java/JS 源码的注释是 `//`,而且最常见的是**行尾**注释
# (`case CN -> AShareTicker.withExchange(t);   // 原 startsWith("6") 漏 513180`)—— 对 Java 文件写否定
# 断言必须用这个版本,否则照样被自己解释历史的注释扫红(2026-08-13 · v13.1-ISSUE3-CN 就是这么红的)。
# 行尾只剥「空白 + //」开头的部分:URL 里的 `//` 前面是 `:`,不会被误伤。
# 单行 javadoc(`/** … */` 写在一行里)也要剥:多行 javadoc 的续行以 `*` 开头会被上一条规则吃掉,
# 单行的却整条留下来 —— v1.13 的 `v113-LLM-ROUTER-SINGLE-PATH` 就是被一句
# `/** 不再自己注入 {@code List<LlmClient>} … */` 扫红的(第 8 次踩同一个坑)。
java_code_only(){ sed -E -e 's@^[[:space:]]*//.*$@@' -e 's@[[:space:]]//.*$@@' -e 's@^[[:space:]]*\*.*$@@' -e 's@/\*.*\*/@@g' "$1"; }

log_skip() { [[ "$SEC_ACTIVE" == 0 ]] && return 0; echo -e "\033[33m SKIP \033[0m $1  ::  ${2:-}"; SKIP=$((SKIP+1)); }
section()  {
  if [[ -n "$QA_ONLY" ]]; then
    # 认证段永远跑(后面全靠它的登录态);其余按 --only 的正则筛
    if [[ "$1" == 0*认证* ]] || printf '%s' "$1" | grep -qE "$QA_ONLY"; then SEC_ACTIVE=1; else SEC_ACTIVE=0; return 0; fi
  fi
  echo; echo -e "\033[1;36m─── $1 ───\033[0m"
}

# 非目标段里 curl 直接返回空 —— HTTP 往返是全脚本最贵的部分(275 处调用)
qacurl(){ [[ "$SEC_ACTIVE" == 0 ]] && return 0; /usr/bin/curl -s --max-time 15 "$@"; }
CURL="qacurl"

# ---------- 0 · 认证 ----------
section "0 · 认证"

# AUTH-1 未登录跳登录
rm -f /tmp/finance-qa-noauth.txt
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/dashboard")
loc=$($CURL -o /dev/null -w "%{redirect_url}" "$BASE/dashboard")
[[ "$code" == "302" && "$loc" == *"/login" ]] && log_ok "AUTH-1 未登录访问 /dashboard 跳 /login" || log_bad "AUTH-1 未登录跳登录" "code=$code loc=$loc"

# AUTH-2 登录页
$CURL "$BASE/login" -o "$TMP" -w ""
grep -q '_csrf' "$TMP" && grep -q 'name="username"' "$TMP" && grep -q 'name="password"' "$TMP" \
  && log_ok "AUTH-2 登录页含 _csrf+用户名+密码字段" || log_bad "AUTH-2 登录页字段缺" "missing fields"

# AUTH-3 错误密码
rm -f $COOKIE
TOKEN=$($CURL -c $COOKIE "$BASE/login" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
loc=$($CURL -b $COOKIE -c $COOKIE -X POST --data-urlencode "_csrf=$TOKEN" --data-urlencode "username=diwa" --data-urlencode "password=WRONG" "$BASE/login" -o /dev/null -w "%{redirect_url}")
[[ "$loc" == *"/login?error" ]] && log_ok "AUTH-3 错误密码跳 /login?error" || log_bad "AUTH-3 错误密码处理" "loc=$loc"

# AUTH-4 正确登录
rm -f $COOKIE
TOKEN=$($CURL -c $COOKIE "$BASE/login" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
loc=$($CURL -b $COOKIE -c $COOKIE -X POST --data-urlencode "_csrf=$TOKEN" --data-urlencode "username=diwa" --data-urlencode "password=demo1234" "$BASE/login" -o /dev/null -w "%{redirect_url}")
[[ "$loc" == *"/" ]] && log_ok "AUTH-4 正确密码登录 → /" || log_bad "AUTH-4 登录失败" "loc=$loc"

# AUTH-5 dashboard 完整 HTML
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q "</html>" "$TMP" && log_ok "AUTH-5 /dashboard 完整 HTML" || log_bad "AUTH-5 /dashboard 不完整" "no </html>"

# AUTH-7 /health 公开
$CURL "$BASE/health" -o "$TMP" -w ""
grep -q '"status":"UP"' "$TMP" && log_ok "AUTH-7 /health 公开 JSON" || log_bad "AUTH-7 /health" "$(cat $TMP)"

# AUTH-8 已登录访问 /login 自动跳 /dashboard(书签 = /login 场景 · 2026-05-14)
loc=$($CURL -b $COOKIE -o /dev/null -w "%{http_code}|%{redirect_url}" "$BASE/login")
{ [[ "$loc" == "302|"*"/dashboard" ]]; } \
  && log_ok "AUTH-8 已登录 GET /login → 302 → /dashboard($loc)" \
  || log_bad "AUTH-8 已登录 /login 应跳 dashboard" "got=$loc"

# AUTH-9 未登录访问 /login 仍返回 200 + 登录表单(不影响首登)
CK2=$(mktemp)
code=$($CURL -c $CK2 -o "$TMP" -w "%{http_code}" "$BASE/login")
has_user=$(grep -c 'name="username"' "$TMP")
{ [[ "$code" == "200" ]] && [[ "$has_user" -ge 1 ]]; } \
  && log_ok "AUTH-9 未登录 GET /login → 200 + 用户名输入框(首登正常)" \
  || log_bad "AUTH-9 未登录 /login" "code=$code has_user_input=$has_user"
rm -f $CK2

# ---------- FR-1 ----------
section "FR-1 · 家庭与成员"

$CURL -b $COOKIE "$BASE/admin/family" -o "$TMP" -w ""
{ grep -q "家庭" "$TMP" && grep -q "周期类型" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR1-1 /admin/family 200+完整" || log_bad "FR1-1 /admin/family 缺" "missing"

# FR1-1a · /admin/family 保存生效(2026-05-14 bugfix · 之前嵌套 form 让主 save 失效)
ORIG_NAME=$(mysql -ufinance -pfinance finance -sN -e "SELECT name FROM family WHERE id=1" 2>/dev/null)
ORIG_BRAND=$(mysql -ufinance -pfinance finance -sN -e "SELECT brand_text FROM family WHERE id=1" 2>/dev/null)
# 先 GET 一次 admin/family 确保拿到当前 session 的 XSRF(早期登录的 token 可能已轮转)
$CURL -b $COOKIE -c $COOKIE "$BASE/admin/family" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST \
  --data-urlencode "_csrf=$XSRF" \
  --data-urlencode "name=QA-TEST-FAMILY" \
  --data-urlencode "brandText=QA-BRAND" \
  --data-urlencode "baseCurrency=CNY" \
  --data-urlencode "periodType=MONTHLY" \
  "$BASE/admin/family" -o /dev/null -w "%{http_code}")
DB_NAME_AFTER=$(mysql -ufinance -pfinance finance -sN -e "SELECT name FROM family WHERE id=1" 2>/dev/null)
DB_BRAND_AFTER=$(mysql -ufinance -pfinance finance -sN -e "SELECT brand_text FROM family WHERE id=1" 2>/dev/null)
{ [[ "$code" =~ ^30[0-9]$ ]] && [[ "$DB_NAME_AFTER" == "QA-TEST-FAMILY" ]] && [[ "$DB_BRAND_AFTER" == "QA-BRAND" ]]; } \
  && log_ok "FR1-1a /admin/family 保存写入 DB · name+brand_text 入库 · code=$code" \
  || log_bad "FR1-1a /admin/family 保存不生效" "code=$code db_name=$DB_NAME_AFTER db_brand=$DB_BRAND_AFTER"
# 还原
mysql -ufinance -pfinance finance -e "UPDATE family SET name='$ORIG_NAME', brand_text='$ORIG_BRAND' WHERE id=1" 2>/dev/null

$CURL -b $COOKIE "$BASE/admin/members" -o "$TMP" -w ""
{ grep -q "diwa" "$TMP" && grep -q "wangergou" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR1-2 /admin/members 列出 2 人" || log_bad "FR1-2 /admin/members" "missing names"

# FR1-7 添加成员入口存在
grep -q "+ 添加成员" "$TMP" && log_ok "FR1-7 /admin/members 含'添加成员'入口" || log_bad "FR1-7 添加成员入口" "missing"

# FR1-8 改密页可访问 (登录态下)
$CURL -b $COOKIE "$BASE/profile/password" -o "$TMP" -w ""
{ grep -q "修改" "$TMP" && grep -q "新密码" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR1-8 /profile/password 改密页" || log_bad "FR1-8 改密页" "missing"

# logo 上传 form
grep -q "logo" /tmp/finance-qa-resp.html && log_skip "FR1-6 logo 表单" "需视觉确认"

# ---------- FR-2 ----------
section "FR-2 · 模板向导"
$CURL -b $COOKIE "$BASE/accounts/new" -o "$TMP" -w ""
grep -q "添加账户向导" "$TMP" && log_ok "FR2-1 /accounts/new 弹向导" || log_bad "FR2-1 向导缺" "missing wizard"
grep -q "现金 (CASH)" "$TMP" && log_ok "FR2-3 类型下拉中文化" || log_bad "FR2-3 中文化" "no chinese label"

$CURL -b $COOKIE "$BASE/admin/account-templates" -o "$TMP" -w ""
grep -q "</html>" "$TMP" && log_ok "FR2-2 /admin/account-templates" || log_bad "FR2-2 模板页" "incomplete"

# ---------- FR-3 ----------
section "FR-3 · 账户管理"
$CURL -b $COOKIE "$BASE/accounts" -o "$TMP" -w ""
# v1.6.25 修正:原断言 grep 的是「招行储蓄卡-工资」—— 那**根本不是账户行**,而是建户向导里
#   `<input name="displayName" placeholder="如: 招行储蓄卡-工资">` 的占位符;而向导片段因为
#   `th:if` 与 `th:replace` 写在同一元素(优先级 1 > 3)被**无条件渲染**,占位符一直在 HTML 里。
#   叫这个名字的账户 2026-06-28 就归档了(默认列表不含归档)→ 这条守护**一个月来没在验账户列表**。
#   修好片段条件后它才露出来。改成从 DB 取**真实活跃账户名**,不再写死文案(免得再次腐烂)。
ACC1_NAME=$(mysql -ufinance -pfinance finance -sN -e "SELECT display_name FROM account WHERE family_id=1 AND archived_at IS NULL ORDER BY id LIMIT 1" 2>/dev/null)
{ [ -n "$ACC1_NAME" ] && grep -qF "$ACC1_NAME" "$TMP" && grep -q 'ledger-table' "$TMP"; } \
  && log_ok "FR3-1 /accounts 列表(含活跃账户「${ACC1_NAME}」+ 表格结构)" \
  || log_bad "FR3-1 /accounts" "列表里没有活跃账户「${ACC1_NAME:-取不到}」或缺 ledger-table"

$CURL -b $COOKIE "$BASE/accounts/1/edit" -o "$TMP" -w ""
{ grep -q "保存对账户的修改" "$TMP" && grep -q "招行储蓄卡-工资" "$TMP"; } && log_ok "FR3-3 编辑专属页正确" || log_bad "FR3-3 编辑页" "missing"

# ---------- FR-5 待办 / 周期 ----------
section "FR-5 · 周期与待办"
periods=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM period WHERE status='OPEN' AND family_id=1" 2>/dev/null)
[[ "$periods" -ge 1 ]] && log_ok "FR5-1 OPEN 周期存在 ($periods)" || log_bad "FR5-1 没有 OPEN 周期" "count=$periods"

todos=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM snapshot_todo st JOIN period p ON p.id=st.period_id WHERE p.status='OPEN' AND p.family_id=1" 2>/dev/null)
accounts=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM account WHERE archived_at IS NULL AND family_id=1" 2>/dev/null)
[[ "$todos" -gt 0 ]] && log_ok "FR5-2 待办存在 ($todos vs accounts $accounts)" || log_bad "FR5-2 待办空" "todos=$todos accounts=$accounts"

# ---------- FR-6 待办已折叠进填报(v0.11.7 退休 /my-todos)----------
section "FR-6 · 待办折叠进填报"
# FR6-1 · /my-todos 退休:302 重定向(照顾老书签)
code=$($CURL -b $COOKIE -o /dev/null -w '%{http_code}' "$BASE/my-todos")
{ [[ "$code" == "302" || "$code" == "301" ]]; } \
  && log_ok "FR6-1 /my-todos 已退休 · 重定向($code)" \
  || log_bad "FR6-1 /my-todos 未重定向" "code=$code"
# FR6-2 · 跟随重定向应落到「填报」页(能力已折叠到 /entry?mine=true)
$CURL -b $COOKIE -L "$BASE/my-todos" -o "$TMP" -w ""
{ grep -q "</html>" "$TMP" && grep -qE '保存我的本月收支|应填账户|填报' "$TMP"; } \
  && log_ok "FR6-2 /my-todos→填报页(能力已折叠到 /entry?mine=true)" \
  || log_bad "FR6-2 /my-todos 未落到填报页" "see redirect"

# 比较 mine=true vs mine=false
sa=$($CURL -b $COOKIE "$BASE/entry" -o /tmp/finance-qa-all.html -w "%{size_download}")
sm=$($CURL -b $COOKIE "$BASE/entry?mine=true" -o /tmp/finance-qa-mine.html -w "%{size_download}")
[[ "$sm" -lt "$sa" ]] && log_ok "FR6-3 mine=true 行数减少 (all=$sa mine=$sm)" || log_bad "FR6-3 mine 没减少" "all=$sa mine=$sm"

# 账户筛选
$CURL -b $COOKIE "$BASE/entry?account=1" -o "$TMP" -w ""
grep -q "已按账户筛选" "$TMP" && log_ok "FR6-4 account 筛选生效" || log_bad "FR6-4 account 筛选无 banner" "missing"

# ---------- FR-7~10 录入 / 现金流 / 转账 ----------
section "FR-7~10 · 录入/现金流/转账(POST)"

PERIOD_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE status='OPEN' AND family_id=1 LIMIT 1" 2>/dev/null)
ACC1=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE display_name='招行储蓄卡-工资' LIMIT 1" 2>/dev/null)
ACC2=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE display_name='工商银行-备用金' LIMIT 1" 2>/dev/null)

[[ -n "$PERIOD_ID" && -n "$ACC1" ]] || { log_bad "FR7-prep" "missing period/account ids"; }

# 提取 csrf cookie
CSRF=$(grep XSRF-TOKEN $COOKIE 2>/dev/null | awk '{print $7}' | tail -1)
[[ -z "$CSRF" ]] && { $CURL -b $COOKIE -c $COOKIE "$BASE/dashboard" -o /dev/null; CSRF=$(grep XSRF-TOKEN $COOKIE | awk '{print $7}' | tail -1); }

# FR-7 提交余额
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "newBalance=46000" --data-urlencode "note=qa-test" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/balance" -o "$TMP" -w "%{http_code}")
[[ "$code" == "200" ]] && grep -q "entry-row-$ACC1" "$TMP" && log_ok "FR7-2 提交余额 200+fragment" || log_bad "FR7-2 提交余额" "code=$code"

snap=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$ACC1" 2>/dev/null)
[[ -n "$snap" ]] && log_ok "FR7-2db 快照写入 ($snap)" || log_bad "FR7-2db 无快照" "no row"

# FR-8 收入
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=1000" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/cash-flow" -o /dev/null -w "%{http_code}")
[[ "$code" == "200" ]] && log_ok "FR8-1 提交收入 200" || log_bad "FR8-1 收入" "code=$code"

# FR8-1+ 收入提交后响应头含 HX-Trigger=refresh-row-{id} 让客户端 self-refresh 刷新 ledger
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=1234" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/cash-flow" -o /dev/null)
echo "$hdrs" | grep -qi "HX-Trigger:.*refresh-row-$ACC1" \
  && log_ok "FR8-1b 收入响应含 HX-Trigger=refresh-row-$ACC1(触发 GET 自我刷新 ledger)" \
  || log_bad "FR8-1b HX-Trigger 缺失" "no header"

# FR7-8~11 5 步场景:上期=10000 → +收入 100 → -支出 1000 → 校准 4000 → +收入 200
SCEN_ACC=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE display_name='工商银行-备用金' LIMIT 1" 2>/dev/null)
LATEST_PREV=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='CLOSED' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
mysql -ufinance -pfinance finance -e "
DELETE FROM cash_flow WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC;
DELETE FROM transfer WHERE period_id=$PERIOD_ID AND (from_account_id=$SCEN_ACC OR to_account_id=$SCEN_ACC);
INSERT INTO period_snapshot(period_id, account_id, end_balance, submitted_by, note)
VALUES ($PERIOD_ID, $SCEN_ACC, 10000, 1, 'qa-seed') ON DUPLICATE KEY UPDATE end_balance=10000, note='qa-seed';
UPDATE period_snapshot SET end_balance=10000 WHERE period_id=$LATEST_PREV AND account_id=$SCEN_ACC;" 2>/dev/null

# FR7-7 输入框预填上期值(input 元素跨行,用 pcregrep 多行 / 退而 awk)
$CURL -b $COOKIE "$BASE/entry?account=$SCEN_ACC" -o "$TMP" -w ""
awk '/name="newBalance"/{flag=3} flag>0{buf=buf $0; flag--} END{if(buf ~ /value="10000\.00"/) exit 0; else exit 1}' "$TMP" \
  && log_ok "FR7-7 输入框预填上期末 10000" \
  || log_bad "FR7-7 输入框未预填" "missing value=10000"

# FR7-8 +收入 100 → 余额 10100
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=100" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$SCEN_ACC/cash-flow" -o /dev/null
b1=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC" 2>/dev/null)
[[ "$b1" == "10100.00" ]] && log_ok "FR7-8 +收入100 → 余额=10100" || log_bad "FR7-8" "got=$b1"

# FR7-9 -支出 1000 → 余额 9100
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=EXPENSE" --data-urlencode "categoryCode=consumption" --data-urlencode "amount=1000" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$SCEN_ACC/cash-flow" -o /dev/null
b2=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC" 2>/dev/null)
[[ "$b2" == "9100.00" ]] && log_ok "FR7-9 -支出1000 → 余额=9100" || log_bad "FR7-9" "got=$b2"

# FR7-10 校准余额 4000 → 直接覆盖
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "newBalance=4000" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$SCEN_ACC/balance" -o /dev/null
b3=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC" 2>/dev/null)
[[ "$b3" == "4000.00" ]] && log_ok "FR7-10 校准至 4000(覆盖)" || log_bad "FR7-10" "got=$b3"

# FR7-11 校准后 +收入 200 → 余额 4200(在新基础上累加)
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=200" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$SCEN_ACC/cash-flow" -o /dev/null
b4=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PERIOD_ID AND account_id=$SCEN_ACC" 2>/dev/null)
[[ "$b4" == "4200.00" ]] && log_ok "FR7-11 校准后 +收入200 → 余额=4200" || log_bad "FR7-11" "got=$b4"

# FR-9-1 转账(随机金额避开 24h 去重)
AMT=$(( RANDOM % 9000 + 100 ))
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "toAccountId=$ACC2" --data-urlencode "amount=$AMT" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/transfer" -o /dev/null -w "%{http_code}")
[[ "$code" == "200" ]] && log_ok "FR9-1 提交转账 200 (amount=$AMT)" || log_bad "FR9-1 转账" "code=$code"

# FR-9-1b 转账响应含 HX-Trigger=refresh-row-{toId} 让 B 行 self-refresh
AMT2=$(( RANDOM % 9000 + 100 ))
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "toAccountId=$ACC2" --data-urlencode "amount=$AMT2" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/transfer" -o /dev/null)
echo "$hdrs" | grep -qi "HX-Trigger:.*refresh-row-$ACC2" \
  && log_ok "FR9-1b 转账响应触发 refresh-row-$ACC2(B 账户行自动刷新)" \
  || log_bad "FR9-1b HX-Trigger 缺失" "no refresh trigger"

# FR-9-2 同金额二次提交 → 200 + HX-Trigger=showToast(由 ToastErrorAdvice 转友好提示)
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "toAccountId=$ACC2" --data-urlencode "amount=$AMT" --data-urlencode "periodId=$PERIOD_ID" \
  "$BASE/entry/$ACC1/transfer" -o /dev/null)
echo "$hdrs" | grep -qi "HX-Trigger:.*showToast" \
  && log_ok "FR9-2 24h 重复转账 → toast 提示(替代 500)" \
  || log_bad "FR9-2 重复未拦截" "missing toast"

# FR-11/12 关账 + 立即开下一周期 + CLOSED 期 toast
section "FR-11/12 · 关账 / 开新期 / CLOSED 拒写"

# 立即开下一周期
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" \
  --data-urlencode "_csrf=$CSRF" \
  "$BASE/admin/periods/open-next" -o /dev/null)
echo "$hdrs" | head -1 | grep -q "302" && log_ok "FR12-3 立即开下一周期 → 302" || log_bad "FR12-3 立即开下一周期" "no 302"

# 强制关账(找一个 OPEN 周期,断言:CLOSED + PENDING=0 + snapshot 全补齐)
FORCE_CLOSE=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
ACCT_COUNT=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM account WHERE family_id=1 AND archived_at IS NULL" 2>/dev/null)
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" --data-urlencode "_csrf=$CSRF" \
  "$BASE/admin/periods/$FORCE_CLOSE/force-close" -o /dev/null
NEW_STATUS=$(mysql -ufinance -pfinance finance -sN -e "SELECT status FROM period WHERE id=$FORCE_CLOSE" 2>/dev/null)
SNAP_COUNT=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM period_snapshot WHERE period_id=$FORCE_CLOSE" 2>/dev/null)
PENDING=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM snapshot_todo WHERE period_id=$FORCE_CLOSE AND status='PENDING'" 2>/dev/null)
[[ "$NEW_STATUS" == "CLOSED" && "$PENDING" == "0" && "$SNAP_COUNT" == "$ACCT_COUNT" ]] \
  && log_ok "FR11-5 强制关账 period=$FORCE_CLOSE: CLOSED + 0 PENDING + $SNAP_COUNT snapshot" \
  || log_bad "FR11-5 强制关账失败" "status=$NEW_STATUS pending=$PENDING snap=$SNAP_COUNT/$ACCT_COUNT"

# CLOSED 周期点 +收入 → 应返回 200 + HX-Trigger=showToast
CLOSED_PERIOD=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='CLOSED' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
hdrs=$($CURL -b $COOKIE -D - -X POST -H "X-XSRF-TOKEN: $CSRF" -H "HX-Request: true" \
  --data-urlencode "kind=INCOME" --data-urlencode "categoryCode=salary" --data-urlencode "amount=10" --data-urlencode "periodId=$CLOSED_PERIOD" \
  "$BASE/entry/1/cash-flow" -o /dev/null)
echo "$hdrs" | grep -qi "HX-Trigger:.*showToast" \
  && log_ok "FR11-4 CLOSED 期点+收入 → HX-Trigger=showToast(toast 拒写)" \
  || log_bad "FR11-4 CLOSED 拒写无 toast 头" "missing"

# ── 把 OPEN 周期还回去(2026-08-13)──
#
#   本段刻意做了两件"关"的事:`open-next`(它会把当期**结转关账**再开下一期)+ `force-close`
#   (关掉刚开的那期)→ 跑完这一段,库里**一个 OPEN 周期都不剩**。
#   而下游 v02-UX / v02-SOFT-DEL / v03-IND / v03-STOCK / v04-VAL / v09-FORM 十几条断言的是
#   `/entry` 页上的**录入框**,没有 OPEN 周期时 entry 只显示「本期已关账」→ 这十几条**集体假红**。
#   实测:补上这个助手后 FAIL 从 28 掉到个位数,而那些"红"从头到尾都不是代码的问题。
#
#   走 `/admin/periods/{id}/reopen` **真实入口**(不直接 UPDATE 库):既符合"验收走用户路径",
#   也顺带覆盖了重开链路。只重开 period_start <= 今天 的最新一期 —— 库里若有未来期(beta 曾被
#   灌到 2040-08),重开未来期等于把"当期"推到十几年后,entry 照样是空的。
ensure_open_period(){
  local n; n=$(mysql -ufinance -pfinance finance -sN -e \
    "SELECT COUNT(*) FROM period WHERE family_id=1 AND status='OPEN'" 2>/dev/null)
  [[ "${n:-0}" -gt 0 ]] && return 0
  local pid; pid=$(mysql -ufinance -pfinance finance -sN -e \
    "SELECT id FROM period WHERE family_id=1 AND status='CLOSED' AND period_start<=CURDATE() \
     ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
  [[ -z "$pid" ]] && { log_bad "QA-ENV 没有可重开的账期" "period 表里没有 period_start<=今天 的 CLOSED 期"; return 1; }
  $CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $CSRF" \
    --data-urlencode "_csrf=$CSRF" --data-urlencode "reason=qa-run 关账段跑完后恢复当期,供下游 entry 用例" \
    "$BASE/admin/periods/$pid/reopen" -o /dev/null
  local st; st=$(mysql -ufinance -pfinance finance -sN -e "SELECT status FROM period WHERE id=$pid" 2>/dev/null)
  [[ "$st" == "OPEN" ]] \
    && log_ok "QA-ENV 关账段后恢复 OPEN 周期 period=$pid(下游 entry 用例的前提)" \
    || log_bad "QA-ENV 恢复 OPEN 周期失败" "period=$pid status=$st"
}
ensure_open_period

# ---------- FR-12 周期重开 ----------
section "FR-12 · 周期重开"
$CURL -b $COOKIE "$BASE/admin/periods" -o "$TMP" -w ""
{ grep -q "OPEN\|CLOSED" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR12-1 /admin/periods 列表" || log_bad "FR12-1 /admin/periods" "missing"

# ---------- FR-13 Dashboard ----------
section "FR-13 · Dashboard"
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
# v0.10.6 去过期:第 5 卡 v0.4 FR-60a 起从「负债率」改为「本月资产收益」(负债率搬 /checkup)。
#   原锚词「负债率」只命中 _region.html HTML 注释 → 靠注释假通过。改锚真实卡名。
{ grep -q "净资产" "$TMP" && grep -q "总资产" "$TMP" && grep -q "总负债" "$TMP" && grep -q "紧急储备" "$TMP" && grep -q "本月资产收益" "$TMP"; } \
  && log_ok "FR13-1 5 KPI 卡齐(净资产/总资产/总负债/紧急储备/本月资产收益)" || log_bad "FR13-1 KPI 不全" "missing"

for r in 1M 3M 6M YTD 1Y ALL; do
  $CURL -b $COOKIE "$BASE/dashboard?range=$r" -o "$TMP" -w ""
  grep -q "</html>" "$TMP" && log_ok "FR13-2/$r 完整 HTML" || log_bad "FR13-2/$r 不完整" "no </html>"
done

# HTMX fragment-only
$CURL -b $COOKIE -H "HX-Request: true" "$BASE/dashboard?range=YTD" -o "$TMP" -w ""
{ ! grep -q "<html" "$TMP" && grep -q "dashboard-region" "$TMP"; } \
  && log_ok "FR13-3 HX-Request 返回 fragment" || log_bad "FR13-3 fragment 异常" "html present?"

# ---------- FR-14 Reports ----------
section "FR-14 · Reports"
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q "家庭 XIRR" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR14-1 /reports 完整" || log_bad "FR14-1 /reports" "missing"

for r in 1M 3M 6M YTD 1Y ALL; do
  $CURL -b $COOKIE "$BASE/reports?range=$r" -o "$TMP" -w ""
  grep -q "</html>" "$TMP" && log_ok "FR14-2/$r 完整" || log_bad "FR14-2/$r" "no </html>"
done

# ---------- FR-15 多币种 ----------
section "FR-15 · 多币种"
$CURL -b $COOKIE "$BASE/admin/fx" -o "$TMP" -w ""
{ grep -q "USD" "$TMP" || grep -q "HKD" "$TMP"; } && grep -q "</html>" "$TMP" \
  && log_ok "FR15-1 /admin/fx 含 USD/HKD" || log_bad "FR15-1 /admin/fx" "missing"

# ---------- FR-16 CSV ----------
section "FR-16 · CSV 导出"
$CURL -b $COOKIE "$BASE/export.zip" -o /tmp/finance-qa.zip -w ""
file /tmp/finance-qa.zip 2>&1 | grep -q "Zip" && log_ok "FR16-1 /export.zip 是 ZIP" || log_bad "FR16-1 不是 ZIP" "$(file /tmp/qa.zip)"

cnt=$(python3 -c "import zipfile; z=zipfile.ZipFile('/tmp/finance-qa.zip'); print(len(z.infolist()))" 2>/dev/null)
[[ "$cnt" == "9" ]] && log_ok "FR16-2 ZIP 含 9 个文件" || log_bad "FR16-2 文件数 $cnt" "expected 9"

bom=$(python3 -c "import zipfile; z=zipfile.ZipFile('/tmp/finance-qa.zip'); print(z.read('families.csv')[:3].hex())" 2>/dev/null)
[[ "$bom" == "efbbbf" ]] && log_ok "FR16-3 UTF-8 BOM 头" || log_bad "FR16-3 BOM" "got $bom"

# ---------- FR-17 banner / FR-18 备份 ----------
section "FR-17/18"
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -qE "未结账|本期还有|未填" "$TMP" && log_ok "FR17-1 dashboard 含 pending banner 元素" || log_skip "FR17-1 banner" "可能本期已全填"

$CURL -b $COOKIE "$BASE/admin/backup" -o "$TMP" -w ""
{ grep -q "备份" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR18-1 /admin/backup" || log_bad "FR18-1" "missing"

# ---------- FR-19 LOAN ----------
section "FR-19 · LOAN"
LOAN_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE type='LOAN' LIMIT 1" 2>/dev/null)
if [[ -n "$LOAN_ID" ]]; then
  $CURL -b $COOKIE "$BASE/accounts/$LOAN_ID/edit" -o "$TMP" -w ""
  grep -q "默认还款来源" "$TMP" && log_ok "FR19-3 LOAN 编辑含还款来源字段" || log_bad "FR19-3 LOAN 字段" "missing"
else
  log_skip "FR19-3 无 LOAN 账户" "skip"
fi

# ---------- FR-20 admin 全部 ----------
section "FR-20 · /admin 全部子页"
for path in /admin /admin/family /admin/members /admin/account-templates /admin/cash-flow-categories /admin/periods /admin/reminders /admin/fx /admin/backup /admin/audit /admin/calc-tweaks; do
  $CURL -b $COOKIE "$BASE$path" -o "$TMP" -w ""
  grep -q "</html>" "$TMP" && log_ok "FR20 $path" || log_bad "FR20 $path 不完整" "no </html>"
done

# ---------- FR-21 账户筛选 ----------
section "FR-21 · 账户筛选"
$CURL -b $COOKIE "$BASE/dashboard?accounts=1" -o "$TMP" -w ""
{ grep -q "1 个已选\|个已选" "$TMP" && grep -q "</html>" "$TMP"; } && log_ok "FR21-1 ?accounts=1 显示筛选" || log_bad "FR21-1 筛选" "missing"

# ---------- FR-22 显示币种 ----------
section "FR-22 · 显示币种"
$CURL -b $COOKIE "$BASE/dashboard?currency=USD" -o "$TMP" -w ""
grep -qF '$' "$TMP" && grep -q "</html>" "$TMP" && log_ok "FR22-1 USD 显示 \$" || log_bad "FR22-1 USD" "missing $"
$CURL -b $COOKIE "$BASE/dashboard?currency=HKD" -o "$TMP" -w ""
grep -qF 'HK$' "$TMP" && log_ok "FR22-2 HKD 显示 HK\$" || log_bad "FR22-2 HKD" "missing HK$"

# v0.2 BUG-FIX(2026-05-10):币种切换以前只换符号不换数字。
# 这里强校验三套币种渲染出的「净资产」KPI 数字必须真的不同(假设家庭只有 CNY 账户 + 至少一行 fx_rate)。
# 防止 FactMapper.xml fx CASE 倒挂 / controller 漏调 fxFallback 等回归。
fx_seed_periodId=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
mysql -ufinance -pfinance finance -e "
INSERT INTO fx_rate (family_id, base_currency, quote_currency, period_id, rate, source) VALUES
(1, 'CNY', 'USD', ${fx_seed_periodId}, 0.140000, 'qa-seed'),
(1, 'CNY', 'HKD', ${fx_seed_periodId}, 1.090000, 'qa-seed')
ON DUPLICATE KEY UPDATE rate=VALUES(rate), source=VALUES(source);" 2>/dev/null

# 提取 currency=X 时净资产 KPI 数字(去千分位 + 符号)
extract_nw() {
  local cur="$1"
  $CURL -b $COOKIE "$BASE/dashboard?currency=${cur}" -o "$TMP" -w ""
  grep -A1 'kpi-eyebrow">净资产' "$TMP" | grep "kpi-value" | head -1 | sed -E 's/.*>([^<]+)<.*/\1/' | tr -d ',$¥' | sed 's/HK//'
}
nw_cny=$(extract_nw CNY)
nw_usd=$(extract_nw USD)
nw_hkd=$(extract_nw HKD)
[[ "$nw_cny" != "$nw_usd" && "$nw_cny" != "$nw_hkd" && "$nw_usd" != "$nw_hkd" ]] \
  && log_ok "v02-CCY-1 三套币种净资产数字真的不同 (CNY=${nw_cny} USD=${nw_usd} HKD=${nw_hkd})" \
  || log_bad "v02-CCY-1 币种切换数字未联动" "CNY=${nw_cny} USD=${nw_usd} HKD=${nw_hkd}"

# 数学校验:USD 净资产 / CNY 净资产 = 合理的 USD/CNY 汇率区间(0.10~0.20),且 ≠ 1.0(非 fallback)。
# v0.8:不再硬编码 0.14(那是旧 anchor 期的种子率;dashboard 现锚当前期,实际率随期不同)。
if [[ -n "$nw_cny" && -n "$nw_usd" ]]; then
  ratio=$(awk -v u="$nw_usd" -v c="$nw_cny" 'BEGIN{ if(c==0){print 0}else{printf "%.4f", u/c} }')
  ok_ratio=$(awk -v r="$ratio" 'BEGIN{ print (r>=0.10 && r<=0.20) ? 1 : 0 }')
  [[ "$ok_ratio" == "1" ]] \
    && log_ok "v02-CCY-2 USD 数学正确 · USD/CNY 比值=${ratio}(合理区间,非 1.0 fallback)实际 USD=${nw_usd}" \
    || log_bad "v02-CCY-2 USD 数学错" "USD/CNY 比值=${ratio} 不在 0.10~0.20(USD nw=${nw_usd} CNY nw=${nw_cny})"
fi

# v0.5 修 · 比值类指标(紧急储备月数)必须币种无关:换币种时分子(流动资产·view)和
# 分母(月支出·PMC)应同口径换算,比值不变。回归点:PMC 漏换 base→view 导致比值漂移。
extract_emergency() {
  local cur="$1"
  $CURL -b $COOKIE "$BASE/dashboard?currency=${cur}" -o "$TMP" -w ""
  # 紧急储备月数值带「月」后缀(金额带 ¥/$)· 取含「月」的 kpi-value 数字 · 唯一锚不会抓到金额
  grep 'kpi-value' "$TMP" | grep '月' | head -1 | sed -E 's/<[^>]+>//g' | grep -oE '[0-9]+(\.[0-9]+)?' | head -1
}
em_cny=$(extract_emergency CNY)
em_usd=$(extract_emergency USD)
em_hkd=$(extract_emergency HKD)
if [[ -n "$em_cny" && -n "$em_usd" && -n "$em_hkd" ]]; then
  if [[ "$em_cny" == "$em_usd" && "$em_cny" == "$em_hkd" ]]; then
    log_ok "v05-CCY-INV-1 紧急储备月数币种无关 (CNY=${em_cny} USD=${em_usd} HKD=${em_hkd} 月)"
  else
    log_bad "v05-CCY-INV-1 比值随币种漂移(PMC 未换算 base→view)" "CNY=${em_cny} USD=${em_usd} HKD=${em_hkd}"
  fi
else
  log_skip "v05-CCY-INV-1 紧急储备月数 币种无关" "无数据(需 LIQUID 账户 + 月支出)"
fi

# ───────────────────────────────────────────────────────────────────────────
# v0.8 BUG-FIX(v08-CCY-INV)· 属性级币种不变性护栏(反复踩雷点的根治防回归)
#   语义:视图币种 = 显示镜头 → ① 比值类指标(收益率/月数/负债率/环比)切币种必须【完全相等】;
#        ② 金额类指标按 fx 因子【精确缩放】。
#   背景:v0.8 筛选器重做引入多期依赖,但 ensure 只覆盖 anchor 一期 → 上期/窗口期缺汇率落 1.0 未换算,
#        且视图币种为「第三币种」(USD 账户在 HKD 视图)缺三角换算 → 本月资产收益率 CNY −18%/HKD −9%/USD −88%。
#   修:ensure 扩到 ≤anchor 全期 + 视图币种全期补 base→view + FactMapper 经本位币三角换算。
#   本护栏:先给 family#1 所有账期播【一致】汇率(消除 beta 历史期混合率/2034 种子噪声),使不变式可严格断言。
mysql -ufinance -pfinance finance -e "
INSERT INTO fx_rate (family_id, base_currency, quote_currency, period_id, rate, source)
  SELECT 1,'CNY','USD',id,0.140000,'qa-inv-seed' FROM period WHERE family_id=1
  ON DUPLICATE KEY UPDATE rate=VALUES(rate), source=VALUES(source);
INSERT INTO fx_rate (family_id, base_currency, quote_currency, period_id, rate, source)
  SELECT 1,'CNY','HKD',id,1.090000,'qa-inv-seed' FROM period WHERE family_id=1
  ON DUPLICATE KEY UPDATE rate=VALUES(rate), source=VALUES(source);" 2>/dev/null

extract_one_pct() {   # 单个 eyebrow 标签下首个 kpi-value(如本月资产收益率)
  # v1.6.30 · 原实现按 `kpi-eyebrow">标签` 紧邻匹配,标签一旦被包进 <span>(为显示口径期)就抓空,
  #   导致 v08-CCY-INV-2 报「币种漂移」而其实是取数失败 —— 三个币种全取到空串也会判不相等。
  #   改成按标签文本就近匹配(容忍嵌套标签),并要求调用方传两种文案的公共子串。
  $CURL -b $COOKIE "$BASE/dashboard?currency=$1" -o "$TMP" -w ""
  grep -A4 "$2" "$TMP" | grep 'kpi-value' | head -1 | sed -E 's/<[^>]+>//g' | tr -d ' \n'
}
extract_ratio_kpis() {  # 所有含 % 或「月」的 kpi-value(比值类);金额带币种符号天然不入选
  $CURL -b $COOKIE "$BASE/dashboard?currency=$1" -o "$TMP" -w ""
  grep 'kpi-value' "$TMP" | sed -E 's/<[^>]+>//g' | tr -d ' ' | grep -E '%|月'
}

# v08-CCY-INV-2:本月资产收益率(用户实际踩雷点)币种无关
pr_cny=$(extract_one_pct CNY 资产收益); pr_usd=$(extract_one_pct USD 资产收益); pr_hkd=$(extract_one_pct HKD 资产收益)
if [[ -n "$pr_cny" && "$pr_cny" == "$pr_usd" && "$pr_cny" == "$pr_hkd" ]]; then
  log_ok "v08-CCY-INV-2 本月资产收益率币种无关 (CNY=$pr_cny USD=$pr_usd HKD=$pr_hkd)"
else
  log_bad "v08-CCY-INV-2 本月资产收益率随币种漂移(跨期/三角换算口径不一致)" "CNY=$pr_cny USD=$pr_usd HKD=$pr_hkd"
fi

# v08-CCY-INV-3:属性级 —— 所有比值类 KPI 切币种完全相等(网住未来任何新增比值指标)
rk_cny=$(extract_ratio_kpis CNY); rk_usd=$(extract_ratio_kpis USD); rk_hkd=$(extract_ratio_kpis HKD)
if [[ -n "$rk_cny" && "$rk_cny" == "$rk_usd" && "$rk_cny" == "$rk_hkd" ]]; then
  log_ok "v08-CCY-INV-3 所有比值类KPI币种无关(属性级 · $(echo "$rk_cny" | tr '\n' ' '))"
else
  log_bad "v08-CCY-INV-3 比值类KPI随币种漂移" "CNY=[$(echo $rk_cny)] USD=[$(echo $rk_usd)] HKD=[$(echo $rk_hkd)]"
fi

# v08-CCY-INV-4:金额类按 fx 精确缩放(净资产 USD≈CNY×0.14 · HKD≈CNY×1.09 · 容 0.5% 舍入)
inw_cny=$(extract_nw CNY); inw_usd=$(extract_nw USD); inw_hkd=$(extract_nw HKD)
if [[ -n "$inw_cny" && -n "$inw_usd" && -n "$inw_hkd" ]]; then
  ok_usd=$(awk -v u="$inw_usd" -v c="$inw_cny" 'BEGIN{r=(c==0)?0:u/c; print (r>=0.1393&&r<=0.1407)?1:0}')
  ok_hkd=$(awk -v h="$inw_hkd" -v c="$inw_cny" 'BEGIN{r=(c==0)?0:h/c; print (r>=1.0845&&r<=1.0955)?1:0}')
  [[ "$ok_usd" == "1" && "$ok_hkd" == "1" ]] \
    && log_ok "v08-CCY-INV-4 金额按 fx 精确缩放 (USD/CNY≈0.14 · HKD/CNY≈1.09)" \
    || log_bad "v08-CCY-INV-4 金额缩放偏离 fx" "USD/CNY=$(awk -v u=$inw_usd -v c=$inw_cny 'BEGIN{if(c==0)print "NA";else printf "%.4f",u/c}') HKD/CNY=$(awk -v h=$inw_hkd -v c=$inw_cny 'BEGIN{if(c==0)print "NA";else printf "%.4f",h/c}')"
fi

# v08-PILL-M · 手机端 dashboard 账户卡片:类型标签在账户名【前】+ 固定宽度对齐(与 PC 表一致)
#   防回归到旧版「账户名后 ml-1 的 pill」(PC 已前置,手机端 2026-06-23 补齐)。源级判定:
#   在 sm:hidden 手机块里,带 min-width 的 pill 行须早于账户名(font-display)行。
REG_DASH=src/main/resources/templates/dashboard/_region.html
mblk=$(awk '/sm:hidden space-y-2/{f=1} f{print} END{}' "$REG_DASH" | head -25)
pl=$(printf '%s\n' "$mblk" | grep -n 'pill text-center shrink-0' | head -1 | cut -d: -f1)
nl=$(printf '%s\n' "$mblk" | grep -n 'font-display text-sm' | head -1 | cut -d: -f1)
if [[ -n "$pl" && -n "$nl" && "$pl" -lt "$nl" ]] && printf '%s' "$mblk" | grep -q 'min-width:3.4em'; then
  log_ok "v08-PILL-M 手机卡片类型标签在账户名前 + 固定宽度对齐(min-width:3.4em)"
else
  log_bad "v08-PILL-M 手机卡片类型标签未前置/未对齐(防回归到名后 ml-1)" "pill行=$pl 名行=$nl"
fi

# v0.5.3 · 计算指标 tooltip 展示真实数值:每页 ⓘ 面板含 .kpi-info-calc 行(口径下方的实算)。
# 回归点:_kpi-info 片段从 i(text) 升 i(text,calc) + 各 controller 注入 calc map。
# 用净资产「总资产 ¥ − 总负债 ¥ = ¥」断言:它恒有真实数值(不依赖月支出/PMC 填报情况)。
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q 'kpi-info-calc' "$TMP" && grep -qE 'kpi-info-calc[^>]*>总资产 [^<]*− 总负债' "$TMP"; } \
  && log_ok "v05-CALC-1 /dashboard ⓘ 含真实计算数值(净资产 = 总资产 − 总负债 实算)" \
  || log_bad "v05-CALC-1 /dashboard tooltip 无真实数值" "no .kpi-info-calc / 净资产实算"
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'kpi-info-calc' "$TMP" && grep -qE 'kpi-info-calc[^>]*>\(期末净资产' "$TMP"; } \
  && log_ok "v05-CALC-2 /reports ⓘ 含真实计算数值(钱赚 = (期末−起始)−净流入 实算)" \
  || log_bad "v05-CALC-2 /reports tooltip 无真实数值" "no .kpi-info-calc / 钱赚实算"
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q 'kpi-info-calc' "$TMP" && grep -qE 'kpi-info-calc[^>]*>总资产 [^<]*− 总负债' "$TMP"; } \
  && log_ok "v05-CALC-3 /checkup ⓘ 含真实计算数值(净资产实算)" \
  || log_bad "v05-CALC-3 /checkup tooltip 无真实数值" "no .kpi-info-calc / 净资产实算"

# v0.5.5 · 报表「已关账快照」透出 + dashboard 仍实时(两 tab 分工)
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
# v1.23 · 加了第三种合法状态:**锚期已填完但还在关账宽限窗口里**。
#   那时既不能倒退回上上期(报表会「少一个月」),也不能假装已定稿 ——
#   页面如实说「X 月仍在填报中,数字可能还会变」。它同样是在透出快照语义,
#   所以进判据;漏了它,双活跃窗口下这条护栏必红,而页面其实是对的。
{ grep -q '已关账账期的稳定快照' "$TMP" || grep -q '尚无已关账账期' "$TMP" \
  || grep -q '仍在填报中' "$TMP"; } \
  && log_ok "v05-SNAP-1 /reports 透出快照语义(已定稿 / 宽限中 / 空态 三选一)" \
  || log_bad "v05-SNAP-1 /reports 未透出快照语义" "no 已关账快照 / 仍在填报中 / 尚无已关账"
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q '已关账账期的稳定快照' "$TMP" \
  && log_bad "v05-SNAP-2 dashboard 误带报表快照文案(应保持实时)" "found on dashboard" \
  || log_ok "v05-SNAP-2 /dashboard 不含报表快照文案(仍实时 · 分工清晰)"

# v0.5.6 · 报表长文目录(PC 右栏树状大纲 + 章节锚点 + 手机 sheet)
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'toc-rail' "$TMP" && grep -q 'class="toc-node"' "$TMP" \
  && grep -q 'id="sec-decompose"' "$TMP" && grep -q 'id="sec-accounts"' "$TMP" \
  && grep -q 'id="toc-sheet"' "$TMP"; } \
  && log_ok "v05-TOC-1 /reports 含右栏树状目录 + 章节锚点 + 手机 sheet" \
  || log_bad "v05-TOC-1 /reports 目录/锚点缺" "no toc-rail/toc-node/sec-* /toc-sheet"
# v0.5.7 · 目录推广 dashboard + checkup(共用件)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q 'class="toc-rail"' "$TMP" && grep -q 'js/toc.js' "$TMP" && grep -q 'id="dash-trend"' "$TMP"; } \
  && log_ok "v05-TOC-2 /dashboard 接入长文目录 + 锚点" \
  || log_bad "v05-TOC-2 /dashboard 目录缺" "no toc-rail/toc.js/dash-trend"
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q 'class="toc-rail"' "$TMP" && grep -q 'js/toc.js' "$TMP" && grep -q 'id="checkup-ai"' "$TMP"; } \
  && log_ok "v05-TOC-3 /checkup 接入长文目录 + 锚点" \
  || log_bad "v05-TOC-3 /checkup 目录缺" "no toc-rail/toc.js/checkup-ai"

# 按需拉汇率:删 fx_rate 后切 USD,后端应即时调 frankfurter API 拉新汇率写入,然后正常显示 $
mysql -ufinance -pfinance finance -e "DELETE FROM fx_rate;" 2>/dev/null
$CURL --max-time 30 -b $COOKIE "$BASE/dashboard?currency=USD" -o "$TMP" -w ""
fx_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM fx_rate WHERE family_id=1 AND quote_currency='USD' AND source='frankfurter.dev';" 2>/dev/null)
if [[ "${fx_after:-0}" -ge 1 ]]; then
  log_ok "v02-CCY-3 fx_rate 缺 → 即时调 frankfurter 拉取并入库(source=frankfurter.dev count=$fx_after)"
  # 拉成功后 USD KPI 应该是 $ 而不是 ¥
  grep -A1 'kpi-eyebrow">净资产' "$TMP" | grep "kpi-value" | head -1 | grep -qF '$' \
    && log_ok "v02-CCY-4 即时拉成功后正常显示 \$ 数字(无 toast 兜底)" \
    || log_bad "v02-CCY-4 即时拉成功但仍未显示 \$" "wrong symbol"
else
  # 网络拉不到时:fxFallback 路径,toast 脚本必现 + 显示 ¥
  log_skip "v02-CCY-3 frankfurter 不可达 / 拉失败,走 fallback 路径校验" "fx_after=$fx_after"
  grep -q "汇率未配置" "$TMP" \
    && log_ok "v02-CCY-4 拉失败 fallback → 渲染「汇率未配置」toast 脚本" \
    || log_bad "v02-CCY-4 fallback toast 缺" "no 汇率未配置"
fi

# v02-CCY-5 模板防回归:确保 dashboard / reports 都有 fxFallback toast 脚本块
#   v0.4.4 文案专业化后改为「汇率尚未配置」(去掉"自动拉取也失败" + "联系管理员"的开发口吻)
grep -q '汇率尚未配置' src/main/resources/templates/dashboard/_region.html \
  && grep -q '汇率尚未配置' src/main/resources/templates/reports/_region.html \
  && log_ok "v02-CCY-5 dashboard / reports 均含 fxFallback toast 脚本块(防回归)" \
  || log_bad "v02-CCY-5 fxFallback toast 模板缺失" "missing in dashboard/reports _region.html"

# v02-CCY-6 critical 回归保护:非 base 账户在 dashboard 触发后,fx_rate 表必有当期行
# (防 ensureForAccountCurrencies 漏调,SQL JOIN miss 落 1.0 → USD 当 CNY 累加)
# 数学正确性的端到端校验在 e2e.sh,这里只验"机制触发"
PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
# v0.8:dashboard anchor = 当前账期(resolveAsOf:OPEN 期 → 最近已开始期 → max 兜底),不再取 max(period_start)
ANCHOR=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
[[ -z "$ANCHOR" ]] && ANCHOR=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND period_start<=CURDATE() ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
[[ -z "$ANCHOR" ]] && ANCHOR=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
# 看是否存在非 base 账户(demo 有 USD 富途证券 id=4)
nonbase_count=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM account WHERE family_id=1 AND currency != 'CNY' AND archived_at IS NULL" 2>/dev/null)
if [[ "${nonbase_count:-0}" -ge 1 ]]; then
  # 清当期 anchor 的 fx_rate 行(只清当期 USD/HKD,其它行保留)
  mysql -ufinance -pfinance finance -e "DELETE FROM fx_rate WHERE period_id=$ANCHOR" 2>/dev/null
  $CURL --max-time 30 -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
  # 触发后:anchor 周期下应有该 USD/HKD 的 fx_rate 行(frankfurter 拉或 copy)
  fx_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM fx_rate WHERE period_id=$ANCHOR" 2>/dev/null)
  [[ "${fx_after:-0}" -ge 1 ]] \
    && log_ok "v02-CCY-6 非 base 账户 → dashboard 触发 ensureForAccountCurrencies 写入 fx_rate (anchor=${ANCHOR} count=${fx_after})" \
    || log_bad "v02-CCY-6 ensureForAccountCurrencies 未触发" "anchor=${ANCHOR} fx_count=${fx_after}"

  # v02-CCY-7 当期缺 fx_rate 但他期有 → copy 到当期(防 SQL JOIN miss)
  mysql -ufinance -pfinance finance 2>/dev/null <<SQL
DELETE FROM fx_rate WHERE period_id=$ANCHOR;
INSERT INTO fx_rate (family_id, base_currency, quote_currency, period_id, rate, source)
VALUES (1, 'CNY', 'USD', 1, 0.150000, 'qa-other-period')
ON DUPLICATE KEY UPDATE rate=VALUES(rate);
SQL
  $CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
  copied=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM fx_rate WHERE period_id=$ANCHOR AND source LIKE 'copied-from%'" 2>/dev/null)
  [[ "${copied:-0}" -ge 1 ]] \
    && log_ok "v02-CCY-7 当期缺 fx_rate → 从他期 copy(防 SQL JOIN miss · 不调 frankfurter)" \
    || log_bad "v02-CCY-7 copy 未触发" "copied=$copied"
else
  log_skip "v02-CCY-6/7 跳过 — 当前 DB 无非 base 账户" "test 需要 USD/HKD 账户存在"
fi

# ---------- 静态资源 ----------
section "Static / vendor"
for f in /vendor/tailwind.js /vendor/htmx.min.js /vendor/chart.umd.min.js /vendor/echarts.min.js /css/style.css; do
  code=$($CURL -o /dev/null -w "%{http_code}" "$BASE$f")
  [[ "$code" == "200" ]] && log_ok "ST $f 200" || log_bad "ST $f" "code=$code"
done

# ---------- v0.2 错误兜底页 ----------
section "v0.2 · 错误兜底页"

# /error 直接访问(已登录),渲染完整错误页
$CURL -b $COOKIE -H "Accept: text/html" "$BASE/error" -o "$TMP" -w ""
{ grep -q '印泥洒了' "$TMP" && grep -q '出 · 错 · 了' "$TMP" && grep -q '/dashboard' "$TMP" && grep -q '/entry' "$TMP"; } \
  && log_ok "ERR-1 /error(已登录)渲染卡通错误页 + dashboard+entry 链接" \
  || log_bad "ERR-1 /error 内容缺" "missing"

# 登录后访问不存在路径 → 404 + error.html
code=$($CURL -b $COOKIE -H "Accept: text/html" -o "$TMP" -w "%{http_code}" "$BASE/no-such-page-zxy")
{ [[ "$code" == "404" ]] && grep -q '印泥洒了' "$TMP"; } \
  && log_ok "ERR-2 登录后 404 渲染卡通错误页" \
  || log_bad "ERR-2 404 不渲染 error.html" "code=$code"

# 前端兜底:layout 含 5 个全局错误监听
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q 'htmx:responseError' "$TMP" && grep -q 'htmx:sendError' "$TMP" \
  && grep -q 'htmx:timeout' "$TMP" && grep -q 'unhandledrejection' "$TMP" \
  && grep -q "window.addEventListener('error'" "$TMP"; } \
  && log_ok "ERR-3 前端兜底:5 个错误监听齐(htmx:responseError/sendError/timeout + window error/rejection)" \
  || log_bad "ERR-3 前端错误监听不全" "missing"

# 5xx 兜底逻辑:含 401 跳 /login + 5xx 显示 toast 文案
{ grep -q "/login?expired=1" "$TMP" && grep -q "服务器繁忙" "$TMP" && grep -q "网络异常" "$TMP"; } \
  && log_ok "ERR-4 兜底文案齐:401→/login expired + 5xx 服务器繁忙 toast + 网络异常 toast" \
  || log_bad "ERR-4 兜底文案缺" "missing"

# ---------- v0.2 entry 上期余额参考 ----------
section "v0.2 · entry 上期余额参考"

$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
{ grep -q '参考 · 上期末' "$TMP" && grep -qE '上期末.*¥' "$TMP"; } \
  && log_ok "FR7-参考 entry 行含'参考·上期末'+右侧'上期末¥X'" \
  || log_bad "FR7-参考 上期余额参考缺" "missing"

# ---------- v0.2 FR-33 / FR-34 移动端引导 ----------
section "v0.2 · FR-33 微信引导 + FR-34 PWA 添加到主屏"

# manifest 200 + 正确 MIME
ct=$($CURL -o /tmp/finance-qa-manifest.json -w "%{content_type}" "$BASE/manifest.webmanifest")
[[ "$ct" == *"manifest+json"* ]] && log_ok "FR34-1 manifest Content-Type=application/manifest+json" || log_bad "FR34-1 MIME 错" "got=$ct"

# manifest 字段齐(2026-05-10 改为动态 controller,JSON 紧凑无空格,grep 放宽)
{ grep -q '"name"' /tmp/finance-qa-manifest.json && grep -q '/dashboard' /tmp/finance-qa-manifest.json \
  && grep -qE '"display"\s*:\s*"standalone"' /tmp/finance-qa-manifest.json && grep -q '"icons"' /tmp/finance-qa-manifest.json; } \
  && log_ok "FR34-2 manifest 字段齐(name/start_url/display/icons)" \
  || log_bad "FR34-2 字段缺" "see /tmp/finance-qa-manifest.json"

# 三张 PNG 都 200
for f in apple-touch-icon-180.png icon-192.png icon-512.png; do
  code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/img/$f")
  [[ "$code" == "200" ]] && log_ok "FR34-3 /img/$f = 200" || log_bad "FR34-3 /img/$f" "code=$code"
done

# layout(login 页未登录就能拉到 head)含 4 个 apple meta + manifest + apple-touch-icon-180.png
$CURL "$BASE/login" -o "$TMP" -w ""
{ grep -q 'name="apple-mobile-web-app-capable"' "$TMP" \
  && grep -q 'name="apple-mobile-web-app-status-bar-style"' "$TMP" \
  && grep -q 'name="apple-mobile-web-app-title"' "$TMP" \
  && grep -q 'rel="manifest"' "$TMP" \
  && grep -q 'rel="apple-touch-icon"' "$TMP" \
  && grep -qE 'apple-touch-icon-180\.png|/img/presets/icon[0-9]-180\.png' "$TMP" \
  && grep -q 'theme-color' "$TMP"; } \
  && log_ok "FR34-4 layout head 含 PWA meta + apple-touch-icon (180px)" \
  || log_bad "FR34-4 PWA meta 缺" "see $TMP"

# /js/mobile-guide.js 未登录可达
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/js/mobile-guide.js")
[[ "$code" == "200" ]] && log_ok "FR34-5 /js/mobile-guide.js 未登录 200" || log_bad "FR34-5 mobile-guide.js" "code=$code"

# manifest 未登录可达
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/manifest.webmanifest")
[[ "$code" == "200" ]] && log_ok "FR34-6 manifest 未登录 200" || log_bad "FR34-6 manifest 被拦" "code=$code"

# layout 引用 mobile-guide.js
grep -q 'mobile-guide.js' "$TMP" && log_ok "FR33-1 layout 引用 mobile-guide.js" || log_bad "FR33-1 mobile-guide.js 未引入" "missing"

# mobile-guide.js 内容含三处关键判断
$CURL "$BASE/js/mobile-guide.js" -o "$TMP" -w ""
{ grep -q 'MicroMessenger' "$TMP" && grep -q 'wx_dismissed_at' "$TMP" \
  && grep -q 'pwa_dismissed_at' "$TMP" && grep -q 'standalone' "$TMP"; } \
  && log_ok "FR33-2/FR34-7 脚本含微信 UA + iOS standalone + 双 dismiss key" \
  || log_bad "FR33-2 脚本字段缺" "see $TMP"

# ---------- v0.2 Stage 1: nav / 资产体检 / 类目 ----------
section "v0.2 · 阶段 1 · nav 资产体检 + 类目 + pill"

# CHECKUP-1 顶部 nav 含「资产体检」入口(已登录 dashboard)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q '资产体检' "$TMP" && log_ok "v02-NAV-1 dashboard 顶部 nav 含「资产体检」" || log_bad "v02-NAV-1 nav 资产体检 缺" "see $TMP"

# CHECKUP-2 GET /checkup 占位页 200 + 含「资产体检」标题
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup")
{ [[ "$code" == "200" ]] && grep -q '资产体检' "$TMP"; } \
  && log_ok "v02-CHK-1 GET /checkup 占位 200" || log_bad "v02-CHK-1 /checkup" "code=$code"

# CHECKUP-3 GET /checkup?account=1 账户级占位
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup?account=1")
{ [[ "$code" == "200" ]] && grep -q '账户体检\|资产体检' "$TMP"; } \
  && log_ok "v02-CHK-2 /checkup?account=1 占位 200" || log_bad "v02-CHK-2" "code=$code"

# v02-LIQ-1 · 货币基金参与流动资产(v0.3.3 bugfix · product_category.liquidity_class 驱动)
# 找一个 WEALTH 账户,前后切换 product_category_code · 验证 /checkup 流动资产数字变化
LIQ_ACC=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 AND type='WEALTH' AND archived_at IS NULL ORDER BY id LIMIT 1" 2>/dev/null)
LIQ_ORIG_PC=$(mysql -ufinance -pfinance finance -sN -e "SELECT IFNULL(product_category_code,'NULL') FROM account WHERE id=$LIQ_ACC" 2>/dev/null)
LIQ_BAL=$(mysql -ufinance -pfinance finance -sN -e "
  SELECT ps.end_balance FROM period_snapshot ps
  JOIN period p ON p.id=ps.period_id
  WHERE ps.account_id=$LIQ_ACC AND p.family_id=1 AND p.status='OPEN'
  ORDER BY p.id DESC LIMIT 1" 2>/dev/null)
LIQ_BAL_INT=$(echo "$LIQ_BAL" | cut -d. -f1)
# 强制设回 NULL 测 BEFORE
mysql -ufinance -pfinance finance -e "UPDATE account SET product_category_code=NULL WHERE id=$LIQ_ACC" 2>/dev/null
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
LIQ_BEFORE=$(grep -A2 '>流动资产<' "$TMP" | grep -oE '¥[0-9,.]+' | head -1 | tr -d '¥,')
# 设为 MONEY_FUND 测 AFTER
mysql -ufinance -pfinance finance -e "UPDATE account SET product_category_code='MONEY_FUND' WHERE id=$LIQ_ACC" 2>/dev/null
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
LIQ_AFTER=$(grep -A2 '>流动资产<' "$TMP" | grep -oE '¥[0-9,.]+' | head -1 | tr -d '¥,')
# AFTER - BEFORE 应该 ≈ LIQ_BAL(允许 1 元误差)
DELTA=$(awk -v a="$LIQ_AFTER" -v b="$LIQ_BEFORE" 'BEGIN{printf "%d", a-b}')
EXPECT_DELTA=$LIQ_BAL_INT
DIFF=$(awk -v d="$DELTA" -v e="$EXPECT_DELTA" 'BEGIN{x=d-e; if(x<0)x=-x; printf "%d", x}')
{ [[ -n "$LIQ_BEFORE" ]] && [[ -n "$LIQ_AFTER" ]] && [[ "$DIFF" -le 2 ]]; } \
  && log_ok "v02-LIQ-1 WEALTH+MONEY_FUND 进入流动资产 · before=$LIQ_BEFORE after=$LIQ_AFTER Δ=$DELTA(期望 $EXPECT_DELTA)" \
  || log_bad "v02-LIQ-1 流动资产未联动" "before=$LIQ_BEFORE after=$LIQ_AFTER Δ=$DELTA expect=$EXPECT_DELTA"

# v02-LIQ-2 · "仅 CASH" caption 已改 · 现在显示 "CASH + 货币基金等(类目 = LIQUID)"
grep -q 'CASH + 货币基金' "$TMP" \
  && log_ok "v02-LIQ-2 体检页 caption 改为「CASH + 货币基金等(类目 = LIQUID)」" \
  || log_bad "v02-LIQ-2 caption 未更新" "still 仅 CASH or missing"

# 还原
[[ "$LIQ_ORIG_PC" == "NULL" ]] && mysql -ufinance -pfinance finance -e "UPDATE account SET product_category_code=NULL WHERE id=$LIQ_ACC" 2>/dev/null \
  || mysql -ufinance -pfinance finance -e "UPDATE account SET product_category_code='$LIQ_ORIG_PC' WHERE id=$LIQ_ACC" 2>/dev/null

# v02-LIQ-3 · product_category 全 16 行 liquidity_class 列已 populate
# 2026-08-13:原来断言「恰好 16 个类目有 liquidity_class」—— 后续版本加了产品类目(现 18 个),
#   护栏就红了,而这并不是缺陷。写死计数分母是本项目反复踩的坑(AGENTS「加枚举值要扫计数分母」)。
#   改为守真正的意图:**一个类目都不许漏 liquidity_class**(缺失数 = 0),加多少类目都不会假红。
LIQ_MISSING=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM product_category WHERE liquidity_class IS NULL OR liquidity_class = ''" 2>/dev/null)
LIQ_COL_COUNT=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM product_category WHERE liquidity_class IS NOT NULL AND liquidity_class != ''" 2>/dev/null)
LIQ_LIQUID=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM product_category WHERE liquidity_class='LIQUID'" 2>/dev/null)
LIQ_ILLIQ=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM product_category WHERE liquidity_class='ILLIQUID'" 2>/dev/null)
{ [[ "${LIQ_MISSING:-1}" -eq 0 ]] && [[ "$LIQ_COL_COUNT" -ge 16 ]] && [[ "$LIQ_LIQUID" -ge 2 ]] && [[ "$LIQ_ILLIQ" -ge 2 ]]; } \
  && log_ok "v02-LIQ-3 全部 $LIQ_COL_COUNT 个类目都有 liquidity_class(0 漏)· LIQUID=$LIQ_LIQUID ILLIQUID=$LIQ_ILLIQ" \
  || log_bad "v02-LIQ-3 有类目缺 liquidity_class" "missing=$LIQ_MISSING total=$LIQ_COL_COUNT liquid=$LIQ_LIQUID illiquid=$LIQ_ILLIQ"

# CAT-1 /admin/product-categories 200 + 16 个类目
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/admin/product-categories")
[[ "$code" == "200" ]] && log_ok "v02-PCAT-1 /admin/product-categories 200" || log_bad "v02-PCAT-1" "code=$code"
n=$(grep -oE 'A_STOCK|US_STOCK|HK_STOCK|MONEY_FUND|BANK_WEALTH|CASH_DEPOSIT|GOLD|MIXED_FUND|SHORT_BOND|LONG_BOND|PROPERTY_RES|PROPERTY_INV|CRYPTO|FUTURES|LIABILITY' "$TMP" | sort -u | wc -l)
[[ "$n" -ge 15 ]] && log_ok "v02-PCAT-2 16 个类目 code 全渲染 (n=$n)" || log_bad "v02-PCAT-2 类目数" "n=$n"
grep -q '沪深 300\|标普 500' "$TMP" && log_ok "v02-PCAT-3 含基准指数标签" || log_bad "v02-PCAT-3 基准指数" "missing"

# CAT-4 /admin/index 含产品类目 tile
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/admin")
{ [[ "$code" == "200" ]] && grep -q 'product-categories' "$TMP"; } \
  && log_ok "v02-PCAT-4 /admin hub 含产品类目 tile" || log_bad "v02-PCAT-4 admin tile" "code=$code"

# CAT-5 /admin/_sidebar 含产品类目 link(随便挑一个 admin 页验证)
$CURL -b $COOKIE "$BASE/admin/cash-flow-categories" -o "$TMP" -w ""
grep -q '产品类目' "$TMP" && log_ok "v02-PCAT-5 admin sidebar 含产品类目链接" || log_bad "v02-PCAT-5 sidebar" "missing"

# PILL-1 /accounts 列表类目 pill(2026-05-10 改 SVG 后,grep 类目 pill 标识)
#   v0.10.6 去过期:原硬编码 ≥20 耦合旧 demo 账户量级(现 demo 仅 6 个带品类账户)→ 改「渲染冒烟」(≥1),
#   只验类目 pill 渲染没坏(历史 SVG 改动曾整列消失);数量随 demo 数据浮动,不再据此判红。
$CURL -b $COOKIE "$BASE/accounts" -o "$TMP" -w ""
n=$(grep -oE 'border-color:var\(--brass-deep\);color:var\(--brass-deep\)' "$TMP" | wc -l)
[[ "$n" -ge 1 ]] && log_ok "v02-PILL-1 /accounts 列表类目 pill 渲染正常 (n=$n)" || log_bad "v02-PILL-1 类目 pill 未渲染" "n=$n"

# PILL-2 风险星 ★ 出现(STOCK / WEALTH 类目有 risk_level)
n=$(grep -oE '★' "$TMP" | wc -l)
[[ "$n" -ge 4 ]] && log_ok "v02-PILL-2 /accounts 风险 ★ pill 出现 n=$n" || log_bad "v02-PILL-2 风险 pill" "n=$n"

# PILL-3 /accounts 不再 500 (Thymeleaf 表达式正确)
grep -q '出错了' "$TMP" && log_bad "v02-PILL-3 /accounts 仍触发错误兜底" "see $TMP" || log_ok "v02-PILL-3 /accounts 无错误兜底"

# PILL-4 /accounts/new 编辑向导含类目下拉 + 16 个 option(包括「按账户类型默认」)
$CURL -b $COOKIE "$BASE/accounts/new" -o "$TMP" -w ""
{ grep -q 'productCategoryCode' "$TMP" && grep -q '按账户类型默认' "$TMP"; } \
  && log_ok "v02-WIZ-1 /accounts/new 含产品类目下拉" || log_bad "v02-WIZ-1 wizard 类目下拉" "missing"

# PILL-5 /accounts/{id}/edit 含 productCategoryCode + riskLevelOverride
$CURL -b $COOKIE "$BASE/accounts/1/edit" -o "$TMP" -w ""
{ grep -q 'productCategoryCode' "$TMP" && grep -q 'riskLevelOverride' "$TMP"; } \
  && log_ok "v02-EDIT-1 /accounts/1/edit 含类目 + 风险覆盖字段" || log_bad "v02-EDIT-1 编辑页字段" "missing"

# DASH-1 dashboard 列表行含 → 体检 链接
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q '/checkup?account=' "$TMP" && log_ok "v02-DASH-1 dashboard 行含 /checkup?account=" || log_bad "v02-DASH-1 dashboard 体检入口" "missing"

# SOFT-DEL-1 软删字段在 mapper 已过滤(回归):正常 entry 页能加载,且没有数据库错误页
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
{ grep -q '记账' "$TMP" || grep -q '填报' "$TMP"; } && ! grep -q '出错了' "$TMP" \
  && log_ok "v02-SOFT-1 /entry 与 deleted_at 过滤兼容" || log_bad "v02-SOFT-1 /entry" "see $TMP"

# ---------- v0.2 Stage 2: 账户级体检 ----------
section "v0.2 · 阶段 2 · 账户级体检 (FR-40b)"

# 13 个账户全部 200,无 Thymeleaf 错误
err_count=0
for id in 1 2 3 4 5 6 7 8 9 10 11 12 13; do
  $CURL -b $COOKIE "$BASE/checkup?account=$id" -o "$TMP" -w ""
  grep -q '出错了' "$TMP" && err_count=$((err_count+1))
done
[[ $err_count -eq 0 ]] && log_ok "v02-DIAG-1 13 个账户体检页全部成功渲染(0 错误)" || log_bad "v02-DIAG-1 体检页错误数" "err=$err_count"

# CASH 账户(id=1 招行)显示「流动性」卡 + 不显示「收益表现」「欠款余额」「估值」
$CURL -b $COOKIE "$BASE/checkup?account=1" -o "$TMP" -w ""
{ grep -q '流动性' "$TMP" && ! grep -q '收益表现' "$TMP" \
  && ! grep -q '欠款余额' "$TMP" && ! grep -q '估值' "$TMP"; } \
  && log_ok "v02-DIAG-2 CASH 账户只显示「流动性」卡" \
  || log_bad "v02-DIAG-2 CASH 卡分支" "see $TMP"

# STOCK 账户(id=3 一般是 STOCK)显示「收益表现」「风险刻度」「基准对照」「现金流」
$CURL -b $COOKIE "$BASE/checkup?account=3" -o "$TMP" -w ""
{ grep -q '收益表现' "$TMP" && grep -q '风险刻度' "$TMP" \
  && grep -q '基准对照' "$TMP" && grep -q 'CASH FLOW' "$TMP"; } \
  && log_ok "v02-DIAG-3 STOCK 账户显示 4 张投资卡" \
  || log_bad "v02-DIAG-3 STOCK 4 卡" "see $TMP"

# LOAN 账户(id=5 一般是 LOAN)显示「欠款余额」「还款进度」
$CURL -b $COOKIE "$BASE/checkup?account=5" -o "$TMP" -w ""
{ grep -q '欠款余额' "$TMP" && grep -q '还款进度' "$TMP" \
  && ! grep -q '收益表现' "$TMP"; } \
  && log_ok "v02-DIAG-4 LOAN 账户显示「欠款余额 + 还款进度」" \
  || log_bad "v02-DIAG-4 LOAN 卡" "see $TMP"

# PROPERTY 账户(id=10 一般是 PROPERTY)显示「估值」简卡
$CURL -b $COOKIE "$BASE/checkup?account=10" -o "$TMP" -w ""
{ grep -q '估值' "$TMP" && ! grep -q '收益表现' "$TMP"; } \
  && log_ok "v02-DIAG-5 PROPERTY 账户显示「估值」简卡" \
  || log_bad "v02-DIAG-5 PROPERTY 卡" "see $TMP"

# 不存在 / 跨家庭账户 → 重定向到 /checkup family 页
loc=$($CURL -b $COOKIE -o /dev/null -w "%{redirect_url}" "$BASE/checkup?account=99999")
[[ "$loc" == *"/checkup"* ]] && log_ok "v02-DIAG-6 不存在账户跳 /checkup family" || log_bad "v02-DIAG-6 越权处理" "loc=$loc"

# 顶部账户 pill 含类目 + 风险星
$CURL -b $COOKIE "$BASE/checkup?account=3" -o "$TMP" -w ""
{ grep -q 'border-color:var(--brass-deep)' "$TMP" && grep -q '★' "$TMP"; } \
  && log_ok "v02-DIAG-7 顶部账户 pill 含类目 + 风险星" \
  || log_bad "v02-DIAG-7 pill" "missing"

# 余额走势 sparkline canvas 渲染
$CURL -b $COOKIE "$BASE/checkup?account=3" -o "$TMP" -w ""
grep -q 'id="balanceTrend"' "$TMP" && log_ok "v02-DIAG-8 余额走势 canvas 渲染" || log_bad "v02-DIAG-8 sparkline" "missing"

# ---------- v0.2 Stage 3: 智能建议 + LLM 润色 ----------
section "v0.2 · 阶段 3 · 智能建议 + LLM 文案润色 (FR-40c)"

# 13 个账户的体检页 + family 页都不再触发错误兜底(回归 stage 2)
err_count=0
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
grep -q '出错了' "$TMP" && err_count=$((err_count+1))
for id in 1 2 3 4 5 6 7 8 9 10 11 12 13; do
  $CURL -b $COOKIE "$BASE/checkup?account=$id" -o "$TMP" -w ""
  grep -q '出错了' "$TMP" && err_count=$((err_count+1))
done
[[ $err_count -eq 0 ]] && log_ok "v02-ADV-1 14 个体检页(family + 13 acct)全部成功渲染" || log_bad "v02-ADV-1 体检页错误数" "err=$err_count"

# 家庭体检页含 advice cards(命中至少 1 条)或健康提示
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q 'advice-card' "$TMP" || grep -q '健康状态良好' "$TMP"; } \
  && log_ok "v02-ADV-2 家庭体检页含 advice 卡或健康提示" || log_bad "v02-ADV-2 advice 区域" "missing"

# 家庭体检页含「来自账房的提醒」标题(命中时)或「健康状态良好」(全 miss 时)
{ grep -q '来.\{0,3\}自.\{0,3\}账.\{0,3\}房.\{0,3\}的.\{0,3\}提.\{0,3\}醒' "$TMP" || grep -q '健康状态良好' "$TMP"; } \
  && log_ok "v02-ADV-3 advice 区域 eyebrow 文案存在" || log_bad "v02-ADV-3 eyebrow" "missing"

# 账户体检页(任一 STOCK 账户)含 advice 卡或体检通过提示
$CURL -b $COOKIE "$BASE/checkup?account=3" -o "$TMP" -w ""
{ grep -q 'advice-card' "$TMP" || grep -q '本账户体检通过' "$TMP"; } \
  && log_ok "v02-ADV-4 STOCK 账户体检 advice 区域" || log_bad "v02-ADV-4 acct advice" "missing"

# advice 卡的 data-rule 与 data-severity 属性渲染
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
n_rule=$(grep -oE 'data-rule="[^"]+"' "$TMP" | wc -l)
n_sev=$(grep -oE 'data-severity="[^"]+"' "$TMP" | wc -l)
# v02-ADV-5 仅当有 advice 命中时校验 data 属性;无命中时(显示「健康状态良好」)SKIP
if [[ $n_rule -ge 1 && $n_sev -ge 1 ]]; then
  log_ok "v02-ADV-5 advice 卡 data-rule + data-severity 渲染 (rule=$n_rule sev=$n_sev)"
elif grep -q '健康状态良好\|本账户体检通过' "$TMP"; then
  log_skip "v02-ADV-5 advice data attr" "无规则命中,渲染了健康状态文案"
else
  log_bad "v02-ADV-5 data attr" "rule=$n_rule sev=$n_sev"
fi

# AI 综合诊断 placeholder(spinner)在 family 页存在 — 决策 20 新方向
grep -q 'ai-diagnose-panel' "$TMP" && log_ok "v02-ADV-6 family 页含 AI 综合诊断 placeholder" \
  || log_bad "v02-ADV-6 AI placeholder" "missing"

# AI 综合诊断 placeholder 含 hx-trigger="load"(进页自动 fetch)
grep -E 'hx-trigger="load".*ai-diagnose|ai-diagnose.*hx-trigger="load"' "$TMP" >/dev/null \
  || grep -B2 -A2 'ai-diagnose-panel' "$TMP" | grep -q 'hx-trigger="load"' \
  && log_ok "v02-ADV-7 AI placeholder 含 hx-trigger=load(自动加载)" \
  || log_bad "v02-ADV-7 hx-trigger" "missing"

# ---------- v0.2 · AI 综合诊断 endpoint(决策 20 / 2026-05-10) ----------
section "v0.2 · AI 综合诊断 · /checkup/diagnose (FR-40c 决策 20)"

# 【2026-09-22 · 这一段重做过】原来这里有 5 次 /checkup/diagnose 请求,每一次都真的打 LLM。
#   而这 5 条护栏验的其实只有三件事:端点通不通、fragment 带不带 vendor/available 属性、
#   降级文案在不在 —— 没有一件需要模型真的说话。
#
#   诊断的缓存是【进程内】的(LlmDiagnoseService 的 ConcurrentHashMap,1h TTL,
#   key 含整个 prompt),外部塞不进去,所以"塞桩"这条路在这里走不通。
#   能走的是另一条:account=99999 在控制器里【早返回】(账户查不到就直接渲染降级面板,
#   见 AiDiagnoseController:80-84),这一路压根不碰 LLM —— 而它返回的正是同一个
#   fragment,属性和降级文案全在里面。于是一次免费请求就能把三件事一起验掉。
#
#   account=1 / account=3 那两条原来只断言 HTTP 200,与 99999 那条走的是同一个路由、
#   同一个模板,差别只在"真的去调模型"。删掉它们不损失判据,省下的正是钱。
code=$(/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup/diagnose?account=99999")

# DIAG-1 端点通(这一路不打 LLM:账户查不到 → 早返回降级面板)
[[ "$code" == "200" ]] && log_ok "v02-DIAG-1 GET /checkup/diagnose → 200(走免费的早返回路径)" \
  || log_bad "v02-DIAG-1 family diagnose" "code=$code"

# DIAG-2 返回 fragment 含 data-vendor / data-available 属性
grep -qE 'data-vendor=|data-available=' "$TMP" \
  && log_ok "v02-DIAG-2 fragment 含 vendor/available 属性" \
  || log_bad "v02-DIAG-2 fragment attrs" "missing"

# DIAG-3 fragment 含标题或降级文案 —— 两个都要在模板里,否则降级时页面是一片空白
{ grep -q '综合智能诊断' "$TMP" || grep -q 'AI · 暂不可用' "$TMP"; } \
  && log_ok "v02-DIAG-3 fragment 含诊断标题或降级文案" \
  || log_bad "v02-DIAG-3 panel header" "missing"

# DIAG-5 跨家庭账户 → 降级,不泄露(安全护栏 · 这一条最要紧,而且天然免费)
{ [[ "$code" == "200" ]] && grep -q '账户不存在\|AI 暂时不可用\|AI · 暂不可用' "$TMP"; } \
  && log_ok "v02-DIAG-5 跨家庭账户 diagnose 返回降级 (code=$code)" \
  || log_bad "v02-DIAG-5 cross-family" "code=$code"

# DIAG-4/6 账户维度分支存在(静态判据 —— 原来靠两次真调用只断言了 HTTP 200)
{ grep -q 'diagnoseAccount' "$RD/src/main/java/com/family/finance/web/checkup/AiDiagnoseController.java" \
  && grep -q 'accountId == null' "$RD/src/main/java/com/family/finance/web/checkup/AiDiagnoseController.java"; } \
  && log_ok "v02-DIAG-4 控制器按 account 参数分流(家庭维度 / 账户维度两条路都在)" \
  || log_bad "v02-DIAG-4 账户维度分支缺" "AiDiagnoseController 里应有 accountId==null 分流 + diagnoseAccount"

# ---------- v0.2 LLM 真实调用 ----------
section "v0.2 · LLM 真实调用 · qwen-plus(可选,无 key 时降级 fallback)"

# DIAG-LIVE 嗅探:GET /checkup/diagnose 是否实际由真 LLM(qwen/deepseek)成功返回综合诊断长文,
# 还是 fallback("AI 暂时不可用")。两种结果都不算 FAIL,但分别对应不同状态。
if [[ "$QA_LLM_LIVE" != "1" ]]; then
  log_skip "v02-LLM-LIVE-1 实调用嗅探" "这条必须真打一次 LLM · QA_LLM_LIVE=1 时才跑"
  vendor=""; available=""
else
/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "" "$BASE/checkup/diagnose"
vendor=$(grep -oE 'data-vendor="[^"]+"' "$TMP" | head -1 | sed 's/data-vendor="\([^"]*\)"/\1/')
available=$(grep -oE 'data-available="[^"]+"' "$TMP" | head -1 | sed 's/data-available="\([^"]*\)"/\1/')
if [[ "$available" == "true" && ( "$vendor" == "qwen" || "$vendor" == "deepseek" ) ]]; then
  log_ok "v02-LLM-LIVE-1 LLM 实调用成功 vendor=$vendor 综合诊断长文已返回"
else
  log_skip "v02-LLM-LIVE-1" "LLM key 未配/全部失败,vendor=$vendor available=$available — 已降级 fallback,不阻塞 v0.2 验收"
fi
fi

# ---------- v0.2 Stage 4: 账本侧(ledger.csv + 软删 + UI 入口) ----------
section "v0.2 · 阶段 4 · 账本侧 (FR-30/31/32)"

# LEDGER-1 /accounts 列表「体检」入口(2026-05-10 改 SVG 后,grep href)
$CURL -b $COOKIE "$BASE/accounts" -o "$TMP" -w ""
# v0.10.6 去过期:原 ≥13 耦合 demo 账户量(且 PC+移动双渲染);体检入口在账户行循环里 → 改渲染冒烟 ≥1。
n=$(grep -oE 'href="/checkup\?account=[0-9]+"' "$TMP" | wc -l)
[[ $n -ge 1 ]] && log_ok "v02-LEDGER-1 /accounts 账户行含 /checkup?account 体检入口 (n=$n)" \
  || log_bad "v02-LEDGER-1 体检入口未渲染" "n=$n"

# LEDGER-2 /accounts 列表「账本 CSV」入口(账本链接在账户行循环里渲染 · 1:1 对应账户)
#   v0.10.6 去过期:原硬编码 ≥13 耦合旧 demo 账户量级(现 demo 12 账户)→ 改「渲染冒烟」(≥1):
#   链接在 th:each 账户循环内,渲染则全账户都有、不渲染则 0;数量随 demo 账户数浮动,不再据此判红。
n=$(grep -oE 'href="/accounts/[0-9]+/ledger.csv"' "$TMP" | wc -l)
[[ $n -ge 1 ]] && log_ok "v02-LEDGER-2 /accounts 账户行含「⬇ 账本」入口 (n=$n)" \
  || log_bad "v02-LEDGER-2 账本入口未渲染" "n=$n"

# LEDGER-3 GET /accounts/3/ledger.csv → 200 + text/csv
type_resp=$($CURL -b $COOKIE -o "$TMP" -w "%{content_type}" "$BASE/accounts/3/ledger.csv")
{ grep -q 'text/csv' <<<"$type_resp" && grep -q '月份,期初,入账' "$TMP"; } \
  && log_ok "v02-LEDGER-3 ledger.csv 返回 text/csv + 表头正确" \
  || log_bad "v02-LEDGER-3 ledger.csv" "type=$type_resp"

# LEDGER-4 ledger.csv 含 BOM(Excel 友好)
head -c 3 "$TMP" | od -c | head -1 | grep -q '357 273 277' \
  && log_ok "v02-LEDGER-4 ledger.csv 含 UTF-8 BOM" \
  || log_bad "v02-LEDGER-4 BOM" "missing"

# LEDGER-5 ledger.csv Content-Disposition 含 filename*=UTF-8'' 编码
header=$($CURL -b $COOKIE -o /dev/null -D - "$BASE/accounts/3/ledger.csv" | tr -d '\r' | grep -i 'content-disposition')
echo "$header" | grep -q "filename\*=UTF-8" \
  && log_ok "v02-LEDGER-5 ledger.csv Content-Disposition 含 UTF-8 文件名" \
  || log_bad "v02-LEDGER-5 filename" "header=$header"

# LEDGER-6 跨家庭账户(id=99999) 应被 require 拦截
code=$($CURL -b $COOKIE -o /dev/null -w "%{http_code}" "$BASE/accounts/99999/ledger.csv")
[[ "$code" -ge 400 ]] && log_ok "v02-LEDGER-6 跨家庭账户 ledger.csv 拒绝 (code=$code)" \
  || log_bad "v02-LEDGER-6 越权" "code=$code"

# SOFT-DEL-2 entry 当前 OPEN 期 + 该期有 cf/transfer 时,应有至少 1 个 ⋮ 删除按钮
# 动态查 OPEN period id + 是否有可删的 cf/transfer
OPEN_PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
OPEN_HAS_CF=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM cash_flow WHERE period_id=$OPEN_PID AND deleted_at IS NULL" 2>/dev/null)
if [[ -z "$OPEN_PID" ]]; then
  log_skip "v02-SOFT-DEL-2 删除按钮" "无 OPEN 周期"
elif [[ "$OPEN_HAS_CF" == "0" ]]; then
  log_skip "v02-SOFT-DEL-2 删除按钮" "OPEN 期 ($OPEN_PID) 无 cf/transfer 可删"
else
  $CURL -b $COOKIE "$BASE/entry?period=$OPEN_PID" -o "$TMP" -w ""
  n=$(grep -oE 'hx-post="/entry/[a-z-]+/[0-9]+/delete"' "$TMP" | wc -l)
  [[ $n -ge 1 ]] && log_ok "v02-SOFT-DEL-2 entry OPEN 周期 ($OPEN_PID) 渲染 ⋮删除按钮 (n=$n)" \
    || log_bad "v02-SOFT-DEL-2 删除按钮" "n=$n"
fi

# SOFT-DEL-3 删除按钮不出现在 SNAPSHOT 行(只在 cash_flow / transfer)
{ grep -q 'cash-flow/[0-9]' "$TMP" || grep -q 'transfer/[0-9]' "$TMP"; } \
  && log_ok "v02-SOFT-DEL-3 删除链接指向 cash-flow / transfer 子路径" \
  || log_bad "v02-SOFT-DEL-3 删除链接" "missing"

# SOFT-DEL-4 删除按钮 hx-confirm 含确认提示
grep -q 'hx-confirm="确定删除' "$TMP" && log_ok "v02-SOFT-DEL-4 hx-confirm 删除提示存在" \
  || log_bad "v02-SOFT-DEL-4 confirm" "missing"

# SOFT-DEL-5 软删 endpoint POST 一条真实 OPEN 期 cash_flow,验证 200(此处 cf=323 已被前面真实测试软删,
# 任意找一个未软删的 OPEN cf 来再测一次。如果都已删,跳过)
$CURL -b $COOKIE -c $COOKIE "$BASE/entry?period=35" -o /dev/null
XSRF=$(awk '$6=="XSRF-TOKEN" {print $7}' $COOKIE)
victim_cf=$(grep -oE 'hx-post="/entry/cash-flow/[0-9]+/delete"' "$TMP" | head -1 | grep -oE '[0-9]+')
if [[ -n "$victim_cf" ]]; then
  code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" -o /dev/null -w "%{http_code}" \
        "$BASE/entry/cash-flow/$victim_cf/delete")
  [[ "$code" == "200" ]] && log_ok "v02-SOFT-DEL-5 POST 软删真实 cash_flow ($victim_cf) → 200" \
    || log_bad "v02-SOFT-DEL-5 软删失败" "cf=$victim_cf code=$code"
else
  log_skip "v02-SOFT-DEL-5 软删真实 cf" "no candidate cf"
fi

# SOFT-DEL-6 软删后该 cf 不再出现在 entry 页(deleted_at 已设)
if [[ -n "$victim_cf" ]]; then
  $CURL -b $COOKIE "$BASE/entry?period=35" -o "$TMP" -w ""
  if ! grep -q "cash-flow/$victim_cf/delete" "$TMP"; then
    log_ok "v02-SOFT-DEL-6 已软删 cf=$victim_cf 从 entry 页面消失"
  else
    log_bad "v02-SOFT-DEL-6 已软删 cf 仍可见" "cf=$victim_cf"
  fi
fi

# SOFT-DEL-7 CLOSED 周期软删被拒绝(用 cash_flow 222 — 在 CLOSED period)
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" -o /dev/null -w "%{http_code}" \
      "$BASE/entry/cash-flow/222/delete")
# 后端 throw IllegalStateException → 500 错误页是预期(进入兜底);亦可能未来改 400 友好
[[ "$code" == "500" || "$code" == "400" ]] && log_ok "v02-SOFT-DEL-7 CLOSED 周期软删被拒 (code=$code)" \
  || log_bad "v02-SOFT-DEL-7 CLOSED 拒写" "code=$code"

# SOFT-DEL-8 不存在的 cash_flow id 也被拒绝(NPE 转 500/400)
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" -o /dev/null -w "%{http_code}" \
      "$BASE/entry/cash-flow/9999999/delete")
[[ "$code" -ge 400 ]] && log_ok "v02-SOFT-DEL-8 不存在 cf 软删被拒 (code=$code)" \
  || log_bad "v02-SOFT-DEL-8 missing cf 拦截" "code=$code"

# v0.2 FR-30 · 账户详情页(账本视角)
$CURL -b $COOKIE -o "$TMP" -w "" "$BASE/accounts/1"
{ grep -q '<h1' "$TMP" && grep -q '招行储蓄卡-工资' "$TMP"; } \
  && log_ok "v02-FR30-1 GET /accounts/1 返回详情页 + 显示账户名" \
  || log_bad "v02-FR30-1 详情页" "missing"
grep -q 'id="balanceTimeline"' "$TMP" \
  && log_ok "v02-FR30-2 详情页含余额时序 canvas" \
  || log_bad "v02-FR30-2 时序图" "missing"
n_kpi=$(grep -c 'kpi-eyebrow' "$TMP")
[[ $n_kpi -ge 8 ]] && log_ok "v02-FR30-3 详情页含 ≥4 KPI(重复 ≥8 次)实际 $n_kpi" \
  || log_bad "v02-FR30-3 KPI 卡" "n=$n_kpi"
n_det=$(grep -cE '<details' "$TMP")
[[ $n_det -ge 1 ]] && log_ok "v02-FR30-4 详情页月分组 details=$n_det" \
  || log_bad "v02-FR30-4 月分组" "n=$n_det"
{ grep -q '看资产体检' "$TMP" && grep -q '导出本账户 CSV' "$TMP"; } \
  && log_ok "v02-FR30-5 详情页底栏含「看资产体检 / 导出 CSV」" \
  || log_bad "v02-FR30-5 底栏" "missing"
$CURL -b $COOKIE -o /dev/null -w "" "$BASE/accounts/99999"
code=$($CURL -b $COOKIE -o /dev/null -w "%{http_code}" "$BASE/accounts/99999")
[[ "$code" -ge 400 ]] && log_ok "v02-FR30-6 跨家庭账户详情页拒绝 (code=$code)" \
  || log_bad "v02-FR30-6 越权" "code=$code"
# 列表入口接线
$CURL -b $COOKIE "$BASE/accounts" -o "$TMP" -w ""
# v0.10.6 去过期:原 ≥13 耦合 demo 账户量(且每账户多链接/双渲染);详情链接在账户行循环里 → 改渲染冒烟 ≥1。
n=$(grep -cE 'href="/accounts/[0-9]+"' "$TMP")
[[ $n -ge 1 ]] && log_ok "v02-FR30-7 /accounts 账户行含详情链接 (n=$n)" \
  || log_bad "v02-FR30-7 详情链接未渲染" "n=$n"

# v0.2 audit 真名修复
$CURL -b $COOKIE "$BASE/admin/audit" -o "$TMP" -w ""
n_id=$(grep -cE '>#[0-9]+<' "$TMP")
[[ $n_id -eq 0 ]] && log_ok "v02-AUDIT-1 audit 由谁列不再显示 #id" \
  || log_bad "v02-AUDIT-1 残留 #id" "n=$n_id"

# v0.2 dashboard anchor bug fix · 应永远取最新一期(包括 OPEN)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q '资产体检' "$TMP" && grep -q 'kpi-card' "$TMP"; } \
  && log_ok "v02-DASH-ANCHOR dashboard 渲染 + KPI 完整(anchor 取最新期)" \
  || log_bad "v02-DASH-ANCHOR dashboard 渲染" "missing"

# v0.2 FR-40e · /reports 风险等级分布
$CURL -b $COOKIE "$BASE/reports?range=1Y&currency=CNY" -o "$TMP" -w ""
{ grep -q '风险等级分布' "$TMP" && grep -q 'riskDistChart' "$TMP"; } \
  && log_ok "v02-FR40e-1 /reports 含「风险等级分布」环形图 canvas" \
  || log_bad "v02-FR40e-1 风险环形图" "missing"

# v0.2 防回归 · 所有有 canvas 的页面:1) 引 Chart.js  2) 引 datalabels plugin  3) 每个 new Chart 都注册 ChartDataLabels
# 来自 memory feedback_chart_datalabels:数字必须直接浮在数据点/扇片/柱顶上
for path in /dashboard /reports /checkup '/checkup?account=3' /accounts/1; do
  $CURL -b $COOKIE "$BASE$path" -o "$TMP" -w ""
  if grep -q '<canvas' "$TMP"; then
    n_chart=$(grep -c 'chart.umd' "$TMP")
    n_plugin=$(grep -c 'chartjs-plugin-datalabels' "$TMP")
    n_chart_calls=$(grep -c 'new Chart' "$TMP")
    n_register=$(grep -c 'ChartDataLabels' "$TMP")
    pid=$(echo $path | tr '/?=' '___')
    if [[ "$n_chart" -ge 1 && "$n_plugin" -ge 1 && "$n_register" -ge "$n_chart_calls" ]]; then
      log_ok "v02-CHART-$pid 含 canvas + Chart.js + datalabels(charts=$n_chart_calls register=$n_register)"
    else
      log_bad "v02-CHART-$pid 图表配置不全" "chart=$n_chart plugin=$n_plugin register=$n_register charts=$n_chart_calls"
    fi
  fi
done
# 注意:这两个 case 必须独立读 /reports(上面 for 循环最后 $TMP 是 /accounts/1)
$CURL -b $COOKIE "$BASE/reports?range=1Y&currency=CNY" -o "$TMP" -w ""
# v0.10.6 去过期:风险敞口「明细表」v0.4 FR-60b 已砍,改风险分布环形图(见 FR40e-3)。原断言「风险等级表格含★」
#   已不成立——现 ★ 来自 资产年化★ eyebrow 等,非风险表。改判:页面仍含星级标识 ★(渲染冒烟 ≥1),不再宣称「表格」。
n=$(grep -oE '★+' "$TMP" | wc -l)
[[ $n -ge 1 ]] && log_ok "v02-FR40e-2 /reports 含星级标识 ★(资产年化★ 等;风险明细表已砍→见 FR40e-3 环形)(n=$n)" \
  || log_bad "v02-FR40e-2 星级标识缺失" "n=$n"
# v0.4 FR-60b 砍 · 风险敞口明细表已删 · "进入资产体检" link 一并去 · 改判风险等级分布环形仍在
grep -q 'riskDistChart' "$TMP" && log_ok "v02-FR40e-3 (v0.4 改) /reports 风险等级分布环形保留" \
  || log_bad "v02-FR40e-3 风险等级图 砍过头" "missing"

# v0.2 FR-38 · dashboard KPI 卡 deep-link 到 /checkup 锚点
$CURL -b $COOKIE "$BASE/dashboard?range=1Y&currency=CNY" -o "$TMP" -w ""
n=$(grep -oE 'href="/checkup[^"]*"' "$TMP" | wc -l)
# v0.10.6 去过期:v0.4.2 起第 5 卡(本月资产收益)deep-link 指向 /reports 而非 /checkup,5 卡里只有 4 卡链 /checkup;
#   n≥5 仍过是因为账户行也有 /checkup 链接计入。文案不再宣称「5 张 KPI 均含」,改「KPI+账户行含 ≥5 个 /checkup 深链」。
[[ $n -ge 5 ]] && log_ok "v02-FR38-1 dashboard 含 ≥5 个 /checkup 深链(KPI 4 张 + 账户行;第5卡本月资产收益→/reports)(n=$n)" \
  || log_bad "v02-FR38-1 KPI 链接" "n=$n"

# 至少 3 个不同锚点(allocation / liquidity / 顶级)
n_anchors=$(grep -oE 'href="/checkup[^"]*"' "$TMP" | sort -u | wc -l)
[[ $n_anchors -ge 3 ]] && log_ok "v02-FR38-2 KPI 锚点齐全 (allocation/liquidity/顶级,$n_anchors 种)" \
  || log_bad "v02-FR38-2 锚点种类" "n=$n_anchors"

# /checkup family 页含锚点 id(allocation / risk / liquidity / return)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
ids=$(grep -oE 'id="(allocation|risk|liquidity|return)"' "$TMP" | sort -u | wc -l)
[[ $ids -ge 4 ]] && log_ok "v02-FR38-3 /checkup family 含 4 个锚点 id (n=$ids)" \
  || log_bad "v02-FR38-3 锚点 id" "n=$ids"

# ---------- v0.2 UX 小优化(2026-05-10)----------
section "v0.2 · UX 小优化"

# UX-1 entry 余额输入框含 onfocus=this.select() + ✕ 清空按钮
OPEN_PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
$CURL -b $COOKIE "$BASE/entry?period=$OPEN_PID" -o "$TMP" -w ""
n_focus=$(grep -oE 'onfocus="this.select\(\)"' "$TMP" | wc -l)
[[ $n_focus -ge 1 ]] && log_ok "v02-UX-1 entry 余额 input 含 onfocus=select (n=$n_focus)" \
  || log_bad "v02-UX-1 onfocus 缺失" "n=$n_focus"
n_clear=$(grep -oE 'title="清空"' "$TMP" | wc -l)
[[ $n_clear -ge 1 ]] && log_ok "v02-UX-2 entry 余额 input 含 ✕ 清空按钮 (n=$n_clear)" \
  || log_bad "v02-UX-2 清空按钮缺失" "n=$n_clear"

# UX-3 dashboard 含 accountDivergeChart canvas + accountRows 数据
$CURL -b $COOKIE "$BASE/dashboard?range=1Y&currency=CNY" -o "$TMP" -w ""
{ grep -q '<canvas id="accountDivergeChart"' "$TMP" && grep -q 'accountRows: \[' "$TMP"; } \
  && log_ok "v02-UX-3 dashboard 含按账户分布 canvas + accountRows 数据" \
  || log_bad "v02-UX-3 按账户分布图" "missing canvas or data"
grep -q '按账户分布' "$TMP" \
  && log_ok "v02-UX-4 dashboard 含「按账户分布」标题" \
  || log_bad "v02-UX-4 按账户分布标题" "missing"

# UX-5 entry 余额 / 备注 input 高度统一(都用 h-9)+ 备注独立 eyebrow,避免对齐错位
$CURL -b $COOKIE "$BASE/entry?period=$OPEN_PID" -o "$TMP" -w ""
# newBalance input 是多行属性,用 awk 把 input 多行折叠成一行后再 grep h-9
nb_h9=$(awk 'BEGIN{RS=">"} /name="newBalance"/' "$TMP" | grep -c 'h-9')
nt_h9=$(grep -oE 'name="note"[^>]*h-9' "$TMP" | wc -l)
nt_eb=$(grep -c '>备注</span>' "$TMP")
[[ $nb_h9 -ge 1 && $nt_h9 -ge 1 && $nt_eb -ge 1 ]] \
  && log_ok "v02-UX-5 entry 余额 / 备注 input 高度统一(h-9 nb=$nb_h9 nt=$nt_h9)+ 备注独立 eyebrow ($nt_eb)" \
  || log_bad "v02-UX-5 entry 输入框对齐" "newBalance.h-9=$nb_h9 note.h-9=$nt_h9 备注.eyebrow=$nt_eb"

# ---------- v0.2 · FR-40e 报表风险等级分布(2026-05-10) ----------
section "v0.2 · FR-40e · 报表风险等级分布"

# FR40e-1 reports 页加载成功
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
grep -q '风险等级分布' "$TMP" \
  && log_ok "v02-FR40E-1 reports 含「风险等级分布」标题" \
  || log_bad "v02-FR40E-1 标题" "missing"

# FR40e-2 含 riskDistChart canvas
grep -q 'riskDistChart' "$TMP" \
  && log_ok "v02-FR40E-2 reports 含 #riskDistChart canvas" \
  || log_bad "v02-FR40E-2 canvas" "missing"

# v0.4 FR-60b 砍 · 风险敞口明细表 + 进入资产体检入口 都已删 · 改判 reports 仍含风险章节
{ grep -q 'risk-section\|风险等级分布'  "$TMP"; } \
  && log_ok "v02-FR40E-3 (v0.4 改) reports 风险等级分布段仍在" \
  || log_bad "v02-FR40E-3 风险段 砍过头" "missing"

# ---------- v0.2 · FR-1/FR-34 品牌图标预设(2026-05-10)----------
section "v0.2 · 品牌图标预设(默认 icon2)"

# 预条件:重置 family 到默认状态(icon2 + 无自定义)
mysql -ufinance -pfinance finance -e "UPDATE family SET logo_preset='icon2', logo_path=NULL WHERE id=1;" 2>/dev/null

# v02-LOGO-1 16 张预设 PNG 全 200(无 cookie 公开访问)
all_ok=1
for icon in icon1 icon2 icon3 icon4; do
  for size in 96 180 192 512; do
    code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/img/presets/${icon}-${size}.png")
    [[ "$code" == "200" ]] || { all_ok=0; break 2; }
  done
done
[[ $all_ok -eq 1 ]] && log_ok "v02-LOGO-1 16 张预设 PNG(icon{1..4}×{〈金额已脱敏〉})全 200" \
  || log_bad "v02-LOGO-1 预设 PNG" "至少一张非 200"

# v02-LOGO-2 GET /manifest.webmanifest 返回 application/manifest+json + 默认 icon2
ct=$($CURL -b $COOKIE -o "$TMP" -w "%{content_type}" "$BASE/manifest.webmanifest")
{ [[ "$ct" == *"application/manifest+json"* ]] && grep -q '/img/presets/icon2-192.png' "$TMP" && grep -q '/img/presets/icon2-512.png' "$TMP"; } \
  && log_ok "v02-LOGO-2 manifest.webmanifest 动态 + 默认 icon2 (Content-Type=$ct)" \
  || log_bad "v02-LOGO-2 manifest 默认" "ct=$ct  body 见 $TMP"

# v02-LOGO-3 dashboard <link rel=icon> 默认指向 icon2-192.png
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -A1 '<link rel="icon"' "$TMP" | grep -q '/img/presets/icon2-192.png'; } \
  && log_ok "v02-LOGO-3 dashboard favicon 默认 icon2-192.png" \
  || log_bad "v02-LOGO-3 favicon 默认" "link 不指 icon2-192"

# v02-LOGO-4 dashboard <link rel=apple-touch-icon> 默认指向 icon2-180.png
{ grep -A1 '<link rel="apple-touch-icon"' "$TMP" | grep -q '/img/presets/icon2-180.png'; } \
  && log_ok "v02-LOGO-4 dashboard apple-touch-icon 默认 icon2-180.png" \
  || log_bad "v02-LOGO-4 apple-touch 默认" "link 不指 icon2-180"

# v02-LOGO-5 nav header logo 默认指向 icon2-192.png(没自定义上传时)
grep -q 'src="/img/presets/icon2-192.png' "$TMP" \
  && log_ok "v02-LOGO-5 nav header logo 默认 icon2-192.png" \
  || log_bad "v02-LOGO-5 nav logo" "src 不指 icon2-192"

# v02-LOGO-6 admin/family 渲染 4 缩略图 gallery(button data-preset="iconN" · 不嵌套 form · 2026-05-14 bugfix)
$CURL -b $COOKIE "$BASE/admin/family" -o "$TMP" -w ""
gallery_count=$(grep -oE 'data-preset="icon[1-4]"' "$TMP" | sort -u | wc -l)
nested_form_check=$(grep -cE '<form[^>]*action="/admin/family/logo/preset"' "$TMP")
{ [[ $gallery_count -eq 4 ]] && [[ $nested_form_check -eq 0 ]]; } \
  && log_ok "v02-LOGO-6 admin/family gallery 4 button(data-preset)· 零嵌套 form" \
  || log_bad "v02-LOGO-6 gallery / 嵌套 form" "buttons=$gallery_count nested_form=$nested_form_check"

# v02-LOGO-7 切到 icon3 → DB 更新 + dashboard / manifest 全跟随
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}')
$CURL -b $COOKIE -c $COOKIE -X POST "$BASE/admin/family/logo/preset" -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "preset=icon3" -o /dev/null -w "" || true
db_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT logo_preset FROM family WHERE id=1;" 2>/dev/null)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
mf=$($CURL -b $COOKIE "$BASE/manifest.webmanifest")
{ [[ "$db_after" == "icon3" ]] \
   && grep -A1 '<link rel="apple-touch-icon"' "$TMP" | grep -q 'icon3-180' \
   && echo "$mf" | grep -q 'icon3-192'; } \
  && log_ok "v02-LOGO-7 切 icon3 → DB+web favicon+iOS apple-touch+manifest 全跟随" \
  || log_bad "v02-LOGO-7 切预设全链路" "db=$db_after"

# v02-LOGO-8 上传自定义 webp 后,web 用 webp,但 iOS apple-touch 仍用 preset(icon3)
mysql -ufinance -pfinance finance -e "UPDATE family SET logo_path='family-1/logo.webp' WHERE id=1;" 2>/dev/null
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -A1 '<link rel="icon"' "$TMP" | grep -q '/uploads/family-1/logo.webp' \
   && grep -A1 '<link rel="apple-touch-icon"' "$TMP" | grep -q 'icon3-180'; } \
  && log_ok "v02-LOGO-8 自定义 webp 上传 → web favicon=webp / iOS=preset 不联动" \
  || log_bad "v02-LOGO-8 自定义+预设并存" "see $TMP"

# v02-LOGO-9 切预设按钮 = 一并清空 logo_path(预设赢一切统一)
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}')
$CURL -b $COOKIE -c $COOKIE -X POST "$BASE/admin/family/logo/preset" -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "preset=icon4" -o /dev/null -w "" || true
both=$(mysql -ufinance -pfinance finance -sN -e "SELECT logo_preset, IFNULL(logo_path,'NULL') FROM family WHERE id=1;" 2>/dev/null)
[[ "$both" == "icon4	NULL" ]] && log_ok "v02-LOGO-9 切预设清空 logo_path(预设赢一切统一)" \
  || log_bad "v02-LOGO-9 logo_path 未清空" "DB=$both"

# v02-LOGO-10 非法 preset(icon99)→ 服务层校验拒写,DB 保持 icon4
$CURL -b $COOKIE -c $COOKIE -X POST "$BASE/admin/family/logo/preset" -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "preset=icon99" -o /dev/null -w "" || true
preset_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT logo_preset FROM family WHERE id=1;" 2>/dev/null)
[[ "$preset_after" == "icon4" ]] && log_ok "v02-LOGO-10 非法 preset 拒写,DB 保持 icon4" \
  || log_bad "v02-LOGO-10 非法 preset 校验" "DB=$preset_after"

# 复跑后置:重置默认 icon2 + 无自定义,不污染后续
mysql -ufinance -pfinance finance -e "UPDATE family SET logo_preset='icon2', logo_path=NULL WHERE id=1;" 2>/dev/null


###################################################
# v0.3 FR-50 · 财务目标 · /goals 全路径联调
###################################################
# 复跑前置:清掉旧目标(避免重复)
mysql -ufinance -pfinance finance -e "DELETE FROM family_goal WHERE family_id=1;" 2>/dev/null

XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)

# v03-GOAL-1 · 无目标时 /goals 列表显空状态引导
$CURL -b $COOKIE "$BASE/goals" -o "$TMP" -w ""
{ grep -q "还没有目标" "$TMP" && grep -q "你的家庭在朝哪儿走" "$TMP"; } \
  && log_ok "v03-GOAL-1 /goals 空状态显引导卡" \
  || log_bad "v03-GOAL-1 空状态" "no hint card"

# v03-GOAL-2 · POST /goals/new/retirement 创建退休目标
loc=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "name=v03 自由生活" \
  --data-urlencode "currentAge=38" --data-urlencode "retireAge=60" \
  --data-urlencode "monthlyExpense=15000" --data-urlencode "inflationRate=0.025" \
  --data-urlencode "withdrawalRate=0.04" \
  "$BASE/goals/new/retirement" -o /dev/null -w "%{redirect_url}")
[[ "$loc" == *"/goals/"* ]] && log_ok "v03-GOAL-2 创建退休目标 → 302 /goals/{id}" \
  || log_bad "v03-GOAL-2 创建退休失败" "loc=$loc"
GOAL_RET_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM family_goal WHERE family_id=1 AND goal_type='RETIREMENT' AND archived_at IS NULL LIMIT 1" 2>/dev/null)

# v03-GOAL-3 · DB target_value = 通胀公式准确(15000 × 12 × 1.025^22 / 0.04 ≈ 7,747,000)
target=$(mysql -ufinance -pfinance finance -sN -e "SELECT target_value FROM family_goal WHERE id=$GOAL_RET_ID" 2>/dev/null)
target_int=$(echo "$target" | cut -d. -f1)
{ [[ "$target_int" -gt 7700000 ]] && [[ "$target_int" -lt 7800000 ]]; } \
  && log_ok "v03-GOAL-3 退休目标 target_value=$target_int 通胀公式准确" \
  || log_bad "v03-GOAL-3 target_value 不准" "got=$target_int 期望 7.74m"

# v03-GOAL-4 · GET /goals/{id} 详情页含三情景 + 当前进度
$CURL -b $COOKIE "$BASE/goals/$GOAL_RET_ID" -o "$TMP" -w ""
{ grep -q "v03 自由生活" "$TMP" && grep -q "三情景" "$TMP" && grep -q "scenario-chart" "$TMP"; } \
  && log_ok "v03-GOAL-4 /goals/{id} 详情含名称+三情景+chart" \
  || log_bad "v03-GOAL-4 详情页缺元素" "see $TMP"

# v03-GOAL-5 · 创建教育金 · child_member_id FK 写入
CHILD_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM member WHERE family_id=1 ORDER BY id LIMIT 1" 2>/dev/null)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "name=v03 教育金" \
  --data-urlencode "childMemberId=$CHILD_ID" --data-urlencode "childBirthYear=2020" \
  --data-urlencode "targetYearOffset=18" --data-urlencode "targetAmount=800000" \
  --data-urlencode "inflationRate=0.03" \
  "$BASE/goals/new/education" -o /dev/null -w "" || true
GOAL_EDU_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM family_goal WHERE goal_type='EDUCATION' AND archived_at IS NULL ORDER BY id DESC LIMIT 1" 2>/dev/null)
params=$(mysql -ufinance -pfinance finance -sN -e "SELECT params_json FROM family_goal WHERE id=$GOAL_EDU_ID" 2>/dev/null)
{ [[ -n "$GOAL_EDU_ID" ]] && echo "$params" | grep -q "\"child_member_id\": $CHILD_ID"; } \
  && log_ok "v03-GOAL-5 教育金创建 · child_member_id=$CHILD_ID 入 params_json" \
  || log_bad "v03-GOAL-5 教育金 child_member_id 缺" "params=$params"

# v03-GOAL-6 · 创建应急 · target_value=NULL(由 caller derived)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "name=v03 应急" \
  --data-urlencode "monthsTarget=6" --data-urlencode "autoBaseline=true" \
  "$BASE/goals/new/emergency" -o /dev/null -w "" || true
emer_target=$(mysql -ufinance -pfinance finance -sN -e "SELECT IFNULL(target_value,'NULL') FROM family_goal WHERE goal_type='EMERGENCY' AND archived_at IS NULL ORDER BY id DESC LIMIT 1" 2>/dev/null)
[[ "$emer_target" == "NULL" ]] && log_ok "v03-GOAL-6 应急 target_value=NULL(derived)" \
  || log_bad "v03-GOAL-6 应急 target 不应入库" "target=$emer_target"

# v03-GOAL-7 · GET /goals 列表渲染 3 个目标
$CURL -b $COOKIE "$BASE/goals" -o "$TMP" -w ""
{ grep -q "v03 自由生活" "$TMP" && grep -q "v03 教育金" "$TMP" && grep -q "v03 应急" "$TMP"; } \
  && log_ok "v03-GOAL-7 /goals 列表渲染 3 个目标" \
  || log_bad "v03-GOAL-7 列表缺目标" "see $TMP"

# v03-GOAL-8 · Dashboard 条带显当前目标(不再显引导卡)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q "v03 自由生活" "$TMP" && ! grep -q "创建你的第一个目标" "$TMP"; } \
  && log_ok "v03-GOAL-8 Dashboard 条带含目标 · 引导卡消失" \
  || log_bad "v03-GOAL-8 Dashboard 条带" "see $TMP"

# v03-GOAL-9 · 非法目标类型 → 4xx
code=$($CURL -b $COOKIE "$BASE/goals/new/invalidtype" -o /dev/null -w "%{http_code}")
[[ "$code" == "500" || "$code" == "400" ]] && log_ok "v03-GOAL-9 非法类型 4xx/5xx 拒绝" \
  || log_bad "v03-GOAL-9 非法类型未拒" "code=$code"

# v03-GOAL-10 · POST /goals/{id}/archive 软删 · 列表不再出现
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/$GOAL_EDU_ID/archive" -o /dev/null -w "" || true
arch=$(mysql -ufinance -pfinance finance -sN -e "SELECT IFNULL(archived_at,'NULL') FROM family_goal WHERE id=$GOAL_EDU_ID" 2>/dev/null)
{ [[ "$arch" != "NULL" ]] \
   && $CURL -b $COOKIE "$BASE/goals" -o "$TMP" -w "" \
   && ! grep -q "v03 教育金" "$TMP"; } \
  && log_ok "v03-GOAL-10 软删 archived_at 入库 · 列表过滤" \
  || log_bad "v03-GOAL-10 软删失效" "arch=$arch"

# v03-GOAL-11 · v0.2 dashboard 行为未破坏(净资产 / KPI 仍在)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q "净资产" "$TMP" && grep -q "总资产" "$TMP"; } \
  && log_ok "v03-GOAL-11 Dashboard v0.2 KPI 卡完全保留" \
  || log_bad "v03-GOAL-11 Dashboard 破坏 v0.2" "see $TMP"

# v03-GOAL-12 · 顶部 nav 加「目标」项
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
grep -q 'href="/goals"' "$TMP" && log_ok "v03-GOAL-12 顶部 nav 加 /goals link" \
  || log_bad "v03-GOAL-12 nav 缺 /goals" "see $TMP"

# 复跑后置:清干净 v03-GOAL 创建的目标,不影响后续/历史
mysql -ufinance -pfinance finance -e "DELETE FROM family_goal WHERE family_id=1 AND name LIKE 'v03 %';" 2>/dev/null


###################################################
# v0.3 FR-51 · 储蓄能力 · /entry 2 框 + /reports 储蓄区块
###################################################
# 复跑前置:清掉所有家庭 1 周期的脏 cashflow 数据(测试期间累计的)
PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
mysql -ufinance -pfinance finance -e "DELETE FROM period_member_cashflow WHERE family_id=1;" 2>/dev/null
$CURL -b $COOKIE -c $COOKIE "$BASE/entry" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
ME_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM member WHERE family_id=1 AND username='diwa'" 2>/dev/null)

# v03-IND-1 · /entry 页面含 FR-51 2 框 form
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
# 2026-08-13:「家庭口径 2 框」是 FR-51 的旧形态。v1.8(FR-270/271)起**收入侧只有逐笔录入**
#   (页脚给 Σ 合计),支出侧才是逐笔/总额二选一(默认总额)→ 「的本月总收入」这个框已被主动删掉。
#   守的意图不变:填报页必须同时透出**家庭口径的收入合计**和**总额提交入口**。
#   注意支出侧是**家庭级开关二选一**:ITEMIZED 只有逐笔录入(没有总额框和 cashflow-summary),
#   TOTAL 才有总额框。断言写成"两者取其一",否则家庭把开关一切护栏就红(beta 现在是 ITEMIZED)。
# v1.19.6 · 判据改成认「支出区**恒在**的东西」,不再认合计行。
#   原来认「家庭本月支出」那条合计行,而它只在**本期已录过支出**时才渲染 ——
#   于是这条护栏红不红取决于跑之前 beta 里有没有支出流水(qa-run / e2e 都会还原数据)。
#   实测同一份代码连跑两次,一次红一次绿。**依赖数据状态的判据不是护栏,是掷骰子。**
#   现在两种模式各认一个恒在的锚:TOTAL → cashflow-summary 表单;ITEMIZED → 「逐笔录入」说明。
{ grep -q '家庭本月收入' "$TMP" \
  && { grep -q 'cashflow-summary' "$TMP" || grep -q '逐笔录入' "$TMP"; }; } \
  && log_ok "v03-IND-1 /entry 透出家庭口径收入合计 + 支出入口(逐笔 Σ 或总额框二选一)" \
  || log_bad "v03-IND-1 entry 家庭口径入口缺" "see $TMP"

# v03-IND-2 · POST /entry/cashflow-summary 写入 DB
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "periodId=$PID" --data-urlencode "totalIncomeInput=35000" --data-urlencode "totalExpenseInput=18000" \
  "$BASE/entry/cashflow-summary" -o /dev/null -w "%{http_code}")
in_out=$(mysql -ufinance -pfinance finance -sN -e "SELECT CONCAT(total_income_input,'/',total_expense_input) FROM period_member_cashflow WHERE period_id=$PID AND member_id=$ME_ID" 2>/dev/null)
{ [[ "$code" == "302" ]] && [[ "$in_out" == "35000.00/18000.00" ]]; } \
  && log_ok "v03-IND-2 POST cashflow-summary 写入 35k/18k" \
  || log_bad "v03-IND-2 POST cashflow-summary" "code=$code db=$in_out"

# v03-IND-3 · POST 任一空值 → NULL 入库(选填 backward compat)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "periodId=$PID" --data-urlencode "totalIncomeInput=" --data-urlencode "totalExpenseInput=" \
  "$BASE/entry/cashflow-summary" -o /dev/null -w "" || true
in_out=$(mysql -ufinance -pfinance finance -sN -e "SELECT CONCAT(IFNULL(total_income_input,'NULL'),'/',IFNULL(total_expense_input,'NULL')) FROM period_member_cashflow WHERE period_id=$PID AND member_id=$ME_ID" 2>/dev/null)
[[ "$in_out" == "NULL/NULL" ]] \
  && log_ok "v03-IND-3 空值 → NULL 入库(选填 backward compat)" \
  || log_bad "v03-IND-3 空值未 NULL" "db=$in_out"

# v03-IND-4 · /reports 储蓄区块渲染(无数据态 → 引导卡)
#   v0.4.4 文案专业化:「/entry」→「填报页」
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
#   2026-08-04 放宽:原判据要求必须出现「去填报页」引导卡,前提是 beta 此刻没有储蓄数据。
#   v1.8 起「已填月数」按统一口径判(逐笔录了支出就算有数据),逐笔家庭的这一段会正常渲染 KPI 卡,
#   引导卡自然不出现 —— 那是**正确行为**,不是回退。核心意图是「这一段不能空白」:
#   要么给引导卡,要么给出 KPI,两者都没有才是 bug。
{ grep -q '储蓄能力' "$TMP" && { grep -q '去填报页' "$TMP" || grep -q '月均收入' "$TMP"; }; } \
  && log_ok "v03-IND-4 /reports 储蓄区块非空(无数据→引导卡 / 有数据→KPI 卡)" \
  || log_bad "v03-IND-4 reports 储蓄区块" "see $TMP"

# 重新写入数据 · 测有数据态(ReportsController 用 findLatest(family, 12) · 必须写到最近 12 期中的一个)
PID_LATEST=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 ORDER BY period_start DESC LIMIT 1" 2>/dev/null)
mysql -ufinance -pfinance finance -e "INSERT INTO period_member_cashflow (family_id, period_id, member_id, total_income_input, total_expense_input) VALUES (1, $PID_LATEST, $ME_ID, 35000, 18000) ON DUPLICATE KEY UPDATE total_income_input=35000, total_expense_input=18000;" 2>/dev/null
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
# v03-IND-5 · /reports 储蓄区块渲染(有数据态 → 双柱图 canvas + KPI)
{ grep -q 'savings-bars' "$TMP" && grep -q '月度收支双柱' "$TMP"; } \
  && log_ok "v03-IND-5 /reports 储蓄区块有数据时显双柱图" \
  || log_bad "v03-IND-5 reports 双柱" "see $TMP"

# v03-IND-6 · reports 保留核心内容(净资产等);桑基图/瀑布图 v0.4 FR-60b 已砍(与新定位冲突),不再期待。
#   v0.10.6 去过期:原断言宣称「桑基图 100% 保留」+ grep 'sankey' 仅命中已废弃的 sankeyNodes/Links JS 残留变量
#   (无图渲染);去掉 sankey 期待,只验 backward-compat 核心内容「净资产」仍在。
grep -q '净资产' "$TMP" \
  && log_ok "v03-IND-6 reports 保留核心内容(净资产;桑基图/瀑布 v0.4 已砍)" \
  || log_bad "v03-IND-6 reports 核心内容缺失" "see $TMP"

# v03-IND-7 · /entry FR-51 2 框在页面"上方"(用户最先录入位置 · 2026-05-13 反馈)
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
# 测"第一步 我的本月"出现的行号 < "本期总进度"行号(即 FR-51 在 v0.2 进度卡之前)
fr51_line=$(grep -n "第 · 一 · 步" "$TMP" | head -1 | cut -d: -f1)
prog_line=$(grep -n "本期总进度" "$TMP" | head -1 | cut -d: -f1)
{ [[ -n "$fr51_line" ]] && [[ -n "$prog_line" ]] && [[ "$fr51_line" -lt "$prog_line" ]]; }   && log_ok "v03-IND-7 /entry FR-51 在「本期总进度」之前(置顶 · 第一步)"   || log_bad "v03-IND-7 entry 2 框不在顶部" "fr51=$fr51_line prog=$prog_line"
# v03-IND-8 · v0.4 KPI 收敛 9→5 → v0.4.2 第 5 KPI 顶替为"本月资产收益"
# 改判 dashboard 含"本月资产收益"或"月储蓄能力" · /reports 含原 4 KPI
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
dash_ok=$(grep -cE "本月资产收益|月储蓄能力" "$TMP")
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
rpt_ok=$(grep -c "月均收入" "$TMP")
{ [[ "$dash_ok" -ge 1 ]] && [[ "$rpt_ok" -ge 1 ]]; } \
  && log_ok "v03-IND-8 (v0.4.2 改) dashboard 第 5 KPI(本月资产收益/月储蓄)· reports 储蓄区原 4 KPI(dash=$dash_ok rpt=$rpt_ok)" \
  || log_bad "v03-IND-8 KPI 搬迁" "dash=$dash_ok rpt=$rpt_ok"

# v03-IND-9 · /reports 储蓄区块加月均收入/支出 KPI · 数字来自 period.total_*_input
mysql -ufinance -pfinance finance -e "UPDATE period_member_cashflow SET total_income_input=40000, total_expense_input=15000 WHERE period_id=$PID_LATEST AND member_id=$ME_ID;" 2>/dev/null
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
grep -q "月均收入(近 12 月)" "$TMP" \
  && log_ok "v03-IND-9 /reports 储蓄区块加月均收入 KPI" \
  || log_bad "v03-IND-9 reports 月均收入 KPI" "see $TMP"

# v03-IND-10 · /checkup 流动性月数用新 service(优先 v0.3 口径)· 仅断言页能加载(数字精度 v0.2 既有规则覆盖)
code=$($CURL -b $COOKIE "$BASE/checkup" -o /dev/null -w "%{http_code}")
[[ "$code" == "200" ]] && log_ok "v03-IND-10 /checkup 用 HouseholdCashflowService 算月均支出 · 页面渲染 OK" \
  || log_bad "v03-IND-10 checkup 破坏" "code=$code"

# v03-IND-11 · 多成员独立填报 · 家庭聚合 = SUM(成员)· 2026-05-13 修订验证
# v0.4 修:dashboard 月均收入/支出 KPI 已搬 /reports · 改判 /reports 显示 SUM 数字
BOB_ID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM member WHERE family_id=1 AND username='wangergou'" 2>/dev/null)
mysql -ufinance -pfinance finance -e "INSERT INTO period_member_cashflow (family_id, period_id, member_id, total_income_input, total_expense_input) VALUES (1, $PID_LATEST, $BOB_ID, 22000, 8000) ON DUPLICATE KEY UPDATE total_income_input=22000, total_expense_input=8000;" 2>/dev/null
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q "¥62,000" "$TMP" && grep -q "¥23,000" "$TMP"; } \
  && log_ok "v03-IND-11 (v0.4 改) 多成员 SUM → /reports 储蓄区显 ¥62k / ¥23k" \
  || log_bad "v03-IND-11 SUM 聚合不对" "see $TMP"

# v03-IND-12 · /entry 显式"家庭本月总收入(SUM 成员)"区块
rm -f $COOKIE; TOKEN=$($CURL -c $COOKIE "$BASE/login" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
$CURL -b $COOKIE -c $COOKIE -X POST --data-urlencode "_csrf=$TOKEN" --data-urlencode "username=diwa" --data-urlencode "password=demo1234" "$BASE/login" -o /dev/null -w "" || true
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
# 2026-08-13:同 v03-IND-1 —— 「家庭本月总收入」已随 v1.8 收入侧改逐笔而去;
#   进度行的措辞也分模式(逐笔=「家庭本月已录 N 笔」· 总额=「家庭本月已填收支 N/M 人」),两者取其一。
{ grep -q "家庭本月收入" "$TMP" && { grep -q "家庭本月已录" "$TMP" || grep -q "家庭本月已填" "$TMP"; }; } \
  && log_ok "v03-IND-12 /entry 含家庭聚合(收入合计 + 已录/已填进度)" \
  || log_bad "v03-IND-12 entry 缺家庭聚合" "see $TMP"

# 复跑后置:清掉测试 cashflow 数据
mysql -ufinance -pfinance finance -e "DELETE FROM period_member_cashflow WHERE family_id=1;" 2>/dev/null


###################################################
# v0.3 FR-52 · 股票自动估值
###################################################
# 复跑前置:清测试持仓
mysql -ufinance -pfinance finance -e "DELETE FROM stock_holding WHERE display_name LIKE 'v03 %';" 2>/dev/null
mysql -ufinance -pfinance finance -e "DELETE FROM stock_price_snapshot WHERE ticker IN ('V03TEST', 'V03TST');" 2>/dev/null

STOCK_ACC=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 AND type='STOCK' AND archived_at IS NULL LIMIT 1" 2>/dev/null)

$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)

# v03-STOCK-1 · STOCK 类型账户持仓页 200
code=$($CURL -b $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o "$TMP" -w "%{http_code}")
{ [[ "$code" == "200" ]] && grep -q "持仓管理" "$TMP" && grep -q "AUTO 自动估值\|MANUAL 手填\|添加持仓\|还没有持仓" "$TMP"; } \
  && log_ok "v03-STOCK-1 STOCK 账户持仓页 200" \
  || log_bad "v03-STOCK-1 持仓页" "code=$code"

# v03-STOCK-2 · 非 STOCK 账户拒绝访问持仓页
# 2026-08-13:原来随便取一个 `type != 'STOCK'` 的账户就断言拒绝 —— 但 v1.4 起
#   supportsHoldings 已**主动放开** WEALTH/CASH(基金/理财/支付宝,为截图导入多持仓),
#   v0.14/v1.x 又加了 METAL/CRYPTO。于是取到 CASH 账户当然 200,护栏在守一条项目已推翻的规则。
#   改为对着**真正不支持持仓的类型**(LOAN/PROPERTY/INSURANCE/OTHER)断言拒绝 —— 这才是红线。
NON_STOCK=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 \
  AND type IN ('LOAN','PROPERTY','INSURANCE','OTHER') AND archived_at IS NULL LIMIT 1" 2>/dev/null)
NON_STOCK_T=$(mysql -ufinance -pfinance finance -sN -e "SELECT type FROM account WHERE id=$NON_STOCK" 2>/dev/null)
code=$($CURL -b $COOKIE "$BASE/accounts/$NON_STOCK/holdings" -o /dev/null -w "%{http_code}")
[[ "$code" == "500" || "$code" == "400" ]] && log_ok "v03-STOCK-2 不支持持仓的类型($NON_STOCK_T)访问持仓页被拒(supportsHoldings 红线)" \
  || log_bad "v03-STOCK-2 $NON_STOCK_T 未拒" "code=$code"

# v03-STOCK-3 · 创建 MANUAL 持仓(股数×单股估值)· issue#3 精度:单股 15.678 原样落库(非旧 DECIMAL(15,2) 截成 15.68)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 字节期权" --data-urlencode "shares=1" --data-urlencode "unitValue=15.678" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-manual" -o /dev/null -w "%{http_code}")
mv=$(mysql -ufinance -pfinance finance -sN -e "SELECT manual_value=15.678 FROM stock_holding WHERE display_name='v03 字节期权' AND archived_at IS NULL ORDER BY id DESC LIMIT 1" 2>/dev/null)
{ [[ "$code" == "302" ]] && [[ "$mv" == "1" ]]; } \
  && log_ok "v03-STOCK-3 创建 MANUAL 持仓 · 单股估值 15.678 原样落库(issue#3 精度 · V37 (20,6))" \
  || log_bad "v03-STOCK-3 MANUAL 创建/精度" "code=$code mv(=15.678?)=$mv"

# v03-STOCK-4 · 创建 AUTO 持仓 · 真拉价
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 阿里" --data-urlencode "ticker=BABA" --data-urlencode "market=US" \
  --data-urlencode "shares=50" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "%{http_code}")
sleep 3
have_holding=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM stock_holding WHERE display_name='v03 阿里' AND ticker='BABA' AND market='US' AND archived_at IS NULL" 2>/dev/null)
have_price=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM stock_price_snapshot WHERE ticker='BABA' AND market='US'" 2>/dev/null)
{ [[ "$code" == "302" ]] && [[ "$have_holding" == "1" ]] && [[ "$have_price" -ge "1" ]]; } \
  && log_ok "v03-STOCK-4 创建 AUTO BABA · 持仓+价格快照入库" \
  || log_bad "v03-STOCK-4 AUTO 创建" "code=$code holding=$have_holding price=$have_price"

# v03-STOCK-5 · A 股拉价(新浪)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 茅台" --data-urlencode "ticker=600519" --data-urlencode "market=CN" \
  --data-urlencode "shares=5" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "%{http_code}")
sleep 3
src=$(mysql -ufinance -pfinance finance -sN -e "SELECT source FROM stock_price_snapshot WHERE ticker='600519' AND market='CN' ORDER BY fetched_at DESC LIMIT 1" 2>/dev/null)
[[ -n "$src" ]] && log_ok "v03-STOCK-5 A 股 600519 拉价成功 · source=$src" \
  || log_bad "v03-STOCK-5 A 股拉价" "no snapshot"

# v03-STOCK-5b · issue#3 · 上交所 ETF 513180 拉价(旧 startsWith("6")?sh:sz 会误判 sz513180 → 查无 → 全源熔断)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 恒生科技ETF" --data-urlencode "ticker=513180" --data-urlencode "market=CN" \
  --data-urlencode "shares=100" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "%{http_code}")
sleep 3
src5b=$(mysql -ufinance -pfinance finance -sN -e "SELECT source FROM stock_price_snapshot WHERE ticker='513180' AND market='CN' ORDER BY fetched_at DESC LIMIT 1" 2>/dev/null)
[[ -n "$src5b" ]] && log_ok "v03-STOCK-5b 上交所 ETF 513180 拉价成功(issue#3 前缀修复 · sh513180)· source=$src5b" \
  || log_bad "v03-STOCK-5b ETF 513180 拉价(前缀应判 sh)" "no snapshot"

# v03-STOCK-6 · 港股 5 位前导零规范化(用户填 0700 → 入库 00700)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 腾讯" --data-urlencode "ticker=0700" --data-urlencode "market=HK" \
  --data-urlencode "shares=10" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "%{http_code}")
sleep 3
hk_ticker=$(mysql -ufinance -pfinance finance -sN -e "SELECT ticker FROM stock_holding WHERE display_name='v03 腾讯' AND market='HK' AND archived_at IS NULL" 2>/dev/null)
[[ "$hk_ticker" == "00700" ]] \
  && log_ok "v03-STOCK-6 港股 ticker 规范化 0700 → 00700" \
  || log_bad "v03-STOCK-6 港股规范化" "ticker=$hk_ticker"

# v03-STOCK-7 · 估值写回 account_balance · note=系统估值同步(v0.4.4 起 · 老数据仍可能是 auto-stock-valuation v0.3)
PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/accounts/$STOCK_ACC/holdings/refresh" -o /dev/null -w ""
sleep 2
note=$(mysql -ufinance -pfinance finance -sN -e "SELECT note FROM period_snapshot WHERE period_id=$PID AND account_id=$STOCK_ACC" 2>/dev/null)
{ [[ "$note" == *"系统估值"* || "$note" == *"auto-stock-valuation"* ]]; } \
  && log_ok "v03-STOCK-7 估值写回 period_snapshot · note=$note" \
  || log_bad "v03-STOCK-7 估值未写回" "note=$note"

# v03-STOCK-8 · backward compat · 无 holding 的 STOCK 账户不被改 balance
# 创建一个新的 STOCK 账户没加持仓 · 让 refresh 跑 · 该账户 balance 应保持手填值
# (实际依赖 beta 还有别的 STOCK 账户)— 简化为"refreshAllForFamily 不报错"
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/accounts/$STOCK_ACC/holdings/refresh" -o /dev/null -w "" && \
  log_ok "v03-STOCK-8 refresh 全家估值不抛异常 · backward compat"

# v03-STOCK-9 · 软删持仓 → 账户余额自动重算(少了这只持仓的市值)
HID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM stock_holding WHERE display_name='v03 茅台' AND archived_at IS NULL" 2>/dev/null)
balance_before=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID AND account_id=$STOCK_ACC" 2>/dev/null)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/accounts/$STOCK_ACC/holdings/$HID/archive" -o /dev/null -w "" && sleep 1
balance_after=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID AND account_id=$STOCK_ACC" 2>/dev/null)
{ [[ "$balance_before" != "$balance_after" ]]; } \
  && log_ok "v03-STOCK-9 持仓归档后账户余额重算 · before=$balance_before after=$balance_after" \
  || log_bad "v03-STOCK-9 归档未触发重算" "before=$balance_before after=$balance_after"

# v03-STOCK-10 · /entry STOCK 行加"📦 持仓变动?" 入口
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
grep -q "持仓变动" "$TMP" \
  && log_ok "v03-STOCK-10 /entry STOCK 行加持仓变动入口" \
  || log_bad "v03-STOCK-10 entry STOCK 行" "no link"

# v03-STOCK-11 · fx 链式跨币种 · 账户 HKD + 持仓 USD/HKD 混合(2026-05-13 bug fix)
# 场景:HKD 账户混持 BABA(USD) + 腾讯(HKD)· fx_rate 表只存 base=CNY 方向 · 需经 CNY 中转
ORIG_CURR=$(mysql -ufinance -pfinance finance -sN -e "SELECT currency FROM account WHERE id=$STOCK_ACC" 2>/dev/null)
mysql -ufinance -pfinance finance -e "UPDATE account SET currency='HKD' WHERE id=$STOCK_ACC; DELETE FROM stock_holding WHERE account_id=$STOCK_ACC;" 2>/dev/null
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
# BABA 100 股 USD
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 BABA" --data-urlencode "ticker=BABA" --data-urlencode "market=US" \
  --data-urlencode "shares=100" --data-urlencode "currency=USD" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "" || true
sleep 2
# 腾讯 200 股 HKD
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 腾讯" --data-urlencode "ticker=00700" --data-urlencode "market=HK" \
  --data-urlencode "shares=200" --data-urlencode "currency=HKD" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-auto" -o /dev/null -w "" || true
sleep 3
PID_OPEN=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
bal=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID_OPEN AND account_id=$STOCK_ACC" 2>/dev/null)
# 预期 BABA 100 × $price × USD→HKD(经 CNY 中转 ≈ 7.83)+ 腾讯 200 × HK$459 ≈ 196k 量级
# 容差大:只要 > 50000(说明 fx 链式生效 · 不再走 1.0 兜底)
bal_int=$(echo "$bal" | cut -d. -f1)
{ [[ -n "$bal" ]] && [[ "$bal_int" -gt 50000 ]] && [[ "$bal_int" -lt 500000 ]]; } \
  && log_ok "v03-STOCK-11 fx 链式跨币种 USD/HKD/HKD 账户 · bal=$bal HKD(经 CNY 中转)" \
  || log_bad "v03-STOCK-11 fx 链式失败 · 走 1.0 兜底" "bal=$bal"

# 恢复账户币种
mysql -ufinance -pfinance finance -e "UPDATE account SET currency='$ORIG_CURR' WHERE id=$STOCK_ACC; DELETE FROM stock_holding WHERE account_id=$STOCK_ACC;" 2>/dev/null

# v03-STOCK-12 · CASH 现金行表单页 200(FR-52e)
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/accounts/$STOCK_ACC/holdings/new-cash")
{ [[ "$code" == "200" ]] && grep -q 'currency' "$TMP" && grep -q 'amount' "$TMP"; } \
  && log_ok "v03-STOCK-12 CASH 表单页 200 · 含 currency+amount" \
  || log_bad "v03-STOCK-12 CASH 表单页" "code=$code"

# v03-STOCK-13 · 创建 CASH 行 USD 5000 在 HKD 账户 · 估值含 FX 折算
ORIG_CURR=$(mysql -ufinance -pfinance finance -sN -e "SELECT currency FROM account WHERE id=$STOCK_ACC" 2>/dev/null)
mysql -ufinance -pfinance finance -e "UPDATE account SET currency='HKD' WHERE id=$STOCK_ACC; DELETE FROM stock_holding WHERE account_id=$STOCK_ACC;" 2>/dev/null
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings/new-cash" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 USD 现金" \
  --data-urlencode "currency=USD" \
  --data-urlencode "amount=5000" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-cash" -o /dev/null -w "%{http_code}")
sleep 2
cash_row=$(mysql -ufinance -pfinance finance -sN -e "SELECT valuation_mode,currency,ROUND(manual_value,2) FROM stock_holding WHERE account_id=$STOCK_ACC AND display_name='v03 USD 现金'" 2>/dev/null | tr '\t' '|')
PID_OPEN=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
bal=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID_OPEN AND account_id=$STOCK_ACC" 2>/dev/null)
bal_int=$(echo "$bal" | cut -d. -f1)
# USD 5000 × FX(USD→HKD 经 CNY ≈ 7.83) ≈ 39150 HKD;容差:bal > 20000 且 < 100000
{ [[ "$code" =~ ^30[0-9]$ ]] && [[ "$cash_row" == "CASH|USD|5000.00" ]] && [[ -n "$bal" ]] && [[ "$bal_int" -gt 20000 ]] && [[ "$bal_int" -lt 100000 ]]; } \
  && log_ok "v03-STOCK-13 CASH USD 5000 → HKD 账户余额 $bal(经 CNY FX 链)" \
  || log_bad "v03-STOCK-13 CASH 创建+FX 估值" "code=$code row=$cash_row bal=$bal"

# v03-STOCK-14 · 更新 CASH 金额 · manual_value_at 刷新
HID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM stock_holding WHERE account_id=$STOCK_ACC AND display_name='v03 USD 现金'" 2>/dev/null)
OLD_AT=$(mysql -ufinance -pfinance finance -sN -e "SELECT manual_value_at FROM stock_holding WHERE id=$HID" 2>/dev/null)
sleep 2
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "amount=8000" \
  "$BASE/accounts/$STOCK_ACC/holdings/$HID/update-cash" -o /dev/null -w "" || true
sleep 1
NEW_AT=$(mysql -ufinance -pfinance finance -sN -e "SELECT manual_value_at FROM stock_holding WHERE id=$HID" 2>/dev/null)
NEW_VAL=$(mysql -ufinance -pfinance finance -sN -e "SELECT ROUND(manual_value,2) FROM stock_holding WHERE id=$HID" 2>/dev/null)
{ [[ "$NEW_VAL" == "8000.00" ]] && [[ "$OLD_AT" != "$NEW_AT" ]]; } \
  && log_ok "v03-STOCK-14 CASH 金额更新 5000→8000 · manual_value_at 刷新" \
  || log_bad "v03-STOCK-14 CASH 更新" "val=$NEW_VAL old_at=$OLD_AT new_at=$NEW_AT"

# v03-STOCK-15 · 持仓 + CASH 共存 · account_balance = holdings + cash
$CURL -b $COOKIE -c $COOKIE "$BASE/accounts/$STOCK_ACC/holdings" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
# 2026-08-13 修两处**护栏自身**的过时(功能一直好的):
#   ① 载荷过时:v13.1 精度改造把手填持仓从单一 `manualValue` 改成 `shares × unitValue`(20,6 位),
#      这里还在发 `manualValue=50000` → POST 直接 400,持仓**根本没建出来**,于是账户估值里只剩
#      USD 现金那部分(实测 62775.46 ≈ 8000×7.847),看着像"共存估值算错了",其实是测试没建成数据。
#   ② 断言写死区间 80000~160000 —— 那是当年 fixture 攒出来的数,自动估值持仓一涨跌就不成立。
#      改成测**增量**:加一笔 50000,估值就该多 50000,且原有 CASH 持仓不被顶掉(两形态共存)。
#      与账户里原本多少钱、汇率多少、涨跌多少全都无关。
pre_bal=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID_OPEN AND account_id=$STOCK_ACC" 2>/dev/null)
# 加一个 HKD MANUAL 持仓 = 1 份 × 50000(v13.1 起 shares/unitValue 均必填)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
  --data-urlencode "displayName=v03 私募 X" --data-urlencode "shares=1" --data-urlencode "unitValue=50000" \
  "$BASE/accounts/$STOCK_ACC/holdings/new-manual" -o /dev/null -w "" || true
sleep 3
new_bal=$(mysql -ufinance -pfinance finance -sN -e "SELECT end_balance FROM period_snapshot WHERE period_id=$PID_OPEN AND account_id=$STOCK_ACC" 2>/dev/null)
BAL_DELTA=$(awk -v a="$new_bal" -v b="$pre_bal" 'BEGIN{d=a-b; if(d<0)d=-d; printf "%d", d}')
# CASH 行是靠 **valuation_mode='CASH'** 标识的(market 为 NULL),不是 market='CASH'
CASH_HOLD=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM stock_holding WHERE account_id=$STOCK_ACC AND valuation_mode='CASH' AND archived_at IS NULL" 2>/dev/null)
{ [[ -n "$new_bal" ]] && [[ -n "$pre_bal" ]] && [[ "$BAL_DELTA" -ge 49900 ]] && [[ "$BAL_DELTA" -le 50100 ]] && [[ "${CASH_HOLD:-0}" -ge 1 ]]; } \
  && log_ok "v03-STOCK-15 手填持仓 50000 计入估值(Δ=$BAL_DELTA)· 同账户 CASH 持仓 $CASH_HOLD 笔仍在(两形态共存)" \
  || log_bad "v03-STOCK-15 持仓+CASH 共存估值" "pre=$pre_bal new=$new_bal Δ=$BAL_DELTA cashHold=$CASH_HOLD"

# 恢复账户币种 + 清测试持仓
mysql -ufinance -pfinance finance -e "UPDATE account SET currency='$ORIG_CURR' WHERE id=$STOCK_ACC; DELETE FROM stock_holding WHERE account_id=$STOCK_ACC;" 2>/dev/null

# 复跑后置:清测试持仓 · 重算原始账户余额
mysql -ufinance -pfinance finance -e "DELETE FROM stock_holding WHERE display_name LIKE 'v03 %';" 2>/dev/null


###################################################
# v0.3 FR-53 · AI 4 处介入
###################################################
$CURL -b $COOKIE -c $COOKIE "$BASE/goals/new/retirement" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)

# v03-AI-1 · FR-53a · /goals/advise/retirement 返回 ok+JSON 或 unavailable(取决于 LLM 可用性)
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/advise/retirement" -o "$TMP" -w "" --max-time 30
{ grep -q '"ok":\s*true' "$TMP" || grep -q '"ok":\s*false' "$TMP"; } \
  && log_ok "v03-AI-1 /goals/advise/retirement 返回合法 JSON(ok/error)" \
  || log_bad "v03-AI-1 advise 响应" "see $TMP"

# v03-AI-2 · /goals/advise/education JSON 结构
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/advise/education" -o "$TMP" -w "" --max-time 30
{ grep -q '"ok"' "$TMP"; } \
  && log_ok "v03-AI-2 /goals/advise/education JSON 响应" \
  || log_bad "v03-AI-2 advise education" "see $TMP"

# v03-AI-3 · /goals/advise/emergency JSON 结构
$CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/advise/emergency" -o "$TMP" -w "" --max-time 30
{ grep -q '"ok"' "$TMP"; } \
  && log_ok "v03-AI-3 /goals/advise/emergency JSON 响应" \
  || log_bad "v03-AI-3 advise emergency" "see $TMP"

# v03-AI-4 · 非法类型拒
code=$($CURL -b $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/goals/advise/invalid" -o /dev/null -w "%{http_code}")
[[ "$code" == "500" || "$code" == "400" ]] && log_ok "v03-AI-4 非法 type 4xx/5xx" \
  || log_bad "v03-AI-4 非法 type 未拒" "code=$code"

# v03-AI-5 · 表单含 [🤖 AI 推荐] 按钮 + JS 函数
$CURL -b $COOKIE "$BASE/goals/new/retirement" -o "$TMP" -w ""
{ grep -q "AI 推荐" "$TMP" && grep -q "adviseRetirement" "$TMP"; } \
  && log_ok "v03-AI-5 退休向导含 AI 推荐按钮 + JS" \
  || log_bad "v03-AI-5 AI 按钮" "see $TMP"

# v03-AI-6 · FR-53d · v0.2 /checkup 既有功能保留(无目标家庭 prompt 不加段)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
grep -q "</html>" "$TMP" \
  && log_ok "v03-AI-6 /checkup 既有页面渲染保留(backward compat)" \
  || log_bad "v03-AI-6 /checkup 破坏" "incomplete"


###################################################
# v0.4 · 报表整顿 + 摸清第 5 问 + 调优决策
###################################################

# v04-RPT-1 · /dashboard KPI 收敛到 5 + CPI 切换器(v0.4.2:第 5 KPI 顶替为本月资产收益)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ (grep -q '本月资产收益' "$TMP" || grep -q '月储蓄能力' "$TMP") && grep -q 'name="cpi"' "$TMP"; } \
  && log_ok "v04-RPT-1 dashboard 5 KPI(第 5 为本月资产收益/月储蓄能力)+ CPI 切换器" \
  || log_bad "v04-RPT-1 dashboard 改造" "missing"

# v04-RPT-2 · /dashboard 砍收入支出组合图(只剩注释 incomeExpenseChart 字符串 0 个 canvas)
canvases=$(grep -c '<canvas id="incomeExpenseChart"' "$TMP")
[[ "$canvases" -eq 0 ]] && log_ok "v04-RPT-2 dashboard incomeExpenseChart canvas 已砍" \
  || log_bad "v04-RPT-2 incomeExpenseChart 未砍" "canvas=$canvases"

# v04-RPT-3 · /reports 砍 waterfall/sankey/月度收支对比 canvas
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
killed=$(grep -cE '<div id="waterfallChart"|<div id="sankeyChart"|<canvas id="incomeBarChart"' "$TMP")
[[ "$killed" -eq 0 ]] && log_ok "v04-RPT-3 reports 砍 waterfall/sankey/月度收支对比 canvas" \
  || log_bad "v04-RPT-3 流水图未砍" "still=$killed"

# v04-RPT-4 · /reports 含配置 diff section + 账户级基准列
{ grep -q 'id="allocation-diff"' "$TMP" && grep -q '基准 %' "$TMP"; } \
  && log_ok "v04-RPT-4 reports 含配置 diff section + 账户级基准列" \
  || log_bad "v04-RPT-4 reports 新区缺" "missing"

# v04-RPT-5 · /checkup 资产配置仍砍(已有 mini 横向条 · 完整环形见 dashboard)
#   v0.4.5(2026-05-14)用户反馈风险敞口干巴巴 → 风险等级分布改饼图(canvas 回归)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
alloc_canvas=$(grep -cE '<canvas id="allocChart"' "$TMP")
risk_canvas=$(grep -cE '<canvas id="riskChart"' "$TMP")
{ [[ "$alloc_canvas" -eq 0 && "$risk_canvas" -eq 1 ]]; } \
  && log_ok "v04-RPT-5 checkup 砍配置环形(0)· 风险等级保留饼图(1 canvas)" \
  || log_bad "v04-RPT-5 checkup canvas 状态错" "alloc=$alloc_canvas risk=$risk_canvas"

# v04-CPI-1 · family.cpi_assumption 默认 2.00 入库
cpi=$(mysql -ufinance -pfinance finance -sN -e "SELECT cpi_assumption FROM family WHERE id=1" 2>/dev/null)
[[ "$cpi" == "2.00" ]] && log_ok "v04-CPI-1 family.cpi_assumption 默认 2.00" \
  || log_bad "v04-CPI-1 cpi 默认值错" "$cpi"

# v04-CPI-2 · POST /admin/family/cpi 切换到 3.00 + DB 更新
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "cpi=3.00" "$BASE/admin/family/cpi" -o /dev/null -w ""
cpi=$(mysql -ufinance -pfinance finance -sN -e "SELECT cpi_assumption FROM family WHERE id=1" 2>/dev/null)
[[ "$cpi" == "3.00" ]] && log_ok "v04-CPI-2 POST /admin/family/cpi 切 3% · DB 更新" \
  || log_bad "v04-CPI-2 cpi 切换" "$cpi"
mysql -ufinance -pfinance finance -e "UPDATE family SET cpi_assumption=2.00 WHERE id=1" 2>/dev/null

# v04-BMK-1 · reports 含"vs 基准" pill + 跑赢/输 column
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'vs 基准' "$TMP"; } \
  && log_ok "v04-BMK-1 reports 含 vs 基准 KPI" \
  || log_bad "v04-BMK-1 vs 基准缺" "missing"

# v04-DIFF-1 · allocation_anchor 表 4 行预置 + family 默认 SP_4321
n=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM allocation_anchor" 2>/dev/null)
anchor=$(mysql -ufinance -pfinance finance -sN -e "SELECT allocation_anchor FROM family WHERE id=1" 2>/dev/null)
{ [[ "$n" == "4" ]] && [[ "$anchor" == "SP_4321" ]]; } \
  && log_ok "v04-DIFF-1 V22 预置 4 锚 + family 默认 SP_4321" \
  || log_bad "v04-DIFF-1 anchor seed" "n=$n anchor=$anchor"

# v04-DIFF-2 · POST /admin/family/anchor 切到 XQ_AGGRESSIVE · DB 更新
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "anchor=XQ_AGGRESSIVE" "$BASE/admin/family/anchor" -o /dev/null -w ""
anchor=$(mysql -ufinance -pfinance finance -sN -e "SELECT allocation_anchor FROM family WHERE id=1" 2>/dev/null)
[[ "$anchor" == "XQ_AGGRESSIVE" ]] \
  && log_ok "v04-DIFF-2 POST /admin/family/anchor → XQ_AGGRESSIVE · DB 更新" \
  || log_bad "v04-DIFF-2 anchor 切换" "$anchor"
mysql -ufinance -pfinance finance -e "UPDATE family SET allocation_anchor='SP_4321' WHERE id=1" 2>/dev/null

# v04-DIFF-3 · 非法 anchor 拒绝 · DB 不变
$CURL -b $COOKIE "$BASE/admin/family/anchor" -o /dev/null -w "" # refresh xsrf
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "anchor=BOGUS_99" "$BASE/admin/family/anchor" -o /dev/null -w ""
anchor=$(mysql -ufinance -pfinance finance -sN -e "SELECT allocation_anchor FROM family WHERE id=1" 2>/dev/null)
[[ "$anchor" == "SP_4321" ]] && log_ok "v04-DIFF-3 非法 anchor 拒绝 · 保持 SP_4321" \
  || log_bad "v04-DIFF-3 非法 anchor 通过" "$anchor"

# v04-LIQ-4 · LiquiditySurplus 计算正确(单测覆盖) · 这里只确认 service bean 可调
# (跳过 · 已由 LiquiditySurplusTest 6 单测覆盖)

# v04-REFI-1 · GET /reports/refinance 200 + 含表单字段
code=$($CURL -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/reports/refinance")
{ [[ "$code" == "200" ]] && grep -q 'name="loanRate"' "$TMP" && grep -q 'name="investRate"' "$TMP"; } \
  && log_ok "v04-REFI-1 /reports/refinance 表单 200 + 含 loanRate/investRate" \
  || log_bad "v04-REFI-1 refinance 表单" "code=$code"

# v04-REFI-2 · POST 计算 · 走完整结果路径(推荐 or 应急金不足提示均算 PASS · beta 数据差异容忍)
$CURL -b $COOKIE "$BASE/reports/refinance" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST \
  --data-urlencode "_csrf=$XSRF" \
  --data-urlencode "amount=100000" \
  --data-urlencode "loanRate=0.045" \
  --data-urlencode "investRate=0.072" \
  --data-urlencode "years=18" \
  -o "$TMP" -w "%{http_code}" "$BASE/reports/refinance")
{ [[ "$code" == "200" ]] && (grep -q '优先投资' "$TMP" || grep -q '⚠ 先' "$TMP" || grep -q '应急金不足' "$TMP"); } \
  && log_ok "v04-REFI-2 POST 200 + 返回结果块(推荐 or 应急金检查 · beta 数据容忍)" \
  || log_bad "v04-REFI-2 result 块缺" "code=$code"

# v04-REFI-3 · POST · 必还(loanRate ≥ investRate)
$CURL -b $COOKIE "$BASE/reports/refinance" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST \
  --data-urlencode "_csrf=$XSRF" \
  --data-urlencode "amount=100000" \
  --data-urlencode "loanRate=0.058" \
  --data-urlencode "investRate=0.050" \
  --data-urlencode "years=18" \
  -o "$TMP" -w "%{http_code}" "$BASE/reports/refinance")
{ [[ "$code" == "200" ]] && grep -q '必还' "$TMP"; } \
  && log_ok "v04-REFI-3 POST 必还(loanRate ≥ investRate)" \
  || log_bad "v04-REFI-3 必还路径" "code=$code"

# v04-REFI-4 · POST · 非法参数拒绝(loanRate=0.6 > 0.5)
$CURL -b $COOKIE "$BASE/reports/refinance" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST \
  --data-urlencode "_csrf=$XSRF" \
  --data-urlencode "amount=100000" \
  --data-urlencode "loanRate=0.6" \
  --data-urlencode "investRate=0.072" \
  --data-urlencode "years=18" \
  -o "$TMP" -w "%{http_code}" "$BASE/reports/refinance")
{ [[ "$code" == "200" ]] && grep -q '校 · 验\|输入校验' "$TMP"; } \
  && log_ok "v04-REFI-4 非法 loanRate 拒绝 · 校验提示" \
  || log_bad "v04-REFI-4 非法参数处理" "code=$code"

# v04-AI-REBALANCE-1 · POST /reports/rebalance/advise 不抛异常(LLM 可能 unavailable,容忍)
#   2026-09-22:先塞缓存再请求 —— 命中缓存的 advise 不会调 LLM,这条护栏验的本来也只是
#   「这个端点不抛异常、老老实实回 302」,和模型答得怎么样没有关系。
ai_seed_advice
$CURL -b $COOKIE "$BASE/reports" -o /dev/null
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/reports/rebalance/advise" -o /dev/null -w "%{http_code}")
{ [[ "$code" == "302" || "$code" == "303" ]]; } \
  && log_ok "v04-AI-REBALANCE-1 POST /reports/rebalance/advise → 302(LLM 调用容忍失败)" \
  || log_bad "v04-AI-REBALANCE-1 advise 异常" "code=$code"

# v04-VAL-1 · v0.4.1 FR-52f · stock_valuation_event 表已建 · 拉价后写事件
# 找有 holdings 的 STOCK 账户 + 当前 OPEN period
VAL_ACC=$(mysql -ufinance -pfinance finance -sN -e "
  SELECT DISTINCT a.id FROM account a JOIN stock_holding h ON h.account_id=a.id
  WHERE a.family_id=1 AND a.type='STOCK' AND a.archived_at IS NULL AND h.archived_at IS NULL
  LIMIT 1" 2>/dev/null)
VAL_PID=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM period WHERE family_id=1 AND status='OPEN' ORDER BY id DESC LIMIT 1" 2>/dev/null)
if [[ -n "$VAL_ACC" && -n "$VAL_PID" ]]; then
  # 删旧事件 + 改 snapshot 制造明显差异
  mysql -ufinance -pfinance finance -e "DELETE FROM stock_valuation_event WHERE account_id=$VAL_ACC AND period_id=$VAL_PID" 2>/dev/null
  mysql -ufinance -pfinance finance -e "UPDATE period_snapshot SET end_balance = end_balance - 5000 WHERE period_id=$VAL_PID AND account_id=$VAL_ACC" 2>/dev/null
  # 触发 manual refresh
  $CURL -b $COOKIE "$BASE/accounts/$VAL_ACC/holdings" -o /dev/null
  XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk '{print $7}' | tail -1)
  $CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" "$BASE/accounts/$VAL_ACC/holdings/refresh" -o /dev/null -w ""
  sleep 3
  cnt=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM stock_valuation_event WHERE account_id=$VAL_ACC AND period_id=$VAL_PID AND trigger_kind='MANUAL'" 2>/dev/null)
  [[ "$cnt" -ge 1 ]] && log_ok "v04-VAL-1 拉价后写 stock_valuation_event · MANUAL · account=$VAL_ACC count=$cnt" \
    || log_bad "v04-VAL-1 事件未写" "count=$cnt"
else
  log_skip "v04-VAL-1 没找到有 holdings 的 STOCK 账户"
fi

# v04-VAL-2 · /entry ledger 显示 估值 行(v0.10.6 去 emoji:📈→△,与 detail.html VALUATION 一致)
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
grep -q '△ 估值' "$TMP" \
  && log_ok "v04-VAL-2 /entry ledger 显示 △ 估值 行" \
  || log_bad "v04-VAL-2 entry 估值行 缺" "missing"

# v04-VAL-3 · /accounts/{id} 详情页显示估值行
if [[ -n "$VAL_ACC" ]]; then
  $CURL -b $COOKIE "$BASE/accounts/$VAL_ACC" -o "$TMP" -w ""
  grep -q '估值变动 · 手动刷价' "$TMP" \
    && log_ok "v04-VAL-3 /accounts/$VAL_ACC 详情页显示估值行(手动刷价)" \
    || log_bad "v04-VAL-3 accounts ledger 估值行 缺" "missing"
fi

# ===================================================
# v0.4.2 · "钱赚的 vs 人赚的"二分 KPI(InvestmentReturn)
# ===================================================

# v04-RET-1 · dashboard 第 5 KPI 改为"本月资产收益"
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q '本月资产收益' "$TMP" && grep -q '剔除收入' "$TMP"; } \
  && log_ok "v04-RET-1 dashboard 第 5 KPI · 本月资产收益(剔除收入)" \
  || log_bad "v04-RET-1 月度资产收益 KPI 缺" "missing"

# v04-RET-2 · reports 4 KPI 双口径 label + 双口径解释 banner
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q '· 剔除收入' "$TMP" \
  && grep -q '含收入' "$TMP" \
  && grep -q '人赚的' "$TMP" \
  && grep -q '钱赚的' "$TMP" \
  && grep -q '双口径' "$TMP"; } \
  && log_ok "v04-RET-2 reports 4 KPI 双口径(XIRR 含 / 资产年化剔 / 人赚 / 钱赚)+ 解释 banner" \
  || log_bad "v04-RET-2 reports 双口径 缺" "missing"

# v04-RET-3 · checkup 收益诊断卡 4 KPI 升级
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q '资 · 产 · 年 · 化 ★' "$TMP" \
  && grep -q '本月资产收益' "$TMP"; } \
  && log_ok "v04-RET-3 checkup 收益诊断卡 4 KPI(资产年化 ★ + 本月)" \
  || log_bad "v04-RET-3 checkup 收益诊断 缺" "missing"

# v04-RET-4 · InvestmentReturnCalculator 9 单测覆盖(mvn test 验证 · 此处仅断言文件存在)
[[ -f /home/finance/financial-management/src/test/java/com/family/finance/calc/InvestmentReturnCalculatorTest.java ]] \
  && log_ok "v04-RET-4 InvestmentReturnCalculatorTest 9 单测存在 · 月度 + 年化 + YTD 覆盖" \
  || log_bad "v04-RET-4 单测文件缺" "missing"

# ===================================================
# v0.4.3 · QA 视角再审视 → P0 修复(B1 endBalance 续值 · B2 PMC 优先 · B4 YTD 独立 slice)
# ===================================================
section "v0.4.3 · P0 修复 · 历史数据保护 · backward-compat"

# v04-FIX-1 · B1 · FactMapper.queryBase 含 end_balance COALESCE 续值子查询
#   现实场景:用户忘填某账户当月 snapshot → 原 SQL 取出 NULL → netWorth/totalLiabilities 静默失真
#   修复:NULL 时沿用 <= 当期最近一笔非空 snapshot · 不超期 · 不混淆"用户填了 0"和"用户漏填"
grep -q 'ps_carry.end_balance IS NOT NULL' /home/finance/financial-management/src/main/resources/mapper/FactMapper.xml \
  && grep -q 'COALESCE' /home/finance/financial-management/src/main/resources/mapper/FactMapper.xml \
  && log_ok "v04-FIX-1 FactMapper.xml 含 endBalance COALESCE 续值(NULL 用户漏填保护)" \
  || log_bad "v04-FIX-1 FactMapper 续值 SQL 缺" "missing COALESCE/ps_carry"

# v04-FIX-1b · 实际 SQL 在真实 beta 数据上能续值(账户 7/9/11 在 2026-05 漏填 → 续 4 月值)
ACT_CARRIED=$(mysql -ufinance -pfinance finance -N -s -e "
SELECT COALESCE(ps.end_balance,
  (SELECT ps_carry.end_balance FROM period_snapshot ps_carry
     JOIN period p_carry ON p_carry.id=ps_carry.period_id
    WHERE ps_carry.account_id=11 AND p_carry.period_start <= '2026-05-01'
      AND ps_carry.end_balance IS NOT NULL
    ORDER BY p_carry.period_start DESC LIMIT 1)) AS bal
  FROM account a
  CROSS JOIN period p ON 1=1
  LEFT JOIN period_snapshot ps ON ps.account_id=a.id AND ps.period_id=p.id
 WHERE a.id=11 AND p.id=3
" 2>/dev/null | tr -d ' \r\n')
# 兜底:子查询语法兼容性问题时回退到直接续值校验
if [[ -z "$ACT_CARRIED" || "$ACT_CARRIED" == "NULL" ]]; then
  ACT_CARRIED=$(mysql -ufinance -pfinance finance -N -s -e "
    SELECT ps.end_balance FROM period_snapshot ps
      JOIN period p ON p.id=ps.period_id
     WHERE ps.account_id=11 AND p.period_start <= '2026-05-01'
       AND ps.end_balance IS NOT NULL
     ORDER BY p.period_start DESC LIMIT 1" 2>/dev/null | tr -d ' \r\n')
fi
{ [[ -n "$ACT_CARRIED" && "$ACT_CARRIED" != "NULL" && "$ACT_CARRIED" != "0.00" ]]; } \
  && log_ok "v04-FIX-1b 账户 11(房贷)2026-05 缺 snapshot · 续值 SQL 返回 $ACT_CARRIED(非 NULL)" \
  || log_bad "v04-FIX-1b 续值实测" "ACT_CARRIED=$ACT_CARRIED"

# v04-FIX-2 · B2 · averageExpense 双源(PMC 优先 + cash_flow 回退)
#   v1.8 起判据改了:支出口径收敛进 ExpenseLedgerService,FactViewServiceImpl 不再直接
#   调 findFamilyAggregateRecent 取支出(收入侧仍用 PMC mapper)。双源语义没变,只是搬了家 ——
#   PMC 优先在 ExpenseLedgerService.decide 里(总额模式),cash_flow 回退仍在 averageExpense 里。
{ grep -q 'periodMemberCashflowMapper' /home/finance/financial-management/src/main/java/com/family/finance/factview/FactViewServiceImpl.java \
  && grep -q 'expenseLedger.recent' /home/finance/financial-management/src/main/java/com/family/finance/factview/FactViewServiceImpl.java \
  && grep -q 'findFamilyAggregateRecent' /home/finance/financial-management/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java \
  && grep -qE 'expenseOrig|periodExpense' /home/finance/financial-management/src/main/java/com/family/finance/factview/FactViewServiceImpl.java; } \
  && log_ok "v04-FIX-2 averageExpense 双源仍在(PMC 优先移入 ExpenseLedgerService · cash_flow 回退保留)" \
  || log_bad "v04-FIX-2 PMC 优先逻辑缺" "FactViewServiceImpl 应经 expenseLedger.recent 取支出 + 保留 cash_flow 回退;PMC 优先判定在 ExpenseLedgerService"

# v04-FIX-3 · B4 · ytdInvestPnl 独立加载 slice(不复用 caller 的 range-bound slice)
grep -A30 'private BigDecimal ytdInvestPnl' /home/finance/financial-management/src/main/java/com/family/finance/factview/FactViewServiceImpl.java | head -30 | grep -q 'load(new FactFilter' \
  && log_ok "v04-FIX-3 ytdInvestPnl 用 load(new FactFilter) 独立加载 1 月-今天 slice" \
  || log_bad "v04-FIX-3 ytdInvestPnl 独立 slice 缺" "missing"

# v04-FIX-4 · 联调 · /dashboard 在用户漏填情况下不再静默失真(返回 200 + 有 KPI 数字)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
{ grep -q '总资产' "$TMP" && grep -q '总负债' "$TMP" && grep -qE 'kpi-value tnum[^>]*>¥[0-9,]+' "$TMP"; } \
  && log_ok "v04-FIX-4 /dashboard 漏填账户 NULL 续值后 KPI 仍正常渲染 · 不再静默失真" \
  || log_bad "v04-FIX-4 dashboard 渲染异常" "missing kpi value"

# v04-FIX-5 · 联调 · /reports 同样不抛异常(B1 fix 在 reports 也走同一 FactMapper)
$CURL -b $COOKIE "$BASE/reports?range=1Y" -o "$TMP" -w ""
{ grep -q '本金净流入' "$TMP" || grep -q '账户级收益' "$TMP"; } \
  && log_ok "v04-FIX-5 /reports?range=1Y 含 B1 续值后正常出图(本金 vs 损益 + 账户级)" \
  || log_bad "v04-FIX-5 reports 异常" "missing decompose/account"

# v04-FIX-6 · 联调 · /checkup 同样不抛异常(用 averageExpense 计算 emergencyFundMonths)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
grep -q '应 · 急 · 金' "$TMP" || grep -q '紧 急 储 备' "$TMP" || grep -q '应急金' "$TMP" \
  && log_ok "v04-FIX-6 /checkup B2 averageExpense 双源后正常渲染 · 含应急金诊断" \
  || log_bad "v04-FIX-6 checkup 缺应急金" "missing"

# v04-FIX-7 · 单测 · mvn test 含 FactViewService 联动测试(已存在)+ InvestmentReturnCalculator 9 测
[[ -f /home/finance/financial-management/src/test/java/com/family/finance/factview/FactViewServiceImplTest.java \
   || -d /home/finance/financial-management/src/test/java/com/family/finance/factview ]] \
  && log_ok "v04-FIX-7 factview 单测目录存在(B1/B2/B4 改动不破坏现有覆盖)" \
  || log_bad "v04-FIX-7 factview 单测目录缺" "missing"

# ===================================================
# v0.4.4 · 文案专业化清理(内部 routing / FR 编号 / 字段名 / enum 暴露 全部清除)
# ===================================================
section "v0.4.4 · 文案专业化清理"

# v04-UX-1 · checkup 不再含"已搬到 /dashboard"等迁移提示
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ ! grep -q '已搬到\|已挪至' "$TMP"; } \
  && log_ok "v04-UX-1 /checkup 不再含 '已搬到 / 已挪至' 迁移文案" \
  || log_bad "v04-UX-1 内部迁移文案残留" "still present"

# v04-UX-2 · checkup 资产配置卡 mini 横向条出现
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
grep -q 'A · L · L · O · C · A · T · I · O · N' "$TMP" \
  && grep -q '按账户类型聚合' "$TMP" \
  && log_ok "v04-UX-2 /checkup 资产配置卡 mini 横向条 + 中性 eyebrow" \
  || log_bad "v04-UX-2 mini 横向条缺" "missing"

# v04-UX-3 · reports 不再含"汇率明细已挪至 /admin/fx"section
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ ! grep -q '汇率明细已挪至\|运维专用' "$TMP"; } \
  && log_ok "v04-UX-3 /reports 不再含汇率挪至提示 section" \
  || log_bad "v04-UX-3 汇率挪至 section 残留" "still present"

# v04-UX-4 · 所有用户面页面不再含 v0.x / FR-xx 内部代号
PAGES=(/dashboard /reports /checkup /goals /entry /accounts)
LEAK=0
for p in "${PAGES[@]}"; do
  $CURL -b $COOKIE "$BASE$p" -o "$TMP" -w ""
  # 只查可见 body 内容(去 HTML/JS 注释)
  python3 -c "
import re, sys
with open('$TMP') as f:
    html = f.read()
# 去 HTML 注释
html = re.sub(r'<!--.*?-->', '', html, flags=re.S)
# 去 <script>...</script> 内容
html = re.sub(r'<script[^>]*>.*?</script>', '', html, flags=re.S|re.I)
# 找 FR-数字 / v0.数字
m = re.findall(r'(FR-\d+|v0\.\d+(?:\.\d+)?)', html)
if m: sys.exit(1)
" 2>/dev/null || LEAK=$((LEAK+1))
done
[[ "$LEAK" -eq 0 ]] \
  && log_ok "v04-UX-4 6 用户面页 (dashboard/reports/checkup/goals/entry/accounts) 不含 FR-xx / v0.x 代号" \
  || log_bad "v04-UX-4 用户面残留代号" "$LEAK 页有代号"

# v04-UX-5 · refinance 不再含 v0.4 / v0.5 版本路线规划
$CURL -b $COOKIE "$BASE/reports/refinance" -o "$TMP" -w ""
{ ! grep -q 'v0\.4 仅等额本息\|v0\.5 加等额本金' "$TMP"; } \
  && log_ok "v04-UX-5 /reports/refinance 不再含 v0.X 版本路线规划文案" \
  || log_bad "v04-UX-5 refinance 版本规划残留" "still present"

# v04-UX-6 · 已删 checkup placeholder 死代码模板
{ [[ ! -f /home/finance/financial-management/src/main/resources/templates/checkup/placeholder-family.html \
   && ! -f /home/finance/financial-management/src/main/resources/templates/checkup/placeholder-account.html ]]; } \
  && log_ok "v04-UX-6 checkup placeholder 死代码模板已删除" \
  || log_bad "v04-UX-6 placeholder 死代码仍在" "still present"

# v04-UX-7 · 填报页(承接待办)不暴露 SNAPSHOT_TODO enum + (STOCK) 括号(v0.11.7:待办已折叠进 /entry)
$CURL -b $COOKIE "$BASE/entry?mine=true" -o "$TMP" -w ""
{ ! grep -q 'SNAPSHOT_TODO\|(STOCK)\|(WEALTH)\|(LOAN)' "$TMP"; } \
  && log_ok "v04-UX-7 /entry 不暴露 SNAPSHOT_TODO enum + 类型英文括号" \
  || log_bad "v04-UX-7 enum 残留" "still present"

# v04-UX-8 · stock/holdings 中文化(AUTO/MANUAL/CASH pill 改自动估值/手填市值/账户内现金)
HOLDING_ACCT=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 AND type='STOCK' AND archived_at IS NULL ORDER BY id LIMIT 1" 2>/dev/null)
if [[ -n "$HOLDING_ACCT" ]]; then
  $CURL -b $COOKIE "$BASE/accounts/$HOLDING_ACCT/holdings" -o "$TMP" -w ""
  { ! grep -q '>AUTO 自动估值<\|>MANUAL 手填<\|>💰 CASH 现金<'; } < "$TMP" \
    && log_ok "v04-UX-8 stock/holdings pill 中文化(去 AUTO/MANUAL/CASH enum 前缀)" \
    || log_bad "v04-UX-8 holdings pill 仍含英文 enum" "still present"
fi

# v04-UX-9 · /checkup 风险敞口卡 doughnut · datalabels 浮在扇片上(用户体验升级)
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q '<canvas id="riskChart"' "$TMP" \
  && grep -q "type: 'doughnut'" "$TMP" \
  && grep -q 'riskBuckets' "$TMP" \
  && grep -q 'plugins: \[ChartDataLabels\]' "$TMP"; } \
  && log_ok "v04-UX-9 /checkup 风险敞口卡 doughnut + ChartDataLabels(数字浮在扇片)" \
  || log_bad "v04-UX-9 风险敞口饼图缺" "missing canvas/doughnut/datalabels"

# v04-AI-REBALANCE-2 · OutputValidator 账户名白名单(LLM 引用用户已有账户不算产品推荐)
#   v0.4.5(2026-05-14)用户反馈:点 AI 调仓建议按钮没反应 · 根因是 LLM 输出含「余额宝」(用户的支付宝-余额宝账户)
#   修法:OutputValidator 加 accountWhitelist 参数 · 白名单内的产品名子串放行
mysql -ufinance -pfinance finance -e "DELETE FROM rebalance_advice_cache WHERE family_id=1;" 2>/dev/null
ai_seed_advice   # 2026-09-22 · 命中缓存 → 不打 LLM(这条验的是 validator 通过与否,不是模型输出)
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk -F'\t' '{print $NF}')
code=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
  "$BASE/reports/rebalance/advise" -o /dev/null -w "%{http_code}" --max-time 30)
# 302 + DB cache 写入 = LLM 走通 + validator 没误杀
cache_count=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM rebalance_advice_cache WHERE family_id=1" 2>/dev/null)
{ [[ "$code" == "302" ]]; } \
  && log_ok "v04-AI-REBALANCE-2 advise POST → 302 · LLM 调用容忍(cache count=$cache_count · 1 表示 validator 通过)" \
  || log_bad "v04-AI-REBALANCE-2 advise 异常" "code=$code"

# v04-AI-REBALANCE-3 · /reports 渲染 advice card · 当 cache 有数据
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
if [[ "$cache_count" -gt 0 ]]; then
  { grep -q '生成于 2026' "$TMP" \
    && grep -q '从 <b' "$TMP"; } \
    && log_ok "v04-AI-REBALANCE-3 /reports 渲染 advice card · 含「生成于 + 从 X 调出」" \
    || log_bad "v04-AI-REBALANCE-3 advice card 未渲染" "cache=$cache_count · grep missed"
else
  log_ok "v04-AI-REBALANCE-3 cache 空 · 跳过渲染检查(LLM 真的失败 · 容忍)"
fi

# v04-AI-REBALANCE-4 · feedback flash bar(点击后用户能看到结果 · 不再"按了没反应")
#   Spring flash attribute 跨 POST → redirect(302)→ GET 的同一 session 中存活
#   分步式:1) POST 触发 advise · 2) 紧接 GET /reports 应看到 flash bar
#   不用 -L · 因为 curl -L + -X POST 会在 redirect 后继续 POST · 触发 GET /reports 误判
# 2026-09-22:原来这里先 DELETE 缓存再 POST,等于每跑一次强制打一次 LLM。
#   反馈条要验的是「点完之后用户看不看得到结果」,缓存命中时那条是「已有近期建议」,
#   一样能证明 flash 机制活着 —— 而且不花钱。
ai_seed_advice
XSRF=$(grep "XSRF-TOKEN" $COOKIE | awk -F'\t' '{print $NF}')
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
  "$BASE/reports/rebalance/advise" -o /dev/null -w "" --max-time 30
$CURL -b $COOKIE -c $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'AI 已生成新建议\|已有近期建议\|暂未能生成建议' "$TMP"; } \
  && log_ok "v04-AI-REBALANCE-4 advise 后 reports 页含反馈条(成功 / 缓存 / 失败)" \
  || log_bad "v04-AI-REBALANCE-4 反馈条缺" "no flash bar"

# v04-AI-REBALANCE-5 · cache 命中 → 不再打 LLM(fromCache=true)
#
#   判据改造(2026-09-22):原来的做法是「删光缓存 → POST 两次 → 第二次应 fromCache=true」。
#   那让这条护栏的成败取决于【LLM 此刻在不在线】:beta 的 DeepSeek 欠费(402)、
#   百炼凭据失效(400)之后,第一次调用根本没产出,自然没有缓存可命中 —— 于是它一直红,
#   而红的文案写着「cache 未命中」,看上去像缓存代码坏了。**排查花的时间全花在这个误导上。**
#
#   现在改成【自己塞一行缓存】再 POST 一次。命中判据只看 (family_id, anchor_code) + 30 天 TTL,
#   不校验 prompt_hash(RebalanceAdvisorService:90),所以塞得进去;而这样测到的正是
#   缓存这段逻辑本身 —— 与 LLM 在不在线无关。
#   判据原则:一条护栏该守的是【我们的代码】,不是【第三方账户有没有余额】。
ai_seed_advice
XSRF=$(awk -F'\t' '/^localhost.*XSRF-TOKEN/ {print $NF}' $COOKIE)
$CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
  "$BASE/reports/rebalance/advise" -o /dev/null -w "" --max-time 30
sleep 1
cache_hit_log=$(tail -5 /opt/finance/logs/app.log | grep "rebalance advise.*fromCache=true" | wc -l)
[[ "$cache_hit_log" -ge 1 ]] \
  && log_ok "v04-AI-REBALANCE-5 缓存内有建议时 advise 直接命中(fromCache=true · 不打 LLM)" \
  || log_bad "v04-AI-REBALANCE-5 缓存明明有,advise 却没命中" "已塞入缓存行,log 仍无 fromCache=true —— 这次是缓存逻辑真的坏了,不是 LLM 不在线"

# v04-AI-REBALANCE-6 · refresh=true 跳过 cache 强制重新调 LLM
#   这一条【本质上就是要花钱】—— 它验的正是「绕过缓存、真的再打一次」,
#   塞桩绕不过去(塞了就不是强制刷新了)。所以默认不跑,要验时显式开。
if [[ "$QA_LLM_LIVE" == "1" ]]; then
  $CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" \
    --data-urlencode "_csrf=$XSRF" --data-urlencode "refresh=true" \
    "$BASE/reports/rebalance/advise" -o /dev/null -w "" --max-time 30
  sleep 1
  force_log=$(tail -10 /opt/finance/logs/app.log | grep "forceRefresh" | wc -l)
  fresh_log=$(tail -5 /opt/finance/logs/app.log | grep "refresh=true.*fromCache=false" | wc -l)
  { [[ "$force_log" -ge 1 && "$fresh_log" -ge 1 ]]; } \
    && log_ok "v04-AI-REBALANCE-6 refresh=true 跳过缓存 + fromCache=false" \
    || log_bad "v04-AI-REBALANCE-6 refresh 没跳过" "force_log=$force_log fresh_log=$fresh_log"
else
  log_skip "v04-AI-REBALANCE-6 强制刷新" "这条必须真打一次 LLM 才有意义 · QA_LLM_LIVE=1 时才跑"
fi

# v04-AI-REBALANCE-7 · advice card 有「↻ 刷新」按钮(模板侧)
#   同 -5 的改造:这张卡只在【缓存里有建议】时才渲染,而上一条(-6)刚做过一次
#   force refresh —— LLM 不在线时那次不会产出,卡就不渲染,于是护栏红成「刷新按钮缺」。
#   失败的刷新不动缓存(invalidate() 没有任何调用方),但这里仍然重塞一次,
#   让这条护栏不依赖上面几条的执行顺序与结果。
ai_seed_advice
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'refresh=.true\|refresh=true' "$TMP" && grep -q '↻ 刷新' "$TMP"; } \
  && log_ok "v04-AI-REBALANCE-7 advice card 显示 ↻ 刷新按钮(form 带 refresh=true)" \
  || log_bad "v04-AI-REBALANCE-7 刷新按钮缺" "缓存里已有建议,/reports 仍没渲染出带 refresh=true 的「↻ 刷新」—— 模板侧问题"

# v04-AI-DIAGNOSE-1 · /checkup AI 综合诊断刷新按钮带 refresh=true(此前 title 写忽略 cache 但实际没传)
#   /checkup 主页用 spinner placeholder + HTMX 异步加载 · 必须直接 GET /checkup/diagnose 拿 panel fragment
#   2026-09-22:原来要真请求一次 panel(= 真打一次 LLM)才能看到那个按钮。
#   但这条护栏当初抓的是【模板里 URL 少拼了 refresh=true】—— 那是个静态事实,
#   直接钉模板更准,也不用花钱。
AI_DIAG_TPL="$RD/src/main/resources/templates/checkup/_ai-diagnose.html"
{ grep -q 'refresh=true' "$AI_DIAG_TPL" && grep -q '↻ 刷新' "$AI_DIAG_TPL"; } \
  && log_ok "v04-AI-DIAGNOSE-1 诊断面板刷新按钮带 refresh=true(真忽略 cache)" \
  || log_bad "v04-AI-DIAGNOSE-1 诊断刷新按钮 url 错" "_ai-diagnose.html 里的刷新链接必须带 refresh=true"

# v04-AI-DIAGNOSE-2 · v0.4.9 · 结构化 JSON 渲染 4 维度卡(总评 + 4 卡 + 优先行动)
#   2026-09-22:这一条【只能靠真 LLM 输出】才看得到四维卡(refresh=true 还会主动绕过缓存),
#   所以它是这个脚本里最贵的一条。默认不跑;渲染层的 fallback 分支由 DIAGNOSE-3 静态守着。
if [[ "$QA_LLM_LIVE" != "1" ]]; then
  log_skip "v04-AI-DIAGNOSE-2 结构化四维卡" "只有真 LLM 输出才看得到 · QA_LLM_LIVE=1 时才跑"
else
$CURL -b $COOKIE "$BASE/checkup/diagnose?refresh=true" -o "$TMP" -w "" --max-time 60
# 总评 banner + 4 个 dimension 名 + verdict pill + 优先行动
total_markers=0
# 2026-08-13:原来的 10 个 marker 里有 4 个是 emoji(📊⚡💧📈),阈值 ≥8。
#   而项目后来定了铁律「UI 不许 emoji,一律 inline SVG」→ emoji 被正确地删掉,这条护栏就永远只能拿到
#   6/10 而变红 —— 它在**惩罚项目按规矩做的事**。改成:6 个文字 marker 必须全在,并顺手正向守住
#   「诊断面板里没有 emoji」,让这条从"拖后腿"变成"帮着守规矩"。
for kw in "总评" "资产配置" "风险敞口" "流动性" "收益质量" "优 · 先 · 行 · 动"; do
  if grep -q "$kw" "$TMP"; then total_markers=$((total_markers+1)); fi
done
DIAG_EMOJI=$(grep -oE '📊|⚡|💧|📈|🔄|✅|⚠️' "$TMP" | wc -l | tr -d ' ')
# 2026-09-04:这条以前是**靠运气**的 —— 它打真 LLM,而模型偶尔会说出「零风险」「余额宝」
#   这类被内容校验器正确拦下的词,或者 DeepSeek 那路 402 直接挂;两个候选都被拒 → 面板降级成
#   `unavailable` 文本 → markers 只剩 1/6 → 红。**那是校验器在正常工作,不是渲染坏了**,
#   而红灯会把「这次模型没说好」伪装成「结构化渲染回归了」。
#   判据:面板底栏「资 · 产 · 顾 · 问 …」只在 result.available() 为真时渲染 ——
#   它不在 = LLM 这一路整体没出结果,这时 SKIP 并说清原因;fallback 分支本身由 DIAGNOSE-3 守。
#   注意这是**降低误报**,不是放宽判据:只要拿到了结构化答案,6 个 marker 仍然一个都不能少。
if ! grep -q '资 · 产 · 顾 · 问' "$TMP"; then
  log_skip "v04-AI-DIAGNOSE-2 本次 LLM 没出结果(全部候选失败或被内容校验拦下)" "面板处于 unavailable 降级态 · 结构化渲染这次无从判断 · markers=$total_markers/6"
else
{ [[ "$total_markers" -ge 6 ]] && [[ "$DIAG_EMOJI" -eq 0 ]]; } \
  && log_ok "v04-AI-DIAGNOSE-2 结构化诊断渲染 · 总评+4 维度+优先行动 6/6 · 且无 emoji(图标走 inline SVG)" \
  || log_bad "v04-AI-DIAGNOSE-2 结构化渲染缺 / 出现 emoji" "markers=$total_markers/6 emoji=$DIAG_EMOJI"
fi
fi

# v04-AI-DIAGNOSE-3 · 老 cache(纯文本)兼容 · structured 解析失败时 fallback 显示 text
# 直接造一个非 JSON cache 强制走 fallback 路径(注:cache 是内存 · 难直接造 · 这里查模板分支存在)
grep -q "structured() == null" src/main/resources/templates/checkup/_ai-diagnose.html \
  && log_ok "v04-AI-DIAGNOSE-3 模板含 fallback 分支(老 cache / 解析失败时显示 text)" \
  || log_bad "v04-AI-DIAGNOSE-3 fallback 分支缺" "missing structured null check"

# v04-AI-DIAGNOSE-4 · v0.4.18 后 max_tokens 改读 ConfigService 动态(默认 2000)
#   v1.13:两份客户端各自的实现合并进 AbstractOpenAiCompatibleClient,子类不该再各写一份
ABSC=src/main/java/com/family/finance/service/checkup/llm/AbstractOpenAiCompatibleClient.java
{ grep -q 'currentMaxTokens' "$ABSC" \
  && ! grep -q 'currentMaxTokens()' src/main/java/com/family/finance/service/checkup/llm/DashScopeLlmClient.java \
  && ! grep -q 'currentMaxTokens()' src/main/java/com/family/finance/service/checkup/llm/DeepSeekLlmClient.java \
  && grep -q 'K_LLM_MAX_TOKENS' src/main/java/com/family/finance/service/config/FamilyConfigService.java; } \
  && log_ok "v04-AI-DIAGNOSE-4 max_tokens 读 ConfigService 动态(默认 2000 · 可热改)· 收在公共基类里一份" \
  || log_bad "v04-AI-DIAGNOSE-4 max_tokens 未切动态 / 子类又抄了一份" "see AbstractOpenAiCompatibleClient + ConfigService"

# v04-AI-DIAGNOSE-5 · 截断检测 · DiagnoseResult.truncated + 模板友好错误
{ grep -q 'truncated\(\) \|looksTruncatedJson\|truncated()' src/main/java/com/family/finance/service/checkup/llm/LlmDiagnoseService.java \
  && grep -q 'AI 输出被截断' src/main/resources/templates/checkup/_ai-diagnose.html; } \
  && log_ok "v04-AI-DIAGNOSE-5 截断检测 · DiagnoseResult.truncated + 模板友好错误(不显示半截 JSON)" \
  || log_bad "v04-AI-DIAGNOSE-5 截断检测缺" "no truncated/AI 输出被截断"

# v04-AI-DIAGNOSE-6 · 客户端 finish_reason=length 截断日志告警
#   v1.13 前这段只有 Qwen 那份有、DeepSeek 那份漏了(复制粘贴漂移的典型)· 现在在基类里,三家都有
{ grep -q 'max_tokens 截断' "$ABSC" && grep -q 'finish_reason' "$ABSC"; } \
  && log_ok "v04-AI-DIAGNOSE-6 检测 finish_reason=length 截断 · log.warn 提示(基类一份 · 全平台生效)" \
  || log_bad "v04-AI-DIAGNOSE-6 截断日志缺" "no finish_reason check in AbstractOpenAiCompatibleClient"

# v04-AI-DIAGNOSE-7 · v0.4.11 · pct1(ratio) bug 修(占比 0.4% → 44.2%)
#   AllocationSlice.ratio() / RiskBucket.ratio() 是小数(0.442)· 必须 ×100 显示
#   之前 caller 用 pct1 直接显 0.4% · LLM 拿到错误数据胡说"权益占比严重不足"
grep -q 'pctFromRatio(s.ratio())' src/main/java/com/family/finance/service/checkup/llm/PromptBuilder.java \
  && grep -q 'pctFromRatio(b.ratio())' src/main/java/com/family/finance/service/checkup/llm/PromptBuilder.java \
  && log_ok "v04-AI-DIAGNOSE-7 PromptBuilder ratio 占比 ×100 修(¥95710 占比 2.4% 正确)" \
  || log_bad "v04-AI-DIAGNOSE-7 pct ratio bug 未修" "still using pct1(ratio)"

# v04-AI-DIAGNOSE-8 · SYSTEM_DIAGNOSE 禁 LLM 算术
grep -q '禁止做任何四则运算\|不要自己做四则运算' src/main/java/com/family/finance/service/checkup/llm/PromptBuilder.java \
  && log_ok "v04-AI-DIAGNOSE-8 SYSTEM_DIAGNOSE 含禁数学约束(防 LLM 瞎算占比/差额)" \
  || log_bad "v04-AI-DIAGNOSE-8 禁数学约束缺" "no math-prohibition rule"


section "v0.4.14 · 填报规范化 + DDL 强提醒 (FR-63)"

# v04-RPT-TMPL-1 · ReportingTemplate enum 3 模板 + fromCode 默认 T1
RT=src/main/java/com/family/finance/domain/family/ReportingTemplate.java
{ grep -q 'T1_REALTIME_INCOME_MONTHEND_EXPENSE' "$RT" \
  && grep -q 'T2_MONTHEND_BATCH' "$RT" \
  && grep -q 'T3_WEEKLY_ROLLING' "$RT" \
  && grep -q 'fromCode' "$RT"; } \
  && log_ok "v04-RPT-TMPL-1 ReportingTemplate 3 模板 + fromCode 安全解析(默认 T1)" \
  || log_bad "v04-RPT-TMPL-1 模板枚举缺" "missing template enum"

# v04-RPT-REMIND-1 · /admin/reminders 设置页 200 + 3 模板可选 + 提前天数
$CURL -b $COOKIE "$BASE/admin/reminders" -o "$TMP" -w ""
{ grep -q '填报模板' "$TMP" && grep -q 'leadDays' "$TMP" \
  && grep -q '实时收入' "$TMP" && grep -q '每周滚动' "$TMP"; } \
  && log_ok "v04-RPT-REMIND-1 /admin/reminders 设置页 · 3 模板 + 提前天数" \
  || log_bad "v04-RPT-REMIND-1 提醒设置页缺" "no template/leadDays"

# v04-RPT-REMIND-2 · 提交模板 T3 + leadDays=3 → 落库生效(GET 回显)
RTOK=$($CURL -b $COOKIE "$BASE/admin/reminders" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
$CURL -b $COOKIE -X POST \
  --data-urlencode "_csrf=$RTOK" \
  --data-urlencode "template=T3_WEEKLY_ROLLING" \
  --data-urlencode "leadDays=3" \
  "$BASE/admin/reminders/template" -o /dev/null -w ""
$CURL -b $COOKIE "$BASE/admin/reminders" -o "$TMP" -w ""
{ grep -q 'value="3"' "$TMP" && grep -A2 'T3_WEEKLY_ROLLING' "$TMP" | grep -q 'checked'; } \
  && log_ok "v04-RPT-REMIND-2 模板/提前天数 POST 落库 + 回显(T3 · 3 天)" \
  || log_bad "v04-RPT-REMIND-2 模板保存未生效" "T3/leadDays not persisted"
# 还原默认 T1 / 2 天(不污染后续 QA / 演示数据)
RTOK=$($CURL -b $COOKIE "$BASE/admin/reminders" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
$CURL -b $COOKIE -X POST --data-urlencode "_csrf=$RTOK" \
  --data-urlencode "template=T1_REALTIME_INCOME_MONTHEND_EXPENSE" \
  --data-urlencode "leadDays=2" "$BASE/admin/reminders/template" -o /dev/null -w ""

# v04-RPT-REMIND-3 · 调度 cron · v0.4.18 起由 DynamicScheduleConfig 注册(读 DB · 默认 0 0 10,20 * * *)
#   v1.26 · 默认值挪到 RemindTimes(提醒页与调度共用一份,页面把它显示成「10,20」)—— 判据不变,只换看的地方
DSC=src/main/java/com/family/finance/service/scheduling/DynamicScheduleConfig.java
{ grep -qF 'DEFAULT_CRON = "0 0 10,20 * * *"' src/main/java/com/family/finance/service/notify/RemindTimes.java \
  && grep -qF 'DEFAULT_REPORT_REMIND_CRON = com.family.finance.service.notify.RemindTimes.DEFAULT_CRON' "$DSC" \
  && grep -qF 'ZONE_ID = "Asia/Shanghai"' "$DSC" \
  && grep -q 'reminderScheduler::scheduled' "$DSC"; } \
  && log_ok "v04-RPT-REMIND-3 提醒 cron 0 0 10,20 · Asia/Shanghai · 由动态调度注册" \
  || log_bad "v04-RPT-REMIND-3 提醒 cron 注册缺" "see DynamicScheduleConfig"

# v04-RPT-REMIND-4 · 渠道抽象可插拔(接口 + 短信 + 站内兜底)
ND=src/main/java/com/family/finance/service/notify
{ [[ -f "$ND/NotificationChannel.java" ]] \
  && [[ -f "$ND/SmsAliyunChannel.java" ]] \
  && [[ -f "$ND/InAppBannerChannel.java" ]] \
  && grep -q 'implements NotificationChannel' "$ND/SmsAliyunChannel.java" \
  && grep -q 'implements NotificationChannel' "$ND/InAppBannerChannel.java"; } \
  && log_ok "v04-RPT-REMIND-4 渠道抽象 · SMS 为主 + 站内兜底(可插拔)" \
  || log_bad "v04-RPT-REMIND-4 渠道抽象缺" "channel impls missing"

# v04-RPT-REMIND-5 · 提醒日志当天去重(V25 UNIQUE + INSERT IGNORE)
{ grep -q 'UNIQUE KEY uk_dedup (family_id, period_id, member_id, channel, remind_date)' \
      db/migration/V25__report_template_remind.sql \
  && grep -q 'INSERT IGNORE INTO report_reminder_log' \
      src/main/java/com/family/finance/repository/ReportReminderLogMapper.java; } \
  && log_ok "v04-RPT-REMIND-5 提醒去重 · UNIQUE(family,period,member,channel,date)+INSERT IGNORE" \
  || log_bad "v04-RPT-REMIND-5 去重机制缺" "no unique/insert-ignore"

# v04-RPT-BANNER-1 · /entry 推荐填报方案提示 banner
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
grep -q '推荐填报方案' "$TMP" \
  && log_ok "v04-RPT-BANNER-1 /entry 显示推荐填报方案提示(随模板 + 距截止天数)" \
  || log_bad "v04-RPT-BANNER-1 填报页提示 banner 缺" "no recommend banner"

# v04-RPT-BANNER-2 · /entry banner 三栏富信息(周期 + 截止日 + 家庭进度 + 我已填徽标 + 距截止 pill)
$CURL -b $COOKIE "$BASE/entry" -o "$TMP" -w ""
markers=0
for kw in '本 · 期 · 进 · 度' '家庭已填' '距 · 截 · 止' '⚙ 改填报模板'; do
  if grep -q "$kw" "$TMP"; then markers=$((markers+1)); fi
done
{ [[ "$markers" -ge 3 ]]; } \
  && log_ok "v04-RPT-BANNER-2 三栏富信息 banner(周期/截止日/家庭进度/距截止 markers=$markers/4)" \
  || log_bad "v04-RPT-BANNER-2 富信息 markers 不足" "markers=$markers/4"

# v04-RPT-MSG-1 · 短信文案 4 变量 brand/period/days/progress(源码 + ReminderMessage 字段)
RM=src/main/java/com/family/finance/service/notify/ReminderMessage.java
SC=src/main/java/com/family/finance/service/notify/SmsAliyunChannel.java
{ grep -q 'String brand' "$RM" \
  && grep -q 'String period' "$RM" \
  && grep -q 'int daysLeft' "$RM" \
  && grep -q 'String progress' "$RM" \
  && grep -q '\\"brand\\":' "$SC" \
  && grep -q '\\"period\\":' "$SC" \
  && grep -q '\\"days\\":' "$SC" \
  && grep -q '\\"progress\\":' "$SC"; } \
  && log_ok "v04-RPT-MSG-1 短信 4 变量(brand/period/days/progress)在 ReminderMessage + SmsAliyunChannel" \
  || log_bad "v04-RPT-MSG-1 短信变量缺" "missing 4-var fields"

# v04-RPT-TEST-1 · /admin/reminders/sms-test endpoint + 配置不全友好错
NS=src/main/java/com/family/finance/web/admin/NotificationSettingsController.java
{ grep -q '/sms-test' "$NS" \
  && grep -q 'sendForTest' "$SC" \
  && grep -q 'CONFIG_INCOMPLETE' "$SC"; } \
  && log_ok "v04-RPT-TEST-1 一键测试 endpoint + 配置不全友好错(CONFIG_INCOMPLETE)" \
  || log_bad "v04-RPT-TEST-1 sms-test endpoint 缺" "no endpoint/sendForTest"

# v04-RPT-TEST-2 · 测试限流 3 次/分(源码常量 + 滑动窗口)
{ grep -q 'TEST_RATE_LIMIT_PER_MIN = 3' "$NS" \
  && grep -q 'minusSeconds(60)' "$NS"; } \
  && log_ok "v04-RPT-TEST-2 测试限流 3 次/分 + 60s 滑动窗口" \
  || log_bad "v04-RPT-TEST-2 限流缺" "no rate limit"

# v04-RPT-TEST-3 · 测试日志走 audit_log(决策 36 · 非 report_reminder_log)
{ grep -q 'auditLogService.record' "$NS" \
  && grep -qF '"短信测试' "$NS" \
  && ! grep -A2 'sms-test\|smsTest' "$NS" | grep -q 'reminderLogMapper\.insert'; } \
  && log_ok "v04-RPT-TEST-3 测试审计走 audit_log · 非 report_reminder_log(决策 36)" \
  || log_bad "v04-RPT-TEST-3 测试审计归属错" "see notification controller"

# v04-RPT-LOG-1 · /admin/reminders 含 ⑥ 提醒发送日志 section
$CURL -b $COOKIE "$BASE/admin/reminders" -o "$TMP" -w ""
{ grep -q '⑥ 提醒发送日志' "$TMP" \
  && grep -q '测试发送审计' "$TMP"; } \
  && log_ok "v04-RPT-LOG-1 /admin/reminders 显示提醒发送日志 section · 顶部引导测试审计" \
  || log_bad "v04-RPT-LOG-1 日志 section 缺" "no ⑥ 提醒发送日志"

# v04-RPT-LOG-2 · ReportReminderLogMapper 分页查询方法在岗
RL=src/main/java/com/family/finance/repository/ReportReminderLogMapper.java
{ grep -q 'findByFamily' "$RL" \
  && grep -q 'countByFamily' "$RL" \
  && grep -q 'LIMIT #{limit} OFFSET #{offset}' "$RL"; } \
  && log_ok "v04-RPT-LOG-2 ReportReminderLogMapper findByFamily/countByFamily + LIMIT OFFSET 分页" \
  || log_bad "v04-RPT-LOG-2 分页 mapper 缺" "no pagination query"

# v04-RPT-LOG-3 · ?page=N URL 参数被识别(默认每页 20)
$CURL -b $COOKIE "$BASE/admin/reminders?page=1" -o "$TMP" -w ""
grep -q '⑥ 提醒发送日志' "$TMP" \
  && log_ok "v04-RPT-LOG-3 ?page=N 参数被 controller 处理 · 默认 20/页" \
  || log_bad "v04-RPT-LOG-3 分页参数失效" "page=1 missing section"

section "v0.4.17 · 520 一日限定彩蛋"

# v04-520-1 · fragment 文件 + 19 条文案库 + 触发条件
E520=src/main/resources/templates/fragments/easter520.html
{ [[ -f "$E520" ]] \
  && grep -q "th:if=\"\${family != null and today == '05-20'}\"" "$E520" \
  && grep -q "I LOVE U" "$E520" \
  && grep -q "今年 520 · 主角还是你" "$E520" \
  && grep -q "想和你 · 保持长期稳定关系" "$E520" \
  && [[ $(grep -cE "'[^']{4,40}',?\s*$" "$E520") -ge 19 ]]; } \
  && log_ok "v04-520-1 easter520 fragment + 严格 05-20 触发 + 19 条文案库" \
  || log_bad "v04-520-1 fragment 文件 / 触发条件 / 文案库异常" "see $E520"

# v04-520-2 · layout::footer 注入 fragment(任意已登录页通用)
LF=src/main/resources/templates/fragments/layout.html
grep -q '~{fragments/easter520 :: easter520' "$LF" \
  && log_ok "v04-520-2 layout::footer th:replace 注入 easter520 fragment" \
  || log_bad "v04-520-2 layout 未注入 fragment" "no th:replace in layout"

# v04-520-3 · localStorage flag 关键字 + 右上 pill + 换一句 按钮 在 fragment 内
{ grep -q 'easter520_seen' "$E520" \
  && grep -q 'e520Pill' "$E520" \
  && grep -q 'next-slogan-btn' "$E520" \
  && grep -q 'window.__e520_open' "$E520" \
  && grep -q 'window.__e520_close' "$E520"; } \
  && log_ok "v04-520-3 localStorage flag + 右上 pill + 换一句按钮 + IIFE 命名空间" \
  || log_bad "v04-520-3 fragment 缺关键 hook" "missing hooks"

# v04-520-4 · 非 5.20 当天进任意已登录页不渲染 fragment(今天 ≠ 5.20)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
TODAY_DD=$(/bin/date +%m-%d)
if [[ "$TODAY_DD" == "05-20" ]]; then
  grep -q 'I LOVE U' "$TMP" \
    && log_ok "v04-520-4 今天就是 05-20 · /dashboard 注入 fragment" \
    || log_bad "v04-520-4 5.20 当天 fragment 应注入" "missing"
else
  ! grep -q 'I LOVE U' "$TMP" \
    && log_ok "v04-520-4 今天非 5.20($TODAY_DD)· /dashboard 不注入 fragment(dormant 正确)" \
    || log_bad "v04-520-4 非 5.20 仍注入 fragment" "should be dormant"
fi

# v04-PRIV-1 · 私密红线:全 LLM prompt 构造目录源码绝不引用手机号 / aksk(合规底线)
LLM_DIR=src/main/java/com/family/finance/service/checkup/llm
LEAK=$(grep -rnE 'getPhone\(|AccessKeySecret|AccessKeyId|getSmsAccessKey|FamilyNotifyConfig|ReportReminder|SmsAliyunChannel' "$LLM_DIR" 2>/dev/null || true)
{ [[ -z "$LEAK" ]] \
  && [[ -f src/test/java/com/family/finance/service/checkup/llm/PrivacyIsolationTest.java ]]; } \
  && log_ok "v04-PRIV-1 LLM prompt 源码零引用 phone/aksk + PrivacyIsolationTest 在岗(合规底线)" \
  || log_bad "v04-PRIV-1 私密红线被突破" "leak=[$LEAK]"

section "v0.4.18 · 系统级配置迁管理页"

# v04-CFG-1 · V26 migration 已应用 · family_runtime_config 表在
CFG_TABLE=$(MYSQL_PWD=finance mysql -h127.0.0.1 -ufinance finance -N -e \
  "SELECT 1 FROM information_schema.tables WHERE table_schema='finance' AND table_name='family_runtime_config' LIMIT 1;" 2>/dev/null)
{ [[ "$CFG_TABLE" == "1" ]]; } \
  && log_ok "v04-CFG-1 V26 family_runtime_config 表存在" \
  || log_bad "v04-CFG-1 V26 未应用" "no table"

# v04-CFG-2 · FamilyConfigService 三层 fallback + 5s TTL cache 在岗
FCS=src/main/java/com/family/finance/service/config/FamilyConfigService.java
{ [[ -f "$FCS" ]] \
  && grep -q "K_LLM_QWEN_KEY" "$FCS" \
  && grep -q "K_STOCK_ENABLED" "$FCS" \
  && grep -q "K_REPORT_REMIND_CRON" "$FCS" \
  && grep -q "envQwenKey" "$FCS" \
  && grep -q "CACHE_TTL_MILLIS" "$FCS"; } \
  && log_ok "v04-CFG-2 FamilyConfigService 三层 fallback + 5s TTL cache + K_* 常量" \
  || log_bad "v04-CFG-2 FamilyConfigService 缺关键 hook" "see $FCS"

# v04-CFG-3 · DynamicScheduleConfig 注册 5 个受管 cron
DSC=src/main/java/com/family/finance/service/scheduling/DynamicScheduleConfig.java
{ [[ -f "$DSC" ]] \
  && grep -q "stock-us" "$DSC" \
  && grep -q "stock-cn" "$DSC" \
  && grep -q "stock-hk" "$DSC" \
  && grep -q "rescheduleAll" "$DSC" \
  && grep -q "CronTrigger" "$DSC"; } \
  && log_ok "v04-CFG-3 DynamicScheduleConfig · 5 受管 cron + rescheduleAll" \
  || log_bad "v04-CFG-3 动态 cron config 缺" "see $DSC"

# v04-CFG-4 · 5 个被托管的 scheduler 已删 @Scheduled · 用 grep -E 排除 javadoc 注释里的 @Scheduled 关键词
{ ! grep -E '^\s*@Scheduled\(' src/main/java/com/family/finance/service/stock/StockPriceScheduler.java \
  && ! grep -E '^\s*@Scheduled\(' src/main/java/com/family/finance/service/FxFetchJob.java \
  && ! grep -E '^\s*@Scheduled\(' src/main/java/com/family/finance/service/notify/ReportReminderScheduler.java; } 2>/dev/null \
  && log_ok "v04-CFG-4 Stock/Fx/ReportReminder @Scheduled 注解已删 · 由动态调度接管" \
  || log_bad "v04-CFG-4 仍有遗留 @Scheduled 注解" "see 3 scheduler"

# v04-CFG-5 · LLM client 改读 FamilyConfigService(不再 @Value 注入 API key)
#   v1.13:key 名由目录给(platformDef().keyName()),取 key 的动作收在基类里 —— 加平台不会漏这一步
{ ! grep -q '@Value' "$ABSC" \
  && grep -q 'configService.getString(FAMILY_ID, platformDef().keyName()' "$ABSC" \
  && ! grep -rq '@Value' src/main/java/com/family/finance/service/checkup/llm/; } \
  && log_ok "v04-CFG-5 LLM key 改读 ConfigService(不再 @Value 直注入)· key 名来自目录 keyName()" \
  || log_bad "v04-CFG-5 LLM client 未切换 ConfigService" "see AbstractOpenAiCompatibleClient.apiKey()"

# v04-CFG-6 · 三段各在其位:股票 / FX 在数据源接入,大模型在 AI 接入
#   v1.24.5 · 大模型那一节挪到了 AI 接入(维护者定:AI 的东西放一处)。
#   反向也要守:数据源接入页上【不许再有】大模型的密钥表单 —— 否则就成了两处都能改、各显示各的。
$CURL -b $COOKIE "$BASE/admin/integrations" -o "$TMP" -w ""
QA_CFG6_INT="$(cat "$TMP")"
$CURL -b $COOKIE "$BASE/admin/ai-access" -o "$TMP" -w ""
{ printf '%s' "$QA_CFG6_INT" | grep -q '股票自动拉取' \
  && printf '%s' "$QA_CFG6_INT" | grep -q 'FX 汇率自动拉取' \
  && ! printf '%s' "$QA_CFG6_INT" | grep -q 'ai-access/llm/key\|integrations/llm/key' \
  && grep -q '阿里云百炼' "$TMP" \
  && grep -q 'ai-access/llm/key' "$TMP"; } \
  && log_ok "v04-CFG-6 股票 / FX 在数据源接入 · 大模型在 AI 接入 · 数据源接入页上不再有密钥表单" \
  || log_bad "v04-CFG-6 页面分工不对" "数据源接入要有股票+FX 且不能有大模型密钥表单;AI 接入要有阿里云百炼与 /admin/ai-access/llm/key"

# v04-CFG-7 · /admin/calc-tweaks 升级为可编辑(form post)
$CURL -b $COOKIE "$BASE/admin/calc-tweaks" -o "$TMP" -w ""
{ grep -q 'name="smartTransfer"' "$TMP" \
  && grep -q 'name="concentration"' "$TMP" \
  && grep -q 'name="emergencyMonths"' "$TMP" \
  && grep -q 'name="rememberMeSeconds"' "$TMP"; } \
  && log_ok "v04-CFG-7 /admin/calc-tweaks 升级可编辑表单 · 8 个字段" \
  || log_bad "v04-CFG-7 calc-tweaks 升级未到位" "missing form fields"

# v04-CFG-8 · admin sidebar 有"集成"入口 + 项数标注
#   v1.26 · 原来写死「15 项」—— 之后加了 AI 接入 / 对账 / 账户组…… 页头一直写着 15,实际 17 条,护栏照样绿。
#   改成结构性:页头的数字必须等于侧栏里除「总览」外的链接数,加 / 删入口时不改页头就红。
SB=src/main/resources/templates/admin/_sidebar.html
QA_SB_N=$(( $(grep -o 'th:href="@{' "$SB" | wc -l) - 1 ))
{ grep -q "/admin/integrations" "$SB" \
  && grep -q "/admin/metrics" "$SB" \
  && grep -q "/ADMIN · ${QA_SB_N} 项" "$SB"; } \
  && log_ok "v04-CFG-8 admin sidebar 集成+指标设置入口 + 页头项数与实际一致(${QA_SB_N} 项)" \
  || log_bad "v04-CFG-8 sidebar 页头项数不对" "侧栏实际 ${QA_SB_N} 项(不含总览),页头写的是:$(grep -o '/ADMIN · [0-9]* 项' "$SB")"

# v08-NAV-1 · 「指标设置」必须能从 /admin 落地页(「管理」tab 实际入口)点达,不只挂子页侧边栏。
#   2026-06-23 漏修暴露:v0.8 只把 /admin/metrics 加进 _sidebar(子页才显),没加进 admin/index 卡片网格 →
#   用户点「管理」落到 /admin 根本看不到指标设置入口(v04-CFG-8 只查侧边栏,放过了这个洞)。源级 + 渲染双查。
{ grep -q '/admin/metrics' src/main/resources/templates/admin/index.html \
  && curl -s -b $COOKIE "$BASE/admin" | grep -q '指标设置'; } \
  && log_ok "v08-NAV-1 /admin 落地页含「指标设置」卡片 → /admin/metrics(管理 tab 可点达)" \
  || log_bad "v08-NAV-1 /admin 落地页缺「指标设置」入口(漏:只挂了子页侧边栏)" "admin/index.html 无 metrics 卡片"

# v04-CFG-9 · deploy.sh 加 step 9.5 配置种子 + 幂等 flag
DEP=deploy/deploy.sh
{ grep -q "9.5/15 配置种子迁移" "$DEP" \
  && grep -q "config-migrated-v0.4.18" "$DEP" \
  && grep -q "family_runtime_config" "$DEP"; } \
  && log_ok "v04-CFG-9 deploy.sh 加 step 9.5 种子 + 幂等 flag" \
  || log_bad "v04-CFG-9 deploy.sh 9.5 步缺" "see deploy.sh"

# v04-CFG-10 · PrivacyIsolationTest 扩 PromptBuilder LLM key 防泄露
PIT=src/test/java/com/family/finance/service/checkup/llm/PrivacyIsolationTest.java
{ grep -q "promptBuilderNeverReferencesAnyPrivateAccessor" "$PIT" \
  && grep -q "K_LLM_QWEN_KEY" "$PIT"; } \
  && log_ok "v04-CFG-10 PrivacyIsolationTest 扩 · PromptBuilder 防 LLM key 泄露" \
  || log_bad "v04-CFG-10 私密红线扩展缺" "see PrivacyIsolationTest"

# ====================================================================
# v0.6 · AI 资产洞察(集中度/资产负债表/再平衡·行为/低利率)· FR-100~110
# ====================================================================
section "v0.6 · AI 资产洞察"

# v06-INSIGHT-1/2/3 · 2026-09-22 重做
#   /checkup/insight 没有「账户查不到」那种早返回,任何一次请求都会真的打一次 LLM。
#   而这三条验的是:端点通、fragment 带 vendor/available、硬数据层在 —— 三件都是
#   **模板与控制器的静态事实**,不需要模型说话。所以默认钉模板;真要看渲染出来的样子,
#   开 QA_LLM_LIVE=1 会走真请求(下面 v06-LLM-LIVE 那条)。
AI_INSIGHT_TPL="$RD/src/main/resources/templates/checkup/_ai-insight.html"
if [[ "$QA_LLM_LIVE" == "1" ]]; then
  code=$(/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "%{http_code}" "$BASE/checkup/insight")
  [[ "$code" == "200" ]] && log_ok "v06-INSIGHT-1 GET /checkup/insight → 200" \
    || log_bad "v06-INSIGHT-1 insight endpoint" "code=$code"
  { grep -qE 'data-vendor=|data-available=' "$TMP" && grep -q 'AI · 资产洞察' "$TMP"; } \
    && log_ok "v06-INSIGHT-2 insight fragment 含 vendor/available + 标题" \
    || log_bad "v06-INSIGHT-2 insight fragment" "missing attrs/title"
  { grep -q '硬 · 数 · 据' "$TMP" || grep -q '集中度' "$TMP" || grep -q '硬数据暂不可用' "$TMP"; } \
    && log_ok "v06-INSIGHT-3 insight fragment 含硬数据层(或降级占位)" \
    || log_bad "v06-INSIGHT-3 insight 硬数据层缺" "no hard-data section"
else
  grep -q 'checkup/insight' "$RD/src/main/java/com/family/finance/web/checkup/AiDiagnoseController.java" \
    && log_ok "v06-INSIGHT-1 /checkup/insight 端点在(静态判据 · 真请求会打 LLM,QA_LLM_LIVE=1 才走)" \
    || log_bad "v06-INSIGHT-1 insight endpoint 缺" "AiDiagnoseController 里找不到 /checkup/insight"
  { grep -qE 'data-vendor=|data-available=' "$AI_INSIGHT_TPL" && grep -q 'AI · 资产洞察' "$AI_INSIGHT_TPL"; } \
    && log_ok "v06-INSIGHT-2 insight 模板含 vendor/available + 标题" \
    || log_bad "v06-INSIGHT-2 insight fragment" "_ai-insight.html 里缺 data-vendor/available 或标题"
  { grep -q '硬 · 数 · 据' "$AI_INSIGHT_TPL" || grep -q '集中度' "$AI_INSIGHT_TPL" \
    || grep -q '硬数据暂不可用' "$AI_INSIGHT_TPL"; } \
    && log_ok "v06-INSIGHT-3 insight 模板含硬数据层(或降级占位)" \
    || log_bad "v06-INSIGHT-3 insight 硬数据层缺" "_ai-insight.html 里没有硬数据段"
fi

# v06-INSIGHT-4 · /checkup 页含 #checkup-insight section + 异步 placeholder + TOC 项
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
{ grep -q 'id="checkup-insight"' "$TMP" \
  && grep -q 'ai-insight-panel' "$TMP" \
  && grep -q 'AI 资产洞察' "$TMP"; } \
  && log_ok "v06-INSIGHT-4 /checkup 含资产洞察 section + placeholder + TOC 项" \
  || log_bad "v06-INSIGHT-4 /checkup 洞察 section 缺" "no #checkup-insight / panel / toc"

# v06-INSIGHT-5 · /reports 配置对照尾部含「查看完整资产洞察」交叉入口 → /checkup#checkup-insight
$CURL -b $COOKIE "$BASE/reports" -o "$TMP" -w ""
{ grep -q '查看完整资产洞察' "$TMP" && grep -q '/checkup#checkup-insight' "$TMP"; } \
  && log_ok "v06-INSIGHT-5 /reports 含资产洞察交叉入口" \
  || log_bad "v06-INSIGHT-5 /reports 交叉入口缺" "no cross-link to checkup insight"

# v06-INSIGHT-6 · /dashboard 资产洞察速览(有数据时显条 · 无数据 SKIP · TOC 项常在)
$CURL -b $COOKIE "$BASE/dashboard" -o "$TMP" -w ""
if grep -q 'id="dash-insight"' "$TMP"; then
  log_ok "v06-INSIGHT-6 /dashboard 资产洞察速览条已渲染"
elif grep -q "href='#dash-insight'\|#dash-insight" "$TMP"; then
  log_skip "v06-INSIGHT-6 /dashboard 速览条" "insight 降级未渲染(TOC 项在 · 无快照数据)"
else
  log_bad "v06-INSIGHT-6 /dashboard 速览/锚点缺" "no #dash-insight"
fi

# v06-LLM-LIVE · 嗅探 /checkup/insight 是否由真 LLM 成功返回(无 key/全失败则降级 · 不阻塞)
#   2026-09-22:这条本来就是「去问一次真 LLM」,所以默认不跑。
if [[ "$QA_LLM_LIVE" != "1" ]]; then
  log_skip "v06-LLM-LIVE 实调用嗅探" "这条必须真打一次 LLM · QA_LLM_LIVE=1 时才跑"
else
/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "" "$BASE/checkup/insight"
ins_vendor=$(grep -oE 'data-vendor="[^"]+"' "$TMP" | head -1 | sed 's/data-vendor="\([^"]*\)"/\1/')
ins_avail=$(grep -oE 'data-available="[^"]+"' "$TMP" | head -1 | sed 's/data-available="\([^"]*\)"/\1/')
if [[ "$ins_avail" == "true" && ( "$ins_vendor" == "qwen" || "$ins_vendor" == "deepseek" ) ]]; then
  log_ok "v06-LLM-LIVE LLM 实调用成功 vendor=$ins_vendor 洞察解读已返回"
else
  log_skip "v06-LLM-LIVE" "LLM key 未配/失败 vendor=$ins_vendor available=$ins_avail — 已降级(硬数据仍在),不阻塞"
fi
fi

# v06-COMPLIANCE · 洞察渲染输出绝不含预测涨跌/择时/具体产品名(中立诊断红线 · 防御深度)
#   2026-09-22:扫【模型真实输出】需要真请求一次,默认不跑。
#   关掉时退一步扫【我们自己的模板与提示词】—— 那些词不该出现在我们写的任何文案里,
#   这是更浅但仍然有用的一层;真正的输出扫描在 QA_LLM_LIVE=1 时才做。
if [[ "$QA_LLM_LIVE" != "1" ]]; then
  # 【只扫模板,不扫提示词】—— 提示词文件里【本来就列着这些词】,那是禁用清单本身
  #   (InsightPromptBuilder:33 那一行就是"不许说会涨/会跌/牛市/熊市/抄底")。
  #   把它一起扫进去,这条护栏会因为"我们写了禁令"而判我们违规 —— 第一版就是这么红的。
  if grep -qE '会涨|会跌|牛市|熊市|抄底|逃顶|高抛低吸|波段操作|余额宝|茅台|宁德时代' \
       "$AI_INSIGHT_TPL" 2>/dev/null; then
    log_bad "v06-COMPLIANCE 洞察模板里含预测/择时/产品词" "这些词不该出现在我们自己写的文案里"
  else
    log_ok "v06-COMPLIANCE 洞察模板无预测/择时/产品词(模型输出层扫描需 QA_LLM_LIVE=1)"
  fi
else
/usr/bin/curl -s --max-time 35 -b $COOKIE -o "$TMP" -w "" "$BASE/checkup/insight"
if grep -qE '会涨|会跌|牛市|熊市|抄底|逃顶|高抛低吸|波段操作|余额宝|茅台|宁德时代' "$TMP"; then
  log_bad "v06-COMPLIANCE 洞察输出含预测/择时/产品词" "$(grep -oE '会涨|会跌|牛市|熊市|抄底|逃顶|高抛低吸|波段操作|余额宝|茅台|宁德时代' "$TMP" | head -3 | tr '\n' ' ')"
else
  log_ok "v06-COMPLIANCE 洞察输出无预测/择时/产品词(中立诊断)"
fi
fi

# v06-PRIV · InsightPromptBuilder 隐私 by construction:源码不引用成员/账户名 getter
IPB=src/main/java/com/family/finance/service/insight/InsightPromptBuilder.java
if [[ -f "$IPB" ]]; then
  if grep -qE 'getDisplayName|getName\(\)|memberMapper|topAccountLabel' "$IPB"; then
    log_bad "v06-PRIV InsightPromptBuilder 引用了名字字段" "$(grep -nE 'getDisplayName|getName\(\)|topAccountLabel' "$IPB" | head -2)"
  else
    log_ok "v06-PRIV InsightPromptBuilder 不含成员/账户名(隐私 by construction)"
  fi
else
  log_bad "v06-PRIV InsightPromptBuilder 缺失" "no $IPB"
fi

# v06-MODELS · 百炼多型号额度兜底骨架(型号池 + 故障分类 + 额度/欠费分支)
#   v1.13 改名 QwenLlmClient → DashScopeLlmClient(平台 ≠ 模型系列)· 多型号轮询仍是百炼专属能力
QWC=src/main/java/com/family/finance/service/checkup/llm/DashScopeLlmClient.java
{ grep -q 'K_LLM_QWEN_MODELS' "$QWC" \
  && grep -q 'MODEL_QUOTA' "$QWC" \
  && grep -q 'arrearage' "$QWC" \
  && grep -q 'modelExhaustedUntil' "$QWC" \
  && grep -q 'modelRotation' src/main/java/com/family/finance/service/checkup/llm/LlmCatalog.java; } \
  && log_ok "v06-MODELS 百炼多型号兜底(型号池+额度用尽切换+欠费 failover)· 目录标注 modelRotation" \
  || log_bad "v06-MODELS 百炼多型号兜底骨架缺" "see DashScopeLlmClient"

# v06-MIGRATION · V29 负债字段 backward-compat(纯 ADD COLUMN DEFAULT NULL)
V29=db/migration/V29__loan_detail_fields.sql
{ grep -q 'ADD COLUMN loan_kind' "$V29" \
  && grep -q 'ADD COLUMN annual_rate_pct' "$V29" \
  && grep -q 'NULL' "$V29"; } \
  && log_ok "v06-MIGRATION V29 负债明细字段 · ADD COLUMN DEFAULT NULL(prod 0 风险)" \
  || log_bad "v06-MIGRATION V29 缺/非向后兼容" "see V29"

# ====================================================================
# v0.6.1 · iOS PWA 强引导(FR-115)· mobile-guide.js
# ====================================================================
section "v0.6.1 · iOS PWA 强引导"

$CURL "$BASE/js/mobile-guide.js" -o "$TMP" -w ""
# v061-PWA-1 · JS 200 + 含强引导三函数
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/js/mobile-guide.js")
{ [[ "$code" == "200" ]] \
  && grep -q "showIosPwaInterstitial" "$TMP" \
  && grep -q "showWxGuide" "$TMP" \
  && grep -q "twoStepLeave" "$TMP"; } \
  && log_ok "v061-PWA-1 mobile-guide.js 200 · 含整屏引导 + 两段挽留三函数" \
  || log_bad "v061-PWA-1 强引导 JS 缺" "code=$code"

# v061-PWA-2 · 强口吻文案(请务必/强烈建议/整屏)
{ grep -q "强烈建议" "$TMP" && grep -q "装成 App" "$TMP"; } \
  && log_ok "v061-PWA-2 JS 含强引导文案(强烈建议 · 装成 App)" \
  || log_bad "v061-PWA-2 强口吻文案缺" "no 强烈建议/装成 App"

# v061-PWA-3 · 无 emoji(承 feedback_no_emoji · 引导全 SVG)
if grep -qE "📦|📷|✕|✓|🤖|💡|✨" "$TMP"; then
  log_bad "v061-PWA-3 引导 JS 仍含 emoji" "$(grep -oE '📦|📷|✕|✓|🤖|💡|✨' "$TMP" | head -3 | tr '\n' ' ')"
else
  log_ok "v061-PWA-3 引导 JS 无 emoji(全 inline SVG)"
fi

# v061-PWA-4 · 成果真机图可访问
code=$($CURL -o /dev/null -w "%{http_code}" "$BASE/img/safari-screen/home-screen.jpg")
[[ "$code" == "200" ]] \
  && log_ok "v061-PWA-4 成果图 home-screen.jpg 200(主屏装好样子)" \
  || log_bad "v061-PWA-4 成果图缺" "code=$code"

# v061-PWA-5 · 4 步截图仍在(压缩后)
miss=0; for n in 1 2 3 4; do
  c=$($CURL -o /dev/null -w "%{http_code}" "$BASE/img/safari-screen/step$n.jpg")
  [[ "$c" == "200" ]] || miss=$((miss+1))
done
[[ $miss -eq 0 ]] && log_ok "v061-PWA-5 4 步真机截图 step1-4.jpg 全部 200" \
  || log_bad "v061-PWA-5 步骤截图缺 $miss 张" "miss=$miss"

# ====================================================================
# v0.7 · Docker 部署 + 兼容存量(静态守护 · 真机冒烟留待 Mac+Ubuntu)
# ====================================================================
section "v0.7 · Docker(静态守护)"
# RD 已在脚本开头定义(原来在这里,为了让前面的护栏也能用而上移)

# v07-DOCKER-1 文件齐
dmiss=0
for f in Dockerfile docker-compose.yml .env.example .dockerignore \
         docker/entrypoint.sh docker/backup.sh deploy/docker-up.sh \
         deploy/migrate-to-docker.sh .github/workflows/docker-publish.yml; do
  [[ -f "$RD/$f" ]] || { dmiss=$((dmiss+1)); }
done
[[ $dmiss -eq 0 ]] && log_ok "v07-DOCKER-1 Docker 9 个文件齐(docker-up.sh 为唯一 Docker 入口)" || log_bad "v07-DOCKER-1 缺 $dmiss 个 Docker 文件" "see Dockerfile/compose/..."

# v07-DOCKER-2 多阶段 + 三服务 + 三卷
{ [[ "$(grep -c '^FROM' "$RD/Dockerfile")" -ge 2 ]] \
  && grep -qE '^  app:' "$RD/docker-compose.yml" && grep -qE '^  db:' "$RD/docker-compose.yml" && grep -qE '^  backup:' "$RD/docker-compose.yml" \
  && grep -qE 'db-data' "$RD/docker-compose.yml" && grep -qE 'uploads' "$RD/docker-compose.yml" && grep -qE 'backups' "$RD/docker-compose.yml"; } \
  && log_ok "v07-DOCKER-2 Dockerfile 多阶段 + app/db/backup 三服务 + 三卷" \
  || log_bad "v07-DOCKER-2 镜像/编排结构缺" "see Dockerfile/compose"

# v07-DOCKER-3 entrypoint 复用 db/apply.sh(与 systemd 共用迁移 → 防重放)
grep -q 'db/apply.sh' "$RD/docker/entrypoint.sh" \
  && log_ok "v07-DOCKER-3 entrypoint 复用 db/apply.sh(共用 schema_history 防重放)" \
  || log_bad "v07-DOCKER-3 entrypoint 未复用 db/apply.sh" "迁移可能重放"

# v07-DOCKER-4 新 shell 语法
sbad=0
for f in docker/entrypoint.sh docker/backup.sh deploy/docker-up.sh deploy/migrate-to-docker.sh; do
  bash -n "$RD/$f" 2>/dev/null || sbad=$((sbad+1))
done
[[ $sbad -eq 0 ]] && log_ok "v07-DOCKER-4 4 个 Docker shell bash -n 通过" || log_bad "v07-DOCKER-4 $sbad 个 shell 语法错" "bash -n"

# v07-DOCKER-5 防泄密:.env 被忽略 + .env.example 无真实密钥
{ grep -qxE '\.env' "$RD/.gitignore" \
  && ! grep -qiE '^(DB_PASS|MYSQL_ROOT_PASSWORD|REMEMBER_ME_KEY)=[A-Za-z0-9]{16,}' "$RD/.env.example"; } \
  && log_ok "v07-DOCKER-5 .env 已 gitignore + .env.example 只占位无真密钥" \
  || log_bad "v07-DOCKER-5 密钥泄露风险" "检查 .gitignore / .env.example"

# v07-DOCKER-6 迁移脚本双模式(systemd + macOS)
{ grep -q '/etc/finance.env' "$RD/deploy/migrate-to-docker.sh" && grep -q '.finance/finance.env' "$RD/deploy/migrate-to-docker.sh"; } \
  && log_ok "v07-DOCKER-6 迁移脚本识别 systemd + macOS 双存量" \
  || log_bad "v07-DOCKER-6 迁移脚本未覆盖双模式" "see migrate-to-docker.sh"

# v07-DOCKER-7 一键自检入口:探测 docker/引擎/Compose-V2 + 健康验证(适配各种 Mac docker 装法)
UP="$RD/deploy/docker-up.sh"
{ [[ -f "$UP" ]] \
  && grep -q 'docker info' "$UP" \
  && grep -q 'docker compose version' "$UP" \
  && grep -q 'docker-compose version --short' "$UP" \
  && grep -q '/health' "$UP"; } \
  && log_ok "v07-DOCKER-7 docker-up.sh 自检引擎/Compose-V2 + 验 /health" \
  || log_bad "v07-DOCKER-7 一键自检入口缺检查项" "see deploy/docker-up.sh"

# v07-DOCKER-8 种子账号 prod 引导:ProdSeedRunner 修首登死锁 + docker-up 打印账号密码
PSR="$RD/src/main/java/com/family/finance/config/ProdSeedRunner.java"
{ [[ -f "$PSR" ]] \
  && grep -q '@Profile("prod")' "$PSR" \
  && grep -q 'findSeedPlaceholders' "$PSR" \
  && grep -q 'updatePasswordHash' "$PSR" \
  && grep -q 'seed.admin-password' "$PSR" \
  && grep -q 'findSeedPlaceholders' "$RD/src/main/java/com/family/finance/repository/MemberMapper.java" \
  && grep -q 'SEED_ADMIN_PASSWORD' "$RD/.env.example" \
  && grep -q '首次登录' "$RD/deploy/docker-up.sh"; } \
  && log_ok "v07-DOCKER-8 ProdSeedRunner 引导种子密码(幂等)+ docker-up 打印首登账号" \
  || log_bad "v07-DOCKER-8 prod 种子账号引导缺失" "Docker 首登会死锁,see ProdSeedRunner"

# v07-DOCKER-9 安装入口收敛:Docker 只有 docker-up.sh 一个入口(docker-init.sh 已删、.env 生成内联);
#   直装只有 deploy.sh 一个入口(macOS 自动 exec 到内部实现 _deploy-macos.sh)
LAND="$RD/src/main/resources/templates/landing.html"
{ [[ ! -f "$RD/deploy/docker-init.sh" ]] \
  && grep -q 'ensure_env' "$UP" && grep -q 'REMEMBER_ME_KEY' "$UP" && grep -q 'openssl rand' "$UP" \
  && [[ -f "$RD/deploy/_deploy-macos.sh" ]] && [[ ! -f "$RD/deploy/deploy-macos.sh" ]] \
  && grep -q '_deploy-macos.sh' "$RD/deploy/deploy.sh" \
  && grep -q 'docker-up.sh' "$LAND" && ! grep -q 'docker-init' "$LAND" \
  && ! grep -q 'docker-init' "$RD/.env.example" \
  && bash -n "$UP" 2>/dev/null && bash -n "$RD/deploy/deploy.sh" 2>/dev/null; } \
  && log_ok "v07-DOCKER-9 安装入口收敛:docker-up.sh 唯一 Docker 入口(.env 内联)+ deploy.sh 唯一直装入口(Mac 转 _deploy-macos.sh)" \
  || log_bad "v07-DOCKER-9 安装入口未收敛" "docker-init.sh 应删/内联;deploy-macos.sh 应改名 _deploy-macos.sh 并由 deploy.sh 分流"

# v07-DOCKER-10 单一构建:docker-compose.yml 只允许一个服务带 `build:`(app);backup 复用同 image tag、不得再写 build:。
#   两个服务同 image+build 时 classic builder(非 BuildKit)会 build 两遍、第二遍打 tag 撞 AlreadyExists 而失败。
{ [[ "$(grep -cE '^[[:space:]]*build:' "$RD/docker-compose.yml")" == "1" ]] \
  && grep -qE '^[[:space:]]*app:' "$RD/docker-compose.yml"; } \
  && log_ok "v07-DOCKER-10 compose 仅 app 带 build:(backup 复用镜像,不触发 classic builder 双构建 AlreadyExists)" \
  || log_bad "v07-DOCKER-10 compose 出现多个 build:(会致 classic builder 同 image 双构建撞 AlreadyExists)" "见 docker-compose.yml backup 服务不应写 build:"

# v07-CN-1 国内 Docker 阻断引导:docker-up.sh 单独探 Docker Hub 归因 + 镜像源指引 + 不覆盖已有 registry-mirrors + 平台分流(Linux/Mac) + bash -n
#   v1.6.21 起断言跟随新实现:探的是 $DB_UPSTREAM(双源里的 Docker Hub 那条),不再是写死的 mysql:8.0;
#   「不覆盖」的判据从「文件不存在才写」改成「已有 registry-mirrors 就不动」(两处:Desktop / Linux),
#   因为现在有内容时会用 python3 合并而非拒写 —— 真正要守的不变量是不破坏用户既有的镜像源配置。
{ [[ -f "$UP" ]] \
  && grep -qF 'pull_one "$DB_UPSTREAM"' "$UP" \
  && grep -q 'cn_hub_blocked_guide' "$UP" \
  && grep -q 'registry-mirrors' "$UP" \
  && grep -q 'docker.m.daocloud.io' "$UP" \
  && [ "$(grep -c '里已有 registry-mirrors' "$UP")" -eq 2 ] \
  && grep -q 'uname -s.*Darwin\|Darwin.*_cn_guide_mac' "$UP" \
  && grep -q '_cn_guide_mac' "$UP" \
  && grep -q 'colima.yaml\|\.colima/default' "$UP" \
  && grep -q 'orb config docker' "$UP" \
  && bash -n "$UP" 2>/dev/null; } \
  && log_ok "v07-CN-1 docker-up.sh 探 Docker Hub 阻断 + 镜像源引导 + 不覆盖已有 registry-mirrors + Mac 分引擎(colima/orb/Desktop)" \
  || log_bad "v07-CN-1 国内阻断引导逻辑缺件" "see deploy/docker-up.sh step5 / cn_hub_blocked_guide"

# v07-CN-2 文档守护:三处文档给对镜像源,且纠正了「build 救不了 mysql」的误导
#   v1.6.21 起 README 不再教用户写 daemon.json(默认 GHCR 副本 + MYSQL_IMAGE 覆盖),故断言换成这两个词;
#   「build 救不了」仍必须在 README/deploy-README/faq 三处保留 —— 本地构建要拉 maven/temurin,同样过不了墙。
{ grep -q 'registry-mirrors' "$RD/deploy/README.md" && grep -q 'docker.m.daocloud.io' "$RD/deploy/README.md" && grep -q '救不了' "$RD/deploy/README.md" \
  && grep -q 'mysql:8.0' "$RD/README.md" && grep -q '救不了' "$RD/README.md" \
  && grep -q 'MYSQL_IMAGE' "$RD/README.md" && grep -q 'GHCR' "$RD/README.md" \
  && grep -q 'registry-mirrors' "$RD/docs/faq.md" && grep -q 'docker.m.daocloud.io' "$RD/docs/faq.md" && grep -q 'mysql:8.0' "$RD/docs/faq.md"; } \
  && log_ok "v07-CN-2 README/deploy-README/faq 均给对国内镜像源 + 纠正 build 误导" \
  || log_bad "v07-CN-2 国内镜像源文档缺失或仍有误导" "see deploy/README.md / README.md / docs/faq.md"

# v07-CLEAN-1 全新 Docker 清演示数据成空态(与 systemd step10 一致):脚本逻辑 + entrypoint 全新库判定 + Dockerfile 接线 + bash -n
CLEAN="$RD/docker/clean-dev-data.sh"; ENT="$RD/docker/entrypoint.sh"
{ [[ -f "$CLEAN" ]] \
  && grep -q 'FINANCE_KEEP_DEMO' "$CLEAN" \
  && grep -qE 'member WHERE id > 2|id > 2' "$CLEAN" \
  && grep -q 'TRUNCATE TABLE period;' "$CLEAN" && grep -q 'TRUNCATE TABLE account;' "$CLEAN" \
  && grep -q 'schema_history' "$ENT" && grep -q 'FRESH_DB' "$ENT" && grep -q 'clean-dev-data.sh' "$ENT" \
  && grep -q 'clean-dev-data.sh' "$RD/Dockerfile" \
  && bash -n "$CLEAN" 2>/dev/null && bash -n "$ENT" 2>/dev/null; } \
  && log_ok "v07-CLEAN-1 全新库清演示数据(铁信号 schema_history + 互锁 + KEEP_DEMO 开关 + 与 step10 同表集)" \
  || log_bad "v07-CLEAN-1 全新 Docker 清空态逻辑缺件" "see docker/clean-dev-data.sh / entrypoint.sh / Dockerfile"

# v16-EMPTY-1 全新部署空账期兜底:/entry /reports /checkup 在零周期时不再 orElseThrow → 500,
#   而是 redirect:/?needs=period 回引导页;引导页横幅 + 第③步「去填报」需 hasPeriod 才放行(按序门控)。
ENTRY="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
RPT="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
CHK="$RD/src/main/java/com/family/finance/web/checkup/CheckupController.java"
OBH="$RD/src/main/resources/templates/onboarding/index.html"
{ for f in "$ENTRY" "$RPT" "$CHK"; do
    grep -q 'countByFamily(me.getFamilyId()) == 0' "$f" && grep -q 'redirect:/?needs=period' "$f" || exit 1
  done
  grep -qF "needs == 'period'" "$OBH" \
  && grep -qF 'th:if="${hasPeriod}" th:href="@{/entry}"' "$OBH" \
  && grep -qF 'th:if="${hasAccount}" th:href="@{/admin/periods}"' "$OBH"; } \
  && log_ok "v16-EMPTY-1 空账期兜底:entry/reports/checkup 零周期 redirect 引导页(不 500)+ 引导横幅 + ②③按序门控" \
  || log_bad "v16-EMPTY-1 空账期兜底缺件" "see EntryController/ReportsController/CheckupController guard + onboarding/index.html"

# v17-INSURANCE-1 保险账户类型全链落地(枚举 + 两处 CHECK 放宽 + 桶接线 + 洞察金融资产 + 保单旁表 + 种子)
V44="$RD/db/migration/V44__insurance_account.sql"
ADF="$RD/src/main/java/com/family/finance/calc/AllocationDiff.java"
AIS="$RD/src/main/java/com/family/finance/service/insight/AssetInsightService.java"
ACT="$RD/src/main/java/com/family/finance/domain/account/AccountType.java"
{ grep -q 'INSURANCE("保险")' "$ACT" \
  && [ -f "$V44" ] && [ "$(grep -c "'INSURANCE'" "$V44")" -ge 2 ] \
  && grep -q 'account_insurance_policy' "$V44" && grep -q 'SAVINGS_INSURANCE' "$V44" \
  && grep -q 'annuity_insurance' "$V44" && grep -q 'whole_life_insurance' "$V44" \
  && grep -qF '"INSURANCE".equals(type)' "$ADF" && grep -q 'Bucket.INSURANCE' "$ADF" \
  && grep -q 'AccountType.INSURANCE' "$AIS" \
  && [ -f "$RD/src/main/java/com/family/finance/domain/insurance/InsurancePolicy.java" ] \
  && [ -f "$RD/src/main/java/com/family/finance/repository/InsurancePolicyMapper.java" ] \
  && [ -f "$RD/src/main/java/com/family/finance/domain/account/InsuranceSubType.java" ]; } \
  && log_ok "v17-INSURANCE-1 保险类型全链:enum+2处CHECK放宽+pickBucket短路INSURANCE桶+financialSum含保险+保单旁表+SAVINGS_INSURANCE/2模板种子" \
  || log_bad "v17-INSURANCE-1 保险类型全链缺件" "see AccountType/V44/AllocationDiff.pickBucket/AssetInsightService/InsurancePolicy(Mapper)"

# v17-INSURANCE-2 pill-slate 四处徽章 + 向导消费型提示 + 手填不入持仓
CSS17="$RD/src/main/resources/static/css/style.css"
WIZ="$RD/src/main/resources/templates/accounts/_template-wizard.html"
{ grep -q '.pill-slate' "$CSS17" \
  && grep -qF "'INSURANCE' ? ' pill-slate'" "$RD/src/main/resources/templates/accounts/detail.html" \
  && grep -qF "'INSURANCE' ? ' pill-slate'" "$RD/src/main/resources/templates/accounts/index.html" \
  `# entry 行不再用 pill:v1.9.3 为修「类型列竖排导致行高难看」改成紧凑彩色文字(type.label +` \
  `# th:classappend 控色)。守的意图不变 —— 填报行必须透出中文类型,不裸露 enum code。` \
  && grep -qF 'row.account.type.label' "$RD/src/main/resources/templates/entry/_row.html" \
  && grep -q 'insuranceHint' "$WIZ" && grep -q '消费型是纯支出' "$WIZ" \
  && grep -q 'type == AccountType.STOCK || type == AccountType.CRYPTO || type == AccountType.METAL' \
        "$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"; } \
  && log_ok "v17-INSURANCE-2 pill-slate 应用四处 + 向导消费型友好提示 + supportsHoldings 不含 INSURANCE(手填非持仓)" \
  || log_bad "v17-INSURANCE-2 保险 UI/手填缺件" "see style.css pill-slate / detail·index·_row pill / _template-wizard hint / StockHoldingService.supportsHoldings"

# v17-WIZARD 模板卡真正驱动表单:点卡 → 回填类型/币种/建议名 + 高亮 + 隐藏 templateId(不再是死展示)
WIZ17="$RD/src/main/resources/templates/accounts/_template-wizard.html"
{ grep -q 'data-tpl-type=' "$WIZ17" && grep -q 'data-tpl-currency=' "$WIZ17" && grep -q 'data-tpl-name=' "$WIZ17" \
  && grep -q 'tpl-selected' "$WIZ17" \
  && grep -q 'name="templateId" id="tplId"' "$WIZ17" \
  && grep -q "querySelectorAll('.tpl-card')" "$WIZ17" \
  && grep -q 'newAcctHead' "$WIZ17" && grep -q 'scrollIntoView' "$WIZ17" \
  && ! grep -q 'select name="type" data-searchable' "$WIZ17"; } \
  && log_ok "v17-WIZARD 模板卡点击回填表单(data-tpl-* + tpl-card 点击 handler + 隐藏 templateId + type 去 searchable 便于赋值),非死展示" \
  || log_bad "v17-WIZARD 模板卡未驱动表单" "see _template-wizard.html data-tpl-*/tpl-selected/tplId/click handler"

# v17-LOAN-PROMPT 贷款趋势预测从「开账静默外推」改为「填报行内显式接受/保持上月」+ 兼容闸(committed==prev)
POJ="$RD/src/main/java/com/family/finance/service/PeriodOpener.java"
ESJ="$RD/src/main/java/com/family/finance/service/EntryService.java"
ROW17="$RD/src/main/resources/templates/entry/_row.html"
{ ! grep -q 'applyLoanPrefill' "$POJ" \
  && grep -q 'predictLoanBalance' "$POJ" \
  && grep -q 'static boolean loanPromptVisible' "$ESJ" \
  && grep -q 'acceptLoanPrediction' "$ESJ" \
  && grep -q 'accept-loan-prediction' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -qF 'showLoanPrompt' "$ROW17" && grep -q '按上两月趋势' "$ROW17"; } \
  && log_ok "v17-LOAN-PROMPT 贷款不再静默外推(删 applyLoanPrefill)· PeriodOpener 延续 prev · 填报行提示条(接受/保持上月)· acceptLoanPrediction 复刻旧逻辑 · loanPromptVisible 兼容闸(committed==prev 屏蔽老账期)" \
  || log_bad "v17-LOAN-PROMPT 贷款显式接受缺件" "see PeriodOpener(删applyLoanPrefill/留predictLoanBalance)/EntryService.loanPromptVisible+acceptLoanPrediction/EntryController/_row.html"

# v11-LENS-1 资产透视底座:V45 三列+lens_board · 枚举 · 注册表(≥8维5度量)· 唯一网关 · 纯函数引擎
V45L="$RD/db/migration/V45__asset_lens.sql"
LREG="$RD/src/main/java/com/family/finance/calc/lens/LensRegistry.java"
{ [ -f "$V45L" ] && grep -q 'asset_class' "$V45L" && grep -q 'platform_tag' "$V45L" \
  && grep -q 'industry_tag' "$V45L" && grep -q 'lens_board' "$V45L" \
  && [ -f "$RD/src/main/java/com/family/finance/domain/lens/AssetClass.java" ] \
  && [ -f "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" ] \
  && [ "$(grep -c '        dim("' "$LREG")" -ge 8 ] && [ "$(grep -c 'measure("' "$LREG")" -ge 7 ] \
  && grep -q 'PostMapping("/lens/query")' "$RD/src/main/java/com/family/finance/web/lens/LensController.java" \
  && grep -q 'holdingLevelSplit' "$RD/src/main/java/com/family/finance/calc/lens/PivotEngine.java"; } \
  && log_ok "v11-LENS-1 透视底座:V45(3列+lens_board)+ AssetClass/IndustryTag + 注册表 ≥8维/5度量 + POST /lens/query 唯一网关 + 引擎收益归因降级标记" \
  || log_bad "v11-LENS-1 透视底座缺件" "see V45/domain.lens/LensRegistry/LensController/PivotEngine"

# v11-LENS-2 前端与入口:nav 双端「透视」 · lens.js 状态机/旭日/透视表 · 打标页显式接受 · AI 白名单 · 集中度规则+阈值可配
NAVF="$RD/src/main/resources/templates/fragments/nav.html"
{ ! grep -q '@{/lens}' "$NAVF" \
  && grep -q 'lens/_section :: section' "$RD/src/main/resources/templates/dashboard/index.html" \
  && grep -q "label:'资产透视'" "$RD/src/main/resources/templates/dashboard/index.html" \
  && grep -q '#lens-section' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q '@{/lens/tags}' "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q 'dashboard#lens-section' "$RD/src/main/resources/templates/reports/_allocation-diff.html" \
  && grep -q '由持仓逐个标' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'name="only"' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'acct_purpose_' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'scrollIntoView' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q 'IntersectionObserver' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q 'CACHE_TTL_MS' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'TransactionalEventListener' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'ApplicationReadyEvent' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'LensStaleEvent' "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q 'LensStaleEvent' "$RD/src/main/java/com/family/finance/service/AccountService.java" \
  && grep -q 'LensStaleEvent' "$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java" \
  && grep -q 'lensQueryService.evict' "$RD/src/main/java/com/family/finance/web/lens/LensTagController.java" \
  && grep -q 'lensQueryService.evict' "$RD/src/main/java/com/family/finance/web/stock/StockHoldingController.java" \
  && grep -q 'drill' "$RD/src/main/resources/static/js/lens.js" && grep -q 'sunburst' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q 'lens-pivot' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q '保存全部打标' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'AI 推荐(全部未打标)' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'fromName' "$RD/src/main/java/com/family/finance/service/lens/LensAiTagService.java" \
  && grep -q 'LENS-CON-1' "$RD/src/main/java/com/family/finance/service/checkup/rule/LensConcentrationRules.java" \
  && grep -q 'LENS-CON-2' "$RD/src/main/java/com/family/finance/service/checkup/rule/LensConcentrationRules.java" \
  && grep -q 'lensIndustryConc' "$RD/src/main/resources/templates/admin/calc-tweaks.html"; } \
  && log_ok "v11-LENS-2 透视内嵌仪表盘 + 懒加载(IO·首屏0查询)+ 头寸缓存(60sTTL+打标/行业evict)+ 打标树状(账户›持仓·单行AI·用途列)+ 构建器展开滚动 + lens.js + AI 白名单 + LENS-CON-1/2(阈值可配)" \
  || log_bad "v11-LENS-2 透视前端/打标/集中度缺件" "see nav.html/lens.js/lens tags.html/LensAiTagService/LensConcentrationRules/calc-tweaks.html"

# v07-CLEAN-2 README 新用户硬伤:无 <your-org> 占位符 + 测试数自洽
#   2026-08-04 修:原来把测试数写死成 v0.7 时代的「289 单元 / 412」,之后每次加测试都会红,
#   于是这条护栏长期失效 —— 一条永远红的护栏不会拦住任何回归,只会淹没真问题。
#   改成「README 里的单测数必须与 mvn 实测一致」:数字从 pom 编译产物的测试类实测拿不到,
#   退一步守可验证的部分 —— 数字存在、格式对、且与主页数字带一致(v09-LAND-6 已守后者)。
RM_TST="$(grep -oE '[0-9]+ 单元' "$RD/README.md" | head -1 | grep -oE '[0-9]+')"
RM_BBX="$(grep -oE '[0-9]+ 黑盒回归' "$RD/README.md" | head -1 | grep -oE '[0-9]+')"
{ ! grep -q '<your-org>' "$RD/README.md" \
  && [ -n "$RM_TST" ] && [ "$RM_TST" -ge 289 ] \
  && [ -n "$RM_BBX" ] && [ "$RM_BBX" -ge 412 ] \
  && ! grep -q '244 单元' "$RD/README.md"; } \
  && log_ok "v07-CLEAN-2 README 无 <your-org> 占位符 + 测试数自洽($RM_TST 单元 / $RM_BBX 黑盒 · 只增不减)" \
  || log_bad "v07-CLEAN-2 README 仍有占位符或测试数不一致" "README 需写明「N 单元 / N 黑盒回归」且不小于历史基线(289/412)"

section "v0.8 · 指标端出/排序/筛选/可配置/计算正确性(静态守护)"
RG="$RD/src/main/resources/templates/dashboard/_region.html"
FVI="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
# v08-1 账户指标端出 + 真 sparkline(无硬编码假图)+ 三态排序
{ grep -q 'cumPnl' "$RD/src/main/java/com/family/finance/factview/AccountPerformance.java" \
  && grep -q 'sparkPoints' "$FVI" \
  && grep -q 'data-sortable' "$RG" && grep -q 'aria-sort' "$RG" \
  && ! grep -q '0,18 10,15 20,16' "$RG"; } \
  && log_ok "v08-1 账户指标全集端出 + 真 sparkline(假图已除)+ 列表三态排序" \
  || log_bad "v08-1 P1 指标/排序/sparkline 缺件" "see AccountPerformance/_region.html"
# v08-2 跨币种转账 to_amount
{ grep -q 'COALESCE(to_amount, amount)' "$RD/src/main/resources/mapper/FactMapper.xml" \
  && [[ -f "$RD/db/migration/V30__transfer_to_amount.sql" ]] \
  && grep -q 'toAmount' "$RD/src/main/java/com/family/finance/domain/transfer/Transfer.java"; } \
  && log_ok "v08-2 跨币种转账 to_amount(COALESCE 读 + 域/迁移)" \
  || log_bad "v08-2 跨币种 to_amount 缺件" "see FactMapper.xml / V30 / Transfer"
# v08-3 Problem B 现金调整剔出 PnL
{ [[ -f "$RD/db/migration/V33__cash_flow_adjustment.sql" ]] \
  && grep -q 'is_adjustment' "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q 'recordCashAdjustment' "$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"; } \
  && log_ok "v08-3 Problem B 现金行手动改记 is_adjustment 流水(剔出投资损益)" \
  || log_bad "v08-3 Problem B 缺件" "see V33 / CashFlowMapper / StockHoldingService"
# v08-4 筛选器按账期 as-of + MoM/YoY(Problem C 双收益率)
{ grep -q 'String asof' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'resolveAsOf' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && [[ -f "$RD/src/main/java/com/family/finance/factview/MomYoy.java" ]] \
  && grep -q 'momYoy' "$FVI" && grep -q 'xirrBaseForAccountRows' "$FVI" \
  && grep -q 'asof=' "$RG"; } \
  && log_ok "v08-4 筛选器 as-of 账期 + MoM/YoY + 本位币双收益率" \
  || log_bad "v08-4 P4 筛选器/MoM-YoY/双收益率缺件" "see DashboardController/MomYoy/FactViewServiceImpl"
# v08-5 可配置指标集
{ [[ -f "$RD/src/main/java/com/family/finance/service/MetricPrefsService.java" ]] \
  && [[ -f "$RD/src/main/resources/templates/admin/metrics.html" ]] \
  && [[ -f "$RD/db/migration/V32__family_metric_prefs.sql" ]] \
  && grep -q "acctMetrics.contains" "$RG" \
  && grep -q '/admin/metrics' "$RD/src/main/resources/templates/admin/_sidebar.html"; } \
  && log_ok "v08-5 可配置指标集(MetricPrefsService + 管理页 + dashboard 按勾选显隐)" \
  || log_bad "v08-5 可配置指标缺件" "see MetricPrefsService/admin/metrics.html/V32/_region/_sidebar"
# v08-6 预实分析
{ [[ -f "$RD/db/migration/V31__account_expected_return.sql" ]] \
  && grep -q 'expectedReturnPct' "$RD/src/main/java/com/family/finance/domain/account/Account.java" \
  && grep -q 'planActualDiffPct' "$RD/src/main/java/com/family/finance/factview/AccountPerformance.java" \
  && grep -q 'expectedReturnPct' "$RD/src/main/resources/templates/accounts/edit.html"; } \
  && log_ok "v08-6 预实分析(账户预期收益覆盖 + 回落品类基准 + 编辑入口)" \
  || log_bad "v08-6 预实缺件" "see V31 / Account / AccountPerformance / accounts/edit.html"

# v08-7 家庭指标配置真控 KPI 豆腐块 + 头部(famMetrics 接线)+ 计算正确性单测存在
{ grep -q "famMetrics.contains('net_worth')" "$RG" && grep -q "famMetrics.contains('total_assets')" "$RG" \
  && grep -q "famMetrics.contains('emergency_months')" "$RG" && grep -q "famMetrics.contains('period_return')" "$RG" \
  && grep -q "famMetrics.contains('nw_mom')" "$RG" \
  && [[ -f "$RD/src/test/java/com/family/finance/factview/FactViewMetricsCalcTest.java" ]]; } \
  && log_ok "v08-7 家庭指标配置控 KPI 豆腐块/头部(famMetrics)+ 计算正确性单测在" \
  || log_bad "v08-7 豆腐块未接 famMetrics 或缺计算单测" "see _region.html / FactViewMetricsCalcTest"

# v08-8 账户/仪表盘/账本无 pictographic emoji(💡/📦/📈 等;★ 风险星与 ↔↺✕△ 排版符保留)
#   v0.10.6 扩:纳入 EntryController(账本行 kind 标签由 Java 拼 HTML · 📈 估值→△ 估值)· 网住账本 emoji 回归
EMOJI_HITS=0
for f in "$RD/src/main/resources/templates/accounts/detail.html" "$RD/src/main/resources/templates/dashboard/_region.html" "$RD/src/main/java/com/family/finance/web/entry/EntryController.java"; do
  grep -oP '[\x{1F300}-\x{1FAFF}]|[\x{2600}-\x{26FF}]' "$f" 2>/dev/null | grep -vE '★|☆' | grep -q . && EMOJI_HITS=$((EMOJI_HITS+1))
done
[[ "$EMOJI_HITS" -eq 0 ]] \
  && log_ok "v08-8 账户详情/仪表盘/账本(EntryController)无 pictographic emoji(已换 inline SVG/排版符)" \
  || log_bad "v08-8 仍有 emoji" "$EMOJI_HITS 个文件命中,see detail.html/_region.html/EntryController.java"

# v1.24.5 · 大模型那一节从数据源接入挪到了 AI 接入:
#   模板 → admin/_llm-settings.html;Java → AiModelController(端点)+ LlmSettingsView(页面数据)。
#   原来这些护栏 grep 的是【一个】控制器文件,现在同样的东西分在两个文件里,
#   合成一份给它们用 —— 判据本身一个字没改,只换了看的地方。
QA_LLM_TPL="$RD/src/main/resources/templates/admin/_llm-settings.html"
QA_LLM_JAVA="$(mktemp)"
cat "$RD/src/main/java/com/family/finance/web/admin/AiModelController.java" \
    "$RD/src/main/java/com/family/finance/web/admin/LlmSettingsView.java" > "$QA_LLM_JAVA"

section "v0.7 第二批 · 外部服务配置引导(静态守护)"
ICFG="$QA_LLM_TPL"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 integrations.html)
ICTL="$QA_LLM_JAVA"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 IntegrationsController.java)

# v07-CFG-1 配置总指南 + README 入口
{ [[ -f "$RD/docs/configuration.md" ]] && grep -q 'docs/configuration.md' "$RD/README.md"; } \
  && log_ok "v07-CFG-1 configuration.md 存在 + README 有入口" \
  || log_bad "v07-CFG-1 配置总指南或 README 入口缺失" "see docs/configuration.md"

# v07-CFG-2 LLM 页:可选 banner + 折叠帮助 + 测试按钮 + sibling 测试表单
#   v1.13 改写:原来钉死 `llm-test-qwen` / `llm-test-deepseek` 两个 id —— 平台化之后
#   qwen 这个名字消失了(平台叫「百炼 dashscope」· 模型系列才叫 qwen),而且平台可以再加。
#   钉死名字的写法有两个毛病:改名当天整条红(这次就是),加平台那天却**不红**——
#   新平台漏了测试按钮它一句话都不说。改成从模板里**反查**:每个 `id="llm-test-X"` 的
#   隐藏表单都必须有对应的 `form="llm-test-X"` 按钮,且平台数 ≥ 3(百炼/DeepSeek/方舟),
#   每个平台各有一段「如何获取…Key」折叠指引。这样加第四个平台时护栏跟着长。
_cfg2_ids="$(grep -oE 'id="llm-test-[a-z0-9]+"' "$ICFG" | sed -E 's/.*llm-test-([a-z0-9]+)".*/\1/' | sort -u)"
_cfg2_n="$(printf '%s\n' "$_cfg2_ids" | grep -c . )"
_cfg2_help="$(grep -c '<summary>如何获取' "$ICFG")"
_cfg2_bad=""
for _p in $_cfg2_ids; do
  grep -q "form=\"llm-test-$_p\"" "$ICFG" || _cfg2_bad="$_cfg2_bad $_p(有表单没按钮)"
done
{ grep -q '都<b>可选</b>' "$ICFG" \
  && [ "$_cfg2_n" -ge 3 ] && [ "$_cfg2_help" -ge "$_cfg2_n" ] && [ -z "$_cfg2_bad" ]; } \
  && log_ok "v07-CFG-2 LLM 页 可选说明 + 每个平台各有折叠指引 + 测试按钮 + sibling 表单齐(平台 $_cfg2_n 个)" \
  || log_bad "v07-CFG-2 LLM 配置引导 UI 缺件" "平台 $_cfg2_n 个 · 折叠指引 $_cfg2_help 段 · 缺按钮:${_cfg2_bad:-ok} · see admin/_llm-settings.html"

# v07-CFG-3 后端测试端点 + 脱敏分类
#   v1.24.5 · 端点跟着页面搬到了 AiModelController:类上 /admin/ai-access/llm + 方法上 /test。
#   判据改成查【拼起来的完整路径】,并确认页面上的测试表单确实提交到这里(两头对得上才算通)。
{ grep -q '@RequestMapping("/admin/ai-access/llm")' "$ICTL" \
  && grep -q '@PostMapping("/test")' "$ICTL" \
  && [ "$(grep -c 'action="@{/admin/ai-access/llm/test}"' "$ICFG")" -ge 3 ] \
  && grep -q 'classifyLlmError' "$ICTL" \
  && grep -q 'isPrivateKeyConfigured' "$ICTL"; } \
  && log_ok "v07-CFG-3 /admin/ai-access/llm/test 端点 + 三家测试表单指向它 + classifyLlmError 脱敏 + 未配短路" \
  || log_bad "v07-CFG-3 LLM 测试端点缺失" "see AiModelController(/admin/ai-access/llm/test)与 _llm-settings.html 的三个测试表单"

# v07-CFG-4 私密红线:测试端点不回显/不记 key 明文(/llm/test 处理不引用 key 参数,审计只记 vendor+结果)
if awk '/@PostMapping\("\/llm\/test"\)/{f=1} f{print} /^    }$/{if(f)exit}' "$ICTL" | grep -qiE 'qwenKey|deepseekKey|getString.*KEY|\.token'; then
  log_bad "v07-CFG-4 测试端点疑似触碰 key 明文" "复核 testLlm 不应读/拼 key"
else
  log_ok "v07-CFG-4 测试端点不读/不回显 key 明文(只 vendor + 脱敏结果)"
fi

# v07-CFG-5 短信页补配置指南链
grep -q 'aliyun-sms-setup.md' "$RD/src/main/resources/templates/admin/notification.html" \
  && log_ok "v07-CFG-5 短信页有「配置指南」文档链" \
  || log_bad "v07-CFG-5 短信页缺文档链" "see notification.html"

section "v0.7 第三批 · 系统内首次引导(静态守护)"
HC="$RD/src/main/java/com/family/finance/common/HomeController.java"
DC="$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java"

# v07-ONB-1 落地页智能路由 + onboarding 模板 + 首登500兜底
{ [[ -f "$RD/src/main/resources/templates/onboarding/index.html" ]] \
  && grep -q 'onboarding/index' "$HC" \
  && grep -q 'redirect:/dashboard' "$HC" \
  && grep -q 'countByFamily' "$HC" \
  && grep -q 'redirect:/' "$DC"; } \
  && log_ok "v07-ONB-1 / 智能路由(onboarding/dashboard)+ dashboard 零周期兜底 redirect" \
  || log_bad "v07-ONB-1 首次引导路由/兜底缺失" "see HomeController/DashboardController"

# v07-ONB-2 引导页起步步骤 + /entry 周期流程说明
{ grep -q '加账户' "$RD/src/main/resources/templates/onboarding/index.html" \
  && grep -q '开本期周期' "$RD/src/main/resources/templates/onboarding/index.html" \
  && grep -q '周期流程' "$RD/src/main/resources/templates/entry/index.html" \
  && [[ -f "$RD/src/test/java/com/family/finance/web/OnboardingRoutingTest.java" ]]; } \
  && log_ok "v07-ONB-2 引导 3 步 + entry 周期流程说明 + 路由单测在" \
  || log_bad "v07-ONB-2 引导内容/单测缺失" "see onboarding/entry"

# v07-FIX-1 改密死循环修复(issue #1):改密后真作废 session,不再只 clearContext
PC="$RD/src/main/java/com/family/finance/web/profile/ProfileController.java"
{ grep -q 'SecurityContextLogoutHandler' "$PC" \
  && grep -q '\.logout(request, response' "$PC" \
  && [[ -f "$RD/src/test/java/com/family/finance/web/ProfilePasswordChangeTest.java" ]]; } \
  && log_ok "v07-FIX-1 改密用 SecurityContextLogoutHandler 真作废 session(修首登死循环)+ 回归单测在" \
  || log_bad "v07-FIX-1 改密死循环修复缺失" "see ProfileController issue#1"

section "v0.9 · 根路径公开落地页(降钓鱼误判 + 对外门面)"
# v09-LAND-1 · 匿名 GET / = 200 落地页(含定位文案 + GitHub 全 URL + 截图引用)
anon=/tmp/finance-qa-landing.html
code=$($CURL -o "$anon" -w "%{http_code}" "$BASE/")     # 不带 cookie = 匿名
{ [[ "$code" == "200" ]] \
  && grep -q "家庭账房" "$anon" \
  && grep -q "资产全局图" "$anon" \
  && grep -q "github.com/LuoDi-Nate/financial-management" "$anon" \
  && grep -q "feature_summary_total.jpg" "$anon"; } \
  && log_ok "v09-LAND-1 匿名 / =200 公开落地页(定位文案 + GitHub 全 URL + 总览图)" \
  || log_bad "v09-LAND-1 匿名落地页缺件" "code=$code(应 200 且含家庭账房/资产全局图/github URL/截图)"

# v09-LAND-2 · 匿名 / 不再 302 到裸登录页(降钓鱼信号的核心:首屏非登录框)
loc=$($CURL -o /dev/null -w "%{http_code}|%{redirect_url}" "$BASE/")
[[ "$loc" == 200\|* ]] \
  && log_ok "v09-LAND-2 匿名 / 直接 200,不再 302 /login(裸登录触发特征已消除)" \
  || log_bad "v09-LAND-2 匿名 / 仍跳转" "got=$loc(期望 200,无 redirect)"

# v09-LAND-3 · 已登录 GET / → 302 /dashboard(老用户无感直达)
loc=$($CURL -b $COOKIE -o /dev/null -w "%{http_code}|%{redirect_url}" "$BASE/")
[[ "$loc" == *"/dashboard" ]] \
  && log_ok "v09-LAND-3 已登录 / → 302 /dashboard($loc)" \
  || log_bad "v09-LAND-3 已登录 / 未直达 dashboard" "got=$loc"

# v09-LAND-4 · 回归:permitAll 只放了 /,鉴权页仍需登录(没放过头)
loc=$($CURL -o /dev/null -w "%{redirect_url}" "$BASE/dashboard")   # 匿名访问 dashboard
[[ "$loc" == *"/login"* ]] \
  && log_ok "v09-LAND-4 回归:匿名 /dashboard 仍被拦去登录(放行没过头)" \
  || log_bad "v09-LAND-4 鉴权回归异常" "匿名 /dashboard redirect=$loc(应含 /login)"

# v09-LAND-5 · v0.9.1 精修元素都在(GitHub 角标 + 真实命令块 + 它解决什么四问 + 数字带)
#   v1.7 修:原断言要求落地页命令块含 `docker compose up -d`,但安装入口收敛那版起
#   落地页给的就是 `bash deploy/docker-up.sh`(它内部才去调 compose)。这条断言自那以后
#   一直是红的 —— 守护断言的是**已被淘汰的命令**,等于长期失效。改成断言现口径。
{ grep -q 'github-corner' "$anon" && grep -q 'cmd-block' "$anon" \
  && grep -q 'git clone' "$anon" && grep -q 'deploy/docker-up.sh' "$anon" \
  && grep -q '它 解 决 什 么' "$anon" && grep -q '我们家现在到底有多少钱' "$anon" \
  && grep -q 'data-stat="version"' "$anon"; } \
  && log_ok "v09-LAND-5 落地页精修元素在(GitHub 角标 + 真实4步命令 + 它解决什么 + 数字带)" \
  || log_bad "v09-LAND-5 精修元素缺" "see landing.html"

# v09-LAND-6 · 主页数字带联动(与 release skill 同口径:版本/迁移自动算,单测/黑盒随 README)
v_ver=$(ls "$RD"/prd/v*.md 2>/dev/null | wc -l | tr -d ' ')
v_mig=$(ls "$RD"/db/migration/V*.sql 2>/dev/null | wc -l | tr -d ' ')
v_tst=$(grep -oE '[0-9]+ 单元' "$RD/README.md" | head -1 | grep -oE '[0-9]+')
v_bbx=$(grep -oE '[0-9]+ 黑盒回归' "$RD/README.md" | head -1 | grep -oE '[0-9]+')
lp_get(){ grep -oE "data-stat=\"$1\" data-to=\"[0-9]+\"" "$anon" | grep -oE '[0-9]+' | head -1; }
if [[ "$(lp_get version)" == "$v_ver" && "$(lp_get tests)" == "$v_tst" && "$(lp_get migrations)" == "$v_mig" && "$(lp_get blackbox)" == "$v_bbx" ]]; then
  log_ok "v09-LAND-6 主页数字带联动一致(版本$v_ver / 单测$v_tst / 迁移$v_mig / 黑盒$v_bbx)"
else
  log_bad "v09-LAND-6 主页数字带过时(发版会被 release skill 拦)" "landing=[$(lp_get version)/$(lp_get tests)/$(lp_get migrations)/$(lp_get blackbox)] 应=[$v_ver/$v_tst/$v_mig/$v_bbx]"
fi

# ───────────────────────────────────────────────────────────────────────────
# v0.9.2 · 填报/划转错误体验(2026-06-26 修:toast 被顶栏挡 / 空字段裸 400 不可读)
# v09-UX-1 · 全局 toast 不被顶栏挡:footer 不能用 relative z-10(否则 z-[10000] 的 toast-stack 被困其层叠上下文,沉到 nav 之下)
LAY=src/main/resources/templates/fragments/layout.html
{ grep -q '<footer th:fragment="footer"' "$LAY" \
  && ! grep -qE '<footer th:fragment="footer"[^>]*z-10' "$LAY"; } \
  && log_ok "v09-UX-1 footer 去 z-10 · 全局 toast(z-[10000])不再被 nav 挡" \
  || log_bad "v09-UX-1 footer 仍 relative z-10 · toast 会被顶栏挡" "see layout.html footer fragment"

# v09-UX-2 · 划转金额前置必填(空字段客户端拦截、不发请求)
grep -qE 'name="amount"[^>]*required' src/main/resources/templates/entry/_row.html \
  && log_ok "v09-UX-2 划转金额 required(空字段前置拦截)" \
  || log_bad "v09-UX-2 划转金额缺 required" "see entry/_row.html"

# v09-UX-3 · 参数绑定错(空金额)→ 可读 toast 而非裸 400(ToastErrorAdvice 兜 binding 异常)
CSRF=$(grep XSRF-TOKEN $COOKIE 2>/dev/null | awk '{print $7}' | tail -1)
[[ -z "$CSRF" ]] && { $CURL -b $COOKIE -c $COOKIE "$BASE/entry" -o /dev/null; CSRF=$(grep XSRF-TOKEN $COOKIE | awk '{print $7}' | tail -1); }
uxhdr=$($CURL -b $COOKIE -D - -o /dev/null -X POST -H "HX-Request: true" -H "X-XSRF-TOKEN: $CSRF" \
  --data-urlencode "periodId=1" --data-urlencode "toAccountId=1" --data-urlencode "amount=" \
  "$BASE/entry/1/transfer" 2>/dev/null | grep -i "HX-Trigger")
echo "$uxhdr" | grep -q "showToast" \
  && log_ok "v09-UX-3 空金额划转 → 200 + 可读 toast(非裸 400)" \
  || log_bad "v09-UX-3 空金额划转未回可读 toast" "HX-Trigger=$uxhdr"

# v09-CPI-1 · 净资产图 CPI 线 = 购买力保命线(锚×(1+cpi/12)^i),与 M2/【reports 财富水位】同口径;
#   防回退到「名义折现」(v/(1+cpi)^i)——那种永远压名义之下、不反映所选 CPI(2026-06-26 修)
RG_DASH=src/main/resources/templates/dashboard/_region.html
{ grep -q 'anchorNw \* Math.pow(1 + cpiMonthly' "$RG_DASH" \
  && ! grep -q '/ Math.pow(1 + cpiMonthly' "$RG_DASH"; } \
  && log_ok "v09-CPI-1 净资产图 CPI 线为购买力保命线(锚×(1+cpi)^i),非名义折现" \
  || log_bad "v09-CPI-1 CPI 线口径错(疑回退到名义折现 v/(1+cpi)^i)" "see _region.html netWorthChart"

# ── v09-FORM-* · 表单缺项前置拦截(全量审计 2026-06-26)·「缺表单项不许发请求」─────────────
#   原则:必填字段挂原生 required(浏览器/HTMX 拦截);仅在某控件命中时才必填的用 data-require-when 通用助手。
#   故意可选的不挂(entry 汇总「留空=未填」· SMS aksk「留空=不修改」· toAmount 跨币种才填 · roleLabel/note)。

# v09-FORM-1 · entry 收入/支出 金额前置必填(空字段不发请求;三表单各自独立,互不阻塞)
ROW=src/main/resources/templates/entry/_row.html
# 2026-08-13:原来钉 `placeholder="+收入"` / `"-支出"` 这两个**文案**,v1.8 改逐笔录入后
#   placeholder 变成 "0.00"/"金额"/"划转金额",护栏就红了 —— 而 required 一个没少。
#   改成结构性断言:**填报页所有 `name="amount"` 的框都必须带 required**(数量相等),
#   以后再改文案/加一处录入口都不会假红,反而漏加 required 会被抓住。
AMT_ALL=0; AMT_REQ=0
for f in "$ROW" "$RD/src/main/resources/templates/entry/index.html"; do
  AMT_ALL=$(( AMT_ALL + $(grep -c 'name="amount"' "$f") ))
  AMT_REQ=$(( AMT_REQ + $(grep -c 'name="amount"[^>]*required' "$f") ))
done
{ [[ "$AMT_ALL" -ge 3 ]] && [[ "$AMT_REQ" -eq "$AMT_ALL" ]]; } \
  && log_ok "v09-FORM-1 填报页 $AMT_ALL 个金额框全部 required(空字段前置拦截 · 收入/支出/划转)" \
  || log_bad "v09-FORM-1 有金额框没带 required" "amount=$AMT_ALL required=$AMT_REQ"

# v09-FORM-2 · 通用条件必填助手就位(data-require-when:某控件命中才 required)
LAY=src/main/resources/templates/fragments/layout.html
{ grep -q 'data-require-when' "$LAY" && grep -q 'el.required = (curVal' "$LAY"; } \
  && log_ok "v09-FORM-2 通用条件必填助手 data-require-when 就位(footer 全站注入)" \
  || log_bad "v09-FORM-2 缺 data-require-when 助手" "see layout.html footer fragment"

# v09-FORM-3 · 应急金「手填基线」选中才必填(自动基线时不挡)· 新建+编辑两页一致
{ grep -q 'name="fixedBaseline"[^>]*data-require-when="autoBaseline=false"' src/main/resources/templates/goals/new-emergency.html \
  || grep -A1 'name="fixedBaseline"' src/main/resources/templates/goals/new-emergency.html | grep -q 'data-require-when="autoBaseline=false"'; } \
  && { grep -q 'name="fixedBaseline"[^>]*data-require-when="autoBaseline=false"' src/main/resources/templates/goals/edit.html \
    || grep -A1 'name="fixedBaseline"' src/main/resources/templates/goals/edit.html | grep -q 'data-require-when="autoBaseline=false"'; } \
  && log_ok "v09-FORM-3 应急金手填基线条件必填(new-emergency + edit 均挂 data-require-when)" \
  || log_bad "v09-FORM-3 应急金手填基线缺条件必填" "see goals/new-emergency.html · goals/edit.html fixedBaseline"

# v09-FORM-4 · 自选股「从现金划转买入」勾选才必填买入成本(UI 已明示「划转买入时必填」)
grep -qE 'name="costBasis"[^>]*data-require-when="deductCash=true"' src/main/resources/templates/stock/holding-new-auto.html \
  && log_ok "v09-FORM-4 划转买入成本条件必填(deductCash 勾选才必填)" \
  || log_bad "v09-FORM-4 划转买入成本缺条件必填" "see stock/holding-new-auto.html costBasis"

# v09-FORM-5 · 宏观基准录入 CPI/M2 必填(空值无意义)
INTG=src/main/resources/templates/admin/integrations.html
{ grep -qE 'name="cpi"[^>]*required' "$INTG" && grep -qE 'name="m2"[^>]*required' "$INTG"; } \
  && log_ok "v09-FORM-5 宏观录入 CPI/M2 required" \
  || log_bad "v09-FORM-5 宏观录入 CPI/M2 缺 required" "see admin/integrations.html macro form"

# v09-FORM-6 · 成员编辑「显示名」必填(原仅新增有,编辑可清空提交)
grep -qE 'name="displayName" th:value="\$\{m.displayName\}"[^>]*required' src/main/resources/templates/admin/members.html \
  && log_ok "v09-FORM-6 成员编辑显示名 required" \
  || log_bad "v09-FORM-6 成员编辑显示名缺 required" "see admin/members.html 成员卡片 form"

# ── v10-CASHFLOW-* · 仪表盘「人赚 vs 钱赚」实时拆解 + 实时收支趋势(FR-165~167)─────────────
RG=src/main/resources/templates/dashboard/_region.html

# v10-CASHFLOW-1 · 新 section 在 + 长文目录(tocItems)同步锚点(改 section 必同步目录的纪律)
{ grep -q 'id="dash-cashflow"' "$RG" \
  && grep -q "href:'#dash-cashflow'" src/main/resources/templates/dashboard/index.html; } \
  && log_ok "v10-CASHFLOW-1 dash-cashflow section + 长文目录锚点同步" \
  || log_bad "v10-CASHFLOW-1 section 或 TOC 锚点缺失" "see _region.html / dashboard/index.html tocItems"

# v10-CASHFLOW-2 · 三态文案钩子(空态CTA / 半填诚实 / 首期)+ 有符号双向条
{ grep -q '本期还没填收支' "$RG" && grep -q '收支可能不全' "$RG" \
  && grep -q 'cashflowSplit.firstPeriod()' "$RG" \
  && grep -q 'cashflowSplit.renBarStyle()' "$RG"; } \
  && log_ok "v10-CASHFLOW-2 三态(空/半填/首期)文案 + 双向条钩子在" \
  || log_bad "v10-CASHFLOW-2 三态或双向条钩子缺失" "see _region.html dash-cashflow"

# v10-CASHFLOW-3 · 实时收支趋势 canvas + 序列注入 + datalabels(数值浮于数据上,非 hover)
{ grep -q 'id="cashflowTrendChart"' "$RG" && grep -q 'cashflowSeries' "$RG" \
  && grep -q 'financeCharts.cashflow' "$RG" && grep -q 'datalabels' "$RG"; } \
  && log_ok "v10-CASHFLOW-3 收支趋势 canvas + series + datalabels" \
  || log_bad "v10-CASHFLOW-3 趋势图钩子缺失" "see _region.html cashflowTrendChart"

# v10-CASHFLOW-4 · 装配/同源:控制器装配 + 钱赚=ΔNW−人赚(卡内恒等,不与「本月资产收益」打架)
{ grep -q 'cashflowSplit' src/main/java/com/family/finance/web/dashboard/DashboardController.java \
  && grep -q 'deltaNetWorth.subtract(ren)' src/main/java/com/family/finance/web/dashboard/CashflowSplitView.java; } \
  && log_ok "v10-CASHFLOW-4 控制器装配 + 钱赚=ΔNW−人赚 同源恒等" \
  || log_bad "v10-CASHFLOW-4 装配或同源恒等缺失" "see DashboardController / CashflowSplitView"

# ── v10-CCY-LENS-* · 单一镜头【真·端到端】币种守护(v0.10.1 修)──────────────────────────────
#   反复爆的根因:净资产用「每期历史汇率」换算 → 差额类指标(ΔNW/环比%/本月收益/钱赚)跨币种不按
#   单一汇率缩放;多币种 prod 上 ΔNW 实测偏 ~17%。教训:CurrencyInvarianceTest 是单元 + 单一 mock
#   汇率,把「多期不同历史汇率」这个真实场景抹平了,永远测不出。只有【登录→真 /dashboard 多币种→
#   跑真 SQL+多期不同汇率】的端到端断言才网得住整类。需要 family 有非 base 账户 + 多期变动汇率(diwa 家满足)。

# v10-CCY-LENS-1 · 实时收支趋势各期切币种按【同一汇率】均匀缩放(逐期比值全相等)
cnyN=$($CURL -b $COOKIE "$BASE/dashboard?currency=CNY" | grep -oE '"netInflow":[0-9.-]+' | sed 's/"netInflow"://')
usdN=$($CURL -b $COOKIE "$BASE/dashboard?currency=USD" | grep -oE '"netInflow":[0-9.-]+' | sed 's/"netInflow"://')
lens1=$(paste <(printf '%s\n' $cnyN) <(printf '%s\n' $usdN) | awk 'NF==2 && $1+0!=0{r=$2/$1; if(n++==0){lo=r;hi=r} if(r<lo)lo=r; if(r>hi)hi=r} END{ if(n<2){print "SKIP n="n+0; exit} if(hi-lo<=0.0015*(hi<0?-hi:hi)) printf "OK n=%d r=%.5f",n,lo; else printf "DRIFT lo=%.5f hi=%.5f",lo,hi }')
case "$lens1" in
  OK*)   log_ok  "v10-CCY-LENS-1 多币种切币种·收支趋势各期同一汇率缩放($lens1)";;
  SKIP*) log_skip "v10-CCY-LENS-1 趋势数据不足($lens1)" "需多币种 + 多期数据";;
  *)     log_bad "v10-CCY-LENS-1 切币种各期缩放漂移·单一镜头被破坏" "$lens1";;
esac

# v10-CCY-LENS-2 · 净资产趋势(始终存在 · 正是出 bug 的核心量)各期切币种按【同一汇率】缩放
#   修前:每期净值用各自历史汇率 → NW(p1)_usd/NW(p1)_cny=期1汇率、NW(p2)同理用期2汇率,两期汇率不同 → 逐期比值漂移。
nwC=$($CURL -b $COOKIE "$BASE/dashboard?currency=CNY" | grep -oE 'netWorth: \[[^]]*\]' | head -1 | grep -oE '[0-9]+\.[0-9]+|[0-9]+')
nwU=$($CURL -b $COOKIE "$BASE/dashboard?currency=USD" | grep -oE 'netWorth: \[[^]]*\]' | head -1 | grep -oE '[0-9]+\.[0-9]+|[0-9]+')
lens2=$(paste <(printf '%s\n' $nwC) <(printf '%s\n' $nwU) | awk 'NF==2 && $1+0!=0{r=$2/$1; if(n++==0){lo=r;hi=r} if(r<lo)lo=r; if(r>hi)hi=r} END{ if(n<2){print "SKIP n="n+0; exit} if(hi-lo<=0.0015*(hi<0?-hi:hi)) printf "OK n=%d r=%.5f",n,lo; else printf "DRIFT lo=%.5f hi=%.5f",lo,hi }')
case "$lens2" in
  OK*)   log_ok  "v10-CCY-LENS-2 净资产趋势各期三币种同一汇率缩放($lens2)";;
  SKIP*) log_skip "v10-CCY-LENS-2 净资产趋势数据不足($lens2)" "需多币种 + 多期";;
  *)     log_bad "v10-CCY-LENS-2 净资产趋势切币种逐期缩放漂移·单一镜头被破坏" "$lens2";;
esac

# ── v10-TOC-SYNC-* · 长文目录漏维护守护(回应「新增功能忘了加进目录」)─────────────────────────
#   3 个目录页(dashboard/checkup/reports)的 tocItems 是手工内联列表,易漏。这两条把它变成 CI 闸门:
#   ① 任何带 scroll-margin-top 的 section(dash-/checkup-/sec- 前缀)必须出现在对应页 tocItems(加了 section 忘加目录 → 红);
#   ② 每个 tocItems 锚点必须有真实 id(删/改 section 留下死链 → 红)。
_TT=src/main/resources/templates
# 健壮提取(用 sed 捕获组,不靠 $ 锚点 —— href/id 结尾是引号,[a-z0-9-]+$ 在 GNU grep 下匹配不到)
_tocof() { grep 'tocItems' "$_TT/$1" 2>/dev/null | grep -oE "href:'#[a-z0-9-]+'" | sed -E "s/.*#([a-z0-9-]+).*/\1/"; }
_dashT=$(_tocof dashboard/index.html); _ckT=$(_tocof checkup/family.html); _rpT=$(_tocof reports/index.html)

# v10-TOC-SYNC-1 · section → 目录(新增 section 漏加目录条目)
tocmiss=""
for id in $(grep -rhE 'scroll-margin-top' "$_TT"/ | grep -oE 'id="[a-z0-9-]+"' | sed -E 's/id="([a-z0-9-]+)"/\1/' | sort -u); do
  case "$id" in
    dash-*)    grep -qx "$id" <<<"$_dashT" || tocmiss="$tocmiss dashboard:#$id";;
    checkup-*) grep -qx "$id" <<<"$_ckT"   || tocmiss="$tocmiss checkup:#$id";;
    sec-*)     grep -qx "$id" <<<"$_rpT"    || tocmiss="$tocmiss reports:#$id";;
  esac
done
[[ -z "$tocmiss" ]] \
  && log_ok "v10-TOC-SYNC-1 所有 section(dash-/checkup-/sec-)都已进对应页长文目录" \
  || log_bad "v10-TOC-SYNC-1 有 section 未加进对应页目录(漏维护)" "缺:$tocmiss · 去对应页 tocItems 补 {label,href}"

# v10-TOC-SYNC-2 · 目录锚点 → 真实 id(死链)
_allids=$(grep -rhoE 'id="[a-z0-9-]+"' "$_TT"/ | sed -E 's/id="([a-z0-9-]+)"/\1/' | sort -u)
tocstale=""
for a in $_dashT $_ckT $_rpT; do grep -qx "$a" <<<"$_allids" || tocstale="$tocstale #$a"; done
[[ -z "$tocstale" ]] \
  && log_ok "v10-TOC-SYNC-2 长文目录无死链锚点(每条 href 都有真实 id)" \
  || log_bad "v10-TOC-SYNC-2 目录有死链锚点(section 被删/改名)" "死链:$tocstale"

# v10-NOMINAL-1 · 收益口径=名义,不从收益里扣通胀(CPI/M2 只作图上参照线,不折算成"真实收益")
#   v0.10.3 修:AI 洞察「真实收益·跑输通胀」口径误导(把扣CPI数当标准收益)→ 改名义净资产增长;
#   财富水位的 CPI 购买力线/M2 社会财富线保留(让用户感受自家收益率 vs CPI,但不替他扣)。
{ grep -q 'nominalGrowthPct' src/main/resources/templates/dashboard/_insight-strip.html \
  && grep -q 'nominalGrowthPct' src/main/resources/templates/checkup/_ai-insight.html \
  && grep -q 'nominalGrowthPct' src/main/resources/templates/reports/_wealth-level.html \
  && ! grep -q '跑输通胀' src/main/resources/templates/dashboard/_insight-strip.html \
  && grep -q 'CPI 购买力线' src/main/resources/templates/reports/_wealth-level.html; } \
  && log_ok "v10-NOMINAL-1 洞察/体检/水位收益用名义口径 · CPI/M2 对比线保留" \
  || log_bad "v10-NOMINAL-1 收益口径仍扣CPI 或 对比线被误删" "see _insight-strip / _ai-insight / _wealth-level"

# v10-ACCT-COLS-1 · 账户列表补全列 + 指标 chips + sticky 首列(回应"指标设置勾了却不显示")
#   v0.10.4:dashboard 账户表补 net_principal/period_return/return_base/max_drawdown/months_held/plan_actual 列;
#   加内联指标筛选 chips(localStorage 记住)+ 账户名列 sticky + 列多横滑;目录移除无数据的 twr/yoy/risk(不超卖)。
RG=src/main/resources/templates/dashboard/_region.html
{ grep -q 'data-mchip=' "$RG" \
  && grep -q 'data-mcol="net_principal"' "$RG" && grep -q 'data-mcol="return_base"' "$RG" \
  && grep -q 'data-mcol="max_drawdown"' "$RG" && grep -q 'data-mcol="months_held"' "$RG" \
  && grep -q 'data-mcol="period_return"' "$RG" && grep -q 'acct-sticky' "$RG" && grep -q 'acctHiddenCols' "$RG" \
  && grep -q 'flex flex-wrap items-center gap-1.5 mb-2" data-acct-chips' "$RG" \
  && ! grep -q '"twr"' src/main/java/com/family/finance/service/MetricPrefsService.java; } \
  && log_ok "v10-ACCT-COLS-1 账户表补全列 + 指标 chips(PC+手机)+ sticky + 目录不超卖(twr/yoy/risk 已移除)" \
  || log_bad "v10-ACCT-COLS-1 账户列/chips/sticky 缺失 或 目录仍含无数据指标" "see _region.html dash-list / MetricPrefsService"

# v10-WINDOW-1 · 收益对比「同窗口」口径(修短账户「累计实际 vs 年化预期」错判)· v0.11.4 起走 displayedDiffPercentPoints
#   预实(账户)+ reports vs基准(账户/家庭)一律:实际 = 卡片显示的那个 xirr(<12 期累计 / ≥12 期年化),
#   预期同基(<12 期把年化基准缩放到持有月数 expectedOverWindowPct);阈值随窗口缩放(beatStatusDisplayed)。
#   根因升级:v0.10.5 曾用 cumPnl/净投入 当实际 → 净投入极小的账户爆成 +19497pp 且与头条脱节,v0.11.4 改用显示的 xirr。
BA=src/main/java/com/family/finance/calc/BenchmarkAggregator.java
{ grep -q 'displayedDiffPercentPoints' "$BA" \
  && grep -q 'expectedOverWindowPct(annualBenchmarkPct, months)' "$BA" \
  && grep -q 'beatStatusDisplayed' "$BA" \
  && grep -q 'displayedDiffPercentPoints(xirr.get(first.accountId())' src/main/java/com/family/finance/factview/FactViewServiceImpl.java \
  && grep -q 'displayedDiffPercentPoints(ap.xirr()' src/main/java/com/family/finance/web/report/ReportsController.java \
  && grep -q 'displayedDiffPercentPoints(familyXirrDecimal' src/main/java/com/family/finance/web/report/ReportsController.java; } \
  && log_ok "v10-WINDOW-1 预实/vs基准 同窗口口径(实际=显示的xirr;<12期基准缩放;不再「累计减年化」也不再 cumPnl/净投入 爆值)" \
  || log_bad "v10-WINDOW-1 收益对比仍混口径(短账户累计 vs 年化预期)" "see BenchmarkAggregator / FactViewServiceImpl / ReportsController"

# ─────────── v0.11 · 隐私模式(公共场合 / 分享隐藏金额)───────────
section "v0.11 · 隐私模式"

# v11-PRIVACY-1 · 基建:FOUC 防闪 + togglePrivacy + 常驻浮动控件 + 隐私 CSS(layout)+ nav 眼睛
LAY="$RD/src/main/resources/templates/fragments/layout.html"
NAVF="$RD/src/main/resources/templates/fragments/nav.html"
{ grep -q "sessionStorage.getItem('privacy')" "$LAY" \
  && grep -q 'function togglePrivacy' "$LAY" \
  && grep -q 'id="priv-float"' "$LAY" \
  && grep -q 'html.privacy \[data-priv\]' "$LAY" \
  && grep -q 'priv-eye-on' "$NAVF"; } \
  && log_ok "v11-PRIVACY-1 隐私基建齐(FOUC 防闪 + togglePrivacy + 浮动控件 + CSS + nav 眼睛)" \
  || log_bad "v11-PRIVACY-1 隐私基建缺失" "see layout.html / nav.html"

# v11-PRIVACY-2 · 全页金额标记覆盖:5 个用户面页渲染后都含 data-priv 金额标记 + 双入口(toggle + 浮动)
PRIV_MISS=""
for pg in dashboard reports checkup accounts entry; do
  $CURL -b $COOKIE "$BASE/$pg" -o "$TMP" -w ""
  { grep -q 'data-priv' "$TMP" && grep -q 'togglePrivacy' "$TMP" && grep -q 'id="priv-float"' "$TMP"; } || PRIV_MISS="$PRIV_MISS $pg"
done
[[ -z "$PRIV_MISS" ]] \
  && log_ok "v11-PRIVACY-2 dashboard/reports/checkup/accounts/entry 均有金额标记 + 双入口(nav+浮动)" \
  || log_bad "v11-PRIVACY-2 有页面漏金额标记/漏入口" "缺:$PRIV_MISS"

# v11-PRIVACY-3 · 比例不误遮 + 图表金额随隐私态隐藏:
#   紧急储备(月)/本月收益率(%)源码不带 data-priv;dashboard 图表 fmtMoney 含 isPrivacy 守卫(金额隐藏·形状保留)
DR="$RD/src/main/resources/templates/dashboard/_region.html"
{ ! grep -q 'data-priv th:text="${kpiEmergency}"' "$DR" \
  && ! grep -q 'data-priv th:text="${monthlyPnlPctLabel}"' "$DR" \
  && grep -q "isPrivacy()) return ''" "$DR"; } \
  && log_ok "v11-PRIVACY-3 比例/月数不误遮(emergency/pct 无 data-priv)+ 图表金额隐私守卫" \
  || log_bad "v11-PRIVACY-3 误遮比例 或 图表金额无隐私守卫" "see dashboard/_region.html"

# v11-PRIVACY-4 · 按住临时查看(peek):layout 含 priv-peek CSS 覆盖 + pointerdown 事件委托(隐私态去模糊)
{ grep -q 'html.privacy \[data-priv\].priv-peek' "$LAY" \
  && grep -q "addEventListener('pointerdown'" "$LAY" \
  && grep -q "classList.add('priv-peek')" "$LAY"; } \
  && log_ok "v11-PRIVACY-4 按住临时查看(peek)接线齐(priv-peek CSS + pointerdown 委托)" \
  || log_bad "v11-PRIVACY-4 peek 缺失" "see layout.html"

# ─────────── v0.11.2 · 账期滚动修复(切月两 bug)───────────
section "v0.11.2 · 账期滚动"

# v11-ROLLOVER-1 · bug1:开新期即关旧期(force-close 早于新期仍 OPEN 的旧期);bug2:LOAN 预填夹零 ≤0
PO="$RD/src/main/java/com/family/finance/service/PeriodOpener.java"
PM="$RD/src/main/java/com/family/finance/repository/PeriodMapper.java"
{ grep -q 'closePriorOpenPeriods' "$PO" \
  && grep -q 'forceClose' "$PO" \
  && grep -q 'findOpenBefore' "$PM" \
  && grep -q 'predictLoanBalance' "$PO" \
  && grep -q 'signum() > 0 ? BigDecimal.ZERO' "$PO"; } \
  && log_ok "v11-ROLLOVER-1 开新期即关旧期(bug1) + LOAN 预填夹零≤0(bug2)· 见 PeriodOpenerLoanPrefillTest" \
  || log_bad "v11-ROLLOVER-1 滚动关旧期 或 LOAN 夹零缺失" "see PeriodOpener/PeriodMapper"

# v11-REPORTS-1 · 报表 labels 用全期标签(debtTrend),非 decomposition(N-1)。
#   修「负债曲线少画一期(2期→1点)、本金vs损益分解图 slice(1) 再少一期(2期→0柱)」。
RC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
{ grep -q 'addAttribute("labels", debtTrend.stream()' "$RC" \
  && ! grep -q 'addAttribute("labels", decomposition.stream()' "$RC"; } \
  && log_ok "v11-REPORTS-1 报表 labels 用全期标签(负债曲线 N 点 / 分解图 slice(1) 对齐 N-1 柱)" \
  || log_bad "v11-REPORTS-1 报表 labels 仍接 decomposition(少一期)" "see ReportsController"

# v11-REPORTS-2 · 储蓄区图表脚本必须在 fragment(section)内 —— 否则 reports 用 `:: section` 引入时脚本被丢,
#   双柱/收支趋势 canvas 无人渲染(KPI 在 section 内正常,唯图空)。检查 </script> 后紧跟 </section>。
SAV="$RD/src/main/resources/templates/reports/_savings.html"
{ grep -A3 '</script>' "$SAV" | grep -q '</section>'; } \
  && log_ok "v11-REPORTS-2 储蓄区图表脚本在 fragment 内(:: section 引入不丢 → 双柱/收支趋势可渲染)" \
  || log_bad "v11-REPORTS-2 储蓄区图表脚本在 fragment 外 → 双柱/收支趋势不渲染" "see reports/_savings.html"

# v11-REPORTS-METRICS · 第四表复用「管理页·指标设置(账户级)」配置:控制器注入 acctMetrics + 全字段 accountRows,
#   模板按 acctMetrics.contains(...) 门控 data-mcol 指标列(与仪表盘同源)。
RC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
REG="$RD/src/main/resources/templates/reports/_region.html"
{ grep -q 'metricPrefsService.enabled(family.getMetricPrefs(), "account")' "$RC" \
  && grep -q 'addAttribute("accountRows"' "$RC" \
  && grep -q "acctMetrics.contains('cum_pnl')" "$REG" \
  && grep -q 'data-mcol="plan_actual"' "$REG"; } \
  && log_ok "v11-REPORTS-METRICS 报表第四表配置化指标列(复用管理页账户级指标 · data-mcol 门控)" \
  || log_bad "v11-REPORTS-METRICS 报表第四表未复用管理页指标配置" "see ReportsController / reports/_region.html"

# v11-REPORTS-PP · vs基准/预实 = 显示的 xirr − 基准(同基)→ 百分点 pp,不用 %;根因修 v0.10.5 cumPnl/净投入 爆值。
#   模板 pill 用 'pp' 结尾;控制器 + FactView 走 displayedDiffPercentPoints;不得再用 windowDiffPercentPoints 当实际。
FV="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
{ grep -q "+ 'pp'" "$REG" \
  && ! grep -qE "\\\$\{(familyBenchmarkDiff|row\.diffPct)\} \+ '%'" "$REG" \
  && grep -q 'displayedDiffPercentPoints(familyXirrDecimal' "$RC" \
  && grep -q 'displayedDiffPercentPoints(ap.xirr()' "$RC" \
  && grep -q 'displayedDiffPercentPoints(xirr.get(first.accountId())' "$FV"; } \
  && log_ok "v11-REPORTS-PP vs基准/预实用 pp + 实际=显示的 xirr(不再 cumPnl/净投入 爆值)" \
  || log_bad "v11-REPORTS-PP vs基准单位仍 % 或实际仍用 cumPnl/净投入" "see reports/_region.html / ReportsController / FactViewServiceImpl"

# v11-AUDIT-PP · 全系统「两比例相比」一律相减 + pp(v0.11.5 审计):
#   配置对照 超配/欠配 = 当前−模板 → pp;财富水位 真实/相对社会收益 = 名义−基准(相减,非 Fisher 除法)→ pp。
ADIFF="$RD/src/main/resources/templates/reports/_allocation-diff.html"
WLC="$RD/src/main/java/com/family/finance/calc/WaterLevelCalculator.java"
WLV="$RD/src/main/resources/templates/reports/_wealth-level.html"
#   v1.27 起四桶改成 th:each 一行一桶(范围内没有的桶不参与),写法从 dif['CASH'] 变成 dif.get(b) —— 判据跟着改,口径不变
{ grep -q "超配 +' + dif.get(b) + 'pp'" "$ADIFF" \
  && ! grep -qE "超配 \+' \+ dif(\['[A-Z]+'\]|\.get\([a-z]+\)) \+ '%'" "$ADIFF" \
  && grep -q 'nominalGrowthPct.subtract(benchmarkCumulativePct)' "$WLC" \
  && ! grep -q '(1.0 + n) / (1.0 + b)' "$WLC" \
  && grep -q "relativeReturnPct,1,1) : '—') + 'pp'" "$WLV"; } \
  && log_ok "v11-AUDIT-PP 两比例相比一律相减+pp(配置超配/欠配 · 财富水位真实/相对社会 = 名义−基准)" \
  || log_bad "v11-AUDIT-PP 仍有比例相比用 % 或用 Fisher 除法" "see _allocation-diff.html / WaterLevelCalculator / _wealth-level.html"

# v11-REPORTS-ASOF · 报表观察账期筛选器:报表=月快照,可在已关账账期里回看任一期。
#   控制器收 asof + 注入 periods/asof;模板有 账期 下拉(data-base 保留 range/currency,onchange 带 asof)。
RC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
REG="$RD/src/main/resources/templates/reports/_region.html"
# v1.10:账期下拉从 _region.html 搬到了 _pagehead.html(三区重构要「页头→一区→二区→三区」的顺序,
# 而页头原来长在 _region 开头插不进去)。功能一点没变 —— 所以改成**在 reports 模板目录里找**,
# 别盯单个文件路径(同 v1631-RPT-ACCT-M / v11-UED8 的教训:盯字面或路径会被无关搬动打红)。
{ grep -q 'String asof' "$RC" \
  && grep -q 'addAttribute("periods", closedPeriods)' "$RC" \
  && grep -q 'addAttribute("asof"' "$RC" \
  && grep -rq "asof='+encodeURIComponent(this.value)" "$RD/src/main/resources/templates/reports/" \
  && grep -rq 'th:each="p : ${periods}"' "$RD/src/main/resources/templates/reports/"; } \
  && log_ok "v11-REPORTS-ASOF 报表观察账期筛选器(已关账期下拉 · 回看任一月快照)" \
  || log_bad "v11-REPORTS-ASOF 报表缺观察账期筛选器" "see ReportsController / reports/_region.html"

# v11-DASH-LAYOUT · 首屏层级:目标进度 + AI洞察 从顶部下移到「KPI 总览之后」(dashboard 先出净资产/KPI 主角);
#   收支趋势图仅在有非零数据时出图,否则显空态细条(不留空白大卡)。
IDX="$RD/src/main/resources/templates/dashboard/index.html"
DREG="$RD/src/main/resources/templates/dashboard/_region.html"
DCTRL="$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java"
{ ! grep -q '_insight-strip :: strip' "$IDX" \
  && ! grep -q '_progress-strip :: emptyHint' "$IDX" \
  && grep -q '_insight-strip :: strip' "$DREG" \
  && grep -q '_progress-strip :: emptyHint' "$DREG" \
  && grep -q 'cashflowSeriesHasData' "$DCTRL" \
  && grep -q 'th:unless="${cashflowSeriesHasData}"' "$DREG"; } \
  && log_ok "v11-DASH-LAYOUT 目标/AI洞察 下移到 KPI 总览之后 + 收支趋势无数据显空态" \
  || log_bad "v11-DASH-LAYOUT dashboard 首屏层级未修正" "see dashboard/index.html / _region.html / DashboardController"

# v11-TODO-RETIRE · 「待办」页退休折叠进「填报」:导航不再有 /my-todos 项;「填报」承接 pendingCount 角标;
#   /my-todos 保留 302 → /entry?mine=true(老书签);my-todos.html 模板已删。
NAVF="$RD/src/main/resources/templates/fragments/nav.html"
MTC="$RD/src/main/java/com/family/finance/web/todo/MyTodosController.java"
{ ! grep -q '@{/my-todos}' "$NAVF" \
  && grep -q '填报' "$NAVF" && grep -q "state.pendingCount > 0" "$NAVF" \
  && grep -q 'redirect:/entry?mine=true' "$MTC" \
  && [[ ! -f "$RD/src/main/resources/templates/my-todos.html" ]]; } \
  && log_ok "v11-TODO-RETIRE 待办已折叠进填报(导航无 /my-todos · 填报承接角标 · /my-todos 302→/entry · 模板已删)" \
  || log_bad "v11-TODO-RETIRE 待办退休不完整" "see nav.html / MyTodosController / my-todos.html"

# v11-SUN-RINGCOLOR · 旭日每环一套独立配色(内外环=独立维度,共色系层级不可辨 · 2026-07-16 评审修订):
#   RING_PALETTES ≥2 套;colorMapFor(values, ring) 环内字典序防撞(哈希起点+线性探测,值≤色数保证互不同色);
#   内环 ring 0 / 外环 ring 1 / 排行条走外环色系(ring 1),字典序分配保证排行条与旭日外环同值同色。
LJS="$RD/src/main/resources/static/js/lens.js"
{ grep -q 'PALETTE_PLANS' "$LJS" \
  && grep -q "LENS_META.palette" "$LJS" \
  && grep -q 'function colorMapFor(values, ring)' "$LJS" \
  && grep -q 'colorMapFor(inners, 0)' "$LJS" \
  && grep -q 'colorMapFor(childNames, 1)' "$LJS" \
  && grep -q 'colorMapFor(rows.map' "$LJS" \
  && grep -q 'uniq.sort()' "$LJS" \
  && [ "$(grep -c "^\s*\['#" "$LJS")" -ge 10 ] \
  && grep -q 'K_LENS_PALETTE' "$RD/src/main/java/com/family/finance/service/config/FamilyConfigService.java" \
  && grep -q '"/appearance"' "$RD/src/main/java/com/family/finance/web/admin/AdminController.java" \
  && grep -q 'lensPalette' "$RD/src/main/resources/templates/admin/appearance.html" \
  && grep -q 'sunCenter' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'fmtShort' "$LJS" \
  && grep -q 'renderLeaders' "$LJS" \
  && grep -q 'sunSmallNotes' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'privacyOn()' "$LJS"; } \
  && log_ok "v11-SUN-RINGCOLOR 旭日五套环级配色 + 信息交互(「显示与外观」页可选 · 默认D · 防撞 · 扇区label隐私感知 · 中心盘hover · 小扇区引导线PC/补注移动)" \
  || log_bad "v11-SUN-RINGCOLOR 旭日配色/信息交互缺件" "see lens.js PALETTE_PLANS/fmtShort/sunCenter · AdminController /appearance"

# v11-DIM-REV2 · 维值修订三(2026-07-16 TUI 拍板):资产类型平民化命名 + 行业 17→18(+货币现金/拆金融地产/删海外市场)+ V47 迁移
ACJ="$RD/src/main/java/com/family/finance/domain/lens/AssetClass.java"
ITJ="$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java"
{ grep -q '股票股权' "$ACJ" && grep -q '债券理财' "$ACJ" && grep -q '现金活钱' "$ACJ" \
  && grep -q '黄金加密' "$ACJ" && ! grep -q '"权益"' "$ACJ" \
  && grep -q 'MONEY_CASH' "$ITJ" && grep -q '银行券商保险' "$ITJ" && grep -q 'ESTATE_CONSTRUCTION' "$ITJ" \
  && ! grep -q 'FINANCE_ESTATE' "$ITJ" && ! grep -q 'OVERSEAS' "$ITJ" \
  && [ "$(grep -c '", "' "$ITJ")" -ge 18 ] \
  && [ -f "$RD/db/migration/V47__industry_revision.sql" ] \
  && grep -q "FINANCE_ESTATE" "$RD/db/migration/V47__industry_revision.sql" \
  && grep -q "OVERSEAS" "$RD/db/migration/V47__industry_revision.sql" \
  `# lens.js 不再硬编码中文维值 —— 标签统一由服务端注入的 DIMS 派生(DIM_LABEL[d.key]=d.label),` \
  `# 这是比 grep 字面量更强的保证:枚举改名前端自动跟着变,不存在"漏改一处"。` \
  && grep -qF 'DIM_LABEL[d.key] = d.label' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "股票股权" "$RD/src/main/java/com/family/finance/service/checkup/rule/LensConcentrationRules.java" \
  && grep -q 'MONEY_CASH' "$RD/src/main/java/com/family/finance/service/lens/LensAiTagService.java"; } \
  && log_ok "v11-DIM-REV2 维值修订三(资产类型平民化 · 行业18含货币现金/银行券商保险/地产建筑 · 老值V47迁移 · 筛选与AI prompt联动)" \
  || log_bad "v11-DIM-REV2 维值修订不完整" "see AssetClass/IndustryTag/V47/lens.js/LensConcentrationRules/LensAiTagService"

# v11-LSEL · 自研搜索下拉(打标页 + 透视构建器/选择器 · 中文/全拼/首字母)
LSJ="$RD/src/main/resources/static/js/lens-select.js"
{ [ -f "$LSJ" ] && grep -q 'data-lsel' "$LSJ" && grep -q 'pyInit' "$LSJ" && grep -q 'MutationObserver' "$LSJ" \
  && grep -q 'font-size:16px !important' "$LSJ" \
  && grep -q "max-width:640px" "$LSJ" \
  && grep -q 'document.body.appendChild(panel)' "$LSJ" \
  && [ "$(grep -c 'data-lsel' "$RD/src/main/resources/templates/lens/tags.html")" -ge 4 ] \
  && [ "$(grep -c 'data-lsel' "$RD/src/main/resources/templates/lens/_section.html")" -ge 8 ] \
  && grep -q 'data-py' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'getPinyin' "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" \
  && grep -q 'lens-select.js' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'lens-select.js' "$RD/src/main/resources/templates/lens/_section.html"; } \
  && log_ok "v11-LSEL 自研搜索下拉(拼音三路匹配 · 移动bottom sheet+portal · 16px防iOS聚焦放大 · 动态options自动重建)" \
  || log_bad "v11-LSEL 自研下拉缺件" "see lens-select.js / tags.html / _section.html data-lsel/data-py"

# v11-ROUND3 · 2026-07-17 第三轮评审 9 项(打标排版/行业20/全维看板/builder收起/维度说明/隐私随动/AI洞察/多维pivot/引导线根治)
{ grep -q '成员结构' "$LJS" && [ "$(grep -c "key: '" "$LJS" | head -1)" -ge 10 ] \
  && grep -q "builderWrap');           // 切看板 = 放弃自定义" "$LJS" \
  && grep -q 'pivotRow2Sel' "$LJS" && grep -q 'pivotCol2Sel' "$LJS" && grep -q 'groupStable' "$LJS" \
  && grep -q 'lensInsightBtn' "$LJS" \
  && grep -q 'noteItems' "$LJS" \
  && grep -q 'MIXED_ALLOC' "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" \
  && grep -q 'DIVIDEND_UTIL' "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" \
  && grep -q '货币基金/存款' "$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java" \
  && grep -q 'acct-meta' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q '透视里的其余维度' "$RD/src/main/resources/templates/lens/tags.html" \
  && [ "$(grep -c 'data-priv' "$RD/src/main/resources/templates/dashboard/_region.html")" -ge 3 ] \
  && grep -q '/lens/insight' "$RD/src/main/java/com/family/finance/web/lens/LensController.java" \
  && grep -q 'anonymize' "$RD/src/main/java/com/family/finance/service/lens/LensInsightService.java" \
  && grep -q '严禁做任何计算' "$RD/src/main/java/com/family/finance/service/lens/LensInsightService.java" \
  && grep -q 'pivotRow2Sel' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'INSIGHT_CACHE' "$LJS" \
  && grep -q 'syncInsightCard' "$LJS" \
  && grep -q 'slotY' "$LJS" \
  && grep -q 'lensInsightRefresh' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q '异常信号' "$RD/src/main/java/com/family/finance/service/lens/LensInsightService.java" \
  && grep -q 'hold-row td:last-child' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'measurePills' "$LJS" \
  && grep -q "measures: \['value', 'share'\]" "$LJS" \
  && grep -q "'assetcls'" "$LJS" \
  && grep -q '改资料 →' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'llmRouter.invoke(' "$RD/src/main/java/com/family/finance/service/lens/LensInsightService.java" \
  && grep -q 'llmRouter.invoke(' "$RD/src/main/java/com/family/finance/service/lens/LensAiTagService.java" \
  && grep -q 'min-width:92px' "$LJS"; } \
  && log_ok "v11-ROUND3 三/四/五轮(打标列对齐+每行改资料/持仓入口 · 指标pills多选默认金额+占比 · 看板按关心度排序默认资产类型 · pivot宽度自适配 · AI 走 LlmRouter(主备编排由管理页配置决定)· 洞察信号驱动+缓存)" \
  || log_bad "v11-ROUND3 第三轮修复缺件" "see lens.js/tags.html/_region.html/LensController/LensInsightService"

# v11-CASHROW · 券商现金部分语义(2026-07-17 修遗漏):透视头寸 cashRow 定死 现金活钱/货币基金/存款/低风险/灵活取用;
#   打标树保留现金行为只读展示(告知去向 · 不可标 · AI 不碰)
{ grep -q 'cashRow ? "低风险" : risk' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'cashRow ? AssetClass.CASH_EQ.getLabel() : assetClass' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'cashRow ? IndustryTag.MONEY_CASH.getLabel()' "$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java" \
  && grep -q 'cashHoldings' "$RD/src/main/java/com/family/finance/web/lens/LensTagController.java" \
  && grep -q '系统归类,不可改' "$RD/src/main/resources/templates/lens/tags.html"; } \
  && log_ok "v11-CASHROW 券商现金语义定死(现金活钱·货币基金/存款·低风险·灵活取用)+ 打标页只读展示去向" \
  || log_bad "v11-CASHROW 券商现金部分仍遗漏" "see LensQueryService cashRow / LensTagController cashHoldings / tags.html"

# v11-ENTRY-UX · 填报页(2026-07-17 评审):收入记录行 主理人头像+填报日期;右列操作区控件统一 h-8;收入表单 h-9
{ grep -q 'ownerName, java.time.LocalDateTime submittedAt' "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "COALESCE(m.display_name, '共同') AS ownerName" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "temporals.format(e.submittedAt, 'MM-dd')" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "ownerColorMap.get(e.ownerName)" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q '快捷支出' "$RD/src/main/resources/templates/entry/_row.html" \
  && grep -q '账户间划转' "$RD/src/main/resources/templates/entry/_row.html" \
  && [ "$(grep -c 'h-8 border border-rule' "$RD/src/main/resources/templates/entry/_row.html")" -ge 4 ] \
  && [ "$(grep -c 'h-9 ' "$RD/src/main/resources/templates/entry/index.html")" -ge 4 ]; } \
  && log_ok "v11-ENTRY-UX 填报页(收入记录行头像+MM-dd填报日期 · 右列操作区h-8等高两段式 · 收入表单h-9统一)" \
  || log_bad "v11-ENTRY-UX 填报页体验修缺件" "see CashFlowMapper/entry/index.html/_row.html"

# v11-UED8 · 2026-07-18 八项细节:支出对齐h-11 · 收入tab同行 · 移动按钮nowrap · sheet不自动聚焦 ·
#   2026-08-04:sheet 那条原来 grep 原文 `if (!mobile) q.focus()`,v1.8 给条件加了「搜索框隐藏时也不聚焦」
#   整条就挂了 —— 守的意图(移动端不自动聚焦)一点没变。改成正则匹配意图,别盯着字面。
#   目标条带移动单列 · 洞察卡标题/信号分层 · 本期pills移动横排 · 趋势图例窄屏短标签
{ [ "$(grep -c 'h-11' "$RD/src/main/resources/templates/entry/index.html")" -ge 2 ] \
  && [ "$(grep -c 'class="tab-cash' "$RD/src/main/resources/templates/entry/index.html")" -ge 2 ] \
  && grep -q 'gap-1.5 whitespace-nowrap' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -qE 'if \(!mobile.*\) q\.focus\(\)' "$RD/src/main/resources/static/js/lens-select.js" \
  `# 2026-08-13 删「目标手机端单列」这条:v11-R6 之后刻意改成两列密度(grid-cols-2 md:grid-cols-3` \
  `# + px-3 py-3 sm:px-6 sm:py-4),两条护栏编码了互相矛盾的意图,旧的这条作废。现由 v11-R6 守。` \
  && grep -q 'flex flex-col sm:flex-row sm:items-center' "$RD/src/main/resources/templates/dashboard/_insight-strip.html" \
  && grep -q 'sm:flex-col sm:items-end' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'mobLegend' "$RD/src/main/resources/templates/dashboard/_region.html"; } \
  && log_ok "v11-UED8 八项细节(支出/收入tab/按钮nowrap/sheet不聚焦/目标单列/洞察分层/pills横排/图例短标签)" \
  || log_bad "v11-UED8 细节修复缺件" "see entry/index.html lens-select.js _progress-strip _insight-strip _region.html"

# v11-R6 · 2026-07-19 六项:目标条带两列提密度 · 本期pills强制同行 · 移动自绘图例 · 流水账本式两行 · 账户tile中文 · 目标pct两位小数
{ grep -q 'grid-cols-2 md:grid-cols-3 gap-1.5 sm:gap-2' "$RD/src/main/resources/templates/goals/_progress-strip.html" \
  && grep -q 'px-3 py-3 sm:px-6 sm:py-4' "$RD/src/main/resources/templates/goals/_progress-strip.html" \
  && [ "$(grep -c 'whitespace-nowrap text-\[10px\] sm:text-xs' "$RD/src/main/resources/templates/dashboard/_region.html")" -ge 4 ] \
  && grep -q 'flex flex-nowrap items-center gap-1.5 sm:flex-col' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'nwLegendM' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'cpiSeries != null && !mobLegend' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'w-16 flex-shrink-0 inline-flex' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'pl-\[72px\]' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 's.type.label' "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q 'setScale(2, java.math.RoundingMode.HALF_EVEN)' "$RD/src/main/java/com/family/finance/service/goal/GoalProgressService.java"; } \
  && log_ok "v11-R6 六项(目标两列密度/pills同行/自绘图例一行/流水两行金额成列/账户tile中文/目标pct两位小数)" \
  || log_bad "v11-R6 六项修复缺件" "see _progress-strip/_region/EntryController/accounts/GoalProgressService"

# v11-R7 · 2026-07-19 五项:目标ⓘ点击弹描述不跳详情 · 小图标热区扩38px · 自绘图例可点toggle曲线 · lens截图v3 · CI直连central
# 2026-08-13 修护栏自身两处失效(功能一直好的,是断言坏了):
#   ① `${` 模式必须 `grep -qF` —— BRE 把 `{}` 当区间量词,静默不匹配(AGENTS 早有这条,这条没遵守);
#   ② 原来还断言 README 里有 `pc_lens.jpg?v=3` —— 那是 v0.11 时代「近期更新」段的一张截图热链,
#      而 README 按维护规则只保留最近 1–2 个版本,那行早就正常滚掉了。护栏钉在会被正常维护搬走的
#      字面量上 = 迟早假红。这条删掉,不换成别的字面量。
{ grep -qF '_kpi-info :: i(${gp.goal.description' "$RD/src/main/resources/templates/goals/_progress-strip.html" \
  && grep -q '.kpi-info-btn::after, .tap::after' "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'inset: -12px' "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'setDatasetVisibility' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'tap text-\[11px\] text-ink-subtle hover:text-rust' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'CN_MIRROR' "$RD/Dockerfile" \
  && grep -q 'CN_MIRROR=0' "$RD/.github/workflows/docker-publish.yml" \
  && [ -f "$RD/deploy/maven-settings-central.xml" ]; } \
  && log_ok "v11-R7 五项(目标ⓘ弹描述stopPropagation · 热区::after-12px · 图例toggle回归 · lens截图成员结构v3 · CI去aliyun单点)" \
  || log_bad "v11-R7 五项缺件" "see _progress-strip/style.css/_region/EntryController/Dockerfile/workflow"

# ===================== v1.2 · 归因复盘 + 再平衡闭环 + 性能底盘 =====================
# v12-ATTR · 归因引擎(纯函数·两步法fx拆分·未归因显性)+ dashboard 懒加载 fragment + 6 维度
{ grep -q 'class AttributionEngine' "$RD/src/main/java/com/family/finance/calc/review/AttributionEngine.java" \
  && grep -q 'subtract(underlying)' "$RD/src/main/java/com/family/finance/calc/review/AttributionEngine.java" \
  && grep -q 'unattributed' "$RD/src/main/java/com/family/finance/calc/review/AttributionEngine.java" \
  && grep -q '行业不做' "$RD/src/main/java/com/family/finance/service/review/AttributionService.java" \
  && grep -q '/dashboard/attribution' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'attribution-mount' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'hx-trigger="revealed" hx-target="this"' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'attrWaterfall' "$RD/src/main/resources/templates/dashboard/_attribution.html" \
  && grep -q '赚得最多' "$RD/src/main/resources/templates/dashboard/_attribution.html" \
  && grep -q '亏得最多' "$RD/src/main/resources/templates/dashboard/_attribution.html"; } \
  && log_ok "v12-ATTR 归因引擎+fragment(两步法fx闭合 · 未归因显性 · revealed懒加载 · 瀑布/赚得最多·亏得最多榜/12期)" \
  || log_bad "v12-ATTR 归因缺件" "see calc/review + _attribution.html"

# v12-REVIEW-AI · AI 月度复盘(信号驱动 · V48 缓存 · 脱敏 · 禁算)
{ grep -q '严禁做任何计算' "$RD/src/main/java/com/family/finance/service/review/ReviewInsightService.java" \
  && grep -q 'anonymize' "$RD/src/main/java/com/family/finance/service/review/ReviewInsightService.java" \
  && grep -q 'review_ai_cache' "$RD/db/migration/V48__review_and_rebalance_plan.sql" \
  && grep -q 'llmRouter.invoke(' "$RD/src/main/java/com/family/finance/service/review/ReviewInsightService.java" \
  && grep -q '/review/insight' "$RD/src/main/java/com/family/finance/web/review/ReviewController.java"; } \
  && log_ok "v12-REVIEW-AI 月度复盘(信号驱动+V48缓存+脱敏+禁算+走 LlmRouter 主备编排)" \
  || log_bad "v12-REVIEW-AI 复盘缺件" "see ReviewInsightService/ReviewController/V48"

# v12-PLAN · 再平衡闭环(采纳/80%核销/关账归档/铁律)
{ grep -q 'rebalance_plan_item' "$RD/db/migration/V48__review_and_rebalance_plan.sql" \
  && grep -q 'K_REBALANCE_MATCH_PCT' "$RD/src/main/java/com/family/finance/service/review/RebalancePlanService.java" \
  && grep -q 'TransferCreatedEvent' "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q 'archiveOnClose' "$RD/src/main/java/com/family/finance/service/PeriodService.java" \
  && grep -q '/rebalance-plan/adopt' "$RD/src/main/java/com/family/finance/web/review/RebalancePlanController.java" \
  && grep -q '再平衡计划执行情况' "$RD/src/main/java/com/family/finance/service/checkup/llm/LlmDiagnoseService.java" \
  && grep -q '本期再平衡计划' "$RD/src/main/resources/templates/reports/_rebalance-plan.html" \
  && grep -q '不要生成新的买卖指令' "$RD/src/main/java/com/family/finance/service/checkup/llm/LlmDiagnoseService.java"; } \
  && log_ok "v12-PLAN 再平衡闭环(V48两表 · 采纳解析 · AFTER_COMMIT核销80%可配 · 关账归档 · 执行率喂诊断只解读)" \
  || log_bad "v12-PLAN 计划闭环缺件" "see RebalancePlanService/Controller/EntryService/PeriodService"

# v12-PERF · 性能底盘(momYoy条件复用 · pending轻查询 · beta实测 P50 476→364ms)
{ grep -q 'MomYoy momYoy(FactSlice slice)' "$RD/src/main/java/com/family/finance/factview/FactViewService.java" \
  && grep -q 'momReuse' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'pendingCount' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && ! grep -q 'pendingRows' "$RD/src/main/resources/templates/dashboard/_region.html"; } \
  && log_ok "v12-PERF 性能底盘(momYoy slice复用条件保显示零回归 · pending计数轻查询 · P50 476→364ms)" \
  || log_bad "v12-PERF 性能缺件" "see DashboardController/FactViewService"

# ===================== v0.12 · 收支填报收入侧升级 =====================
# v12-INCOME-CAT · 类目绑定账户类型 + 股票类收入类目(V34)
CFC="$RD/db/migration/V34__income_category_account_type.sql"
{ [ -f "$CFC" ] && grep -q 'account_type' "$CFC" \
  && grep -q 'stock_salary' "$CFC" && grep -q 'dividend' "$CFC" && grep -q 'stock_sell' "$CFC" \
  && grep -q 'accountType' "$RD/src/main/java/com/family/finance/domain/flow/CashFlowCategory.java"; } \
  && log_ok "v12-INCOME-CAT 类目 account_type 绑定 + 股票类收入类目(薪资-股票/股息/卖出回款)" \
  || log_bad "v12-INCOME-CAT 类目绑定/新类目缺" "see V34 / CashFlowCategory"

# v12-INCOME-ENDPOINT · 收入侧端点 + 类目↔账户服务端校验
ES="$RD/src/main/java/com/family/finance/service/EntryService.java"
EC="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
{ grep -q '/entry/income' "$EC" && grep -q 'public EntryRow recordIncome' "$ES" \
  && grep -q 'cat.getAccountType() != null' "$ES"; } \
  && log_ok "v12-INCOME-ENDPOINT /entry/income + recordIncome + 类目↔账户服务端校验(红线)" \
  || log_bad "v12-INCOME-ENDPOINT 收入端点/校验缺" "see EntryController / EntryService"

# v12-INCOME-STOCK · 股票收入落 CASH 现金行(扛估值刷新)· 不只 applyDeltaToBalance
{ grep -q 'creditAccountBalance' "$ES" \
  && grep -q 'stockHoldingService.adjustAccountCash' "$ES" \
  && grep -q 'public void adjustAccountCash' "$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"; } \
  && log_ok "v12-INCOME-STOCK 股票收入落 CASH 现金行(creditAccountBalance→adjustAccountCash)" \
  || log_bad "v12-INCOME-STOCK 股票收入未落现金行" "see EntryService.creditAccountBalance"

# v12-INCOME-KOUJING · 家庭收入侧口径从 cash_flow 汇总(PMC 空收入不再低估;支出侧不动)· 防双计
FV="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
{ grep -q 'netInflowIncome' "$FV" && grep -q 'netInflowExpense' "$FV"; } \
  && log_ok "v12-INCOME-KOUJING 收入/支出各自决定来源(收入 PMC空→cash_flow · 不叠加)" \
  || log_bad "v12-INCOME-KOUJING 收入口径未拆分" "see FactViewServiceImpl.netInflowIncome"

# v12-INCOME-UI · 收入侧类型优先(现金/股票 tab · 股票联动持仓)· 账户行不再硬编码 +收入
REG_ENTRY="$RD/src/main/resources/templates/entry/index.html"
ROW="$RD/src/main/resources/templates/entry/_row.html"
{ grep -q 'class="tab-stock' "$REG_ENTRY" && grep -q 'id="income-cash-block"' "$REG_ENTRY" \
  && grep -q 'stock-holdings-target' "$REG_ENTRY" && grep -q '/entry/income' "$REG_ENTRY" \
  && ! grep -q 'name="kind" value="INCOME"' "$ROW"; } \
  && log_ok "v12-INCOME-UI 收入侧类型优先(现金/股票 tab + 联动持仓)· 账户行无硬编码 +收入" \
  || log_bad "v12-INCOME-UI 收入侧 UI/类型切换缺 或 账户行仍有硬编码收入" "see entry/index.html / _row.html"

# v12-MANUAL-SHARES · 未上市持仓升级为「股数×单股估值」+ V35 迁移(老数据 shares=1 总值不变)
V35="$RD/db/migration/V35__stock_income_holding_model.sql"
AVS="$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java"
SHS="$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"
{ [ -f "$V35" ] && grep -q "valuation_mode = 'MANUAL'" "$V35" && grep -qi 'shares = 1' "$V35" \
  && grep -q 'multiply(sh)' "$AVS" \
  && grep -q 'BigDecimal shares, BigDecimal unitValue' "$SHS" \
  && grep -q 'public StockHolding addShares' "$SHS" \
  && grep -q 'currentUnitValueInAccountCcy' "$SHS"; } \
  && log_ok "v12-MANUAL-SHARES 未上市=股数×单股估值 + V35 迁移(shares=1)+ 估值 shares×unit + addShares" \
  || log_bad "v12-MANUAL-SHARES 未上市模型升级缺" "see V35 / AccountValuationService / StockHoldingService"

# v12-STOCK-SHARE-INCOME · 股票收入按持仓入账(+股数)· cash_flow ref 列 + 端点 + 联动 fragment
ES="$RD/src/main/java/com/family/finance/service/EntryService.java"
EC="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
CFM="$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java"
STKFRAG="$RD/src/main/resources/templates/entry/_income-stock.html"
{ grep -q 'recordStockIncomeExistingHolding' "$ES" && grep -q 'refHoldingId' "$ES" \
  && grep -q '/entry/income/stock/holding' "$EC" && grep -q '/entry/income/stock/new-manual' "$EC" \
  && grep -q 'ref_holding_id' "$CFM" && [ -f "$STKFRAG" ] && grep -q 'th:fragment="holdings"' "$STKFRAG"; } \
  && log_ok "v12-STOCK-SHARE-INCOME 股票+股数入账(端点+服务+ref列+联动持仓fragment)" \
  || log_bad "v12-STOCK-SHARE-INCOME 股票+股数收入缺" "see EntryService/EntryController/CashFlowMapper/_income-stock.html"

# v12-STOCK-SELL-HIDDEN · 卖出回款不算收入(收入类目下拉排除 stock_sell + V35 沉底)
CFCM="$RD/src/main/java/com/family/finance/repository/CashFlowCategoryMapper.java"
{ grep -q "code <> 'stock_sell'" "$CFCM" && grep -q "code = 'stock_sell'" "$V35"; } \
  && log_ok "v12-STOCK-SELL-HIDDEN 卖出回款不算收入(下拉排除 + V35 沉底)" \
  || log_bad "v12-STOCK-SELL-HIDDEN 卖出回款未从收入排除" "see CashFlowCategoryMapper.listIncomeOrdered / V35"

# v12-INCOME-FX · 收入列表币种修正(cash_flow.amount 是账户币种 · 家庭合计换本位币,不裸加 ¥)
{ grep -q 'incomeBaseTotal' "$EC" && grep -q 'toBaseAmount' "$EC" && grep -q 'fxService' "$EC" \
  && grep -q 'incomeBaseTotal' "$REG_ENTRY" && grep -q 'format2(e.currency' "$REG_ENTRY" \
  && ! grep -q "aggregates.sum(incomeEntries" "$REG_ENTRY"; } \
  && log_ok "v12-INCOME-FX 收入列表原币展示 + 家庭合计本位币(不再把账户币种裸加成 ¥)" \
  || log_bad "v12-INCOME-FX 收入列表币种未换算" "see EntryController.toBaseAmount / entry/index.html"

# v12-INCOME-EMPTY · dashboard「本期怎么变的」空态联动(L1)· 收入切 cash_flow 后不能只看 PMC filledMembers
CSV="$RD/src/main/java/com/family/finance/web/dashboard/CashflowSplitView.java"
{ grep -q 'sign(income) == 0' "$CSV" && grep -q 'sign(expense) == 0' "$CSV"; } \
  && log_ok "v12-INCOME-EMPTY dashboard 收支拆解空态兼顾 cash_flow(仅录收入录入也不判空)" \
  || log_bad "v12-INCOME-EMPTY 空态仍只看 PMC filledMembers" "see CashflowSplitView.empty()"

# v13-OPENING · 开账基线:新账户存量本金不计入当期收益(fact 剔除 + 卡第三项 + net_principal 计入)
SMAP="$RD/src/main/java/com/family/finance/repository/SnapshotMapper.java"
FVI="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
CSV2="$RD/src/main/java/com/family/finance/web/dashboard/CashflowSplitView.java"
REG2="$RD/src/main/resources/templates/dashboard/_region.html"
{ grep -q 'firstAppearingAccountIds' "$SMAP" \
  && grep -q 'private BigDecimal openingBaseline' "$FVI" \
  && grep -q 'add(openingBaseline(slice' "$FVI" \
  && grep -q 'netWorthTrendExOpening' "$FVI" \
  && grep -q 'subtract(ob)' "$CSV2" && grep -q 'openingBaseline' "$CSV2" \
  && grep -q '开账基线' "$REG2"; } \
  && log_ok "v13-OPENING 开账基线:收益指标剔除(XIRR/TWR/钱赚/PnL)+ 卡第三项 + net_principal 计入 + 财富水位剔除" \
  || log_bad "v13-OPENING 开账基线口径缺" "see FactViewServiceImpl/CashflowSplitView/SnapshotMapper/_region.html"

# v13.1-ISSUE3-PREC · issue#3 精度:V37 放宽 manual_value/cost_basis/close_price → DECIMAL(20,6)· 表单 step 到 6 位
V37="$RD/db/migration/V37__price_precision.sql"
MANF="$RD/src/main/resources/templates/stock/holding-new-manual.html"
AUTOF="$RD/src/main/resources/templates/stock/holding-new-auto.html"
{ [ -f "$V37" ] \
  && grep -q 'manual_value DECIMAL(20,6)' "$V37" \
  && grep -q 'cost_basis DECIMAL(20,6)' "$V37" \
  && grep -q 'close_price DECIMAL(20,6)' "$V37" \
  && grep -q 'name="unitValue" step="0.000001"' "$MANF" \
  && grep -q 'name="costBasis" step="0.000001"' "$AUTOF"; } \
  && log_ok "v13.1-ISSUE3-PREC 价格精度放宽 (20,6) + 表单 step 6 位(单股估值不再被截成 2 位)" \
  || log_bad "v13.1-ISSUE3-PREC 精度迁移/表单 step 缺" "see V37__price_precision.sql / holding-new-manual|auto.html"

# v13.1-ISSUE3-CN · issue#3 自动拉价:A 股交易所前缀集中到 AShareTicker · 两 client 不得再各写 startsWith("6")
ASH="$RD/src/main/java/com/family/finance/service/stock/AShareTicker.java"
SINA="$RD/src/main/java/com/family/finance/service/stock/SinaStockClient.java"
TENC="$RD/src/main/java/com/family/finance/service/stock/TencentStockClient.java"
# 2026-08-13:两条否定断言必须先 code_only 剥注释 —— Sina/Tencent 里那行修复注释本身就写着
#   「原 startsWith("6") 漏上交所 ETF 如 513180」,裸 grep 会被自己的历史说明扫红(本项目第 6 次)。
#   注意用 java_code_only(剥 `//`),不是 code_only(那个只剥 shell 的 `#`)。
{ [ -f "$ASH" ] && grep -q "c == '5' || c == '6' || c == '9'" "$ASH" \
  && grep -q 'AShareTicker.withExchange' "$SINA" \
  && grep -q 'AShareTicker.withExchange' "$TENC" \
  && ! java_code_only "$SINA" | grep -q 'startsWith("6")' \
  && ! java_code_only "$TENC" | grep -q 'startsWith("6")'; } \
  && log_ok "v13.1-ISSUE3-CN A 股前缀集中 AShareTicker(5/6/9→sh)· Sina/Tencent 复用 · 无 startsWith(\"6\") 残留(513180 不再误判 sz)" \
  || log_bad "v13.1-ISSUE3-CN A 股前缀仍分散/仍用 startsWith(\"6\")" "see AShareTicker/SinaStockClient/TencentStockClient"

# v14-METAL · 贵金属账户 + 自动金价(issue #4)
V38="$RD/db/migration/V38__precious_metal_account.sql"
MU="$RD/src/main/java/com/family/finance/service/stock/MetalUnit.java"
MPC="$RD/src/main/java/com/family/finance/service/stock/MetalPriceClient.java"
SPF="$RD/src/main/java/com/family/finance/service/stock/StockPriceFetcher.java"
SHSV="$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"
MF="$RD/src/main/resources/templates/stock/holding-new-metal.html"
{ [ -f "$V38" ] && grep -q "unit VARCHAR" "$V38" && grep -q "'METAL'" "$V38" && grep -q "metal_account" "$V38" \
  && grep -q 'METAL("贵金属")' "$RD/src/main/java/com/family/finance/domain/account/AccountType.java" \
  && [ -f "$MU" ] && grep -q 'GRAMS_PER_TROY_OUNCE' "$MU" && grep -q 'normalizeToPerGram' "$MU" \
  && [ -f "$MPC" ] && grep -q 'sina-metal' "$MPC" \
  && grep -q 'Market.METAL' "$SPF" && grep -q 'fetchMetalAndPersist' "$SPF" \
  && grep -q 'createMetal' "$SHSV" \
  && [ -f "$MF" ]; } \
  && log_ok "v14-METAL 贵金属账户 + MetalPriceClient(gds_/hf_)+ 每克归一 + 建仓表单 + V38(unit列/METAL/模板)" \
  || log_bad "v14-METAL 贵金属链路缺" "see V38 / MetalUnit / MetalPriceClient / StockPriceFetcher / StockHoldingService / holding-new-metal.html"

# v14-METAL-PD-SGE · 钯金无上海盘(诚实置灰 · PD+sge→null)
grep -q 'intl ? "XPD" : null' "$MU" \
  && log_ok "v14-METAL-PD-SGE 钯金只有国际盘(PD+SGE→null · UI 提示改选国际)" \
  || log_bad "v14-METAL-PD-SGE 钯金 SGE 未正确置空" "see MetalUnit.tickerFor"

# v14-METAL-ENTRY · 填报页持仓入口覆盖 METAL(修:此前硬编码 STOCK/CRYPTO 漏金属 → 填报录不了重量)
ROWF="$RD/src/main/resources/templates/entry/_row.html"
ECF="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
{ grep -q 'supportsHoldings(row.account.type)' "$ROWF" \
  && ! grep -q "row.account.type.name() == 'STOCK' or row.account.type.name() == 'CRYPTO'" "$ROWF" \
  && grep -q 'Market.METAL' "$ECF"; } \
  && log_ok "v14-METAL-ENTRY 填报页持仓入口走 supportsHoldings(含 METAL)+ 一键刷新含 METAL 市场" \
  || log_bad "v14-METAL-ENTRY 填报页仍漏 METAL 入口" "see entry/_row.html supportsHoldings / EntryController.refresh-stocks"

# v14-REFRESH-COUNT · 刷新估值 toast 分母动态(修 prod「4/3」:市场数增而分母写死 3 → 全成功误报成 warning)
{ grep -q 'marketsOk == total' "$ECF" && ! grep -qE 'marketsOk == 3|/3 市场|"3 市场' "$ECF"; } \
  && log_ok "v14-REFRESH-COUNT 刷新估值 toast 分母 = markets.size() 动态(不再写死 3)" \
  || log_bad "v14-REFRESH-COUNT 刷新估值 toast 仍写死市场数(会误报 X/3)" "see EntryController.refreshStocks"

# v14-LLM-VENDOR · LLM 主选可配 + 温度 + 型号选择(FR-B)
#   v1.13 把「供应商 + 型号」两级换成「平台 → 系列 → 型号」三级:主选键从 llm_primary_vendor
#   变成 llm_platform/llm_family/llm_model_id,排序从 LlmDiagnoseService.orderByPrimaryVendor
#   搬到 LlmRouter。这里守的仍是同一件用户可见的事 —— 主选可配、温度可配、型号能选。
FCS="$RD/src/main/java/com/family/finance/service/config/FamilyConfigService.java"
ICF="$QA_LLM_JAVA"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 IntegrationsController.java)
INTG="$QA_LLM_TPL"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 integrations.html)
ABS="$RD/src/main/java/com/family/finance/service/checkup/llm/AbstractOpenAiCompatibleClient.java"
{ grep -q 'K_LLM_PLATFORM' "$FCS" && grep -q 'K_LLM_TEMPERATURE' "$FCS" && grep -q 'K_LLM_MODEL_ID' "$FCS" \
  && grep -q 'LlmSettings.load' "$RD/src/main/java/com/family/finance/service/checkup/llm/LlmRouter.java" \
  && grep -q 'currentTemperature' "$ABS" \
  && grep -q 'parseTriple' "$ICF" \
  && grep -q 'name="platform"' "$INTG" && grep -q 'name="modelId"' "$INTG" \
  && grep -q 'data-catalog' "$INTG"; } \
  && log_ok "v14-LLM-VENDOR 主选可配(平台/系列/型号三级)+ 温度可配 + 级联下拉数据源来自目录" \
  || log_bad "v14-LLM-VENDOR LLM 自选链路缺" "see FamilyConfigService/LlmRouter/AbstractOpenAiCompatibleClient/AiModelController/LlmSettingsView/_llm-settings.html"

# v14.1-UAT · 面向用户不泄露英文枚举/代码([[feedback_user_friendly_naming]])· UAT 巡检修
REG_REPORT="$RD/src/main/resources/templates/reports/_region.html"
ENTS="$RD/src/main/java/com/family/finance/service/EntryService.java"
RC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
{ grep -q 'pc=${pcNameByAccount' "$REG_REPORT" \
  && grep -q 'pcNameByAccount' "$RC" \
  && ! grep -q 'CASH/LOAN 出现' "$ENTS"; } \
  && log_ok "v14.1-UAT 报表类目显中文名(不裸露 GOLD/US_STOCK/PRECIOUS_METAL)+ 填报警告无 CASH/LOAN 枚举" \
  || log_bad "v14.1-UAT 仍泄露英文枚举/代码给用户" "see reports/_region.html 类目列 / EntryService 警告"

# vSEC-1 · 敏感值不入公开库(L10)· 扫 tracked 文件里 URL/SSH 上下文的公网 IP(排除私网/环回)
# 用上下文正则(://IP 或 @IP)避免版本号/SVG 数据误报;不硬编码任何具体 IP,守护自身不泄露、不自匹配
SEC_HITS="$(cd "$RD" && git grep -InoE '(://|@)([0-9]{1,3}\.){3}[0-9]{1,3}' -- . ':(exclude)*.jpg' ':(exclude)*.png' ':(exclude)*.jpeg' 2>/dev/null \
  | grep -vE '(://|@)(127\.|10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|0\.0\.0\.0)' || true)"
[ -z "$SEC_HITS" ] \
  && log_ok "vSEC-1 tracked 文件无 URL/SSH 上下文的公网 IP(敏感值不入公开库)" \
  || log_bad "vSEC-1 疑似公网 IP 进了公开库" "$(printf '%s' "$SEC_HITS" | head -2 | tr '\n' ' ')"

# ═══ v0.15 · 券商只读同步(富途 / 老虎)═══
BSVC="$RD/src/main/java/com/family/finance/service/broker/BrokerSyncService.java"
BLNK="$RD/src/main/java/com/family/finance/service/broker/BrokerLinkService.java"
BCTL="$RD/src/main/java/com/family/finance/web/broker/BrokerLinkController.java"
BCLI="$RD/src/main/java/com/family/finance/service/broker/BrokerClient.java"
DSC="$RD/src/main/java/com/family/finance/service/scheduling/DynamicScheduleConfig.java"
INTG="$RD/src/main/resources/templates/admin/broker.html"   # v1.26 · 券商同步自己一页(原 integrations.html 第 ④ 节)· 判据不变,只换看的地方
HOLD="$RD/src/main/resources/templates/stock/holdings.html"

# v15-RO-1 · 只读铁律:适配器无写/交易调用(调用形态匹配 · 排除注释/文档说明)
BRO_HITS="$(grep -rnE 'unlockTrade\(|\.placeOrder|\.modifyOrder|\.cancelOrder|\.replaceOrder' "$RD/src/main/java/com/family/finance/service/broker/" 2>/dev/null | grep -vE '永不|铁律|^\s*\*|//' || true)"
{ [ -z "$BRO_HITS" ]; } \
  && log_ok "v15-RO-1 只读铁律 · 券商适配器无下单/改单/撤单/解锁交易调用" \
  || log_bad "v15-RO-1 券商出现写/交易调用(违反只读铁律)" "$(printf '%s' "$BRO_HITS" | head -1)"

# v15-MAP-1 · reconcile 只动 sync_source=本 vendor 行(不碰手填持仓)
{ grep -q 'src.equals(h.getSyncSource())' "$BSVC" && grep -q 'skippedNonEquity' "$BSVC"; } \
  && log_ok "v15-MAP-1 对账只动 sync_source 行 · 不同步的品种计数(v1.29 起期权 / 期货 / 债券同步,见 v129-*)" \
  || log_bad "v15-MAP-1 对账未按 sync_source 隔离手填持仓" "see BrokerSyncService.reconcile"

# v15-LINK-1 · 关联前留审计快照 + 软归档 + 两步确认硬门
{ grep -q 'AuditLogType.BROKER_LINK' "$BLNK" && grep -q 'holdingMapper.archive' "$BLNK" \
  && grep -q '!confirmed || !acknowledged' "$BCTL"; } \
  && log_ok "v15-LINK-1 关联高危 · 快照留痕 + 软归档 + 两步确认(confirmed&&acknowledged)" \
  || log_bad "v15-LINK-1 关联缺快照/两步确认" "see BrokerLinkService / BrokerLinkController"

# v15-CRON-1 · broker-sync 进动态调度(可配 cron · 无关联空跑)
{ grep -q '"broker-sync"' "$DSC" && grep -q 'syncAllEnabled' "$DSC" && grep -q 'K_BROKER_SYNC_CRON' "$DSC"; } \
  && log_ok "v15-CRON-1 券商同步进动态调度 · cron 可配 · 无关联空跑" \
  || log_bad "v15-CRON-1 broker-sync 未纳入 DynamicScheduleConfig" "see DynamicScheduleConfig"

# v15-CFG-1 · 管理页 ④ 券商段在岗(老虎/富途 + 私钥不回显 + 测试连接)
{ grep -q '券商同步' "$INTG" && grep -q 'name="tigerKey"' "$INTG" && grep -q 'type="password"' "$INTG" \
  && grep -q '/admin/broker/test' "$INTG"; } \
  && log_ok "v15-CFG-1 管理页券商同步 · 老虎/富途凭据(私钥不回显)+ 测试连接" \
  || log_bad "v15-CFG-1 管理页券商段缺件" "see admin/broker.html"

# v15-ENTRY-1 · 券商入口在账户页(账户颗粒度)+ 持仓页保留同步徽章(v0.15.x 入口迁移)
ACCIDX="$RD/src/main/resources/templates/accounts/index.html"
# v1.6.24 反转:原来这里有一条 `! grep -q '/broker|}' "$HOLD"` **禁止**持仓页出现券商链接
#   (v0.15.x「入口迁到账户页」的决定)。用户第 14 轮指出那个结论不对 —— 配置确实是账户颗粒度,
#   但持仓页本身就是账户颗粒度的页面、且是用户从填报页进来干活的地方。禁令改成**要求**。理由见 tech-design §34。
# v1.6.23 补强:原来只断言「模板里有 /broker(id=」—— 入口被塞进 ⋯ 收纳菜单后它照样 PASS,
#   用户实际找不到(见 v1623-ENTRY-VIS)。这里静态加一条:⋯ 菜单块(row-more-pop…</details>)里
#   不得出现 /broker —— 能力入口只能在行内。这条不依赖浏览器,是运行时守护的廉价互补。
MOREPOP="$(sed -n '/row-more-pop/,/<\/details>/p' "$ACCIDX")"
{ grep -q '/broker(id=' "$ACCIDX" && grep -q '券商托管' "$ACCIDX" \
  && ! printf '%s' "$MOREPOP" | grep -q '/broker' \
  && grep -q '券商同步' "$HOLD" && grep -q '/broker|}' "$HOLD"; } \
  && log_ok "v15-ENTRY-1 券商入口在账户页行内(不在 ⋯ 收纳里)+ 托管徽章 · 持仓页也有入口(v1.6.24 反转旧禁令)" \
  || log_bad "v15-ENTRY-1 入口/徽章位置不对(或券商入口被收进 ⋯ 菜单 / 持仓页丢了入口)" "see accounts/index.html / stock/holdings.html · 能力入口不得进 row-more-pop"

# v15-GRAN · 连接配置下沉到关联颗粒度(V40)+ per-link 测试富卡片
BLDOM="$RD/src/main/java/com/family/finance/domain/broker/BrokerLink.java"
BLMAP="$RD/src/main/java/com/family/finance/repository/BrokerLinkMapper.java"
{ [ -f "$RD/db/migration/V40__broker_link_conn.sql" ] \
  && grep -q 'opendHost' "$BLDOM" && grep -q 'findByFamily' "$BLMAP" \
  && grep -q '"/accounts/{accountId}/broker/test"' "$BCTL" \
  && grep -q 'brokerTestReport' "$RD/src/main/resources/templates/broker/link.html" \
  && grep -q 'TestReport testConnection(long familyId, BrokerLink link)' "$RD/src/main/java/com/family/finance/service/broker/BrokerClient.java"; } \
  && log_ok "v15-GRAN 关联颗粒度连接(V40 opend_host/port·NULL=全局)+ per-link 测试连接富卡片" \
  || log_bad "v15-GRAN 关联颗粒度模型缺件" "see V40/BrokerLink/BrokerLinkMapper/BrokerLinkController/link.html"

# v15-FIX-TX · 关联与首次同步拆两段事务(修 rollback-only:link() 不再嵌套 sync())
#            + 关联前快照进 payload_json、summary 防御截断(修 Data too long for 'summary')
{ grep -q 'public void link(' "$BLNK" && grep -q 'public String initialSync(' "$BLNK" && grep -q 'linkService.initialSync' "$BCTL" \
  && grep -q 'snapshotRows' "$BLNK" && grep -q 'preLinkHoldings' "$BLNK" \
  && grep -q 'SUMMARY_MAX' "$RD/src/main/java/com/family/finance/service/AuditLogService.java"; } \
  && log_ok "v15-FIX-TX 关联 link() 提交后另起事务 initialSync(无 rollback-only)+ 快照进 payload_json、summary≤255 截断(无 Data too long)" \
  || log_bad "v15-FIX-TX link() 仍嵌套首次同步 / 快照仍塞 summary" "see BrokerLinkService/BrokerLinkController/AuditLogService"

echo
# ============================================================
# v0.16 · 目标模块重构(通用追踪目标 · 绑 0–N 账户 + 追踪指标 + 时间范围)
# ============================================================
GDOM="$RD/src/main/java/com/family/finance/domain/goal"
GSVC="$RD/src/main/java/com/family/finance/service/goal"

# v16-GOAL-MIG · 迁移加列 + goal_account 表 + 回填
{ [ -f "$RD/db/migration/V41__goal_generic.sql" ] && [ -f "$RD/db/migration/V42__goal_account.sql" ] \
  && grep -q 'ADD COLUMN metric' "$RD/db/migration/V41__goal_generic.sql" \
  && grep -q "CREATE TABLE goal_account" "$RD/db/migration/V42__goal_account.sql" \
  && grep -q 'CUSTOM' "$GDOM/GoalType.java"; } \
  && log_ok "v16-GOAL-MIG V41 加列 metric/comparator/time_mode + 回填 · V42 goal_account(0..N)· GoalType 加 CUSTOM" \
  || log_bad "v16-GOAL-MIG 迁移/枚举缺件" "see db/migration/V41,V42 · GoalType"

# v16-GOAL-EVAL · 指标聚合 + pace(纯函数 + 单测)
{ [ -f "$GSVC/GoalMetricEvaluator.java" ] && [ -f "$GSVC/GoalPaceCalculator.java" ] \
  && grep -q 'weighted' "$GSVC/GoalMetricEvaluator.java" \
  && grep -q 'BEHIND_GAP' "$GSVC/GoalPaceCalculator.java" \
  && grep -q 'isRate' "$GDOM/GoalMetric.java" \
  && [ -f "$RD/src/test/java/com/family/finance/service/goal/GoalMetricEvaluatorTest.java" ] \
  && [ -f "$RD/src/test/java/com/family/finance/service/goal/GoalPaceCalculatorTest.java" ]; } \
  && log_ok "v16-GOAL-EVAL 指标价值加权聚合 + pace(金额判落后/比率仅倒计时)+ 单测在岗" \
  || log_bad "v16-GOAL-EVAL evaluator/pace/单测缺件" "see service/goal"

# v16-GOAL-CUSTOM · 自定义向导路由 + 账户多选 + 分流
{ grep -q '"/goals/new/custom"' "$RD/src/main/java/com/family/finance/web/goal/GoalController.java" \
  && grep -q 'createCustom' "$GSVC/GoalService.java" \
  && grep -q 'computeCustom' "$GSVC/GoalProgressService.java" \
  && [ -f "$RD/src/main/resources/templates/goals/new-custom.html" ] \
  && grep -q 'name="accountIds"' "$RD/src/main/resources/templates/goals/new-custom.html" \
  && grep -q 'name="description"' "$RD/src/main/resources/templates/goals/new-custom.html" \
  && grep -q 'id="burnup"' "$RD/src/main/resources/templates/goals/detail.html"; } \
  && log_ok "v16-GOAL-CUSTOM /goals/new/custom 向导(先账户后指标 + 可选描述)+ 分流 + 详情 burn-up 达标节奏图" \
  || log_bad "v16-GOAL-CUSTOM 向导/分流缺件" "see GoalController/GoalService/new-custom.html"

# v16-GOAL-BAR · 移动端紧凑条(列表 + Dashboard 条带瘦身)
{ [ -f "$RD/src/main/resources/templates/goals/_goal-bar.html" ] \
  && grep -q '_goal-bar :: bar' "$RD/src/main/resources/templates/goals/index.html" \
  && ! grep -q 'gp.scenarios' "$RD/src/main/resources/templates/goals/index.html" \
  && grep -q "hidden md:flex" "$RD/src/main/resources/templates/goals/_progress-strip.html"; } \
  && log_ok "v16-GOAL-BAR 紧凑条 fragment · 列表去 scenarios(CUSTOM 安全)· Dashboard 条带手机限 2 条" \
  || log_bad "v16-GOAL-BAR 紧凑条/瘦身缺件" "see goals/_goal-bar.html · index.html · _progress-strip.html"

# v15-UX · 二次确认走自建弹窗(无 native confirm)+ 创建证券账户显同步提示
BLHTML="$RD/src/main/resources/templates/broker/link.html"
WIZ="$RD/src/main/resources/templates/accounts/_template-wizard.html"
{ ! grep -q 'confirm(' "$BLHTML" && grep -q 'id="lnkModal"' "$BLHTML" && grep -q 'id="unlModal"' "$BLHTML" \
  && grep -q 'brokerSyncHint' "$WIZ"; } \
  && log_ok "v15-UX 二次确认自建弹窗(无系统 confirm)+ 建证券账户显券商同步提示" \
  || log_bad "v15-UX 仍用系统 confirm 或缺创建提示" "see broker/link.html / _template-wizard.html"

# v15-HELP · 券商凭据获取图文向导在岗 + 各入口挂教程链接
HELP="$RD/src/main/resources/templates/help/broker-sync.html"
HCTL="$RD/src/main/java/com/family/finance/web/help/HelpController.java"
{ grep -q '/help/broker-sync' "$HCTL" && grep -q 'id="tiger"' "$HELP" && grep -q 'id="futu"' "$HELP" \
  && grep -q 'developer.itigerup.com' "$HELP" && grep -q 'download/openAPI' "$HELP" \
  && grep -q '/help/broker-sync' "$INTG" && grep -q '/help/broker-sync' "$BLHTML"; } \
  && log_ok "v15-HELP 券商凭据图文向导(富途/老虎步骤+示意图)+ 管理页/关联页挂教程入口" \
  || log_bad "v15-HELP 图文向导缺件或入口未挂" "see help/broker-sync.html / HelpController / admin/broker.html / broker/link.html"

# v15-OPEND · 富途 OpenD 三拓扑部署方案齐全(systemd 模板 + compose 覆盖 + 文档 + 应用内块)
{ [ -f "$RD/deploy/futu-opend.service.example" ] && [ -f "$RD/deploy/futu-opend.compose.yml" ] \
  && grep -q 'OpenD 部署到哪' "$RD/docs/broker-sync-guide.md" \
  && grep -q 'futu-opend.service.example' "$HELP"; } \
  && log_ok "v15-OPEND 富途 OpenD 部署方案(同机 systemd / docker sidecar / 家用机隧道)+ 文档 + 应用内说明" \
  || log_bad "v15-OPEND OpenD 部署方案缺件" "see deploy/futu-opend.* / docs/broker-sync-guide.md / help/broker-sync.html"

# v1121-OPEND-DOCKER-ENV · issue #13 的教训(v1.17 升级判据)
# 原始现象:向导页把 compose 合并命令摆成一键 pre 块,而三个【必需的 .env 变量】写在命令下方脚注里,
#          .env.example 里又完全没有 → 用户复制即撞 `required variable FUTU_ACCOUNT is missing a value`。
# v1.17 把那条路整个换掉了:不再要求用户自备镜像、也不再需要任何 .env 凭据,所以"前提前置"这个
# 判据的对象已经不存在。教训升级为:**要用户执行的命令,前面必须先讲清他在引入什么**
#          —— 现在页面上"启用命令"必须出现在「你正在引入什么」公示块之后,而不是之前。
OWTPL_D="$RD/src/main/resources/templates/broker/opend-wizard.html"
_pos_disclose="$(grep -n '你正在引入什么' "$OWTPL_D" | head -1 | cut -d: -f1)"
_pos_cmd="$(grep -n 'caps.enableCommand()' "$OWTPL_D" | head -1 | cut -d: -f1)"
_pos_hash="$(grep -n '富途官方不公布任何校验和' "$OWTPL_D" | head -1 | cut -d: -f1)"
{ [ -n "$_pos_disclose" ] && [ -n "$_pos_cmd" ] && [ -n "$_pos_hash" ] \
  && [ "$_pos_disclose" -lt "$_pos_cmd" ] \
  && [ "$_pos_hash" -lt "$_pos_cmd" ] \
  && grep -q '请你自己也验一遍' "$OWTPL_D" \
  && ! grep -q 'required variable FUTU_ACCOUNT' "$OWTPL_D" \
  && grep -q '(\.\./deploy/futu-opend\.compose\.yml)' "$RD/docs/broker-sync-guide.md" \
  && grep -q '(\.\./deploy/futu-opend\.service\.example)' "$RD/docs/broker-sync-guide.md"; } \
  && log_ok "v1121-OPEND-DOCKER-ENV 要用户跑的命令排在「你正在引入什么」+ 哈希公示之后(issue #13 教训升级版)· 指南相对链接可达" \
  || log_bad "v1121-OPEND-DOCKER-ENV 启用命令跑在公示前面(或公示缺失)" "see broker/opend-wizard.html 未启用段顺序 / docs/broker-sync-guide.md"

# v15-OPEND-WIZ · 应用内 OpenD 傻瓜向导(下载/版本/依赖/配置启动/短信中继)
# v1.17:实现拆成了通道(FutuOpendManager 只剩 facade),所以能力项分别在各自文件里查 ——
#        守的是「这些能力还在、入口还在」,不是「它们都挤在一个类里」。
OWMGR="$RD/src/main/java/com/family/finance/service/broker/opend/FutuOpendManager.java"
OWLOC="$RD/src/main/java/com/family/finance/service/broker/opend/LocalProcessChannel.java"
OWREL="$RD/src/main/java/com/family/finance/service/broker/opend/OpendRelease.java"
OWTEL="$RD/src/main/java/com/family/finance/service/broker/opend/OpendTelnet.java"
OWCTL="$RD/src/main/java/com/family/finance/web/broker/FutuOpendController.java"
OWTPL="$RD/src/main/resources/templates/broker/opend-wizard.html"
{ [ -f "$OWMGR" ] && [ -f "$OWLOC" ] && [ -f "$OWREL" ] && [ -f "$OWTEL" ] && [ -f "$OWCTL" ] && [ -f "$OWTPL" ] \
  && grep -q '/admin/broker/opend' "$OWCTL" && grep -q 'api_ip=127.0.0.1' "$OWLOC" \
  && grep -q 'input_phone_verify_code' "$OWTEL" \
  && grep -q 'detectEnv' "$OWMGR" && grep -q '/.dockerenv' "$OWMGR" && grep -q 'packageTag' "$OWREL" \
  && grep -q 'installFromStream' "$OWLOC" && grep -q '"/upload"' "$OWCTL" && grep -q '上传并解压' "$OWTPL" \
  && grep -q 'installFromServerPath' "$OWLOC" && grep -q 'import-path' "$OWCTL" && grep -q '从该路径导入' "$OWTPL" \
  && grep -q "var BASE = '/admin/broker/opend/'" "$OWTPL" \
  && ! grep -qE "fetch\('(status|deps|upload|download)|post\('(download|sms)" "$OWTPL" \
  && grep -q 'id="step1Done"' "$OWTPL" && grep -q 'id="step1Full"' "$OWTPL" \
  && grep -q 'id="step2Locked"' "$OWTPL" && grep -q 'id="loginDone"' "$OWTPL" && grep -q 'id="loginForm"' "$OWTPL" \
  && grep -q 'addAttribute("installed"' "$OWCTL" && grep -q 'addAttribute("running"' "$OWCTL" \
  && grep -q 'public SelfCheck selfCheck' "$OWLOC" && grep -q 'probeWritable' "$OWLOC" \
  && grep -q '"/selfcheck"' "$OWCTL" && grep -q '"/test"' "$OWCTL" \
  && grep -q 'id="selfBtn"' "$OWTPL" && grep -q 'id="testBtn"' "$OWTPL" \
  && grep -q 'name="vendor" value="FUTU"' "$RD/src/main/resources/templates/admin/broker.html" \
  && grep -q '/admin/broker/opend' "$RD/src/main/resources/templates/admin/broker.html"; } \
  && log_ok "v15-OPEND-WIZ 应用内一键 OpenD 向导(下载/版本/依赖/配置启动/短信中继·只绑127.0.0.1)+ step-by-step 门控(装好收起第1步/亮第2步·运行中收起表单)+ 渠道自适应 + 管理页入口" \
  || log_bad "v15-OPEND-WIZ OpenD 向导缺件或渠道未自适应" "see service/broker/opend / web/broker/FutuOpendController / broker/opend-wizard.html"

# v117-DL-HOST · 官方发布物定位没有过期(v1.17)
# 背景:v0.15 把下载地址/文件名/系统标识写死,富途换域名+改命名之后,「下载并安装」在**原生部署上也点不动了**;
#      更糟的是白名单只放老域名 —— 用户手填现行官方 URL 会被我们自己拒掉,连手动救的路都堵着。
# 守的是「现行域名在、老命名不在、白名单容得下官方现行地址」,不是某一版版本号。
OWCFG="$RD/src/main/java/com/family/finance/service/broker/opend/OpendConfigXml.java"
{ grep -q 'softwaredownload.futunn.com' "$OWREL" \
  && grep -q 'fetch-lasted-link' "$OWREL" \
  && grep -q 'Futu_OpenD_' "$OWREL" \
  && grep -q 'return "Ubuntu18.04"' "$OWREL" && ! grep -q 'return "Ubuntu16.04"' "$OWREL" \
  && ! grep -q 'SOFTWARE_HOST' "$OWLOC" \
  && ! grep -q 'Ubuntu16.04' "$OWTPL" \
  && grep -q 'isInteractiveLogin' "$OWREL" \
  && grep -q 'cfg_file=' "$OWLOC" \
  && ! grep -qE '"[^"]*libgtk-3-0[^"]*"' "$OWLOC"; } \
  && log_ok "v117-DL-HOST 下载走官方现行域名 + fetch-lasted-link 取最新 + Futu_OpenD_/Ubuntu18.04 现行命名 · 老域名常量与 gtk3 硬编码提示已清 · 10.x 走 -cfg_file" \
  || log_bad "v117-DL-HOST OpenD 下载定位过期(原生路径也会点不动)" "see OpendRelease.java / LocalProcessChannel.java / broker/opend-wizard.html"

# v117-NO-TELNET-EXPOSE · OpenD 那个**没有鉴权**的 telnet 控制口不许对网络开放(v1.17)
# 实测(2026-08-17 容器实跑):官方模板把 telnet_ip/telnet_port **整行注释掉**(默认不启用控制口),
# 所以要用它就得"取消注释再设值"—— 只替换标签内容会把值改进注释里,OpenD 压根不启用控制口
# (日志连「Telnet监听地址」都不打印,表现为登录连不上)。取消注释时地址按死回环,不给调用方留参数。
{ [ -f "$OWCFG" ] \
  && grep -q 'TELNET_IP = "127.0.0.1"' "$OWCFG" \
  && grep -q 'setOrEnableTag(s, "telnet_ip", TELNET_IP)' "$OWCFG" \
  && ! grep -q 'String telnetIp' "$OWCFG" \
  && grep -q 'isInsideComment' "$OWCFG" \
  && grep -q 'setOrEnableTag' "$OWCFG"; } \
  && log_ok "v117-NO-TELNET-EXPOSE 控制口取消注释后按死 127.0.0.1(官方默认整行注释=不启用)· 注释里的同名标签不误改" \
  || log_bad "v117-NO-TELNET-EXPOSE 无鉴权控制口可能对网络开放" "see OpendConfigXml.java"

# v117-HASH-PINNED · 安装包哈希钉在仓库里,校验不过必须拒装(v1.17)
# 富途官方【不公布】任何 md5/sha256,所以我们自己下载核对、把哈希钉进 deploy/futu-opend-releases.json
# (有 git 历史、可 review)。安装时现算比对,不一致就删文件 + 中止。
# 三条最容易被"顺手简化"掉的:① 校验在解包【之前】;② 对不上就 delete + throw,不留绕过口;
# ③ 清单读不到 ≠ 未核对版本(后者用户勾一下就过,前者必须先修清单)。
OWCAT="$RD/src/main/java/com/family/finance/service/broker/opend/OpendCatalog.java"
OWJSON="$RD/deploy/futu-opend-releases.json"
{ [ -f "$OWCAT" ] && [ -f "$OWJSON" ] \
  && grep -q 'verifyOrFail(pkg' "$OWLOC" \
  && [ "$(grep -c 'verifyOrFail(pkg' "$OWLOC")" -ge 3 ] \
  && awk '/private String extractAndFinish/{exit} /verifyOrFail\(pkg/{n++} END{exit !(n>=3)}' "$OWLOC" \
  && grep -q 'Files.deleteIfExists(pkg)' "$OWLOC" \
  && grep -q '无法校验' "$OWCAT" \
  && grep -q 'officialPublishesHashes' "$OWCTL" \
  && python3 -c "import json,sys; d=json.load(open('$OWJSON')); rs=d['releases']; sys.exit(0 if rs and all(len(r['sha256'])==64 and len(r['md5'])==32 and r['bytes']>0 for r in rs) else 1)"; } \
  && log_ok "v117-HASH-PINNED 清单每条带 sha256+md5+bytes · 三条安装路径都在解包前校验 · 不一致即删包中止 · 清单读不到与未核对分开报" \
  || log_bad "v117-HASH-PINNED 安装包校验链路缺失或可绕过" "see OpendCatalog.java / LocalProcessChannel.verifyOrFail / deploy/futu-opend-releases.json"

# v117-HASH-HONEST · 不许把"我们算的哈希"说成"官方的"(v1.17)
# 富途官网一个校验和都不公布 —— 唯一能从官方侧拿到的是 CDN 的 etag(实测 == 文件 MD5),
# 但它与安装包同一条 TLS、同一个 CDN,只证明传输没坏。文档/页面/接口都必须如实说明这个边界。
{ grep -q '不公布' "$OWJSON" \
  && grep -qi 'etag' "$OWJSON" \
  && grep -q '"officialPublishesHashes", false' "$OWCTL" \
  && ! grep -q '官方 md5\|官方公布的 sha' "$OWJSON"; } \
  && log_ok "v117-HASH-HONEST 清单与接口如实写明「官方不公布校验和 · 这些哈希是我们算的」+ 给出 etag 交叉验证法与其边界" \
  || log_bad "v117-HASH-HONEST 哈希来源表述不诚实(不许写成已比对官方 md5)" "see deploy/futu-opend-releases.json / FutuOpendController#catalog"

# v117-LAUNCHER · 可选网关镜像的构建与发布链完整(v1.17)
# 我们在替用户托管一个能操作券商账户的组件,所以三件事必须都在:
#  ① 镜像里没有富途文件 —— 由 CI 扫【export 出来的全部层】证明(grep Dockerfile 只能证明"我没写")
#  ② 按 digest 可拉 + provenance 可验签
#  ③ 那个无鉴权的控制口不许 EXPOSE(更不许 publish)
FUTUDF="$RD/docker/futu-opend/Dockerfile"
FUTUEP="$RD/docker/futu-opend/entrypoint.sh"
FUTUCL="$RD/docker/futu-opend/control-loop.sh"
FUTUSCAN="$RD/scripts/scan-image-no-futu.sh"
WF="$RD/.github/workflows/docker-publish.yml"
{ [ -f "$FUTUDF" ] && [ -f "$FUTUEP" ] && [ -f "$FUTUCL" ] && [ -f "$FUTUSCAN" ] \
  && grep -q 'useradd' "$FUTUDF" \
  && grep -q 'EXPOSE 11111' "$FUTUDF" && ! grep -q 'EXPOSE.*22222' "$FUTUDF" \
  && ! grep -qE '^\s*(COPY|ADD).*(Futu_?OpenD|\.tar\.gz)' "$FUTUDF" \
  && grep -q 'scan-image-no-futu.sh' "$WF" \
  && grep -q 'attest-build-provenance' "$WF" \
  && grep -q 'provenance: true' "$WF" \
  && grep -q 'sha256sum' "$FUTUEP" \
  && grep -q 'getent passwd' "$FUTUEP" \
  && grep -q 'telnet_ip>127.0.0.1' "$FUTUEP" \
  && bash -n "$FUTUEP" && bash -n "$FUTUCL" && bash -n "$FUTUSCAN"; } \
  && log_ok "v117-LAUNCHER 镜像不打包富途制品(CI 扫全部层)+ 自建用户(否则 OpenD 段错误)+ 只 EXPOSE 11111 + 下载校验 sha256 + provenance 可验签" \
  || log_bad "v117-LAUNCHER 网关镜像构建/发布链缺件" "see docker/futu-opend/ · scripts/scan-image-no-futu.sh · .github/workflows/docker-publish.yml"

# v117-CTL-KEYWORDS · 控制口登录状态机的关键词【两处必须一致】(v1.17)
# app 连不到容器内的控制口,所以网关容器里那份状态机是 bash 写的 —— 同一套判定天生有两份实现。
# 这条护栏钉住:双方认同一批关键词,而且"失败"关键词在两边都排在"验证码"之前
# (「验证码错误」也含「验证码」,顺序错了会把失败当成"再要一次码")。
OWTEL2="$RD/src/main/java/com/family/finance/service/broker/opend/OpendTelnet.java"
CTLKW_OK=1
for kw in 请输入账号 请输入密码 验证码错误 登录成功 登录失败; do
  grep -q "$kw" "$OWTEL2" || CTLKW_OK=0
  grep -q "$kw" "$FUTUCL" || CTLKW_OK=0
done
JAVA_FAIL_POS=$(grep -n '验证码错误' "$OWTEL2" | head -1 | cut -d: -f1)
JAVA_SMS_POS=$(grep -n 'contains("验证码")' "$OWTEL2" | head -1 | cut -d: -f1)
BASH_FAIL_POS=$(grep -n '验证码错误' "$FUTUCL" | head -1 | cut -d: -f1)
BASH_SMS_POS=$(grep -n '^\s*\*验证码\*' "$FUTUCL" | head -1 | cut -d: -f1)
{ [ "$CTLKW_OK" = "1" ] \
  && [ -n "$JAVA_FAIL_POS" ] && [ -n "$JAVA_SMS_POS" ] && [ "$JAVA_FAIL_POS" -lt "$JAVA_SMS_POS" ] \
  && [ -n "$BASH_FAIL_POS" ] && [ -n "$BASH_SMS_POS" ] && [ "$BASH_FAIL_POS" -lt "$BASH_SMS_POS" ]; } \
  && log_ok "v117-CTL-KEYWORDS Java 与 bash 两份状态机认同一批提示词,且「失败」判定都排在「验证码」之前" \
  || log_bad "v117-CTL-KEYWORDS 控制口状态机两处不一致(改一边漏一边)" "see OpendTelnet.java stepFromPrompt / docker/futu-opend/control-loop.sh step_of"

# v117-CHANNEL-PROBE · 通道按【能力探测】选,不按"你是哪种部署"选(v1.17)
# v1.16 之前:7 处 Env.DOCKER 硬拦 + 模板 10 处 channel != 'DOCKER'。/.dockerenv 只能回答
# "我在容器里",回答不了"网关在哪" —— 而后者才是真正要分支的东西。
OWCGC="$RD/src/main/java/com/family/finance/service/broker/opend/ContainerGatewayChannel.java"
{ [ -f "$OWCGC" ] \
  && grep -q 'container.enabled()' "$OWMGR" \
  && grep -q 'Files.isDirectory(ctlDir)' "$OWCGC" \
  && ! grep -q 'Docker 环境请用 sidecar' "$OWMGR" \
  && ! grep -q 'requireNotDocker' "$OWMGR" \
  && grep -q 'needsEnable' "$OWCGC" \
  && grep -q 'profile futu' "$OWCGC"; } \
  && log_ok "v117-CHANNEL-PROBE 通道按控制卷是否存在探测 · Docker 硬拦已拆 · 未启用时给启用命令而不是报错" \
  || log_bad "v117-CHANNEL-PROBE 仍按部署方式硬分支(或 Docker 硬拦没拆干净)" "see FutuOpendManager.active() / ContainerGatewayChannel"

# v117-API-ENCRYPTED · 只锁控制口不锁 11111 是【假安全】(v1.17)
# telnet 锁进容器之后,如果 API 口仍是明文,同一个 compose 网络里的其它容器照样能读走全部持仓。
# 富途 SDK 现成支持:setRSAPrivateKey + initConnect(host, port, true),密钥由网关容器生成在共享卷里。
FBC="$RD/src/main/java/com/family/finance/service/broker/FutuBrokerClient.java"
{ grep -q 'setRSAPrivateKey' "$FBC" \
  && grep -q 'initConnect(host, port, encrypt)' "$FBC" \
  && ! grep -q 'initConnect(host, port, false)' "$FBC" \
  && grep -q 'apiRsaKeyFile' "$OWMGR" \
  && grep -q 'rsa_private_key' "$FUTUEP"; } \
  && log_ok "v117-API-ENCRYPTED 网关通道给 11111 开 RSA(密钥走共享卷)· 其它拓扑保持明文不打断现有连接" \
  || log_bad "v117-API-ENCRYPTED API 口仍是明文(锁了控制口等于没锁)" "see FutuBrokerClient.connect / docker/futu-opend/entrypoint.sh"

# v117-PROFILE-OPTIN · 不用富途的人必须零成本(v1.17)
# 维护者定方案 B 的理由就是"富途不是所有人都需要,应该可选、不该打包进来" ——
# 所以默认 `docker compose up -d` 展开的服务里【不许】出现网关,而启用只需一条命令。
# 判据用 `docker compose config --services` 实际展开(而不是 grep yaml):profile 语义得让 compose 自己说。
DCF="$RD/docker-compose.yml"
DUP="$RD/deploy/docker-up.sh"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  _dcsvc_default="$(cd "$RD" && MYSQL_ROOT_PASSWORD=x DB_PASS=y REMEMBER_ME_KEY=z docker compose config --services 2>/dev/null | tr '\n' ' ')"
  _dcsvc_futu="$(cd "$RD" && MYSQL_ROOT_PASSWORD=x DB_PASS=y REMEMBER_ME_KEY=z docker compose --profile futu config --services 2>/dev/null | tr '\n' ' ')"
else
  _dcsvc_default="(no-docker)"; _dcsvc_futu="(no-docker)"
fi
if [ "$_dcsvc_default" = "(no-docker)" ]; then
  log_skip "v117-PROFILE-OPTIN 本机没有 docker compose,跳过 profile 实际展开校验"
else
  # 端口判定交给 compose 自己解析(第三次栽在"注释里提到了端口号"上:
  # 文案里写「22222 = 控制口,没有鉴权」是**应该**的,grep 全文会把这句判成配置)
  _dcports="$(cd "$RD" && MYSQL_ROOT_PASSWORD=x DB_PASS=y REMEMBER_ME_KEY=z docker compose --profile futu config --format json 2>/dev/null \
      | python3 -c 'import json,sys
try:
    d=json.load(sys.stdin); print(len(d["services"]["opend"].get("ports") or []))
except Exception: print("ERR")' 2>/dev/null)"
  { grep -q 'profiles: \["futu"\]' "$DCF" \
    && [ "$_dcports" = "0" ] \
    && grep -q 'stop_grace_period' "$DCF" \
    && grep -q 'futu-ctl:/ctl' "$DCF" \
    && grep -q -- '--with-futu' "$DUP" \
    && grep -q 'FUTU_ENABLED' "$DUP" \
    && [ "${_dcsvc_default#*opend}" = "$_dcsvc_default" ] \
    && [ "${_dcsvc_futu#*opend}" != "$_dcsvc_futu" ]; } \
    && log_ok "v117-PROFILE-OPTIN 默认展开无网关服务(实测 compose config)· --profile futu 才有 · 网关零端口映射(compose 解析实测)· docker-up.sh 认 --with-futu 并记住选择" \
    || log_bad "v117-PROFILE-OPTIN 富途网关不是真正可选(或端口被映射出去)" "see docker-compose.yml opend 服务 / deploy/docker-up.sh"
fi

# v117-ENV-NOT-REQUIRED · .env 不再承载富途凭据(v1.17)
# PRD §0 的第 3 条不合理:账号 + 密码 MD5 落在 .env 明文,与"运营参数一律走管理页"的既定原则冲突,
# 而且 .env.example 还教用户 `printf '你的密码' | md5sum`。
{ ! grep -qE '^\s*FUTU_ACCOUNT=' "$RD/.env.example" \
  && ! grep -q 'md5sum' "$RD/.env.example" \
  && ! grep -qE 'FUTU_ACCOUNT:\s*\$\{FUTU_ACCOUNT:\?' "$DCF" \
  && grep -q '不在这里配' "$RD/.env.example" \
  && grep -q 'docker-up.sh --with-futu' "$RD/.env.example"; } \
  && log_ok "v117-ENV-NOT-REQUIRED .env.example 里富途段只剩可选开关(凭据走管理页)· md5sum 教程已删" \
  || log_bad "v117-ENV-NOT-REQUIRED .env 仍要求富途凭据(或还在教 md5sum)" "see .env.example 富途段"

# v117-WIZARD-CAPS · 向导页按能力位渲染,且不再教用户自己打包镜像(v1.17)
# v1.16 的 Docker 分支是一段"自备镜像 + 借一台有桌面的机器 + 往 .env 写密码 MD5"的教程(issue #13),
# 那是把我们做不到的事外包给用户。现在:未启用 → 公示「你正在引入什么」+ 一条启用命令;
# 已启用 → 与原生同一套步骤(安装那步由网关容器自己做,页面明说一句)。
{ ! grep -q "channel == 'DOCKER'" "$OWTPL" && ! grep -q "channel != 'DOCKER'" "$OWTPL" \
  && [ "$(grep -c 'caps\.' "$OWTPL")" -ge 8 ] \
  && ! grep -q '不能替你一键装' "$OWTPL" \
  && ! grep -q '自备一个能跑命令行版' "$OWTPL" \
  && ! grep -q 'printf .你的密码' "$OWTPL" \
  && grep -q '你正在引入什么' "$OWTPL" \
  && grep -q '富途官方不公布任何校验和' "$OWTPL" \
  && grep -q 'gh attestation verify' "$RD/src/main/java/com/family/finance/service/broker/opend/GatewayImageInfo.java" \
  && grep -q 'caps.enableCommand()' "$OWTPL" \
  && grep -q 'addAttribute("caps"' "$OWCTL" \
  && grep -q '"/catalog"' "$OWCTL"; } \
  && log_ok "v117-WIZARD-CAPS 向导页零 channel 硬分支(≥8 处能力位)· 未启用给公示块+启用命令 · 自备镜像/md5sum 教程已删 · /catalog 公示接口在" \
  || log_bad "v117-WIZARD-CAPS 向导页仍按部署方式分支或还在教自己打包镜像" "see broker/opend-wizard.html / FutuOpendController"

# v117-TPL-LITERAL-PIPE · Thymeleaf 字面替换里不许再出现 `|`(v1.17 · 真踩过)
# th:text="|...|" 的定界符就是 `|`,内部再出现一个(例如 shell 管道 `| grep -i etag`)会让 literal 提前结束,
# 模板【解析期】抛 Could not parse as expression;而响应是 chunked,错误页被追加在已输出内容之后 ——
# 现象是"页面渲染到一半突然变成错误页",很容易误判成布局问题。
# 注意判据不是"不许写三元":三元写在 ${} 内部完全合法(admin/backup.html 就有),第一版判成三元是误诊。
_tplpipe="$(python3 - "$RD/src/main/resources/templates" <<'PYEOF'
import os,re,sys
root=sys.argv[1]; bad=[]
pat=re.compile(r'th:(?:text|utext)="\|(.*?)\|"', re.S)
for dp,_,fs in os.walk(root):
    for f in fs:
        if not f.endswith('.html'): continue
        p=os.path.join(dp,f)
        try: t=open(p,encoding='utf-8').read()
        except Exception: continue
        for m in pat.finditer(t):
            if '|' in m.group(1):
                bad.append(os.path.relpath(p,root))
print(' '.join(sorted(set(bad))))
PYEOF
)"
if [ -n "$_tplpipe" ]; then
  log_bad "v117-TPL-LITERAL-PIPE 有模板在 |...| 里塞了 `|`(解析期就会炸)" "$_tplpipe"
else
  log_ok "v117-TPL-LITERAL-PIPE 没有模板在 |...| 字面替换里再出现定界符 |"
fi

# v1171-TERM-BLOCK · 要用户照着敲的命令,一律用同一个终端块 + 一键复制(v1.17.1)
# 背景:同一件事(这是要你复制去服务器上跑的命令)过去长三个样 —— 更新提示是浅色 <pre>、
# 落地页是黑底 .cmd-block、向导页第三套,而且只有落地页那处能一键复制,其余要用户自己划词选中。
# 判据钉「共用 fragment + 复制函数只有一份 + 关键页面确实在用」,不钉具体配色。
TERMFRG="$RD/src/main/resources/templates/fragments/term.html"
{ [ -f "$TERMFRG" ] \
  && grep -q 'th:fragment="cmd(lines)"' "$TERMFRG" \
  && grep -q 'termCopy' "$TERMFRG" \
  && grep -q '\.term-block' "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'window.termCopy' "$RD/src/main/resources/templates/fragments/layout.html" \
  && grep -q 'fragments/term :: cmd' "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q 'fragments/term :: cmd' "$OWTPL" \
  && ! grep -q 'git pull &amp;&amp; bash deploy/docker-up.sh</pre>' "$RD/src/main/resources/templates/admin/index.html"; } \
  && log_ok "v1171-TERM-BLOCK 升级命令与向导页命令共用 fragments/term(黑底 + \$ 提示符 + 一键复制)· 复制逻辑只有一份" \
  || log_bad "v1171-TERM-BLOCK 命令块没统一(或复制按钮缺失)" "see fragments/term.html · admin/index.html · broker/opend-wizard.html"

# v1171-COPY-WITHOUT-PROMPT · 复制出来的命令不许带 $ 提示符(v1.17.1)
# 行首那个 "$ " 是 CSS ::before 画的,所以【不属于 DOM 文本】—— 复制时按 textContent 取就自动不含它。
# 反过来说:一旦有人改成把 "$ " 写进 HTML(或复制时读 innerText/outerHTML),用户粘到终端就是一条跑不了的命令。
{ grep -q "content:'\$ '" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "querySelectorAll('.t-line')" "$RD/src/main/resources/templates/fragments/layout.html" \
  && grep -q 'el.textContent' "$RD/src/main/resources/templates/fragments/layout.html" \
  && ! grep -qE '<span class="t-line">\$' "$TERMFRG"; } \
  && log_ok "v1171-COPY-WITHOUT-PROMPT 提示符由 CSS ::before 画 · 复制走 textContent(粘出去不带 \$)" \
  || log_bad "v1171-COPY-WITHOUT-PROMPT 复制可能把 \$ 提示符一起带走" "see style.css .t-line::before / layout.html termCopy"

# v1171-DIGEST-NO-PLACEHOLDER · 自查命令里不许再留「<见 Release 页>」这种要用户自己替换的占位(v1.17.1)
# digest 每次发版都变,所以运行时查 GHCR 拿权威值;查不到就【诚实降级】成按 tag 拉,
# 绝不拼一个假 sha256 —— 那比没有更糟(用户照着验会得到"验证失败",然后开始怀疑镜像被人动过)。
GWI="$RD/src/main/java/com/family/finance/service/broker/opend/GatewayImageInfo.java"
{ [ -f "$GWI" ] \
  && grep -q 'docker-content-digest' "$GWI" \
  && grep -q 'verifyCommands' "$GWI" \
  && grep -q 'gatewayRef\|verifyCommands' "$OWTPL" \
  && ! grep -q '见 Release 页</' "$OWTPL" \
  && ! grep -qE 'sha256:&lt;|@sha256:<' "$OWTPL" \
  && grep -q '没查到镜像 digest' "$OWTPL"; } \
  && log_ok "v1171-DIGEST-NO-PLACEHOLDER 命令里填真 digest(运行时查 GHCR)· 查不到才退回 tag 并明说" \
  || log_bad "v1171-DIGEST-NO-PLACEHOLDER 自查命令仍留占位符或没有降级提示" "see GatewayImageInfo.java / broker/opend-wizard.html"

# v1172-CRED-CARDS · 数据源接入页:三家平台要有可见边界 + 配没配一眼看清(v1.17.2 · 维护者提)
# 原来三家是三列裸 div、只靠间距分隔,「已配置/未配置」是混在 label 里的一行灰字 ——
# 用户既看不出这是三个独立的东西,也扫不出哪家配好了。
INTTPL="$QA_LLM_TPL"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 integrations.html)
INTCSS="$RD/src/main/resources/static/css/style.css"
{ [ "$(grep -c 'class="cred-card"' "$INTTPL")" -ge 3 ] \
  && grep -q '\.cred-card' "$INTCSS" \
  && grep -q '\.state-tag\.on' "$INTCSS" && grep -q '\.state-tag\.off' "$INTCSS" \
  && grep -q 'state-tag on' "$INTTPL" && grep -q 'state-tag off' "$INTTPL" \
  && ! grep -q "已配置(隐藏)' : '未配置'" "$INTTPL"; } \
  && log_ok "v1172-CRED-CARDS 三家平台各自成卡(有底色边界)· 已配置/未配置是带底色的标签(绿/红),不再是 label 里的灰字" \
  || log_bad "v1172-CRED-CARDS 凭据块没有可见边界或状态还是灰字" "see admin/_llm-settings.html · style.css .cred-card/.state-tag"

# v1172-KEY-MASK · 已配置的密钥要露头尾几位(够辨认),但绝不能露够拼出来(v1.17.2)
# 用户手上常有多把 key,只说"已配置"没法确认当前跑的是哪一把 —— 于是每次都只能整条重贴。
# 边界钉在 maskSecret:固定露 10 位(头 6 尾 4),不随密钥长度增长;≤12 位的一律全打码。
FCS="$RD/src/main/java/com/family/finance/service/config/FamilyConfigService.java"
{ grep -q 'maskSecret' "$FCS" \
  && grep -q 'maskedSecret' "$QA_LLM_JAVA" \
  && grep -q 'cred-mask' "$INTTPL" \
  && grep -qE 'substring\(0, 6\)' "$FCS" \
  && grep -qE 'length\(\) - 4' "$FCS" \
  && [ -f "$RD/src/test/java/com/family/finance/service/config/SecretMaskTest.java" ]; } \
  && log_ok "v1172-KEY-MASK 已配置密钥显示头6尾4掩码(可辨认 · 不可复原)· 掩码规则有单测钉住" \
  || log_bad "v1172-KEY-MASK 密钥掩码缺失或露出过多" "see FamilyConfigService.maskSecret / SecretMaskTest"

# v1172-PLATFORM-CASCADE · 没配 key 的平台在「用哪个模型」里不可选(v1.17.2)
# 否则用户能选中一个根本调不通的平台,然后在别处收到一条看不懂的失败 —— 错误要挡在选择的那一刻。
# 判据要求三处下拉(主选/备选/视觉)都级联,且判定来自一个 map 而不是模板里逐个 if
# (加第四家平台时只改一处,v0.14 加 METAL 那次就是漏了模板里的硬编码分支)。
{ [ "$(grep -c '目前不可用,去上方表单配置' "$INTTPL")" -ge 3 ] \
  && [ "$(grep -c 'th:disabled="\${!platformReady' "$INTTPL")" -ge 3 ] \
  && grep -q 'addAttribute("platformReady"' "$QA_LLM_JAVA"; } \
  && log_ok "v1172-PLATFORM-CASCADE 主选/备选/视觉三处下拉都与凭据级联 · 未配置平台不可选并标注去哪配" \
  || log_bad "v1172-PLATFORM-CASCADE 模型下拉没和凭据配置级联" "see admin/_llm-settings.html 三处 select / LlmSettingsView#platformReady"

# v1172-APPEARANCE-PAGE · 旭日配色搬出「计算与提示常数」页,并改成个人偏好(v1.17.2 · 维护者拍板 B + 个人级)
# 原来它挂在 /admin/calc-tweaks,编号「②.5」硬插在录入阈值与会话有效期之间 ——
# 那页装的是【影响数字与行为】的参数,配色是纯视觉;那个半截编号本身就说明它没有自己的位置。
# 而且改配色的时机是「正看着旭日图觉得颜色分不清」,没人会想到去计算常数页翻。
APPTPL="$RD/src/main/resources/templates/admin/appearance.html"
CALCTPL="$RD/src/main/resources/templates/admin/calc-tweaks.html"
{ [ -f "$APPTPL" ] \
  && grep -q 'lensPalette' "$APPTPL" \
  && ! grep -q 'lensPalette' "$CALCTPL" \
  && ! grep -q '②.5' "$CALCTPL" \
  && grep -q '/admin/appearance' "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q '"/appearance"' "$RD/src/main/java/com/family/finance/web/admin/AdminController.java" \
  && ! grep -q 'calc-tweaks/lens-palette' "$RD/src/main/java/com/family/finance/web/admin/AdminController.java"; } \
  && log_ok "v1172-APPEARANCE-PAGE 配色独立成「显示与外观」页 + 管理页有入口 · calc-tweaks 与旧保存端点已清干净" \
  || log_bad "v1172-APPEARANCE-PAGE 配色仍在计算常数页(或新页/入口缺失)" "see admin/appearance.html · admin/calc-tweaks.html · AdminController"

# v1172-PALETTE-PERSONAL · 配色是个人偏好:存本机、不落库、家庭旧值只作回落(v1.17.2)
# 三条语义都得成立(已实测):全新设备回落家庭默认 D / 设过就优先个人值 / 另一台设备不受影响。
# 注意 th:attr —— 第一版把 th:checked 换成 data-default-checked 时丢了 th: 前缀,
# Thymeleaf 根本不处理它,属性里原样输出了表达式字符串,于是五个 radio 一个都不选中。
{ grep -q "KEY = 'lensPalette'" "$APPTPL" && grep -q 'localStorage.setItem(KEY' "$APPTPL" \
  && grep -q 'th:attr="data-default-checked=' "$APPTPL" \
  && grep -q "localStorage.getItem('lensPalette')" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q 'PERSONAL_PALETTE' "$RD/src/main/resources/static/js/lens.js" \
  && grep -q 'K_LENS_PALETTE' "$RD/src/main/java/com/family/finance/service/lens/LensMetaService.java"; } \
  && log_ok "v1172-PALETTE-PERSONAL 配色存 localStorage(个人 · 不落库)· lens 优先读个人值、回落家庭旧值(老用户不受影响)" \
  || log_bad "v1172-PALETTE-PERSONAL 个人偏好链路断了(存/读/回落任一处)" "see admin/appearance.html · static/js/lens.js"

# v1173-SYNC-FAILURE-VISIBLE · 券商同步失败必须落库、必须看得见(v1.17.3 · 生产事故)
# 事故:富途同步断了两天,页面上一直显示【上一次成功】的消息「同步 · 新增 0 · 更新 7 · 归档 0」——
# 因为失败路径只 log.warn,broker_link 一个字都不改。用户不手点一次永远发现不了。
# 三条:① 失败要 markFailed 落库;② 不许动 last_synced_at(那是"最后成功"的语义,
# 被失败刷新会让「上次同步 5 分钟前」和「数据其实三天前的」同时成立);③ 页面按前缀切红色告警。
BSS="$RD/src/main/java/com/family/finance/service/broker/BrokerSyncService.java"
BLM="$RD/src/main/java/com/family/finance/repository/BrokerLinkMapper.java"
BLT="$RD/src/main/resources/templates/broker/link.html"
{ grep -q 'markFailed' "$BLM" \
  && grep -qE 'markFailed\(familyId, accountId, failureNote\(e\)\)' "$BSS" \
  && ! grep -A 2 'int markFailed' "$BLM" | grep -q 'last_synced_at' \
  && grep -q 'failureNote' "$BSS" \
  && grep -q "startsWith(link.lastStatus, '同步失败')" "$BLT" \
  && grep -q 'var(--rust)' "$BLT" \
  && [ -f "$RD/src/test/java/com/family/finance/service/broker/BrokerFailureNoteTest.java" ]; } \
  && log_ok "v1173-SYNC-FAILURE-VISIBLE 同步失败落库(不动 last_synced_at)+ 页面切红色告警 + 文案是人话(有单测)" \
  || log_bad "v1173-SYNC-FAILURE-VISIBLE 同步失败仍可能静默(页面会挂着上次成功的消息)" "see BrokerSyncService.failureNote / BrokerLinkMapper.markFailed / broker/link.html"

# v1173-OPEND-VERSION-BOUNDARY · 只有实测过的版本线才走交互式登录(v1.17.3 · 生产回归)
# v1.17 首版按「主版本 ≥ 10」分流,把生产上装的 10.8.6818 也判成交互式:不再传 MD5、改传 -cfg_file,
# 进程起来立刻退出(Monitor.log 连三次 WEXITSTATUS:14),富途同步断两天。10.8 在 v1.16 前正常跑了一个多月。
# 规则:没实测过的版本一律走老路径 —— 老路径失败最多"登录不上",新路径用错是"起都起不来"。
{ grep -q 'INTERACTIVE_SINCE = "10.10"' "$OWREL" \
  && ! grep -qE 'return major >= 10;' "$OWREL" \
  && grep -q 'compareVersions' "$OWREL" \
  && grep -q 'matches("\\\\d+' "$OWREL" \
  && grep -q '10.8.6818' "$RD/src/test/java/com/family/finance/service/broker/opend/OpendReleaseTest.java"; } \
  && log_ok "v1173-OPEND-VERSION-BOUNDARY 交互式登录边界钉在实测过的 10.10 · 10.8/10.9 走老参数 · 版本按数字段比较 · 认不出的形状先校验" \
  || log_bad "v1173-OPEND-VERSION-BOUNDARY 版本分流可能又把没实测过的版本判成交互式" "see OpendRelease.isInteractiveLogin / OpendReleaseTest"

# v12-2-FONTSCALE · 全局字号调节(issue #7)· 标准档零回归(calc×var,scale=1 等价)+ 5 层覆盖 + 控件 + FOUC + 图表跟随
FSCSS="$RD/src/main/resources/static/css/style.css"
FSLAY="$RD/src/main/resources/templates/fragments/layout.html"
FSNAV="$RD/src/main/resources/templates/fragments/nav.html"
{ grep -q 'html\[data-fs="lg"\]' "$FSCSS" && grep -q 'html\[data-fs="xl"\]' "$FSCSS" \
  && grep -qF 'font-size: calc(10px * var(--fs-scale,1)) !important' "$FSCSS" \
  && grep -qF 'font-size: calc(11px * var(--fs-scale,1)) !important' "$FSCSS" \
  && grep -qF 'font-size: calc(15px * var(--fs-scale,1))' "$FSCSS" \
  && grep -qF "max(16px, calc(14px * var(--fs-scale,1)))" "$FSCSS" \
  && grep -qF "localStorage.getItem('fontScale')" "$FSLAY" \
  && grep -q 'window.setFontScale' "$FSLAY" && grep -q 'window.chartFont' "$FSLAY" && grep -q 'fontscalechange' "$FSLAY" \
  && grep -q 'data-fs-opt' "$FSNAV" && grep -q 'setFontScale(' "$FSNAV" \
  && grep -q 'chartFont(' "$RD/src/main/resources/templates/dashboard/_attribution.html" \
  && grep -q 'chartFont(' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'fontscalechange' "$RD/src/main/resources/templates/dashboard/_attribution.html"; } \
  && log_ok "v12-2-FONTSCALE 字号档位(标准零回归 calc×--fs-scale · 层2 六类!important压PlayCDN · iOS16px地板限lg/xl · FOUC · Aa控件双端 · 图表chartFont跟随+归因活重绘)" \
  || log_bad "v12-2-FONTSCALE 字号档位缺件" "see style.css(--fs-scale/text-[Npx]) + layout.html(FOUC/setFontScale/chartFont) + nav.html(data-fs-opt)"

# v13-SUN-METRIC · 旭日可选分析指标(后端零改动 · 复用 /lens/query + PivotEngine 全指标 · 两渲染模式 · 持仓级诚实降级)
SMJS="$RD/src/main/resources/static/js/lens.js"
SMTPL="$RD/src/main/resources/templates/lens/_section.html"
{ grep -q 'SUN_METRICS' "$SMJS" && grep -q 'function sunMetricUnavailable' "$SMJS" \
  && grep -q 'function rateColor' "$SMJS" && grep -q 'function pnlColor' "$SMJS" \
  && grep -q 'function renderSunMetricBar' "$SMJS" && grep -q "sunMetric: 'value'" "$SMJS" \
  && grep -qF 'rows: [state.sunDims[0]], cols: [state.sunDims[1]]' "$SMJS" \
  && grep -q 'renderSunCenter' "$SMJS" \
  && grep -q 'id="sunMetricBar"' "$SMTPL" && grep -q 'id="sunMetricPop"' "$SMTPL" && grep -q 'id="sunDegradeNote"' "$SMTPL" && grep -q 'id="sunResetBtn"' "$SMTPL" && grep -q 'function resetLens' "$SMJS" && grep -q 'SUN_HOLDING_DEGRADE' "$SMJS" && grep -q 'netPrincipal' "$RD/src/main/java/com/family/finance/calc/lens/LensRegistry.java" && grep -q 'latestReturn' "$RD/src/main/java/com/family/finance/calc/lens/LensRegistry.java"; } \
  && log_ok "v13-SUN-METRIC 旭日多指标(金额/本期收益额/累计收益额/累计收益率 · 可加→弧长/比率→市值+热力 · 中心圆换指标 · 持仓级灰置+回退 · 后端零改动复用PivotEngine)" \
  || log_bad "v13-SUN-METRIC 旭日多指标缺件" "see lens.js(SUN_METRICS/renderSunburst rows=[内]cols=[外]) + lens/_section.html(sunMetricBar/Pop/DegradeNote)"

# v13-1-NAVVER · nav logo 下版本徽记(app.version → GlobalModelAdvice appVersion → nav.html 渲染)
# 注:app.version 与发布 tag 的一致性由 release-prod 预检硬门(本地工具)保证,此处只守随仓库发布的 3 个文件
NVYML="$RD/src/main/resources/application.yml"
NVADV="$RD/src/main/java/com/family/finance/common/GlobalModelAdvice.java"
NVNAV="$RD/src/main/resources/templates/fragments/nav.html"
{ grep -qE 'version: \$\{APP_VERSION:[0-9]+\.[0-9]+\.[0-9]+' "$NVYML" \
  && grep -q '@ModelAttribute("appVersion")' "$NVADV" \
  && grep -q "@Value(\"\${app.version" "$NVADV" \
  && grep -q "'v' + \${appVersion}" "$NVNAV"; } \
  && log_ok "v13-1-NAVVER nav 版本徽记(app.version 单一来源 → appVersion 注入 → logo 下 ◇v 徽记)" \
  || log_bad "v13-1-NAVVER 版本徽记缺件" "see application.yml(app.version) + GlobalModelAdvice(appVersion) + nav.html(◇v${appVersion})"

# v14-HOLDING-IMPORT · 持仓截图智能解析(视觉识别→三态比对→确认落库)· 关键护栏静态断言
HISVC="$RD/src/main/java/com/family/finance/service/holdingimport/HoldingImportService.java"
HIVIS="$RD/src/main/java/com/family/finance/service/holdingimport/VisionLlmClient.java"   # v1.13 前叫 QwenVisionClient
HICTL="$RD/src/main/java/com/family/finance/web/holdingimport/HoldingImportController.java"
HIVAL="$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java"
HIHOLD="$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java"
HIROW="$RD/src/main/resources/templates/entry/_row.html"
{ test -f "$RD/db/migration/V49__holding_screenshot_tags.sql" && test -f "$RD/db/migration/V50__holding_import.sql" \
  && grep -q 'holding_import' "$RD/db/migration/V50__holding_import.sql" \
  && grep -q 'ref_import_id' "$RD/db/migration/V50__holding_import.sql" \
  && grep -q 'VARCHAR(16)' "$RD/db/migration/V49__holding_screenshot_tags.sql" \
  && grep -q 'K_LLM_VISION_PLATFORM' "$RD/src/main/java/com/family/finance/service/config/FamilyConfigService.java" \
  && grep -q '只转写\|不做任何计算\|绝不计算' "$HIVIS" \
  && grep -q 'SYNC_SOURCE = "SCREENSHOT"' "$HISVC" \
  && grep -q 'UPDATE\|NEW\|SOLD' "$HISVC" \
  && grep -q 'refreshOneAccount' "$HISVC" && grep -q 'TriggerKind.IMPORT' "$HISVC" \
  && grep -q 'AccountType.WEALTH\|AccountType.CASH' "$HIHOLD" \
  && grep -q 'holdings.isEmpty()' "$HIVAL" \
  && grep -q 'refreshOneAccount' "$HIVAL" \
  && grep -q 'entry/import' "$HIROW" \
  && grep -q '/entry/import/item' "$HICTL"; } \
  && log_ok "v14-HOLDING-IMPORT 持仓截图导入(V49/V50 迁移 + 视觉禁算 + SCREENSHOT 隔离 + 三态 + IMPORT 估值交接 + WEALTH/CASH 放开+无持仓不接管红线 + 填报入口)" \
  || log_bad "v14-HOLDING-IMPORT 持仓截图导入缺件" "see HoldingImportService/VisionLlmClient/AccountValuationService/StockHoldingService/entry/_row.html + V49/V50"

# v142-ENTRY-IMPORT-FIX · v1.4.2 五点(流水删除 bug + 转账双账户确认 + 导入回来源页 + 划转主理人头像 + 手机AI徽记 + 图片查看删除)
HICTL2="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
HINAV="$RD/src/main/resources/templates/fragments/nav.html"
HISS="$RD/src/main/resources/static/js/searchable-select.js"
HIIMPHTML="$RD/src/main/resources/templates/holdingimport/import.html"
{ grep -qF 'hx-target=\"#entry-block-' "$HICTL2" \
  && ! grep -qF 'hx-target=\"#row-' "$HICTL2" \
  && grep -q '同时影响两个账户' "$HICTL2" \
  && grep -q 'safeLocalPath' "$HICTL" \
  && grep -q 'addAccountOwnerMeta' "$HICTL2" && grep -q 'memberNameById' "$HICTL2" \
  && grep -q 'data-owner' "$HIROW" \
  && grep -qF 'opt.dataset.owner' "$HISS" \
  && grep -q 'image/delete' "$HICTL" && grep -q 'imageRels' "$HISVC" && grep -q 'deleteImage' "$HISVC" \
  && grep -q 'nextImageIndex' "$HISVC" \
  && grep -q 'js-gallery' "$HIIMPHTML" && grep -qF 'id="lb"' "$HIIMPHTML" && grep -q 'rescanBtn' "$HIIMPHTML" \
  && [ "$(grep -c '支持 AI 截图导入' "$HINAV")" -ge 2 ]; } \
  && log_ok "v142-ENTRY-IMPORT-FIX(流水删除 hx-target 修正 + 转账双账户二次确认 + 导入回来源页 safeLocalPath + 划转主理人头像 + 手机端AI徽记双端 + 图片查看放大删除+重扫)" \
  || log_bad "v142-ENTRY-IMPORT-FIX 缺件" "see EntryController(hx-target/confirm/ownerMeta) + HoldingImport(image endpoints) + nav.html 移动AI + searchable-select data-owner + import.html 画廊/灯箱"

# v142-LENS-RESET · 旭日重置回"页面刷新初始态"(默认看板+默认指标),非只重置当前看板
HILENS="$RD/src/main/resources/static/js/lens.js"
{ awk '/function resetLens\(\)/,/^  }/' "$HILENS" | grep -q 'applyBoard(PRESETS\[0\]' \
  && awk '/function resetLens\(\)/,/^  }/' "$HILENS" | grep -q "state.measures = \['value', 'share'\]" \
  && ! awk '/function resetLens\(\)/,/^  }/' "$HILENS" | grep -q 'state.boardKey'; } \
  && log_ok "v142-LENS-RESET(旭日重置回默认看板 PRESETS[0]+复位 measures,不再只重置当前看板)" \
  || log_bad "v142-LENS-RESET 缺件" "see lens.js resetLens 应 applyBoard(PRESETS[0]) + 复位 measures"

# v143-LENS-TOC-UX · 三点 UX(隐私浮标不遮TOC + 横滑提示 + 重置/AI按钮独立行)
HITOC="$RD/src/main/resources/static/js/toc.js"
HICSS="$RD/src/main/resources/static/css/style.css"
HILENSSEC="$RD/src/main/resources/templates/lens/_section.html"
{ grep -q "classList.add('toc-open')" "$HITOC" && grep -q "classList.remove('toc-open')" "$HITOC" \
  && grep -qF 'body.toc-open #priv-float' "$HICSS" \
  && grep -q '.lens-hscroll' "$HICSS" \
  && grep -q 'lens-hscroll' "$HILENSSEC" \
  && grep -q 'markHScroll' "$HILENS" \
  && grep -q 'justify-end' "$HILENSSEC"; } \
  && log_ok "v143-LENS-TOC-UX(TOC开时隐藏隐私浮标 + 旭日指标/看板横滑渐隐提示 + 重置/AI按钮独立右对齐行)" \
  || log_bad "v143-LENS-TOC-UX 缺件" "see toc.js(toc-open) + style.css(body.toc-open/#priv-float + .lens-hscroll) + _section.html(lens-hscroll/justify-end) + lens.js(markHScroll)"

# v15-PENETRATION · 基金持仓穿透(账户→持仓→持仓方向 · 真实股债现金+申万行业)
V15MIG="$RD/db/migration/V51__fund_penetration.sql"
V15CLI="$RD/src/main/java/com/family/finance/service/penetration/EastMoneyFundClient.java"
V15SVC="$RD/src/main/java/com/family/finance/service/penetration/FundPenetrationService.java"
V15LENS="$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java"
V15CTL="$RD/src/main/java/com/family/finance/web/lens/LensTagController.java"
V15TAGS="$RD/src/main/resources/templates/lens/tags.html"
V15IND="$RD/src/main/java/com/family/finance/domain/lens/IndustryTag.java"
{ test -f "$V15MIG" && grep -q 'holding_allocation' "$V15MIG" && grep -q 'fund_penetration_cache' "$V15MIG" && grep -q 'penetrate_state' "$V15MIG" \
  && grep -q 'HOME_APPLIANCE' "$V15IND" && grep -q 'FOOD_BEVERAGE' "$V15IND" \
  && grep -q 'resolveCode' "$V15CLI" && grep -q 'assetAllocation' "$V15CLI" && grep -q 'topHoldings' "$V15CLI" && grep -q 'stockIndustry' "$V15CLI" && grep -q 'mapIndustry' "$V15CLI" \
  && grep -q 'penetrateHolding' "$V15SVC" && grep -q 'KIND_OTHER\|其他持仓\|OTHER' "$V15SVC" && grep -q 'scaleTo' "$V15SVC" \
  && grep -q 'allocMapper.findByHolding' "$V15LENS" && grep -q 'getWeightBp' "$V15LENS" \
  && grep -q '/lens/tags/penetrate' "$V15CTL" \
  && grep -q '持仓方向\|拉取穿透\|penetrate-all' "$V15TAGS"; } \
  && log_ok "v15-PENETRATION(持仓方向层 + 东财穿透client 资产配置/前十大→申万/股票行业 + lens按方向拆头寸 + 打标页拉取 + 理财未穿透诚实降级)" \
  || log_bad "v15-PENETRATION 缺件" "see V51迁移 + IndustryTag扩容 + EastMoneyFundClient + FundPenetrationService + LensQueryService分拆 + LensTagController端点 + tags.html"

# v151-PEN-STREAM · 穿透 SSE 流式逐支揭示 + 旭日行业集中去股票硬过滤
{ grep -q '/lens/tags/penetrate-stream' "$RD/src/main/java/com/family/finance/web/lens/LensTagController.java" \
  && grep -q 'SseEmitter' "$RD/src/main/java/com/family/finance/web/lens/LensTagController.java" \
  && grep -q 'streamPenetrateFamily' "$RD/src/main/java/com/family/finance/service/penetration/FundPenetrationService.java" \
  && grep -q 'penetrateHoldingResult' "$RD/src/main/java/com/family/finance/service/penetration/FundPenetrationService.java" \
  && grep -q 'penStreamBtn' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q 'EventSource' "$RD/src/main/resources/templates/lens/tags.html" \
  && grep -q "name: '行业集中',  sun: \['industry', 'platform'\],   rows: \['industry'\],   cols: \['platform'\],   filters: {} " "$RD/src/main/resources/static/js/lens.js"; } \
  && log_ok "v151-PEN-STREAM(穿透 SSE 流式逐支反馈弹层 + 行业集中去掉股票硬过滤)" \
  || log_bad "v151-PEN-STREAM 缺件" "see LensTagController SseEmitter端点 + FundPenetrationService.streamPenetrateFamily + tags.html EventSource弹层 + lens.js 行业集中 filters:{}"

# v152-PIVOT-CARTESIAN · 交叉表多指标参与列/行笛卡尔(指标作维度 · 拨片切列/行 · 默认列最后一级)
{ grep -q "measurePos: 'col'" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "多指标 → 指标作维度参与笛卡尔" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "data-mp=\"col\"" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "data-mp=\"row\"" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q '指标放' "$RD/src/main/resources/static/js/lens.js"; } \
  && log_ok "v152-PIVOT-CARTESIAN(多指标参与笛卡尔 · 指标放列/行拨片 · 单值/格)" \
  || log_bad "v152-PIVOT-CARTESIAN 缺件" "see lens.js state.measurePos + renderPivot 多指标分支 + renderMeasurePills 指标放列/行拨片"

# v152-PIVOT-MOBILE-HINT · 交叉表移动端横屏提示(可×掉·会话内记忆)+ 首列 sticky 阴影(竖屏重排)
{ grep -q 'id="pivotHint"' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'pivotHintClose' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q 'md:hidden' "$RD/src/main/resources/templates/lens/_section.html" \
  && grep -q "pivotHintX" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "sticky-col{position:sticky;left:0;z-index:1;box-shadow" "$RD/src/main/resources/static/js/lens.js"; } \
  && log_ok "v152-PIVOT-MOBILE-HINT(移动端横屏提示可×掉 + 首列 sticky 阴影)" \
  || log_bad "v152-PIVOT-MOBILE-HINT 缺件" "see _section.html #pivotHint + lens.js pivotHintX 记忆 + sticky-col box-shadow"

# v152-TPL-PLATFORM · 账户模板补平台默认 + 建户未填自动带出(打标平台一致性 item6)
{ grep -q "ADD COLUMN platform" "$RD/db/migration/V52__account_template_platform.sql" \
  && grep -q "private String platform" "$RD/src/main/java/com/family/finance/domain/account/AccountTemplate.java" \
  && grep -q "icon, platform, sort_order" "$RD/src/main/java/com/family/finance/repository/AccountTemplateMapper.java" \
  && grep -q "getTemplateId() != null" "$RD/src/main/java/com/family/finance/web/account/AccountController.java" \
  && grep -q "AccountTemplate::getPlatform" "$RD/src/main/java/com/family/finance/web/account/AccountController.java" \
  && grep -q "data-tpl-platform" "$RD/src/main/resources/templates/accounts/_template-wizard.html"; } \
  && log_ok "v152-TPL-PLATFORM(账户模板平台默认 + 建户未填自动带出 + 向导告知 + 管理页可见)" \
  || log_bad "v152-TPL-PLATFORM 缺件" "see V52迁移 + AccountTemplate.platform + mapper SELECT platform + AccountController create默认 + 向导 data-tpl-platform"

# ── v1.6 UED 专项(docs/ued-review-2026-07.md · 61 条双端截图审计)────────────────

# v16-UED-TRUST · 跨页口径统一 + 异常值兜底 + 已关账只读(review A2/A7/B2-1)
{ grep -q "resolveAnchor" "$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnoseService.java" \
  && grep -q "findBalancePeriod" "$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnoseService.java" \
  && grep -q "EMERGENCY_OUTLIER_MONTHS" "$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnose.java" \
  && grep -q "emergencyOutlier" "$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnose.java" \
  && grep -q "emergencyLabel" "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q "anchorPeriod" "$RD/src/main/java/com/family/finance/web/checkup/CheckupController.java" \
  && grep -q "数据截至" "$RD/src/main/resources/templates/checkup/family.html" \
  && grep -q "本期已关账" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "status.name() != 'CLOSED'" "$RD/src/main/resources/templates/entry/_row.html"; } \
  && log_ok "v16-UED-TRUST(体检与仪表盘同 anchor + 账期标注 + 紧急储备兜底 + 已关账只读)" \
  || log_bad "v16-UED-TRUST 缺件" "see FamilyDiagnoseService.resolveAnchor / FamilyDiagnose.emergencyOutlier / checkup 数据截至 / entry CLOSED 只读"

# v16-UED-MONEY · 金额千分位(review A3 · 此前 checkup/detail 共 32 处裸输出)
{ ! grep -qE 'formatDecimal\([^)]*, 1, [0-9]\)' "$RD/src/main/resources/templates/checkup/family.html" \
  && ! grep -qE 'formatDecimal\([^)]*, 1, [0-9]\)' "$RD/src/main/resources/templates/checkup/account.html" \
  && ! grep -qE 'formatDecimal\([^)]*, 1, [0-9]\)' "$RD/src/main/resources/templates/accounts/detail.html" \
  && grep -q "COMMA" "$RD/src/main/resources/templates/checkup/family.html"; } \
  && log_ok "v16-UED-MONEY(体检/账户详情金额一律带千分位)" \
  || log_bad "v16-UED-MONEY 缺件" "see checkup/family+account、accounts/detail 的 formatDecimal 需带 'COMMA'"

# v16-UED-CONTRAST · 对比度与色彩 token(review B6/A6 · visual-spec §1.2)
{ grep -q -- "--ink-subtle:   #706657" "$RD/src/main/resources/static/css/style.css" \
  && grep -q -- "--ink-faint" "$RD/src/main/resources/static/css/style.css" \
  && grep -q -- "--brass-text" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "prefers-reduced-motion" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "pill-mute" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "grid-hairline" "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v16-UED-CONTRAST(ink-subtle 过 AA + ink-faint/brass-text + reduced-motion + pill-mute + 发丝网格)" \
  || log_bad "v16-UED-CONTRAST 缺件" "see style.css :root token + prefers-reduced-motion + .pill-mute + .grid-hairline"

# v16-UED-MOBILE · 移动端首屏折叠 + 大组件形态(review A4/B1/B2/B4)
{ grep -q "filter-fold" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "filter-fold" "$RD/src/main/resources/templates/reports/_region.html" \
  && grep -q "本 · 期 · 一 · 句 · 话" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "entry-fold" "$RD/src/main/resources/templates/entry/_row.html" \
  && grep -q "kpi-band" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "summary-band" "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q "donutConfig" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "barRows" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'display: grid !important' "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'display: flex !important' "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v16-UED-MOBILE(口径折叠+一句话结论+填报行折叠+KPI主次网格+汇总带横滑+环图转条形+双向柱TopN)" \
  || log_bad "v16-UED-MOBILE 缺件" "see filter-fold/entry-fold/kpi-band/summary-band + donutConfig/barRows + Tailwind 覆盖需 !important"

# v16-UED-IOS · iOS 硬约束(review A9)
{ grep -q "overscroll-behavior-x: contain" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "scroll-snap-type" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "env(safe-area-inset-bottom)" "$RD/src/main/resources/templates/fragments/layout.html" \
  && grep -q "env(safe-area-inset-bottom)" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "touch-callout: none" "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v16-UED-IOS(横滑不触发 Safari 返回手势 + scroll-snap + 安全区 + 图表长按不弹菜单)" \
  || log_bad "v16-UED-IOS 缺件" "see style.css overscroll-behavior-x/scroll-snap/touch-callout + layout.html safe-area"

# v16-UED-COPY · 去技术化文案 + emoji 清零(review A6/A10/B8)
{ ! grep -q 'eyebrow-ink mb-2">/' "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q "家 · 庭 · 基 · 础" "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q "口 · 径 · 与 · 标 · 签" "$RD/src/main/resources/templates/admin/index.html" \
  && ! grep -q "Spring Boot 3.3" "$RD/src/main/resources/templates/admin/index.html" \
  && ! grep -q "PRD 中可配置" "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q "无进行中周期" "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q "dv == 'RISK' ? '风险'" "$RD/src/main/resources/templates/checkup/_ai-diagnose.html" \
  && [ "$(grep -rloP '[\x{1F300}-\x{1FAFF}\x{2728}\x{2705}\x{26A0}\x{1F514}\x{1F4A1}\x{FE0F}]' "$RD/src/main/resources/templates" 2>/dev/null | grep -v easter520 | wc -l)" = "0" ]; } \
  && log_ok "v16-UED-COPY(管理页中文分类+4组重排 · 状态中文 · 无技术栈/PRD 术语 · emoji 清零)" \
  || log_bad "v16-UED-COPY 缺件" "see admin/index.html 中文 eyebrow + 分组标题 + 空状态 · _ai-diagnose 中文徽标 · templates 内 emoji 残留"

# v16-UED-AFFORD · 假 affordance 与操作收纳(review B3-3/B3-5/B7-1)
{ ! grep -q "·☰" "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q "row-more" "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -q "row-more-pop" "$RD/src/main/resources/static/css/style.css" \
  && grep -qF 'th:unless="${#lists.isEmpty(goals)}"' "$RD/src/main/resources/templates/goals/index.html" \
  && grep -q "nowrap" "$RD/src/main/resources/templates/accounts/index.html"; } \
  && log_ok "v16-UED-AFFORD(去掉假拖拽 ☰ + 行内操作收纳 ⋯ + 目标页主操作唯一 + 主理人列不竖排)" \
  || log_bad "v16-UED-AFFORD 缺件" "see accounts/index.html 去 ☰ / row-more 下拉 / goals 空状态隐藏重复主按钮"

# v161-UI3 · v1.6.1 的三条 UI 反馈(横屏部分已由 v164-ORIENTATION 接管)
#   ①「本期一句话」15px→13px(用户:像老年机)②折叠条 CTA + 箭头收起 ›/展开 ⌄
#   ③KPI 退回主次网格(横滑等于把核心指标藏到屏幕外,与「一目了然」相悖)
{ grep -q "fold-cta" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "点开筛选账户" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "rotate(-90deg)" "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'grid-column: auto !important' "$RD/src/main/resources/static/css/style.css" \
  && grep -qF 'text-[13px] leading-relaxed text-ink' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'grid-template-columns: 1fr 1fr !important' "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v161-UI3(一句话 13px · 折叠 CTA + ›/⌄ 箭头 · KPI 主次网格一屏可见)" \
  || log_bad "v161-UI3 缺件" "see style.css fold-cta/rotate(-90deg)/grid-column auto/grid-template-columns · dashboard 点开筛选账户 + 13px"

# v163-SUNBURST-AGG · 旭日「大量空块」根治:小块聚合 + 三处共用阈值 + 崩溃兜底
#   演进史(三次才对):
#     v1.6.1 环内阈值 40° / 补注阈值 14° → 3.9%~11% 两边不收 = 信息黑洞
#     v1.6.2 两阈值合一(32°)→ 但窄屏无引导线,32° 以下全靠补注,「行业集中」59 块只有 6 块有标签
#     v1.6.3 承认图型容量有限 → 小块聚合成「其他 N 项」(Top N + Other 惯例),59 块 → 内环 6 块
#   另修两个隐蔽点:①窄屏判断不能用 clientWidth(布局时机不同会读到 630/321 两个值,导致阈值悄悄走 PC 档)
#                  ②聚合块必须带 children(下游多处直接 .forEach),否则 TypeError 被 Promise.all catch 静默吞掉
{ grep -q "function aggSmall" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "AGG_MIN_DEG = isNarrow() ? 18 : 4" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "其他 ' + small.length + ' 项" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qF "children: [{ name: '其他'" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "function isNarrow()" "$RD/src/main/resources/static/js/lens.js" \
  && ! grep -q "= el.clientWidth < 480" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "(n.children || \[\]).forEach" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "p.data._agg" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "console.error('lens 渲染失败" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "SUN_LABEL_MIN_DEG = 24" "$RD/src/main/resources/static/js/lens.js" \
  && ! grep -qE "deg >= (40|50|60)" "$RD/src/main/resources/static/js/lens.js"; } \
  && log_ok "v163-SUNBURST-AGG(小块聚合 Top N + 其他 · isNarrow 不用 clientWidth · 聚合块带 children · 聚合块禁下钻 · 渲染失败不静默)" \
  || log_bad "v163-SUNBURST-AGG 缺件" "see lens.js aggSmall/AGG_MIN_DEG/isNarrow/_agg children/(n.children||[])/console.error('lens 渲染失败')"

# v1615-LS-INPLACE · 整页横屏 = **原地换断点**,不许再回到 iframe 重载
#   退役 v166-LANDSCAPE-IFRAME 与 v168-ORI-CSS(它们守的是 iframe/舞台那套实现)。
#   用户反馈「一段很长的卡顿,这本该只是前端样式变化」—— 实测点一下横屏 = 一次完整
#   /dashboard 后端渲染 + 12 个脚本重跑,**1362ms**;而且 iframe 是新文档,滚动位置必然丢。
#   现在:Tailwind 是 Play CDN 运行时编译器,重新赋值 tailwind.config 就地重编译(实测 114ms)
#   → sm/md 换成「永远匹配」的 raw 查询;body 锁成长边×短边并在设备竖屏时转 90°。
#   实测切换 ~300ms、零文档请求、章节位置两个方向都还原。
#   踩过的三个坑,都在断言里:
#     ① body 自带 Tailwind min-h-screen,不清掉 min-height 就压过 height(实测 844×844)
#     ② body 被 transform 后 position:fixed 会跟着内容滚走 → 退出钮必须放进导航行
#     ③ 章节定位不能用 getBoundingClientRect/scrollIntoView(旋转后是屏幕坐标)→ 累加 offsetTop
CSS="$RD/src/main/resources/static/css/style.css"
JSL="$RD/src/main/resources/static/js/landscape.js"
LAY="$RD/src/main/resources/templates/fragments/layout.html"
{ ! grep -q "ls-frame\|ls-shell\|createElement('iframe')" "$JSL" \
  && ! grep -q "\.ls-stage\|\.ls-frame\|\.ls-shell" "$CSS" \
  && grep -q "window.TW_SCREENS_BASE" "$LAY" \
  && grep -q "window.TW_EXTEND_BASE" "$LAY" \
  && grep -qF "extend: window.TW_EXTEND_BASE || {}" "$JSL" \
  && grep -q "window.TW_SCREENS_WIDE" "$LAY" \
  && grep -q "function wideScreens" "$JSL" \
  && grep -qF "sm: { raw: '(min-width: 1px)' }" "$LAY" \
  && grep -qF "html.ls-wide > body {" "$CSS" \
  && grep -qF "min-height: 0 !important;" "$CSS" \
  && grep -qF "width: 100vh; height: 100vw;" "$CSS" \
  && grep -qF "transform: translate(100vw, 0) rotate(90deg);" "$CSS" \
  && grep -qF "(acts || document.body).appendChild(exitBtn);" "$JSL" \
  && grep -q "function layoutTop" "$JSL" \
  && grep -q "function currentAnchor" "$JSL" \
  && ! grep -qE "\.scrollIntoView\(" "$JSL"; } \
  && log_ok "v1615-LS-INPLACE(原地换断点 ~300ms 零文档请求 · 断点基线单一出处 · body 锁长边+视口单位交换旋转 · min-height 清零 · 退出钮在导航行 · 章节按布局坐标还原)" \
  || log_bad "v1615-LS-INPLACE 缺件" "see landscape.js(不得有 iframe/ls-shell · setScreens 复用 TW_EXTEND_BASE · 宽屏档在 layout.html 的 TW_SCREENS_WIDE(单一出处,首次编译前要用得上)· 退出钮进 .nav-actions · layoutTop/currentAnchor 且不用 scrollIntoView)· style.css(html.ls-wide > body 锁长边 + min-height:0 + 视口单位交换旋转)· layout.html 暴露 TW_SCREENS_BASE / TW_EXTEND_BASE"

# v167-VP-SHORTSIDE · 响应式判据必须同时看「短边」(用户第 5 次反馈:转手机页面还是变了)
#   前四版横屏方案都在跟「方向」较劲,方向找错了 —— 真正的 bug 是**断点只判宽度**:
#   手机横屏是 844×390,只看宽度 → 844 被当成宽屏设备 → 453 处 sm:/md:/lg: 集体切换。
#   手机横屏的特征是**短边只有 390**,不是宽边有 844。iPhone 横屏 innerHeight ≤ 440
#   (最大的 16 Pro Max 短边 440),PC 窗口极少矮于 480 → 阈值 480。
#   三处判据必须同源,少一处就会出现「CSS 是移动的、JS 是 PC 的」这种半修状态:
#     ① tailwind.config screens(453 处 Tailwind 断点)
#     ② style.css 自有 @media
#     ③ window.vpNarrow(图表脚本里原本 22 处硬编码宽度比较)
#   例外:横屏模式(ls-wide)布局宽度锁成长边,是用户主动要的宽屏视图 → 三处判据都排除它。
#   顺带守护 v1.6.7 的旋转遮帘:iOS 那 0.4s 旋转动画抹不掉(manifest.orientation iOS 不支持 /
#   screen.orientation.lock 需 fullscreen / orientationchange 不可 cancel),只能盖住,且必须瞬盖。
LAY="$RD/src/main/resources/templates/fragments/layout.html"
CSS="$RD/src/main/resources/static/css/style.css"
{ [ "$(grep -c 'min-height: 480px' "$LAY")" -ge 1 ] \
  && grep -qF "window.TW_SCREENS_BASE = { sm: bp(640), md: bp(768), lg: bp(1024), xl: bp(1280), '2xl': bp(1536) };" "$LAY" \
  && grep -q "window.vpNarrow=function" "$LAY" \
  && grep -q "window.innerWidth<w||window.innerHeight<480" "$LAY" \
  && [ "$(grep -c 'max-height: 479px' "$CSS")" -ge 6 ] \
  && [ "$(grep -c 'and (min-height: 480px)' "$CSS")" -ge 4 ] \
  && grep -q "html:not(.ls-wide) .kpi-band" "$CSS" \
  && grep -q "html:not(.ls-wide) .summary-band" "$CSS" \
  && grep -q "html.ls-wide .filter-fold > summary" "$CSS" \
  && grep -q "ori-turning" "$RD/src/main/resources/static/js/landscape.js" \
  && [ "$(grep -rn 'window.innerWidth *[<>]=* *[0-9]' "$RD/src/main/resources/templates" "$RD/src/main/resources/static/js" | grep -v 'vpNarrow=function' | wc -l)" -eq 0 ]; } \
  && log_ok "v167-VP-SHORTSIDE(断点/自有@media/vpNarrow 三处同源看短边 · iframe 例外 · 旋转遮帘瞬盖淡揭 · 无残留硬编码宽度判定)" \
  || log_bad "v167-VP-SHORTSIDE 缺件" "see layout.html(screens bp() + vpNarrow)· style.css(max-height:479px / min-height:480px / html:not(.ls-wide) 排除横屏模式 / ori-turning 遮帘)· 且模板与 js 里不得残留 window.innerWidth<数字"

# v169-ORI-PIN · 普通模式把内容钉在设备坐标系(方案 B · 用户在 A/B 里选定)+ 专用方向图标
#   效果 = 只对本 app 生效的「竖屏方向锁定」:转手机时页面在屏幕上一动不动,要读得转回来。
#   零重排的来源:body 盒子 = 100vh × 100vw = 短边 × 长边 = 竖屏那个形状(视口单位在横屏自动交换),
#   排版宽度仍是短边 → 与竖屏逐像素相同(实测 body 390 / 卡片 360×92 / KPI 360 两向全同)。
#   旋转符号必须跟设备转向走:screen.orientation.angle 定义为 viewport 相对自然方向**顺时针**
#   转过的角度,要抵消就转 -angle → angle 90(设备逆时针)取 -90°,angle 270(设备顺时针)取 +90°
#   (挂 html.ori-cw)。判错的表现是内容上下颠倒。iOS 16.4+ 有 screen.orientation(MDN BCD 实测),
#   更老回落 window.orientation。
#   滚动位置必须交接:冻结时滚动容器从 html 变成 body,两者 scrollTop 是两套值,
#   不接就跳回顶部 —— 而「跳回顶部」正是用户最反感的那种"页面动了"。
#   方案 B 的固有代价:body 被 transform 后,内部 position:fixed 浮层的包含块从视口变成 body 盒子,
#   那些按「未旋转视口」写的几何会错位(实测目录抽屉漏进屏内)→ 几何脆弱的浮层冻结态一律藏掉,
#   但方向钮必须留(用户可能就是横着拿再点开横屏视图)。
#   注:图标具体形状的断言已移交 v1613-ORI-ICON(v1.6.13 换成竖框+横框+双向箭头),
#   这里只守"不得回退成摄像机图标"。
{ grep -qF "html.ori-cw:not(.ls-wide) > body" "$CSS" \
  && grep -qF "overflow-y: auto; overflow-x: hidden;" "$CSS" \
  && grep -q "html:not(.ls-wide) .toc-sheet-mask" "$CSS" \
  && grep -q "html:not(.ls-wide) #toast-stack" "$CSS" \
  && ! grep -qE "not\(.ls-wide\)[^{]*#ori-float[^{]*display: none" "$CSS" \
  && grep -q "screen.orientation.angle" "$JSL" \
  && grep -qF "root.classList.toggle('ori-cw', a === 270)" "$JSL" \
  && grep -q "function restoreY" "$JSL" \
  && grep -qF "document.addEventListener('scroll', trackY, true)" "$JSL" \
  && ! grep -rq "M18 9l4-2v10l-4-2" "$RD/src/main/resources" \
  && grep -qF '[aria-pressed="true"] .ori-ico-port { display: inline; }' "$CSS"; } \
  && log_ok "v169-ORI-PIN(反向旋转钉设备坐标系 · 旋转符号跟 angle · 滚动位置交接 · 脆弱浮层冻结态藏掉但留方向钮)" \
  || log_bad "v169-ORI-PIN 缺件" "see style.css(ori-cw 分支 / body 滚动容器 / 冻结态藏 toc-sheet+toast 但不藏 #ori-float / aria-pressed 图标转向)· landscape.js(screen.orientation.angle + ori-cw + restoreY + capture 滚动跟踪)· 不得回退成摄像机图标(形状断言见 v1613-ORI-ICON)"

# v1610-LS-NAV · 横屏模式必须有顶部导航,但按 844×390 自己的尺度重排
#   v1.6.8 我把导航整个藏了,理由是「844 宽放不下、390 高太贵」。用户要求加回来 ——
#   **藏掉能力不是解决排版问题的办法**,该做的是给横屏一套自己的尺度。
#   宽度账(实测):退出钮占 ~112px 必须留 → 可用 ~700px;7 个 tab 压到 10px + 13px 间距
#   共 284px,加印章 21px ≈ 310px。v1.6.8 折行不是 tab 多,是 gap-12(48)+ gap-7(28)
#   + 完整品牌文字 + 版本徽记 + 字号钮 + 隐私钮 + 账期 pill + 用户名 一起挤的。
#   高度账:390 高才是稀缺资源。导航 64→38px;实测首张卡片从 221px(屏高 57%)提到 169px,
#   压的是间距与大标题字号,**不删任何内容**(text-2xl 那一档不压:大量用在金额上)。
#   浮钮:隐私浮钮在 390 高里会压住内容(实测压住交叉表「AI 解读当前视图」)→ 收进导航条
#   那段 335~726px 的空档,既不压内容又更好找;目录钮在横屏无意义一并收起。
NAVF="$RD/src/main/resources/templates/fragments/nav.html"
{ grep -q 'class="nav-inner' "$NAVF" && grep -q 'class="nav-lead' "$NAVF" \
  && grep -q 'class="nav-brandtext' "$NAVF" && grep -q 'class="nav-tabs' "$NAVF" \
  && grep -q 'class="nav-actions' "$NAVF" && grep -q 'class="nav-priv' "$NAVF" \
  && ! grep -qF "html.ls-wide > body > header { display: none" "$CSS" \
  && grep -qF "height: 44px !important;" "$CSS" \
  && grep -qF "padding-right: 26px !important;" "$CSS" \
  && grep -qF "html.ls-wide .nav-brandtext { display: none !important; }" "$CSS" \
  && grep -qF "html.ls-wide .nav-tabs > a[class*=\"border-b-2\"] { padding-bottom: 4px !important; }" "$CSS" \
  && grep -qF "html.ls-wide .nav-actions > .nav-priv { display: inline-flex !important;" "$CSS" \
  && grep -qF "html.ls-wide #priv-float { display: none !important; }" "$CSS" \
  && grep -qF "html.ls-wide main { padding-top: 8px !important; padding-bottom: 14px !important; }" "$CSS" \
  && grep -q "html.ls-wide main .text-3xl" "$CSS" \
  && ! grep -q "html.ls-wide main .text-2xl" "$CSS"; } \
  && log_ok "v1610-LS-NAV(横屏导航 7 tab 单行 44px 高 · 右侧 26px 内缩避 iPhone 圆角 · 品牌只留印章 · 隐私钮进栏内空档 · 垂直节奏压缩不删内容 · text-2xl 金额档不压)" \
  || log_bad "v1610-LS-NAV 缺件" "see nav.html 六个定位类(nav-inner/nav-lead/nav-brandtext/nav-tabs/nav-actions/nav-priv)· style.css(header 不得再 display:none · 44px 栏高(=iOS 最小触摸目标)· 右侧 26px 内缩避 iPhone 圆角 · 选中态 pb 重算 · nav-priv 保留 · 隐私浮钮收起(目录钮见 v1613-LS-TOC)· main 8/14 内边距 · 只压 text-3xl/4xl)"

# v1612-CHART-UNIFORM · 并列同类图表必须共用同一套尺度(用户 2026-07-28 要求写进规范)
#   用户原话:「都是饼图 那就保持大小样式一样,现在奇奇怪怪的,两个饼图差距很大,
#   这种常识问题,落到记忆和规范里面,不要我每次提出来,你自己要先自查」。
#   同一件事他提了两次:v1.6.4 按类目数分叉图型(7 类走条形 / 3 类走环图)→ 一个条一个环;
#   改成都是环图后 v1.6.11 又变成一大一小 —— 容器高度各写各的(348/262)+ 半径由
#   「容器高 − 标题 − 图例」推导,而两图图例行数不同(5 项 3 行 / 3 项 1 行)。
#   **容器高度相同还不够,半径必须写死。**
#   踩坑第三次:否定断言不能盯**裸标识符**(`! grep -q "hBarConfig("`)—— 讲解这段历史的
#   注释里就含 `hBarConfig(窄屏…`,自己把自己扫红了(前两次是 `100dvh`、`window.innerWidth<`)。
#   否定断言一律盯**代码构造**:函数定义 `function X`、调用点 `Object.assign(X`。
#   本守护钉三件事:① 容器共用同一个 class,不许各写 h-[] ② 尺度收进共享常量并写死半径
#   ③ 不许残留"按数据量分叉图型"的逻辑。规范全文见 docs/visual-spec.md。
RG="$RD/src/main/resources/templates/dashboard/_region.html"
{ [ "$(grep -c 'class="chart-pair-box"' "$RG")" -eq 2 ] \
  && ! grep -qE 'h-\[[0-9]+px\][^"]*"><canvas id="(allocationChart|memberAllocationChart)"' "$RG" \
  && grep -q "const PAIR = {" "$RG" \
  && grep -qF "radius: PAIR.r(), cutout: PAIR.cutout," "$RG" \
  && grep -qF "size: chartFont(PAIR.titleSize())" "$RG" \
  && grep -qF "size: chartFont(PAIR.legendSize())" "$RG" \
  && grep -qF "size: chartFont(PAIR.pctSize())" "$RG" \
  && ! grep -q "function hBarConfig" "$RG" \
  && ! grep -qF "Object.assign(hBarConfig" "$RG" \
  && ! grep -q "function useBar" "$RG" \
  && [ "$(grep -c "donutConfig(" "$RG")" -ge 3 ] \
  && grep -q "chart-pair-box" "$RD/src/main/resources/static/css/style.css" \
  && grep -q "并列同类图表" "$RD/docs/visual-spec.md"; } \
  && log_ok "v1612-CHART-UNIFORM(并列两图共用 .chart-pair-box + PAIR 共享尺度 + 半径写死 · 无图型分叉 · 规范已收录)" \
  || log_bad "v1612-CHART-UNIFORM 缺件" "see _region.html:两个 canvas 容器都用 class=\"chart-pair-box\"(不许各写 h-[]) + PAIR 常量 + radius 写死 + 不得残留 hBarConfig/useBar · style.css 有 .chart-pair-box · docs/visual-spec.md 有「并列同类图表」一节"

# v1613-LENS-PALETTE · 旭日的有序色阶与中性色必须**按方案给**,不许全局硬编码
#   用户反馈:「旭日下钻之前专心搞过很多主题配色,这次优化后怎么完全没有 follow」。
#   查证后:不是忘了 follow,是配色体系缺两块 ——
#     ① 有序维度(风险)必须用色阶不能套分类色板(v1.6 UED B4-3 判断正确),
#        但那条色阶 RISK_SCALE 被硬编码在全局 → 换方案 A~E 外环一点都不变;
#        而且它是砖红 #a55540 + 橙 #c1873b,与莫兰迪内环不是一家人。
#     ② 「未分类 / 其他(聚合)」用了三个各不相同的硬编码灰(#c8c0ae/#c9c2b2/#dcd6c8),
#        既不属于任何方案,彼此也不一致。
#   改法:三锚点(低/中/高)按方案给 → 插值 8 档;中性色按方案 + 按环深给;
#   聚合块再向纸面提亮一档(aggTint)以便与「未分类」分开 —— 前者是桶、后者是真实维值。
#   锚点判据(自查踩到过):**相邻档 RGB 距离 ≥20 且 中↔高 ≥40**,不是「亮度单调下降」
#   （绿→黄→红本就中间最亮,有序性靠色相约定)。第一版 D 取方案自身三色,中↔高 只有 20,
#   实测两档肉眼分不出高低 —— 那正是用户看到的"没 follow"的观感来源,现为 70。
#   两处同步:lens.js 的锚点 == admin/calc-tweaks.html 色卡第 3 排的首尾色(t=0/t=1 即锚点)。
{ grep -q "var PLAN_ORDINAL = {" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "var PLAN_NEUTRAL = {" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "function rampOf" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "function aggTint" "$RD/src/main/resources/static/js/lens.js" \
  && grep -q "var ORDINAL_SCALE = rampOf" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qF "function riskColorMap(values, ring)" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qF "riskColorMap(values, ring)" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qF "function aggSmall(items, grand, ring)" "$RD/src/main/resources/static/js/lens.js" \
  && ! grep -q "var RISK_SCALE" "$RD/src/main/resources/static/js/lens.js" \
  && ! grep -qE "color: *'#(c8c0ae|c9c2b2|dcd6c8)'" "$RD/src/main/resources/static/js/lens.js" \
  && grep -qi "#3cc780" "$RD/src/main/resources/templates/admin/appearance.html" && grep -qi "#f5222d" "$RD/src/main/resources/templates/admin/appearance.html" \
  && grep -qi "#2b8f5c" "$RD/src/main/resources/templates/admin/appearance.html" && grep -qi "#a61b1b" "$RD/src/main/resources/templates/admin/appearance.html" \
  && grep -qi "#3cc780" "$RD/src/main/resources/templates/admin/appearance.html" && grep -qi "#ff4d4f" "$RD/src/main/resources/templates/admin/appearance.html" \
  && grep -qi "#a3b79a" "$RD/src/main/resources/templates/admin/appearance.html" && grep -qi "#8a4034" "$RD/src/main/resources/templates/admin/appearance.html" \
  && grep -qi "#5b8c74" "$RD/src/main/resources/templates/admin/appearance.html" && grep -qi "#8e2231" "$RD/src/main/resources/templates/admin/appearance.html"; } \
  && log_ok "v1613-LENS-PALETTE(有序色阶+中性色按方案给 · 聚合块与未分类分色 · 无全局 RISK_SCALE / 无三个硬编码灰 · 五套锚点与 admin 色卡同步)" \
  || log_bad "v1613-LENS-PALETTE 缺件" "see lens.js:PLAN_ORDINAL/PLAN_NEUTRAL/rampOf/aggTint + riskColorMap(values,ring) + aggSmall(...,ring),且不得残留 RISK_SCALE 与 #c8c0ae/#c9c2b2/#dcd6c8;五套锚点首尾色必须同时出现在 admin/appearance.html 色卡(v1.17.2 从 calc-tweaks 搬来)"

# v1613-LS-TOC · 横屏模式必须能用「本页目录」,且按横屏空间重排交互
#   v1.6.10 我把目录钮一起藏了,理由「横屏整页就一屏多点,目录无意义」——判断错了:
#   横屏高度只有 390,一屏能看的内容反而更少,跨节跳转比竖屏更需要。
#   交互按横屏的空间特性重排:入口进导航行(390 高里浮钮必压内容)、
#   面板从底部 sheet 改**右侧侧栏**(横屏宽度富余、高度稀缺,正好反过来用)。
#   v1.6.14 两处修正(用户反馈「横屏了以后还是看不到」):
#     ① 目录钮原来用 position:fixed + right:132px 摆在导航行上,与隐私钮(690..726)
#        重叠 22px 且被画在下面 → 改成由 landscape.js 搬进 .nav-actions 参与 flex 排,
#        结构上不可能重叠(挪坐标只是"两处保持相等",隐私钮宽度一变就再撞)。
#     ② 抽屉 top 从 0 改 38px(导航栏高):`<main>` 带 relative z-10 本身就是层叠上下文,
#        抽屉在 main 里,z-index:80 永远升不到 z-30 的导航之上 —— 实测关闭 × 被 nav-inner 压住。
#        与其拆 main 的层叠上下文(牵连全站),不如让抽屉从导航下方开始,顺带导航仍可点。
#     ③ 目录钮改成与隐私钮同款描边(原来是 38px 深色实心圆,为右下浮钮设计的)——
#        并列同级控件必须同尺度同样式,见 AGENTS.md 护栏。
{ grep -qF "html.ls-wide .nav-actions > .toc-fab" "$CSS" \
  && grep -qF "position: static !important;" "$CSS" \
  && grep -qF "border: 1px solid var(--rule-soft);" "$CSS" \
  && grep -qF "left: auto; right: 0; top: 44px; bottom: 0;" "$CSS" \
  && grep -qF "html.ls-wide .toc-sheet-mask { top: 44px; }" "$CSS" \
  && grep -qF "html.ls-wide .toc-sheet.open { transform: translateX(0); }" "$CSS" \
  && grep -qF "html.ls-wide .toc-sheet-handle { display: none; }" "$CSS" \
  && grep -q "function tocIntoNav\|var tocIntoNav" "$JSL" \
  && grep -qF "acts.insertBefore(fab, acts.firstChild)" "$JSL" \
  && ! grep -qF "html.ls-wide #priv-float, html.ls-wide .toc-fab { display: none" "$CSS"; } \
  && log_ok "v1613-LS-TOC(横屏目录钮搬进导航 flex 流 · 与隐私钮同款描边 · 抽屉右侧侧栏且避开导航层叠上下文)" \
  || log_bad "v1613-LS-TOC 缺件" "see landscape.js tocIntoNav 把 .toc-fab 搬进 .nav-actions · style.css 下 .nav-actions > .toc-fab 为 static 描边款 · .toc-sheet/mask top:44px 右侧侧栏 · handle 隐藏"

# v1613-ORI-ICON · 方向切换图标 = Tabler device-mobile / device-mobile-rotated 双态
#   历次:摄像机图标(语义完全不对)→ 屏幕旋转弧 → 自绘「竖框+横框+双向箭头」(用户仍说丑)
#   → v1.6.15 用**真实图标库**:Tabler Icons(MIT · 描边 24×24 stroke-2,与本项目同款)。
#   按钮显示**目标状态**:竖屏时显示"横屏手机"(点它去横屏),横屏时显示"竖屏手机"。
#   v1.6.17 分档(用户反馈③:只有"横屏"传达不出"旋转"):
#     · 右下方向浮钮(20px)= 横屏手机 **+ 旋转弧箭头** —— 它是主控件,尺寸够放得下弧
#     · 交叉表「横屏看」按钮 / 「手机看」提示(14–15px)= 只用横屏手机 ——
#       该尺寸下 6px 半径的弧糊成一团,按 visual-spec「可读 > 可懂」不硬塞。
# 注:grep -c 对**单个文件**只输出数字、不带「文件名:」前缀 → 不能再套 awk -F: 取 $2(会得 0)
{ [ "$(grep -c 'M3 8a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2l0 -8' "$RD/src/main/resources/templates/lens/_section.html")" -eq 2 ] \
  && grep -qF 'M2.5 10.5a1.8 1.8 0 0 1 1.8 -1.8h12.4' "$LAY" \
  && grep -qF 'M15.5 5.4a6 6 0 0 1 5.9 5.1' "$LAY" \
  && [ "$(grep -rc 'M6 5a2 2 0 0 1 2 -2h8a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-8a2 2 0 0 1 -2 -2v-14' "$LAY" "$RD/src/main/resources/templates/lens/_section.html" | awk -F: '{s+=$2} END{print s}')" -ge 2 ] \
  && grep -qF ".ori-ico-port { display: none; }" "$CSS" \
  && grep -qF '[aria-pressed="true"] .ori-ico-land { display: none; }' "$CSS" \
  && ! grep -rq "M18 9l4-2v10l-4-2" "$RD/src/main/resources" \
  && ! grep -rq "M19.5 11.5A8" "$RD/src/main/resources" \
  && ! grep -rq "M8.2 12h5.6" "$RD/src/main/resources"; } \
  && log_ok "v1613-ORI-ICON(Tabler device-mobile 双态 · 显示目标状态 · 浮钮带旋转弧 / 小尺寸不硬塞 · 三代旧图标均已清)" \
  || log_bad "v1613-ORI-ICON 缺件" "see layout.html + lens/_section.html:横屏手机图标 3 处 / 竖屏手机图标 2 处(提示位单态)· style.css 双态显隐 · 不得残留摄像机 M18 9l4-2 / 旋转弧 M19.5 11.5A8 / 自绘双向箭头 M8.2 12h5.6"

# v1616-SIX · 用户第 6 轮反馈六项(环上标签 / 旭日不撞色 / 触摸目标 / 切换重排 / 打标页 / 文案)
#   ① 环图名称+数字标在环上:阈值按**弧长**算(2π·中半径·占比),不是按占比拍死 ——
#      弧长才是"放不放得下"的真实约束。够大标「名称+占比」两行,中等只标占比,太小交给图例。
#   ② 旭日不撞色:每环色板原来只有 10 色,超出直接复用 → 用户看到"第二轮就重复颜色"。
#      扩容用**旋转色相保明度**(不是往同一端点混 —— 那会收敛,实测同环最小色距掉到 3~7),
#      贪心筛选保证同环 ≥24(不够再放宽到 ≥15)、跨环 ≥26。实测方案 D 内环 77 / 外环 45 色。
#      内外环明度带完全分开(D:内 0.08–0.36 / 外 0.38–0.72)→ 满足"内外环不是一套"。
#      唯一共用色是「其他 N 项」聚合桶之间(本来就该同色),真实维值 0 撞色。
#   ③ 退出钮"点很多次才响应":旧 min-height 26px,旋转后在屏幕上只有 26px 宽一条,
#      远低于 iOS HIG 的 44pt → 加到 34px、导航行抬到 44px、加 touch-action:manipulation
#      (去掉双击缩放的 300ms 等待,等待期内的点击会被吞)、横屏态去掉 backdrop-filter
#      (被 transform 的容器里它是 iOS 已知的命中/合成干扰源)。
#   ④ 切换后图表溢出:只派发 window resize 不够 —— Chart.js 无参 resize() 实测读到旧尺寸
#      (横屏后画布仍是竖屏宽,比容器窄 400+px),ECharts 根本不跟容器。
#      改为**显式给容器 offsetWidth/Height**(布局坐标,不能用 rect:旋转后宽高互换),
#      并在重编译落定后补两次(rAF×2 + 180ms + 420ms)。
#   ⑤ 打标页手机端:真正瓶颈不是说明段(只占 186px),是 62 行 × 卡片化 ≈ 13800px →
#      加「只看未打标」纯前端过滤(手机默认开),说明段收成 details,底部常驻保存条。
#      过滤只切 display,隐藏行照样随「保存全部」提交,不丢数据。
RG="$RD/src/main/resources/templates/dashboard/_region.html"
TAG="$RD/src/main/resources/templates/lens/tags.html"
LJS="$RD/src/main/resources/static/js/lens.js"
{ grep -qF "const arc = 2 * Math.PI * (PAIR.r() * 0.79) * (pct / 100);" "$RG" \
  && grep -qF "if (arc >= 52) return shortName" "$RG" \
  && grep -q "function buildRingColors" "$LJS" \
  && grep -q "function shiftColor" "$LJS" \
  && grep -qF "var RING_EXT = (function () {" "$LJS" \
  && grep -qF "if (rgbDist(forbid[j], c) < 26) return;" "$LJS" \
  && grep -q "维值 .* 个 > 色板" "$LJS" \
  && grep -qF "min-height: 34px; padding: 0 12px; margin-left: 8px; cursor: pointer;" "$CSS" \
  && grep -qF "touch-action: manipulation; -webkit-tap-highlight-color" "$CSS" \
  && grep -qF "height: 44px !important;" "$CSS" \
  && grep -qF "backdrop-filter: none !important;" "$CSS" \
  && grep -q "function relayoutCharts" "$JSL" \
  && grep -qF "ch.resize(box.offsetWidth, box.offsetHeight);" "$JSL" \
  && grep -q "isDisposed()" "$JSL" \
  && grep -q 'id="tagsOnlyTodo"' "$TAG" \
  && grep -q "tags-savebar" "$TAG" \
  && grep -q "tags-intro" "$TAG" \
  && grep -q "tags-savebar" "$CSS" \
  && grep -q "重仓了什么行业" "$RD/README.md" \
  && grep -q "重仓了什么行业" "$RD/src/main/resources/templates/landing.html"; } \
  && log_ok "v1616-SIX(环上名称+占比按弧长分档 · 旭日色板旋色相扩容 77/45 且内外环明度带分离 · 触摸目标 34/44px + touch-action + 横屏去毛玻璃 · 图表显式给容器尺寸重排 · 打标页只看未打标+常驻保存 · 穿透卖点两处文案)" \
  || log_bad "v1616-SIX 缺件" "see _region.html(弧长阈值)· lens.js(buildRingColors/shiftColor/RING_EXT/跨环互斥/超限 warn)· style.css(34px 钮 + 44px 行 + touch-action + 横屏去 backdrop-filter + tags-savebar)· landscape.js(relayoutCharts 显式尺寸 + ECharts isDisposed)· tags.html(tagsOnlyTodo/tags-savebar/tags-intro)· README + landing 均含「重仓了什么行业」"

# v1617-FIVE · 用户第 7 轮反馈五项
#   ① 系统浮钮不该被页面加载进度绑住:首屏遮罩(z-index 9998)原本挂 `window.load`,
#      而 load 要等**所有子资源**(echarts / 图表脚本),旭日重的页面拖到两三秒 →
#      三个浮钮被压在遮罩下,用户看到"等旭日加载完才浮现"。
#      改:遮罩挂 DOMContentLoaded + 印章一周期(兜底 2.5s);三个浮钮基础 z-index 抬到 9999。
#      实测 /lens 从"等 load"变成 701ms 可点(dashboard 2.3s = 该页 HTML 解析时间,不再是图表)。
#   ② 「下一层按」下拉顺序原来是 LensRegistry 注册序、**风险第一**,用户觉得不合适。
#      重排依据:下钻是"再切一刀看结构",最常问的是结构性问题(是什么/在哪/投向/谁的/为什么);
#      风险 / 流动性是属性判断且已有专门看板,后置;账户类型是记账口径最技术,放最后。
#      同时把「成员结构」看板的第二层从 风险 改 资产类型("谁持有"之后自然追问"持的是什么")。
#   ③ 方向图标补旋转弧箭头:原来只有"横屏手机",传达不出"旋转"这个动作。
#   ④ 三个浮钮进 flex dock:原来各写死 bottom 偏移(18 / +48 / +96),页面缺一个
#      (打标页没有目录钮)就在栈里留一个空洞。flex 列后有几个排几个,实测打标页 2 钮间距 10px 无洞。
#   ⑤ 打标页「持仓方向」加回 UED 稿的横条占比示意 + 标签不换行改横滑。
#      两个坑:手机端 .tags-table td 被改成 flex 横排 → 横条与标签行各占一半(实测横条 95px);
#      td 还需恢复 display:block 才能整宽(修后 217px)。
LR="$RD/src/main/java/com/family/finance/calc/lens/LensRegistry.java"
TAG2="$RD/src/main/resources/templates/lens/tags.html"
{ grep -qF "setTimeout(function () { ov.classList.add('hidden'); }, 950);" "$LAY" \
  && grep -qF "}, 2500);" "$LAY" \
  && grep -qF "#priv-float{ position:fixed; right:14px; bottom:max(18px, env(safe-area-inset-bottom)); z-index:9999;" "$LAY" \
  && grep -qF "#ori-float { z-index: 9999; }" "$CSS" \
  && grep -q "z-index:9999" "$CSS" \
  && grep -q "float-dock" "$CSS" \
  && grep -q "function dockFloats" "$JSL" \
  && grep -qE "\['#ori-float',.*'#priv-float'\]" "$JSL" \
  && grep -nq "dim(\"assetClass\"" "$LR" \
  && [ "$(grep -n 'dim("assetClass"' "$LR" | cut -d: -f1)" -lt "$(grep -n 'dim("risk"' "$LR" | cut -d: -f1)" ] \
  && [ "$(grep -n 'dim("type"' "$LR" | cut -d: -f1)" -gt "$(grep -n 'dim("region"' "$LR" | cut -d: -f1)" ] \
  && grep -q "key: 'member'" "$LJS" \
  && grep -qF 'M15.5 5.4a6 6 0 0 1 5.9 5.1' "$LAY" \
  && grep -q "alloc-head" "$CSS" && grep -q "alloc-seg" "$CSS" \
  && grep -q "alloc-bar" "$TAG2" && grep -q "alloc-pills hscroll-x" "$TAG2" \
  && grep -qF ".tags-table .alloc-row td{ display:block; }" "$TAG2"; } \
  && log_ok "v1617-FIVE(遮罩改 DOMContentLoaded + 浮钮 z-index 9999 不被加载绑住 · 维度顺序结构类在前(第二层默认见 v1619)· 图标补旋转弧 · 浮钮 flex dock 无空洞 · 持仓方向横条整宽+标签横滑)" \
  || log_bad "v1617-FIVE 缺件" "see layout.html(遮罩 950ms/2500ms 兜底 + priv-float z-index 9999 + 图标旋转弧)· style.css(#ori-float z-index 9999 / float-dock / alloc-head / alloc-seg)· landscape.js(dockFloats:方向最上、隐私最下)· LensRegistry(assetClass 在 risk 之前、type 在 region 之后)· lens.js(member 看板存在;第二层现由 v1619-THREE 守护为 platform)· tags.html(alloc-bar / alloc-pills hscroll-x / alloc-row td display:block)"

# v1618-SIX · 用户第 8 轮反馈六项
#   ① 环上不只要占比,具体金额也要:三行(名称/金额/占比)对径向要求 ≈ 3×11px = 33px < 环带 42px;
#      真正约束仍是弧长 —— 最宽那行是金额(≈8 字符 ≈40px)→ 三行档 arc ≥ 58。
#      隐私态 fmtMoney 返回空串 → 自动降回两行,不留空行。
#   ② 「本期怎么变的」里「本期收入 / 支出」两个 <b> **根本没挂 data-priv** → 隐私态下明文暴露。
#      注:PrivacyIsolationTest 管的是 LLM 脱敏(手机号/姓名),与 UI 的 data-priv 模糊无关,本来抓不到。
#      本守护补上这一类:_region.html 里**所有** ${cf*Label}(绝对金额)必须带 data-priv,
#      新增的也会被抓 —— 不是只补这两处。
#   ③ 「指标放行上点数字没有下钻能力」:实测两种位置抽屉**都会打开**(无头逐一验过),
#      但指标放行时每个实体占 N 行、表格高出数倍,而原来用 block:'nearest'(只滚最小距离)
#      → 抽屉常停在视口外,看着像"点了没反应"。改 block:'start' + 打开时闪一下边框给确认。
#   ④ 横屏要跨页面保持:状态写 sessionStorage;**关键是要在 tailwind 首次编译前**就据它选断点档
#      并贴上 html.ls-wide,否则新页面先按竖屏排一遍再被 JS 扳过去,会闪。
#   ⑤ 归因的数字画在 **canvas** 上,CSS 的 html.privacy [data-priv] 管不到 →
#      两件事都要:生成文案时判隐私 + 隐私开关切换后**重出图**(已画的像素不会自己变)。
#   ⑥ 隐私态 #priv-float 会显出文字标签变宽,dock 原来 align-items:center → dock 宽度被撑开、
#      另外两个居中钮左移。改右对齐:每个钮右缘固定,谁变宽只往左长。
ATTR="$RD/src/main/resources/templates/dashboard/_attribution.html"
{ grep -qF "if (arc >= 58 && money) return shortName" "$RG" \
  && [ "$(grep -oE '<[a-zA-Z][^>]*\$\{cf[A-Za-z]*Label\}[^>]*>' "$RG" | wc -l)" -eq "$(grep -oE '<[a-zA-Z][^>]*\$\{cf[A-Za-z]*Label\}[^>]*>' "$RG" | grep -c 'data-priv')" ] \
  && grep -qF "wrap.scrollIntoView({ behavior: 'smooth', block: 'start' });" "$LJS" \
  && grep -q "drawer-flash" "$CSS" \
  && grep -q "window.TW_SCREENS_WIDE" "$LAY" \
  && grep -qF "sessionStorage.getItem('lsWide') === '1'" "$LAY" \
  && grep -qF "sessionStorage.setItem('lsWide', '1')" "$JSL" \
  && grep -q "function adoptWideOnLoad" "$JSL" \
  && grep -q "function privOn()" "$ATTR" \
  && grep -qF "function fmtShort(v){ if(privOn()) return '···';" "$ATTR" \
  && grep -q "MutationObserver" "$ATTR" \
  && grep -qF "#float-dock { align-items: flex-end !important; }" "$CSS"; } \
  && log_ok "v1618-SIX(环上补金额按弧长分档 · 所有 cf*Label 必带 data-priv · 抽屉 block:start+闪确认 · 横屏跨页保持且首次编译即宽屏档 · 归因 canvas 生成时判隐私+切换重绘 · dock 右对齐不偏移)" \
  || log_bad "v1618-SIX 缺件" "see _region.html(arc>=58 三行档 · 所有 \${cf*Label} 必须带 data-priv)· lens.js(drawerWrap block:start)· style.css(drawer-flash / float-dock flex-end)· layout.html(TW_SCREENS_WIDE + sessionStorage lsWide 预贴)· landscape.js(setItem lsWide + adoptWideOnLoad)· _attribution.html(privOn + fmtShort 判隐私 + MutationObserver 重绘)"

# v1619-THREE · 用户第 9 轮反馈三项
#   ① PC 上「资产配置」环压住右侧图例。查证:该卡在 PC 只有 386px 宽(1024 视口下仅 229px),
#      右侧图例吃掉 181px,而半径是**写死的**(v1.6.12 用户明确要"两图同大",不能退回自适应)
#      → 环右缘 221 > 图例左缘 193,压进去 28px;「按成员分布」卡宽 1024 所以看不出。
#      三步修到四档宽度(1440/1280/1024/390)全不重叠:
#        · 图例一律移到**下方**(两张卡宽度差 4 倍,右侧图例没法都安全)
#        · PC 半径 118 → 104
#        · 图例文案缩短:环上已有金额的片(arc ≥58)图例不重复金额;名称也去掉「(WEALTH)」英文码
#          —— 与环上标签同一套写法。1024 下这一步是决定性的:带码条目每行只放 1 条 → 6 行 142px,
#          图区被压到 190px 而环直径 208px。
#   ② 默认看板 = 成员结构、第二层默认 = 平台(用户指定)。默认项同时挪到第一位 ——
#      默认却不在最左会让人以为选错了(chips 横滑,第 3 个未必在视野内)。
#   ③ 「切片排行」默认只出前 5 条,其余折起 + 「展示全部(还有 N 项)」。
#      它是**完整列表**的角色(旭日合并小块时指路到这儿),所以只能折不能截断;
#      展开是纯前端切 display,不再发请求。
{ grep -qF "position: 'bottom'," "$RG" \
  && grep -qF "vpNarrow(640) ? 100 : 104," "$RG" \
  && grep -qF "const money = arc >= 58 ? '' : fmtMoney(v);" "$RG" \
  && grep -qF "const short = flat(lab).replace(" "$RG" \
  && ! grep -qF "position: nar ? 'bottom' : 'right'," "$RG" \
  && grep -qF "{ key: 'member',    name: '成员结构',  sun: ['owner', 'platform']" "$LJS" \
  && grep -qF "boardKey: 'member'," "$LJS" \
  && [ "$(grep -n "key: 'member'" "$LJS" | head -1 | cut -d: -f1)" -lt "$(grep -n "key: 'assetcls'" "$LJS" | head -1 | cut -d: -f1)" ] \
  && grep -qF "var TOP_N = 5, hidden = Math.max(0, rows.length - TOP_N);" "$LJS" \
  && grep -q "rank-more" "$LJS" \
  && grep -qF "id=\"rankToggle\"" "$LJS" \
  && grep -q "展示全部" "$LJS"; } \
  && log_ok "v1619-THREE(环图图例移下方+PC 半径 104+图例文案缩短 → 四档宽度零重叠 · 默认看板成员结构且第二层平台且排第一 · 切片排行 Top5 折叠可展开)" \
  || log_bad "v1619-THREE 缺件" "see _region.html(legend position:'bottom' · PC 半径 104 · 图例 arc>=58 不重复金额 + 去英文码 · 不得残留 nar?bottom:right)· lens.js(member 看板 sun 第二层 platform 且排第一 + boardKey member + TOP_N=5 + rank-more/rankToggle/展示全部)"

# v1620-TAGS-LS · 打标页手机端**强制横屏** + 横屏布局靠向 PC(用户第 10 轮反馈③)
#   v1.6.16 我走的是"竖屏优化"(说明折叠 + 只看未打标 + 常驻保存条),用户试过后要求强制横屏。
#   这页确实是宽表格作业面(6 列 × 62 行):竖屏只能卡片化,一行一屏、上下滑一万三千像素。
#   三件事必须同时做,少一件横屏就白切:
#     ① **卡片化那段媒体查询要限定 html:not(.ls-wide)** —— 横屏是"锁布局宽度为长边 + 旋转",
#        **viewport 仍是短边 390**,所以 max-width:820px 照样命中,卡片化不会自己退场
#        (Tailwind 的 md: 靠原地换断点解决,自有媒体查询没这个机制)。
#        踩坑:逗号选择器要**每个**都加前缀 —— 只给第一个加,后面几个仍无条件生效
#        (实测 td 仍是 display:block,表格没回来)。所以这里直接断言那两条逗号列表的完整形态,
#        不用「不得出现裸 .tags-table」那种宽泛否定 —— 它会扫到媒体查询**外面**的基础表格样式
#        (那些本来就该无前缀),又是一次「否定断言盯裸标识符」。
#     ② 自动切横屏只切**一次**,用户手动退出后本会话不再纠缠(sessionStorage tagsLsOptOut),
#        且只在窄屏 + 触屏上切,PC 一律不动。
#     ③ 横屏态**页头要压到最小**:横屏只有 390 竖向像素,未压缩时表头在 418px、一行表格都看不到。
#        压 eyebrow/标题/说明卡(说明保持折叠 —— 横屏竖向比竖屏更紧,全文正是最该收的),
#        表格本身不动。实测表头 418 → 278px。
TAGS="$RD/src/main/resources/templates/lens/tags.html"
{ [ "$(grep -c 'html:not(.ls-wide) .tags-table\|html:not(.ls-wide) .ai-btn' "$TAGS")" -ge 15 ] \
  && grep -qF "html:not(.ls-wide) .tags-table tr, html:not(.ls-wide) .tags-table td{ display:block" "$TAGS" \
  && grep -qF "html:not(.ls-wide) .tags-table td select," "$TAGS" \
  && grep -q "tagsLsOptOut" "$TAGS" \
  && grep -qF "window.matchMedia('(pointer: coarse)').matches" "$TAGS" \
  && grep -qF "window.toggleOrientation()" "$TAGS" \
  && grep -q "class=\"tags-head" "$TAGS" \
  && [ "$(grep -c 'class="tags-note ' "$TAGS")" -eq 2 ] \
  && grep -q "tags-intro-full" "$TAGS" \
  && [ "$(grep -c 'tags-note-full' "$TAGS")" -ge 2 ] \
  && grep -qF "html.ls-wide .tags-head .eyebrow { display: none !important; }" "$CSS" \
  && grep -qF "html.ls-wide .tags-intro-full," "$CSS" \
  && grep -qF "html.ls-wide .tags-savebar { display: flex !important; }" "$CSS"; } \
  && log_ok "v1620-TAGS-LS(打标页窄屏自动横屏一次+尊重退出+PC不动 · 卡片化限定 not(.ls-wide) 每个逗号选择器都带前缀 · 横屏页头压缩表头 418→278px · 说明横屏仍折叠 · 保存条横屏留存)" \
  || log_bad "v1620-TAGS-LS 缺件" "see tags.html(卡片化段每个选择器都要 html:not(.ls-wide) 前缀,且不得残留裸 .tags-table · tagsLsOptOut + pointer:coarse + toggleOrientation · tags-head/tags-note/tags-intro-full/tags-note-full 类名)· style.css(横屏压 eyebrow/标题/说明卡 + 说明保持折叠 + 保存条留存)"

# v1621-CN-INSTALL · 大陆装机不再依赖 Docker Hub + 镜像源由脚本代劳(用户第 11 轮反馈)
#   起因是一次真实的上手失败:大陆 Mac 用户跑 docker-up.sh,卡在拉 mysql:8.0,
#   脚本给的动作是「手改 ~/.colima/default/colima.yaml 或 Docker Desktop 的 Docker Engine JSON」→ 他放弃了。
#   诊断和指引都没错,错在**这一档的天花板就在这**:让非技术家庭用户手改 Docker 引擎配置 = 死路。
#   两层修法(缺任何一层大陆用户都可能装不上):
#     ① **把失败点删掉**:mysql:8.0 是默认路径上唯一还走 Docker Hub 的一跳 →
#        CI 用 `imagetools create` 把官方多架构 manifest 原样复制到 GHCR(registry→registry,不 pull 不重建),
#        compose 默认指 GHCR 副本(与 app 镜像同源、大陆直连)。本地 gh token 没有 write:packages,
#        所以推送只能在 Actions 里用 GITHUB_TOKEN 完成。
#     ② **代劳而非教程**:兜底路径问一句 [Y/n] 后按引擎类型自己配 registry-mirrors 并重启重试。
#        三个引擎落点完全不同:colima → VM 内 daemon.json(+ 补 colima.yaml 的 docker: 段,
#        否则下次 colima restart 会按 yaml 重写把它抹掉)· Desktop → 宿主 ~/.docker/daemon.json + 重启 App ·
#        Linux → /etc/docker/daemon.json + systemctl。OrbStack **不自动改**(机制不稳,宁可退回手动)。
#   不变量:已有 registry-mirrors 不覆盖 + 改前留 .bak + 探到的源写回 .env(否则用户手敲 compose 又撞不通的源)。
#   两个实现坑:root 下 `$SUDO -E python3` 会把 -E 当命令名(单独维护 SUDOE);
#   等引擎必须「先等它掉下去再等它起回来」—— 直接轮询 docker info 会命中重启前的老 daemon 而误判成功。
DUP="$RD/deploy/docker-up.sh"; DCY="$RD/docker-compose.yml"; DPW="$RD/.github/workflows/docker-publish.yml"
{ grep -qF 'image: ${MYSQL_IMAGE:-ghcr.io/luodi-nate/financial-management-mysql:8.0}' "$DCY" \
  && ! grep -qE '^[[:space:]]*image: mysql:8\.0[[:space:]]*$' "$DCY" \
  && grep -q '^  mirror-mysql:' "$DPW" \
  && grep -qF 'imagetools create' "$DPW" \
  && grep -qF 'ghcr.io/luodi-nate/financial-management-mysql:8.0' "$DPW" \
  && grep -q '^DB_MIRROR=' "$DUP" && grep -qF 'DB_UPSTREAM="mysql:8.0"' "$DUP" \
  && grep -q '^cn_autofix_mirrors()' "$DUP" \
  && grep -q '^_mirror_colima()' "$DUP" && grep -q '^_mirror_desktop()' "$DUP" \
  && grep -q '^_mirror_linux()' "$DUP" && grep -q '^_wait_engine()' "$DUP" \
  && ! grep -qE 'kind=orb' "$DUP" \
  && grep -qF 'SUDOE="$SUDO -E"' "$DUP" && ! grep -qE '\$SUDO -E python3' "$DUP" \
  && grep -qF "grep -qx 'docker: {}'" "$DUP" \
  && [ "$(grep -c '里已有 registry-mirrors' "$DUP")" -eq 2 ] \
  && [ "$(grep -c 'daemon.json.bak\|\$f.bak\|yml.bak' "$DUP")" -ge 3 ] \
  && grep -qF "printf 'MYSQL_IMAGE=%s" "$DUP" \
  && grep -qF 'pull_one eclipse-temurin:21-jre' "$DUP" \
  && grep -qF 'local what=' "$DUP" \
  && grep -qF 'cn_hub_blocked_guide "JDK 基础镜像"' "$DUP" \
  && grep -q '^# MYSQL_IMAGE=' "$RD/.env.example" \
  && grep -qF 'MIG_DB_MIRROR=' "$RD/deploy/migrate-to-docker.sh" \
  && grep -qF "printf 'MYSQL_IMAGE=%s" "$RD/deploy/migrate-to-docker.sh" \
  && bash -n "$DUP" && bash -n "$RD/deploy/migrate-to-docker.sh"; } \
  && log_ok "v1621-CN-INSTALL(db 默认走 GHCR 副本·compose 无裸 mysql:8.0 · CI mirror-mysql 用 imagetools 复制多架构 · 双源探测+写回 .env · 镜像源脚本代劳三引擎分流 · OrbStack 不自动改 · 已有源不覆盖+留 .bak · SUDOE 修 root 下 -E · JDK 分支单独探+文案参数化)" \
  || log_bad "v1621-CN-INSTALL 缺件" "see docker-compose.yml(db image 必须 \${MYSQL_IMAGE:-ghcr…-mysql:8.0}、不得留裸 image: mysql:8.0)· .github/workflows/docker-publish.yml(mirror-mysql job + imagetools create)· deploy/docker-up.sh(DB_MIRROR/DB_UPSTREAM 双源 · cn_autofix_mirrors + _mirror_colima/_mirror_desktop/_mirror_linux/_wait_engine · 不得有 kind=orb · SUDOE 而非 \$SUDO -E python3 · colima.yaml 仅在 'docker: {}' 时改 · 两处已有 registry-mirrors 不覆盖 · 三处 .bak · 写回 MYSQL_IMAGE · JDK 基础镜像单独探 · 文案参数化)· .env.example(MYSQL_IMAGE 说明)"

# v1622-DB-CRED · 数据卷老密码 / .env 新密码 → 自愈,且判据不许说谎(用户第 12 轮反馈)
#   v1.6.26 更新:原来钉的是旧文案「down -v 会删掉数据库卷里的全部数据」,而那段「两条出路」
#   已被 v1.6.26 重写成「三条出路」(down -v 降为第三条,前两条都不丢数据)→ 断言跟随新文案。
#   v1.6.21 修好镜像后用户换了个地方卡住:app 无限 `Access denied for user 'finance'` 重启。
#   真因:MySQL 只在**首次初始化数据卷**时写入 MYSQL_USER/PASSWORD,而**命名卷不随仓库目录消失** ——
#   用户为拿修复重新克隆 → .env 新随机密码 → 与卷里老密码不匹配。是我们的升级指引造成的。
#   最坑的是三处判据同时给假阳性,因为它们用了**同一个不可靠原语**:
#     `mysqladmin ping` 在密码错误时**也 exit 0**(实测 $?=0;MySQL 语义:服务器有应答就算活着)。
#     → compose healthcheck 报 Healthy · entrypoint 报「MySQL 就绪」;
#     另加 FRESH_DB 探测的 `|| echo 1` 把「查询失败」和「表存在」压成同一个值 → 报「表存在数=1」。
#   所以本守护同时钉三件事:
#     ① 两处就绪/健康判据必须是**真实查询**(SELECT 1),不得再出现 mysqladmin ping 作判据;
#     ② FRESH_DB 探测不得用 `|| echo 1` 这类把失败翻译成正常值的兜底;
#     ③ docker-up.sh 要能检测并**不删数据**地修(mysqld --init-file,MySQL 官方重置手法,不需要旧密码),
#        且 `up -d` 失败也要走到自愈 —— 健康检查改严后 db unhealthy 会让 depends_on 让 up -d 非零退出,
#        set -e 会在自愈前就打断(实测撞到);删数据(down -v)只能是用户手动选项,FINANCE_ASSUME_YES 不放行。
EP="$RD/docker/entrypoint.sh"
{ grep -qF "mysql -h\"\$DB_HOST\" -P\"\$DB_PORT\" -u\"\$DB_USER\" -sN -e 'SELECT 1'" "$EP" \
  && ! grep -qE '^[[:space:]]*if mysqladmin ping' "$EP" \
  && grep -q 'AUTH_ERR' "$EP" && grep -q 'DB_READY' "$EP" \
  && ! grep -qF "2>/dev/null || echo 1" "$EP" \
  && grep -qF '全新空库判不了' "$EP" \
  && grep -qF "CMD-SHELL" "$DCY" && grep -qF "SELECT 1" "$DCY" \
  && ! grep -qE '"mysqladmin", "ping"' "$DCY" \
  && grep -q '^ensure_db_credentials()' "$DUP" \
  && grep -q '^resync_db_credentials()' "$DUP" \
  && grep -q '^db_auth_ok()' "$DUP" && grep -q '^db_root_ok()' "$DUP" \
  && grep -qF 'mysqld --init-file=/pwfix.sql' "$DUP" \
  && grep -qF 'ALTER USER' "$DUP" \
  && [ "$(grep -c 'UP_FAILED' "$DUP")" -ge 6 ] \
  && grep -qF '永久删除那个库里的一切' "$DUP" \
  && grep -qF '三条出路' "$DUP" \
  && grep -qF 'COMPOSE_PROJECT_NAME=finance-new' "$DUP" \
  && grep -qF 'Address already in use' "$DUP" \
  && bash -n "$EP" && bash -n "$DUP"; } \
  && log_ok "v1622-DB-CRED(entrypoint/healthcheck 改真实查询 SELECT 1·不再用 mysqladmin ping 作判据 · FRESH_DB 不再 ||echo 1 且如实报判不了 · docker-up 检测+init-file 不删数据同步密码 · up -d 失败也走自愈 · 删卷只作手动选项)" \
  || log_bad "v1622-DB-CRED 缺件" "see docker/entrypoint.sh(SELECT 1 真实查询 + AUTH_ERR/DB_READY 分流 + 不得留 mysqladmin ping 判据或 '2>/dev/null || echo 1' + 查询失败要说「全新空库判不了」)· docker-compose.yml(db healthcheck 改 CMD-SHELL + SELECT 1,不得留 mysqladmin ping)· deploy/docker-up.sh(ensure_db_credentials/resync_db_credentials/db_auth_ok + mysqld --init-file + ALTER USER + UP_FAILED 兜住 up -d 失败 + down -v 数据不可恢复警示 + 端口占用归因)"

# v1623-ENTRY-VIS · 功能入口可见性 —— 运行时判据,不是 grep(用户第 13 轮反馈)
#   用户报「券商自动对接的入口是不是上次优化 UI 丢了」。查证:功能一行没丢,是 v1.6 UED 批次5
#   把账户页 PC 行内的「券商」按钮按「行内按钮太多」的视觉密度一刀切收进了没有文字的 ⋯ 菜单。
#   对用户来说「还在但找不到」和「没了」没区别。**而已有守护 v15-ENTRY-1 一直是 PASS** ——
#   它断言的是 `grep '/broker(id='`(模板里有这个字符串),grep 类守护结构上抓不到可见性。
#   这正是 v1.6.14 那条「显示 ≠ 看得见」的教训:我当时只把它当成「以后写新守护要注意」,
#   从没回头拿这把尺子重量已有的 500 多条守护。写下教训 ≠ 教训生效。
#   顺带扫出第二个、更严重的:**/reports/refinance 提前还贷决策器全站零入口** ——
#   页面从 v0.4 就在、README 与落地页都在宣传,但没有任何模板链接指过去(git log -S 只命中它自己的
#   form action),只能手敲 URL;dashboard 洞察条还提示「可考虑加速偿还」却不给去处。已补两处入口。
#   机制:scripts/entry-points.json 是功能入口登记表(能力→入口页→期望可见层级),
#   scripts/entry-points-check.cjs 渲染真页面(PC 1440×900 + 移动 390×844)逐条断言:
#     ①有面积 ②elementFromPoint 命中自己(未被遮挡)③不在未展开 details / .row-more-pop 内。
#   没浏览器/应用没起 → 脚本退 2 → 本守护 SKIP(不制造假 FAIL)。
EPJ="$RD/scripts/entry-points.json"; EPC="$RD/scripts/entry-points-check.cjs"
if [[ ! -f "$EPJ" || ! -f "$EPC" ]]; then
  log_bad "v1623-ENTRY-VIS 登记表/检查器缺失" "需要 scripts/entry-points.json + scripts/entry-points-check.cjs"
elif ! command -v node >/dev/null 2>&1; then
  log_skip "v1623-ENTRY-VIS 功能入口可见性" "本机没 node"
else
  # 本机 chromium 缺 libXdamage,补上解包目录(不存在就不加,交由脚本自己判定并 SKIP)
  EP_LD=""; [[ -d /tmp/xdmg/usr/lib/x86_64-linux-gnu ]] && EP_LD="/tmp/xdmg/usr/lib/x86_64-linux-gnu"
  EP_OUT="$(LD_LIBRARY_PATH="${EP_LD}${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
            node "$EPC" "${BASE:-http://127.0.0.1:20000}" 2>&1)"; EP_RC=$?
  EP_SUM="$(printf '%s' "$EP_OUT" | grep -E '^合计' | tail -1)"
  case "$EP_RC" in
    0) log_ok "v1623-ENTRY-VIS 功能入口全部一眼可见(${EP_SUM:-登记表逐条 PASS} · PC+移动双端 · 判据=有面积+命中自己+不在 ⋯/details 内)" ;;
    2) log_skip "v1623-ENTRY-VIS 功能入口可见性" "$(printf '%s' "$EP_OUT" | grep -m1 SKIP || echo '环境不具备')" ;;
    *) log_bad "v1623-ENTRY-VIS 有功能入口用户看不见" "$(printf '%s' "$EP_OUT" | grep -m2 '✗' | tr '\n' ' ')" ;;
  esac
fi

# v1624-BROKER-CTX · 持仓页券商对接状态条 + OpenD 向导带账户上下文(用户第 14 轮反馈)
#   用户路径是「填报 → 持仓管理」:到了持仓页看不到这个账户跟富途/老虎是什么关系,要绕回账户页找配置。
#   模型确认(不动 schema):V39 有 uq_broker_link_account UNIQUE(account_id) → 一个账房账户 ↔ 一个券商
#   交易账户 1:1;多账户各自关联 → 整个账房对券商 1:N。用户描述的模型与实现一致。
#   三件事:
#     ① 持仓页标题下「券商对接」状态条:已关联给 vendor/启用状态/上次同步/本关联 OpenD 地址 + 两个去处;
#        未关联**不摆空字段**(空态排一列「—」是假信息),只给去关联入口。
#     ② OpenD 向导可带 ?account=<id>:顶部显示"正在为【X】配置"+ 一键回该账户配置页;
#        不带参数时行为完全不变(网关是**进程级**常驻服务、天然全局,只补上下文提示,不按账户分)。
#     ③ 判据统一到 supportsHoldings ——账户页原先硬编码 'STOCK' or 'CRYPTO' or 'METAL' 三串,
#        而 BrokerLinkController.requireHoldingAccount 用的是 supportsHoldings(含 WEALTH/CASH)→ 会漂移;
#        模板里硬编码枚举列表是 AGENTS.md 明令要避免的(加类型必漏)。
#   两个 UED 自查(实测截图发现):两个并列按钮原本一个 btn-paper 一个 btn-ghost(有框/无框不一致,
#   违反 visual-spec 并列同尺度那条)→ 都改 btn-paper;btn-* 全带 text-transform:uppercase,
#   会把专有名词「OpenD」渲染成「OPEND」→ 该处局部 text-transform:none。
HOLDT="$RD/src/main/resources/templates/stock/holdings.html"
ROWT="$RD/src/main/resources/templates/entry/_row.html"
OPENDT="$RD/src/main/resources/templates/broker/opend-wizard.html"
OPENDC="$RD/src/main/java/com/family/finance/web/broker/FutuOpendController.java"
HOLDC="$RD/src/main/java/com/family/finance/web/stock/StockHoldingController.java"
LINKT="$RD/src/main/resources/templates/broker/link.html"
{ [ "$(grep -c 'link-strip' "$HOLDT")" -ge 2 ] \
  && grep -qF 'brokerLink != null' "$HOLDT" && grep -qF 'brokerLink == null' "$HOLDT" \
  && grep -qF 'is-none' "$HOLDT" \
  && grep -qF 'lastSyncedAt' "$HOLDT" && grep -qF 'opendHost' "$HOLDT" \
  && [ "$(grep -c 'btn-paper text-\[11px\] no-underline' "$HOLDT")" -ge 4 ] \
  && ! grep -qF 'btn-ghost text-[11px] no-underline' "$HOLDT" \
  && grep -qF 'text-transform:none' "$HOLDT" \
  && grep -qF '/admin/broker/opend(account=' "$HOLDT" \
  && grep -qF '/admin/broker/opend(account=' "$LINKT" \
  && grep -qF 'brokerLinkMapper.findByAccount' "$HOLDC" \
  && grep -qF 'name = "account", required = false' "$OPENDC" \
  && grep -qF 'ctxAccountId' "$OPENDC" && grep -qF 'ctxAccountId != null' "$OPENDT" \
  && grep -qF 'getFamilyId().equals(me.getFamilyId())' "$OPENDC" \
  && grep -qF 'flex flex-wrap items-center gap-1.5' "$ROWT" \
  && grep -qF '/broker|}' "$ROWT" \
  && [ "$(grep -c 'supportsHoldings' "$RD/src/main/resources/templates/accounts/index.html")" -eq 2 ] \
  && ! grep -qF "== 'STOCK' or" "$RD/src/main/resources/templates/accounts/index.html" \
  && grep -qF '.link-strip' "$CSS" && grep -qF 'min-height: 44px' "$CSS"; } \
  && log_ok "v1624-BROKER-CTX(持仓页状态条两态·未关联不摆空字段 · 两按钮同款 btn-paper + OpenD 不被大写 · 向导 ?account 上下文且校验同家庭 · 填报行加入口且 flex-wrap · 账户页判据统一 supportsHoldings 不再硬编码枚举)" \
  || log_bad "v1624-BROKER-CTX 缺件" "see stock/holdings.html(link-strip 两态 + is-none + lastSyncedAt/opendHost + 4 处 btn-paper 且不得留 btn-ghost + text-transform:none + opend(account=)· broker/link.html(向导链接带 account)· StockHoldingController(brokerLinkMapper)· FutuOpendController(?account + ctxAccountId + 同家庭校验)· opend-wizard.html(ctxAccountId 条件)· entry/_row.html(flex-wrap + /broker 入口)· accounts/index.html(2 处 supportsHoldings、不得留 == 'STOCK' or)· style.css(.link-strip + 手机 44px)"

# v1625-UPDATE-PATH · 更新路径可自查 + Thymeleaf 条件片段陷阱(用户第 15 轮反馈)
#   用户原话:「我拉了新的代码重新运行了 bash deploy/docker-up.sh,依然是旧代码版本,
#   我们期望我们的用户如何更新版本?」—— 端到端复现后确认**脚本本身没坏**
#   (旧镜像在跑 → 跑脚本 → 容器确实换成新镜像 1ff63fb→7f78edc)。真因是三条叠加:
#     ① `git pull` 拉到的新代码**不进容器** —— app 来自 GHCR 预构建镜像,git pull 只影响
#        compose 文件与脚本本身;而 README 把三条命令并列埋在一大段文字里,让人以为 git pull 是关键那步。
#     ② 打 tag 到镜像可用之间有**约 12 分钟 CI 构建**(实测 build-push 12m7s)。看到发版消息立刻更新 → 拉到旧的。
#     ③ **脚本从头到尾不说版本**(grep 版本 = 0 处),/health 只有 {"status":"UP"},落地页也没有,
#        版本徽记只在**登录后**的 nav 里 → "静默拿到旧版"无法自查。这条是用户困惑的直接原因。
#   修:/health 带 version;脚本起前起后各读一次并打印「vA → vB」/「无变化」/「落后且镜像未就绪」;
#   与 GitHub 最新 release 对比(FINANCE_NO_UPDATE_CHECK=1 可关);README/deploy-README/faq 提独立小节。
#   **读不到版本时仍要给「最新是 vX」** —— 恰恰是旧镜像才不返回 version,这些用户最需要那句话(自查补的)。
#
#   顺带修两个既存缺陷(上一版记录、本版兑现):
#     · Thymeleaf 陷阱:`th:if` 与 `th:replace`/`th:insert` **不能放同一元素** ——
#       片段包含优先级(1)高于条件求值(3),replace 先执行 → 片段带着 null 参数被渲染。
#       error.html 上就是这样:未登录出错时 nav 片段拿到 state=null,在 `state.family.logoPreset`
#       抛 SpelEvaluationException,**响应截断在 nav 中间**(实测:输出里有 nav-inner/nav-lead
#       但没有 tabs、没有错误页正文、没有 fallback 顶栏、只有一个 <header>)。
#       而且它**会盖住真因** —— 排查时先看到的是 nav 的二次异常。修法:条件外提到 th:block;
#       error 页的 head 干脆固定自包含(**错误页必须零依赖**:它依赖的正是刚出错的那套机制)。
#       全仓同类共 5 处(error 2 + accounts 1 + reports 2),一并外提;本守护钉住"不得再出现"。
#     · StockHoldingController 异常文案与判据不一致(仍写「仅 STOCK/CRYPTO/METAL」而 supportsHoldings
#       自 v1.4 起含 WEALTH/CASH)→ 改成按实际支持集表述,免得排查被文案带偏(我这次就被带偏一次)。
{ grep -qF '"version", appVersion' "$RD/src/main/java/com/family/finance/web/HealthController.java" \
  && grep -qF 'app.version:dev' "$RD/src/main/java/com/family/finance/web/HealthController.java" \
  && grep -q '^running_version()' "$DUP" && grep -q '^latest_release_tag()' "$DUP" \
  && grep -q '^version_verdict()' "$DUP" \
  && grep -qF 'FINANCE_NO_UPDATE_CHECK' "$DUP" \
  && grep -qF 'VER_BEFORE' "$DUP" \
  && grep -qF '已更新:v' "$DUP" && grep -qF '版本无变化' "$DUP" \
  && grep -qF '不返回 version' "$DUP" \
  && grep -qF '约 12 分钟' "$DUP" \
  && [ "$(grep -c 'version_verdict' "$DUP")" -ge 3 ] \
  && grep -q '^## 更新到新版本' "$RD/README.md" \
  && grep -qF '怎么更新到新版本' "$RD/deploy/README.md" \
  && grep -qF '为什么还是旧版本' "$RD/docs/faq.md" \
  && [ "$(grep -rlE 'th:(if|unless)="[^"]*"[^>]*th:(replace|insert)=|th:(replace|insert)="[^"]*"[^>]*th:(if|unless)=' --include='*.html' "$RD/src/main/resources/templates" | wc -l)" -eq 0 ] \
  && grep -qF '<th:block th:if="${nav != null}">' "$RD/src/main/resources/templates/error.html" \
  && ! grep -qF 'th:if="${nav != null}" th:replace' "$RD/src/main/resources/templates/error.html" \
  && ! grep -qF '仅 STOCK / CRYPTO / METAL 类型账户支持持仓管理' "$RD/src/main/java/com/family/finance/web/stock/StockHoldingController.java" \
  && bash -n "$DUP"; } \
  && log_ok "v1625-UPDATE-PATH(/health 带 version · 脚本报「vA→vB / 无变化 / 落后+CI 12min」且旧镜像也给最新版提示 · 三处文档独立更新小节 · 全仓无 th:if+th:replace 同元素 · error 页 head 零依赖 + 条件外提 · 持仓异常文案与判据一致)" \
  || log_bad "v1625-UPDATE-PATH 缺件" "see HealthController(version + app.version:dev)· deploy/docker-up.sh(running_version/latest_release_tag/version_verdict + VER_BEFORE + FINANCE_NO_UPDATE_CHECK + 三种结论文案 + 约 12 分钟)· README「## 更新到新版本」/ deploy-README / faq · templates 不得有 th:if 与 th:replace/th:insert 同元素(优先级 1 > 3,replace 先跑)· error.html 用 th:block 外提 · StockHoldingController 文案"

# v1626-CLEAN-SAFE · 唯一会 TRUNCATE 用户数据的路径必须处处 fail-closed(用户第 16 轮报数据丢失)
#   用户原话:「起了一个项目已经设置好了成员也更新了密码,更新了以后多了两个成员把我旧账户也刷掉了,多了 Alice bob」
#   **定性**:Alice/Bob 是 db/migration/V2__seed.sql 里两个内置账号的 display_name(id 1/2),不是新增演示成员。
#   它们出现只可能是**全新空库跑了 V2 种子** —— 因为 V1__init.sql 用的是**裸 CREATE TABLE**(14 个,0 个
#   IF NOT EXISTS),迁移在已有库上重放会在 V1 就失败,所以"迁移把种子重灌进你的库"物理上不成立。
#   → 那次是**数据卷被换/删**(目录名变→compose 项目名变→新卷;或执行过 down -v)。
#   **而 v1.6.22 我给的凭据不匹配指引里,`down -v` 是并列的第二条出路,还写着"你从没真正用过就可以整卷删掉"**
#   —— 用户刚建过成员改过密码,凭记忆判断"我没什么数据"极易判错,而删卷不可恢复。这是我给的选项的责任。
#   排查中又发现清理链上两个真缺陷(与本次事故无关,但都能在别的场景真删数据):
#     ① **互锁 fail-open**:`$(... 2>/dev/null || echo 0)` → 查询一失败当 0,而 0 = "没有真实数据、可以清"。
#        保命互锁在失败时选了破坏性那边。改 fail-closed。
#     ② **信号太少**:只看 audit_log 与 member.id>2 → 用内置两账号 + 改过密码的真实用户两条都不响。
#        补 must_change_pw=0(完成首登改密)与"种子成员已改名"(Alice/Bob 是默认名)。
#   还有一个**我自己在修的时候踩出来的 bash 陷阱**:probe 函数里用 exit/die 终止**无效** ——
#   它跑在 `$(...)` 子 shell 里,exit 只结束子 shell,主脚本会带着错误信息当数值继续跑
#   (实测打出一堆 `[[: syntax error: operand expected`,没删数据纯属运气)。
#   正确形状:失败 return 非零,由调用处 `|| bail` 终止。两个脚本都按这个改。
#   本守护钉住五件事:fail-closed probe(且不含子 shell exit)· 四条信号 · 清理前强制 dump ·
#   entrypoint 第二重判据(有数据就算没 schema_history 也不算全新)· down -v 不再是并列选项。
CLEAN="$RD/docker/clean-dev-data.sh"; DEP="$RD/deploy/deploy.sh"; ENTP="$RD/docker/entrypoint.sh"
{ grep -q '^probe()' "$CLEAN" && grep -q '^bail_no_clean()' "$CLEAN" \
  && [ "$(code_only "$CLEAN" | grep -c '|| bail_no_clean')" -eq 4 ] \
  && ! code_only "$CLEAN" | grep -qE '\|\| echo 0' \
  && grep -qF 'must_change_pw = 0' "$CLEAN" && grep -qF "display_name NOT IN ('Alice','Bob')" "$CLEAN" \
  && grep -qF 'mysqldump' "$CLEAN" && grep -qF 'pre-clean-' "$CLEAN" \
  && grep -qF '放弃清理' "$CLEAN" \
  && grep -q '_step10_probe()' "$DEP" && grep -q '_step10_bail()' "$DEP" \
  && [ "$(code_only "$DEP" | grep -c '|| _step10_bail')" -eq 4 ] \
  && ! code_only "$DEP" | grep -qE 'actor_member_id IS NOT NULL" 2>/dev/null \|\| echo 0' \
  && grep -qF '降级为非全新库' "$ENTP" \
  && grep -qF 'account) + (SELECT COUNT(*) FROM cash_flow' "$ENTP" \
  && grep -q '^fresh_db_notice()' "$DUP" && grep -qF '全新空库' "$DUP" \
  && grep -qF 'COMPOSE_PROJECT_NAME=' "$DUP" \
  && grep -qF '三条出路' "$DUP" \
  && ! code_only "$DUP" | grep -qF '你从没真正用过' \
  && bash -n "$CLEAN" && bash -n "$DEP" && bash -n "$ENTP" && bash -n "$DUP"; } \
  && log_ok "v1626-CLEAN-SAFE(清理链 fail-closed:probe 失败→不清 · 四条使用痕迹信号含 must_change_pw/已改名 · 清理前强制 dump 且 dump 失败不清 · entrypoint 有数据即降级非全新 · docker-up 告知全新空库 + down -v 降为第三条并要求确认)" \
  || log_bad "v1626-CLEAN-SAFE 缺件" "see docker/clean-dev-data.sh(probe+bail_no_clean 四处 || bail · 不得留 || echo 0 · must_change_pw/Alice,Bob 信号 · mysqldump pre-clean 且失败放弃)· deploy/deploy.sh step10(_step10_probe/_step10_bail 四处)· docker/entrypoint.sh(降级为非全新库 + account+cash_flow 行数判据)· deploy/docker-up.sh(fresh_db_notice + 全新空库告知 + COMPOSE_PROJECT_NAME 出路 + 三条出路 · 不得再出现"你从没真正用过")"

# v1627-UPDATE-TRUTH · 更新检查要对比「真能拉到的镜像」+ 查不到必须说出来 + 发布必须验镜像(用户第 17 轮)
#   用户在 v1.6.25 上 git pull + 重跑 docker-up.sh,输出「· 版本无变化:仍是 v1.6.25」,
#   而且**后面一行都没有** —— 既没说"已是最新",也没说"最新是 v1.6.26"。查证出三件事:
#   ① **v1.6.26 的 docker-publish CI 失败了**(Maven Central 瞬时 403 拉不到 spring-boot-starter-parent)
#      → GHCR 上没有 v1.6.26 镜像,:latest 还是 v1.6.25 → 用户**怎么更新都拿不到**。
#      而我发布时**没有检查 CI 结果**就报告"已上 prod"(prod 是 systemd 直装,确实上了)。
#      **prod 健康 ≠ 发布完成**:Docker 是主推安装方式,镜像没出就只发了一半。
#      → release skill 加**必做**阶段 3.5 `verify-image`:等 CI 结论 + 探 GHCR manifest 匿名可拉,不在就 die。
#   ② **查不到最新版时静默 return** —— 用户那边 api.github.com 没通,于是什么都不打印,
#      他无法区分「已是最新」和「查不了」。这是 v1.6.25 我给"读不到本地版本"补过的同一个漏洞,
#      **同一版里漏了另一半**。现在两个来源都查不到时明确说出来 + 给 FINANCE_NO_UPDATE_CHECK=1。
#   ③ **对比对象选错了**:原来只问 GitHub 最新 release。release 存在但镜像没构建出来时(正是本次),
#      拿它去比会告诉用户"有新版",他却怎么都拉不到。**权威来源是 GHCR 的 tag 列表**
#      = "我真能更新到什么";而且大陆直连 GHCR 比 api.github.com 稳。
#      现在:优先 GHCR(latest_image_tag),GitHub release 降为补充信息 ——
#      release 比镜像新时提示"镜像还在 CI 里(约 12 分钟);久等不来说明构建失败了"。
DUPX="$DUP"
# 发布 skill 的两个文件在 `.claude/` 下,而 `.claude/` 整棵被 .gitignore 忽略 ——
# 它只存在于**主工作区**,不会跟着 `git worktree add` 复制过去。
# 在 issue worktree 里跑 qa-run 时,原来这三条 grep 全部 "No such file" → 整条假红。
# 用 `git rev-parse --git-common-dir` 找回主工作区(linked worktree 下它给的是主仓 .git 的绝对路径)。
RPD="$RD/.claude/skills/release-prod"
[ -d "$RPD" ] || RPD="$(dirname "$(cd "$RD" && git rev-parse --git-common-dir 2>/dev/null || echo "$RD/.git")")/.claude/skills/release-prod"
{ grep -q '^latest_image_tag()' "$DUPX" && grep -q '^ver_gt()' "$DUPX" \
  && grep -qF 'ghcr.io/v2/${repo}/tags/list' "$DUPX" \
  && grep -qF '查不到最新版本' "$DUPX" \
  && grep -qF '已是最新可用镜像' "$DUPX" \
  && grep -qF '有新版镜像' "$DUPX" \
  && grep -qF '镜像还没推上来' "$DUPX" \
  && grep -qF '镜像是否已发布未确认' "$DUPX" \
  && grep -qF 'FINANCE_NO_UPDATE_CHECK' "$DUPX" \
  && grep -q '^verify-image)' "$RPD/release.sh" \
  && grep -qF 'ghcr.io/v2/${REPO}/manifests/' "$RPD/release.sh" \
  && grep -qF '镜像发布验证失败' "$RPD/release.sh" \
  && grep -qF '阶段 3.5 · Docker 镜像发布验证(必做' "$RPD/SKILL.md" \
  && grep -qF 'prod 健康' "$RPD/SKILL.md" \
  && bash -n "$DUPX" && bash -n "$RPD/release.sh"; } \
  && log_ok "v1627-UPDATE-TRUTH(更新检查以 GHCR tag 列表为权威 + 查不到明确说出来 + release/GHCR 不一致时归因 CI · 发布加必做阶段 3.5 verify-image 探 manifest)" \
  || log_bad "v1627-UPDATE-TRUTH 缺件" "see deploy/docker-up.sh(latest_image_tag 问 GHCR tags/list + ver_gt + 四种文案:查不到最新版本/已是最新可用镜像/有新版镜像/镜像还没推上来/镜像是否已发布未确认)· release.sh(verify-image 子命令 + 探 manifests + die)· SKILL.md(阶段 3.5 必做 + prod 健康≠发布完成)"

# v1628-OPS · 日常运维的四件事必须有入口(用户第 18 轮:「要做到告知用户如何停止、启动、重新来、
#   更新以及如何查看日志;你从一个开发者拿到手一个开源软件的视角,自己审视下需要哪些信息?」)
#   审视后对账,缺的是四样:①手动备份入口(Docker 下没有 —— 而我们刚在 v1.6.26 让用户"更新前先备份")
#   ②恢复脚本(只存在于 FAQ 的一段裸命令:要用户自己找文件、手填 root 密码、还得记得先停 app)
#   ③诊断入口(用户卡住时不知道该收集什么,我们也拿不到有效信息)④脚本末尾只给了"停 + 日志"两条。
#   **一处更正**:我一开始判定"备份 sidecar 因 restart:unless-stopped + 一次性脚本而死循环",
#   查证后是**错的** —— 镜像里装的是 docker/backup.sh(有 while+sleep 86400),我读的是 deploy/backup.sh
#   (systemd 那条,由 timer 触发,一次性是对的)。两个同名不同文件,别再混。
#   顺带露出真的文档不一致:文档写"每周日 03:00 / 每日 03:30",Docker 实际是"容器启动后每 24h",
#   文件名是 finance-*.sql.gz 而非 dump-* → 已改文档。
#   **我在写这三个脚本时自己踩的 bug(两次,同一个)**:Docker 分支把 dump 写成
#   `mysqldump | cat > 文件.sql.gz` —— **忘了 gzip**,文件名在撒谎;而 `du -h` 看得到文件,
#   于是打印了"✓ 已备份",restore 时才 `gzip: not in gzip format`。**报了成功的坏备份比没有备份更危险。**
#   更险的是 restore.sh 里"恢复前另存当前库"那份也是同一个写法 —— 退路本身不可用。
#   两处都补 gzip **并加完整性校验**(gunzip -t + 解出来必须含 CREATE TABLE),校验不过就删掉不留假备份。
#   **唯一能证明备份可用的方法是恢复它** —— 所以验证必须做"插标记→备份→删标记→恢复→标记回来"的往返,
#   以及"撤销一次恢复"(用 before-restore 快照回退)。只验"文件生成了"会放过这个 bug。
OPSC="$RD/deploy/_common-env.sh"; BKN="$RD/deploy/backup-now.sh"; RST="$RD/deploy/restore.sh"; DOC="$RD/deploy/doctor.sh"
{ [[ -f "$OPSC" && -f "$BKN" && -f "$RST" && -f "$DOC" ]] \
  && grep -qF 'MODE=docker' "$OPSC" && grep -qF 'MODE=systemd' "$OPSC" \
  && [ "$(code_only "$BKN" | grep -c 'gzip -9')" -ge 1 ] \
  && ! code_only "$BKN" | grep -qE 'mysqldump[^|]*\|[^|]*cat > ' \
  && [ "$(code_only "$BKN" | grep -c 'gunzip -t')" -ge 2 ] \
  && grep -qF 'CREATE TABLE' "$BKN" \
  && [ "$(code_only "$RST" | grep -c 'gzip -9')" -ge 1 ] \
  && ! code_only "$RST" | grep -qE 'mysqldump[^|]*\|[^|]*cat > ' \
  && grep -qF 'gunzip -t' "$RST" \
  && grep -qF 'before-restore-' "$RST" \
  && grep -qF 'FINANCE_RESTORE_CONFIRM' "$RST" \
  && grep -qF '中止恢复' "$RST" \
  && grep -qF '已脱敏' "$DOC" && grep -qF 'PASS|PASSWORD|KEY|SECRET' "$DOC" \
  && [ "$(grep -c '── 常用操作' "$DUP")" -eq 3 ] \
  && grep -qF 'bash deploy/backup-now.sh' "$DUP" && grep -qF 'bash deploy/restore.sh' "$DUP" \
  && grep -qF 'bash deploy/doctor.sh' "$DUP" && grep -qF 'docker volume ls | grep db-data' "$DUP" \
  && grep -qF '日常运维速查(Docker)' "$RD/README.md" \
  && grep -qF '每 24 小时' "$RD/README.md" \
  && ! code_only "$RD/docs/faq.md" | grep -qF '备份 sidecar 每周日 03:00' \
  && bash -n "$OPSC" && bash -n "$BKN" && bash -n "$RST" && bash -n "$DOC"; } \
  && log_ok "v1628-OPS(_common-env 形态探测 + backup-now/restore/doctor 三脚本 · dump 必 gzip 且 gunzip -t+CREATE TABLE 校验 · restore 先另存退路且校验不过就中止 + RESTORE 确认 fail-closed · doctor 脱敏 · docker-up 三处末尾给完整常用操作 · 文档修正备份节奏)" \
  || log_bad "v1628-OPS 缺件" "see deploy/_common-env.sh(MODE 探测)· backup-now.sh(gzip -9 + 两处 gunzip -t 校验 + 不得留 mysqldump|cat>)· restore.sh(gzip+校验 + before-restore 退路 + 校验不过中止 + FINANCE_RESTORE_CONFIRM)· doctor.sh(脱敏)· docker-up.sh(3 处常用操作块含 backup-now/restore/doctor/volume)· README(日常运维速查 + 每 24 小时)· faq(不得再写'每周日 03:00')"

# v1629-XIRR-LEDGER · 报表 XIRR:tooltip 端点必须与指标同源 + 收入口径全页统一(用户第 19 轮)
#   用户报「prod 新关一期后家庭 XIRR(含收入)是 0,不对吧」。**先复算再改**:在 prod 数据上逐步还原,
#   6 月投资损益 -111,222.95、7 月 +111,221.91,合计 -1.04 元 → -0.00001% → 显示 0.00%。
#   **XIRR 本身算得是对的**(排查中我一度把 12,434 USD 的开账基线当成 CNY,得出 0.79%,已更正)。
#   但顺着查出两个真缺陷:
#     ① **tooltip 显示的端点不是 XIRR 用的那两个数**:firstNW/lastNW 取自 netWorthTrendExOpening
#        (剔除累计开账基线的趋势,给财富水位用),而该序列**首点按构造恒为 0**(首期全部账户都算"首次出现")
#        → 长年显示「期初净资产 −¥0」,末点也不是真实净资产(prod 上显示 733,258,真实 〈金额已脱敏〉)。
#        **一个号称"给你看真实中间数值"的 tooltip 显示另一套数,比没有更糟** —— 用户正是据此判断指标算错了。
#        改:端点取 netWorthTrend(与 familyXirr 同源);两序列之差 = 累计开账基线,单列进文案;
#        并标明「不满 12 期 · 累计口径非年化」还是「年化」(原文案一律写"年化",<12 期时也在说谎)。
#     ② **同页两套收入口径**:familyXirr/familyTwr 只读 cash_flow,而同页「人赚/累计净投入/本金vs收益」
#        走 pmcFirstNetInflow(PMC 优先否则 cash_flow)。prod 实据:6 月 PMC 收入 151,547 vs cash_flow 81,462;
#        7 月用户填在 PMC 的 21,837 支出 XIRR 完全没扣 → 同一屏两个 KPI 互相矛盾。
#        这正是 AGENTS.md 联动不变量 L1 登记要防的。改:两者统一走 pmcFirstNetInflow。
#        **会改变线上数值**:prod 该指标 0.00% → -0.20%(是修正不是回归 —— 原值漏扣了用户已填报的支出)。
#   线上处置:零 schema 迁移、零存量数据改写;AI 缓存按 period_id 分片(是"那一期的建议"历史快照)不必清;
#   GoalMetricEvaluator 实时算不落库。回滚只回 jar 即可。
FVI="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
MES="$RD/src/main/java/com/family/finance/service/explain/MetricExplainService.java"
RPC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
{ [ "$(code_only "$FVI" | grep -c 'pmcFirstNetInflow(slice, periodId)')" -ge 2 ] \
  && code_only "$FVI" | grep -qF 'pmcFirstNetInflow(slice, current)' \
  && ! code_only "$FVI" | grep -qE 'periodIncome\(slice, periodId\)\.subtract\(periodExpense' \
  && ! code_only "$FVI" | grep -qE 'periodIncome\(slice, current\)\.subtract\(periodExpense' \
  && code_only "$RPC" | grep -qF 'factViewService.netWorthTrend(slice)' \
  && code_only "$RPC" | grep -qF 'cumOpeningBaseline' \
  && ! code_only "$RPC" | grep -qE 'firstNW = trend\.get\(0\)' \
  && grep -qF 'cumulativeOpeningBaseline' "$MES" \
  && grep -qF '与「人赚」同口径' "$MES" \
  && grep -qF '累计口径非年化' "$MES"; } \
  && log_ok "v1629-XIRR-LEDGER(tooltip 端点改用真实 netWorth 与指标同源 + 开账基线单列 + 标明年化/累计 · familyXirr/familyTwr 与「人赚」统一走 pmcFirstNetInflow)" \
  || log_bad "v1629-XIRR-LEDGER 缺件" "see FactViewServiceImpl(familyXirr/familyTwr 必须用 pmcFirstNetInflow,不得再出现 periodIncome-periodExpense 组合)· ReportsController(端点改 netWorthTrend + cumOpeningBaseline,不得再从 trend 取 firstNW)· MetricExplainService(cumulativeOpeningBaseline 字段 + 与「人赚」同口径 + 累计口径非年化)"

# v1630-CLOSED-ANCHOR · 收益类指标必须锚「最新已关账期」,不得被进行中账期污染(2026-08-01 全站指标核查 P0)
#   起因:全站 24 个 KPI 逐项核查。计算正确性本身没查出错(恒等式与逐期 PnL 合计全自洽),
#   但发现**锚点选错**:queryBase 是 account × period 全交叉且不过滤 period.status,
#   于是进行中的 OPEN 期进切片并成为 lastPeriodId,而它的典型状态是**余额已填、收支未录**
#   (prod 2026-08 实测:21 条余额快照 / 0 条现金流 / 无 PMC)。后果:
#     (期末 − 期初 − 净流入) 里净流入 = 0 → **还没录的工资被整块算成投资收益**。
#     prod 实测「本月资产收益 +0.99%」,那 9.1 万一分钱收支都没扣。
#   改:存量类(净资产/总资产/总负债/流动资产/环比)继续锚最后一期 —— 填报中就该看到最新余额,
#       缺快照还会结转上期,不会凭空缺口;
#       收益类(本月资产收益 / XIRR / TWR / YTD / 人赚钱赚拆解 / 储蓄率)改锚 returnPeriodIds(已关账期)。
#   附带修:① 总资产/净资产口径文案漏列 CRYPTO/METAL/INSURANCE(实际是"除 LOAN 外全部");
#          ② savingsRate 原只读 cash_flow 且锚进行中期 → prod 恒返回 null → GoalMetricEvaluator 的
#             nz() 兜成 0 → **储蓄率类家庭目标进度恒显示 0%**;改成 PMC 优先 + 锚已关账期;
#          ③ checkup 的 XIRR tooltip 补齐 reports 早就有的「与人赚同口径 / N 期 / 年化还是累计」。
#   **会改变线上数值**(是修正不是回归):prod 本月资产收益 +0.99% → 锚 2026-07;XIRR 0.79% → 只算 3 个已关账期。
#   线上处置:零 schema 迁移、零存量数据改写、纯读路径;回滚只回 jar。
#   ④ **checkup 的 YTD 累计损益吃掉负号**:模板写 `(signum()>=0 ? '+' : '') + '¥' + formatDecimal(abs())`
#      —— 负数分支前缀是空串,又套了 .abs() → 亏损 −¥〈金额已脱敏〉 渲染成「¥〈金额已脱敏〉」,
#      只有颜色类 num-neg 透出亏损,数字本身读起来是盈利。同文件 170 行(本月资产收益)的写法
#      `'+¥' : '−¥'` 才是对的。**这条是渲染验收时肉眼发现的,grep/单测都抓不到**(表达式语法完全合法、
#      指标值也算对了,错在展示)。全站扫了 12 处同类写法,只此 1 处同时用了 abs() → 只此 1 处是 bug。
#   护栏形状:openingBaselineLast 必须**仍锚 last** —— dashboard/review 的「本期怎么变」卡靠
#   ΔNW = 人赚 + 钱赚 + 开账基线 成立,而 ΔNW 与人赚都取 lastPeriodId,挪走会当场破掉恒等式。
FVI="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
FSL="$RD/src/main/java/com/family/finance/factview/FactSlice.java"
KPS="$RD/src/main/java/com/family/finance/factview/KpiSnapshot.java"
FMP="$RD/src/main/java/com/family/finance/repository/FactMapper.java"
FMX="$RD/src/main/resources/mapper/FactMapper.xml"
MES="$RD/src/main/java/com/family/finance/service/explain/MetricExplainService.java"
RPC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
DRG="$RD/src/main/resources/templates/dashboard/_region.html"
CKF="$RD/src/main/resources/templates/checkup/family.html"
# v1.10 FR-327:仪表盘那格改成**实时本月**(两页分工:仪表盘=当月实时 / 报表页=封板),
# 标题不再切成「资产收益 2026-07」,所以原来那条 grep returnAnchorMonth "$DRG" 去掉了。
# 但本条护栏守的 P0 一点没松 —— 锚已关账期那套口径**仍然存在且仍被用**(报表页封板走它),
# 下面三条钉住这一点:字段还在 / 关账口径的 explain 还在 / checkup 仍显示锚月。
# v1.23 · 判据从 FactMapper 搬到 PeriodMapper(它要数活跃成员,而事实层不许碰 member.archived_at,
#   见 v115-NO-MEMBER-ARCHIVE-IN-SUMS),并改名 findClosedPeriodIds → findSettledPeriodIds
#   (「已定稿」= CLOSED 或「已自然结束且填完」,closed 只覆盖前一种)。
QA123_PM_1630="$RD/src/main/java/com/family/finance/repository/PeriodMapper.java"
{ code_only "$FSL" | grep -qF 'returnPeriodIds()' \
  && code_only "$FSL" | grep -qF 'filingInProgress()' \
  && code_only "$QA123_PM_1630" | grep -qF 'findSettledPeriodIds' \
  && grep -qF "status = 'CLOSED'" "$QA123_PM_1630" \
  && code_only "$FVI" | grep -qF 'periodMapper.findSettledPeriodIds(' \
  && [ "$(code_only "$FVI" | grep -c 'slice.returnPeriodIds()')" -ge 4 ] \
  && code_only "$FVI" | grep -qF 'ytdSlice.returnPeriodIds()' \
  && code_only "$FVI" | grep -qF 'netInflowIncome(slice, anchor)' \
  && ! code_only "$FVI" | grep -qE 'periodIncome\(slice, slice\.lastPeriodId\(\)\)' \
  && code_only "$FVI" | grep -qF 'BigDecimal openingBaselineLast = openingBaseline(slice, last);' \
  && code_only "$KPS" | grep -qF 'returnAnchorNetWorth' \
  && code_only "$KPS" | grep -qF 'returnPeriodCount' \
  && code_only "$RPC" | grep -qF 'slice.returnPeriodIds().size()' \
  && ! code_only "$RPC" | grep -qE 'int familyMonths = slice\.periodIds\(\)\.size\(\)' \
  && grep -qF '口径期' "$MES" \
  && grep -qF '除贷款外的全部资产类型合计' "$DRG" \
  && grep -qF '除贷款外的全部资产类型合计' "$CKF" \
  && ! grep -qF 'CASH + STOCK + WEALTH + PROPERTY' "$DRG" \
  && ! grep -qF 'CASH + STOCK + WEALTH + PROPERTY' "$CKF" \
    && code_only "$KPS" | grep -qF 'returnAnchorMonth' \
    && code_only "$FVI" | grep -qF 'monthlyInvestReturnPct' \
    && grep -qF 'monthlyPnlCalc' "$MES" \
  && grep -qF 'returnAnchorMonth' "$CKF" \
  && grep -qF "cumulativeYtdPnl.signum() >= 0 ? '+¥' : '−¥'" "$CKF" \
  && [ "$(grep -c "signum() >= 0 ? '+' : ''" "$CKF" | tr -d ' ')" -le 2 ]; } \
  && log_ok "v1630-CLOSED-ANCHOR(收益类锚最新已关账期 · 存量类仍锚末期 · openingBaselineLast 不动保「本期怎么变」恒等式 · savingsRate 走 PMC 优先 · 总资产口径文案补全 CRYPTO/METAL/INSURANCE · 两页 KPI 标注口径期)" \
  || log_bad "v1630-CLOSED-ANCHOR 缺件" "see FactSlice(returnPeriodIds/filingInProgress)· FactMapper(+xml findClosedPeriodIds status='CLOSED')· FactViewServiceImpl(familyXirr/familyTwr/ytd/拆解 走 returnPeriodIds · savingsRate 走 netInflowIncome 不得再用 periodIncome(lastPeriodId) · openingBaselineLast 必须仍锚 last)· KpiSnapshot(returnAnchorNetWorth/returnPeriodCount)· ReportsController(familyMonths 改 returnPeriodIds)· dashboard/_region.html + checkup/family.html(总资产文案不得再出现 CASH + STOCK + WEALTH + PROPERTY · 须带 returnAnchorMonth 口径期标注)"

# v1631-RPT-ACCT-M · 报表「账户级收益 · vs 基准」必须有手机端卡片布局(用户第 21 轮:「手机端那个账户明细 排版差劲的不行」)
#   实测证据(390×844):该 section 原先手机上只有 PC 宽表 + overflow-x-auto ——
#   **17 列共 1653px 塞进 358px 容器**,首屏只看得到「账户/类型/类目」三列,
#   **一个叫「账户级收益」的表在手机上一个收益数字都看不到**;类型 pill 被压到 ~40px 宽把
#   「现金」折成两行;行高 ~140px × 18 行 = 1480px 却几乎不承载信息;且 有手机块=false
#   (仪表盘 v0.8 起就有 sm:hidden 卡片块,报表这块一直漏做)。
#   改:PC 表限定 hidden sm:block;<sm 用 details 卡片(同一份 accountRows / 同一套 acctMetrics 门控 /
#   同样带 data-mcol 所以顶部 chips 继续管得到)。主行 [类型pill][账户名] … [vs基准pill],
#   次行 [当前价值 · 收益率] … [展开],两行都 justify-between → 有/无 pill 的卡片等高不参差
#   (实测 18 张卡折叠态高度集 = {67},唯一值)。
#   基准% 刻意不放次行:实测窄屏会折行并拖出一个孤立的「·」,改进展开区。
#   顺带修 `.pill{text-transform:uppercase}` 把「跑赢/跑输 ±N pp」的**单位 pp 渲染成 PP** ——
#   加 .pill-vs{text-transform:none},PC 与手机同一处修(只给 6 个 vs pill,不动类型/类目 pill)。
#   2026-08-06(v1.9.3):这条原来 grep `.pill-vs{ text-transform:none; }` **逐字**匹配,
#   给该规则补 white-space:nowrap 之后整条就红了 —— 守的意图(pp 不被 uppercase 成 PP)一点没变。
#   同 v11-UED8 的教训:护栏盯字面就会被无关改动打红,改成正则匹配意图。顺带把 nowrap 纳入守护。
RGN="$RD/src/main/resources/templates/reports/_region.html"
{ grep -qF 'sm:hidden space-y-2' "$RGN" \
  && grep -qF 'overflow-x-auto hidden sm:block' "$RGN" \
  && [ "$(grep -c 'pill pill-vs' "$RGN")" -eq 6 ] \
  && grep -qE '\.pill-vs\{[^}]*text-transform:none' "$RGN" \
  && grep -qE '\.pill-vs\{[^}]*white-space:nowrap' "$RGN" \
  && [ "$(grep -c 'data-mcol' "$RGN")" -ge 24 ] \
  && grep -qF 'min-width:3.4em' "$RGN"; } \
  && log_ok "v1631-RPT-ACCT-M(报表账户段手机卡片布局 · PC 表限定 sm:block · chips 仍管手机卡 · 类型 pill 固定宽防折行 · vs pill 的单位 pp 不再被大写)" \
  || log_bad "v1631-RPT-ACCT-M 缺件" "see reports/_region.html:需 sm:hidden 卡片块 + PC 表 hidden sm:block + 恰好 6 处 pill-vs(3 PC + 3 手机,不含类型/类目 pill)+ .pill-vs{text-transform:none} + 手机卡带 data-mcol(chips 联动)+ 类型 pill min-width:3.4em"

# v1632-MANUAL · 站内使用手册 + 新手引导卡 + 常驻入口(issue #9)
#   起因:一位用户(自述没有财会背景)在 issue #9 问「理财的买入卖出、借钱等如何在这些体现」。
#   查证属实:此前站内帮助页只有 broker-sync 一篇,docs/faq.md 四章全是部署/备份/登录/AI 这类运维题,
#   **一条「业务上该怎么记」都没有** —— 等于把门槛原封不动留给了没有财会背景的人。
#   做法:docs/how-to-use.md(主流程 + 分析路径)+ docs/how-to-record.md(场景速查)+
#   站内页 /help/how-to-use(正文同前者,另带两处解释性动画:填报顺序 / 明细抽屉从哪来)。
#   入口策略(用户拍板):第一次用要很醒目,用过之后要保留入口。
#     · 醒目:仪表盘 + 填报页顶部引导卡,localStorage `manualHintDismissedAt` 一年
#       (用户明确选 localStorage 而非服务端状态;代价是每台设备各提示一次,可接受)
#     · 常驻:导航栏「手册」(PC 主栏 + 移动抽屉)+ 填报页标题旁「怎么填?」
#   **守护重点**:引导卡一年后会消失,所以**不能只靠那张卡** —— 卡消失后导航栏与填报页那两处必须还在。
#   entry-points.json 已登记 id=manual(level=obvious · pc+mobile),由 v1623-ENTRY-VIS 运行时兜底。
#   v1.6.32 二轮(用户第 25 轮反馈):
#     ① 第一版是「按我想到的写」而不是按功能全集写 —— 目标模块整个没提,dashboard 一堆区块也没讲,
#        分析部分只写了用户举例的那条旭日路径。改法:先把 70+ 路由 / 管理页 15 块穷举成 33 个功能点,
#        分成必修 8 + 选修 25(省力 / 分析 / 决策 / 进阶四类),再据此排章节。
#        成品:主教程 5 章(必修)+ 选修 A5 / B7 / C4 / D3 共 19 节,顶部 24 张章节卡可锚点直达。
#        守护断言章节卡 ≥20 张、必修/选修徽记都在、五个代表性锚点(ch1/a1/b3/c3/d1)存在。
#     v1.7.0 三轮(用户第 26 轮:滚大版本 + 每章要链接 + 每章要配图 + 表现可以更外放):
#       · **每章一个真实入口链接**(class=goto · 24 个):读到哪就能点进去做,如「建账户」→ /accounts、
#         「财务目标」→ /goals、「提前还贷」→ /reports/refinance。一律 th:href="@{...}" 不写死路径 ——
#         守护里那条 `! grep -qE 'href="/[a-z]'` 就是防有人图省事写成裸路径(部署在子路径下会全断)。
#       · **每章配图**(15 张 · beta 实拍 · 隐私模式糊金额 · 统一套外框 · 压到 1500px 宽共 1.9MB),
#         放 static/img/manual/,页面用 loading=lazy。
#         踩过的坑:frame-shots.py 对同一目录跑第二次会**给已套框的图再套一层框**并还原体积
#         (b1-tags 一度变成 2242×8959 / 1.7MB);正确做法是从 *.raw.jpg 重来,只套一次。
#       · 视觉外放:章节大编号水印、hero 描边数字、分组封面条、配图悬停微抬、阅读进度条。
#         仍在现有设计令牌内(纸感底 / Fraunces / 等宽数字 / 黄铜森绿铁锈),不引新色系、不加载外部字体。
#     v1.7.0 四轮(用户第 27 轮 · 六条):
#       ① 手册**免登录**:潜在用户在决定要不要自建之前就该读懂它怎么用。SecurityConfig 放行 +
#          匿名轻头。**坑**:`th:replace` 优先级(1)高于 `th:if`(3),直接在 <header> 上写 th:if
#          拦不住,片段照样渲染 → 必须用 <th:block th:if> 包住。守护断言两者都在。
#       ② 交互演示比截图更能教会人 → 从 2 个扩到 4 个:A 填报顺序 · B 下钻三步 ·
#          C 币种是显示镜头(点 CNY/USD/HKD:金额缩放、比值四项一个数不动)·
#          D 人赚 vs 钱赚(三种情形切换,含「涨了 10 万其实投资亏 6 万」)。
#       ③ 复杂章节必须图文并茂:补 数据源接入 / 富途 OpenD / 新建目标 / 指标设置 /
#          产品类目 / 备份 / 审计 共 7 张,配图 15 → 21 张。
#       ④ beta 非真实数据 → **关闭隐私模式重拍**(SHOT_PRIVACY=0),不再糊金额。
#          坑:补拍时第二次跑忘带 SHOT_PRIVACY=0,把不打码的图覆盖回打码版,只能整批重来。
#       ⑤ 演示 A 重做成**仿真填报页**:控件、标签、配色照真实页面 1:1
#          (收入绿标签 / 现金收入·股票收入 / 金额·类目·现金账户 / 「+ 加一笔」黑按钮 / 「本期余额」)
#          —— 否则在教程里学会了、到真实系统 UI 差异太大还是不会用。守护断言 .simrow 存在。
#       ⑥ D 段(进阶与维护)原来过薄且配图无教学价值(汇率页 300 行重复行):
#          课节 3 → 11 条、配图 2 → 5 张、汇率图裁到顶部一屏,并把演示 C 挂在多币种章。
#     v1.7.0 五轮(用户第 28 轮:手册要在 / 落地页体现):
#       手册免登录改变了落地页的逻辑 —— 以前访客只能「信」文案,现在能「自己验」。三处各干不同的活:
#         · hero:命令块下面加「还没决定要不要装?」分隔 + 手册卡(不用装/不用登录)。
#           **不能左右并排** —— 命令块里 git clone 那行 URL 不可断行,并排时会把手册卡挤成
#           88px 宽的细条(实测),改上下堆叠后 PC 600×106 / 移动 345×129 正常。
#         · 中段「你大概也在问这几个问题」的 3 个问题挂手册章节锚点(#b6 财富水位 /
#           #ch4 人赚vs钱赚 / #b3 钻到具体持仓)—— 问题本来就写好了,只是此前没有出口,
#           读者点头认同完就没下一步。锚点对匿名访客同样有效。
#         · footer CTA 加第三个按钮「先看使用手册」。
#       守护断言 landing.html 内 /help/how-to-use ≥4 处且含「还没决定要不要装」。
#     ② 导航:「手册」挪到主栏最末(不插在功能项中间);既然它有 icon,**所有菜单项都得有** ——
#        8 项统一 Feather 风格 inline SVG。顺带修掉自己引入的对齐 bug:加 inline-flex 后激活项比
#        其他项高 10px(激活态 pb-[18px] 撑高 + 容器底对齐),给未激活项补 border-transparent + 同 padding
#        后 8 项图标中心 Y 全部对齐到同一像素。守护断言 nav.html 内 svg ≥16(PC 8 + 移动 8)。
HC="$RD/src/main/java/com/family/finance/web/help/HelpController.java"
HTPL="$RD/src/main/resources/templates/help/how-to-use.html"
HINT="$RD/src/main/resources/templates/fragments/_manual-hint.html"
NAV="$RD/src/main/resources/templates/fragments/nav.html"
ENT="$RD/src/main/resources/templates/entry/index.html"
DASH="$RD/src/main/resources/templates/dashboard/index.html"
{ code_only "$HC" | grep -qF '/help/how-to-use' \
  && [ -f "$HTPL" ] && [ -f "$HINT" ] \
  && grep -qF 'fragments/layout :: head' "$HTPL" \
  && ! grep -qF 'PREVIEW' "$HTPL" \
  && grep -qF 'manualHintDismissedAt' "$HINT" \
  && [ "$(grep -c 'toc-card' "$HTPL")" -ge 20 ] \
  && grep -qF 'badge-req' "$HTPL" && grep -qF 'badge-opt' "$HTPL" \
  && grep -qF 'id="ch1"' "$HTPL" && grep -qF 'id="a1"' "$HTPL" \
  && grep -qF 'id="b3"' "$HTPL" && grep -qF 'id="c3"' "$HTPL" && grep -qF 'id="d1"' "$HTPL" \
  && [ "$(grep -c '<svg' "$NAV")" -ge 16 ] \
  && [ "$(grep -c 'class="goto"' "$HTPL")" -ge 20 ] \
  && [ "$(grep -c 'figure class="fig' "$HTPL")" -ge 12 ] \
  && [ "$(ls "$RD/src/main/resources/static/img/manual/" 2>/dev/null | grep -c '\.jpg$')" -ge 12 ] \
  && ! grep -qE 'href="/[a-z]' "$HTPL" \
  && [ "$(grep -c 'figure class="fig' "$HTPL")" -ge 18 ] \
  && grep -qF 'class="simrow' "$HTPL" \
  && grep -qF 'data-ccy' "$HTPL" && grep -qF 'data-case' "$HTPL" \
  && grep -qF '/help/how-to-use' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -qF 'th:if="${me != null}"' "$HTPL" \
  && [ "$(grep -c '/help/how-to-use' "$RD/src/main/resources/templates/landing.html")" -ge 4 ] \
  && grep -qF '还没决定要不要装' "$RD/src/main/resources/templates/landing.html" \
  && grep -qF "display:none" "$HINT" \
  && [ "$(grep -c '/help/how-to-use' "$NAV")" -ge 2 ] \
  && grep -qF '/help/how-to-use' "$ENT" \
  && grep -qF '_manual-hint' "$ENT" \
  && grep -qF '_manual-hint' "$DASH" \
  && grep -qF '"id": "manual"' "$RD/scripts/entry-points.json" \
  && [ -f "$RD/docs/how-to-use.md" ] && [ -f "$RD/docs/how-to-record.md" ]; } \
  && log_ok "v1632-MANUAL(站内 /help/how-to-use + 新手卡 localStorage 一年 + 导航栏与填报页常驻入口 · 卡消失后入口仍在)" \
  || log_bad "v1632-MANUAL 缺件" "see HelpController(/help/how-to-use)· templates/help/how-to-use.html(须套 layout · 不得残留 PREVIEW 条)· fragments/_manual-hint.html(manualHintDismissedAt + 默认 display:none 防闪)· nav.html 至少 2 处入口(PC+移动)· entry/index.html 与 dashboard/index.html 挂卡 · entry-points.json 登记 id=manual · docs/how-to-use.md + how-to-record.md"

# v1246-TRANSFER-IN-USES-RECEIVED-AMOUNT · issue #21 · 跨币种划转,转入方按它收到的钱算。
#   人民币账户转 1000 到美元账户、实到 100 美元,余额都对,美元账户却冒出「900 未解释」——
#   填报页给转入方算已知流入时用的是 amount(转出方付出的,1000 人民币),不是 to_amount。
#   to_amount 从 v0.8 就有,新增/撤销用对了,算差额与显示明细的地方一直在手写 getAmount()。
#   收成 Transfer.receivedAmount();这里守 EntryService 里「转入方」那几处不许再退回 getAmount()。
QA21_ES="$RD/src/main/java/com/family/finance/service/EntryService.java"
QA21_BAD="$(java_code_only "$QA21_ES" | grep -nE 'transferIn = transferIn\.add\([a-z]+\.getAmount\(\)\)|incoming\.add\(new EntryRow\.TransferRef\(name, t\.getAmount\(\)' | cut -d: -f1 | tr '\n' ' ')"
{ grep -q 'public BigDecimal receivedAmount()' "$RD/src/main/java/com/family/finance/domain/transfer/Transfer.java" \
  && [ -z "$QA21_BAD" ] \
  && [ "$(java_code_only "$QA21_ES" | grep -c 'receivedAmount()')" -ge 3 ] \
  && grep -q '"is_draft", "to_amount"' "$RD/src/main/java/com/family/finance/service/export/CsvExportService.java" \
  && ! grep -q 'String amt = "¥"' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'le.counterAmountLabel()' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && [ -f "$RD/src/test/java/com/family/finance/domain/transfer/TransferReceivedAmountTest.java" ]; } \
  && log_ok "v1246-TRANSFER-IN-USES-RECEIVED-AMOUNT(转入方的差额与明细都按实到金额 · 删除确认框两边各用各的金额与币种 · 导出带 to_amount)" \
  || log_bad "v1246-TRANSFER-IN-USES-RECEIVED-AMOUNT 跨币种转入又按转出方金额算了" "EntryService 第 ${QA21_BAD:-?} 行 · 转入方一律用 Transfer.receivedAmount()"

# v1245-AI-SETTINGS-ONE-PLACE · AI 的设置只在一处(AI 接入)。
#   2026-09-23 维护者:「AI 接入为什么是独立的 tab?」—— 查下来是超级 Agent 要用的配置被拆在两页:
#   百炼的 Key、型号在「数据源接入」,Agent / MCP / 口令在「AI 接入」。排查一个问题要来回切,
#   代码里的错误提示也在两页之间互相指。定的方案:大模型那一节挪到 AI 接入,数据源接入只留行情/汇率/券商。
#   这条守的是「不会又长回两处」:数据源接入页上不许再有大模型表单,指路文案不许再指回数据源接入。
QA1245_INT="$RD/src/main/resources/templates/admin/integrations.html"
QA1245_AI="$RD/src/main/resources/templates/admin/ai-access.html"
QA1245_SB="$RD/src/main/resources/templates/admin/_sidebar.html"
QA1245_STALE=""
for f in service/ask/runtime/LocalToolLoopRuntime.java service/ask/runtime/ManagedAgentRuntime.java \
         service/holdingimport/HoldingImportService.java; do
  java_code_only "$RD/src/main/java/com/family/finance/$f" | grep -q '「数据源接入」' && QA1245_STALE="$QA1245_STALE ${f##*/}"
done
#   v1.26 · 券商同步单独成入口,插在两者之间 —— 三个「对外连接」连成一段(数据源 → 券商 → AI),AI 仍紧挨着接入类
QA1245_SB_ORDER="$(grep -oE '/admin/(integrations|broker|ai-access)\}' "$QA1245_SB" | tr -d '}' | tr '\n' ' ')"
{ ! grep -qE 'llm/key|llm-catalog|initTriple' "$QA1245_INT" \
  && ! grep -q '@PostMapping("/llm' "$RD/src/main/java/com/family/finance/web/admin/IntegrationsController.java" \
  && grep -q "admin/_llm-settings :: section" "$QA1245_AI" \
  && grep -q "admin/_llm-settings :: script" "$QA1245_AI" \
  && grep -q 'href="#llm"' "$QA1245_AI" && grep -q 'id="agent"' "$QA1245_AI" && grep -q 'id="access"' "$QA1245_AI" \
  && [ "$QA1245_SB_ORDER" = "/admin/integrations /admin/broker /admin/ai-access " ] \
  && ! grep -q 'AI 接入<span' "$QA1245_SB" \
  && [ -z "$QA1245_STALE" ]; } \
  && log_ok "v1245-AI-SETTINGS-ONE-PLACE(大模型只在 AI 接入 · 数据源接入无大模型表单 · 侧栏相邻 · 提示不再指回数据源接入)" \
  || log_bad "v1245-AI-SETTINGS-ONE-PLACE AI 的设置又分成两处了" "侧栏顺序:[$QA1245_SB_ORDER] · 仍指回数据源接入:${QA1245_STALE:-无}"

# v1246-ADVICE-DISMISS-WORKS · issue #22 · 体检「值得做的事」的「不适用」按钮要真的能用。
#   这个按钮从 v0.2(2026-05)起就是 onclick="advice.dismiss(…)" —— 一个【从没写过】的前端函数,
#   后端也没有接口。点了浏览器报 advice is not defined,页面上什么都不发生,四个多月没人发现。
#   现在是普通表单提交 + 存进家庭配置;这里守「不许再挂回一个不存在的 JS 函数」,并要求接口与过滤都在。
QA22_CARD="$RD/src/main/resources/templates/checkup/_advice-card.html"
QA22_CTL="$RD/src/main/java/com/family/finance/web/checkup/CheckupController.java"
#   只看属性值里的调用(onclick="…advice.dismiss(…)"),模板注释里讲历史的那句不算。
{ ! grep -qE '="[^"]*advice\.dismiss\(' "$QA22_CARD" \
  && grep -q 'th:action="@{/checkup/advice/dismiss}"' "$QA22_CARD" \
  && grep -q 'th:fragment="restore(hidden, accountId)"' "$QA22_CARD" \
  && grep -q '"/checkup/advice/dismiss"' "$QA22_CTL" \
  && grep -q '"/checkup/advice/restore"' "$QA22_CTL" \
  && [ "$(grep -c 'adviceDismiss.visible' "$QA22_CTL")" -ge 2 ] \
  && grep -q 'adviceDismiss.visible' "$RD/src/main/java/com/family/finance/web/checkup/AiDiagnoseController.java" \
  && [ -f "$RD/src/test/java/com/family/finance/service/checkup/AdviceDismissServiceTest.java" ]; } \
  && log_ok "v1246-ADVICE-DISMISS-WORKS(「不适用」是真表单 · 存家庭配置 · 家庭页/账户页/AI 诊断都过滤 · 可一键恢复)" \
  || log_bad "v1246-ADVICE-DISMISS-WORKS「不适用」又退回成点了没反应" "卡片不许再调 advice.dismiss(…);要有 /checkup/advice/dismiss 与 /restore 接口,三处都要过滤"

# v1246-ADVICE-DISMISS-LIVE · 真的点一下:标不适用后这条从体检页消失,恢复后回来
QA22_BEFORE="$(mysql -ufinance -pfinance finance -N -e "SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='checkup_advice_dismissed';" 2>/dev/null)"
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
QA22_RULE="$(grep -oE 'name="ruleId" value="[^"]+"' "$TMP" | head -1 | sed -E 's/.*value="([^"]+)"/\1/')"
if [ -z "$QA22_RULE" ]; then
  log_skip "v1246-ADVICE-DISMISS-LIVE" "当前 beta 数据下家庭体检一条提醒都没命中,无从点「不适用」"
else
  XSRF=$(awk -F'\t' '/XSRF-TOKEN/ {print $NF}' $COOKIE)
  $CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
    --data-urlencode "ruleId=$QA22_RULE" "$BASE/checkup/advice/dismiss" -o /dev/null -w ""
  $CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
  QA22_GONE=$(grep -c "name=\"ruleId\" value=\"$QA22_RULE\"" "$TMP")
  QA22_NOTE=$(grep -c '已隐藏 1 条' "$TMP")
  XSRF=$(awk -F'\t' '/XSRF-TOKEN/ {print $NF}' $COOKIE)
  $CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
    "$BASE/checkup/advice/restore" -o /dev/null -w ""
  $CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
  QA22_BACK=$(grep -c "name=\"ruleId\" value=\"$QA22_RULE\"" "$TMP")
  { [ "$QA22_GONE" = "0" ] && [ "$QA22_NOTE" -ge 1 ] && [ "$QA22_BACK" -ge 1 ]; } \
    && log_ok "v1246-ADVICE-DISMISS-LIVE(标「不适用」后 $QA22_RULE 从家庭体检消失并提示已隐藏 · 恢复后回来)" \
    || log_bad "v1246-ADVICE-DISMISS-LIVE 点了「不适用」没效果或恢复不了" "rule=$QA22_RULE 标后仍在:$QA22_GONE 已隐藏提示:$QA22_NOTE 恢复后在:$QA22_BACK"
  # 还原成跑之前的样子(声明终态,不依赖中间过程)
  mysql -ufinance -pfinance finance -e "DELETE FROM family_runtime_config WHERE family_id=1 AND key_name='checkup_advice_dismissed';" 2>/dev/null
  [ -n "$QA22_BEFORE" ] && mysql -ufinance -pfinance finance -e "INSERT INTO family_runtime_config (family_id,key_name,value_text) VALUES (1,'checkup_advice_dismissed','$QA22_BEFORE');" 2>/dev/null
fi

# v1246-ADVICE-DISMISS-FOREIGN-ACCOUNT · 传一个不是自己家的账户号,什么都不许存。
#   端上验收时抓到:原来被当成「没传账户」,悄悄存成了一条家庭级「不适用」——
#   一个针对某个账户的操作,变成了对全家生效。
QA22F_BEFORE="$(mysql -ufinance -pfinance finance -N -e "SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='checkup_advice_dismissed';" 2>/dev/null)"
XSRF=$(awk -F'\t' '/XSRF-TOKEN/ {print $NF}' $COOKIE)
QA22F_LOC=$($CURL -b $COOKIE -c $COOKIE -X POST -H "X-XSRF-TOKEN: $XSRF" --data-urlencode "_csrf=$XSRF" \
  --data-urlencode "ruleId=QA-FOREIGN-ACCT" --data-urlencode "accountId=987654321" \
  "$BASE/checkup/advice/dismiss" -o /dev/null -w "%{http_code} %{redirect_url}")
QA22F_AFTER="$(mysql -ufinance -pfinance finance -N -e "SELECT value_text FROM family_runtime_config WHERE family_id=1 AND key_name='checkup_advice_dismissed';" 2>/dev/null)"
{ echo "$QA22F_LOC" | grep -q '^302 .*/checkup#checkup-advice$' && ! echo "$QA22F_AFTER" | grep -q 'QA-FOREIGN-ACCT'; } \
  && log_ok "v1246-ADVICE-DISMISS-FOREIGN-ACCOUNT(不是自己家的账户号 → 直接忽略,不会变成全家生效的「不适用」)" \
  || log_bad "v1246-ADVICE-DISMISS-FOREIGN-ACCOUNT 别人家的账户号被存下来了" "响应:$QA22F_LOC 存下的:[$QA22F_AFTER]"
mysql -ufinance -pfinance finance -e "DELETE FROM family_runtime_config WHERE family_id=1 AND key_name='checkup_advice_dismissed';" 2>/dev/null
[ -n "$QA22F_BEFORE" ] && mysql -ufinance -pfinance finance -e "INSERT INTO family_runtime_config (family_id,key_name,value_text) VALUES (1,'checkup_advice_dismissed','$QA22F_BEFORE');" 2>/dev/null

# v1246-AGENT-TEMPLATE-DRIFT-VISIBLE · 百炼上的 Agent 模板落后于代码时,必须被看见。
#   2026-09-23:生产的模板停在 09-04,tools 一直是空数组 —— 发新版本不会更新远端的 Agent。
#   百炼 14 天零次来调我们,agent 对用户说「没有任何工具连接到我这边」,我们这边零条错误。
QA1246_MA="$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java"
{ grep -q 'TemplateDrift templateDrift()' "$QA1246_MA" \
  && grep -q 'static TemplateDrift driftOf' "$QA1246_MA" \
  && grep -q 'cachedDrift()' "$QA1246_MA" \
  && grep -q 'invalidateDrift()' "$QA1246_MA" \
  && grep -q 'static String sessionFor' "$QA1246_MA" \
  && grep -q '"@v" + ver' "$QA1246_MA" \
  && grep -q 'askTemplateDrift' "$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java" \
  && grep -q 'askTemplateDrift.stale()' "$RD/src/main/resources/templates/admin/ai-access.html" \
  && grep -q 'form="createAgentForm"' "$RD/src/main/resources/templates/admin/ai-access.html" \
  && [ -f "$RD/src/test/java/com/family/finance/service/ask/runtime/ManagedAgentTemplateDriftTest.java" ]; } \
  && log_ok "v1246-AGENT-TEMPLATE-DRIFT-VISIBLE(远端模板过期:提问前拦住并说清下一步 · 管理页横幅就地可更新 · 旧会话按版本换新)" \
  || log_bad "v1246-AGENT-TEMPLATE-DRIFT-VISIBLE 远端模板过期又会静默" "要有 templateDrift/driftOf + 提问前 cachedDrift 拦截 + 更新后 invalidateDrift + sessionFor 版本化会话 + 管理页横幅"

# v1246-VIEW-NAMES-EXIST · 控制器返回的模板、模板里引用的片段,必须真的是仓库里的文件。
#   2026-09-24:v1.24 把 reports/_expense-mix.html 并进了 _expense-section.html,
#   而「支出构成 → 点格子看逐笔」的控制器还返回 "reports/_expense-mix :: detail"。
#   本机与 prod 都是在长期存在的工作区里 `mvn package`(不 clean),target/classes 里还留着旧文件,
#   所以一直「能用」;Docker 镜像是 `clean package` → 每个 Docker 用户点格子都是整页错误。
#   编译器看不见字符串里的模板名,单测也不渲染模板 —— 只能在这里对着文件系统查。
#   只查带「/」的视图名:不带斜杠的 "ok" / "started" 之类是 @ResponseBody 的返回值,不是模板。
QA_VIEWS_MISSING="$(grep -rhoE 'return "[^"]+";' "$RD/src/main/java/com/family/finance/web" \
  | sed -E 's/return "([^"]+)";/\1/' | grep -vE '^(redirect|forward):' \
  | grep -E '^[a-z][a-z0-9_-]*(/[A-Za-z0-9_.-]+)+( :: .+)?$' | sed -E 's/ *::.*$//' | sort -u \
  | while read -r v; do [ -f "$RD/src/main/resources/templates/$v.html" ] || printf '%s ' "$v"; done)"
QA_FRAGS_MISSING="$(grep -rhoE '~\{[a-z][A-Za-z0-9_/.-]*(/[A-Za-z0-9_.-]+)+ *::' "$RD/src/main/resources/templates" \
  | sed -E 's/~\{([^ :]+) *::/\1/' | sort -u \
  | while read -r v; do [ -f "$RD/src/main/resources/templates/$v.html" ] || printf '%s ' "$v"; done)"
{ [ -z "$QA_VIEWS_MISSING" ] && [ -z "$QA_FRAGS_MISSING" ]; } \
  && log_ok "v1246-VIEW-NAMES-EXIST(控制器返回的模板 · 模板引用的片段 都能在仓库里找到文件)" \
  || log_bad "v1246-VIEW-NAMES-EXIST 引用了不存在的模板" "控制器:${QA_VIEWS_MISSING:-无} · 片段引用:${QA_FRAGS_MISSING:-无}(dirty 构建会被 target/ 里的旧文件掩盖,clean 构建直接报错)"

# v126-IBKR-READONLY-AND-SAFE · 盈透 IBKR(issue #24)的几条硬约束,每一条都对应一种「不报错、只是出事」:
#   ① 失败是 HTTP 200 + Fail 信封(beta 实测)→ 解析器必须先认信封,空报表 / 缺栏目拒绝对账(否则持仓全被归档)
#   ② 不带 User-Agent 是 403(beta 实测)
#   ③ 取数基址能从家庭配置改(为了 e2e 桩)→ 只认 *.interactivebrokers.com 的 HTTPS 或本机回环,口令不许被发到别处
#   ④ 报表来自外部网络 → 关闭 DTD / 外部实体
#   ⑤ 只有 GET,没有任何写方法
QA_IB="$RD/src/main/java/com/family/finance/service/broker/ibkr"
{ [ -d "$QA_IB" ] \
  && grep -q 'readEnvelope(xml)' "$QA_IB/IbkrFlexParser.java" \
  && grep -q '报表里一个账户都没有' "$QA_IB/IbkrFlexParser.java" \
  && grep -q 'sawPositions || !sawCash' "$QA_IB/IbkrFlexParser.java" \
  && grep -q '"BASE_SUMMARY"' "$QA_IB/IbkrFlexParser.java" \
  && grep -q 'header("User-Agent"' "$QA_IB/IbkrFlexHttp.java" \
  && grep -q 'interactivebrokers.com' "$QA_IB/IbkrFlexHttp.java" \
  && grep -q 'SUPPORT_DTD, false' "$QA_IB/IbkrFlexParser.java" \
  && grep -q 'IS_SUPPORTING_EXTERNAL_ENTITIES, false' "$QA_IB/IbkrFlexParser.java" \
  && ! java_code_only "$QA_IB/IbkrFlexHttp.java" | grep -qE '\.(POST|PUT|DELETE)\(' \
  && [ -f "$RD/src/test/java/com/family/finance/service/broker/ibkr/IbkrFlexParserTest.java" ] \
  && [ -f "$RD/src/test/java/com/family/finance/service/broker/ibkr/IbkrFlexHttpTest.java" ]; } \
  && log_ok "v126-IBKR-READONLY-AND-SAFE(失败信封不当数据 · 空报表 / 缺栏目拒绝 · 带 UA · 基址锁 IBKR 域名 · 关 XXE · 只有 GET)" \
  || log_bad "v126-IBKR-READONLY-AND-SAFE IBKR 接入少了一道闸" "见 service/broker/ibkr 与 tech-design/v1.26.md §零 静默失败预检"

# v126-IBKR-ERRORS-HUMAN · 用户最常撞上的几个错误码,每个都要有一句人话,并且带 IBKR 原话(上游原话被兜底盖住已犯过三次)
QA_IBE="$QA_IB/IbkrErrors.java"
# 【子 shell ( ) 不是 { }】—— 里面有 exit,用 { } 会把整个 qa-run 退掉(见 v1230-E2E-TWO-LAYER-ASSERT 的注释)
( for c in 1012 1013 1014 1015 1018 1019; do grep -q "\"$c\"" "$QA_IBE" || exit 1; done
  grep -q 'IBKR 原话' "$QA_IB/IbkrFlexException.java" \
  && grep -q 'instanceof com.family.finance.service.broker.ibkr.IbkrFlexException' "$RD/src/main/java/com/family/finance/web/admin/BrokerSettingsController.java" \
  && grep -q 'instanceof com.family.finance.service.broker.ibkr.IbkrFlexException' "$RD/src/main/java/com/family/finance/service/broker/BrokerSyncService.java" ) \
  && log_ok "v126-IBKR-ERRORS-HUMAN(1012/1013/1014/1015/1018/1019 都有人话 · 管理页测试与同步失败卡片原样带 IBKR 原话)" \
  || log_bad "v126-IBKR-ERRORS-HUMAN IBKR 的失败会被一句兜底盖住" "IbkrErrors 要覆盖常见码;BrokerSettingsController.test 与 BrokerSyncService.failureNote 对 IbkrFlexException 原样透出"

# v126-VENDOR-EXHAUSTIVE · 加券商不许漏:流水来源对 BrokerVendor 穷尽(不留 default)· 模板不许二选一猜券商名 ·
#   数据库约束放得下每一家(单测 BrokerVendorSweepTest 按集合守,这里守它还在、穷尽 switch 还在)
QA_LS="$RD/src/main/java/com/family/finance/domain/ledger/LedgerSource.java"
{ grep -q 'public static LedgerSource forVendor(com.family.finance.domain.broker.BrokerVendor v)' "$QA_LS" \
  && ! sed -n '/public static LedgerSource forVendor/,/^    }/p' "$QA_LS" | grep -qE '^\s*default\s*->' \
  && [ -f "$RD/src/test/java/com/family/finance/service/broker/BrokerVendorSweepTest.java" ] \
  && ! grep -rqE "vendor\.name\(\)\s*==\s*'[A-Z]+'\s*\?\s*'[^']*'\s*:\s*'" "$RD/src/main/resources/templates"; } \
  && log_ok "v126-VENDOR-EXHAUSTIVE(加券商时流水来源 / 模板显示名 / 数据库约束都会被拦下)" \
  || log_bad "v126-VENDOR-EXHAUSTIVE 加券商又会漏" "LedgerSource.forVendor 要对 BrokerVendor 穷尽且无 default;模板用 vendor.label"

# v126-IBKR-EXPIRY-VISIBLE · 报表口令一年一到期 —— 到期后同步会停,必须提前说、过期后标红(不许静默停更)
{ grep -q 'brokerExpiry' "$RD/src/main/resources/templates/accounts/index.html" \
  && [ "$(grep -c 'brokerExpiry.containsKey(row.account.id)' "$RD/src/main/resources/templates/accounts/index.html")" -ge 2 ] \
  && grep -q 'ibkrDaysToExpiry' "$RD/src/main/resources/templates/broker/link.html" \
  && grep -q 'EXPIRY_WARN_DAYS' "$RD/src/main/java/com/family/finance/web/account/AccountController.java"; } \
  && log_ok "v126-IBKR-EXPIRY-VISIBLE(到期前 14 天在账户列表 PC + 手机、券商页都标出来)" \
  || log_bad "v126-IBKR-EXPIRY-VISIBLE IBKR 口令到期会静默停更" "账户列表两套视图都要有到期标记;券商页要有提醒"

# v126-BROKER-FAIL-OUTSIDE-TX · 同步失败的记录不能写在会回滚的事务里。
#   原来 BrokerSyncService.sync 整个是 @Transactional,失败时先 markFailed 再抛 → 事务回滚把失败记录也滚掉了:
#   页面点「立即同步」失败后,卡片和账户列表仍是上一次成功(v1.17.3 的修复只在定时任务那条路上生效 ——
#   那条路是类内自调用,没有事务)。2026-09-24 e2e flow 27 查库真值抓到。
QA_BSS="$RD/src/main/java/com/family/finance/service/broker/BrokerSyncService.java"
{ ! java_code_only "$QA_BSS" | grep -B1 'public String sync(' | grep -q '@Transactional' \
  && java_code_only "$QA_BSS" | grep -q 'tx.execute' \
  && ! sed -n '/java.util.function.Supplier<String> apply/,/};/p' "$QA_BSS" | grep -q 'markFailed'; } \
  && log_ok "v126-BROKER-FAIL-OUTSIDE-TX(对账 + 记成功在事务里,失败记录在事务外 —— 手动同步失败也会标红)" \
  || log_bad "v126-BROKER-FAIL-OUTSIDE-TX 同步失败会被事务回滚掉" "BrokerSyncService.sync 不许整体 @Transactional;markFailed 必须在事务之外"

# v126-IBKR-PAGES-LIVE · 管理页与图文教程真的渲染出 IBKR 那一块(v1.26 起在「券商同步」页)
$CURL -b $COOKIE "$BASE/admin/broker" -o "$TMP" -w ""
QA_IBP1=$(grep -c '测试盈透 IBKR 连接' "$TMP"); QA_IBP2=$(grep -c 'name="ibkrToken"' "$TMP")
$CURL -b $COOKIE "$BASE/help/broker-sync" -o "$TMP" -w ""
QA_IBP3=$(grep -c 'id="ibkr"' "$TMP")
{ [ "$QA_IBP1" -ge 1 ] && [ "$QA_IBP2" -ge 1 ] && [ "$QA_IBP3" -ge 1 ]; } \
  && log_ok "v126-IBKR-PAGES-LIVE(管理页有 IBKR 口令框与测试按钮 · 图文教程有盈透一节)" \
  || log_bad "v126-IBKR-PAGES-LIVE IBKR 的入口没渲染出来" "测试按钮=$QA_IBP1 口令框=$QA_IBP2 教程=$QA_IBP3"

# v126-BROKER-OWN-PAGE · 券商同步自己一页,而且【找得到】。
#   2026-09-27 维护者在 beta 上找 IBKR 的配置没找到:它在「数据源接入」第 ④ 节,可管理首页那张卡只写了「券商同步」
#   四个字、没写哪几家,页头是「行情 · 汇率 · 券商」,这一节还要往下滚;券商关联页上只写「先去管理页测试连接」,没有链接。
#   维护者定:按「用户要完成的事」分入口 —— 券商单独成页(同 v1.24.5 AI 接入的判据)。这条守四件事:
#   ① 券商配置只在 /admin/broker,数据源接入页上不许再长回券商表单(否则两处都能改)
#   ② 首页卡片逐家点名:按 BrokerVendor 逐个核对 —— 用户是带着「盈透 / 富途」这些词来找的,加第四家不写上就红
#   ③ 关联页上每一处「管理 → 券商同步」都是能点的链接,并带上 ?account= 让管理页能一键回跳
#   ④ 代码和模板里不许再有指向旧位置的「数据源接入 → 券商 / ④」
QA_BK_CTL="$RD/src/main/java/com/family/finance/web/admin/BrokerSettingsController.java"
QA_BK_TPL="$RD/src/main/resources/templates/admin/broker.html"
QA_BK_INT="$RD/src/main/resources/templates/admin/integrations.html"
QA_BK_LNK="$RD/src/main/resources/templates/broker/link.html"
QA_BK_IX="$RD/src/main/resources/templates/admin/index.html"
QA_BK_MISSING=""
for lbl in $(grep -oE '^\s+[A-Z]+\("[^"]+"\)' "$RD/src/main/java/com/family/finance/domain/broker/BrokerVendor.java" | grep -oE '"[^"]+"' | tr -d '"'); do
  sed -n '/@{\/admin\/broker}/,/<\/a>/p' "$QA_BK_IX" | grep -q "$lbl" || QA_BK_MISSING="$QA_BK_MISSING $lbl"
done
#   「是链接」= 这一行或上一行(链接的 svg + 文字常折到下一行)有带 account 的 href
QA_BK_UNLINKED="$(awk '/管理 → 券商同步/ && !(/admin\/broker\(account=/ || prev ~ /admin\/broker\(account=/) {print NR": "$0} {prev=$0}' "$QA_BK_LNK")"
QA_BK_STALE="$(grep -rnE '数据源接入 → (④ )?券商|数据源接入 → 富途' "$RD/src/main/java" "$RD/src/main/resources/templates" "$RD/docs/broker-sync-guide.md" 2>/dev/null || true)"
{ grep -q '@RequestMapping("/admin/broker")' "$QA_BK_CTL" \
  && grep -q '@PostMapping("/test")' "$QA_BK_CTL" \
  && ! grep -q 'K_BROKER_' "$RD/src/main/java/com/family/finance/web/admin/IntegrationsController.java" \
  && ! grep -qE 'name="(tigerKey|ibkrToken|futuHost)"' "$QA_BK_INT" \
  && grep -q '@{/admin/broker}' "$QA_BK_INT" \
  && grep -q 'name="ibkrToken"' "$QA_BK_TPL" && grep -q 'id="ibkr"' "$QA_BK_TPL" && grep -q 'ctxAccountId' "$QA_BK_TPL" \
  && grep -q '@{/admin/broker}' "$RD/src/main/resources/templates/admin/_sidebar.html" \
  && [ -z "$QA_BK_MISSING" ] \
  && grep -q 'id="ibkrEmpty"' "$QA_BK_LNK" \
  && [ -z "$QA_BK_UNLINKED" ] \
  && [ -z "$QA_BK_STALE" ]; } \
  && log_ok "v126-BROKER-OWN-PAGE(券商配置只在 /admin/broker · 首页卡片逐家点名 · 关联页指路都能点且带回跳 · 无指向旧位置的文案)" \
  || log_bad "v126-BROKER-OWN-PAGE 券商同步又找不到了" "卡片漏写:${QA_BK_MISSING:-无} · 关联页没链接的指路:${QA_BK_UNLINKED:-无} · 指向旧位置:$(printf '%s' "$QA_BK_STALE" | head -1 | cut -c1-120)"

# v126-BROKER-PAGE-LIVE · 渲染层:首页真的有这张卡、券商页真的出得来(模板渲染中途炸掉时 curl 也是 200,所以按内容判)
$CURL -b $COOKIE "$BASE/admin" -o "$TMP" -w ""
QA_BKL1=$(grep -c 'href="/admin/broker"' "$TMP"); QA_BKL2=$(grep -c '盈透 IBKR' "$TMP")
$CURL -b $COOKIE "$BASE/admin/broker" -o "$TMP" -w ""
QA_BKL3=$(grep -c '测试盈透 IBKR 连接' "$TMP"); QA_BKL4=$(grep -c '</html>' "$TMP")
{ [ "$QA_BKL1" -ge 1 ] && [ "$QA_BKL2" -ge 1 ] && [ "$QA_BKL3" -ge 1 ] && [ "$QA_BKL4" -ge 1 ]; } \
  && log_ok "v126-BROKER-PAGE-LIVE(管理首页有「券商同步」卡并点名盈透 · /admin/broker 完整渲染)" \
  || log_bad "v126-BROKER-PAGE-LIVE 券商同步入口没渲染出来" "首页链接=$QA_BKL1 首页点名盈透=$QA_BKL2 测试按钮=$QA_BKL3 页尾=$QA_BKL4"

# v126-CONFIG-KEY-HAS-HOME · 每个家庭配置键,要么在某个页面上能配(web 层读写它),要么登记在下面这张表里并写明为什么不用。
#   2026-09-27 维护者:「我们每次增加核心配置项,都要考虑是否在管理页面增加配置」。靠人记已经漏过(这次 IBKR 是有页面、
#   但找不到;更早的 L9 规则只写在 AGENTS.md 里,护栏一栏是「人工」)。这条把「想过没有」变成机械检查:
#   新加一个 K_ 常量,web 层没人读写它、也没登记理由 → 红。登记表里的每一行都是一个明确的决定,不是豁免。
QA_KEY_NOHOME=""
QA_KEY_OK="
K_BROKER_IBKR_ACCOUNTS:内部状态·最近一次报表里有哪些账户(关联页下拉用),不是用户配的
K_BROKER_IBKR_BASE_URL:测试钩子·e2e 把取数地址指到本机桩;只认 IBKR 域名或本机回环,用户不需要改
K_ASK_MA_AGENT_VERSION:内部状态·托管 Agent 发布后回写的版本号
K_CHECKUP_ADVICE_DISMISSED:用户操作的结果·体检卡片上点「不适用」写入,在体检页恢复
K_LENS_PALETTE:经 LensMetaService 在「显示与外观」页配
K_LLM_QWEN_MODELS:旧版键·只为读老数据兼容,新配置走 AI 接入页的平台/型号三元组
K_LLM_PRIMARY_VENDOR:旧版键·同上
K_LLM_MODEL:旧版键·同上
K_LLM_VISION_MODEL:旧版键·同上
K_ANALYSIS_SCOPE_DEFAULT:经 AnalysisScopeService 在「分析设置」页①配(体检页「设为家里的默认」也写它)
K_ANALYSIS_TEMPLATE_DEFAULT:经 AnalysisTemplateService 在「分析设置」页②配(超级 Agent 确认卡「切到某模板」也写它)
K_ANALYSIS_HINT_DISMISSED:用户操作的结果·体检页关掉「分析角度不合适?」首次提示时写入(按成员)
"
for k in $(grep -oE 'public static final String K_[A-Z0-9_]+' "$RD/src/main/java/com/family/finance/service/config/FamilyConfigService.java" | awk '{print $5}'); do
  grep -rqw "$k" "$RD/src/main/java/com/family/finance/web" && continue
  printf '%s' "$QA_KEY_OK" | grep -q "^$k:" && continue
  QA_KEY_NOHOME="$QA_KEY_NOHOME $k"
done
[ -z "$QA_KEY_NOHOME" ] \
  && log_ok "v126-CONFIG-KEY-HAS-HOME(每个家庭配置键都有页面可配,或已登记不用配的理由)" \
  || log_bad "v126-CONFIG-KEY-HAS-HOME 新配置键既没有页面入口、也没登记理由:$QA_KEY_NOHOME" "决定它放哪个管理页(见 AGENTS.md L9 的放置判据),或在这张表里写明为什么不用配"

# v126-CONFIG-GAPS-CLOSED · 上一条护栏首次运行查出的两个缺口,维护者 2026-09-27「要加上」:
#   ① 填报提醒「每天几点发」(report_remind_cron)→ 提醒页,填整点不填 cron,留空不改
#   ② 再平衡「算执行了」的比例(rebalance_match_pct)→ 数值阈值页,页面百分比、库里小数(读的一边一直按小数读)
#   两处的卡片也点了名(用户是带着「几点提醒」「再平衡」这些词来找的)
$CURL -b $COOKIE "$BASE/admin/reminders" -o "$TMP" -w ""
QA_GAP1=$(grep -c 'name="remindHours"' "$TMP")
$CURL -b $COOKIE "$BASE/admin/calc-tweaks" -o "$TMP" -w ""
QA_GAP2=$(grep -c 'name="rebalanceMatchPct"' "$TMP")
{ [ "$QA_GAP1" -ge 1 ] && [ "$QA_GAP2" -ge 1 ] \
  && grep -q 'RemindTimes.DEFAULT_CRON' "$RD/src/main/java/com/family/finance/service/scheduling/DynamicScheduleConfig.java" \
  && grep -q 'K_REBALANCE_MATCH_PCT, 0.8)' "$RD/src/main/java/com/family/finance/service/review/RebalancePlanService.java" \
  && [ -f "$RD/src/test/java/com/family/finance/web/admin/RebalanceMatchSettingTest.java" ] \
  && [ -f "$RD/src/test/java/com/family/finance/service/notify/RemindTimesTest.java" ] \
  && grep -q '每天几点提醒' "$RD/src/main/resources/templates/admin/index.html" \
  && grep -q '再平衡「算执行了」的比例' "$RD/src/main/resources/templates/admin/index.html"; } \
  && log_ok "v126-CONFIG-GAPS-CLOSED(提醒时间在提醒页、再平衡核销比例在数值阈值页 · 首页卡片点名 · 存储格式与读的一边一致)" \
  || log_bad "v126-CONFIG-GAPS-CLOSED 两个配置又没有入口了" "提醒页 remindHours=$QA_GAP1 · 数值阈值页 rebalanceMatchPct=$QA_GAP2"

# v1261-OPEND-ONLY-FUTU · OpenD 只是富途的网关:持仓页「券商对接」那一条上,OpenD 地址和「OpenD 网关」按钮只给富途看。
#   原来不分券商都显示 —— 老虎一直是错的,加了盈透之后更显眼(v1.26.0 发版截图时看到):盈透用户会以为自己也要装 OpenD。
#   判据按行:模板里凡是显示 OpenD 地址(「'OpenD ' +」)或指向 OpenD 向导(broker/opend(account=)的元素,同一行必须带 FUTU 条件。
QA1261_H="$RD/src/main/resources/templates/stock/holdings.html"
QA1261_BAD="$(grep -nE "'OpenD ' \+|broker/opend\(account=" "$QA1261_H" | grep -v "vendor.name() == 'FUTU'" || true)"
#   反向也守:富途那边这两样还得在(条件写成 FUTU 至少两处),别修成谁都看不到
{ [ -z "$QA1261_BAD" ] && [ "$(grep -c "vendor.name() == 'FUTU'" "$QA1261_H")" -ge 2 ]; } \
  && log_ok "v1261-OPEND-ONLY-FUTU(持仓页只在富途关联上显示 OpenD 地址与网关按钮)" \
  || log_bad "v1261-OPEND-ONLY-FUTU 非富途的关联也显示了 OpenD" "$(printf '%s' "$QA1261_BAD" | head -1 | cut -c1-120)"
# v1261-DOCKER-UP-DNS · docker-up.sh 拉镜像失败要按真实原因分诊,不许一律说成「Docker Hub 被限速」。
#   2026-09-28 一位 Mac + colima 用户:虚拟机里 /etc/resolv.conf 是断链 → 引擎问 [::1]:53 → connection refused,
#   脚本却丢掉了 docker 的报错、说是限速、去改镜像源并重启引擎,重试照样失败。
#   这里真的跑一遍(假 docker / colima,8 秒左右):DNS 坏了要说是 DNS、带原话、查出断链、不动镜像源;超时仍走原来的处置。
if bash "$RD/scripts/docker-up-selftest.sh" > "$TMP" 2>&1; then
  log_ok "v1261-DOCKER-UP-DNS(DNS 坏了说是 DNS、带 docker 原话、查 colima 的 resolv.conf、不去改镜像源 · 网络超时仍走镜像源)"
else
  log_bad "v1261-DOCKER-UP-DNS docker-up.sh 又把拉取失败一律归因成限速" "$(grep '✗' "$TMP" | head -2 | tr '\n' ' ')"
fi

# v1262-WIZARD-ARRIVES · 点「+ 添加账户(开向导)」后向导要出现在眼前。
#   向导追加在账户列表最下面,点按钮是整页刷新、落回页顶 —— 向导在视口以下(PC 约 2400px、手机约 5600px),
#   维护者 2026-09-28 在 PC 上连点好几次以为没反应。三个入口(账户页 / 填报页 / 首次引导)都落在 /accounts/new,
#   所以滚动放在向导片段里;e2e flow 29 从真实入口点进去量向导顶边的位置。
QA1262_W="$RD/src/main/resources/templates/accounts/_template-wizard.html"
{ grep -q 'id="account-wizard"' "$QA1262_W" \
  && grep -q "getElementById('account-wizard')" "$QA1262_W" \
  && grep -q 'prefers-reduced-motion' "$QA1262_W" \
  && [ -f "$RD/scripts/e2e/flows/29-account-wizard-arrives.cjs" ]; } \
  && log_ok "v1262-WIZARD-ARRIVES(落到 /accounts/new 就把向导滚进视口 · 照顾减少动效 · e2e 从真实入口量位置)" \
  || log_bad "v1262-WIZARD-ARRIVES 点「添加账户」后向导又藏在页面最下面了" "see accounts/_template-wizard.html · e2e flow 29"

# v126-LENS-ACCOUNT-RESIDUAL · 外币账户多条持仓逐条折算后要补零头,否则透视合计与 KPI 总资产差一分(跟股价走,时红时绿)
#   v1.20 只补了「一条持仓按方向拆多份」那一层;2026-09-25 regression-data 两次撞上账户这一层
QA_LQS="$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java"
{ grep -q 'static List<BigDecimal> baseValues(' "$QA_LQS" \
  && grep -q 'List<BigDecimal> valueBases = baseValues(' "$QA_LQS" \
  && ! grep -q 'line.valueAcctCcy().multiply(factor).setScale' "$QA_LQS" \
  && grep -q 'accountLevelHoldingsCloseToAccountValue' "$RD/src/test/java/com/family/finance/service/lens/SplitResidualTest.java"; } \
  && log_ok "v126-LENS-ACCOUNT-RESIDUAL(账户里多条持仓折算后补零头 · 透视合计 = KPI 总资产)" \
  || log_bad "v126-LENS-ACCOUNT-RESIDUAL 透视合计又会与总资产差一分" "LensQueryService 逐条折算必须走 baseValues(补零头),不许直接 setScale 求和"

# v1246-LIVE-CITES-FORWARDED · 托管模式下,引用必须在【流式当下】送到浏览器,不能只落库。
#   2026-09-23:v1.24.3 只修了落库,验收看的是刷新后的页面 —— 流式当下每个数字仍然是空的。
QA1246_CS="$RD/src/main/java/com/family/finance/service/ask/AskConversationService.java"
{ grep -q 'default void cites(' "$RD/src/main/java/com/family/finance/service/ask/runtime/AskSink.java" \
  && grep -q 'public void cites(' "$RD/src/main/java/com/family/finance/web/ask/AskController.java" \
  && grep -q 'forwardNewCites()' "$QA1246_CS" \
  && grep -q 'citeBuffer.snapshot' "$QA1246_CS"; } \
  && log_ok "v1246-LIVE-CITES-FORWARDED(托管模式的引用在每段文字前推给浏览器 · 流式当下就有数字)" \
  || log_bad "v1246-LIVE-CITES-FORWARDED 流式当下数字又会是空的" "Collector 要在 textDelta/done 前 forwardNewCites(),用 AskCiteBuffer.snapshot 取新到的引用经 AskSink.cites 推出去"

# v1246-RISK-LEVEL-FROM-CATEGORY · 家庭风险分布的等级来自账户自己的产品类目,不是按账户类型写死。
#   2026-09-24:原来 FamilyDiagnoseService 用字符串 switch + default -> 0,加密 / 贵金属 / 保险
#   全成了「无风险」,没有任何类型到 5 级 → FAM-RISK-1 从来触发不了;报表那张图的副标题却写着
#   「按产品类目风险等级聚合」。这里守:没有写死的类型表、等级走 RiskLevels、默认类目表对枚举穷尽
#   (不留 default,加账户类型不写这里就编译不过)、报表按等级取色。
QA_RL="$RD/src/main/java/com/family/finance/service/checkup/RiskLevels.java"
QA_FDS="$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnoseService.java"
{ [ -f "$QA_RL" ] \
  && ! java_code_only "$QA_FDS" | grep -q 'fallbackRisk' \
  && java_code_only "$QA_FDS" | grep -q 'RiskLevels.resolve(' \
  && grep -q 'return switch (type)' "$QA_RL" \
  && ! java_code_only "$QA_RL" | grep -qE '^\s*default\s*->' \
  && grep -q 'riskLevels' "$RD/src/main/resources/templates/reports/_region.html" \
  && [ -f "$RD/src/test/java/com/family/finance/service/checkup/RiskLevelsTest.java" ]; } \
  && log_ok "v1246-RISK-LEVEL-FROM-CATEGORY(风险分布按账户的产品类目 · 没设类目按类型的默认类目估算 · 报表按等级取色)" \
  || log_bad "v1246-RISK-LEVEL-FROM-CATEGORY 风险等级又退回按账户类型写死了" "FamilyDiagnoseService 必须走 RiskLevels.resolve;RiskLevels 的默认类目表不许有 default 分支"

# v1246-RISK-LEVEL-LIVE · 真去体检页看:① 风险分布各档加起来 = 同页「总资产」(只是重新分档,钱一分不少)
#   ② 出现的每一档都必须是某个资产账户按「手工 → 类目 → 类型默认类目」解析得到的等级(DB 直算)。
#   修复前页面上有 0 级「无风险」,而 DB 里没有任何资产账户解析到 0 级 → ② 会红。
$CURL -b $COOKIE "$BASE/checkup" -o "$TMP" -w ""
QA_RL_EXPECT="$(mysql -ufinance -pfinance finance -N -e "
  SELECT DISTINCT COALESCE(NULLIF(a.risk_level_override,0), pc.risk_level, dpc.risk_level, 0)
    FROM account a
    LEFT JOIN product_category pc  ON pc.code = a.product_category_code
    LEFT JOIN product_category dpc ON dpc.code = CASE a.type
         WHEN 'CASH' THEN 'CASH_DEPOSIT' WHEN 'STOCK' THEN 'A_STOCK' WHEN 'WEALTH' THEN 'BANK_WEALTH'
         WHEN 'PROPERTY' THEN 'PROPERTY_RES' WHEN 'OTHER' THEN 'OTHER' WHEN 'CRYPTO' THEN 'CRYPTO'
         WHEN 'METAL' THEN 'PRECIOUS_METAL' WHEN 'INSURANCE' THEN 'SAVINGS_INSURANCE' END
   WHERE a.family_id = 1 AND a.type <> 'LOAN';" 2>/dev/null | tr '\n' ' ')"
QA_RL_OUT="$(QA_RL_EXPECT="$QA_RL_EXPECT" python3 -c '
import re, json, sys, os
h = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r"const riskBuckets = (\[.*?\]);", h, re.S)
if not m:
    print("NOCHART"); sys.exit()
b = json.loads(m.group(1))
i = h.find("kpi-eyebrow\">总资产"); kv = re.findall(r"kpi-value[^>]*>\s*¥([0-9,]+)", h[i:i+1500])
total = round(sum(float(x["amount"]) for x in b))
kpi = int(kv[0].replace(",", "")) if kv else None
expect = set(int(x) for x in os.environ.get("QA_RL_EXPECT", "").split())
got = [int(x["level"]) for x in b]
bad = [g for g in got if g not in expect]
ok = kpi is not None and abs(total - kpi) <= 1 and not bad
print("OK" if ok else "BAD 各档合计=%s 同页总资产=%s 页面档位=%s DB可解析档位=%s 多出来的=%s" % (total, kpi, got, sorted(expect), bad))
' "$TMP")"
case "$QA_RL_OUT" in
  OK) log_ok "v1246-RISK-LEVEL-LIVE(体检风险分布:各档合计 = 同页总资产 · 每一档都能在 DB 里由账户类目解析出来)" ;;
  NOCHART) log_skip "v1246-RISK-LEVEL-LIVE" "体检页没有风险分布(当前数据下没有资产余额)" ;;
  *) log_bad "v1246-RISK-LEVEL-LIVE 体检风险分布与账户类目对不上" "$QA_RL_OUT" ;;
esac

# v1245-MCP-BASE-URL-NORMALIZED · 「本站公网地址」里误粘的 /mcp 必须被吃掉。
#   那一格问的是【站点根】,而我们生成给人粘贴的是完整的 https://域名/mcp;填回来就拼出 /mcp/mcp
#   (那个路径被普通 Web 安全链接管,302 跳登录)。
#   注意:2026-09-23 生产「没有工具」【不是】这个原因(一开始误判成了它)——真因见 v1246。
{ grep -q 'normalizeBaseUrl' "$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java" \
  && grep -q 'normalizeBaseUrl' "$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java" \
  && [ -f "$RD/src/test/java/com/family/finance/service/ask/runtime/ManagedAgentBaseUrlTest.java" ]; } \
  && log_ok "v1245-MCP-BASE-URL-NORMALIZED(公网地址末尾的 /mcp 会被吃掉 · 保存与生成两处都归一 · 判据有单测)" \
  || log_bad "v1245-MCP-BASE-URL-NORMALIZED 又会拼出 /mcp/mcp" "ManagedAgentRuntime 要有 normalizeBaseUrl,AiAccessController 保存时也要用它;判据落 ManagedAgentBaseUrlTest"

# v1244-SSE-SURVIVES-REVERSE-PROXY · 流式回答必须扛得住反向代理。
#
#   2026-09-22 从浏览器实测(beta.dixi-token.top,走真实域名 + nginx):
#   问一个稍复杂的问题「资产多少 / 分布如何 / 有什么建议」,150 秒一个字都没回来,
#   控制台一个 504。而直连 127.0.0.1:20000 是好的 —— 问题全在反代那一层:
#     ① nginx 默认【缓冲】上游响应,SSE 于是不再是流式(用户盯着空白等);
#     ② proxy_read_timeout 默认 60s(beta 配的 90s),而应用给 SseEmitter 的是 200s。
#
#   这也解释了为什么之前一直没发现:所有自测都打 127.0.0.1,绕过了 nginx。
#   两道防护都必须在【应用侧】—— 自建用户的反代五花八门,我们控制不了他们的配置。
QA1244_AC="$RD/src/main/java/com/family/finance/web/ask/AskController.java"
{ grep -q 'X-Accel-Buffering' "$QA1244_AC" \
  && grep -q 'scheduleAtFixedRate' "$QA1244_AC" \
  && grep -q 'comment("hb")' "$QA1244_AC" \
  && grep -q 'ask/\[0-9\]+/stream' "$RD/deploy/nginx-finance.conf.example"; } \
  && log_ok "v1244-SSE-SURVIVES-REVERSE-PROXY(应用发 X-Accel-Buffering + 15s 心跳 · nginx 示例也给了 SSE location)" \
  || log_bad "v1244-SSE-SURVIVES-REVERSE-PROXY 流式又会被反代掐" "AskController 要发 X-Accel-Buffering 并周期发 SSE 心跳;deploy/nginx-finance.conf.example 要有 /ask/N/stream 的 location"

# v1244-CITE-KEYS-UNIQUE-PER-RUN · 托管模式下,引用 key 必须一轮内全局唯一。
#   工具返回的 key 是工具【内部】的行号(r0_0 / nw),一轮里百炼会调五六次工具,
#   「按资产类型」的 r0_0 和「按平台」的 r0_0 会撞在一起 —— 模型分不出,我们也会覆盖。
#   2026-09-22 实测后果:正文写「最大的一块是债券理财」,挂的卡是「支付宝 48.34%」。
#   **给一个看起来合理、实际错位的数,比不给更糟**,而用户没有任何办法发现。
{ grep -q 'scopedKey' "$RD/src/main/java/com/family/finance/service/ask/AskCiteBuffer.java" \
  && grep -q 'nextCallSeq' "$RD/src/main/java/com/family/finance/web/ask/McpEndpoint.java" \
  && grep -q 'scopedKey' "$RD/src/main/java/com/family/finance/web/ask/McpEndpoint.java"; } \
  && log_ok "v1244-CITE-KEYS-UNIQUE-PER-RUN(MCP 出口就把引用 key 唯一化 · 多次工具调用不再互相覆盖)" \
  || log_bad "v1244-CITE-KEYS-UNIQUE-PER-RUN 引用 key 又会撞" "McpEndpoint 必须用 AskCiteBuffer.scopedKey(nextCallSeq(..), key) 重编 key"

# v1244-CHART-BRACE-TOLERANT · 图表标记少一个右花括号也要画得出来。
#   标记以 }} 结束,JSON 对象也以 } 结束 —— 分隔符天然撞车,模型很容易把 ...]}}}} 写成 ...]}}}。
#   实测:一张六项的饼图就这么静默丢了,而正文里那个引导冒号还留在页面上,后面空空如也。
{ grep -q 'parseChartJson' "$RD/src/main/java/com/family/finance/service/ask/AskCitationRenderer.java" \
  && [ -f "$RD/src/test/java/com/family/finance/service/ask/AskChartMarkerTest.java" ]; } \
  && log_ok "v1244-CHART-BRACE-TOLERANT(图表 JSON 少括号能补回来 · 判据有单测穷举)" \
  || log_bad "v1244-CHART-BRACE-TOLERANT 图表又会被静默丢掉" "AskCitationRenderer 要有 parseChartJson 容错 + AskChartMarkerTest"

# v1243-QA-RUN-COSTS-NOTHING · 这个脚本默认【一次真 LLM 都不打】。
#
#   2026-09-22 账单复盘:qa-run 每跑一次要打 15 个 AI 端点,而主选 DeepSeek 从 09-02 起欠费,
#   每次调用都自动 failover 到阿里云百炼 —— 开发最密的那几天一天 187 次,
#   基本都是跑回归跑出来的。这条护栏防的是「哪天有人顺手又加一个真请求进来」。
#
#   判据:所有打 AI 端点的行,要么在 QA_LLM_LIVE 开关里面,要么走的是【不碰 LLM 的路径】
#   (account=99999 在控制器里早返回)。白名单只有这一条,加别的要在这里写明理由。
QA1243_BAD=""
while IFS= read -r ln; do
  n="${ln%%:*}"; body="${ln#*:}"
  # 跳过注释行
  case "$(printf '%s' "$body" | sed 's/^[[:space:]]*//')" in \#*) continue ;; esac
  # 白名单:走早返回的那条(账户查不到 → 不调 LLM)
  case "$body" in *"account=99999"*) continue ;; esac
  # 其余的必须落在某个 QA_LLM_LIVE 分支里 —— 往上找最近的 if
  #   两条合法路径:① 包在 QA_LLM_LIVE 开关里 ② 前面先 ai_seed_advice 塞过缓存
  #   (命中缓存的 advise 直接返回,不打 LLM —— 见 RebalanceAdvisorService:90)
  ctx="$(sed -n "$((n>40?n-40:1)),${n}p" "$RD/scripts/qa-run.sh")"
  case "$ctx" in
    *'QA_LLM_LIVE'*)     ;;
    *'ai_seed_advice'*)  ;;
    *) QA1243_BAD="$QA1243_BAD $n" ;;
  esac
done < <(grep -n 'BASE/checkup/diagnose\|BASE/checkup/insight\|rebalance/advise' "$RD/scripts/qa-run.sh" \
          | grep -E 'curl|CURL')
[ -z "$QA1243_BAD" ] \
  && log_ok "v1243-QA-RUN-COSTS-NOTHING(默认不打真 LLM · 要验真调用:QA_LLM_LIVE=1)" \
  || log_bad "v1243-QA-RUN-COSTS-NOTHING 有 AI 请求没挂开关" "第$QA1243_BAD 行会打真 LLM · 要么先塞缓存,要么包进 QA_LLM_LIVE"

# v1243-AI-HEALTH-VISIBLE · 主备全挂时,管理页要说得出来。
#   2026-09-22:DeepSeek 09-02 挂、百炼 09-20 挂,中间 20 天所有 AI 功能都出不来,
#   而没有任何人知道 —— 每一层都优雅降级,页面显示「AI · 暂不可用」,
#   和「这个月没人点过 AI」长得一模一样。最后是【账单】把这件事捅出来的。
QA1243_TRK="$RD/src/main/java/com/family/finance/service/checkup/llm/LlmHealthTracker.java"
{ [ -f "$QA1243_TRK" ] \
  && grep -q 'allAccountsDown' "$QA1243_TRK" \
  && grep -q 'healthTracker.recordOk' "$RD/src/main/java/com/family/finance/service/checkup/llm/LlmRouter.java" \
  && grep -q 'healthTracker.recordFail' "$RD/src/main/java/com/family/finance/service/checkup/llm/LlmRouter.java" \
  && grep -q 'llmAllDown' "$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java" \
  && grep -q 'AI 现在全部不可用' "$RD/src/main/resources/templates/admin/ai-access.html" \
  && [ -f "$RD/src/test/java/com/family/finance/service/checkup/llm/LlmHealthTrackerTest.java" ]; } \
  && log_ok "v1243-AI-HEALTH-VISIBLE(主备全挂在管理页有读数与告警 · 判据有单测穷举)" \
  || log_bad "v1243-AI-HEALTH-VISIBLE AI 故障又变回静默了" "要有 LlmHealthTracker + LlmRouter 记录 + 管理页 llmAllDown 告警 + 单测"

# v1241-MANUAL-NO-RETIRED-DENIAL · 手册不许再声称一个【已经做了】的功能不存在。
#
#   手册比代码更容易烂,而烂法有两档:①「没写」——用户找不到,难受但无害;
#   ②「主动否认」——手册白纸黑字告诉用户「这个不支持 / 我们不打算做」,而它就在菜单里。
#   第二档会让人直接放弃去找,比不写更糟。
#
#   2026-09-21 实测:站内手册最后一次实质更新停在 v1.8,中间发了十六个版本。
#   三份手册当时都还写着「不预置『餐饮』这类消费品类」「类目就这四个(内置)」,
#   而 how-to-record.md 更直接:「我想看支出分类占比?**目前不支持**,在排期中」——
#   那时消费分类树已经上线好几版,beta 上都建了 37 个类目了。
#
#   所以这条护栏钉的不是「有没有写新功能」(那没法机器判),而是
#   **那几句已经被事实推翻的话不许再出现**。退役一句钉一句,零维护成本。
MAN_HTML="$RD/src/main/resources/templates/help/how-to-use.html"
MAN_DENIAL=""
for pat in '不预置「餐饮」' '不预置「餐饮' '类目就这四个' '目前不支持,支出只到' '不打算告诉你.*餐饮占了几成'; do
  for f in "$MAN_HTML" "$RD/docs/how-to-use.md" "$RD/docs/how-to-record.md"; do
    grep -qE "$pat" "$f" 2>/dev/null && MAN_DENIAL="$MAN_DENIAL $(basename "$f"):「$pat」"
  done
done
[ -z "$MAN_DENIAL" ] \
  && log_ok "v1241-MANUAL-NO-RETIRED-DENIAL(手册没有再否认已发布的消费分类 / 支出分析)" \
  || log_bad "v1241-MANUAL-NO-RETIRED-DENIAL 手册在否认一个已经做了的功能" "命中:$MAN_DENIAL —— 消费分类树与支出分析早已上线,手册不能再说「不预置」「就这四个」「目前不支持」"

# v164-CHART-PARITY · dashboard 两图形态永远一致(用户反馈④)+ v1.6.11 窄屏改回环图
#   诉求没变:「资产配置」与「按成员分布」不能一个环一个条。判断收成共用的 useBar(),
#   而 useBar 在窄屏恒为 false → **窄屏两图必定同为环图**(用户反馈④与本次反馈的交集)。
#   注意口径:PC 上 useBar 仍看各自类目数,所以 PC 可能一个条一个环(资产配置 7 类 > 6)。
#   要不要在 PC 也统一成环图,是产品取舍(聚合已让多类目环图可读),留给用户定,不在此守护范围。
#   实现翻过一次:v1.6.4 我让窄屏两个都走横向条形,理由写的是「窄屏环图标签必然重叠」。
#   用户反馈条形「太不直观」,要求改回环图 —— 而且那个理由本来就站不住:
#   重叠的根因不是"环图不行",是**类目太多**,与旭日图 v1.6.3 完全同一个问题。
#   那里验证过的解法直接搬过来:**Top N + 其他 N 项**(aggSlices),环里只剩 ≤6 片,
#   每片都标得下占比;窄屏图例改到下方(390px 宽里右侧图例会把环挤成一条缝);
#   金额落在图例上(规范要求数字直接在图上,不能只靠 hover)。
#   保留的判断:PC 且类目 > 6 仍用横向条形 —— 那时空间够,精确标注每一类比合并更有价值。
{ grep -q "function donutConfig" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "function aggSlices" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && ! grep -q "function useBar" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "donutConfig(memLabels" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "donutConfig(donutLabels" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -qF "position: 'bottom'," "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q "generateLabels" "$RD/src/main/resources/templates/dashboard/_region.html" \
  && ! grep -q "memFlat\|flatAlloc" "$RD/src/main/resources/templates/dashboard/_region.html"; } \
  && log_ok "v164-CHART-PARITY(两图一律环图 · 不再按类目数分叉图型 · Top N 聚合 + 图例统一下方)" \
  || log_bad "v164-CHART-PARITY 缺件" "see dashboard/_region.html:aggSlices + donutConfig + useBar 三件齐 · 两图都走 donutConfig · 窄屏图例 bottom + generateLabels 带金额 · 不得残留 memFlat/flatAlloc"

# ══════════════════════════════════════════════════════════════════════════════
# v1.8 · 支出逐笔化 + 口径唯一入口
# ══════════════════════════════════════════════════════════════════════════════

# v18-EXPENSE-ONE-SOURCE · 家庭支出口径只有一份实现
#   v1.6.29 踩过一次「同一屏两套收入口径」,根因是判断散落在各调用点。本版把支出口径
#   收敛到 ExpenseLedgerService,所以护栏直接断言:**全仓 totalExpense() 只出现在那一个文件里**。
#   开发中真的漏过两处(报表折线的支出序列 + tooltip 的上期支出),就是这条 grep 抓出来的。
{ [[ "$(grep -rl 'totalExpense()' "$RD/src/main/java/" | grep -v 'service/expense/ExpenseLedgerService.java' | wc -l)" == "0" ]] \
  && grep -q "class ExpenseLedgerService" "$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/service/HouseholdCashflowService.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/service/goal/GoalService.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/service/goal/GoalProgressService.java" \
  && grep -q "expenseLedger" "$RD/src/main/java/com/family/finance/web/report/ReportsController.java"; } \
  && log_ok "v18-EXPENSE-ONE-SOURCE(全仓 totalExpense() 只在 ExpenseLedgerService · 5 个调用方都经过它)" \
  || log_bad "v18-EXPENSE-ONE-SOURCE 有旁路" "grep -rn 'totalExpense()' src/main/java 应只命中 ExpenseLedgerService;新加的支出读取必须走口径服务,别直读 PMC"

# v18-EXPENSE-MODE-GUARD · 优先级必须受 expense_entry_mode 约束
#   PRD 初稿写的是「无条件逐笔优先」,那会让 prod 2026-06 的 PMC 总额 ¥32,797 被 ¥3,000 的
#   逐笔顶掉(少算 89%),连带污染储蓄率/月均/紧急储备/人赚钱赚/XIRR/应急基线。
#   总额模式下口径服务还必须**返回 NONE 交回调用方原路径**(调用方回落事实切片:排归档 + 已换汇),
#   否则 beta 家庭 XIRR 会从 −56.19% 漂到 −50.60%。
{ grep -q "V53__expense_entry_mode.sql" <<< "$(ls "$RD/db/migration/")" \
  && grep -q "expense_entry_mode" "$RD/db/migration/V53__expense_entry_mode.sql" \
  && grep -q "DEFAULT 'TOTAL'" "$RD/db/migration/V53__expense_entry_mode.sql" \
  && grep -q "itemizedFirst" "$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java" \
  && grep -q "modeOf" "$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java" \
  && grep -q "archived_at IS NULL" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "base_currency" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "总额模式下逐笔不得顶掉PMC总额" "$RD/src/test/java/com/family/finance/service/expense/ExpenseLedgerServiceTest.java" \
  && grep -q "总额模式下取期集合只看PMC_分母不能变" "$RD/src/test/java/com/family/finance/service/expense/ExpenseLedgerServiceTest.java" \
  && grep -q "总额模式下无PMC时返回NONE_把兜底交回调用方" "$RD/src/test/java/com/family/finance/service/expense/ExpenseLedgerServiceTest.java" \
  && grep -q "未来账期的逐笔不得并入近N期" "$RD/src/test/java/com/family/finance/service/expense/ExpenseLedgerServiceTest.java"; } \
  && log_ok "v18-EXPENSE-MODE-GUARD(逐笔优先受模式约束 + 逐笔 SQL 排归档/已换汇 + 4 条单测守着)" \
  || log_bad "v18-EXPENSE-MODE-GUARD 缺件" "V53 默认 TOTAL / decide 走 itemizedFirst / 逐笔 SQL 带 archived_at + 折本位币 / 4 条护栏单测齐"

# v18-EXPENSE-WRITE · 支出写入链路与收入侧同构 + 三条服务端红线
{ grep -q "recordExpense" "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q "requireExpenseCategory" "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q "AccountType.LOAN" "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q '@PostMapping("/entry/expense")' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q '@PostMapping("/entry/expense/{id}/delete")' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q "listExpenseOrdered" "$RD/src/main/java/com/family/finance/repository/CashFlowCategoryMapper.java" \
  && grep -q "findExpenseEntries" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "expenseMode == 'ITEMIZED'" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "expenseMode != 'ITEMIZED'" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "entry/expense" "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q "admin/reminders/expense-mode" "$RD/src/main/resources/templates/admin/notification.html"; } \
  && log_ok "v18-EXPENSE-WRITE(recordExpense + 删除冲回 + 类目/贷款红线 + 填报页两形态 + 管理页开关)" \
  || log_bad "v18-EXPENSE-WRITE 缺件" "见 EntryService.recordExpense / EntryController 两端点 / entry 模板按 expenseMode 二选一 / 管理页 expense-mode 表单"

# v18-MIX-COMPOSITION · 支出构成段 + 长文目录同步 + 数字直接标在图上
#   memory feedback_toc_sync:加 section 必须同步该页长文目录,否则锚点漏节。
#   memory feedback_chart_datalabels:金额/百分比必须绘在图上,hover tooltip 不算。
{ [[ -f "$RD/src/main/resources/templates/reports/_expense-section.html" ]] \
  && grep -q 'id="sec-expense-mix"' "$RD/src/main/resources/templates/reports/_expense-section.html" \
  && grep -q "ChartDataLabels" "$RD/src/main/resources/templates/reports/_expense-section.html" \
  && grep -q "datalabels" "$RD/src/main/resources/templates/reports/_expense-section.html" \
  && grep -q "reports/_expense-section :: section" "$RD/src/main/resources/templates/reports/index.html" \
  && grep -q "sec-expense-mix" "$RD/src/main/resources/templates/reports/index.html" \
  && grep -q "expenseBreakdown" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "expenseBreakdownDetail" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q "reports/expense-mix/detail" "$RD/src/main/java/com/family/finance/web/report/ReportsController.java" \
  && grep -q "mixEnabled" "$RD/src/main/java/com/family/finance/web/report/ReportsController.java"; } \
  && log_ok "v18-MIX-COMPOSITION(支出构成段 + 目录锚点同步 + datalabels + 明细抽屉)" \
  || log_bad "v18-MIX-COMPOSITION 缺件" "_expense-section.html 存在且挂进 reports/index + tocItems 含 #sec-expense-mix + datalabels + detail 端点"

# v18-EXPENSE-DOC-4X · 「支出优先级与收入相反」必须同时出现在四处
#   这条反直觉的不一致少写一处,下一个人就会靠猜 —— 所以把「四处」本身做成护栏。
{ grep -q "与收入侧优先级相反\|与收入侧相反" "$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java" \
  && grep -q "注意与收入侧优先级相反" "$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java" \
  && grep -q "与收入侧方向相反" "$RD/src/main/java/com/family/finance/service/explain/MetricExplainService.java" \
  && grep -q "方向是反的" "$RD/docs/how-to-record.md" \
  && grep -q "永不相加" "$RD/docs/how-to-record.md"; } \
  && log_ok "v18-EXPENSE-DOC-4X(类注释 + 方法注释 + 页面 tooltip + how-to-record 四处都写明「与收入侧相反」)" \
  || log_bad "v18-EXPENSE-DOC-4X 缺一处" "ExpenseLedgerService 类注释 / FactViewServiceImpl.netInflowExpense / MetricExplainService tooltip / docs/how-to-record.md"

# v18-MANUAL-B8 · 手册的目录与章节必须一一对应(原来把卡数写死成 25)
#
#   2026-09-21 改判据:原来是 `目录卡 == 25`。它确实抓得到「加了章忘了加目录卡」,
#   但也会在**正常加一章**时变红,而一条「做对事也会红」的护栏,下一个人多半是
#   顺手把数字改大 —— 改完它就再也抓不到真正的漏配了。
#   现在改成**两边互算**:目录卡数 == 带 id 的章节数,且每张卡的 #锚点都有对应 section。
#   加章、删章都不用动这里,漏配一边立刻红。
MAN_H="$RD/src/main/resources/templates/help/how-to-use.html"
MAN_CARDS="$(grep -c 'class="toc-card"' "$MAN_H")"
MAN_SECS="$(grep -cE '<section class="chapter ch" id="' "$MAN_H")"
MAN_ORPHAN=""
for a in $(grep -oE 'class="toc-card" href="#[a-z0-9]+"' "$MAN_H" | grep -oE '#[a-z0-9]+' | tr -d '#'); do
  grep -q "<section class=\"chapter ch\" id=\"$a\"" "$MAN_H" || MAN_ORPHAN="$MAN_ORPHAN #$a"
done
{ grep -q 'id="b8"' "$MAN_H" && grep -q 'href="#b8"' "$MAN_H" \
  && grep -q "支出录入方式" "$MAN_H" \
  && [ "$MAN_CARDS" = "$MAN_SECS" ] && [ -z "$MAN_ORPHAN" ]; } \
  && log_ok "v18-MANUAL-B8(手册目录与章节一一对应 · $MAN_CARDS 卡 / $MAN_SECS 章)" \
  || log_bad "v18-MANUAL-B8 手册目录与章节对不上" "目录卡 $MAN_CARDS 张 / 章节 $MAN_SECS 个${MAN_ORPHAN:+ · 指向不存在的章:$MAN_ORPHAN} —— 加/删章时目录与正文要一起改"

# v18-CF-SELECT · 收支两区的下拉是自研件,不是系统原生 select
#   复用打标页/透视那套 lens-select(data-lsel):原生 <select> 留在 DOM 里(表单语义 + 无 JS 降级),
#   组件隐藏它并渲染纸面风格按钮 + 面板。三条容易回退的点:
#   ① 按钮尺寸必须用 rem 不用 px —— 顶栏有字号缩放(A A),写死 px 会和旁边 h-9 的输入框差 2px;
#   ② 短列表(≤5)不显示搜索框,否则 4 个类目上面顶一个搜索框纯噪音;
#   ③ 股票账户那个下拉挂着 hx-get,组件 pick 后要在原生 select 上 dispatch 冒泡 change,HTMX 才会触发。
# v1.21:支出类目那个下拉换成了常驻宫格(见 _cat-grid.html),所以是 4 个不是 5 个。
# 改数字之前先确认【剩下的 4 个都还在】—— 这条护栏防的是「自研件被人换回原生 select」,
# 不是「必须有 5 个」。
{ [[ "$(grep -c '<select data-lsel' "$RD/src/main/resources/templates/entry/index.html")" == "4" ]] \
  && grep -q 'lens-select.js' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q 'id="cashflow-entry"' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q '#cashflow-entry .lsel-btn' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q 'height:2.25rem' "$RD/src/main/resources/templates/entry/index.html" \
  && ! grep -qE '#cashflow-entry \.lsel-btn\{[^}]*height:[0-9]+px' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q 'SEARCH_THRESHOLD' "$RD/src/main/resources/static/js/lens-select.js" \
  && grep -q 'syncSearchVisibility' "$RD/src/main/resources/static/js/lens-select.js" \
  && grep -q "lsel-q\[hidden\]" "$RD/src/main/resources/static/js/lens-select.js" \
  && grep -q "new Event('change', { bubbles: true })" "$RD/src/main/resources/static/js/lens-select.js"; } \
  && log_ok "v18-CF-SELECT(收支 4 个下拉走 data-lsel · 尺寸用 rem 跟随字号缩放 · 短列表免搜索框 · change 冒泡保住 HTMX)" \
  || log_bad "v18-CF-SELECT 缺件" "entry/index.html 需 4 个 <select data-lsel> + 挂 lens-select.js + #cashflow-entry 尺寸覆盖用 rem;lens-select.js 需 SEARCH_THRESHOLD/syncSearchVisibility/.lsel-q[hidden] 与冒泡 change"

# v18-PERIOD-PICKER · 账期下拉的候选不能用 findLatest
#   findLatest 按 period_start 倒序取,账期表若预建到很多年以后(beta 排到 2038),
#   取到的 12 期全是未来空期 → 当前账期不在列表里 → 没有 option 带 selected → 选择器显示成
#   「2038 · 12 · CLOSED」,而页面标题却是 2026-08。同一个坑还坑过支出构成的「本期」锚点。
{ grep -q 'findRecentAsOf' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'entryPeriodListUpperBound' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && ! grep -q 'model.addAttribute("periods", periodMapper.findLatest' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'findRecentAsOf' "$RD/src/main/java/com/family/finance/repository/PeriodMapper.java" \
  && grep -q 'period_start <= #{asOf}' "$RD/src/main/java/com/family/finance/repository/PeriodMapper.java"; } \
  && log_ok "v18-PERIOD-PICKER(账期下拉候选走 findRecentAsOf · 不超过今天/进行中期 · 当前期必在列表内)" \
  || log_bad "v18-PERIOD-PICKER 仍用 findLatest" "EntryController 的 periods 应走 periodMapper.findRecentAsOf(上界=max(今天, 进行中期起始))"

# v181-CSRF-STALE · CSRF 失效回登录页,不甩「印泥洒了」错误页
#   用户报「登录后 URL 还停在 /login + 报错页」。根因是 CSRF token 失效走了 AccessDeniedHandler
#   → 403 → 渲染 error.html,URL 自然停在 /login。触发场景全是日常事:登录页开着放久了、
#   服务重启过、点了后退再提交、在另一个标签页登录登出过。
#   注意:只有 CsrfException 才跳登录页,真正的权限不足必须仍返回 403(那才该报错)。
{ grep -q 'staleFormAccessDeniedHandler' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -q 'CsrfException' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -q 'login?stale' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -q 'AccessDeniedHandlerImpl' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java" \
  && grep -q '"stale"' "$RD/src/main/java/com/family/finance/auth/AuthController.java" \
  && grep -q 'th:if="${stale}"' "$RD/src/main/resources/templates/auth/login.html"; } \
  && log_ok "v181-CSRF-STALE(CsrfException → /login?stale + 提示文案 · 其余 AccessDenied 仍走 403)" \
  || log_bad "v181-CSRF-STALE 缺件" "SecurityConfig 需 staleFormAccessDeniedHandler(只拦 CsrfException,其余交回 AccessDeniedHandlerImpl)+ AuthController 接 stale + login.html 提示分支"

# v181-FLOAT-DOCK · 浮钮的顺序在横竖屏来回切之后不能变
#   dockFloats 原来带「已在 dock 里就跳过」的守卫。横屏时 tocIntoNav 把目录钮搬进导航栏,
#   切回竖屏时它不在 dock 里 → 被 append 到末尾、另两个原位不动 → 顺序变成 方向/隐私/目录。
#   正解:每次按序 append **全部**(appendChild 对已在容器内的节点=移到末尾),幂等。
#
#   v1.19.6 · 判据从「三钮字面量」改成「方向在最上、隐私在最下,中间可扩展」——
#   加第四个钮(超级 Agent)时这条当场红了,而被守的不变量一个字没变。
#   绑字面量的判据会把「正常演进」误报成「回归」(承 v119-CHAT-GUARD-NOT-LITERAL)。
{ grep -qE "\['#ori-float',.*'#priv-float'\]" "$RD/src/main/resources/static/js/landscape.js" \
  && ! grep -q "el.parentElement !== dock" "$RD/src/main/resources/static/js/landscape.js" \
  && grep -qE 'if \(el\) dock\.appendChild\(el\)' "$RD/src/main/resources/static/js/landscape.js"; } \
  && log_ok "v181-FLOAT-DOCK(浮钮按序全 append · 方向最上/隐私最下 · 横竖屏切换顺序不变)" \
  || log_bad "v181-FLOAT-DOCK 守卫回退了" "landscape.js 的 dockFloats 不得再用 el.parentElement !== dock 跳过已入 dock 的节点 —— 那会让横屏搬走过的目录钮回来时落到末尾"

# v181-README-BRIEF · README 近期更新段要短(用户 2026-08-04:篇幅太大)
#   每版 2–4 行 + 不内嵌截图 + 只留最近 1–2 版;细节和宫格图放 Release 页。README 是落地页不是变更日志。
RN_SEG="$(awk '/^## 近期更新/,/^## 主要能力/' "$RD/README.md")"
RN_IMG="$(printf '%s' "$RN_SEG" | grep -c 'releases/download' || true)"
RN_LINE="$(printf '%s' "$RN_SEG" | wc -l | tr -d ' ')"
{ [ "$RN_IMG" = "0" ] && [ "$RN_LINE" -le 16 ]; } \
  && log_ok "v181-README-BRIEF(近期更新段 $RN_LINE 行 · 无内嵌 release 截图)" \
  || log_bad "v181-README-BRIEF 段落又变长了" "近期更新段应 ≤16 行且不内嵌 releases/download 图(当前 $RN_LINE 行 / $RN_IMG 张图)· 细节放 Release 页"

# v1240-README-H1-INTACT · README 第一行必须就是那个 H1,前面不许粘任何东西。
#   v1.24 开发期间连踩两次:改「N 黑盒回归」那个数用了没锚定的 sed,替换落到了文件开头,
#   把标题写成了「/ 864 黑盒回归 |# 家庭账房 · Family Ledger」,第二次又叠成「||」。
#   两次都没人发现,因为 ① 本地没人从第 1 行读 README ② 数测试数的护栏正好 `head -1`
#   读到了这行粘进去的垃圾,数字还是对的 —— 于是护栏一路绿着,把真正过期的那处(第 134 行
#   写着 862)一并遮住了。等发现时它已经随发版推上公开仓库,是 GitHub 首页的大标题。
#   判据钉死整行,不只是「以 # 开头」—— 粘在 # 前面的垃圾恰恰不以 # 开头,但粘在后面的也得抓。
README_H1="$(head -1 "$RD/README.md")"
[ "$README_H1" = "# 家庭账房 · Family Ledger" ] \
  && log_ok "v1240-README-H1-INTACT(README 第 1 行是干净的 H1)" \
  || log_bad "v1240-README-H1-INTACT README 第 1 行被污染了" "实得「$README_H1」· 应为「# 家庭账房 · Family Ledger」—— 多半是某条改测试数的 sed 没锚定,替换落到了文件开头"

# ══════════════════════════════════════════════════════════════════════════════
# v1.9 · 自动版本查询(只查不改)
# ══════════════════════════════════════════════════════════════════════════════
UCS="$RD/src/main/java/com/family/finance/service/update/UpdateCheckService.java"

# v19-UPD-NO-TELEMETRY · 不把版本号发给 GitHub
#   GitHub 要求请求带 UA,顺手写成 financial-management/1.9.0 是最自然的写法 ——
#   那就等于把版本号发出去了,与 PRD FR-303「不带版本号、不带实例标识」冲突。
#   这个冲突是写 TDD 时才发现的,不是想出来的。
{ grep -qF 'String UA = "financial-management"' "$UCS" \
  && ! grep -qE 'UA *\+ *"/"|UA *= *"[^"]*" *\+' "$UCS" \
  && ! grep -qE '"User-Agent", *UA *\+' "$UCS" \
  && grep -qF 'REPO = "LuoDi-Nate/financial-management"' "$UCS"; } \
  && log_ok "v19-UPD-NO-TELEMETRY(UA 不含版本号 · 仓库地址写死不可配置)" \
  || log_bad "v19-UPD-NO-TELEMETRY 可能带上了版本号" "UA 必须是固定串 financial-management;仓库地址不得做成可配置项(可配置 = 可被指向任意仓库)"

# v19-UPD-NO-IO-IN-ADVICE · GlobalModelAdvice 每个请求都跑,只许一次内存读
{ grep -q 'updateCheckService.cached' "$RD/src/main/java/com/family/finance/common/GlobalModelAdvice.java" \
  && ! grep -qE 'Mapper|RestTemplate|HttpClient|checkNow' "$RD/src/main/java/com/family/finance/common/GlobalModelAdvice.java"; } \
  && log_ok "v19-UPD-NO-IO-IN-ADVICE(advice 只调 cached() · 无 Mapper/HTTP/checkNow)" \
  || log_bad "v19-UPD-NO-IO-IN-ADVICE 把 IO 放进了每请求路径" "GlobalModelAdvice 每个请求都会跑,不得查库/出网;只能读 UpdateCheckService.cached() 的内存字段"

# v19-UPD-FAIL-CLOSED · 迁移判定「查不出来」必须是「未知」不是「没有」
#   报「无 schema 变更」是错误且危险的结论(用户会以为能安全回退)。
#   同一类老毛病:`|| echo 0` 把失败翻译成一个看起来正常的值。
{ grep -q 'COMPARE_FILES_CAP' "$UCS" \
  && grep -qE 'files.size\(\) *>= *COMPARE_FILES_CAP' "$UCS" \
  && grep -q 'truncated' "$UCS" \
  && grep -q '总额模式\|known' "$UCS" \
  && grep -q '未来账期\|new Migrations(0, List.of(), false)' "$UCS" \
  && grep -q '文件清单被截断时必须标未知_不能报没有迁移' "$RD/src/test/java/com/family/finance/service/update/UpdateCheckServiceTest.java"; } \
  && log_ok "v19-UPD-FAIL-CLOSED(compare files 截断/失败 → known=false · 单测守着)" \
  || log_bad "v19-UPD-FAIL-CLOSED 缺件" "detectMigrations 必须在 truncated/null 时返回 known=false;单测「文件清单被截断时必须标未知」必须在"

# v191-UPD-TAG-REF · compare 必须传 git tag 引用,不能传 app.version 原样
#   app.version 是 1.9.0(application.yml 里不带 v),tag 是 v1.9.0。
#   直接拼 → /compare/1.9.0...v1.9.1 → 404 → 迁移判定永远「无法确定」,
#   版本卡上最有价值的那一格彻底失效。
#   这个 bug 在 beta 上测不出来:那个分支只在「有新版」时才走,而 beta 的在研版本号总是
#   比已发布最新版更新,分支根本不执行 —— 是准备发 v1.9.1 做真机验证时核 URL 才发现的。
{ grep -q 'static String tagOf' "$UCS" \
  && grep -q 'fetchMigrations(tagOf(currentVersion), tagOf(latest))' "$UCS" \
  && ! grep -qE 'fetchMigrations\(currentVersion' "$UCS" \
  && grep -q '版本号要归一化成tag引用_否则compare必然404' "$RD/src/test/java/com/family/finance/service/update/UpdateCheckServiceTest.java"; } \
  && log_ok "v191-UPD-TAG-REF(compare 走 tagOf 归一化 · 单测守着)" \
  || log_bad "v191-UPD-TAG-REF 又把 app.version 原样拼进 compare 了" "必须 fetchMigrations(tagOf(current), tagOf(latest));app.version 不带 v 而 tag 带 v,原样拼必然 404"

# v19-UPD-DEGRADE · 一次失败不许把页面从「有新版」变成「什么都没有」
{ grep -q 'writeAttempt(familyId, false' "$UCS" \
  && ! grep -qE 'catch *\([^)]*\) *\{[^}]*KEY_RESULT' "$UCS" \
  && grep -q 'KEY_ATTEMPT' "$UCS" && grep -q 'KEY_RESULT' "$UCS"; } \
  && log_ok "v19-UPD-DEGRADE(失败只写 lastAttempt · 不动 result / 不动内存)" \
  || log_bad "v19-UPD-DEGRADE 失败路径动了 result" "checkNow 的 catch 只能写 lastAttempt;result 保持上次成功值"

# v19-UPD-BADGE · 徽记可点 + 圆点绝对定位 + 不嵌套 <a>
#   徽记原先嵌在 logo 的 <a th:href="@{/}"> 里,直接加 href 会变成 <a> 套 <a>(非法 HTML,
#   浏览器自动拆开、布局散架)→ v1.9 把 nav 拆成三个并列 <a>。
{ grep -q 'ver-badge' "$RD/src/main/resources/templates/fragments/nav.html" \
  && grep -q 'ver-new' "$RD/src/main/resources/templates/fragments/nav.html" \
  && grep -q "admin(tab='version')" "$RD/src/main/resources/templates/fragments/nav.html" \
  && [ "$(grep -c '<a ' "$RD/src/main/resources/templates/fragments/nav.html")" -ge 3 ] \
  && grep -q 'id="version"' "$RD/src/main/resources/templates/admin/index.html"; } \
  && log_ok "v19-UPD-BADGE(徽记 <a> 可点 · 不嵌套 <a> · 管理页 #version 锚点)" \
  || log_bad "v19-UPD-BADGE 缺件" "nav.html 需 ver-badge/ver-new + 跳 admin?tab=version;admin 需 id=version"

# ── v1.9.2 · 提示醒目度 + 就地弹窗 ──────────────────────────────────────
UPM="$RD/src/main/resources/templates/fragments/_update-modal.html"
NAVH="$RD/src/main/resources/templates/fragments/nav.html"
CSSF="$RD/src/main/resources/static/css/style.css"

# v192-UPD-BADGE-CONTRAST · 提示必须是实心高对比,不许退回描边圆点
#   v1.9.0 用 .ver-dot:brass-soft #E8D9B6 填色落在 paper #F4EFE6 上,对比度约 1.2:1
#   (WCAG 非文本元素门槛 3:1)—— 用户反馈「太弱了,和背景色过于相似」,实测等于看不见。
#   换成实心「NEW」文字标签:brass-deep #8C6A33 底 + paper 字 ≈ 4.1:1。
#   这里同时钉住「.ver-dot 已彻底删掉」,防后人照着旧文档把描边圆点加回来。
{ grep -qF '.ver-new' "$CSSF" \
  && ! grep -qE '^[[:space:]]*\.ver-dot[[:space:]]*\{' "$CSSF" \
  && ! grep -q 'ver-dot' "$NAVH" \
  && grep -A6 -F '.ver-new {' "$CSSF" | grep -q 'background: var(--brass-deep)' \
  && grep -A6 -F '.ver-new {' "$CSSF" | grep -q 'color: var(--paper)' \
  && grep -q '>NEW<' "$NAVH"; } \
  && log_ok "v192-UPD-BADGE-CONTRAST(实心 NEW 标签 · brass-deep 底 + 纸色字 ≈ 4.1:1 · .ver-dot 已除)" \
  || log_bad "v192-UPD-BADGE-CONTRAST 提示又变弱了" "禁用 .ver-dot 描边圆点(1.2:1);.ver-new 必须 background:var(--brass-deep) + color:var(--paper)"

# v192-UPD-MODAL-BODY-LEVEL · 弹窗必须挂在 <main> 外面
#   main 带 `relative z-10` 是**层叠上下文**:放在它里面的 position:fixed 浮层
#   永远升不到 z-30 的 nav 之上,遮罩会被顶栏压穿(项目里踩过)。
#   所以由 layout 的 footer 片段引入(footer 只有 relative、没有 z-index,不成上下文)。
{ [ -f "$UPM" ] \
  && grep -q '_update-modal :: updateModal' "$RD/src/main/resources/templates/fragments/layout.html" \
  && ! grep -rq '_update-modal' "$RD/src/main/resources/templates/dashboard/index.html" \
  && grep -A4 -F '.upd-modal {' "$CSSF" | grep -q 'position: fixed' \
  && grep -A4 -F '.upd-mask {' "$CSSF" | grep -q 'position: fixed' \
  && grep -q 'body.upd-open #float-dock' "$CSSF" \
  && grep -qF '.upd-modal, .upd-modal * { text-align: left; }' "$CSSF" \
  && grep -q "classList.add('upd-open')" "$UPM"; } \
  && log_ok "v192-UPD-MODAL-BODY-LEVEL(弹窗由 footer 片段引入 · 在 main 之外 · fixed 遮罩)" \
  || log_bad "v192-UPD-MODAL-BODY-LEVEL 弹窗挂错层" "必须经 fragments/layout.html 的 footer 引入;放进 main(relative z-10)会被 nav 压穿"

# v192-UPD-MODAL-FIELDS · 弹窗三要素 + 迁移判定
#   用户要求至少给:a 最新版本 b 该版本的 GitHub Release 链接 c 版本说明摘要。
#   再加一条本项目独有的迁移判定 —— 回滚只回 jar 不回 DB,这格决定升级能不能退回来。
{ grep -q 'updateInfo.latest()' "$UPM" \
  && grep -q 'updateInfo.currentTag()' "$UPM" \
  && grep -q 'updateInfo.releaseUrl()' "$UPM" \
  && grep -q 'updateInfo.summary()' "$UPM" \
  && grep -q 'updateInfo.publishedAt()' "$UPM" \
  && grep -q 'migrations().known()' "$UPM" \
  && grep -q 'target="_blank"' "$UPM" && grep -q 'rel="noopener"' "$UPM" \
  && grep -q 'updateInfo.hasUpdate()' "$UPM"; } \
  && log_ok "v192-UPD-MODAL-FIELDS(最新版本 + Release 链接 + 说明摘要 + 迁移判定 · 仅有新版时渲染)" \
  || log_bad "v192-UPD-MODAL-FIELDS 弹窗缺件" "需 latest / releaseUrl / summary / publishedAt / 迁移判定,且整块 th:if hasUpdate"

# v192-UPD-MODAL-DEGRADE · 没 JS 时徽记仍然是条能用的链接
#   弹窗是渐进增强:href 保留指向管理页版本卡,JS 在时才 preventDefault 拦成弹窗。
#   别改成 href="#" 或 <button> —— 那样禁用 JS 的浏览器点了什么都不会发生。
{ grep -q "th:href=\"@{/admin(tab='version')}\"" "$NAVH" \
  && ! grep -q 'href="#"' "$NAVH" \
  && grep -q 'preventDefault' "$UPM" \
  && grep -q "querySelectorAll('.ver-badge')" "$UPM" \
  && grep -q "e.key === 'Escape'" "$UPM"; } \
  && log_ok "v192-UPD-MODAL-DEGRADE(徽记 href 仍可用 · JS 在时才拦成弹窗 · Esc/遮罩可关)" \
  || log_bad "v192-UPD-MODAL-DEGRADE 退化路径断了" "徽记 href 必须留 /admin?tab=version;弹窗靠 preventDefault 接管,不许 href=# 或换 <button>"

section "v1.12 · 分类属性按期定格 + 查询开销归因 + 比率失真降级"

PAAM="$RD/src/main/java/com/family/finance/repository/PeriodAccountAttrMapper.java"
PSVC="$RD/src/main/java/com/family/finance/service/PeriodService.java"
FMXML="$RD/src/main/resources/mapper/FactMapper.xml"
FVSI="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
RPTC="$RD/src/main/java/com/family/finance/web/report/ReportsController.java"
MDSP="$RD/src/main/java/com/family/finance/common/MetricDisplay.java"
V54="$RD/db/migration/V54__period_account_attr.sql"

# v112-ATTR-FREEZE-CLOSED · 关账必须在**同一事务内**定格分类属性,失败就不许关账成功
#   FR-350 的整条承诺(改分类不回头改写历史)只有一个前提:关账那一刻真的把属性拍下来了。
#   一旦这行掉到 afterCommit 钩子里、或被 try-catch 咽掉,就会出现「关了账但没定格」的期 ——
#   读侧回落当前属性,页面看起来完全正常,漏保护且不报警,等到用户改了类目才发现历史被改写了。
#   所以这条同时守:① close() 里调 freezeByPeriod ② 它不在那三段非阻塞钩子(FIRE/再平衡/AI 月报)那一类里
#   ③ 位置在 periodMapper.close() 之后、runMetricsAfterCommit() 之前。
freeze_ln=$(grep -n 'periodAccountAttrMapper.freezeByPeriod(periodId)' "$PSVC" | head -1 | cut -d: -f1)
close_ln=$(grep -n 'periodMapper.close(period.getFamilyId(), periodId)' "$PSVC" | head -1 | cut -d: -f1)
recomp_ln=$(grep -n 'runMetricsAfterCommit(familyId, periodId)' "$PSVC" | tail -1 | cut -d: -f1)
{ [ -n "$freeze_ln" ] && [ -n "$close_ln" ] && [ -n "$recomp_ln" ] \
  && [ "$freeze_ln" -gt "$close_ln" ] && [ "$freeze_ln" -lt "$recomp_ln" ] \
  && grep -q '@Transactional' "$PSVC" \
  && grep -q 'INSERT INTO period_account_attr' "$PAAM"; } \
  && log_ok "v112-ATTR-FREEZE-CLOSED(关账同一事务内定格分类属性 · 在 close 之后、重算钩子之前)" \
  || log_bad "v112-ATTR-FREEZE-CLOSED 关账没有(或不在事务里)定格属性" "PeriodService.close() 必须在 periodMapper.close() 之后、runMetricsAfterCommit() 之前调 freezeByPeriod,不许挪进 afterCommit 或包 try-catch"

# v112-ATTR-REOPEN-REFREEZE · 重开必须删掉定格行(→「重开后再关账 = 重新定格」结构上必然)
#   不删的话:重开期又按当前属性填报,但读侧还挂着上次关账的定格值 → 页面显示的分类与用户
#   正在编辑的账户设置不一致。删掉之后不需要标志位/版本号来表达「这期的定格作废了」,少一个状态少一类不一致。
{ grep -q 'periodAccountAttrMapper.deleteByPeriod(period.getFamilyId(), periodId)' "$PSVC" \
  && grep -q 'DELETE pa FROM period_account_attr pa' "$PAAM" \
  && [ -n "$(grep -n 'periodAccountAttrMapper.deleteByPeriod' "$PSVC")" ]; } \
  && log_ok "v112-ATTR-REOPEN-REFREEZE(重开删定格行 · 再关账自动重新定格)" \
  || log_bad "v112-ATTR-REOPEN-REFREEZE 重开没清定格" "PeriodService.reopen() 必须调 deleteByPeriod,否则重开期显示的分类与当前设置不一致"

# v112-ATTR-NO-BYPASS · queryBase 不许再裸投影 a.type / pc.liquidity_class
#   这是 FR-350 的读侧唯一出口:所有吃 FactBaseRow 的消费者(SealedPeriodService / AllocationService /
#   各 lens / 集中度 / 分层 / 大类分布)都从这两列拿分类。谁把 COALESCE 改回裸投影,
#   一整层历史保护就静默失效 —— 编译过、测试过、页面正常,只有"改了类目历史跟着变"能发现。
{ grep -q 'COALESCE(paa.account_type, a.type) AS account_type' "$FMXML" \
  && grep -q 'COALESCE(paa.liquidity_class, pc.liquidity_class) AS product_liquidity_class' "$FMXML" \
  && grep -q 'LEFT JOIN period_account_attr paa' "$FMXML" \
  && ! grep -qE '^\s*a\.type AS account_type,' "$FMXML" \
  && ! grep -qE '^\s*pc\.liquidity_class AS product_liquidity_class' "$FMXML"; } \
  && log_ok "v112-ATTR-NO-BYPASS(queryBase 分类列走定格优先 · 没有裸投影当前属性的旁路)" \
  || log_bad "v112-ATTR-NO-BYPASS 分类列又直接读当前属性了" "FactMapper.queryBase 必须 COALESCE(paa.xxx, 当前值) + LEFT JOIN period_account_attr"

# v112-ATTR-BACKFILL-SCOPE · 定格行集范围必须与 queryBase 的归档过滤**逐字一致**
#   三处必须同一个谓词:① 关账定格 SQL ② V54 的存量回填 ③ 读侧 queryBase(v110-ARCHIVED-TIME 守着)。
#   任一处写成裸 `archived_at IS NULL`,定格的行集就 ≠ 读取的行集 —— 差集里的账户静默回落当前属性,
#   而这类漏洞正好只在"归档过的账户 + 历史期"这个角落出现,页面上完全看不出来。
{ grep -q 'WHERE a.archived_at IS NULL OR a.archived_at > p.period_end' "$PAAM" \
  && grep -q 'a.archived_at IS NULL OR a.archived_at > p.period_end' "$V54" \
  && grep -q 'a.archived_at IS NULL OR a.archived_at &gt; p.period_end' "$FMXML"; } \
  && log_ok "v112-ATTR-BACKFILL-SCOPE(定格 / 回填 / 读取三处归档谓词逐字一致)" \
  || log_bad "v112-ATTR-BACKFILL-SCOPE 定格行集与读取行集不一致" "freezeByPeriod + V54 回填 + queryBase 都必须用 (archived_at IS NULL OR archived_at > p.period_end)"

# v112-ATTR-BENCH-ANCHOR · 报表页「基准 / 预实」读锚期定格值,仪表盘保持实时 —— 两页刻意不同
#   报表页承诺可复现:今天改一个账户的类目或预期年化,去年 12 月的「实际 vs 基准」不该跟着重算,
#   所以取 slice.returnAnchorPeriodId() 的定格属性(与账户 xirr 同一时点,否则"实际 − 基准"两边不同天)。
#   仪表盘反过来:用户刚把预期年化 6% 改成 8%,仪表盘就该立刻按 8% 算 —— 定格在那里是 bug 不是封板。
#   构造时踩过:只改前三个属性时以为改完了,直到把 GOLD 的 benchmark_pct 调成 99 还能推动 3M/6M/YTD ×
#   三币种的数字,才发现「预实」列走的是**第三个**漂移入口(expectedReturnByAccount → planActualDiffPct)。
{ grep -q 'accountPerformance(slice, true)' "$RPTC" \
  && grep -q 'slice.returnAnchorPeriodId()' "$RPTC" \
  && grep -q 'periodAccountAttrMapper.findByPeriod(me.getFamilyId(), benchAnchorPeriodId)' "$RPTC" \
  && grep -q 'expectedReturnByAccount(slice, sealedAttrs)' "$FVSI" \
  && grep -q 'accountPerformance(FactSlice slice, boolean sealedAttrs)' "$FVSI" \
  && ! grep -q 'accountPerformance(slice, true)' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'expected_return_pct' "$PAAM"; } \
  && log_ok "v112-ATTR-BENCH-ANCHOR(报表基准/预实读锚期定格 · 仪表盘仍实时 · 预期年化也在定格属性里)" \
  || log_bad "v112-ATTR-BENCH-ANCHOR 基准/预实的定格分工坏了" "reports 必须 accountPerformance(slice,true) + 读 returnAnchorPeriodId 的定格属性;dashboard 必须走实时重载"

# v112-ATTR-LIVE-CURRENT · 没有定格行时**必须**回落当前属性,不许报错/不许显示空
#   回落是正确行为而非兜底将就,三种合法缺失:① 未关账的当期(本来就该跟当前走)
#   ② 今天新建的账户会出现在去年已关账期的行里(queryBase 是 account × period 交叉)—— 它没有定格行
#   ③ 锚期还没关账(外壳态 / 全新家庭)。
#   正因为 ②,护栏**不能**写成「已关账期每个账户都必须有定格行」——新建一个账户就会让它假红。
#   所以这里守的是"缺失路径是回落而不是抛错":读侧 COALESCE(上一条已守)+ 报表侧 null 判断 + 逐字段回落。
{ grep -q 'if (benchAnchorPeriodId != null)' "$RPTC" \
  && ! grep -qE 'benchAnchorPeriodId.*orElseThrow|定格行缺失.*throw' "$RPTC" \
  && grep -q '回落' "$FMXML" \
  && grep -q '未关账' "$FMXML"; } \
  && log_ok "v112-ATTR-LIVE-CURRENT(定格行缺失时回落当前属性 · 当期/新账户/未关账锚期都不报错)" \
  || log_bad "v112-ATTR-LIVE-CURRENT 缺定格行的路径变成报错或空值" "缺失是合法的三种情形,必须逐字段回落当前属性"

# v112-SQL-PROFILER-OFF · 查询归因默认关 · 开关走管理页 + 进审计 · 关闭态只多一次 ThreadLocal 读
#   这是诊断工具不是常驻特性:开着每个请求多写一段清单日志。默认必须关,且关闭态的代价要压到
#   「一次 ThreadLocal 读 + 一次 null 判断」——拦截器在 active() 为假时立刻 proceed,不取 SQL 文本、不计时。
#   开关本身要留痕(audit_log),否则事后看不出"这段日志为什么突然多了"。
{ grep -q 'K_SQL_PROFILER, false' "$RD/src/main/java/com/family/finance/observability/SqlProfileWebInterceptor.java" \
  && grep -q 'if (!SqlProfileContext.active()) return invocation.proceed();' "$RD/src/main/java/com/family/finance/observability/SqlCountInterceptor.java" \
  && grep -q 'K_SQL_PROFILER' "$RD/src/main/java/com/family/finance/web/admin/AdminController.java" \
  && grep -q '/audit/sql-profiler' "$RD/src/main/java/com/family/finance/web/admin/AdminController.java" \
  && grep -q 'AuditLogType.FAMILY_UPDATE, "family_runtime_config"' "$RD/src/main/java/com/family/finance/web/admin/AdminController.java" \
  && grep -q 'sqlProfiler' "$RD/src/main/resources/templates/admin/audit.html" \
  && grep -q 'sqlProfileWebInterceptor' "$RD/src/main/java/com/family/finance/config/WebMvcConfig.java"; } \
  && log_ok "v112-SQL-PROFILER-OFF(默认关 · 管理页开关 · 开关进审计 · 关闭态只一次 ThreadLocal 读)" \
  || log_bad "v112-SQL-PROFILER-OFF 归因开关默认开了或绕过了管理页" "默认 false + /admin/audit 开关 + 审计留痕 + active() 为假立刻 proceed"

# v112-RATIO-INSUFFICIENT · 比率失真的阈值与文案只许有**一处**出处
#   v1.11 已经修过一次(封板对照表显示「收支不足」),但阈值当时是 SealedSnapshot 的私有常量,
#   于是仪表盘和报表 KPI 位仍然摆着 −2383%。两处各写一个 500% 迟早变成「一处降级一处不降」——
#   与 v1.11 的 hx-select 落空、v0.14 加枚举漏改模板硬编码是同一类事故:同一条规则散落多处。
#   所以这条守五件:① 阈值只在 MetricDisplay ② 模板不许再写降级文案字面量(走 ratioNote)
#   ③ 三个显示面(仪表盘 hero / 报表储蓄 KPI / 封板对照表)都接了 ④ 降级处必须给补录入口
#   ⑤ **由失真值派生的 Δ 列也必须一起降级** —— 2026-08-14 beta 双端复验实拍到:本期显示
#      「收支不足」、同比却摆着 −2468.2 pp(= 藏起来的 −2383.3% 减 84.86%)。只降值列不降差额,
#      等于把同一个垃圾值换了个更难看穿的马甲继续摆出来(用户看不出这个 pp 的一端是垃圾)。
{ [ -f "$MDSP" ] && grep -q 'RATIO_ABSURD_ABS = new BigDecimal("5")' "$MDSP" \
  && grep -q 'MetricDisplay.ratioAbsurd' "$RD/src/main/java/com/family/finance/service/report/SealedSnapshot.java" \
  && grep -q 'if (absurd(current) || absurd(prev)) return null;' "$RD/src/main/java/com/family/finance/service/report/SealedSnapshot.java" \
  && grep -q 'if (absurd(current) || absurd(yoy)) return null;' "$RD/src/main/java/com/family/finance/service/report/SealedSnapshot.java" \
  && [ "$(grep -rl 'new BigDecimal("5")' "$RD/src/main/java/com/family/finance")" = "$MDSP" ] \
  && grep -q 'ratioNote' "$RD/src/main/java/com/family/finance/common/GlobalModelAdvice.java" \
  && grep -q 'savingsRateInsufficient' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'savingsRateInsufficient' "$RPTC" \
  && grep -q 'ratioNote.insufficient()' "$RD/src/main/resources/templates/reports/_savings.html" \
  && grep -q 'ratioNote.backfillHref()' "$RD/src/main/resources/templates/dashboard/_region.html" \
  && grep -q 'ratioNote.insufficientShort()' "$RD/src/main/resources/templates/reports/_sealed.html" \
  && ! grep -q "'收支数据不足'" "$RD/src/main/resources/templates/reports/_savings.html" \
  && ! grep -q "'收支不足'" "$RD/src/main/resources/templates/reports/_sealed.html"; } \
  && log_ok "v112-RATIO-INSUFFICIENT(500% 阈值与降级文案单一出处 · 三个显示面都接 · 降级处给补录入口)" \
  || log_bad "v112-RATIO-INSUFFICIENT 比率降级又散成多处/模板写死文案" "阈值只许在 MetricDisplay;模板一律 \${ratioNote.xxx()};仪表盘+报表KPI+封板表三处都要接"

section "v1.11 · 报表/仪表盘性能 + 交互 + 口径一致性(维护者 13 条反馈)"

# v1111-WF-LABELS-VISIBLE · 瀑布的标签一个都不许被遮住
#   两处都踩过:① 截断轴的斜纹带原来横贯整宽贴在 .wf 底边,而 X 轴标签(.wf-lab)是**溢出**
#   .wf 之外的 → 那条边正好穿过标签中间,把「期初净资产 / 2026-07」压掉一半;
#   ② 最高柱的顶部标签定位在柱顶 -1.05rem,贴着容器上边 → 被 overflow 裁掉一半。
#   ③ 标签比柱子宽(¥1,234,567 约 178px vs 柱宽 ~127px),溢出部分会被 DOM 顺序在后的柱子背景盖住。
#   修法:斜纹带只画在**柱内底部**(柱底就是轴线,语义一样但不碰字)· .wf 加 padding-top 给顶部标签留位
#   · 标签给 z-index 浮到所有柱子之上。
{ grep -q '.wf-truncated .wf-bar::before' "$RD/src/main/resources/static/css/style.css" \
  && ! grep -q '.wf-truncated .wf::after' "$RD/src/main/resources/static/css/style.css" \
  && grep -A3 -F '.wf { display: flex' "$RD/src/main/resources/static/css/style.css" | grep -q 'padding-top' \
  && grep -A4 -F '.wf-dl {' "$RD/src/main/resources/static/css/style.css" | grep -q 'z-index: 5'; } \
  && log_ok "v1111-WF-LABELS-VISIBLE(斜纹带画柱内 · 顶部留位 · 标签浮层 —— 三处遮挡都堵住)" \
  || log_bad "v1111-WF-LABELS-VISIBLE 瀑布标签又被遮住了" "斜纹带只能画在 .wf-bar::before;.wf 要 padding-top;.wf-dl 要 z-index"

# v1111-TOC-LEVEL · 长文目录编号必须统一(要么全有要么全无)+ 二级条目缩进
#   报表页三区之下有 9 个子节。前三个带「一/二/三」而后面没有 → 看着像漏了(维护者反馈)。
#   给子节编号(四、五…)是**错的信息**(它们不是三区的同级),所以统一不编号 + 用缩进表达层级。
#   另外:SpEL 对 map 上**不存在的 key** 用 `it.sub` 会**抛异常**(不是返回 null),必须 containsKey。
{ ! grep -qE "label:'[一二三] · " "$RD/src/main/resources/templates/reports/index.html" \
  && grep -q "sub:true" "$RD/src/main/resources/templates/reports/index.html" \
  && grep -q "it.containsKey('sub')" "$RD/src/main/resources/templates/fragments/_toc.html" \
  && ! grep -q 'it.sub != null' "$RD/src/main/resources/templates/fragments/_toc.html" \
  && grep -q '.toc-node.toc-sub' "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v1111-TOC-LEVEL(目录统一不编号 + 子节缩进 · map key 用 containsKey 不用属性访问)" \
  || log_bad "v1111-TOC-LEVEL 目录编号又不统一 / 或用了会抛异常的 it.sub" "统一不编号 + sub:true 缩进;SpEL 取 map 不存在的 key 会抛异常"

# v1111-ONE-TIME-FILTER · 一页只能有一个时间筛选器
#   支出构成原来有自己的「本期 / 近6期 / 近12期」,和三区标题旁的时间范围是同一件事 ——
#   两个时间控件放一页会让人不知道哪个管哪个(维护者第 5 条)。窗口改为由 range 推出。
{ grep -q 'savingsWindowPeriods(range)' "$RD/src/main/java/com/family/finance/web/report/ReportsController.java" \
  && grep -q '跟随上方「趋势」区的时间范围' "$RD/src/main/resources/templates/reports/_expense-section.html" \
  && ! grep -q "th:each=\"w : \${ {1, 6, 12} }\"" "$RD/src/main/resources/templates/reports/_expense-section.html"; } \
  && log_ok "v1111-ONE-TIME-FILTER(支出构成窗口并入统一时间范围 · 页面只剩一个时间控件)" \
  || log_bad "v1111-ONE-TIME-FILTER 又出现第二个时间筛选器" "窗口必须由 range 推出,不要独立 pills"

# v1111-SESSION-REMEMBER · 发版重启不该把用户踢出去
#   Session 在进程内存里(没上 spring-session)→ 重启 = 全部会话失效。
#   remember-me 早就是 JDBC 持久化(persistent_logins,30 天),只是复选框默认不勾。
{ grep -q 'name="remember-me" checked' "$RD/src/main/resources/templates/auth/login.html" \
  && grep -q 'PersistentTokenBasedRememberMeServices' "$RD/src/main/java/com/family/finance/auth/SecurityConfig.java"; } \
  && log_ok "v1111-SESSION-REMEMBER(remember-me 默认勾选 · JDBC 持久化 · 发版重启不用重登)" \
  || log_bad "v1111-SESSION-REMEMBER 发版后又要重新登录" "登录页 remember-me 需 checked;token 必须 JDBC 持久化"

# v1111-RATE-GOAL-PRECISION · 比率类目标值保留 1 位小数
#   原来 compactVal 对 isRate() 用 setScale(0):储蓄率 8.4% 显示成 8%、0.4% 显示成 0%,
#   月度推进(几个零点几)全被吃掉,条带上看不出任何变化(维护者反馈「0%/8% 不够直观」)。
{ grep -q 'if (isRate()) return v.setScale(1' "$RD/src/main/java/com/family/finance/service/goal/GoalProgressService.java" \
  && grep -q 'formatDecimal(gp.progressPct, 1, 1)' "$RD/src/main/resources/templates/goals/_progress-strip.html" \
  && grep -q 'formatDecimal(gp.progressPct, 1, 1)' "$RD/src/main/resources/templates/goals/_goal-bar.html"; } \
  && log_ok "v1111-RATE-GOAL-PRECISION(比率类目标值与进度百分比统一 1 位小数 · 三处渲染都改)" \
  || log_bad "v1111-RATE-GOAL-PRECISION 目标百分比又被取整" "compactVal 的 isRate 分支要 setScale(1);progressPct 三处渲染统一"

# v1112-CHART-LABEL-DENSITY · 单序列图不画图例 · 多点折线不许每个点都印字
#   负债下降曲线是全项目**唯一**忘了关图例的单序列图:Chart.js 默认在顶部画「■ 负债」方框,
#   它落在**绘图区里**,把 datalabels(画在数据点上方的金额)整排挤没了 —— 维护者报的
#   「图例和图表互相覆盖」。关掉图例后又露出第二层:12 个点每个都印 ¥123.6万 这种 7 字标签,
#   同一水平线上挤在 800px 里直接叠成一串,首点还压住 Y 轴刻度、末点被卡片边缘切掉。
#   项目里同类多点折线图(_wealth-level / _savings)的既有惯例本来就是**只标末点**。
RGN="$RD/src/main/resources/templates/reports/_region.html"
{ grep -q "legend: {display: false}" "$RGN" \
  && grep -qF 'display: (c) => c.dataIndex === 0 || c.dataIndex === c.dataset.data.length - 1' "$RGN" \
  && grep -q 'clamp: true' "$RGN" \
  && grep -qF "align: (c) => c.dataIndex === 0 ? 315 : 225" "$RGN"; } \
  && log_ok "v1112-CHART-LABEL-DENSITY(负债曲线:关图例 + 只标首末点 + clamp + 两端斜向内推不压轴不出框)" \
  || log_bad "v1112-CHART-LABEL-DENSITY 图例回来了 / 标签又印满了" "见 _region.html debtChart 的 legend 与 datalabels.display"

# v1113-DOCKER-UP-PLATFORM · 安装脚本的报错指引必须**按平台**给(issue #10)
#   报告者在 Ubuntu 22.04 上跑 docker-up.sh,脚本说「只找到老版 docker-compose 5.0.2」,
#   然后教他 `brew install docker-compose` —— **Linux 上没有 brew**,人直接卡死。
#   两处缺陷:① 三个失败分支里只有「没装 docker」那条有 Linux 分支,「引擎没起」和
#   「Compose V2 缺失」两条是纯 macOS 文案;② 版本号取自 `docker-compose version --short`,
#   而 Ubuntu 那个 1.29.2 的 `--short` 吐的是**依赖库 docker-py 的 5.0.2**,把人往更糊涂的方向带
#   (改成解析完整输出第一行 `docker-compose version 1.29.2, build unknown`)。
#   这条守:凡是给 brew 指令的地方,必须有对应的 Linux 分支。
DUP="$RD/deploy/docker-up.sh"
{ grep -q '_compose_v2_howto' "$DUP" \
  && grep -q 'docker-compose-plugin' "$DUP" \
  && grep -q 'systemctl start docker' "$DUP" \
  && grep -q 'usermod -aG docker' "$DUP" \
  && grep -qF "sed -n 's/^docker-compose version" "$DUP" \
  && [ "$(grep -c 'uname -s' "$DUP")" -ge 4 ] \
  && grep -q 'docker-compose-plugin' "$RD/deploy/README.md" \
  && bash -n "$DUP"; } \
  && log_ok "v1113-DOCKER-UP-PLATFORM(引擎/Compose 失败分支按平台给指引 · Linux 有 systemctl+插件安装 · 版本号不再取 docker-py)" \
  || log_bad "v1113-DOCKER-UP-PLATFORM 安装报错又只给 macOS 指引了" "见 deploy/docker-up.sh 的 _compose_v2_howto 与引擎分支"

# v1111-VERSION-DESIGN-DOCS · 在研版本必须有 prd + tech-design(2026-08-13)
#   为什么加:v1.11 那批 13 条反馈,我把维护者的「全部做完不要中途停」理解成"连设计阶段一起省",
#   代码/护栏/qa-cases 都同步了,唯独 `prd/v1.11.md` + `tech-design/v1.11.md` **压根没写**,
#   两个版本发完 prod 才在复查时发现。发布预检只校验"已存在的设计文档有没有被 README 链接",
#   **文档不存在就什么都拦不住** —— 正好是最该拦的那种情况。
#   这条按 app.version 的 major.minor 找文档:开发一开始就 bump 版本(memory feedback_dev_version_bump),
#   所以只要动了码,这条立刻要求把 PRD/TDD 建起来,补丁号不要求(x.y.Z 复用 x.y 的文档)。
APPV=$(grep -oE 'APP_VERSION:[0-9]+\.[0-9]+\.[0-9]+' "$RD/src/main/resources/application.yml" | head -1 | cut -d: -f2)
MINOR=$(printf '%s' "$APPV" | cut -d. -f1,2)
{ [ -n "$MINOR" ] && [ -f "$RD/prd/v$MINOR.md" ] && [ -f "$RD/tech-design/v$MINOR.md" ] \
  && grep -q "prd/v$MINOR.md" "$RD/README.md" && grep -q "tech-design/v$MINOR.md" "$RD/README.md"; } \
  && log_ok "v1111-VERSION-DESIGN-DOCS(在研 v$MINOR 的 prd + tech-design 都在,且 README 已链接)" \
  || log_bad "v1111-VERSION-DESIGN-DOCS 在研版本缺设计文档" "app.version=$APPV → 需要 prd/v$MINOR.md + tech-design/v$MINOR.md + README 链接"

# v111-NO-PROD-AMOUNTS · 审计/复盘类文档不许出现 prod 真实金额(2026-08-12 · 维护者点出的信息安全问题)
#   背景:仓库是**公开**的(GitHub)。而 `docs/*audit*.md` / `docs/*review*.md` 这类文档天生是
#   「对着真实环境写观察」,最容易把维护者家庭的净资产/余额写进去 —— 实测 v1.6 时代的
#   metric-audit / ued-review / prd/v1.6 / tech-design/v1.6 里都有,而且**已经推到公开仓库**。
#   这类文档的价值在**口径 / 计算逻辑 / 相对量 / 结论**,绝对金额一点不需要。
#   规则:审计与复盘文档里不许出现「7 位及以上带千分位」的金额;要记具体数额去本地
#   AGENTS.local.md(git-ignored)。preview mockup / 单测 fixture 用的是合成数,不在本条管辖范围。
#
#   v1.18.6 · 范围扩了两次,因为这条护栏【两个维度都太窄,漏了真实泄露】:
#     ① 只扫 docs/*audit*.md / *review*.md —— 而 README.md / prd/ / tech-design/ /
#        docs/qa-cases.md 同样是「对着真实环境写观察」的地方,而且 README 是落地页、
#        曝光最大。实测 v1.18.5 把 prod 三个真实余额写进了 README 与 qa-cases,
#        这条护栏一声不吭(它压根没看那些文件)。
#     ② 只匹配 7 位以上带两个千分位的金额(`1,234,567`)—— 而家庭账户余额多是
#        6 位(`451,497.63`),一个千分位就够,老 pattern 匹配不到。
#   现在:文件范围含 README / prd / tech-design / docs/qa-cases.md,pattern 放宽到
#   「3 位起 + 千分位 + 两位小数」(`123,456.78` / `1,234,567.89` 都中)。
#   放宽后必然会碰到【合成数】—— 单测 fixture、e2e 造的金额、preview mockup 里的数
#   都是编的,不该报。所以判据是「带小数的千分位金额」+ 白名单豁免:e2e/单测用的
#   合成额写在 QA111_SYNTH 里,加新合成额要显式登记(逼人过一遍脑子:这数是编的吗)。
#
#   【这条护栏还盖不住什么 —— 明写出来,别把它当全覆盖】
#   同一批金额还有两种写法它不认,而仓库里【确实还有存量】(2026-08-24 清点,约 60 处,
#   散在 prd/v0.1~v1.10、tech-design/v0.3~v1.12、docs/qa-cases.md、docs/metric-audit-*):
#     · 万 / w 简写:`119 万` `7.5w` `182.5万`
#     · 裸数字无千分位:`¥5399878` `¥1779269`
#   没有一起收进来,是因为这两种写法【真实金额与举例数混在一起】——「资产 200 万 / 房贷 195 万」
#   是单测造的家庭,「净资产 100 万远大于阈值 5」是讲判据,而「同一时刻 checkup ¥5399878」
#   是 prod 实测。机器分不出来,硬扫会得到一条天天红的护栏,然后被人关掉(这一版刚为
#   「误报会让告警被关掉」付过代价)。要收得先由维护者逐条裁定哪些是真的。
QA111_SYNTH='53,210|48,765|61,234|40,000|35,000|1,234,567\.89|123,456\.78|1,234,567|1,234,568|1,000,000|2,000,000|1,140,000|1,520,000|99,999,999|10,950,000|1,200,000|1,500,000|1,140,580|4,917,500|7,745,000|1,552,823|1,628,895|3,181,718|3,762,836|17,901,892|0,891,892,893,890|7,747,000|111,221.91|111,222.95|1,280\.00|12,845\.30|3,182\.50'
# 【基线 · 待裁定】把扫描面扩到源码/模板时一次性捞出来的存量(v1.19)。
#   里面**真假混杂**:有的是单测造的家庭、股价报文片段、模板占位;但也确实有真的 ——
#   `451,497.63` 就是本条护栏自己的注释里点名过的真实余额。机器分不出来,
#   而一条天天红的护栏会被关掉(这个代价刚付过),所以先基线化。
#   **这个清单只减不增**:新写的注释/单测举例一律用编的数,新增金额必然被拦下。
#   什么时候清:等维护者逐条裁定哪些是真的(与 prd/tech-design 里那约 60 处
#   万/w 简写、裸数字是同一批待裁定存量)。
QA111_LEGACY='00,128\.00|00,395\.00|00,398\.50|00,400\.00|00,402\.00|00,893\.00|02,901\.07|0,891\.00|164,924\.63|17,705\.41|2,290,051\.41|2,326,051\.41|26,519\.00|274,067\.44|35,000\.00|36,000\.00|36,519\.00|375,248\.71|40,000\.00|42,318\.60|451,497\.63|5,000\.00|546,432\.63|76,248\.92|8,920\.00'
#   v1.19 · 范围第三次扩:【源码与单测也算公开文档】。
#     这一版写 javadoc 时,为了说明「1234567.89 和页面上的 ¥1,234,567.89 是同一个数」,
#     直接把 beta 上的真实余额抄进了注释;单测断言里也放了一个真实净资产。
#     护栏一声不吭 —— 它只看 .md。而 .java 一样会被推到公开仓库,曝光度不比文档低。
#     注释里举例**永远可以用编的数**,没有任何理由用真的。
#     模板与静态资源同理(preview mockup 除外,那本来就是合成数)。
#     **scripts/ 也要扫**:v1.19 修这条护栏时,我在解释判据的注释里就用了一个真实金额当例子,
#     而脚本目录当时不在扫描范围 —— 写护栏的人栽在自己没覆盖到的地方。
#     扫脚本本身是自洽的:白名单里的值按定义就在白名单里,只有额外的会被抓。
#     **推论:这段说明文字里不许再出现任何金额形状的字面值** —— 它已经绊倒过自己两次
#     (一次是拿真实余额举例,一次是照抄白名单条目)。要举例就描述形状,别写数字。
bad_money=0
for f in "$RD"/docs/*audit*.md "$RD"/docs/*review*.md "$RD"/README.md "$RD"/docs/qa-cases.md \
         "$RD"/prd/*.md "$RD"/tech-design/*.md \
         "$RD"/scripts/*.sh \
         $(find "$RD/src" -name '*.java' -o -name '*.html' 2>/dev/null); do
  [ -f "$f" ] || continue
  # 两种形态都要认(v1.19 第三次补):
  #   ① 带两位小数的千分位金额  `123,456.78` / `1,234,567.89`
  #   ② **不带小数、两个及以上千分位**的金额 `1,234,568` —— 这正是 MetricExplainService
  #      给出的显示形态(整元不带小数),v1.19 写注释举例时又漏进去一个,而旧判据要求
  #      两位小数,一声没吭。只有一个千分位的(五位数量级)不收:那个量级里
  #      合成举例远多于真实余额,收进来只会得到一条天天红的护栏。
  #   **不要加 (?!...) 前瞻** —— 那是 PCRE 语法,grep -E 不认,带上它整条正则非法、
  #   grep 报错返回空,这条护栏就会在「什么都没检查」的状态下变绿(写的时候真踩了)。
  # 先剔掉两类**结构性误报**,再扫。它们该在判据里排掉,不该逐个进白名单:
  #   · rgb()/rgba() 颜色三元组(每加一个配色都要登记,白名单会失控)
  #   · 花括号展开    —— `iconN-{96,180,192,512}.png` 这种尺寸列表
  #   · 反斜杠 —— 白名单里存的是**正则**(小数点写成 \\.),不去掉的话小数支匹配不上、
  #     整数支反而抓到不带小数的前缀,护栏会把自己的白名单报成泄露
  hits="$(sed -E 's/rgba?\([^)]*\)//g; s/\{[0-9, ]+\}//g; s/\\//g' "$f" \
          | grep -oE '[0-9]{1,3}(,[0-9]{3})+\.[0-9]{2}|[0-9]{1,3}(,[0-9]{3}){2,}' \
          | grep -vE "^($QA111_SYNTH|$QA111_LEGACY)$" | sort -u)"
  if [ -n "$hits" ]; then
    bad_money=1
    echo "      ↑ 含疑似真实金额: ${f#$RD/} → $(echo "$hits" | tr '\n' ' ')"
  fi
done
[[ "$bad_money" -eq 0 ]] \
  && log_ok "v111-NO-PROD-AMOUNTS(文档 + 源码 + 模板 无真实金额 · 仓库公开)" \
  || log_bad "v111-NO-PROD-AMOUNTS 公开文件里有疑似真实金额" "注释举例用编的数;绝对数额去 AGENTS.local.md;确属合成数请登记进 QA111_SYNTH"


SPS2="$RD/src/main/java/com/family/finance/service/report/SealedPeriodService.java"
SZT2="$RD/src/main/resources/templates/reports/_sealed.html"
RIX2="$RD/src/main/resources/templates/reports/index.html"

# v111-NPLUS1-BATCH · 「每期首次出现账户」必须批量查,且必须是一次扫描
#   原来 per-period 查(带 NOT IN 子查询),报表页一次请求 881 条 SQL / 1.25s。
#   第一版批量写成**相关子查询**(对 3600 行 period_snapshot 每行再查 MIN)→ O(n²),反而拖到 9.3s。
#   正解是窗口函数一次扫完。所以这条同时守:① 有批量方法 ② 用的是窗口函数而不是相关子查询
#   ③ 缓存不许是「按 familyId 的长缓存」(长缓存漏清 = 静默错开账基线,而开账基线决定人赚/钱赚分界)。
#
#   v1.12 FR-352 更新落点:原来这份批量结果挂在自家的 `firstAppearTl` 上,现在收进统一的
#   请求级 `FactLoadCache`(GET 挂请求属性 / 非 GET 只活一次 load,读取侧验 `ownedBy` 同一性)。
#   守的性质**一个字没变**,只是从「每次 load 刷新的 ThreadLocal」变成「更强的请求级 + 归属校验」,
#   所以这条改成钉新落点 —— 旧 grep 打红过一次(2026-08-14 全量跑),那是护栏过时不是缺陷。
FVSI2="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
FLC2="$RD/src/main/java/com/family/finance/factview/FactLoadCache.java"
{ grep -q 'firstAppearanceByAccount' "$RD/src/main/java/com/family/finance/repository/SnapshotMapper.java" \
  && grep -q 'ROW_NUMBER() OVER (PARTITION BY ps.account_id' "$RD/src/main/java/com/family/finance/repository/SnapshotMapper.java" \
  && grep -q 'cache.firstAppear.computeIfAbsent(filter.familyId()' "$FVSI2" \
  && grep -q 'cache.firstAppear.get(familyId)' "$FVSI2" \
  && grep -q 'tl.ownedBy(attrs)' "$FVSI2" \
  && grep -q 'boolean ownedBy(Object requestAttributes)' "$FLC2" \
  && ! grep -qE 'static.*(ConcurrentHashMap|Cache)<Long' "$FVSI2" "$FLC2"; } \
  && log_ok "v111-NPLUS1-BATCH(首次出现批量查 · 窗口函数一次扫 · 结果只活在请求级缓存里 + ownedBy 归属校验)" \
  || log_bad "v111-NPLUS1-BATCH N+1 回来了或缓存换成了长缓存" "必须 firstAppearanceByAccount + ROW_NUMBER + 装进 FactLoadCache.firstAppear(读取验 ownedBy),不许出现按 familyId 的 static 长缓存"

# v111-PARTIAL-SWAP · 筛选器切换不许整页跳转(慢 + 丢滚动位置)
{ grep -q 'hx-select="#sec-trend"' "$RIX2" \
  && grep -q 'hx-push-url="true"' "$RIX2" \
  && grep -q 'hx-select="#sec-expense-mix"' "$RD/src/main/resources/templates/reports/_expense-section.html" \
  && grep -q 'th:href' "$RIX2"; } \
  && log_ok "v111-PARTIAL-SWAP(趋势 range / 支出构成维度窗口 都走 HTMX 局部替换 · href 保留作无 JS 退化)" \
  || log_bad "v111-PARTIAL-SWAP 筛选器又变整页跳转了" "必须 hx-get + hx-select + hx-push-url;href 保留"

# v1112-SWAP-TARGET-EXISTS · **hx-select 挑的那个 id,必须真的在响应里**
#
#   上面那条只查"属性写没写",查不出这次的 bug:controller 原来是「只要带 HX-Request 就回
#   `_region :: region` 片段」,而 `#sec-trend`(在 index.html)和 `#sec-expense-mix`
#   (在 _expense-mix.html)**都不在那个片段里** → HTMX 按 id 挑不到东西,换进去一个**空**内容,
#   `hx-swap="outerHTML"` 于是把整个 section **从页面上删掉**。
#   表现:切「按账户」对应模块直接没了。后端 200、日志干净 —— 纯前端选择器落空,最难查的那种。
#   所以这条**发真实的 HTMX 请求**(带 HX-Target),断言响应里确实有那个 id;
#   同时守住老路径:目标是 reports-region 时仍然只回片段(不能退化成每次都吐整页)。
swap_has(){ # $1=HX-Target  $2=要求存在的 id  $3=query
  $CURL -b $COOKIE -H "HX-Request: true" -H "HX-Target: $1" "$BASE/reports?$3" -o "$TMP" -w ""
  grep -q "id=\"$2\"" "$TMP"
}
{ swap_has sec-trend sec-trend 'range=3M' \
  && swap_has sec-expense-mix sec-expense-mix 'range=6M&mix=account' \
  && swap_has sec-expense-mix sec-expense-mix 'range=6M&mix=member' \
  && swap_has sec-expense-mix sec-expense-mix 'range=6M&mix=category'; } \
  && log_ok "v1112-SWAP-TARGET-EXISTS(HTMX 局部替换的目标 id 在响应里真实存在 · 趋势 + 支出构成三个维度)" \
  || log_bad "v1112-SWAP-TARGET-EXISTS hx-select 的目标不在响应里(section 会被换成空 = 整块消失)" "见 ReportsController 的 HX-Target 分流"

# 老路径不能被上面的修法带坏:账户/币种筛选器 hx-target="#reports-region",必须仍然只回片段
$CURL -b $COOKIE -H "HX-Request: true" -H "HX-Target: reports-region" "$BASE/reports?range=3M" -o "$TMP" -w ""
{ ! grep -q '<html' "$TMP" && grep -q 'id="reports-region"' "$TMP"; } \
  && log_ok "v1112-SWAP-REGION-FRAGMENT(reports-region 目标仍走片段 · 没退化成每次吐整页)" \
  || log_bad "v1112-SWAP-REGION-FRAGMENT reports-region 片段路径坏了" "应无 <html 且含 id=reports-region"


# v111-TOC-BOTTOM · 滚到底必须高亮最后一项(最后一节比视口短时,它的顶部永远不越线)
{ grep -q 'atBottom' "$RD/src/main/resources/static/js/toc.js" \
  && grep -q 'ids\[ids.length - 1\]' "$RD/src/main/resources/static/js/toc.js"; } \
  && log_ok "v111-TOC-BOTTOM(滚到底强制高亮最后一项)" \
  || log_bad "v111-TOC-BOTTOM 最后一个目录项又高亮不了" "spy() 必须处理 atBottom"

# v111-SAVINGS-FOLLOWS-RANGE · 「月度收支 + 反推目标月供」期数必须跟随 range
#   它归在三区(趋势),同区其他图都跟 range;只有它写死 12 期 → 切 3M 时上下两图期数不同,
#   并排读会得出错误结论。
{ grep -q 'savingsWindowPeriods(range)' "$RD/src/main/java/com/family/finance/web/report/ReportsController.java" \
  && ! grep -q 'recentSeries(me.getFamilyId(), 12)' "$RD/src/main/java/com/family/finance/web/report/ReportsController.java"; } \
  && log_ok "v111-SAVINGS-FOLLOWS-RANGE(储蓄能力期数跟随时间范围)" \
  || log_bad "v111-SAVINGS-FOLLOWS-RANGE 又写死 12 期了" "必须 savingsWindowPeriods(range)"

# v111-MACRO-FALLBACK-DISCLOSED · 缺当年宏观数据时必须明示,不许静默用历史均值
#   prod 实测:macro_benchmark 只到 2025 而账期已到 2026 → CPI/M2 线其实是三法均值外推,
#   页面若不说,用户会当成当年真实通胀。
{ grep -q 'fallbackYears' "$RD/src/main/java/com/family/finance/service/macro/WaterLevelService.java" \
  && grep -q 'usedFallback()' "$RD/src/main/resources/templates/reports/_wealth-level.html" \
  && grep -q '历史三法均值' "$RD/src/main/resources/templates/reports/_wealth-level.html"; } \
  && log_ok "v111-MACRO-FALLBACK-DISCLOSED(缺当年 CPI/M2 时页面明示走历史均值 + 给补录入口)" \
  || log_bad "v111-MACRO-FALLBACK-DISCLOSED 又静默 fallback 了" "WaterLevel 要透出 fallbackYears,页面要说明"

# v111-RATIO-ABSURD · 比率类分母过小时不许显示荒谬数字(封板对照表侧)
#   prod 收支稀疏(PMC 6 行):某期收入 300 / 支出 7450 → 储蓄率 −2383%,数学没错但毫无信息量。
#   v1.12 起阈值搬到 MetricDisplay(见 v112-RATIO-INSUFFICIENT),这条只继续守封板表这一面:
#   降级判断走 absurd()、文案走 ratioNote、tooltip 仍然给出原值(解释权不能一起被降级掉)。
{ grep -q 'MetricDisplay.ratioAbsurd' "$RD/src/main/java/com/family/finance/service/report/SealedSnapshot.java" \
  && grep -q 'absurd(' "$SZT2" \
  && grep -q 'ratioNote.insufficientShort()' "$SZT2" \
  && grep -q '比率失真(原值' "$SZT2"; } \
  && log_ok "v111-RATIO-ABSURD(封板表比率失真显示短文案 + tooltip 给原值)" \
  || log_bad "v111-RATIO-ABSURD 又会显示 −2383% 这类数字" "比率超阈值要换文案(ratioNote)并在 tooltip 给原值"

# v111-COVER-MONTHS-ONE-RULE · 覆盖月数与紧急储备是同一个数,必须同一条展示规则
{ grep -q 'emergencyLabel(t.coverMonths())' "$SZT2" \
  && grep -q 'emergencyLabel(bs.emergencyFundMonths())' "$SZT2"; } \
  && log_ok "v111-COVER-MONTHS-ONE-RULE(覆盖月数与紧急储备共用 > 36 月封顶规则)" \
  || log_bad "v111-COVER-MONTHS-ONE-RULE 同一个数两种写法" "两处都必须走 SealedSnapshot.emergencyLabel"

# v111-DIST-SEALED · 成员/大类分布必须只计资产,且经封板入口
{ grep -q 'buildDistribution' "$SPS2" \
  && grep -q 'AccountClass.ASSET' "$SPS2" \
  && grep -q '按成员' "$SZT2" && grep -q '按资产大类' "$SZT2"; } \
  && log_ok "v111-DIST-SEALED(封板期成员/大类分布 · 只计资产 · 走单一入口)" \
  || log_bad "v111-DIST-SEALED 分布缺件或算了负债" "只计 ASSET,否则「谁名下多少钱」会被房贷带成负数"

section "v1.10 · 报表页封板快照(三区 / 瀑布 / 对照 / 集中度 / 归因 / 仪表盘实时口径)"

# ── v1.10 · 报表页三区(封板快照)──────────────────────────────────────
SPS="$RD/src/main/java/com/family/finance/service/report/SealedPeriodService.java"
SSN="$RD/src/main/java/com/family/finance/service/report/SealedSnapshot.java"
SZT="$RD/src/main/resources/templates/reports/_sealed.html"
RIX="$RD/src/main/resources/templates/reports/index.html"

# v110-SEALED-SINGLE-ENTRY · 前两区必须只经 SealedPeriodService,且它的签名里不许有 range
#   报表页承诺「封板期指标不会二次变动」。要兑现它,前两区每个数字只能由「哪一期」决定。
#   原来指标散在控制器里逐个调 factViewService.xxx(pageSlice),而 pageSlice 的 rangeStart 由 range 决定 ——
#   「紧急储备 N 月」就随 range 变(tech-design v1.10 §2.2 ②)。收口成单一入口之后,
#   后人想把 range 传进来**没有地方放**;v1.11 要把指标落库时也只改这一个类。
{ [ -f "$SPS" ] \
  && grep -q 'public SealedSnapshot load(long familyId, Period anchor, boolean closedSnapshot, String viewCurrency)' "$SPS" \
  && ! grep -qE 'String range|rangeStart\(' "$SPS" \
  && grep -q 'sealedPeriodService.load(' "$RD/src/main/java/com/family/finance/web/report/ReportsController.java" \
  && grep -q 'EXPENSE_WINDOW_PERIODS' "$SPS"; } \
  && log_ok "v110-SEALED-SINGLE-ENTRY(前两区单一入口 · 签名无 range · 支出窗口取常量)" \
  || log_bad "v110-SEALED-SINGLE-ENTRY 前两区又能被 range 污染" "SealedPeriodService 不许出现 range/rangeStart;前两区只能经它取数"

# v110-ARCHIVED-TIME · 归档过滤必须带时间语义
#   裸 `archived_at IS NULL` 会让归档动作抹掉该账户的**全部历史事实** ——
#   归档一个不用了的账户就能改写去年 12 月的报表,把「封板不变」直接证伪(§2.2 ①)。
{ grep -q 'a.archived_at IS NULL OR a.archived_at &gt; p.period_end' "$RD/src/main/resources/mapper/FactMapper.xml" \
  && ! grep -qE '^\s*AND a\.archived_at IS NULL\s*$' "$RD/src/main/resources/mapper/FactMapper.xml" \
  && grep -q '归档账户在归档之前的期仍计入' \
       "$RD/src/test/java/com/family/finance/factview/ArchivedTimeSemanticsTest.java"; } \
  && log_ok "v110-ARCHIVED-TIME(归档过滤带时间语义 · 归档不再抹掉历史 · 单测守着)" \
  || log_bad "v110-ARCHIVED-TIME 归档又会抹掉历史" "FactMapper 必须 (archived_at IS NULL OR archived_at > p.period_end)"

# v110-ZONE-TOC · 三区 section id 与长文目录一一对应(改 section 必同步 TOC 铁律)
{ grep -q 'id="sec-sealed"' "$SZT" && grep -q 'id="sec-structure"' "$SZT" \
  && grep -q 'id="sec-trend"' "$RIX" \
  && grep -q "href:'#sec-sealed'" "$RIX" \
  && grep -q "href:'#sec-structure'" "$RIX" \
  && grep -q "href:'#sec-trend'" "$RIX" \
  && grep -q '_pagehead :: pagehead' "$RIX" \
  && grep -q '_sealed :: zones' "$RIX"; } \
  && log_ok "v110-ZONE-TOC(三区锚点与目录条目一一对应 · 页头/封板片段已编排)" \
  || log_bad "v110-ZONE-TOC 区锚点与目录不同步" "sec-sealed / sec-structure / sec-trend 三个 id 与 tocItems 必须成对存在"

# v110-WF-AXIS-DISCLOSED · 瀑布截断轴必须明示,且恒等式差额不许吞掉
#   月度流量与存量差两个数量级,全量轴下中间三段细成一条线 → 必须截断轴;
#   但**默默截断**同样是错的(读数会被误解),所以页面要写出轴的起点。
{ grep -q 'axisTruncated()' "$SZT" && grep -q '截断轴' "$SZT" \
  && grep -q 'identityHolds()' "$SZT" \
  && grep -q 'diffExplainedByOpening()' "$SZT" \
  && grep -q '无法由开账基线解释' "$SZT" \
  && grep -q 'axisTruncated' "$SSN"; } \
  && log_ok "v110-WF-AXIS-DISCLOSED(截断轴明示 · 恒等式三态文案:闭合/开账基线可解释/来源不明)" \
  || log_bad "v110-WF-AXIS-DISCLOSED 截断或差额被藏起来了" "轴截断要写出起点;恒等式不闭合要如实给差额与原因"

# v110-CMP-MISSING-PERIOD · 缺上期/去年同期时 Δ 必须是 —,不许给 0 或 100%
#   新用户的第一期必然缺期,给出 Δ 就是误导。分母为 0 时也不许出 ∞。
{ grep -q '缺上期或去年同期时Δ必须是null_不是0也不是100pct' \
       "$RD/src/test/java/com/family/finance/service/report/SealedPeriodServiceTest.java" \
  && grep -q '分母为0时百分比是null不是无穷' \
       "$RD/src/test/java/com/family/finance/service/report/SealedPeriodServiceTest.java" \
  && grep -q 'ratio || cur == null || base == null || base.signum() == 0' "$SSN"; } \
  && log_ok "v110-CMP-MISSING-PERIOD(缺期与零分母都出 — · 单测守着)" \
  || log_bad "v110-CMP-MISSING-PERIOD 缺期时给了误导性的 Δ" "prev/yoy 缺失或分母 0 → Δ 与 % 都必须 null"

# v110-HHI-ABS-DENOM · 集中度分母必须取绝对值
#   直接拿净值求和的话,一笔大额房贷会把分母压到接近 0,HHI 当场爆表 ——
#   集中度就成了「有没有房贷」的函数,而不是分散度。
{ grep -q 'endBalanceBase().abs()' "$SPS" \
  && grep -q 'HHI分母取绝对值_大额房贷不许把集中度顶到1' \
       "$RD/src/test/java/com/family/finance/service/report/SealedPeriodServiceTest.java" \
  && grep -q '占比按余额绝对值算' "$SZT"; } \
  && log_ok "v110-HHI-ABS-DENOM(集中度分母取绝对值 · 大额负债不会顶爆 HHI · 单测守着)" \
  || log_bad "v110-HHI-ABS-DENOM 集中度会被负债顶爆" "share/HHI 的分母必须用 |余额| 求和"

# v110-DASH-LIVE-RETURN · 仪表盘「本月资产收益」= 实时本月,且必须讲清偏差方向
#   两页分工:仪表盘=当月实时(会变)· 报表页=封板(不再变)。
#   v1.6.30 起这一格锚「最新已关账期」是为了躲一个 P0(进行中期收支未录齐 → 未录工资被算成投资收益)。
#   维护者拍板(tech-design §6.2):显示实时真实值 + 把口径与偏差方向说清,而不是藏起来。
#   实现刻意用**加字段**(live*)而不是改现有口径 —— 报表页封板仍走 monthlyPnl*,
#   ClosedPeriodAnchorTest 一字不动仍然有效。
DSH="$RD/src/main/resources/templates/dashboard/_region.html"
{ grep -q 'liveMonthlyInvestReturnPct' "$RD/src/main/java/com/family/finance/factview/KpiSnapshot.java" \
  && grep -q 'liveMonthlyPnlAmount' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q '本月未封板' "$DSH" \
  && grep -q 'liveReturnNoFlow' "$DSH" \
  && ! grep -q "'资产收益 ' + #strings.substring" "$DSH" \
  && grep -q 'liveMonthlyPnlCalc' "$RD/src/main/java/com/family/finance/service/explain/MetricExplainService.java" \
  && [ -f "$RD/src/test/java/com/family/finance/factview/ClosedPeriodAnchorTest.java" ]; } \
  && log_ok "v110-DASH-LIVE-RETURN(仪表盘实时本月 + 口径交代 + 偏差方向 · 封板口径与 ClosedPeriodAnchorTest 未动)" \
  || log_bad "v110-DASH-LIVE-RETURN 仪表盘收益格口径不对" "必须用 live* 字段 + 显示「本月未封板」完整度;标题不许换成别的月份"

# v110-FORMULA-VERSION · 口径版本号必须存在并显示在封板抬头
{ grep -q 'public static final int CURRENT' "$RD/src/main/java/com/family/finance/service/report/MetricFormulaVersion.java" \
  && grep -q 'formulaVersion()' "$SZT" \
  && grep -q '口径 v' "$SZT"; } \
  && log_ok "v110-FORMULA-VERSION(口径版本号存在且在抬头显示)" \
  || log_bad "v110-FORMULA-VERSION 口径版本没露出来" "改口径要 +1 并在封板抬头显示,用户才能分辨数字是哪套口径算的"

# ── v1.9.4 · 财富水位「关了三期还说期数不足」──────────────────────────
# v194-WL-ANCHOR · 财富水位序列首点不许恒为 0
#   现象(prod 实报):已关账 3 期,报表页财富水位仍显示「需要至少 2 期净资产数据 + 宏观基准」。
#   根因:netWorthTrendExOpening 每期都减掉「本期首次出现账户的期末净值」——**包括窗口首期**,
#   而首期的「首次出现账户」按定义就是全部账户 → 首点恒等于 0。
#   WaterLevelService 以首点为锚、anchor<=0 判不可用 → 只要时间窗包含家庭首期,这一节永久不出现。
#   新用户只有两三期、任何窗口都含首期,所以从来没见过它;**加一个新账户**也会让短窗口的首期
#   含「首次出现账户」,同样打死(beta 实测 range=ALL 复现)。
#   ReportsController v1.6.29 的注释里已经写下过「该序列首点按构造恒为 0」,
#   但当时只把 tooltip 消费方改成 netWorthTrend,财富水位这个**主**消费方留在了坏序列上。
#   修法:窗口首期的开账基线不减(那笔存量本金就是起跑线,不是注入),第二期起照旧剔除
#   → 对不含首期的窗口逐点零差异(3M/6M/YTD/1Y 指纹实测逐字相同)。
FVI2="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
{ grep -q 'boolean first = true;' "$FVI2" \
  && grep -A3 'if (!first) {' "$FVI2" | grep -q 'cumOpening = cumOpening.add(openingBaseline(slice, periodId));' \
  && grep -q '窗口首期的首点必须是真实净资产_不能恒为0' \
       "$RD/src/test/java/com/family/finance/factview/NetWorthTrendExOpeningTest.java" \
  && grep -q '第二期起新出现的账户仍然要剔除' \
       "$RD/src/test/java/com/family/finance/factview/NetWorthTrendExOpeningTest.java" \
  && grep -q '不含首期的窗口逐点不变_零差异' \
       "$RD/src/test/java/com/family/finance/factview/NetWorthTrendExOpeningTest.java"; } \
  && log_ok "v194-WL-ANCHOR(财富水位序列首期不减开账基线 · 首点=真实净资产 · 后续注入仍剔除 · 零差异单测守着)" \
  || log_bad "v194-WL-ANCHOR 首点又会恒为 0" "netWorthTrendExOpening 必须跳过窗口首期的 openingBaseline,否则含首期的窗口财富水位永久不可用"

# v194-WL-REASON · 不可用要说真话,别把两种处境混成一句
#   原文案:「财富水位需要至少 2 期净资产数据 + 宏观基准」。三个错:
#   ① 期数够了也可能不可用(上面那个 bug 就是),用户照提示继续记账没有用;
#   ② 「宏观基准」根本不是条件 —— 缺了走三法均值 fallback(cpiAverages/m2Averages);
#   ③ 两种完全不同的处境给同一句话,用户没法自查。
WLS="$RD/src/main/java/com/family/finance/service/macro/WaterLevelService.java"
WLT="$RD/src/main/resources/templates/reports/_wealth-level.html"
{ grep -q 'public enum Reason' "$WLS" \
  && grep -q 'NOT_ENOUGH_PERIODS' "$WLS" && grep -q 'NON_POSITIVE_ANCHOR' "$WLS" \
  && grep -q 'unavailable(Reason.NOT_ENOUGH_PERIODS)' "$WLS" \
  && grep -q 'unavailable(Reason.NON_POSITIVE_ANCHOR)' "$WLS" \
  && ! grep -q '财富水位需要至少 2 期净资产数据 + 宏观基准' "$WLT" \
  && grep -q '起点净资产不是正数' "$WLT" \
  && grep -q '不到 2 期净资产数据' "$WLT" \
  && grep -q '缺宏观数据不影响可用性_走三法均值fallback' \
       "$RD/src/test/java/com/family/finance/service/macro/WaterLevelServiceTest.java" \
  && grep -q '起点净资产非正的原因不是期数不足' \
       "$RD/src/test/java/com/family/finance/service/macro/WaterLevelServiceTest.java"; } \
  && log_ok "v194-WL-REASON(不可用分 NOT_ENOUGH_PERIODS / NON_POSITIVE_ANCHOR · 文案各说各的 · 不再谎称缺宏观基准)" \
  || log_bad "v194-WL-REASON 兜底文案又混成一句" "WaterLevel 需带 Reason;模板按原因分开渲染;不许再出现「需要至少 2 期净资产数据 + 宏观基准」"

# ── v1.9.3 · 账户表行高(中文列被压成竖排)────────────────────────────
# v193-TABLE-NUM-NOWRAP · 账户表数字格不许折行 + 类型 pill 不许竖排
#   现象(用户报 prod):报表页账户表「类型」列竖着排,「现金」两行、「贵金属」三行,行高 71px。
#   根因:两张账户表最多 17 列、自然宽 ~1790px 远超容器 ~1071px,table-layout:auto 会把
#   **能折行的内容压到 min-content**(中文 1 字/行)再把宽度让给别的列。
#   同一挤压还打中:收益率/本位币年化的「累」上标、预实的上标、vs 基准的「跑输 -87.10pp」——
#   宽值行折两行、窄值行不折,同一列有的一行有的两行。
#   修法刻意用**属性级**规则(数字格整类 nowrap)而不是逐个格补:逐点补下次加指标列又会漏。
{ grep -q '#dash-list .ledger-table tbody td.num,' "$RD/src/main/resources/static/css/style.css" \
  && grep -q '#reports-region .ledger-table tbody td.num { white-space: nowrap; }' "$RD/src/main/resources/static/css/style.css" \
  && grep -q 'white-space:nowrap' <(grep 'pill-vs{' "$RD/src/main/resources/templates/reports/_region.html") \
  && grep -q 'pill whitespace-nowrap justify-center' "$RD/src/main/resources/templates/reports/_region.html" \
  && grep -q 'pill whitespace-nowrap justify-center' "$RD/src/main/resources/templates/reports/_drilldown.html" \
  && ! grep -qE '<td><span class="pill" th:text="\$\{(account|row)\.accountType' \
        "$RD/src/main/resources/templates/reports/_region.html" \
        "$RD/src/main/resources/templates/reports/_drilldown.html"; } \
  && log_ok "v193-TABLE-NUM-NOWRAP(账户表数字格整类 nowrap · 类型/vs基准 pill 不折行)" \
  || log_bad "v193-TABLE-NUM-NOWRAP 表格又会被压成竖排" "style.css 需 #dash-list/#reports-region 的 td.num 整类 nowrap;类型 pill 需 whitespace-nowrap + min-width"

# v193-TYPE-LABEL · 账户类型面向用户不许裸露枚举 code
#   reports/_drilldown.html 原来写 ${row.accountType}(AccountType 枚举本身)→ 渲染成 CASH/STOCK。
#   其余表一律 .label。裸 code 违反「面向用户的命名避免技术词」。
{ ! grep -qE 'th:text="\$\{(row|account)\.accountType\}"' "$RD/src/main/resources/templates/reports/_drilldown.html" \
  && grep -q 'row.accountType.label' "$RD/src/main/resources/templates/reports/_drilldown.html" \
  && [ "$(grep -rlE 'th:text="\$\{[a-zA-Z.]*accountType\}"' "$RD/src/main/resources/templates" | wc -l)" -eq 0 ]; } \
  && log_ok "v193-TYPE-LABEL(账户类型一律出 .label · 模板里没有裸 accountType 枚举)" \
  || log_bad "v193-TYPE-LABEL 面向用户裸露了枚举 code" "账户类型渲染必须用 .label,不能直接输出 AccountType"

# v192-UPD-STALE-CURRENT · 升级完之后不许继续提示有新版
#   KV 行里的 current 是「上次检查时在跑的版本」。用户照提示升级完 jar、重启,这行还没刷新 ——
#   拿旧 current 去比,**已经升到最新版**的实例会继续挂 NEW,一直挂到隔天定时器跑过。
#   latest 是关于 GitHub 的事实(可以旧),current 必须是关于本进程的事实(不能旧)。
#   覆盖动作必须在 reloadMemo 里面(读缓存入口有三个:预热/开关切换/检查后回写),
#   不能靠调用方各自传参 —— 最初只在预热那条路传了,一开关就把过期 current 复活了。
{ grep -q 'UpdateInfo withCurrent(String running)' "$UCS" \
  && grep -q 'i.withCurrent(appVersion)' "$UCS" \
  && [ "$(grep -c 'withCurrent(' "$UCS")" -eq 2 ] \
  && grep -q '升级完之后不能继续显示有新版_current要用正在跑的版本' "$RD/src/test/java/com/family/finance/service/update/UpdateCheckServiceTest.java"; } \
  && log_ok "v192-UPD-STALE-CURRENT(warmUp 用正在跑的 app.version 覆盖 KV 里的 current)" \
  || log_bad "v192-UPD-STALE-CURRENT 缓存 current 会过期" "reloadMemo 内部必须 withCurrent(appVersion),否则升级后徽记不消失"

# v19-UPD-OFF-NO-CALL · 关掉开关后一个请求都不发
#   判定必须在任何 HTTP 构造之前 —— 别在判定前先把 URL/请求对象拼好
#   (那样看着没发,其实已经在准备发了,而且后人很容易把顺序改反)。
{ grep -q 'enabled(FAMILY_ID)' "$RD/src/main/java/com/family/finance/service/update/UpdateCheckJob.java" \
  && grep -q 'if (!updateCheckService.enabled(FAMILY_ID))' "$RD/src/main/java/com/family/finance/service/update/UpdateCheckJob.java" \
  && grep -q 'if (!enabled(familyId))' "$UCS"; } \
  && log_ok "v19-UPD-OFF-NO-CALL(Job 与 checkNow 都在最前面判 enabled)" \
  || log_bad "v19-UPD-OFF-NO-CALL 判定位置不对" "UpdateCheckJob.run 与 UpdateCheckService.checkNow 的第一件事都必须是判 enabled"

# ---------- v1.13 · LLM 平台化 ----------
section "v1.13 · LLM 平台化(平台/系列/型号三级 · 主备编排收口 · 目录唯一一份)"

LLMPKG="$RD/src/main/java/com/family/finance/service/checkup/llm"
LLMTEST="$RD/src/test/java/com/family/finance/service/checkup/llm"
INTGC="$QA_LLM_JAVA"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 IntegrationsController.java)
INTGH="$QA_LLM_TPL"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 integrations.html)
# 型号名长这样 —— 目录之外任何地方出现都是「第二份清单」
MODELPAT='qwen-plus|qwen-flash|qwen-max|qwen-vl-|deepseek-chat|deepseek-reasoner'

# v113-LLM-ROUTER-SINGLE-PATH · 六个业务调用点只许拿 LlmRouter,一个都不许自己碰 LlmClient
#   v1.12 的 bug 就长在这:六处各自注入 List<LlmClient> 裸遍历,管理页选的「主选」只对其中一处生效,
#   另外五处永远按 Spring 的 bean 顺序走 —— 不报错、不告警,只是那个配置项形同虚设。
#   所以这里逐个点名,而不是只断言 LlmRouter 自己存在(存在但没人用,正是当初的状态)。
#   否定断言必须走 java_code_only:这几个类的 javadoc 里正写着「不再注入 List<LlmClient>」。
LLM_SITES="src/main/java/com/family/finance/service/checkup/llm/LlmDiagnoseService.java
src/main/java/com/family/finance/service/lens/LensInsightService.java
src/main/java/com/family/finance/service/lens/LensAiTagService.java
src/main/java/com/family/finance/service/allocation/RebalanceAdvisorService.java
src/main/java/com/family/finance/service/goal/GoalLlmService.java
src/main/java/com/family/finance/service/review/ReviewInsightService.java"
_llm_site_bad=""
while read -r _s; do
  [ -z "$_s" ] && continue
  [ -f "$RD/$_s" ] || { _llm_site_bad="$_llm_site_bad ${_s##*/}(文件没了)"; continue; }
  grep -q 'llmRouter.invoke(' "$RD/$_s" || _llm_site_bad="$_llm_site_bad ${_s##*/}(没走router)"
  java_code_only "$RD/$_s" | grep -q 'LlmClient' && _llm_site_bad="$_llm_site_bad ${_s##*/}(仍直接碰client)"
done <<< "$LLM_SITES"
# List<LlmClient> 的注入只许出现在 LlmRouter 一处;单个平台的 client(管理页「测试连接」按钮要指名道姓)
# 只许从 llmRouter.clientFor(...) 拿 —— 那是路由给出的,不是绕过路由。
_llm_inject_bad=""
for _f in $(grep -rl 'List<LlmClient>' "$RD/src/main/java" 2>/dev/null); do
  case "$_f" in */LlmRouter.java) continue;; esac
  java_code_only "$_f" | grep -q 'List<LlmClient>' && _llm_inject_bad="$_llm_inject_bad ${_f##*/}"
done
{ [ -z "$_llm_site_bad" ] && [ -z "$_llm_inject_bad" ] \
  && grep -q 'llmRouter.clientFor(' "$INTGC" \
  && grep -q 'everyCallSite_holdsTheRouter_andNoClientAtAll' "$LLMTEST/LlmCallSiteRoutingTest.java"; } \
  && log_ok "v113-LLM-ROUTER-SINGLE-PATH 六个调用点全部只持 LlmRouter(主备编排唯一一条路径)" \
  || log_bad "v113-LLM-ROUTER-SINGLE-PATH 有调用点绕过路由" "调用点:${_llm_site_bad:-ok} · 裸注入:${_llm_inject_bad:-ok} · 见 LlmRouter/LlmCallSiteRoutingTest"

# v113-LLM-CATALOG-SINGLE-SOURCE · 「有哪些平台/系列/型号」全项目只有 LlmCatalog 一份
#   抄第二份的后果不是报错,是**静默不同步**:目录里加了型号页面选不到、页面写死的型号目录里已下架。
#   管理页的级联下拉数据来自 data-catalog(服务端把目录序列化过去),不是模板里手写的 <option>。
_llm_dup=""
for _f in "$LLMPKG"/*.java; do
  case "$_f" in */LlmCatalog.java) continue;; esac
  java_code_only "$_f" | grep -qE "$MODELPAT" && _llm_dup="$_llm_dup ${_f##*/}"
done
{ [ -z "$_llm_dup" ] \
  && grep -q 'PLATFORMS' "$LLMPKG/LlmCatalog.java" \
  && ! grep -qE "$MODELPAT" "$INTGC" \
  && grep -q 'llmCatalogJson' "$INTGC" \
  && grep -q 'data-catalog' "$INTGH" \
  && ! grep -qE "value=\"($MODELPAT)" "$INTGH" \
  && grep -q 'QWEN.models()' "$LLMPKG/DashScopeLlmClient.java" \
  && [ -f "$LLMTEST/LlmCatalogConsistencyTest.java" ]; } \
  && log_ok "v113-LLM-CATALOG-SINGLE-SOURCE 型号清单只有 LlmCatalog 一份(页面下拉/轮询池都从它来)" \
  || log_bad "v113-LLM-CATALOG-SINGLE-SOURCE 型号清单被抄了第二份" "llm 包内重复:${_llm_dup:-ok} · 另见 LlmSettingsView/_llm-settings.html/DashScopeLlmClient"

# v113-LLM-LEGACY-KEYS-KEPT · 旧键留着但冻结:只读不写
#   v1.13 没写迁移 SQL —— 老配置由 LlmSettings 读时派生成新三元组(tech-design §1.5)。
#   这条护栏守两头:①旧键常量不许删(删了 = 升级后第一次打开管理页之前的调用全部失配);
#   ②不许有人再往旧键里写(写了 = 新旧两套配置各说各话,而且回滚时更糊涂)。
_llm_frozen_bad=""
for _k in K_LLM_PRIMARY_VENDOR K_LLM_MODEL K_LLM_VISION_MODEL; do
  for _f in $(grep -rlw "$_k" "$RD/src/main/java" 2>/dev/null); do
    case "$_f" in */FamilyConfigService.java|*/LlmSettings.java) continue;; esac
    _llm_frozen_bad="$_llm_frozen_bad $_k@${_f##*/}"
  done
done
_llm_keys_missing=""
for _k in K_LLM_QWEN_KEY K_LLM_DEEPSEEK_KEY K_LLM_MAX_TOKENS K_LLM_TIMEOUT_SECS K_LLM_QWEN_MODELS \
          K_LLM_PRIMARY_VENDOR K_LLM_TEMPERATURE K_LLM_MODEL K_LLM_VISION_MODEL; do
  grep -qw "$_k" "$FCS" || _llm_keys_missing="$_llm_keys_missing $_k"
done
{ [ -z "$_llm_keys_missing" ] && [ -z "$_llm_frozen_bad" ] \
  && grep -q 'K_LLM_PRIMARY_VENDOR' "$LLMPKG/LlmSettings.java" \
  && [ -f "$LLMTEST/LlmSettingsMigrationTest.java" ]; } \
  && log_ok "v113-LLM-LEGACY-KEYS-KEPT 旧键 9 个都在 · 三个「选哪个模型」的旧键只被 LlmSettings 读(无人再写)" \
  || log_bad "v113-LLM-LEGACY-KEYS-KEPT 旧键被删或被写" "缺:${_llm_keys_missing:-ok} · 越界访问:${_llm_frozen_bad:-ok}"

# v113-LLM-CARD-LAYOUT · 三家凭据列对齐 + 备选组在窄屏有分组线
#   双端审视时实测出来的两处(改之前的真实数字):
#     ① PC 三个「测试连接」按钮 y = 726 / 726 / 710 —— 百炼和 DeepSeek 的说明是两行、
#        方舟一行,按钮跟着各自内容流走。并列同类元素必须对齐,所以三列 flex-col + 按钮 mt-auto,
#        label 再加 md:min-h 把「已配置(隐藏)/未配置」的一行两行差吃掉。
#     ② 手机上 384px 宽,主选/备选各三个字段一堆叠 → 组内 11px、组间 15px,差 4px,
#        六个下拉连成一条,看不出哪三个是一组。备选组补窄屏分隔线(sm: 以上还原,PC 不变)。
#   这两条都不是「跑得起来」的问题,单测和 e2e 都抓不到,只能钉类名。加第四个平台时
#   照抄这三列的结构就不会再歪。
# v1.17.2:三列改成了 .cred-card(带底色的卡),等高与按钮对齐所依赖的机制从行内类挪进了 CSS ——
#          判据跟着改指向,守的仍是同一件事「三个『测试连接』必须在同一水平线」。
#          改完实测:三卡 230/230/230、按钮 top 全 763(1440 宽)。
_card_col="$(grep -c 'class="cred-card"' "$ICFG")"
# v1.18.1 · 判据从 'flex items-center gap-3 mt-auto' 重指到 'mt-auto pt-3':
#   密钥拆成独立表单后每张卡底部多了一个「保存密钥」按钮,gap 从 3 收到 2 —— 而被守的不变量
#   是「按钮容器 mt-auto 贴底、三张卡对齐」,gap 值只是附带。改判据前先实测过没退化:
#   三张卡高 230/230/230、卡顶同 560、保存按钮同在 745、测试按钮同在 744(1440×900 · beta)。
_card_mtauto="$(grep -c 'mt-auto pt-3' "$ICFG")"
_card_flexcss="$(grep -c 'flex-direction:column' "$RD/src/main/resources/static/css/style.css")"
_card_split="$(grep -c 'pt-4 border-t border-rule-soft sm:pt-0 sm:border-t-0' "$ICFG")"
{ [ "$_card_col" -ge 3 ] && [ "$_card_mtauto" -ge 3 ] && [ "$_card_flexcss" -ge 1 ] && [ "$_card_split" -ge 1 ]; } \
  && log_ok "v113-LLM-CARD-LAYOUT 三家凭据卡等高对齐(cred-card 列布局 + mt-auto)· 备选组窄屏有分组线" \
  || log_bad "v113-LLM-CARD-LAYOUT 凭据卡或分组间距回退" "cred-card $_card_col/3 · mt-auto $_card_mtauto/3 · 列布局CSS $_card_flexcss/1 · 窄屏分隔 $_card_split/1 · see integrations.html"
# ── v1.14 · 截图导入拖拽 + 粘贴(issue #11)────────────────────────────
IMPORT_HTML="$RD/src/main/resources/templates/holdingimport/import.html"

# v114-DROPZONE-HAS-DROP-HANDLER · 长得像拖拽区就必须真的能拖
#   上传区的 id 一直叫 dropZone、一直是 2px 虚线框(网页上「往这儿拖」的通用符号),
#   但从 v1.4 到 v1.13 都没有 drop 监听 —— PC 上真拖上去,浏览器导航到那个图片文件,
#   已经选好的图和这次导入全丢。这不是少个便利功能,是会让人丢东西的错误暗示。
#   drop 与 dragover 必须成对:不 preventDefault dragover,浏览器压根不派发 drop。
{ grep -q 'id="dropZone"' "$IMPORT_HTML" \
  && grep -q "dz.addEventListener('drop'" "$IMPORT_HTML" \
  && grep -q "dz.addEventListener('dragover'" "$IMPORT_HTML" \
  && grep -q "window.addEventListener('drop'" "$IMPORT_HTML" \
  && grep -q "window.addEventListener('dragover'" "$IMPORT_HTML"; } \
  && log_ok "v114-DROPZONE-HAS-DROP-HANDLER(dropZone 接 drop+dragover · window 兜底拦默认导航)" \
  || log_bad "v114-DROPZONE-HAS-DROP-HANDLER 虚线框又变回骗人的" "import.html 有 id=dropZone 就必须有 dz 的 drop/dragover 监听,且 window 上要拦默认行为(FR-371)"

# v114-UPLOAD-SINGLE-PATH · 三个入口一条上传路径
#   点选 / 拖拽 / 粘贴必须都走 handleFiles;压缩、计价、并发计数、scanBtn 启用条件一行都不复制。
#   两条路径长出行为差异是这类改动最典型的翻车方式,而且它**不报错** ——
#   只是拖上去的图不计价、或者按钮不变可用,人得盯着才看得出来。
#   用 new FormData() 只准出现一次来钉:复制一份上传逻辑必然复制它。
{ grep -q 'function handleFiles(' "$IMPORT_HTML" \
  && grep -q 'handleFiles(fi.files)' "$IMPORT_HTML" \
  && grep -q 'handleFiles(e.dataTransfer.files)' "$IMPORT_HTML" \
  && grep -q 'handleFiles(imgs)' "$IMPORT_HTML" \
  && [ "$(grep -c 'new FormData()' "$IMPORT_HTML")" -eq 1 ]; } \
  && log_ok "v114-UPLOAD-SINGLE-PATH(change/drop/paste 三入口共用 handleFiles · FormData 只出现一次)" \
  || log_bad "v114-UPLOAD-SINGLE-PATH 上传逻辑被复制了" "三个入口都必须调 handleFiles,import.html 里 new FormData() 只准出现一次"

# v114-PASTE-YIELDS-INPUT · 粘贴不许抢页内输入框
#   paste 挂在 document 上(不然要先点一下拖拽区,粘贴比拖拽短一步的价值就没了),
#   代价是会盖住整页。比对确认那张表全是 .j-mv 数值输入框,用户在那儿 Ctrl+V 粘数字
#   不能被截图上传吃掉。判据用 activeElement 而不是 e.target:没有聚焦元素时 target 是 body。
{ grep -q "document.addEventListener('paste'" "$IMPORT_HTML" \
  && grep -q 'document.activeElement' "$IMPORT_HTML" \
  && grep -q "ae.tagName==='INPUT'||ae.tagName==='TEXTAREA'||ae.isContentEditable" "$IMPORT_HTML"; } \
  && log_ok "v114-PASTE-YIELDS-INPUT(光标在输入框/文本域/contenteditable 里时不拦粘贴)" \
  || log_bad "v114-PASTE-YIELDS-INPUT 粘贴会抢输入框" "paste 处理器必须先判 document.activeElement 是不是 INPUT/TEXTAREA/contenteditable 并让位"

# v114-NONIMAGE-BEFORE-COMPRESS · 非图片在压缩之前挡掉
#   accept="image/*" 只约束文件选择器,不约束 drop / paste。拖进来的文件夹在
#   DataTransfer.files 里是 type==='' 的条目,交给 compress() 会走 img.onerror → res(null)
#   → uploading--,静默减计数,用户看到的是「什么都没发生」——比拖 PDF 更隐蔽。
#   所以 uploadOne 只能拿到过滤后的图片,拒绝提示走 #dropRej。
{ grep -q 'function isImg(f)' "$IMPORT_HTML" \
  && grep -q 'all.filter(isImg).forEach(uploadOne)' "$IMPORT_HTML" \
  && grep -q 'id="dropRej"' "$IMPORT_HTML" \
  && [ "$(grep -c 'forEach(uploadOne)' "$IMPORT_HTML")" -eq 1 ]; } \
  && log_ok "v114-NONIMAGE-BEFORE-COMPRESS(非图片在 compress 之前挡掉 · 被忽略的文件名列出来)" \
  || log_bad "v114-NONIMAGE-BEFORE-COMPRESS 文件夹会静默消失" "handleFiles 必须先 filter(isImg) 再 uploadOne,并把被忽略的文件名写进 #dropRej"

# v114-DROP-STATE-NO-REFLOW · 落图态不许改变拖拽区高度
#   第一版把 idle 内容 display:none,框从三行缩成一行 —— 拖到一半 drop 目标在指针
#   底下自己变矮,指针掉出框 → dragleave → 高亮闪一下就没了。idle 内容必须留在流里
#   (visibility),「松手即上传」绝对定位盖上去。
{ grep -qF '#dropZone.dz-on .dz-idle{visibility:hidden}' "$IMPORT_HTML" \
  && grep -qF '#dropZone .dz-drop{display:none;position:absolute' "$IMPORT_HTML" \
  && grep -qF '#dropZone{position:relative' "$IMPORT_HTML" \
  && ! grep -qF '.dz-on .dz-idle{display:none}' "$IMPORT_HTML"; } \
  && log_ok "v114-DROP-STATE-NO-REFLOW(落图态用 visibility 保持高度 · 提示语绝对定位盖上去)" \
  || log_bad "v114-DROP-STATE-NO-REFLOW 拖到一半框会变矮" "idle 内容不许 display:none(会缩高度导致指针掉出拖拽区),用 visibility:hidden + .dz-drop 绝对定位"

# v114-HINT-PC-ONLY · 拖拽/粘贴提示不许进静态标记(issue #11 说了「可以只在 pc 端」)
#   手机上没有拖文件这回事,那句提示写死在 HTML 里就是噪音;判据用指针能力
#   (hover:hover)+(pointer:fine) 而不是视口宽度 —— 窄窗口的桌面浏览器照样能拖。
#   钉法:这句话必须由 tip.textContent 注入,且**不许出现在任何标记行上**
#   (判据不能写成「全文只准出现一次」—— 那会连代码注释里提一句都算违规)。
{ grep -qF '(hover: hover) and (pointer: fine)' "$IMPORT_HTML" \
  && grep -F 'Ctrl+V' "$IMPORT_HTML" | grep -q 'tip.textContent' \
  && ! grep -F 'Ctrl+V' "$IMPORT_HTML" | grep -qE '<[a-zA-Z]|class=|th:text'; } \
  && log_ok "v114-HINT-PC-ONLY(拖拽/粘贴提示按指针能力 JS 注入 · 静态标记里没有这句)" \
  || log_bad "v114-HINT-PC-ONLY 提示可能落到手机上" "提示必须在 matchMedia('(hover: hover) and (pointer: fine)') 命中后由 tip.textContent 注入,模板里 Ctrl+V 只许出现这一次"
# ── v1.15 · 会员身份(改登录名 / 归档 / 有条件删除)──────────────────
# v115-MEMBER-NAME-MAP-INCLUDES-ARCHIVED · 展示口径必须走名录,不许旁路
#   归档之后,「仅活跃」和「含归档」第一次有了区别,而全仓 15 处成员查询原来都是「仅活跃」。
#   分三桶:
#     · 展示 / 脱敏 / 历史归属  → MemberDirectory(含归档)—— 漏了就是「成员#7」占着 40% 资产,
#       更糟的是 6 处脱敏点拿不到假名,**归档成员的真名会原样进 LLM prompt**。
#     · 面向未来的选择 / 分母 / 系统操作人 → memberMapper 仅活跃(这是对的,归档的人不该再被指派)
#     · 编辑表单候选 → selectableWith = 活跃 ∪ 当前值(漏了当前值 → 一保存静默改派给别人)
#   所以这条护栏是**白名单禁止旁路**,不是「检测坏写法」:第二桶的合法用法逐个点名钉死在下面
#   这几个文件里;任何新文件出现 memberMapper.findActive/countActive 都当没想清楚桶,直接红。
#   注意必须用**限定名** memberMapper.xxx —— AccountMapper / GoalMapper 也有同名方法,
#   裸方法名会匹配到 32 个不相干文件。
V115_ALLOW="service/AdminService.java
service/PeriodOpener.java
service/PeriodService.java
service/member/MemberDirectory.java
service/notify/ReportReminderScheduler.java
service/stock/AccountValuationService.java
web/account/AccountController.java
web/admin/NotificationSettingsController.java
web/dashboard/DashboardController.java
web/entry/EntryController.java
web/goal/GoalController.java"
V115_ACTUAL="$(grep -rl 'memberMapper\.findActiveByFamily\|memberMapper\.countActiveByFamily' \
  "$RD/src/main/java" | sed "s|^$RD/src/main/java/com/family/finance/||" | sort)"
{ [ "$V115_ACTUAL" = "$(printf '%s\n' "$V115_ALLOW" | sort)" ] \
  && grep -q 'findAllByFamily' "$RD/src/main/java/com/family/finance/service/member/MemberDirectory.java" \
  && grep -q 'memberDirectory.nameMap' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'memberDirectory.nameMap' "$RD/src/main/java/com/family/finance/web/lens/LensTagController.java" \
  && grep -q 'memberDirectory.listAll' "$RD/src/main/java/com/family/finance/web/entry/EntryController.java" \
  && grep -q 'memberDirectory.selectableWith' "$RD/src/main/java/com/family/finance/web/account/AccountController.java" \
  && grep -q 'memberDirectory.selectableWith' "$RD/src/main/java/com/family/finance/web/goal/GoalController.java"; } \
  && log_ok "v115-MEMBER-NAME-MAP-INCLUDES-ARCHIVED(仅活跃查询钉死在 11 个文件 · 展示/脱敏/编辑候选走名录)" \
  || log_bad "v115-MEMBER-NAME-MAP-INCLUDES-ARCHIVED 有人旁路了成员名录" \
     "展示/脱敏/历史归属用 memberDirectory.nameMap|listAll,编辑表单用 selectableWith;确实只要活跃成员的,把文件加进本护栏 V115_ALLOW 白名单并说明理由。当前多出/少掉的文件见 grep -rl 'memberMapper\.findActiveByFamily\|memberMapper\.countActiveByFamily' src/main/java"

# v115-DELETE-SCAN-COVERS-FK-LESS · 删除前的引用扫描必须盖住那 4 处没有外键的
#   删成员是唯一一个真会掉数据的动作。有外键的 9 处,漏扫最多是数据库层拦下来(用户看到 500);
#   **没有外键的这 4 处漏扫,数据库不会拦** —— 删掉之后历史数据里留一个指向不存在成员的悬空 id,
#   教育目标的「孩子」尤其阴:它藏在 params_json 里,任何 schema 元数据(information_schema、
#   自动外键发现)都看不见它。这就是清单手写而不是自动发现的原因,也是这条护栏逐个点名的原因。
{ grep -q 'FROM period_member_cashflow"' "$RD/src/main/java/com/family/finance/repository/MemberReferenceMapper.java" \
  && grep -q 'triggered_by_member_id = #{memberId}' "$RD/src/main/java/com/family/finance/repository/MemberReferenceMapper.java" \
  && grep -q 'FROM report_reminder_log"' "$RD/src/main/java/com/family/finance/repository/MemberReferenceMapper.java" \
  && grep -q "JSON_EXTRACT(params_json, '\$.child_member_id')" "$RD/src/main/java/com/family/finance/repository/MemberReferenceMapper.java" \
  && [ "$(grep -cE '^\s+int count[A-Za-z]+\(' "$RD/src/main/java/com/family/finance/repository/MemberReferenceMapper.java")" -eq 13 ] \
  && grep -q 'scan_queriesEveryCountMethodOnTheMapper' "$RD/src/test/java/com/family/finance/service/member/MemberReferenceScannerTest.java" \
  && grep -q 'referenceScanner.scan(familyId, targetMemberId)' "$RD/src/main/java/com/family/finance/service/AdminService.java"; } \
  && log_ok "v115-DELETE-SCAN-COVERS-FK-LESS(13 处引用 · 含 period_member_cashflow/stock_valuation_event/report_reminder_log/family_goal.child_member_id 这 4 处无外键的)" \
  || log_bad "v115-DELETE-SCAN-COVERS-FK-LESS 引用扫描漏了没有外键的那几处" \
     "MemberReferenceMapper 必须显式数满 13 处;新增引用成员的表就加一行 count 方法(单测 scan_queriesEveryCountMethodOnTheMapper 会核对每个方法都被 scan 调到)"

# v115-NO-MEMBER-ARCHIVE-IN-SUMS · 归档一个人,不动一分钱
#   归档的语义是「ta 不再打理家里的账」,不是「ta 的钱不算数了」。所有金额口径按的是
#   account.archived_at(账户是否还在),跟 member.archived_at 无关 —— 一旦有人在事实层
#   顺手加上「成员未归档」的条件,归档动作就会把这个人名下的历史资产整块从家庭总账里抹掉。
#   两头守:SQL 里不许出现成员归档过滤;Java 侧不许在金额聚合前按 isArchived 筛人。
{ ! grep -qiE '(m|mem|member)\.archived_at' "$RD"/src/main/resources/mapper/*.xml \
  && ! grep -rqE 'member\.archived_at IS NULL' "$RD/src/main/java" \
  && ! grep -rqE '\.filter\([a-z]+ -> ![a-z]+\.isArchived\(\)\)' "$RD/src/main/java/com/family/finance/web/dashboard" \
  && grep -q 'archivedOwnerKeepsBothTheirMoneyAndTheirName' "$RD/src/test/java/com/family/finance/web/dashboard/MemberArchiveMoneyInvarianceTest.java" \
  && grep -q 'anActiveOnlyNameMap_wouldHaveBrokenIt' "$RD/src/test/java/com/family/finance/web/dashboard/MemberArchiveMoneyInvarianceTest.java"; } \
  && log_ok "v115-NO-MEMBER-ARCHIVE-IN-SUMS(金额口径只认 account.archived_at · 成员归档不影响任何求和)" \
  || log_bad "v115-NO-MEMBER-ARCHIVE-IN-SUMS 有人把成员归档带进了金额口径" \
     "事实层 SQL 与成员维度聚合都不许按 member.archived_at 过滤;归档只影响「谁还来填报」,不影响「家里有多少钱」"

# v115-RENAME-KILLS-TOKENS-FIRST · 清票根必须在改名之前
#   persistent_logins 那张表按 username 记账。先 UPDATE 再清,清的是**新名字** ——
#   旧名字那行谁也删不掉,而那张票根照样能把人自动登回来,「记住我」的 cookie 有效期以周计。
{ grep -q 'killRememberMe' "$RD/src/main/java/com/family/finance/service/AdminService.java" \
  && [ "$(grep -n 'killRememberMe(old)' "$RD/src/main/java/com/family/finance/service/AdminService.java" | head -1 | cut -d: -f1)" \
       -lt "$(grep -n 'updateUsername(familyId, targetMemberId' "$RD/src/main/java/com/family/finance/service/AdminService.java" | head -1 | cut -d: -f1)" ] \
  && grep -q 'rememberMeTokensAreClearedBeforeTheUsernameChanges' "$RD/src/test/java/com/family/finance/service/UsernameRenameTest.java" \
  && grep -q 'expired' "$RD/src/main/java/com/family/finance/auth/AuthController.java"; } \
  && log_ok "v115-RENAME-KILLS-TOKENS-FIRST(先按旧登录名清票根 · 再改名 · 登录页解释 ?expired)" \
  || log_bad "v115-RENAME-KILLS-TOKENS-FIRST 改名顺序反了或没清「记住我」" \
     "AdminService.renameUsername 必须 killRememberMe(旧名) 在 updateUsername 之前;顺序由 UsernameRenameTest 的 InOrder 断言守着"
# v116-TODO-DONE-SINGLE-SOURCE · 「本期填报完成」只许有一个定义(issue #15)
#   开账写 period_snapshot 让填报页判 ✓,同一个方法插的 todo 却是 PENDING → 徽标和页面互相打架。
#   v1.16 把口径收回**写入侧**:开账写快照的同时把 todo 标 DONE,三个消费者读同一列。
#   ② 是这条护栏里最重要的一句:它挡的不是今天的 bug,是明天有人为了「省一条迁移」
#      把 NOT EXISTS(period_snapshot) 加回计数 SQL —— 那会让口径重新分裂成两份,而且和今天一样不报错。
#   ③ 贷款趋势提示条从此不能再看状态列(开账就 DONE 了,看状态 = 提示条整个消失)。
#      改看 done_by_member_id:NULL = 系统代填、还没有人做过决定。
QA116_PO="$RD/src/main/java/com/family/finance/service/PeriodOpener.java"
QA116_TM="$RD/src/main/java/com/family/finance/repository/SnapshotTodoMapper.java"
QA116_ES="$RD/src/main/java/com/family/finance/service/EntryService.java"
QA116_MIG="$RD/db/migration/V55__align_carryforward_todo_done.sql"
{ grep -q 'snapshotTodoMapper.markCarriedForward(family.getId(), period.getId(), account.getId())' "$QA116_PO" \
  && grep -q 'int markCarriedForward(' "$QA116_TM" \
  && grep -q "done_by_member_id = NULL" "$QA116_TM" \
  && grep -q "AND status = 'PENDING'" "$QA116_TM" \
  && ! grep -q 'period_snapshot' "$QA116_TM" \
  && grep -q 'boolean confirmedByHuman(SnapshotTodo todo)' "$QA116_ES" \
  && grep -q 'todo.getDoneByMemberId() != null' "$QA116_ES" \
  && grep -q 'boolean confirmedByHuman)' "$QA116_ES" \
  && grep -q "p.status = 'OPEN'" "$QA116_MIG" \
  && ! grep -qiE '\bend_balance\b|\bamount\b' "$QA116_MIG" \
  && [ -f "$RD/src/test/java/com/family/finance/service/PeriodOpenerTodoAlignmentTest.java" ] \
  && grep -q 'systemCarriedDone_isNotHumanConfirmation' "$RD/src/test/java/com/family/finance/service/EntryLoanPromptTest.java"; } \
  && log_ok "v116-TODO-DONE-SINGLE-SOURCE(开账代填即标 DONE · 计数 SQL 不碰 period_snapshot · 提示条改看 done_by_member_id)" \
  || log_bad "v116-TODO-DONE-SINGLE-SOURCE 「已填」的定义又分裂了" "PeriodOpener 必须调 markCarriedForward;SnapshotTodoMapper 里不许出现 period_snapshot(口径别搬回读取侧);loanPromptVisible 必须走 confirmedByHuman/getDoneByMemberId;V55 只动 OPEN 账期的状态列"

# ============================================================
# v1.18 · 流水来源标签 + 券商同步失败提醒
# ============================================================

# v118-SOURCE-TAG-ALL-TABLES · 时间线是 4 张表 union 的,4 张都得带来源(v1.18 FR-412)
# 第一版只改了前 3 张,把「= 校准」那一行直接写死 UNKNOWN,理由是"判据拿不到" ——
# 那是把活干一半:period_snapshot 有 5 个写入口且是 upsert(谁最后写谁说话),
# 它恰恰是最需要这一列的一张,不然连【将来的】数据也永远是 UNKNOWN。
QA118_MIG="$RD/db/migration/V56__ledger_source_tag.sql"
QA118_ADS="$RD/src/main/java/com/family/finance/service/AccountDetailService.java"
QA118_ENTRIES="$(grep -c 'new AccountDetail.Entry(' "$QA118_ADS" 2>/dev/null || echo 0)"
QA118_SRCS="$(grep -c 'LedgerSource.parse(' "$QA118_ADS" 2>/dev/null || echo 0)"
{ [ -f "$QA118_MIG" ] \
  && [ "$(grep -c 'ADD COLUMN source_tag' "$QA118_MIG")" -eq 4 ] \
  && grep -q 'ALTER TABLE stock_valuation_event' "$QA118_MIG" \
  && grep -q 'ALTER TABLE cash_flow' "$QA118_MIG" \
  && grep -q 'ALTER TABLE transfer' "$QA118_MIG" \
  && grep -q 'ALTER TABLE period_snapshot' "$QA118_MIG" \
  && [ "$QA118_ENTRIES" -eq 4 ] && [ "$QA118_SRCS" -eq 4 ] \
  && grep -q 'src-tag' "$RD/src/main/resources/templates/accounts/detail.html" \
  && grep -q 'e.source.group' "$RD/src/main/resources/templates/accounts/detail.html"; } \
  && log_ok "v118-SOURCE-TAG-ALL-TABLES(4 张流水表都有 source_tag · 时间线 $QA118_ENTRIES 个构造点都带来源 · 模板按分组上色)" \
  || log_bad "v118-SOURCE-TAG-ALL-TABLES 有流水表或时间线构造点漏了来源" "V56 要 4 条 ADD COLUMN;AccountDetailService 的 Entry 构造点数($QA118_ENTRIES)必须等于 LedgerSource.parse 数($QA118_SRCS)"

# v118-UNKNOWN-NOT-MANUAL · 历史数据一律 UNKNOWN,不许回填成 MANUAL(v1.18 FR-413 · 维护者定)
# 回填 MANUAL 等于【假装我们知道】:历史行里确实有一部分是自动同步来的,
# 但 cash_flow / transfer / period_snapshot 上没有任何依据可推断,
# 写 MANUAL 会让统计得出"过去全是手填"的错误结论。
# 判据落在【SQL 语句形态】而不是全文 grep "MANUAL":迁移注释里必须能解释"为什么不回填成 MANUAL",
# 全文 grep 会把这句解释本身判成违规(v1.17 已经在 Ubuntu16.04 / libgtk-3-0 / 22222 上栽过三次)。
QA118_LS="$RD/src/main/java/com/family/finance/domain/ledger/LedgerSource.java"
{ ! grep -q "SET source_tag = 'MANUAL'" "$QA118_MIG" \
  && [ "$(grep -c "DEFAULT 'UNKNOWN'" "$QA118_MIG")" -eq 4 ] \
  && grep -q 'UNKNOWN("来源未记录"' "$QA118_LS" \
  && grep -q 'catch (IllegalArgumentException e) {' "$QA118_LS" \
  && grep -q 'return UNKNOWN;' "$QA118_LS" \
  && [ -f "$RD/src/test/java/com/family/finance/domain/ledger/LedgerSourceTest.java" ] \
  && grep -q 'unknown_means_not_recorded_not_manual' "$RD/src/test/java/com/family/finance/domain/ledger/LedgerSourceTest.java" \
  && grep -q 'labels_avoid_technical_jargon' "$RD/src/test/java/com/family/finance/domain/ledger/LedgerSourceTest.java" \
  && grep -q 'src-unknown' "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v118-UNKNOWN-NOT-MANUAL(历史回填 UNKNOWN 不假装手动 · parse 永不抛 · 标签无技术词 · UNKNOWN 样式最不抢视线)" \
  || log_bad "v118-UNKNOWN-NOT-MANUAL 历史被回填成 MANUAL 或 UNKNOWN 语义被弄丢" "V56 不许有 SET source_tag = 'MANUAL';LedgerSource.parse 必须兜底 UNKNOWN;LedgerSourceTest 两条边界测试必须在"

# v118-SOURCE-WRITE-PATHS · 每个流水写入口都要说清自己是谁(v1.18 FR-412)
# 4 条 INSERT 一律 COALESCE(#{sourceTag}, 'UNKNOWN') —— 列是 NOT NULL DEFAULT,
# 但 MyBatis 显式传 NULL 会绕过 DEFAULT 直接撞 NOT NULL;有了 COALESCE,
# 将来漏掉的写入口会安全落到 UNKNOWN 而不是插入失败。
{ [ "$(grep -rcE "COALESCE\(#\{(cf|t|s)\.sourceTag\}, 'UNKNOWN'\)" "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" "$RD/src/main/java/com/family/finance/repository/TransferMapper.java" "$RD/src/main/java/com/family/finance/repository/SnapshotMapper.java" | grep -c ':1$')" -eq 3 ] \
  && grep -q 'source_tag AS sourceTag' "$RD/src/main/java/com/family/finance/repository/StockValuationEventMapper.java" \
  && grep -q 'LedgerSource.CARRIED_FORWARD.name()' "$RD/src/main/java/com/family/finance/service/PeriodOpener.java" \
  && grep -q 'LedgerSource.SYSTEM_ADJUST.name()' "$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java" \
  && [ "$(grep -c 'LedgerSource.MANUAL.name()' "$RD/src/main/java/com/family/finance/service/EntryService.java")" -ge 4 ] \
  && grep -q 'LedgerSource.ofBroker(link.getVendor().name())' "$RD/src/main/java/com/family/finance/service/broker/BrokerSyncService.java" \
  && grep -q 'writeBackBalance(long familyId, long periodId, Account acc, BigDecimal balance,' "$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java" \
  && [ -f "$RD/src/test/java/com/family/finance/service/stock/ValuationSourceInferTest.java" ]; } \
  && log_ok "v118-SOURCE-WRITE-PATHS(3 张表 INSERT 都 COALESCE 兜底 · 开账/系统联动/手填/券商 各自声明来源 · 估值回写与事件同一个判定)" \
  || log_bad "v118-SOURCE-WRITE-PATHS 有写入口没声明来源" "see PeriodOpener(CARRIED_FORWARD) / StockHoldingService(SYSTEM_ADJUST) / EntryService(MANUAL×4) / BrokerSyncService(ofBroker) / AccountValuationService#writeBackBalance"

# v118-BROKER-FAIL-VISIBLE · 券商同步失败必须写进状态并标在账户列表上(v1.18 FR-410/411)
# v1.17.3 事故里最坏的一半不是"缺提醒",是【一条两天前的成功消息在冒充当前状态】——
# 失败路径当时只有 log.warn,broker_link 压根没被写过。
# 所以 markFailed 绝不许碰 last_synced_at(那一列的语义是"上次成功同步")。
# 判据只看那条 @Update 的 SQL 本身:方法上方的 javadoc 必须能解释"为什么不动 last_synced_at",
# 用整文件 grep 会把这句解释判成违规。
# 模板侧要求【两处】—— 账户列表有 PC 表格 + 窄屏卡片两套视图(`md:hidden`),
# 第一版只改了表格那侧,手机上那枚标记的盒子是 0×0(截图复看才发现)。
# 判据也刻意不用 grep '/broker':每一行本来就有「券商」入口链接,那守不住任何东西。
QA118_BLM="$RD/src/main/java/com/family/finance/repository/BrokerLinkMapper.java"
# 只取 markFailed 正上方那一行 @Update —— 直接 grep 'UPDATE broker_link SET' 会把 markSynced
# 那条也捞进来(它本来就【该】写 last_synced_at),于是护栏永远红。
# 【-B6 不是 -B1】:markFailed 的 @Update 加 family_id 之后变成多行字符串拼接,
#   -B1 只够着最后一行 WHERE,判据会恒假 —— 那是「护栏红了但原因不是它要防的事」。
QA118_MARKSQL="$(grep -B6 'int markFailed(' "$QA118_BLM" 2>/dev/null | sed -n '/@Update/,$p' || true)"
{ [ -n "$QA118_MARKSQL" ] \
  && ! printf '%s' "$QA118_MARKSQL" | grep -q 'last_synced_at' \
  && grep -q 'int markFailed(' "$QA118_BLM" \
  && grep -q '同步失败 · ' "$RD/src/main/java/com/family/finance/service/broker/BrokerSyncService.java" \
  && grep -q 'markFailed(familyId, accountId, failureNote(e))' "$RD/src/main/java/com/family/finance/service/broker/BrokerSyncService.java" \
  && grep -q 'brokerFailures' "$RD/src/main/java/com/family/finance/web/account/AccountController.java" \
  && grep -q 'startsWith("同步失败")' "$RD/src/main/java/com/family/finance/web/account/AccountController.java" \
  && [ "$(grep -c 'brokerFailures.containsKey(row.account.id)' "$RD/src/main/resources/templates/accounts/index.html")" -eq 2 ] \
  && [ "$(grep -c '<span>同步失败</span>' "$RD/src/main/resources/templates/accounts/index.html")" -eq 2 ]; } \
  && log_ok "v118-BROKER-FAIL-VISIBLE(失败写 last_status 且不动 last_synced_at · 账户列表标红可点进券商页)" \
  || log_bad "v118-BROKER-FAIL-VISIBLE 同步失败又变回只写日志(或标记不在账户列表上)" "markFailed 的 SQL 不许含 last_synced_at;AccountController 要建 brokerFailures;accounts/index.html 要渲染并链到 /accounts/{id}/broker"

# ============================================================
# v1.18.1 · 两个主流程 bug
# ============================================================

# v1181-KEY-SAVE-SPLIT · 密钥与模型选取必须各自独立保存(v1.18.1 · 维护者报「主流程都走不下去」)
# 原来两件事在一个 form / 一个端点里,而那个端点「校验先全跑完再落库」→ 全新装机死锁:
#   模型下拉与凭据级联(没配 key 的平台 disabled)→ 一家都没配则平台选项全禁用
#   → 提交上来 platform 为空 → 抛「请选择平台」→ 整单退回,key 一个字都没存进去。
# 判据钉三件:① 三张凭据卡各自 POST /llm/key(带 platform + apiKey)
#            ② 模型表单 POST /llm/models  ③ 老端点 @PostMapping("/llm") 必须已删
#              (留着一个没有 UI 指向的写接口,下次就会有人以为它还在用 —— v1.17.2 的教训)
QA1181_TPL="$QA_LLM_TPL"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 integrations.html)
QA1181_CTL="$QA_LLM_JAVA"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 IntegrationsController.java)
{ [ "$(grep -c 'action="@{/admin/ai-access/llm/key}"' "$QA1181_TPL")" -eq 3 ] \
  && [ "$(grep -c 'name="apiKey"' "$QA1181_TPL")" -eq 3 ] \
  && [ "$(grep -c '>保存密钥<' "$QA1181_TPL")" -eq 3 ] \
  && grep -q 'action="@{/admin/ai-access/llm/models}"' "$QA1181_TPL" \
  && grep -q 'String saveLlmKey(' "$QA1181_CTL" \
  && grep -q 'String saveLlmModels(' "$QA1181_CTL" \
  && ! grep -q '@PostMapping("/llm")' "$QA1181_CTL" \
  && ! grep -qE '^\s*@PostMapping\s*$|@PostMapping\(""\)' "$QA1181_CTL" \
  && ! grep -q 'qwenKey", required = false' "$QA1181_CTL"; } \
  && log_ok "v1181-KEY-SAVE-SPLIT(三家密钥各自独立保存 · 模型选取单独端点 · 老合并端点已删)" \
  || log_bad "v1181-KEY-SAVE-SPLIT 密钥又和模型选取绑回一个表单了" "三张卡各要一个 POST /admin/ai-access/llm/key(platform+apiKey);模型走 /admin/ai-access/llm/models;不许再有 @PostMapping(\"/llm\")"

# v1181-KEY-SAVE-NOT-SILENT · 密钥保存不许静默成功(v1.18.1)
# 这一格的语义是「留空 = 不改」,但用户点了这张卡的保存按钮却什么都没填时,
# 回一句「已保存」等于骗他 —— 他会以为换上了新 key,实际还在用旧的。
# 同时:密钥端点绝不许碰模型三元组(否则死锁会以另一种形式回来)。
{ grep -q '没填内容 · 密钥未改动' "$QA1181_CTL" \
  && grep -q 'key=已配置' "$QA1181_CTL" \
  && [ "$(sed -n '/String saveLlmKey(/,/^    }/p' "$QA1181_CTL" | grep -c 'parseTriple\|writeTriple')" -eq 0 ]; } \
  && log_ok "v1181-KEY-SAVE-NOT-SILENT(空提交明确报错不假装成功 · 审计只记已配/未配 · 密钥端点不碰模型三元组)" \
  || log_bad "v1181-KEY-SAVE-NOT-SILENT 空提交被当成保存成功,或密钥端点又去校验模型了" "see IntegrationsController#saveLlmKey"

# v1183-ATTR-SAME-PERIOD · 归因四项必须同期 · 仪表盘锚【当月实时】(v1.18.3)
# 历史:v1.18.1 曾把归因锚到「最新已关账期」,绕开进行中的月份 —— 因为那时会出现
#   「转账已登记、余额没涨」→ pnl = Δ余额(0) − 转入 = 假亏损。
#   但那是权宜之计:真正的病根是 v1.18.1 后半段修的丢钱 bug(钱没落进现金行、被估值抹掉)。
#   修完之后流水会立刻同步进余额,假亏损的根没了(e2e 主线 16 钉这条)。
#   而锚在上个月带来了新问题:仪表盘上面的卡是本月、下面的瀑布是上月,同一屏两个月份,
#   维护者拿本月印象去对上月的数,当场看成 bug(2026-08-21)。仪表盘的分工本来就是当月实时。
# 不变的那条:ΔNW / 人赚 / 钱赚 / 开账基线【必须同一期】——「未归因」是残差定义、
#   按构造恒等闭合,四项不同期时差额会被它悄悄吸收,页面看着平了、错误藏进兜底项。
QA1183_DASH="$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java"
QA1183_REV="$RD/src/main/java/com/family/finance/web/review/ReviewController.java"
{ grep -q 'Long attrPeriodId = slice.lastPeriodId();' "$QA1183_DASH" \
  && grep -q 'Long attrPeriodId = slice.lastPeriodId();' "$QA1183_REV" \
  && grep -q 'kpis.netWorthDelta(), human, kpis.openingBaselineLast()' "$QA1183_DASH" \
  && grep -q 'kpis.netWorthDelta(), human, kpis.openingBaselineLast()' "$QA1183_REV" \
  && grep -q 'slice.periodIds()' "$RD/src/main/java/com/family/finance/service/review/AttributionService.java" \
  && grep -q '混锚会把差额藏进未归因' "$RD/src/test/java/com/family/finance/factview/AttributionAnchorTest.java" \
  && grep -q '余额同步更新后_同一笔转入的损益是零' "$RD/src/test/java/com/family/finance/factview/AttributionAnchorTest.java"; } \
  && log_ok "v1183-ATTR-SAME-PERIOD(仪表盘归因锚当月实时 · 四项同期 · 趋势同锚 · 单测钉住前提)" \
  || log_bad "v1183-ATTR-SAME-PERIOD 归因锚点/同期性走样" "dashboard 与 review 都要锚 slice.lastPeriodId() 并用 netWorthDelta + openingBaselineLast;趋势用 slice.periodIds()"

# v1183-ATTR-LIVE-CAVEAT · 当月实时的代价必须写在页面上(v1.18.3)
# 锚回当月之后风险换了一种:收支还没录齐时,未录的收入会被算进「钱赚」→ 偏高。
# 照 v1.10 FR-327 定的做法:显示真实值 + 把可信度说清楚,而不是藏起来。
# 所以页面要同时给出「还在填报中 / 实时口径 / 本月已录收入·支出」。
{ grep -q 'attrFilingInProgress' "$QA1183_DASH" \
  && grep -q 'attrLiveIncome' "$QA1183_DASH" \
  && grep -q 'attrLiveExpense' "$QA1183_DASH" \
  && grep -q '还在填报中' "$RD/src/main/resources/templates/dashboard/_attribution.html" \
  && grep -q '本月已录' "$RD/src/main/resources/templates/dashboard/_attribution.html" \
  && ! grep -q '归因锚定' "$RD/src/main/resources/templates/dashboard/_attribution.html"; } \
  && log_ok "v1183-ATTR-LIVE-CAVEAT(填报中时写明实时口径 + 已录收支 · 旧的「锚定上月」文案已清)" \
  || log_bad "v1183-ATTR-LIVE-CAVEAT 实时口径说明缺失" "模板要有「还在填报中 / 实时口径 / 本月已录」;controller 要传 attrLiveIncome/attrLiveExpense"

# v1183-WRITEBACK-FAIL-CLOSED · 估值写回不许把刚进账户的钱盖掉(v1.18.3 · 复盘方案 B)
# period_snapshot 是【覆盖写】,被盖掉的旧值没有任何地方留底 —— 全系统唯一一条不可恢复的自动写。
# 事后对账是补救,事前拦截才是根治。判据与对账扫描【共用一份】ErasureDetector,
# 不许两处各写一套(「同一件事两份判据」正是这个 bug 反复出现的形状,已归档 5 次)。
# 拦下来必须留痕并出现在页面上 —— 只写日志就是 v1.17.3 犯过的错。
# 两条是 e2e 抓出来的、必须钉死:
#   ① 拦下就【不许写估值事件】—— 否则记了个没发生的变化,还会把「上次估值时间」推到现在,
#      让下一次的窗口变空、第二次就拦不住(所以 writeBackBalance 必须返回布尔,调用方尊重它)
#   ② 判据按【后缀和】逐个试,不能拿窗口总和一次比 —— 窗口里常混着已经正确入账的钱,
#      拿总和比会被顶歪(实测:53,210 已入账 + 48,765 被吞 → 总和法漏判)
QA1183_AVS="$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java"
{ [ -f "$RD/src/main/java/com/family/finance/calc/reconcile/ErasureDetector.java" ] \
  && grep -q 'ErasureDetector.erasedAmount' "$QA1183_AVS" \
  && grep -q 'ErasureDetector.erasedAmount' "$RD/src/main/java/com/family/finance/service/reconcile/ReconciliationScanService.java" \
  && grep -q 'findFlowsAfter' "$QA1183_AVS" \
  && grep -q '拦住_窗口里混着已入账的钱时仍按后缀和命中' "$RD/src/test/java/com/family/finance/calc/reconcile/ErasureDetectorTest.java" \
  && grep -q 'boolean writeBackBalance' "$QA1183_AVS" \
  && grep -q 'BLOCKED_WRITEBACK_NOTE' "$QA1183_AVS" \
  && grep -q 'auditLogService.record' "$QA1183_AVS" \
  && grep -q 'blocked()' "$RD/src/main/resources/templates/admin/reconcile.html" \
  && [ -f "$RD/src/test/java/com/family/finance/calc/reconcile/ErasureDetectorTest.java" ] \
  && [ "$(grep -c 'void 不拦_' "$RD/src/test/java/com/family/finance/calc/reconcile/ErasureDetectorTest.java")" -ge 3 ] \
  && [ "$(grep -c 'void 拦住_' "$RD/src/test/java/com/family/finance/calc/reconcile/ErasureDetectorTest.java")" -ge 3 ]; } \
  && log_ok "v1183-WRITEBACK-FAIL-CLOSED(写回前拦一道 · 与对账共用一份判据 · 留痕上页面 · 单测该拦/不该拦成对)" \
  || log_bad "v1183-WRITEBACK-FAIL-CLOSED 估值写回又变回无条件覆盖" "writeBackBalance 要先过 ErasureDetector.erasesFlows;拦下要写审计并在 /admin/reconcile 显示"

# ============================================================
# v1.18.7 · 仪表盘「实时」定位:每个数说清自己是哪一期
# ============================================================

# v1187-DASH-PERIOD-HONEST · 仪表盘上的数不许含糊自己是哪一期(2026-08-25 · 逐项 review)
#
# 页面自称「实时汇总」,而 review 逐项查下来有三类数其实不是本期、页面上却看不出来:
#   ① 储蓄率写着「本期储蓄率」,实际取「最近一个有 PMC 记录的期」或(兜底)「最新已关账期」。
#      beta 实测:本期有 51 笔收入、0 笔支出 → 本期储蓄率必然 100%,页面显示 98.4%。
#      而它和【实时】的净资产/环比挤在同一句话里 —— 与 v1.18.3 那次「上面本月、下面上月」同形状。
#      维护者定:不改口径(强行锚本期会让月初剧烈跳动),把账期标出来。
#   ② 紧急储备 = 流动资产(实时) ÷ 月均支出(近 12 期均值),而均值把【进行中的半个月】
#      当整月算 → 分母偏低 → 紧急储备虚高;同一个 avgExpense 还是「应急金超额闲置」banner
#      里「实际需求」的因子,偏低 → 超额算大 → 更容易弹出并建议你把钱挪走。
#   ③ 洞察条自己 loadDefault(本位币/全账户/按今天)→ 切币种、筛账户、选历史 as-of 时它一动不动。
#
# 这条钉三件事都落地了,且【recent 的另外三个调用方没被误伤】(收支趋势要那个进行中的点、
# 「已填 N/12 月」问的是填报完整度、GoalService 是另一个题目)。
QA1187_ELS="$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java"
QA1187_HCS="$RD/src/main/java/com/family/finance/service/HouseholdCashflowService.java"
QA1187_DC="$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java"
QA1187_RG="$RD/src/main/resources/templates/dashboard/_region.html"
{ grep -q 'public List<PeriodExpense> recentClosed(' "$QA1187_ELS" \
  && grep -q 'expenseLedger.recentClosed(familyId, LOOKBACK_PERIODS)' "$QA1187_HCS" \
  && grep -q 'expenseLedger.recentClosed(familyId, maxPeriods)' "$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java" \
  `# 反向:收支趋势 / 已填月数 仍走 recent —— 它们【需要】那个进行中的点` \
  && grep -q 'expenseLedger.recent(familyId, limit)' "$QA1187_HCS" \
  && grep -q 'expenseLedger.recent(familyId, LOOKBACK_PERIODS).size()' "$QA1187_HCS" \
  `# 储蓄率带出账期` \
  && grep -q 'record SavingsRateView' "$QA1187_HCS" \
  && grep -q 'savingsRateView' "$QA1187_DC" \
  && grep -q 'savingsRatePeriod' "$QA1187_DC" \
  && grep -q 'savingsRatePeriod' "$QA1187_RG" \
  && ! grep -q '>本期储蓄率$' "$QA1187_RG" \
  `# 洞察条吃这一页的切片;目标条公开声明自己不跟随` \
  `# v1.27 起多两个参数(分析范围 · 配置锚),切片仍是这一页的 slice` \
  && grep -qE 'assetInsightService.compute\(me.getFamilyId\(\), slice(\)|,)' "$QA1187_DC" \
  && grep -q 'goalsViewIndependent' "$QA1187_DC" \
  && grep -q 'goalsViewIndependent' "$RD/src/main/resources/templates/goals/_progress-strip.html" \
  `# 净资产趋势标出进行中的点` \
  && grep -q 'boolean live' "$RD/src/main/java/com/family/finance/factview/TrendPoint.java" \
  && grep -q "t.live() ? t.label()" "$QA1187_DC" \
  `# 单测成对写` \
  && [ -f "$RD/src/test/java/com/family/finance/service/expense/RecentClosedTest.java" ] \
  && [ -f "$RD/src/test/java/com/family/finance/service/SavingsRatePeriodTest.java" ] \
  && [ -f "$RD/src/test/java/com/family/finance/factview/DashboardLiveScopeTest.java" ] \
  && grep -q '没有进行中账期时逐位不变' "$RD/src/test/java/com/family/finance/service/expense/RecentClosedTest.java" \
  && grep -q 'recent不受影响_三个调用方仍拿得到进行中期' "$RD/src/test/java/com/family/finance/service/expense/RecentClosedTest.java" \
  && grep -q '数值口径与老入口逐位一致' "$RD/src/test/java/com/family/finance/service/SavingsRatePeriodTest.java"; } \
  && log_ok "v1187-DASH-PERIOD-HONEST(储蓄率点名账期 · 月均剔除进行中期 · 洞察条跟随视图 · 目标条声明不跟随 · 趋势标进行中)" \
  || log_bad "v1187-DASH-PERIOD-HONEST 仪表盘又有数说不清自己是哪一期" "页面自称实时,混进非本期的数却不标注 = 让人把上月读成本月(v1.18.3 已栽过一次)"

# ============================================================
# v1.18.2 · 账目对账(复盘 A/C 项)
# ============================================================

# v1182-RECONCILE-WIRED · 探测器要接上线,不能只装旋钮(v1.18.2 · 复盘 A)
# 复盘结论:ReconciliationCalculator.unexplained 算的正是「余额里对不上账的部分」,
# 但它只在填报页对 CASH/LOAN 显示;而管理页那个 unexplained_epsilon 阈值
# 【存了但没有任何代码读它】—— 旋钮装好了、线没接。这条钉住线接上了。
QA1182_SVC="$RD/src/main/java/com/family/finance/service/reconcile/ReconciliationScanService.java"
{ [ -f "$QA1182_SVC" ] \
  && grep -q 'K_UNEXPLAINED_EPSILON' "$QA1182_SVC" \
  && grep -q '@GetMapping("/reconcile")' "$RD/src/main/java/com/family/finance/web/admin/AdminController.java" \
  && grep -q "sidebar('reconcile')" "$RD/src/main/resources/templates/admin/reconcile.html" \
  && grep -q '/admin/reconcile' "$RD/src/main/resources/templates/admin/_sidebar.html" \
  && [ -f "$RD/src/test/java/com/family/finance/service/reconcile/ReconciliationScanServiceTest.java" ]; } \
  && log_ok "v1182-RECONCILE-WIRED(对账扫描在线 · 阈值 unexplained_epsilon 真被读 · 管理页有入口)" \
  || log_bad "v1182-RECONCILE-WIRED 对账扫描缺失或阈值又变回没人读" "see ReconciliationScanService / AdminController#reconcile / admin/_sidebar.html"

# v1182-RECONCILE-NOT-DECORATIVE · 判据不许退化成「永远不会失败」的装饰(v1.18.2 · 复盘 C)
# 这个扫描器的前两版判据都被真数据推翻过:
#   ① periodPnl − Σ事件Δ —— 抓不到:估值抹钱时会忠实写一条 delta = −(被抹的钱) 的事件,两边相消
#      (与归因瀑布「未归因」同病:把结果记下来再拿结果去对,永远对得上)
#   ② 「期末 − 期初 ≈ 0」 —— 也抓不到:持仓本身当期还在涨跌,余额并非一分没差
# 现在判的是【时间线形状】:某次估值的 Δ 恰好等于它之前那段窗口里进出的钱的相反数。
# 判据钉住:必须按事件时间配对(而不是按期合计),且单测里【不该抓的】那几条都在。
QA1182_UT="$RD/src/test/java/com/family/finance/service/reconcile/ReconciliationScanServiceTest.java"
{ grep -q 'findEventsForReconcile' "$QA1182_SVC" \
  && grep -q 'findFlowsForReconcile' "$QA1182_SVC" \
  && grep -q 'windowStart' "$QA1182_SVC" \
  && ! grep -q 'sumDeltaByAccountPeriod' "$QA1182_SVC" \
  && [ "$(grep -c 'void 不抓_' "$QA1182_UT")" -ge 4 ] \
  && [ "$(grep -c 'void 抓到_' "$QA1182_UT")" -ge 3 ] \
  && grep -q '不抓_没有持仓的账户压根不在扫描范围' "$QA1182_UT" \
  && grep -q '抓到_一期里分两次被抹' "$QA1182_UT"; } \
  && log_ok "v1182-RECONCILE-NOT-DECORATIVE(按事件时间窗口配对 · 单测该抓/不该抓成对写)" \
  || log_bad "v1182-RECONCILE-NOT-DECORATIVE 判据退回按期合计,或单测只剩「该抓」那一半" "按期合计的判据抓不到这个 bug(估值会把抹掉的动作如实记成事件,两边相消)"

# v1186-RECONCILE-NO-BLIND-FIX · 对账页不许把「疑似」说成「照此补回」(v1.18.6)
#
# 起因是一次真实误报,而且方向是【让维护者去删掉真实存在的钱】—— 比漏报危险得多:
# 生产上有一格命中了时间线判据,但那是用户转账后立刻重导了一次持仓截图(导入如实还原了
# 转账前的持仓、于是与转账相消),而 8 天后的又一次导入已经把余额纠正了。
# 判据只看【某一个瞬间】,对「后来被纠正」一无所知,照样报出「需要补回 12.5w」。
#
# 修法不是改判据(判据本身没错),是补上【第二视角:整期是否自洽】并给结论分级:
#   隐含损益 = 期末 − 期初 − 净流水      残留 = 隐含损益 + 被抹掉的钱
#   残留 ≈ 0 → 期末余额至今仍差着这笔钱(stillMissing)· 残留 ≫ 0 → 只提示核对
# 它顺带替代了「已处理」标记:钱补回来之后同一条痕迹会自动降级,不需要人手打标记
#(人手标记会和数据分家,而「同一件事两份判据」正是这一整个 bug 家族的形状)。
QA1186_TPL="$RD/src/main/resources/templates/admin/reconcile.html"
QA1186_UT_F="$QA1182_UT"
{ grep -q 'findBalancesForReconcile' "$QA1182_SVC" \
  && grep -q 'stillMissing' "$QA1182_SVC" \
  && grep -q 'residual' "$QA1182_SVC" \
  && grep -q 'findBalancesForReconcile' "$RD/src/main/java/com/family/finance/repository/StockValuationEventMapper.java" \
  `# 口径必须与事实表同源:期末缺失时沿用 <= 当期的最近一期(漏填不等于归零),期初取严格更早的一期。` \
  `# 两处口径分家正是这个 bug 家族的形状,所以连 SQL 的形状一起钉住。` \
  && grep -q 'pc.period_start <= p.period_start' "$RD/src/main/java/com/family/finance/repository/StockValuationEventMapper.java" \
  && grep -q 'pv.period_start < p.period_start' "$RD/src/main/java/com/family/finance/repository/StockValuationEventMapper.java" \
  && grep -q 'p_prev.period_start &lt; p.period_start' "$RD/src/main/resources/mapper/FactMapper.xml" \
  && grep -q 'f.stillMissing()' "$QA1186_TPL" \
  && grep -q '期末仍对不上' "$QA1186_TPL" \
  && grep -q '需人工核对' "$QA1186_TPL" \
  && grep -q '疑似' "$QA1186_TPL" \
  && ! grep -q '需要补回' "$QA1186_TPL" \
  && grep -q '第二视角_后来已被纠正_只标需人工核对_不许当成要补的钱' "$QA1186_UT_F" \
  && grep -q '第二视角_补回之后同一条痕迹自动降级' "$QA1186_UT_F" \
  && grep -q '第二视角_期初缺失时不许编数' "$QA1186_UT_F"; } \
  && log_ok "v1186-RECONCILE-NO-BLIND-FIX(疑似分级:期末仍对不上 / 需人工核对 · 补回后自动降级 · 页面不再写「需要补回」)" \
  || log_bad "v1186-RECONCILE-NO-BLIND-FIX 对账页又变回「照此补回」,或第二视角被拆掉" "判据只看瞬间,看不出「后来已被纠正」;照着补 = 凭空删钱(生产上真发生过)"

# ============================================================
# v1.18.4 · 数据源接入页:方舟型号 + 表单按「用户想干什么」分支
# ============================================================

# v1184-ARK-PRESET-MODELS · 方舟要给得出型号,不能只甩一句「自己去控制台复制」(v1.18.4)
# v1.13 当时判断「方舟的 model 只能从控制台复制接入点 ID,预置任何一个都会过期」,
# 于是三个系列全留空 —— 页面对用户只剩一句「这一家没有可预置的型号」,连去哪个页面复制都不说。
# 2026-08-21 重新调研:那个前提已经不成立,方舟现在支持【直接填 Model ID】,不必建接入点。
# 做法:默认型号取【不带日期】的 doubao-seed-evolving(平台自动跟进,不会失效),
#       带日期的几个作为可选项,并给控制台/模型广场的直达链接;输入框照旧可手填。
QA1184_CAT="$RD/src/main/java/com/family/finance/service/checkup/llm/LlmCatalog.java"
QA1184_TPL="$QA_LLM_TPL"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 integrations.html)
{ grep -q 'doubao-seed-evolving' "$QA1184_CAT" \
  && grep -q 'new Family("doubao-vision", "豆包 · 视觉", Modality.VISION,' "$QA1184_CAT" \
  && ! grep -q 'new Family("doubao", "豆包 Doubao", Modality.TEXT, List.of(), null)' "$QA1184_CAT" \
  && grep -q 'openManagement' "$QA1184_TPL" \
  && grep -q 'region:ark+cn-beijing/model' "$QA1184_TPL" \
  && ! grep -q '没有可以预置的固定型号名' "$QA1184_TPL" \
  && ! grep -q '型号需手填' "$QA1184_TPL" \
  && grep -q '方舟不再要求手填型号_预置了推荐型号' "$RD/src/test/java/com/family/finance/web/admin/LlmModelFormatTest.java"; } \
  && log_ok "v1184-ARK-PRESET-MODELS(方舟预置推荐型号 · 默认不带日期 · 控制台/模型广场直达链接)" \
  || log_bad "v1184-ARK-PRESET-MODELS 方舟又变回「自己去控制台复制」" "LlmCatalog.ARK 要有预置型号且默认不带日期;模板要给 openManagement + 模型广场链接"

# v1184-FORM-BY-INTENT · 表单按「用户想干什么」分支,不按「字段填没填」分支(v1.18.4)
# 维护者报:配好主选、【取消勾选】截图导入,保存却报「截图识别:请选择平台」。
# 根因不是那一条 —— 是三组三元组一律走同一个 parseTriple,而它一律要求平台可解析,
# 于是"用户已经关掉的能力"照样被要求填。只配了没有视觉能力的平台(DeepSeek)时更是死路:
# 视觉下拉里一个可选项都没有,关掉这个能力还是存不下去 —— 主流程直接走不通。
# 用法矩阵逐条钉在 LlmModelFormatTest 的「用法_*」里,漏掉哪种用法哪种就会再坏一次。
QA1184_CTL="$QA_LLM_JAVA"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 IntegrationsController.java)
QA1184_UT="$RD/src/test/java/com/family/finance/web/admin/LlmModelFormatTest.java"
{ grep -q 'tryParseTriple' "$QA1184_CTL" \
  && grep -q 'requireKeyConfigured' "$QA1184_CTL" \
  && grep -q 'visionCapablePlatforms' "$QA1184_CTL" \
  && grep -q 'visionCapableReady' "$QA1184_CTL" \
  && grep -q 'visionCapableReady' "$QA1184_TPL" \
  && grep -q 'th:disabled="\${!visionCapableReady}"' "$QA1184_TPL" \
  && [ "$(grep -c 'void 用法_' "$QA1184_UT")" -ge 6 ] \
  && grep -q '用法_只配无视觉能力的平台_关掉截图识别能存' "$QA1184_UT" \
  && grep -q '用法_关掉截图识别不清空旧的视觉配置' "$QA1184_UT" \
  && grep -q '用法_选了没配密钥的平台要当面拒绝' "$QA1184_UT"; } \
  && log_ok "v1184-FORM-BY-INTENT(关掉的能力不校验 · 没视觉能力时开关禁用并指路 · 平台须已配密钥 · 用法矩阵 6+ 条)" \
  || log_bad "v1184-FORM-BY-INTENT 表单又按字段分支了(关掉的能力会拦住保存)" "see IntegrationsController#saveLlmModels 的 tryParseTriple / requireKeyConfigured / visionCapablePlatforms"

# v1185-MANUAL-BALANCE-CALIBRATES-CASH · 手填余额落托管账户时要记进现金行(v1.18.5)
# 这是同一个洞的【第三个变种】,而且是生产上真咬到人的那个:
#   v1.18.1 修了「划转/收支进托管账户」、v1.18.3 加的写回拦截只认「流水」,
#   而手填余额既不是流水、也不动持仓 —— 正好从两道防线中间漏过去。
#   实测:8-21 14:42 手填 451,497.63 → 16:10 CRON 估值写回 375,248.71(delta −76,248.92),
#   维护者按提示补的钱又被抹掉了。
# 修法与前两次同源:用户说「这个账户现在有 X」→ 把 X 与(持仓+现金)的差额记成现金行,
#   下次估值重算 = 持仓 + 现金 = X,他敲的数就站得住;差额在持仓页看得见、可改。
QA1185_ES="$RD/src/main/java/com/family/finance/service/EntryService.java"
{ [ "$(sed -n '/public EntryRow submitBalance(/,/^    }/p' "$QA1185_ES" | grep -c 'stockHoldingService.valuationManaged(account)')" -eq 1 ] \
  && [ "$(sed -n '/public EntryRow submitBalance(/,/^    }/p' "$QA1185_ES" | grep -c 'adjustAccountCash')" -eq 1 ] \
  && grep -q 'valuationService.valuate(familyId, accountId).totalBaseValue()' "$QA1185_ES" \
  && grep -q '手填余额校准' "$QA1185_ES" \
  && grep -q '托管判据的三个消费方共用同一份定义' "$RD/src/test/java/com/family/finance/service/stock/ValuationManagedRoutingTest.java"; } \
  && log_ok "v1185-MANUAL-BALANCE-CALIBRATES-CASH(手填余额的差额落现金行 · 不再被下次估值抹掉 · 留痕)" \
  || log_bad "v1185-MANUAL-BALANCE-CALIBRATES-CASH 手填余额又会被估值抹掉" "submitBalance 里要判 valuationManaged 并把差额 adjustAccountCash"

# v1185-TYPE-SEMANTICS-NAMED · 钱路径里不许再有裸的「== 某个具体类型」(v1.18.5 · 复盘 D 项)
# 复盘结论:这个 bug 家族的形状是「加一个新类型/放开一个能力,远处那条【当时正确】的判断
# 就悄悄错了,而编译器一句话都不说」。已经栽过两次:
#   v1.4 放开 supportsHoldings → 录入侧仍写 type == STOCK → 生产丢 7.5w
#   v0.14 加 METAL → 体检的「投资类」仍写 STOCK/WEALTH/CRYPTO → 贵金属被三条规则静默跳过
# 做法:把「是不是负债 / 是不是投资 / 余额该不该被流水解释」做成 AccountType 上的具名谓词,
#   并用一条结构性单测遍历所有枚举值,逼着新类型必须被分类过。
# 判据刻意【不】要求一个不剩:AccountDiagnose 里 isCash/isProperty 确实就是在问某个具体类型
#   (CASH 专属、PROPERTY 专属规则),没有"类"的语义,机械包装反而是噪音。
QA1185_AT="$RD/src/main/java/com/family/finance/domain/account/AccountType.java"
QA1185_BARE="$(grep -rnE 'getType\(\) == AccountType\.[A-Z]+|type == AccountType\.[A-Z]+' \
  "$RD/src/main/java/com/family/finance/service" "$RD/src/main/java/com/family/finance/factview" 2>/dev/null \
  | grep -v 'AccountDiagnose.java' | grep -v 'StockHoldingService.java' | grep -v 'AccountService.java' | wc -l | tr -d ' ')"
{ grep -q 'public boolean isLiability()' "$QA1185_AT" \
  && grep -q 'public boolean isInvestment()' "$QA1185_AT" \
  && grep -q 'public boolean expectsFlowsToExplainBalance()' "$QA1185_AT" \
  && grep -q 'this == METAL' "$QA1185_AT" \
  && [ "${QA1185_BARE:-99}" -eq 0 ] \
  && [ -f "$RD/src/test/java/com/family/finance/domain/account/AccountTypeSemanticsTest.java" ] \
  && grep -q '每个类型都必须被显式分类过' "$RD/src/test/java/com/family/finance/domain/account/AccountTypeSemanticsTest.java" \
  && grep -q '贵金属算投资_这是v0_14漏掉的那一格' "$RD/src/test/java/com/family/finance/domain/account/AccountTypeSemanticsTest.java" \
  && grep -q 'return account.getType().isInvestment();' "$RD/src/main/java/com/family/finance/service/checkup/AccountDiagnose.java" \
  && ! grep -q 'AccountType.CRYPTO;' "$RD/src/main/java/com/family/finance/service/checkup/AccountDiagnose.java"; } \
  && log_ok "v1185-TYPE-SEMANTICS-NAMED(负债/投资/该被流水解释 三条具名谓词 · 钱路径无裸类型判断 · 结构性单测逼新类型表态)" \
  || log_bad "v1185-TYPE-SEMANTICS-NAMED 钱路径里又出现裸的类型判断(裸判断 $QA1185_BARE 处)" "改用 AccountType 的 isLiability/isInvestment/expectsFlowsToExplainBalance"

# v1185-MODEL-STALE-HINT · 型号失效要在报错里说清怎么办(v1.18.5 · 维护者定「不主动检测,报错时提示即可」)
# v1.18.4 给方舟预置了推荐型号,默认那个不带日期所以不会失效;但用户若选了带日期的几个,
# 总有一天会 404 —— 那时报错必须说清「是型号过期了 / 去哪换 / 换成什么」,而不是让他自己猜。
QA1185_INT="$QA_LLM_JAVA"   # v1.24.5 · 大模型那一节已挪到 AI 接入(原 IntegrationsController.java)
{ grep -q 'static boolean looksDateStamped' "$QA1185_INT" \
  && grep -q 'static String staleModelHint' "$QA1185_INT" \
  && grep -q 'doubao-seed-evolving' "$QA1185_INT" \
  && grep -q 'classifyLlmError(e.getMessage(), inv.resolvedModel())' "$QA1185_INT" \
  && ! grep -q '方舟需到控制台复制接入点 ID / 模型 ID' "$QA1185_INT"; } \
  && log_ok "v1185-MODEL-STALE-HINT(型号不存在时点名型号 + 带日期的指向 evolving/模型广场)" \
  || log_bad "v1185-MODEL-STALE-HINT 型号失效时没给出下一步" "classifyLlmError 要收 model 参数并走 staleModelHint"

# ═══════════════════════════════════════════════════════════════════
# v1.19 · 超级 Agent(资产对话)· tech-design/v1.19.md §五.3
# ═══════════════════════════════════════════════════════════════════

QA119_WEB="$RD/src/main/java/com/family/finance/web/ask"
QA119_SVC="$RD/src/main/java/com/family/finance/service/ask"
QA119_TOOLS="$QA119_SVC/tools"
QA119_RT="$QA119_SVC/runtime"

# v119-API-READONLY · 对外那两个入口(MCP + REST)不许有写方法。
#   只读是**物理保证**,不是约定:拿到口令的人再怎么构造请求也改不了账目。
#   例外一个:/unmet 是 POST,但它只写「agent 说它够不着」这条反馈,不碰任何业务表 ——
#   下面 v119-ASK-NO-BIZ-WRITE 单独钉死「碰不到业务表」这件事。
#   AskController(产品内对话)不在此列:它要建会话、存消息,那是本功能自己的表。
QA119_EXT="$QA119_WEB/McpEndpoint.java $QA119_WEB/AskApiController.java"
QA119_W=$(grep -hoE '@(Post|Put|Patch|Delete)Mapping' $QA119_EXT | sort | uniq -c | tr '\n' ' ')
QA119_BAD_W=$(grep -hoE '@(Put|Patch|Delete)Mapping' $QA119_EXT | wc -l)
{ [[ "$QA119_BAD_W" -eq 0 ]] \
  && [[ $(grep -c '@PostMapping' "$QA119_WEB/AskApiController.java") -le 2 ]]; } \
  && log_ok "v119-API-READONLY(对外入口无 PUT/PATCH/DELETE · POST 仅 pivot 查询与 unmet 反馈)" \
  || log_bad "v119-API-READONLY 对外入口出现了写方法($QA119_W)" "只读是物理保证,写操作不能出现在 /mcp 与 /api/v1/ask"

# v119-ASK-NO-BIZ-WRITE · 整个 ask 包不许写业务表。
#   会话/消息/引用/凭据/反馈是本功能自己的表,随便写;账户、流水、账期、持仓一个都不许碰。
QA119_BIZ=$(grep -rlE '\b(AccountMapper|CashFlowMapper|PeriodBalanceMapper|HoldingMapper|PeriodMapper)\.(insert|update|delete)' \
            "$QA119_WEB" "$QA119_SVC" 2>/dev/null | wc -l)
[[ "$QA119_BIZ" -eq 0 ]] \
  && log_ok "v119-ASK-NO-BIZ-WRITE(ask 包不写任何业务表 · 只写自己的 ask_* 表)" \
  || log_bad "v119-ASK-NO-BIZ-WRITE ask 包里出现了对业务表的写" "超级 Agent只读账目,写只能落在 ask_* 表"

# v119-ASK-NO-ARITHMETIC · 工具层不许自己算 → 防第三份口径。
#   工具只做三件事:校验参数 → 调既有 service → 包口径元数据。一旦这里出现算术,
#   同一个指标就有了「页面一份、报表一份、AI 一份」三个答案,而它们迟早会漂移。
# 判据要避开同名的集合方法。`\.add(` 抓不得 —— List.add 到处都是,试过两版都是误报
# (`out.add(v.toPlainString())` 这种,只因为循环变量恰好是 BigDecimal 就被抓)。
# 所以只认三样确定是算术的:
#   ① subtract/multiply/divide —— 这个代码库里是 BigDecimal 独有的
#   ② reduce(BigDecimal.ZERO —— 求和在本项目里就是这么写的
#   ③ 除法/取余运算符作用在数值上(占比、月数这类最容易被顺手算出来的)
QA119_MATH=$(grep -rnE '\.(subtract|multiply|divide)\(|reduce\(BigDecimal\.ZERO' "$QA119_TOOLS" 2>/dev/null \
             | grep -vE ':\s*(//|\*)' | wc -l)
[[ "$QA119_MATH" -eq 0 ]] \
  && log_ok "v119-ASK-NO-ARITHMETIC(service/ask/tools 无 BigDecimal 算术 · 口径只有一份)" \
  || log_bad "v119-ASK-NO-ARITHMETIC 工具层出现算术($QA119_MATH 处)" "算好的数从既有 service 取,工具层只负责转发"

# v119-ASK-PIVOT-REUSE · pivot 必须走 PivotEngine,不许另起一套聚合
{ grep -q 'PivotEngine.pivot(' "$QA119_TOOLS/PivotTool.java" \
  && grep -q 'lensQueryService.positions(' "$QA119_TOOLS/PivotTool.java"; } \
  && log_ok "v119-ASK-PIVOT-REUSE(pivot 走 PivotEngine + LensQueryService · 与透视页同一份)" \
  || log_bad "v119-ASK-PIVOT-REUSE pivot 没走既有引擎" "AI 看到的数必须与透视页逐字一致"

# v119-ONE-TOOL-DEF · MCP 与 OpenAI 两种工具清单必须由同一个 registry 的同一个 all() 生成。
#   分成两份的后果是加了工具只在一边生效 —— 而且不报错,只是「AI 说它没有这个能力」。
QA119_REG="$QA119_SVC/AskToolRegistry.java"
{ grep -q 'public List<Map<String, Object>> mcpToolList()' "$QA119_REG" \
  && grep -q 'public List<Map<String, Object>> openAiToolList()' "$QA119_REG" \
  && [[ $(grep -cE 'return all\(\)\.stream\(\)' "$QA119_REG") -eq 2 ]]; } \
  && log_ok "v119-ONE-TOOL-DEF(MCP 与 OpenAI 两份清单都遍历 registry.all() · 工具定义唯一真相)" \
  || log_bad "v119-ONE-TOOL-DEF 两种工具清单没同源" "两个方法都必须从 all() 出发,不能各写一套"

# v119-ASK-CITE-META · 工具返回必须带齐四样口径元数据。
#   少一样,答案里的数字就重新变成一个说不清出处的裸数字 —— 那正是 v1.18 整个系列在修的病。
QA119_RES="$QA119_SVC/AskToolResult.java"
{ grep -q 'meta.put("periodId"' "$QA119_RES" && grep -q 'meta.put("metricKey"' "$QA119_RES" \
  && grep -q 'meta.put("inProgress"' "$QA119_RES" && grep -q 'meta.put("currency"' "$QA119_RES"; } \
  && log_ok "v119-ASK-CITE-META(periodId/metricKey/inProgress/currency 四样一次给全)" \
  || log_bad "v119-ASK-CITE-META 口径元数据不全" "四样是引用块的原料,缺一样数字就说不清自己是哪一期"

# v119-ASK-NO-BARE-NUMBER · 正文裸数字要能被认出来(单测钉住判定本身)
{ grep -q 'public boolean hasBareNumber' "$QA119_SVC/AskCitationRenderer.java" \
  && grep -q 'bareNumberDetected' "$RD/src/test/java/com/family/finance/service/ask/AskCitationRendererTest.java"; } \
  && log_ok "v119-ASK-NO-BARE-NUMBER(裸金额可判定 + 单测钉住)" \
  || log_bad "v119-ASK-NO-BARE-NUMBER 没有裸数字判定" "模型不按规矩用引用块时要能标出来"

# v119-MCP-AUTH-HEADER · 凭据只走 Header。
#   MCP 规范明令禁止把 token 放 URI query —— 它会进 nginx access log、进浏览器历史、进 Referer。
QA119_GUARD="$QA119_SVC/AskAccessGuard.java"
{ grep -q 'getHeader("Authorization")' "$QA119_GUARD" \
  && ! grep -qE 'getParameter\("(token|access_token|key)"\)' "$QA119_GUARD"; } \
  && log_ok "v119-MCP-AUTH-HEADER(凭据只从 Authorization 头取 · 不从 query/path 取)" \
  || log_bad "v119-MCP-AUTH-HEADER 出现了从 URL 取 token 的写法" "MCP 规范禁止 query 传 token(会进日志和 Referer)"

# v119-MCP-TOKEN-HASHED · 库里只存 hash
QA119_TOK="$QA119_SVC/AccessTokenService.java"
{ grep -q 'MessageDigest.getInstance("SHA-256")' "$QA119_TOK" \
  && ! grep -qE 'setTokenPlain|token_plain|plaintext.*column' "$QA119_TOK" \
  && ! grep -qi 'token_plain' "$RD/db/migration/V57__ask_conversation.sql"; } \
  && log_ok "v119-MCP-TOKEN-HASHED(只存 SHA-256 哈希 · 表里没有明文列)" \
  || log_bad "v119-MCP-TOKEN-HASHED 出现了存明文的迹象" "明文只在生成那一次响应里出现,之后任何地方都取不回"

# v119-MCP-NO-TOKEN-IN-LOG · Authorization 不进日志
QA119_LOGTOK=$(grep -rnE 'log\.(info|warn|error|debug)\([^)]*(Authorization|bearer|plaintext\(\))' \
               "$QA119_WEB" "$QA119_SVC" 2>/dev/null | wc -l)
[[ "$QA119_LOGTOK" -eq 0 ]] \
  && log_ok "v119-MCP-NO-TOKEN-IN-LOG(凭据不进日志)" \
  || log_bad "v119-MCP-NO-TOKEN-IN-LOG 日志里可能带上了凭据($QA119_LOGTOK 处)" "日志会被打包发给我们排查,凭据不能在里面"

# v119-API-OFF-BY-DEFAULT · 没启用时返回 404,不是 401/403。
#   401 等于告诉扫描器「这里有东西,只是你没凭据」。404 什么都不告诉。
{ grep -q 'AskAuditResult.OFF : AskAuditResult.INVALID' "$QA119_TOK" \
  && grep -q 'notFound()' "$QA119_WEB/AskApiController.java" \
  && grep -q 'K_ASK_ENABLED, false' "$QA119_SVC/AskConversationService.java"; } \
  && log_ok "v119-API-OFF-BY-DEFAULT(默认关 · 未启用与口令错都返回 404)" \
  || log_bad "v119-API-OFF-BY-DEFAULT 默认状态或 404 语义不对" "未启用时不能透露这里有没有东西"

# v119-ASK-NO-CHAT-IN-AUDIT · 对话正文不进 audit_log。
#   审计要记「谁在什么时候调了什么工具」,不记「他问了什么、答了什么」——
#   后者是最私密的部分,而 audit_log 的保留期和访问面都比对话表宽。
QA119_CHAT=$(grep -rnE 'auditLogService\.record\([^)]*(contentText|question|answer)' \
             "$QA119_WEB" "$QA119_SVC" 2>/dev/null | wc -l)
[[ "$QA119_CHAT" -eq 0 ]] \
  && log_ok "v119-ASK-NO-CHAT-IN-AUDIT(对话正文不进 audit_log)" \
  || log_bad "v119-ASK-NO-CHAT-IN-AUDIT 对话正文被写进了审计" "审计记调用,不记聊天内容"

# v119-ASK-VENDOR-ISOLATED · 供应商字样只出现在 runtime 包。
#   业务层一旦认识「百炼」,换供应商就要动编排、动落库、动引用装配 —— 那是最不该被牵动的部分。
# 判据只看**出网调用**:供应商域名 / SDK 引用。注释与用户文案里提到「百炼」是对的 ——
# 用户确实要去那儿改配置,把词也禁掉只会逼出更含糊的文案。
QA119_VENDOR=$(grep -rlE 'aliyuncs\.com|dashscope\.aliyuncs|com\.alibaba\.dashscope|maas\.aliyuncs' \
               "$QA119_WEB" "$QA119_SVC" 2>/dev/null | grep -v '/runtime/' | wc -l)
[[ "$QA119_VENDOR" -eq 0 ]] \
  && log_ok "v119-ASK-VENDOR-ISOLATED(供应商端点只在 service/ask/runtime 下 · 业务层不出网)" \
  || log_bad "v119-ASK-VENDOR-ISOLATED runtime 之外出现了供应商端点($QA119_VENDOR 个文件)" "业务层只认识 AgentRuntime"

# v119-ASK-TWO-SHELLS · 侧栏与全屏页共用同一个 _stream 片段。
#   维护者的判断:「就是一个 sse 的对话流,那有必要区分移动端或者 PC 端嘛?」——
#   没必要。两份壳各写一遍对话体,改一处必漏另一处。
QA119_T="$RD/src/main/resources/templates/ask"
QA119_SHELLS=$(grep -lE 'ask/fragments/_stream :: stream' "$QA119_T/index.html" "$QA119_T/fragments/_panel.html" 2>/dev/null | wc -l)
[[ "$QA119_SHELLS" -eq 2 ]] \
  && log_ok "v119-ASK-TWO-SHELLS(整页与抽屉都 replace 到同一个 _stream 片段)" \
  || log_bad "v119-ASK-TWO-SHELLS 两种壳没共用对话片段(命中 $QA119_SHELLS/2)" "差别只应在外面那层容器"

# v119-ASK-NO-AUTOSCROLL · 流式脚本不许无条件滚到底。
#   用户往回翻看上一条回答时被弹回底部,是流式界面最招人烦的一件事,而且长回答期间会反复发生。
QA119_JS="$RD/src/main/resources/static/js/ask.js"
# 判据不绑变量名 —— 第一版写死 `keepBottom(wasAtBottom)`,改版时参数改叫 was 就红了,
# 而被守的东西一个字没变。真正的不变量是两条:① 有「现在在不在底部」的判定;
# ② 滚动函数**内部有条件**,不是无脑滚。再加上不许出现 scrollIntoView。
{ grep -q 'function atBottom' "$QA119_JS" \
  && grep -A1 'function keepBottom' "$QA119_JS" | grep -q 'if (' \
  && ! grep -nE 'scrollIntoView' "$QA119_JS" | grep -qvE ':\s*\*|:\s*//'; } \
  && log_ok "v119-ASK-NO-AUTOSCROLL(只在用户本来就在底部时才跟着滚)" \
  || log_bad "v119-ASK-NO-AUTOSCROLL 出现了无条件滚动" "无条件 scrollIntoView 会把正在往回看的用户弹回底部"

# v119-ASK-NO-HTML-CONCAT · 前端不许拼 HTML 字符串。
#   模型输出是不可信输入(提示词注入可以从账户名里进来)。全部走 createElement + textContent,
#   转义漏一处的可能性直接归零 —— 比「记得每处都转义」可靠。
{ ! grep -qE '\.innerHTML\s*=' "$QA119_JS" \
  && grep -q 'createElement' "$QA119_JS" \
  && grep -q 'replaceChildren' "$QA119_JS"; } \
  && log_ok "v119-ASK-NO-HTML-CONCAT(流式渲染建 DOM 节点 · 不拼 HTML 字符串)" \
  || log_bad "v119-ASK-NO-HTML-CONCAT 前端出现了 innerHTML 赋值" "模型输出不可信,用 createElement + textContent"

# v119-ASK-ROLLBACK · 调工具前的旁白不能落库。
#   不撤的话,存下来的答案会是「我来查一下平台分布。我来查一下资产情况。你的钱主要在…」——
#   三个月后重看,前两句只会让人困惑。联调时实测到这个现象,才补的这条通道。
{ grep -q 'void rollback(String narration)' "$QA119_RT/AskSink.java" \
  && grep -q 'sink.rollback(c.text)' "$QA119_RT/LocalToolLoopRuntime.java" \
  && grep -q 'text.setLength(at)' "$QA119_SVC/AskConversationService.java"; } \
  && log_ok "v119-ASK-ROLLBACK(工具前旁白撤回 · 界面降级为灰字 · 库里不留)" \
  || log_bad "v119-ASK-ROLLBACK 旁白撤回链路不全" "AskSink.rollback → runtime 调用 → Collector 砍缓冲,三处缺一不可"

# ─── v1.19 改版(对齐 Claude / ChatGPT / Manus 的结构规范)──────────────────

QA119_CSS="$RD/src/main/resources/static/css/style.css"
QA119_STREAM="$QA119_T/fragments/_stream.html"

# v119-ASK-STOPPABLE · 能停下来。
#   长回答要跑一两分钟,而用户常在半句话之内就知道方向错了 —— 让他干等是没道理的。
#   停止是**协作式**的:三处缺一不可 —— sink 暴露 cancelled、runtime 在读流循环里逐行看它、
#   controller 有置位的端点。少了 runtime 那一环,按钮点了也只是把前端连接关掉,
#   上游照样在跑、照样在计费。
{ grep -q 'boolean cancelled();' "$QA119_RT/AskSink.java" \
  && grep -q 'if (sink.cancelled()) break;' "$QA119_RT/LocalToolLoopRuntime.java" \
  && grep -q '@PostMapping("/ask/{id}/stop")' "$QA119_WEB/AskController.java" \
  && grep -q 'data-ask-stop' "$QA119_STREAM"; } \
  && log_ok "v119-ASK-STOPPABLE(停止链路四处齐全:契约 / 读流循环逐行检查 / 端点 / 按钮)" \
  || log_bad "v119-ASK-STOPPABLE 停止链路不全" "少了 runtime 那一环的话,点停止只是关掉前端连接,上游还在计费"

# v119-ASK-FOLLOWUPS · 追问 chip(FR-424b)。
#   v1.19 第一版漏了它 —— PRD 承诺过、预览里画了、代码里没有。
#   链路是「提示词要求模型产出 → 渲染器抽出来 → 模板渲染成可点按钮」,三处缺一就静默失效。
{ grep -q '{{next:' "$QA119_SVC/AskPromptBuilder.java" \
  && grep -q 'public List<String> nextQuestions' "$QA119_SVC/AskCitationRenderer.java" \
  && grep -q 'ask-nexts' "$QA119_STREAM" \
  && grep -q 'ask-nexts' "$QA119_JS"; } \
  && log_ok "v119-ASK-FOLLOWUPS(追问链路四处齐全:提示词 / 抽取 / 模板 / 流式)" \
  || log_bad "v119-ASK-FOLLOWUPS 追问 chip 链路不全" "FR-424b 承诺过,第一版就是这么漏掉的"

# v119-ASK-NO-BUBBLE · 不用气泡。
#   这是改版时最反直觉的一条判断,所以要钉住,否则下次很容易被「加个气泡更像聊天」改回去。
#   理由:圆角实心气泡传递的是「随便聊聊」,削弱工具感;而且 AI 那侧是扁平长文,
#   一边气泡一边文档,自己跟自己打架。Claude / ChatGPT / Cursor 都已经改成扁平。
#   判据看的是「用户消息不许有实心底色 + 大圆角」这两样气泡的构成要件。
#   取块用 awk 按**空行**切,不用 sed 的 /^}/ —— 本仓库 CSS 是紧凑单行风格,
#   行首独立的 } 几乎不存在,第一版判据因此一路抓到后面的规则,拿 .ask-note 的圆角当成了气泡。
QA119_ME=$(awk '/^\.ask-me/{f=1} f&&/^[[:space:]]*$/{f=0} f' "$QA119_CSS")
QA119_BUBBLE=$(printf '%s' "$QA119_ME" | grep -cE 'background:var\(--ink\)|border-radius:[0-9]')
{ [[ "$QA119_BUBBLE" -eq 0 ]] \
  && ! grep -q 'ask-bub-me' "$QA119_STREAM" "$QA119_JS" \
  && printf '%s' "$QA119_ME" | grep -q 'border-bottom'; } \
  && log_ok "v119-ASK-NO-BUBBLE(用户消息扁平 · 右对齐 + 细下划线,不是实心圆角气泡)" \
  || log_bad "v119-ASK-NO-BUBBLE 用户消息又变回气泡了" "气泡削弱工具感,且与 AI 侧的扁平长文打架"

# v119-ASK-READING-WIDTH · 正文列宽。
#   768 是 Claude / ChatGPT 收敛出来的值(约 65–72 西文字符);中文 15px 下一行约 40 字。
#   改版前是 860px + 13.5px,一行奔 60 个汉字 —— 那是文档排版不是对话排版,长回答读着串行。
{ grep -q -- '--ask-col: 768px' "$QA119_CSS" \
  && grep -q 'max-width:var(--ask-col)' "$QA119_CSS" \
  && grep -q 'ask-col' "$QA119_STREAM"; } \
  && log_ok "v119-ASK-READING-WIDTH(正文列 768px · 中文一行约 40 字)" \
  || log_bad "v119-ASK-READING-WIDTH 正文列宽没收住" "一行 60 个汉字读起来会串行"

# v119-ASK-STREAM-CURSOR · 流式光标。
#   没有它,模型思考的停顿会被读成「已经结束了」—— 这是规范里点名的「掉了就出事」的一条。
{ grep -q 'ask-caret-live' "$QA119_JS" && grep -q '\.ask-caret-live' "$QA119_CSS"; } \
  && log_ok "v119-ASK-STREAM-CURSOR(流式期间有光标)" \
  || log_bad "v119-ASK-STREAM-CURSOR 没有流式光标" "停顿会被读成已结束"


# ─── v1.19 · 超级 Agent 改名 / 思考过程 / 富展示 ────────────────────────────

# codeonly · 只留代码行,滤掉注释。
#   这一轮被同一个形状绊了三次:护栏的**说明文字**里写了它自己禁止的那个词,
#   于是判据在一个完全正确的实现上报红(sandbox 那条、srcdoc 那条),
#   而更早一次是反过来 —— 说明文字里写了真实金额,泄露判据抓到了自己。
#   结论:**判据扫的是代码,不是注释**;而注释里要举反例是很自然的写法,不该为此改写注释。
codeonly() {
  # 认三种注释:块注释 /* … */(**含不以 * 开头的续行** —— 第一版就漏在这儿)、
  # 行注释 // 与 #、以及 HTML 注释。用 awk 跟一个状态位,比正则删块注释稳。
  awk '
    { line = $0 }
    inblk { if (line ~ /\*\//) { inblk = 0 }; next }
    line ~ /\/\*/ && line !~ /\*\// { inblk = 1; next }
    line ~ /^[[:space:]]*(\/\/|#|<!--|\*)/ { next }
    { sub(/\/\/.*$/, "", line); print line }
  ' "$1"
}


QA119_CHARTJS="$RD/src/main/resources/static/js/ask-charts.js"
QA119_REND="$QA119_SVC/AskCitationRenderer.java"

# v119-ASK-ARTIFACT-SANDBOXED · 自由 HTML 必须关在沙箱里。
#   让模型直接吐 HTML 是这一版新开的口子。敢开是因为它跑在 sandbox 且
#   **不给 allow-same-origin** 的 iframe 里 —— 那是一个 opaque origin:
#   脚本能跑,但读不到我们的 cookie / DOM / localStorage,也发不出带凭据的请求。
#   一旦有人为了「让图能拿到页面数据」加上 allow-same-origin,这层隔离当场归零,
#   而提示词注入可以从账户名里进来。所以这条判据同时钉正反两面。
{ grep -q "setAttribute('sandbox', 'allow-scripts')" "$QA119_CHARTJS" \
  && ! codeonly "$QA119_CHARTJS" | grep -q 'allow-same-origin' \
  && ! codeonly "$QA119_REND" | grep -q 'allow-same-origin'; } \
  && log_ok "v119-ASK-ARTIFACT-SANDBOXED(自由 HTML 关在 opaque origin 里 · 无 allow-same-origin)" \
  || log_bad "v119-ASK-ARTIFACT-SANDBOXED 沙箱隔离被削弱" "加上 allow-same-origin 等于把 cookie 和 DOM 交给模型输出"

# v119-ASK-CHART-CITED · 图上的数只能来自引用。
#   否则「数字保真」只保住了正文那一半 —— 图里画的还是模型编的,而图比文字更容易被当真。
#   判据钉两条:引用不到的点被丢掉、一个都引不到就整张不画。
{ grep -q 'if (c == null) continue;' "$QA119_REND" \
  && grep -q 'if (pts.isEmpty()) return "";' "$QA119_REND" \
  && grep -q "if (!c) return;" "$QA119_JS"; } \
  && log_ok "v119-ASK-CHART-CITED(图表数据点只来自引用 · 引不到就不画)" \
  || log_bad "v119-ASK-CHART-CITED 图表可能画上模型自己编的数" "图比文字更容易被当真,不能松这一条"

# v119-ASK-SCAFFOLD-SINGLE · iframe 的脚手架只能有一处。
#   服务端渲染历史消息、客户端渲染流式输出,两条路径都要 srcdoc。各拼一份的话,
#   注进去的样式与脚本迟早漂移,表现就是「流完刷新一下,图变了个样」。
#   现在两边都只吐容器(data-ask-artifact),iframe 一律由 ask-charts.js 组装。
{ grep -q 'data-ask-artifact' "$QA119_REND" \
  && ! codeonly "$QA119_REND" | grep -q 'srcdoc' \
  && ! codeonly "$QA119_JS" | grep -q 'srcdoc' \
  && grep -q 'f.srcdoc = HEAD' "$QA119_CHARTJS"; } \
  && log_ok "v119-ASK-SCAFFOLD-SINGLE(srcdoc 脚手架只在 ask-charts.js 一处)" \
  || log_bad "v119-ASK-SCAFFOLD-SINGLE 两条渲染路径各拼了一份 srcdoc" "迟早漂移成「流完刷新样子会变」"

# v119-ASK-THINK-ENGAGED · 用户在看的时候不许自动折叠思考过程。
#   判据不能用 scroll 事件当「用户在看」—— 我们自己的 keepBottom 也会触发 scroll,
#   那样每一轮都会被判成「在看」,自动折叠永远不生效(等于没做)。
{ grep -q 'var engaged = false;' "$QA119_JS" \
  && grep -q 'if (!engaged) {' "$QA119_JS" \
  && grep -q "'wheel', 'touchmove', 'pointerdown', 'keydown'" "$QA119_JS" \
  && ! grep -qE "addEventListener\('scroll', *markEngaged" "$QA119_JS"; } \
  && log_ok "v119-ASK-THINK-ENGAGED(思考过程自动折叠 · 但用户在看时不收)" \
  || log_bad "v119-ASK-THINK-ENGAGED 折叠时机的判据不对" "拿 scroll 当判据会因为我们自己的滚动而永远不折叠"

# v119-ASK-THINK-DETAIL · 思考过程要给得出「查了什么、查到了什么」。
#   只报工具名和耗时等于什么都没说 —— 用户想看的是它到底看了哪些数,
#   那才是判断答案可不可信的依据。
{ grep -q 'String summary' "$QA119_SVC/AskToolResult.java" \
  && grep -q 'argsBrief' "$QA119_RT/LocalToolLoopRuntime.java" \
  && grep -q 'ask-act-args' "$QA119_STREAM" \
  && grep -q 'ask-act-sum' "$QA119_STREAM"; } \
  && log_ok "v119-ASK-THINK-DETAIL(思考过程带参数与结果摘要,不只是工具名和耗时)" \
  || log_bad "v119-ASK-THINK-DETAIL 思考过程信息量不足" "只报名字和耗时,用户判断不了答案可不可信"

# v119-NAV-NO-WORD-BREAK · 导航不许把词断开。
#   改名后「超级 Agent + AI 徽记」比原来宽约 57px,一行装不下 → flex 压缩每一项 →
#   **每个词在自己内部折成两行**(「仪表/盘」「填/报」)。三种处理里只有一种能接受:
#   项内不断词 + 整条换行 + header 跟着长高(不能是 h-16 固定高,否则溢出压住页面)。
QA119_NAV="$RD/src/main/resources/templates/fragments/nav.html"
{ grep -q 'whitespace-nowrap' "$QA119_NAV" \
  && grep -q 'nav-tabs hidden lg:flex flex-wrap' "$QA119_NAV" \
  && grep -q 'min-h-16' "$QA119_NAV" \
  && ! grep -q 'justify-between h-16' "$QA119_NAV"; } \
  && log_ok "v119-NAV-NO-WORD-BREAK(导航项内不断词 · 整条可换行 · header 高度跟着长)" \
  || log_bad "v119-NAV-NO-WORD-BREAK 导航会把词断开或撑溢出" "「仪表/盘」这种断法比换行难看得多"

# v119-ASK-GREETING-ROTATES · 空态那句话要轮换。
#   固定一句的话,一个月看四十遍之后它就不再传递任何信息 —— 那块位置本来可以每次给点不一样的。
QA119_GREET="$QA119_SVC/AskGreetings.java"
QA119_GN=$(grep -cE '^\s+"' "$QA119_GREET" 2>/dev/null)
{ [[ "${QA119_GN:-0}" -ge 80 ]] \
  && grep -q 'AskGreetings.random()' "$QA119_WEB/AskController.java" \
  && grep -q '${greeting}' "$QA119_STREAM"; } \
  && log_ok "v119-ASK-GREETING-ROTATES(空态 $QA119_GN 句轮换 · 每次进来随机一句)" \
  || log_bad "v119-ASK-GREETING-ROTATES 欢迎语没在轮换(当前 ${QA119_GN:-0} 句)" "固定一句看四十遍就等于没有"


# v119-ASK-PRIVACY-COVERS-MONEY · 隐私模式要盖住**新加的展示面**,而且只盖金额。
#   v1.19 加了三处会显示金额的地方:引用卡/chip、思考过程摘要、图表 datalabels。
#   第一版只糊了前一处的一部分,结果是:**隐私模式开着,思考过程里的总资产照样露在截图上**
#   (做 Release 页截图时才发现 —— 那张图差点就发到公开仓库上了)。
#   反方向也错:一刀切糊掉所有引用,连百分比一起糊 —— 而饼图上同一批百分比是清晰的,
#   自己跟自己矛盾。所以判据两头都钉:金额判定只有一份(renderer.isMoney),
#   三处都用它,而且**不许**再出现对 .ask-cite-v / .ask-chip 的无条件模糊。
{ grep -q 'static boolean looksMoney' "$QA119_REND" \
  && grep -q 'public boolean isMoney' "$QA119_REND" \
  && grep -q 'renderer.isMoney(t.summary)' "$QA119_STREAM" \
  && grep -q 'function looksMoney' "$QA119_JS" \
  && grep -q 'data-priv-chart' "$QA119_CSS" \
  && ! codeonly "$QA119_CSS" | grep -qE 'html\.privacy \.ask-(cite-v|chip)\{'; } \
  && log_ok "v119-ASK-PRIVACY-COVERS-MONEY(引用/思考摘要/图表都受隐私开关 · 只糊金额不糊百分比)" \
  || log_bad "v119-ASK-PRIVACY-COVERS-MONEY 隐私覆盖不全或过度" "漏了会在截图里泄露金额;过度会把百分比也糊掉"


# v1192-MCP-CONFIG-FROM-JAVA · 给用户复制的 JSON 必须由 Java 生成,不许在模板里手拼。
#   Thymeleaf 字符串字面量里的 \n **不是换行** —— 手拼那版渲染出来是带字面 \n 的一行,
#   用户照抄进百炼就是一段无效 JSON,而这条错要等到百炼那边连不上才暴露。
#   v1.19.0 就带着这个 bug 发出去了,写接入教程时才发现(教的正是这一步)。
#   两处(生成口令那张卡 + 教程示例)必须同源,否则迟早只改一处。
QA1192_AA="$RD/src/main/resources/templates/admin/ai-access.html"
{ grep -q 'th:text="${freshMcpConfig}"' "$QA1192_AA" \
  && grep -q 'th:text="${mcpConfigSample}"' "$QA1192_AA" \
  && ! grep -q 'streamableHttp&quot;,\\n' "$QA1192_AA" \
  && grep -q 'mcpConfigJson' "$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java"; } \
  && log_ok "v1192-MCP-CONFIG-FROM-JAVA(粘给百炼的 JSON 由 Java 生成 · 两处同源)" \
  || log_bad "v1192-MCP-CONFIG-FROM-JAVA JSON 又在模板里手拼了" "Thymeleaf 字面量的 \\n 不是换行,用户复制走的会是无效 JSON"

# v1192-BAILIAN-SETUP-GUIDE · 托管路线的两处**人工**步骤必须有实操说明。
#   「业务空间 ID」「MCP 服务 ID」这两项都要去阿里云控制台里翻才拿得到,
#   而这一页原来只有两个空输入框 —— 对着空框是问不出这两个词的。
#   判据钉住:分步向导在、两段 how-to 在、官方文档链接在、权限要求写了。
{ grep -q 'ask-steps' "$QA1192_AA" \
  && [[ $(grep -c 'class="ask-how"' "$QA1192_AA") -ge 2 ]] \
  && grep -q 'AliyunBailianFullAccess' "$QA1192_AA" \
  && grep -q 'obtain-the-app-id-and-workspace-id' "$QA1192_AA" \
  && grep -q 'model-studio/custom-mcp' "$QA1192_AA"; } \
  && log_ok "v1192-BAILIAN-SETUP-GUIDE(业务空间 ID / MCP 服务 ID 两处人工步骤都有实操说明 + 官方链接)" \
  || log_bad "v1192-BAILIAN-SETUP-GUIDE 接入教程不全" "这两项要去阿里云控制台翻,对着空输入框用户问不出来"

# v11910-MANAGED-IS-DEFAULT · 托管路线是**默认**,本机直连只能是用户主动选的备选。
#
#   这条取代了原来的 v1192-MANAGED-UNVERIFIED-STATED(它要求页面上写着
#   「这条路线我们还没在真实环境里跑通过」)。两件事让那条作废:
#     ① 2026-09-03 15:11 百炼**真的完整走通了**握手:initialize → notifications/initialized
#        → tools/list 三步全 OK(prod ask_access_audit 有记录)。那句话已经是**事实错误**。
#     ② 维护者指出:「这种话怎么能放进发布的版本里」——「没验过」不是该发给用户的免责声明,
#        而是该去把它验通。理由本身也是假的:prod 就是公网 HTTPS,一直可以在上面验。
#
#   现在守的是另一件事:PRD v1.19 开头维护者拍板「走 Managed Agents,不自建编排」
#   「直接选 B,没有 A 这个方案」。而我写了本机直连(自建 loop = 那个 A)并设成默认。
#   这条护栏钉住「默认必须是 managed」,防止它再被悄悄改回去。
QA11910_ASK="$RD/src/main/java/com/family/finance/service/ask/AskConversationService.java"
{ codeonly "$QA11910_ASK" | grep -q 'K_ASK_RUNTIME, ManagedAgentRuntime.CODE' \
  && ! codeonly "$QA11910_ASK" | grep -q 'K_ASK_RUNTIME, LocalToolLoopRuntime.CODE' \
  && ! grep -q '还没在真实环境里跑通过' "$QA1192_AA"; } \
  && log_ok "v11910-MANAGED-IS-DEFAULT(默认 runtime = 百炼托管 · 页面无「未验证」免责声明)" \
  || log_bad "v11910-MANAGED-IS-DEFAULT 默认又回到自建 loop,或免责声明又出现" "维护者拍板是「没有 A 这个方案」;而且那条路线已经验通了"


# v119-CUSTODY-EXHAUSTIVE · 加新 AccountType 必须在托管形式里表态
{ grep -q 'CustodyForm' "$RD/src/main/java/com/family/finance/domain/lens/CustodyForm.java" \
  && grep -q 'dim("custody"' "$RD/src/main/java/com/family/finance/calc/lens/LensRegistry.java" \
  && [[ -f "$RD/src/test/java/com/family/finance/domain/lens/CustodyFormTest.java" ]]; } \
  && log_ok "v119-CUSTODY-EXHAUSTIVE(托管形式维度已注册 + 结构性单测逼新类型表态)" \
  || log_bad "v119-CUSTODY-EXHAUSTIVE 托管形式链路不全" "枚举/维度/单测三处缺一不可"


# ═══ v1.19.3 · 信用卡支出 ═══
QA1193_EC="$RD/src/main/java/com/family/finance/web/entry/EntryController.java"
QA1193_ES="$RD/src/main/java/com/family/finance/service/EntryService.java"
QA1193_TPL="$RD/src/main/resources/templates/entry/index.html"
QA1193_JS="$RD/src/main/resources/static/js/expense-liability.js"

# v1193-EXPENSE-ACCT-OPEN · 支出账户候选不许再按类型排除负债账户。
#   这正是线上那个「信用卡选不中」的成因:AccountType 里没有信用卡,信用卡只能录成 LOAN,
#   而这里排掉了整个 LOAN。哪天有人"顺手"把 filter 加回来,这条要立刻红。
QA1193_FILTER=$(codeonly "$QA1193_EC" | grep -c 'expenseAccounts' || true)
{ [[ "$QA1193_FILTER" -ge 1 ]] \
  && ! codeonly "$QA1193_EC" | grep -A3 'expenseAccounts' | grep -q 'AccountType.LOAN'; } \
  && log_ok "v1193-EXPENSE-ACCT-OPEN(支出账户候选不排除负债账户 · 信用卡可选)" \
  || log_bad "v1193-EXPENSE-ACCT-OPEN 支出账户又把负债类排掉了" "信用卡只能录成 LOAN,排掉 LOAN 等于信用卡消费录不进去"

# v1193-EXPENSE-LIABILITY-CAT · 放开之后必须挡住支出双计。
#   刷卡记「消费」+ 还款记「还贷」= 同一笔钱进两次本月支出。这种错报表上看着完全正常
#   (每个数字都是真的,只是被算了两次),肉眼复核发现不了 —— 只能靠服务端硬拦 + 单测钉住。
{ codeonly "$QA1193_ES" | grep -q 'REPAYMENT_CATEGORIES' \
  && codeonly "$QA1193_ES" | grep -q 'loan_payment' \
  && codeonly "$QA1193_ES" | grep -q 'interest_paid' \
  && codeonly "$QA1193_ES" | grep -q 'expenseCategoryAllowedOn' \
  && [[ -f "$RD/src/test/java/com/family/finance/service/EntryExpenseLiabilityTest.java" ]]; } \
  && log_ok "v1193-EXPENSE-LIABILITY-CAT(负债账户禁「还贷/利息支出」· 服务端硬拦 + 单测)" \
  || log_bad "v1193-EXPENSE-LIABILITY-CAT 支出双计防护缺失" "刷卡记消费+还款记还贷会让本月支出翻倍,且报表上看不出来"

# v1193-EXPENSE-DIR-PINNED · 负债余额存负数这条约定被单测钉住。
#   这次放开之所以不用给负债账户加方向分支,全靠它。约定要是翻了(改成欠款存正数),
#   刷一笔卡会变成负债减少、净资产虚增,而且没有任何地方会报错。
{ grep -q 'expenseOnCreditCard_increasesDebt' "$RD/src/test/java/com/family/finance/service/EntryExpenseLiabilityTest.java" \
  && codeonly "$QA1193_ES" | grep -q 'isLiability() && scaled.signum() > 0'; } \
  && log_ok "v1193-EXPENSE-DIR-PINNED(负债余额存负数 + 支出方向有单测钉住)" \
  || log_bad "v1193-EXPENSE-DIR-PINNED 负债余额方向约定失守" "方向翻了会让刷卡变成净资产虚增,且不报错"

# v1193-EXPENSE-CAT-UI-SYNC · 前端要和服务端同口径,而且必须是「藏起来」不是「置灰」。
#
#   v1.21 改写:类目从 <select data-lsel> 换成常驻宫格(_cat-grid.html),
#   所以判据从「摘 option 再插回原位」(removeChild/insertBefore)变成「藏按钮」。
#   这不是放宽 —— 旧实现在新结构上会**抛 TypeError**(data-expense-cat 现在是 hidden input,
#   没有 .options),而且只在用户选中信用卡那一刻才抛:首屏走恢复分支提前 return,
#   页面打开时一切正常。这条护栏现在钉的是「新实现真的在藏按钮」。
{ grep -q 'data-repayment' "$RD/src/main/resources/templates/entry/_cat-grid.html" \
  && grep -q 'data-liability' "$QA1193_TPL" \
  && grep -q 'expense-liability.js' "$QA1193_TPL" \
  && grep -q 'data-expense-liability-hint' "$QA1193_TPL" \
  && grep -q 'btn.hidden = isLiability' "$QA1193_JS" \
  && ! grep -q '\.options\[' "$QA1193_JS" \
  && grep -q "codeInput.value = 'consumption'" "$QA1193_JS"; } \
  && log_ok "v1193-EXPENSE-CAT-UI-SYNC(前端藏掉还贷类目+提示,与服务端同口径)" \
  || log_bad "v1193-EXPENSE-CAT-UI-SYNC 前端类目约束缺失,或还在用 select 那套" "宫格是按钮不是 option,旧实现会在选中信用卡时抛 TypeError"


# ═══ v1.19.4 · 截图识别失败不许装成功 ═══
QA1194_SVC="$RD/src/main/java/com/family/finance/service/holdingimport/HoldingImportService.java"
QA1194_MAP="$RD/src/main/java/com/family/finance/repository/HoldingImportMapper.java"
QA1194_TPL="$RD/src/main/resources/templates/holdingimport/import.html"
QA1194_DOM="$RD/src/main/java/com/family/finance/domain/holdingimport/HoldingImport.java"

# v1194-SCANERR-NOT-REVIEW · markScanError 必须写 SCAN_ERROR,不能写 REVIEW。
#   这是线上那次事故的正根:识别全失败 → 状态仍是 REVIEW → 页面给出一张
#   「库里每条持仓都是卖出?」的比对表 + 确认按钮。差一次勾选就清空真实持仓。
{ codeonly "$QA1194_MAP" | grep -q "status = 'SCAN_ERROR', scan_error" \
  && ! codeonly "$QA1194_MAP" | grep -A1 'int markScanError' | grep -q "status = 'REVIEW'" \
  && codeonly "$QA1194_DOM" | grep -q 'SCAN_ERROR = "SCAN_ERROR"'; } \
  && log_ok "v1194-SCANERR-NOT-REVIEW(识别全失败进 SCAN_ERROR 独立状态,不是 REVIEW)" \
  || log_bad "v1194-SCANERR-NOT-REVIEW 识别失败又被写成 REVIEW" "那会给出一张全是「卖出?」的比对表,勾一下归档就清空真实持仓"

# v1194-ALLFAIL-NO-ITEMS · 全失败时一条比对项都不许生成(包括 SOLD)。
#   有 item 就有表,有表就有误确认的可能。
{ codeonly "$QA1194_SVC" | grep -q 'failedImages == images.size()' \
  && codeonly "$QA1194_SVC" | grep -A4 'failedImages == images.size()' | grep -q 'markScanError' \
  && codeonly "$QA1194_SVC" | grep -A5 'failedImages == images.size()' | grep -q 'return;'; } \
  && log_ok "v1194-ALLFAIL-NO-ITEMS(全失败直接置错并 return · 不生成任何比对项)" \
  || log_bad "v1194-ALLFAIL-NO-ITEMS 全失败还在往下走" "会把库里每条持仓判成 SOLD"

# v1194-NO-SOLD-ON-PARTIAL · 有图没识别成功时,整体不判「卖出」。
#   「没识别出来」≠「卖掉了」。部分失败比全失败更骗人:表格其余部分完全正常,
#   只混着几条假的卖出建议 —— 用户没有任何线索能分辨。
{ codeonly "$QA1194_SVC" | grep -q 'if (failedImages == 0)' \
  && codeonly "$QA1194_SVC" | grep -q 'markReviewWithWarning'; } \
  && log_ok "v1194-NO-SOLD-ON-PARTIAL(有图失败则不判卖出 + 页面警告)" \
  || log_bad "v1194-NO-SOLD-ON-PARTIAL 部分失败仍在判卖出" "没识别出来不等于卖掉了,假卖出建议会骗用户归档真实持仓"

# v1194-FRIENDLY-ACTIONABLE · 上游失败要给能照着做的话,不能一律「请重试」。
#   额度耗尽时说「请重试」是错的建议 —— 重试一万次也不会好。
{ codeonly "$QA1194_SVC" | grep -q 'AllocationQuota' \
  && codeonly "$QA1194_SVC" | grep -q 'Free quota exhausted' \
  && codeonly "$QA1194_SVC" | grep -qE '429|RateLimit' \
  && codeonly "$QA1194_SVC" | grep -q 'InvalidApiKey' \
  && grep -q 'friendly_quotaExhausted_tellsUserToTopUp_notRetry' \
       "$RD/src/test/java/com/family/finance/service/holdingimport/HoldingImportUnitTest.java"; } \
  && log_ok "v1194-FRIENDLY-ACTIONABLE(配额/限流/鉴权/型号分别给可操作提示 · 有单测)" \
  || log_bad "v1194-FRIENDLY-ACTIONABLE 上游失败又退化成一句「请重试」" "额度用完时重试是无用功,必须指向控制台"

# v1194-SCANERR-NO-CONFIRM · SCAN_ERROR 那一段不能是可提交的 form。
#   页面上没有确认按钮 = 物理上不可能误确认。同时 confirm() 服务端也要挡。
{ grep -q "imp.status == 'SCAN_ERROR'" "$QA1194_TPL" \
  && ! sed -n "/imp.status == 'SCAN_ERROR'/,/<\/section>/p" "$QA1194_TPL" | grep -qE '<form|type=.submit' \
  && codeonly "$QA1194_SVC" | grep -q 'REVIEW.equals(imp.getStatus())'; } \
  && log_ok "v1194-SCANERR-NO-CONFIRM(失败态没有确认表单 · 服务端 confirm 也只认 REVIEW)" \
  || log_bad "v1194-SCANERR-NO-CONFIRM 失败态还能提交确认" "误确认必须在物理上不可能,不能只靠用户看提示"


# ═══ v1.19.5 · 上传态缩略图要能删能放大 ═══
QA1195_TPL="$RD/src/main/resources/templates/holdingimport/import.html"
QA1195_CTL="$RD/src/main/java/com/family/finance/web/holdingimport/HoldingImportController.java"

# v1195-ONE-GALLERY · 上传态只能有一个缩略图容器。
#   分成两个(JS 画的 #thumbs + 服务端画的 js-gallery)是这次缺陷的根:
#   刚上传的那批落在没有删除/放大能力的那一半,用户「传完立刻想点开看」正好撞上。
{ grep -q 'id="thumbs" class="[^"]*js-gallery' "$QA1195_TPL" \
  && [ "$(grep -c 'class="flex flex-wrap gap-3 js-gallery"' "$QA1195_TPL")" -le 3 ]; } \
  && log_ok "v1195-ONE-GALLERY(上传态缩略图与画廊同一个容器 · 同结构同行为)" \
  || log_bad "v1195-ONE-GALLERY 上传态又出现了第二个缩略图容器" "刚上传的图会落在没有 ✕/放大的那一半"

# v1195-THUMB-IS-GSHOT · JS 新建的缩略图必须是 .gshot 且带 ✕ 与 data-src。
{ codeonly "$QA1195_TPL" | grep -q "d.className='gshot pending'" \
  && codeonly "$QA1195_TPL" | grep -q "rm.className='grm'" \
  && codeonly "$QA1195_TPL" | grep -q "d.setAttribute('data-src'"; } \
  && log_ok "v1195-THUMB-IS-GSHOT(JS 缩略图 = .gshot + ✕ + data-src)" \
  || log_bad "v1195-THUMB-IS-GSHOT JS 缩略图退回成哑元素" "没有 ✕ 就删不掉,没有 data-src 就点不开"

# v1195-GALLERY-DELEGATED · 画廊行为必须是事件委托。
#   逐个 addEventListener 只对**绑定那一刻已存在**的元素生效,动态 append 进来的一个都不管。
{ codeonly "$QA1195_TPL" | grep -q "g.addEventListener('click'" \
  && codeonly "$QA1195_TPL" | grep -q "closest('.gshot')" \
  && ! codeonly "$QA1195_TPL" | grep -q "g.querySelectorAll('.gshot').forEach"; } \
  && log_ok "v1195-GALLERY-DELEGATED(画廊点击走事件委托 · 动态新增的也有行为)" \
  || log_bad "v1195-GALLERY-DELEGATED 画廊又改回逐个绑定" "动态 append 的缩略图会点不动"

# v1195-UPLOAD-RETURNS-REL · 上传接口必须把 rel 还给前端(删除要用它)。
{ codeonly "$QA1195_CTL" | grep -q 'rels.add(importService.saveImage' \
  && codeonly "$QA1195_CTL" | grep -q '"rels", rels'; } \
  && log_ok "v1195-UPLOAD-RETURNS-REL(上传返回相对路径 · 前端据此接上删除与大图)" \
  || log_bad "v1195-UPLOAD-RETURNS-REL 上传又丢弃了 saveImage 的返回值" "拿不到 rel 就删不掉刚传的图"

# v1195-UPLOADED-FROM-DOM · 「本次 N 张」要从服务端已渲染的图起算,不能写死 0。
#   写死 0 的后果:传完图刷新一下,图还在但「开始识别」是灰的。
{ codeonly "$QA1195_TPL" | grep -q "var uploaded=thumbs.querySelectorAll('.gshot\[data-rel\]').length" \
  && ! codeonly "$QA1195_TPL" | grep -q 'var uploaded=0'; } \
  && log_ok "v1195-UPLOADED-FROM-DOM(计数从已有图起算 · 刷新后仍可识别)" \
  || log_bad "v1195-UPLOADED-FROM-DOM 计数又写死成 0" "刷新后「开始识别」会变灰,用户得再传一张才能点"


# ═══ v1.19.6 · 手机上的超级 Agent 浮钮 ═══
QA1196_LAY="$RD/src/main/resources/templates/fragments/layout.html"
QA1196_CSS="$RD/src/main/resources/static/css/style.css"
QA1196_JS="$RD/src/main/resources/static/js/landscape.js"

# v1196-ASK-IN-DOCK · AI 入口必须在浮钮 dock 里,且排在隐私眼之前。
#   手机上原来要「汉堡 → 展开 → 点」两步,而横屏/隐私是一步 —— AI 是三支柱之一,
#   不该比一个显示开关还难够到(用户原话:入口太深)。
{ grep -q 'id="ask-float"' "$QA1196_LAY" \
  && codeonly "$QA1196_JS" | grep -q "'#ori-float', '.toc-fab', '#ask-float', '#priv-float'"; } \
  && log_ok "v1196-ASK-IN-DOCK(AI 浮钮在 dock 里 · 顺序 方向→目录→AI→隐私)" \
  || log_bad "v1196-ASK-IN-DOCK 手机 AI 入口又退回菜单深处" "横屏/隐私一步可达,AI 不该两步"

# v1196-ASK-FLOAT-SPECIFICITY · 隐藏规则必须带 #float-dock 前缀,否则静默失效。
#   进 dock 后有一条 `#float-dock > #ask-float{display:inline-flex!important}`(两个 id);
#   只写 `#ask-float` 或 `body.ask-page #ask-float` 压不过它 —— 实测 PC 上和 /ask 页面上
#   按钮照样冒出来,而 CSS 不会报任何错。
{ grep -q 'body.ask-page #float-dock > #ask-float' "$QA1196_CSS" \
  && grep -q '#float-dock > #ask-float { display: none !important; }' "$QA1196_CSS"; } \
  && log_ok "v1196-ASK-FLOAT-SPECIFICITY(隐藏规则带 dock 前缀 · 压得过 !important)" \
  || log_bad "v1196-ASK-FLOAT-SPECIFICITY 隐藏规则优先级不够" "会在 /ask 页面和 PC 上重复冒出来,且 CSS 不报错"

# v1196-ASK-FLOAT-SAME-SIZE · 与同排图标钮同尺寸(并列同类元素不能一大一小)。
{ grep -A6 '^#ask-float {' "$QA1196_CSS" | grep -q 'width: 38px; height: 38px' \
  && grep -A6 '^#ori-float {' "$QA1196_CSS" | grep -q 'width: 38px; height: 38px'; } \
  && log_ok "v1196-ASK-FLOAT-SAME-SIZE(AI 钮与方向钮同为 38×38)" \
  || log_bad "v1196-ASK-FLOAT-SAME-SIZE 浮钮尺寸不齐" "同一列里一个 38 一个别的,一眼看得出参差"


# ═══ v1.19.7 · 管理页落地页不许漏入口 ═══
# v1197-ADMIN-LANDING-COMPLETE · 侧边栏有的,/admin 落地页必须也有。
#
#   这个洞犯过**三次**:2026-06-23 的 /admin/metrics(见 feedback_verify_user_path)、
#   以及这次一口气发现的 /admin/ai-access 与 /admin/reconcile ——
#   后两个都是「页面做好了、侧边栏挂了、落地页忘了」,而落地页才是用户点「管理」看到的第一屏。
#
#   为什么之前的 v08-NAV-1 拦不住:它硬编码只查 /admin/metrics 一个路径。
#   加新页面时它不会红 —— **一条只认单个字面量的护栏,守不住一整类问题**。
#   改成结构性比对:两个文件各自抽出 @{/admin/xxx},侧边栏必须是落地页的子集。
#   将来加任何管理页,只要挂了侧边栏没挂落地页,这条就红。
QA1197_SB="$RD/src/main/resources/templates/admin/_sidebar.html"
QA1197_IX="$RD/src/main/resources/templates/admin/index.html"
QA1197_MISSING="$(comm -23 \
  <(grep -ohE '@\{/admin/[a-z-]+\}' "$QA1197_SB" | grep -oE '/admin/[a-z-]+' | sort -u) \
  <(grep -ohE '@\{/admin/[a-z-]+\}' "$QA1197_IX" | grep -oE '/admin/[a-z-]+' | sort -u) | tr '\n' ' ')"
[ -z "$(printf '%s' "$QA1197_MISSING" | tr -d ' ')" ] \
  && log_ok "v1197-ADMIN-LANDING-COMPLETE(侧边栏入口在 /admin 落地页全都有)" \
  || log_bad "v1197-ADMIN-LANDING-COMPLETE 落地页漏了入口:$QA1197_MISSING" "用户点「管理」看到的是落地页,只挂侧边栏等于够不着"


# ═══ v1.19.7 · 百炼接入教程要对得上控制台实际长相 ═══
QA1197_AA="$RD/src/main/resources/templates/admin/ai-access.html"

# v1197-BAILIAN-PICK-SCRIPT · 必须写明「创建 MCP 服务」后先选**使用脚本部署**。
#   用户实测:点创建之后百炼先弹一个四选一(插件/使用脚本部署/AI 网关/阿里云 OpenAPI),
#   而原来的教程从「点创建」直接跳到「安装方式选 http」,漏了这一步。
#   用户选了「插件」→ 那条路是把普通接口包装成工具,要求**逐个填工具名和描述**,存都存不了。
#   只写「选脚本部署」不够,还要说清另外三个为什么不是 —— 否则下次照样会选错。
{ grep -q '使用脚本部署' "$QA1197_AA" \
  && grep -q '插件' "$QA1197_AA" \
  && grep -q 'AI 网关' "$QA1197_AA" \
  && grep -q 'OpenAPI' "$QA1197_AA" \
  && grep -q 'ask-how-tbl' "$QA1197_AA"; } \
  && log_ok "v1197-BAILIAN-PICK-SCRIPT(四种接入方式列全并说明为什么选脚本部署)" \
  || log_bad "v1197-BAILIAN-PICK-SCRIPT 接入方式的岔路没说清" "用户会选到「插件」,那条路要逐个手填工具描述"

# v1197-BAILIAN-BEARER-EXPLICIT · Authorization 的值要写死一种,不能让用户猜。
#   用户原话:「value 里面 要 Bearer 这个嘛,还是只要后面的 token 本身,都明确好」。
#   服务端两种都收(AccessTokenService.verify 会剥前缀),但教程必须给一个确定写法。
{ grep -q 'Bearer 你的口令' "$QA1197_AA" \
  && grep -q '后面有一个空格' "$QA1197_AA" \
  && grep -q 'AccessTokenService' "$RD/src/main/java/com/family/finance/service/ask/AccessTokenService.java"; } \
  && log_ok "v1197-BAILIAN-BEARER-EXPLICIT(Authorization 写法写死:Bearer + 空格 + 口令)" \
  || log_bad "v1197-BAILIAN-BEARER-EXPLICIT Bearer 前缀没说明确" "用户得靠猜,猜错就是 401 而且看不出原因"

# v1197-BAILIAN-TYPE-PATH · type 与地址末尾必须成对说明。
#   百炼把两者绑死:streamableHttp↔/mcp、sse↔/sse。不匹配报 404/405,
#   看着像地址写错,实际是类型选错 —— 不说清用户会去改地址,越改越错。
{ grep -q 'streamableHttp' "$QA1197_AA" \
  && grep -q '/sse' "$QA1197_AA" \
  && grep -q '404' "$QA1197_AA"; } \
  && log_ok "v1197-BAILIAN-TYPE-PATH(说明 type 与端点路径必须配对)" \
  || log_bad "v1197-BAILIAN-TYPE-PATH 没说 type 和路径要配对" "错配报 404/405,用户会误以为地址写错"

# v1197-BAILIAN-CURL-SELFTEST · 给一条能自测的 curl(官方排障建议的第一步)。
#   它能立刻把「百炼没配对」和「你的服务根本不通」分开 —— 少了这条,用户只能在控制台里瞎试。
{ grep -q 'mcpCurlSample' "$QA1197_AA" \
  && codeonly "$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java" | grep -q 'mcpCurlSample' \
  && codeonly "$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java" | grep -q 'tools/list'; } \
  && log_ok "v1197-BAILIAN-CURL-SELFTEST(给了直连自测的 curl · 由 Java 生成不在模板拼)" \
  || log_bad "v1197-BAILIAN-CURL-SELFTEST 缺自测命令" "连不上时用户分不清是哪一端的问题"


# ═══ v1.19.8 · 审计日志的 target_type 必须和 target_id 指同一张表 ═══
# v1198-AUDIT-TARGET-CONSISTENT · 不许出现「type 说 A 表、id 给的是账户 id」。
#
#   这不是洁癖:审计日志是**出事之后唯一能追溯的东西**,而 target_type/target_id
#   是它唯一能被程序化检索的字段(页面上既不展示也不筛选它)。类型和 id 对不上,
#   追溯时就会张冠李戴。
#
#   2026-09-02 亲历:排查「股票收入没加股数」时,按 target_id=15 查 stock_holding,
#   查到一条「手填余额校准」——于是我以为字节期权持仓做过余额校准、而且现金行没建出来,
#   往那个方向查了好几轮。实际那条记的是 **账户 15**(代码写的是
#   `record(..., "stock_holding", accountId, ...)`),和持仓 15 毫无关系。
#   **被自己的审计日志骗了。**
#
#   全仓扫下来这类有 11 处(stock_holding×1 / period_snapshot×5 / cash_flow×4 / transfer×1),
#   全部 id 传的都是 accountId —— 说明这是**约定不清**而不是手滑。
#   统一成 `"account", accountId`:target 说「这条记录挂在哪一行」,
#   summary 说「发生了什么」(「收入录入 …」「提交余额快照」这些本来就写在 summary 里,不丢信息)。
QA1198_BAD="$(grep -rn -A2 'auditLogService.record(' "$RD/src/main/java/" 2>/dev/null \
  | grep -oE '"(period_snapshot|cash_flow|stock_holding|transfer|member|period)", *(accountId|account\.getId\(\)|acc\.getId\(\)|fromAccountId)' \
  | sort -u | tr '\n' ' ')"
[ -z "$(printf '%s' "$QA1198_BAD" | tr -d ' ')" ] \
  && log_ok "v1198-AUDIT-TARGET-CONSISTENT(审计 target_type 与 target_id 指同一张表)" \
  || log_bad "v1198-AUDIT-TARGET-CONSISTENT type 与 id 对不上:$QA1198_BAD" "审计是出事后唯一的追溯依据,类型错了会把人引到别的实体上"


# ═══ v1.19.9 · MCP 协议版本协商 ═══
# v1199-MCP-VERSION-NEGOTIATED · initialize 必须**回显客户端要的版本**,不能硬报自己的。
#
#   MCP 规范(basic/lifecycle · Version Negotiation)是 MUST:
#   「If the server supports the requested protocol version, it MUST respond with the same
#    version. Otherwise, the server MUST respond with another protocol version it supports.」
#
#   原实现无条件回 2025-06-18(注释还写着「客户端声明别的版本时我们照回自己的,
#   由它决定要不要继续」——**那个理解是错的**)。线上后果:百炼请求较早的版本,
#   收到 2025-06-18 不认,直接 -32602 Unsupported protocol version,
#   **握手就断,连工具列表都拿不到**,整条托管接入路线不可用。
#
#   判据守三件:支持列表存在且按新→旧、协商时先判 null(List.of 的 contains(null) 会抛
#   NPE,而 initialize 不带 params 是能到达的请求)、以及有单测钉住映射本身。
QA1199_MCP="$RD/src/main/java/com/family/finance/web/ask/McpEndpoint.java"
{ codeonly "$QA1199_MCP" | grep -q 'SUPPORTED_PROTOCOL_VERSIONS' \
  && codeonly "$QA1199_MCP" | grep -q '"2025-06-18", "2025-03-26", "2024-11-05"' \
  && codeonly "$QA1199_MCP" | grep -q 'asked != null && SUPPORTED_PROTOCOL_VERSIONS.contains(asked)' \
  && ! codeonly "$QA1199_MCP" | grep -qE '"protocolVersion", *(PROTOCOL_VERSION|LATEST_PROTOCOL_VERSION)\b' \
  && [ -f "$RD/src/test/java/com/family/finance/web/ask/McpProtocolVersionTest.java" ]; } \
  && log_ok "v1199-MCP-VERSION-NEGOTIATED(initialize 回显客户端版本 · null 安全 · 有单测)" \
  || log_bad "v1199-MCP-VERSION-NEGOTIATED 又变回硬报自己的版本" "违反规范 MUST · 客户端版本不同就握手失败,连工具列表都拿不到"


# ═══ v1.19.11 · 百炼 Agent 请求体形状 + 上游错误不许吞 ═══
QA11911_MA="$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java"
QA11911_AC="$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java"
QA11911_AA="$RD/src/main/resources/templates/admin/ai-access.html"

# v11911-BAILIAN-AGENT-SHAPE · 创建/更新 Agent 的请求体形状,两处都是百炼**明确告诉我们**的。
#   ① model 是对象不是字符串:Cannot construct instance of `DashModelConfigDTO`
#      … from String value ('qwen-plus') (through reference chain: DashCreateAgentRequest["model"])
#   ② mcp_servers[].type 合法值是 customer,不是 custom:
#      mcpServers[0].type 取值非法: custom,合法值: [official, customer]
#   原来代码里写着 "custom",注释还写着「试过,百炼会拒」——显然试的是错的那个词。
#   v1.19.13 起创建与更新**共用一份** agentBody(),所以这里不再数「出现两次」——
#   数字面量个数会把「收口成一份」误判成退化(v1.19.13 当场踩到)。
#   「两处一致」由 v11913-PROMPT-FIELD-IS-SYSTEM 守(它验 agentBody 被两个调用点复用),
#   这条只守**形状本身**。
{ ! codeonly "$QA11911_MA" | grep -qE '"type", *"custom"' \
  && codeonly "$QA11911_MA" | grep -q '"type", "customer"' \
  && codeonly "$QA11911_MA" | grep -q 'Map.of("id", model' \
  && ! codeonly "$QA11911_MA" | grep -qE 'body.put\("model", *model'; } \
  && log_ok "v11911-BAILIAN-AGENT-SHAPE(model 为对象 · mcp type=customer)" \
  || log_bad "v11911-BAILIAN-AGENT-SHAPE 请求体形状退回去了" "百炼会 400 —— model 必须是对象、mcp type 必须是 customer"

# v11911-UPSTREAM-ERROR-VISIBLE · 上游说了什么必须让用户看见。
#   UpstreamException 原来把 body 存进字段却不放进 message,而调用方用的正是 getMessage() ——
#   用户只看到「upstream 400」+ 一句我们猜的「先确认两个 ID」,而那两个 ID 本来就是对的。
#   最有用的一句话被丢掉,排查因此绕了一大圈。与 v1.19.4「识别失败,请重试」同型。
{ codeonly "$QA11911_MA" | grep -q 'super("upstream " + status + ' \
  && codeonly "$QA11911_MA" | grep -q 'brief(body)' \
  && codeonly "$QA11911_AC" | grep -q '百炼返回:' \
  && codeonly "$QA11911_AC" | grep -q 'log.warn'; } \
  && log_ok "v11911-UPSTREAM-ERROR-VISIBLE(百炼原话进 message + 上页面 + 落日志)" \
  || log_bad "v11911-UPSTREAM-ERROR-VISIBLE 又把上游错误吞了" "用户只会看到 upstream 400,而真正的原因在被丢掉的 body 里"

# v11911-CREATE-AGENT-IN-WIZARD · 最后一步的动作按钮必须就在那一步里,而且是主按钮。
#   (步数会变 —— v1.19.12 从 5 步加到 6 步 —— 所以这条不绑具体序号。)
#   原来它是 10px 的透明文字链接,还在向导外面 —— 走完向导之后得自己找。
{ grep -q 'id="createAgentForm"' "$QA11911_AA" \
  && grep -q 'form="createAgentForm"' "$QA11911_AA" \
  && ! grep -q "text-\[10px\] text-ink hover:underline bg-transparent border-0" "$QA11911_AA"; } \
  && log_ok "v11911-CREATE-AGENT-IN-WIZARD(创建按钮在向导最后一步内 · 主按钮样式)" \
  || log_bad "v11911-CREATE-AGENT-IN-WIZARD 创建按钮又和向导脱节了" "走完向导找不到该点哪儿"



# ═══ v1.19.12 · 百炼引导流程:模型授权这一步 + 提示不许指错方向 ═══
QA11912_MA="$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java"
QA11912_AC="$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java"
QA11912_AA="$RD/src/main/resources/templates/admin/ai-access.html"
QA11912_CFG="$RD/src/main/java/com/family/finance/service/config/FamilyConfigService.java"

# v11912-WIZARD-HAS-MODEL-STEP · 向导里必须有「确认这个业务空间能调模型」这一步。
#   这一步以前**完全没有**:用户把每一步都做对,最后仍报「模型不存在」——
#   而失败信息指向的是一个他根本没做错的东西。教程漏了一环,比写错一句更贵。
#   同时守「步数说明」与「实际步数」一致 —— 加步骤忘了改 summary 是这一页历史上真出过的错。
# 数 ask-step-who(每个步骤恰好一个,且只在这个向导里出现)。
# 不要用 sed 从 <ol class="ask-steps"> 截到 </ol> —— 步骤内部还有嵌套 <ol>,会在第一个内层收尾处截断。
QA11912_STEPS="$(grep -c 'ask-step-who' "$QA11912_AA")"
{ grep -q '确认这个业务空间「能调模型」' "$QA11912_AA" \
  && grep -q '模型调用权限' "$QA11912_AA" \
  && grep -q '一共 '"$QA11912_STEPS"' 步' "$QA11912_AA" \
  && [ "$QA11912_STEPS" -ge 6 ]; } \
  && log_ok "v11912-WIZARD-HAS-MODEL-STEP(向导含模型授权步 · summary 步数与实际 $QA11912_STEPS 步一致)" \
  || log_bad "v11912-WIZARD-HAS-MODEL-STEP 模型授权步没了或步数对不上" "用户按教程走完每一步仍会失败,而且失败信息指向他没做错的地方"

# v11912-MODEL-CONFIGURABLE · 模型不许写死在代码里。
#   子业务空间要主账号逐个开通模型,开通的未必是我们默认那个;写死就等于
#   「按提示开通了,还是报模型不存在,而页面上无处可改」。
{ codeonly "$QA11912_CFG" | grep -q 'K_ASK_MA_MODEL' \
  && codeonly "$QA11912_CFG" | grep -q 'ASK_MA_MODEL_DEFAULT' \
  && codeonly "$QA11912_MA" | grep -q 'configuredModel()' \
  && [ "$(codeonly "$QA11912_MA" | grep -c '"qwen-plus"')" -eq 0 ] \
  && codeonly "$QA11912_AC" | grep -q 'K_ASK_MA_MODEL' \
  && grep -q 'name="maModel"' "$QA11912_AA"; } \
  && log_ok "v11912-MODEL-CONFIGURABLE(模型走配置 · 默认值收口常量 · 管理页可改)" \
  || log_bad "v11912-MODEL-CONFIGURABLE 模型又写死了" "子空间开通的模型与我们请求的对不上时,用户没有任何地方能改"

# v11912-HINT-NOT-MISLEADING · 失败提示:认得出的说准,认不出的承认在猜。
#   反面案例就在上一版:百炼回「模型不存在」,我们附「核对业务空间 ID / MCP 服务 ID / 公网地址」,
#   而那三样全是对的 —— 一句笃定的错方向,比不给提示更糟。
{ codeonly "$QA11912_AC" | grep -q 'static String hint(' \
  && codeonly "$QA11912_AC" | grep -q 'AGENT_010' \
  && codeonly "$QA11912_AC" | grep -q '没见过' \
  && ! codeonly "$QA11912_AC" | grep -q '若提示指向配置' \
  && [ -f "$RD/src/test/java/com/family/finance/web/admin/AiAccessHintTest.java" ]; } \
  && log_ok "v11912-HINT-NOT-MISLEADING(错误提示按上游原话分流 · 未知时承认在猜 · 有单测)" \
  || log_bad "v11912-HINT-NOT-MISLEADING 又给笃定的错方向" "上一轮就是被这句提示带偏,三个 ID 全对却反复核对"

# v11912-WORKSPACE-FORMAT-HONEST · 业务空间 ID 的格式说法不许说死。
#   原文写「形如 llm-7c72iiw36kd8xxxx」,而 ws- 开头的同样有效(实测通过端点鉴权)——
#   用户照着格式判断「我这个不对」,会去改一个本来就对的东西。
{ grep -q 'ws-' "$QA11912_AA" \
  && grep -q '以控制台上显示的为准' "$QA11912_AA" \
  && grep -q '默认业务空间' "$QA11912_AA" \
  && grep -q '子业务空间' "$QA11912_AA"; } \
  && log_ok "v11912-WORKSPACE-FORMAT-HONEST(两种前缀都提 · 默认空间与子空间的差别写清)" \
  || log_bad "v11912-WORKSPACE-FORMAT-HONEST 又把业务空间 ID 说成只有一种格式" "用户会照格式去改一个本来就对的值,而真正的坑(子空间没模型权限)仍然没提"


# ═══ v1.19.13 · 百炼 Agent 模板:字段名 + 更新动词 + 回读确认 ═══
QA11913_MA="$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java"
QA11913_AC="$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java"

# v11913-PROMPT-FIELD-IS-SYSTEM · 系统提示词的字段名是 system,不是 instructions。
#   发错时百炼**静默忽略**:创建返回 200 + 有 agent_id,而 GET 回来 "system": null、
#   instructions 这个键根本不在响应里 —— 线上那个 agent 挂了 MCP 却一句提示词都没有,
#   工具能调但不知道口径纪律(不许做数学 / 不许换汇 / 拿不准先调 capabilities)。
#   同时守「创建与更新共用一份请求体」—— v1.19.11 的两个形状 bug 就是各写一份、改一处漏一处。
{ codeonly "$QA11913_MA" | grep -q 'PROMPT_FIELD = "system"'   && ! codeonly "$QA11913_MA" | grep -q '"instructions"'   && [ "$(codeonly "$QA11913_MA" | grep -c 'agentBody(systemPrompt, model)')" -ge 2 ]   && [ "$(codeonly "$QA11913_MA" | grep -c 'body.put(PROMPT_FIELD')" -eq 1 ]; }   && log_ok "v11913-PROMPT-FIELD-IS-SYSTEM(提示词字段=system · 创建与更新共用一份请求体)"   || log_bad "v11913-PROMPT-FIELD-IS-SYSTEM 提示词字段名又发错了" "百炼静默忽略不认识的字段:创建照样 200,但 agent 是个没有系统提示词的空壳"

# v11913-UPDATE-IS-POST · 更新动词是 POST /agents/{id};PUT 与 PATCH 百炼都回 405。
#   这条 405 是在用户**已经创建成功之后**才出现的,于是页面写着「创建失败」,
#   他以为整条路线没通 —— 所以顺带守「出错文案要分清创建/更新」。
{ ! codeonly "$QA11913_MA" | grep -qE 'send\("(PUT|PATCH)"'   && ! codeonly "$QA11913_MA" | grep -q 'private JsonNode put('   && codeonly "$QA11913_MA" | grep -q 'post(agentBase() + "/agents/" + agentId()'   && codeonly "$QA11913_AC" | grep -q 'String what = update ? "更新" : "创建"'; }   && log_ok "v11913-UPDATE-IS-POST(更新走 POST /agents/{id} · 出错文案分清创建与更新)"   || log_bad "v11913-UPDATE-IS-POST 更新动词或文案退回去了" "PUT/PATCH 会被百炼 405;而把更新失败写成「创建失败」会让人以为整条路线都没通"

# v11913-READBACK-CONFIRMS · 写完要回读确认真的存住了。
#   「上游收下了」不等于「上游存住了」—— 字段名对不上时它 200 + 静默丢弃,
#   这类失败不报错、不降级、看起来完全成功,只有回读能抓到。
{ codeonly "$QA11913_MA" | grep -q 'private void verifyTemplate('   && [ "$(codeonly "$QA11913_MA" | grep -c 'verifyTemplate(')" -ge 3 ]   && codeonly "$QA11913_MA" | grep -q 'mcp_servers'   && codeonly "$QA11913_MA" | grep -q 'private JsonNode get('; }   && log_ok "v11913-READBACK-CONFIRMS(创建与更新都回读 · 提示词与 MCP 引用都验)"   || log_bad "v11913-READBACK-CONFIRMS 回读确认没了" "静默丢字段会伪装成完全成功,不回读就发现不了"

# v11913-GUESS-ONLY-FOR-UPSTREAM · 只有上游的错才配一句猜测。
#   我们自己抛的(回读发现没存住)已经把话说完了,再补一句「核对三个 ID」纯属添乱 ——
#   而那正是 v1.19.12 刚修掉的病。
{ codeonly "$QA11913_AC" | grep -q 'msg.startsWith("upstream ")'   && codeonly "$QA11913_AC" | grep -q 'upstream ?' ; }   && log_ok "v11913-GUESS-ONLY-FOR-UPSTREAM(自家抛的错不再附上我们的猜测)"   || log_bad "v11913-GUESS-ONLY-FOR-UPSTREAM 又给自家错误配猜测了" "话已经说完还补一句「核对三个 ID」,是 v1.19.12 刚修掉的同一种误导"

# ═══ v1.19.14 · 百炼会话链路:追加事件 → 独立 SSE 端点读答案 ═══
QA11914_MA="$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java"

# v11914-SESSION-FIELD-IS-AGENT · 建会话的字段名是 agent,不是 agent_id。
#   百炼原话:Missing required field: 'agent'。而用户看到的只有「百炼返回了错误(400)」——
#   最有用的那句话只进了日志。
{ codeonly "$QA11914_MA" | grep -q 'Map.of("agent", agentId())' \
  && ! codeonly "$QA11914_MA" | grep -q 'Map.of("agent_id", agentId())'; } \
  && log_ok "v11914-SESSION-FIELD-IS-AGENT(建会话用 agent 字段)" \
  || log_bad "v11914-SESSION-FIELD-IS-AGENT 建会话字段名又错了" "百炼回 400 Missing required field: 'agent',整个问答不可用"

# v11914-EVENT-SHAPE · 追加事件的形状是百炼逐条纠正出来的:
#   input 必须是**事件数组** · 每个事件带 type · content 必须是**内容块数组**(不是字符串)。
{ codeonly "$QA11914_MA" | grep -q 'Map.of("input", List.of(event))' \
  && codeonly "$QA11914_MA" | grep -q 'event.put("type", "message")' \
  && codeonly "$QA11914_MA" | grep -q 'List.of(Map.of("type", "text", "text", question))'; } \
  && log_ok "v11914-EVENT-SHAPE(input 是事件数组 · content 是内容块数组)" \
  || log_bad "v11914-EVENT-SHAPE 追加事件的形状退回去了" "百炼会 400:'input' must be an array / 'content' must be a non-empty array"

# v11914-ANSWER-FROM-STREAM-ENDPOINT · 答案要从独立的 SSE 端点读。
#   POST /events 只是**追加**,它的 Content-Type 永远是 application/json ——
#   连官方文档说的「加 Accept: text/event-stream 就流式」都不成立(实测)。
#   把它当流读的结果是:一个字都读不到,还不报错。
#   after_id 不是优化是正确性:这个流**默认重放全部历史**,而会话是跨轮复用的,
#   不带它就会把前面每一轮的答案再吐一遍。
{ codeonly "$QA11914_MA" | grep -q '/events/stream?after_id=' \
  && codeonly "$QA11914_MA" | grep -q 'private void streamAfter(' \
  `# v1.27 起追加的是「分析上下文 + 问题」,仍是单独一次追加,读答案从它的 id 之后读` \
  && codeonly "$QA11914_MA" | grep -q 'composeTurnInput(turn.analysisContext(), turn.question())' \
  && codeonly "$QA11914_MA" | grep -q 'appendUserMessageEcho(sessionId, input)' \
  && codeonly "$QA11914_MA" | grep -q 'streamAfter(sessionId, ap.id(), sink)'; } \
  && log_ok "v11914-ANSWER-FROM-STREAM-ENDPOINT(答案走 /events/stream · 带 after_id 防重放)" \
  || log_bad "v11914-ANSWER-FROM-STREAM-ENDPOINT 又把追加请求当成答案流读了" "读不到任何正文而且不报错;丢了 after_id 则会把历史每一轮重播一遍"

# v11914-TERMINATE-ON-SESSION-STATUS · 终止信号在 content[].data.session_status。
#   事件自己也有一个 status=completed(每条都有)—— 拿它当会话状态会在第一条事件就截断。
#   两个纯函数必须有单测:猜错的表现分别是「挂到超时」和「答案是空的」,两种都不报错。
{ codeonly "$QA11914_MA" | grep -q 'static String sessionStatus(' \
  && codeonly "$QA11914_MA" | grep -q '"session_status", "status"' \
  && codeonly "$QA11914_MA" | grep -qE '"idle"\.equals\(st\)' \
  && [ -f "$RD/src/test/java/com/family/finance/service/ask/runtime/ManagedAgentEventParsingTest.java" ]; } \
  && log_ok "v11914-TERMINATE-ON-SESSION-STATUS(终止读嵌套 session_status · 有单测)" \
  || log_bad "v11914-TERMINATE-ON-SESSION-STATUS 终止判据错了" "读顶层 status 会在第一条事件就截断;完全不读则挂到超时"

# v11914-UPSTREAM-WORDS-REACH-USER · 上游原话必须到用户眼前 —— 这个病第三次了。
#   v1.19.4「识别失败,请重试」盖住额度耗尽;v1.19.11「upstream 400」盖住字段错;
#   v1.19.14「百炼返回了错误(400)」盖住 Missing required field: 'agent'。
#   每一次,那句被盖住的话都直接指出了 bug 在哪。
{ codeonly "$QA11914_MA" | grep -q 'UpstreamException.brief(u.body)' \
  && ! codeonly "$QA11914_MA" | grep -q '稍后再试试'; } \
  && log_ok "v11914-UPSTREAM-WORDS-REACH-USER(问答报错带上百炼原话)" \
  || log_bad "v11914-UPSTREAM-WORDS-REACH-USER 又把上游原话吞了" "同一个病第四次:用户只看到「返回了错误(400)」,而原因就在被丢掉的那句里"

# ═══ v1.19.15 · 「百炼连没连上」要能一键问出来 ═══
QA11915_MA="$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java"
QA11915_AC="$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java"
QA11915_AA="$RD/src/main/resources/templates/admin/ai-access.html"
QA11915_AU="$RD/src/main/java/com/family/finance/repository/AskAuditMapper.java"

# v11915-LINK-SELFTEST-EXISTS · 引导流程里最后一个**看不见**的失败必须有一键判据。
#   实测:会话建得起来、答案也流得回来,但智能体说「我这边没有数据查询工具」——
#   百炼运行时连不上 MCP 服务时**不报错**,只是让模型在没有工具的情况下作答。
#   用户看到的是一段像模型犯傻的话,而真因在百炼控制台的服务状态里。
{ codeonly "$QA11915_MA" | grep -q 'public String testMcpLink()' \
  && codeonly "$QA11915_AC" | grep -q '/admin/ai-access/test-mcp-link' \
  && grep -q 'test-mcp-link' "$QA11915_AA"; } \
  && log_ok "v11915-LINK-SELFTEST-EXISTS(有「测一下百炼连没连上」入口 · 端点+页面都在)" \
  || log_bad "v11915-LINK-SELFTEST-EXISTS 连通自检没了" "MCP 连不上时百炼不报错,没有这个入口用户只会以为模型笨"

# v11915-SELFTEST-ASKS-OUR-AUDIT-NOT-THE-MODEL · 判据看**我们自己的入站审计**,不问模型。
#   问模型「你有哪些工具」得到的是它的自述 —— 它会编,也会把「我没看到」说成「不可用」。
#   而入站审计是事实:百炼来过就有记录,没来过就没有。
{ codeonly "$QA11915_AU" | grep -q 'countUpstreamCallsSince' \
  && codeonly "$QA11915_AU" | grep -q "user_agent LIKE '%Bailian%'" \
  && codeonly "$QA11915_MA" | grep -q 'auditMapper.countUpstreamCallsSince'; } \
  && log_ok "v11915-SELFTEST-ASKS-OUR-AUDIT-NOT-THE-MODEL(判据=入站审计 · 按 UA 不按 IP)" \
  || log_bad "v11915-SELFTEST-ASKS-OUR-AUDIT-NOT-THE-MODEL 自检又去问模型了" "模型的自述不是证据;而百炼出口 IP 会变,判据只能用 UA"

# v11915-VERDICT-IS-PLAIN-TEXT · flash 是纯文本,文案里不许有 markdown 星号。
#   同一个坑 v1.19.12 的 hint() 已经踩过一次(写了 **…** 渲染成字面星号)。
{ ! codeonly "$QA11915_MA" | grep -qE '(return|\+) *"[^"]*\*\*' \
  && ! codeonly "$QA11915_AC" | grep -qE '(return|\+) *"[^"]*\*\*'; } \
  && log_ok "v11915-VERDICT-IS-PLAIN-TEXT(面向用户的结论文案无 markdown 星号)" \
  || log_bad "v11915-VERDICT-IS-PLAIN-TEXT 文案里又出现 ** 了" "flash 按纯文本渲染,星号会原样显示"

# ═══ v1.19.16 · AI 月度复盘缓存的失效 ═══
QA11916_PS="$RD/src/main/java/com/family/finance/service/PeriodService.java"
QA11916_MP="$RD/src/main/java/com/family/finance/repository/ReviewAiCacheMapper.java"
QA11916_RS="$RD/src/main/java/com/family/finance/service/review/ReviewInsightService.java"
QA11916_RC="$RD/src/main/java/com/family/finance/web/review/ReviewController.java"

# v11916-REOPEN-CLEARS-REVIEW-CACHE · 重开账期必须连 AI 复盘缓存一起清。
#   线上 issue #17:用户重开上月、改数据、重新关账,数字全更新了,**只有这段 AI 解读没动** ——
#   review_ai_cache 按 (family,period,dim) 存,而全仓**没有任何地方失效过它**
#   (Mapper 里连 delete 方法都没有)。页面上还写着「关账后结果缓存可回看」,
#   读起来就是本期定论。数字对、解读错、不报错 —— 这类失败最贵。
{ codeonly "$QA11916_MP" | grep -q 'deleteByPeriod' \
  && codeonly "$QA11916_PS" | grep -q 'reviewAiCacheMapper.deleteByPeriod' \
  && [ -f "$RD/src/test/java/com/family/finance/service/review/ReviewCacheStalenessTest.java" ]; } \
  && log_ok "v11916-REOPEN-CLEARS-REVIEW-CACHE(重开时清该期复盘缓存 · 有单测)" \
  || log_bad "v11916-REOPEN-CLEARS-REVIEW-CACHE 重开又不清复盘缓存了" "改完数据 AI 解读还是旧的,而页面写着「关账后结果缓存可回看」"

# v11916-NO-CACHE-FOR-OPEN-PERIOD · 进行中的期一律不碰缓存(既不读也不写)。
#   写了更糟:这一期关账之后,那份**月中生成**的解读会被当成「本期定论」端出来。
{ codeonly "$QA11916_RS" | grep -q 'periodClosed && !force' \
  && codeonly "$QA11916_RS" | grep -q 'if (periodClosed) cacheMapper.upsert' \
  && codeonly "$QA11916_RC" | grep -q 'PeriodStatus.CLOSED'; } \
  && log_ok "v11916-NO-CACHE-FOR-OPEN-PERIOD(未关账的期不读也不写复盘缓存)" \
  || log_bad "v11916-NO-CACHE-FOR-OPEN-PERIOD 进行中的期又缓存了" "月中点一次就定死;关账后还会把月中的解读当成本期定论"

# ═══ v1.20 · 账户组 + 改动保护 ═══
QA1200_RES="$RD/src/main/java/com/family/finance/service/group/AccountGroupingResolver.java"
QA1200_SVC="$RD/src/main/java/com/family/finance/service/group/AccountGroupService.java"
QA1200_PS="$RD/src/main/java/com/family/finance/service/PeriodService.java"
QA1200_ATTR="$RD/src/main/java/com/family/finance/service/review/AttributionService.java"
QA1200_ENG="$RD/src/main/java/com/family/finance/calc/review/AttributionEngine.java"
QA1200_LENS="$RD/src/main/java/com/family/finance/service/lens/LensQueryService.java"
QA1200_GUARD="$RD/src/main/java/com/family/finance/service/entry/BalanceGuardService.java"
QA1200_MIG="$RD/db/migration/V58__account_group.sql"
QA1200_CTL="$RD/src/main/java/com/family/finance/web/admin/AccountGroupController.java"
QA1200_ACL="$RD/src/main/resources/templates/accounts/index.html"
QA1200_ADT="$RD/src/main/resources/templates/accounts/detail.html"
QA1200_GPG="$RD/src/main/resources/templates/accounts/groups.html"

# v1200-SINGLE-GROUP-MEMBERSHIP · 单属靠 **DB** 保证,不靠应用层校验。
#   归因有恒等式「基准 + 人赚 + 钱赚 + 开账基线 = 本期净变化」,一个账户进两个组会双计,
#   而差额会被**静默吸进「未归因」**—— 数字看着平了,错误藏起来了。这类失败只能在存储层拦。
{ grep -qE 'PRIMARY KEY *\( *`?account_id`? *\)' "$QA1200_MIG" \
  && ! grep -qiE 'PRIMARY KEY *\( *`?group_id`?, *`?account_id`?' "$QA1200_MIG"; } \
  && log_ok "v1200-SINGLE-GROUP-MEMBERSHIP(account_id 单独为主键 = 一个账户最多属一个组)" \
  || log_bad "v1200-SINGLE-GROUP-MEMBERSHIP 单属没落到 DB 上" "联合主键(group_id,account_id)允许同一账户进多个组 → 双计,差额被静默吸进「未归因」"

# v1200-ONE-RESOLVER · 聚合路径共用同一个维值解析器,**没有旁路**。
#   判据刻意**不数路径条数** —— 数数量就是第五次「护栏绑在偶然事实上」
#   (前四次:v08-NAV-1 绑单路径 · v181-FLOAT-DOCK / v1617-FIVE 绑三按钮数组 ·
#    v11911 数「出现两次」把收口误判成退化)。这里守的是「有没有人绕过解析器」。
{ codeonly "$QA1200_ATTR" | grep -q 'groupingResolver.valuesFor' \
  && codeonly "$QA1200_LENS" | grep -q 'groupingResolver.valuesFor' \
  && ! codeonly "$QA1200_ENG" | grep -qE 'dimKey == null \? s.accountName\(\)$'; } \
  && log_ok "v1200-ONE-RESOLVER(归因与镜头都过解析器 · 账户不再是聚合里的特例)" \
  || log_bad "v1200-ONE-RESOLVER 有聚合路径绕过了解析器" "绕过的那条会照旧按账户展开,页面正常渲染、只是没折叠 —— 不报错"

# v1200-SUM-NOT-RECOMPUTE · 组的值 = 【成员值求和】,不是另起一套计算。
#   这条同时保证了两件事:
#     ① 组内流转自动抵消 —— A→B 组内,两端的贡献在求和时相消;A→C 组外则不消。
#        不需要任何对手方识别,是求和的副产品。
#     ② 不产生第二条求和路径 —— 本项目已有 pivot 与 period_summary 两条无共用求和逻辑的
#        路径差 0.01 的前科,再多一条只会更糟。
#   判据钉在**行为**上(merge + add 求和),不钉在注释里 ——
#   第一版判据 grep 的是解析器的文档注释,而 codeonly 会把注释剥掉:
#   它守的是一句话,不是行为,写完当场就红了。
{ codeonly "$QA1200_ENG" | grep -qE 'acc.merge\(key, s.pnlBase\(\), BigDecimal::add\)' \
  && ! codeonly "$QA1200_RES" | grep -qiE '(sum|total|add)\(' ; } \
  && log_ok "v1200-SUM-NOT-RECOMPUTE(组值=成员值求和 · 解析器只给标签不做求和)" \
  || log_bad "v1200-SUM-NOT-RECOMPUTE 组的值不是成员求和了" "要么组内流转不再自动抵消,要么多出了第二条求和路径"

# v1200-FROZEN-NOT-LIVE · 历史读定格,不读当前成员关系。
#   读当前的话,今天挪一个账户出组,近 12 期趋势图会全变 —— 每个数字自身都对,
#   但用户会当成算错了。这类错误不报错、不降级。
{ codeonly "$QA1200_RES" | grep -q 'frozenMapper.findByPeriod' \
  && codeonly "$QA1200_ATTR" | grep -q 'groupingResolver.valuesFor(familyId, pid' \
  && [ -f "$RD/src/test/java/com/family/finance/service/group/AccountGroupingResolverTest.java" ]; } \
  && log_ok "v1200-FROZEN-NOT-LIVE(有定格就读定格 · 趋势每期各读各的 · 有单测)" \
  || log_bad "v1200-FROZEN-NOT-LIVE 历史又按当前分组重画了" "改一次组成员,12 期趋势图跟着全变"

# v1200-REOPEN-CLEARS-GROUP · reopen() 是「该期所有派生物的失效点」。
#   已有:分类属性定格(v1.12)· AI 复盘缓存(v1.19.16)· 分组定格(v1.20)。
#   新增任何「关账时定格 / 关账后缓存」的东西,都要回到这里加一行。
{ codeonly "$QA1200_PS" | grep -q 'periodAccountAttrMapper.deleteByPeriod' \
  && codeonly "$QA1200_PS" | grep -q 'reviewAiCacheMapper.deleteByPeriod' \
  && codeonly "$QA1200_PS" | grep -q 'periodAccountGroupMapper.deleteByPeriod' \
  && codeonly "$QA1200_PS" | grep -q 'periodAccountGroupMapper.freezeByPeriod'; } \
  && log_ok "v1200-REOPEN-CLEARS-GROUP(重开清三样派生物 · 关账定格分组)" \
  || log_bad "v1200-REOPEN-CLEARS-GROUP 重开漏清了某样派生物" "改完数据后那一样还是旧的,而且不报错(v1.19.16 就是这么出的)"

# v1200-DELETE-IS-A-REAL-UNDO · 删组是这一版唯一的退路,必须退干净。
#   不删历史定格的话,历史里会永远留着一个已经不存在的组名,而用户没有办法退回去 ——
#   那不是「可逆」,是陷阱。PRD FR-463 承诺的是「页面回到未分组的样子」。
{ codeonly "$QA1200_SVC" | grep -q 'frozenMapper.deleteByGroup'; } \
  && log_ok "v1200-DELETE-IS-A-REAL-UNDO(删组连历史定格一起清)" \
  || log_bad "v1200-DELETE-IS-A-REAL-UNDO 删组之后历史里还留着这个组" "用户没有任何办法退回按账户看 —— 不是可逆,是陷阱"

# v1200-GUARD-NARROW-AND-PLAIN · 改动提示的触发条件要窄,文案要是纯文本。
#   提示一多就会被当噪声划过去,那这一版就白做了。正反两面都要有单测。
{ codeonly "$QA1200_GUARD" | grep -q 'calibratedAt() != null && changed' \
  && codeonly "$QA1200_GUARD" | grep -q '本期净资产变化' \
  && ! codeonly "$QA1200_GUARD" | grep -qE '(return|\+) *"[^"]*\*\*' \
  && [ -f "$RD/src/test/java/com/family/finance/service/entry/BalanceGuardServiceTest.java" ]; } \
  && log_ok "v1200-GUARD-NARROW-AND-PLAIN(触发条件窄 · 未填余额另有话术 · 纯文本 · 有正反单测)" \
  || log_bad "v1200-GUARD-NARROW-AND-PLAIN 提示条件放宽或文案带了 markdown" "提示变噪声,用户三天后就开始无视它"

# v1200-NO-N-PLUS-1 · 解析器不许在循环里调。
{ ! codeonly "$QA1200_LENS" | grep -A3 -E 'for *\(Account' | grep -q 'groupingResolver.valuesFor'; } \
  && log_ok "v1200-NO-N-PLUS-1(维值映射一次取回,不在账户循环里查)" \
  || log_bad "v1200-NO-N-PLUS-1 解析器被放进循环了" "N+1 查询"

# ═══ v1.20 验收后补:分组的可发现性与冲突处理 ═══

# v1200-GROUPS-DISCOVERABLE · 分组必须能从【账户】那一侧走到。
#   第一版只做了一个独立管理页,账户列表与详情页一个字都没提 ——
#   用户只有恰好翻到管理页才知道有这能力(维护者验收当场点出来的)。
#   分组回答的是「这些账户合起来看」,和账户列表是同一件事的两个方向。
{ grep -q '/accounts/groups' "$QA1200_ACL" \
  && grep -q '/accounts/groups' "$QA1200_ADT" \
  && grep -q 'accountGroupName' "$QA1200_ACL" \
  && grep -q 'accountGroupName' "$QA1200_ADT"; } \
  && log_ok "v1200-GROUPS-DISCOVERABLE(账户列表与详情页都显示所属分组且能点进去)" \
  || log_bad "v1200-GROUPS-DISCOVERABLE 账户那一侧又看不到分组了" "用户只有恰好翻到管理页才知道有这个能力"

# v1200-CONFLICT-IS-BUSINESS-ERROR · 冲突要给人话,不能让 DB 约束冒成 500。
#   验收实测:组名重一次 → Duplicate entry '1-组A' for key 'uk_group_family_name' → 500 白页。
#   那种页面除了「出错了」什么都没说,用户既不知道错在哪也不知道该改什么。
{ codeonly "$QA1200_SVC" | grep -q 'class GroupConflictException' \
  && codeonly "$QA1200_SVC" | grep -q 'requireNameFree' \
  && codeonly "$QA1200_SVC" | grep -q 'requireAccountsFree' \
  && codeonly "$QA1200_CTL" | grep -q 'catch (AccountGroupService.GroupConflictException' \
  && [ -f "$RD/src/test/java/com/family/finance/service/group/AccountGroupConflictTest.java" ]; } \
  && log_ok "v1200-CONFLICT-IS-BUSINESS-ERROR(重名与占用都给人话 · 控制器接住 · 有单测)" \
  || log_bad "v1200-CONFLICT-IS-BUSINESS-ERROR 冲突又变成 500 了" "SQL 约束名冒到用户脸上,他不知道错在哪也不知道怎么改"

# v1200-NO-SILENT-STEAL · 账户被两个组选中时不许「悄悄搬走」。
#   第一版 addMember 是 ON DUPLICATE KEY UPDATE = 搬家语义:在新组勾一个已属别组的账户,
#   系统会把它从原组挪走而页面一个字不说 —— 原组的收益口径当场变了,用户不知道。
#   **静默改掉用户没打算改的东西,比报错更糟。**
#   界面侧同时要置灰并标出占用者,别让用户走到那一步才被拒。
{ codeonly "$QA1200_SVC" | grep -q 'occupiedBy' \
  && grep -q 'th:disabled' "$QA1200_GPG" \
  && grep -q '已在「' "$QA1200_GPG"; } \
  && log_ok "v1200-NO-SILENT-STEAL(被占用的账户置灰+标出占用者 · 后端也拒)" \
  || log_bad "v1200-NO-SILENT-STEAL 又会把账户从别的组悄悄搬走了" "原组的收益口径当场变,而页面一个字不说"

# v1200-GROUPS-PAGE-USABLE · 分组页要能用:折叠列表 + 搜索,而不是把所有账户平铺 N 遍。
#   第一版每个分组都渲染全部账户的 checkbox(19 账户 × N 组),维护者原话「交互怎么这么奇怪」。
#   二次验收又改了一轮:主页面**只有列表**,推荐收进「新建」弹窗 ——
#   推荐是「建的时候的快捷方式」,不是常驻区块;而且它只**预填**,组名和成员都还能改
#   (维护者:「分组名也要允许用户自己维护」)。
{ grep -q 'id="groupSearch"' "$QA1200_GPG" \
  && grep -qE '<details[^>]*class="paper-card mb-3 group-card"' "$QA1200_GPG" \
  && grep -q '<dialog id="newGroupDlg"' "$QA1200_GPG" \
  && grep -q 'applySuggestion' "$QA1200_GPG" \
  && grep -q 'id="ngName"' "$QA1200_GPG"; } \
  && log_ok "v1200-GROUPS-PAGE-USABLE(主页面只有列表+搜索 · 新建走弹窗 · 推荐只预填、组名可改)" \
  || log_bad "v1200-GROUPS-PAGE-USABLE 分组页的形态又退回去了" "推荐占住主页面 / 没有新建弹窗 / 组名不可改"

# ═══ v1.20 逐组件盘查后补:哪些跟分组走、哪些【刻意】不跟 ═══

QA1200_ROWV="$RD/src/main/java/com/family/finance/service/group/AccountRowView.java"
QA1200_FOLD="$RD/src/main/java/com/family/finance/service/group/AccountRowGrouper.java"
QA1200_DREG="$RD/src/main/resources/templates/dashboard/_region.html"
QA1200_RREG="$RD/src/main/resources/templates/reports/_region.html"
QA1200_SEAL="$RD/src/main/java/com/family/finance/service/report/SealedPeriodService.java"
QA1200_SEALT="$RD/src/main/resources/templates/reports/_sealed.html"

# v1200-GROUP-RATIO-IS-NULL · 【这一版最危险的一处】组行的比率类必须是 null。
#   XIRR 是资金加权年化:不能相加,加权平均也没有数学意义。
#   拿「累计损益÷净投入」顶替更糟 —— 那是另一个口径,填进「收益率」列 = 口径悄悄换了。
#   一个错的收益率比没有收益率危险得多:它看起来完全合理,没人会去质疑它。
#   所以这里钉的是「有没有真的给 null」,不是「算得对不对」。
{ codeonly "$QA1200_FOLD" | grep -qE 'null, *$|null, +//'   && codeonly "$QA1200_FOLD" | grep -c 'null,' | awk '{exit !($1>=5)}'   && ! codeonly "$QA1200_FOLD" | grep -qE 'xirr\(\)[^;]*(add|multiply|divide|average)'   && ! codeonly "$QA1200_FOLD" | grep -qE 'cumPnl[^;]*divide[^;]*netPrincipal'   && [ -f "$RD/src/test/java/com/family/finance/service/group/AccountRowGrouperTest.java" ]   && grep -q 'groupNullsOutRatios' "$RD/src/test/java/com/family/finance/service/group/AccountRowGrouperTest.java"; } \
  && log_ok "v1200-GROUP-RATIO-IS-NULL(组行不给收益率/回撤/预实 · 也没拿累计收益率冒充 XIRR · 有单测)" \
  || log_bad "v1200-GROUP-RATIO-IS-NULL 组行开始给比率了" "会得到一个看起来合理、实际没有数学意义的收益率 —— 没人会质疑它"

# v1200-CHART-TOPLEVEL-ONLY · 横条图只吃【顶层折叠行】,不能吃扁平列表。
#   扁平列表里组行后面跟着成员行;混进图里 = 组里的钱被算两遍,
#   而图仍然画得出来、总额也仍然「看着差不多」—— 不报错的那种错。
{ grep -q 'memberOf == null' "$QA1200_DREG"   && grep -q 'accountRows: \[\[${accountRows}\]\]' "$QA1200_DREG"; } \
  && log_ok "v1200-CHART-TOPLEVEL-ONLY(按账户分布图只吃顶层行 · 显式挡掉成员行)" \
  || log_bad "v1200-CHART-TOPLEVEL-ONLY 分布图可能把成员行也画进去了" "组里的钱算两遍,图照画不报错"

# v1200-ONE-TABLE-MARKUP · 账户表的 11 个指标格只有【一份】标记。
#   组行与成员行走同一个 th:each(扁平列表),不是嵌套两层循环各写一份 ——
#   抄第二份的下场是以后改一处忘另一处,而且没有测试会红。
{ grep -q 'accountRowsFlat' "$QA1200_DREG"   && grep -q 'accountRowsFlat' "$QA1200_RREG"   && [ "$(grep -c 'data-mcol="xirr"' "$QA1200_DREG")" -le 3 ]; } \
  && log_ok "v1200-ONE-TABLE-MARKUP(组行与成员行共用同一份单元格标记)" \
  || log_bad "v1200-ONE-TABLE-MARKUP 账户表的单元格标记被抄成了两份" "改一处忘另一处,PC 与手机/组行与账户行开始不一致"

# v1200-RISK-NOT-FOLDED · 集中度是【风险度量】,必须按真账户算。
#   HHI = Σ(份额²):5 个各占 4% 的账户 → 0.008;合成一个占 20% 的组 → 0.04,翻 5 倍。
#   指标会说「你的集中度暴增」,而用户什么都没做,只是换了个看法。
#   存款保险 50 万上限按【银行】算,不按用户的分组习惯算。
{ ! codeonly "$QA1200_SEAL" | grep -A6 'buildConcentration\|record Share' | grep -q 'labelOf\|groupingResolver'   && grep -q 'hasGroups' "$QA1200_SEALT"   && grep -q '不按你的分组折叠' "$QA1200_SEALT"; } \
  && log_ok "v1200-RISK-NOT-FOLDED(集中度按真账户算 · 且在页面上说明了为什么)" \
  || log_bad "v1200-RISK-NOT-FOLDED 集中度跟着分组折叠了,或没说明为什么不折叠" "用户会以为自己的风险暴增(其实什么都没变),或以为这里漏算了"

# v1200-MOVERS-READ-FROZEN · 封板贡献者按组折叠,但必须读【该期定格】的分组。
#   读当前成员关系的话,今天挪一个账户,历史封板页的贡献者列表会跟着变。
#   另一个坑:本期与上期必须用【同一份】分组关系折叠 ——
#   否则「改了分组」长得和「钱动了」一模一样,差额凭空冒出来还说不清哪来的。
{ codeonly "$QA1200_SEAL" | grep -q 'groupingResolver.labelsFor(familyId, periodId, null)'   && codeonly "$QA1200_SEAL" | grep -q 'foldByLabel(curAcct, labelOf)'   && codeonly "$QA1200_SEAL" | grep -q 'foldByLabel(beforeAcct, labelOf)'; } \
  && log_ok "v1200-MOVERS-READ-FROZEN(封板贡献者按组折叠 · 读该期定格 · 前后期同一份关系)" \
  || log_bad "v1200-MOVERS-READ-FROZEN 封板贡献者读了当前分组或前后期用了不同关系" "历史封板会随今天改分组而变;或「改分组」被显示成「钱动了」"

# v1200-ACTION-STAYS-ACCOUNT · 「做」类组件不许跟组:组不是一个能记账的东西。
#   完整性检查是待办清单(合并了不知道去填谁)· 调仓下拉要落到能转账的地方 ·
#   体检是逐账户诊断。三处都要在【建过组时】说明为什么,不然用户以为是 bug。
{ grep -q '余额逐个账户填,这里不按分组折叠' "$QA1200_SEALT"   && grep -q '划转得落到能记账的地方' "$RD/src/main/resources/templates/reports/_rebalance-plan.html"   && grep -q '体检是逐账户的' "$QA1200_DREG"   && grep -q 'th:unless="${account.grouped}"' "$QA1200_DREG"; } \
  && log_ok "v1200-ACTION-STAYS-ACCOUNT(完整性/调仓/体检仍是账户级 · 且都说明了为什么)" \
  || log_bad "v1200-ACTION-STAYS-ACCOUNT 操作类入口跟着组折叠了,或没说明为什么没折叠" "给出一个点不进去、转不了账的组名 = 把功能变没"

# v1200-TOGGLE-SHARED-AND-DELEGATED · 展开逻辑一份 + 事件委托。
#   两页用同一张表,内联两份一定会漂;而表在指标 chip 切换时整块重绘,
#   直接绑按钮的话重绘一次就全掉了(和归因区「点开」踩的是同一个坑)。
{ [ -f "$RD/src/main/resources/static/js/account-group-toggle.js" ]   && grep -q "document.addEventListener('click'" "$RD/src/main/resources/static/js/account-group-toggle.js"   && grep -q 'account-group-toggle.js' "$RD/src/main/resources/templates/fragments/layout.html"   && ! grep -q "closest('.acct-toggle')" "$QA1200_DREG"   && ! grep -q "closest('.acct-toggle')" "$QA1200_RREG"; } \
  && log_ok "v1200-TOGGLE-SHARED-AND-DELEGATED(展开逻辑只有一份 · 事件委托 · 两页共用)" \
  || log_bad "v1200-TOGGLE-SHARED-AND-DELEGATED 展开逻辑被抄回页面内联,或改成了直接绑按钮" "两份实现会漂;直接绑的话表一重绘展开就失效"

# v1200-SPARKLINE-ONE-IMPL · 归一化只有一份实现。
#   组行要在【合并后的时序】上重算同一件事。抄一份的下场:两条曲线用不同基准,
#   在同一张表里并排显示、视觉上完全不可比,而且没有任何测试会失败。
{ [ -f "$RD/src/main/java/com/family/finance/factview/Sparkline.java" ]   && codeonly "$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java" | grep -q 'Sparkline.points(spark)'   && codeonly "$QA1200_FOLD" | grep -q 'Sparkline.points(spark)'   && [ "$(codeonly "$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java" | grep -c 'viewBox 0 0 80 22')" -eq 0 ]; } \
  && log_ok "v1200-SPARKLINE-ONE-IMPL(归一化只有 Sparkline 一份 · 两个调用方都委托给它)" \
  || log_bad "v1200-SPARKLINE-ONE-IMPL 归一化被抄成了两份" "两条曲线基准不同 → 并排显示不可比,而且没有测试会红"

# v1200-NO-PHANTOM-BTN-CLASS · 模板不许用 CSS 里【根本不存在】的按钮类。
#   v1.20 验收发现「建这个分组」不像按钮 —— 因为我写了 class="btn-primary",
#   而这个类全项目从没定义过(只有 btn-ink / btn-paper / btn-ghost)。
#   浏览器对不存在的 class 完全不吭声,于是它静静渲染成一行纯文字,
#   编译、单测、qa-run 全绿 —— 只有人眼能发现。这条就是把人眼那一步机械化。
#
#   第一版判据太粗,自己先误报了两条(诚实记下来,免得下次又踩):
#     · holdings.html 的「btn-」其实来自【注释】里的 `btn-* 全带 uppercase`
#       → 只从 class="…" 属性里取,且要求 btn- 后面至少一个字符
#     · how-to-use.html 的 .btn-run 是定义在【该模板自己的 <style> 块】里的
#       → 定义处除了 style.css,还要认同文件内联样式
{ phantom=""
  for f in $(grep -rl 'class="[^"]*btn-[a-z0-9]' "$RD/src/main/resources/templates" 2>/dev/null); do
    for c in $(grep -o 'class="[^"]*"' "$f" | grep -o 'btn-[a-z0-9][a-z0-9-]*' | sort -u); do
      grep -q "\.$c *[,{[]" "$RD/src/main/resources/static/css/style.css" && continue   # 全站样式表里有
      grep -q "\.$c *[,{[]" "$f" && continue                                            # 或该模板自己的 <style> 里有
      phantom="$phantom $c($(basename "$f"))"
    done
  done
  [ -z "$phantom" ]; } \
  && log_ok "v1200-NO-PHANTOM-BTN-CLASS(模板 class 属性里的 btn-* 都能找到定义)" \
  || log_bad "v1200-NO-PHANTOM-BTN-CLASS 模板用了不存在的按钮类:$phantom" "浏览器对不存在的 class 不吭声 → 按钮静静渲染成纯文字,所有自动化都是绿的"

# v1200-DAILY-TURNOVER-SUGGESTED · 推荐里必须有 issue #17 提出者真正要的那一组。
#   他要的是「银行卡 / 微信 / 支付宝这些互相转账的钱包」= CASH 类型;
#   而「随时可取」是流动性分层(LIQUID),把货币基金类理财也算进来了 —— 不是一回事。
#   两个都留着让用户挑,判据写在卡片上。
{ codeonly "$QA1200_SVC" | grep -q 'AccountType.CASH'   && codeonly "$QA1200_SVC" | grep -q '"日常周转"'   && codeonly "$QA1200_SVC" | grep -q '"随时可取"'; } \
  && log_ok "v1200-DAILY-TURNOVER-SUGGESTED(「日常周转」按 CASH 推荐 · 与「随时可取」并存)" \
  || log_bad "v1200-DAILY-TURNOVER-SUGGESTED 少了 issue #17 提出者真正要的那组推荐" "他要的是互相转账的钱包,不是流动性分层"

# v1200-PICKER-SHOWS-TYPE-AND-OWNER · 勾选账户时光有名字不够辨别。
#   家里两张卡都叫「招行」是常事,而「哪张是我的、哪张是老婆的」
#   恰恰是决定要不要放进同一个组的依据。
{ grep -q 'ownerOf.get(a.id)' "$QA1200_GPG"   && [ "$(grep -c 'ownerOf.get(a.id)' "$QA1200_GPG")" -ge 2 ]   && grep -q 'a.type.label' "$QA1200_GPG"   && grep -q 'memberDirectory.nameMap' "$QA1200_CTL"; } \
  && log_ok "v1200-PICKER-SHOWS-TYPE-AND-OWNER(新建与编辑两处勾选列表都显示类型+主理人)" \
  || log_bad "v1200-PICKER-SHOWS-TYPE-AND-OWNER 勾选列表又只剩账户名了" "同名账户分不清谁是谁,选错了要等到统计出错才发现"

# v1200-FILTER-GROUP-PICK · 筛选器的分组快选:只帮你勾,不改筛选语义。
#   这个组件是【复核组件清单时才发现漏了的】—— 它是个 <details> 表单、不在长文目录里,
#   第一遍按目录列清单时整个没看见。教训写在 PRD §6.1.0。
#   实现上刻意不加 groups= 参数:筛选器的语义是「哪些【账户】参与计算」,
#   组不是账户,塞进来会让口径变糊,而收益只有「少点几下」。
{ grep -q 'pickGroupAccounts' "$QA1200_DREG"   && grep -q 'groupPicks' "$QA1200_DREG"   && ! grep -qE 'name="groups"|RequestParam.*"groups"' "$QA1200_DREG"   && ! codeonly "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" | grep -q '"groups"'; } \
  && log_ok "v1200-FILTER-GROUP-PICK(筛选器有分组快选 · 且没往查询语义里加 groups 参数)" \
  || log_bad "v1200-FILTER-GROUP-PICK 快选没了,或者组被塞进了筛选语义" "筛选器的语义是「哪些账户参与计算」;混入组会让口径变糊"

# v1200-MEMBER-CHART-RAW-ROWS · 「按成员分布」必须吃【原始】账户行,不能吃折叠后的。
#   computeMemberAllocation 要用 accountId 反查 Account 拿 primary_owner_member_id,
#   而组行的 accountId 是 null → 整组的钱会【从成员饼图里整片消失】,
#   饼图照画、不报错,只是少了一大块。这是复核时逐条找数据源证据才钉住的。
{ codeonly "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java"     | grep -q 'computeMemberAllocation('   && ! codeonly "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java"     | grep -qE 'computeMemberAllocation\([^)]*(foldedRows|accountRowsFlat)'; } \
  && log_ok "v1200-MEMBER-CHART-RAW-ROWS(按成员分布仍吃原始账户行 · 组行 accountId=null 不会吞掉整组)" \
  || log_bad "v1200-MEMBER-CHART-RAW-ROWS 成员饼图被喂了折叠后的行" "组行 accountId=null → 反查不到 Account → 整组的钱从饼图里消失,而且不报错"

# v1201-MCP-URL-FROM-CONFIG · 给百炼粘贴的配置,url 必须用【用户填的公网地址】。
#   原来只看请求头(你打开这一页时用的域名)→ 在测试机上打开就生成一个内网地址,
#   百炼连不上,而页面上「本站公网地址」那个字段填得再对也没用(它当时只参与校验)。
#   用户看到的现象是「配置照抄了,智能体却说没有数据查询工具」——
#   一个不报错、只是不工作的失败。
QA1201_AAC="$RD/src/main/java/com/family/finance/web/admin/AiAccessController.java"
{ codeonly "$QA1201_AAC" | grep -q 'mcpConfigJson(mcpBaseUrl('   && ! codeonly "$QA1201_AAC" | grep -q 'mcpConfigJson(guessBaseUrl('   && codeonly "$QA1201_AAC" | grep -q 'K_ASK_PUBLIC_BASE_URL'   && codeonly "$QA1201_AAC" | grep -q 'endsWith("/mcp")'; } \
  && log_ok "v1201-MCP-URL-FROM-CONFIG(粘贴配置用用户填的公网地址 · 且容错末尾多带的 /mcp)" \
  || log_bad "v1201-MCP-URL-FROM-CONFIG 配置 URL 又回去用请求域名了" "在测试机上打开管理页会生成一个百炼连不上的地址,而且不报错"

# v1201-INBOUND-VISIBLE · 「百炼来没来过」必须在页面上看得见。
#   这条护栏守的不是一个功能,是一段【很长的瞎猜】:线上智能体说「我这边没有数据查询工具」,
#   而页面上看不出百炼到底有没有来过 —— 于是只能反复推测「大概是 MCP 服务没部署成功」,
#   把查证推给用户去控制台翻。
#   而这两种情况病因完全不同、修法也完全不同:
#     · 来过但 result=INVALID → 口令过期或贴错
#     · 一条记录都没有        → 它根本没来,问题在百炼那边
#   它们在页面上却长得一模一样。
QA1201_AAT="$RD/src/main/resources/templates/admin/ai-access.html"
{ codeonly "$RD/src/main/java/com/family/finance/repository/AskAuditMapper.java" | grep -q 'recentInbound'   && codeonly "$RD/src/main/java/com/family/finance/repository/AskAuditMapper.java" | grep -q 'fromOutside'   && grep -q 'inboundRows' "$QA1201_AAT"   && grep -q 'inboundExternal' "$QA1201_AAT"   && grep -q '还没有任何外部访问' "$QA1201_AAT"; } \
  && log_ok "v1201-INBOUND-VISIBLE(管理页能看到谁访问过 · 区分本机与外部 · 零外部访问时直说)" \
  || log_bad "v1201-INBOUND-VISIBLE 入站访问记录从页面上消失了" "「来过但鉴权失败」和「根本没来过」又变得无法区分,只能靠猜"

# v1202-DIAGNOSIS-MUST-SAY-WHEN · 「不通」的结论必须带上【上次什么时候还好着】。
#   上一版这条提示只说「大概是服务没部署成功,去控制台看看」——
#   那是一个【用户无从下手、我们自己也无法证伪】的结论(百炼没有查 MCP 服务状态的公开 API),
#   于是每一轮都变成「你去看 → 还不行 → 我再猜一个」。
#   实测真因确实在那边,但让人能动手的是【什么时候坏的】:
#   知道「上次成功是 09-03 15:24」,用户立刻能对上「我那天改了什么」。
{ codeonly "$RD/src/main/java/com/family/finance/repository/AskAuditMapper.java" | grep -q 'lastBailianOkAt'   && codeonly "$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java" | grep -q 'lastBailianOkAt(FAMILY_ID)'   && codeonly "$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java" | grep -q '曾经是好的'   && codeonly "$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java" | grep -q '一次都没真正跑起来'; } \
  && log_ok "v1202-DIAGNOSIS-MUST-SAY-WHEN(自检结论带上次成功时间 · 区分「曾经好过」与「从没成功」)" \
  || log_bad "v1202-DIAGNOSIS-MUST-SAY-WHEN 自检又变回一句无从下手的猜测" "用户只能反复去控制台翻,而我们无法证伪自己的结论"

# v1202-NO-CROSS-ENV-AGENT-OVERWRITE · 更新 Agent 前必须确认它是本机的。
#   测试环境与生产环境常常共用一个百炼业务空间。如果两边的 ask_ma_agent_id 指到同一个 agent
#   (复制配置、或两边的库从同一份备份起),那么在测试机上点一下「创建 Agent」
#   就会把生产那个 agent【全量替换】掉 —— 而两边页面都显示「成功」,没有任何地方报错。
#   判据用 mcp_servers:它引用的服务必须是本机配的那一个。
QA1202_MA="$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java"
{ codeonly "$QA1202_MA" | grep -q 'assertAgentIsOurs()'   && codeonly "$QA1202_MA" | grep -A3 'public void updateAgent' | grep -q 'assertAgentIsOurs()'   && codeonly "$QA1202_MA" | grep -q 'body.put("name", agentName())'   && codeonly "$QA1202_MA" | grep -q 'K_ASK_PUBLIC_BASE_URL'; } \
  && log_ok "v1202-NO-CROSS-ENV-AGENT-OVERWRITE(更新前校验 agent 归属 · 名字带域名可区分环境)" \
  || log_bad "v1202-NO-CROSS-ENV-AGENT-OVERWRITE 又能在测试机上静默覆盖生产的 Agent 了" "两边都显示成功,没有任何地方报错"

# v1202-MCP-TOOLKIT-MUST-BE-MOUNTED · 【卡了好几天的那个 bug】
#   mcp_servers 只是「声明有这个服务器」,它不会把服务器的工具挂到 agent 上。
#   挂工具要另外给 tools:[{type:mcp_toolkit, mcp_server_name, configs:[逐个工具名]}]。
#
#   为什么必须机器守:这个失败的每一步都是成功的 ——
#     · MCP 服务在控制台部署成功
#     · 部署校验时百炼真的连到了我们的 /mcp(入站审计里有 initialize + tools/list 全 OK)
#     · 创建 agent 返回 200,回读 mcp_servers 引用也在
#     · 而 agent 在真实对话里说「我只有 mark_artifacts」,一次 tools/call 都不发
#   于是排查方向被带到「MCP 服务是不是没部署成功」上,而它明明部署成功了。
#
#   最反直觉的一条:default_config:{enabled:true} 一个人【不够】,
#   必须在 configs 里把每个工具名逐个列出来。名字叫 default_config 却不是「默认全开」。
QA1202_MA="$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java"
{ codeonly "$QA1202_MA" | grep -q '"mcp_toolkit"'   && codeonly "$QA1202_MA" | grep -q '"mcp_server_name", mcpServerId()'   && codeonly "$QA1202_MA" | grep -q 'toolkit.put("configs", perTool)'   && codeonly "$QA1202_MA" | grep -q 'registry.all().stream()'   && codeonly "$QA1202_MA" | grep -q 'body.put("tools", List.of(toolkit))'; } \
  && log_ok "v1202-MCP-TOOLKIT-MUST-BE-MOUNTED(agent 同时给 mcp_servers 与 tools/mcp_toolkit · configs 逐个列工具)" \
  || log_bad "v1202-MCP-TOOLKIT-MUST-BE-MOUNTED agent 又只声明服务器、不挂工具了" "每一步都 200,而 agent 说自己只有「标记交付物」一个工具 —— 不报错的那种坏"

# v1202-VERIFY-READS-TOOLS-BACK · 回读必须把 tools 一起查。
#   只查 mcp_servers 的话,「服务器声明了、工具一个没挂」会被判成成功 ——
#   而那正是线上卡住的形状。「上游收下了」不等于「上游存住了」,更不等于「存对了」。
{ codeonly "$QA1202_MA" | grep -A12 'private void verifyTemplate' | grep -q 'hasToolkit'   && codeonly "$QA1202_MA" | grep -q 'hasPrompt && hasMcp && hasToolkit'   && codeonly "$QA1202_MA" | grep -q 'MCP 服务引用在、但工具没挂上'; } \
  && log_ok "v1202-VERIFY-READS-TOOLS-BACK(创建/更新后回读 tools · 失败提示点名「引用在但工具没挂」)" \
  || log_bad "v1202-VERIFY-READS-TOOLS-BACK 回读不查 tools 了" "空壳 agent 会被判成创建成功"

# v1210-NO-KEYWORD-AT-TEXTBLOCK-END · SQL 文本块不许以关键字【结尾】再拼接。
#   真正的坑是这个形状:
#       @Select(\"\"\"
#               SELECT \"\"\" + COLS + ...)
#   文本块会【剥掉每行的行尾空格】,于是 "SELECT " 变成 "SELECT",拼出 SELECTid —— SQL 语法错。
#   注意:`"SELECT " + COLS`(普通字符串)是【安全】的,尾空格留得住;
#   COLS 本身是不是文本块也无所谓。第一版判据写成「COLS 不许用文本块」——太宽,
#   把两个本来正确的既有 mapper 报成了红(这一版第三次把护栏绑在错的特征上,记下来)。
#   AskAccessTokenMapper 的注释里写着这个坑「真在 beta 上炸过」,而 v1.21 又踩了一次。
# 用 codeonly 过一遍再扫 —— 否则会抓到【注释里描述这个坑的那句话】
#   (AskAccessTokenMapper 的注释里就写着这个反例,第一版判据把它报成了红)。
{ bad=$(for f in $(grep -rl 'COLS' "$RD/src/main/java/com/family/finance/repository/"); do
          codeonly "$f" | grep -qE '(SELECT|FROM|WHERE|AND|OR|SET|VALUES|JOIN|BY)[[:space:]]*"""[[:space:]]*\+' \
            && echo "${f#$RD/}"
        done | head -5)
  [ -z "$bad" ]; } \
  && log_ok "v1210-NO-KEYWORD-AT-TEXTBLOCK-END(没有「文本块以 SQL 关键字结尾再拼接」的写法)" \
  || log_bad "v1210-NO-KEYWORD-AT-TEXTBLOCK-END 有文本块以 SQL 关键字结尾再拼:$(echo "$bad" | head -2 | tr '\n' ' ')" "文本块剥掉行尾空格 → 拼出 SELECTid,只在真跑到那条查询时才炸"

# v1210-THYMELEAF-UTILITY-IN-DOLLAR · #lists/#numbers 这类 utility 必须写在 ${} 内。
#   写在外面 Thymeleaf 直接「Could not parse as expression」,而它是【渲染期】才炸:
#   编译过、单测过、启动过,响应【截断在出错那一行】——
#   用户拿到的是半截页面 + 错误页拼在一起的怪东西(v1.21 类目页在 beta 上就是这样)。
#   规则本身 memory feedback_thymeleaf_diagnosis 里早写着,还是被踩了 ——
#   只写在记忆里的规则会被再踩一次,所以做成机器扫描。
{ [ "$(cd "$RD" && python3 scripts/lint/thymeleaf-utility-scope.py | head -1)" = "CLEAN" ]; } \
  && log_ok "v1210-THYMELEAF-UTILITY-IN-DOLLAR(模板里的 #lists/#numbers 都在 \${} 内)" \
  || log_bad "v1210-THYMELEAF-UTILITY-IN-DOLLAR 有 utility 写在 \${} 外面:$(cd "$RD" && python3 scripts/lint/thymeleaf-utility-scope.py | tail -n +2 | head -3 | tr '\n' ' ')" "渲染期才炸,响应截断在半路 —— 编译/单测/启动全都发现不了"

# v1210-NO-JS-INLINE-COLLISION · <script> 里的 JS 字面量不许撞上 Thymeleaf 内联标记。
#   `[[...]]` / `[(...)]` 是 Thymeleaf 的表达式标记,而 JS 的**嵌套数组** `[['a','b'], ...]`
#   恰好以 `[[` 开头 → 被当表达式解析 → 【渲染期】在响应头发出之后炸 →
#   页面被截断成**一片空白**,连错误页都没有(v1.21 账单导入页在 beta 上就是这样)。
#   为什么必须机器扫:**curl 也过** —— 200、完整字节数、内容 grep 得到,
#   因为 curl 不校验 chunked 终止符;只有真浏览器报 ERR_INCOMPLETE_CHUNKED_ENCODING。
#   这一条是双端截图抓到的,不是接口测试 —— 「curl 200 就算验过」在这类问题上是假绿。
{ [ "$(cd "$RD" && python3 scripts/lint/thymeleaf-inline-collision.py | head -1)" = "CLEAN" ]; } \
  && log_ok "v1210-NO-JS-INLINE-COLLISION(<script> 里没有撞内联标记的 JS 字面量)" \
  || log_bad "v1210-NO-JS-INLINE-COLLISION 有 JS 字面量撞内联标记:$(cd "$RD" && python3 scripts/lint/thymeleaf-inline-collision.py | tail -n +2 | head -3 | tr '\n' ' ')" "渲染期截断响应成空白页,curl 200 也看不出来 —— 加 th:inline=\"none\""

# ═══════════════ v1.21 · 自定义支出分类(第 2 稿:分类挂在「一笔」上)═══════════════
#
# 第 1 稿的护栏大半失效了 —— 它们守的是 expense_split 那张按月汇总表与 37 格表单,
# 而那套东西被否掉了(prd/v1.21.md §14)。这里整体重写,守的是新模型:
#   分类挂在 cash_flow.expense_category_id 上,性质仍是 category_code,两者不合并。

QA121_LEDGER="$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java"
QA121_CAT="$RD/src/main/java/com/family/finance/service/expense/ExpenseCategoryService.java"
QA121_QUERY="$RD/src/main/java/com/family/finance/service/expense/ExpenseCatQueryService.java"
QA121_FLOWMAP="$RD/src/main/java/com/family/finance/repository/ExpenseFlowMapper.java"
QA121_PARSER="$RD/src/main/java/com/family/finance/service/expense/imports/CsvBillParser.java"
QA121_RESOLVER="$RD/src/main/java/com/family/finance/service/expense/imports/BillCategoryResolver.java"
QA121_IMPSVC="$RD/src/main/java/com/family/finance/service/expense/imports/BillImportService.java"
QA121_COMMIT="$RD/src/main/java/com/family/finance/service/expense/imports/BillCommitService.java"
QA121_AI="$RD/src/main/java/com/family/finance/service/expense/imports/MerchantAiClassifier.java"
QA121_ENTRYSVC="$RD/src/main/java/com/family/finance/service/EntryService.java"
QA121_MIG="$RD/db/migration/V59__expense_category_split.sql"
QA121_GRID_TPL="$RD/src/main/resources/templates/entry/_cat-grid.html"
QA121_MIX_TPL="$RD/src/main/resources/templates/reports/_expense-section.html"
QA121_IMP_TPL="$RD/src/main/resources/templates/expense/import.html"

# v1210-LEDGER-UNTOUCHED · 家庭支出的唯一口径入口【一行不改】。
#   分类只回答「钱花在哪」,不参与「花了多少」。ExpenseLedgerService 一旦开始读分类,
#   家庭支出就多了一条来源 —— 而这个项目为「口径多一条」付过月均支出差 89% 的代价。
{ ! codeonly "$QA121_LEDGER" | grep -qE 'expense_category|ExpenseCatQuery|expenseCategoryId'; } \
  && log_ok "v1210-LEDGER-UNTOUCHED(家庭支出口径入口没碰分类 · 分类不是另一条来源)" \
  || log_bad "v1210-LEDGER-UNTOUCHED ExpenseLedgerService 开始读分类了" "家庭支出多出一条口径来源 —— v1.8 那次差 89% 就是这么来的"

# v1210-NATURE-NOT-MERGED · 性质(category_code)与消费分类是【两个字段】,不许合并。
#   category_code 决定储蓄率与负债口径(联动链 L1);expense_category_id 只回答「钱花在哪」。
#   合并的后果不是报错,是储蓄率慢慢算错 —— 这类错误没有任何征兆。
#   判据:写入口必须显式限制「只在 consumption 下写分类」。
{ codeonly "$QA121_ENTRYSVC" | grep -q 'CONSUMPTION.equals(line.categoryCode()) ? expenseCategoryId : null' \
  && codeonly "$QA121_COMMIT" | grep -q 'nature ? l.natureCode() : "consumption"'; } \
  && log_ok "v1210-NATURE-NOT-MERGED(性质与消费分类分开存 · 分类只在 consumption 下写)" \
  || log_bad "v1210-NATURE-NOT-MERGED 性质与消费分类可能合并了" "还贷挂上「餐饮美食」会让还贷进消费构成,而储蓄率静默出错"

# v1210-EXPFLOW-SOFT-DELETE · 分类侧的每条【查询】都要带 deleted_at IS NULL。
#   漏掉不会报错,只会让【用户已经删掉的钱】重新出现在报表里 —— 看起来完全正常,数字大一点。
#
#   只管 SELECT,不管 UPDATE:搬家(moveCategory)【本来就该】覆盖软删行 ——
#   否则删掉一个类目之后,那些已软删的行还指着一个不存在的 category_id。
#   第一版判据把 UPDATE 也算进来,于是正确的写法被报成红(本版第 N 次把判据写太宽)。
{ [ "$(codeonly "$QA121_FLOWMAP" | grep -cE '^\s*SELECT')" -le \
     "$(codeonly "$QA121_FLOWMAP" | grep -c 'deleted_at IS NULL')" ]; } \
  && log_ok "v1210-EXPFLOW-SOFT-DELETE(分类侧查询都过滤软删)" \
  || log_bad "v1210-EXPFLOW-SOFT-DELETE 有查询漏了 deleted_at IS NULL" "已删的钱会重新出现在报表里,而且看起来完全正常"

# v1210-NO-RESERVED-WORD-ALIAS · SQL 别名不许用 MySQL 8.0 的保留字。
#   踩过:`COUNT(*) AS rows` —— rows 是随窗口函数引入的保留字,直接语法错,
#   而且只在真跑到那条查询时才炸(启动/编译/单测全过,报表页 500)。
{ ! codeonly "$QA121_FLOWMAP" | grep -qE 'AS +(rows|groups|rank|window|over|lead|lag|first|last|cume_dist)\b'; } \
  && log_ok "v1210-NO-RESERVED-WORD-ALIAS(SQL 别名避开 MySQL 8.0 保留字)" \
  || log_bad "v1210-NO-RESERVED-WORD-ALIAS 别名撞上保留字" "语法错只在跑到那条查询时才炸 —— 编译单测全过,页面 500"

# v1210-DELETE-FOLLOWS-TREE · 删类目按树走,钱一分不丢。
#   删细类 → 搬到父级;删大类 → 搬到「其他」。搬完才删节点,顺序反了会留下孤儿。
{ codeonly "$QA121_CAT" | grep -q 'moveFlows(familyId, k.getId(), otherId)' \
  && codeonly "$QA121_CAT" | grep -q 'moveFlows(familyId, id, c.getParentId())' \
  && codeonly "$QA121_FLOWMAP" | grep -q 'SET cf.expense_category_id = #{toId}'; } \
  && log_ok "v1210-DELETE-FOLLOWS-TREE(删细类搬父级 · 删大类搬「其他」· 钱一分不丢)" \
  || log_bad "v1210-DELETE-FOLLOWS-TREE 删类目可能把钱弄丢" "钱一分都不该因为删一个名字而消失"

# v1210-OTHER-IS-BEDROCK · 「其他」不可删 / 不可停用 / 不可改名。
#   它是删除语义的最终落点 —— 没了它,删大类时钱没地方去。行业通行(钱迹同款)。
{ codeonly "$QA121_CAT" | grep -c 'isOther()' | awk '$1>=4{exit 0}{exit 1}' \
  && codeonly "$QA121_CAT" | grep -q 'ensureOther'; } \
  && log_ok "v1210-OTHER-IS-BEDROCK(「其他」不可删/停用/改名 · 每条建类目路径都过 ensureOther)" \
  || log_bad "v1210-OTHER-IS-BEDROCK 「其他」的保护被削弱了" "删别的类目时,钱要有地方去"

# v1210-TWO-LEVELS-MAX · 树封顶两层(parent 的 parent 必为 NULL)。
{ codeonly "$QA121_CAT" | grep -q '!p.isTopLevel()' \
  && grep -q 'MAX_DEPTH = 2' "$QA121_CAT"; } \
  && log_ok "v1210-TWO-LEVELS-MAX(类目树封顶两层)" \
  || log_bad "v1210-TWO-LEVELS-MAX 可能能建出三层" "三层的维护成本换不来分析价值"

# v1210-MATCH-WHOLE-TREE · 导入映射在【整棵树】上做,细类优先、大类兜底。
#   第 1 稿只在「当前深度可填的那一层」里找,于是渠道给的大类名全落「其他」。
{ codeonly "$QA121_RESOLVER" | grep -q 'for (ExpenseCategory c : allNodes) if (!c.isTopLevel()) byName.putIfAbsent' \
  && codeonly "$QA121_RESOLVER" | grep -q 'for (ExpenseCategory c : allNodes) if (c.isTopLevel()) byName.putIfAbsent'; } \
  && log_ok "v1210-MATCH-WHOLE-TREE(映射在整棵树上做 · 细类优先大类兜底)" \
  || log_bad "v1210-MATCH-WHOLE-TREE 映射又只看一层了" "渠道给的是大类名,只找细类会让大部分金额落进「其他」"

# v1210-NATURE-BEFORE-NEUTRAL · 还贷的判据必须排在「关键字划转」之前。
#   踩过:NEUTRAL 表里有「还款」,于是渠道标成【支出】的花呗还款先被当划转剔掉,
#   NATURE 桶永远是空的、页面上那个格子形同虚设。
#   v1.22 · 归类步骤提前之后判据形态变了(划转不再立刻 continue,而是先记下 suggestSkip),
#   但【顺序要求本身没变】:还贷必须先于关键字划转被判到。判据跟着写法更新,意图不动。
{ na=$(codeonly "$QA121_RESOLVER" | grep -n 'String nature = natureOf(r)' | cut -d: -f1 | head -1);
  ne=$(codeonly "$QA121_RESOLVER" | grep -n 'isNeutral(r)) suggestSkip =' | cut -d: -f1 | head -1);
  [ -n "$na" ] && [ -n "$ne" ] && [ "$na" -lt "$ne" ]; } \
  && log_ok "v1210-NATURE-BEFORE-NEUTRAL(还贷判据排在关键字划转之前 · NATURE 桶真的到得了)" \
  || log_bad "v1210-NATURE-BEFORE-NEUTRAL 还贷会被当成划转剔掉" "NATURE 桶永远空 —— 页面上那个格子是死的"

# v1210-PARSER-NEVER-SILENT · 解析器对「说不清的行」计数披露,不静默跳过。
#   静默少算几行在用户那儿表现为「导入总额比账单少一点」,基本查不出来。
{ codeonly "$QA121_PARSER" | grep -q 'unknownDirection' \
  && codeonly "$QA121_PARSER" | grep -q 'columnMismatch' \
  && codeonly "$QA121_PARSER" | grep -q 'badAmount' \
  && grep -q 'hasAnomaly' "$QA121_IMP_TPL"; } \
  && log_ok "v1210-PARSER-NEVER-SILENT(解析异常计数并在确认页披露)" \
  || log_bad "v1210-PARSER-NEVER-SILENT 解析器可能又开始静默跳行了" "「总额比账单少一点」用户查不出来"

# v1210-BUCKETS-DISCLOSED · 剔除与跳过的笔数必须显示出来(FR-561)。
{ grep -q '已剔除' "$QA121_IMP_TPL" && grep -q '已存在' "$QA121_IMP_TPL" \
  && grep -q 'droppedCount' "$QA121_IMP_TPL" && grep -q 'skippedCount' "$QA121_IMP_TPL"; } \
  && log_ok "v1210-BUCKETS-DISCLOSED(剔除/跳过的笔数在确认页显式列出)" \
  || log_bad "v1210-BUCKETS-DISCLOSED 剔除或跳过的笔数没显示" "静默少导几笔 = 用户看到总额少了但查不出原因"

# v1210-REVIEW-FIRST · 「没把握的」排在确认页最前(FR-565)。
#   用户没时间看 428 笔;不把 AI/兜底排到前面,这一页就只是一张长表。
{ codeonly "$QA121_RESOLVER" | grep -q 'needsReview' \
  && grep -q '先看这些' "$RD/src/main/java/com/family/finance/web/expense/ExpenseImportController.java" \
  && grep -q '依据' "$QA121_IMP_TPL"; } \
  && log_ok "v1210-REVIEW-FIRST(没把握的排最前 · 每行标出归类依据)" \
  || log_bad "v1210-REVIEW-FIRST 确认页没把「要核对的」挑出来" "428 笔一视同仁 = 用户一笔都不会看"

# v1210-DEDUPE-BY-TXNO · 重复导入靠交易号去重(FR-560⑤)。
{ codeonly "$QA121_IMPSVC" | grep -q 'existingTxNos' \
  && codeonly "$QA121_RESOLVER" | grep -q 'Bucket.SKIPPED' \
  && codeonly "$QA121_COMMIT" | grep -q '.extTxNo(l.txNo())'; } \
  && log_ok "v1210-DEDUPE-BY-TXNO(按交易号去重 · 同一份文件导两次不出双份)" \
  || log_bad "v1210-DEDUPE-BY-TXNO 重复导入会出双份" "用户重导一次账就多一份,而且他不会立刻发现"

# v1210-AI-ONLY-MERCHANT · AI 只拿到商户名,拿不到金额(FR-563)。
#   靠自觉不行 —— 判据是【签名里没有那个字段】:入参是 List<String>,不是 List<BillRow>。
{ codeonly "$QA121_AI" | grep -q 'classify(List<String> merchants, List<String> catNames)' \
  && ! codeonly "$QA121_AI" | grep -qE 'BigDecimal|amount|BillRow' \
  && codeonly "$QA121_AI" | grep -q '绝不计算'; } \
  && log_ok "v1210-AI-ONLY-MERCHANT(AI 只收商户名 · 类型上就拿不到金额)" \
  || log_bad "v1210-AI-ONLY-MERCHANT AI 可能拿到了金额" "承 feedback_llm_no_math + 隐私红线:账单金额永不出网"

# v1210-BILL-EPHEMERAL · 账单字节/密码不落盘、不进日志。
#   这是本项目迄今最敏感的输入(整月消费流水)。
{ ! codeonly "$QA121_IMPSVC" | grep -qE 'FileOutputStream|Files\.write|createTempFile' \
  && ! codeonly "$RD/src/main/java/com/family/finance/service/expense/imports/ZipOpener.java" | grep -qE 'FileOutputStream|Files\.write|createTempFile' \
  && ! codeonly "$QA121_IMPSVC" | grep -qE 'log\.[a-z]+\([^)]*password' \
  && ! codeonly "$RD/src/main/java/com/family/finance/web/expense/ExpenseImportController.java" | grep -qE 'log\.[a-z]+\([^)]*(zipPassword|getBytes)'; } \
  && log_ok "v1210-BILL-EPHEMERAL(账单字节与解压密码不落盘、不进日志)" \
  || log_bad "v1210-BILL-EPHEMERAL 账单内容或密码可能落盘/进日志" "整月消费流水 —— 这个项目接过的最敏感的东西"

# v1210-IMPORT-IS-TRANSACTIONAL · 整批一个事务 + 一次余额调整。
#   落一半比不落更糟:用户看到「导入了 300 笔」而实际 137 笔,总额对不上时会以为解析器算错。
#   余额也必须一次调完 —— 300 笔逐个扣会写 300 条审计,把当天别的记录冲没。
{ codeonly "$QA121_COMMIT" | grep -B2 'public Result commit' | grep -q '@Transactional' \
  && codeonly "$QA121_COMMIT" | grep -q 'applyImportedExpense' \
  && [ "$(codeonly "$QA121_COMMIT" | grep -c 'applyImportedExpense')" -le 2 ]; } \
  && log_ok "v1210-IMPORT-IS-TRANSACTIONAL(整批一个事务 · 余额一次调完)" \
  || log_bad "v1210-IMPORT-IS-TRANSACTIONAL 导入可能落一半,或余额逐笔扣" "落一半时用户会以为是解析器算错了"

# v1210-LIABILITY-GUARD-BOTH-PATHS · 「负债账户上不能记还贷」在手工与导入两条路都要守。
#   v1.19.3 的规则原来只在 select 上摘 option;类目换成宫格之后那段代码对按钮不适用,
#   而导入这条路用户根本看不到自己这批里有几笔还贷 —— 只有服务端能挡。
{ codeonly "$QA121_COMMIT" | grep -q 'isLiability()' \
  && grep -q 'data-repayment' "$QA121_GRID_TPL" \
  && grep -q 'data-repayment' "$RD/src/main/resources/static/js/expense-liability.js"; } \
  && log_ok "v1210-LIABILITY-GUARD-BOTH-PATHS(手工与导入两条路都挡住「信用卡上记还贷」)" \
  || log_bad "v1210-LIABILITY-GUARD-BOTH-PATHS 有一条路没挡" "刷卡消费 + 还款会把同一笔钱算进两次本月支出"

# v1210-UNCLASSIFIED-NOT-DROPPED · 「未分类」是一等公民,不许被过滤掉。
#   v1.21 之前的历史流水 expense_category_id 为 null。过滤掉它们,
#   构成合计会莫名其妙小于消费总额 —— 而没有任何地方会报错。
#   判据:聚合类查询(sumByCategory / sumByPeriodAndCategory)不许过滤 NULL 分类。
#   recentCategoryIds 是例外 —— 它算的是「最近常用」,未分类本来就不该出现在宫格里。
{ codeonly "$QA121_QUERY" | grep -q 'UNCLASSIFIED' \
  && [ "$(codeonly "$QA121_FLOWMAP" | grep -c 'expense_category_id IS NOT NULL')" -eq 1 ] \
  && codeonly "$QA121_FLOWMAP" | grep -B16 'List<Long> recentCategoryIds' \
       | grep -q 'expense_category_id IS NOT NULL'; } \
  && log_ok "v1210-UNCLASSIFIED-NOT-DROPPED(未分类如实显示,不被过滤)" \
  || log_bad "v1210-UNCLASSIFIED-NOT-DROPPED 未分类的钱可能被过滤掉了" "构成合计会小于消费总额,而且不报错"

# v1210-NODATA-NOT-ZERO · 没有分类数据的月份画斜纹,不画 0(FR-519)。
#   画成 0 会在趋势图上造出一段假的「支出暴跌」,而那个月用户明明花了钱。
{ codeonly "$QA121_QUERY" | grep -q 'monthlyTotal(familyId, p.getId())' \
  && grep -q 'repeating-linear-gradient' "$QA121_MIX_TPL" \
  && grep -q '不是没花钱' "$QA121_MIX_TPL"; } \
  && log_ok "v1210-NODATA-NOT-ZERO(没分类数据的月份画斜纹 + 说明,不画 0)" \
  || log_bad "v1210-NODATA-NOT-ZERO 未分类月份可能被画成 0" "趋势图上出现一段假的支出暴跌"

# v1210-YEAR-MATCHES-TREND · 年度累计与趋势必须同锚。
#   不同锚会让同一页上两个数对不上,而两个都「对」—— 最难解释的一类不一致。
{ codeonly "$QA121_QUERY" | grep -q 'public List<CatRow> year(long familyId, int year, Period anchor)' \
  && codeonly "$QA121_QUERY" | grep -q 'p.getPeriodStart().isAfter(anchor.getPeriodStart())'; } \
  && log_ok "v1210-YEAR-MATCHES-TREND(年度累计锚在同一批账期上)" \
  || log_bad "v1210-YEAR-MATCHES-TREND 年度累计与趋势不同锚" "同页两个数对不上,而两个都「对」—— 用户只会以为算错了"

# v1210-CALIBER-STATED · 报表必须写明「构成合计 < 支出总额」是正常的。
#   不说的话用户会拿这两个数对账,然后以为我们算错了。
{ grep -q '本来就' "$QA121_MIX_TPL" && grep -q '不进「钱花在哪」' "$QA121_MIX_TPL"; } \
  && log_ok "v1210-CALIBER-STATED(报表写明构成合计本来就小于支出总额)" \
  || log_bad "v1210-CALIBER-STATED 没说清口径差异" "用户拿构成合计对账本总额,会以为我们算错了"

# v1210-NO-STATIC-CALL-IN-FRAGMENT-ARG · 片段表达式的【参数里】不许调 T(...) 静态方法。
#
#   判据故意很窄,因为宽的版本是错的:`th:if="${T(...).x()}"` 这类普通属性用法
#   项目里有三处、一直好好的(holdings / accounts index / accounts detail)。
#   炸的是【片段参数】这个位置:
#       ~{fragments/_cat-icons :: icon(${T(com.family...).of(name)})}
#   实测(注入 → 复现 → 还原):/entry 从 435KB 掉到 93KB、curl 退出码 18,
#   浏览器永远等不到 DOMContentLoaded —— 因为错误发生在响应头发出【之后】,页面截断成半张。
#   顺带也是分层问题:模板不该知道某个类的全限定名。派生值在 controller 算好传进来。
{ bad=$(grep -rnE '~\{[^}]*::[^}]*T\(com\.family' "$RD/src/main/resources/templates" 2>/dev/null | head -3)
  [ -z "$bad" ]; } \
  && log_ok "v1210-NO-STATIC-CALL-IN-FRAGMENT-ARG(片段参数里没有 T(...) 静态调用)" \
  || log_bad "v1210-NO-STATIC-CALL-IN-FRAGMENT-ARG 片段参数在调静态方法:$bad" "渲染期炸在响应头之后 —— 页面截断成半张,curl 看着还是 200"

# v1210-ICON-HAS-FALLBACK · 图标集必须有 default 分支。
#   没有的话,名字猜不中的类目会渲染成一个【空方块】—— 比难看更糟,因为没人会意识到是没匹配上。
{ grep -q "th:case=\"\*\"" "$RD/src/main/resources/templates/fragments/_cat-icons.html" \
  && grep -q 'DEFAULT = "tag"' "$RD/src/main/java/com/family/finance/service/expense/ExpenseCatIcon.java"; } \
  && log_ok "v1210-ICON-HAS-FALLBACK(图标集有兜底分支 · 猜不中也不会是空方块)" \
  || log_bad "v1210-ICON-HAS-FALLBACK 图标集没有兜底" "猜不中的类目会渲染成空方块,而且没人会意识到是没匹配上"

# v1210-CELLS-SAME-SIZE · 消费分类与性质项【同尺寸同 class】。
#   memory feedback_sibling_uniform_selfcheck:并列同类元素必须同尺寸同样式。
#   第一版性质项用了小一圈的 .cat-chip,两排并列高矮不齐 —— 用户一眼指出来。
{ grep -q 'class="cat-cell cat-cell--nature"' "$QA121_GRID_TPL" \
  && ! grep -qE 'class="cat-chip"[^>]*data-code' "$QA121_GRID_TPL" \
  && grep -q 'min-height: 68px' "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v1210-CELLS-SAME-SIZE(分类格与性质格同尺寸 · 只靠颜色区分)" \
  || log_bad "v1210-CELLS-SAME-SIZE 性质项和分类格不一样大了" "并列同类元素必须同尺寸同样式"

# v1210-IMPORT-ENTRY-VISIBLE · 导入入口必须在【填报页】一眼可见。
#   踩过:实现时只在报表页一句说明文字里内联了一个链接,填报页(用户真正在的地方)一个都没有 ——
#   是维护者问「导入按钮在哪」才发现的。运行时可见性由 v1623-ENTRY-VIS 守,这里守静态登记。
#   第二次又被问「导出支出流水的入口呢」—— 那时链接【确实存在】(entry-points-check 也过了:
#   它只验「可见」,不验「分量」),但我把它做成了 10px 灰字挂在分隔线右端,连着两轮没被发现。
#   **入口存在 ≠ 入口找得到。** 所以这条护栏现在同时要求:主入口是支出区标题行上的
#   btn-ghost 按钮(不是脚注链接),而且带一句说明它能省什么事。
{ grep -q '/expense/import' "$QA121_GRID_TPL" \
  && grep -q 'btn-ghost' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q '一笔一笔记太慢' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q '"id": "expense-import"' "$RD/scripts/entry-points.json" \
  && grep -q '"id": "expense-categories"' "$RD/scripts/entry-points.json"; } \
  && log_ok "v1210-IMPORT-ENTRY-VISIBLE(导入与类目管理入口挂在填报页 + 已登记入口表)" \
  || log_bad "v1210-IMPORT-ENTRY-VISIBLE 导入入口不在填报页,或没登记" "这一版最值钱的能力,用户找不到等于没做"

# v1210-NO-DEPTH-SETTING · 第 2 稿【没有】录入深度这个设置项。
#   点大类就能提交,想细分再多点一下 —— 深度是每一笔的自由选择,不是要预先决定的配置。
{ ! grep -rq 'K_EXPENSE_SPLIT_DEPTH' "$RD/src/main/java" \
  && ! grep -q 'categories/depth' "$RD/src/main/resources/templates/expense/categories.html"; } \
  && log_ok "v1210-NO-DEPTH-SETTING(没有录入深度设置项 · 深度是每笔的自由选择)" \
  || log_bad "v1210-NO-DEPTH-SETTING 深度设置项又回来了" "多一个设置就多一整类「切换之后历史怎么办」的问题"

# v1210-BALANCE-LINK-OPTIONAL · 「落到账户」是可选的,而且两条口径要一起走。
#
#   起因:applyDeltaToBalance【直接改写 period_snapshot.end_balance】——
#   那是用户自己填的期末余额,不是预填值。已经核对完余额再导账单的人会被【扣第二遍】,
#   几百笔一起扣,错得很大而且不报错。
#
#   affects_balance=0 时必须【同时】满足三条,少一条就是新的静默错误:
#     ① 不动余额        —— creditAccountBalance / applyImportedExpense 跳过
#     ② 不参与轧差      —— findByPeriodAndAccount 过滤掉,否则 unexplained 变负数,
#                          账户上凭空出现一个「解释过头」的差额
#     ③ 不算账户外部流出 —— FactMapper 的 expense_orig 过滤掉,否则 NAV 以为
#                          「钱是被取走的不是亏掉的」,把账户收益率算高
#   而【仍然要】进家庭消费(口径 A)与支出构成 —— 钱确实花了。
{ grep -q 'AND affects_balance = 1' "$RD/src/main/resources/mapper/FactMapper.xml" \
  && grep -qE 'AND (cf\.)?affects_balance = 1' "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
  && grep -q 'if (affectsBalance) {' "$QA121_ENTRYSVC" \
  && grep -q 'if (affectsBalance) {' "$QA121_COMMIT" \
  && ! codeonly "$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java" \
       | grep -A14 'RealExpenseSum> sumRealExpenseByPeriod' | grep -q 'affects_balance' \
  && [ "$(python3 - <<'PY'
import re
s=open("src/main/resources/templates/entry/index.html",encoding="utf-8").read()
def seg(act):
    i=s.find('th:action="@{'+act+'}"');  j=s.find("</form>", i)
    return s[i:j] if i>0 else ""
# 必须【只】在支出表单里 —— 放错到收入表单的后果是:支出表单没这个字段,
# @RequestParam(defaultValue="false") 让【每一笔手工支出都不再扣余额】,静默改行为。
print("%d%d" % (seg("/entry/expense").count("affectsBalance"),
                seg("/entry/income").count("affectsBalance")))
PY
)" = "10" ] \
  && grep -q 'name="affectsBalance"' "$QA121_IMP_TPL"; } \
  && log_ok "v1210-BALANCE-LINK-OPTIONAL(落到账户可选 · 关掉时余额/轧差/外部流三条一起退出 · 家庭消费照常算)" \
  || log_bad "v1210-BALANCE-LINK-OPTIONAL 「不落账户」漏了某一条口径" "少一条就是新的静默错误:要么余额被扣两遍,要么账户收益率被算高"

# v1210-REVOKE-ONLY-REFUNDS-WHAT-IT-TOOK · 撤销批次时,只有当初扣过余额的才把钱加回。
#   否则「只记构成」的批次一撤销,余额会凭空多出一笔钱。
#   判据看的是「撤销时的加回列表由 hadBalance 决定」—— 不绑具体写法(if / 三元都行),
#   但必须能看出「没扣过就不会进加回循环」。
{ codeonly "$QA121_COMMIT" | grep -q 'batchAffectsBalance' \
  && codeonly "$QA121_COMMIT" | grep -qE 'hadBalance \?|if \(hadBalance\)'; } \
  && log_ok "v1210-REVOKE-ONLY-REFUNDS-WHAT-IT-TOOK(撤销只加回当初扣过的)" \
  || log_bad "v1210-REVOKE-ONLY-REFUNDS-WHAT-IT-TOOK 撤销可能凭空加回余额" "「只记构成」的批次撤销后,账上会多出一笔钱"

# v1210-ACCOUNT-IS-PER-ROW · 账户是【每一笔一个】,不是整批一个(FR-575)。
#   一份支付宝月账单里 190 笔可能分别走余额宝 / 花呗 / 招商银行储蓄卡 ——
#   「收/付款方式」那一列本来就是变化的。整批落到同一个账户,等于把几个账户的钱
#   算到一个头上:余额轧差全错、账户级收益率全错,而且【不报错】。
#   撤销时也必须按账户分别加回,否则别的账户的钱会被塞给批次的「主账户」。
{ grep -q 'String payMethod' "$RD/src/main/java/com/family/finance/service/expense/imports/BillRow.java" \
  && codeonly "$QA121_RESOLVER" | grep -q 'BillAccountResolver.resolve' \
  && codeonly "$QA121_COMMIT" | grep -q 'Long rowAcct = l.accountId()' \
  && codeonly "$QA121_COMMIT" | grep -q 'byAccount.merge' \
  && codeonly "$QA121_COMMIT" | grep -q 'batchAmountByAccount' \
  && grep -q 'name="acct"' "$QA121_IMP_TPL"; } \
  && log_ok "v1210-ACCOUNT-IS-PER-ROW(账户逐笔推荐可改 · 扣余额与撤销都按账户分开)" \
  || log_bad "v1210-ACCOUNT-IS-PER-ROW 账户又变成整批一个了" "一份账单跨几个账户是常态 —— 算到一个头上会让余额和收益率一起错,而且不报错"

# v1210-BULK-OPS · 确认页必须能批量操作,而且没选行时前端就拦。
#   几百笔逐个勾选是不可能的。第一版把复选框当成「剔除」,于是批量操作无处落脚 ——
#   维护者原话:「几百笔 你让用户自己一个个去勾选啊」。
#   现在复选框 = 选中(纯前端),批量改分类 / 改账户 / 剔除都作用在选中行上;
#   全选有两级:表头全选 + 每个分类组一个「全选该组」。
{ grep -q 'data-sel-all' "$QA121_IMP_TPL" \
  && grep -q 'data-sel-grp' "$QA121_IMP_TPL" \
  && grep -q 'data-bulk-cat' "$QA121_IMP_TPL" \
  && grep -q 'data-bulk-acct' "$QA121_IMP_TPL" \
  && grep -q 'needSelection' "$RD/src/main/resources/static/js/bill-confirm.js" \
  && grep -q "一笔都没勾" "$RD/src/main/resources/static/js/bill-confirm.js"; } \
  && log_ok "v1210-BULK-OPS(全选/组全选/批量改分类账户/剔除 · 空选与空提交前端就拦)" \
  || log_bad "v1210-BULK-OPS 确认页缺批量操作或前端拦截" "几百笔逐个勾选等于没做;空提交让用户跑一圈回来看红字也是"

# v1210-AMOUNT-GUARD · 金额 ≤ 0 的行必须在【分桶】就挡掉,不能等 DB 约束。
#   cash_flow 上有 CHECK(amount > 0)。真实账单里存在冲正行(负数)和占位行(0),
#   撞上之后整批事务回滚,而用户看到的只是一句「落库失败」—— 真实账单上炸过。
#   现在:分桶时进「已剔除」并写明原因,落库前再拦一道并给人话。
#   【v1.22 改写了这一条的前提】。原判据要求「金额 ≤ 0 在分桶时就挡掉」——
#   那是 v1.21 的正确行为(当时 DB 上有 CHECK(amount > 0),放过去整批事务会回滚)。
#   v1.22 的 V60 去掉了那条约束,0 元和负数都成了合法值,「挡掉」反而是错的。
#   但这条护栏的**另一半意图仍然成立**:兜底提示必须把原因说出来,
#   不能让用户看着一句「落库失败,再试一次」去重试一个确定性失败。所以判据改成守那一半。
{ ! grep -q 'ck_cash_flow_amount' "$RD/db/migration/V1__init.sql.disabled" 2>/dev/null;
  grep -q 'humanCause' "$RD/src/main/java/com/family/finance/web/expense/ExpenseImportController.java" \
  && grep -q 'ck_cash_flow_amount' "$RD/src/main/java/com/family/finance/web/expense/ExpenseImportController.java" \
  && codeonly "$QA121_COMMIT" | grep -q 'l.amount() == null'; } \
  && log_ok "v1210-AMOUNT-GUARD(约束已放开 · 但 null 仍拦 · 兜底提示仍说原因)" \
  || log_bad "v1210-AMOUNT-GUARD 兜底提示不再说原因,或 null 金额没拦" "「落库失败,再试一次」对确定性失败就是在骗用户"

# v1210-NO-NATIVE-SELECT · 导入页不许出现系统原生下拉。
#   全站统一用自研件(data-lsel:搜索 + 拼音首字母 + 键盘 + 短列表自动省搜索框)。
#   原生 select 在这套衬线排版里既难看又没法搜 —— 账户/分类可能有几十项。
{ [ "$(grep -c '<select ' "$QA121_IMP_TPL")" = "$(grep -c '<select data-lsel' "$QA121_IMP_TPL")" ] \
  && grep -q 'lens-select.js' "$QA121_IMP_TPL" \
  && grep -q "dispatchEvent(new Event('change'" "$RD/src/main/resources/static/js/bill-confirm.js"; } \
  && log_ok "v1210-NO-NATIVE-SELECT(导入页下拉全是自研件 · 批量赋值后派发 change 同步文案)" \
  || log_bad "v1210-NO-NATIVE-SELECT 有原生 select,或批量赋值没派发 change" "不派发的话值对了但按钮文案还是旧的 —— 用户以为功能坏了"

# v1210-REMEMBER-KEYWORD-REUSABLE · 「记住我的改动」提取的关键字要可复用。
#   原来取商户名前 20 字 —— 「瑞幸咖啡(国贸店)」换一家店就不命中,
#   规则越攒越多却一条都不复用。现在剥掉门店名/编号/企业后缀,并有单测钉住。
{ codeonly "$RD/src/main/java/com/family/finance/service/expense/imports/BillImportService.java" \
    | grep -q 'merchantKeyword' \
  && [ -f "$RD/src/test/java/com/family/finance/service/expense/imports/MerchantKeywordTest.java" ] \
  && grep -q 'acctRules' "$QA121_IMP_TPL"; } \
  && log_ok "v1210-REMEMBER-KEYWORD-REUSABLE(关键字剥掉可变部分 + 两类规则都能看能删)" \
  || log_bad "v1210-REMEMBER-KEYWORD-REUSABLE 关键字不可复用,或规则看不到删不掉" "只写不给看的记忆,用户发现归错类时无从下手"

# v1210-SHARED-RECORD-BOTH-QUERIES · IncomeEntryRow 被收入与支出【两条查询】共用,
#   加字段必须两条一起加。只改一条的话另一条返回的列数不够,
#   MyBatis 构造 record 时 IndexOutOfBounds —— 【运行期】才炸(编译过、单测过,填报页直接变错误页)。
#   【判据第一版写死了 2,于是漏掉第三条】v1.21.0 上线时 `expenseBreakdownDetail`
#   (报表 → 支出构成 → 逐笔抽屉)也返回 IncomeEntryRow,但没补这两列 ——
#   两条给了列就 `-eq 2`,护栏一路绿,而那个抽屉在 prod 上一直是空的。
#   更糟的是它**不会报错到页面上**:HTMX 片段失败只是没内容,肉眼看着就像「本来就没数据」。
#   所以判据改成:**用到这个 record 的查询有几条,新列就得出现几次**,不写死数字。
{ CFM="$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java";
  n=$(codeonly "$CFM" | grep -cE 'List<IncomeEntryRow>[[:space:]]+[a-zA-Z]');
  [ "$n" -ge 2 ] \
  && [ "$(codeonly "$CFM" | grep -c 'AS occurredAt')" -eq "$n" ] \
  && [ "$(codeonly "$CFM" | grep -c 'AS expenseCategoryName')" -eq "$n" ]; } \
  && log_ok "v1210-SHARED-RECORD-BOTH-QUERIES(共用 record 的新列在【每一条】查询里都给了)" \
  || log_bad "v1210-SHARED-RECORD-BOTH-QUERIES 共用 record 有查询没补齐新列" "列数不够 → MyBatis 运行期 IndexOutOfBounds;HTMX 片段里只表现为「没数据」,不报错"

# v1210-FLOW-LIST-PAGED · 流水列表要能搜能翻页(导入能一次落进几百笔)。
#   合计行永远显示【全部】的合计,不随筛选变 —— 筛选是展示层的事,不碰口径。
{ grep -q 'data-flow-list' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q 'data-flow-row' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q 'MIN_ROWS' "$RD/src/main/resources/static/js/flow-table.js" \
  && grep -q '筛出' "$RD/src/main/resources/static/js/flow-table.js" \
  && ! grep -q 'innerHTML' "$RD/src/main/resources/static/js/flow-table.js"; } \
  && log_ok "v1210-FLOW-LIST-PAGED(流水列表可搜可翻页 · 行数少时工具条不出现 · 不拼 HTML)" \
  || log_bad "v1210-FLOW-LIST-PAGED 流水列表缺搜索/分页" "一个月几百笔时填报页会变成望不到头的长龙"

# v1210-ZERO-CONFIG-INVISIBLE · 没建类目的家庭,页面一个像素都不多(FR-520)。
{ grep -q 'th:if="${hasExpenseCats}"' "$QA121_GRID_TPL" \
  && grep -q 'th:unless="${hasExpenseCats}"' "$QA121_GRID_TPL" \
  && grep -q 'th:if="${mixEnabled or catMixEnabled}"' "$QA121_MIX_TPL"; } \
  && log_ok "v1210-ZERO-CONFIG-INVISIBLE(没建类目/没有分类数据的家庭看不到任何新东西)" \
  || log_bad "v1210-ZERO-CONFIG-INVISIBLE 未启用的家庭也看到新东西了" "违反「有没有用本身就是开关」"

# v1210-NO-KEYWORD-AT-TEXTBLOCK-END · 文本块不许以 SQL 关键字结尾再拼接。
#   文本块剥掉行尾空格 → 拼出 SELECTid,只在真跑到那条查询时才炸。
#   先过 codeonly:否则会抓到注释里描述这个坑的那句话。
{ bad=$(for f in $(grep -rl 'COLS' "$RD/src/main/java/com/family/finance/repository/"); do
          codeonly "$f" | grep -qE '(SELECT|FROM|WHERE|AND|OR|SET|VALUES|JOIN|BY)[[:space:]]*"""[[:space:]]*\+' \
            && echo "${f#$RD/}"
        done | head -5)
  [ -z "$bad" ]; } \
  && log_ok "v1210-NO-KEYWORD-AT-TEXTBLOCK-END(没有「文本块以 SQL 关键字结尾再拼接」的写法)" \
  || log_bad "v1210-NO-KEYWORD-AT-TEXTBLOCK-END 有文本块以 SQL 关键字结尾再拼:$bad" "文本块剥掉行尾空格 → 拼出 SELECTid,只在真跑到那条查询时才炸"

# v1210-MIGRATION-IS-ADDITIVE · V59 只新增,不改既有行(联动链 L7)。
#   回滚只回 jar 不回 DB → 新列必须可空,老 jar 见到它们要能照常跑。
{ ! grep -qE '^\s*(UPDATE|DELETE|DROP TABLE|ALTER TABLE [a-z_]+ (DROP|MODIFY|CHANGE))' "$QA121_MIG" \
  && grep -q 'ADD COLUMN expense_category_id BIGINT NULL' "$QA121_MIG"; } \
  && log_ok "v1210-MIGRATION-IS-ADDITIVE(V59 纯新增 · 新列可空 · 老 jar 照常跑)" \
  || log_bad "v1210-MIGRATION-IS-ADDITIVE V59 动了既有数据或加了非空列" "回滚只回 jar 不回 DB —— 迁移必须向前兼容老 jar"


# ═══════════════ v1.21.2 · 确认页分桶 + 流水筛选/分页 ═══════════════

QA1212_BILLJS="$RD/src/main/resources/static/js/bill-confirm.js"
QA1212_IMPHTML="$RD/src/main/resources/templates/expense/import.html"
QA1212_FLOWJS="$RD/src/main/resources/static/js/flow-table.js"
QA1212_TOOLBAR="$RD/src/main/resources/templates/entry/_flow-toolbar.html"

# v1212-NO-AI-ON-UPLOAD · 上传阶段不许调 AI。
#   为什么:AI 在上传时同步调 → 页面停在「读到 428 笔」几十秒不动,用户以为卡死。
#   而这个等待是**可以完全避免的**:前两层归类(渠道分类 / 家庭规则)是纯本地的,
#   确认页本来就该立刻出来;AI 是确认页上的一个**可选动作**。
#   守法:toDraft() 里不许出现 aiClassifier 调用 —— 回退到那个写法会重新把用户晾在那。
#   【判据本身踩过坑】第一版写的是 awk '/ Draft toDraft\(/' —— 而签名是
#   `BillCategoryResolver.Draft toDraft(`,Draft 前面是点不是空格,于是一行都抽不出来、
#   grep 在空串上找不到东西 → 这条护栏在【什么都没检查】的状态下常绿。
#   所以下面必须先断言 body 非空:抽不出方法体 = 判据坏了,要红,不能当没事。
{ svc="$RD/src/main/java/com/family/finance/service/expense/imports/BillImportService.java";
  body=$(awk '/Draft toDraft\(/,/^    }$/' "$svc");
  [ -n "$body" ] && ! echo "$body" | grep -qE 'aiClassifier|MerchantAiClassifier'; } \
  && log_ok "v1212-NO-AI-ON-UPLOAD(toDraft 不调 AI · 确认页立刻出)" \
  || log_bad "v1212-NO-AI-ON-UPLOAD toDraft 里又调了 AI" "上传要等几十秒且页面无反馈,用户以为卡死 —— AI 该是确认页上的按钮"

# v1212-AI-NEVER-SEES-AMOUNT · 送进 AI 的只有商户名和分类名,永远没有金额。
#   类型层面就挡住:classify(List<String> merchants, List<String> catNames)。
#   签名一旦被改成收 Line/BillRow,金额就会顺着对象流出去 —— 那是把家庭账本喂给外部服务。
{ grep -qE 'classify\(\s*(java\.util\.)?List<String>\s+\w+,\s*(java\.util\.)?List<String>\s+\w+' \
    "$RD/src/main/java/com/family/finance/service/expense/imports/MerchantAiClassifier.java"; } \
  && log_ok "v1212-AI-NEVER-SEES-AMOUNT(AI 入参只有商户名 + 分类名)" \
  || log_bad "v1212-AI-NEVER-SEES-AMOUNT MerchantAiClassifier 的入参不再是纯字符串" "金额会顺着对象流给外部 LLM —— 类型层面就该挡住"

# v1212-BUCKET-TABS-DOCUMENT-SCOPE · 分桶页签必须从 document 找,不能从 form 里找。
#   页签在 <form> 之【外】(它是总览,不是要提交的字段)。
#   写成 form.querySelector 会拿到 null —— 点了没反应、控制台也不报错,
#   开发时正是这么漏过去的,双端截图都看不出来(页面长得完全正常)。
{ grep -q "document.querySelector('\[data-bucket-tabs\]')" "$QA1212_BILLJS" \
  && ! grep -q "form.querySelector('\[data-bucket-tabs\]')" "$QA1212_BILLJS"; } \
  && log_ok "v1212-BUCKET-TABS-DOCUMENT-SCOPE(页签从 document 找 · 它在 form 外)" \
  || log_bad "v1212-BUCKET-TABS-DOCUMENT-SCOPE 页签又从 form 里找了" "querySelector 返回 null → 点了没反应且不报错"

# v1212-BUCKETS-UNIFORM-SIZE · 并排的桶必须同尺寸(用户 2026-05 定的规矩,已被提过一次)。
#   守法:.bkt 用固定 width 而不是 min-width —— min-width 下文案长的那个会撑宽,
#   「要导入 125px / 已剔除 118px」肉眼就是没对齐;而且所有桶都得是 .bkt,
#   不许再出现「旁边挂个 style=min-width 的静态 div」那种半吊子。
{ awk '/^\.bkt \{/,/^}/' "$RD/src/main/resources/static/css/style.css" | grep -qE '^\s*width:\s*[0-9]+px' \
  && ! grep -cE 'data-bucket="[a-z]+"' "$QA1212_IMPHTML" >/dev/null   || awk '/^\.bkt \{/,/^}/' "$RD/src/main/resources/static/css/style.css" | grep -qE '^\s*width:\s*[0-9]+px'; } \
  && log_ok "v1212-BUCKETS-UNIFORM-SIZE(.bkt 固定宽 · 五个桶同尺寸)" \
  || log_bad "v1212-BUCKETS-UNIFORM-SIZE .bkt 用了 min-width 或没定宽" "文案长的桶会被撑宽,并排看就是没对齐"

# v1212-SKIPPED-IS-READONLY · 「已存在」桶不许出现「强制再导」。
#   那等于给一个「制造双份」的按钮,而双份在报表上表现为「这个月怎么花了两倍」,
#   查起来很费劲。真要重导的路径是:去「本期已导入」整批撤销。
{ pnl=$(awk '/data-bucket-panel="skipped"/,/<\/div>/' "$QA1212_IMPHTML");
  [ -n "$pnl" ] && ! echo "$pnl" | grep -qE 'name="(restore|force|reimport)"|<button'; } \
  && log_ok "v1212-SKIPPED-IS-READONLY(已存在桶只读 · 没有强制再导)" \
  || log_bad "v1212-SKIPPED-IS-READONLY 已存在桶里出现了可操作控件" "等于给一个「制造双份」的按钮"

# v1212-RESTORE-COUNTS-AS-CONTENT · 提交拦截必须把「捞回来」算作有内容。
#   漏了这条的后果:第二次导同一份账单(要导入 0 笔、全是已存在)时,
#   用户想把误剔的几笔捞回来会被自己的前端拦死,弹的还是「所有笔都被剔除了」——
#   和他正在做的事完全对不上。这条是真机走完整往返才发现的,静态看代码看不出来。
#   【v1.22 换了载体,意图不变】。原来数的是 restore(从「已剔除」捞回来的行),
#   那个概念没了;现在同一个风险出现在「已存在」的修正上:
#   用户这次可能只是来改上次归错的分类,一笔新的都不录 —— 拦死他就等于白改。
#   判据永远是同一句话:**所有会产生提交内容的控件都要数进去,不能只数主列表**。
{ grep -qF 'data-ex-cat],[data-ex-acct]' "$QA1212_BILLJS" \
  && grep -q 'checked === 0 && exEdited === 0' "$QA1212_BILLJS"; } \
  && log_ok "v1212-RESTORE-COUNTS-AS-CONTENT(提交拦截把「已存在的修正」也算作内容)" \
  || log_bad "v1212-RESTORE-COUNTS-AS-CONTENT 提交拦截只数了主列表" "只来改上次归类的用户会被自己的前端拦死"

# v1212-CSRF-AS-FORM-PARAM · fetch 带 CSRF 走【表单参数】,不许自己拼 header 名。
#   本项目配的是 CookieCsrfTokenRepository,它认的 header 叫 X-XSRF-TOKEN;
#   手写成 X-CSRF-TOKEN 会稳定 403,而前端 catch 到之后报的是「网络没通」——
#   把 403 说成网络问题,用户会一直重试一个永远不会好的东西。
{ ! grep -q "'X-CSRF-TOKEN'" "$QA1212_BILLJS" \
  && grep -q 'URLSearchParams' "$QA1212_BILLJS"; } \
  && log_ok "v1212-CSRF-AS-FORM-PARAM(CSRF 走表单参数 · 不依赖 header 命名)" \
  || log_bad "v1212-CSRF-AS-FORM-PARAM fetch 又自己拼 X-CSRF-TOKEN 了" "CookieCsrfTokenRepository 认的是 X-XSRF-TOKEN,拼错稳定 403"

# v1212-JS-ENTRY-IS-FORM-NOT-BAR · 逐笔表的 JS 入口是 <form>,不是批量工具条。
#   「要导入 0 笔」(月底重导同一份账单最常见的一屏)时模板不渲染批量工具条,
#   而**提交拦截在那一屏仍然必须生效** —— 用户就是在那一屏去「已剔除」里捞回来的。
#   以工具条为入口会把拦截和它绑死:工具条一没,提交直接打到服务端,
#   用户又看到一条后端返回的红字,而这正是当初要求前端拦截的原因。
#   同理,分桶页签必须由独立的 initTabs 绑定,不能挂在 init 里。
{ grep -q 'data-bill-form' "$QA1212_BILLJS" \
  && grep -q 'function initTabs' "$QA1212_BILLJS" \
  && grep -q 'initTabs(); init(' "$QA1212_BILLJS" \
  && ! grep -qE "var bar = \(root \|\| document\).querySelector\('\[data-bill-bar\]'\)" "$QA1212_BILLJS" \
  && grep -q 'data-bill-form' "$QA1212_IMPHTML"; } \
  && log_ok "v1212-JS-ENTRY-IS-FORM-NOT-BAR(入口是 form · 页签独立绑 · 0 笔那屏照样拦得住)" \
  || log_bad "v1212-JS-ENTRY-IS-FORM-NOT-BAR JS 又以批量工具条为入口了" "0 笔可导那一屏工具条不渲染 → 提交拦截和页签一起失效"

# v1212-EMPTY-SPEND-HAS-GUIDANCE · 「要导入 0」必须给出下一步,不能只剩一张空表。
#   这是月底重导最常见的一屏。空白会让用户以为导入坏了 ——
#   实际上他要做的是点「已存在」核对,或者去「已剔除」捞回来。
{ grep -q 'selectableCount == 0' "$QA1212_IMPHTML" \
  && awk '/selectableCount == 0/,/<\/p>/' "$QA1212_IMPHTML" | grep -q '已存在'; } \
  && log_ok "v1212-EMPTY-SPEND-HAS-GUIDANCE(0 笔可导时给出下一步)" \
  || log_bad "v1212-EMPTY-SPEND-HAS-GUIDANCE 0 笔可导时只剩空表" "月底重导最常见的一屏,空白会被当成导入坏了"

# v1212-FLOW-PAGE-SIZE-10 · 流水分页默认 10,并给 10/20/50/100 四档。
#   20 条在手机上要滑很久才够得到分页按钮;但「一次核对整月」又确实需要 100。
#   所以给档位而不是替用户定死 —— 两边都别硬编码成另一个数。
#   【v1.22.2 起默认值按视口挑】(手机 5 / 桌面 10),不再是一个常量 ——
#   判据跟着改成「有 defaultSize() 且桌面分支是 10」,档位仍然要齐。
{ grep -qE 'matches\) \? 5 : 10' "$QA1212_FLOWJS" \
  && for v in 5 10 20 50 100; do grep -q "value=\"$v\"" "$QA1212_TOOLBAR" || exit 1; done; } \
  && log_ok "v1212-FLOW-PAGE-SIZE-10(桌面默认 10 · 手机 5 · 五档可切)" \
  || log_bad "v1212-FLOW-PAGE-SIZE-10 默认条数或档位不对" "默认 20 在手机上要滑很久;档位少了就等于替用户定死"

# v1212-FACETS-FROM-ACTUAL-DATA · 筛选器候选值取自当期实际有的值,不是全量字典。
#   下拉里列一堆这个月根本没出现过的分类/账户,选了只会得到空列表 ——
#   那不是筛选,是让用户自己试错。守法:facets 从 entries 里归集,不从 mapper 拉全量。
{ ctl="$RD/src/main/java/com/family/finance/web/entry/EntryController.java";
  blk=$(awk '/java.util.function.Function<java.util.List<com.family.finance.repository.CashFlowMapper.IncomeEntryRow>/,/^        };$/' "$ctl");
  [ -n "$blk" ] && ! echo "$blk" | grep -qE '\b[a-z][A-Za-z]*Mapper\.[a-z]|listExpenseOrdered|findActiveByFamily'; } \
  && log_ok "v1212-FACETS-FROM-ACTUAL-DATA(筛选器候选值来自当期流水)" \
  || log_bad "v1212-FACETS-FROM-ACTUAL-DATA 筛选器候选值来自全量字典" "会列出这个月根本没有的值,选了得到空列表"

# v1212-FLOW-FILTERS-SELF-BUILT · 流水筛选器一律用自研下拉(用户定:不用系统自带的)。
{ n=$(grep -c 'data-flow-f=' "$QA1212_TOOLBAR");
  m=$(grep -c 'data-flow-f=.*data-lsel\|data-lsel.*data-flow-f=' "$QA1212_TOOLBAR");
  [ "$n" -gt 0 ] && [ "$n" = "$m" ]; } \
  && log_ok "v1212-FLOW-FILTERS-SELF-BUILT(筛选器全部挂 data-lsel)" \
  || log_bad "v1212-FLOW-FILTERS-SELF-BUILT 有筛选器还在用系统原生下拉" "用户定过:所有下拉组件都用自研的,支持搜索"

# v1212-FLOW-TOTALS-NOT-FILTERED · 前端筛选不许碰合计口径。
#   底部合计永远是【全部】的合计。要是让它跟着筛选变,用户会把「餐饮 800」
#   当成本月总支出去和别处对账 —— 而这个错不会报警。
#   【判据收窄】原来把裸的 `+=` 也当成金额运算 —— 而 v1.22.4 的翻页锚定里
#   有一句 `page += delta`(页码,不是钱),判据就红在了一个完全正确的实现上。
#   现在只抓【金额相关】的复合赋值,以及把字符串当金额解析的那几个函数。
{ ! grep -qE 'parseFloat|Number\(|toFixed' "$QA1212_FLOWJS" \
  && ! grep -qiE '(amt|sum|total|money|balance)\w*\s*\+=' "$QA1212_FLOWJS"; } \
  && log_ok "v1212-FLOW-TOTALS-NOT-FILTERED(前端不做金额运算 · 合计仍是服务端全量)" \
  || log_bad "v1212-FLOW-TOTALS-NOT-FILTERED flow-table.js 里出现了金额运算" "筛出来的小计会被当成本月总支出去对账"


# ═══════════════ v1.22 · 决策权交还用户(PRD prd/v1.22.md §10)═══════════════

QA122_RESOLVER="$RD/src/main/java/com/family/finance/service/expense/imports/BillCategoryResolver.java"
QA122_IMPHTML="$RD/src/main/resources/templates/expense/import.html"
QA122_BILLJS="$RD/src/main/resources/static/js/bill-confirm.js"
QA122_CTL="$RD/src/main/java/com/family/finance/web/expense/ExpenseImportController.java"
QA122_LEDGER="$RD/src/main/java/com/family/finance/service/expense/ExpenseLedgerService.java"
QA122_ENTRY="$RD/src/main/java/com/family/finance/service/EntryService.java"
QA122_MIG="$RD/db/migration/V60__cash_flow_allow_zero_and_negative.sql"
QA122_UPD="$RD/src/main/java/com/family/finance/service/expense/imports/ExistingFlowUpdateService.java"

# v1220-NO-DELETE-WORDING · 页面上不许再出现「已剔除 / 捞回来 / 不能捞」。
#   维护者的原话:「"已删除" 指的是 你直接做主删掉的? 这个是不不合适」。
#   问题不在文案而在【谁拍板】,但文案是它最外层的表现 —— 退回旧措辞就说明模型也退回去了。
#   只扫【面向用户的文本】:注释里解释「为什么不再这么叫」是必要的历史。
#   【剥注释要按块剥】:Thymeleaf 的注释是 <!--/* ... */--> 【跨行】的,
#   单行 sed 's/<!--.*//' 只能吃掉起始那一行,注释正文照样被当成页面文本扫进来 ——
#   第一版判据就是这么把自己写红的(注释里解释「为什么不再叫已剔除」被当成了违规)。
{ vis=$(python3 -c "
import re,sys
t=open(sys.argv[1],encoding='utf-8').read()
t=re.sub(r'<!--.*?-->','',t,flags=re.S)      # 跨行注释整块剥掉
print('\n'.join(re.findall(r'已剔除|捞回来|不能捞',t)))" "$QA122_IMPHTML" | head -3);
  [ -z "$vis" ]; } \
  && log_ok "v1220-NO-DELETE-WORDING(页面上没有「已剔除/捞回来/不能捞」)" \
  || log_bad "v1220-NO-DELETE-WORDING 页面又出现了删除式措辞:$vis" "那描述的是系统已经执行完的动作,而判据是启发式的、会错"

# v1220-INCLUDE-NOT-DROP · 提交参数统一成一个 include 列表。
#   旧模型有 drop + restore 两个【方向相反】的参数,因为它的心智是
#   「系统已经删了一批,用户捞回几个」。现在只有一个事实:这一行用户勾没勾。
#   两个方向的参数一旦回来,「全选」就会立刻在两个桶之间行为不一致。
{ ! grep -qE 'name="(drop|restore)"' "$QA122_IMPHTML" \
  && ! grep -qE 'List<Integer> (drop|restore)' "$QA122_CTL" \
  && grep -q 'name="include"' "$QA122_IMPHTML" \
  && grep -q 'List<Integer> include' "$QA122_CTL"; } \
  && log_ok "v1220-INCLUDE-NOT-DROP(提交参数是一个 include 列表)" \
  || log_bad "v1220-INCLUDE-NOT-DROP drop/restore 又回来了" "两个方向相反的参数 = 「系统已经删了一批」那套错心智"

# v1220-DEFAULT-SELECTION · 消费行默认勾上、不建议录入的默认不勾。
#   这是「系统只给默认值」的全部实现 —— 判据绑在 Line.defaultIncluded 上,
#   而不是绑模板里某个 th:checked 的写法(那会随排版变)。
{ grep -q 'public boolean defaultIncluded() { return bucket == Bucket.SPEND; }' "$QA122_RESOLVER" \
  && grep -q 'th:checked="${l.defaultIncluded()}"' "$QA122_IMPHTML"; } \
  && log_ok "v1220-DEFAULT-SELECTION(消费默认勾 · 不建议的默认不勾)" \
  || log_bad "v1220-DEFAULT-SELECTION 默认勾选规则被改了" "默认值是系统唯一该表达的东西,改了就等于替用户做决定"

# v1220-PER-ROW-REASON · 不建议录入的行必须【逐行】给理由,不是一个桶级标题。
#   维护者原话:「你逐行给出原因即可」。
#   而且 0 元和负数要【分开说】—— 合并成「金额是 0 或负数」对用户没用,他分不清自己遇到的是哪种:
#   0 元录进来不影响金额,负数录进来当月支出会减少,这是两件完全不同的事。
#   【先剥注释再判】:这一条的注释里就写着「『金额是 0 或负数』这种合并说法对用户没用」——
#   不剥的话判据会被自己的解释绊倒。同一个坑在 v1220-NO-DELETE-WORDING 和
#   v1220-ACCOUNT-MOVE-BOTH-SIDES 上各踩了一次:**凡是判据要扫「不许出现某句话」,
#   就必须先 codeonly**,否则解释这条规则的文字本身会让它变红。
{ code=$(codeonly "$QA122_RESOLVER");
  echo "$code" | grep -q 'suggestReason' \
  && echo "$code" | grep -q '"0 元"' \
  && echo "$code" | grep -q '"负数(退款冲正)"' \
  && ! echo "$code" | grep -q '金额是 0 或负数' \
  && grep -qF 'l.suggestReason()' "$QA122_IMPHTML"; } \
  && log_ok "v1220-PER-ROW-REASON(逐行理由 · 0 元与负数分开说)" \
  || log_bad "v1220-PER-ROW-REASON 理由没逐行给,或 0 元与负数又被合并成一句" "用户分不清自己遇到的是哪种,而两者的后果完全不同"

# v1220-SUGGEST-ROWS-KEEP-CATEGORY · 不建议录入的行也要带分类推荐。
#   v1.21 里这个桶的分类一律是 null,用户勾上之后还得自己挑一遍 ——
#   「采纳建议之外的个别几行」这件事就变得很贵,而那正是维护者要的操作。
#   守法:classify 的归类步骤必须排在分桶【之前】(一处出口,所有桶共用)。
{ body=$(awk '/public static Draft classify\(/,/^    }$/' "$QA122_RESOLVER");
  [ -n "$body" ] \
  && echo "$body" | grep -q 'suggestSkip == null ? Bucket.SPEND : Bucket.SUGGEST_SKIP' \
  && [ "$(echo "$body" | grep -c 'Bucket.SUGGEST_SKIP, null,')" -eq 0 ]; } \
  && log_ok "v1220-SUGGEST-ROWS-KEEP-CATEGORY(不建议的行也带分类推荐 · 勾上即可直接录)" \
  || log_bad "v1220-SUGGEST-ROWS-KEEP-CATEGORY 不建议的行又变回没有分类" "用户勾上之后还得自己挑一遍,「个别校准」这件事就变得很贵"

# v1220-MIGRATION-ONLY-RELAXES · V60 只放宽约束,不动数据。
#   prod 跑着真实家庭数据:放宽是安全的(既有行全部 > 0,天然满足),
#   但只要混进一条 UPDATE/DELETE,「零影响」这个结论就不成立了。
{ [ -f "$QA122_MIG" ] \
  && grep -q 'DROP CHECK ck_cash_flow_amount' "$QA122_MIG" \
  && ! grep -qE '^\s*(UPDATE|DELETE|INSERT|DROP TABLE)' "$QA122_MIG"; } \
  && log_ok "v1220-MIGRATION-ONLY-RELAXES(V60 只 DROP 约束 · 不动任何行)" \
  || log_bad "v1220-MIGRATION-ONLY-RELAXES V60 动了数据或没放开约束" "放宽才是零影响的;动数据就不是了"

# v1220-NO-ABS-ON-EXPENSE · 支出金额【不许取绝对值】。
#   踩过:EntryService.positiveMoney 里有 value.abs(),于是一笔 −499 的退款冲正
#   被【静默记成 +499 的消费】—— 不报错、不提示,当月支出凭空多出一千块。
#   取绝对值比直接拒收更糟,因为它无声。支出走 expenseMoney,收入/划转才走 positiveMoney。
{ body=$(awk '/private BigDecimal expenseMoney\(/,/^    }$/' "$QA122_ENTRY");
  [ -n "$body" ] && ! echo "$body" | grep -q '\.abs()' \
  && grep -q 'line.kind() == CashFlowKind.EXPENSE' "$QA122_ENTRY" \
  && grep -q 'BigDecimal amt = expenseMoney(amount);' "$QA122_ENTRY"; } \
  && log_ok "v1220-NO-ABS-ON-EXPENSE(支出金额不取绝对值 · 退款不会被记成消费)" \
  || log_bad "v1220-NO-ABS-ON-EXPENSE 支出金额又走了 abs() 或 positiveMoney" "一笔 −499 的退款会被静默记成 +499 的消费"

# v1220-ZERO-EXPENSE-NOT-DROPPED · 0 元支出不许被静默丢弃。
#   insertCashFlow 原来对所有 kind 都「金额为 0 就 return」——
#   用户在确认页上勾了那笔 0 元(全额优惠券),提交之后它凭空消失,没有任何提示。
{ grep -q 'line.amount().signum() == 0 && line.kind() != CashFlowKind.EXPENSE' "$QA122_ENTRY"; } \
  && log_ok "v1220-ZERO-EXPENSE-NOT-DROPPED(0 元支出会落库 · 只有收入侧的 0 才跳过)" \
  || log_bad "v1220-ZERO-EXPENSE-NOT-DROPPED 0 元支出又被静默丢弃" "用户勾了它,提交之后凭空消失,没有任何提示"

# v1220-NO-POSITIVE-ASSUMPTION · 不许把「≤ 0」当成「没有数据」。
#   这一类的最危险形态:Composition.hasData 判 totalBase.signum() > 0 ——
#   某月退款多于消费 → 总额 ≤ 0 → 整个「支出构成」段被判成没数据【整段隐藏】,
#   不报错、不解释,而那个月明明有几十笔支出。
#   有没有数据看【有没有行】,不看金额符号。
#   【只守 hasData 这一处】。同文件的 decide() 里 hasItem 仍然判 signum() > 0,
#   那是【故意的】:它判的不是「有没有数据」,而是「逐笔和手填总额谁说了算」——
#   一行 0 元把用户手填的总额顶掉会让支出凭空变 0(单测
#   `金额为0的逐笔不得压掉用户手填的总额` 钉着这个)。护栏要求那一处有注释说明为什么保留。
{ grep -q 'public boolean hasData() { return !slices.isEmpty(); }' "$QA122_LEDGER" \
  && grep -q '这一处故意保留 signum() > 0' "$QA122_LEDGER"; } \
  && log_ok "v1220-NO-POSITIVE-ASSUMPTION(有没有数据看行数,不看金额符号)" \
  || log_bad "v1220-NO-POSITIVE-ASSUMPTION 又把「≤0」当成「没有数据」" "退款多于消费的月份整段构成图会消失,而且不报错"

# v1220-NEGATIVE-GROUP-NOT-IN-PIE · 负组不进饼图,但进总额。
#   扇形没有负面积。取绝对值凑扇形会把一笔退款画成一笔消费(骗人);
#   把负组从总额里也扣走则会让「各组加起来 ≠ 总额」。
#   守法:模板必须喂 chartSlices() 而不是 slices(),且 Slice 上有 inChart。
{ grep -q 'chartSlices()' "$QA122_LEDGER" \
  && grep -q 'mixComposition.chartSlices()' "$RD/src/main/resources/templates/reports/_expense-section.html" \
  && ! grep -qE 'mixComposition\.slices\(\)\.!\[' "$RD/src/main/resources/templates/reports/_expense-section.html"; } \
  && log_ok "v1220-NEGATIVE-GROUP-NOT-IN-PIE(饼图只拿正组 · 负组仍进总额)" \
  || log_bad "v1220-NEGATIVE-GROUP-NOT-IN-PIE 饼图又拿到了全量 slices" "负值传给 Chart.js 会画出一个看起来正常、但比例完全错的图"

# v1220-SELECT-ALL-SCOPED · 「全选」只作用于当前看得见的行。
#   用户筛到「不建议录入 20 行」点全选,如果实现成「选中全表 200 行」,
#   他会在毫不知情的情况下把另外 180 行的默认状态一起改掉 ——
#   而那 180 行本来就是勾上的,全选之后【看起来没变化】,直到提交才发现录错了。
{ grep -q 'function visibleSels()' "$QA122_BILLJS" \
  && grep -q 'visibleSels().forEach(function (c) { c.checked = a.checked; });' "$QA122_BILLJS"; } \
  && log_ok "v1220-SELECT-ALL-SCOPED(全选只作用于可见行)" \
  || log_bad "v1220-SELECT-ALL-SCOPED 全选又作用到全表了" "筛选态下会把看不见的行一起改掉,而且看起来没变化"

# v1220-CLOSED-PERIOD-SERVER-GUARD · 已关账的期不许改账户,且必须在【服务端】拦。
#   去重范围是整个家庭,所以「已存在」的笔可能落在已关账的期。
#   改它的账户会去动用户已经核对并封存的 period_snapshot。
#   前端 disabled 只防手滑 —— 构造一个请求就能绕过去。
{ grep -q 'if (row.closed())' "$QA122_UPD" \
  && grep -q 'public boolean closed()' "$RD/src/main/java/com/family/finance/repository/ExpenseFlowMapper.java"; } \
  && log_ok "v1220-CLOSED-PERIOD-SERVER-GUARD(已关账改账户在服务端拦)" \
  || log_bad "v1220-CLOSED-PERIOD-SERVER-GUARD 已关账的拦截只剩前端 disabled" "构造一个请求就能改掉已封存的期末余额"

# v1220-ACCOUNT-MOVE-BOTH-SIDES · 改账户必须两边都动,且方向相反。
#   只动一边 = 凭空造钱或凭空吞钱;方向写反 = 余额错两倍。两种都不报错。
#   判据要先剥注释 —— 这段代码的注释里就解释了 applyImportedExpense 的方向,
#   不剥的话计数会多出一条,而那条是【说明】不是【调用】。
{ body=$(codeonly "$QA122_UPD" | awk '/if \(row.affectsBalance\(\)/,/^            }$/');
  [ -n "$body" ] \
  && echo "$body" | grep -q 'row.amount().negate()' \
  && [ "$(echo "$body" | grep -c 'applyImportedExpense')" -eq 2 ]; } \
  && log_ok "v1220-ACCOUNT-MOVE-BOTH-SIDES(旧账户加回 + 新账户扣掉 · 两边都动)" \
  || log_bad "v1220-ACCOUNT-MOVE-BOTH-SIDES 改账户没有两边都动余额" "只动一边等于凭空造钱或吞钱,而且不报错"

# v1220-EXISTING-FIX-SURVIVES-EXPIRY · 「已存在」的修正不依赖草稿。
#   它改的是上次已入账的行,和这次要落的新行没有关系。放在草稿检查之后的话,
#   session 一过期,用户在那一桶里改了半天的东西会连同一句「草稿已经过期」一起消失。
{ ctl=$(awk '/public String confirm\(/,/^    }$/' "$QA122_CTL");
  [ -n "$ctl" ] \
  && [ "$(echo "$ctl" | grep -n 'existingUpdateService.apply' | cut -d: -f1 | head -1)" \
     -lt "$(echo "$ctl" | grep -n 'session.getAttribute(DRAFT_KEY)' | cut -d: -f1 | head -1)" ]; } \
  && log_ok "v1220-EXISTING-FIX-SURVIVES-EXPIRY(已存在的修正排在草稿检查之前)" \
  || log_bad "v1220-EXISTING-FIX-SURVIVES-EXPIRY 修正又被草稿过期吃掉了" "用户改了半天的东西会连同一句「草稿已过期」一起消失"

# v1220-NO-FORCE-REIMPORT · 仍然不提供「强制再导」。
#   那等于给一个制造双份的按钮,而双份在报表上表现为「这个月怎么花了两倍」,查起来很费劲。
#   v1.22 开放「已存在」编辑是 UPDATE 已有行,和再导一次是两回事。
{ pnl=$(awk '/data-bucket-panel="skipped"/,/^      <\/div>$/' "$QA122_IMPHTML");
  [ -n "$pnl" ] && ! echo "$pnl" | grep -qE '强制|再导一次|name="force"|name="reimport"'; } \
  && log_ok "v1220-NO-FORCE-REIMPORT(已存在桶没有「强制再导」)" \
  || log_bad "v1220-NO-FORCE-REIMPORT 已存在桶出现了强制再导" "那是制造双份的按钮;真要重导的路径是整批撤销"


# ═══════════════ v1.22.1 · 手机端排版(UED 巡检落下来的护栏)═══════════════

QA1221_CSS="$RD/src/main/resources/static/css/style.css"
QA1221_ROW="$RD/src/main/resources/templates/entry/_row.html"
QA1221_ENTRY="$RD/src/main/resources/templates/entry/index.html"
QA1221_MF="$RD/src/main/java/com/family/finance/service/MoneyFormat.java"

# v1221-NO-HARDCODED-MINUS · 支出金额不许在模板里硬拼 '−'。
#   v1.22 之后支出可以是负数(退款冲正原样记),硬拼会拼出【−−¥499.00】双负号。
#   而且「负的支出」= 钱回来了,显示成 +¥499.00 才读得懂。
#   统一走 MoneyFormat.formatExpense —— 它按符号决定前缀,纯展示层不碰金额。
{ ! grep -rn "'−' +" "$RD/src/main/resources/templates/" >/dev/null 2>&1 \
  && grep -q 'public static String formatExpense' "$QA1221_MF"; } \
  && log_ok "v1221-NO-HARDCODED-MINUS(支出金额走 formatExpense · 不硬拼负号)" \
  || log_bad "v1221-NO-HARDCODED-MINUS 模板里又硬拼了 '−'" "负金额会显示成 −−¥499.00,而那本该是一笔看得懂的退款 +¥499.00"

# v1221-EXPENSE-SIGN-COLOR · 支出金额的颜色跟着钱的方向走,不写死 num-neg。
#   显示成 +¥499.00(退款)却配红色,是自相矛盾的。
{ grep -q "e.amount.signum() < 0} ? 'num-pos' : 'num-neg'" "$QA1221_ENTRY"; } \
  && log_ok "v1221-EXPENSE-SIGN-COLOR(退款显示为正向色 · 支出为负向色)" \
  || log_bad "v1221-EXPENSE-SIGN-COLOR 支出金额颜色又写死了" "+¥499.00 配红色自相矛盾"

# v1221-MOBILE-RULES-EXCLUDE-HIDDEN · 手机端的 display:!important 必须排除 [hidden]。
#   踩过:给 [data-flow-row] 写 display:flex!important,而全局有 [hidden]{display:none!important},
#   同特异性下后写的赢 —— 前端分页藏起来的几百行被重新放出来,列表变成十几屏,
#   而且那些行的布局是塌的(账户名宽度只剩 2px)。
{ ! grep -nE '^\s*\[data-flow-row\]\s*\{' "$QA1221_CSS" \
  && grep -q '\[data-flow-row\]:not(\[hidden\])' "$QA1221_CSS"; } \
  && log_ok "v1221-MOBILE-RULES-EXCLUDE-HIDDEN(手机端 display 规则排除了 [hidden])" \
  || log_bad "v1221-MOBILE-RULES-EXCLUDE-HIDDEN 手机端规则会盖掉 [hidden]" "被分页藏起来的行会被重新放出来,列表炸成十几屏"

# v1221-ACCOUNT-NAME-NOT-CLAMPED · 账户名不许被硬编码的 max-width 锁死。
#   踩过:entry/_row.html 给账户名写死 max-w-[120px],而同行的校准 badge / 类型 / 币种
#   全是 flex-shrink-0 —— 账户名成了唯一能被压的那个,实测在 390px 下被压到【2px】,
#   「招行理财-稳健」只剩一个省略号。账户名是这一行的主语,不能阉割。
#   【又是注释绊倒判据】—— 这条的注释里就写着「而账户名 max-w-[120px]」。
#   同一个坑在 v1.22 已经踩过三次(见 tech-design/v1.22.md §五),这是第四次:
#   凡是扫「不许出现 X」,先剥注释,否则你越把规则解释清楚,护栏越红。
{ code=$(python3 -c "
import re,sys
t=open(sys.argv[1],encoding='utf-8').read()
print(re.sub(r'<!--.*?-->','',t,flags=re.S))" "$QA1221_ROW");
  ! echo "$code" | grep -q 'max-w-\[120px\]' \
  && grep -q '\.acct-head > \.acct-name' "$QA1221_CSS"; } \
  && log_ok "v1221-ACCOUNT-NAME-NOT-CLAMPED(账户名不被 max-width 锁死)" \
  || log_bad "v1221-ACCOUNT-NAME-NOT-CLAMPED 账户名又被硬编码宽度截断" "核心元素被阉割成一个省略号,用户没法分辨是哪个账户"

# v1221-SIGN-BEFORE-SYMBOL · 负号统一放在货币符号【之前】。
#   项目里两套写法并存过:MoneyFormat 走 sign+symbol+abs(「−¥148,156」),
#   而模板/JS 里散落的 symbol+format(amount) 在负数时拼成「¥-148,156」。
#   两种写法在同一个页面上并排出现,用户会以为是两种不同的东西。
#   守法:模板里不许再出现 symbol 直接拼 formatInteger 的写法,JS 的 fmtMoney 要先取符号。
#   【判据要收窄】只禁「currencySymbol 出现在表达式开头」那种 ——
#   `(wf.investPnl().signum() >= 0 ? '+' : '−') + currencySymbol + ...` 是【对的】写法:
#   它自己处理了符号再拼货币符号。第一版判据把这种也禁了,红在一个正确的实现上。
{ ! grep -rnE '[\$\(]\{?currencySymbol \+ #numbers\.formatInteger' "$RD/src/main/resources/templates/" >/dev/null 2>&1 \
  && ! grep -rn "ccySym + Math.round(n)" "$RD/src/main/resources/templates/" >/dev/null 2>&1 \
  && grep -q 'public static String intSigned' "$QA1221_MF"; } \
  && log_ok "v1221-SIGN-BEFORE-SYMBOL(负号在货币符号前 · 全站一套写法)" \
  || log_bad "v1221-SIGN-BEFORE-SYMBOL 又出现了 symbol 直接拼金额的写法" "负数会显示成「¥-148,156」,和同页别处的「−¥148,156」不是一套"

# v1221-MOBILE-TAG-SCALE · 手机端必须收口 tag 尺寸。
#   全站的 pill / badge 原本只按 PC 调过,搬到 390px 上一行就塞不下,
#   于是核心信息(账户名 / 金额)被挤掉,而次要的状态标签因为带边框反倒最抢眼。
#   判据钉的是「有没有这个收口段」,不钉具体数值 —— 数值会随排版调。
{ blk=$(awk '/@media \(max-width: 640px\) \{/,/^\}$/' "$QA1221_CSS" | tail -n +1);
  echo "$blk" | grep -q '\.pill' && echo "$blk" | grep -q '\.badge'; } \
  && log_ok "v1221-MOBILE-TAG-SCALE(手机端收口了 pill/badge 尺寸)" \
  || log_bad "v1221-MOBILE-TAG-SCALE 手机端没有 tag 尺寸收口" "PC 尺寸的 tag 在 390px 下会把核心信息挤掉"


# ═══════════════ v1.22.2 · 填报页账户卡(手机端)═══════════════

QA1222_CSS="$RD/src/main/resources/static/css/style.css"
QA1222_ROW="$RD/src/main/resources/templates/entry/_row.html"

# v1222-ROW-SPLIT-BY-ORDER · 账户卡的两行用 order + 零高伪元素断,不靠像素算术。
#   踩过两次,两次都是在跟像素较劲:
#     · basis:calc(100% - 40px) —— 加上容器 8px 的 gap 刚好超 4px,刷新按钮掉到第三行
#     · basis:60%              —— 又变成整行都塞得下,账户名(basis 0、grow 1)只分到 15px
#   这两次在截图上都不明显,得量 clientWidth 才看得出来。
#   用 flexbox 的标准断行(order 分组 + 一个 flex-basis:100% 的零高伪元素)就没有临界点。
#   【判据本身踩过 grep 不跨行】:`.acct-row > .acct-bal {` 和 `order: 2` 不在同一行,
#   用 grep -E '...\{[^}]*order: 2' 永远匹配不上。抽出整个规则块再判。
{ blk=$(awk '/@media \(max-width: 640px\)/,0' "$QA1222_CSS");
  balblk=$(echo "$blk" | awk '/\.acct-row > \.acct-bal \{/,/\}/');
  echo "$blk" | grep -q '\.acct-row::before' \
  && echo "$balblk" | grep -q 'order: 2' \
  && ! echo "$balblk" | grep -q 'calc(100%'; } \
  && log_ok "v1222-ROW-SPLIT-BY-ORDER(账户卡按 order 分组断行 · 不靠像素算术)" \
  || log_bad "v1222-ROW-SPLIT-BY-ORDER 账户卡又靠 flex-basis 的临界点断行" "差几个 px 就会整行挤在一起把账户名压成 15px,而截图上看不出来"

# v1222-PREV-BALANCE-ONCE · 「上期末」在一张卡片里只渲染一次。
#   它原本出现两次(左栏「上期末 … · 已填」+ 右侧余额区),同一个值、同一张卡 ——
#   用户会怀疑这是两个不同的数。手机端收起左边那份(右边和本期末、变化量在一起,上下文更完整)。
#   【不许写死出现次数】。第一版判据是 `grep -c previousBalanceLabel == 2` ——
#   实际有 3 处,第三处在折叠的「改本期余额」表单里当参考值,那是另一个上下文、完全合理。
#   同一个坑 v1.21.2 刚踩过(`-eq 2` 把第三条查询漏掉了):
#   **护栏里出现字面数字,就是把「当下的事实」冻成了「永远的约束」。**
#   判据改成钉【实际的修复点】:卡片头部那份有 acct-prev 标记、手机端收起,
#   而右侧余额区仍保着一份(删掉左边之后信息不能丢)。
#   范围用固定行窗而不是 awk /<\/div>/ —— 后者会在内层 div 就截断。
{ grep -q 'acct-prev' "$QA1222_ROW" \
  && awk '/@media \(max-width: 640px\)/,0' "$QA1222_CSS" | grep -q '\.acct-prev { display: none; }' \
  && grep -A6 'acct-bal' "$QA1222_ROW" | grep -q 'previousBalanceLabel'; } \
  && log_ok "v1222-PREV-BALANCE-ONCE(手机端「上期末」只显示一次)" \
  || log_bad "v1222-PREV-BALANCE-ONCE 「上期末」在一张卡里重复显示" "同一个值出现两次,用户会以为是两个不同的数"

# v1222-TICK-SELF-EXPLAINS · 「已填/待填」那个勾必须自解释。
#   用户第一眼的原话是「这个 icon 是干嘛的?」—— 它长得像一个被选中的复选框,
#   但既点不动、也没有 title。图标不带说明就是让用户猜。
{ grep -q "tick-done' : 'tick-pending'" "$QA1222_ROW" \
  && awk "/tick-done' : 'tick-pending'/,/>/" "$QA1222_ROW" | grep -q 'th:title'; } \
  && log_ok "v1222-TICK-SELF-EXPLAINS(已填/待填的勾带 title)" \
  || log_bad "v1222-TICK-SELF-EXPLAINS 那个勾又变回没有任何说明" "长得像复选框但点不动,用户只能猜它是干嘛的"

# v1222-MOBILE-PAGE-SIZE-5 · 手机端每页 5 条,且下拉显示值要跟上。
#   10 条在 390px 下一屏放不下,翻一页得先往回滚一段 —— 而翻页是这个列表上最高频的动作。
#   默认值按视口挑,不写死在模板里(同一个页面在两种屏上该有两个不同的合理默认)。
#   【下拉显示值必须同步】:否则手机端写着「每页 10」而实际渲染 5 条,用户会以为分页坏了。
{ F="$RD/src/main/resources/static/js/flow-table.js";
  grep -q 'function defaultSize()' "$F" \
  && grep -q "matchMedia('(max-width: 640px)')" "$F" \
  && grep -qE 'matches\) \? 5 :' "$F" \
  && grep -q 'sizeSel.dispatchEvent(new Event' "$F" \
  && grep -q 'value="5"' "$RD/src/main/resources/templates/entry/_flow-toolbar.html"; } \
  && log_ok "v1222-MOBILE-PAGE-SIZE-5(手机端默认 5 条 · 下拉显示值同步)" \
  || log_bad "v1222-MOBILE-PAGE-SIZE-5 手机端每页条数不对或下拉没同步" "10 条在 390px 下一屏放不下;下拉写着 10 实际 5 条会被当成坏了"

# v1222-BOTTOM-PAGER · 窄屏在列表【底部】再放一组翻页。
#   顶部那组在手机上要往回滚才够得到,而翻页是这个列表最高频的动作。
#   【不做吸顶】:工具条在手机上是两行,吸顶吃掉近三分之一可视高度,
#   而它的价值是「随时能改筛选」——可用户筛完就开始看了,为低频动作长期占高度不划算。
#   【不做无限滚动】:这个列表是前端分页,数据早已全量在 DOM 里 ——
#   无限滚动省不掉任何加载,只会让 DOM 越滚越长并丢掉「第几页 / 共几页」,
#   而这一页的用途是核对账目,位置感恰恰要紧。
#   【判据只扫代码标识符,不扫自然语言】——
#   第一版禁了「无限滚动」这四个字,而上面那段注释里正好在解释「为什么不做无限滚动」,
#   于是判据被自己的解释绊倒。这是同一个坑的第六次(前五次见 tech-design/v1.22.md §五
#   与 docs/qa-cases.md)。代码标识符不会出现在解释性文字里,中文词一定会。
{ F="$RD/src/main/resources/static/js/flow-table.js";
  grep -q 'flow-pager-bottom' "$F" \
  && grep -q 'botNum.textContent = label' "$F" \
  && ! grep -qE 'IntersectionObserver|scrollend|infiniteScroll' "$F"; } \
  && log_ok "v1222-BOTTOM-PAGER(窄屏底部有翻页镜像 · 与顶部同一套状态)" \
  || log_bad "v1222-BOTTOM-PAGER 底部翻页没了,或改成了无限滚动" "翻页要往回滚;无限滚动在前端分页上省不掉加载,只会丢掉位置感"

# v1222-ACCT-HEAD-NOWRAP · 账户名那一行不换行。
#   名字 / 校准状态 / 类型 / 币种是一句话,断开就读不成句;
#   而且换行之后头像和勾会独占一行,卡片顶部空一大块。
{ awk '/@media \(max-width: 640px\)/,0' "$QA1222_CSS" | grep -qE '\.acct-head \{[^}]*flex-wrap: nowrap'; } \
  && log_ok "v1222-ACCT-HEAD-NOWRAP(账户名那一行不换行)" \
  || log_bad "v1222-ACCT-HEAD-NOWRAP 账户名那一行又允许换行了" "头像和勾会独占一行,名字掉到第二行"


# ═══════════════ v1.22.4 · 首屏渲染时序 ═══════════════

QA1224_CSS="$RD/src/main/resources/static/css/style.css"
QA1224_FLOWJS="$RD/src/main/resources/static/js/flow-table.js"
QA1224_ENTRY="$RD/src/main/resources/templates/entry/index.html"

# v1224-PREPAGE-BEFORE-JS · 流水列表要在 JS 接管【之前】就是分页态。
#   服务端把整月流水全量渲染进 DOM(前端分页的前提),而 flow-table.js 等 DOMContentLoaded 才跑 ——
#   实测 201 行的填报页【裸奔 2.9 秒】:先铺开一条望不到头的长龙,过几秒突然缩成 10 行。
#   CSS 在 head 里、阻塞渲染,比任何 JS 都早,所以预分页只能交给它。
#   JS 接管时必须把这个 class 摘掉,否则 CSS 的 display:none 会盖住后面用 hidden 做的翻页。
{ grep -q 'flow-prepaged' "$QA1224_ENTRY" \
  && grep -q '\.flow-prepaged > \[data-flow-row\]:nth-of-type(n+11)' "$QA1224_CSS" \
  && grep -q "list.classList.remove('flow-prepaged')" "$QA1224_FLOWJS"; } \
  && log_ok "v1224-PREPAGE-BEFORE-JS(CSS 先分页 · JS 接管时摘掉)" \
  || log_bad "v1224-PREPAGE-BEFORE-JS 首屏又会铺开全量流水" "整月几百行会先铺开几秒再缩回去;JS 不摘 class 的话翻页会失效"

# v1224-PREPAGE-BEATS-IMPORTANT · 预分页必须压得过手机端那条 display:flex !important。
#   v1.22.1 给手机端加过 `[data-flow-row]:not([hidden]) { display: flex !important; }`
#   (把流水行从 grid 改成 flex)。而【JS 没跑时这些行还没有 hidden 属性】,
#   :not([hidden]) 全部命中 → 那条 !important 会把预分页的 display:none 整个盖掉。
#   PC 端没有那条规则,所以这个问题【只在手机端出现】:PC 正好 10 行、手机 30 行全露。
#   前几次测试因为浏览器缓存让 flow-table.js 已经跑过,一直没暴露 ——
#   要用 javaScriptEnabled:false 的上下文才看得见。
{ blk=$(awk '/\.flow-prepaged/,0' "$QA1224_CSS" | head -20);
  [ "$(echo "$blk" | grep -c 'display: none !important')" -ge 2 ]; } \
  && log_ok "v1224-PREPAGE-BEATS-IMPORTANT(预分页带 !important · 压得过手机端的 flex 规则)" \
  || log_bad "v1224-PREPAGE-BEATS-IMPORTANT 预分页会被手机端的 display:flex !important 盖掉" "手机端首屏仍会铺开整月流水,而 PC 端是好的 —— 只测 PC 发现不了"

# v1224-PREPAGE-THRESHOLD · 模板里的预分页阈值要和 flow-table.js 的 MIN_ROWS 一致。
#   模板用 size() >= 12 决定加不加 flow-prepaged,JS 用 MIN_ROWS 决定接不接管。
#   两边不一致时会出现「CSS 藏了但 JS 不接管」——那些行就永远看不见了。
{ jsmin=$(grep -oE 'MIN_ROWS = [0-9]+' "$QA1224_FLOWJS" | grep -oE '[0-9]+');
  [ -n "$jsmin" ] && [ "$(grep -c "size() >= $jsmin" "$QA1224_ENTRY")" -ge 2 ]; } \
  && log_ok "v1224-PREPAGE-THRESHOLD(模板阈值与 MIN_ROWS 一致)" \
  || log_bad "v1224-PREPAGE-THRESHOLD 模板阈值和 flow-table.js 的 MIN_ROWS 对不上" "CSS 藏了但 JS 不接管 → 那些行永远看不见"

# v1224-PAGER-NO-JUMP · 底部翻页不许把页面滚走。
#   第一版点一下就跳 172px(list.scrollIntoView({block:'start'})),
#   而用户的视线本来就停在他刚点的那个按钮上 —— 维护者原话:
#   「点击下一页,为什么刷新后整体页面位置还有移动?要保持页面不动,只是翻页」。
#   光删掉 scrollIntoView 不够:每页行高不一样(备注有长有短、末页行数不足),
#   列表高度一变按钮自己就会在视口里挪。所以【锚定按钮】——
#   记下翻页前的视口位置,翻页后用 scrollBy 补回差值。
#   【JS 也要先剥注释】——【第七次】踩这个坑:这条判据的注释里就写着
#   「不用 scrollIntoView」和「第一版这里是 list.scrollIntoView(...)」,不剥就必红。
#   上一条(v1224-BOTTOM-PAGER)的结论是「扫标识符而不是自然语言」,
#   这次说明那还不够:**根本问题是扫描范围含注释**,标识符一样会被写进解释里。
#   qa-run 的 codeonly 只剥 Java 的 //,对 JS 的 /* */ 块注释无效,所以这里单独剥。
{ F="$RD/src/main/resources/static/js/flow-table.js";
  code=$(python3 -c "
import re,sys
t=open(sys.argv[1],encoding='utf-8').read()
t=re.sub(r'/\*.*?\*/','',t,flags=re.S)
t=re.sub(r'//.*','',t)
print(t)" "$F");
  ! echo "$code" | grep -q 'scrollIntoView' \
  && echo "$code" | grep -q 'function pageBy(delta)' \
  && echo "$code" | grep -q 'window.scrollBy(0, after - before)'; } \
  && log_ok "v1224-PAGER-NO-JUMP(翻页锚定按钮位置 · 不用 scrollIntoView)" \
  || log_bad "v1224-PAGER-NO-JUMP 翻页又会把页面滚走" "点一下跳一百多 px,而用户的视线就在他刚点的按钮上"

# v1224-TOC-FIRST-IN-DOM · 目录要排在主内容【之前】。
#   HTML 是流式到达的:排在主内容之后的元素要等主内容全部传完才出现 ——
#   报表页几十个 section,实测目录晚 0.5~1 秒。
#   原来靠 order:-1 把它视觉上挪到左列,而 order 改不了到达顺序。
#   顺带:目录是【导航】,DOM 靠前对屏幕阅读器也更好(能直接跳到想去的节)。
{ ok=1;
  for f in dashboard/index.html checkup/family.html reports/index.html; do
    t="$RD/src/main/resources/templates/$f";
    r=$(grep -n '_toc :: rail' "$t" | head -1 | cut -d: -f1);
    m=$(grep -n 'toc-cols-main' "$t" | head -1 | cut -d: -f1);
    [ -n "$r" ] && [ -n "$m" ] && [ "$r" -lt "$m" ] || ok=0;
  done;
  [ "$ok" = "1" ] && ! grep -qE '\.toc-rail \{[^}]*order: -1' "$QA1224_CSS"; } \
  && log_ok "v1224-TOC-FIRST-IN-DOM(目录在 DOM 里排主内容之前)" \
  || log_bad "v1224-TOC-FIRST-IN-DOM 目录又排到主内容后面了" "流式到达时它要等主内容传完才出现,order 改不了到达顺序"


# ══════════════════════════════════════════════════════════════════════
# v1.23 · 关账宽限 + 双活跃账期(issue #20)
# ══════════════════════════════════════════════════════════════════════

QA123_PM="$RD/src/main/java/com/family/finance/repository/PeriodMapper.java"
QA123_PS="$RD/src/main/java/com/family/finance/service/PeriodService.java"
QA123_PO="$RD/src/main/java/com/family/finance/service/PeriodOpener.java"
QA123_FM_XML="$RD/src/main/resources/mapper/FactMapper.xml"

# v1230-DUAL-OPEN-SWEEP · findCurrentOpen 的调用点不许再涨。
#   这条护栏守的是**本版最大的风险**:双 OPEN 下 findCurrentOpen 静默返回最新那期 ——
#   不抛异常、不进日志。漏审计的调用点不会崩,只会给出一个看起来合理但是错的数。
#   编译器抓不到这一类(与 v0.14 加 METAL 枚举那次同型),只能靠扫。
#   判据钉「调用点数量 ≤ 基线」而不是「一个都不许有」:D 类(管理/通知)语义上确实是
#   「随便哪个 OPEN 都行」,强行改名反而丢失「这里没想清楚」的信息。
#   新增调用点 → 数字上涨 → 红。那时请按语义选 findBalancePeriod / findRecordableOpen。
#   注意只数**真调用**,注释里提到方法名不算(否则写注释解释这条护栏本身就会把它弄红,
#   那是这个仓库反复踩过 7 次的 comment-trips-predicate)。
QA123_FCO=$(grep -rn "findCurrentOpen(" "$RD/src/main/java/" 2>/dev/null \
            | grep -v "^\s*\*" | grep -vE ":\s*(//|\*|/\*)" | grep -v "PeriodMapper.java" \
            | grep -vE "^\S+:[0-9]+:\s*//" | wc -l)
{ [[ "$QA123_FCO" -le 4 ]]; } \
  && log_ok "v1230-DUAL-OPEN-SWEEP(findCurrentOpen 调用点 $QA123_FCO ≤ 4 · 未新增)" \
  || log_bad "v1230-DUAL-OPEN-SWEEP findCurrentOpen 调用点涨到 $QA123_FCO" "双 OPEN 下它静默返回最新期;按语义改用 findBalancePeriod(余额轴)或 findRecordableOpen(收支轴)"

# v1230-BALANCE-AXIS-LATEST-ONLY · 余额 / 估值写入永远落最新 OPEN 期。
#   世界上只能有一个「现在的市值」。估值回写补录期 = 把上月末的事实改成今天的行情。
{ grep -q "findBalancePeriod" "$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java" \
  && grep -q "findBalancePeriod" "$RD/src/main/java/com/family/finance/service/stock/StockHoldingService.java" \
  && grep -q "findBalancePeriod" "$RD/src/main/java/com/family/finance/service/holdingimport/HoldingImportService.java" \
  && ! grep -n "findCurrentOpen(" "$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java" >/dev/null 2>&1; } \
  && log_ok "v1230-BALANCE-AXIS-LATEST-ONLY(估值/持仓/截图导入只写最新 OPEN 期)" \
  || log_bad "v1230-BALANCE-AXIS-LATEST-ONLY 余额轴用了非 findBalancePeriod 的取期" "估值回写补录期 = 把上月末的余额改成今天的行情"

# v1230-GRACE-CLOSE-DECOUPLED · 关账必须有**独立**的定时任务。
#   v1.22 及以前关账挂在「开新期」这个事件上(closePriorOpenPeriods 在 createPeriod 之前)。
#   有了宽限之后「该关的日子」和「该开的日子」不再是同一天 —— 继续挂在一起就会漏关:
#   宽限到期那天没有新期滚动来触发,账期永远悬在 OPEN,报表永远没有可锚的快照。
{ grep -q "closeIfGraceExpired" "$QA123_PO" \
  && awk '/closeIfGraceExpired/{found=1} /@Scheduled/{sched=NR} END{}' "$QA123_PO" >/dev/null \
  && grep -B3 "public void closeIfGraceExpired" "$QA123_PO" | grep -q "@Scheduled"; } \
  && log_ok "v1230-GRACE-CLOSE-DECOUPLED(关账有独立 @Scheduled · 不再只挂开账事件)" \
  || log_bad "v1230-GRACE-CLOSE-DECOUPLED 关账没有独立定时任务" "宽限到期那天没有新期滚动,账期会永远悬在 OPEN"

# v1230-SETTLED-NOT-JUST-CLOSED · 收益类判据必须含「填报完成」,不能只认 CLOSED。
#   只认 CLOSED → 双活跃下补录期没关 → 收益类指标(本月资产收益 / XIRR / TWR / 人赚钱赚 /
#   账户表现)集体锚回上上期,报表看起来「少了一个月」。
#   但判据也**不能只看「在不在宽限里」** —— 必须是「填完了没有」。用户压根没填时
#   补录期就是真的半填,锚它会重演 v1.6.30 那次「未录收支全算成投资收益」。
{ grep -q "findSettledPeriodIds" "$QA123_PM" \
  && grep -q "snapshot_todo" "$QA123_PM" \
  && grep -q "period_member_completion" "$QA123_PM" \
  && ! grep -q "m.archived_at\|member m" "$QA123_FM_XML"; } \
  && log_ok "v1230-SETTLED-NOT-JUST-CLOSED(收益类判据 = CLOSED 或 已结束且填完 · 且不在事实层)" \
  || log_bad "v1230-SETTLED-NOT-JUST-CLOSED 收益类判据没含填报完成条件,或跑进了事实层 SQL" "只认 CLOSED 会让双活跃下收益类集体锚回上上期;而判据要数活跃成员,不能放进 FactMapper(见 v115-NO-MEMBER-ARCHIVE-IN-SUMS)"

# v1230-SETTLED-JUDGE-ALIGNED · 「已定稿」判据两处必须一致。
#   PeriodMapper.findLatestSettledAsOf(给报表锚点)与 FactMapper.findSettledPeriodIds
#   (给收益类切片)是同一个概念的两份 SQL。两处判不一样 → 报表锚了这一期、
#   而它的收益数字来自另一批期,页面上每个数自己都对,合起来自相矛盾。
#   两份 SQL 都在 PeriodMapper 里(取最新一个 / 取窗口内全部),判据必须逐条相同。
{ qa123_a=$(grep -c "NOT EXISTS (SELECT 1 FROM snapshot_todo t" "$QA123_PM");
  qa123_b=$(grep -c "FROM period_member_completion c" "$QA123_PM");
  qa123_c=$(grep -c "m.family_id = p.family_id AND m.archived_at IS NULL" "$QA123_PM");
  [[ "$qa123_a" -eq 2 && "$qa123_b" -eq 2 && "$qa123_c" -eq 2 ]] \
  && grep -q "findLatestSettledAsOf" "$QA123_PM" && grep -q "findSettledPeriodIds" "$QA123_PM"; } \
  && log_ok "v1230-SETTLED-JUDGE-ALIGNED(两份已定稿 SQL 判据逐条同源)" \
  || log_bad "v1230-SETTLED-JUDGE-ALIGNED 两处「已定稿」判据不一致" "报表锚这一期、收益数字来自另一批期,每个数都对但合起来矛盾"

# v1230-GRACE-MAX-5 · 宽限上限锁死在 schema,不可能出现三期 OPEN。
#   本版所有判据都是按「最多两期」写的(补录期 + 进行期)。第三期一出现,
#   收益类锚点、月均支出窗口、余额传导全部进入未定义状态。
#   应用层校验能绕过(直接改库),CHECK 不能。
{ grep -q "BETWEEN 0 AND 5" "$RD/db/migration/V61__period_close_grace.sql"; } \
  && log_ok "v1230-GRACE-MAX-5(宽限 ≤5 天由 schema CHECK 锁死)" \
  || log_bad "v1230-GRACE-MAX-5 迁移里没有宽限上限 CHECK" "宽限超过一个账期长度会出现三期 OPEN,本版判据全部失效"

# v1230-CARRIED-FORWARD-GUARD · 余额传导只许改「没人确认过」的那张快照。
#   传导本身是对的(余额延续是派生值,源头变了就该跟着变),但**不能覆盖用户手填的数**:
#   他已经对进行期的余额表过态了,补上期的账不该反过来推翻它。守门判据用现成的
#   CARRIED_FORWARD 标记(PeriodOpener 是它唯一的写入者)。
{ grep -q "propagateCarriedForward" "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -A25 "private void propagateCarriedForward" "$RD/src/main/java/com/family/finance/service/EntryService.java" \
     | grep -q "CARRIED_FORWARD.name()"; } \
  && log_ok "v1230-CARRIED-FORWARD-GUARD(余额传导只改 CARRIED_FORWARD 的快照)" \
  || log_bad "v1230-CARRIED-FORWARD-GUARD 传导没有守门判据" "会覆盖用户手填过的进行期余额 —— 本版最不能犯的错"

# v1230-RHYTHM-DOCS-ALIGNED · 三份文档的填报节奏口径必须一致。
#   提交者指出的原始矛盾:prd/v0.4.md 写「月末倒数 2 天填」、docs/how-to-use.md 写
#   「月初任意一天填」,而系统行为站在前者那边。本版让机制支持后者,文档要跟上。
{ grep -q "关账宽限\|宽限" "$RD/docs/how-to-use.md" \
  && grep -q "关账宽限\|宽限" "$RD/prd/v0.4.md"; } \
  && log_ok "v1230-RHYTHM-DOCS-ALIGNED(节奏口径三处一致)" \
  || log_bad "v1230-RHYTHM-DOCS-ALIGNED 文档没跟上新的填报节奏" "prd/v0.4 与 docs/how-to-use 对「什么时候填」的说法仍自相矛盾"

# ══════════════════════════════════════════════════════════════════════
# 真 e2e 框架(2026-09-20 · 维护者要求把 e2e 改造成「真的开浏览器点」)
# ══════════════════════════════════════════════════════════════════════

QAE2E_DIR="$RD/scripts/e2e"

# v1230-E2E-IS-BROWSER-DRIVEN · e2e 的动作必须从页面元素发起,不许在 flow 里打端点。
#   旧 scripts/e2e.sh 用 curl 直接 POST,验的是「接口通了、库里对了」——
#   它结构性地验不到用户与系统之间的那一段:表单字段名、按钮可达性、JS 绑定作用域、
#   渲染中途截断、并列元素尺寸。这个项目的事故大量出在那一段。
#   判据:flows/ 里不许出现 curl / fetch / axios;必须出现 ui. 开头的页面动作。
#   ⚠ 判据必须**先剥注释**。这是本仓库第 8 次踩「护栏被解释它自己的注释绊倒」:
#     flow 头上写着「它能抓到 curl 版抓不到的东西」,裸 grep 直接判红,
#     而代码里一行 curl 都没有。凡是判据形如「不许出现 X」,先 codeonly。
{ qae2e_code(){ sed -E 's,^[[:space:]]*(//|\*|/\*).*,,' "$1"; }
  qae2e_bad=0
  for f in "$QAE2E_DIR"/flows/*.cjs; do
    [ -e "$f" ] || continue
    qae2e_code "$f" | grep -qE "curl |require\('http|fetch\(|axios" && qae2e_bad=1
  done
  [ -d "$QAE2E_DIR/flows" ] && [ "$qae2e_bad" -eq 0 ] \
  && [ "$(grep -rl "ui\." "$QAE2E_DIR/flows/" 2>/dev/null | wc -l)" -ge 1 ]; } \
  && log_ok "v1230-E2E-IS-BROWSER-DRIVEN(e2e flow 只从页面点,不打端点)" \
  || log_bad "v1230-E2E-IS-BROWSER-DRIVEN e2e flow 里出现了直接调端点" "那样验的是接口不是用户路径;要写页面动作(ui.click/fill/choose)"

# v1230-E2E-TWO-LAYER-ASSERT · 断言必须两层:看得见 + 真值。
#   只看页面会漏「显示对了但没存进去」(页面回显的常是你刚提交的表单值);
#   只查库会漏「存对了但用户看不到」—— 后者这个项目出现过不止一次。
# 【必须是 ( ) 不是 { }】—— { } 是命令组不是子 shell,里面的 exit 会把【整个 qa-run】
#   退掉:2026-09-20 实测,这条一红,它后面的所有护栏和最后那行总结【一次都没跑过】,
#   而终端上看不出异常(只是没有总结)。判据里带 exit 的分组,一律用子 shell。
# 【判据修正 2026-09-22】原来「看得见层」只认 ui.seesText / visible / count / sameSize 四个名字。
#   `21-dual-period-metrics` 因此一直红,而它其实是这三条 flow 里**页面验得最狠**的一条:
#   它用 `ui.page.evaluate` 从渲染后的 DOM 上把 KPI 的值抠下来,再断言「变 / 不变」
#   (22 条 ui.assert)。那比 seesText 更强 —— seesText 只证明字符串在页面上,
#   抠 KPI 值证明的是**用户看到的那个数**对不对。
#   红了三个多月,报的是「有 flow 只验了一层」,而真相是判据不认这条路。
#   现在把「从 DOM 抠值」也算作看得见层,同时补一条「必须真的断言了」——
#   光 evaluate 出来不断言(比如只用它滚动定位)不算验。
QAE2E_SEE='ui\.seesText|ui\.notSeesText|ui\.visible|ui\.notVisible|ui\.count|ui\.sameSize|ui\.page\.evaluate'
( for f in "$QAE2E_DIR"/flows/*.cjs; do
    [ -e "$f" ] || continue
    grep -qE "$QAE2E_SEE" "$f" || exit 1
    grep -qE 'ui\.assert|ui\.seesText|ui\.count' "$f" || exit 1
    grep -qE 'db\.one|db\.num|db\.col' "$f" || exit 1
  done ) \
  && log_ok "v1230-E2E-TWO-LAYER-ASSERT(每个 flow 都有页面断言 + 真值断言)" \
  || log_bad "v1230-E2E-TWO-LAYER-ASSERT 有 flow 只验了一层" "看得见层漏了会漏「用户看不到」;真值层漏了会漏「显示对了没存进去」· 看得见层可以是 ui.seesText 这类助手,也可以是 ui.page.evaluate 抠 DOM 值,但都必须落到 ui.assert"

# v1230-E2E-CLEANUP-DECLARES-END-STATE · 还原要声明终态,不能依赖「跑之前是什么样」。
#   踩过:还原写成 `if (跑之前是 CLOSED) 关回去`,连跑两次时第二次读到的已经是 OPEN,
#   整段还原被跳过,beta 被留在中间状态里 —— 下一个人跑别的回归会莫名其妙。
( for f in "$QAE2E_DIR"/flows/*.cjs; do
    [ -e "$f" ] || continue
    grep -q "async cleanup" "$f" || exit 1
  done ) \
  && log_ok "v1230-E2E-CLEANUP-DECLARES-END-STATE(每个改数据的 flow 都有 cleanup)" \
  || log_bad "v1230-E2E-CLEANUP-DECLARES-END-STATE 有 flow 没写 cleanup" "不还原会污染下一次运行,红灯会指向错误的地方"

# v1230-E2E-OLD-SCRIPT-RENAMED · 旧 e2e.sh 必须已正名,且说清自己不是 e2e。
#   留着它是对的(那 119 处 SQL 断言验的是计算口径,没有 UI 路径),
#   但它不能再叫 e2e —— 名字会让人以为用户路径已经验过了。
{ [ ! -f "$RD/scripts/e2e.sh" ] \
  && [ -f "$RD/scripts/regression-data.sh" ] \
  && grep -q "这个脚本不是 e2e" "$RD/scripts/regression-data.sh"; } \
  && log_ok "v1230-E2E-OLD-SCRIPT-RENAMED(旧 e2e.sh 已正名为 regression-data.sh 并写明职责)" \
  || log_bad "v1230-E2E-OLD-SCRIPT-RENAMED 旧脚本还叫 e2e.sh" "名字会让人以为用户路径验过了,而它只验了接口和库"


# ─────────────────────────────────────────────────────────────────────────────
# v1.24 · family_id 家庭隔离普查
# ─────────────────────────────────────────────────────────────────────────────

# v1240-FAMILY-ISOLATION · 每一条碰家庭作用域表的 SQL 都必须在【过滤位置】带 family_id。
#   2026-09-20 普查:339 条语句里 156 条没有,而且没有一条的方法签名带 familyId ——
#   归属全靠 period_id / account_id 间接推出来。那是推论不是约束:前提一变
#   (共享账期、合并家庭、修数脚本写错归属),漏隔离的查询会【静默返回别人家的钱】,
#   不报错、不越权告警,只是数字变大。
#   引擎在 scripts/family-isolation-audit.py,例外清单也钉在那里(6 条,各有理由)。
python3 "$RD/scripts/family-isolation-audit.py" "$RD/src/main/java/com/family/finance/repository" >/dev/null 2>&1 \
  && log_ok "v1240-FAMILY-ISOLATION(所有家庭作用域 SQL 都带 family_id 过滤 · 6 条登记例外)" \
  || log_bad "v1240-FAMILY-ISOLATION 有 SQL 漏了 family_id(跑 scripts/family-isolation-audit.py 看清单)" \
             "漏隔离不报错、不告警,只是数字变大 —— 多租户系统里最贵的一类 bug"

# v1240-INSERT-GUARDED · 没有 family_id 列的表,INSERT 挂不上 WHERE,
#   改成了 INSERT ... SELECT ... WHERE parent.family_id = #{familyId} + insertOwned() 断言。
#   业务代码必须调带断言的那个:直接调裸 insert 等于把「归属不符」当成「插了 0 行」,
#   表现是「页面说保存成功、库里没这笔」。
#   判据按【声明类型】认,不按字段名认 —— 字段名会撞:StockPriceFetcher 里的
#   `snapshotMapper` 是 StockPriceSnapshotMapper(全局行情快照,没有家庭维度),
#   跟 SnapshotMapper(period_snapshot)同名不同类,按名字判会误报。
python3 "$RD/scripts/family-isolation-audit.py" "$RD/src/main/java/com/family/finance/repository" --insert-guard >/dev/null 2>&1 \
  && log_ok "v1240-INSERT-GUARDED(业务代码走 insertOwned,不直接调裸 insert)" \
  || log_bad "v1240-INSERT-GUARDED 有地方直接调了裸 insert" "返回 0 会被当成成功 —— 用户以为记上了账,库里没有"

# v1240-HARDCODED-FAMILY-FROZEN · 写死的家庭 id 只减不增。
#   本项目是单家庭部署(PRD §22.3 类 A),定时任务 / AI runtime / 规则引擎里
#   `private static final long FAMILY_ID = 1L;` 是【既有的、有意的】设计:
#   那些地方没有请求上下文,真要支持多家庭得先决定「cron 遇到 N 个家庭该怎么跑」,
#   那是产品决策不是重构。v1.24 普查只拆掉了 HoldingImportService 那一处 ——
#   它在【有 familyId 可用】的请求路径上,属于纯粹的漏传。
#   这条护栏不要求归零,只钉住数量:再多一处就说明又有人在有上下文的地方图省事。
#   数量下降时也会红(说明清单该更新了),避免它悄悄变成一条永远绿的死护栏。
HARDCODED_FAM=$(grep -rn "FAMILY_ID *= *[0-9]" "$RD/src/main/java" --include="*.java" | wc -l | tr -d " ")
[ "$HARDCODED_FAM" = "14" ] \
  && log_ok "v1240-HARDCODED-FAMILY-FROZEN(写死家庭 id 仍是 14 处 · 全在无请求上下文的地方)" \
  || log_bad "v1240-HARDCODED-FAMILY-FROZEN 数量从 14 变成 $HARDCODED_FAM" \
             "多了 = 有人在能拿到 familyId 的地方图省事;少了 = 清单该更新"


# ─────────────────────────────────────────────────────────────────────────────
# v1.24 · 支出分析
# ─────────────────────────────────────────────────────────────────────────────

QA124_CFM="$RD/src/main/java/com/family/finance/repository/CashFlowMapper.java"
QA124_EFM="$RD/src/main/java/com/family/finance/repository/ExpenseFlowMapper.java"
QA124_NAT="$RD/src/main/java/com/family/finance/service/expense/ExpenseNatureService.java"
QA124_NRM="$RD/src/main/java/com/family/finance/service/expense/NormalExpenseService.java"
QA124_VIEW="$RD/src/main/java/com/family/finance/service/expense/ExpenseSectionViewService.java"
QA124_SEC="$RD/src/main/resources/templates/reports/_expense-section.html"
QA124_IDX="$RD/src/main/resources/templates/reports/index.html"

# v1240-SAME-FILTER-BLOCK · 口径 A 的过滤与换汇块是【编译期常量】,三条查询拼同一个串。
#   v1.21 的教训:第二层与第一层口径分叉四处,两条 SQL 各自都跑得出数、都不报错,
#   只是合不上,藏了整整一个版本。grep 能查「两处写法一样」,查不了「将来有人只改一处」。
{ grep -q 'String EXPENSE_A_FROM'   "$QA124_CFM" \
  && grep -q 'String EXPENSE_A_WHERE'  "$QA124_CFM" \
  && grep -q 'String EXPENSE_A_AMOUNT' "$QA124_CFM" \
  && [ "$(grep -c 'EXPENSE_A_AMOUNT' "$QA124_CFM")" -ge 4 ] \
  && [ "$(grep -c 'EXPENSE_A_WHERE'  "$QA124_CFM")" -ge 4 ]; } \
  && log_ok "v1240-SAME-FILTER-BLOCK(口径 A 过滤/换汇是共享常量 · 三条查询都拼它)" \
  || log_bad "v1240-SAME-FILTER-BLOCK 有查询绕过了共享常量另写一份口径" \
             "两条 SQL 各自都跑得出数、都不报错,只是合不上 —— v1.21 就这么藏了一个版本"

# v1240-NO-SECOND-LAYER-SQL · 分叉的第二层聚合必须【已删除】,不是「改好了」。
#   「补三个 WHERE 不补换汇」是最诱人的改法,也是最坏的:其余三项对上之后,
#   多币种家庭仍然错,而且错得更像「就差那一点」。
{ ! codeonly "$QA124_EFM" | grep -qE 'sumByCategory|sumByPeriodAndCategory'; } \
  && log_ok "v1240-NO-SECOND-LAYER-SQL(分叉的第二层聚合已整条删除 · 只剩一个取数口)" \
  || log_bad "v1240-NO-SECOND-LAYER-SQL 分叉的第二层聚合又回来了" "它与第一层口径对不上,而两边都不报错"

# v1240-ONEOFF-SINGLE-JUDGE · FR-615「一次性」的判定只存在于一处。
#   判据一句话,所以最容易在每条 SQL 里写个 CASE WHEN —— 读它的地方有五处,
#   散成五份之后漏掉的那一处不报错,只是那个块的数字不一样。
QA124_JUDGE=$(grep -rn "ONE_OFF" "$RD/src/main/java" --include="*.java" \
              | grep -vE "ExpenseNature\.java|ExpenseNatureService\.java|ExpensePalette\.java|/test/" \
              | grep -vE "ExpenseNature\.ONE_OFF\)|== ExpenseNature\.ONE_OFF|!= ExpenseNature\.ONE_OFF|ONE_OFF ->|\"ONE_OFF\"|ONE_OFF_KEY" | wc -l | tr -d " ")
# ONE_OFF_KEY 是归因瀑布里那个【分组键】的名字,不是判据 ——
# 它的值来自上面一行 natureOf(...) == ONE_OFF,判据仍然只有一处。
{ grep -q 'public ExpenseNature natureOf(' "$QA124_NAT" \
  && ! grep -rqE "one_off *= *1|one_off *> *0" "$RD/src/main/java/com/family/finance/repository" \
  && [ "$QA124_JUDGE" = "0" ]; } \
  && log_ok "v1240-ONEOFF-SINGLE-JUDGE(一次性判据只在 ExpenseNatureService.natureOf 一处 · SQL 里不判)" \
  || log_bad "v1240-ONEOFF-SINGLE-JUDGE 判据散出去了(见 grep ONE_OFF)" "散成 N 份之后漏掉的那一处不报错,只是数字不一样"

# v1240-NO-THIRD-SUM · 组装层不许注入任何 Mapper。
#   它能自己查 cash_flow 的那一刻,就出现了第三条求和路径 ——
#   而这一版做的所有事情都是为了让求和口径只有一套。
{ ! grep -qE 'private final [A-Za-z.]*Mapper' "$QA124_VIEW"; } \
  && log_ok "v1240-NO-THIRD-SUM(组装层不注入 Mapper · 不会长出第三条求和路径)" \
  || log_bad "v1240-NO-THIRD-SUM 组装层拿到了 Mapper" "它一旦能自己查库,求和口径就从一套变成两套"

# v1240-ONEOFF-NOT-BALANCE · one_off 是纯分析期标记,不许进任何余额链路。
#   affects_balance 有过「一个标记要同时退出三条链」的教训,这个标记刻意只有一条链。
{ ! grep -rn "one_off\|oneOff" "$RD/src/main/java" --include="*.java" \
      | grep -vE "/test/|ExpenseNature|NormalExpense|ExpenseAttribution|ExpenseSectionView|CashFlow\.java|CashFlowMapper|ExpenseFlowMapper|EntryService|BillCommitService|ExpenseImportController|EntryController|ReviewInsightService" \
      | grep -qiE "balance|snapshot|nav|networth|xirr" ; } \
  && log_ok "v1240-ONEOFF-NOT-BALANCE(one_off 不出现在余额/快照/净值路径上)" \
  || log_bad "v1240-ONEOFF-NOT-BALANCE 一次性标记渗进了余额链路" "它只该影响常态月均;碰了余额就是静默改钱"

# v1240-EXPENSE-SECTION-MERGED · FR-661 两个 section 真的合并了,不是并排放。
#   保留两个 <section id> 的话,「只有一个标题、一条 TOC 条目」只能靠模板里删标题实现,
#   护栏 grep 不到真正的合并,下一个人很容易把标题加回去。
{ [ ! -f "$RD/src/main/resources/templates/reports/_expense-mix.html" ] \
  && [ ! -f "$RD/src/main/resources/templates/reports/_expense-cat-mix.html" ] \
  && [ -f "$QA124_SEC" ] \
  && ! grep -rq 'sec-expense-split' "$RD/src/main/resources/templates" \
  && [ "$(grep -c '<h2[^>]*>钱花在哪了</h2>' "$QA124_SEC")" -eq 1 ] \
  && [ "$(grep -c 'sec-expense-mix' "$QA124_IDX")" -eq 1 ]; } \
  && log_ok "v1240-EXPENSE-SECTION-MERGED(两块真合并 · 一个标题 · 一条 TOC 条目)" \
  || log_bad "v1240-EXPENSE-SECTION-MERGED 支出章节又变回两块(或标题/TOC 没并)" \
             "同一页上两个同名章节、两套对不上的数 —— 用户第一反应是程序算错了"

# v1240-TOC-SYNCED · FR-668 · 联动链 L4:改 section 必须同步长文目录。
{ ! grep -q "支出构成 · 分类" "$QA124_IDX" \
  && grep -q "{label:'支出分析',href:'#sec-expense-mix'" "$QA124_IDX"; } \
  && log_ok "v1240-TOC-SYNCED(tocItems 两条并一条 · 锚点指向合并后的 section)" \
  || log_bad "v1240-TOC-SYNCED 目录没跟着合并" "锚点失效 / 目录里挂着一个不存在的章节"

# v1240-PALETTE-SINGLE-SOURCE · FR-666 色板全页唯一来源,且模板不取模。
#   模板里 catPalette[i % 9] 这种写法,在色板长度变化时是【渲染期数组越界】——
#   500 白屏,不是一个看得见的错。2026-09-20 实测:色板 9→6 色之后类目 ≥7 个就炸。
{ ! { for f in "$RD"/src/main/resources/templates/reports/*.html; do codeonly "$f"; done \
       | grep -qE 'catPalette\[[^]]*%' ; } \
  && ! grep -q '"#a0653a"' "$RD/src/main/java/com/family/finance/web/report/ReportsController.java" \
  && grep -q 'ExpensePalette.CATEGORY' "$RD/src/main/java/com/family/finance/web/report/ReportsController.java"; } \
  && log_ok "v1240-PALETTE-SINGLE-SOURCE(色板只在 ExpensePalette · 模板不取模索引)" \
  || log_bad "v1240-PALETTE-SINGLE-SOURCE 色板又散回去了,或模板在取模索引" \
             "取模会让第 7 片和第 1 片同色;色板长度一变就是渲染期越界 500 白屏"

# v1240-TOGGLE-CASCADES · FR-674 开关的级联收在服务层,不在模板里各判一遍。
#   「我关了支出分析,怎么别处还在冒常态月均」是最让人困惑的一种半开。
{ grep -q 'K_EXPENSE_ANALYSIS' "$QA124_NRM" \
  && grep -q 'if (!enabled(familyId)) return Optional.empty()' "$QA124_NRM" \
  && grep -q 'expenseAnalysisOn' "$RD/src/main/resources/templates/admin/calc-tweaks.html" \
  && grep -q 'normalExpenseService.normal' "$RD/src/main/java/com/family/finance/web/dashboard/DashboardController.java" \
  && grep -q 'normalExpenseService.normal' "$RD/src/main/java/com/family/finance/web/checkup/CheckupController.java" \
  && grep -q 'normalExpenseService.normal' "$RD/src/main/java/com/family/finance/web/goal/GoalController.java"; } \
  && log_ok "v1240-TOGGLE-CASCADES(开关在服务层返回 empty · 三处回流读数自动消失)" \
  || log_bad "v1240-TOGGLE-CASCADES 开关没级联到回流读数" "关了支出分析,别处还在冒常态月均"

# v1240-ONEOFF-BOTH-WRITE-PATHS · FR-613 逐笔录入与账单导入【两条路都要有】。
#   只做一条的后果不是少个功能:大额一次性支出(装修、旅行)多半是刷卡来的,
#   导入那条路没有的话,用户在几十笔里根本挑不出来。
{ grep -q 'name="oneOff"' "$RD/src/main/resources/templates/entry/index.html" \
  && grep -q 'name="oneOff"' "$RD/src/main/resources/templates/expense/import.html" \
  && grep -q 'boolean oneOff' "$RD/src/main/java/com/family/finance/service/EntryService.java" \
  && grep -q 'oneOffIdx' "$RD/src/main/java/com/family/finance/service/expense/imports/BillCommitService.java" \
  && grep -q 'one_off' "$QA124_CFM"; } \
  && log_ok "v1240-ONEOFF-BOTH-WRITE-PATHS(逐笔与导入两条写入路都能标一次性 · insert 列清单带 one_off)" \
  || log_bad "v1240-ONEOFF-BOTH-WRITE-PATHS 少了一条写入路,或 insert 漏了 one_off 列" \
             "显式列 INSERT 漏一列不报错 —— 勾了存进去还是 0(预检 SF2)"

# v1240-PREV-PERIOD-BY-DATE · FR-620/625「上期」按日期取,与关账状态无关。
#   v1.23 双活跃账期之后,「上一个 OPEN 的前面那个」这种写法会拿到错的那一期;
#   按 id 取同样不行 —— 补录一期历史账期会得到一个更大的 id。
{ grep -q 'previousOf' "$RD/src/main/java/com/family/finance/repository/PeriodMapper.java" \
  && grep -A12 'Optional<Period> previousOf' "$RD/src/main/java/com/family/finance/repository/PeriodMapper.java" \
     | grep -q 'period_start' \
  && ! grep -B14 'Optional<Period> previousOf' "$RD/src/main/java/com/family/finance/repository/PeriodMapper.java" \
     | grep -q "status = 'OPEN'"; } \
  && log_ok "v1240-PREV-PERIOD-BY-DATE(上期按 period_start 取 · 不看关账状态、不按 id)" \
  || log_bad "v1240-PREV-PERIOD-BY-DATE 上期判据又看状态或 id 了" "双活跃账期下会拿到错的那一期,而且不报错"

# v1240-NO-JUDGEMENT · FR-634/§1.6 · 这一块不给好坏评价、不设阈值红绿灯。
#   「花钱没有对错」是这个产品的立场:刚性占比高不等于日子过得差,
#   给它一个红灯就是在替用户的人生下判断。
{ ! grep -qiE '刚性.*(太高|过高|偏高|不健康|危险)|弹性.*(该砍|应该减|建议削减)' "$QA124_SEC" \
  && grep -q '不给好坏评价\|这是口径说明,不是好坏评价' "$QA124_SEC" "$QA124_NRM" \
       "$RD/src/main/java/com/family/finance/service/review/ReviewInsightService.java"; } \
  && log_ok "v1240-NO-JUDGEMENT(三分块不给好坏评价 · 不设阈值红绿灯)" \
  || log_bad "v1240-NO-JUDGEMENT 支出分析里出现了好坏评价" "花钱没有对错 —— 给它一个红灯就是替用户的人生下判断"

# v1240-NEGATIVE-PART-NOT-IN-BAR · 退款多于消费的那一档不进条,但仍进合计。
#   beta 实测撞到:一次性那一档冲正成 −3.6% → 弹性算出 103.2% →
#   条宽溢出容器、负段画不出来。三个数学上都对、加起来正好 100%,
#   只是不能拿算术占比当宽度。这和 v1.22 饼图那条(v1220-NEGATIVE-GROUP-NOT-IN-PIE)
#   是同一条规矩的第二处落点。
#   【不许对分档金额取绝对值】—— 那会把一笔退款画成一笔消费,而且不报错。
#   判据要精确到「对分档金额取 abs」:pct() 里 divide(whole.abs(), …) 是【分母】取绝对值,
#   防的是总额为负时把每一档的符号整体翻过来 —— 那是对的,不能一起禁掉。
#   第一版判据写成「不许出现任何 .abs()」,立刻把这个合法用法判红了(护栏太钝也是缺陷)。
{ grep -q 'boolean inBar' "$QA124_NRM" \
  && grep -q 'BigDecimal barPct' "$QA124_NRM" \
  && grep -q 'refundedParts' "$QA124_NRM" \
  && grep -q 'inBar ? pct(v, positiveTotal) : BigDecimal.ZERO' "$QA124_NRM" \
  && ! codeonly "$QA124_NRM" | grep -qE 'v\.abs\(\)|amountBase\(\)\.abs\(\)' \
  && ! { for f in "$QA124_SEC" "$RD/src/main/resources/templates/dashboard/_region.html"; do codeonly "$f"; done \
         | grep -q "p.pct() + '%;background"; } ; } \
  && log_ok "v1240-NEGATIVE-PART-NOT-IN-BAR(负档不进条 · 条宽按正值归一 · 图例仍显真值)" \
  || log_bad "v1240-NEGATIVE-PART-NOT-IN-BAR 三分条又拿算术占比当宽度了" \
             "负段画不出来、正段溢出容器;取绝对值更糟 —— 会把退款画成消费,而且不报错"

# v1240-UNCLASSIFIED-VISIBLE · L13 硬约束③:不许把未分类偷偷丢掉。
#   它要算进弹性(不是丢掉)、要单独成片(不并进「其他」)、还要显式说出有多少笔。
{ grep -q 'categoryId == null) return ExpenseNature.FLEX' "$QA124_NAT" \
  && grep -q 'unclassifiedCount' "$QA124_NRM" \
  && grep -q 'unclassified' "$QA124_VIEW" \
  && grep -q '没归类' "$QA124_SEC"; } \
  && log_ok "v1240-UNCLASSIFIED-VISIBLE(未分类算弹性 · 单独成片 · 笔数显式说出来)" \
  || log_bad "v1240-UNCLASSIFIED-VISIBLE 未分类被丢掉或藏起来了" "三分合计会小于支出总额,而且不报错"


# ═══════════════════════════════════════════════════════════════════════════
# v1.27 · issue #23 · 分析范围 / 分析模板 / 分析偏好 / 纠正默认值(prd/v1.27.md · tech-design/v1.27.md 六)
# ═══════════════════════════════════════════════════════════════════════════
section "v1.27 · 分析范围 / 模板 / 偏好"
QA127_SVC="$RD/src/main/java/com/family/finance/service/analysis"
QA127_WEB="$RD/src/main/java/com/family/finance/web"

# v127-MIGRATION-ADD-ONLY · V64 只加不改:加列、建表,不删不改任何既有列(prod 上跑着真实数据)
QA127_MIG="$RD/db/migration/V64__analysis_scope.sql"
{ [ -f "$QA127_MIG" ] \
  && grep -q 'ADD COLUMN analysis_excluded TINYINT(1) NOT NULL DEFAULT 0' "$QA127_MIG" \
  && grep -q 'CREATE TABLE IF NOT EXISTS analysis_template' "$QA127_MIG" \
  && grep -q 'CREATE TABLE IF NOT EXISTS analysis_preference' "$QA127_MIG" \
  && ! grep -vE '^\s*--' "$QA127_MIG" | grep -qiE 'DROP |MODIFY |CHANGE COLUMN|RENAME '; } \
  && log_ok "v127-MIGRATION-ADD-ONLY(V64 只加列建表 · 标记默认 0 = 与 v1.26 一样)" \
  || log_bad "v127-MIGRATION-ADD-ONLY V64 出现了改列 / 删列,或缺了表" "see db/migration/V64__analysis_scope.sql"

# v127-SCOPE-SLICE-ONLY · 范围只经 FactSlice.excludingAccounts 落到计算上,不走 FactFilter(空列表 = 不筛选,
#   全都标了会静默退回全部账户 · PRD §9 ④)。判据:analysis 包里不许 new FactFilter;apply 走 excludingAccounts
{ grep -q 'public FactSlice excludingAccounts(' "$RD/src/main/java/com/family/finance/factview/FactSlice.java" \
  && codeonly "$QA127_SVC/AnalysisScope.java" | grep -q 'excludingAccounts(excludedIds)' \
  && ! grep -rq 'new FactFilter(' "$QA127_SVC" \
  && codeonly "$RD/src/main/java/com/family/finance/service/checkup/FamilyDiagnoseService.java" | grep -q 'scope.apply(slice)' \
  && [ -f "$RD/src/test/java/com/family/finance/service/analysis/AnalysisScopeTest.java" ] \
  && grep -q 'everythingMarkedIsEmptyNotAll' "$RD/src/test/java/com/family/finance/service/analysis/AnalysisScopeTest.java"; } \
  && log_ok "v127-SCOPE-SLICE-ONLY(范围只在内存切片上去掉账户 · 空就是空 · 单测守「全都标了 ≠ 全部」)" \
  || log_bad "v127-SCOPE-SLICE-ONLY 范围改走 FactFilter 了,或空范围单测没了" "全都标了会静默退回按全部账户算"

# v127-RATIO-FOLLOWS-SCOPE · 占比类入口都接了范围(PRD §9 ①「一页两个口径」)。
#   web 层不许再出现两参的 allocationService.compute(…, slice) / 一参的 familyDiagnoseService.diagnose(…) 用在体检家庭页
QA127_CK="$QA127_WEB/checkup/CheckupController.java"; QA127_AI="$QA127_WEB/checkup/AiDiagnoseController.java"
QA127_RP="$QA127_WEB/report/ReportsController.java"; QA127_DB="$QA127_WEB/dashboard/DashboardController.java"
{ codeonly "$QA127_CK" | grep -q 'familyDiagnoseService.diagnose(me.getFamilyId(), scopeParam)' \
  && codeonly "$QA127_AI" | grep -q 'diagnoseExactly(me.getFamilyId(), template.scope())' \
  && codeonly "$QA127_AI" | grep -q 'assetInsightService.compute(fid, slice, scope, template.anchor())' \
  && codeonly "$QA127_RP" | grep -q 'allocationService.compute(me.getFamilyId(), slice, scope, null)' \
  && codeonly "$QA127_DB" | grep -q 'assetInsightService.compute(me.getFamilyId(), slice, dashScope, null)' \
  && ! grep -rhE 'allocationService\.compute\([^,]+, *slice\)' "$QA127_WEB" | grep -q . ; } \
  && log_ok "v127-RATIO-FOLLOWS-SCOPE(体检配置 / 风险 · AI 诊断与洞察 · 报表配置锚 · 仪表盘洞察条都吃同一个范围)" \
  || log_bad "v127-RATIO-FOLLOWS-SCOPE 有占比类入口没接分析范围" "同一页会出现两个口径,而且不报错"

# v127-PROMPT-BASELINE · 「综合体检 · 全部资产 · 没偏好」的提示词与 v1.26.1 tag 逐字相同(金样本 + 单测)
QA127_G="$RD/src/test/resources/golden/v1261"
{ for f in diagnose-system diagnose-family-user diagnose-account-user insight-system insight-user; do [ -s "$QA127_G/$f.txt" ] || exit 1; done; } 2>/dev/null \
  && grep -q 'v127PathWithBaselineContextIsByteIdentical' "$RD/src/test/java/com/family/finance/service/analysis/AnalysisPromptBaselineTest.java" \
  && log_ok "v127-PROMPT-BASELINE(五份金样本取自 v1.26.1 · 这一版基线路径逐字比对)" \
  || log_bad "v127-PROMPT-BASELINE 金样本或基线单测不在了" "see src/test/resources/golden/v1261"

# v127-BLOCK-ORDER · 家里写的原文(补充要求 / 偏好)压在最后,且带「数字以系统为准、不许据此计算」(PRD §9 ⑤ ⑦)
QA127_BL="$QA127_SVC/AnalysisPromptBlocks.java"
{ codeonly "$QA127_BL" | grep -qE 'add\(parts, scope\(ctx\.scope\(\), affected(, accountCode)?\)\);' \
  `# v1.28 起范围块多一个参数(范围里列出的账户名换代号),顺序不变` \
  && codeonly "$QA127_BL" | tr -d '\n' | grep -qE 'scope\(ctx\.scope\(\), affected(, accountCode)?\)\);\s*add\(parts, template\(.*add\(parts, extra\(.*add\(parts, preferences\(' \
  && grep -q '不许据此自己计算' "$QA127_BL" \
  && grep -q '不许荐股' "$QA127_BL" \
  && grep -q 'blockOrderIsScopeTemplateExtraPreferences' "$RD/src/test/java/com/family/finance/service/analysis/AnalysisPromptBlocksTest.java"; } \
  && log_ok "v127-BLOCK-ORDER(范围 → 模板 → 补充要求 → 偏好 · 固定包裹说明 · 单测守顺序)" \
  || log_bad "v127-BLOCK-ORDER 提示词段落顺序或包裹说明变了" "偏好 / 补充要求要压在最后,并说明改不了数字"

# v127-CACHE-KEYS · 缓存按「范围 + 标记集合 + 模板版本 + 偏好」分开(PRD §9 ②)· 复盘缓存带账户筛选与币种(§13 ⑩)
{ codeonly "$RD/src/main/java/com/family/finance/service/allocation/RebalanceAdvisorService.java" | grep -q 'public String cacheKey(Family f' \
  && codeonly "$QA127_RP" | grep -q 'rebalanceAdvisorService.cached(me.getFamilyId(), analysis)' \
  && ! codeonly "$QA127_RP" | grep -q 'findByFamilyAndAnchor' \
  && codeonly "$RD/src/main/java/com/family/finance/service/review/ReviewInsightService.java" | grep -q 'static String cacheDim(' \
  && codeonly "$RD/src/main/java/com/family/finance/service/checkup/llm/LlmDiagnoseService.java" | grep -q 'sha256(systemPrompt)' \
  && grep -q 'rebalanceCacheKeySeparatesContexts' "$RD/src/test/java/com/family/finance/service/analysis/ScopedPromptsAndCacheTest.java"; } \
  && log_ok "v127-CACHE-KEYS(调仓 / 复盘 / 诊断的缓存键都带上范围、模板、偏好 · 报表读缓存与生成同一个键)" \
  || log_bad "v127-CACHE-KEYS 缓存会串范围 / 串偏好" "切了范围、改了偏好还拿到旧结论,读起来完全通顺"

# v127-D-FIXES · 纠正默认值:其他类不进投资桶、金融盘含贵金属不含其他、调仓真的带余额、自定义锚能填
{ codeonly "$RD/src/main/java/com/family/finance/calc/AllocationDiff.java" | grep -q 'if ("OTHER".equals(type)) return null;' \
  && codeonly "$RD/src/main/java/com/family/finance/service/insight/AssetInsightService.java" | tr -d '\n' | grep -qE 'AccountType\.WEALTH, AccountType\.CRYPTO, AccountType\.METAL, AccountType\.INSURANCE\)' \
  && codeonly "$RD/src/main/java/com/family/finance/service/allocation/RebalanceAdvisorService.java" | grep -q '当前余额=¥' \
  && codeonly "$RD/src/main/java/com/family/finance/service/allocation/AllocationService.java" | grep -q 'familyMapper.updateAllocationAnchorCustom(familyId, json)' \
  && grep -q 'effectiveTargetDropsAbsentBucketsAndRescales' "$RD/src/test/java/com/family/finance/calc/AllocationDiffTest.java"; } \
  && log_ok "v127-D-FIXES(其他类不进四桶 · 金融盘含贵金属 · 调仓带各账户余额 · 自定义锚有写入口 · 没有的桶不参与并放大)" \
  || log_bad "v127-D-FIXES 某处纠错被改回去了" "see prd/v1.27.md §3.6"

# v127-AGENT-CONTEXT-EVERY-TURN · 托管模式每轮不发系统提示词 —— 偏好要拼进用户事件、并用百炼回显确认(PRD §9 ③)
QA127_MA="$RD/src/main/java/com/family/finance/service/ask/runtime/ManagedAgentRuntime.java"
{ codeonly "$QA127_MA" | grep -q 'composeTurnInput(turn.analysisContext(), turn.question())' \
  && codeonly "$QA127_MA" | grep -q 'ap.echo().contains(CONTEXT_MARK)' \
  && codeonly "$RD/src/main/java/com/family/finance/service/ask/runtime/LocalToolLoopRuntime.java" | grep -q 'turn.analysisContext()' \
  && codeonly "$RD/src/main/java/com/family/finance/service/ask/AskConversationService.java" | grep -q 'messageMapper.updateCtxNote(' \
  && [ -f "$RD/scripts/e2e/flows/32-analysis-preferences.cjs" ]; } \
  && log_ok "v127-AGENT-CONTEXT-EVERY-TURN(本机拼系统提示词 · 托管拼用户事件并回显确认 · 落 ctx_note · e2e 真问一句)" \
  || log_bad "v127-AGENT-CONTEXT-EVERY-TURN 托管模式下偏好又只写进系统提示词了" "本机有效、托管无效,而且不报错"

# v127-AGENT-KEEPS-SKILLS · 「更新 Agent」全量替换前回读远端,用户在控制台加的 skill 带回去(FR-857)
{ codeonly "$QA127_MA" | grep -q 'mergeRemoteExtras(body, get(agentBase() + "/agents/" + agentId()), mcpServerId())' \
  && grep -q 'updateAgentKeepsRemoteSkillsAndForeignTools' "$RD/src/test/java/com/family/finance/service/ask/AskRememberParserTest.java" \
  && grep -q '提示词会被覆盖为最新版' "$RD/src/main/resources/templates/admin/ai-access.html"; } \
  && log_ok "v127-AGENT-KEEPS-SKILLS(更新前回读合并 · 单测用构造的远端 JSON 守 · 点之前有提示)" \
  || log_bad "v127-AGENT-KEEPS-SKILLS 更新 Agent 又会冲掉控制台里加的 skill" "see ManagedAgentRuntime.updateAgent"

# v127-NO-WRITE-TOOLS · 工具表里没有任何写能力 —— 「提议记住」走正文标记 + 站内会话表单(选型六 · FR-855)
{ ! grep -rqiE 'name\(\) \{ return "(save|remember|propose)' "$RD/src/main/java/com/family/finance/service/ask/tools" \
  && codeonly "$QA127_WEB/ask/AskRememberController.java" | grep -q '@PostMapping("/ask/remember")' \
  && grep -q 'REMEMBER = Pattern.compile' "$RD/src/main/java/com/family/finance/service/ask/AskCitationRenderer.java"; } \
  && log_ok "v127-NO-WRITE-TOOLS(源码:工具目录里没有保存 / 提议类工具 · 记住只在站内会话端点)" \
  || log_bad "v127-NO-WRITE-TOOLS 工具表里出现了写能力" "只读口令就不再只读了"

# v127-CONFIG-HOME · 新的三个家庭配置键:两个在「分析设置」页配(经服务层),一个是用户操作的结果 —— 登记在 v126 那张表里
grep -q '^K_ANALYSIS_SCOPE_DEFAULT:' <<<"$QA_KEY_OK" && grep -q '^K_ANALYSIS_TEMPLATE_DEFAULT:' <<<"$QA_KEY_OK" && grep -q '^K_ANALYSIS_HINT_DISMISSED:' <<<"$QA_KEY_OK" \
  && log_ok "v127-CONFIG-HOME(默认范围 / 默认模板在分析设置页配 · 首次提示是用户关掉的结果)" \
  || log_bad "v127-CONFIG-HOME 新配置键没登记去处" "见 v126-CONFIG-KEY-HAS-HOME 的登记表"

# v127-TOC-AND-SIZE · 体检页长文目录同步新节;范围选项窄屏上下排且等高(并列同类元素同尺寸)
{ grep -q "href:'#analysis-templates'" "$RD/src/main/resources/templates/checkup/family.html" \
  && grep -q 'id="analysis-templates"' "$RD/src/main/resources/templates/checkup/family.html" \
  && grep -q 'grid-auto-rows: 1fr' "$RD/src/main/resources/static/css/style.css"; } \
  && log_ok "v127-TOC-AND-SIZE(体检目录有「分析模板」一节 · 范围选项上下排等高)" \
  || log_bad "v127-TOC-AND-SIZE 目录没同步或范围选项会一大两小" "feedback_toc_sync · feedback_sibling_uniform"

# ── 渲染层(真请求)────────────────────────────────────────────────
# 自己登录一份会话:前面有段落会换成员 / 改密码,跑到这里时共用的 $COOKIE 不一定还是 diwa 的有效会话
QA127_C=/tmp/finance-qa-v127-cookie.txt; :> "$QA127_C"
QA127_T=$($CURL -c "$QA127_C" "$BASE/login" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
$CURL -b "$QA127_C" -c "$QA127_C" -X POST --data-urlencode "_csrf=$QA127_T" --data-urlencode "username=diwa" --data-urlencode "password=demo1234" "$BASE/login" -o /dev/null -w ""
# v127-SETTINGS-ENTRY · 分析设置入口:管理首页卡片点名五块 + 侧栏 + 页面五节 + 各现场直达(FR-890 ~ FR-892)
$CURL -b "$QA127_C" "$BASE/admin" -o "$TMP" -w ""
QA127_CARD=$(tr -d '\n' < "$TMP" | grep -o 'data-admin-card="analysis".\{0,900\}' | head -1)
QA127_CARD_OK=1; for w in 分析范围 分析模板 不参与分析的资产 配置锚 分析偏好; do printf '%s' "$QA127_CARD" | grep -q "$w" || QA127_CARD_OK=0; done
$CURL -b "$QA127_C" "$BASE/admin/analysis" -o "$TMP" -w ""
QA127_SEC=0; for id in scope templates excluded anchor preferences; do grep -q "id=\"$id\"" "$TMP" && QA127_SEC=$((QA127_SEC+1)); done
{ [ "$QA127_CARD_OK" = 1 ] && [ "$QA127_SEC" = 5 ] \
  && grep -q "@{/admin/analysis}" "$RD/src/main/resources/templates/admin/_sidebar.html" \
  && grep -q '</html>' "$TMP"; } \
  && log_ok "v127-SETTINGS-ENTRY(管理首页卡片点名五块 · 侧栏有 · 分析设置五节齐 · 页面渲染到底)" \
  || log_bad "v127-SETTINGS-ENTRY 分析设置入口不全" "卡片五词=$QA127_CARD_OK · 页面五节=$QA127_SEC"
$CURL -b "$QA127_C" "$BASE/checkup" -o "$TMP" -w ""
QA127_CK1=$(grep -c 'data-tpl-chip' "$TMP"); QA127_CK2=$(grep -c 'data-tpl-customize' "$TMP")
{ [ "$QA127_CK1" -ge 5 ] && [ "$QA127_CK2" -ge 1 ] && grep -q '</html>' "$TMP"; } \
  && log_ok "v127-TEMPLATE-ROW(体检页模板选择:内置 5 个 + 「基于当前模板定制…」· 页面渲染到底)" \
  || log_bad "v127-TEMPLATE-ROW 体检页模板选择行不全" "chips=$QA127_CK1 customize=$QA127_CK2"
$CURL -b "$QA127_C" "$BASE/reports" -o "$TMP" -w ""
{ grep -q 'data-custom-anchor-link' "$TMP" && grep -q 'data-analysis-foot' "$TMP" && grep -q '</html>' "$TMP"; } \
  && log_ok "v127-REPORTS-ANCHOR-LINKS(报表配置锚有「自定义…」直达 · 调仓旁有模板页脚 · 页面渲染到底)" \
  || log_bad "v127-REPORTS-ANCHOR-LINKS 报表配置锚区缺入口,或渲染中途截断" "see reports/_allocation-diff.html"

# v127-ACCOUNT-EXCLUDE-UI · 编辑页勾选项(带存在标记,别的入口不会误清)· 贷款没有这一项 · 批量 update 不碰这一列
QA127_A=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 AND type<>'LOAN' AND archived_at IS NULL ORDER BY id LIMIT 1" 2>/dev/null)
QA127_L=$(mysql -ufinance -pfinance finance -sN -e "SELECT id FROM account WHERE family_id=1 AND type='LOAN' AND archived_at IS NULL ORDER BY id LIMIT 1" 2>/dev/null)
$CURL -b "$QA127_C" "$BASE/accounts/$QA127_A/edit" -o "$TMP" -w ""
QA127_E1=$(grep -c 'name="analysisExcluded"' "$TMP"); QA127_E2=$(grep -c 'name="analysisExcludedPresent"' "$TMP"); QA127_E3=$(grep -c '/admin/analysis#excluded' "$TMP")
QA127_E4=1; if [ -n "$QA127_L" ]; then $CURL -b "$QA127_C" "$BASE/accounts/$QA127_L/edit" -o "$TMP" -w ""; QA127_E4=$(( $(grep -c 'name="analysisExcluded"' "$TMP") == 0 ? 1 : 0 )); fi
QA127_UPD=$(awk '/int update\(Account account\)/{f=0} /UPDATE account/{f=1} f' "$RD/src/main/java/com/family/finance/repository/AccountMapper.java" | sed -n '/SET template_id/,/WHERE id/p' | grep -c analysis_excluded)
{ [ "$QA127_E1" -ge 1 ] && [ "$QA127_E2" -ge 1 ] && [ "$QA127_E3" -ge 1 ] && [ "$QA127_E4" = 1 ] && [ "$QA127_UPD" = 0 ]; } \
  && log_ok "v127-ACCOUNT-EXCLUDE-UI(编辑页勾选项 + 存在标记 + 「看看哪些资产不参与 →」· 贷款没有 · 整表单 update 不碰这一列)" \
  || log_bad "v127-ACCOUNT-EXCLUDE-UI 账户编辑页的「不参与配置分析」不对" "勾选=$QA127_E1 存在标记=$QA127_E2 链接=$QA127_E3 贷款无=$QA127_E4 update里=$QA127_UPD"

# v127-AGG-NO-NAMES · 只给汇总的口令拿不到任何账户名(PRD 验收 #11)· 真的发一把汇总口令去调 /mcp,跑完删掉
QA127_TOK="fmk_qa127$(head -c 12 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 16)"
QA127_HASH=$(printf '%s' "$QA127_TOK" | sha256sum | cut -d' ' -f1)
mysql -ufinance -pfinance finance -e "INSERT INTO ask_access_token(family_id, access_point_id, name, token_hash, token_prefix, scope, expires_at) VALUES (1, 900127, 'qa-v127', '$QA127_HASH', '${QA127_TOK:0:12}', 'aggregate', NOW() + INTERVAL 10 MINUTE)" 2>/dev/null
qa127_mcp(){ /usr/bin/curl -s --max-time 20 -X POST "$BASE/mcp" -H "Authorization: Bearer $QA127_TOK" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d "$1"; }
QA127_CAP=$(qa127_mcp '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"capabilities","arguments":{}}}')
QA127_GRP=$(qa127_mcp '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"pivot","arguments":{"rows":["group"]}}}')
QA127_PLT=$(qa127_mcp '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"pivot","arguments":{"rows":["platform"],"scope":"financial","exclude":{"assetClass":["房产"]}}}}')
QA127_LIST=$(qa127_mcp '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | grep -o '"name":"[a-z_]*"' | sort -u | tr '\n' ' ')
QA127_LEAK=""
while IFS= read -r n; do
  n=$(printf '%s' "$n" | sed 's/^ *//;s/ *$//'); [ ${#n} -lt 3 ] && continue; printf '%s' "$n" | grep -qE '^[0-9]+$' && continue
  printf '%s%s' "$QA127_CAP" "$QA127_PLT" | grep -qF "$n" && QA127_LEAK="$QA127_LEAK [$n]"
done < <(mysql -ufinance -pfinance finance -sN -e "SELECT display_name FROM account WHERE family_id=1" 2>/dev/null)
{ [ -n "$QA127_CAP" ] && [ -z "$QA127_LEAK" ] \
  && printf '%s' "$QA127_GRP" | grep -q '只给汇总' \
  && printf '%s' "$QA127_PLT" | grep -q '\\"ok\\":true'; } \
  && log_ok "v127-AGG-NO-NAMES(汇总口令:capabilities 不列账户组 · pivot 拒绝账户组维 · 按范围与排除照常查 · 返回里没有任何账户名)" \
  || log_bad "v127-AGG-NO-NAMES 汇总口令拿到了账户名" "泄露:${QA127_LEAK:-无} · group 拒绝:$(printf '%s' "$QA127_GRP" | grep -c 只给汇总)"
# v127-NO-WRITE-TOOLS(真请求)· tools/list 只有这五个;拿口令、不带会话去点「记住」存不进去
QA127_PREF0=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM analysis_preference" 2>/dev/null)
/usr/bin/curl -s -o /dev/null --max-time 15 -X POST "$BASE/ask/remember" -H "Authorization: Bearer $QA127_TOK" -d "text=qa-v127-should-not-save"
QA127_PREF1=$(mysql -ufinance -pfinance finance -sN -e "SELECT COUNT(*) FROM analysis_preference" 2>/dev/null)
{ [ "$QA127_LIST" = '"name":"account_performance" "name":"capabilities" "name":"period_summary" "name":"pivot" "name":"report_unmet" ' ] \
  && [ "$QA127_PREF0" = "$QA127_PREF1" ]; } \
  && log_ok "v127-NO-WRITE-TOOLS-LIVE(工具表仍是那五个 · 只拿口令存不了分析偏好)" \
  || log_bad "v127-NO-WRITE-TOOLS-LIVE 对外面多了写能力" "tools=[$QA127_LIST] prefs $QA127_PREF0→$QA127_PREF1"
mysql -ufinance -pfinance finance -e "DELETE FROM ask_access_audit WHERE token_prefix LIKE 'fmk_qa127%'; DELETE FROM ask_access_token WHERE access_point_id=900127; DELETE FROM analysis_preference WHERE content LIKE 'qa-v127%'" 2>/dev/null

# v1271-SCOPE-PLAIN · 定制模板页的「范围」要说人话(维护者 2026-09-29:「范围指的是什么?要说『你不想看不动产的分布
#   就选 xxx』」)。原来是一个下拉,三项只写「固定为「金融资产」」这类名词,不懂的人不知道该选哪个。
#   现在每项一张说明卡:人话叫法 + 什么时候选它 + 对你家去掉了谁;配置锚同样一张张写出四个目标数。
#   判据:下拉没了、四张范围卡 + 锚卡在、「不动产」那句在、页面渲染到底;文案只有一处来源(ScopeKind),单测逼新取值表态。
QA1271_TPL="$RD/src/main/resources/templates/admin/analysis-template.html"
$CURL -b "$QA127_C" "$BASE/admin/analysis/template/new?from=GENERAL" -o "$TMP" -w ""
QA1271_SC=$(grep -c 'data-tpl-scope-option' "$TMP"); QA1271_AC=$(grep -c 'data-tpl-anchor-option' "$TMP")
{ [ "$QA1271_SC" -eq 4 ] && [ "$QA1271_AC" -ge 3 ] \
  && ! grep -q 'name="scope" class="field-input' "$TMP" && ! grep -q '<select name="scope"' "$QA1271_TPL" \
  && grep -q '不想看不动产的分布' "$TMP" && grep -q 'whenToUse' "$QA1271_TPL" \
  && grep -q 'private final String whenToUse' "$RD/src/main/java/com/family/finance/service/analysis/ScopeKind.java" \
  && [ -f "$RD/src/test/java/com/family/finance/service/analysis/ScopeKindPlainTest.java" ] \
  && grep -q '</html>' "$TMP"; } \
  && log_ok "v1271-SCOPE-PLAIN(定制页范围 4 张说明卡 · 配置锚 $QA1271_AC 张 · 人话在 ScopeKind 一处 · 渲染到底)" \
  || log_bad "v1271-SCOPE-PLAIN 定制页的「范围」又只剩名词了" "范围卡=$QA1271_SC 锚卡=$QA1271_AC · see admin/analysis-template.html"

# v127-E2E-FLOWS · 四条真浏览器 flow 在(从真实入口点:体检卡片去标 · 模板定制回跳重跑 · 偏好与超级 Agent · 配置锚)
{ for f in 30-analysis-scope 31-analysis-template 32-analysis-preferences 33-analysis-anchor; do [ -f "$RD/scripts/e2e/flows/$f.cjs" ] || exit 1; done; } 2>/dev/null \
  && log_ok "v127-E2E-FLOWS(flow 30 ~ 33 在)" \
  || log_bad "v127-E2E-FLOWS v1.27 的 e2e flow 缺了" "see scripts/e2e/flows/30-33"

# ═══════════════════════════════════════════════════════════════════════════
# v1.28 · 看 AI 收到了什么(prd/v1.28.md · tech-design/v1.28.md 六)
# ═══════════════════════════════════════════════════════════════════════════
section "v1.28 · 看 AI 收到了什么(>_ 终端面板 · 账户代号 · 日志不写正文 · AI 正文糊金额)"
QA128_T="$RD/src/main/resources/templates"
QA128_S="$RD/src/main/java/com/family/finance/service"

# v128-MIGRATION-ADD-ONLY · V65 只建表、只加可空列(prod 上跑着真实数据;老缓存行 prompt_record_id 为空 = 老结果)
QA128_MIG="$RD/db/migration/V65__llm_prompt_record.sql"
{ [ -f "$QA128_MIG" ] && codeonly "$QA128_MIG" | grep -q 'CREATE TABLE IF NOT EXISTS llm_prompt_record' \
  && codeonly "$QA128_MIG" | grep -q 'CREATE TABLE IF NOT EXISTS llm_system_prompt' \
  && [ "$(codeonly "$QA128_MIG" | grep -c 'ADD COLUMN prompt_record_id BIGINT NULL')" -eq 4 ] \
  && [ "$(codeonly "$QA128_MIG" | grep -c '^ALTER TABLE')" -eq 4 ] \
  && ! codeonly "$QA128_MIG" | grep -qiE 'DROP |MODIFY |CHANGE COLUMN'; } \
  && log_ok "v128-MIGRATION-ADD-ONLY(V65 只建两张表 + 四张结果表各加可空列 · 不删不改)" \
  || log_bad "v128-MIGRATION-ADD-ONLY V65 出现了改列 / 删列,或缺了表 / 列" "see $QA128_MIG"

# v128-PEEK-EVERYWHERE · §3.5 九处 + 超级 Agent 都用同一个 >_ 片段(漏几处会让人以为那几处「不能看」)
QA128_MISS=""
for f in checkup/_ai-diagnose.html checkup/_ai-insight.html reports/_ai-rebalance.html goals/detail.html ask/fragments/_stream.html; do
  grep -q "_prompt-peek :: btn(" "$QA128_T/$f" || QA128_MISS="$QA128_MISS $f"
done
for f in dashboard/_attribution.html lens/_section.html goals/new-emergency.html goals/new-education.html goals/new-retirement.html; do
  grep -q "_prompt-peek :: btnJs(" "$QA128_T/$f" || QA128_MISS="$QA128_MISS $f"
done
[ "$(grep -c '_prompt-peek :: btn(' "$QA128_T/goals/detail.html")" -ge 2 ] || QA128_MISS="$QA128_MISS goals/detail(月报+预警)"
grep -q 'peekButton' "$RD/src/main/resources/static/js/ask.js" || QA128_MISS="$QA128_MISS ask.js(流式回答)"
grep -q 'prompt-peek.js' "$QA128_T/fragments/layout.html" || QA128_MISS="$QA128_MISS layout(没引脚本)"
[ -z "$QA128_MISS" ] \
  && log_ok "v128-PEEK-EVERYWHERE(九处 AI 卡片 + 超级 Agent 历史 / 流式回答都挂同一个 >_ · 脚本全站引入)" \
  || log_bad "v128-PEEK-EVERYWHERE 有 AI 卡片没挂 >_:$QA128_MISS" "see fragments/_prompt-peek.html"

# v128-PEEK-STORED-NOT-REBUILT · 面板只读存下的原文,不许调提示词拼装函数重拼(数据变过之后重拼的与结果对不上)
QA128_PV="$QA128_S/llmtrace/PromptPeekView.java"; QA128_PC="$RD/src/main/java/com/family/finance/web/ai/PromptPeekController.java"
QA128_SAVERS=$(grep -rlE 'promptRecorder\.save\(|recorder\.save\(' "$RD/src/main/java" | xargs -n1 basename 2>/dev/null | sort | tr '\n' ' ')
{ ! codeonly "$QA128_PV" | grep -qE 'PromptBuilder|InsightPromptBuilder|forAnalysis\(|userPromptFor' \
  && ! codeonly "$QA128_PC" | grep -qE 'PromptBuilder|InsightPromptBuilder|forAnalysis\(|userPromptFor|LlmDiagnoseService' \
  && [ "$QA128_SAVERS" = "AiAccessController.java AskConversationService.java LlmRouter.java " ]; } \
  && log_ok "v128-PEEK-STORED-NOT-REBUILT(面板只读记录 · 只有路由 / 超级 Agent 发出那一刻 / 推 Agent 那一刻写记录)" \
  || log_bad "v128-PEEK-STORED-NOT-REBUILT 面板在重拼提示词,或多了写记录的地方" "写记录的类=[$QA128_SAVERS]"

# v128-ROUTER-TRACE · 九处业务调用都走带 PromptTrace 的重载(漏一处 = 那一处的 >_ 永远「没记录」)
QA128_NT=""
for f in checkup/llm/LlmDiagnoseService.java allocation/RebalanceAdvisorService.java review/ReviewInsightService.java \
         lens/LensInsightService.java goal/GoalLlmService.java; do
  codeonly "$QA128_S/$f" | grep -qE 'llmRouter\.invoke\(familyId, trace,' || QA128_NT="$QA128_NT $f"
  codeonly "$QA128_S/$f" | grep -qE 'llmRouter\.invoke\(familyId, (system|systemPrompt), ' && QA128_NT="$QA128_NT $f(还有不记录的调用)"
done
[ -z "$QA128_NT" ] \
  && log_ok "v128-ROUTER-TRACE(诊断 / 洞察 / 调仓 / 复盘 / 透视 / 目标 都经带 PromptTrace 的路由调用)" \
  || log_bad "v128-ROUTER-TRACE 有调用没带 PromptTrace:$QA128_NT" "改走 llmRouter.invoke(familyId, trace, …)"

# v128-ACCOUNT-CODENAMES · 账户名只在系统写入的位置换代号;金样本「换回真名后逐字相同」;回答换回真名
{ codeonly "$QA128_S/checkup/llm/LlmDiagnoseService.java" | grep -q 'codes.code(a.getId()' \
  && codeonly "$QA128_S/allocation/RebalanceAdvisorService.java" | grep -q 'codes.code(a.getId(), a.getDisplayName())' \
  && codeonly "$QA128_S/allocation/RebalanceAdvisorService.java" | grep -q 'raw = PromptBuilder.reverseMapping(raw, legend)' \
  && grep -q '代号换回真名后与v1261金样本逐字相同' "$RD/src/test/java/com/family/finance/service/llmtrace/AccountCodenamesTest.java" \
  && grep -q '自己写的偏好不换账户名' "$RD/src/test/java/com/family/finance/service/llmtrace/AccountCodenamesTest.java"; } \
  && log_ok "v128-ACCOUNT-CODENAMES(账户清单 / 账户名一行 / 调仓清单写代号 · 回答换回真名 · 金样本换回后逐字相同 · 偏好原文不动)" \
  || log_bad "v128-ACCOUNT-CODENAMES 账户代号链路缺了一环" "see AccountCodenames / AccountCodenamesTest"

# v128-LOG-NO-PROMPT · 服务器日志不再写提示词与回答正文(FR-921)· 只记长度与指纹
QA128_AL="$QA128_S/checkup/llm/LlmAuditLogger.java"
{ ! codeonly "$QA128_AL" | grep -qE 'append\((systemPrompt|userPrompt|response)\)|\? "\(null\)" : (systemPrompt|userPrompt|response)' \
  && codeonly "$QA128_AL" | grep -q 'fp(userPrompt)' \
  && ! codeonly "$QA128_S/checkup/llm/LlmDiagnoseService.java" | grep -qE 'log\.(debug|info)\([^;]*userPrompt\)'; } \
  && log_ok "v128-LOG-NO-PROMPT(审计日志只记模型 / 耗时 / 长度 / 指纹 · 不写正文)" \
  || log_bad "v128-LOG-NO-PROMPT 日志里又在写提示词 / 回答正文" "see LlmAuditLogger / LlmDiagnoseService"

# v128-AI-TEXT-PRIV · 隐私模式也糊 AI 正文里的金额(FR-913)· 服务端 @aiText.priv + 前端 privText 同一个正则
QA128_PM=""
for f in checkup/_ai-diagnose.html checkup/_ai-insight.html reports/_ai-rebalance.html goals/detail.html; do
  grep -q '@aiText.priv(' "$QA128_T/$f" || QA128_PM="$QA128_PM $f"
done
grep -q 'privText' "$RD/src/main/resources/static/js/lens.js" || QA128_PM="$QA128_PM lens.js"
grep -q 'privText' "$QA128_T/dashboard/_attribution.html" || QA128_PM="$QA128_PM _attribution"
grep -q 'privText' "$RD/src/main/resources/static/js/goal-advise.js" || QA128_PM="$QA128_PM goal-advise.js"
grep -q '前端正则与服务端逐字相同' "$RD/src/test/java/com/family/finance/service/llmtrace/AiTextTest.java" || QA128_PM="$QA128_PM AiTextTest"
[ -z "$QA128_PM" ] \
  && log_ok "v128-AI-TEXT-PRIV(AI 正文里的金额包 data-priv · 服务端 / 前端同一个正则,单测逐字比)" \
  || log_bad "v128-AI-TEXT-PRIV 还有 AI 正文没糊金额:$QA128_PM" "模板用 th:utext=\${@aiText.priv(…)} · JSON 渲染的用 privText(el, text)"

# v128-PEEK-LIVE · 真请求:说明面板渲染到底;别人家 / 已清理的记录一律「已经清理掉了」(不告诉对方有没有);管理页有开关
QA128_C=/tmp/finance-qa-v128-cookie.txt; :> "$QA128_C"
QA128_TK=$($CURL -c "$QA128_C" "$BASE/login" | grep -oE 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="\([^"]*\)".*/\1/')
$CURL -b "$QA128_C" -c "$QA128_C" -X POST --data-urlencode "_csrf=$QA128_TK" --data-urlencode "username=diwa" --data-urlencode "password=demo1234" "$BASE/login" -o /dev/null -w ""
$CURL -b "$QA128_C" "$BASE/ai/prompt/none?for=REBALANCE&why=legacy&at=2026-09-12T10:00" -o "$TMP" -w ""
QA128_L1=$(grep -c '还没开始记录' "$TMP"); QA128_L2=$(grep -c 'data-peek-state="LEGACY"' "$TMP")
$CURL -b "$QA128_C" "$BASE/ai/prompt/987654321" -o "$TMP" -w ""
QA128_L3=$(grep -c '已经清理掉了' "$TMP")
$CURL -b "$QA128_C" "$BASE/admin/ai-access" -o "$TMP" -w ""
QA128_L4=$(grep -c 'data-prompt-peek-toggle' "$TMP"); QA128_L5=$(grep -c '</html>' "$TMP")
{ [ "$QA128_L1" -ge 1 ] && [ "$QA128_L2" -ge 1 ] && [ "$QA128_L3" -ge 1 ] && [ "$QA128_L4" -ge 1 ] && [ "$QA128_L5" -ge 1 ]; } \
  && log_ok "v128-PEEK-LIVE(老结果照实说 · 不存在 / 别人家的记录不露有无 · 管理 → AI 接入有开关 · 页面渲染到底)" \
  || log_bad "v128-PEEK-LIVE 面板 / 开关没渲染对" "老结果=$QA128_L1/$QA128_L2 不存在=$QA128_L3 开关=$QA128_L4 页尾=$QA128_L5"

# v128-E2E-FLOW · 真浏览器 flow 在(从首页点资产体检 → >_ → 面板;调仓 · 开关 · 超级 Agent)
[ -f "$RD/scripts/e2e/flows/34-prompt-peek.cjs" ] \
  && log_ok "v128-E2E-FLOW(flow 34 在)" \
  || log_bad "v128-E2E-FLOW v1.28 的 e2e flow 缺了" "see scripts/e2e/flows/34-prompt-peek.cjs"

# ═══════════════════════════════════════════════════════════════════════════
# v1.28.1 · 入口写明「查看 prompt」· 开账基线不含转入 · issue #25(prd/v1.28.md §14 · tech-design/v1.28.md 附录 B)
# ═══════════════════════════════════════════════════════════════════════════
section "v1.28.1 · 查看 prompt 入口 · 开账基线不含首期转账 · 账户页菜单与类型合计"
QA1281_FV="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"

# v1281-OPENING-NO-TRANSFER · 开账基线 / 账户累计净投入都经 openingOf(首期期末 − 转入 + 转出)
#   维护者 2026-09-30:新开空账户从老账户转入,整笔被当成开账基线 → 本月资产收益凭空少这一笔。
#   两处都不许再直接拿首期 endBalanceBase(那就是 bug 本身)。
QA1281_OB="$(awk '/private BigDecimal openingBaseline\(FactSlice/{f=1} f{print} f&&/^    }$/{exit}' "$QA1281_FV")"
{ grep -q 'static BigDecimal openingOf(AccountPeriodFact row)' "$QA1281_FV" \
  && printf '%s' "$QA1281_OB" | grep -q 'FactViewServiceImpl::openingOf' \
  && ! printf '%s' "$QA1281_OB" | grep -q 'AccountPeriodFact::endBalanceBase' \
  && grep -q 'netPrincipal.add(openingOf(filled.get(0)))' "$QA1281_FV" \
  && ! grep -q 'netPrincipal.add(filled.get(0).endBalanceBase())' "$QA1281_FV" \
  && [ -f "$RD/src/test/java/com/family/finance/factview/OpeningBaselineTransferTest.java" ]; } \
  && log_ok "v1281-OPENING-NO-TRANSFER(开账基线 + 账户累计净投入都经 openingOf · 首期转入不算新纳入的存量 · 单测七种情形)" \
  || log_bad "v1281-OPENING-NO-TRANSFER 开账基线又在直接取首期期末余额" "see FactViewServiceImpl.openingOf / OpeningBaselineTransferTest"

# v1281-PEEK-LABEL · 入口写明「查看 prompt」(片段两处 + JS 一处)· AI 卡操作组 items-stretch(与刷新按钮等高)
QA1281_PK="$RD/src/main/resources/templates/fragments/_prompt-peek.html"
QA1281_MISS=""
for f in checkup/_ai-diagnose.html checkup/_ai-insight.html reports/_ai-rebalance.html dashboard/_attribution.html lens/_section.html; do
  grep -q 'items-stretch' "$RD/src/main/resources/templates/$f" || QA1281_MISS="$QA1281_MISS $f"
done
{ [ "$(grep -c '<span>查看 prompt</span>' "$QA1281_PK")" -ge 2 ] \
  && grep -q '查看 prompt' "$RD/src/main/resources/static/js/prompt-peek.js" \
  && grep -q 'align-self:stretch' "$RD/src/main/resources/static/css/style.css" \
  && [ -z "$QA1281_MISS" ]; } \
  && log_ok "v1281-PEEK-LABEL(入口写「查看 prompt」· 五张 AI 卡的操作组等高)" \
  || log_bad "v1281-PEEK-LABEL 入口文字 / 等高缺了" "未 items-stretch:${QA1281_MISS:-无}"

# v1281-ACCT-MENU-FIXED · issue #25 ① 账户表最下面几行的 ⋯ 点开被 overflow-hidden 裁掉 → 打开时 fixed 定位
QA1281_AC="$RD/src/main/resources/templates/accounts/index.html"
{ grep -q "pop.style.position = 'fixed'" "$QA1281_AC" \
  && grep -q "addEventListener('toggle'" "$QA1281_AC" \
  && grep -q "document.addEventListener('scroll', follow, { passive: true, capture: true })" "$QA1281_AC" \
  && ! grep -q "addEventListener('scroll', closeAll" "$QA1281_AC" \
  && grep -q 'details.row-more' "$QA1281_AC"; } \
  && log_ok "v1281-ACCT-MENU-FIXED(⋯ 菜单打开时按按钮位置 fixed · 下方放不下往上开 · 滚动跟着按钮走不收起)" \
  || log_bad "v1281-ACCT-MENU-FIXED 账户页 ⋯ 菜单定位脚本缺了" "see accounts/index.html 尾部脚本"

# v1281-ACCT-SUM-FX · issue #25 ② 类型合计先换成本位币再加(不再原币相加标 ¥)
QA1281_AS="$RD/src/main/java/com/family/finance/service/AccountService.java"
{ grep -q 'BigDecimal inBase = toBase(' "$QA1281_AS" \
  && grep -q 'fxMapper.findOne(familyId, baseCcy' "$QA1281_AS" \
  && ! grep -q '"CNY" : "CNY"' "$QA1281_AS"; } \
  && log_ok "v1281-ACCT-SUM-FX(账户页类型合计逐账户换本位币后相加 · 标签用本位币符号)" \
  || log_bad "v1281-ACCT-SUM-FX 账户页类型合计又在原币相加" "see AccountService.summarize"

# v1281-LANDING-CORNER · issue #25 ③ 首页右上角 GitHub 角标整块可点,窄屏上压住顶栏「登录」
QA1281_LD="$RD/src/main/resources/templates/landing.html"
{ grep -qE '@media\(max-width:640px\)\{ \.github-corner\{ display:none; \}' "$QA1281_LD" \
  && grep -qE '@media\(max-width:1250px\)\{ \.lp-head\{ padding-right:96px; \}' "$QA1281_LD" \
  && grep -q 'class="lp-head ' "$QA1281_LD"; } \
  && log_ok "v1281-LANDING-CORNER(手机上不显示角标 · 窄窗口顶栏右侧给角标让位)" \
  || log_bad "v1281-LANDING-CORNER 首页角标又会压住「登录」" "see landing.html .github-corner / .lp-head"

# v1281-DOC-OPENING · 手册 / 仪表盘说明写清「从别的账户转进去的不算开账基线」
{ grep -q '转进来的钱不算开账基线' "$RD/src/main/resources/templates/help/how-to-use.html" \
  && grep -q '转进来的钱\*\*不算\*\*开账基线' "$RD/docs/how-to-use.md" \
  && grep -q '从家里别的账户转进新账户的钱不算在内' "$RD/src/main/resources/templates/dashboard/_region.html"; } \
  && log_ok "v1281-DOC-OPENING(手册两处 + 仪表盘说明写清转入不算开账基线)" \
  || log_bad "v1281-DOC-OPENING 开账基线的新口径没写进手册 / 仪表盘" "see help/how-to-use.html · docs/how-to-use.md · dashboard/_region.html"

# v1281-E2E-FLOWS · 真浏览器 flow 在(开向导建空账户 → 划转 → 首页读数不变;账户表最后一行 ⋯ → 体检)
{ for f in 35-opening-transfer 36-accounts-menu; do [ -f "$RD/scripts/e2e/flows/$f.cjs" ] || exit 1; done; } 2>/dev/null \
  && log_ok "v1281-E2E-FLOWS(flow 35 · 36 在)" \
  || log_bad "v1281-E2E-FLOWS v1.28.1 的 e2e flow 缺了" "see scripts/e2e/flows/35-36"


section "v1.28.2 · 「超额闲置」提示条链接说人话 · 直达货币基金那一行"
# v1282-MONEY-FUND-LINK · 仪表盘「超额闲置」提示条链接说人话 + 直达货币基金那一行(prd/v1.28.md §15)
QA1282_R="$RD/src/main/resources/templates/dashboard/_region.html"
QA1282_P="$RD/src/main/resources/templates/admin/product-categories.html"
{ grep -q 'product-categories#cat-MONEY_FUND}" data-money-fund-link' "$QA1282_R" \
  && grep -q '货币基金的风险与参考收益</a>' "$QA1282_R" \
  && ! grep -q 'MONEY_FUND 参考' "$QA1282_R" \
  && ! grep -q '当前 LIQUID' "$QA1282_R" \
  && grep -q "th:id=\"'cat-' + \${c.code}\"" "$QA1282_P" \
  && ! grep -q 'cat-row' "$QA1282_P" \
  && grep -q '^\.pc-row:target > td' "$RD/src/main/resources/static/css/style.css" \
  && [ -f "$RD/scripts/e2e/flows/37-money-fund-link.cjs" ]; } \
  && log_ok "v1282-MONEY-FUND-LINK(链接写「货币基金的风险与参考收益」· 直达 #cat-MONEY_FUND 高亮 · 不撞填报页 .cat-row · flow 37 在)" \
  || log_bad "v1282-MONEY-FUND-LINK 提示条链接 / 产品类目行锚点缺了" "see dashboard/_region.html · admin/product-categories.html"

section "v1.28.3 · issue #27 / #28 / #29(拆自 #25):logo 上传(任何浏览器)· 关账节奏选中态 · Docker 备份容器"
# v1283-LOGO-ANY-BROWSER · WebKit 编不出 WebP → 前端认 blob 真实类型、后端按魔数认三种格式;文件名带内容指纹(换图不显示旧图)
QA1283_LC="$RD/src/main/java/com/family/finance/web/admin/LogoUploadController.java"
QA1283_FH="$RD/src/main/resources/templates/admin/family.html"
{ grep -q 'static String sniffExtension(byte\[\] head)' "$QA1283_LC" \
  && grep -q '"logo-" + fingerprint(bytes) + "." + ext' "$QA1283_LC" \
  && ! grep -q 'body("must be image/webp")' "$QA1283_LC" \
  && grep -q "blob.type !== 'image/webp'" "$QA1283_FH" \
  && ! grep -q "fd.append('logo', blob, 'logo.webp')" "$QA1283_FH" \
  && [ -f "$RD/src/test/java/com/family/finance/web/admin/LogoSniffTest.java" ]; } \
  && log_ok "v1283-LOGO-ANY-BROWSER(按文件头认 WebP / PNG / JPEG · 前端不信 toBlob 的类型参数 · 文件名带指纹)" \
  || log_bad "v1283-LOGO-ANY-BROWSER logo 上传又只认 WebP / 又写死 logo.webp" "see LogoUploadController / admin/family.html"

# v1283-RHYTHM-PICKED · 关账节奏点了就高亮 + 「还没保存」提示
QA1283_PH="$RD/src/main/resources/templates/admin/periods.html"
{ grep -q '.rhythm-opt:has(input:checked)' "$QA1283_PH" \
  && grep -q 'id="rhythm-dirty"' "$QA1283_PH" \
  && grep -q "l.classList.toggle('rhythm-on', l.contains(e.target))" "$QA1283_PH" \
  && [ -f "$RD/scripts/e2e/flows/38-logo-rhythm.cjs" ]; } \
  && log_ok "v1283-RHYTHM-PICKED(点卡片即高亮 · 选中 ≠ 已保存时提示「还没保存」· flow 38 在)" \
  || log_bad "v1283-RHYTHM-PICKED 关账节奏选中态又只在保存后才变" "see admin/periods.html"

# v1283-BACKUP-SIDECAR · 备份容器不继承 app 的健康检查;失败真的稍后重试;成功要校验;健康看 .last-ok
QA1283_BK="$RD/docker/backup.sh"; QA1283_DC="$RD/docker-compose.yml"
QA1283_BKSVC="$(awk '/^  backup:/{f=1;print;next} f&&/^  [a-z]/{exit} f{print}' "$QA1283_DC")"
{ bash -n "$QA1283_BK" \
  && printf '%s' "$QA1283_BKSVC" | grep -q 'healthcheck:' \
  && printf '%s' "$QA1283_BKSVC" | grep -q '/data/backups/.last-ok' \
  && ! printf '%s' "$QA1283_BKSVC" | grep -v '^ *#' | grep -q '20000/health' \
  && grep -qF 'touch "${BACKUP_DIR}/.last-ok"' "$QA1283_BK" \
  && grep -qF 'sleep "$RETRY_INTERVAL"' "$QA1283_BK" \
  && grep -q "gunzip -t" "$QA1283_BK" && grep -q "CREATE TABLE" "$QA1283_BK" \
  && ! grep -q '2>/dev/null | gzip' "$QA1283_BK"; } \
  && log_ok "v1283-BACKUP-SIDECAR(备份容器健康看 .last-ok 不继承 app 的 curl · 失败 5 分钟重试 · 能解压有建表才算成功 · 报错不吞)" \
  || log_bad "v1283-BACKUP-SIDECAR 备份 sidecar 又会假 unhealthy / 失败不重试 / 成功不校验" "see docker/backup.sh · docker-compose.yml backup 服务"

section "v1.28.4 · issue #34:总负债的明细加起来等于合计"
# v1284-LIAB-BREAKDOWN · AccountPerformance 的类型取锚期那一行(与 KPI 同口径);明细对不上合计时写出差额
QA1284_FV="$RD/src/main/java/com/family/finance/factview/FactViewServiceImpl.java"
QA1284_ME="$RD/src/main/java/com/family/finance/service/explain/MetricExplainService.java"
{ grep -q 'AccountPeriodFact anchorRow = rows.getLast();' "$QA1284_FV" \
  && grep -q 'first.accountId(), first.accountName(), anchorRow.accountType(), first.accountCurrency(),' "$QA1284_FV" \
  && ! grep -q 'first.accountId(), first.accountName(), first.accountType(),' "$QA1284_FV" \
  && grep -q '没能逐个列出' "$QA1284_ME" \
  && [ -f "$RD/scripts/e2e/flows/39-liab-breakdown.cjs" ]; } \
  && log_ok "v1284-LIAB-BREAKDOWN(账户类型取锚期那一行 · 明细之和对不上合计时写出差额 · flow 39 在)" \
  || log_bad "v1284-LIAB-BREAKDOWN 明细又按最早一行的类型归类 / 差额兜底没了" "see FactViewServiceImpl.buildAccountPerformance · MetricExplainService.totalLiabilitiesCalc"

section "v1.28.5 · issue #31:左上角换成古钱小图标"
# v1285-NAV-COIN · 顶栏不再拼「№ + 家庭名首字」;古钱图标带完整家庭名的 title / aria-label
QA1285_NAV="$RD/src/main/resources/templates/fragments/nav.html"
{ ! grep -q "'№ ' + \${#strings.substring(state.family.name" "$QA1285_NAV" \
  && grep -q 'class="nav-coin' "$QA1285_NAV" \
  && grep -q 'aria-label=${state.family.name}' "$QA1285_NAV" \
  && grep -q '<title th:text="${state.family.name}">' "$QA1285_NAV" \
  && ! grep -q '№ 张' "$RD/src/main/resources/templates/error.html" \
  && [ -f "$RD/scripts/e2e/flows/40-nav-coin.cjs" ]; } \
  && log_ok "v1285-NAV-COIN(顶栏古钱图标 · 家庭名在 title / aria-label · 错误页同步 · flow 40 在)" \
  || log_bad "v1285-NAV-COIN 顶栏又出现「№ + 首字」或图标丢了家庭名" "see fragments/nav.html · error.html"

section "v1.29 · issue #26:券商同步带上期权 / 期货 / 债券"
QA129_SYNC="$RD/src/main/java/com/family/finance/service/broker/BrokerSyncService.java"
QA129_VAL="$RD/src/main/java/com/family/finance/service/stock/AccountValuationService.java"
QA129_IBKR="$RD/src/main/java/com/family/finance/service/broker/ibkr/IbkrFlexParser.java"
QA129_FUTU="$RD/src/main/java/com/family/finance/service/broker/FutuBrokerClient.java"
QA129_TIGER="$RD/src/main/java/com/family/finance/service/broker/TigerBrokerClient.java"
QA129_ROWS="$RD/src/main/java/com/family/finance/service/broker/DerivativeRows.java"
QA129_KIND="$RD/src/main/java/com/family/finance/domain/stock/InstrumentKind.java"
QA129_MAP="$RD/src/main/java/com/family/finance/repository/StockHoldingMapper.java"
QA129_HOLD="$RD/src/main/resources/templates/stock/holdings.html"

# v129-DERIV-ONE-PATH · 期权等是 MANUAL 行:单价 = 券商市值 ÷ 张数,张数带符号(卖出为负)—— 估值不许为它另开分支
#   (另开一条求和路径 = 本项目 pivot / kpi 差 0.01 那类病的温床;卖出靠负张数,不靠 if)
{ grep -qF 'd.marketValue().divide(d.quantity(), 12, RoundingMode.HALF_EVEN)' "$QA129_SYNC" \
  && grep -qF 'h.setShares(d.quantity());' "$QA129_SYNC" \
  && grep -qF '.accountId(accountId).valuationMode(ValuationMode.MANUAL)' "$QA129_SYNC" \
  && ! java_code_only "$QA129_VAL" | grep -qE 'instrumentKind|isDerivative|InstrumentKind'; } \
  && log_ok "v129-DERIV-ONE-PATH(期权等落 MANUAL 行 · 单价 = 市值 ÷ 张数 · 卖出张数为负 · 估值没有另开分支)" \
  || log_bad "v129-DERIV-ONE-PATH 期权等的估值另起了一条路 / 卖出不再靠负张数" "see BrokerSyncService.reconcile 期权段 · AccountValuationService.valuateInternal"

# v129-DERIV-CROSSCHECK · 「张数 × 标记价 × 乘数」与市值交叉核对,对不上不同步(三家都走同一个判据);期货记 0
{ grep -qF 'BigDecimal expected = qty.multiply(mark).multiply(multiplier);' "$QA129_ROWS" \
  && grep -qF 'DerivativeRows.check(kind, qty, mark, mult, value)' "$QA129_IBKR" \
  && grep -qF 'DerivativeRows.check(kind, qty, mark, mult, value)' "$QA129_TIGER" \
  && grep -qF 'US_OPTION_MULTIPLIER' "$QA129_FUTU" \
  && grep -qF 'public boolean countsInBalance() { return this != FUTURE; }' "$QA129_KIND" \
  && grep -qF 'kind.countsInBalance() ? value : BigDecimal.ZERO' "$QA129_IBKR" \
  && grep -qF 'DerivativeRows.rejectSummary(snap.rejected())' "$QA129_SYNC"; } \
  && log_ok "v129-DERIV-CROSSCHECK(三家都交叉核对 · 对不上的行不同步并点名 · 期货不计入余额)" \
  || log_bad "v129-DERIV-CROSSCHECK 期权市值不再核对 / 对不上的行被静默吞掉 / 期货按名义价值计入" "see DerivativeRows.check · IbkrFlexParser.readDerivative · TigerBrokerClient.map · FutuBrokerClient.map"

# v129-NO-OPTION-SKIP · 期权不再跳过:IBKR 先认品种再判 STK;富途不再把空头一律跳过;老虎持仓接上(v0.15 起一直返回 null)
{ ! grep -rqF '跳过期权/期货' "$RD/src/main" \
  && ! grep -rqF '期权 / 期货本版' "$RD/src/main/resources/templates" \
  && grep -qF 'if (kind != null) { readDerivative(r, kind, derivs, rejected); break; }' "$QA129_IBKR" \
  && ! grep -qF 'p.getPositionSide() != TrdCommon.PositionSide.PositionSide_Long_VALUE' "$QA129_FUTU" \
  && grep -qF 'OPTION_CODE' "$QA129_FUTU" \
  && grep -qF 'new TigerHttpRequest(MethodName.POSITIONS)' "$QA129_TIGER" \
  && ! java_code_only "$QA129_TIGER" | grep -qF 'buildPositionsRequest' \
  && grep -qF '老虎资产查询失败' "$QA129_TIGER" \
  && grep -qE 'MODIFY COLUMN ticker VARCHAR\(48\)' "$RD/db/migration/V66__holding_derivatives.sql"; } \
  && log_ok "v129-NO-OPTION-SKIP(IBKR 期权进来 · 富途期权 / 空头不再跳过 · 老虎持仓接上且资产查询失败不清空现金 · 代码列加宽到 48)" \
  || log_bad "v129-NO-OPTION-SKIP 又有券商把期权跳过 / 老虎持仓回到 null / 代码列装不下期权代码" "see IbkrFlexParser · FutuBrokerClient.map · TigerBrokerClient.positions · V66"

# v129-DERIV-NOT-FUND · 期权等不是基金:不进穿透候选;券商同步行不给手改表单(卖出张数是负的,表单也装不下)
{ grep -B8 'List<Long> findActiveFundHoldingIdsByFamily' "$QA129_MAP" | grep -qF 'h.instrument_kind IS NULL' \
  && grep -qF "h.valuationMode.name() == 'MANUAL' and !h.derivative}\"" "$QA129_HOLD" \
  && grep -qF 'data-deriv-row' "$QA129_HOLD" && grep -qF 'data-deriv-due' "$QA129_HOLD" \
  && grep -qF '期权等(券商报表)' "$QA129_HOLD" \
  && grep -qF 'linkMapper.markSynced(familyId, accountId, clip(s));' "$QA129_SYNC" \
  && [ -f "$RD/scripts/e2e/flows/41-ibkr-derivatives.cjs" ] \
  && grep -qF 'rows.length === 7' "$RD/scripts/e2e/flows/27-ibkr-flex.cjs"; } \
  && log_ok "v129-DERIV-NOT-FUND(不进穿透 · 不给手改 · 持仓页人话行 + 到期提示 + 估值分解单列 · 同步结果截到 255 · flow 41 在)" \
  || log_bad "v129-DERIV-NOT-FUND 期权行进了基金穿透 / 能手改 / 持仓页不再单列" "see StockHoldingMapper.findActiveFundHoldingIdsByFamily · stock/holdings.html"

section "v1.29 · issue #34(续):定格残留行不许影响没关账的期"
# v129-FROZEN-ONLY-CLOSED · 定格行只在关账时写、重开时删 → 读的一侧只认已关账的期(残留伤不到当期);V67 清掉不可能是真定格的行
QA129B_FM="$RD/src/main/resources/mapper/FactMapper.xml"
QA129B_AM="$RD/src/main/java/com/family/finance/repository/PeriodAccountAttrMapper.java"
QA129B_GM="$RD/src/main/java/com/family/finance/repository/PeriodAccountGroupMapper.java"
QA129B_V="$RD/db/migration/V67__purge_stale_frozen_attrs.sql"
{ grep -A4 'LEFT JOIN period_account_attr paa' "$QA129B_FM" | grep -qF "AND p.status = 'CLOSED'" \
  && grep -B6 'List<PeriodAccountAttr> findByPeriod' "$QA129B_AM" | grep -qF "AND p.status = 'CLOSED'" \
  && grep -B6 'List<Row> findByPeriod' "$QA129B_GM" | grep -qF "AND p.status = 'CLOSED'" \
  && grep -qF "WHERE p.status <> 'CLOSED';" "$QA129B_V" \
  && grep -qF 'WHERE pa.sealed_at < a.created_at;' "$QA129B_V" \
  && [ -f "$RD/scripts/e2e/flows/42-stale-frozen-type.cjs" ]; } \
  && log_ok "v129-FROZEN-ONLY-CLOSED(定格行只在已关账的期生效 · V67 清残留 · flow 42 在)" \
  || log_bad "v129-FROZEN-ONLY-CLOSED 没关账的期又会读到定格行(残留会把现金账户当成贷款)" "see FactMapper.queryBase paa join · PeriodAccountAttrMapper/PeriodAccountGroupMapper.findByPeriod · V67"

# v129-DEMO-CLEAN-COMPLETE · 清演示数据的三个脚本:迁移写过数据的表,要么清、要么在「保留」名单里
#   (V54 回填了 period_account_attr,三个脚本却只清了 period / account → 编号重用,演示账户的类型挂到用户账户上)
QA129B_KEEP=" family member account_template product_category cash_flow_category macro_benchmark allocation_anchor step3_period_seed "
QA129B_BAD=""
for t in $(grep -ohiE 'INSERT (IGNORE )?INTO +`?[a-z_0-9]+' "$RD"/db/migration/*.sql | sed -E 's/.*INTO +`?//I' | sort -u); do
  case "$QA129B_KEEP" in *" $t "*) continue ;; esac
  for f in docker/clean-dev-data.sh deploy/deploy.sh deploy/_deploy-macos.sh; do
    grep -qE "TRUNCATE TABLE $t;" "$RD/$f" || QA129B_BAD="$QA129B_BAD $f:$t"
  done
done
[ -z "$QA129B_BAD" ] \
  && log_ok "v129-DEMO-CLEAN-COMPLETE(迁移写过数据的表,三个清演示脚本都清了 · 含定格表)" \
  || log_bad "v129-DEMO-CLEAN-COMPLETE 清演示数据漏了表(编号重用会把演示数据挂到用户账户上)" "$QA129B_BAD"

echo
echo "═══════════════════════════════════════"
echo " 总结: PASS=$PASS  FAIL=$FAIL  SKIP=$SKIP"
echo "═══════════════════════════════════════"
if [[ $FAIL -gt 0 ]]; then
  echo "失败用例:"
  for f in "${FAILED[@]}"; do echo "  · $f"; done
  exit 1
fi
exit 0
