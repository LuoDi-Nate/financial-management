# 贡献指南

感谢你愿意贡献!

## 优先级方向

我们当前最欢迎的贡献:

1. **bug 修复**(开 issue + PR 都行)
2. **i18n**(目前是中文界面 + 部分英文;欢迎完整英文翻译,Locale 切换)
3. **新数据源 / 新模板**(账户模板、产品类目、银行)
4. **移动端 polish**(尤其窄屏 < 380px)
5. **文档改进**(README / deploy / 截图)

**暂不接受**的方向:

- 个股 / 单券持仓级颗粒度(违反"每月 10 分钟"约束)
- 银行账单 OCR / 券商 API 直连(同上)
- 把 Thymeleaf 换成 SPA(后端模板 + HTMX 是有意选择,不引入构建管线)

## 开 issue 之前

- 搜一下有没有重复
- 描述清楚:**做了什么** + **期望什么** + **实际发生什么** + 环境(OS / Java / MySQL 版本)
- bug 报告附上 `journalctl -u finance -n 50` 的关键片段

## 写代码

### 本地准备

见 [README.md · 本地开发](README.md#本地开发)。

### 代码规约

- **Java**: 标准 Spring Boot 风格 + Lombok `@RequiredArgsConstructor`
- **SQL**: 大写 SQL 关键字,小写表名 / 列名
- **Thymeleaf**: 用 fragment 复用,避免大段重复
- **CSS**: Tailwind 优先,自定义只在 `style.css`
- **JS**: vanilla,无 npm 构建。需要库通过 CDN(via `/vendor/`)
- **commit 消息**: 中英文都行,**信息密度** > 格式
  - 简版:`fix · /entry 提交转账 NPE`(动作 · 一句话)
  - 详版:含 commit body 说明根因 + 修法 + 测试

### PR 流程

1. 从 `master` 开新分支:`fix/some-bug` 或 `feat/some-feature`
2. 改完跑测试:`mvn test && bash scripts/qa-run.sh`(可能需要本地 MySQL)
3. 改了 schema?加 `db/migration/V<n+1>__*.sql`(**不要改已发布的 V*.sql**;`apply.sh` 会 sha256 校验拒)
4. 改了行为?同步 `prd/v0.X.md` 或 `tech-design/v0.X.md` 对应段落
5. 改了 endpoint / 重要 UI?加 `scripts/qa-run.sh` 黑盒 case
6. PR 描述:**改了什么** + **为什么改** + **对老数据是什么影响**(prod 已上线项目,backward-compat 是红线)

## 数据库迁移 backward-compat 红线

prod 跑过的 V*.sql 不允许改内容(`apply.sh` sha256 拒)。新增 schema 改动:

| 操作 | 安全做法 |
|---|---|
| 新加列 | `ALTER TABLE x ADD COLUMN y VARCHAR(N) NULL`,**或**有 `DEFAULT` |
| 删列 / 删表 | **禁止**;改为 `archived_at` 软删,等几版后清 |
| 改列类型 / 长度 | 先 `SELECT MAX(LENGTH(col))` 看实际值,确认不溢出 |
| 改默认值 | 新加 `DEFAULT` 只影响新行,老行保留旧值 |
| 大表 ALTER | 用 `ALGORITHM=INPLACE` 或 `pt-online-schema-change`,避免锁表 |

## 测试

```bash
mvn test                       # 76 单元测试(calc / factview / LLM 输出校验等)
bash scripts/qa-run.sh         # 180+ 黑盒 endpoint + 模板渲染
bash scripts/qa-e2e.sh         # 36 端到端真值校验(会清 DB,本地跑)
```

新功能 PR 必须有对应的 ≥1 条 QA case,数学密集型加 1-3 个单元测试。

## License 与贡献者授权

本项目自 v1.36.0 之后的版本起按 **AGPL-3.0-only** 发布(见 [`LICENSE`](LICENSE) 与 [`NOTICE`](NOTICE)),
同时向不能遵守 AGPL 的组织提供商业授权(双许可)。为了让双许可能成立,提交贡献需要同意下面的授权条款。

**提交 PR(或以其它方式提交代码、文档、图片)即表示你同意:**

1. 你提交的内容是你自己的原创,或你有权以下列方式授权它;
2. 你保留自己贡献的著作权;
3. 你授予本项目维护者(LuoDi-Nate)一项永久、全球、非独占、免费、不可撤销的许可:可以复制、修改、
   公开展示、再许可和分发你的贡献,**既可以按 AGPL-3.0,也可以按包括商业授权在内的其它许可条款**;
   如果贡献涉及你拥有的专利,同时授予实施这些专利所需的许可;
4. 你的贡献同时按 AGPL-3.0 向所有人开放。

不同意的话,欢迎开 issue 描述想法,由维护者自行实现。

---

**Contributor terms (English).** This project is licensed under **AGPL-3.0-only** for releases after v1.36.0
and is also offered under separate commercial licenses (dual licensing). By submitting a pull request
(or otherwise contributing code, documentation or images) you agree that:

1. the contribution is your original work, or you otherwise have the right to license it as below;
2. you keep the copyright in your contribution;
3. you grant the project maintainer (LuoDi-Nate) a perpetual, worldwide, non-exclusive, no-charge,
   irrevocable license to reproduce, modify, publicly display, sublicense and distribute your contribution
   **under the AGPL-3.0 or under any other license terms, including commercial licenses**, together with
   a license under any patents you own that are necessarily infringed by the contribution;
4. your contribution is also made available to everyone under the AGPL-3.0.

If you do not agree, please open an issue describing the idea instead.
