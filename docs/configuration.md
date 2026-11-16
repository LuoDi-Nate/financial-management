# 配置与接入指南 · 家庭账房

> **一句话:所有外部服务都是「可选增强」。装好([部署](../deploy/README.md))、登录、记账、看净资产/真实年化/图表 —— 这些核心功能零配置即可用。下面这些配了才解锁对应的额外能力。**

所有运营参数都在**管理页**配(存数据库、改完实时生效、不用上服务器改文件),**不写进 `.env`**;API key / aksk 这类私密信息**绝不进日志明文、不在前端回显、不喂给 AI**。

## 一览表

| 外部服务 | 配了解锁什么 | 是否必需 | 去哪配 | 详细步骤 |
|---|---|---|---|---|
| **阿里云百炼** | AI文字/图片能力,可选主备;保留文字自动轮询 | 可选 | `/admin/ai-access#llm` | [LLM Key 申请](llm-api-keys-setup.md) |
| **DeepSeek官方** | AI文字能力,可选主备 | 可选 | `/admin/ai-access#llm` | [LLM Key 申请](llm-api-keys-setup.md) |
| **火山方舟** | AI文字/图片能力,按具体型号选择 | 可选 | `/admin/ai-access#llm` | [LLM Key 申请](llm-api-keys-setup.md) |
| **硅基流动** | AI文字/图片/本地工具能力,按具体型号选择 | 可选 | `/admin/ai-access#llm` | [LLM Key 申请](llm-api-keys-setup.md) |
| **阿里云短信** | 填报截止前的**短信强提醒** | 可选 | `/admin/reminders` ② | [阿里云短信接入](aliyun-sms-setup.md) |
| 股票行情(新浪/腾讯) | 每日 T+1 自动估值 | **免配** · 内置 | — | 无需 key |
| 加密货币行情(Binance/CoinGecko/Coinbase) | BTC/ETH/USDC 等自动估值 | **免配** · 内置 | — | 无需 key |
| 汇率(frankfurter) | 多币种自动换算 | **免配** · 内置 | — | 无需 key |

> 不配 AI:体检/月报/调仓入口不可用,其余全部正常。
> 不配短信:截止提醒只走站内 banner,不影响记账。
> 股票 / 加密货币 / 汇率开箱即用,**不需要任何 key**。

---

## 1. AI(百炼 / DeepSeek官方 / 方舟 / 硅基流动)

**解锁**:资产体检、月报 AI 洞察、调仓建议。计算全部由工程算好再喂模型,**AI 只解读,不做数学、不荐产品、不预测涨跌**。

**怎么配**:`/admin/ai-access`(管理 → AI 接入)→「大模型」那一节,填 API Key → 保存 → 点该 key 旁的「**测试连接**」验证通不通。
- 四家各自保存密钥,主选与备选由你指定,**配一家即可**;截图独立选型,本地Agent沿用文字主备。硅基流动的托管DeepSeek不与官方平台共用密钥。
- 每个 key 字段下有「**如何获取?**」折叠说明,3 步拿到 key;详版见 [LLM Key 申请与配置](llm-api-keys-setup.md)。
- 选哪家、免费额度怎么省,见 [LLM 厂商选型对比](llm-vendor-comparison.md)。

**省钱提醒(Qwen)**:百炼免费额度**按模型各给一份**,本项目每次调用随机选模型把流量摊开;务必在百炼控制台「费用 → 开启**免费额度用完即停**」,避免用超后被静默计费。

**私密**:key 留空 = 保持原值;绝不进 audit_log 明文 / 前端明文回显 / LLM prompt。

## 2. 阿里云短信(可选)

**解锁**:每月填报截止前 N 天,给没填的成员发短信强提醒(站内 banner 始终兜底,不配短信也有提醒,只是不发短信)。

**怎么配**:`/admin/reminders` → ②「短信」段,填 AccessKeyId / AccessKeySecret / 签名 / 模板 CODE → 保存 → ③「一键测试短信」发一条验证链路。
- 签名要实名报备、模板要审核,**完整 9 步**(含 RAM 子账号、签名/模板申请、4 个模板变量)见 [阿里云短信接入指引](aliyun-sms-setup.md)。

**私密**:aksk 与手机号绝不进 LLM prompt / audit_log 明文 / 前端回显。

## 3. 股票行情 & 汇率(免配)

- **股票**:录 ticker + 数量,系统每日 T+1 自动拉价(新浪主 + 腾讯备,美/A/港三市场),**无需任何 key**。拉取开关 + cron 在 `/admin/integrations` ①。
- **加密货币**:新建 CRYPTO 账户后录 BTC / ETH / USDC 等 ticker + 数量,系统用 Binance market-data-only 主源 + CoinGecko / Coinbase 备源自动估值,**无需任何交易所 API key**。拉取开关 + cron 同在 `/admin/integrations` ②。
- **汇率**:本位币 CNY/USD/HKD,自动从 frankfurter 拉,**无需 key**。cron 在 `/admin/integrations` ②。
- 拉不通(防火墙/网络)时:体检/估值会用最后一次有效值,不影响记账;可手动在 `/admin/fx` 补汇率。

---

## 配置都在哪(速查)

| 管理页 | 配什么 |
|---|---|
| `/admin/integrations` | 股票开关+cron / 汇率 cron / 贵金属 / 宏观 CPI·M2 校正 |
| `/admin/broker` | 券商同步:富途 / 老虎 / 盈透的凭据、测试连接、同步时间;富途 OpenD 向导(v1.26 起单独一页,之前在数据源接入)|
| `/admin/ai-access` | 大模型密钥与型号 / 超级 Agent / 让 AI 读你的账本(只读口令) |
| `/admin/reminders` | 填报模板 / 提前提醒天数 / 每天几点提醒 / 短信 aksk·签名·模板 / 成员手机号 |
| `/admin/calc-tweaks` | 录入提示阈值(含再平衡「算执行了」的比例)/ 体检阈值等计算参数 |

改完即生效、不重启;读取优先级:**数据库 > 环境变量 > 代码默认**。
