# 券商同步 · 凭据获取图文向导(富途 / 老虎 / 盈透)

> 应用内同款向导:登录后打开 **/help/broker-sync**(管理 → 券商同步 → 「不知道怎么填?看图文教程」),那里带示意图。本文是同一份步骤的仓库版。

配好后,系统每天(或手动点一下)**只读**把券商里的持仓 + 各币种现金拉进账房,不用再手抄。

## 先说安全

- 本系统**只查询、永不下单 / 划转**:富途只连本地网关查数据、从不解锁交易(`unlockTrade`);老虎只调查询接口。
- 但你拿到的**私钥 / 网关本身权限较大**,请当密码一样保管;担心时随时能在券商后台吊销 / 重置。
- 凭据在本机加密保存,页面上**私钥永不回显**。

---

## 老虎 Tiger(相对简单 · 全程网页 · 免装网关)

**开始前**:先有一个老虎证券账户并已入金(开放平台对已入金用户免费)。

1. **注册成为开发者** —— 打开 <https://developer.itigerup.com/>,用手机号 + 验证码登录 / 注册(和你的老虎账户同一手机号)。
2. **记下 Tiger ID 和 资金账号** —— 在「开发者信息」页:
   - `Tiger ID`:一串数字 → 填我们的 **tiger_id**。
   - `资金账号`:环球账号 U 开头(如 `U12300123`)、综合账号 5–10 位数字 → 填我们的 **交易账户**(留空 = 默认账户)。
   - `牌照 License`(如 TBSG/TBHK):港股才用到,先记着。
3. **生成 RSA 密钥对,复制私钥** —— 点「生成 RSA 密钥对」。
   - ⚠️ **最关键、别关页面**:私钥只显示这一次、刷新就没了、老虎服务器也不存。立刻整段复制。没存到就点「重新生成」。
   - 我们是 Java 程序 → 选 / 保存 **PKCS#8 格式**(页面通常有 Java / Python 两种,选 Java 那份)。
4. **回本系统填写并测试** —— 管理 → 券商同步 → 老虎 Tiger:

   | 我们的输入框 | 填什么 |
   |---|---|
   | tiger_id | 第 2 步的 Tiger ID |
   | RSA 私钥 | 第 3 步复制的私钥(PKCS#8) |
   | 交易账户 | 第 2 步的资金账号(可留空) |

   点「保存券商配置」→「测试老虎连接」,显示成功即通。再到证券账户「持仓 → 券商自动同步」点关联。

官方参考:[准备工作(含截图)](https://quant.itigerup.com/openapi/zh/python/quickStart/prepare.html) · [API 文档](https://docs.itigerup.com/)

---

## 盈透 IBKR(v1.26 · 全程网页 · 免装网关 · 一年换一次口令)

盈透走的是它的 **Flex 报表**:你在 IBKR 后台定义一份报表、生成一个「报表口令」,账房每天用它把**上一个交易日收盘后**的持仓和现金取回来。报表口令**只能取报表,做不了交易**。

1. 登录 IBKR 网页后台(Client Portal),打开 **Performance & Reports → Flex Queries**。
2. 在 **Activity Flex Query** 那一栏点「+」:勾 **Open Positions** 和 **Cash Report** 两栏(字段全选即可),格式选 **XML**,时段选 **Last Business Day**,保存。它旁边的数字就是**查询号**。两栏一定都要勾 —— 少了哪一栏,账房会拒绝同步并告诉你。
3. 点 **Flex Web Service Configuration**(齿轮),勾选启用。
4. 点「Generate New Token」:**有效期选 1 年**(默认只有 6 小时)。知道本服务器公网 IP 的话可以填进「Valid For IP Address」。重新生成会让旧口令立刻失效。
5. 回 **管理 → 券商同步** 的「盈透 IBKR」:粘上报表口令、查询号,填上**口令到期日**(填了,到期前 14 天账房会提醒你换),保存,点「测试盈透 IBKR 连接」。然后到证券账户的「券商」页选「盈透 IBKR」关联。

- 不在美 / 港 / A 股的股票(伦敦、东京 …)系统拉不到价,按报表里的收盘价做手动估值,每次同步更新。
- 期权 / 权证 / 债券按报表里的持仓市值同步(已含合约乘数,卖出为负、从余额里减去);期货只展示名义价值、不计入余额(盈亏每天结算进现金)。报表字段要全选 —— 缺了乘数 / 市值的那一行不同步,同步结果里会点名(v1.29)。
- 升级到 v1.29 后第一次同步:新关联的账户算开账;已经在记的账户,期权等第一次被算进来那一跳计入当月收益(当月收益率会偏高一次)。以前为了凑数手填过期权市值的,把那条手填持仓归档,免得算两遍。
- 服务器要能访问 `ndcdyn.interactivebrokers.com:443`;连不上时测试结果会直接说。

## 富途 Futu(需常驻一个「OpenD」网关程序)

**开始前**:有富途牛牛账户并开通对应市场权限。富途机制:官方给一个叫 **FutuOpenD** 的小网关,你在自己电脑运行它、用富途账号登录;我们的系统连这个本地网关只读取数据。**网关关掉就同步不了**,最好放一台常开的电脑 / 服务器。

1. **下载 FutuOpenD** —— <https://www.futunn.com/download/openAPI>。有「可视化(带界面)」和「命令行」两种,**入门选可视化版**。支持 Windows / Mac / Linux。
2. **运行 OpenD,用富途账号登录** —— 输入富途账号 + 登录密码。首次登录可能要完成一次问卷 + 协议确认。
3. **记下地址和端口** —— OpenD 界面显示监听地址,默认 `127.0.0.1` 端口 `11111`。管理 → 券商同步 → 富途 Futu:

   | 我们的输入框 | 填什么 |
   |---|---|
   | FutuOpenD 地址 | OpenD 与本系统同机 → `127.0.0.1`;不同机 → 那台机器的内网 IP |
   | OpenD 端口 | `11111`(除非你在 OpenD 改过) |

   保存后「测试富途连接」验证。再到证券账户「持仓 → 券商自动同步」点关联。

**两个提醒**:
- 查持仓 / 现金**不需要「解锁交易」**:解锁只在下单时用,我们永不下单,不用输交易密码。
- OpenD 装云服务器时别把监听设成 `0.0.0.0` 直接开公网端口 —— 用内网 / SSH 隧道更安全。

官方参考:[OpenAPI 文档](https://openapi.futunn.com/futu-api-doc/intro/intro.html) · [OpenD 常见问题](https://openapi.futunn.com/futu-api-doc/qa/opend.html)

---

## 富途 OpenD 部署到哪(自托管在服务器时)

**先理解一件事**:富途没有「拿 key 直连的云端 API」——`futu-api` SDK 只能连 **FutuOpenD 网关**,网关再连富途。所以富途要生效,必须有一个 OpenD **一直开着、且本系统能通过 TCP(host:port)连到它**。老虎则是直连云端 API、不需要任何网关——如果你嫌 OpenD 麻烦,**老虎是零运维的那条路**。

> ⚠️ **富途账号的一次性手续**:开通 OpenAPI 权限、做完风险问卷与协议确认 —— 这只能你本人在富途做。
>
> 这里以前写着"必须先在一台**有桌面的机器**上用可视化版 OpenD 登录一次,否则 headless 起不来"。
> **我们没能验证这句话**(需要真实富途账号走一遍),而且它与向导页里"问卷在牛牛 App / 网页做"的说法互相矛盾。
> 所以现在只讲能确认的部分:**手续要在富途侧完成**;至于牛牛 App 够不够、还是非得桌面版 OpenD 登录一次,
> 以你实际遇到的提示为准。命令行版在容器里能正常起、能走到"请输入账号"这一步是**实测过**的
> (v1.17 · 2026-08-17),所以"headless 起不来"至少不是普遍成立的。

按你的部署形态三选一:

### 拓扑 A · 与 app 同机(systemd 部署推荐)
app 用 systemd 跑在某台 Linux 服务器上 → 把命令行版 OpenD 也常驻在同机,配 `127.0.0.1:11111`。
- 用模板 [`deploy/futu-opend.service.example`](../deploy/futu-opend.service.example) → `cp` 成 `/etc/systemd/system/futu-opend.service`,账号密码放 `chmod 600` 的 `/etc/futu-opend.env`。
- `systemctl enable --now futu-opend`,`ss -ltn | grep 11111` 确认监听。
- 管理页 ⑥ 富途填 `127.0.0.1` / `11111`。
- 安全:`api_ip=127.0.0.1` 只本机可连,别设 `0.0.0.0` 开公网。

### 拓扑 B · docker compose sidecar
app 走 docker compose 时,用我们提供的**可选**网关容器跑 OpenD(v1.17 起)。

**默认不装**:`docker compose up -d` 不拉、不起、不占磁盘。要用富途才启用:

```bash
bash deploy/docker-up.sh --with-futu     # 推荐:会把选择记进 .env,以后不用再记 profile
docker compose --profile futu up -d      # 等价的原生命令
```

**这个镜像里没有富途的任何文件** —— 只有一个下载器、两个 shell 脚本、和我们核对过的哈希清单
([`deploy/futu-opend-releases.json`](../deploy/futu-opend-releases.json))。OpenD 本体是容器**首次启动时从富途官网下载**的,
下完先比对 sha256,不一致就拒绝启动。

**关于哈希,有一件事必须说清**:**富途官方不公布任何 md5 / sha256**。清单里那些值是**我们自己下载后算的**,
钉在这个仓库里(git 历史可查)。你可以三方交叉核对:

```bash
sha256sum Futu_OpenD_10.10.7008_Ubuntu18.04.tar.gz            # ① 你自己算
curl -sI "https://softwaredownload.futunn.com/Futu_OpenD_10.10.7008_Ubuntu18.04.tar.gz" | grep -i etag   # ② CDN 给的
                                                               # ③ 仓库里钉的(上面那个 json)
```
腾讯云 COS 的 `etag` 实测等于文件 MD5,但它和安装包走**同一条 TLS、同一个 CDN**,只证明"传输没坏",
不是独立第三方担保 —— 三个值都对上才有意义。

镜像本身也可以验:

```bash
docker pull ghcr.io/luodi-nate/financial-management-futu-opend@sha256:<见 Release 页>
gh attestation verify --repo LuoDi-Nate/financial-management \
  oci://ghcr.io/luodi-nate/financial-management-futu-opend@sha256:<同上>
docker run --rm --entrypoint ls ghcr.io/luodi-nate/financial-management-futu-opend /opt/futu
```

**账号密码在页面上填**(管理 → 券商同步 → 富途 → OpenD 向导),存本机数据库 —— v1.17 起 `.env` 里不再需要
`FUTU_ACCOUNT` / `FUTU_PWD_MD5`(老配置留着也不报错,只是不再被读)。登录与短信验证码都在向导页完成。

**端口**:两个都不对宿主发布。`11111` 是富途 API(仅 compose 内网,且走 RSA 加密,同栈其它容器读不到你的持仓);
`22222` 是 OpenD 的控制口,**它没有任何鉴权**,所以只绑容器内 `127.0.0.1`。
⚠️ **千万不要给这个服务加 `ports:`** —— 那等于把券商网关的遥控器放到网上。

**停止**:`docker compose stop opend`。OpenD 实测**不响应 SIGTERM**(给 60 秒也照样被强杀),
所以 `stop_grace_period` 设成 15 秒,别白等;强杀已验证可恢复(安装物与登录状态都完好,重启照常工作)。

**逃生阀**:不信我们构建的镜像,`.env` 里设 `FUTU_OPEND_IMAGE=你自己的镜像`,其余流程不变;
老路径([`deploy/futu-opend.compose.yml`](../deploy/futu-opend.compose.yml) + 自备镜像 + `.env` 凭据)也仍然保留,原有配置照旧生效。

### 拓扑 C · OpenD 在家用机 / NAS + 反向隧道
不想在云服务器上放券商登录态,就把 OpenD 跑在家里常开的机器,用 SSH 反向隧道把它的 11111 转到 prod 本地:
```
# 在家用机执行(把家里的 11111 反向暴露到 prod 的 127.0.0.1:11111)
ssh -N -R 11111:127.0.0.1:11111 用户@prod服务器IP
```
- 管理页 ⑥ 富途填 `127.0.0.1` / `11111`(prod 上看到的是隧道口)。
- 隧道断了就同步不了 → 建议用 `autossh` 保活。

**共同点**:三种都要 OpenD 常开;查持仓 / 现金**不需要解锁交易**(我们永不下单)。嫌重就先用老虎。

