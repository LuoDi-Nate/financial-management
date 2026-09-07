# AGENTS.md · 家庭账房 项目操作手册

> **这是每次迭代的单一入口。动代码前先读第 5 节(流程)+ 第 7 节(联动链)+ 第 8 节(护栏)。**
> 目的:把"项目是什么、怎么改、改了要同步什么、别再踩哪些坑"沉淀成一份随代码走的文档,让反复犯的错收敛掉。
> 维护规则:发现新坑 / 新联动 → **当场加一行**,能机器校验的**配一个 `scripts/qa-run.sh` 守护**。文档靠人记会漏,守护才是兜底。

---

## 1. 项目是什么(评估任何 FR 的标尺)

**定位**(用户拍板):**不是流水/记账 APP**,而是「中产家庭**摸清资产** + **辅助管理让钱增值不贬值**」的工具。填"账本类只到记录、没到决策辅助"的空白。

**两条产品轴** —— 任何 FR 先问"服务哪条轴?都不服务就砍":
1. **摸清** — 跨账户/类型/币种/产品 看清家庭资产全貌(净资产 + 配置 + 流动性 + 趋势)。
2. **增值不贬值** — 配置 vs 目标差 / 调仓建议 / 通胀对照 / 收益基准 / 提前还贷决策 / 应急金不闲置。

**硬约束**:夫妻每月**异步、10 分钟内**完成全部录入。任何"更全面但要更频繁录入"的功能都要拒。颗粒度**永远停在账户级月度快照**,不做单券持仓明细。

**刻意不做**(反例,别提议):逐笔流水账 / 定投提醒 / 预算包络 / 消费品类细化 / AA 账本 / 报销 / 券商 API 直连 / 银行账单 OCR / Docker 之外引入 K8s。

**恒等式(红线)**:`ΔNetWorth(M) = 人赚(净流入) + 钱赚(投资损益) + 开账基线(M)`

- **开账基线**(v0.13 起)= 本期**首次出现**账户的期末净值合计,是「本来就有、现在才开始记」的外部资本纳入,
  既不算人赚也不算钱赚 —— 早期版本的恒等式漏了这一项,**别再照抄两项版**。
- 处理方式是**显式暴露差额,不抛异常**(v1.10 报表页资金流瀑布):容差 ¥1(四舍五入噪声),
  不闭合时页面写出差额与原因(开账基线可解释 / 来源不明),因为封板报表宁可让人看到"对不上多少"
  也不能假装闭合。历史文档里的 `DataInconsistencyException` **代码里从来不存在**,已删除该说法`。账户级 `PnL = ΔNW − 净流入 − 净划转`(外部流入被剔除 → 收入不该抬高收益率)。

用户:Java 工程师,有自己的服务器 + 域名;**妻子非技术** → UI 先保证她能独立完成。中国大陆环境。**对话一律中文**。

---

## 2. 环境拓扑(别搞错 · 致命级)

- **beta** = 开发基线(维护者本机 · systemd `finance` · 本地 `:20000`)· **prod** = 维护者的独立服务器 · 在线 demo `https://dixi-token.top`(README 有 demo 账号)。两者是**不同机器、不同数据**。
- 规则:开发/自测/截图都在 **beta**;beta 通过 + 维护者验收后才上 **prod**。别把 demo 域名当成 beta。
- prod 操作**仅限 `release-prod` skill 编排流程内**(受控授权边界)。
- 单家庭模式 `family_id=1`。
- **具体值(IP / SSH / 凭据 / 部署路径 / sudoers)见本地 `AGENTS.local.md`(git-ignored,不入公开库)**。

---

## 3. 技术栈 & 关键领域概念

**栈**:Spring Boot 3.3 + Java 21 + MyBatis 3 + MySQL 8;Thymeleaf + HTMX 1.9 + Chart.js 4 / ECharts;**无 SPA、无构建管线**;版本化 SQL 迁移 + sha256(无 Flyway)。UI = **晚清账册风**,**禁 emoji · 用 inline SVG**。

**必须先懂的领域概念**(改相关代码前务必对齐):

- **双轨收入**:`period_member_cashflow`(PMC「2框」· 家庭成员月度总收入/总支出 · 无账户关联)vs 账户级 `cash_flow`(每账户逐笔 · 驱动余额/XIRR/PnL)。**FR-142 起收入侧以 `cash_flow` 汇总为准**(历史 PMC 收入 >0 时兜底优先,防双计);支出侧仍 PMC 优先。
- **股票账户估值**:账户余额 = Σ 持仓估值。持仓 `ValuationMode`:**AUTO**(上市 · ticker+shares · 自动拉价)/ **MANUAL**(未上市如字节 · **v0.12.1 起 = 股数 × 单股手填估值**)/ **CASH**(券商现金 · 按币种)。估值刷新(`AccountValuationService.refreshAllForFamily`)会**重算并覆盖 `period_snapshot`** → 股票收入必须落 **CASH 行**(现金)或 **+持仓股数**(RSU),再 `applyDeltaToBalance` 立即入账,别直接改余额(会被刷新覆盖)。
- **`is_adjustment`(V33)**:手动改股票现金行的本金进出 → `cash_adjust`/`is_adjustment=1`,**剔出 PnL、不计家庭收入**;真实外部收入 `is_adjustment=0`。
- **币种三层**(极易错,见 L2/L3):
  - **账户币种** — `cash_flow.amount`、`period_snapshot.end_balance`、持仓 `manual_value` 都存这个。
  - **本位币**(`family.base_currency`,默认 CNY)— 家庭/跨账户**聚合**必须 `× fxToBase` 换到本位币,不能裸加。
  - **视图币种** — dashboard 镜头切换;**比值类 KPI 三币种必须完全相等**,金额按 fx 缩放。
- **fact 层**:`FactViewServiceImpl` + `FactMapper.queryBase` 产出 `AccountPeriodFact`(`incomeBase`/`expenseBase` 已换本位币);dashboard/reports/KPI 都从这层取。

---

## 4. 页面地图(顶栏 7 tab + 公开页)

| tab / 页 | 路由 | 干什么 | 关键文件 |
|---|---|---|---|
| 仪表盘 | `/dashboard` | 净资产/趋势(CPI+M2 线)/配置环/KPI 横条/**人赚vs钱赚拆解**/AI 洞察 | `dashboard/index.html` + `_region.html` · `DashboardController` · `CashflowSplitView` |
| 填报 | `/entry` | 每月录入:账户余额快照 + 收支 + 划转;**收入侧结构化**(现金/股票·联动持仓);退休目标折叠于此 | `entry/index.html` + `_row.html` + `_income-stock.html` · `EntryController`/`EntryService` |
| 账户 | `/accounts` | **9 类**账户簿(现金/股票/理财/加密/贵金属/房产/负债/保险/其他) · 按成员归集 · 划转/体检/账本/导出;股票账户 → 持仓管理 | `accounts/*` · `stock/holdings.html` · `StockHoldingController` |
| 报表 | `/reports` | **月度封板快照**(v1.10 三区):一区 本期封板(资产负债表/资金流瀑布/环比同比/归因)· 二区 结构与风险(集中度/流动性分层)· 三区 趋势(**range 只作用于此**)· 账期筛选 + 长文目录 TOC | `reports/*` · `_toc` |
| 目标 | `/goals` | FIRE 退休 / 教育 / 应急金 · 三情景预测 | `goals/*` |
| 资产体检 | `/checkup` | 4 维诊断(配置/风险/流动性/收益)+ AI 调仓 · **长文目录 TOC** | `checkup/*` · `_toc` |
| 管理 | `/admin` | **所有运营参数热改**(品牌/成员/周期/提醒/汇率/数据源/阈值/aksk/key)· 改即生效不重启 | `admin/*` |
| 公开 | `/`(landing) `/login` `/onboarding` | 落地页(含工程数字带)/ 登录 / 首次引导 | `landing.html`(`data-stat`)· `auth/*` · `onboarding/*` |

---

## 5. 研发迭代全流程(功能级 = 硬 gate,逐步停等审)

判定量级:**新 FR / 新页面 / 新表 / 多文件 = 功能级**,走全流程;纯 bug fix / 文案 / 单文件小改可直接做(仍守第 7、8 节)。不确定先问。

```
1. PRD        prd/vX.Y.md(用户视角 FR · 目标/非目标/验收标准)· **照 prd/_TEMPLATE.md 的骨架写**
   + preview  preview/vX.Y/<feature>.html(静态 mockup · 复用 ../assets/style.css + 晚清账册骨架)
   → ★ 用户评审(停,等明确通过)
2. TDD        tech-design/vX.Y.md(每个关键决策:2-3 备选 + 取舍 + 选定理由 + 为什么不选)· **照 tech-design/_TEMPLATE.md 的骨架写**
   → ★ 用户评审(停,等明确通过)
3. 代码 + QA  实现 + 单测 + docs/qa-cases.md 用例 + scripts/qa-run.sh 守护 + scripts/e2e.sh 主线
4. 自测       mvn -o test(全绿) · bash scripts/qa-run.sh(静态守护) · bash scripts/e2e.sh(端到端真验收) · 无头截图视觉验收
5. 部署 beta  sudo cp jar + restart → 走用户真实点击路径复验(顶栏进入 · 手机视图 · 落地卡片)
   → ★ 用户在 beta review
6. 发布 prod  用户回精确串 release vX.Y.Z → release-prod skill(见第 10 节)
```

- **每个 gate 单独产出、单独等审**;不要一轮把 PRD/TDD/code 做完再给看(那不是 gate 是既成事实)。
- **达成一致后**:端到端做到完,中途**不擅自** checkpoint / 重排 / 问"要不要继续";工程量超预期也不停(逐块 commit + 自验推进)。合法的停只有上面的 ★ gate。
- **「不要中间停下来」不豁免 PRD/TDD gate**(2026-08-13 复盘):v1.11 那批 13 条反馈,我把
  「全部做完不要中途停」理解成"连设计阶段一起省",直接开发 —— 代码/护栏/qa-cases 都同步了,
  但 `prd/v1.11.md` + `tech-design/v1.11.md` **压根没写**,事后才补。那句话约束的是**开发阶段不许中断**,
  不是取消设计。**批量反馈同样按功能级走**,PRD 可以短,但必须有 —— 代价是真实的:
  ① 有一条反馈只改了一处渲染路径就交付,被**第二次打回**(PRD 里本该把"所有渲染路径"写成验收标准);
  ② 有三条反馈要的交付物是**「你的判断和理由」**,当时只存在于对话里,今天复查无处可查。
- 文档与代码**必须同步**(见 L1/L5):任何代码改动都同步 prd / tech-design / CHANGELOG / docs/qa-cases,别等用户提醒。

---

## 6. 关键文件 / 路径 & 用法速查

| 路径 | 用途 | 用法 |
|---|---|---|
| `prd/_TEMPLATE.md` | **PRD 骨架** | 大厂模板按本项目裁过;**按版本大小分档**(补丁 4 节 / 大版本 12 节)· 不适用的节写「不适用 · 理由」而不是删 |
| `tech-design/_TEMPLATE.md` | **TDD 骨架** | 同上;选型对比只写在这边,PRD 不重复 |
| `prd/vX.Y.md` | 需求(用户视角 FR) | 新版本新建;v0.1 已封板别改 |
| `tech-design/vX.Y.md` | 技术方案(选型+取舍) | 实施权威源 |
| `preview/vX.Y/<f>.html` | PRD 阶段交互预览 | 复用 `preview/assets/style.css` + 4 字体 + `kpi/pill/paper-card/eyebrow/btn-ink` 类;**别用废弃 `preview/pages/`** |
| `db/migration/V<n>__*.sql` | schema 迁移 | 只增不改已发布的;全 backward-compat(见 L7)。跑:`DB_USER=… DB_PASS=… DB_NAME=… bash db/apply.sh`(prod 读 `/etc/finance.env`;本地值见 `AGENTS.local.md`) |
| `scripts/qa-run.sh` | 黑盒静态守护(广度) | `bash scripts/qa-run.sh` · 加守护参考 `v12-*` 写法 |
| `scripts/e2e.sh` | 端到端真验收(深度 · mysqldump 快照/还原) | `bash scripts/e2e.sh` · 断言用**增量**不用绝对值 · 不用 pipefail |
| `docs/qa-cases.md` | QA 用例登记 | 每功能加一段 |
| `CHANGELOG.md` | 版本记录 | 每版一段 |
| `src/main/resources/templates/landing.html` | 落地页**工程数字带** | `data-stat` version/tests/migrations/blackbox 必须与现状一致(release preflight 硬门) |
| `.claude/skills/release-prod/` | 发布 prod skill | 见第 10 节 |
| `.claude/skills/auto-issue-killer/` | GitHub issue 预处理 skill | `bash .claude/skills/auto-issue-killer/issue.sh scan`;先 👀 → 打 label → 以「作者的 agent 助手」双语回复;bug 开 `issue/<n>-<slug>` 分支修到 beta 为止,需求先写 PRD+preview,**发布/合并/关 issue 一律等作者批准** |
| `deploy/deploy.sh` `rollback.sh` | prod 部署/回滚 | 幂等 + 失败自动回滚 |
| `AGENTS.md`(本文) | 项目操作手册 | 每次迭代必过 |
| memory `~/.claude/projects/-home-finance-financial-management/memory/` | Claude 跨会话记忆 | 一事一文件 + `MEMORY.md` 索引;详细规则见各 `feedback_*` |

**无头截图视觉验收**(排版类问题渲染 beta 实际看):用 playwright-core + 本地 chromium 登录 beta 截图;套路 = login → goto 页 → `waitForTimeout(2500)`(等 Chart 画完)→ screenshot。**具体工具路径 / 依赖(libXdamage、CJK 字体)/ demo 账号见 `AGENTS.local.md`** 与 memory `reference_headless_screenshot`。

---

## 7. 联动不变量登记表(改 X → 必须同步 Y,否则下游静默错)

| # | 触发 | 必须同步 | 守护 |
|---|---|---|---|
| L1 · 收支口径 | 改收入/支出**来源口径**(PMC↔`cash_flow`)、`is_adjustment` 语义 | `FactViewServiceImpl`(`netInflowIncome/Expense`/`cashflowBreakdown`/`familyXirr` 外部流入)· `HouseholdCashflowService`(`incomeBlend`/月均/储蓄率)· **dashboard `CashflowSplitView.empty()`**(空态别只看 PMC filledMembers)· `reports/_savings` 空态 · 收入列表展示/合计 | `v12-INCOME-KOUJING/EMPTY` · `CashflowSplitEmptyTest` · `CashflowBreakdownTest` |
| L2 · 账户币种 vs 本位币 | 任何**展示/汇总** `cash_flow.amount` / `period_snapshot.end_balance` | 存的是**账户币种**;单账户展示用账户币种符号(`MoneyFormat`);**家庭/跨账户聚合必须 ×`fxToBase`**,不裸加 ¥;缺直接汇率反向取倒数兜底(`EntryController.toBaseAmount`/`AccountValuationService.resolveFxRate`) | `v12-INCOME-FX` |
| L3 · 比值类 KPI 币种不变性 | 新增/改**比例类**指标 | 三视图币种下**比值完全相等**、金额按 fx 缩放;两比值相比用**相减(pp)**非相除 | `v05-CCY-INV-1` · `v08-CCY-INV-3` · `CurrencyInvarianceTest` |
| L4 · 长文目录 TOC | `reports`/`dashboard`/`checkup` 加/改/删/调序 section | 同步该页 `fragments/_toc` + `static/js/toc.js` + section `id` 锚点 | `v05-TOC-1/2/3` |
| L5 · 主页数字带 + 文档 | 出新版本 / 加迁移 / 改单测数或黑盒数 | `landing.html` `data-stat`(version=prd 个数 · migrations=`V*.sql` 个数 · tests/blackbox=README)+ README「N 单元 / N 黑盒」+ `prd`+`tech-design`+`CHANGELOG`+`docs/qa-cases` | release preflight(硬门)· `v09-LAND-6` · `v07-CLEAN-2` |
| L6 · 股票估值/持仓模型 | 改 `stock_holding` 字段 / `ValuationMode` / 计值 | `AccountValuationService.valuateInternal`(AUTO/MANUAL/CASH 三分支)· 迁移 backward-compat(prod 老数据折算总值不变)· 持仓管理 UI + 收入侧联动 | `v12-MANUAL-SHARES` · `v03-STOCK-*` |
| L7 · prod backward-compat | 任何 schema/代码/部署改动 | 先想对线上现有数据影响:迁移只 `ADD COLUMN NULL`/数据折算不破坏;**回滚只回 jar 不回 DB → 迁移须向前兼容老 jar** | release preflight 迁移提示 |
| L8 · UI 规范 | 新增 UI 文案/图标 | **禁 emoji**,用 inline SVG(Feather 24×24 `stroke=currentColor`);入口/按钮命名**避免技术词**(集成/API/接口)让非技术家庭成员看得懂 | `TODO: no-emoji grep 守护` |
| L9 · 运营参数 | 新增阈值/aksk/节奏/手机号等运营配置 | 走**管理页**配(DB > env > 代码默认 三层 fallback)· 不写服务器配置文件;涉及外部平台接入配一键测试入口 | 人工 |
| L10 · 敏感值不入公开库 | 写文档/脚本/配置涉及 IP / SSH / 域名后台 / 凭据 / 密钥 / 部署路径 / 邮箱 / **prod 真实金额(净资产 / 账户余额 / 收支)** 等 | **不进任何 tracked 文件**(仓库是公开开源库)· 具体值放 git-ignored `AGENTS.local.md` 或 Claude memory · 正文只留占位/通用说法 · 误提交后需**重写历史 + 强推**(`git filter-repo`)清除 | `vSEC-1`(扫 tracked 文件里 URL/SSH 上下文的公网 IP) |
| L11 · 功能入口可见性 | **收纳 / 精简 / 去杂**类 UI 改动;或新增能力 | diff 里每个被移除/移动/塞进折叠容器的 `th:href` 逐个确认在别处仍**一眼可见**;新能力同时登记进 `scripts/entry-points.json`。判据见 `docs/entry-points.md`:能力入口必须 `obvious`,`⋯`/`details` 只放低频维护动作(归档/导出/恢复) | `v1623-ENTRY-VIS`(运行时·PC+移动)· `v15-ENTRY-1`(静态·券商不得落在 `row-more-pop` 里) |
| L12 · 指标口径锚点 | 新增/修改任何指标,或改取数窗口 | 取数是 `账户 × 账期` 全交叉且**不过滤 `period.status`** → 进行中账期会成为「最后一期」。**存量类**(净资产/总资产/总负债/流动资产/环比)锚 `lastPeriodId`;**收益类**(本月资产收益/XIRR/TWR/YTD/人赚钱赚/储蓄率)必须走 `FactSlice.returnPeriodIds()`(最近 ≤12 个已关账期)。三条硬约束:① `openingBaselineLast` **必须仍锚 last**(否则「本期怎么变」卡的 ΔNW = 人赚 + 钱赚 + 开账基线 恒等式破掉);② 同名指标跨页必须取到**同一批账期**(各页窗口宽度本就不同:报表锚已关账期 / 仪表盘 −12 月 / 体检 −11 月);③ 换锚必须在**页面上显示口径期**并同步 tooltip —— 口径变了不说等于制造新困惑 | `v1630-CLOSED-ANCHOR` · `ClosedPeriodAnchorTest` |

| L13 · 封板快照定格性 | 报表页一区/二区加任何指标 | 只能经 `SealedPeriodService`(签名里**没有 range**,传不进去)· 前两区在不同 range 下渲染必须**逐字相同** | `v110-SEALED-SINGLE-ENTRY` / `v110-SNAPSHOT-RANGE-INVARIANT` |
| L14 · 归档的时间语义 | 任何按 `archived_at` 过滤事实的 SQL | 必须 `archived_at IS NULL OR archived_at > p.period_end` —— 裸 `IS NULL` 会让归档动作**抹掉该账户全部历史**,一个整理动作改写去年的报表 | `v110-ARCHIVED-TIME` |
| L15 · 指标口径版本 | 任何影响封板指标**数值**的口径改动 | `MetricFormulaVersion.CURRENT` +1 并在变更表记一行(封板抬头会显示「口径 vN」,用户据此分辨数字是哪套口径算的) | `v110-FORMULA-VERSION` |

**新链怎么加**:出现"改 A 漏了 B"事故 → 加一行(触发/必须同步/守护)+ `qa-run.sh` 加静态 grep 把它网住,下次它自己 fail。

---

## 8. 全局护栏 / 纪律(踩过的坑 · 收敛清单)

**流程 / 交付**
- **验收走用户真实点击路径**(顶栏 tab → 落地卡片 → 手机视图),别只测端点/单测/grep;护栏守用户实际入口非旁路。优先 `e2e.sh`(唤起 beta + 调接口 + DB 真值),UT/qa-run 静态守护只作补充。
- **commit 自主;tag/push/发 prod 须用户验收**,每个新版本重新确认(授权不顺延),用精确串 `release vX.Y.Z`。
- 选型对比表**只放用户能感知的维度**,别把"我写代码省不省事"伪装成用户价值。
- 每次代码改动**主动同步文档**(prd/tech-design/CHANGELOG/qa-cases),不等提醒。

**验收 UI 的两条硬纪律(踩过)**
- **浮层/按钮别只验 `display`,必须验遮挡**:取元素中心点跑 `document.elementsFromPoint(cx,cy)[0]`,最顶层必须是它自己或它的后代。v1.6.13 的横屏目录钮 `display:inline-flex`、坐标也对,但被隐私钮压在下面 —— 我断言了"显示"就收工,用户一句"还是看不到"打回来。
- **`<main>` 带 `relative z-10` 是层叠上下文**:任何放在 main 里的 `position:fixed` 浮层(抽屉/遮罩/模态)**都升不到 `z-30` 的 nav 之上**,写多大 z-index 都没用。要么让它避开 nav 的区域(如抽屉 `top:38px`),要么把节点搬出 main —— 别去调 z-index 空耗。

**旋转容器(html.ls-wide)里,视觉坐标 ≠ 布局坐标**
- 要"钉住/定位"就走**布局坐标**:`offsetTop` 累加 + 直接写 `scrollTop`。`getBoundingClientRect` / `scrollIntoView` 在 `rotate(90°)` 的容器里返回**屏幕坐标**,阅读流的"上"映射到屏幕的"右" —— 拿它挑章节会挑到毫不相干的元素(实测漂到 top=3125)。`getBoundingClientRect`/`elementsFromPoint` 只适合判"用户看到/点到什么"。
- `position: fixed` 在被 transform 的容器里**包含块变成该容器**,于是跟着内容滚走(实测退出钮滚到屏外)。要常驻就放进 sticky 的导航行,别用 fixed。
- body 自带 Tailwind `min-h-screen` → 给 body 设 `height` 必须同时 `min-height: 0 !important`,否则被压过。

**写守护(qa-run.sh)**
- **否定断言只盯代码构造,不盯裸标识符/ 裸字面量。** 写 `! grep -q "function X"` / `! grep -qF "Object.assign(X"` / `! grep -qE "color: *'#abc'"`,**不要**写 `! grep -q "X("` 或 `! grep -q "#abc"` —— 讲解这段历史的注释里必然会出现那个词,自己把自己扫红。已踩 **5 次**:`100dvh`(注释在辨析 vh/dvh)、`window.innerWidth<`(注释举例)、`hBarConfig(`、`#c8c0ae`(注释在列旧色值)、`scrollIntoView`(注释在说"不能用它")。第 4、5 次都发生在这条规矩写下之后 —— 写守护时**逐条回看这一行**。
- 含 `${` / `[{` / `[]` 的模式一律 `grep -qF`(BRE 会把 `{}` 当区间量词,静默不匹配)。**这条被违反过并且真的假红了**:`v11-R7` 断言 `_kpi-info :: i(${gp.goal.description` 用了裸 `grep -q`,功能一直好的,护栏红了好几个版本没人发现。
- **对 Java/JS 源码写否定断言用 `java_code_only`,不是 `code_only`** —— 后者只剥 shell 的 `#`;Java 的坑是**行尾** `// 原 startsWith("6") 漏 513180` 这种。`v13.1-ISSUE3-CN` 就是这么红的(自扫第 6 次)。
- **护栏不许惩罚项目按规矩做的事**:`v04-AI-DIAGNOSE-2` 的 10 个 marker 里有 4 个是 emoji、阈值 ≥8 —— 后来定了「UI 不许 emoji」铁律,emoji 被正确删掉,这条就永远只能 6/10。发现这种护栏,把它**反过来正向守规矩**(断言"没有 emoji"),别降阈值糊过去。
- **不要写死计数分母**:`v02-LIQ-3` 断言「恰好 16 个类目有 liquidity_class」,后来加了类目变 18 就红。改守真意图「**一个都不许漏**(缺失数=0)」,加多少都不假红。同类坑见「加枚举值要扫计数分母」。
- **两条护栏可能编码互相矛盾的意图**:`v11-UED8` 守「目标手机端单列」,而后来的 `v11-R6` 刻意改成两列密度 —— 旧的那条必须删,不是调。改 UI 决策时**回头搜一遍有没有老护栏守着被推翻的那个决策**。
- `grep -c` 对**单个文件**只输出数字、**不带「文件名:」前缀**;`grep -rc`/多文件才带。所以 `grep -c f | awk -F: '{s+=$2}'` 在单文件下恒为 0(断言静默通不过)。单文件直接 `-eq N`,多文件才套 awk。
- **删/换整个守护块时,用块尾的 `log_bad` 行定位**,不要用 `\n# ` 找块尾 —— 那会匹配到块**内部**的注释行,只切掉一行,结果同名守护出现两份(一个 PASS 一个 FAIL)。
- 一致性问题**能用"只有一处"消除的,不要用"两处保持相等"去守护**(共享常量 / 共用 class 优于比较两个值)。
- **交互类护栏必须断言效果,不能只 grep 属性**(2026-08-13):`v111-PARTIAL-SWAP` 只查了 `hx-select`/`hx-push-url` 这些属性写没写,而真实故障是 **`hx-select` 挑的那个 id 根本不在 HTMX 响应里** → 换进去空内容、`outerHTML` 把整个 section 从页面删掉,**后端 200、日志干净**。补的 `v1112-SWAP-TARGET-EXISTS` 直接发带 `HX-Target` 的真实请求,断言响应含那个 id。凡是"点了之后页面应该变成什么样"的护栏,都要发请求验结果。
- **会写真库的测试脚本必须自己还原(策略 A)**:`mysqldump --single-transaction` 存基线 + `trap EXIT` 还原 + 重启。qa-run 以前不还原,后果实测两条:① 一次全量跑把**用户留作验收的当期关账了** → beta 从此 0 个 OPEN 周期,隔天再跑,依赖"当期可录入"的用例(FR5/FR7/v02-*/v03-*)**30+ 条集体级联变红**,看着像新代码打穿一堆东西,实际全是上次跑自己留下的状态 —— 排查这批假红比跑测试还贵;② 周期表被逐次往后灌到 2040-08(168 个未来 CLOSED 期),而 `findLatest` 按 `period_start` 倒序取 → 应用侧"最新期"落到十几年后。**还原判定不能写 `mysql … | grep -v`**(退出码变成 grep 的,正常还原无输出反被报成失败)。
- **一堆护栏同时变红,先怀疑环境状态、别急着改代码**:先看红的那批有没有共同前提(有没有 OPEN 周期 / 登录态 / 某账户还在不在),再对照本次 diff 摸过的文件 —— 没摸过的文件对应的红几乎都是环境。

**代码 / 技术**
- **并列同类元素必须同尺度**:同一屏语义同级、并列展示的元素(两个饼图 / 并列 KPI 卡 / 并列按钮组)图型·容器高度·主体尺寸·字号·图例位置全同,尺度落共享常量或共用 class。环图这类"主体尺寸由剩余空间推导"的图**必须写死半径**(容器同高 ≠ 同直径)。规范见 `docs/visual-spec.md`「并列同类图表」。
- **LLM 严禁做数学**:所有计算类指标工程算好填进 prompt;SYSTEM 加禁数学约束;prompt 里数字先 read 验证;LLM 输出胡话先怀疑 prompt。LLM 校验失败先抓 prompt + raw output。
- **图表数字浮层**:Chart.js 图用 datalabels 把金额/百分比绘在扇片/柱顶/数据点上,hover tooltip 不算。**但这不等于"每个点都得印字"**(2026-08-13 澄清):多点折线上 12 个 `¥123.6万` 挤在同一水平线会**叠成一串**,一个都读不出来,`range=ALL` 可达 240 点。既有惯例是**只标末点**(`_wealth-level` / `_savings`),负债这类"降了多少"的曲线标**首末两点**;两端要 `clamp:true` + 用**角度** `align`(315 右上 / 225 左上)斜向内推,否则首点压 Y 轴刻度、末点被卡片边缘切掉。
- **单序列图不画图例**:标题已经说明是什么,Chart.js 默认图例会落在**绘图区内**把数据标签挤没(负债曲线是全项目唯一漏写 `legend:{display:false}` 的,被维护者报成"图例和图表互相覆盖")。
- **Thymeleaf `th:if` 与 `th:replace`/`th:insert` 不能放同一元素**(v1.6.25 实测):片段包含优先级(1)高于条件求值(3)→ **replace 先执行**,片段会带着 null 参数被渲染并抛异常,而且**响应已经流式输出了一半**,用户拿到半截页面、日志里是片段的二次异常**盖住真因**。条件必须外提到 `<th:block th:if=...>` 包一层。守护 `v1625-UPDATE-PATH` 钉住全仓 0 处。
- **错误页必须零依赖**:`/error` 依赖的正是刚刚出错的那套机制(nav 需要 `state`,而错误转发的 model 里没有)→ 错误页的 head/顶栏一律自包含,不复用需要 model 的片段。
- **安装/部署脚本的报错指引必须按平台给**(2026-08-13 · issue #10):报告者在 Ubuntu 上被 `docker-up.sh` 教去 `brew install docker-compose` —— Linux 没有 brew,直接卡死。**凡是出现 `brew` 的分支,必须有对应的 Linux 分支**;Linux 的「引擎连不上」还要再分两种(`sudo docker info` 能通 = 权限,要 `usermod -aG docker`;不通 = 服务没起,要 `systemctl start docker`)。另:**别信 `docker-compose version --short`** —— Ubuntu 的 1.29.2 会吐出依赖库 docker-py 的版本(5.0.2),要解析完整输出第一行。这类分支**必须用桩程序把每条失败路径跑一遍**,只读代码看不出文案错给了哪个平台。
- **面向用户的「版本」必须不登录可见**(v1.6.25):`/health` 返回 `version`。此前版本徽记只在登录后的 nav 里,`/health` 只有 `status`,导致用户和部署脚本都无法自查"我到底跑的哪一版" —— 用户 `git pull` + 重跑脚本后拿到旧版而无从判断。**部署/更新脚本必须打印版本结论**(vA→vB / 无变化 / 落后+原因),不能只说"起好了"。
- **Thymeleaf**:`#xxx.yyy()` utility 必须在 `${}` 内;conditional render 必须 force-trigger 验证;诊断 prod 栈先找最早 ERROR。
- **RestTemplate 调签名 URL**:自己签名+pctEncode 过的 URL 传 `URI.create(s)`,别传 String(会被二次编码 → `SignatureDoesNotMatch`)。
- **跨账户转账走 `transfer` 表**,绝不合并进 `cash_flow`(账户级方案红线)。
- **任何会删/清数据的判断,必须 fail-closed**(v1.6.26):`$(查询 || echo 0)` 这种"查不出来就当 0"在互锁里等于**失败时选破坏性那一边**。判据查不出来 → 一律当作「有真实数据」并放弃删除。删除前**强制先 dump**,dump 失败也不删 —— 把误判代价从"不可恢复"降到"可恢复"。
- **`exit`/`die` 在 `$(...)` 里终止不了主脚本**(v1.6.26 实测踩到):命令替换是子 shell,里面 `exit` 只结束子 shell,主脚本会带着"错误信息当数值"继续跑(实测打出一堆 `[[: syntax error: operand expected`,没删数据纯属运气)。要终止就让函数 `return 1`,由调用处 `|| bail` 处理。
- **判断"用户用过没有"不能只看新增行**:v1.6.26 前互锁只看 `audit_log` 与 `member.id>2`,而"用内置两个账号 + 改过密码"的真实用户两条都不响。可靠信号:`must_change_pw=0`(完成过首登改密)、种子成员 `display_name` 已自定义。
- **否定断言前机械剥掉整行注释**(`code_only` 助手,qa-run 里):"否定断言被自己解释历史的注释扫红"已踩 **7 次**,靠"记得盯代码构造"不管用 —— 剥注释是结构性解法。
- **断言用的字符串必须来自"被断言的那个东西"**(v1.6.25):`FR3-1 /accounts 列表` 一直 grep「招行储蓄卡-工资」,而那其实是建户向导里 `<input placeholder="如: …">` 的**占位符** —— 向导片段又因 `th:if`+`th:replace` 同元素被无条件渲染,于是守护一直绿;而同名账户早在一个月前就归档了(默认列表不含归档),**这条守护一个月没在验它声称要验的东西**。别拿"可能出现在别处的通用文案"(占位符 / 示例 / 注释 / 帮助文字)当断言锚点;能从 DB 取真值就取真值。
- **`grep` 类守护抓不到「东西还在但用户找不到」**(v1.6.23):`v15-ENTRY-1` 断言 `grep '/broker(id='` 一直是 PASS,而券商入口已被收进没有文字的 `⋯` 菜单、用户实际报障。**可见性/可达性必须用运行时判据**(渲染真页面 + 有面积 + `elementFromPoint` 命中自己 + 不在折叠容器内),见 `scripts/entry-points-check.cjs`。同源教训在 v1.6.14 就写过(「显示 ≠ 看得见」),但我只当成"以后写新守护要注意",**从没回头拿这把尺子重量已有的 500+ 条守护** —— 写下教训 ≠ 教训生效;新增一条通用护栏后要问「已有守护里有多少条正踩这个坑」。
- **UI 收纳按"是什么"分,不按"有多少"分**:能力入口(通往一整套功能)与低频维护动作(归档/导出/恢复)是两类东西,不能因为"行内按钮太多"一刀切收进 `⋯`。能力入口进了 `⋯` 就等于没有入口。
- **「提示」和「能力」之间不能断链**:dashboard 洞察条提示「可考虑加速偿还」却不给去处,而决策器页面 `/reports/refinance` 就在那儿 —— 且**它从 v0.4 起全站零入口**、README 还在宣传。新页面上线时必须问一句「用户从哪进来」,`git log -S '<路由>' -- src/main/resources/templates` 能一眼看出有没有人指向它。
- **「连通性探针」不能当「可用性判据」**(v1.6.22 一次踩出三处假阳性):健康检查/就绪探测必须执行一次**需要权限的真实操作**(`SELECT 1`),不能问「对方在不在」。典型:`mysqladmin ping` 在**密码错误时也 exit 0**(MySQL 语义:服务器有应答就算活着)→ 同一个原语被 compose healthcheck、容器入口就绪检查同时误用,一起报「一切正常」,故障推迟到很后面才爆。HTTP 200 之于「登录能不能用」同理。
- **`|| echo <默认值>` 不许用在判据上**:它把「失败」翻译成一个看起来正常的值(实测:`mysql … 2>/dev/null || echo 1` 让连不上库时照样打印「表存在数=1」,把认证失败完全盖住)。兜底方向选对(fail-safe)也不够,**必须把「判不了」如实说出来** —— 诊断信息的价值在于它**故障时**说的话。
- **把判据由宽改严,要顺依赖链问一遍「谁在读它」**:v1.6.22 把 db healthcheck 改成真实查询后,`depends_on: service_healthy` 让 `docker compose up -d` 直接非零退出,`set -e` 在自愈逻辑之前就打断了脚本 —— 健康检查从「提示」变成了**控制流**。
- **用户上手路径上的失败,优先级是:删掉失败点 > 代替用户完成修复 > 把提示写得更清楚**。第三档有天花板(v1.6.21/22 两次真实劝退都是停在第三档);超时/失败提示不要写成让用户猜的清单(「常见:A / B」),日志在手边就按特征归因、能自动修的当场修。

**性能 / SQL(v1.11 踩)**
- **「一条 SQL」不等于「一次扫描」**:把 per-period 的 N+1 合并成一条时,第一版写成相关子查询(对 3600 行的
  `period_snapshot` 每行再查一次 MIN)→ O(n²),报表页从 1.25s **拖到 9.3s**。合并查询必须看执行计划/实测耗时,
  不能只数「条数少了」。正解是窗口函数(`ROW_NUMBER() OVER (PARTITION BY …)`)一次扫完。
- **缓存的生命周期要选「结构上不可能陈旧」的那个**:按 familyId 长缓存必须在**所有**写路径清掉(实测 4 文件 6 处
  `snapshotMapper.upsert`),漏一处 = 静默算错开账基线 → 人赚/钱赚分界错。改成「每次 `load()` 刷新的 ThreadLocal」,
  代价是一次请求多 2~3 条查询,换来零失效风险 —— 这笔交易在「静默错值」面前永远划算。
- **量化再优化**:先用 `SHOW GLOBAL STATUS LIKE 'Questions'` 数出每次请求的 SQL 条数 + `curl -w '%{time_total}'` 取中位数,
  改完再量同样两个数。没有前后对比数字的"优化"不算优化。

**交互 / 前端(v1.11 踩)**
- **筛选器切换别用整页跳转**:普通 `<a href>` 会重载页面 → 慢 + **滚动位置回到页顶**。用 HTMX
  `hx-get` + `hx-select="#目标块"` + `hx-target` + `hx-push-url="true"`:不用新端点(服务端渲染一行不改)、
  不重载页面 → 位置天然保留;`href` 保留作无 JS 退化。
- **scrollspy 的「顶部越线的最后一节」有盲区**:最后一节比视口短时,页面已到底它的顶部还没越线 →
  **最后一个菜单永远高亮不了**。到底(`innerHeight + scrollY >= scrollHeight - 4`)就强制高亮最后一项。
- **区锚点要包住整个区**:只包标题壳(几十 px)的 section 在滚动中一划就过,目录里那一项几乎永远高亮不到。
- **截断轴必须明示**:存量(净资产百万级)与流量(月度收支万级)差两个数量级时,瀑布图必须用截断轴,
  但**默默截断**同样是错的 —— 实测期初柱 11% 高、期末柱 87% 高而金额只差 45%,会被读成"涨了 8 倍"。
  要在轴上写出起点,并在柱底压一道 broken-axis 斜纹带。
- **窄段的标签放到条外**:堆叠条里占比 < 18% 的段,段内标签会被 `overflow:hidden` 裁掉 → 数字看不全,
  改成图例补齐(prod 流动资产占比小,实测踩到)。

**信息安全(2026-08-12 维护者点出 · 已发生过泄露)**
- **prod 真实金额属于敏感值**,和 IP/凭据同级。仓库是**公开**的,而 `docs/*audit*.md`、`docs/*review*.md`、
  `prd/*`、`tech-design/*` 这类文档天生"对着真实环境写观察",最容易把维护者家庭的净资产/余额写进去。
  **实测:v1.6 时代的 metric-audit / ued-review / prd/v1.6 / tech-design/v1.6 / qa-run.sh 里都有,而且已经推到公开仓库。**
- 这类文档的价值在**口径 / 计算逻辑 / 相对量 / 结论**,绝对金额一点不需要 ——
  「漏折汇误差 25.4%」比一个具体余额有用得多,而且不泄露任何东西。
  (写这条的时候我自己又把真实净资产当例子写了进去 —— 说明靠"记得"不管用,必须靠护栏。)
- 要记具体数额 → 本地 `AGENTS.local.md`(git-ignored)。守护 `v111-NO-PROD-AMOUNTS`。
- **commit message 同样是公开的**:我曾把 prod 净资产写进 commit body(未推送时发现)。写 message 前同样过一遍这条。
- 区分清楚:preview mockup / 单测 fixture / beta 数据是**合成或测试数据**,可以留;只有 prod 真实观测值要脱敏。

**运维小纪律**
- 清空文件用 `:> foo`,别用 `rm`(触发审批)。所有编码自己做,不用 codex 插件。
- beta 账期常被 qa-run/e2e 滚动搞乱(无 OPEN 期 / 冒出未来期)→ 修复:`SET FOREIGN_KEY_CHECKS=0` 删未来期(period_snapshot/snapshot_todo/cash_flow/transfer/period_member_cashflow/stock_valuation_event + period)+ `UPDATE period SET status='OPEN' WHERE id=<当月>`。

---

## 9. 命令速查

```bash
# 编译 / 测试(离线)
mvn -o -q compile              # 只编译
mvn -o test                    # 全量单测(当前 506)
bash scripts/qa-run.sh         # 黑盒静态守护(当前 582 · 全量实测 ~5.3 分钟 · 快照还原不污染 beta)
bash scripts/qa-run.sh --no-restore          # 例外:就是要看跑完之后的库状态(排查用 · 会污染基线)
bash scripts/qa-run.sh --only 'v1.10|v1.8'   # 只跑匹配的 section(~2.5 分钟)· 开发中用这个,别等全量
bash scripts/e2e.sh            # 端到端真验收(13 主线 93 断言 · 快照还原不污染)

# 部署 beta(自测全绿后)· 具体路径/凭据见 AGENTS.local.md
mvn -o -q package -DskipTests
bash db/apply.sh               # 应用新迁移(DB 凭据见 AGENTS.local.md / /etc/finance.env)
# 切 jar + 重启 systemd(sudoers 白名单命令)→ 等 /health 200

# 发布 prod(用户回 release vX.Y.Z 后)
bash .claude/skills/release-prod/release.sh preflight vX.Y.Z   # 阶段0 预检
# → 停,等用户精确回 `release vX.Y.Z`
bash .claude/skills/release-prod/release.sh tag-push vX.Y.Z
bash .claude/skills/release-prod/release.sh deploy vX.Y.Z
bash .claude/skills/release-prod/release.sh verify
```

---

## 10. 发布 prod(release-prod skill · 唯一闸门)

触发词:用户说「发布 / 上线 / release / ship / 发版」。流程(逐步不跳):
1. **阶段 0 预检**:`release.sh preflight [版本]` — git 干净 / tag 不重 / 列 commit+diff / 扫迁移 / **README 联动 + 主页数字带 + 部署脚本 硬门** / `mvn test`。
2. **阶段 0.5 硬 gate**:把预检输出给用户,**停下**。用户回**严格等于 `release vX.Y.Z`**(版本一致)才继续;回别的/不回 = 中止,不打 tag、不碰 prod。
3. **阶段 1** `tag-push`:annotated tag + push origin(gitlab)+ github。
4. **阶段 2** `deploy`:ssh prod checkout tag + `deploy.sh`(mysqldump 备份 + 迁移 + 切 jar + 健康检查 + 失败自动回滚)。
5. **阶段 3** `verify`:loopback + 域名 `/health`+`/login` 200。
6. **阶段 4** 成功 `notify ok` / 失败 `rollback` + `notify rollback`。

版本号约定:精化/bugfix 走 patch(vX.Y.Z);prod 落后时可跳版本发(tag 含全部增量)。
