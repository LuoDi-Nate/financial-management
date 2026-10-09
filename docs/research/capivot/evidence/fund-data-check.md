# 场外基金数据可得性核对(2026-10-05 实测)

目的:验证「按基金代码自动拉净值」在数据上走不走得通。用的是本项目 v1.5 起已经在用的天天基金(东方财富)公开文件
(`EastMoneyFundClient.java` 已在读 `fundcode_search.js` 与 `pingzhongdata/{code}.js`),不是新引入的数据源。

## 1 · 名↔码表

```
GET https://fund.eastmoney.com/js/fundcode_search.js
```

- 返回 28,010 条,每条 `[代码, 拼音缩写, 名称, 类型, 拼音全拼]`,例如
  `['002943', 'GFDYZHH', '广发多因子混合', '混合型-灵活', 'GUANGFADUOYINZIHUNHE']`
- 类型分布前几位(条数):混合型-偏股 5781 · 指数型-股票 5744 · 债券型-长债 2635 · 混合型-灵活 2399 ·
  货币型-普通货币 969 · FOF-稳健型 790 · 指数型-海外股票 367 · QDII-混合偏股 128 · Reits 103 …
- 结论:**代码、名称、类型一次拿全**,可以做「输代码 / 输名字都能搜到」;类型能直接区分货币基金、QDII、FOF。

## 2 · 单只基金净值

```
GET https://fund.eastmoney.com/pingzhongdata/{code}.js   (Referer: https://fund.eastmoney.com/)
```

| 代码 | 名称 | `ishb` | 关键字段 | 最新一条 |
|---|---|---|---|---|
| 002943 | 广发多因子混合 | false | `Data_netWorthTrend`(单位净值序列,2368 点) | 4.78 · 2026-09-30 |
| 270042 | 广发纳斯达克100ETF联接人民币(QDII)A | false | `Data_netWorthTrend`(3403 点) | 8.3418 · **2026-09-29**(QDII 晚一天) |
| 000198 | 天弘余额宝货币 | **true** | 没有 `Data_netWorthTrend`;有 `Data_millionCopiesIncome`(万份收益)/ `Data_sevenDaysYearIncome`(七日年化) | 万份收益 0.2253 · 七日年化 0.83 · 2026-10-01 |

结论:
- 普通开放式基金:`单位净值 × 份额` 即市值,净值日期随数据给出。
- **QDII 净值比境内基金晚 1 个交易日**(上表 09-29 vs 09-30)—— 页面必须显示「净值日期」,不能假装是当天的。
- **货币基金没有净值序列**(`ishb=true`),净值恒为 1,收益按万份收益每天结转成份额 —— 「代码 + 份额」这套对它不适用,要单独处理。

## 3 · 银行理财(对照)

- 2026-09-01 起「中国理财网 · 理财行业统一信息披露平台」全面上线(新华网 2026-09-03 报道),页面可按「产品登记编码」查到「份额净值 / 净值日期」。
- 但页面背后的接口请求体是加密的(实测 `POST .../lcxp-platService/product/getProductList`,body 为密文,且先走一次 RSA 公钥握手 `.../m/n`)。
  没有公开的机读接口,抓它等于逆向加密 —— 不稳、也可能违反其使用条款。见截图 `../img/ext-chinawealth-xinxipilu.jpg`。
