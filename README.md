# 家庭账房 · Family Ledger

> **每月 10 分钟,把全家散在各处的钱,变成一张只在你自己服务器上的资产全局图**——
> 算得出真实年化、分得清「人赚的」和「钱赚的」;大账户手 key 太累?**AI 截图一拍就把每支真实持仓识别拆开**;
> 还有 AI 帮你诊断持仓与配置、看清风险敞口、把再平衡落成可执行的行动(只解读,不荐产品、不预测涨跌)。

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-green)](https://spring.io/projects/spring-boot)

## 功能截图

### 桌面端

<table>
  <tr>
    <td width="33%"><img src="docs/screenshots/fs_dashboard.jpg" alt="仪表盘"><br><sub><b>仪表盘</b> · 净资产趋势(CPI 购买力线 + M2 社会财富线)+ 资产配置环形 + KPI 横条</sub></td>
    <td width="33%"><img src="docs/screenshots/fs_checkup.jpg" alt="资产体检"><br><sub><b>资产体检</b> · 配置 / 风险 / 流动性 / 收益 四维 + AI 综合诊断(数字工程算,LLM 只解读)</sub></td>
    <td width="33%"><img src="docs/screenshots/fs_lens.jpg" alt="资产透视"><br><sub><b>资产透视</b> · 旭日下钻 + 交叉透视表 + 钻到持仓明细 · 10 块预设看板 · 6 分析指标 · 同维值同色</sub></td>
  </tr>
  <tr>
    <td width="33%"><img src="docs/screenshots/fs_accounts.jpg" alt="账户簿"><br><sub><b>账户簿</b> · 9 类账户 · 按成员归集 · 划转 / 体检 / 账本 / 一键导出</sub></td>
    <td width="33%"><img src="docs/screenshots/fs_tags.jpg" alt="多维打标"><br><sub><b>多维打标</b> · 账户 › 持仓 · 资产类型 / 平台 / 行业 / 用途 · AI 按底层投向推荐</sub></td>
    <td width="33%"><img src="docs/screenshots/fs_import.jpg" alt="AI 截图导入持仓"><br><sub><b>AI 截图导入持仓</b> · 识别每支「名称+市值」· 三态比对确认</sub></td>
  </tr>
</table>

### 移动端 · 响应式 + iOS PWA

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/fs_m_dashboard.jpg" width="130" alt="移动端仪表盘"></td>
    <td align="center"><img src="docs/screenshots/fs_m_entry.jpg" width="130" alt="移动端填报"></td>
    <td align="center"><img src="docs/screenshots/fs_m_checkup.jpg" width="130" alt="移动端资产体检"></td>
    <td align="center"><img src="docs/screenshots/fs_m_lens.jpg" width="130" alt="移动端资产透视"></td>
    <td align="center"><img src="docs/screenshots/fs_m_app.jpg" width="130" alt="iOS 主屏 PWA"></td>
    <td align="center"><img src="docs/screenshots/fs_m_import.jpg" width="130" alt="移动端 AI 截图导入"></td>
  </tr>
  <tr>
    <td align="center"><sub>仪表盘 · 洞察速览</sub></td>
    <td align="center"><sub>每月填报</sub></td>
    <td align="center"><sub>资产体检</sub></td>
    <td align="center"><sub>资产透视下钻</sub></td>
    <td align="center"><sub>装为 App(iOS PWA)</sub></td>
    <td align="center"><sub>AI 截图导入持仓</sub></td>
  </tr>
</table>

## 在线体验 · Live Demo(无需部署)

不想先架服务器?直接进演示环境随便点 —— 合成数据、完整功能、手机电脑都行:

### → https://beta.dixi-token.top

| 用户名 | 密码 |
|---|---|
| `wangergou` | `demo1234` |

> 演示环境(beta · HTTPS)· **大家共用 · 全是合成的假数据**:随便逛、随便改都行,但**请勿录入任何真实隐私信息**。
> 想长期、私密地用?往下看「快速开始」自托管一份 —— 数据只待在你自己的服务器上。

## 这是给谁的

**✓ 适合** —— 能自己架一台 Linux 服务器 · 钱散在银行 / 支付宝 / 券商 / 房产 / 房贷 · 在意隐私,不想把全家财务交给任何商业 App · 想知道**真实**年化收益(不是 App 给你看的那种)。

**✗ 不适合** —— 想下个应用商店 App 就用(本项目要自部署)· 想逐笔每日记账(这是**月度快照**,不是流水账)· 想要预算省钱那类记账。

## 它解决什么(你大概也在问这几个问题)

家庭资产散在 N 个渠道(银行 / 支付宝 / 券商 / 房产 / 房贷……),日常没系统记录:

- **「我们家现在到底有多少钱?」** —— 散成 N 处,没人看得到全局。
- **「这一年是变富了还是变穷了?」** —— 没有趋势,更别说扣掉通胀后**真实购买力**变没变。
- **「攒下来的和投资赚的混一起,投资到底行不行?」** —— 分不清「人赚的」和「钱赚的」。
- **「买了一堆基金,家里到底重仓了什么行业?」** —— 基金看不见成分,行业风险藏在名字后面。**穿透**到每支基金的真实持仓(股 / 债 / 现金 + 申万行业),再以**家庭为整体**汇总 —— 看清真实行业敞口,而不是一堆基金名字。
- **「夫妻俩时间错开,谁来记?」** —— 缺一个全家共用、异步填报的载体。

## 为什么不用现成的

| 你可能在用 | 卡在哪 |
|---|---|
| Excel | 手搓几小时,XIRR / 真实年化算不准,不会自动拉价 / 汇率 |
| 支付宝 / 雪球 | 只看自家平台,没有跨账户全局,数据也不在你手里 |
| 通用记账 App | 为「省钱 / 流水」设计,不分本金与收益,还要每天记 |
| Beancount / Firefly III | 强,但学习曲线陡,且偏通用、非中国大陆家庭语境 |

## 设计取舍

**核心约束**:每月找一天、10 分钟以内、夫妻异步完成全部录入。

- **颗粒度**:默认"账户月末快照 + 当月外部现金流";**想更细也不用手 key** —— 基金/理财/券商大账户可用 **AI 截图导入**把每一支真实持仓拆出来(见下)
- **恒等式**:`本期投资损益 = 期末余额 − 期初余额 − 净外部流入`
- **不做**:逐笔每日流水账 / 定投提醒 / 预算包络 / 券商下单(只读铁律)—— 都与"每月 10 分钟"冲突

## 近期更新 · Releases

> 完整发布记录与截图见 [Releases](https://github.com/LuoDi-Nate/financial-management/releases)(本段每版只留 2–4 行,细节不搬过来)。

**[v1.19.16](https://github.com/LuoDi-Nate/financial-management/releases/tag/v1.19.16) · 一张从来没有人失效过的缓存(并含 v1.19.14/15)**
有用户报「改完上月、重新关账后仪表盘还是之前的数据」。照他的路径用**真浏览器**复现下来:数字是更新的,没更新的是**「AI 月度复盘」那段解读** —— 它的缓存按「家庭+账期+维度」存,而全项目**没有任何地方失效过它**。现在重开账期会连该期缓存一起清,进行中的期一律不缓存(月中生成的解读不该在关账后被当成本期定论)。并含 v1.19.14/15:修好百炼托管路线的会话链路(它是三步不是一步),以及新增「测一下百炼连没连上这个账房」一键自检。**无 DB 迁移。**

**[v1.19.13](https://github.com/LuoDi-Nate/financial-management/releases/tag/v1.19.13) · 托管 Agent 建出来了,但它是个没有系统提示词的空壳**
一条 405 背后三件事同时错:① agent 其实**早就创建成功了**(失败文案却一律写「创建失败」,让人以为前面全白做了)② 更新动词该是 `POST` 不是 `PUT` ③ **系统提示词一句都没写进去** —— 百炼收它的字段名是 `system`,我们发的是 `instructions`,而它对不认识的字段**静默忽略**。第 ③ 条不报错、不降级、HTTP 200 还带 agent_id,看起来完全成功;后果是 agent 挂着 MCP 却不知道口径纪律。所以这一版真正的产出是**写完回读**:创建与更新之后各确认一次提示词与 MCP 引用真的存住了。**无 DB 迁移**;已建过 Agent 的升级后点一次「更新百炼上的 Agent 模板」。

## 主要能力

- **每月 10 分钟全家完成** — 月度 / 周度周期可切 · 自动生成「填余额」待办 · 夫妻异步填报 · 移动端响应式 + iOS 可加桌面 PWA
- **AI 截图智能填报(v1.4)** — 基金/理财/券商大账户里堆着十几支真实持仓、手 key 太累?**咔咔截图上传,视觉大模型识别出每支「名称 + 市值」并自动打标**(资产类型/行业),和已有持仓做左旧右新三态比对(更新/新增/卖出),扫一眼确认即入库 · 只转写不算数、识别不准诚实标疑 · 之后旭日/透视/体检自动下钻到真实基金 · 复用你自己的通义千问 key,成本几毛/月(免费额度内)
- **9 类账户、一张全局图** — 现金 / 股票 / 理财 / 加密 / 贵金属 / 房产 / 负债 / 保险 / 其他 · 16 个内置模板 · 按成员归集成家庭净资产
- **资产透视 · 多维下钻 + 基金穿透(v1.5)** — 风险 / 大类 / 行业 / 平台 / 主理人 / 用途 / 地域 / 币种任意切,旭日下钻 + Excel 式交叉透视 + 钻到持仓明细 · 10 块预设看板 + 自定义 · AI 推荐打标 · **基金穿透**:公募基金拆成真实「股/债/现金 + 申万行业」(账户→持仓→持仓方向),行业维度出真实分布而非单标签;只用公开代码查、金额不出服务器、共享缓存;理财诚实标未穿透
- **真实收益率** — 账户级 / 家庭级 XIRR(资金加权)+ 资产 TWR(剔除收入)· 分得清「人赚的(工资攒的)」和「钱赚的(投资)」
- **财富水位:扣通胀看真实身家** — 净资产叠 **CPI 购买力线**(还买得起同样的生活吗)+ **M2 社会财富线**(在社会里的排位升还是降)· 1990–2025 历史底座
- **多币种** — 本位币 CNY / USD / HKD · 自动拉汇率
- **持仓自动估值** — 录 ticker + 数量 · 每日自动拉价(股票:新浪主 + 腾讯备 · 美 / A / 港三市场;加密:Binance 主 + CoinGecko / Coinbase 备;贵金属:新浪 SGE 上海 / 国际现货,金银铂钯按克 / 盎司)· 可与账户现金联动(买入扣现金、卖出加回)
- **AI 资产体检 + 调仓建议** — 4 维诊断(配置 / 风险 / 流动性 / 收益)+ 具体调仓步骤(「从 X 调 ¥N 到 Y」)· **所有数字工程算好,LLM 只解读,不荐产品、不预测涨跌**
- **财务目标** — FIRE 退休(通胀现值 + 4% 提取率,目标支出可自适应近月真实支出)/ 子女教育 / 应急储备 · 三情景预测(乐观 / 中性 / 悲观)
- **决策辅助** — 账户级基准对照 / 提前还贷决策器(NPV 18 年视角)/ 应急金不闲置提示
- **截止前强提醒** — 3 种填报模板 · 截止前 N 天短信(阿里云,可选)+ 站内 banner 兜底
- **全在管理页热改** — LLM key / 股票开关 + cron / 汇率 cron / checkup 阈值 / 会话期 等运营参数实时生效不重启(DB > env > 代码默认 三层 fallback)
- **一键部署 + 隐私可移植** — Docker compose 一键(amd64 + arm64,覆盖 NAS / Apple Silicon)或 systemd 直装 · 自托管,数据只在自己服务器 · 真名脱敏后才喂 LLM · 手机号 / aksk / key 双重防回归 · CSV 一键导出 · Apache 2.0

> 想知道每个能力是哪个版本加的、完整迭代史 → 见 [CHANGELOG.md](CHANGELOG.md)。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Spring Boot 3.3 + Java 21 + MyBatis 3 |
| 持久化 | MySQL 8(版本化 SQL 迁移 + sha256 校验,无 Flyway 依赖) |
| 前端 | Thymeleaf + HTMX 1.9 + Chart.js 4 + ECharts(无 SPA、无构建管线) |
| 认证 | Spring Security + bcrypt + Session Cookie |
| 部署 | **Docker compose 一键(v0.7,推荐)** · 或 Linux systemd + nginx 反代 :80 → :20000 · macOS launchd(可选)直连 :20000 |
| 测试 | JUnit 5 · 785 单元(含 AttributionEngine 归因两步法闭合 + RebalancePlan 核销规则 + PivotEngine 透视引擎(归因降级+币种不变性) + LensAiTag 白名单 +  PrivacyIsolationTest 静态扫源码私密红线 + CurrencyInvarianceTest 币种不变性(含保险) + AShareTicker 交易所前缀 + MetalUnit 贵金属单位/归一 + BrokerReadOnlyGuard 券商只读铁律静态扫 + FutuOpend 向导只读护栏(下载白名单/只绑127.0.0.1/密码只MD5)+ AllocationDiff 保险独立桶 + InsurancePolicy 保单登记 + EntryLoanPrompt 贷款趋势预测兼容闸(含「系统代填的已填不算人确认过」) + PeriodOpenerTodoAlignment 开账代填即已填 + GoalMetricEvaluator 指标聚合 + GoalPaceCalculator 进度落后判定 + 单一镜头端到端币种守护 + ClosedPeriodAnchorTest 收益类指标锚已关账期 + ExpenseLedgerService 家庭支出唯一口径(逐笔/总额优先级受模式约束 · 未来账期不计入 · 归档与换汇对齐事实表) + UpdateCheckService 版本比较/迁移判定 fail-closed/结果收敛进 VARCHAR(512) + MetricDisplay 比率失真降级(正常区间不许降级 · 金额类不被误伤 · 失真值派生的 Δ 列一起降级) + LlmCatalogConsistency 平台/型号目录唯一一份(扫包双向比对,不写死类名) + LlmCallSiteRouting 六个 AI 调用点全部只持路由 + MemberDirectory 成员三桶口径(展示含归档 / 选择仅活跃 / 编辑候选=活跃∪当前) + MemberReferenceScanner 删成员前 13 处引用全覆盖(4 处无外键靠反射测试当外键) + MemberArchiveMoneyInvariance 归档只停「谁来打理」不动钱 + UsernameRename 先清票根再改名 + OpendRelease 官方发布物定位(现行域名/命名/取最新端点/10.x 交互登录分流) + OpendConfigXml 控制口按死回环(官方模板里它是注释掉的) + OpendCatalog 安装包哈希校验(对不上拒装 · 清单读不到≠未核对) + OpendTelnet 登录状态机(失败判定先于验证码) + ContainerGatewayChannel 共享卷通道(未启用给命令不报错 · 心跳过期判掉线 · 密码不落日志) + LedgerSource 流水来源(UNKNOWN≠MANUAL · 解析永不抛 · 标签无技术词) + ValuationSourceInfer 估值来源推断(显式优先 · 按持仓市场分流) + AttributionAnchor 归因锚已关账期(转入不被读成亏损 · 混锚会把差额藏进未归因) + ValuationManagedRouting 余额托管判据一份定义(有持仓才接管 · 无持仓不许凭空造现金行) + ReconciliationScan 账目对账判据(该抓/不该抓成对钉,防退化成永远绿的装饰) + ErasureDetector 抹钱识别(按后缀和逐个试 · 写回拦截与事后对账共用一份) + AccountTypeSemantics 账户类型语义分类(加新类型必须表态的结构性护栏 · METAL 曾漏在投资类外) + ReconcileSecondView 对账第二视角(整期是否自洽 · 已纠正的痕迹自动降级,防照着误报删钱) + RecentClosed 月均支出剔除进行中账期(半个月不当整月)+ SavingsRatePeriod 储蓄率带出账期(它常常不是本期)+ DashboardLiveScope 趋势标出进行中那一期 + EntryExpenseLiability 信用卡可记支出且方向正确(负债余额存负数,与现金账户共用一个符号)+ 负债账户禁还贷/利息防支出双计 + HoldingImport friendly 上游失败分类(额度耗尽不许退化成「请重试」· 配额判据须排在 403 之前)+ ReviewCacheStaleness 复盘缓存失效(已关账才缓存 · 进行中的期不读也不写)+ ManagedAgentEventParsing 百炼事件流解析(终止信号在 content[].data.session_status · 事件自身的 status 不是会话状态 · 正文只收 text 块)+ AiAccessHint 创建 Agent 失败提示按上游原话分流(模型不存在→指空间授权 · 认不出的承认在猜 · 不许再指向三个本来就对的配置项)))/ 175 e2e 断言(21 主线)/ 726 黑盒回归 |

## 快速开始(自托管部署)

> **最低配置**:1 GB 内存 · 1 核 · ~2 GB 磁盘(app 约 512 MB + MySQL 约 300 MB)。**512 MB 的小内存机会 OOM 起不来**,建议 1 GB 起;NAS / 旧笔记本 / 1 核 1 G 云服务器都够跑。

### 方式一 · Docker(推荐 · Linux / macOS / NAS)

```bash
git clone https://github.com/LuoDi-Nate/financial-management.git
cd financial-management
bash deploy/docker-up.sh       # 一条命令:自检环境 + 生成密钥 + 起服务 + 验健康
```

`docker-up.sh` 会自检 docker / 引擎 / Compose V2 是否就绪(macOS 的 Docker Desktop、OrbStack、colima 各种装法,以及 Linux 原生 engine 都适配),卡住时**按你的系统**给可复制的修复命令(Linux 给 `systemctl` / `docker-compose-plugin`,macOS 给 brew / colima);镜像拉不到就本地构建。装 Compose V2 的完整方法见 [`deploy/README.md` § Compose V2 怎么装](deploy/README.md)。

> **中国大陆:不用额外配任何东西**(v1.6.21 起)。数据库镜像默认取 **GHCR 上我们镜像的同一份 `mysql:8.0`** —— 和 app 镜像同一个源,大陆直连,不碰被限速的 Docker Hub。万一 GHCR 也不通,`docker-up.sh` 会自动退回 Docker Hub;两条都不通时它会**问你一句、然后自己把国内镜像源配好并重启 Docker**(colima / Docker Desktop / Linux 原生都覆盖),不再要你手改引擎配置文件。手动配法(OrbStack、或你想自己来)见 [`deploy/README.md` § 国内镜像加速](deploy/README.md#国内镜像加速--apple-silicon)。

<details><summary>想手动控制每一步(老手)</summary>

```bash
cp .env.example .env           # 手改密钥(docker-up.sh 会自动生成随机密钥,手动则自己填)
docker compose up -d           # 起 app + MySQL + 备份;有预构建镜像就拉,否则 docker compose build
```
`db` 默认用 GHCR 上的 `mysql:8.0` 副本(大陆直连);想走 Docker Hub 官方源就在 `.env` 里设 `MYSQL_IMAGE=mysql:8.0`。手动路径不做源探测,拉不动就自己配镜像源(注意 `docker compose build` **救不了** —— 本地构建要从 Docker Hub 拉 `maven`/`eclipse-temurin` 基础镜像,同样过不了墙),见 [`deploy/README.md` § 国内镜像加速](deploy/README.md#国内镜像加速--apple-silicon)(跑 `docker-up.sh` 则会自动探测并代你配)。若报 `unknown shorthand flag: 'd' in -d`,是这台机 Compose V2 没装好,同见该节排障。
</details>

> **Windows**:装 [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/)(WSL2 后端,Win10/11 Home 也支持)→ 打开一个 WSL2(Ubuntu)终端,在里面 `git clone` 后跑**同一条** `bash deploy/docker-up.sh`(WSL2 就是 Linux,脚本原样适用;`docker compose` 随 Docker Desktop 自带)。建议把仓库放在 WSL2 文件系统内(`\\wsl$` 而非 `C:\`)以获得正常性能。前置:BIOS 开虚拟化 + 一次 `wsl --install` 并重启。

浏览器开 `http://<宿主>:20000`。**默认只发布到 loopback(`127.0.0.1`)**——本机 / NAS 直接开即可;**部署在远程 VPS 则从笔记本打不开**(这是安全默认,不是 bug):临时看用 SSH 隧道 `ssh -L 20000:127.0.0.1:20000 user@服务器`,长期用前置反代 + HTTPS(见 [FAQ](docs/faq.md) / [`deploy/README.md` § 反代](deploy/README.md#反代--httpscompose-不内置自己挂))。**起好后怎么登录、第一次怎么用 → 见下方「部署好了:第一次怎么用」一节**。数据持久化在命名卷。**怎么更新到新版本 → 见下方「[更新到新版本](#更新到新版本)」一节**(一条命令,会告诉你从哪一版升到哪一版)。**已用下面 systemd 直装的存量用户**可一键迁移:`sudo bash deploy/migrate-to-docker.sh`(数据零丢)。详见 [`deploy/README.md` § Docker 部署](deploy/README.md#docker-部署v07--推荐)。

### 方式二 · 直装(systemd · 无 Docker 环境)

### 前置

- 一台公网 Linux 服务器(Ubuntu 22+ / Debian 12+ / RHEL 9+ / Alibaba Cloud Linux 都行)
- 你能 SSH 进去 + 有 sudo
- 一个 80 端口可达(可选 443)

### 部署

```bash
# 1. SSH 进服务器
ssh user@your-server

# 2. clone + 一键安装(脚本会装 JDK 21 / Maven / MySQL 8 / nginx 全套依赖)
sudo apt install -y git
git clone https://github.com/LuoDi-Nate/financial-management.git
cd financial-management
sudo bash deploy/deploy.sh

# 中途交互最多 2 个问题(DB 密码、HTTP 端口),其余全自动
```

完成后浏览器访问 `http://<server-ip>/`。**怎么登录、第一次怎么开始用 → 见下方「部署好了:第一次怎么用」一节**(Docker / 直装通用)。

### 后续发版迭代

```bash
cd financial-management
git pull
sudo bash deploy/deploy.sh
```

同一个 `deploy.sh`,自动检测到已上线 → 切到迭代模式:mysqldump 备份 + 增量迁移 + 切 jar + restart + 健康检查 + 失败自动回滚。

### 回滚

```bash
sudo bash deploy/rollback.sh
```

### macOS 本地部署(开发 / 个人自用)

```bash
# 前提:已装 Homebrew
git clone https://github.com/LuoDi-Nate/financial-management.git
cd financial-management
bash deploy/deploy.sh   # 直装唯一入口 · macOS 自动转内部实现(无需自己区分平台)
```

跟 Linux 路径的差异:无 sudo · 用 brew 装依赖 · 文件全在 `$HOME/finance` · 启动用 `bash ~/finance/start.sh`(或 launchd 自启)· 没 nginx 反代,浏览器直接 `http://127.0.0.1:20000/`。详见 [`deploy/README.md` § macOS 本地部署](deploy/README.md#macos-本地部署)。

详细部署文档:[`deploy/README.md`](deploy/README.md)

## 更新到新版本

### Docker(推荐)

```bash
git pull                       # 更新 compose 文件与部署脚本本身
bash deploy/docker-up.sh       # 拉新镜像 + 重建容器 + 告诉你从哪一版升到哪一版
```

**同一条命令既是首装也是更新**,幂等、可反复跑。数据在命名卷里,更新不动数据。跑完它会明确告诉你结果:

```
✓ 已更新:v1.6.24 → v1.6.25
✓ 已是最新发布版(GitHub 最新 release = v1.6.25)
```

或者:

```
· 当前版本 v1.6.24
⚠ 你在跑 v1.6.24,但最新发布版是 v1.6.25。
  最常见原因:刚打 tag 不久,预构建镜像还在 CI 里(约 12 分钟)—— 过几分钟重跑本脚本即可。
```

脚本对比的是 **GHCR 上真的能拉到的最新镜像**(不是 GitHub 上最新的 release)—— 因为发布 tag 之后镜像还要经 CI 构建,
构建没完成或失败时,拿 release 去比会告诉你"有新版"而你怎么都拉不到。两个来源都查不通时它会**明说查不到**,
不会静默(设 `FINANCE_NO_UPDATE_CHECK=1` 可关掉这项检查)。

**两件容易误解的事**(v1.6.25 之前我们没说清,给用户造成过困扰):

1. **`git pull` 拉到的新代码不会进容器。** 应用来自预构建镜像(GHCR),`git pull` 只影响
   **compose 文件**和**部署脚本本身**(它们确实会随版本变,比如 v1.6.21 换了数据库镜像源、
   v1.6.22 改了健康检查判据)。想立刻用上仓库里的代码而不等镜像:`docker compose up -d --build`(本地构建)。
2. **打完 tag 到镜像可用,中间有约 12 分钟的 CI 构建时间**,而且构建**可能失败**(上游 Maven 仓库偶发 403 就够了)。
   脚本会替你分辨「已经最新」「镜像还在构建」「查不到」三种情况;若久等不来,说明那次构建失败了,
   到 [Releases](https://github.com/LuoDi-Nate/financial-management/releases) 或 Actions 页确认一下即可。

想确认当前版本,不用登录:

```bash
curl -s http://127.0.0.1:20000/health     # {"status":"UP","version":"1.6.25"}
```

不想让脚本联网查最新版本:设 `FINANCE_NO_UPDATE_CHECK=1`。

### 日常运维速查(Docker)

| 要做什么 | 命令 |
|---|---|
| 看日志 | `docker compose logs -f app`(只看错误:`docker compose logs --tail=200 app \| grep -i error`) |
| 停 / 起 / 重启 | `docker compose stop` / `docker compose start` / `docker compose restart app` |
| 更新 | `git pull && bash deploy/docker-up.sh` |
| 改配置 | 编辑 `.env` 后 `docker compose up -d`(运营参数如 key/阈值走**管理页**,改 `.env` 无效) |
| **立刻备份一份** | `bash deploy/backup-now.sh [输出目录]`(备完会校验能不能解开;给目录则同时拷到宿主机) |
| **从备份恢复** | `bash deploy/restore.sh`(列出备份让你选 · **灌入前先把当前库另存当退路**) |
| **出问题收集信息** | `bash deploy/doctor.sh`(版本/容器/磁盘/最近错误日志 · 已脱敏 · 可直接贴 issue) |
| 数据在哪 | Docker **命名卷**,不在仓库目录里:`docker volume ls \| grep db-data` |
| 彻底重来 | `docker compose down -v && bash deploy/docker-up.sh` ⚠ `down -v` 会删光数据库,先备份 |

`bash deploy/docker-up.sh` 跑完也会把这张表打印出来,不用回头翻文档。

**备份节奏**:Docker 下由 `backup` 容器每 24 小时 dump 一次到 `backups` 卷(`finance-*.sql.gz`,默认保留 56 天,
`RETENTION_DAYS` 可调);systemd 直装由 `finance-backup.timer` 每天 03:30 跑到 `/var/backup/finance/`。
**重要操作前请自己 `backup-now.sh` 一份** —— 自动备份最多是"昨天那一份"。

### systemd 直装

```bash
git pull
sudo bash deploy/deploy.sh     # 幂等 · 内置迁移 + 失败自动回滚 jar
```

回滚上一版:`bash deploy/rollback.sh`(deploy 会把旧 jar 留成 `app.jar.prev`)。

---

## 部署好了:第一次怎么用(Docker / 直装通用)

部署成功后,浏览器打开 `http://<宿主>:20000`(Docker)或 `http://<server-ip>/`(systemd 经 nginx),用默认账号登录:

| 用户名 | 密码 |
|---|---|
| `diwa` 或 `wangergou` | `demo1234`(Docker 下可在 `.env` 的 `SEED_ADMIN_PASSWORD` 改;**首次登录强制改密**)|

**先理解一个概念**:本工具按「**周期**」记账 —— 一个周期 = 一次月度快照。流程是 **开周期 → 各成员填本期余额 → 关周期 → 出净资产 / 收益报告**。每月花 10 分钟填一次,不用逐笔记。

然后按这 6 步起步:

1. `/admin/family` 改家庭名 + 选品牌图标
2. `/admin/members` 把成员显示名改成你和家人
3. `/admin/periods` 点「立即开下一周期」开一个 OPEN 周期
4. `/accounts/new` 用向导加你的银行卡 / 支付宝 / 券商 / 房 / 房贷
5. `/entry` 填本期余额 → 关周期,就能在仪表盘看到净资产和收益
6.(可选)接 AI 体检 / 短信提醒 → [配置与接入指南](docs/configuration.md)(全部可选,核心零配置即用)

> 第一次用 / 想知道每月该怎么走一遍 → 见 **[使用手册 · 主流程与分析路径](docs/how-to-use.md)**(带截图)。
> 不确定某笔钱该怎么记(工资 / 买基金 / 还房贷 / 账户间转钱)→ 见 **[怎么记 · 场景速查](docs/how-to-record.md)**。
> 卡住 / 想了解远程访问、备份恢复、忘记密码等 → 见 [常见问题 FAQ](docs/faq.md)。

## 本地开发

```bash
# 起 MySQL
sudo systemctl start mysql
sudo mysql <<'SQL'
CREATE DATABASE finance CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'finance'@'localhost' IDENTIFIED BY 'finance';
GRANT ALL ON finance.* TO 'finance'@'localhost';
FLUSH PRIVILEGES;
SQL

# 跑 schema 迁移
DB_USER=finance DB_PASS=finance DB_NAME=finance bash db/apply.sh

# 启动应用(dev profile · DevSeedRunner 把 PLACEHOLDER 密码设为 demo1234 的 bcrypt)
mvn spring-boot:run
```

打开 `http://localhost:8080/login`,默认账号见上。

测试:

```bash
mvn test                       # JUnit 单元测试(770)
bash scripts/qa-run.sh         # 黑盒 endpoint + 模板渲染(见 README 上方测试行的黑盒回归数)
bash scripts/e2e.sh            # 端到端主线真验收(13 主线 93 断言 · 唤起 beta 调接口 + DB 真值判定 · mysqldump 快照/还原,不清库)
```

## 文档

- **产品需求**:[`prd/v0.1.md`](prd/v0.1.md) · [`prd/v0.2.md`](prd/v0.2.md) · [`prd/v0.3.md`](prd/v0.3.md) · [`prd/v0.4.md`](prd/v0.4.md) · [`prd/v0.5.md`](prd/v0.5.md) · [`prd/v0.6.md`](prd/v0.6.md) · [`prd/v0.7.md`](prd/v0.7.md) · [`prd/v0.8.md`](prd/v0.8.md) · [`prd/v0.9.md`](prd/v0.9.md) · [`prd/v0.10.md`](prd/v0.10.md) · [`prd/v0.11.md`](prd/v0.11.md) · [`prd/v0.12.md`](prd/v0.12.md) · [`prd/v0.13.md`](prd/v0.13.md) · [`prd/v0.14.md`](prd/v0.14.md) · [`prd/v0.15.md`](prd/v0.15.md) · [`prd/v0.16.md`](prd/v0.16.md) · [`prd/v0.17.md`](prd/v0.17.md) · [`prd/v1.1.md`](prd/v1.1.md) · [`prd/v1.2.md`](prd/v1.2.md) · [`prd/v1.2.2.md`](prd/v1.2.2.md) · [`prd/v1.3.md`](prd/v1.3.md) · [`prd/v1.4.md`](prd/v1.4.md) · [`prd/v1.5.md`](prd/v1.5.md) · [`prd/v1.6.md`](prd/v1.6.md)
- **技术设计**:[`tech-design/v0.1.md`](tech-design/v0.1.md) · [`tech-design/v0.2.md`](tech-design/v0.2.md) · [`tech-design/v0.2-checkup.md`](tech-design/v0.2-checkup.md) · [`tech-design/v0.3.md`](tech-design/v0.3.md) · [`tech-design/v0.4.md`](tech-design/v0.4.md) · [`tech-design/v0.5.md`](tech-design/v0.5.md) · [`tech-design/v0.6.md`](tech-design/v0.6.md) · [`tech-design/v0.7.md`](tech-design/v0.7.md) · [`tech-design/v0.8.md`](tech-design/v0.8.md) · [`tech-design/v0.9.md`](tech-design/v0.9.md) · [`tech-design/v0.10.md`](tech-design/v0.10.md) · [`tech-design/v0.11.md`](tech-design/v0.11.md) · [`tech-design/v0.12.md`](tech-design/v0.12.md) · [`tech-design/v0.13.md`](tech-design/v0.13.md) · [`tech-design/v0.14.md`](tech-design/v0.14.md) · [`tech-design/v0.15.md`](tech-design/v0.15.md) · [`tech-design/v0.16.md`](tech-design/v0.16.md) · [`tech-design/v0.17.md`](tech-design/v0.17.md) · [`tech-design/v1.1.md`](tech-design/v1.1.md) · [`tech-design/v1.2.md`](tech-design/v1.2.md) · [`tech-design/v1.2.2.md`](tech-design/v1.2.2.md) · [`tech-design/v1.3.md`](tech-design/v1.3.md) · [`tech-design/v1.4.md`](tech-design/v1.4.md) · [`tech-design/v1.5.md`](tech-design/v1.5.md) · [`tech-design/v1.6.md`](tech-design/v1.6.md)
- **预览原型**:[`preview/index.html`](preview/index.html)(Tailwind CDN 静态预览)· [`preview/v0.4/`](preview/v0.4/index.html) · [`preview/v0.5/`](preview/v0.5/index.html) · [`preview/v0.6/`](preview/v0.6/index.html)(财富水位 / 股票现金联动 / FIRE 自适应 / PWA 引导)· [`preview/v0.14/`](preview/v0.14/precious-metals.html)(贵金属账户 + 自动金价 / LLM 供应商自选 / 配置开放梳理)
- **视觉设计规范**:[`docs/visual-spec.md`](docs/visual-spec.md)(UED 规范:色彩轴 / 对比度底线 / 组件规格 / iOS 约束 / 大组件移动化) · [`docs/ued-review-2026-07.md`](docs/ued-review-2026-07.md)(全站双端截图审计 61 条) · [`docs/design-system.md`](docs/design-system.md)(现状梳理)
- **使用手册(新手先看)**:[`docs/how-to-use.md`](docs/how-to-use.md)(带截图 · **月度主流程**:填报顺序 → 关账 → 看结果 → 季度体检;**分析路径**:「高风险资产在谁手上、具体哪几笔」这类问题从旭日一路钻到单笔持仓)
- **v1.7 设计文档**:[`prd/v1.7.md`](prd/v1.7.md) · [`tech-design/v1.7.md`](tech-design/v1.7.md)(交互式使用手册:功能全集 → 必修/选修分类 → 24 章)
- **v1.8 设计文档**:[`prd/v1.8.md`](prd/v1.8.md) · [`tech-design/v1.8.md`](tech-design/v1.8.md)(支出逐笔化:口径收敛到一处 → 两个「支出」必须分开 → 总额模式逐位不变;tech-design §8 记了施工中被分水岭比对拦下的三处)
- **v1.9 设计文档**:[`prd/v1.9.md`](prd/v1.9.md) · [`tech-design/v1.9.md`](tech-design/v1.9.md)(自动版本查询:只查不改 —— 落后几个版本 + 这中间有没有 DB 迁移;tech-design §7 记了「能不能做一键更新/自动更新」的可行性与风险分析)
- **v1.10 设计文档**:[`prd/v1.10.md`](prd/v1.10.md) · [`tech-design/v1.10.md`](tech-design/v1.10.md)(报表页进化为月度封板快照:期末资产负债表 + 资金流瀑布(含恒等式校验)+ 环比/同比三列对照 + 集中度/流动性分层 + 本期归因;`range` 只管趋势区 · 指标不落库但修掉会改写历史的漂移源)
- **v1.11 设计文档**:[`prd/v1.11.md`](prd/v1.11.md) · [`tech-design/v1.11.md`](tech-design/v1.11.md)(报表页可用性收束 13 项 + 全量指标审计;tech-design 记了三项「维护者点名要判断」的结论:同一区内窗口必须共用一个时间控件且只属于趋势区 · 哪些仪表盘指标值得进封板区的逐项取舍 · 以及「一条 SQL ≠ 一次扫描」那次把 1.25s 优化成 9.3s 的反面案例。**两份文档是事后补写的**,原因与教训写在 prd §5)
- **v1.12 设计文档**:[`prd/v1.12.md`](prd/v1.12.md) · [`tech-design/v1.12.md`](tech-design/v1.12.md)(把「封板」做实:关账时把账户的分类属性一并定格,改设置不再改写历史月份的集中度/流动性分层/大类分布/vs 基准 —— 只冻结**分类输入**、不冻结**指标输出**,保住「口径改了自动对全部历史生效」;顺带补 SQL 归因手段并消掉报表页剩余 N+1)
- **v1.13 设计文档**:[`prd/v1.13.md`](prd/v1.13.md) · [`tech-design/v1.13.md`](tech-design/v1.13.md)(LLM 配置从「两家二选一」升级为**平台 → 模型系列 → 具体型号**三级,新接火山方舟,文本与视觉各配一套;主备编排收口到一个路由 —— 施工前查证发现六处调用点里只有一处真的听「主选」那个配置,tech-design §0.1 记了这个 bug 的形态与为什么不能逐点修;旧配置**读时派生**不写迁移 SQL,回滚仍可用)
- **v1.14 设计文档**:[`prd/v1.14.md`](prd/v1.14.md) · [`tech-design/v1.14.md`](tech-design/v1.14.md)(截图导入支持拖拽 + Ctrl+V 粘贴,来自 GitHub issue #11:上传区的 id 一直叫 `dropZone`、外框一直是虚线,却从来不接拖拽 —— PC 上真拖上去浏览器会导航走、这次导入全丢,所以这版兑现的是一个会让人丢东西的错误暗示)
- **v1.15 设计文档**:[`prd/v1.15.md`](prd/v1.15.md) · [`tech-design/v1.15.md`](tech-design/v1.15.md)(成员身份:登录名可改 · 归档 · 零引用才给删。归档**只停掉「谁还来打理」,不动一分钱**,他名下账户与历史流水照旧计入总账;删除前逐表数引用,那 4 处没有外键的靠显式清单兜住 —— 自动发现外键会给出一个自信的错答案。tech-design §9 记了六处施工偏差,其中一处是隐私:6 个脱敏点原来拿「仅活跃」列表建假名表,归档成员的真名会原样进 LLM prompt)
- **v1.16 设计文档**:[`prd/v1.16.md`](prd/v1.16.md) · [`tech-design/v1.16.md`](tech-design/v1.16.md)(「本期填报完成」只留一个定义 · GitHub issue #15:开账把上期末余额延续成本期快照的同时,把同一行待填也标成已填 —— 填报页的 ✓、tab 徽标、自动关账从此读同一列,不再出现「页面显示全填好了、徽标还挂着 ·1」;tech-design §1.1 记了「为什么不在计数 SQL 上打补丁」的取舍)
- **v1.17 设计文档**:[`prd/v1.17.md`](prd/v1.17.md) · [`tech-design/v1.17.md`](tech-design/v1.17.md)(Docker 部署下的富途 OpenD 一键接入:今天 Docker 用户被要求自备镜像 + 借一台有桌面的机器 + 把券商密码写进 `.env`,而原生用户只是在向导页点几下。方案定为「我们发一个**可选**的网关镜像」—— 默认 `up -d` 不拉不起,富途二进制不打包、运行时从官方下载并校验哈希,镜像 digest 与打包过程公示可验证。实测证据也表明「容器里跑不了 OpenD」这个前提是错的 —— gtk3/fuse 是桌面版的依赖,命令行版零缺失;顺带修掉过期的下载域名 / 文件名 / 启动参数,那几处今天在原生路径上也是坏的)
- **v1.19 设计文档**:[`prd/v1.19.md`](prd/v1.19.md) · [`tech-design/v1.19.md`](tech-design/v1.19.md) · [`preview/v1.19/ask.html`](preview/v1.19/ask.html)(**超级 Agent**:把六个「我们替你问」的固定 AI 卡片,换成一个「你自己问」的对话入口。我们只维护系统提示词与工具清单,由 agent 自己决定调哪个工具。工具**只给已经算好的口径、不给原始表** —— LLM 一拿到原始数就会自己算占比,而那条「禁止四则运算」的铁律是用生产事故换来的;答案里每个数字都是**可点引用块**,挂着账期、标着是否进行中、点得回产生它的那一页,模型正文里只写引用标记、**碰不到数字本身**。对话历史存在**你自己的服务器**上。runtime 两条:主选**百炼 Managed Agents**(要公网 HTTPS,换来服务端 session 持久化与中断续接),兜底**本机直连**(模型出网、工具在本进程跑、零入网需求)—— 后者是为 NAT 后面、没有域名的自建用户准备的,对他们来说托管路线不是麻烦而是不可能。选型对比与实测改掉的四处设计见 tech-design 附录与 §四.0)
- **v1.18 设计文档(在研)**:[`prd/v1.18.md`](prd/v1.18.md) · [`tech-design/v1.18.md`](tech-design/v1.18.md)(流水时间线的每一行都标出「这笔是谁写进来的」:手动填报 / 自动 · 股价 / 自动 · 金价 / 自动 · 富途 / 截图导入 / 开账延续 / 系统联动,共 10 个来源。以前 `kind` 只说是收入还是估值,`trigger_kind` 只说什么动作触发 —— 同一个定时任务可能是股价接口也可能是金价接口,用户分不出来。历史数据一律 `UNKNOWN`(「来源未记录」)**不回填成手动** —— 那等于假装我们知道。同一版还把券商同步失败从「只写日志」改成写进状态并标在账户列表上:v1.17.3 那次生产事故里富途断了两天,而页面一直显示两天前的**成功**消息)
- **怎么记账 · 场景速查**:[`docs/how-to-record.md`](docs/how-to-record.md)(工资/消费/买卖基金/借钱还贷/账户间转钱 逐场景对照 · 没有财会背景也能看懂)
- **配置与接入**:[`docs/configuration.md`](docs/configuration.md)(AI / 短信 等外部服务配置总指南 · 全部可选)
- **券商同步图文向导**:[`docs/broker-sync-guide.md`](docs/broker-sync-guide.md)(富途 / 老虎凭据一步步获取 · 应用内同款 `/help/broker-sync` 带示意图)
- **常见问题**:[`docs/faq.md`](docs/faq.md)(最低配置 / 远程访问 / 备份恢复 / 忘记密码 / 多家庭 …)
- **QA case 库**:[`docs/qa-cases.md`](docs/qa-cases.md)
- **部署运行**:[`deploy/README.md`](deploy/README.md)

## 目录结构

```
financial-management/
├── src/main/java/com/family/finance/    # Spring Boot 应用代码
│   ├── auth/                              # Spring Security
│   ├── domain/                            # 实体(family/member/account/period/cash_flow/...)
│   ├── repository/                        # MyBatis @Mapper
│   ├── service/                           # 业务服务 + LLM + FX
│   ├── factview/                          # 大宽表抽象(净资产 / 趋势 / 配置 等指标统一从这里出)
│   ├── calc/                              # 纯函数(PnL / XIRR / TWR / Reconciliation)
│   └── web/                               # Controller(account / dashboard / entry / reports / checkup / admin)
├── src/main/resources/
│   ├── application.yml                   # dev/prod profile
│   ├── mapper/                           # MyBatis XML
│   ├── static/                           # CSS / JS / 图标
│   └── templates/                        # Thymeleaf 模板
├── src/test/java/                        # JUnit 5
├── db/
│   ├── apply.sh                          # 版本化迁移运行器(sha256 校验)
│   └── migration/V*__*.sql               # 数据库 schema + 种子
├── deploy/
│   ├── deploy.sh                         # 直装唯一入口(Linux+Mac · Darwin 自动转内部实现)
│   ├── _deploy-macos.sh                  # deploy.sh 的 macOS 内部实现($HOME/finance · brew · 无 sudo)
│   ├── docker-up.sh                      # Docker 唯一入口(全平台 · 自检+生成密钥+起+验健康)
│   ├── finance.macos.plist.template      # macOS launchd 开机自启模板(可选)
│   ├── rollback.sh                       # Linux 紧急回滚
│   ├── nginx-setup.sh                    # Linux 单独 nginx 配置
│   ├── maven-settings.xml                # 国内 mirror 加速(可改)
│   ├── finance.service                   # Linux systemd unit
│   ├── backup.sh + finance-backup.{service,timer}  # Linux 每日自动备份
│   └── README.md                         # 部署手册
├── prd/                                  # 产品需求文档
├── tech-design/                          # 技术设计文档
├── preview/                              # 静态 HTML 预览(v0.1 ~ v0.6 各版本卷)
├── docs/qa-cases.md                      # QA case 库
├── icons/                                # 用户可替换的图标源 PNG
└── scripts/
    ├── qa-run.sh                         # 黑盒回归
    └── qa-e2e.sh                         # 端到端真值校验
```

## 配置项

> 想接 **AI(Qwen/DeepSeek)/ 阿里云短信** 等外部服务?一站式步骤见 **[配置与接入指南](docs/configuration.md)**(全部可选,核心功能零配置即用)。下面是系统级 env 与管理页参数的分工说明。

**v0.4.18 起 · 运营参数沉淀到管理页 · 实时生效不重启**(详 [`prd/v0.4.md`](prd/v0.4.md) §22)。读取链:**DB 优先 → env(@Value)→ 代码常量**。

### A · 留 `/etc/finance.env`(系统级 · 启动前必须存在)

由 `deploy.sh` 自动生成,首装时交互填:

| 项 | 说明 |
|---|---|
| `DB_*` | MySQL 连接信息(`deploy.sh` 自动生成 24 字符随机密码,亦可手填)|
| `SERVER_PORT` | Spring Boot 监听端口(默认 20000,nginx 反代到这里)|
| `SERVER_ADDRESS` | `127.0.0.1` 让 nginx 走 loopback 反代;`0.0.0.0` 让 Spring 直接对外 |
| `UPLOAD_ROOT` | 用户上传 logo 的本地路径 |
| `REMEMBER_ME_KEY` | Remember-me cookie 签名 key(自动 32 字节随机 · 改即踢人)|
| `BACKUP_DIR` | mysqldump 备份目录(默认 `/var/backup/finance`)|
| `RETENTION_DAYS` | 备份保留天数(默认 56 · 被 backup.sh 独立 cron 读)|

### B · 沉淀到管理页(运营参数 · 实时生效)

| 配置 | 入口 | env 兜底 |
|---|---|---|
| **LLM Qwen API key** | `/admin/integrations` ① 段(私密 · 留空保原值) | `FINANCE_LLM_QWEN_API_KEY` 仍可用作 fallback |
| **LLM DeepSeek API key** | 同上 | `FINANCE_LLM_DEEPSEEK_API_KEY` |
| **LLM max_tokens / timeout** | `/admin/integrations` | — |
| **股票自动拉取开关** | `/admin/integrations` ② 段 · checkbox | `FINANCE_STOCK_FETCH_ENABLED=true/false` |
| **行情 4 市场 cron**(美 06:05 / A 16:10 / 港 16:30 / 加密 06:15) | `/admin/integrations` ② 段 · 各自 cron 表达式 · 改即 cancel 旧 future + 重排 | 代码默认 |
| **FX 拉取 cron**(月初 02:30) | `/admin/integrations` ③ 段 | 代码默认 |
| **提醒 cron**(每天 10:00/20:00) | `/admin/reminders` | 代码默认 |
| **smart_transfer 阈值**(¥3000) | `/admin/calc-tweaks` ① 段 | 代码默认 |
| **checkup 阈值**(集中度 40% / 高风险 40% / LIQUID 1.5x / 应急金 6 月) | `/admin/calc-tweaks` ② 段 | 代码默认 |
| **会话有效期**(remember-me · 默认 30 天) | `/admin/calc-tweaks` ③ 段 · 注意新值生效需重启 | `app.remember-me-validity-seconds` |
| **填报模板 + 提前提醒天数**(v0.4.14) | `/admin/reminders` ① 段 | DB · 默认 T1 · leadDays=2 |
| **短信 aksk + 签名 + 模板**(阿里云) | `/admin/reminders` ② 段(私密)| DB 单一来源 · `docs/aliyun-sms-setup.md` 9 步接入 |
| **成员手机号**(短信收件人) | `/admin/reminders` ④ 段 | DB · `member.phone` |

**升级路径**:`deploy.sh` step 9.5 一次性把 env 里的 LLM keys + 股票开关 seed 到 `family_runtime_config` 表(幂等 flag `/var/finance/.config-migrated-v0.4.18`)。之后改 env 不再生效 · DB 是 source of truth · env 仅当 fallback。

### 不要做

- ✗ 改 `/etc/finance.env` 期望生效(v0.4.18 后改 env 不会触发任何 reload · 改管理页才生效)
- ✗ 直接 SQL 改 `family_runtime_config`(可以,但 cache 5s TTL 内不立刻生效;走管理页才会同步 invalidate cache + rescheduleAll)

## 安全

- 单家庭 / 多成员 · Spring Security session cookie · bcrypt 密码哈希
- 所有写操作走 CSRF · 表单与 HTMX 请求自动带 token
- SQL 100% 参数化(MyBatis)· 无 OGNL / 自由表达式
- 文件上传:前端 Canvas 压缩为 WebP + 后端 RIFF magic 校验 + 200KB 上限 + path traversal 防护
- 数据库每日自动备份(systemd timer)· 备份目录权限隔离
- LLM prompt 真名脱敏(成员 A/B/C 稳定映射)· 输出 OutputValidator 检查担保词 / 真名泄露 / 产品代码
- **私密红线 · 编译期 + 静态扫双重防回归**(v0.4.14 + v0.4.18)— 手机号 / 短信 aksk / LLM API key 绝不进 LLM prompt / audit_log 明文 / 前端明文回显 · `PrivacyIsolationTest` 静态扫源码 + 行为单测 · `qa-run v04-PRIV-1` grep gate

发现安全问题?见 [`SECURITY.md`](SECURITY.md)。

## 贡献

欢迎贡献!见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

## License

Apache 2.0 · 见 [`LICENSE`](LICENSE)。

## 致谢

**开源框架 / 工具**

- [Spring Boot](https://spring.io/projects/spring-boot) · [MyBatis](https://mybatis.org/) · [HTMX](https://htmx.org/) · [Chart.js](https://www.chartjs.org/) · [ECharts](https://echarts.apache.org/) · [Thymeleaf](https://www.thymeleaf.org/)
- [阿里云 Maven Mirror](https://maven.aliyun.com/)(国内拉依赖加速)
- 字体:Fraunces / Source Serif 4 / Noto Serif SC / JetBrains Mono(均为开源字体)
- 美学:晚清账册风 + 中式纸面信笺(墨/纸/黄铜/朱印 配色)

**公开数据来源**(自动估值 / 汇率 / 穿透 / 财富水位 —— 仅只读取用公开行情与披露,不涉及账户接入)

- 汇率:[Frankfurter](https://www.frankfurter.dev/)(免费 ECB 汇率 API)
- 股票行情:[新浪财经](https://finance.sina.com.cn/) · [腾讯财经](https://stockapp.finance.qq.com/)(A / 港 / 美股实时价,主备双源)
- 加密货币:[Binance](https://www.binance.com/) · [CoinGecko](https://www.coingecko.com/) · [Coinbase](https://www.coinbase.com/)(主备三源)
- 贵金属:[上海黄金交易所](https://www.sge.com.cn/) / 国际现货(经新浪,金银铂钯按克 · 盎司)
- 基金穿透(v1.5):[东方财富 · 天天基金](https://fund.eastmoney.com/)(公募基金资产配置 / 前十大重仓股 / 个股行业,仅用公开基金代码查询)
- 财富水位基线:国家统计局 CPI · 中国人民银行 M2(公开宏观序列)

**社区贡献**

- 加密货币账户(PR)· 贵金属账户 + 自动金价(issue)等来自社区,见 [Releases](https://github.com/LuoDi-Nate/financial-management/releases) / [CHANGELOG](CHANGELOG.md) 的致谢标注。感谢每一位贡献者。

## Star History

<a href="https://www.star-history.com/?repos=LuoDi-Nate%2Ffinancial-management&type=date&legend=bottom-right">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=LuoDi-Nate/financial-management&type=date&theme=dark&legend=bottom-right&sealed_token=s6BjKV6g9W299xIZnCP3iBRIMc9FPhFYC-g7JRsqSx0mcaD4XO4YpLjDp9QUSTkO6FWANgiH-ZiJKxg9luz4wK2pJqt4_YPmFRasqet8q6aQ9lDsDKdbOg" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=LuoDi-Nate/financial-management&type=date&legend=bottom-right&sealed_token=s6BjKV6g9W299xIZnCP3iBRIMc9FPhFYC-g7JRsqSx0mcaD4XO4YpLjDp9QUSTkO6FWANgiH-ZiJKxg9luz4wK2pJqt4_YPmFRasqet8q6aQ9lDsDKdbOg" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=LuoDi-Nate/financial-management&type=date&legend=bottom-right&sealed_token=s6BjKV6g9W299xIZnCP3iBRIMc9FPhFYC-g7JRsqSx0mcaD4XO4YpLjDp9QUSTkO6FWANgiH-ZiJKxg9luz4wK2pJqt4_YPmFRasqet8q6aQ9lDsDKdbOg" />
 </picture>
</a>

