#!/usr/bin/env python3
"""family_id 隔离普查(v1.24)· 护栏 v1240-FAMILY-ISOLATION 的引擎。

逐条扫 repository/ 下每个 @Select/@Insert/@Update/@Delete 的 SQL:
凡是碰到「家庭作用域的表」、却没有在**过滤位置**出现 family_id 的,就是违规。

为什么要自己写而不是靠人看:
  · 156 条违规是 2026-09-20 这次普查扫出来的,全是「一直都在、一直没人发现」的;
  · 靠人列调用方清单必漏(memory feedback_metric_refactor_baseline),靠 code review
    更漏 —— 漏隔离不报错、不告警,只是数字变大。

**只认过滤位置**:`SELECT id, family_id, ... WHERE id = #{id}` 这种把 family_id
查出来却不拿它过滤的,第一版审计当成绿的,漏掉了 10 条(含 AccountMapper /
PeriodMapper / GoalMapper 三个最常用的 findById)。

退出码:0 = 只剩已登记的例外;1 = 有新增违规或例外清单对不上。
"""
import re
import sys
import pathlib

# ── 表的分类(2026-09-20 对着 beta 的 information_schema 逐张核过)─────────────
DIRECT = set("""account account_group ask_access_audit ask_access_token ask_conversation
ask_unmet_need audit_log backup_log expense_account_rule expense_category expense_import_batch
expense_merchant_rule family_goal family_notify_config family_runtime_config fx_rate holding_import
lens_board member metrics_recompute_log period period_member_cashflow rebalance_advice_cache
rebalance_plan report_reminder_log review_ai_cache stock_valuation_event holding_share_event principal_adjustment""".split())

INDIRECT = set("""account_group_member account_insurance_policy broker_link cash_flow goal_account
goal_ai_report holding_allocation holding_import_item period_account_attr period_account_group
period_member_completion period_reopen_log period_snapshot rebalance_plan_item snapshot_todo
stock_holding transfer ask_citation ask_message ask_tool_call""".split())

SCOPED = DIRECT | INDIRECT

# ── 合法例外清单 · 改这里必须同时改对应 mapper 的 javadoc ─────────────────────
#
# 每一条都必须满足「加 familyId 会让它变**更**错」,而不是「加起来麻烦」。
# 新增例外 = 改这份清单 + 在方法上写明理由,两边都要动,防止顺手放行。
EXCEPTIONS = {
    ('AskAccessTokenMapper.java', 'findByHash'):
        '凭据解析入口 · familyId 是它的产物不是输入',
    ('AskAccessTokenMapper.java', 'countUsableAll'):
        'verify 阶段还没解析出家庭 · 只返回一个计数',
    ('MemberMapper.java', 'findByUsername'):
        '登录入口 · familyId 是它的产物不是输入',
    ('MemberMapper.java', 'existsUsername'):
        '用户名是全局唯一命名空间 · 按家庭拆开 = 允许重名 = 登录坏掉',
    ('MemberMapper.java', 'findSeedPlaceholders'):
        '首次部署引导跑在任何家庭上下文之前',
    ('StockHoldingMapper.java', 'findDistinctAutoTickersByMarket'):
        '拉价 cron · 只返回 (ticker, market) 公开行情键 · 不含任何家庭数据',
}

ANN = re.compile(r'@(Select|Insert|Update|Delete)\s*\(')
SIG = re.compile(r'^\s*(?!@)([\w<>,\[\]\. ]+?)\s+(\w+)\s*\(([^;]*?)\)\s*;', re.M | re.S)


def statements(src):
    """按注解切出每条 SQL(处理 text block 与嵌套括号),并带上它属于哪个方法。"""
    out = []
    for m in ANN.finditer(src):
        i, depth, n = m.end(), 1, len(src)
        while i < n and depth:
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                i = n if j < 0 else j + 3
                continue
            c = src[i]
            if c == '"':
                i += 1
                while i < n and src[i] != '"':
                    i += 2 if src[i] == '\\' else 1
            elif c == '(':
                depth += 1
            elif c == ')':
                depth -= 1
            i += 1
        sig = SIG.search(src, i)
        out.append((m.group(1),
                    src.count('\n', 0, m.start()) + 1,
                    src[m.end():i - 1],
                    sig.group(2) if sig else '?'))
    return out


def tables(sql):
    s = re.sub(r'<[^>]+>', ' ', sql)      # mybatis 标签
    s = re.sub(r'--[^\n]*', ' ', s)        # 行注释
    found = set()
    for kw in (r'from', r'join', r'update', r'into', r'delete\s+from'):
        found |= {t.lower() for t in re.findall(kw + r'\s+`?(\w+)`?', s, re.I)}
    return found


def isolated(sql):
    """family_id 必须出现在**过滤位置**;只出现在 SELECT 列表里不算。"""
    return bool(re.search(r'family_id\s*(=|IN\b|<)', sql, re.I) or re.search(r'familyId', sql))


# 把同文件里的 `String XXX = """…""";` 常量解析出来,拼进 SQL 再判 ——
# v1.24 起口径 A 的 WHERE 块是编译期常量(EXPENSE_A_WHERE),
# 注解体里只有 `+ EXPENSE_A_WHERE`,不展开的话会把 expenseBreakdown 误报成漏隔离。
CONST = re.compile(r'String\s+([A-Z_][A-Z0-9_]*)\s*=\s*"""(.*?)"""\s*;', re.S)


def constants(src):
    return {m.group(1): m.group(2) for m in CONST.finditer(src)}


def expand(body, consts):
    for name, val in consts.items():
        if name in body:
            body = body.replace(name, val)
    return body


def main(root):
    violations, seen_exc = [], set()
    for p in sorted(pathlib.Path(root).glob('*.java')):
        src = p.read_text(encoding='utf-8')
        consts = constants(src)
        for kind, line, body, method in statements(src):
            body = expand(body, consts)
            if not (tables(body) & SCOPED) or isolated(body):
                continue
            key = (p.name, method)
            if key in EXCEPTIONS:
                seen_exc.add(key)
                continue
            violations.append(f"{p.name}:{line} @{kind} {method}")

    stale = set(EXCEPTIONS) - seen_exc
    for v in violations:
        print(f"VIOLATION {v}")
    for s in sorted(stale):
        print(f"STALE-EXCEPTION {s[0]}:{s[1]} 已经不违规了,从清单里删掉")
    if not violations and not stale:
        print(f"OK {len(EXCEPTIONS)} 条已登记例外,无新增违规")
        return 0
    return 1


def insert_guard(repo_root, src_root):
    """业务代码必须走 insertOwned/upsertOwned,不许调裸 insert/upsert。

    **按声明类型认,不按字段名认** —— 字段名会撞:StockPriceFetcher 里的
    `snapshotMapper` 是 StockPriceSnapshotMapper(全局行情快照,没有家庭维度),
    和 SnapshotMapper(period_snapshot)同名不同类。按名字判会误报。
    """
    owned = set()
    for p in pathlib.Path(repo_root).glob('*.java'):
        if re.search(r'default void (insert|upsert)Owned', p.read_text(encoding='utf-8')):
            owned.add(p.stem)
    if not owned:
        return ['没找到任何 insertOwned/upsertOwned —— 断言机制被拆掉了?']

    bad = []
    for p in sorted(pathlib.Path(src_root).rglob('*.java')):
        if '/repository/' in str(p):
            continue
        src = p.read_text(encoding='utf-8')
        # 字段名 → 声明类型
        fields = dict((m.group(2), m.group(1))
                      for m in re.finditer(r'\b(\w*Mapper)\s+(\w+)\s*[;,)=]', src))
        for m in re.finditer(r'\b(\w+)\.(insert|upsert)\(', src):
            if fields.get(m.group(1)) in owned:
                bad.append(f"{p.name}:{src.count(chr(10), 0, m.start()) + 1} "
                           f"{m.group(1)}.{m.group(2)}( → 应该用 {m.group(2)}Owned")
    return bad


if __name__ == '__main__':
    repo = sys.argv[1] if len(sys.argv) > 1 else 'src/main/java/com/family/finance/repository'
    if '--insert-guard' in sys.argv:
        bad = insert_guard(repo, 'src/main/java/com/family/finance')
        for b in bad:
            print(f"VIOLATION {b}")
        if not bad:
            print("OK 业务代码都走带断言的 insertOwned/upsertOwned")
        sys.exit(1 if bad else 0)
    sys.exit(main(repo))
