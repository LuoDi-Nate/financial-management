# 家庭账房 · 部署

参考 openclash / vaultwarden 等自托管项目的部署形态:**服务器上 git clone + 一行命令**,无所谓本机推送、SSH 串行执行那些复杂套路。

## 部署条件

- 一台公网 Linux 服务器:Ubuntu 22+ / Debian 12+ / RHEL 9+ / Alibaba Cloud Linux 都行
- 你能 SSH 进去 + 有 sudo
- 一个公网 80 端口(可选 443),能从公网拉 apt 包
- 内存:应用 + MySQL 常驻约 1G;**在服务器上编译时还要再空出约 1G**(`deploy.sh` 编译前会检查,不够就停下说明,见下面「迭代」第 1 步)。小内存机器可以在别处编好 jar 用 `--jar` 传上来

**或** macOS 本机(本地开发 / 个人自用 · 见下面 [macOS 部署](#macos-本地部署))。

---

## 首次部署(2 步)

```bash
# 1. 把本仓库拉到服务器上(任意位置;建议 /opt/src 或 ~)
sudo apt install -y git    # Ubuntu/Debian;RHEL 系是 dnf
git clone https://gitlab.com/xblteam/financial-management.git
cd financial-management

# 2. 跑一次部署
sudo bash deploy/deploy.sh
```

`deploy.sh` 会:

1. 装 JDK 21 / Maven / MySQL 8 / nginx / 辅助工具
2. 创建 finance 系统用户 + 目录(`/opt/finance`、`/var/finance/uploads`、`/var/backup/finance`)
3. 建 MySQL 库 + 用户(交互输密码或自动生成 24 字符)
4. 写 `/etc/finance.env`(系统配置,640 权限)
5. 编译 jar(`mvn package`)
6. 应用所有 `V*__*.sql` 迁移
7. 设种子用户临时密码(`diwa` / `wangergou` + 你设的密码,登入后强制改)
8. 清掉 V3/V4/V5 灌的 dev 演示数据(`sentinel` + "真实数据探测"双保险,不会误删真用户数据)
9. 装 systemd unit(`finance.service`)+ NOPASSWD sudoers
10. 启服务 + `/health` 健康检查
11. 装 nginx 反代 :80 → :20000(交互式 prompt;不要也行)

完事后浏览器访问 `http://<server-ip>/` 即可。

---

## 后续发版迭代(1 步)

每次代码更新后,**在服务器上**:

```bash
cd ~/financial-management              # 仓库目录
git pull                                # 拉新代码
sudo bash deploy/deploy.sh              # 一键迭代
```

`deploy.sh` 检测到已上线,进入「迭代模式」,做:

1. **先编译**新 jar(v1.34.1 起放在迁移前面 —— 编不出来,库和线上都没动),带内存保护:
   - 编译前看可用内存,**不到 1000MB 就停下**并说明(在本机编译可能把整台机器拖到卡死:磁盘读打满、SSH 都连不上)
   - 编译限堆 `MAVEN_OPTS=-Xmx768m`(你自己设了 `MAVEN_OPTS` 就用你的)
   - 编译期间有看门狗:可用内存连续两次低于 300MB 就停掉编译(这时还没换 jar,线上继续跑旧版)
2. `mysqldump | gzip` 备份 → `/var/backup/finance/pre-deploy-{ts}.sql.gz`(+ `gunzip -t` 完整性校验)
3. 列出待 apply 的增量 `V*.sql`,**交互确认才执行**(`schema_history` 表防重)
4. 旧 jar 备份到 `app.jar.prev` + 新 jar 切入
5. `systemctl restart finance`
6. `/health` 30s 轮询 + `/login` 烟测
7. **换 jar 之后失败 → 自动 `app.jar.prev → app.jar` + restart + 健康复检**(DB 备份保留不动,因 schema 多数 backward-compat,老 jar 兼容新表)

每一步都打开始时间和上一步用时;全程输出另存到 `/var/log/finance-deploy-<时间>.log`(卡住时能看出卡在哪一步)。

**小内存服务器 / 不想在服务器上编译**:在别的机器(同一个版本)`mvn -DskipTests package`,把 `target/app.jar` 传上来:

```bash
sudo bash deploy/deploy.sh --jar /tmp/app.jar     # 核对 jar 里的版本号 == 仓库检出的版本,对不上不换
```

可调:`FINANCE_BUILD_MIN_AVAIL_MB`(编译前要求的可用内存,默认 1000)· `FINANCE_BUILD_KILL_AVAIL_MB`(看门狗阈值,默认 300)· 确认要硬编:`FINANCE_BUILD_FORCE=1 sudo -E bash deploy/deploy.sh`。

---

## 回滚(失败时一行)

```bash
sudo bash deploy/rollback.sh
sudo bash deploy/rollback.sh --expect-current 1.34.1    # 推荐:线上跑的不是这一版就拒绝(新 jar 根本没换上去时,回滚会换成更老的版本)
bash deploy/rollback.sh --expect-current 1.34.1 --check-only   # 只看能不能回滚,什么都不动
```

把 `app.jar.prev` 还原到 `app.jar` + restart + 健康检查。**不动 DB**(若 DB 也要还原见脚本输出的 `gunzip ... | mysql` 提示)。

---

## 常用命令

```bash
sudo systemctl status finance          # 服务状态
sudo journalctl -u finance -f          # 实时日志
sudo systemctl restart finance         # 手动重启(deploy.sh 内部已经做)
mysql -ufinance -p$DB_PASS finance     # 进 DB(密码在 /etc/finance.env)
ls /var/backup/finance/                # 看 DB 备份历史
```

---

## 自动备份

`deploy.sh` 装了 `finance-backup.timer`(systemd 定时器),每天 03:30 自动 `mysqldump | gzip` 到 `/var/backup/finance/`,默认保留 56 天(`RETENTION_DAYS` 在 `/etc/finance.env`)。

```bash
sudo systemctl list-timers finance-backup  # 看下次跑的时间
sudo systemctl start finance-backup        # 手动触发一次
```

---

## HTTPS(可选,但 prod 推荐)

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d your-domain.com
```

certbot 自动改 `finance.conf` 加 `listen 443 ssl` + 配 cron 续签。`deploy.sh` 重跑时检测到 `ssl_certificate` 会避让 certbot 改的配置,不冲突。

---

## 故障排查

| 症状 | 解 |
|---|---|
| Linux:`deploy.sh` 步 7 mysql 失败 | `sudo mysql` 看能不能进(Ubuntu 默认 socket 鉴权) |
| Linux:`deploy.sh` 步 9「可用内存不够,没有编译」 | 同机别的常驻进程占了内存。先停掉它们再跑;或在别处编好 jar 用 `--jar` 传上来。**别硬编** —— 内存不够时编译会把整机拖到卡死(SSH 都连不上,只能控制台重启) |
| Linux:部署中整机卡死(SSH / 网页都超时,CPU 不高,云监控报磁盘读打满) | 内存耗尽、系统反复从盘读回程序代码。控制台重启后线上会是旧版(编译在换 jar 之前)。另查 `sysctl vm.swappiness`:为 0 时内核宁可抖动也不用 swap,建议改 10 |
| Linux:`deploy.sh` 步 14 服务 30s 不起 | 看 `journalctl -u finance --no-pager -n 100`(脚本自动打了 30 行);DB 密码错 / 端口被占最常见 |
| macOS:`deploy.sh` 步 6 ERROR 1045 Access denied for root | root 设了密码 · 重跑输入密码;真忘了:`brew services stop mysql && mysqld_safe --skip-grant-tables &` 重置 |
| macOS:登入后看到 demo 数据 | 你 sentinel 已写但 TRUNCATE 没跑 · 删 `~/finance/.prod-cleaned` + 重跑 `deploy/deploy.sh` |
| macOS:服务起不来 | `tail -f $HOME/finance/logs/app.log` 或前台跑 `bash $HOME/finance/start.sh` 看输出 |
| 切币种 USD 显示 ¥ | `fx_rate` 缺,见 `/admin/fx`;或服务器拉不通 frankfurter.dev(防火墙) |
| 自动备份没生成 | Linux:`sudo systemctl status finance-backup.timer` · macOS:用户自己 `crontab -e` 加 mysqldump |
| Linux:重置成"刚装好"状态 | 删 `/opt/finance/.prod-cleaned` + 重跑 `deploy.sh`(真实数据探测会拦你,见警告 SQL) |

---

## 文件清单

```
deploy/
├── deploy.sh                       ← 直装唯一入口(Linux+Mac · Darwin 自动转 _deploy-macos.sh)
├── _deploy-macos.sh                ← deploy.sh 的 macOS 内部实现($HOME/finance · brew · 无 sudo · 别直接调)
├── docker-up.sh                    ← Docker 唯一入口(全平台 · 自检+生成密钥+起+验健康)
├── finance.macos.plist.template    ← macOS launchd 自启模板(可选)
├── _build-guard.sh                 ← deploy.sh 的编译内存保护(编前查内存 · 限堆 · 看门狗 · 核对 --jar)· 测试 scripts/test-deploy-guards.sh
├── rollback.sh                     ← 紧急回滚(Linux · --expect-current 核对线上版本)
├── nginx-setup.sh                  ← nginx 单独配置(deploy.sh 内部会调,macOS 不用)
├── finance.service                 ← systemd unit 模板(Linux)
├── finance.env.example             ← /etc/finance.env 配置示例(参考用)
├── nginx-finance.conf.example      ← nginx 反代模板(__PORT__ __SERVER_NAME__ 占位)
├── backup.sh                       ← 备份脚本(finance-backup.timer 调)
├── finance-backup.{service,timer}  ← systemd 定时备份
├── gen-presets.sh                  ← 一次性:生成 4 套图标 PNG
├── gen-icons.sh                    ← 历史:生成默认 icon PNG(已被 gen-presets 取代)
└── README.md                       ← 本文件
```

---

## macOS 本地部署

适合:本地开发跑通 · 个人 mac 自用 · 不需要公网访问 / nginx / systemd。

**前提**(自己装):

- [Homebrew](https://brew.sh)(`/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"`)
- 当前 macOS 用户(脚本不需要 sudo · 不创建系统用户)

**部署**:

```bash
git clone https://gitlab.com/xblteam/financial-management.git
cd financial-management
bash deploy/deploy.sh         # 直装唯一入口 · macOS 自动分流到内部实现(无需 sudo)
```

脚本会(12 步幂等):
1. `brew install openjdk@21 maven mysql`(已装则跳过)· `brew services start mysql`
2. 探测 MySQL root 鉴权:无密码(brew 默认)or 密码(`mysql_secure_installation` 装过)· 后者 prompt 3 次重试
3. 建 `finance` 库 + 用户(密码 prompt 或自动生成 24 字符)
4. 写 `$HOME/.finance/finance.env`(600 权限)
5. 跑 `V*__*.sql` 迁移(共享 `db/apply.sh` · sha256 portability shim · macOS 用 `shasum -a 256`)
6. **首装清 dev 演示数据**(V3-V5 灌的账户/周期/流水/快照)· sentinel `$HOME/finance/.prod-cleaned` + 真实数据探测(audit > 50 或 extra members > 0 拒绝 TRUNCATE)· 留 family + member(`diwa` / `wangergou`)种子
7. `mvn package` 编译 jar
8. 拷到 `$HOME/finance/app.jar` + 生成 `$HOME/finance/start.sh`(内部 source env + exec java)

**启动**:

```bash
bash $HOME/finance/start.sh                                                # 前台
nohup bash $HOME/finance/start.sh > $HOME/finance/logs/app.log 2>&1 &      # 后台
```

浏览器 `http://127.0.0.1:20000/`(端口在 `~/.finance/finance.env` 改),默认账号 `diwa` / `wangergou` + 临时密码 `demo1234`(或脚本时你设的)。

**(可选)launchd 开机自启**:

```bash
sed "s|{{HOME}}|$HOME|g" deploy/finance.macos.plist.template > ~/Library/LaunchAgents/com.family.finance.plist
launchctl load -w ~/Library/LaunchAgents/com.family.finance.plist
launchctl list | grep com.family.finance       # 见 PID 即跑
# 卸载:launchctl unload -w ~/Library/LaunchAgents/com.family.finance.plist
```

**macOS vs Linux 差异速查**:

| 项 | Linux | macOS |
|---|---|---|
| 入口 | `sudo bash deploy/deploy.sh` | `bash deploy/deploy.sh`(无 sudo) |
| 应用目录 | `/opt/finance` | `$HOME/finance` |
| env | `/etc/finance.env`(640 root:finance) | `$HOME/.finance/finance.env`(600) |
| 服务管理 | systemd(`finance.service`) | launchd(`com.family.finance.plist` · 可选) |
| 日志 | `journalctl -u finance -f` | `tail -f $HOME/finance/logs/app.log` |
| 包管理 | apt / dnf | brew |
| 反代 | nginx :80 → :SERVER_PORT | 无(直接访问 :SERVER_PORT) |
| 应用用户 | `finance` 系统用户 | 当前 macOS 用户 |

**迭代发版**:跟 Linux 一样 `git pull && bash deploy/deploy.sh`,脚本检测到 `~/finance/app.jar` 自动走迭代分支(mysqldump 备份 + 切 jar)。重启需手动:`pgrep -f $HOME/finance/app.jar | xargs kill && nohup bash $HOME/finance/start.sh > $HOME/finance/logs/app.log 2>&1 &`。

**MySQL root 提示**:brew 默认装好 root 无密码;装过 `mysql_secure_installation` 或用 DMG 装的会有密码。脚本会自动探测 → 失败时 prompt 3 次重试(回车 = 试无密码,输入 = 试密码)。3 次都错 die 并给出重置指引(`brew services stop mysql && mysqld_safe --skip-grant-tables &`)。

---

# Docker 部署(v0.7 · 推荐)

比直装(deploy.sh)更干净:不污染宿主机、跨平台(Linux / macOS / NAS · amd64 + arm64)、升级一条命令。compose 只起 **app + MySQL + 备份 sidecar**,**反代/HTTPS 由你在前面自己挂**(见下「反代/HTTPS」)。

## 全新机一键起

```bash
git clone https://github.com/LuoDi-Nate/financial-management.git
cd financial-management
bash deploy/docker-up.sh          # 自检环境 + 生成密钥 + 起服务 + 验健康,一条命令
```

`docker-up.sh` 是 **Docker 渠道的唯一入口**(全平台一条命令)。它会逐项自检并在卡住时给出可复制的修复命令:① docker 装没装 ② 引擎(daemon)起没起 ③ Compose **V2** 在不在(`docker compose` 优先,回退 V2 版 `docker-compose`,老 V1 直接拒并教你装)④ 镜像拉不到就本地源码构建 ⑤ 起完轮询 `/health`;`.env`(随机 DB/root/REMEMBER_ME_KEY)也由它自动生成。macOS 上 Docker Desktop / OrbStack / colima 各种装法都适配。

> **Windows**:装 [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/)(WSL2 后端,Home 版也支持)后,在 **WSL2(Ubuntu)终端**里 `git clone` 并跑**同一条** `bash deploy/docker-up.sh` —— WSL2 就是 Linux,脚本原样适用,`docker compose` 随 Docker Desktop 自带。仓库放 WSL2 文件系统内(`\\wsl$`,别放 `C:\`)性能才正常。前置一次性:BIOS 开虚拟化 + `wsl --install` + 重启(GUI/重启这步任何脚本都替不了)。

<details><summary>想手动控制每一步(老手)</summary>

```bash
cp .env.example .env              # 手改密钥(docker-up.sh 会自动随机生成,手动则自己填)
docker compose up -d              # 有预构建镜像就拉,没有就 docker compose build 后再 up
```
报 `unknown shorthand flag: 'd' in -d` → 这台机 Compose V2 没装好,见下「Compose V2 怎么装」。
</details>

<details><summary>Compose V2 怎么装(报 <code>unknown shorthand flag: 'd'</code> / 脚本说「只找到老版 docker-compose」时看这里)</summary>

本项目的 `docker-compose.yml` 是**无 `version:` 字段的 V2 写法**,V1(`docker-compose` 1.x,已停止维护)解析不了。
判断标准只有一条:**`docker compose version`(中间是空格)能打印 `v2.x`**。

**Linux** —— apt 里那个 `docker-compose`(1.29.x)是 V1,**装了也没用**,要装的是 compose **插件**:

```bash
sudo apt-get install -y docker-compose-plugin      # Debian/Ubuntu(需已配 Docker 官方源)
sudo dnf install -y docker-compose-plugin          # Fedora/RHEL
sudo apt-get install -y docker-compose-v2          # Debian 12+ / Ubuntu 24.04+ 自带 V2 包

# 不想动仓库,直接下二进制(任何发行版都行):
mkdir -p ~/.docker/cli-plugins
curl -SL "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)" \
  -o ~/.docker/cli-plugins/docker-compose
chmod +x ~/.docker/cli-plugins/docker-compose
```

**macOS** —— Docker Desktop / OrbStack 自带 V2;用 Homebrew 装的纯 docker CLI 要自己软链:

```bash
brew install docker-compose
mkdir -p ~/.docker/cli-plugins
ln -sfn "$(brew --prefix)/opt/docker-compose/bin/docker-compose" ~/.docker/cli-plugins/docker-compose
```

**引擎连不上(Linux)** —— 分两种,别混:

```bash
sudo systemctl start docker        # 服务没起
sudo usermod -aG docker $USER      # 起了但当前用户没权限(改完 newgrp docker 或重登)
```

</details>

浏览器开 `http://<宿主>:20000`(默认只发布到 `127.0.0.1`,公网访问请前置反代)。

**首次登录**:种子账号 **`diwa`** 或 **`wangergou`**,临时密码默认 **`demo1234`**(在 `.env` 的 `SEED_ADMIN_PASSWORD` 可自定义,仅首装生效),**首次登录后强制改密**。`docker-up.sh` 起完会直接把这行打印出来;也可 `docker compose logs app | grep -A4 首次登录` 看启动横幅。(机制:Docker 跑 `prod` profile,`ProdSeedRunner` 在首启时把种子占位密码设为该临时密码;已初始化的库不会被改。)

LLM key / 短信 aksk / 阈值等运营参数,登录后走 `/admin/integrations` 配(存数据库,不在 .env)。

- 数据持久化在命名卷:`db-data`(库)/ `uploads`(logo)/ `backups`(每日 mysqldump)。`docker compose down` 不删卷,数据还在。
- 升级:`git pull && docker compose pull && docker compose up -d`(entrypoint 自动跑增量迁移,幂等)。
- 镜像源码自构建:`docker compose build`(基础镜像 maven / temurin / mysql 均有 arm64,Apple Silicon 原生构建)。

## 从已部署(systemd / macOS)迁移到 Docker

存量用户**数据零丢**迁过来,一条命令自动识别 systemd 还是 macOS:

```bash
# Linux systemd(读 /etc/finance.env,要停 finance 服务,需 sudo):
sudo bash deploy/migrate-to-docker.sh

# macOS(读 ~/.finance/finance.env,会提示你先停掉旧的前台 java / launchd):
bash deploy/migrate-to-docker.sh
```

流程:mysqldump 备份 → 生成 .env(**携带原 REMEMBER_ME_KEY**,登录态不丢)→ 停旧 app 腾端口 → 起 db 容器灌 dump(含 `schema_history`,**迁移不会重放**)→ 搬 uploads → 起 app → 验 `/health`。

**回滚**:全程不删旧部署。不满意 → `docker compose down` + 重启旧应用(systemd:`systemctl start finance`)。满意后再 `systemctl disable finance`(或停 brew mysql)释放宿主资源。

> 注:`deploy.sh`(systemd 直装)路径**继续保留支持**,不想迁的人可不动。

## 反代 / HTTPS(compose 不内置,自己挂)

app 默认只在 `127.0.0.1:20000`。在前面挂个反代即可。两段照抄:

**nginx + certbot**
```nginx
server {
    listen 80;
    server_name your.domain.com;
    location / {
        proxy_pass         http://127.0.0.1:20000;
        proxy_http_version 1.1;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
    }
}
# 然后:sudo certbot --nginx -d your.domain.com --redirect
```

**Caddy(自动 HTTPS,零配置)** —— Caddyfile:
```
your.domain.com {
    reverse_proxy localhost:20000
}
```

## 日常运维(Docker)

| 要做什么 | 命令 |
|---|---|
| 看日志 | `docker compose logs -f app`(只看错误:`... \| grep -i error`) |
| 停 / 起 / 重启 | `docker compose stop` / `start` / `restart app` |
| 更新 | `git pull && bash deploy/docker-up.sh` |
| 改配置 | 编辑 `.env` 后 `docker compose up -d`(运营参数走管理页,改 `.env` 无效) |
| 立刻备份 | `bash deploy/backup-now.sh [输出目录]` —— 备完会 `gunzip -t` 校验,校验不过删掉不留假备份 |
| 从备份恢复 | `bash deploy/restore.sh` —— 列出备份让你选,**灌入前先把当前库另存 `before-restore-*` 当退路** |
| 诊断 | `bash deploy/doctor.sh` —— 只读 + 已脱敏,可直接贴 issue |
| 数据在哪 | Docker 命名卷:`docker volume ls \| grep db-data` |
| 彻底重来 | `docker compose down -v && bash deploy/docker-up.sh` ⚠ 先备份 |

**备份节奏**:Docker 是 `backup` 容器每 **24 小时**(容器启动起算)dump 到 `backups` 卷(`finance-*.sql.gz`,保留 `RETENTION_DAYS` 天,默认 56);
systemd 直装是 `finance-backup.timer` 每天 03:30 到 `/var/backup/finance/`。
**自动备份最多是"昨天那一份"** —— 重要操作前自己 `backup-now.sh` 一次。

恢复的确认闸门:交互时手输 `RESTORE`;非交互(灾备演练)用 `FINANCE_RESTORE_CONFIRM=RESTORE`;两者都没有则拒绝执行。

---

## 怎么更新到新版本(Docker)

```bash
git pull && bash deploy/docker-up.sh
```

**同一个脚本既是首装也是更新**(幂等,可反复跑;数据在命名卷,更新不动数据)。v1.6.25 起它会明确报告版本结论:

| 情况 | 输出 |
|---|---|
| 更新成功 | `✓ 已更新:v1.6.24 → v1.6.25` |
| 已经最新 | `· 版本无变化:仍是 v1.6.25` + `✓ 已是最新发布版` |
| 落后且镜像未就绪 | `⚠ 你在跑 v1.6.24,但最新发布版是 v1.6.25` + 「CI 约 12 分钟,过几分钟重跑」 |
| 镜像早于 v1.6.25 | `· 读不到版本(/health 不返回 version)` + 「最新发布版是 vX」 |

**两个必须说清的机制**(v1.6.25 前没说清,用户因此踩过坑):

- **`git pull` 的新代码不进容器** —— app 来自 GHCR 预构建镜像;`git pull` 只影响 compose 文件与部署脚本本身
  (它们会随版本变:v1.6.21 换了 db 镜像源、v1.6.22 改了健康检查判据)。要立刻用仓库代码:`docker compose up -d --build`。
- **打 tag 到镜像可用之间有约 12 分钟 CI 构建** —— `:latest` 是构建完才更新的。看到发布消息立刻更新,大概率仍拉到旧镜像;
  脚本会替你分辨「已经最新」与「镜像还在构建」。

确认当前版本(不需要登录):`curl -s http://127.0.0.1:20000/health` → `{"status":"UP","version":"…"}`。
不想让脚本联网查最新版:`FINANCE_NO_UPDATE_CHECK=1`。

## 数据卷密码不匹配(`ERROR 1045 Access denied`)

**症状**:`docker-up.sh` 报「应用 90s 内没就绪」,`docker compose logs app` 一直刷 `Access denied for user 'finance'`,容器不停重启。

**原因**:MySQL 只在**第一次初始化数据卷**时写入 `MYSQL_USER` / `MYSQL_PASSWORD`;之后改 `.env` 不会同步进去。而 Docker 的**命名卷不随仓库目录消失** —— 重新克隆仓库 → `.env` 生成新随机密码 → 与卷里老密码不匹配。

**处理(v1.6.22 起脚本自己做)**:`bash deploy/docker-up.sh` 会主动验一次账号,进不去就用 `mysqld --init-file` 临时以恢复模式起一次数据库,把 `.env` 里的新密码写进已有库 —— MySQL 官方的密码重置手法,**不需要旧密码、不动任何业务数据**,修完自动继续。

同步失败或你不同意时,脚本**停在原地不动数据**,两条出路:① 放回旧 `.env`(数据完整保留,最稳);② 确认那个库从没真正用过 → `docker compose down -v` 重来(**会删全部数据、不可恢复**)。自动化环境里 `FINANCE_ASSUME_YES=1` 只放行不删数据的同步,**永远不会**触发删卷。

**顺带**:db 的健康检查与容器入口的就绪检查都已改成**真实查询**(`SELECT 1`)。原先用的 `mysqladmin ping` 在密码错误时**也返回成功**(MySQL 语义:服务器有应答就算活着),会让密码不对的库照样报 `Healthy`、入口照样打印「MySQL 就绪」,把故障推到很后面才爆。

## 国内镜像加速 / Apple Silicon

**v1.6.21 起,大陆装机默认不需要配任何东西。** 这一节现在是**兜底**说明。

原先的死路:`db` 服务写死 `mysql:8.0`,大陆拉 Docker Hub 会一直超时,而修复要用户手改 Docker 引擎配置 —— 对非技术用户等于劝退(真实发生过)。现在:

- **数据库镜像默认取 GHCR 上的副本** `ghcr.io/luodi-nate/financial-management-mysql:8.0`(由 `.github/workflows/docker-publish.yml` 的 `mirror-mysql` job 用 `docker buildx imagetools create` 把官方多架构 manifest 原样复制过去)。**和 app 镜像同一个源、大陆直连**,默认安装路径因此完全不碰 Docker Hub。
- `docker-up.sh` 会按 **GHCR 副本 → Docker Hub 官方 → 配镜像源后重试** 顺序探,选定后把结果写回 `.env` 的 `MYSQL_IMAGE`(之后你手敲 `docker compose` 也不会又去撞不通的源)。
- 想强制走官方源(海外机器 / 已配好加速):`.env` 里设 `MYSQL_IMAGE=mysql:8.0`。
- ⚠ 仍然成立的一条:**`docker compose build` 救不了拉不动的问题** —— 本地构建要从 Docker Hub 拉 `maven` / `eclipse-temurin` 基础镜像,同样会被卡;`docker-up.sh` 走到本地构建分支时会先探 JDK 基础镜像,拉不动就走下面的镜像源修复。

下面是**手动**配国内镜像源的办法(`registry-mirrors` **只对 Docker Hub 生效**,GHCR 不受影响),用于:OrbStack(脚本不自动改它)、你拒绝了脚本代劳、或已有别的镜像源配置需要自己合。写哪里、怎么重启按装法分:

**Linux 原生 Docker** —— 写 `/etc/docker/daemon.json`(已有就把 `registry-mirrors` 并进去,**别覆盖**其它配置),再 `sudo systemctl restart docker`:
```json
{ "registry-mirrors": ["https://docker.m.daocloud.io", "https://docker.1ms.run"] }
```

**macOS**(引擎在虚拟机里,**不读宿主的 `/etc/docker/daemon.json`**,所以上面那条对 Mac 无效)——按装法选一种:
- **colima**:编辑 `~/.colima/default/colima.yaml`,在 `docker:` 段加:
  ```yaml
  docker:
    registry-mirrors:
      - https://docker.m.daocloud.io
      - https://docker.1ms.run
  ```
  然后 `colima restart`(约 1-2 分钟)。
- **OrbStack**:`orb config docker` 打开配置,加入 `registry-mirrors`(同上 JSON),存盘后 `orb restart docker`。
- **Docker Desktop**:Settings → Docker Engine,把 `registry-mirrors` 并进 JSON,Apply & Restart。

配好后 `docker compose up -d`(或重跑 `bash deploy/docker-up.sh`)。
> **`docker-up.sh` 会代你做上面这些**(v1.6.21):两个源都拉不动时它问一句 `[Y/n]`,同意后按引擎类型自己配好并重启,再自动重试 —
> - **colima**:写 VM 内的 `/etc/docker/daemon.json`(+ 尽力把 `docker:` 段补进 `~/.colima/default/colima.yaml`,否则下次 `colima restart` 会被 colima 按 yaml 重写抹掉),优先在 VM 内 `systemctl restart docker`;
> - **Docker Desktop**:合并进宿主 `~/.docker/daemon.json`,`osascript` 退出 + `open -a Docker` 重启;
> - **Linux 原生**:合并进 `/etc/docker/daemon.json` + `systemctl restart docker`。
>
> 三条硬规矩:**已有 `registry-mirrors` 就不覆盖**(可能是你自己配的别的源)、改前一律留 `.bak`、**OrbStack 不自动改**(配置机制不稳定,宁可退回上面的手动步骤也不写坏你的引擎配置)。非交互环境(无 tty)默认不动你的机器,要自动化就设 `FINANCE_ASSUME_YES=1`。

- **macOS / Apple Silicon**:Docker Desktop / OrbStack / colima 均可;`docker compose build` 原生 arm64,预构建镜像也是 amd64+arm64 多架构,`pull` 自动取对的那个。(Apple Silicon 拉 `mysql:8.0` 同样可能被 Docker Hub 限速,需上面的镜像源。)
- **`brew install docker` 后报「连不上 daemon / Cannot connect to the Docker daemon」**:brew 装的 docker **只是命令行客户端,没有引擎**——Mac 上 docker 引擎跑在一个小 Linux 虚拟机里,要单独装一个。最省事的命令行方案:`brew install colima docker-compose && colima start`(第一次起约 1-2 分钟),再 `bash deploy/docker-up.sh`。或装带界面的 `brew install orbstack`(/ Docker Desktop)打开 App 即可。`docker-up.sh` 已能自检这一步并给出对应你机器的命令。
- **`docker compose up -d` 报 `unknown shorthand flag: 'd' in -d`**:这台机的 Compose V2 插件没装好,docker 没把 `compose` 当子命令,把 `-d` 当成了顶层 flag。处理:
  - Docker Desktop / OrbStack 自带 V2 —— 确认它装好且在运行(`docker compose version` 应有输出)。
  - Homebrew 装的纯 docker CLI(常配 colima):`brew install docker-compose`,再按 caveat 软链到 `~/.docker/cli-plugins/docker-compose`,`docker compose`(带空格)才生效。
  - 临时绕过:直接用老版连字符写法 `docker-compose up -d`(我们的 compose 文件两者都兼容)。`deploy/docker-up.sh` 与 `deploy/migrate-to-docker.sh` 已自动探测这两种写法(V2 优先)。
