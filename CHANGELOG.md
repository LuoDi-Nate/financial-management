# Changelog

按 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 风格记录。每个版本详细需求见对应 [`prd/vX.Y.md`](prd/),技术设计见 [`tech-design/vX.Y.md`](tech-design/),QA case 见 [`docs/qa-cases.md`](docs/qa-cases.md)。

## [v1.37.1] · 未发布

- 修复报表储蓄率停留在旧总额月份，及月均收入、月储蓄中位数漏掉后续逐笔月份的问题。读数、图表和说明面板统一跟随报表观察账期与趋势范围；储蓄率明确显示月份，零收入不回退旧比率，未填、真实零值与退款分别处理。
- 不修改已录金额或数据库结构；全量指标核查与 beta 验证进行中。

## 发布记录在哪(2026-08-14 逐个核对过)

分在两处,**互补、不重复**:

| 区间 | 完整记录 |
|---|---|
| **v0.1 – v0.14.1**(2026-05-10 – 07-09) | **本文件**。GitHub Releases 没有这一段 |
| **v0.15.0 – 至今**(74 个版本) | **[GitHub Releases](https://github.com/LuoDi-Nate/financial-management/releases)**。74 个 tag ↔ 74 个 Release,**1:1 无缺**;每版有完整说明,大版本带 PC + 移动截图 |

本文件对 v0.15.0 之后**只保留最近 5 个版本**的详细条目,方便在仓库里直接看到近况;
不做 Releases 的全量镜像 —— 镜像出来的信息量不会超过原件,却会随 Release 页被编辑而漂移。
README「近期更新」段再往下收一层,只留最近 1–2 版摘要。

早期那段有 10 个补丁 tag 当时没留条目(`v0.2.1` / `v0.3.1`–`v0.3.3` / `v0.4` / `v0.4.1` /
`v0.4.2` / `v0.4.13` / `v0.4.15` / `v0.4.16`),内容在 `git log` 里。

## [v1.16.0] · 2026-08-17

### Fixed — 「本期填报完成」只留一个定义 · [issue #15](https://github.com/LuoDi-Nate/financial-management/issues/15)

- **账户已填完、也没有收支,填报 tab 右边却还挂着 `1`**(FR-390)。「填报完成」在系统里同时有
  三个互不联动的定义:填报页看「这一行有没有本期数字」判 ✓,tab 徽标和首页横幅数
  `snapshot_todo.status='PENDING'`。开账时 `PeriodOpener` 把上期末余额延续成本期快照(备注
  「开账自动延续上期末余额」),**同一段代码写进去的 todo 却仍留在 `PENDING`** —— 矛盾就是在
  这一刻产生的。统一到「**这一行有本期数字,就是已填**」,并且修在**写入侧**:写完快照就把同
  一行 todo 标成已填。判定看「写完之后有没有快照」而不是「有没有算出延续值」,幂等重跑和
  「快照由别的路径先落库」两种情形才都对;没有可延续历史的账户(首期 / 新建账户第一期)保持
  `PENDING` —— 那种确实该催。
- **系统代填不冒名**(FR-391)。`markCarriedForward` 只动 `status='PENDING'` 的行,
  `done_by_member_id` 留 `NULL`,不伪造成某个人确认过。

### Changed — 贷款趋势提示条改看「有没有**人**确认过」

- 提示条原来的闸门是 todo 状态。「开账即已填」之后它会被这一版**静默弄丢**(不报错、不留痕,
  就是再也不出现),所以闸门改成 `done_by_member_id != null`(FR-392):系统代填的「已填」不
  算人做过决定,提示条照常出现;点了「接受」或「保持上月」之后才不再打扰,并记名。

### Changed — 护栏

- `v116-TODO-DONE-SINGLE-SOURCE`:拦住「把计数 SQL 改成去 join 快照表」这类把口径搬回**读取
  侧**的改法;单测 `PeriodOpenerTodoAlignmentTest`(4 条)+ `EntryLoanPromptTest` 补 3 条钉住
  上面那个会被静默弄丢的提示条。

**DB 迁移 `V55__align_carryforward_todo_done.sql`** —— 补齐已经存在的 OPEN 账期的 todo 状态列,
**只动状态,不碰任何金额**。

## [v1.15.0] · 2026-08-17(随 v1.16.0 同批发布 · 无独立 tag)

### Added — 成员身份三件事 · [issue #12](https://github.com/LuoDi-Nate/financial-management/issues/12)

- **登录名可以改了**(FR-380)。以前登录名等于成员建号时随手打的那串,改不了;现在管理页
  「家庭成员」里能改,改完**旧登录名当场失效**:先按旧名清 `persistent_logins` 的「记住我」票根、
  再改名、事务提交后作废该成员所有会话。被踢下线的人会看到登录页明确说「登录名已变更,请用新的
  登录名重新登录」(`?expired`),而不是一个没头没尾的白板登录页。
- **成员可以归档**(FR-381)。家里有人不再参与记账(孩子长大自己管、老人不再录入),以前只能
  留着占位或者硬删。归档**只停掉「谁还来打理」** —— 不再出现在负责人下拉、不进填报分母、不收
  提醒;**他名下的钱一分不动**,历史账期里的归属也原样保留。资产的可见性只由账户自己的归档状态
  决定,和成员归档无关。
- **零引用的成员才允许删除**(FR-383)。删之前扫 13 处引用(账户 / 快照 / 收支 / 划转 / 待办 /
  完成度 / 审计 / 重开日志 / 期间现金流 / 估值事件 / 提醒记录 / 教育目标里的孩子),命中就不给删,
  并把「他在哪儿还有多少条」列给你看,引导改走归档。

### Changed — 「查成员」拆成三个口径

- 归档一旦存在,`findActiveByFamily` 的语义就变成了「**只活跃**」,原来 15 处调用里有一半其实想要
  「含归档」。现在分三桶:**展示 / 脱敏 / 历史归属**走 `MemberDirectory`(含归档)、**面向未来的
  选择项与分母**留活跃口径、**编辑表单候选** = 活跃 ∪ 当前值(否则编辑一条挂在已归档成员名下的
  旧账户,一提交就把归属静默改成别人)。
- ⚠ 顺带修掉一处**隐私缺陷**:6 个 LLM / 导出脱敏点原来拿「活跃成员列表」建真名→假名映射表,
  归档成员不在表里 → **他的真名会原样出现在发给 LLM 的 prompt 里**。

### Changed — 护栏(4 条)

- `v115-MEMBER-NAME-MAP-INCLUDES-ARCHIVED`:**白名单禁止旁路** —— 允许直接调 `memberMapper`
  仅活跃查询的文件钉死为 11 个,新增调用点必须改白名单并说明属于哪一桶。
- `v115-DELETE-SCAN-COVERS-FK-LESS`:13 处引用查询全覆盖;其中 4 处**没有外键**(数据库不会替你
  拦),靠反射比对「mapper 上所有 count 方法 ⊆ scan() 实际调到的方法」当外键使。
- `v115-NO-MEMBER-ARCHIVE-IN-SUMS`:金额侧 SQL 里出现 `member.archived_at` 一律红。
- `v115-RENAME-KILLS-TOKENS-FIRST`:`killRememberMe(旧名)` 的行号必须小于 `updateUsername`。

### Fixed — 成员页窄屏页头(双端截图复核发现)

- 390px 下页头一行塞三样(标题 + 说明 + 「添加成员」),说明文案离按钮只剩 5px、按钮自己折成
  两行;窄屏改竖排,按钮靠左**躲开右下角两个 fixed 悬浮键**(否则点击区被抢),其浮层同步
  改 `left-0`(原 `right-0` 在靠左之后会把 360px 表单甩出屏幕左侧)。PC 版面不动。

**无 DB 迁移** —— `member.archived_at` 自 V1 就在,`audit_log.type` 是 `VARCHAR(40)` 够放新类型。
## [v1.14.0] · 2026-08-17(随 v1.16.0 同批发布 · 无独立 tag)

### Added — 截图导入支持拖拽 + Ctrl+V 粘贴(PC)· [issue #11](https://github.com/LuoDi-Nate/financial-management/issues/11)

- **拖到上传区松手即上传**(FR-370)。与「点击选文件」走完全同一条路径:压缩、计价、并发计数、
  `开始识别` 的启用条件**一行都没复制** —— 先把原来写在 `change` 回调里的逻辑抽成 `handleFiles()`,
  三个入口(点选 / 拖拽 / 粘贴)共用。两条路径行为分叉是这类改动最典型的翻车方式。
- **Ctrl+V 粘贴截图即上传**(FR-375 · 作者拍板加)。PC 上截完图,图已经在剪贴板里,粘贴比拖拽
  还少一步(拖拽得先把图存成文件)。光标在输入框 / 文本域 / contenteditable 里时**不拦**粘贴,
  正常贴文字不受影响。
- **拖拽反馈用 `dragenter`/`dragleave` 计数配对**(FR-372),鼠标划过子元素不会一闪一闪;`window`
  的 `drop`/`dragend` 上强制归零 —— 拖出窗口不一定派发 `dragleave`,只做减一会让高亮永久卡住。
- **非图片明确拒绝并列出文件名**(FR-373):「只收图片(JPG / PNG 等),已忽略 2 个:对账单.pdf、
  截图文件夹」,张数计数器不被悄悄带偏。`accept="image/*"` 只约束文件选择器,拖拽和粘贴都绕过它;
  拖进来的文件夹在 `DataTransfer.files` 里 `type` 为空,不单独挡会掉进「图片解码失败」分支。

### Fixed — 上传区长得像拖拽区却不接拖拽(FR-371)

- `import.html` 的上传区 id 就叫 `dropZone`、渲染成 2px 虚线框(网页上「往这儿拖」的通用符号),
  但全仓库搜不到任何 `drop` 监听 —— PC 上真拖一张图上去,浏览器走默认行为**直接导航到那个图片
  文件,整页没了**,已选好的图连同正在进行的这次导入一起丢。**这个坑独立于新功能存在**:不知道
  能拖的人手滑拖歪一次也会丢。现在 `window` 层拦掉 `dragover`/`drop` 默认行为,落在区域外静默
  忽略、页面纹丝不动。
- **落图态不再让拖拽区变矮**。原来把静态文案换成「松手即上传」用的是 `display:none`,区域从
  115px 缩到约 70px —— **拖拽目标在指针底下自己变矮**,指针可能就此掉出框外,表现是「高亮闪一下
  就没了、松手也没反应」。功能测试看不见这个(脚本派发的拖拽事件不关心布局),靠截图复审才发现。
  改成高度不变、提示语绝对定位盖上去。

### Changed — 移动端逐字不变(FR-374)· 护栏 6 条

- 提示语(「也可以把截图直接拖进来,或按 Ctrl+V 粘贴」)按**指针能力**(`hover: hover` +
  `pointer: fine`)脚本注入,不按视口宽度 —— 手机和触屏平板上那个位置渲染为空。手机上没有「拖
  文件」这回事,加了只是噪音。
- `v114-DROPZONE-HAS-DROP-HANDLER`:出现 `id="dropZone"` 就必须有 `drop` 监听 + `window` 兜底,
  钉住「长得像拖拽区就必须真能拖」,防止以后重构又把监听弄丢、退回今天这个状态。
- `v114-UPLOAD-SINGLE-PATH`:三入口共用 `handleFiles`,`FormData` 全文件只许出现一次。
- `v114-PASTE-YIELDS-INPUT` / `v114-NONIMAGE-BEFORE-COMPRESS` / `v114-DROP-STATE-NO-REFLOW` /
  `v114-HINT-PC-ONLY`:验证过程里每踩一个坑钉一条。

**无 DB 迁移 · 后端一行未改** —— 拖拽 / 粘贴 / 点选送过去的是同一个 FormData;上传之后的识别、
比对、确认入账全没动。
## [v1.13.0] · 2026-08-17(随 v1.16.0 同批发布 · 无独立 tag)

### Fixed — 管理页的「主选供应商」六个 AI 调用点里只有一个听话

- 施工前查证时撞见的**真 bug**,不是这次新功能带出来的:v1.12 里六处业务代码各自注入
  `List<LlmClient>` 裸遍历,只有 AI 体检那一处按配置排了序,月度复盘 / 打标 / 再平衡 / 目标建议 /
  透视洞察全都按 Spring 的 bean 顺序走。**不报错、不告警** —— 用户在管理页改完主选、页面提示保存
  成功,实际只有六分之一生效。
- **逐点补是错的修法**:那只会把「六份各自排序的代码」变成「六份各自排序但暂时一致的代码」,
  第七个调用点照样再犯。改成收口 `LlmRouter`(全项目唯一允许注入 `List<LlmClient>` 的类),
  六处业务只持路由;要单独指定一家的地方(管理页「测试连接」按钮,语义就是测这一家)走
  `llmRouter.clientFor(...)` —— 那是路由给出来的,不是绕过路由。

### Added — LLM 配置改成 平台 → 模型系列 → 具体型号 三级 · [issue #14](https://github.com/LuoDi-Nate/financial-management/issues/14)

- **三级级联配置**(FR-360)。原来只有「Qwen / DeepSeek 二选一 + 一个型号框」,平台和型号搅在一起,
  加第三家就得再动一遍模板。现在主选 / 备选各是一个完整三元组(平台 + 系列 + 型号),failover
  以三元组为单位整体切换。
- **接入火山方舟**(FR-361)。文本调用与其余平台共用 `AbstractOpenAiCompatibleClient`,key 走
  `llm_ark_api_key`(`deploy.sh` step 9.5 一并 seed,升级不用手动补行)。
- **视觉模型走同一套三级**(FR-362)。`QwenVisionClient` 改名 `VisionLlmClient`,持仓截图导入不再
  绑死百炼;开关拆出独立键 `llm_vision_enabled`,关掉视觉时型号仍保留,重新打开不用再选一次。
- **型号放开手填**(作者 2026-08-15 拍板四条之一)。方舟的 `ep-xxxx` 接入点各账号不同、豆包型号名
  带日期版本号,预置任何一个都是挖坑。服务端因此**不能再用白名单等值校验**,改成格式校验
  (≤64 字符 · 只允许字母数字和 `._:-`),推荐清单只喂页面下拉;顺手删掉旧实现里「不认识的型号
  悄悄换成 auto」那段静默回落 —— 用户以为选中了其实没有,比直接报错更坏。
- 百炼那套「多型号轮询摊免费额度」保留为**平台专属能力**(目录里 `modelRotation` 标记),不强行
  抹平成所有平台都有。

### Changed — 目录唯一一份 + 旧配置读时派生 · 护栏 4 条

- **`LlmCatalog` 是「有哪些平台/系列/型号」的唯一出处**(FR-364)。改之前散在四处
  (`QwenLlmClient.DEFAULT_MODELS` / 控制器白名单 / 模板 JS 级联清单 / 视觉下拉写死的三个
  `<option>`),而且**当时就已经不一致**。这类不一致不报错,只表现成「某个选项好像没用」。现在页面
  级联的数据由服务端把目录序列化进 `data-catalog`,轮询池也从它取。
- **旧配置零迁移、可回滚**(FR-363)。`family_config` 是 KV,老配置能无损推出新配置,于是选「读时
  派生」(`LlmSettings.load`:新键有就用新键,没有就从 v0.14 那套旧键推等价三元组)而不是写迁移
  SQL —— 迁移 SQL 的代价是「升级瞬间必须一次性把所有家庭改对,改错没退路」,读时派生的代价只是
  这段代码要多活一个版本,而且它顺带给了回滚能力:旧键原封不动,退回 v1.12 读到的还是它认识的
  那份。取舍写在 `tech-design/v1.13.md` §1.5。
- `v113-LLM-ROUTER-SINGLE-PATH`:六个调用点**逐个点名**,各自必须出现 `llmRouter.invoke(`,且代码行
  里一个 `LlmClient` 都不许有;`List<LlmClient>` 只许出现在 `LlmRouter.java`。只断言「LlmRouter 存在」
  没有意义 —— 出 bug 那一刻它已经存在了,问题是没人用它。
- `v113-LLM-CATALOG-SINGLE-SOURCE` / `v113-LLM-LEGACY-KEYS-KEPT` / `v113-LLM-CARD-LAYOUT`:分别钉住
  型号清单只有一份、9 个旧 `K_LLM_*` 键一个都不许删(三个「选哪个模型」的旧键只许 `LlmSettings` 读)、
  三家凭据列等高对齐 + 备选组窄屏有分组线(双端截图复审实测出来的两处,单测和 e2e 都抓不到)。
- 单测:`LlmCallSiteRoutingTest`(没人绕过路由)+ `LlmRouterPrimaryOrderTest`(排序对六个点全生效)
  + `LlmCatalogConsistencyTest`(扫包双向比对,不写死类名)+ `LlmSettingsMigrationTest`(喂 v1.12
  真实形态配置,派生结果逐项等价、读两次一致)+ `LlmModelFormatTest` + `PromptUnchangedTest`;
  原 `QwenInsightComplianceTest` 拆成 `LlmFaultClassifyTest`(故障分类)+ `InsightComplianceTest`
  (合规红线 · 与平台无关),`LlmVendorOrderingTest` 由 `LlmRouterPrimaryOrderTest` 取代。

**无 DB 迁移 · 旧键原封不动** —— prompt 一个字没改(`PromptUnchangedTest` 钉住),换的只是「谁来
接这次调用」;退回 v1.12 的代码仍能读懂库里的配置。

## [v1.12.1] · 2026-08-15

### Fixed — [issue #13](https://github.com/LuoDi-Nate/financial-management/issues/13)(外部用户报告 · macOS)

- **Docker 渠道的 OpenD 向导:照着一键命令跑必然失败**。向导页把
  `docker compose -f docker-compose.yml -f deploy/futu-opend.compose.yml up -d` 摆成一个
  `<pre>` 一键块,而它依赖的三个变量(`FUTU_OPEND_IMAGE` / `FUTU_ACCOUNT` / `FUTU_PWD_MD5`)
  写在命令**下方**的脚注里 —— 人看到 pre 块的第一反应是复制执行,于是撞
  `required variable FUTU_ACCOUNT is missing a value`。**compose 那个 `${VAR:?}` 硬失败是对的**
  (没配账号就不该起 sidecar),错的是引导顺序。DOCKER 段改成有序四步:备镜像 → 配 `.env` →
  合并启动 → 回管理页填 `opend`/`11111`;末尾原样贴出那两行报错并说明「这是预期行为不是故障」。
- **「富途没有官方镜像」这个门槛被写成了脚注**。它决定这条路**走不走得通**,现在提到最前面用告警块
  承载,并给出退路(改走拓扑 C:OpenD 放家里常开的机器 + 反向隧道)。
- **`.env.example` 里根本没有这三个变量**。`.env` 是从它复制来的,用户即便读到脚注也没有模板可抄,
  只能自己拼变量名。补上一段(默认注释掉,不影响正常部署)+ `printf '密码' | md5sum` 提示。
- **`docs/broker-sync-guide.md` 两个相对链接从 `docs/` 出发必然 404**:
  `](futu-opend.compose.yml)` / `](futu-opend.service.example)` → `../deploy/…`。
- **页面标题不分渠道,DOCKER 用户看到的第一句话就是错的**(双端截图复验时发现):h1「把 OpenD
  装进系统,点几下就好」+ 副标题「下方终端常驻显示…安装与登录在终端下方按步操作」是无条件渲染的,
  而容器渠道既没有内置终端、也不是「点几下」。改成按渠道分,DOCKER 那份直说「这台是容器部署,
  OpenD 得单独起一个 · 本页不能替你一键装」。

### Changed — 护栏

- `v1121-OPEND-DOCKER-ENV`:钉结构不钉措辞 —— `.env.example` 含三变量;向导页 DOCKER 段里
  `FUTU_OPEND_IMAGE` 的**行号必须小于** `up -d` 那行(「前提在命令前面」本身可机器校验);
  段内含原始报错串;模板里存在 `th:unless="${channel == 'DOCKER'}"`(证明 Linux 那句文案被 gate 住);
  指南两个相对链接带 `../deploy/`。

**无 DB 迁移**,纯模板 / 文档 / 示例配置。

## [v1.12.0] · 2026-08-14

### Added — 把「封板」做实

- **FR-350 · 分类属性按期定格**。金额侧的事实早就按期落库了(余额 / 收支 / 汇率 / 持仓估值都是
  per-period),分类侧不是 —— 事实查询拿**当前**的账户行 + 产品类目行去 join 每一期。于是今天在
  管理页把某账户从「货币基金」改成「债基」,**全部历史月份**的集中度 / HHI、流动性分层、紧急储备
  月数、大类分布、vs 基准当场跟着变,金额一分没动。新增 `period_account_attr` 表(迁移 `V54`),
  关账时在**同一事务内**定格 5 个属性,读侧「有定格读定格、没定格回落实时」。
  重开 → 删定格行,再关账即重新定格。
- **FR-351 · SQL 归因开关**;**FR-352** 消掉事实查询的 N+1。

### Fixed

- **FR-353 · 比率类指标失真时降级**:分母趋零会算出 −2383% 这类没有信息量的数;改为超出阈值就
  降级展示。首版把百分比藏了、**Δ 列却漏了**,−2468.2 pp 继续摆在那儿,同一 commit 链里补掉。

### Changed — 护栏

- `v112-ATTR-FREEZE-CLOSED` / `-REOPEN-REFREEZE` / `-NO-BYPASS` / `-BACKFILL-SCOPE` /
  `-BENCH-ANCHOR` / `-LIVE-CURRENT` 六条。其中 `-FREEZE-CLOSED` 断言的是**行号次序**
  (定格必须在 `close()` 之后、`runMetricsAfterCommit()` 之前)—— 挪进 `afterCommit` 就变成
  「关账成功了但没定格」,而这种漏定格**不会报错**。

**含 DB 迁移 `V54__period_account_attr.sql`**(建表 + 回填已关账期)。黑盒 590/0 · e2e 93/0 · 单测 511。

## [v1.11.3] · 2026-08-14

### Fixed — [issue #10](https://github.com/LuoDi-Nate/financial-management/issues/10)(外部用户报告)

- **Linux 上跑 `deploy/docker-up.sh` 被教去 `brew install`**:脚本里 `uname -s` 分支当时**只做在
  CN 镜像源那一段**,而「Docker 引擎没起」和「缺 Compose V2」这两个失败分支的提示文案全是 macOS 的
  —— Linux 用户照着做必然卡死。两个分支都补上按平台分流:Linux 侧再区分**权限不足**
  (`docker ps` 报 permission denied → `usermod -aG docker`)和**服务没起**(→ `systemctl start docker`),
  这两种在 macOS 上是同一句「打开 Docker Desktop」,在 Linux 上是完全不同的两件事。
- **版本探测误报,把 V1 认成 V2**:原来用 `docker-compose version --short`,而 Ubuntu 打包的
  `docker-compose` 1.29.2 是 **Python 实现**,`--short` 返回的是 **docker-py 的版本 `5.0.2`** ——
  脚本据此判定「已经是 V2」直接放行,后面必然失败且提示驴唇不对马嘴。改为解析
  `docker-compose version` 首行的真实版本号,`--short` 只作兜底。

### Added

- `deploy/README.md` 加「Compose V2 怎么装」折叠段(Linux 三条路线 + macOS + 引擎两种情况),
  并点明 **apt 里的 `docker-compose` 1.29.x 是 V1,装了也没用**,唯一该看的判据是
  `docker compose version` 打出 `v2.x`。

### Changed — 护栏

- `v1113-DOCKER-UP-PLATFORM`:所有面向用户的安装指引必须按 `uname -s` 分平台,
  不许出现无条件的 `brew`。验证手段是**在临时 PATH 里放桩** `docker` / `docker-compose` /
  `sudo` / `uname`,把四条失败路径各跑一遍看真实输出,不靠读代码判断。

**无 DB 迁移。** 黑盒 582/0 · e2e 93/0(13 主线)· 单测 506。

## [v1.11.2] · 2026-08-13

### Fixed — 2 条使用反馈

- **「负债下降曲线」图例压住图表**:两层问题。① 它是全项目**唯一没关图例**的单序列图,
  Chart.js 默认把图例画在绘图区里,直接盖住金额标签 —— 单序列的图例只是重复标题,本来就不该画。
  ② 关掉图例后露出真问题:12 个点**每个都印一个 7 字金额**,同一水平线上叠成一串,
  首点压 Y 轴刻度、末点被卡片边缘切掉。改为只标**首尾两点** + `clamp` 防出框 + 斜向 `align`
  (首点右上、末点左上)让开轴与边缘;`range=ALL`(可达 240 点)也不会退化。
- **支出构成切「按账户」整块消失**:后端返 200、日志干净 —— 是 `hx-select` 挑的 id
  **不在 HTMX 响应里**,于是换进空内容,`hx-swap="outerHTML"` 把整个 section 从 DOM 里删掉。
  根因:控制器只看 `HX-Request` 就返回三区片段,而这个请求要换的是片段**之外**的 section。
  改为同时看 **`HX-Target`** 判断是不是整区替换,不是就返回整页让 `hx-select` 去挑。
  趋势 chips 当时也坏着,只是维护者先点到了支出构成。

### Changed — 护栏

- `v1112-SWAP-TARGET-EXISTS` / `v1112-SWAP-REGION-FRAGMENT`:**发真实 HTMX 请求**
  (带 `HX-Request` + `HX-Target`)断言响应里确实含目标 id,而不是 grep 模板里有没有写属性 ——
  这个 bug 的所有属性都写对了,坏的是响应内容。通则已进 `AGENTS.md`:
  **交互类护栏必须断言效果,不能只 grep 属性。**
- `v1112-CHART-LABEL-DENSITY`:多点折线不许每点都印金额标签;单序列图不许开图例。

**无 DB 迁移。** 黑盒 581/0 · e2e 93/0(13 主线)· 单测 506。

## [v1.11.1] · 2026-08-13

### Fixed — 5 条使用反馈

- **发版后不再把所有人踢下线**:session 存在**进程内存**里(没上 spring-session),重启即全部失效;
  remember-me 早就是 JDBC 持久化(`persistent_logins`,30 天),只是登录页复选框**默认没勾**,
  所以从来没生成过持久凭据 → 改为默认勾选(公用电脑可手动取消)。
  没选 `spring-session-jdbc`:那要新建表,为便利性动线上表结构不划算。
- **报表页资金流瀑布数字不再被遮住**:三处叠加,主因是**截断轴斜纹带**原来横贯整宽贴在容器底边,
  而 X 轴标签是**溢出**容器之外画的 → 那条边正好从标签中间穿过。斜纹带改为只画在每根柱子内部的底部。
- **比率类目标保留 1 位小数**:`GoalProgressService.compactVal` 对比率类做了整数取整,
  储蓄率 8.4% 显示成 8%、0.4% 成 0%,月度推进全被吃掉;三处模板渲染一并统一。
- **长文目录统一不编号**:给三区的 9 个子节编成「四、五…」是错的信息(它们不是三区的同级),
  改用缩进表达层级。
- **一页只保留一个时间筛选器**:「钱花在哪了」的窗口改为跟随三区时间范围,`mixWin` 参数仍接受不 404。

### Changed — 测试基建

- `qa-run.sh` 加**基线隔离(策略 A)**:跑前 `mysqldump --single-transaction` 存快照,
  `trap EXIT` 无论成败都还原 + 重启。此前它往真库写却不还原,一次全量跑把维护者留作验收的当期**关账了**,
  导致隔天所有依赖"当期可录入"的用例集体级联假红;账期表还被逐次往后灌到 2040-08。
- **护栏体检**:全量 41 条红逐条查完**一个真缺陷都没有** —— 病因是 `${` 用了 BRE 正则静默不匹配、
  否定断言被自己的历史注释扫红(新增 `java_code_only`)、两条护栏编码互相矛盾的意图、
  断言钉在已被推翻的旧规则/界面文案/写死的计数分母上,以及一条**在惩罚项目按规矩做的事**
  (marker 含 emoji,而项目定了禁 emoji 铁律)。通则已进 `AGENTS.md`:
  **断言写增量 / 存在性 / 结构关系,别写绝对值和界面文案。**
- 全量 qa-run 实测 **320s ≈ 5.3 分钟**(文档里长期写的「~45 分钟」是过时数字)。

**无 DB 迁移。** 黑盒 577/0 · e2e 93/0(13 主线)· 单测 506。

## [v1.11.0] · 2026-08-12

### Added

- 报表页二区新增两块**封板分布**:按成员 / 按资产大类(只统计 ASSET 侧;
  取舍见 `tech-design/v1.11.md` §4「哪些仪表盘指标值得搬到报表页」)。

### Changed — 性能与交互

- **性能**:「每期首次出现账户」从 per-period 查询改成 `ROW_NUMBER() OVER (PARTITION BY …)`
  一次扫描。dashboard **1.735s → 0.784s(−55%)**、reports?range=1Y **1.247s → 0.784s(−37%)**。
  中间踩过一版**相关子查询**,O(n²) 反而拖到 9.3s —— **「一条 SQL」不等于「一次扫描」**。
- 趋势区与支出构成的筛选器从整页跳转改 **HTMX 局部替换**(`hx-select`),不丢滚动位置,
  保留 `href` 以便无 JS 降级。
- 「月度收支 + 反推目标月供」等模块窗口改为**跟随三区时间范围**(原来写死近 12 期,
  切 3M 时上下两图期数不同、并排读会得出错误结论)。
- 长文目录滚到底部时**最后一项能高亮**了。

### Fixed — 指标审计(拿 prod 真实数据逐项校验 47 项)

- 缺当年 CPI/M2 时**静默回落历史均值** → 改为兜底 + **界面明示**哪些年份用了兜底。
- 收支稀疏月份算出 −2383% 这类**荒谬比率** → 越界改显示"数据太少不足以计算"。
- 集中度分母改用**绝对值**(否则一笔大额房贷会把分母压塌)。
- 覆盖月数与仪表盘「紧急储备」收敛到**同一条展示规则**(`> 36 月` 封顶)。
- 审计文档 [`docs/metric-audit-v1.11.md`](docs/metric-audit-v1.11.md);
  另做一轮**信息安全脱敏**(审计/复盘文档与手册页里的真实金额 80+ 处)+ 落护栏。

### 已知未完

- 报表页单请求仍约 **761 条 SQL**;剩余 N+1 未定位,缺 SQL 采样手段(v1.12 前置)。
- 审计发现的 **F4**:集中度/流动性/类型分布依赖**当前**账户属性,改属性会改写历史分类 ——
  本版只在界面披露漂移,彻底修 = 指标落库(v1.12)。

**无 DB 迁移。**

## [v1.10.0] · 2026-08-11

### Added — 报表页从「收益分析页」变成「月度封板快照」

- 两页分工定下来:**仪表盘 = 当期实时**(会变)· **报表页 = 关账后的封板快照**(不再变)。
- 报表页重构成三区,**时间范围只作用于「趋势」区** —— 六个 range 下前两区渲染**逐字相同**
  (`SealedPeriodService` 签名里没有 `range`,类型上就传不进去)。
- 新增:期末资产负债表 · 资金流瀑布(同时是对账表,恒等式
  `ΔNetWorth = 人赚 + 钱赚 + 开账基线` 不闭合就写出差额与原因)· 环比/同比三列对照 ·
  集中度(带上期幽灵条)· 流动性分层 · 本期归因 · 完整度声明。

### Fixed

- **归档一个账户会改写全部历史快照**:裸 `archived_at IS NULL` 让归档动作抹掉该账户的
  全部历史事实行 —— 一个纯整理动作能改写去年的报表。改为带时间语义
  `archived_at IS NULL OR archived_at > p.period_end`。
- 仪表盘「本月资产收益」改成**实时本月**并讲清偏差方向(原来用上个月数据,与"仪表盘=实时"的分工矛盾)。

**无 DB 迁移。** 设计文档:[`prd/v1.10.md`](prd/v1.10.md) · [`tech-design/v1.10.md`](tech-design/v1.10.md)

---

> **↓ 以下是早期段(v0.1 – v0.14.1)· 本文件是这一段的唯一记录**
>
> 中间的 **v0.15.0 – v1.9.4 共 69 个版本**不在这里 ——
> 它们的完整说明在 [GitHub Releases](https://github.com/LuoDi-Nate/financial-management/releases)。
> 这不是断档,是分工:见文件开头的覆盖范围表。

## [v0.14.1] · 2026-07-09

### Changed — UI 打磨(数据源接入 LLM 段 + UAT 巡检)

- **数据源接入 · LLM 段重排**:凭据(API Key)与「调用设置」(主选供应商 / 模型级联 / 温度 / max_tokens / 超时)拆成两组,调用设置进等高统一网格,不再与带大段说明的 key 块参差混排。
- **UAT 巡检修**(headless 真机截图 dashboard / 报表 / 填报 · PC + 手机端):
  - 报表「账户表」类目列此前**裸露 product_category 代码**(`GOLD` / `US_STOCK` / `CASH_DEPOSIT` / `CRYPTO` / `PRECIOUS_METAL`)→ 改显中文名(`pcNameByAccount`),窄 pill 长名竖排堆叠 → `whitespace-nowrap` 横排。
  - 填报账户行警告 `CASH/LOAN 出现未解释变化` 泄露英文枚举 → 改「余额出现未解释变化,建议补一笔收入 / 支出 / 转账对上账」。
- **图表清晰度(UED 视角巡检)**:
  - 「资产配置」环形图挤在净资产趋势旁(1/3 宽)+ 每片「金额+%」双行 → 重叠糊。改:栅格 8/4 → 7/5 加宽,片上只留占比一行(名称/金额进右侧图例 + hover)。
  - 「净资产趋势」名义线原**每点都打金额标签**,密集处重叠发糊 → 点多时只标 首 / 末 / 峰 / 谷。
- **收支趋势线配色**:净流入线由黄铜(近支出朱红)→ 深墨加粗,与红绿柱一眼拉开。
- **emoji → inline SVG**:dashboard / 填报 / 报表 / 持仓 常用页清零 `📦/💡/⚠/✓`,换项目自带 Feather 24×24 stroke SVG(完成勾 / 划转提示 / 持仓入口 / 陈旧告警 / 建议反馈等);排版箭头 `↔ ↳ ⟳` 为单色字形保留。
- **卡片留白节奏**:手机端 2 列 KPI 卡 padding 24→16px、值 26→24px,收紧过重留白;PC 卡片 padding 本已统一(p-6 主)。
- 守护 `v14.1-UAT`(面向用户不泄露英文枚举/代码 · qa →**442**)。

## [v0.14.0] · 2026-07-08

### Added — 贵金属账户 + 自动金价(社区 issue #4 · @BetterQx)

> 详见 [`prd/v0.14.md`](prd/v0.14.md) · [`tech-design/v0.14.md`](tech-design/v0.14.md) · 预览 [`preview/v0.14/`](preview/v0.14/precious-metals.html)

- **`METAL` 贵金属账户类型**:金 / 银 / 铂 / 钯 · 复用现有持仓 / 估值 / 拉价链(与加密对称)。持仓按 **克 / 盎司**(持仓级属性,默认克;1 盎司 = 31.1035 克),记买入价 + 自动现价 → 总估值 = 现价 × 量、盈亏 =(现价 − 买入价)× 量。
- **自动金价**:复用新浪主源新增 `MetalPriceClient`(`gds_*` 上海 SGE / `hf_*` 国际现货),快照统一归一为"原生币种 / 克"(SGE 白银元/千克 ÷1000;国际币种/盎司 ÷31.1035),估值层按持仓单位换算。钯金仅国际盘(SGE 无常见钯,UI 提示改选)。价格源在接入源页可切(默认上海 SGE)+ 独立拉价 cron。
- 迁移 `V38`:`stock_holding.unit` 列 + `METAL` 账户类型 CHECK + `PRECIOUS_METAL` 分类 + 贵金属账户模板(纯新增,向后兼容)。

### Changed — LLM 供应商由管理员自选(FR-B)

- 接入源页新增「**主选 AI 供应商**」(Qwen / DeepSeek · 默认 Qwen · 主选故障自动切备)、「**采样温度**」(0–1 · 默认 0.5)、「**模型**」随供应商级联的**下拉**(内置清单,不手打;越权/空回落「自动(推荐)」,保留 Qwen 多模型轮询)。5 处 AI 调用共用同一路由,一处生效;业务 prompt / 合规校验不变。
- 运营配置开放梳理(FR-C):以上开放到管理页;工程内部旋钮(熔断/缓存/FIRE 假设等)按 10 分钟/月红线保持内部。

- 测试:mvn 337→**353** · e2e 44→**49**(主线 10 贵金属 · 含填报页持仓入口)· qa 436→**440**。
- 修:填报页「持仓变动」入口此前硬编码 STOCK/CRYPTO,漏了 METAL → 金属账户在填报页录不了重量;改走 `supportsHoldings`(覆盖整类)· 账户详情页同修 · 一键刷新估值 + 填报刷新纳入 METAL 市场。守护 `v14-METAL-ENTRY` + e2e 主线10。
- e2e 沉底全链路守护(主线 11):真机新建向导建账户 → 录多类型收入(工资/奖金/利息/其他 4 类现金 + 股票股息)→ 回 dashboard 核对(净资产较基线 +Σ收入、账户列表现新余额)· e2e 48→**55**。
- 修(prod 面):填报页「刷新持仓估值」toast 分母写死 3,而市场从 3→4(加密)→5(贵金属)递增 → 全部成功被误报成「仅 4/3 市场…详情查 journal」的警告。改为动态 `markets.size()`,全成功显示「N 市场估值已刷新」。守护 `v14-REFRESH-COUNT`(qa →**441**)。

## [v0.13.1] · 2026-07-08

### Fixed — 社区 issue #3(@BetterQx)· 股票估值精度截断 + A 股自动拉价报错

> 详见 [`prd/v0.13.md`](prd/v0.13.md) § v0.13.1 补丁 · [`tech-design/v0.13.md`](tech-design/v0.13.md) § v0.13.1

- **精度截断**:未上市持仓手填「单股估值」15.678 / 2.3456 被截成 15.68 → 估值失真。**根因**:`stock_holding.manual_value` 是 `DECIMAL(15,2)`,表单 `step="0.01"` 也只放 2 位。**方案**:迁移 `V37` 把 `manual_value` / `cost_basis` / `stock_price_snapshot.close_price` 统一放宽到 `DECIMAL(20,6)`(纯拓宽,老数据零影响);表单 `step` 到 6 位;单股 / 最新价按真实精度展示(`stripTrailingZeros`,不再强凑或截断小数)。
- **A 股自动拉价报错**:上交所 ETF(如恒生科技 `513180`)全源拉价失败、触发熔断。**根因**:新浪 / 腾讯两个 client 各写死 `startsWith("6") ? "sh" : "sz"`,把 `5` 开头的沪市 ETF 误判成深市 → 请求 `sz513180` 查无此票 → 连续失败熔断。**方案**:交易所前缀规则集中到 `AShareTicker`(沪 = 首位 5 / 6 / 9,深 = 其余),两个 client 复用同一份,消除重复且错误的判断。
- 新增 `AShareTicker` + `AShareTickerTest`(513180→sh 等边界)· Sina / Tencent symbol 单测补 ETF / 科创 / B 股用例 · `StockManualSharesTest` 补单股估值不被四舍五入 · e2e 主线 7 补「15.678 原样落库」DB 断言 · qa `v03-STOCK-5b`(513180 真拉价)/ `v13.1-ISSUE3-PREC` / `v13.1-ISSUE3-CN`。
- 测试:mvn 327→**337** · e2e 43→**44** · qa 433→**436**。

## [v0.13.0] · 2026-07-08

### Fixed — v0.13 · 新账户「开账基线」不计入当期收益(修投资收益虚高)

> 详见 [`prd/v0.13.md`](prd/v0.13.md) · [`tech-design/v0.13.md`](tech-design/v0.13.md) · 预览 [`preview/v0.13/opening-baseline.html`](preview/v0.13/opening-baseline.html)

- **问题**:中途新增一个已有余额的账户(crypto BTC / 新银行卡 / 补录房产…),其存量本金被误算进"钱赚(投资损益)"→ 收益虚高。**根因**:该账户上期不存在、又没记成收入/划转,`钱赚 = ΔNW − 净流入` 把它当成了本期投资赚的。
- **方案**:恒等式加第三项 `ΔNW = 人赚 + 钱赚 + 开账基线(本期新纳入本金)`。「开账基线」= 账户**首次纳入那一期**的期末净值(派生自 `period_snapshot` 历史,零 schema、零用户操作、能追溯已建账户),是外部资本纳入 —— **净资产照常含,所有收益指标剔除**。
- **全指标覆盖**:钱赚(「本期怎么变的」卡单列第三行)· 本月资产收益 % · 家庭 XIRR / 资产 TWR / 年化 · 投资 PnL(累计)/ YTD PnL —— 均把开账基线当外部资本流入剔除;账户表**累计净投入**计入首期开账本金;**财富水位**用剔除开账的真实增长;账户级损益列(收益率/累计损益/本期损益/回撤等)本就用上期余额、天然正确(加测试钉住)。存量/占比类(净资产/总资产/占比/配置环)照常含,不改。
- 新增 `SnapshotMapper.firstAppearingAccountIds` · `FactViewServiceImpl.openingBaseline/netWorthTrendExOpening` · `KpiSnapshot.openingBaselineLast` · `CashflowSplitView` 三分。
- 测试:`CashflowSplitOpeningTest`(三分自洽)· mvn 324→**327** · e2e 主线 9(新账户 → dashboard 出现「开账基线」行 · 41→**43**)· qa `v13-OPENING`(→433)。

### Added — 加密货币账户(社区 PR #2 · @Li-Huanyu)

- **`CRYPTO` 账户类型**:复用现有持仓/估值链(`supportsHoldings()` 泛化"持仓账户"),`Market.CRYPTO` 默认 USD;`stock_holding.shares` 放宽到 `DECIMAL(24,8)` 支持币的小数量;账户模板 + 流动性(SEMI_LIQUID)+ 体检 4 卡 + 配置分类都已接入。
- **加密行情刷新**:Binance(`data-api.binance.vision` 纯行情)主 + CoinGecko / Coinbase 兜底,只发 ticker、无 key;每天 06:15 cron(管理页 `cronCrypto` 可改)。AUTO 估值按报价币种(USD)FX 到账户币种/本位币。
- 迁移 `V36__crypto_account_type.sql`(全 backward-compat:放宽 CHECK + 加精度 + 加模板)。

### Testing 补齐(本仓)

- 安全审计通过(无后门/外带/供应链改动/危险 API/XSS,仅 3 个公开行情域名)· 与 v0.12 兼容性矩阵已核。
- 单测 +`StockPriceFetcherCryptoTest`(三源 fallback 编排)· mvn **324** 全绿(含作者的 client/估值/调度测试)。
- e2e +主线 8(CRYPTO 账户 + AUTO 持仓 + 注入价 → 估值渲染,不依赖外网)· **41** 断言(8 主线)。
- **待办**:crypto 收入接入 v0.12 收入模块(质押/空投)为下一迭代(新 FR,走 PRD→TDD gate);`EntryService.creditAccountBalance` 的 `==STOCK` 建议统一为 `supportsHoldings()`。

## [v0.12.2] · 2026-07-07

### Fixed — 收入口径联动修复(FR-142 遗漏项)+ 币种换算

- **填报页收入列表 / 家庭本月收入合计币种修正**:`cash_flow.amount` 存的是**账户币种**(全系统一致口径);此前填报页收入列表每行硬编码 `¥`、合计把美元/港币金额**跨币种裸加**成 ¥,对非本位币账户(如富途 USD)的收入(尤其未上市 +股数)显示与合计失真。现每行**按原币展示**(`$600.00` / `HK$…`),非本位币再括注 `≈ ¥本位币`;**家庭合计走本位币** = `Σ(amount × fx(账户币种→本位币, 本期))`,与 dashboard「人赚」同源同口径。`EntryController.toBaseAmount`(直接汇率→反向取倒数→1:1 兜底)· qa `v12-INCOME-FX`。
- **dashboard「本期怎么变的」空态误判修复**:收入侧口径切 `cash_flow`(FR-142)后,仅通过收入录入(如股票 +股数,PMC「2框」未填)也应视为已填 —— 但 `CashflowSplitView.empty()` 仍只看 PMC `filledMembers` → 明明录了收入却提示"本期还没填收支"。改为 `PMC 未填 且 无 cash_flow 收/支` 才判空。`CashflowSplitEmptyTest` ×4 · qa `v12-INCOME-EMPTY`。
- **新增 `AGENTS.md`(项目操作手册 · 单一入口)**:全量回顾 PRD + 历史 memory 后沉淀 —— 项目定位/两条产品轴/硬约束、环境拓扑、关键领域概念(双轨收入/股票估值/币种三层/恒等式)、页面地图、**研发迭代全流程(硬 gate)**、关键文件路径与用法、**联动不变量登记表**(改 X→同步 Y→守护)、全局护栏清单、命令速查、发布流程。每次迭代必读、按链改全、能机器校验的落 qa-run,让反复犯的错收敛。
- 单测 307→**311** · 黑盒 429→**431**(+`v12-INCOME-FX`/`v12-INCOME-EMPTY`)。

## [v0.12.1] · 2026-07-07

> 详见 [`prd/v0.12.md`](prd/v0.12.md) · [`tech-design/v0.12.md`](tech-design/v0.12.md) · 预览 [`preview/v0.12/income-stock-journeys.html`](preview/v0.12/income-stock-journeys.html)

### Changed — 股票收入从「填金额」升级为「按持仓入账」(对齐股票账户余额变动机制)

- **股票收入按持仓入账(FR-144/150)**:收入录入先选**类型(现金 / 股票)**;股票则选账户 → **联动该账户已有持仓**,按持仓类型入账:
  - **上市 / 未上市持仓 +股数**:归属到已持有的票(如 RSU 归属),录 `+股数`;上市按最新已知价、未上市按持仓单股估值计值 = 外部流入额,持仓股数随之增加。
  - **新建持仓入账**:首次拿到某票 —— 上市(代码+市场+股数)或未上市(名称+股数+单股估值),建仓不扣现金(收入非买入)。
  - **券商现金 +金额**:股息/利息现金到账,加到该账户现金行(承 v0.12.0)。
  - 一律记外部流入(`is_adjustment=0`,不进 PnL → 收益率不虚高)+ 立即入当期快照 + 汇入家庭收入。
- **未上市(MANUAL)持仓升级为「股数 × 单股估值」(FR-149)**:此前只存整笔市值,现记 `股数 + 单股估值`(账户余额估值 = 股数 × 单股估值),与上市持仓孪生(价格手填而非市价)。持仓页新建/编辑未上市支持股数 + 单股估值。
- **卖出回款不再算收入**:它是账户内「减股+加现金」的调整,不是外部流入 —— 从收入类目下拉隐藏(`stock_sell` 停用);家庭收入唯一以收入模块汇总为准。
- **删/改可逆(FR-145/148)**:`+股数` 收入的 `cash_flow` 记 `ref_holding_id/ref_shares`,删除时按股数精确冲回持仓 + 回退余额;「收入侧」与「账户明细」删按钮共用同一后端,结果一致。

### 迁移

- `V35__stock_income_holding_model.sql`(全 backward-compat):未上市持仓按 **1 股** 折算(`shares=1`,单股=原整笔值,总值不变,用户可后续改正确股数/单股)· 停用 `stock_sell` 类目(排序沉底 + 前端隐藏,不删行)· `cash_flow` 加 `ref_holding_id`/`ref_shares`(nullable,老行全 NULL,0 影响)。

### 测试

- 单测 +13:`StockManualSharesTest`(未上市股数×单股 · createManual/updateManual/addShares/convertToManual 总值守恒)· `ManualHoldingValuationTest`(估值 shares×unit · 老数据 shares=null 兜底 1)· `EntryStockIncomeTest`(+股数外部流入 + ref 冲回)· mvn 294→**307**。
- e2e 主线7 升级为持仓版(未上市建仓 +100 股=5000 → +50 股=2500 → 删除按股数冲回;现金股息承 v0.12.0)· 28→**38** 断言。
- qa-run +v12-MANUAL-SHARES/STOCK-SHARE-INCOME/STOCK-SELL-HIDDEN(黑盒 423→**429**);数字带 迁移 34→**35**。

## [v0.12.0] · 2026-07-02

> 详见 [`prd/v0.12.md`](prd/v0.12.md) · [`tech-design/v0.12.md`](tech-design/v0.12.md) · 预览 [`preview/v0.12/income-entry.html`](preview/v0.12/income-entry.html)

### Changed / Added — 收支填报「收入侧」升级(结构化 · 关联账户 · 直接入账)

- **收入改为结构化录入**:填报页「第一步·本月收支」的收入侧从"手填一个总收入数"升级为**逐笔录入** = `金额 + 类目 + 目标账户`(可多笔)。每笔保存即:写一条 `cash_flow`(INCOME · 真实外部流入 `is_adjustment=0`)+ 直接入该账户本期余额 + 汇入家庭本月收入。**一次录入,不再"2框 + 账户各录一遍"**。支出侧维持"填一个总数"不动。
- **收入支持股票类型 + 类目扩展 + 联动校验(FR-141/143/147)**:类目下拉默认「工资(→现金)」「薪资-股票(→股票)」,并扩 股息/分红、利息、卖出回款;`cash_flow_category` 加 `account_type` 绑定。**选类目 → 目标账户按绑定类型联动过滤**(工资只列现金 / 股息只列股票),**服务端亦校验**错配直接拒(防"工资录进股票 / 股息录进现金")。目标账户本版仅现金/股票(理财/房产 later),LOAN 不作收入落点。
- **股票收入不再虚高收益率(FR-144)**:股票收入(股息/薪资-股票/卖出回款)记为**外部流入**,落到该账户的 CASH 现金行(扛得住「刷新股票估值」重算,不被覆盖)+ 立即入当期快照;从 `PnL = ΔNW − 净流入 − 净划转` 里正确剔除 → 股息不再被当"投资赚的"。取代此前"直接改股票余额→收益率虚高"。
- **家庭收入口径切换(FR-142)**:人赚/净流入/储蓄率/月均收入 的**收入侧**改从"收入录入(cash_flow INCOME)汇总"取;历史账期 PMC 手填收入>0 仍优先(向后兼容,历史指标不变),新账期取 cash_flow —— 按期各取其一、不叠加(防双计)。支出侧口径不变。
- **删/改可逆(FR-145/148)**:「收入侧」与「账户明细」是**同一批流水的两个视图**(同一条 `cash_flow`,非副本)。删一笔 = 软删该 flow + 冲回账户余额(股票冲回 CASH 现金行);账户级其它流水(支出/划转)不受影响。

### 迁移

- `V34__income_category_account_type.sql`:`cash_flow_category` 加 `account_type` 列 + 回填 + 新增 stock_salary/dividend/stock_sell 类目。全 ADD COLUMN NULL + 新行,prod 老数据 0 破坏。

### 测试

- 单测 `CashflowBreakdownTest` +2(PMC 收入缺→取 cash_flow;PMC 与 cash_flow 并存不双计)· mvn 292→**294**。
- e2e `scripts/e2e.sh` +主线7(股票录股息 → snapshot/CASH行 +4200 + 流水 is_adjustment=0;类目↔账户校验拒错配;删除冲回)· 20→**28** 断言 · beta 实跑 28/28。
- qa-run +v12-INCOME-CAT/ENDPOINT/STOCK/KOUJING/UI(黑盒 418→**423**);数字带 版本 11→12 · 迁移 33→34。

## [v0.11.7] · 2026-07-02

### Changed

- **退休「待办」页(`/my-todos`)· 折叠进「填报」**:待办页早已只是 `/entry?mine=true` 的只读子集(列出你名下要填的账户,点「填 →」还得跳去填报页),而填报页本就能内联填 + 有「我未填」标记 + 进度 + 全员提交自动关账;仪表盘也有「本期还有 N 个账户未填」提醒,短信/站内提醒也都指向填报页。三处重叠 + 导航「填报 / 待办」两个并列项对「每月 10 分钟、非技术家属」定位是噪音。
  - 导航去掉「待办」项;把未填数「·N」角标移到「填报」项(`填报 ·N`)。
  - `/my-todos` 保留 **302 → `/entry?mine=true`**(照顾老书签 / 历史深链);删 `my-todos.html` 模板;`MyTodosController` 瘦身为纯重定向。
  - 导航从「仪表盘 · 填报 · 待办 · 账户 · 报表 · 目标 · 资产体检 · 管理」→「仪表盘 · 填报 · 账户 · 报表 · 目标 · 资产体检 · 管理」,单一填报入口。

### 测试

- mvn **292** 绿 · 无头浏览器核对:导航无「待办」项、`填报` 承接角标、`/my-todos` 302→填报页。
- qa-run:`FR6-1/FR6-2` 改为验退休重定向 + 落填报页;`v04-UX-7` 改查 `/entry`;新增 `v11-TODO-RETIRE`(导航无 /my-todos · 填报承接角标 · 重定向 · 模板已删)· 黑盒 417→**418**。

## [v0.11.6] · 2026-07-02

### Fixed

- **dashboard 首屏层级被「财务目标 + AI洞察」压住(排版观感差)**:这两条原挂在 `dashboard/index.html` 顶部(region 外),导致一进 dashboard 先撞见「目标空态引导卡 + AI洞察速览条」,**主角(「家庭仪表盘」标题 + 净资产/KPI 总览)被挤到第 3、5 位**。改为把两条下移进 `_region.html`、放到 **KPI 总览之后**(先看家底,再看目标/洞察)。`index.html` 顶部只留 region;`insight`/`goalsProgress` 本就在 `populateModel`(HTMX 刷新也有),下移后照常渲染。
- **「收支趋势·实时」图在近月无收支数据时留空白大卡**:改为仅当有非零收支(`cashflowSeriesHasData`)时出图;否则显一行空态细条(「填报后出趋势」+ 去填报入口),不再撑出 ~420px 空白。

### 测试

- mvn **292** 绿(仅新增 1 个模型布尔 + 模板搬移,无逻辑变更)· 无头 Chromium 渲染核对 PC + 移动端首屏顺序为「标题 → KPI 总览 → 目标 → AI洞察 → 收支/图表」。
- qa-run +`v11-DASH-LAYOUT`(目标/AI洞察 下移到 KPI 之后 + 收支趋势空态)· 黑盒 416→**417**。

## [v0.11.5] · 2026-07-01

### Fixed

- **全系统「两比例相比」口径审计 → 一律相减 + 单位 pp**(承接 v0.11.4 家庭 XIRR vs基准):逐页逐指标核对,凡「两个比例类指标相比」的结果都应是差额(百分点 pp),不是 `%`。本次修:
  - **配置对照「超配 / 欠配」**(报表第五表):= 当前份额 − 模板份额,是两比例相减 → 单位由 `%` 改 `pp`(份额本身 `cur/tgt` 仍 `%`)。
  - **财富水位「真实收益(剔 CPI)/ 相对社会收益(剔 M2)」**:原用 Fisher 除法 `(1+名义)/(1+基准)−1` 出「真实收益率(%)」;按用户口径改为 **名义增长 − 基准累计** 的超额 pp(`WaterLevelCalculator.realReturnPct` 改相减),展示改 pp + 措辞「名义 − 基准 · 正=跑赢」;LLM prompt 同步为「跑赢 CPI/M2 幅度 X pp」。
  - 审计结论:vs基准 / 预实 / 体检基准对照 / RET 规则本已是 pp(正确);收益率 / XIRR / TWR / 占比 / 回撤 / 负债率 / 储蓄率 / 配置份额 / 风险敞口 / 目标进度 / 环比同比增长 等**单一比率或增长率**保持 `%`(非「相比」)。

### Added

- **报表「观察账期」筛选器(FR-137)**:报表本就是每月快照,现补上与仪表盘一致的 as-of 账期下拉 —— 可在**已关账账期**里回看任一个月的报表快照(收益指标 / 分解 / 负债 / 配置 / 财富水位 / 账户表随之切换)。下拉只列 CLOSED 账期;`asof` 命中则锚它、否则默认最近已关账;切 range/币种/账户范围时保留所选账期。以「默认锚」作下拉上界,规避 JVM/DB 日期偏差把当前锚挤出下拉。

### 测试

- `WaterLevelCalculatorTest` 2 断言改口径(相减 pp);mvn **292** 绿。
- qa-run +`v11-AUDIT-PP`(两比例相比一律相减+pp)+`v11-REPORTS-ASOF`(报表观察账期筛选器);黑盒 414→**416**。
- e2e `scripts/e2e.sh` **20/20**(主线 3 报表 pp/指标列断言 + 主线 1/2/4/5/6 无回归)。

## [v0.11.4] · 2026-07-01

### Fixed

- **报表第一卡「家庭 XIRR · 含收入」vs基准 pill 显示 `跑输 -243.42%`(单位错 + 实际取值错)**:
  - **单位**:分子/分母都是比例的 diff 应是「百分点 pp」,不是 `%`。家庭卡 + 账户行的 跑赢/跑输/持平 pill 统一改用 `pp`(基准值本身仍是 `%`)。
  - **实际取值(根因)**:diff 的「实际」侧原取 `cumPnl ÷ 净投入`(v0.10.5 引入)——净投入极小的账户/家庭会爆成 `+19497pp` 之类,且与卡片头条显示的那个 XIRR 是两个数(头条 8.30% 却显示跑输 -243%)。改为「实际 = 卡片显示的那个 xirr 本身」(与 `XirrCalculator.annualizedOrCumulative` 同口径:<12 期为累计回报、≥12 期为年化),基准同基对齐(≥12 减年化基准、<12 把基准缩放到持有月数)。beta 实测:家庭 8.30% − 加权基准 4.61% = **跑赢 +3.69pp**,量级回归理智。
  - 同源修 `FactViewServiceImpl` 账户「预实」(planActualDiffPct,仪表盘同列)——同样从 `cumPnl/净投入` 改为「显示的 xirr − 预期」。

### Added

- **报表「第四表·账户级收益」复用管理页指标配置**:此前只有 XIRR/基准/vs基准/当前价值 4 列,而控制器早已算好全字段 `AccountPerformance` 却被压缩丢弃。现改为迭代全字段 + 按 `/admin/metrics`(账户级)开关门控渲染指标列(收益率/本位币年化/累计损益/累计净投入/本期损益/本期Δ/最大回撤/占比/持有期数/预实/近况),与仪表盘账户表同一套开关;补「显示指标」chips 条,与仪表盘共享 `acctHiddenCols` 隐藏集(两页一致)。保留报表特有的 类型/类目/基准%/vs基准 列。

### 测试

- 新增 `BenchmarkAggregator.displayedDiffPercentPoints` / `beatStatusDisplayed` + 3 单测(年化/短窗缩放/空值)· mvn 289→**292**。
- qa-run:改 `v10-WINDOW-1`(口径升级为 displayedDiffPercentPoints)· 新增 `v11-REPORTS-METRICS`(配置化指标列)+ `v11-REPORTS-PP`(pp 单位 + 实际=显示的 xirr,无爆值)· 黑盒 412→**414**。
- e2e `scripts/e2e.sh` 主线 3 加 3 断言(账户表 data-mcol 指标列 · vs基准用 pp 不用 % · 无 |pp|>1000 爆值)· 17→**20** 断言 · beta 实跑 20/20。

## [v0.11.3] · 2026-07-01

### Fixed

- **报表「月度收支双柱 / 收支趋势·人赚引擎」永远空(fragment 边界 bug)**:`reports/index.html` 用 `~{reports/_savings :: section}` 只引入 `<section th:fragment="section">`,而画这两张图的 `<script>` 写在 `</section>` **之后**(fragment 外)→ 引入时脚本被丢 → canvas 无人渲染(KPI 在 section 内故正常,唯图空)。把 fragment 的 `</section>` 挪到 `<script>` 之后,让图表脚本进 fragment。beta 实测:`getElementById('savings-bars')` 已在、`const income=[…]` 有值。
  - 前提口径不变(决策 B):这两张图仍只统计家庭月度「2 框」(`period_member_cashflow`)。prod 已填 05/06 的 2 框(PMC 有数据),故本修复上线后即出图。

### 测试

- qa-run +`v11-REPORTS-2`(储蓄区图表脚本在 fragment 内)· 黑盒 411→**412** · mvn 289。

## [v0.11.2] · 2026-07-01

> 切月(6.30→7.01)账期滚动两处生产 bug 修复。

### Fixed

- **旧期不自动关(bug1)**:滚动 cron(每天 00:30 `PeriodOpener.openIfDue`)**只开新期、从不关旧期** → 06 悬挂 OPEN(月报/指标/关账快照不落定),与新开的 07 同时 OPEN。改为**开新期前先 force-close 早于新期仍 OPEN 的旧期**(待填账户按上期末延续 + 触发月报/指标):新增 `PeriodMapper.findOpenBefore` + `PeriodOpener.closePriorOpenPeriods`;`openIfDue`(自动)与 `openNextNow`(管理员手动)同口径。先关旧期 → 新期从已落定的上期结转。
- **LOAN 还平后凭空 +72000(bug2)**:LOAN 开账预填用趋势外推 `prev+(prev−prevPrev)`,贷款一次性还平(-72000→0)后外推越过 0 变 **+72000**。改为**夹到 ≤0**(`predictLoanBalance`:`predicted>0 → 0`);草稿还款额改用 `predicted−prev`(只在实际是还款时起草,已还平不再起草)。房贷匀速还款(仍为负)不受影响。
- **报表「负债曲线 / 本金 vs 投资损益分解图」少画一期(bug3)**:`ReportsController` 把 `labels` 错接成 `decomposition` 标签(N−1 期)。负债曲线用 `labels`+`debtValues`(N 个)→ 只画 N−1 点(2 关账期→1 点);分解图用 `labels.slice(1)`(为对齐 N−1 个分解点)→ 再少一期 → N−2 柱(2 期→0 柱)。改 `labels` 用**全期标签**(`debtTrend`)→ 负债曲线 N 点、分解图 slice(1) 正好对齐 N−1 柱。
- **储蓄区空态文案**:双柱/收支趋势/储蓄率只统计填报页底部的**家庭月度「2 框」总收入/总支出**(`period_member_cashflow`),账户级 `cash_flow` 流水不计入。引导卡文案改为讲清这一点(决策:不做 cash_flow 回退,统一走「2 框」以保口径干净)。

### 测试

- mvn **289**(含 `PeriodOpenerLoanPrefillTest`)· qa-run +`v11-ROLLOVER-1`/`v11-REPORTS-1`、黑盒 409→**411**。

### 现网数据补救(修复仅防后续复发,不追溯已生成的历史数据)

- 06:管理 → 周期管理 **关账**(force-close)。
- 07:填报页把 **迪娃-税务欠款** 余额重填 **0**(覆盖被外推写入的 +72000;该账户 07 待办仍 PENDING 可直接填)。

## [v0.11.1] · 2026-06-30

### Fixed

- **隐私模式 peek 与链接跳转冲突**:多数被遮金额在 `<a>` 内(KPI 跳 /checkup、账户行跳详情),原 peek 在 `pointerdown` 即去模糊,松手那次 click 会误触发跳转。改为**真·长按阈值**(`HOLD_MS=320ms`):**短按 = 正常跳转**(数字仍隐藏)、**长按 = peek 显示** 并在 capture 阶段**抑制随之而来的 click**(不跳转)。`pointermove` 超 10px(滚动/滑走)取消长按。不再在 `pointerdown` preventDefault(避免阻断短按跳转与滚动),防选中/呼出菜单交给 CSS。
- **iOS PWA 长按弹出「链接预览」小窗**:长按被遮金额会触发其外层 `<a>` 的 iOS 链接预览(预加载目标页小窗)。`-webkit-touch-callout:none` 原只设在内层 `[data-priv]`,触发预览的是外层链接 → 改为隐私态对 `html.privacy a`(及其子元素)关 `touch-callout`(不影响轻点跳转);并对 `[data-priv]` 拦 `contextmenu` 兜底。

## [v0.11.0] · 2026-06-30

> 隐私模式:公共场合 / 分享时一键隐藏金额,保留比例。

### Added(FR-116~122 · 决策 126–131)

- **一键「隐私模式」· 双入口**:nav 眼睛 + 常驻左下浮动控件,同步同一 `html.privacy`(两入口只切 class、眼睛 eye/eye-off 纯 CSS 跟随 → **零 JS 同步**)。一点全站绝对金额**瞬时高斯模糊** + 不可选中复制;百分比 / 收益率 / 占比 / 紧急储备月数 / 走势形状照常。
- **遮金额留比例**:KPI / 账户余额 / 账本流水 / 收支 / 盈亏 + 图表 y 轴金额刻度与金额数据标签(经各页 `fmtMoney` 统一隐藏);比例与曲线 / 扇形形状保留。
- **会话级记忆**(sessionStorage):会话内跨页 / HTMX 片段 / 币种账期切换保持;关闭重开默认显示。**FOUC 防闪**:`<head>` 同步脚本据 sessionStorage 早设 `html.privacy`,无明文闪现。
- **随处恢复**:隐私态下浮动控件展开「金额已隐藏 · 长按可看 · 点我恢复」chip,任意滚动位置一键恢复(解决长页滚到底够不着 nav)。
- **按住临时查看(peek · FR-123)**:隐藏态下按住某个被遮金额 → 临时显示,松开/滑走/滚动/失焦 → 立即复原;只显示按住的那一个。手机长按 + 桌面按住通用(事件委托 + Pointer 事件,覆盖 HTMX 片段);触摸阻止长按选中/菜单。比"点开保持"更安全。
- 纯前端叠加:**0 schema / 0 接口 / 0 数据 · prod 升级 0 风险**。已知局限:DOM 仍含真值(威胁模型 = 肩窥 / 截图,非取证级);模糊保留字符宽度(定宽容器压低量级泄露)。

### 测试

- qa-run +`v11-PRIVACY-1/2/3`(基建 + 全 5 页金额标记覆盖 + 比例不误遮 / 图表守卫)· 黑盒 405→**409** · mvn **283** 不变 · 0 schema。

## [v0.10.6] · 2026-06-30

> 账本去 emoji(no-emoji 纪律)+ qa 去过期收尾。

### Fixed

- **账本行去 emoji**:`/entry` 账本 VALUATION 行标签 `📈 估值` → `△ 估值`(`EntryController` 用 Java 拼 HTML 渲染,故未被模板级 emoji 守护覆盖);与 `accounts/detail.html` 的 VALUATION 渲染(`△ 估值`)及同处兄弟标签(`+ 收入`/`↳ 划入`/`↱ 划出`/`= 校准`)风格统一。遵 no-emoji 纪律。
- **守护扩面**:`v08-8` emoji 扫描纳入 `EntryController.java` → 网住「账本行 emoji」整类回归;`v04-VAL-2` 锚词 `📈 估值` → `△ 估值`。

### 测试

- qa-run PASS=406 / FAIL=0 · 黑盒计数 405 不变(仅改锚词/扩守护面)· 零 schema。

## [v0.10.5] · 2026-06-30

> 指标计算口径分析师级梳理:修「短账户拿几个月累计去比年化预期/基准」的时间口径错。

> 同版还含 **qa 逐 case 过期审计**(判断列表 `docs/qa-audit-v0.10.6.md`):修 6 处过期用例(`FR13-1`/`v02-FR40e-2`/`v02-FR38-1`/`v03-IND-6`/`v02-LEDGER-1`/`v02-FR30-7`,均仍 PASS 却靠注释/JS残留/巧合/旧阈值续命),与 `v02-PILL-1`/`LEDGER-2` 一并解耦 demo 数据量级。

### Fixed(决策 125)

- **收益对比「同窗口」口径**:账户 `xirr` 满 12 期才年化、不足 12 期为**累计**(刻意不外推);但 `预实`、reports `vs基准`、家庭 `vs基准` 都拿它去减**年化**预期/基准 → 短账户「几个月累计 2%」被错判「跑输年化 8%」。改为**实际累计回报 vs 预期(年化按持有月数缩放到同窗口)**:`预期窗口 = (1+年化)^(月数/12)−1`;跑赢/输阈值同窗口缩放(`beatStatusWindow`)。账户/reports/家庭三处统一用 `BenchmarkAggregator.windowDiffPercentPoints`(实际累计:账户 cumPnl/净投入、家庭 累计PnL/累计净投入)。
- **「年化」标签诚实化**:`<12 期`时为累计的列动态标注——账户「收益率/本位币年化」per-cell 加「累」角标(tooltip 说明);家庭「资产年化」<12 期显示为「资产累计」。预实 per-cell 标「近 N 月」。
- 梳理确认 OK(无需改):本月资产收益(本月口径)、净资产环比/同比(月/年口径清楚)、收入/支出/人赚/钱赚/累计损益/本期损益(金额非率)。checkup 账户 vs 基准本就 gate「满 12 期」,本次让 dashboard/reports 与之口径一致(改用窗口缩放,短账户也能正确比)。

### 测试

- mvn 280→**283**(`BenchmarkAggregatorTest` +窗口缩放/阈值/「月度2% vs 年化8%=跑赢」三例)· qa-run +`v10-WINDOW-1`、黑盒 404→**405** · 零 schema、纯计算口径 + 展示。

## [v0.10.4] · 2026-06-29

> 账户级指标"超卖"修复:管理页勾了一堆账户指标,dashboard 账户列表却不显示。

### Fixed / Added(决策 124)

- **账户指标勾了不显示**:账户级指标目录有 15 项,但全站唯一消费它的 dashboard 账户列表只渲染了 6 列(current_value + xirr/cum_pnl/mom_delta/share_pct/sparkline),其余勾了等于空操作(v0.8 扩了数据+目录但模板列没补完)。补齐 PC 表列:`累计净投入(net_principal)` `本期损益(period_return)` `本位币年化(return_base)` `最大回撤(max_drawdown)` `持有期数(months_held)` `预实(plan_actual)`(数据本就在 `AccountPerformance`)。
- **目录不超卖**:`twr`(仅家庭级)/`yoy`/`risk`(未接账户级)无 per-account 数据 → 从账户目录移除;`period_return` 标签由"本期收益率"改"本期损益"(渲染 latestPnl 金额)。
- **列多了的展示交互**:账户名列 **sticky 左固定**;指标列超过最佳展示数(7)时表格**左右横滑**展示全部(容器 overflow-x-auto);新增**内联指标筛选 chips**(表上方点选即时切某列显隐,纯前端 + localStorage 记住,默认=管理页「指标设置·账户级」勾选集)。**手机端同样生效**:chips 在手机也显示,账户卡片的指标项同样按「指标设置」门控 + 受 chips 控制(原先手机卡片是写死的、不反映勾选——一并修)。
- 测试:mvn 280(`MetricPrefsServiceTest` 同步 twr→max_drawdown)· qa-run +`v10-ACCT-COLS-1`、黑盒 403→**404** · 零 schema、向后兼容(已存 prefs 的 twr 等失效 key 被 catalog 驱动忽略,无害)。

## [v0.10.3] · 2026-06-29

> 自用复盘三连:收益口径(不再扣通胀)+ 长文目录补漏 + 守护自身 bug 修复。

### Changed · 收益一律名义口径(决策 123)

- **AI 洞察「真实收益(扣CPI)· 跑输通胀」口径误导** —— 把"扣 CPI 后的真实收益"当标准收益摆在洞察里,和环比/本月资产收益挨着,三个负数看着像打架(实为净资产总变化 / 纯投资 / 扣通胀真实,三个不同口径)。修正:**洞察/体检/财富水位的收益数一律用「净资产名义增长」(不扣通胀)**;通胀/社会财富只作**图上的参照线**(CPI 购买力线 / M2 社会财富线**保留**,让用户感受自家收益率 vs CPI,但不替他从收益里扣)。`WaterLevelService` 暴露现成的 `nominalGrowthPct`;`realReturnPct` 计算保留不展示。改 3 处模板(dashboard 速览 / checkup 体检 / reports 财富水位),`相对社会收益·剔M2` 与 CPI/M2 对比线**均保留**(剔M2 非通胀)。`qa-run v10-NOMINAL-1` 防回归。

### Fixed · 长文目录(承 v0.10.2 审计)

- **dashboard 实时收支趋势漏进目录**:`收·支·趋·势·实·时` 是 `dash-cashflow` 节内第二张卡,有标题没独立 id → 不在目录、且 v10-TOC-SYNC 守护(只扫带 scroll-margin-top 的 section)也没网住(审计盲区)。补 `id=dash-cashflow-trend` + scroll-margin-top + tocItems 条目「收支趋势」。
- **守护自身的"假绿"bug**:`v10-TOC-SYNC` 提取锚点/ id 用了 `[a-z0-9-]+$`,但 `href:'#x'`/`id="x"` 结尾是引号,GNU grep 下 `$` 匹配不到 → 集合为空 → 守护可能假绿。改 sed 捕获组 `s/.*#([a-z0-9-]+).*/\1/`,跨 grep/ugrep 都稳。复审三页所有 eyebrow 卡片标题:唯一真漏即上面那张趋势卡。

### 测试

- mvn 280(WaterLevel record 加字段,计算/单测不变)· qa-run +`v10-NOMINAL-1`、黑盒 402→**403** · 零 schema、向后兼容(本位币视图不变)。

## [v0.10.2] · 2026-06-29

> 长文目录(TOC)漏维护防护 + 全量审计。回应「新增功能是否忘了加进各页目录」。

### Added(测试守护 · 防回归)

- **`qa-run v10-TOC-SYNC-1/2`**:把 3 个目录页(dashboard/checkup/reports)的 `tocItems` 手工维护变成 CI 闸门 —— ① 任何带 `scroll-margin-top` 的 `dash-`/`checkup-`/`sec-` 前缀 section 必须出现在对应页 `tocItems`(加 section 漏加目录条目 → 红);② 每个 `tocItems` 锚点必须有真实 `id`(删/改名留下死链 → 红)。以后新增 section 忘了挂目录,QA 直接拦下,不再靠"记得"。
- **全量审计结论**:dashboard / checkup / reports 三页当前**均已同步**,无功能漏进目录(v0.10 的 `dash-cashflow` 已正确加入)。`checkup-advice` 的"重复 id"为良性设计(有建议 / 无建议两态 `th:if` 互斥,共用锚点)。
- 黑盒 400→**402**;记忆 `feedback_toc_sync` 标注"已自动化"。

## [v0.10.1] · 2026-06-29

> 两个 bug 修复:① **多币种家庭切币种,差额类指标(ΔNW/净资产环比/本月收益/钱赚)不按单一汇率缩放**(prod 上 ΔNW 实测偏 ~17%);② **填报短信提醒多发 1 天**。详见 [`prd/v0.10.md`](prd/v0.10.md) v0.10.1 段 / [`tech-design/v0.10.md`](tech-design/v0.10.md) 决策 121-122。

### Fixed

- **币种「单一镜头」根因修复(决策 121 · v10-CCY-LENS)**:净资产换算原先用**每期各自的历史汇率**(`FactMapper` 的 fx 三角换算 join `period_id = p.id`)。单期金额各按当期汇率缩放没问题,但**跨期差额**(ΔNW=期末−期初)被减数/减数用了不同月份的汇率,在多币种家庭(余额大)上 → 切币种时差额类指标不按同一汇率缩放(prod 实测 ΔNW 在 USD 视图按 0.1716 而非应有的 0.1471,偏 ~17%)。改为**取锚点期(窗口内 period_start ≤ rangeEnd 的最新一期)的单一汇率换算所有账期** → 金额/差额/比值三币种全部按同一汇率均匀缩放,彻底符合「币种=显示镜头」。代价:外币视图下净资产趋势 = 本位币趋势 × 常数(不再含历史汇率波动,这是显示镜头应有之义)。这也让净资产侧与 PMC 侧(v0.8 已用锚点汇率)口径统一,消除"补一边爆另一边"的系统性矛盾。
- **填报提醒多发 1 天(决策 122 · v10-REMIND-1)**:`ReportReminderScheduler` 窗口原为 `daysLeft ≤ leadDays`,即 `[0, leadDays]` 共 **leadDays+1** 天 —— lead=2、截止 6.30 时误发 6.28/6.29/6.30 三天。收敛为 `daysLeft < leadDays`(`[0, leadDays-1]` 共 leadDays 天)→ lead=2 只发 6.29/6.30。窗口判断抽成纯静态 `inReminderWindow(daysLeft, leadDays)` 便于单测。

### 测试 / 防回归(关键)

- **真·端到端币种守护**(补上一直缺的那一环):`qa-run` 新增 `v10-CCY-LENS-1/2` —— 登录后请求**真** `/dashboard?currency=CNY/USD`,解析实时收支趋势 + 净资产趋势,断言**各期逐点按同一汇率均匀缩放**。这正是 `CurrencyInvarianceTest`(单元 + 单一 mock 汇率)永远测不出的场景:它把"多期不同历史汇率"抹平了。需 family 有非 base 账户 + 多期变动汇率方有意义(beta diwa 家满足)。
- **SMS 窗口单测**:`ReportReminderWindowTest`(5 例)锁死 lead=N → 恰好 N 个提醒日、过期不发。
- 测试:mvn 275→**280** · qa-run +`v10-CCY-LENS-1/2`、黑盒 398→**400**。prod 只读复算确认修复后 ΔNW 三币种按 0.1471 精确缩放。
- **向后兼容**:`FactMapper` 改动对**本位币视图结果完全不变**(view==base 时 fx 因子恒为 1,与历史汇率无关);仅多币种外币视图的"差额类"数值变正确。零 schema。

## [v0.10.0] · 2026-06-29

> 仪表盘补回「收支」那半边——以**实时**形态。承接「净资产变化 ΔNW = 人赚(你存下的)+ 钱赚(钱替你赚的)」主轴:此前实时仪表盘只显钱赚侧(投资收益/财富水位),人赚侧(收入/支出/净流入)在 v0.4 被搬去 `/reports` 储蓄区(已关账快照,不含本月)。本版把人赚那一刻的拆解 + 实时收支趋势补回首页。详见 [`prd/v0.10.md`](prd/v0.10.md) / [`tech-design/v0.10.md`](tech-design/v0.10.md)(决策 114–120)。

### Added

- **本期「人赚 vs 钱赚」实时拆解卡**(FR-165):`ΔNW = 人赚 + 钱赚`。三数复用现成 `KpiSnapshot`(netWorthDelta / lastNetInflow / monthlyPnlAmount),零新增计算。
- **有符号双向条**(FR-165 · 评审强化):人赚、钱赚各自可正可负,零基线居中、正右(绿)负左(赭),长度 ∝ |值|÷三者最大绝对值;`(+,+)/(+,−)/(−,+)/(−,−)` 四象限统一一套画法,一句话文案随象限自适应。首期无上期 → 只显人赚。
- **本期毛收入/支出明细 + 完整度诚实**(FR-166):新增 `FactViewService.cashflowBreakdown`,与人赚**同源同分支**(PMC 优先 · 空回退 cash_flow · view 币种),保证「收入−支出==人赚」;PMC 成员级 `已填 N/M` 三态——空态引导填报(投资侧仍显)、半填挂琥珀 pill 并注明「人赚是下限」、全填完整展示。
- **实时收支趋势小图**(FR-167):新增 `FactViewService.cashflowSeries`,收入/支出柱 + 净流入(人赚)线,**含进行中本月**(最右浅色,区别于 `/reports` 已关账快照);数值用 datalabels 浮于柱顶/数据点上。
- 新 `<section id="dash-cashflow">` 置于 KPI 带与净资产趋势之间;同步 dashboard 长文目录(`tocItems` + `_toc` + `toc.js`)新增「人赚 vs 钱赚」锚点。

### 设计取舍

- **不挂 metric-pref 开关**(决策 119 修订):dashboard 的 section 本就不受指标开关控制(只有 KPI 豆腐块/头部受控);且 `MetricPrefsService.enabled` 的 `defaultOn` 仅在「整份 prefs 为 null」时生效,已自定义过指标的老家庭升级后反而看不到新卡——故 section **无条件渲染**,与既有 section 一致、零兼容坑。
- **不恢复** v0.4 砍掉的月均/储蓄率中位/收支双柱大图(留在 `/reports` 储蓄区);不做分类预算。

### 防回归 / 兼容

- 单测 +12:`CashflowSplitViewTest`(四象限/三态/双向条宽度)、`CashflowBreakdownTest`(PMC优先/回退/首期)、扩 `CurrencyInvarianceTest`(三币种 人赚+钱赚=ΔNW 恒等、比例不变、金额按 fx 缩放、收入−支出==人赚同源)。
- 测试:mvn 263→**275** · qa-run +`v10-CASHFLOW-1~4`、黑盒 394→**398**(README/landing 数字带/release skill 同口径联动)。
- **零 schema**、向后兼容:纯展示 section + 两个只读 service 方法 + 视图模型;不动既有 KPI 算法、不动 `/reports` 快照语义。

## [v0.9.3] · 2026-06-26

> 表单提交全量审计:缺必填项一律**客户端前置拦截**,不再「发请求 → 拿 400 / 存了脏数据」。承接 v0.9.2 划转空字段拦截,扫遍全站写表单补齐。详见 [`prd/v0.9.md`](prd/v0.9.md) v0.9.3 段 / [`tech-design/v0.9.md`](tech-design/v0.9.md) 决策 113。

### Added / Fixed

- **通用「条件必填」助手**(决策 113):新增 `data-require-when="控件名=值"` 声明式属性 + layout footer 全站注入的小脚本——当同表单内某 radio/checkbox/select 命中指定值时才给该字段挂 `required`,命中外自动摘除(避免对隐藏/不适用字段误挂 required 卡死提交)。一处助手,多处复用。
- **补齐缺失的必填拦截**(全量审计结论):
  - entry 收入/支出金额 → `required min=0.01`(原先空提交;三个 cash-flow 表单各自独立,互不阻塞)。
  - 应急金「手填基线」`fixedBaseline` → `data-require-when="autoBaseline=false"`(仅选「手填」时必填,选「自动基线」不挡)· 新建 + 编辑两页一致。
  - 自选股「从现金划转买入」`costBasis` → `data-require-when="deductCash=true"`(UI 早已写「划转买入时必填」却没强制,补上)。
  - 宏观基准录入 CPI/M2 → `required`(空值无意义)。
  - 成员编辑「显示名」→ `required maxlength=40`(原先仅新增有校验,编辑可清空提交)。
- **刻意保持可选的**(审计后确认,不挂 required):entry 期初汇总收支(占位明写「留空=未填」)· 短信 AccessKey/密钥(「留空=不修改」增量更新设计)· 划转到账额 `toAmount`(仅跨币种填)· 角色标签 / 备注 / 成员手机号 / 各类带默认值的运营参数。
- 测试:mvn 263 · qa-run + `v09-FORM-1~6`(收支金额必填 / 条件必填助手就位 / 应急基线 + 划转成本条件必填 / 宏观必填 / 成员显示名必填)· beta 真机走查各表单空提交均被前置拦截。纯模板 + 1 处全站脚本,零 schema、向后兼容。

## [v0.9.2] · 2026-06-26

> 填报/划转错误体验修复(自用记账时发现):错误提示被顶栏挡住看不见、空字段提交报裸 400 不可读。

### Fixed

- **全局错误 toast 被顶栏挡住(z-index 层叠 bug)**:`#toast-stack` 虽 `z-[10000]`,但被嵌在 `<footer relative z-10>` 内,被 footer 的层叠上下文困死、沉到 nav(z-30)之下。去掉 footer 的 `z-10`,toast 重回根层叠上下文、盖在最上层。
- **划转空字段前置拦截**:划转金额加 `required`(+`min=0.01`),空值客户端直接拦、不再发请求拿 400。(账户下拉 `data-searchable` 会隐藏原生 select,不能加 `required`,且其默认选中首项不为空,故只对金额加。)
- **参数绑定错给可读 toast**:空串→数字等绑定/类型转换失败原本是裸 400(走 layout 兜底「请求被拒绝(400)」)。`ToastErrorAdvice` 增加对 `MethodArgumentTypeMismatch`/`MissingServletRequestParameter`/`HttpMessageNotReadable` 的处理,HTMX 写操作下返回 200 + 可读 toast「输入有误:请检查账户、金额等必填项是否填写正确」。
- **净资产图 CPI「购买力线」口径修正**:dashboard 净资产趋势的 CPI 对照线原画成「名义净资产折现」(`名义 ÷ (1+CPI)^期`)——永远压在名义线下、形状跟着名义走,既不反映所选 CPI 速率、也与 M2 基准线和 `/reports 财富水位` 口径不一致。改成与它们一致的**购买力保命线**:`期初净资产 × (1+CPI/12)^期`(名义在其上方 = 跑赢通胀)。图例改「CPI 购买力线」。`qa-run v09-CPI-1` 防回归。
- 测试:mvn 263 · qa-run + `v09-UX-1/2/3`(toast 不被挡 / 金额 required / 空金额→可读 toast)· beta 真机三项验证通过。纯模板 + 1 处异常处理,零 schema。

## [v0.9.1] · 2026-06-26

> 落地页精修 + 对外门面打磨。参考 brew.sh / ohmyzsh.sh 改**居中单列**布局,加一组克制的微交互(全在晚清账册风内、无 emoji)。详见 [`prd/v0.9.md`](prd/v0.9.md) v0.9.1 段 / [`tech-design/v0.9.md`](tech-design/v0.9.md)。

### Added / Changed

- **居中单列重排**(FR-163):hero 居中(账册图标 → 标语 → 真实快速开始命令 → )→ 它解决什么(四问)→ 三支柱 → 工程数字带 → 截图 → 适合/不适合 → 页脚。中轴对齐,问题→解法衔接。
- **8 个小巧思**:① GitHub 角标 + 悬停挥手(Tim Holman 式,染墨/纸色,`position:fixed` 对齐)② GitHub-Star 按钮(实时拉 star 数,0/失败优雅缺省)③ 进场错落浮现 ④ CTA 下划线生长 ⑤ 一键复制命令 ⑥ 纸张颗粒纹理 ⑦ 卡片悬停轻抬 ⑧ 数字带滚动。`prefers-reduced-motion` 静默、无 JS 不破。(朱印评审去掉。)
- **「它解决什么」四问**:把面向群体(家庭 / 夫妻、钱散多处)与价值讲清楚;紧接三支柱即四问的答案。
- **快速开始改真实 4 步**(不假装一键):`git clone` → `cd` → `bash deploy/docker-init.sh` → `docker compose up -d`,多行命令块一键复制。
- **工程数字带(9 / 263 / 33 / 384)走「发版联动」**:`release-prod` skill preflight 新增**硬门**——版本=`prd/v0.*.md` 个数、迁移=`db/migration/V*.sql` 个数(自动算),单测/黑盒=README「N 单元 / N 黑盒回归」(发版本就更 README);landing 数字过时即 `die` 给出应改值。`qa-run v09-LAND-6` 同口径日常守护,SKILL.md 同步铁律。
- 测试:mvn 263 · qa-run + `v09-LAND-5/6`(精修元素在 + 数字带联动)· beta 真机六项全绿、零 emoji。零 schema、向后兼容(仅模板 + skill + 文档)。

## [v0.9.0] · 2026-06-25

> 根路径公开落地页:把首屏从「裸登录框」换成有品牌/说明/截图/开源链接的介绍页。既给项目对外门面,又消除 Chrome「Deceptive pages」误判的触发特征(`.top` 域 + token + 首屏裸登录)。详见 [`prd/v0.9.md`](prd/v0.9.md) / [`tech-design/v0.9.md`](tech-design/v0.9.md)。

### Added / Changed / Fixed

- **根路径分流(FR-160)**:`/` 未登录 → 公开落地页 `landing`(不再 302 跳 `/login`);已登录 → 沿用既有 dashboard/onboarding 分流。`SecurityConfig` 放行精确根 `/`(非 `/**`);`/login` 与认证逻辑完全不变。
- **落地页(FR-161)**:复用 layout head(**自托管 tailwind + 字体 + style.css,零外部 CDN**)· 品牌+定位 → 三支柱(月度快照记账/真实年化/AI 诊断)→ 功能总览截图 → 适合·不适合 → 自托管隐私 → CTA(登录 + GitHub 全 URL)· 晚清账册风 · 全 inline SVG 无 emoji。截图 `feature_summary_total.jpg` 落本地 `static/img`(不外链)。
- **降钓鱼信号(FR-162)**:首屏即真实介绍页 + 可见开源仓库链接,削弱「unfamiliar 域上的裸登录」判定,配合 Search Console 申诉。
- **教训(已记 tech-design 决策 108 + 记忆)**:实现时误新建 `web.HomeController`,与既有 `common.HomeController`(早已 `@GetMapping("/")`)同 bean 名 → `ConflictingBeanDefinitionException`、beta 启动崩溃重启循环。根因:探落点只 grep `web/`、漏看 `common/` 既有 `/` 映射。改为在既有控制器加匿名分支(保住 v0.7 onboarding 行为)。
- 测试:mvn 263 单元 · qa-run + `v09-LAND-1~4`(匿名 /=200 落地页含定位+GitHub+截图 / 不再跳 login / 已登录→dashboard / 回归:匿名 dashboard 仍要登录);beta 真机四项全绿、零 emoji。零 schema、向后兼容。

## [v0.8.0] · 2026-06-23

> 自用两月痛点驱动:指标可见性 + 时间筛选器按账期重做 + 可配置指标集 + 计算正确性。详见 [`prd/v0.8.md`](prd/v0.8.md) / [`tech-design/v0.8.md`](tech-design/v0.8.md)。

### Added / Changed / Fixed

- **P1 · 账户指标端出 + 真 sparkline + 列表排序**:dashboard 账户列表从「当前价值/XIRR」扩成账户级指标全集(累计投资损益、累计净投入、本期Δ、占比、最大回撤、持有期数),手机改可展开卡片(首屏精简 + 展开看全集);**修掉硬编码假 sparkline**(此前所有账户同一条假上升线 → 改后端真实月末走势 points,<2 期降级,涨绿跌红);列表点列头 正序→倒序→默认 三态排序(空值沉底,aria-sort)。
- **P4 · 时间筛选器按账期重做(修 Q2「筛选器没用」)**:新增 **as-of 观察账期**(默认最新,可选历史月)→ 所有点状 KPI 随之变成「那个月」的状态(此前恒为最新期);窗口只驱动趋势/区间回报;头部加 **MoM 环比 / YoY 同比**(以 [as-of−12, as-of] 最小窗口实时算、与显示窗口解耦,缺对比期显「数据不足」)。**派生指标一律实时算、不落库。**
- **P2 · 可配置指标集 + 预实分析**:管理页 `/admin/metrics` 勾选「我关心的指标」(家庭/账户两组,必选项强制、进阶项默认关),dashboard 与报表共用此配置、各页按上限展示;**预实分析**(账户实际收益率 vs 预期 —— 账户可设 `expected_return_pct`,留空回落品类基准)。
- **P3 · 计算正确性**:① **跨币种转账**加 `to_amount`(转入按到账币种入账,`FactMapper` COALESCE 读;同币种零影响、旧记录零回填)② **Problem B**:手动改股票账户内部现金行 → 记 `is_adjustment` 流水把这笔本金进出从投资损益剔除(不再污染 XIRR)③ **Problem C**:非本位币账户给「原币收益率(剔汇率)+ 本位币收益率(含汇率)」双值。
- **跨币种不变性根治(v08-CCY-INV-2 · beta 验收暴露)**:切币种(CNY→HKD)后「本月资产收益率」乱漂(CNY −18% / HKD −9% / USD −88%)。双重根因:① v0.8 筛选器重做让 MoM/YoY/趋势/TWR/本月收益率都吃**多期** `endBalanceBase`,但 `ensure` 仅覆盖 anchor 一期 → 上期/窗口期缺汇率落 `1.0` 未换算,末期(换了)减上期(没换)= 垃圾;② `FactMapper` 只认「一端=视图币种」的**直连**汇率行,视图币种为**第三币种**(USD 账户在 HKD 视图)缺三角换算 → 落 1.0。修:`ensure` 扩到 **≤anchor 全窗口** + 视图币种**全期补 base→view**(dashboard/reports/checkup 三处)+ `FactMapper` 改**经本位币三角换算** `fx(acct→view)=rate(base→view)/rate(base→acct)`(base 视图结果与旧实现完全一致 → 向后兼容,无 schema 改动)。语义锁定:**视图币种=显示镜头 → 比值类指标币种无关、金额类按 fx 精确缩放**。
- schema:全新增可空列/新表(`transfer.to_amount` / `account.expected_return_pct` / `family.metric_prefs` / `cash_flow.is_adjustment` + `cash_adjust` 类目),旧数据零回填、backward-compat。
- 测试:mvn **263 单元**(+MetricPrefsServiceTest +CurrencyInvarianceTest 属性级:比值类币种无关/金额按因子缩放)· qa-run 加 v08-1~6 + **v08-CCY-INV-2/3/4**(属性级:本月收益率 + 所有比值类 KPI 切币种完全相等 + 金额按 fx 缩放)守护;临时 MySQL 实例真机渲染验证 dashboard/as-of/管理页/账户编辑/reports 全 200;beta 三币种本月收益率实测一致(−27.68%)。

## [v0.7.5] · 2026-06-22

### Fixed / Changed

- **新用户「无痛苦 + 能 run」收口(以全新用户视角审视 README + 部署)**:
  - **`<your-org>` 占位符**(`README` 方式二 systemd + macOS 的 `git clone`)→ 改成真实 `LuoDi-Nate`。此前照抄会 `repository not found`,非 Docker 路径第一步即挂。
  - **全新 Docker 装好后清成空态、触发 onboarding,与 systemd 一致**:此前迁移含演示数据(V2/V3/V4),`deploy.sh` step10 在 systemd 首装清掉、但 Docker entrypoint 没有 → 全新 Docker 用户登录看到别人的演示假数据、v0.7.2 引导从不出现。现 `docker/entrypoint.sh` 在「**迁移前 `schema_history` 表不存在 = 全新空卷**」时,迁移后调新脚本 `docker/clean-dev-data.sh` 清演示数据(与 step10 同表集),让用户从空态按引导录入自己的数据。
    - **删数据三重防线**:① 铁信号「迁移前无 schema_history」才清(自限;migrate-to-docker 灌的 dump 自带 schema_history → 永不触发;升级库 db-data 已有该表 → 老用户哪怕轻量也 100% 安全)② 真实数据互锁(audit>50 或 member.id>2 跳过)③ `FINANCE_KEEP_DEMO=1` 保留开关。0 schema、不动 systemd step10、存量零影响。
  - 文档/提示:README 测试数订正一致(250 单元 / 36 端到端 / 367 黑盒);方式一加远程 VPS 的 loopback 访问提示(SSH 隧道 / 反代)。
  - 回归:`qa-run` 新增 `v07-CLEAN-1/2`;全 docker shell `bash -n`;**beta 隔离测试库真验**(不碰线上 `finance`):全新库清成空态(period/account=0、member/family 保留)、互锁与 `FINANCE_KEEP_DEMO` 跳过、schema_history 新旧库判定。纯脚本 + 文档,0 Java。

## [v0.7.4] · 2026-06-22

### Fixed / Changed

- **国内 Docker 部署网络阻断引导(真机验证暴露的真坑)**:prod 隔离全链路真机验证(host:20099,不碰线上)证明 `docker compose up` 链路本身没问题(mysql healthy → 跑迁移 → app healthy → /health+/login 200,整栈 ~730MB),但暴露中国大陆两处硬阻断——`get.docker.com` 被墙、**Docker Hub 拉 `mysql:8.0` 被墙导致 `docker compose pull` 卡死**(反观 GHCR 上我们自己的 app 镜像大陆能直连)。
  - `deploy/docker-up.sh`:`pull` 失败时**单独探 `mysql:8.0`** 归因——确认是 Docker Hub 被墙就打印可复制的国内镜像源修复(`daemon.json` 的 `registry-mirrors`),**Linux 原生 systemd 引擎**还会征得同意后自动写 `daemon.json`(daocloud + 1ms,免登录)+ 重启 + 重试;**`daemon.json` 已存在则只提示、绝不覆盖**。修掉旧逻辑「pull 失败一律 `up --build`」的伪兜底(build 只构建 app,db 仍要拉 mysql,救不了墙)。新增非交互开关 `FINANCE_ASSUME_YES` / 可覆盖 `FINANCE_DAEMON_JSON`。
  - 文档:`deploy/README.md`「国内镜像加速」纠正两处误导(GHCR 实测直连 OK 不是慢;`docker compose build` 救不了 mysql)+ 给免登录公共镜像;`README.md` 方式一 + `docs/faq.md` 加大陆 mysql 卡死专条。
  - 回归:`qa-run.sh` `v07-CN-1/2`(桩模拟探测/写入/不覆盖 + 文档守护);`docker-up.sh` 过 `bash -n`;实测依据 prod 配镜像源后 mysql 实拉成功。纯脚本 + 文档,0 Java / 0 schema / 0 编排,存量零影响。

## [v0.7.3] · 2026-06-22(hotfix)

### Fixed

- **修改密码死循环(issue #1 · 卡死所有新用户首登 · 关键)**:种子账号首登强制改密,但改密成功后用 `SecurityContextHolder.clearContext()` 想强制重登 —— **Spring Security 6 默认 `requireExplicitSave=true`,`clearContext()` 只清当前线程、不作废 HttpSession**,session 里仍是登录时的旧 `MemberPrincipal`(`must_change_pw=true`)。于是 `AuthController` 把"已登录"用户跳 `/dashboard`、`MustChangePasswordInterceptor` 又按旧快照弹回改密页 → **无限循环**,且手动改库标记无效(循环由 session 旧快照驱动)。改成用 `SecurityContextLogoutHandler` **真正作废 session** → 下次 `/login` 给登录表单 → 用新密码登 → 读到 `must_change_pw=0` → 跳出。
  - 感谢 [@duotui-com](https://github.com/duotui-com) 报告并准确定位根因。
  - 回归:`ProfilePasswordChangeTest`(断言改密后 session.invalidate() + 标记置0 + 跳登录)+ qa-run `v07-FIX-1`;mvn test 250 全绿。纯改动一处,0 schema。

## [v0.7.2] · 2026-06-16

易用性第三批:从「第一次刷到 repo 的开发者」视角补「说不清 / 不够易用」。详 [`prd/v0.7.md`](prd/v0.7.md) §9 + [`tech-design/v0.7.md`](tech-design/v0.7.md) §九。

### Fixed

- **修首登 500(关键)**:`/` 旧实现无脑 `redirect:/dashboard`,而 dashboard 零周期时 `anchorPeriod()` 抛异常 + `deploy.sh` 清数据会清空 `period`/`account` → **全新部署首次登录直接 500**(砸在开源新用户身上,beta/prod 有数据未暴露)。

### Added

- **系统内首次引导**(FR-133~135):`/` 改智能路由——未初始化(零周期 或 零账户)→ 渲染引导页 `onboarding/index`(一句话讲清「开周期→填余额→关周期→出报告」+ 3 步直达按钮 + 状态打勾 + 顺手改名链),否则 → `redirect:/dashboard`;`/dashboard` 加零周期兜底 `redirect:/`。`/entry` 顶部加「周期流程」一句话。
- **README / 文档易用性**:主要能力列表去 `(v0.x)` 版本号噪音(改干净能力总览);加「最低系统要求」(1G 内存,512M 会 OOM);抽出路径无关「部署好了:第一次怎么用」section(讲清周期生命周期 + 6 步,Docker/直装通用);新增 `docs/faq.md`(远程访问 / 备份恢复 / 忘记密码 / 多家庭 / 改 env 不生效 等)。

### 兼容 / 红线

- 纯增量 0 schema;`/dashboard` 只加一行零周期兜底;存量(有数据)家庭 `/` 行为不变(仍直达 dashboard)。
- 无 emoji 用 inline SVG;文案不用技术黑话。`OnboardingRoutingTest` 4 例 + qa-run v07-ONB-1/2 守护;mvn test 248 全绿。

## [v0.7.1] · 2026-06-16

开源用户「跑起来之后怎么接外部服务」的易用性改造 —— 配置引导。详 [`prd/v0.7.md`](prd/v0.7.md) §8 + [`tech-design/v0.7.md`](tech-design/v0.7.md) §八。

### Added

- **配置总指南 `docs/configuration.md`**(FR-129):开篇讲清「全部可选、核心零配置可跑、配了各自解锁什么」+ 一览表,链现有 `llm-api-keys-setup` / `aliyun-sms-setup` / `llm-vendor-comparison` 详版。README 三处加入口(文档 / 配置项 / 首次登录)。
- **管理页折叠式申请指引**(FR-130):`/admin/integrations` LLM 卡每个 key 加 `<details>`「如何获取?」(3-4 步 + 控制台直链 + 配置指南链,纯 CSS 无 JS);短信页补「阿里云短信接入」文档链。
- **LLM 一键测试连接**(FR-131):每个 vendor(Qwen / DeepSeek)一个「测试连接」按钮 → 用**已保存**的 key 发最小探测(复用 `LlmClient.chat`,与体检同链路)→ 顶部 flash 回显成功 / **脱敏**失败原因(Key 无效 / 欠费 / 额度用尽 / 网络)。补齐「外部接入必配一键测试」纪律(短信已有)。
- **「可选 + 解锁什么」说明**(FR-132):LLM / 短信卡顶部明示「不配会怎样、配了得到什么」。

### 私密 / 兼容

- 测试连接:key **不回显、不进 flash / audit / 日志明文**,错误经 `classifyLlmError` 归类(决策 82);测试事件审计只记 vendor + 成功/失败。`PrivacyIsolationTest` + `v04-PRIV-1` 仍绿。
- 纯增量:0 schema 改动、不动配置读取链(DB>env>默认)、不动既有 4 个 POST handler;对已配好的存量用户零影响。
- UI 无 emoji(承既有纪律);qa-run 加 v07-CFG-1~5 守护;mvn test 244 全绿。

## [v0.7.0] · 2026-06-12

一键 Docker 部署,兼容存量 systemd / macOS 用户(数据零丢迁移)。开源宣传后让部署门槛跟上。详 [`prd/v0.7.md`](prd/v0.7.md) + [`tech-design/v0.7.md`](tech-design/v0.7.md)。

### Added

- **一键 Docker**:`Dockerfile`(多阶段 maven 构建 → temurin-21-jre 运行,运行层带 mysql client 供迁移)+ `docker-compose.yml`(`app` + `mysql:8.0` + 备份 sidecar,命名卷 `db-data`/`uploads`/`backups`)+ `docker/entrypoint.sh`(等 db → 跑 `db/apply.sh` 版本化迁移 → `exec java`)。`docker compose up -d` 全新机一键起。
- **自检一键入口** `deploy/docker-up.sh`:解决「Mac 用户各种 docker 装法都得跑通」——逐项自检 docker 装没装 / 引擎起没起 / **Compose V2** 在不在(`docker compose` 优先,回退 V2 版 `docker-compose`,老 V1 直接拒并教装),卡住给可复制修复命令(不再吐 `unknown shorthand flag: 'd'` 这类底层报错);镜像拉不到自动本地构建;起完轮询 `/health` 并**直接打印首次登录账号密码**。适配 Docker Desktop / OrbStack / colima。
- **种子账号 prod 引导 `ProdSeedRunner`(`@Profile("prod")`)**:修 Docker 首登死锁——Docker 跑 `prod` profile,而设种子密码的 `DevSeedRunner` 只在 `dev` 跑、`deploy.sh` step 9b 又是 systemd 专属,导致**种子账号 `diwa`/`wangergou` 停在占位密码、根本登不进也无法重置**。新 runner 首启时把占位密码设为临时密码(`SEED_ADMIN_PASSWORD`,缺省 `demo1234`)+ `must_change_pw=1` + 日志打印登录横幅。**幂等**:只动 `password_hash LIKE 'PLACEHOLDER%'` 的成员,存量/线上库 no-op。
- **配置/密钥**:`.env.example` + `deploy/docker-init.sh`(openssl 生成随机 DB/root/REMEMBER_ME_KEY)。LLM key/aksk/阈值仍走管理页(不进 .env/镜像)。`.env` git-ignored。
- **存量迁移** `deploy/migrate-to-docker.sh`:**自动识别 systemd(`/etc/finance.env`)与 macOS(`~/.finance/finance.env`)**,mysqldump 备份 → 生成 .env(**携带原 REMEMBER_ME_KEY**)→ 停旧 app → 灌 dump(含 `schema_history`)→ 搬 uploads → 起容器 → 验 /health。**数据零丢、版本不重放、可回滚**。
- **备份 sidecar** `docker/backup.sh`:每日 mysqldump 到 `backups` 卷 + 保留天数,与 systemd timer 平价。
- **GHCR 多架构发布** `.github/workflows/docker-publish.yml`:打 tag 构建 **amd64 + arm64**(覆盖 NAS / Apple Silicon Mac)推 GHCR。
- **文档**:README「快速开始」加 Docker 入口;`deploy/README.md` 加「Docker 部署 / 从 systemd·macOS 迁移 / 反代+HTTPS 片段(nginx+certbot、Caddy)/ 国内加速」。

### 兼容 / 红线

- **存量 systemd `deploy.sh` 路径原样保留不弃**(我们 prod + beta 都是存量,零破坏);Docker 作新增推荐路径。
- **共用 `db/apply.sh` + `schema_history` + `V*.sql`** —— 迁移后不重放、未来迁移两路各只跑一次;同 env 契约让 app 对部署方式无感。
- 迁移**前强制备份**、全程不删旧部署、可回滚;密钥不烤进镜像/不进日志;`SERVER_ADDRESS=0.0.0.0`(容器内)+ 默认只发布 loopback 端口。

### 注

- **真机冒烟留待 Mac + Ubuntu 分别验**(beta 是 Linux 且未装 Docker)。本版为「设计正确 + 全脚本静态校验(bash -n)+ 结构守护」;`docker build` / `compose up` / 迁移演练 = 验收基线 #1/#3,待用户真机跑。

## [v0.6.2] · 2026-06-09

LLM 成本治理:Qwen 调用改「每次随机选模型」摊开流量 + 模型池精选。修根因「百炼免费额度**按模型各给一份**,而旧逻辑固定从 qwen-plus 起、只在 429 才切 → 全砸 qwen-plus,用超后账户**静默转计费**(不报 429)→ 出账单」。纯代码,0 schema。

### Changed

- **`QwenLlmClient` 随机选模型**:每次调用 `Collections.shuffle` 模型池、随机起一个(原固定 qwen-plus 起)→ 把流量均匀摊到各模型的**独立免费额度池**;失败/额度用尽仍沿随机序往后试 + 保留 per-model 6h 冷却。
- **默认模型池精选** → `qwen-plus,qwen-flash,qwen-max`(稳定别名 · 三档能力梯度 · 各一个独立免费额度池)· 丢掉 qwen-turbo / qwen2.5-7b 等偏弱(结构化 JSON 诊断易翻车)· 运营可在 `/admin/integrations` 调(`K_LLM_QWEN_MODELS`)。

### 运维(关键)

- **配合百炼控制台开「免费额度用完即停」**(默认关):开后某模型免费额度用尽返回 403 `AllocationQuota.FreeTierOnly` —— 本类 `classify()` 已识别为额度用尽→自动切下个模型,且**永不计费**。这是「不再出账单」的根本开关。
- 清理 beta 演示环境短信 aksk(公开 Demo 防误触发真发短信)。

## [v0.6.1] · 2026-06-08

iOS PWA 引导从「软建议」改「强引导」(FR-115)。原来是「你也可以这么用」,现在是
「iOS 上请务必装成 App」—— 整屏拦截 + 成果真机截图(看到网站能变 App)+ 想留在浏览器/
微信要被**两段挽留**才放行。纯前端,0 schema。

### Changed

- **`mobile-guide.js` 重写**:① iOS Safari → 进站 ~0.7s **整屏强引导**(标题「请把账房装成 App」+ 主屏成果截图 + 3 条价值点 +「看怎么装·4 步真机图」)② iOS 微信 → **整屏强引导转 Safari**(微信内核装不了 PWA · 大箭头指右上「⋯」→「在 Safari 中打开」+ 成果截图)
- **两段挽留(强阻挠)**:点「暂时用浏览器 / 继续在微信用」或 ✕ → 阻挠①(没图标/手输网址/不能全屏)→「仍要继续」→ 阻挠②(装一次 20 秒)→ 才放行 + 3 天内不再弹(原 Safari 7 天一键划走)
- **口吻**全面升「请 / 务必 / 强烈建议」;文案从可选改强推
- **emoji 全清** 📦📷✕✓ → 项目 inline SVG(承 `feedback_no_emoji`)
- 已装成 PWA(standalone)/ 非 iOS → 完全静默不打扰;`?reset_pwa=1 / ?reset_wx=1` dev 强触发保留;安卓微信本版静默(只强推 iOS · 安卓另议)

### Added

- **成果真机图** `img/safari-screen/home-screen.jpg`(主屏装好的账房图标 · 让用户直接看到「网站能像 App」)· 640px/q82 · 74KB

### Perf

- iOS PWA 引导 4 步截图压缩 1320→900px/q82 · 834KB→473KB(-44%)
- presets 品牌图标 32 位真彩→256 色板 PNG · 1370KB→83KB(-94%)· 已眼检无 banding

## [v0.6.0] · 2026-06-05

中国大陆中产视角的「AI 资产洞察」:把小红书/论坛/支付宝等主流讨论里反复出现的资产焦虑,
沉淀成 4 条可审计主线 —— ① 集中度(钱是否太挤在一处)② 资产负债表健康 ③ 再平衡 + 行为
④ 低利率·资产荒(真实购买力)。硬数据全部工程预算,LLM 只做中立解读(不预测涨跌 / 不择时 /
不荐产品)。主阵地资产体检页,dashboard 速览条 + reports 交叉入口。

### Added

- **calc/ 4 纯函数**(无 Spring · 可单测)· `ConcentrationCalculator`(房产/单一账户/单一币种占比 + 风险线)/ `BalanceSheetHealth`(金融盘 vs 不动产 · 负债率分级 · 提前还贷信号)/ `RebalanceDrift`(4 桶偏离方向 OVER/UNDER/OK)/ `BehaviorHeuristics`(顺周期加仓 / 集中度持续走高 · 历史<6 期静默)
- **`AssetInsightService`** · 从 FactView / 账户负债字段 / 配置 diff / 财富水位取数 → 调 4 纯函数 → 组装可审计「硬数据」`AssetInsight`(只读 · 永不抛 · 数据缺失局部降级)
- **AI 资产洞察 LLM 层** · `InsightPromptBuilder`(硬数据铺成 prompt · **不含任何人名/账户名**)+ `LlmDiagnoseService.diagnoseAssetInsight`(复用 client 轮转 + 1h cache + audit · scope=ASSET_INSIGHT)· 结构化 JSON(总评 + 4 维卡 + 纪律性提醒)
- **资产体检页「AI 资产洞察」section**(`checkup/_ai-insight.html`)· 上半第一层硬数据 4 卡 + 下半第二层 AI 解读 · 全 inline SVG(无 emoji)· TOC 新增「AI 资产洞察」项
- **dashboard 资产洞察速览条**(`dashboard/_insight-strip.html`)· 房产占比 / 负债 band / 加速偿还 / 真实收益承压 / 行为提醒 chip + 「查看完整洞察」· 仅硬数据不调 LLM(保持仪表盘轻快)
- **reports 配置对照交叉入口** → `/checkup#checkup-insight`
- **Qwen 多模型免费额度兜底**(FR-108)· `K_LLM_QWEN_MODELS` 有序模型列表(≤10 · 运营可配)· 某模型免费额度用尽(429 `Throttling.AllocationQuota`/`insufficient_quota` · 403 `AllocationQuota.FreeTierOnly`)自动切下一模型并冷却 6h · 账户欠费/账单过期(400 `Arrearage` · `*BillOverdue`)立刻 failover DeepSeek
- **`OutputValidator.checkInsight`** · 在合规底线之上额外拒绝预测涨跌 / 择时 / 买卖时点话术(仅 ASSET_INSIGHT scope · 既有综合诊断 `check` 行为不变)
- **账户负债明细字段** · `account.loan_kind` / `annual_rate_pct`(V29 · 喂提前还贷信号)· 编辑页 LOAN 类型可选填

### Schema

- **V29** · `ALTER TABLE account ADD COLUMN loan_kind VARCHAR(20) NULL, ADD COLUMN annual_rate_pct DECIMAL(6,3) NULL` · 纯 ADD COLUMN DEFAULT NULL · prod 0 风险 · 老账户该维度优雅降级

### Tests

- +`AssetInsightCalcTest`(6)+ `QwenInsightComplianceTest`(6)· 单元 **244** 全绿 · qa-run +v06-INSIGHT-1~6 / v06-LLM-LIVE / v06-COMPLIANCE / v06-PRIV / v06-MODELS / v06-MIGRATION

### 红线

- LLM 严禁算术(数字全工程预算 · prompt 明示只引用)· 不预测涨跌 · 不择时 · 不荐产品 · 中立诊断
- 隐私 by construction:洞察 prompt 不含成员/账户名 · `checkInsight` 防御深度仍扫
- 全部只读取数 · 不写表 · 既有 `/checkup/diagnose` 与 `check()` 行为不变

## [v0.5.7] · 2026-06-04

长文目录推广到所有长 tab 页 + 沉淀为可复用件(无迁移 · 纯前端 · 0 schema)。

### Added

- **长文目录共用件** · `fragments/_toc.html`(`rail(items)` + `mobile(items)` 两片段,items 经 `th:with` 内联 map 传入)+ `static/js/toc.js`(页面无关 scrollspy + 底部 sheet · 自动接 `[data-toc-nav]`)· `style.css` 布局类泛化 `.reports-cols`→`.toc-cols`
- **dashboard 接入目录**(概览 / 净资产趋势 / 按成员分布 / 按账户分布 / 账户列表)· `_region` 加 `#dash-trend/#dash-member/#dash-dist/#dash-list` 锚点
- **checkup 接入目录**(概览 / 资产配置 / 风险敞口 / 流动性 / 收益质量 / 智能建议 / AI 综合诊断 / 单账户体检)· 复用既有 `#allocation/#risk/#liquidity/#return` + 加 `#checkup-overview/#checkup-advice/#checkup-ai/#checkup-accounts`(advice 互斥两态同 id 保锚点恒在)

### Changed

- **reports 目录改用共用件** · 移除原内联脚本/rail/sheet markup · 行为不变(左侧常驻树状 + scrollspy + 手机 sheet)
- **不做目录的页**(明确):填报 / 账户 / 目标列表 / 管理 —— 表单/列表/管理页,非长文阅读

### Tests

- qa-run +v05-TOC-2/3(dashboard/checkup 含目录 + 锚点)· 单元 **232** 不变(纯前端)· 0 schema · 迭代须同步目录(memory `feedback_toc_sync`)

## [v0.5.6] · 2026-06-03

报表两件事(无迁移):① 收益口径回归「已关账快照」(原拟 v0.5.5,不单独发,并入此版)② 长文目录(PC 右栏树状大纲 + scrollspy · 手机左上唤醒钮 + 底部 sheet)。

### Fixed

- **报表四指标锚在进行中账期 → 人赚常年 0 / XIRR·TWR 用半填净值失真**(自 v0.5.1 `findCurrentOpen` 优先引入)· 报表本应是"已关账快照",却跟着月中还空着的 OPEN 期走 · 年轻家庭里唯一填了数的已关账月恰好当了"被排除的基准",真正计入的只剩空 OPEN 期 → #3=0(FR-94)

### Changed

- **报表锚定改「最近已关账(≤今天)账期」· dashboard 不动(实时)** · 四指标终点永远是填满的已关账月;`period_start ≤ 今天` 顺带干净挡掉测试/误建的未来账期(2032),不必再靠 OPEN 兜底 · 新增只读 `PeriodMapper.findLatestClosedAsOf` + 纯函数 `ReportsAnchorResolver`(FR-94)
- **#3 人赚 ⓘ 文案** · 点明「区间逐期累计 · 非单月 · 只统计已关账」(去掉过时的"当前账期没填→0"措辞)(FR-96)
- **报表目录升级**(FR-98)· 原 v0.5.1 右下角低调小 FAB → 业界长文交互:**PC 右侧常驻树状大纲**(竖线/树枝 · 支持多层嵌套)+ `IntersectionObserver`/scroll scrollspy 高亮当前节(`aria-current` a11y 钩子);**手机左上角唤醒钮 → 底部 sheet**(拖拽手柄 + × + Esc · 触控 ≥44px · 点击跳转后收起)· HTMX 换 range/币种后重新 spy

### Added

- **报表页头「已关账」朱印章 + 说明行透出**(FR-97)· 标题旁朱印红横排小印(纯 CSS · `.report-seal` · 无 emoji)+「本页为已关账账期的稳定快照 · 数据截至 X · 进行中的本月请看 仪表盘→」· 让用户一眼分清报表(快照)与仪表盘(实时)
- **已关账账期 <2 的诚实空态**(FR-95)· 四 banner 显「—」+「需 ≥2 个已关账账期」,不再显误导性 0;0 个已关账期 → 引导空态(不盖空印章)
- **章节锚点**(FR-98)· `_region` 加 `#sec-decompose` / `#sec-risk` / `#sec-accounts`(配合 `#reports-region` / `#sec-wealth` / `#sec-savings` / `#allocation-diff`)· `scroll-margin-top` 处理 sticky-nav 偏移

### Tests

- 单元 **232**(+4 `ReportsAnchorResolverTest`:快照/退 OPEN/退 latest/无账期抛错)· qa-run +v05-SNAP-1/2(快照透出 · dashboard 实时)+v05-TOC-1(右栏树 + 锚点 + 手机 sheet)· **0 schema 改动** · dashboard 行为不变 · 目录纯前端

## [v0.5.4] · 2026-06-03

目标 AI 月报三处修复(无迁移)。

### Fixed

- **AI 月报脱敏未回写 → 出现「成员A与成员B」** · `GoalLlmService.generateMonthlyReport` / `generateAlertAdvice` 校验通过后**漏做反向映射**(v0.3 起 latent)· 月报里直接显代号 · 修:校验仍跑在代号 raw 上(不因真名误判泄露),通过后 `PromptBuilder.reverseMapping(raw, codenameToReal)` 把 成员A/成员B 还原回真名供阅读(与 checkup AI 诊断同口径)(v0.5.4)

### Changed

- **目标月报缓存语义对齐 checkup** · 月报本就持久化在 `goal_ai_report`(UNIQUE upsert)且详情页直接复用(加载不重算 = 缓存命中)· 本次补齐 UI:有月报时显「本期复用 · 渲染于…」+「重新生成」按钮(同 checkup ↻ force-refresh 语义),不再只有"尚未生成"态才有触发入口(v0.5.4)
- **目标月报内容保留换行** · `whitespace-pre-line` 渲染多段叙事(v0.5.4)

### Added

- **仪表盘目标条带「AI 阅读总结」小入口** · `goals/_progress-strip` 每个目标卡右侧加 inline-SVG 入口(book-open + AI)· 直达 `/goals/{id}#ai-report` 本期月报锚点 · detail 页 AI 月报段加 `id=ai-report` + `scroll-margin`(v0.5.4)
- **AI 月报段 emoji → inline SVG** · ✨ 换 Feather book-open(承 [[feedback_no_emoji]])(v0.5.4)

### Tests

- 单元 **228**(+2 `GoalLlmServiceTest`:代号→真名回写守护 + 无成员原样返回)· 锁死「月报展示前必反向映射」回归

## [v0.5.3] · 2026-06-03

计算指标透明化 —— ⓘ tooltip 从「只讲口径公式」升级为「口径 + 真实计算数值」(无迁移)。

### Changed

- **ⓘ tooltip 显示真实计算数值** · 全站 28 个计算型 KPI 的 ⓘ 面板,在口径文字下方加一条**真实实算行**(虚线分隔 + 等宽字体)· 例:紧急储备「流动资产 ¥X ÷ 月均支出 ¥Y = Z 月」、月结收入「近 12 月有填 N 个月 · 收入合计 ¥S ÷ N = ¥avg」、本月资产收益「(期末 ¥E − 期初 ¥B − 净流入 ¥I) ÷ 期初 ¥B = +x%」、钱赚 PnL「(期末 ¥E − 起始 ¥B) − 净流入 ¥I = ¥」· 数值与页面 KPI 同源同币种(dashboard/reports KPI 区 viewCurrency · checkup 与储蓄区本位币)(v0.5.3)
- **XIRR / TWR 诚实口径** · 迭代/几何解无法写成单条算式 → 只展示**真实输入端点 + 解得值**(如「期初净资产 −¥B → 期末 +¥E · N 期求解年化 = x%」),不伪造算术步骤(v0.5.3)

### Added

- **`_kpi-info` 片段升级 `i(text)` → `i(text, calc)`** · 第二参可 null(纯定义指标如账户级 XIRR 0% 成因仍只显口径)· `.kpi-info-calc` CSS(虚线分隔 · 等宽 · pre-line 多行分项)(v0.5.3)
- **`MetricExplainService`** · 纯展示层 · 把 FactView / HouseholdCashflow 已算好的中间量格式化成串 · 不做任何业务计算(数值必与页面 KPI 一致)· `KpiSnapshot` 加 4 个透明化中间量(liquidAssets / avgExpense / prevNetWorth / lastNetInflow · 原本算完即弃)(v0.5.3)

### Tests

- 单元 **226**(+8 `MetricExplainServiceTest`:格式化口径 / 三页 calc 自洽 / 钱赚分解恒等式 / 缺数据降级)· qa-run 加 v05-CALC-1~3(dashboard/reports/checkup ⓘ 含 `.kpi-info-calc` 真实数值守护 · 用恒有值的净资产/钱赚分解断言)

## [v0.5.2] · 2026-06-03

目标模块两个 bug 修复(无迁移)。

### Fixed

- **目标编辑页丢失支出口径** · `/goals/{id}/edit` 缺「固定值 / 自动适配月结支出」单选(create 表单有、edit 没有)→ 编辑退休目标时该选项消失。edit.html 补 `expenseMode` + 窗口 + 平滑,从已保存 params 回填(v0.5.2)
- **AI 综合月报永远"尚未生成"** · 月报原只在周期关闭时自动生成,新建目标 / 当前 OPEN 期无法触发。加 `GoalReportService.generateNow`(period_id=0 按需 · upsert 幂等)+ `POST /goals/{id}/report/generate` + 详情页「立即生成月报」按钮(v0.5.2)

### Tests

- qa-cases 加 bf-GOAL-EDIT-1~4 + bf-GOAL-RPT-1~4 回归 case

## [v0.5.1] · 2026-06-02

v0.5 上线后修复 + 体验完善。

### Fixed

- **比值类指标切币种被错算** · 紧急储备月数 / 本月资产收益% 等比值在切到非本位币时漂移 · 根因 `endBalanceBase` 实为 viewCurrency 口径而 PMC(月支出/净流入)按本位币原样存未换算 · 修:`FactViewServiceImpl.baseToViewFactor`(从 slice 本位币账户 fxToBase 自取)· PMC 路径 ×因子 · 比值币种无关(v0.5.1)
- **周期管理无分页** · beta 测试数据账期到 2032、当前期翻不到 · `/admin/periods` 加分页(24/页 倒序)(v0.5.1)
- **reports 净流入显示 0** · `anchorPeriod` 改优先 `findCurrentOpen`(原 `findLatest` 锚到未来测试期)(v0.5.1)

### Changed

- **CPI/M2 水位线文案** · 保命线→**购买力线** · 地位线→**社会财富线**(v0.5.1)
- **tooltip 补充** · 家庭 XIRR「vs 基准」来源说明(类目长期年化基准余额加权)· 人赚净流入口径 · 账户级 XIRR 0% 成因(v0.5.1)
- **reports 移动端浮动目录** · 右下角 FAB 不挡内容 · tap 跳转 section(v0.5.1)

### Tests

- 单元 **218**(+ BenchmarkAverage / WaterLevel / NetInflow 恒等式 / StockHoldingCashLink / FireAutoExpense / CurrencyInvariantRatio)· qa-run 319 PASS + v05-CCY-INV 黑盒守护

## [v0.5] · 2026-06-01

财富水位(资产 vs CPI/M2 双基准)+ 股票现金联动 + FIRE 支出自适应 + 净流入双源修复。详 [`prd/v0.5.md`](prd/v0.5.md) / [`tech-design/v0.5.md`](tech-design/v0.5.md)。

### Added

- **财富水位 · 并入 `/reports`**(FR-72/73/74)— 净资产 vs **CPI 保命线**(购买力)+ **M2 地位线**(社会财富份额)三线图 · 真实收益/相对社会收益 KPI · 人赚/钱赚分解诊断("水位靠收入硬撑")· 推导透明面板(三法均值)· 不新起 tab(v0.5)
- **宏观基准底座**(FR-70/71)— `macro_benchmark` 表 + 1990-2025 CPI/M2 历史 seed · 几何均值 / **剔极端值几何**(默认,防 1994=24.1% 失真)/ 近 10 年 三法推导 · `/admin/integrations` 宏观段可校正(v0.5)
- **收支趋势图**(FR-85)— `/reports` 储蓄区加收入线/支出线/储蓄填充 · 人赚引擎走势 · PMC 源(v0.5)
- **dashboard CPI 线升级 + M2 线**(FR-75)— 硬编码 2% → 真实剔极端 CPI · 加 M2 地位线参考(v0.5)
- **股票账户现金联动**(FR-78/79)— 录 AUTO 持仓可选「从账户现金划转买入」· 买入扣现金(FX 换算 · 成本必填 · 可负)· 归档按市价对称加回 · `cash_linked` 标记保向后兼容(v0.5)
- **FIRE 目标支出自适应**(FR-81/82/83)— 退休目标月支出可选「自动适配月结支出」· 周期关闭按近 N 月真实支出滚动重算(剔极端/中位/均值 · 空期排除 · 不足回退)· 派生值回写使下游零改动(v0.5)

### Fixed

- **净流入(人赚的)双源 bug**(FR-84)— `principalVsReturnDecomposition` + `netInflowForPeriod` 改 **PMC 优先 · cash_flow 回退**(承 v0.4.3 B2)· 修 prod「人赚·净流入显示 0」· 钱赚改 ΔNW − 人赚 · 守 **人赚 + 钱赚 = ΔNetWorth** 恒等式(v0.5)

### Migration

- **V27** `macro_benchmark`(ADD TABLE + 1990-2025 seed)· **V28** `stock_holding.cash_linked`(ADD COLUMN DEFAULT 0)· 全向后兼容 0 风险(v0.5)

### Tests

- 单元 170 → **215**(+45):BenchmarkAverage / WaterLevelCalculator / NetInflowDecomposition(恒等式)/ StockHoldingCashLink / FireAutoExpense · qa-run **319 PASS**(同 v0.4 基线)

## [v0.4.23] · 2026-05-20

**admin hub 补全 + 集成改名** · v0.4 给管理后台加了 4 项新页(集成 / 提醒 v0.4.14 升级 / 数值阈值 v0.4.18 升级 / 各种 KPI),但 `/admin` 总览页(hub)tile 列表和文案没同步,这次一次性对齐。

### Changed

- **「集成」→「数据源接入」** · sidebar item + `/admin/integrations` 页标题 / eyebrow / h1 全部改名 · 让非技术家庭成员能看懂(原工程师术语「集成」)· URL `/admin/integrations` 路径不动(v0.4.23 · 见 [[feedback_user_friendly_naming]])
- **`/admin` hub 补「数据源接入」tile** · 之前 hub 12 个 tile 漏了这条,sidebar 14 项 hub 才 12 项,从 hub 进不去 v0.4.18 加的页(v0.4.23)
- **`/admin` hub 三处文案过时刷新**:
  - **提醒 tile**:「站内提醒条 · 每日触发时间」→「填报模板 · 阿里云短信 · 截止前强提醒 · 成员手机号」(v0.4.14 加的能力补齐)
  - **数值阈值 tile**:「智能提示 / LOAN 异常 / 自动归类」→「智能转账阈值 · 体检 4 阈值(集中度 / 高风险 / 流动性 / 应急金)· 会话有效期」(v0.4.18 加的 5 项补齐)
  - **备份 tile**:「最近 8 次备份状态」→「每周日自动备份 · 最近 8 次状态 · 一键恢复」(v0.4.20 fix 后周备份确实跑 · 文案补节奏)
- **`/admin` hub KPI · 汇率服务**:「exchangerate.host」→「frankfurter.dev」(实际用的就是这个 · 老文案错的)
- **hub 副标题列表补全**:原「家庭、成员、周期、提醒、汇率、备份」→「家庭、成员、周期、提醒、汇率、数据源接入、数值阈值、备份、审计」(v0.4.23)

## [v0.4.22] · 2026-05-20

**UX 改进** · /entry 顶部一键拉取股价按钮 · 让 v0.4.21 修好的 cron 链路也能被用户即时触发,不必再翻进 `/accounts/{id}/holdings` 才能 ↻ 刷新。

### Added

- **/entry 顶部「刷新股票估值」按钮**(Feather refresh-cw + loader 双 SVG · 旋转 indicator)· tab bar 右侧 · 仅家庭有 STOCK 账户时显示 · HTMX POST 不刷整页 · 顶部出现 toast「3 市场估值已刷新 · N 账户」(成功/check)、「仅 N/3 市场...」(警告/warn)、「3 市场均失败」(fail)、「操作太频繁」(clock)四态(6 秒自动渐隐)· 等同三个市场全 fetch + valuation refresh(MANUAL trigger · 写 stock_valuation_event 含触发用户)· 一律 inline SVG 不用 emoji(v0.4.22)
- **`EntryRefreshRateLimiter`** · per family 60s 滑动窗口 ≤3 次 · in-memory · 防点点点滥用 · 超频 toast「⟳ 操作太频繁 · 请 N 秒后再试」(v0.4.22)
- **`POST /entry/refresh-stocks`** · 新端点 · 三市场单独 try/catch · 单市场失败不阻断其他两个 · toast 区分「全部成功 / 部分成功(N/3) / 全部失败」三态(v0.4.22)
- **`EntryRefreshRateLimiterTest`** · 7 个单测 · 锁 3/60s 配额 + 跨 family 隔离 + 常量值不被无意改大(v0.4.22)

### Changed

- **/entry 账户折叠流水排序改为时间倒序(新→旧)** · 之前是升序(老的在最上)· 用户展开折叠看到的第一眼现在是最新流水 · null occurredAt 行留末尾 · 改动只在 `EntryService.java:438` 一处 sort comparator(v0.4.22)
- **UI 一律 inline SVG · 弃用 emoji**(`feedback_no_emoji` 纪律建立)· 新加按钮 / toast 都走项目自带 Feather-style 体系(stroke=currentColor · 24×24 viewBox)· 历史代码里的 emoji 改到相关页面顺手换 · 不专门 sweep(v0.4.22)

## [v0.4.21] · 2026-05-20

**hotfix** · 修自 v0.3 上线起的 latent bug · 自动拉价不触发账户估值刷新。

### Fixed

- **`StockPriceScheduler` cron 入口拉完价后未调 `AccountValuationService.refreshAllForFamily`** · 表象:每日 cron(US 06:05 / CN 16:10 / HK 16:30)都成功 fetch + 写 `stock_price_snapshot`,但 `stock_valuation_event` 表全是 MANUAL(用户在持仓页手点 ↻ 才有)· 账户余额永远停留在用户最后一次手动 ↻ 时的值 · 自 v0.3 上线就遗漏 wire · v0.4.18 重构调度时也只换了触发机制没补这个 wire。修复:`fetchUsStocks/CnStocks/HkStocks` 三个 cron 入口拉完价后接 `refreshAllForFamily(CRON, null)` · admin 手动入口的 `fetchMarket()` 不动(controller 自己接 MANUAL/HOLDING_CHANGE · 避免双跑)(v0.4.21)

### Added

- **`StockPriceSchedulerTest` · 13 个 wire 防回归单测** · 锁死「cron 入口必接 valuation 刷新」+「fetchMarket 直接调不带 refresh」+「TriggerKind=CRON 不污染 MANUAL/HOLDING_CHANGE 审计」三条铁律 · 后续谁删了 `refreshValuationsAfterCron` 编译期单测立挂(v0.4.21)

## [v0.4.20] · 2026-05-19

**hotfix** · 补 v0.4.19 漏修的 timer 行内注释 bug。

### Fixed

- **`finance-backup.timer` 行内注释致 `Persistent=true` 被静默忽略** · L15 `Persistent=true             # 错过...` —— systemd 不支持值后的行内注释,整行被忽略 = 关机错过周日不会自动补跑 · 改为注释独占一行 · `systemd-analyze verify` 通过(v0.4.20)

## [v0.4.19] · 2026-05-19

**hotfix** · 修周备份从 v0.1 起从未跑成功的 deploy 配置漏装问题。

### Fixed

- **`deploy.sh` 漏装 `backup.sh`** · `finance-backup.service` 的 `ExecStart=/opt/finance/deploy/backup.sh` 路径不存在 · 每周日 03:00 触发即 systemd `status=203/EXEC` 失败 · 周备份(`dump-YYYY-MM-DD.sql.gz` + `uploads-*.tar.gz`)从未成功跑过 · `pre-deploy-*` 应急备份不受影响(v0.4.19)
- **`deploy.sh` 自动装 backup unit + 启用 timer** · 之前 `finance-backup.{service,timer}` 需手动 cp 到 `/etc/systemd/system/` + 手动 `systemctl enable --now` · 现在 deploy.sh step 13 自动装两个 unit + step 14 自动 `enable --now finance-backup.timer`(幂等)(v0.4.19)
- **`/opt/finance/deploy/` 目录** · step 6 加入 mkdir 列表(v0.4.19)

## [v0.4.18] · 2026-05-19

系统级配置沉淀到管理页 · 9 项运营参数从 env/代码常量迁到 family_runtime_config 表 · 实时生效不重启。详 [`prd/v0.4.md`](prd/v0.4.md) §22。

### Added

- **`/admin/integrations` 新页** · 集成中心 · 3 段:LLM(Qwen/DeepSeek key + max_tokens + timeout)/ 股票拉取(开关 + 3 市场 cron)/ FX 拉取 cron(v0.4.18)
- **`/admin/calc-tweaks` 升级为可编辑** · 原 3 项只读改可编辑 + 加 4 项体检阈值(集中度/高风险/LIQUID buffer/应急金月数)+ 1 项会话期(v0.4.18)
- **`FamilyConfigService` 三层 fallback** · DB > env(@Value)> 代码常量 · 5s TTL cache · 17 个 K_* 常量(v0.4.18)
- **`DynamicScheduleConfig` 动态 cron** · SchedulingConfigurer + TaskScheduler + CronTrigger · 5 受管 cron(stock 3 + fx + report-remind) · 配置改即 cancel+重排不重启(v0.4.18)
- **deploy.sh step 9.5 配置种子迁移** · 从 /etc/finance.env 一次性 UPSERT 进 family_runtime_config · 幂等 flag `/var/finance/.config-migrated-v0.4.18`(v0.4.18)
- **V26 migration** · 新建通用 K-V 表 `family_runtime_config` · ADD TABLE 0 破坏(v0.4.18)

### Changed

- **LLM client API key 改读 ConfigService** · QwenLlmClient / DeepSeekLlmClient 不再 @Value 直注入 · max_tokens 也动态(默认 2000)· timeout 仍构造期注入(v0.4.18)
- **股票拉取开关改读 DB** · StockPriceScheduler.isEnabled() 每次 schedule 触发都查 ConfigService(v0.4.18)
- **checkup 阈值 + LIQUID buffer 改读 DB** · FAM-RISK-1 / FAM-ALC-1 / LiquiditySurplus 接受 multiplier 参数 · DashboardController/CheckupController 注入 ConfigService(v0.4.18)
- **`/admin` sidebar 加"集成"入口** · 标 13 项 → 14 项(v0.4.18)

### Security

- **PrivacyIsolationTest 扩防 LLM key 泄露** · 新增 `promptBuilderNeverReferencesAnyPrivateAccessor` · 静态扫 PromptBuilder.java 不得引用 K_LLM_*_KEY / FamilyConfigService(只 LlmClient 可以读)· 与 SMS aksk 同纪律(v0.4.18)

## [v0.4.17] · 2026-05-20

520 一日限定爱情宣言彩蛋(详 prd/v0.4.md §21)。仅 5.20 当天 Asia/Shanghai 服务器时间触发,5.21 完全 dormant。

### Added

- **全屏像素彩蛋** · 任意已登录页拦截 · 跳动像素心 + 飘心粒子 + 烟花星空 + brass-deep + valentine 红 + ink 调色(v0.4.17)
- **文案库 19 条随机** · 单行祝福(纯爱意 · 与"账本/记账"无关)· 每次重开/换一句不与上一条重复(v0.4.17)
- **「换 一 句 ↻」** · 副标下方像素按钮 · 不关 overlay 换文案(v0.4.17)
- **右上 ♥520 常驻 pill** · 5.20 当天任意页可点重唤 · 每次重开换新文案(v0.4.17)
- **「叮」音效** · Web Audio API 合成(1318+2637Hz 双频 · 600ms 衰减)· 不增 jar 体积(v0.4.17)
- **localStorage flag** · `easter520_seen=YYYY-MM-DD` 当天首次自动弹 + 关闭后不再自动弹 · pill 仍常驻可手动唤回(v0.4.17)

## [v0.4.14] · 2026-05-18

填报规范化 + 截止前强提醒(FR-63)。详见 [`prd/v0.4.md`](prd/v0.4.md) §20。

### Added

- **填报模板** · 家庭级 3 选 1(实时收入·月末支出 / 月末一次清 / 每周滚动)· 默认 T1 · 统一全家填报节奏(v0.4.14)
- **填报页推荐方案提示** · `/entry` banner 显示当前模板建议 + 距本期截止天数 · ≤2 天红色强提醒(v0.4.14)
- **截止前强提醒调度** · `@Scheduled` 每天 10:00/20:00 · 截止前 N 天(默认 2)对未完成填报成员触达(v0.4.14)
- **渠道抽象** · `NotificationChannel` 可插拔 · 短信为主(阿里云 dysmsapi)+ 站内 banner 强化兜底 · 当天去重(v0.4.14)
- **提醒设置页** · `/admin/reminders` 升级为可配:模板 / 提前天数 / 短信 aksk·签名·模板 / 各成员手机号 / 手动触发(v0.4.14)
- **短信文案 4 变量** · `${brand}/${period}/${days}/${progress}` · 模板 67 字内塞尽量多信息(v0.4.14)
- **填报页三栏富信息 banner** · 左节奏 + 中周期/截止日/家庭进度 N/M + 我已填徽标 + 右距截止 pill 3 状态(v0.4.14)
- **⊕ 一键测试短信** · 配完 aksk 立刻验证链路 · 测试专用文案 · 限流 3次/分 · 走 audit_log 不污染调度日志(v0.4.14)
- **阿里云接入指引** · [`docs/aliyun-sms-setup.md`](docs/aliyun-sms-setup.md) · 9 步从注册到一键测试 · 含错误码对照表(v0.4.14)
- **提醒发送日志** · `/admin/reminders` ⑥ 段 · 真实调度的每次触达 · 分页 20/页 · 列含发送时间/截止日/成员/渠道/状态/详情(v0.4.14)
- **测试短信用真实账期 + days=-1 标识** · 不再用 2099-12-31 假日期 · 运营商日志一眼可辨"非真实窗口测试"(v0.4.14)

### Security

- **私密红线** · `member.phone` / 短信 aksk 绝不进 LLM prompt / audit_log 明文 / 前端明文回显 · `PrivacyIsolationTest` + `qa-run v04-PRIV-1` 双重防回归(v0.4.14)

## [v0.4.11] · 2026-05-14

v0.4 主线 + .1-.11 一系列迭代封板。

详见 [`prd/v0.4.md`](prd/v0.4.md)。

### Added

- **报表大整顿** · 砍 8 张流水视角图(瀑布 / 桑基 / 月度对比 / 风险敞口表 / 汇率表 ...) · dashboard / reports / checkup 三页职责重切(v0.4)
- **CPI 对照线** · 净资产趋势加虚线对比 · 默认 2% 可切换(v0.4)
- **账户级基准对照** · `product_category.benchmark_pct` · 跑赢 / 跑输 pill(v0.4)
- **AI 调仓建议** · 4 桶配置 diff(现金 / 投资 / 房产 / 保险)· LLM JSON 输出 · 30 天复用 + ↻ 刷新(v0.4 + v0.4.6/.8)
- **提前还贷决策器** · `/reports/refinance` · NPV 18 年视角(v0.4)
- **应急金不闲置提示** · LIQUID 超额 6 月时建议挪 50% 至理财(v0.4)
- **「人赚 vs 钱赚」二分收益指标** · 含收入 XIRR / 剔除收入资产年化 · 真实资产回报(v0.4.2)
- **股票估值事件 ledger** · 拉价后 ledger 显示 📈 估值行(v0.4.1)
- **AI 综合诊断 JSON 结构化** · 总评 + 4 维度卡(配置 / 风险 / 流动性 / 收益)+ 优先行动 · verdict 染色(v0.4.9)
- **/checkup 风险敞口饼图化** · doughnut + datalabels(v0.4.5)
- **AI 缓存 + ↻ 刷新按钮** · checkup 综合诊断 + rebalance 调仓建议 · 真跳过缓存(v0.4.8)

### Fixed

- **B1** period_snapshot 漏填账户 NULL → fact_view 续值 ≤ 当期最近一笔非空(读时 COALESCE · 不写库)(v0.4.3)
- **B2** dashboard 紧急储备 vs reports 储蓄能力双源问题 → PMC 优先 + cash_flow 回退(v0.4.3)
- **B4** YTD 复用 caller range slice 漂移 → 独立 load 1 月-今天 slice(v0.4.3)
- **prod 应急 hotfix** · dashboard/_region L157/L158 Thymeleaf 表达式 `#numbers.xxx()` 在 `${}` 外的语法错(beta 不触发 / prod 应急金超额触发)+ layout / nav `_csrf` null-safe 兜底(v0.4.4-hotfix × 3)
- **OutputValidator 账户名白名单** · 用户自家「支付宝-余额宝」被 PRODUCT_NAME_PATTERN 误杀 → 加白名单 + 3 态前端反馈(v0.4.6)
- **OutputValidator 放宽** · 删古典中式词 + 删过度客套 + 真名 length≥3 + rebalance caller 跳过真名扫描(v0.4.7)
- **LLM max_tokens 750→2000 + 截断检测** · JSON 输出被中途截断显示半截 JSON → 提到 2000 + finish_reason 日志 + 截断友好错误(v0.4.10)
- **`pct1(ratio)` 没 ×100** · prompt 给 LLM 占比 0.4%(实际 44.2%)误导 LLM → 新增 `pctFromRatio` ×100 + SYSTEM_DIAGNOSE 加「⚠⚠⚠ 100% 禁四则运算」防 LLM 二次瞎算(v0.4.11)

### Changed

- **用户面文案专业化** · 13 模板 ~30 处 · 删内部 routing 暴露(「已搬到 /dashboard」)+ 清版本号 / FR-XX 代号 + 中文化 AUTO / MANUAL / CASH enum + `/entry` / `/admin/fx` 路径文字改友好中文(v0.4.4)

---

## [v0.3] · 2026-05-13

详见 [`prd/v0.3.md`](prd/v0.3.md)。

### Added

- **财务目标体系** · `/goals` 一级页 + dashboard 进度条带
  - 退休 / FIRE(通胀 PV + 4% 提取率)
  - 子女教育金(通胀公式)
  - 应急储备(CASH 类账户口径 · target = 月支出 × 倍数)
  - 三情景预测(乐观 8% / 中性 5% / 悲观 2%)+ 二分反推达成日
- **AI 4 处介入** · 目标设定向导推荐参数 / 周期关闭后异步月报 / 偏离预警(90 天节流)/ 体检页 prompt 扩展
- **股票账户自动估值** · `/accounts/{id}/holdings` 混合模式
  - 自动拉价(录 ticker + 数量 · 新浪主 + 腾讯备 · 圆形熔断 · 美 / A / 港 三市场)
  - 手填市值(未上市 / 私募)
  - 账户内现金(IBKR / 富途 · FX 链式)
- **储蓄能力指标** · `/entry` 成员级月度收支 +2 框 · `/reports` 月度收支双柱图 + 月均 KPI
- **macOS 一键部署** · `deploy/deploy.sh` 顶部 OS 探测 · Darwin 自动转 `deploy-macos.sh`(brew · 无 sudo)

---

## [v0.2] · 2026-05-10

详见 [`prd/v0.2.md`](prd/v0.2.md)。

### Added

- **资产体检模块** · 全家诊断(配置 / 风险敞口 / 流动性 / 收益质量)+ 账户级诊断(类型差异化)
- **智能建议规则引擎** · 16 条规则(余额负数 / 久未填报 / 单类目集中度过高 等)
- **LLM 综合诊断** · 主 Qwen-Plus + 备 DeepSeek-Chat · 真名脱敏 · 圆形熔断
- **产品类目 + 6 级风险评级体系**
- **单账户详情页** · 账本视角(余额时序 + 月分组流水)+ 单账户 CSV
- **iOS PWA** · 添加到主屏(apple-touch-icon + manifest)
- **微信内浏览器引导** · 打开系统浏览器登录
- **4 套预设品牌图标** · 默认 icon2 · 可自定义 WebP 上传
- **dashboard "按账户分布"横向 bar** + "按成员分布"饼图

---

## [v0.1] · MVP

详见 [`prd/v0.1.md`](prd/v0.1.md)。

### Added

- **6 种账户类型** · 现金 / 股票 / 理财 / 房产 / 负债 / 其他 · 13 个内置账户模板
- **月度 / 周度周期可切** · 每周期自动生成"填余额"待办
- **余额录入 + 现金流 + 跨账户转账** · 轧差自动建议未解释金额怎么分类
- **周期自动关闭 + 指标重算** · 净资产 / 总资产 / 总负债 / 趋势 / 配置
- **账户级 XIRR + 家庭级 XIRR / TWR**
- **多币种** · 本位币 CNY / USD / HKD · 自动拉汇率
- **CSV 一键导出** · 避免被工具锁死
- **移动端响应式**
- **11 个 admin 子页** · logo / 品牌名 / 周期类型 / 汇率覆盖 / 成员 / 账户模板 / 类目 / 周期 / 提醒 / 备份 / 审计
