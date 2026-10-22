# iPhone Duo 官方设计规范调研(2026-10-22 · 补 v1.36 漏掉的部分)

> 起因:维护者 2026-10-22:「我看了下官方推荐,你应该根本没有调研到!他似乎有 6 种形态?外屏、内屏、帐篷、半折叠等;以外屏为例,苹果希望 app 或者网页把操作放到页面右侧,而不是传统的底部。你仔细调研,然后迭代更新好。」
>
> **v1.36 漏在哪**:当时抓 HIG 页面只拿到了标题(苹果文档站是前端渲染,直接抓页面只有空壳),误以为「官方对网页只有两句话」,就只做了「宽度变了不走样」。这次改用文档站背后的数据接口(`developer.apple.com/tutorials/data/…json`)拿到了 HIG 和开发文档的正文,并读了 6 个 Tech Talk 的完整字幕。

## 1. 来源(全部官方,除第 5 节)

- HIG · [Designing for iPhone Duo](https://developer.apple.com/design/human-interface-guidelines/designing-for-iphone-duo)(2026-09-09 新页)
- 开发文档 · [Preparing your app for iPhone Duo](https://developer.apple.com/documentation/technologyoverviews/preparing-your-app-for-iphone-duo)
- Tech Talks:[Design for iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111466/) · [Raise the bar with iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111462/) · [Strike a pose with adaptive layouts](https://developer.apple.com/videos/play/tech-talks/111463/) · [Prepare your app](https://developer.apple.com/videos/play/tech-talks/111461/) · [Multiple displays and scenes](https://developer.apple.com/videos/play/tech-talks/111464/) · [Camera](https://developer.apple.com/videos/play/tech-talks/111465/)
- [Safari 发布说明索引](https://developer.apple.com/documentation/safari-release-notes):截至 10-22 有 Safari 27 和 27.2 Beta,**没有 27.1**;27.2 Beta 里没有任何折叠屏相关条目

## 2. 六种姿态(官方配图「six device poses」,从左到右)

| # | 官方叫法 | 我们叫 | 用哪块屏 · 方向 | 官方要点 |
|---|---|---|---|---|
| 1 | closed | **合上** | 外屏 · 竖握为主,也可横握 | 外屏比普通 iPhone **更宽更矮**;顶部、底部的栏挪到**右侧**竖排 |
| 2 | folded like a tent | **帐篷** | 外屏 · 立在桌上,倒 V 形 | 「standing on its own, resting on its edges」· 免手持观看 |
| 3 | open in a wide layout | **打开 · 横** | 内屏 · 横向 | 控制**仍在右侧**,和外屏位置连贯 |
| 4 | partially folded | **半折(书本)** | 内屏 · 横向,中间弯 | 文字图片自动避开中间弯曲区;**按钮等可点元素挪到两侧** |
| 5 | open in a tall layout | **打开 · 竖** | 内屏 · 竖向 | **唯一保留横向顶栏 / 底栏的姿态**(纵向空间够) |
| 6 | folded like a laptop | **笔记本** | 内屏 · 竖向半折,下半平放 | 可点元素挪到**下半**,媒体放上半;可选专属布局,但不许把功能绑在姿态上 |

另有 **分屏**:内屏上两个 App 各占一半(50/50),**各自的控制放在自己靠外的那条边**(左边的 App 控制在左边)。

## 3. 核心规则(原话 + 意思)

### 3.1 操作放侧边(Vertical controls)—— 这次的主线

- 「Buttons and controls that normally sit at the top and bottom move to the **right side** of the display in order to maximize vertical space and to make them easier to reach with your **right thumb**.」(Design talk)
- 侧边这一列从上到下:**灵动岛 → 状态栏 → 工具栏 → 标签栏**(HIG 配图)。外屏前置摄像头在右上角,和侧边控制**竖直对齐**。
- 「The only pose where we've kept horizontal bars is on the inner display in **portrait**.」
- 排序:**最上面放主导航(返回 / 关闭)**,接着是突出操作(完成);原来顶栏的按钮去侧边上部,原来底栏的去侧边下部,**标签栏保持底对齐**。
- 空间不够时**从下往上收进溢出菜单**;常用操作(写邮件、新建备忘录)和带角标的最后收。
- 每个按钮要有**图标 + 文字**:竖排只显示图标,溢出菜单里显示图标 + 文字;**只有文字的按钮不会进竖排**(会留在横向栏)。
- 太宽的东西(文字按钮、分段控件)留在原位,不挪到侧边。
- **内容不对称**:因为右边被占,内容要按左右各自的安全区排,不能假设左右相等;不滚动的沉浸式界面(计算器)可以占满全宽。
- **靠近它作用的内容**:只作用于某一栏的按钮(邮件列表的筛选)留在那一栏顶上,不挪去侧边。
- 「In general, **don't override the default bar placement**.」

### 3.2 姿态与布局

- **不要给每种姿态做一套布局**:只针对两种宽度 —— 外屏 compact、内屏 regular;避免固定宽度、断点、绑定某块屏的尺寸。
- **折叠时不要大幅改版**:只挪必要的,让东西保持可见、好点。
- **网格优先偶数列**,半折时中线正好落在列缝。
- 半折时:「buttons are tough to tap when they land right in the fold」→ 系统组件(弹层、提示框、菜单、工具栏按钮)会自动**挪离折痕**;**滚动内容不用避开**。
- 弹层(sheet):外屏上弹层的按钮也去侧边;内屏横竖都用横向栏;半折时**弹层滑到一侧**,不压在折痕上。
- 保留区(reserved regions):外屏前置摄像头(一直在)、内屏前置摄像头(用时才在)、折痕区(半开时才在)。

### 3.3 对网页

官方**没有**专门写给网页的指引(HIG、开发文档、6 个 Tech Talk 里都没有 web / Safari 字样)。官方 Q&A(第三方整理)只说:网页不要识别设备型号,用标准网页接口判断有没有折痕。

**能确定的网页事实:**
- Safari 拿不到折痕 / 姿态(Viewport Segments、Device Posture 都没默认开);
- Safari 自己在外屏上也把工具栏放在**右侧竖排**(第三方模拟器实测,见第 5 节)—— 也就是说,苹果自己的浏览器就是按「操作放右侧」做的;
- UA 仍报 iPhone。

**由此推出我们该怎么做**:把 HIG 对 App 的规则平移到我们的页面上 —— 我们的「主屏 App」形态,在家里人看来就是一个 App。

## 4. 尺寸(要真机确认)

- 官方像素:外屏 1398×2034,内屏 1878×2670。按 iPhone 惯用的 3 倍换算:**外屏 466×678 pt,内屏 626×890 pt**(两块屏宽高比都约 1:1.45)。
- 但第三方模拟器报过内屏 669×951 / Safari 横向 951×589,对应约 2.8 倍 —— **两组数对不上**,v1.36 e2e 用的是后一组。
- 结论:判据只能按「形状 + 范围」写,不能写死某个数;真机到货后用 `screen.width/height` 和 `innerWidth/innerHeight` 实测回填。

## 5. 第三方实测(非官方,仅供参考)

- [Mike Piontek · Xcode 27.1 RC 模拟器](https://mikepiontek.com/journal/iphone-duo-safari.html):Safari 外屏工具栏在右侧,**不算安全区** —— 顶部固定栏会被右侧工具栏盖住一截;Safari 取页面右边缘几像素的颜色给工具栏着色,`theme-color` 似乎被忽略。
- [iphoneduosupport](https://iphoneduosupport.com/blog/websites-on-iphone-duo/):左右安全区不再相等(拆开写 left / right);用 `viewport-fit=cover` 才拿得到安全区;别用 UA 分支;宽视口中间那一条别放关键按钮。
- [Group Labs Q&A 整理](https://gist.github.com/frankschlegel/6356a059426b2393528691822edfdae6):网页别识别型号;Q&A 现场没有 WebKit 的人。

## 6. 对照我们 v1.36 的现状

| 官方要求 | v1.36 | 差距 |
|---|---|---|
| 外屏、内屏横:操作在右侧竖排 | 顶栏在上、浮钮在右下 | **没做**(这次的主线) |
| 内屏竖:保留横向顶栏 | 顶栏 | 符合 |
| 折叠时不大幅改版、内容不丢 | 已做(v1.36) | 符合 |
| 网格偶数列、折痕不放按钮 | 951 宽用 3 列的地方不少 | **要扫** |
| 帐篷:外屏横向免手持观看 | 外屏横会触发「手机横屏冻结旋转」,页面被转成侧着的 | **会出错** |
| 半折:弹层 / 按钮离开折痕 | 弹框居中,正压折痕 | **要改** |
| 笔记本:可点元素在下半(可选) | 拿不到折痕,不做 | 不做,记录 |
| 分屏:控制在靠外的边 | 网页不知道自己在左还是右 | 做不到,记录 |
| 左右安全区不对称 | 没开 `viewport-fit=cover`,安全区全 0 | 主屏 App 下可能被摄像头角 / 侧边状态栏挡,**真机确认** |
