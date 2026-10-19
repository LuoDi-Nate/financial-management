# 前沿 UI 组件库调研 · 我们的按钮 / 标签 / 输入框该长什么样(2026-10-19)

> 起因:维护者 2026-10-19 说持仓页「真的丑死了,为什么按钮都那么大,还都是个方框」,
> 并点名要看 shadcn/ui、HeroUI、Mantine、Nuxt UI、shadcn-vue、PrimeVue、daisyUI,
> 动效看 Magic UI、Aceternity UI、React Bits。
> 本文只回答「主流库怎么做、我们借什么」。
> 方向选择(A 精修账册 / B shadcn 中性)见 [`prd/v1.35.md`](../../prd/v1.35.md);
> 实施细节见 `tech-design/v1.35.md`,评审过 PRD 之后写。
> 数值都取自 2026-10-19 拉取的**官方源码或文档**,链接在文末。rem 按 16px 换算。

---

## 0 · 结论

1. **我们的按钮离主流差在五处,每一处都比主流「更大、更方、更吵」**:

   | | 主流共识 | 我们现在(`style.css` `.btn-ink` / `.btn-paper`) |
   |---|---|---|
   | 高度 | 32–40px,多数 36px;数据型产品 32px | **约 42px**(12px 字 + 上下 padding 12px) |
   | 圆角 | 4–8px,或胶囊 | **0**(设计规范还写着「> 2px 破坏账册隐喻」) |
   | 字体 | 14px 无衬线,字重 500–600 | **12px 等宽字体** |
   | 大小写 / 字距 | 不大写,字距 0 | **uppercase + 0.12em 字距**:中文被拉成「添 加 持 仓」 |
   | 一行几个 | 2–3 个,只有 1 个主按钮;再多收进「⋯」 | 持仓页一排 **5 个**,每张持仓卡再竖着叠 3 个整宽大按钮 |

   七个库的按钮源码里**没有一个**写 `text-transform` 或 `letter-spacing`。直角只在 shadcn 的一套可选风格 lyra 里出现(它自述「Boxy and sharp. For mono fonts」),而 lyra 也是不大写、不加字距、32px 高。

2. **这些库都不能直接装进来**:我们是 Thymeleaf 服务端渲染 + HTMX + 浏览器运行时 Tailwind 3.4,没有 React / Vue,也没有构建管线。
   - shadcn / HeroUI / Mantine 依赖 React;Nuxt UI / shadcn-vue / PrimeVue 依赖 Vue。PrimeVue v5 还改成了要许可证。
   - 只有 daisyUI(1.14MB CSS)和 HeroUI 的纯 CSS 版(428KB)能用 CDN 引入,但两者都自带 Tailwind v4 的样式重置,和我们的 Tailwind 3 会打架;daisyUI 的 `.btn-ghost` 还和我们现有的类撞名。
   - **做法**:把它们的**数值**移植进我们自己的 `style.css`,类名不变(`btn-ink` / `btn-paper` / `btn-ghost` / `btn-brass` / `pill`)。全站约 350 处按钮一次全换,不用逐个改模板。

3. **对标 shadcn/ui 的规格,密度取它新默认风格 nova 那一档**:默认 32px、卡片内 28px、圆角 6–8px、14px / 13px 中字重、图标 16px、focus 3px 光圈、按下下沉 1px。理由:
   - shadcn 是这几个库里视觉最克制的一个(细边、留白、无渐变),和「账册」的气质最近。
   - 它的 nova 风格已经把默认按钮从 36px 降到 32px,说明数据密集的界面本来就该小一档。

4. **动效只借两样**:数字滚动(Magic UI 的 Number Ticker:进入视口才触发,弹簧缓动,千分位格式化,原生 JS 能复刻),以及加载时的骨架屏微光。光束、粒子、3D 卡片属于炫技,不适合理财产品。所有动效都要遵守 `prefers-reduced-motion`。

---

## 1 · 各库要点

| 库 | 技术栈 | 默认按钮 | 圆角 | 字号 / 字重 | 标签(Badge / Chip) | 能不能直接用 |
|---|---|---|---|---|---|---|
| **shadcn/ui** | React + Tailwind v4 | 36px(sm 32 · lg 40)· 水平 16px | 8px(`--radius` .625rem × .8) | 14px / 500 | 胶囊,`px-2 py-0.5 text-xs` | 否(React) |
| shadcn · nova(新默认) | 同上 | **32px**(sm 28 · lg 36)· 水平 10px | `rounded-lg` | 14px / 500 · 按下下沉 1px | 固定高 20px | 否 |
| shadcn-vue | Vue | 数值与 shadcn 一致 | 同上 | 同上 | 同上 | 否(Vue) |
| **HeroUI v3** | React(另有纯 CSS 版) | 桌面 36px · 手机 40px | **胶囊**(24px) | 14px / 500 · 按下缩放 .97 | 圆角 16px · 约 24px 高 | 纯 CSS 版可 CDN,但带 Tailwind v4 重置 |
| **Mantine 9** | React | 36px(xs 30 · md 42) | 8px | 14px / **600** · 按下下沉 1px | 胶囊 · 11px **大写 + 粗体** | 否 |
| **Nuxt UI v4** | Vue / Nuxt | 约 32px(sm 28 · lg 36) | 6px | 字重 500 | `rounded-md px-2 py-1 text-xs` | 否 |
| **PrimeVue Aura** | Vue | 约 36px | 6px | 14px / 500 | 12px / **700** · 圆角 6px | 否(Vue + v5 要许可证) |
| **daisyUI 5** | Tailwind 插件,框架不限 | **40px**(sm 32 · lg 48) | 4px | 14px / 600 · 按下下沉 .5px | 24px 高 | 有 CDN(1.14MB),但和我们的 Tailwind 3、`.btn-ghost` 冲突 |

**按钮层级**,各家叫法不同,其实是同一套四层:

| 层级 | shadcn | HeroUI | Mantine | 我们现有的类 |
|---|---|---|---|---|
| 主操作(每区块 1 个) | default:实心深色 | primary | filled | `btn-ink` |
| 次操作 | outline:白底 + 细边 + 极轻阴影 | outline / secondary | default:白底灰边 | `btn-paper` |
| 行内轻操作 | ghost:无底无边,悬停浅底 | ghost | subtle | `btn-ghost` |
| 危险操作 | destructive(nova 里是 10% 红底红字) | danger / danger-soft | filled red | 缺 `btn-danger`(设计规范里写了「待补」,一直没补) |

**状态**:
- **focus**:2–3px 光圈,多数带 2px 间距。
- **disabled**:半透明(shadcn .5、Nuxt .75、Aura .6)。
- **按下**:下沉 1px 或缩到 .97。
- **过渡**:100–250ms。

**输入框**:高度和按钮对齐(shadcn 36 · Nuxt 32 · Mantine 36);圆角与按钮相同;focus 换边框色或加光圈。

**卡片**:圆角 8–14px;要么细边、要么极轻阴影,很少两者都重。

---

## 2 · 动效库

| | Magic UI | Aceternity UI | React Bits |
|---|---|---|---|
| 规模 | 78 个组件 | 约 110 个 | 215+ 个 |
| 依赖 | React,其中 30 个要 `motion` | React + `motion` + `clsx` | React + gsap / motion / three |
| 能借的纯 CSS | Marquee、Shine 的 keyframes | 「Tailwind CSS Buttons」可直接拷类名 | Shiny Text 是独立 CSS |
| 对我们 | **Number Ticker(数字滚动)值得复刻** | 光束、聚光灯偏炫技 | 文字拆分动画偏营销页 |

---

## 3 · 沉淀成我们的规矩(会写进 `docs/design-system.md` §2.1,替换旧的按钮规格)

1. **按钮**:
   - 高度:默认 32px;卡片和行内 28px(模板里写 `text-xs` 就自动是这一档);落地页、登录这类大入口 40px。
   - 外观:圆角 6px;无衬线 13px / 字重 500;**不大写、不加字距**;图标 14–16px、和文字间距 6px。
2. **层级**:
   - 一个区块只有 1 个 `btn-ink`。
   - 并列的次操作用 `btn-paper`(白底细边 + 极轻阴影)。
   - 行内轻操作用 `btn-ghost`(无底无边)。
   - 删除、归档这类不可逆的操作用新的 `btn-danger`。
3. **一行最多 3 个按钮**,再多就收进「⋯」。持仓卡右侧那三个竖排大按钮改成一排小按钮。
4. **标签(`pill`)**:去掉硬边框和字距;圆角 4px 软底,11–12px 中字重;代码类(基金代码、股票代码)保留等宽字体。在 flex 行里**按竖直中线对齐,不按基线**(按基线对齐时,带框标签会比旁边的大字低几像素,v1.35 填报卡就是这么错的)。
5. **状态四件套**:hover 换底色、focus 光圈、按下下沉 1px、disabled 半透明 —— 一次覆盖全部按钮类。
6. **护栏**:qa-run 扫 `style.css`,按钮类里再出现 `text-transform: uppercase` 或 `letter-spacing: 0.12em` 就红。e2e 量真实按钮高度(默认 32 ± 1px、`text-xs` 档 28 ± 1px)。

---

## 来源

- shadcn/ui:[button.tsx](https://github.com/shadcn-ui/ui/blob/main/apps/v4/registry/new-york-v4/ui/button.tsx)(已复核 `h-9 px-4 py-2` / `h-8` / `h-10` / `rounded-md text-sm font-medium`)· [badge.tsx](https://github.com/shadcn-ui/ui/blob/main/apps/v4/registry/new-york-v4/ui/badge.tsx) · [input.tsx](https://github.com/shadcn-ui/ui/blob/main/apps/v4/registry/new-york-v4/ui/input.tsx) · [card.tsx](https://github.com/shadcn-ui/ui/blob/main/apps/v4/registry/new-york-v4/ui/card.tsx) · [theming](https://github.com/shadcn-ui/ui/blob/main/apps/v4/content/docs/%28root%29/theming.mdx) · [style-nova.css](https://github.com/shadcn-ui/ui/blob/main/apps/v4/registry/styles/style-nova.css) · [style-lyra.css](https://github.com/shadcn-ui/ui/blob/main/apps/v4/registry/styles/style-lyra.css) · [button-group-demo](https://github.com/shadcn-ui/ui/blob/main/apps/v4/registry/new-york-v4/examples/button-group-demo.tsx)
- shadcn-vue:[button](https://github.com/unovue/shadcn-vue/blob/dev/apps/v4/registry/new-york-v4/ui/button/index.ts)
- HeroUI v3:[button.css](https://github.com/heroui-inc/heroui/blob/v3/packages/styles/components/button.css) · [chip.css](https://github.com/heroui-inc/heroui/blob/v3/packages/styles/components/chip.css) · [variables.css](https://github.com/heroui-inc/heroui/blob/v3/packages/styles/themes/default/variables.css) · [纯 CSS 版](https://cdn.jsdelivr.net/npm/@heroui/styles@3.2.6/dist/heroui.min.css)
- Mantine:[Button.module.css](https://github.com/mantinedev/mantine/blob/master/packages/@mantine/core/src/components/Button/Button.module.css) · [default-theme.ts](https://github.com/mantinedev/mantine/blob/master/packages/@mantine/core/src/core/MantineProvider/default-theme.ts) · [Badge.module.css](https://github.com/mantinedev/mantine/blob/master/packages/@mantine/core/src/components/Badge/Badge.module.css)
- Nuxt UI:[button.ts](https://github.com/nuxt/ui/blob/v4/src/theme/button.ts) · [badge.ts](https://github.com/nuxt/ui/blob/v4/src/theme/badge.ts) · [card.ts](https://github.com/nuxt/ui/blob/v4/src/theme/card.ts)
- PrimeVue Aura:[button](https://cdn.jsdelivr.net/npm/@primeuix/themes@3.0.1/dist/aura/button/index.mjs) · [tag](https://cdn.jsdelivr.net/npm/@primeuix/themes@3.0.1/dist/aura/tag/index.mjs) · [v5 迁移(许可证)](https://primevue.dev/migration/v5/)
- daisyUI:[button.css](https://github.com/saadeghi/daisyui/blob/master/packages/daisyui/src/components/button.css)(已复核 `--size-field × 10` = 40px)· [CDN 文档](https://github.com/saadeghi/daisyui/blob/master/packages/docs/src/routes/%28routes%29/docs/cdn/%2Bpage.md)
- 动效:[Magic UI registry](https://github.com/magicuidesign/magicui/blob/main/apps/www/registry.json) · [Number Ticker](https://github.com/magicuidesign/magicui/blob/main/apps/www/registry/magicui/number-ticker.tsx) · [Aceternity 组件](https://ui.aceternity.com/components) · [React Bits](https://github.com/DavidHDev/react-bits/blob/main/README.md)
- 「一行几个按钮」:[Carbon Design System · Button usage](https://carbondesignsystem.com/components/button/usage/) · [Menu buttons](https://carbondesignsystem.com/components/menu-buttons/usage/)
- Tailwind:[v4 默认主题](https://github.com/tailwindlabs/tailwindcss/blob/main/packages/tailwindcss/theme.css) · [升级指南](https://tailwindcss.com/docs/upgrade-guide)
