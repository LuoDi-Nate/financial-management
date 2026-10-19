# 家庭账房 · 设计系统与两端设计理念

> 这份文档不是"想要怎样",而是**从现有代码反推出来的实际规则** —— 每条都能在文件里核到。
> 第一部分 = 现状梳理(可当规范用);第二部分 = UED 视角的审计发现(待拍板)。
> 反推来源:`static/css/style.css`(741 行,唯一自有样式表)、`templates/fragments/{layout,nav}.html`、
> 75 个页面模板、7 个 JS(`lens.js` / `mobile-guide.js` / `searchable-select.js` / `lens-select.js` / `toc.js` / `goal-advise.js` / `kpi-info.js`)。

---

# 第一部分 · 设计理念现状

## 0. 定位隐喻(一切决策的源头)

`style.css:1-3` 写着:**"家庭账房 · Private Counting House · 一份起居室里的银行家账册"**。

这不是一句口号,它可验证地推导出了全站几乎所有视觉决策:

| 隐喻要素 | 落地表现 |
|---|---|
| 纸 | `--paper #F4EFE6` 米色底 + `body::before` 4 层噪点纹理 `mix-blend-mode:multiply` |
| 墨 | `--ink #1A1714` 近黑不纯黑,主操作按钮用墨底 |
| 账册细线 | **边框而非阴影**做主要分隔,`rule` / `rule-double` / `rule-triple-bottom`;圆角 v1.35 起:卡片 8px、按钮 / 输入框 6px、标签 4px(控件对齐主流规格,见 §2.1) |
| 铜活字 | `--brass` 铜金作唯一系统强调色 |
| 印章 | 首屏印章遮罩、报表"已关账"朱印 `.report-seal`(rotate -4deg) |
| 装订 | `dog-ear` 翻页角、`folio` 76px 斜体页码水印、`bracket-card` 四角装饰 |
| 打字机元信息 | 一切标签/表头/时间/版本号走 JetBrains Mono + uppercase + `letter-spacing .12~.18em` |

**判断新组件是否"对"的标准**:它像不像账册上本来就该有的东西。这条隐式标准解释了为什么全站没有一个 Material 阴影卡片、没有一个 20px 圆角、没有渐变按钮。

---

## 1. 视觉基础层

### 1.1 配色:三层结构,层与层不混用

**层 1 · 纸墨底(无彩,承载 95% 面积)**
```
paper F4EFE6 / paper-soft EDE6D7 / paper-deep E5DCC8   ← 页面底
card  FFFCF5 / card-soft FAF4E6                        ← 内容底
ink 1A1714 / ink-soft 5C5147 / ink-subtle A09486        ← 三级文字
rule C9BDA8 / rule-soft DBD0BC                          ← 两级分隔线
```
三级文字有明确分工:`ink` = 数据本体与标题;`ink-soft` = 说明文字;`ink-subtle` = 元信息(时间/序号/口径)。

**层 2 · 语义色(5 个,克制使用)**

| 色 | 值 | 唯一语义 | 反例(不许这么用) |
|---|---|---|---|
| brass | B08642 | 当前态 / 强调 / 可展开 / 链接 hover | 不表达"成功" |
| forest | 4F6B47 | 正数 / 收入 / 已完成 / WEALTH 类型 | 不表达"可点击" |
| rust | 9C4A2A | 负数 / 支出 / 危险 / 逾期 / LOAN 类型 | 不表达"重要" |
| slate | 3C4A5A | INSURANCE 类型专属(v0.17 引入) | — |
| plum | 5C3A4B | **已定义但全站未使用** | — |

**层 3 · 图表调色板(独立体系,与层 2 不共享)**
`lens.js:39-63` 定义 5 套双环配色方案(A 明快 / B 深调 / C 反差 / D 莫兰迪 / E 中式),管理页可配,**默认 D**。层 2 的 brass/forest/rust 不进图表分类色 —— 避免"绿色扇片"被误读成"正数"。

**跨类型的固定映射(全站不许出现第二种写法)**
```
LOAN → pill-rust + num-neg + 卡片 border-color:rust-soft
WEALTH → pill-forest    STOCK → pill-brass    INSURANCE → pill-slate
其余 → 默认 pill
```

### 1.2 字体:三套字体严格分工

| 字体 | 类 | 管什么 |
|---|---|---|
| Fraunces | `.font-display` | 标题 h1-h4、KPI 数值、印章字、folio 水印 |
| Source Serif 4 | `.font-serif` | 正文段落、说明文字(body 默认) |
| JetBrains Mono | `.font-mono` | eyebrow、field-label、pill、表头、时间、金额、版本号、按钮文案 |

**铁律:所有金额走 tabular-nums**(`.tnum` 或 `font-feature-settings:"tnum" 1`),保证列表纵向逐位对齐。`ledger-table` 整表默认开 tnum。

装饰性排印:`.dropcap`(首字下沉 3em brass)、`.swsh`(Fraunces 花体替换)、`eyebrow` 的间隔号排版 —— 标题上方永远有一行 `账 · 户 · 全 · 览` 式 mono 小字,这是全站最强的版式识别符。

### 1.3 字号:单变量驱动全站缩放

`--fs-scale`(1 / 1.15 / 1.30),由 `html[data-fs="lg"|"xl"]` 切换。设计约束是:**每一个写死的 px 字号都必须写成 `calc(Npx * var(--fs-scale,1))`**。

- 标准档 scale=1 → `calc(Npx*1)=Npx`,与改造前逐像素等价(零回归策略)
- 6 个 Tailwind arbitrary 字号类被 `!important` 重定义(`style.css:705-710`),压 Play CDN 生成的绝对值
- **控件本身不缩放**(`nav.html:46` 注释):字号选择器要当稳定参照物
- 移动输入框 16px 地板(`style.css:715-720`):`max(16px, calc(14px*scale))`,防 iOS 聚焦触发整页放大 —— 且**只在放大档生效**,标准档完全不动
- 图表跟随:CSS 够不到 canvas,靠 `window.chartFont(base)=round(base×scale)` + `fontscalechange` 事件重绘

### 1.4 卡片四语义

| 类 | 视觉 | 用在哪 |
|---|---|---|
| `paper-card` | card 底 + 1px rule + 8px 圆角(v1.35 · 原 2px)+ 极淡双层阴影 | 默认容器 |
| `paper-card-soft` | card-soft 底 + 0.5px 边 | 次级/嵌套 |
| `envelope` | **1px 墨边 + 4px 硬投影(paper-deep)**,0 圆角 | 重要弹层(信函感) |
| `bracket-card` | 1px 边 + 四角 14px brass 折角 | 提示 banner / 引导块 |

`envelope-modal` 额外在顶边内嵌 4px brass 横条(像封蜡)。

---

## 2. 组件与交互规则

### 2.1 按钮规格(spec)

> **v1.35 起整节改写**(维护者 2026-10-19:「为什么我们系统的按钮都那么大?还都是个方框」)。
> 旧规格是 JetBrains Mono 12px + uppercase + `.12em` 字距 + 12/20 内距 + 直角,一个按钮约 42px,
> 中文被拉成「添 加 持 仓」;规定里还写着「圆角 > 2px 破坏账册隐喻」。
> 新规格对标 shadcn/ui(取它新默认风格 nova 那一档的密度),数值与七个组件库的对比见
> [`docs/research/ui-frameworks-2026-10.md`](research/ui-frameworks-2026-10.md)。
> **账册隐喻留在纸张、配色、标题字体、eyebrow / 表头的排印上;控件按现代产品的规格走。**
> 实现全部在 `style.css` 按钮段;护栏 `v135-BTN-NO-CAPS` / `v135-BTN-STATES` / `v135-BTN-DANGER`,e2e flow 53 实测。

#### 2.1.1 选哪个按钮:决策树

```
这个操作会不可逆地删掉 / 改掉数据(删除、归档、彻底清除、断开、强制关账)?
├─ 是 → btn-danger(最后一步确认用 btn-danger btn-danger-fill)
└─ 否 ↓
   它是本区块用户最该点的那一个?
   ├─ 是 → btn-ink(每区块唯一)
   └─ 否 ↓
      它和别的操作并列、地位相当?
      ├─ 是 → btn-paper
      └─ 否 ↓
         它在列表行 / 表格单元格内,是密集的轻操作?
         ├─ 是 → btn-ghost
         └─ 否 → AI 入口 / 新能力的次操作 → btn-paper btn-accent(铜色软底);
                 真正要抢眼的新功能钩子 → btn-brass(全站 ≤3 处)
```

#### 2.1.2 变体

| 变体 | 底 | 字 | 边 | 语义 | 上限 |
|---|---|---|---|---|---|
| `btn-ink` | `--ink` | `--card` | 同底色 | 本区块主操作 | 每区块 1 个 |
| `btn-paper` | `--card`(白) | `--ink` | 1px `--rule-soft` + `--shadow-xs` | 并列次操作 | 不限 |
| `btn-ghost` | 透明(悬停 `--paper-soft`) | `--ink-soft` | 无 | 行内轻操作 | 行内 ≤3 个 |
| `btn-danger` | 透明(悬停 8% rust) | `--rust` | 1px 38% rust | 删除 / 归档 / 清除 / 断开 | 每行 ≤1 个 |
| `btn-danger btn-danger-fill` | `--rust` | `--card` | — | 不可逆的**最后一步**确认 | 每页 ≤1 个 |
| `btn-paper btn-accent` | `--brass-soft` | `--brass-text` | 无 | AI 入口、新能力的次操作 | — |
| `btn-brass` | `--brass-deep` | `--card` | — | 要抢眼的新功能钩子(旧版铜底米字对比度只有 3.2:1,改深铜 4.9:1) | 全站 ≤3 处 |

**删除用描边、不用实心红**:实心红会抢走 `btn-ink` 主操作的视觉焦点,删除永远不该是页面上最醒目的东西。实心只留给「打完确认语之后的最后一下」。

#### 2.1.3 共性规格

| 属性 | 值 | 为什么 |
|---|---|---|
| 字体 | `var(--ui-sans)`(苹方 / 微软雅黑 / Noto Sans SC 等系统无衬线) | 七个组件库没有一个按钮用等宽字体;中文在等宽字体里本来就回退到系统字体,和数字字母字重对不上 |
| 字号 / 字重 | 13px / 500(小档 12px);都乘 `--fs-scale` | 主流 14px,数据型产品取小一档 |
| 字距 / 大小写 | `letter-spacing: 0` · `text-transform: none` | 主流库源码里一个都没有;中文加字距只会被拉散 |
| 圆角 | 6px(`--r-md`) | 主流 4–8px |
| 布局 | `inline-flex` 居中 · `gap: 6px` · `white-space: nowrap` | 中文按钮被挤窄时会一字一行,宁可旁边的说明多折一行 |
| 高度 | `min-height`(不是 `height`) | 万一真要换行,按钮跟着长高而不是把字截掉 |
| 过渡 | 150ms 颜色 / 边框 / 阴影,按下 100ms | — |

#### 2.1.4 尺寸档:模板里给按钮写的字号类就是尺寸档

| 档 | PC | 手机(≤640px) | 怎么写 | 用在哪 |
|---|---|---|---|---|
| 小档 | 28px · 10px 内距 · 12px 字 | 32px | 按钮带 `text-xs` / `text-[10px]` / `text-[11px]` / `text-[12px]`(或 `btn-sm`) | 卡片内、列表行内、表单旁 |
| 默认 | 32px · 12px 内距 · 13px 字 | 36px | 不写 | 页头、区块操作 |
| 大档 | 40px · 20px 内距 · 14px 字 | 44px | `btn-lg` | 登录、引导这类大入口 |

- 为什么不新增 `btn-sm` 逐个改模板:模板里本来就用字号类表达「小一号」(160 多处),沿用它零改动就对;`:is(...)` 组合让优先级压过 Tailwind 的同名工具类。
- 和输入框并排的按钮可以写 `h-9`,跟输入框齐高(填报页输入行)。
- 模板给按钮加 `block` / `inline-block` 时自动换成 `flex` / `inline-flex`,文字照样竖直居中。

#### 2.1.5 状态(一次覆盖全部按钮类)

| 状态 | 规格 |
|---|---|
| `:hover` | ink / brass 加深;paper / ghost 换 `--paper-soft` 底;danger 出 8% 红底 |
| `:active` | `translateY(1px)` —— 手机没有 hover,这是唯一的按下反馈 |
| `:focus-visible` | `box-shadow: var(--ring)`(2px 卡片色间隔 + 2px 55% 铜色光圈) |
| `[disabled]` / `[aria-disabled=true]` / `.htmx-request` | 半透明 .5 · `cursor: not-allowed` —— 请求中的按钮看得出在等,不会被重复点 |

#### 2.1.6 图标与文字

- 图标一律 inline SVG,`stroke="currentColor"`(跟随按钮各状态变色),`aria-hidden="true"`
- 尺寸:小档 12px,默认 13–14px;和文字间距由按钮的 `gap: 6px` 给,不要再写 `mr-1`
- **纯图标按钮**必须有 `aria-label` + `title`,且 ≥32px

#### 2.1.7 排列与文案

- **主操作唯一**:每个区块只有一个 `btn-ink`。
- **一行最多 3 个按钮**,再多收进菜单(`details.row-more.menu-btn` + `btn-paper` 的 summary,见持仓页「添加其他 ▾」;菜单点外面 / 按 Esc 收起由 `js/menus.js` 管)。
- **卡片里的操作排成一行、同高**,不要竖着叠整宽按钮(v1.35 前的持仓卡就是三个整宽大框竖排)。
- **文案带后果**:危险操作写「动词 + 对象」(「归档此账户」而不是「归档」);不要用「计 · 算 · 对 · 照」这种手工字距。
- **耗时预告**:预期 >3s 的操作在按钮旁标时长(填报区的 `+15s`)。

#### 2.1.8 反例

| 反例 | 为什么错 |
|---|---|
| 按钮加 `uppercase` / `tracking-*` / `font-mono` | 回到旧规格;护栏和 e2e flow 53 会红 |
| `btn-paper` 加行内 `style="color:var(--rust)"` 表示危险 | 用 `btn-danger`(护栏 `v135-BTN-DANGER`) |
| 同一区块两个 `btn-ink` | 主操作失去唯一性 |
| 一排 5 个按钮 | 收进菜单 |
| 字号写死 `font-size:12px` | 绕过 `--fs-scale`,字号档位失效 |
| 用 `<div onclick>` 当按钮 | 键盘不可达,无原生 disabled |

---

### 2.2 标签规格(spec)

> **v1.35 · 标签外观改了(本节下面的分类、语义映射仍然有效)**:`.pill` 去掉硬边框、等宽字体、大写和 `.1em` 字距,
> 改成**软底圆角小标** —— 圆角 4px(`--r-sm`)· 无衬线 11px / 500(手机 10px)· 边框透明(`!important`,
> 压过模板里行内写的 `border-color`)· 软底 + 字色表达类型。
> - 模板里给 pill 写的 `text-[9px]` / `text-[10px]` 一律被压到 11px(9px 中文在手机上几乎读不出来)。
> - `pill-mute` 是「描边空心」那一种,保留细边。
> - 代码类(股票 / 基金代码)加 `font-mono` 或 `data-fund-code-pill` 仍走等宽。
> - **和大字放在同一行时按竖直中线对齐**:`.items-baseline > .pill { align-self: center }`。
>   按基线对齐时小标签会比旁边的大字低几像素(v1.35 填报卡「华泰证券-A股 · 本月已校准」就是这么错的,护栏 `v135-ACCT-HEAD-CENTER`)。
> - 当按钮用的 pill(筛选 chip / 分段 tab)有悬停底色和键盘光圈;**挂在 `<select>` 上的 pill 补回细边**,否则看不出能点。
> - 模板里手搓的带框小 span 自动得 4px 圆角,带框的链接 / 按钮 / summary / label 自动得 6px(`style.css` 末尾「控件收口」)。

现状是**一个 `.pill` 类承担了四种语义完全不同的角色**,靠行内 style 和尺寸覆写勉强区分(64 处覆写 `.pill` 基类字号)。规格上必须先把四类拆开 —— 它们的**行为**不同,不只是颜色不同。

#### 2.2.1 四类标签(核心区分)

| 类型 | 语义 | 可点? | 色彩来源 | 举例 |
|---|---|---|---|---|
| **分类标签** Category | 这条数据"是什么" | 否 | 由数据的枚举值**固定映射** | `现金 (CASH)`、`货币基金`、`风险 ★★` |
| **状态标签** Status | 这条数据"处于什么状态" | 否 | 由状态语义决定 | `已入账 + 流水`、`我未填`、`本期未填收支`、`UNPENETRATED` |
| **筛选标签** Filter | 用户"要看什么" | **是** | 选中 = `pill-ink`,未选 = 基类 | 账户页类型筛选行、`全部` |
| **计数标签** Count | 附属数量 | 否 | 永远 `ink-subtle`,无边无底 | `3个`、`· 11 个账户` |

**这四类现在都是 `<span class="pill">`,视觉上不可区分** —— 尤其筛选标签(可点)和分类标签(只读)长得一模一样,用户不知道哪个能点。这是标签体系最大的问题。

#### 2.2.2 分类标签:色彩固定映射表(不许出现第二种写法)

| 枚举值 | 变体 | 色 |
|---|---|---|
| `LOAN` | `pill-rust` | rust |
| `WEALTH` | `pill-forest` | forest |
| `STOCK` | `pill-brass` | brass |
| `INSURANCE` | `pill-slate` | slate |
| `CASH` / `CRYPTO` / `METAL` / 其余 | 基类 | paper-soft 底 + ink-soft 字 |

**加新枚举值时必须同步这张表** —— 项目已有教训(v0.14 加 `METAL` 时漏了模板里的硬编码类型判断,上线才发现)。

#### 2.2.3 状态标签:语义色映射

| 状态性质 | 变体 | 举例 |
|---|---|---|
| 完成 / 正向 / 已生效 | `pill-forest` | `已入账 + 流水`、`我已填` |
| 缺失 / 逾期 / 异常 | `pill-rust` | `我未填`、`本期已过截止日` |
| 进行中 / 当前 | `pill-brass` | 账期 `2026 · 05 · OPEN` |
| 中性 / 未启用 / 无数据 | `pill-mute`【待补定义】 | `本期未填收支`、`UNPENETRATED` |

**`.pill-mute` 是死类名**:3 处模板在用(`nav.html:48` 字号钮、`nav.html:59` 隐私钮、`dashboard/_region.html:216` 本期未填收支),但 `style.css` 里**没有定义** —— 这些元素现在只吃基类样式,作者想要的"弱化"语义丢失了。

```css
/* 待补 · 补上死类名 */
.pill-mute {
  background: transparent;
  border-color: var(--rule-soft);
  color: var(--ink-subtle);
}
.pill-mute:hover { border-color: var(--rule); color: var(--ink-soft); }  /* 仅当它是按钮时 */
```

#### 2.2.4 筛选标签:必须与只读标签视觉可区分【待补】

三态规格:

| 态 | 视觉 |
|---|---|
| 未选中 | 基类 + `cursor:pointer` + **hover 时 border 变 `--brass`**(暗示可点) |
| 选中 | `pill-ink`(墨底反白) |
| 选中且可清除 | `pill-ink` + 尾部 `×`(12px SVG),点 `×` 单独移除该条件 |

```css
/* 待补 · 可点标签的 affordance */
.pill-filter { cursor: pointer; transition: border-color 150ms ease, color 150ms ease; }
.pill-filter:hover { border-color: var(--brass); color: var(--brass-deep); }
.pill-filter:focus-visible { outline: 2px solid var(--brass); outline-offset: 2px; }
```

现状筛选行(`accounts/index.html:40-47`)是 `<a class="pill">`,没有 hover 反馈也没有 focus 态 —— 只有选中项的 `pill-ink` 能看出"这里可交互"。

#### 2.2.5 三档尺寸(收敛 64 处覆写)

现状:`text-[9px]` 37 处、`text-[10px]` 22 处、`text-xs` 5 处,全都在覆写基类的 10.5px。等于基类尺寸没人用。规格化:

| 档 | 类 | 字号 | padding | 用在哪 |
|---|---|---|---|---|
| xs | `pill-xs`【待补】 | 9px | 2px 7px | 卡片右上角类型角标、次级附注 |
| sm(默认) | `pill` | 10.5px | 4px 10px | 列表行、筛选器、状态标签 |
| md | `pill-md`【待补】 | 12px | 5px 12px | 页头账期、KPI 旁强调 |

```css
/* 待补 · 尺寸档,替代散落的 text-[9px]/text-[10px] 覆写 */
.pill-xs { font-size: calc(9px * var(--fs-scale,1));  padding: 2px 7px; }
.pill-md { font-size: calc(12px * var(--fs-scale,1)); padding: 5px 12px; }
```

#### 2.2.6 标签共性规格

| 属性 | 值 |
|---|---|
| 字体 | JetBrains Mono |
| 字距 | `letter-spacing: .1em`(比按钮的 .12em 略紧) |
| 圆角 | 4px(`--r-sm`,v1.35 · 原 1px;按钮 6px、卡片 8px) |
| 边框 | 恒 1px(即便同色)—— 保证与纯文字区分 |
| 布局 | `inline-flex; align-items:center; gap:6px` |
| 内嵌图标 | 11px SVG,`stroke="currentColor"` |
| 换行 | 长文案加 `whitespace-nowrap`,容器负责 `flex-wrap` |

#### 2.2.7 计数标签:不用 pill

数量附注(`3个`、`11 个账户`)**不套 `.pill`** —— 无边无底,只用 `font-mono text-ink-subtle`。理由:计数不是"状态",给它加边框会与真正的状态标签抢注意力。现状已经是这么做的,写进规格防止后来人"顺手加个 pill"。

#### 2.2.8 反例(不许这么做)

| 反例 | 为什么错 |
|---|---|
| 用 `pill-forest` 表示"可点击" | forest 已占用"正向/完成"语义 |
| 分类标签自己选颜色(不查映射表) | 同一类型在两个页面显示不同色 |
| 筛选标签与分类标签同样式 | 用户不知道哪个能点 |
| 用行内 style 造新变体色 | 已有 5 变体够用;真需要新语义应加类并进本表 |
| 计数套 `.pill` | 与状态标签抢视觉权重 |
| pill 内放两行文字 | pill 是单行原子,多行内容用 `paper-card-soft` |

---

### 2.2b 表单标签(field-label)—— 规范失效最严重的一处

`.field-label` 在 `style.css:260-268` 定义得很完整(mono / 10.5px / `.14em` 字距 / uppercase / `ink-soft` / `margin-bottom:6px`),但:

- **只有 13 个模板在用这个类**
- **有 237 处手写等效样式**(`font-mono text-[10px] ... uppercase text-ink-subtle mb-1` 各种排列组合)

后果是标签样式没有单一来源:字号在 10px / 10.5px 之间飘,颜色在 `ink-soft` / `ink-subtle` 之间飘,间距在 `mb-1`(4px)/ `mb-1.5` / 6px 之间飘。改一次全局标签样式需要动 237 处。

**规格**:所有表单字段标签一律用 `.field-label`。需要变体时加修饰类,不手写:

```css
/* 待补 · 标签变体,替代手写 */
.field-label-inline { display: inline-block; margin-bottom: 0; margin-right: 8px; }  /* 行内紧凑表单 */
.field-label-req::after { content: ' *'; color: var(--rust); }                        /* 必填标记 */
```

这项不影响外观(等效样式本来就一样),纯粹是可维护性 —— 但 237 处的量级意味着它是全站最大的"复制粘贴债"。

### 2.3 图标:inline SVG,零 emoji

硬纪律:Feather 风格 `viewBox="0 0 24 24"` + `stroke="currentColor"` + `stroke-width=2` + `fill=none` + `aria-hidden="true"`。
`stroke=currentColor` 是关键 —— 图标自动继承 `btn-ghost` 的 hover 变色,不需要为图标单独写状态。

尺寸阶梯:9/11/12/13(行内)、14/15/16/17(按钮/提示)、38(FAB 容器)。

JS 动态生成的图标也守这条:`mobile-guide.js:48-65` 有个 `icon(d, size, stroke)` 工厂 + `I` 图标常量表,`document.createElementNS` 造 SVG,**不用 innerHTML**(XSS 纪律)。

### 2.4 表格:`ledger-table`

- 表头:mono 10px uppercase `letter-spacing .16em` + **下边框 1px `--ink`**(不是灰线 —— 账册总账线)
- 数值列 `.num`:右对齐 + mono + tabular-nums
- 行 hover → `card-soft` 底
- 单元格 padding 14px,行分隔用 `rule-soft`(比表头线弱)
- 排序:`.sortable-th` + `aria-sort` 三态,三角用 **CSS border 绘制**(不用字形/emoji),未排序 opacity .28,已排序 brass-deep

### 2.5 表单

**高度阶梯有语义**:`h-8` 密集行内(快捷支出/划转)· `h-9` 常规(本期余额/收入)· `h-11` 页面重点(本月总支出)。

细节处理(做得好的地方):
- `onfocus="this.select()"`(`_row.html:123`)— 移动端聚焦即全选,直接覆写不用先删
- 行内 ✕ 清空钮(绝对定位在输入框右侧,`tabindex="-1"` 不进 Tab 序)
- `data-require-when="控件名=值"`(`layout.html:421-447`)— 声明式条件必填,radio/checkbox/其它统一取值
- `data-searchable`(`searchable-select.js`)— 任何 select 加这个属性自动升级为可搜索下拉
- 参考值不进输入框而作独立 caption:`参考 · 上期末 ¥45,000`(避免"预填"被误认为"已填")

### 2.6 反馈体系(4 层,层层兜底)

| 层 | 机制 | 时长/触发 |
|---|---|---|
| 页面级 | 顶部 3px 渐变进度条 + shimmer 流光 | 链接点击 / 表单提交 / **所有 HTMX 请求**都 rearm |
| 首屏 | 中央印章遮罩(描边→填充→"账"字→朱点→脉冲) | 1.2s(完整动画周期)· 3.5s 硬兜底 |
| 全局消息 | toast 4 级 · 顶部居中 · 4s 淡出 | `HX-Trigger=showToast` 或 `window.showToast()` |
| 行内 | `htmx-indicator` 旋转 loader + `hx-disabled-elt="this"` | 请求期间 |

toast 配色:`error→rust` / `warn→brass` / `info→ink` / `success→forest`,2px 边框 + shadow-2xl。

**HTTP 错误分级兜底**(`layout.html:164-208`)—— 这套分级是全站最成熟的错误设计:
```
401 → 直接跳 /login?expired=1
403 → toast「登录态可能已过期,请刷新页面后重试」
404 → toast「该数据不存在或已被删除」
4xx → 若无 HX-Trigger 才兜底 toast(不覆盖业务已给的具体消息)
5xx → toast + console 留痕,【明确不跳 /error】—— 避免丢失填报现场
```
最后那条注释是真正的 UED 决策:填报页填了一半遇到 5xx,跳错误页 = 数据全丢。

### 2.7 空状态:一律"状态 + 下一步",不写"暂无数据"

46 处 `isEmpty` 判断,文案抽样:
```
还没录收入 · 工资 / 股息 / 利息… 逐笔加,直接入对应账户。
还没有收支数据 · 在填报页填「本月收入 / 支出」后,这里出现收支趋势曲线。
暂无该账户的历史数据 · 请先在「填报」录入至少一期月末余额
还没有开账期 —— 记账 / 报表 / 资产体检都用不了。
暂无风险等级数据 · 给账户设置产品类目后可见
```
**这是全站一致性最好的一项**:每条空状态都说清"缺什么 + 去哪补"。容器多为虚线框(`border-dashed`,12 处)。

### 2.8 危险操作:原生 confirm + 后果三段式

11 处 `confirm()`。文案质量分层明显,最佳实践是 `admin/periods.html:86`:
```
确认强制关闭周期 X?
\n\n未完成填报的账户将自动延续上期末余额作为本期值,系统会代所有成员签收并触发指标重算。
\n\n此操作不可撤销(可后续通过重新打开恢复)。
```
= **做什么 / 影响谁 / 可否撤销** 三段。删除类文案也带影响说明:"确定删除这条流水?余额会自动反向冲销。"

---

## 3. 列表逻辑

### 3.1 三段式结构(全站统一)

```
汇总带  →  筛选器  →  主体
```
- **汇总带**:`grid gap-px bg-rule` + 子项 `bg-card` —— 用 1px 网格间隙露出底色当分隔线,不写 border。移动 2 列 / PC 6 列
- **筛选器**:一行 pill,当前项 `pill-ink`。全部走 URL 参数(`?type=&archived=`),**无前端状态** —— 可分享、可后退
- **主体**:PC 表格 / 移动卡片

### 3.2 按人分组是第一优先级

不按类型、不按金额,而是按**主理人**(`ownerGroups`)。理由符合产品定位:家庭协作,先分清"这是谁的账"。

- avatar 首字圆:`ownerColorMap.get(name) % 5` → `.avatar-0..4` 五个固定色(`style.css:331-335`)
- **同一人全站同一色**(填报页/账户页/收入流水/划转下拉共用同一 map)
- 无主理人 → `avatar-4` slate 灰 + 首字"共"(共同账户)
- 分组头:PC 表格用 `colspan` 整行 `bg-card-soft` + `border-t-2`;移动用 avatar + 名 + `h-px flex-1 bg-rule` 延伸线

### 3.3 成员 tab 锚点导航(替代分页与搜索)

```html
<nav class="... sticky top-[68px] z-20 bg-paper py-2 border-b border-rule-soft">
  <a href="#owner-迪娃"> [avatar] 迪娃 3个 </a>
```
- `sticky top-[68px]` = nav h-16(64px) + `top-1`(4px)
- 目标锚点配 `scroll-mt-32`(PC 表格 `scroll-mt-20`)补偿吸顶遮挡
- **纯 CSS + 原生锚点,零 JS**

### 3.4 双写 DOM:PC 表格 / 移动卡片

`hidden md:block`(表格)+ `md:hidden`(卡片),同一数据两套完整 DOM。
移动卡片的信息层级:`avatar → 名称(可点)→ 序号·主理人 → 类目/风险 pill → 类型 pill(右上)→ 分隔线 → 余额 → 操作按钮行`。

### 3.5 数值的语义化标签

同一个字段按账户类型换措辞:LOAN 显示"本期欠款",其余显示"本期末"(`accounts/index.html:261`)。金额同时套 `num-neg` + `data-priv`。

---

## 4. 翻页逻辑(重要发现:全站基本不分页)

75 个模板、46 处列表,**只有 2 处真分页**:
- `admin/periods.html:97-107` — 每页 24,新→旧
- `admin/notification.html:233-246` — 通知日志

其余全部**全量渲染**,用三种手段替代翻页:
1. 折叠(`<details>` 9 处,展开态文案纯 CSS 切换:`details[open] > summary .lg-when-closed{display:none}`)
2. 锚点跳转(成员 tab / 长文目录)
3. URL 筛选(类型 / 归档 / 成员 / 账期)

**分页组件约定**(仅那 2 处,但约定值得沿用):
```
共 88 期 · 第 1 / 4 页(每页 24 · 新→旧)          ← 位置感 + 排序说明
← 上一页(更新)          下一页(更旧) →           ← 方向带语义,不只是"上/下一页"
禁用态:<span> 而非 <a> + opacity-40/30 + cursor-not-allowed
```
`← 上一页(更新)` 这个写法是对的:时间序列列表里"上一页"到底是更新还是更旧,不标注一半用户会猜错。

---

## 5. 导航与信息架构

### 5.1 顶层 7 tab
`仪表盘 / 填报 / 账户 / 报表 / 目标 / 资产体检 / 管理`

- 当前态:`text-ink + border-b-2 border-brass + pb-[18px]` —— 下划线下沉到 header 底边,像账册标签页
- 非当前:`text-ink-soft` + `hover:text-ink`(**不换底色,只换文字色**)
- 徽记:填报带 `AI` 角标(brass-soft 底)+ 待办数 `·2`(rust)
- header `sticky top-1`(不是 top-0)—— 上方那 1px 是 `fixed h-1 bg-ink` 装饰墨条,每页都有

### 5.2 品牌区信息密度
logo + 家族简称 + `№ 张`(账册编号感)+ **版本徽记** `◇ v1.5.2`(brass-deep 45° 旋转小方块 + mono 9px)。版本号常驻可见 = beta/prod 一眼分辨。

### 5.3 PC 与移动的导航形态完全不同
- PC:横排 7 tab + 右侧工具区(字号下拉 / 隐私眼 / 账期 pill / 用户名 / avatar / 退出)
- 移动:`☰` → `fixed inset-x-0 top-16` 全屏下拉,`divide-y` 分隔的纵向列表,底部附字号档位行 + 当前状态行("─ 当前 · Alice · OPEN")

移动菜单里补了 PC 常驻但移动隐藏的信息(用户名 + 账期),这是对的 —— 不是简单砍掉。

### 5.4 长文目录(reports / dashboard / checkup)
- `≥1024px`:左侧 sticky rail(224px,`top:88px`),树状两级,`aria-current="true"` 作样式钩子(brass 文字 + 左脊 + 底色)
- `<1024px`:左下 FAB(38px 圆钮)→ 底部 sheet(`max-height:72vh`,`translateY(110%)→0`,`cubic-bezier(.22,1,.36,1)`)
- **DOM 顺序内容优先,靠 `order:-1` 视觉左移** —— 阅读/无障碍顺序不变
- 布局不依赖 Tailwind `lg:` 前缀,自己写 media query(`style.css:409` 注释:"重页 JIT 不稳")
- sheet 打开时 `body.toc-open` 隐藏左下浮钮,避免压住底部条目
- sheet 内条目 `min-height:44px`(**全站唯一显式满足 44px 触摸标准的地方**)

---

## 6. 两端差异总表

| 维度 | PC(≥768px) | 移动(<768px) |
|---|---|---|
| 主导航 | 横排 7 tab + 底边 brass 指示 | `☰` 全屏下拉,纵向 divide-y |
| 列表主体 | `ledger-table` 表格 | `paper-card` 卡片(独立 DOM) |
| 字号控件 | nav 内 `<details>` 下拉 | 汉堡菜单内一行三钮 |
| 隐私开关 | nav pill | 左下浮钮(bottom:18px) |
| 长文目录 | 左侧 224px sticky rail | 左下 FAB + 底部 sheet |
| KPI 卡 | padding 24 / 值 30px | padding 16-15 / 值 24px |
| KPI 口径面板 | 绝对定位跟随按钮 | `position:fixed` 钉屏幕顶 5rem |
| 图表 | 字号 10-11px / padding 80 / x 轴不旋转 | 字号 8-9px / padding 10 / x 轴 rotate 34° |
| 交叉表 | 完整宽度 | 首列 sticky + 阴影 + 可关闭横滑提示 |
| 表格列 | 全列 | 部分列 `lg:table-cell` / `md:table-cell` 隐藏 |
| PWA 引导 | 静默 | iOS 整屏强引导(两段挽留) |
| 断点用量 | `md:grid-cols-` 42 次 / `md:col-span-` 38 次(主要是重排) | `md:hidden`+`md:block/flex/inline` 仅 15 次(少量替换) |

最后一行是关键:**移动适配主要靠网格重排(响应式),而非组件替换(适配式)**。只有列表主体和导航走了"替换"路线。

---

## 7. 移动端专属设计

### 7.1 PWA 强引导(`mobile-guide.js`,344 行 —— 全站最重的移动干预)

- iOS Safari → 整屏遮罩(`rgba(20,16,12,.86)` + blur 3px)+ **成果图**(iPhone 黑框包主屏截图)+ 3 条价值点 + "看怎么装 · 4 步真机图"
- iOS 微信 → 先转 Safari,右上角配**动画箭头**(`@keyframes gArrow` 上下浮动,虚线路径指向 `⋯`)
- **两段挽留**:想关闭 → 第 1 次"真的用浏览器?" → 第 2 次"装一次,只要 20 秒" → 才放行,写 `snooze` 3 天
- 已装 standalone → **完全静默**;安卓/桌面 → 静默(注释:"本次只强推 iOS · 安卓另议")
- 全程渐进增强:localStorage 不可用时降级成空实现,不报错
- 开发逃生门:`?reset_pwa=1` / `?reset_wx=1`

### 7.2 左下浮钮操作区(z 55 / 60)
隐私眼(bottom:18px)+ 目录 FAB(bottom:66px)。两者互不遮挡,sheet 打开时一起隐藏。
隐私态时眼睛 chip 展开成文字:"金额已隐藏 · 长按可看 · 点我恢复" —— 状态自解释。

### 7.3 触摸热区扩张而不改布局
```css
.kpi-info-btn::after, .tap::after { content:''; position:absolute; inset:-12px; }
```
视觉 14px 图标 → 热区约 38px,`::after` 不占布局流。2026-07-19 加的,是正确解法。

### 7.4 交叉表的移动取舍(v1.5.2)
不做重排、不做假横屏(`screen.orientation.lock` 在 iOS Safari/PWA 不支持),而是:
可 `×` 掉的提示("左右滑动查看更多列 · 首列固定 · 横屏更清晰",`sessionStorage.pivotHintX` 本会话不再打扰)+ 首列 sticky 右缘 `box-shadow` 暗示"右边还有内容"。

### 7.5 横滑内容的"还有更多"提示
`.lens-hscroll::after` — 右缘渐隐 + `›` 箭头,**溢出且未滑到底时**才 `opacity:1`,同时隐藏原生滚动条。这个模式应该推广到所有横滑区。

---

## 8. 数据可视化规则

1. **强制 datalabels**:金额/百分比必须画在扇片/柱顶/数据点上,hover tooltip 不算(项目硬纪律)
2. **5 套调色板后台可配**(`lens.js:39-63`),每套含内环/外环两组 10 色,默认 D 莫兰迪
3. **热力与盈亏色不用红绿二值**:`pnlColor()` / `rateColor()` 从中性纸色 `#d8cfba` 向 forest/rust **双向插值**,强度 = |值|/max。零值 = 纸色而非白色
4. **隐私模式重绘**:`togglePrivacy()` 遍历所有 canvas 调 `chart.update('none')` —— 形状不动,只重算标签(`formatter` 内查 `window.isPrivacy()`)
5. **字号跟随全局档位**:`chartFont(base)`,监听 `fontscalechange` 重绘
6. **移动端全参数降级**:字号、padding、legend 位置、x 轴 rotate 34°,统一 `window.innerWidth < 640` 分支
7. 图表容器统一 `.chart-card`(card 底 + 1px rule + 24px padding),与 `paper-card` 同族但不带阴影

---

## 9. 隐私模式(全站横切关注点)

设计精髓在**标记粒度**:
- `data-priv` 只标**绝对金额** → `html.privacy` 下 `blur(7px)` + 禁选禁复制禁长按菜单
- **比例/百分比不标记** —— 不敏感,且模糊后图表会失去可读性

工程细节:
- FOUC 防闪:`<head>` 内 DOM 构建前读 `sessionStorage` 设 class(`layout.html:14`),避免明文闪一下
- 长按 peek:320ms 阈值 + 10px 移动容差(区分滚动)→ 临时去模糊,松手复原,并**抑制那一次 click**(防误跳转)
- 两个入口零 JS 同步:都只 toggle `html.privacy`,眼睛图标 eye/eye-off 纯 CSS 跟随
- iOS PWA 特修:隐私态给所有 `<a>` 关 `-webkit-touch-callout`(长按被遮金额会触发链接预览小窗)

---

## 10. 动效规范

| 场景 | 时长 | 曲线 |
|---|---|---|
| ghost hover | 150ms | ease |
| 常规 transition(边框/背景/颜色) | 180ms | ease |
| sheet 滑入 | 260ms | `cubic-bezier(.22,1,.36,1)` |
| 进度条推进 | 350ms | `cubic-bezier(.22,.9,.22,1)` |
| 首屏遮罩淡出 | 550ms | `cubic-bezier(.22,.9,.22,1)` |
| 入场 fade-up(6px 上移) | 600ms | `cubic-bezier(0.2,0.8,0.2,1)` |

入场阶梯:`.fade-in-delay-1..4` = 100/200/300/400ms,逐块揭示。
所有曲线都是**减速为主**(末段慢),符合"纸张落定"的物理感。

---

## 11. z-index 层级(现状散乱,建议归档)

现状实际取值:`1, 2, 3, 10, 20, 30, 40, 50, 55, 60, 61, 70, 80, 9998, 9999, 10000, 10050`

已知占位:
```
1        纸张纹理 body::before / 表格 sticky-col
10       main 内容
20       成员 tab sticky
30       nav header
40       顶部墨条 / 移动菜单下拉
50       scrim 遮罩 / kpi-info-panel
55       隐私浮钮
60       目录 FAB
70       目录 sheet 遮罩
80       目录 sheet
9998     首屏印章遮罩
9999     顶部进度条
10000    toast / 引导截图层
10050    (待查)
```

---

## 12. 文案与语气规范

1. **中文为主,code 为辅**:`现金 (CASH)` —— 面向家庭成员不裸露 code(2026-07-19 评审结论)
2. **不用技术词**:"数据源接入" 而非 "API 集成";"流水档案" 而非 "交易日志"
3. **eyebrow 间隔号排版**:`填 · 报 · 月 · 度`、`账 · 户 · 全 · 览`、`第 · 一 · 步 · 本 · 月 · 收 · 支`
4. **口径透明**:KPI 旁 `?` 展开双层 —— 公式 + **真实代入数值**(`kpi-info-formula` / `kpi-info-calc` 虚线分隔)
5. **耗时预告**:填报区标 `+15s`;PWA 引导写"装一次,只要 20 秒"
6. **流程定位**:填报页顶部常驻 `开周期 → 填本期余额(本页) → 关周期 → 出报告`,当前步加粗
7. **告知降级路径**:"识别不准或失败都能随时手动改,不影响填报" —— AI 功能旁必须写兜底

---

# 第二部分 · UED 审计发现(待拍板)

## P0 · 影响可用性

### 1. 移动端触摸目标普遍不足 44px

- `btn-ghost` = `padding 8px 14px` + 11.5px 字 → **实际高度约 30px**
- 移动卡片操作行(`accounts/index.html:264-316`)一排 **6 个** `btn-paper text-[10px]` / `btn-ghost text-[10px]`,高度更小、彼此间距 `gap-2`(8px)
- 全站显式满足 44px 的只有一处:目录 sheet 条目(`style.css:452`)
- 已有正确解法(`.tap::after { inset:-12px }`)但只用在 KPI 的 `?` 图标上

**建议**:给移动端的 `btn-ghost` / `btn-paper` 加 `min-height:40px` 或统一挂 `.tap`。这是唯一会让用户"点不中"的问题,优先级最高。

### 2. `☰` 是假 affordance(暗示可拖拽,实际不可)

`accounts/index.html:93` 顺序列渲染 `001 ·☰`。`☰` 在几乎所有 UI 语境里 = 拖拽手柄,但全站**没有任何拖拽排序实现**(`grep draggable|Sortable|dragstart` 无结果),排序只能去编辑页填 `displayOrder` 数字。

**建议**:二选一 —— 去掉 `☰` 只留序号;或真的实现拖拽。现状是明确的误导。

---

## P1 · 一致性与可维护性

### 3. emoji 残留,违反项目自身纪律

项目纪律明令"不许 emoji,一律 inline SVG",但扫出:

| 字符 | 位置 |
|---|---|
| 🚀 | `admin/periods.html`(2 处) |
| 🗄 | `stock/holdings.html:236`、`goals/detail.html:23` |
| 💰 | `stock/holdings.html` |
| ✨ | `checkup/_ai-diagnose.html`(3)、`goals/new-retirement.html` |
| ⚠ | `checkup/_advice-card.html`(2)、`help/broker-sync.html`、`goals/new-emergency.html` |
| ✓ | `admin/index.html`、`admin/backup.html`、`goals/new-*.html` |
| ✕ | `holdingimport/import.html`(10)、`reports/_rebalance-plan.html` |
| ⟳ | `entry/_row.html:77`(刷新按钮,纯文字符号) |

其中 `✓` `⚠` 是纪律里点名禁止的。`★`(风险星级,约 50 处)、`☰`、`↔`、`↳`、`↱`、`←`/`→` 我倾向算**排版符号**而非 emoji,可保留 —— 但边界需要你明确一次,否则下次又会争论。

**建议**:①把 🚀🗄💰✨⚠✓✕ 全换 SVG;②`⟳` 换 Feather `refresh-cw`(同页 250-273 行已有现成的);③在 `qa-run.sh` 加一条 emoji 扫描守护,防回归。

### 4. 输入框两套体系并存

- `.field-input` **下划线式**(focus → 2px brass 底线):10 个模板,集中在设置/编辑/向导
- `border border-rule bg-card/bg-paper` **方框式**:27 处,集中在填报/操作页

两者都在 style.css 里有定义(前者)或被反复手写(后者)。同一用户在"编辑账户"和"填报"看到的输入框长得不一样。

**建议**:承认这是**有意的语义分层**(表单页 = 下划线信笺感 / 操作页 = 方框控件感),并写进规范;或统一。现在是"没人决定过"的状态。

### 5. 双写 DOM 已经产生功能不一致

`accounts/index.html` PC 表格(67-198)与移动卡片(200-319)是两套独立 DOM。实测差异:

| 操作 | PC | 移动 |
|---|---|---|
| 流水档案 / 体检 / 编辑 / 券商 / 划转 / 归档 | 有 | 有 |
| **账本 CSV 导出** | 有(153-158) | **无** |

一处漏了就是漏了,编译器抓不到。

**建议**:把行内操作抽成一个 fragment(`accounts/_row-actions.html`),两端传不同尺寸参数复用。至少能保证操作集合一致。

### 6. 危险操作用原生 `confirm()`,与设计语言割裂

11 处 `confirm()`。原生弹窗是系统样式:无 paper 底、无 mono 字、无 envelope 硬投影、移动端还会带域名前缀。而项目已经有 `.envelope-modal`(带封蜡横条)完全没用在这个场景。

文案质量本身很好(三段式后果说明),但 `\n\n` 换行在原生弹窗里是唯一的排版手段,写不出层次。

**建议**:做一个 `confirmEnvelope(opts)` 统一弹层(复用 `.envelope-modal` + `.scrim`),支持"做什么/影响谁/可否撤销"三段结构 + 危险操作按钮用 rust。改造成本一次,收益覆盖 11 处。

### 7. 列表操作列 7 个等权按钮,无主次无收纳

PC 表格操作列:`划转 / 流水档案 / 体检 / 账本 / 编辑 / 券商 / 归档` —— 7 个 `btn-ghost`,同字号同色同权重,横向挤在一列。移动卡片同理 6 个平铺。

按频率,`编辑` 和 `流水档案` 是日常操作,`券商` / `账本 CSV` / `归档` 是低频。

**建议**:高频 2-3 个留在行内,其余收进 `⋯` 下拉(`<details>` 就能做,全站已有 9 处该模式)。

---

## P2 · 体验优化 / 需要你拍板的取舍

### 8. 首屏印章遮罩 1.2s 强制等待

`layout.html:348` — `load` 后固定等 1.2s 才淡出("给印章动画一个完整周期")。

产品硬约束是"每月 10 分钟",填报页是高频入口。首次访问的品牌仪式感值这 1.2s,但**每次导航都付一遍**就是纯摩擦(尤其填报页跳转频繁)。

**建议**:首次会话完整播;同会话内后续导航缩到 300ms 或跳过(`sessionStorage` 标记)。

### 9. 正负数用绿正红负(国际财务惯例),不是红涨绿跌

`--forest` = 正 / `--rust` = 负,全站一致(`num-pos` / `num-neg` / 图表插值)。

中国用户的股市直觉是**红涨绿跌**,与此相反。这在"持仓收益""区域盈亏热力图"上最容易被误读 —— 用户可能把红色单元格看成"赚了"。

这是取舍不是 bug(财务报表语境下绿正红负是对的),但**需要你明确一次**。若要保留,可考虑在盈亏类图表旁加一次性图例说明。

### 10. 无分页的长期风险

全量渲染在家庭规模(20-40 账户)是对的。但两个信号:
- 账期表已经被迫加了分页(88 期)—— 时间序列必然增长
- `accounts/detail.html` 跨期流水档案按月分组,用满 5 年后是 60 组

**建议**:不用现在做分页,但给"按月/按年"的时间序列列表定一条规则(例如默认只渲染最近 12 期 + "加载更早"),避免下次被迫打补丁。

### 11. z-index 无登记表

17 个不同取值散落在 CSS / 模板行内 style / JS 字符串三处。`60` 和 `61` 并存说明已经在"往上加 1"了,这是失控前兆。

**建议**:在 `style.css` 顶部 `:root` 旁写一份 6 档常量注释表(内容 10 / 吸顶 20-40 / 遮罩 50 / 浮钮 55-60 / 抽屉 70-80 / 系统层 9998+),新代码只许用档位值。

### 12. 应用内不尊重 `prefers-reduced-motion`

只有 `landing.html` 做了(`@media(prefers-reduced-motion:reduce){ .reveal{animation:none} }`)。应用内的 fade-up 入场、印章遮罩、shimmer 流光、sheet 滑动、`animate-spin` 都不受控。

**建议**:在 `style.css` 加一个全局块,`reduce` 时把 `.fade-in*` / 印章动画 / shimmer 关掉(功能性 loader 可保留)。一次 10 行,覆盖全站。

### 13. 表单错误回显只有 1 处规范

`profile/password.html:17` 有正规的行内错误块(rust 边框 + rust 底 + rust 字)。其余表单错误全靠 toast 或 HTML5 原生校验气泡 —— toast 4 秒后消失,用户回头改字段时已经看不到错在哪。

**建议**:把 `profile/password.html:17` 那个块提成 `fragments/_form-error.html`,所有 POST 表单页复用;字段级错误加 `border-rust` 高亮。

### 14. 无障碍属性偏薄

`aria-hidden` 83 处(图标,做得好)、`aria-label` 22、`role` 6、`aria-expanded` **仅 3**。

`<details>` 被当菜单/下拉用(nav 字号菜单、汉堡、折叠帮助),原生 `<details>` 语义是"披露",屏幕阅读器读作"摘要/详情"而不是"菜单"。键盘可用(原生支持 Enter/Space),但语义弱。

**建议**:给当菜单用的 `<details>` 补 `role="menu"` + `aria-expanded` 联动(`toggle` 事件里同步),或至少补 `aria-label`。

### 15. 无深色模式,但 `theme-color` 是深色

`layout.html:29` — `<meta name="theme-color" content="#1a1714">`(墨黑),而 UI 全亮(paper 米色)。iOS PWA 全屏下状态栏深色、页面米色,接缝明显。`prefers-color-scheme` 全站 0 处使用。

**建议**:短期把 `theme-color` 改成 `#F4EFE6`(与 paper 一致,接缝消失);长期是否做深色模式另议 —— "账册"隐喻本身偏亮,做深色需要重新定义整套隐喻,不建议轻启。

---

## 补充发现(写按钮/标签规格时新增)

沿用 1-15 的编号继续,优先级单独标注。

### 16. 按钮缺 3 个交互态 —— P1

只实现了 `default` + `:hover`,缺 `:active` / `:focus-visible` / `[disabled]`(见 §2.1.5)。

- `:active` 缺失最实际:**移动端没有 hover**,`:active` 是唯一的按下反馈。现在手机上点任何按钮都没有触觉确认,只能等页面响应 —— 在慢网络下用户会重复点。
- `[disabled]` 缺失有具体触发点:`entry/index.html:255` 的"刷新持仓估值"用了 `hx-disabled-elt="this"`,请求期间按钮真被 disable,但**外观零变化**。
- `:focus-visible` 缺失:Tailwind preflight 重置了 outline,键盘 Tab 时焦点不可见。

修复成本:一个 CSS 块,约 12 行,覆盖全部按钮变体。

### 17. 没有危险操作按钮变体 —— P1

全站无 `btn-danger` / `btn-rust`。删除/归档当前三种写法混用:`btn-ghost`(账户归档)、`btn-paper`(目标归档)、裸 `text-rust`(删收入笔)。不可逆操作与"编辑""导出"完全同权重。

规格与 CSS 见 §2.1.2。取舍是**描边而非实心** —— 实心红会抢走 `btn-ink` 主操作焦点。

### 18. `.pill-mute` 是死类名 —— P1

3 处模板在用(`nav.html:48`、`nav.html:59`、`dashboard/_region.html:216`),`style.css` 中**无定义**。这些元素现在只吃 `.pill` 基类,作者想表达的"弱化/中性"语义丢失。补 4 行 CSS 即可(§2.2.3)。

顺带核实:`pill-pulse` 不是类,是彩蛋里 `e520-pill-pulse` 动画名被 grep 误匹配;`pill-heart` 在 `easter520.html:187` 有定义。这两个不用管。

### 19. 筛选标签与只读标签视觉不可区分 —— P1

`.pill` 一个类承担四种语义(分类 / 状态 / 筛选 / 计数,见 §2.2.1)。其中**筛选标签可点、分类标签只读,但长得一模一样** —— 账户页类型筛选行(`accounts/index.html:40-47`)是 `<a class="pill">`,无 hover 反馈、无 focus 态,只有已选中项的 `pill-ink` 能暗示"这里可交互"。

用户扫一眼无法判断哪些标签能点。补 `.pill-filter`(hover 变 brass 边)即可(§2.2.4)。

### 20. `.field-label` 有 237 处手写副本 —— P2

`style.css:260-268` 定义完整,但只 13 个模板用类名,**237 处手写等效样式**。后果:字号在 10 / 10.5px 飘,颜色在 `ink-soft` / `ink-subtle` 飘,间距在 4 / 6px 飘;改一次全局标签样式要动 237 处。

不影响当前外观(等效样式本来一致),纯可维护性债 —— 但量级是全站最大的一处复制粘贴。详见 §2.2b。

### 21. `.pill` 基类字号实际无人使用 —— P2

基类是 10.5px,但 64 处在覆写:`text-[9px]` 37 处 / `text-[10px]` 22 处 / `text-xs` 5 处。说明需要的是**显式尺寸档**而不是一个默认值。补 `.pill-xs` / `.pill-md` 两个类收敛(§2.2.5)。

---

## 附:值得保留并推广的 5 个做法

1. **空状态一律"状态 + 下一步"** —— 全站最一致的一项,应写进新页面 checklist
2. **HTTP 5xx 明确不跳错误页**(避免丢填报现场)—— 这是真正从用户场景倒推的技术决策
3. **KPI 口径双层展开**(公式 + 真实代入数值)—— 财务类产品的信任感来源,别的地方也该学
4. **单变量字号缩放** `--fs-scale` + 标准档逐像素等价 —— 零回归改造范式
5. **`.tap::after { inset:-12px }` 热区扩张** —— 不改布局解决小图标点击,应推广到所有 <20px 的可点元素

---

*本文档为反推现状 + 审计,不含任何代码改动。第二部分 15 项建议均未实施,待评审后按优先级安排。*
