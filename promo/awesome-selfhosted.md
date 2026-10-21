# awesome-selfhosted 上游收录 · 条目草稿与提交清单

目标仓库:**`awesome-selfhosted/awesome-selfhosted-data`**(master 分支的 `software/` 目录)。
`awesome-selfhosted/awesome-selfhosted` 里的 README / HTML 列表是**从这个 data 仓生成的导出物**,不要直接改那边;
各中文 fork(haiiiiiyun / susetao / …)是上游翻译镜像,一般不单收新项目 → 不优先。

## 1 · 条目文件(提交时放 `software/family-ledger.yml`)

文件名 kebab-case。`stargazers_count` / `updated_at` / `current_release` / `commit_history` 这几个字段
**不要自己写** —— 上游 `make update_metadata` 从 GitHub API 自动补,手写反而会被覆盖/被指出。

```yaml
name: Financial Management
website_url: https://github.com/LuoDi-Nate/financial-management
description: Household net-worth tracker built on account-level monthly snapshots instead of per-transaction bookkeeping: multi-currency balances, real returns (XIRR/TWR), look-through from funds to underlying holdings, and LLM-generated portfolio reviews. (alternative to Kubera)
licenses:
  - AGPL-3.0
platforms:
  - Java
  - Docker
tags:
  - Money, Budgeting & Management
source_code_url: https://github.com/LuoDi-Nate/financial-management
```

字段核对(对着邻居 `software/firefly-iii.yml` 抄的结构):

| 字段 | 值 | 说明 |
|---|---|---|
| `licenses` | `AGPL-3.0` | 2026-10-21 起改为 AGPL(维护者定 · v1.36.0 及以前仍是 Apache-2.0);上游 `licenses.yml` 里已有这个标识(Firefly III 用的就是它)。**PR #3176 里的这一行要你自己改**(上游禁止 AI 代写) |
| `platforms` | `Java` `Docker` | 部署方式两条都成立(systemd 直装 + compose) |
| `tags` | `Money, Budgeting & Management` | 字符串必须**一字不差**(对应上游 `tags/money-budgeting--management.yml`) |
| `demo_url` | **待定** | 见下面「开放决定」 |
| `depends_3rdparty` | **待定(倾向不写=false)** | 见下面「开放决定」 |

## 2 · 上游写作规矩(不合规会被打回,LLM 生成且不合规会被 ban)

- 描述里**不许**出现 `open-source` / `free` / `self-hosted` 这类冗余词(整个列表都是自托管开源,写了是废话)。
- 短优先,别用 `A tool that lets you…` 这种冠词开头的绕法。
- 定位成某商业产品的替代品才写 `(alternative to X, Y)`;是 fork 才写 `(fork of Z)` 并列出差异。
- 文档不是英文的要在描述末尾补 `(documentation in Chinese)` —— **我们 README 是中文为主**,这条大概率适用,
  除非提交前把英文入口文档补齐(`README.en.md` 之类)。
- 排除项对照:不是云厂商专属、不是需要另装服务端的桌面/移动/CLI 应用、不是库/SDK、不是给别人项目做的 Docker 化 → **都不沾**。

## 3 · 4 个月门槛:两种算法,差两个月

上游原话是「你要加的项目**首个 release 在 4 个月以前**」,并说明计时「只在有 release 之后才开始,
以免用户不得不依赖开发版」。我们这边两个时间点不一样:

| 算法 | 起点 | 够格日 |
|---|---|---|
| 按**首个 tag** | `v0.2` = 2026-05-10(138 个 tag 全在 GitHub 上) | **2026-09-10** |
| 按**首个 GitHub Release 页**(带发布说明的那种,共 77 个) | `v0.15.0` = 2026-07-11 | **2026-11-11** |

判断:审的人多半点开 `/releases` 页看,所以保守日是 11-11。但下行风险很小 ——
上游对「太年轻」的处置是**关掉 PR 或挂成 issue 等成熟,不是 ban**(ban 只针对不合规的机器生成内容)。
所以**建议 9-10 之后就提**,并在 PR 描述里直接把话说在前面:首个 tag 2026-05-10、至今 138 个 tag、
CHANGELOG 从 v0.2 起完整无断档。被判太早就转成 issue 等着,不用重开。

## 4 · 提交动作(到点照做)

1. 在 `awesome-selfhosted/awesome-selfhosted-data` 用 GitHub 网页新建 `software/family-ledger.yml`,贴上第 1 节的 YAML。
2. commit message:`add Financial Management`;勾「Create a new branch and start a pull request」。
3. PR 描述里写清:项目年龄证据(见第 3 节)、许可、部署方式、以及 demo/第三方依赖的说明。
4. 上游有 `make awesome_lint` / `url_check` CI,链接必须都活着 —— 提交前自己点一遍 README 里的外链。
5. 不想开 PR 也可以用仓库的 addition issue 模板。

## 5 · 开放决定(需要作者拍板)

- **`demo_url` 填不填**:仓库 homepage 现在挂的是一个公开域名。要当 demo 提交的话,那个实例得是
  **纯假数据**(审的人一定会点进去,而且会点到明细页);隐私模式只是把金额糊掉,不等于假数据。
  不想开放就干脆不写 `demo_url` —— 这个字段是可选的。
- **`depends_3rdparty` 写不写**:核心(录入/报表/水位)离线可用 → 倾向不写(即 false)。
  但 AI 体检要外部 LLM key、基金穿透要行情来源,审的人可能追问。
  想一次性消掉这个疑问,产品侧的做法是支持 OpenAI 兼容的本地端点(Ollama / vLLM)——
  **这是产品决定,不在本清单范围内**,先记着。
- **英文文档**:见第 2 节最后一条,决定是「补英文 README」还是「描述里老实写 (documentation in Chinese)」。
