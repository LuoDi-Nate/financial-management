# 常见问题 FAQ · 家庭账房

## 部署 / 访问

**Q：最低需要什么配置?**
A：1 GB 内存 · 1 核 · ~2 GB 磁盘。app 约 512 MB + MySQL 约 300 MB,**512 MB 的机器会 OOM 起不来**,建议 1 GB 起。NAS、旧笔记本、1 核 1 G 云服务器都够。

**Q：部署在远程 VPS,为什么浏览器打不开 `:20000`?**
A：默认只绑定 `127.0.0.1`(loopback),不对公网开放——这是安全默认值,不是 bug。两种正确做法:
- **临时看一眼**:本地开 SSH 隧道 `ssh -L 20000:127.0.0.1:20000 user@你的服务器`,然后本地浏览器开 `http://127.0.0.1:20000`。
- **长期用**:在服务器上前置反代(nginx / Caddy)并配 HTTPS,把 80/443 转到容器的 20000。片段见 [`deploy/README.md`](../deploy/README.md) 的「反代 / HTTPS」。
- (不推荐)直接公网裸奔:把 `.env` 的 `SERVER_PORT` 映射改成 `0.0.0.0` / 或 compose 端口去掉 `127.0.0.1:` 前缀——务必先配好登录强密码,且家庭财务数据建议别裸奔。

**Q:脚本说「版本无变化」,可我明明看到发布了新版本?**
A:先看它有没有紧跟一行说明。v1.6.27 起会分清三种情况:

- `✓ 已是最新可用镜像(vX)` —— 你已经是最新**能拉到的**镜像了;
- `(GitHub 上已发布 vY,但镜像还没推上来 —— CI 构建约 12 分钟;若久等不来说明构建失败了)` —— 发布 tag 之后镜像要经 CI 构建,这段时间里拉到的还是上一版;
- `· 查不到最新版本(ghcr.io 与 api.github.com 都没通)` —— 网络问题,不影响已经起好的服务。

**为什么对比的是镜像而不是 release**:release 是"我们发布了什么",镜像才是"你能拉到什么"。两者会短暂不一致(构建中),也可能长时间不一致(构建失败)。所以脚本以 GHCR 上的镜像 tag 为准。

如果确认镜像已发布但你仍拉不到,`docker compose pull` 单独跑一次看报错;大陆网络下也可以确认 `.env` 里 `MYSQL_IMAGE` 那类设置没被改坏。

---

**Q:更新后数据"没了",还多出两个叫 Alice / Bob 的成员,怎么回事?**
A:**Alice / Bob 是内置的两个种子账号的默认显示名**(`diwa` / `wangergou`),不是新增的演示成员。它们出现说明你连上的是一个**全新的空数据库** —— 迁移脚本不可能把种子重新灌进已有的库(建表用的是裸 `CREATE TABLE`,重放会直接失败),所以只有"库是新的"这一种可能。

最常见的两个原因:

- **仓库目录名变了** → compose 项目名跟着变 → 用的是**另一个数据卷**。旧卷还在,数据没丢:
  ```bash
  docker volume ls | grep db-data          # 看看是不是有两个 *_db-data
  ```
  用回原来的目录名,或 `COMPOSE_PROJECT_NAME=<原项目名> bash deploy/docker-up.sh`,数据就回来了。
- **执行过 `docker compose down -v`** → 卷被删,那是不可恢复的。备份容器每 **24 小时**(从容器启动起算)dump 一份到 `backups` 卷(`finance-*.sql.gz`,默认保留 56 天),可以从那里恢复 —— 最省事的办法是 `bash deploy/restore.sh`,它会列出可用备份让你选,并在灌入前先把当前库另存一份当退路:
  ```bash
  docker compose exec backup ls -la /data/backups/
  ```

v1.6.26 起,`docker-up.sh` 在检测到"全新空库"时会**主动告诉你**并给出上面的自查命令,而不是默默起好;同时 `down -v` 在凭据不匹配的指引里已降为第三选项(前两条都不丢数据)。

---

**Q:我 `git pull` 了新代码、重跑了 `bash deploy/docker-up.sh`,为什么还是旧版本?**
A:两个原因,v1.6.25 起脚本会直接告诉你是哪一个。

- **`git pull` 拉到的新代码不会进容器。** 应用跑的是预构建镜像(GHCR),`git pull` 只影响 compose 文件和部署脚本本身。想立刻用上仓库里的代码:`docker compose up -d --build`(本地构建,慢一些)。
- **打完 tag 到镜像可用之间有约 12 分钟的 CI 构建时间。** 看到发布消息立刻更新,大概率还是拉到旧镜像 —— 过几分钟重跑脚本即可。

确认当前跑的版本,不用登录:

```bash
curl -s http://127.0.0.1:20000/health     # {"status":"UP","version":"1.6.25"}
```

v1.6.25 之前的镜像不返回 `version`,那时只能登录后看导航栏右上的版本徽记。

---

**Q:应用起不来,日志一直刷 `Access denied for user 'finance'` / `ERROR 1045` 怎么办?**
A:**跑一次 `bash deploy/docker-up.sh` 就会自动修好,不会删你的数据**(v1.6.22 起)。

原因:MySQL 的账号密码**只在第一次创建数据卷时**写入。如果你之前跑过一次、后来重新下载了本仓库(`.env` 里的随机密码换成了新的),而**数据卷不会随仓库目录一起消失** —— 卷里还是老密码,应用就一直被拒。

脚本会检测到这种情况,用 MySQL 官方的密码重置手法把新密码同步进那个已有数据库,**业务数据一行不动**。

如果同步没成功,两条出路:

- 你还留着以前那份 `.env`(或记得旧密码)→ 放回去 / 把 `DB_PASS`、`MYSQL_ROOT_PASSWORD` 改回旧值,再重跑脚本。**数据完整保留,这是最稳的一条。**
- 那个数据库你从没真正用过(没登录进去记过账)→ `docker compose down -v && bash deploy/docker-up.sh` 从零开始。⚠ `down -v` 会**删掉数据库卷里的全部数据、不可恢复**,确认里面没有你要的记账数据再做。

---

**Q:(中国大陆)拉镜像一直超时怎么办?**
A:**v1.6.21 起正常情况下不会再遇到** —— 数据库镜像默认取 GHCR 上我们镜像的同一份 `mysql:8.0`(`ghcr.io/luodi-nate/financial-management-mysql:8.0`),和 app 镜像同一个源、大陆直连,默认安装路径完全不碰被限速的 Docker Hub。

还是卡住的话分两种:

- **用 `bash deploy/docker-up.sh` 起的**:它会按 GHCR 副本 → Docker Hub 官方 → 配镜像源后重试的顺序自己探,两条都不通时**问你一句 `[Y/n]`,同意后自己把国内镜像源配好并重启 Docker**(colima 写虚拟机内的 `daemon.json` 并补 `colima.yaml`、Docker Desktop 合并宿主 `~/.docker/daemon.json` 后重启 App、Linux 原生写 `/etc/docker/daemon.json` + `systemctl`),不需要你手改任何配置文件。已有 `registry-mirrors` 配置它不会覆盖;OrbStack 它不自动改,会给你手动步骤。
- **手动 `docker compose up -d` 的**:这条路不做源探测。拉不动就自己配国内镜像源,把这段写进 Docker 引擎配置(Linux 是 `/etc/docker/daemon.json`,已有则把 `registry-mirrors` 并进去、别覆盖其它配置):

```json
{ "registry-mirrors": ["https://docker.m.daocloud.io", "https://docker.1ms.run"] }
```

再 `sudo systemctl restart docker`。**macOS 上这个路径无效** —— 引擎在虚拟机里不读宿主的 `/etc/docker/daemon.json`,要按装法配,见 [`deploy/README.md` § 国内镜像加速](../deploy/README.md#国内镜像加速--apple-silicon)。另:`docker compose build` **救不了**拉不动 —— 本地构建要从 Docker Hub 拉 `maven`/`eclipse-temurin` 基础镜像,同样会被卡。

**Q:`docker compose down` 会丢数据吗?**
A:不会。数据在命名卷(`db-data` / `uploads` / `backups`),`down` 不删卷。除非你 `down -v`(那会删卷,慎用)。

**Q：怎么升级 / 回滚?**
A：升级 `git pull && docker compose pull && docker compose up -d`(entrypoint 自动跑增量迁移,幂等)。systemd 直装则 `git pull && sudo bash deploy/deploy.sh`。回滚见 `deploy/README.md`(Docker 切回旧 tag;systemd 用 `deploy/rollback.sh`)。

## 数据 / 备份

**Q：备份怎么恢复?**
A:**直接用脚本**(v1.6.28 起):

```bash
bash deploy/restore.sh          # 列出可用备份 → 选一个 → 自动停 app、另存当前库当退路、灌入、起回来
```

它会要你输入 `RESTORE` 确认(覆盖性操作不接受顺手回车),并且**灌入前先把当前库 dump 成 `before-restore-*`** ——
恢复选错了还能用它撤销回来。非交互场景(灾备演练)用 `FINANCE_RESTORE_CONFIRM=RESTORE`。

**备份节奏**:Docker 是备份容器每 24 小时一次(`finance-*.sql.gz`);systemd 直装是 `finance-backup.timer` 每天 03:30
(`/var/backup/finance/dump-*.sql.gz`)。重要操作前建议自己先备一份:`bash deploy/backup-now.sh`。

**备份容器是不是在正常干活**:`docker compose ps` 里 backup 那一行显示 `healthy`,就是最近 26 小时内有一份**校验通过**的备份
(能解压、里面有建表语句)。刚启动时显示 `starting` 属正常,第一份备好就变 `healthy`;一天都没备上才会变 `unhealthy`。
日志(`docker compose logs backup`)每次写一行:成功是「✓ 文件 · N 字节 · M 张表」,失败写的是数据库自己报的原因,
并在 5 分钟后重试(最多 12 次)。v1.28.3 之前备份容器会一直显示 unhealthy —— 那是它误用了 app 的健康检查,不代表备份坏了。

想手工来也可以:
```bash
# Docker:把某个备份灌回 db 容器
gunzip -c 备份文件.sql.gz | docker compose exec -T db mysql -uroot -p<MYSQL_ROOT_PASSWORD> finance
# systemd:
gunzip -c 备份文件.sql.gz | mysql -ufinance -p<DB_PASS> finance
```
恢复前建议先停 app(`docker compose stop app` / `systemctl stop finance`),灌完再起。

**Q:我能托管多个家庭吗?**
A:**目前是单家庭设计**(一套部署 = 一个家庭、多个成员)。要给多个家庭用,各自独立部署一套(各自的数据库/容器)。多租户不在当前路线。

**Q:改了 `/etc/finance.env` 或 `.env` 怎么不生效?**
A:LLM key / 股票开关 / 阈值 等**运营参数走管理页**(存数据库、实时生效),改 env 不再触发 reload。env 只在「数据库还没配过该项」时作为兜底。详见 [配置与接入指南](configuration.md)。DB 连接、端口、`REMEMBER_ME_KEY` 这类系统级才在 env,改完要重启。

## 账号 / 登录

**Q:忘记密码、把自己锁外面了怎么办?**
A:单家庭模式你就是管理员,目前需手动重置(后续版本计划加一键重置)。利用「占位密码会在重启时被重置」的机制:
```bash
# Docker(用 .env 里的 DB_PASS 替换 <DB_PASS>):
docker compose exec db mysql -ufinance -p<DB_PASS> finance \
  -e "UPDATE member SET password_hash='PLACEHOLDER_RESET' WHERE username='diwa';"
docker compose restart app
# systemd:
mysql -ufinance -p<DB_PASS> finance \
  -e "UPDATE member SET password_hash='PLACEHOLDER_RESET' WHERE username='diwa';"
sudo systemctl restart finance
```
重启后,prod profile 的 `ProdSeedRunner` 会把占位密码重置成 `.env` 里的 `SEED_ADMIN_PASSWORD`(默认 `demo1234`),登录后强制改密。把 `diwa` 换成你要重置的用户名。

**Q:默认账号是什么?**
A:`diwa` 或 `wangergou`,初始密码 `demo1234`(Docker 下可在 `.env` 的 `SEED_ADMIN_PASSWORD` 改),首次登录强制改密。

## AI / 数据源

**Q:不配 AI / 短信能用吗?**
A:能。所有外部服务都可选,核心功能(记账 / 净资产 / 真实年化 / 图表)零配置即用。配了各自解锁什么、怎么配,见 [配置与接入指南](configuration.md)。

**Q:股票 / 加密货币 / 汇率拉不到值?**
A:股票(新浪 / 腾讯)、加密货币(Binance / CoinGecko / Coinbase)、汇率(frankfurter)都免费、无需 key,但需要服务器能联外网。拉不通时用最后一次有效值、不影响记账;汇率可在 `/admin/fx` 手动补,持仓也可转为手填市值。

---

没找到答案?提 [Issue](https://github.com/LuoDi-Nate/financial-management/issues) 或看 [`deploy/README.md`](../deploy/README.md)。
