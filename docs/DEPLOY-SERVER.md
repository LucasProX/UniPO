# 生产服务器部署（49.234.21.63）

> 单机整套栈：MySQL + MinIO + 后端 + 前端 + nginx，全部 docker compose 管理。
> IP 直连、**不挂 Caddy**（没有域名也没有证书），所以 `AUTH_COOKIE_SECURE=false`。

## 1. 机器

| 项 | 值 |
|---|---|
| 地址 | `49.234.21.63`（腾讯云，Ubuntu 22.04.5 LTS，x86_64） |
| 规格 | 4 vCPU / 3.7G 内存 / 40G 盘（额外加了 2G swap，见 `/swapfile`） |
| 登录 | `ssh ubuntu@49.234.21.63`（sudo 免密） |
| 代码位置 | `/opt/unipo`（**不是 git checkout**，服务器连不上 GitHub，见 §5） |
| 公网端口 | 80（前端 + /api 反代）、22（SSH）。**其他端口都没开** |
| Docker | 29.9.2 + compose v5.6.0，源用 `mirrors.cloud.tencent.com/docker-ce` |

## 2. 跑着什么

`docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml --profile local-db`

| 容器 | 端口 | 说明 |
|---|---|---|
| `unipo-frontend` | `0.0.0.0:80` | nginx 提供 SPA，`/api/` 反代到 `backend:8080` |
| `unipo-backend` | 仅 compose 内网 8080 | Spring Boot，healthcheck = `/actuator/health` |
| `unipo-mysql` | `127.0.0.1:12306` | MySQL 8.4，数据在 volume `unipo_mysql_data` |
| `unipo-minio` | `127.0.0.1:9000` / `9001` | 对象存储，图片走 `/api/media/{id}/content` 出去 |

后端连库走 compose 网络名（`jdbc:mysql://mysql:3306/biecuoguo`），**不是** 127.0.0.1 —— 容器里的 127.0.0.1 是它自己。
所以 `deploy-ip.sh` 用不上：它在宿主机上预检 `SPRING_DATASOURCE_URL` 的 host:port，而 `mysql` 这个名字宿主机解析不了。

## 3. 首次部署（已经做过一次，这里是记录）

```bash
# 1. 装 docker（腾讯云 apt 源）+ 加 2G swap
# 2. 传代码（本机执行）：排除 .env / .git / node_modules / ios
tar -czf deploy.tar.gz --exclude=./.git --exclude=./.env --exclude=./node_modules --exclude=./ios --exclude=./.logs .
scp deploy.tar.gz ubuntu@49.234.21.63:/opt/unipo/
# 3. 解压 + 写 .env（密钥用 openssl rand 生成，见 §4）
# 4. 起数据库
sudo docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml --profile local-db up -d mysql minio
# 5. 构建并起应用
sudo docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml build backend frontend
sudo docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml up -d --no-build backend frontend
```

**打包一定要 `--exclude=./.env`**：仓库根的本地 `.env`（JWT 是 `local-dev-only-secret-01...`、管理员密码 `Admin@123456`）
会被 tar 带上，解压后直接覆盖服务器的生产 `.env`。第一次部署就踩了，见 DEVLOG 2026-10-21。

## 4. .env 要点（文件在服务器 `/opt/unipo/.env`，权限 600，永不入库）

必填且**没有默认值**的三项，缺一个后端就拒绝启动（`config/StartupSafetyGuard.java`）：

- `JWT_SECRET` — `openssl rand -base64 48`
- `BOOTSTRAP_ADMIN_PASSWORD` — ≥32 字符随机串（`openssl rand -hex 32`），只在**管理员不存在时**用来建号
- `SMTP_HOST` / `SMTP_USERNAME` / `SMTP_PASSWORD` — QQ 邮箱：`smtp.qq.com:465` + 授权码（不是 QQ 密码）

部署相关的其他几项：

```dotenv
DOCKER_REGISTRY=docker.m.daocloud.io/library/        # 国内拉镜像
MINIO_IMAGE=docker.m.daocloud.io/bitnamilegacy/minio:latest   # minio 不走 DOCKER_REGISTRY，单独一个变量
MYSQL_PUBLIC_BIND=127.0.0.1                          # 数据库不要暴露公网
AUTH_COOKIE_SECURE=false                             # 纯 HTTP 部署必须 false，否则「登录成功但没登录」
APP_CORS_ALLOWED_ORIGINS=http://49.234.21.63
RATE_LIMIT_TRUST_FORWARDED_FOR=true                  # 前面有 nginx，不信任 XFF 就全站共用一个桶
STATE_BACKEND=memory                                 # 单实例；上多实例必须换 redis
```

## 5. 更新代码

服务器**连不上 GitHub**（`git ls-remote` 报 `RPC failed; curl 16 Error in the HTTP2 framing layer`），所以不能 `git pull`。
流程是本机打包 → scp → 服务器重新构建：

```bash
# 本机
tar -czf deploy.tar.gz --exclude=./.git --exclude=./.env --exclude=./node_modules \
    --exclude=./backend/target --exclude=./frontend/dist --exclude=./ios --exclude=./.logs .
scp deploy.tar.gz ubuntu@49.234.21.63:/tmp/
# 服务器
ssh ubuntu@49.234.21.63
cd /opt/unipo && tar -xzf /tmp/deploy.tar.gz && rm /tmp/deploy.tar.gz
sudo docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml build backend frontend
sudo docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml up -d --no-build backend frontend
```

后端镜像里跑的是 `mvn package`，4 核机器上首次构建约 8 分钟（有 2G swap 兜着）。
数据库 schema 由 Flyway 在启动时自动迁移，**不要手工改表**。

## 5.1 客户端怎么连（测试期：不挂域名，直连 IP）

**Web**：浏览器打开 `http://49.234.21.63` 就行。前端和 API 同源（nginx 把 `/api/` 反代到后端），
所以 `lib/api.ts` 里的相对路径 `/api/v1` 不用改，Cookie 也是同源下发的。

**iOS**：`ios/UniPO/Resources/Info.plist` 的 `API_BASE_URL` = `http://49.234.21.63/api/v1`（`ios/project.yml`
里同一个值，改完跑 `xcodegen generate`）。App 走 Bearer + Keychain，不碰 Cookie，所以 `AUTH_COOKIE_SECURE=false`
对它没有影响。

- **ATS**：iOS 26.5 模拟器实测，只有 `NSAllowsLocalNetworking` 时，访问这个公网 HTTP IP 会返回 `NSURLErrorDomain -1022`。
  `ios/project.yml` 已为 `49.234.21.63` 单独配置 `NSExceptionDomains` 与 `NSExceptionAllowsInsecureHTTPLoads`，
  修改后必须重新生成、构建并安装 App；不使用全局 `NSAllowsArbitraryLoads`。正式发布前应切换 HTTPS 并移除测试例外。
- **模拟器**同样能直接连公网 IP，不必再改成 127.0.0.1。

**WebSocket（私信实时）**：`ws://49.234.21.63/api/v1/realtime/ws`。nginx 必须转发 `Upgrade` 头
（`proxy_http_version 1.1` + `Upgrade`/`Connection` + `proxy_read_timeout`），**否则握手到不了后端**，
表现是「消息不发也不报错」。已在 `frontend/nginx.conf` 配好，实测握手返回 101。

## 5.2 后端单独更新记录 2026-10-21

本次本机先从 `origin/main` 快进到 `85acdd9`，只把该提交中受 Git 管理的 `backend/` 打成归档上传。服务器仍使用 `/opt/unipo` 与既有 Compose 配置；构建期间旧后端继续服务，构建成功后只重建 `unipo-backend`，现有 nginx 网关、MySQL 与 MinIO 容器未重建。Web 用户端暂停开发不影响当前网关继续代理 iOS API 与 WebSocket。

| 记录 | 值 |
| --- | --- |
| 后端源码版本 | `85acdd9` |
| 新镜像标签 | `unipo-backend:release-85acdd9`，同时指向 `unipo-backend:latest` |
| 新镜像 ID | `sha256:64cab25415a1f5abfdba95adcfda6c206b827a91fe14e127f5fbd77dc48b0692` |
| 切换时间 | 2026-10-21 15:34，Asia/Shanghai |
| 回退镜像 | `unipo-backend:rollback-20261021T072642Z` |
| 部署前备份 | `/var/backups/unipo/backend-85acdd9-20261021T072642Z/` |
| 原源码目录 | `/opt/unipo/backend.rollback-20261021T072642Z` |

备份目录包含 `database.sql.gz`（MySQL 一致性导出，已通过 gzip 完整性检查）和 `backend-source.tar.gz`（原后端源码及 Compose 文件）。目录权限为 700，文件为 600。归档不包含本地 `.env`；服务器 `.env` 内容指纹在切换前后相同，并将其权限收紧为 600。

本机 `mvn -B -ntp package` 通过，47 项测试全部成功。服务器镜像构建成功，Flyway 当前版本仍是 V18，无新增迁移。切换后验证了容器 readiness、经 nginx 的公网 health、学校/分类、帖子游标与相邻页、统一响应包及 requestId、未登录接口与后台权限拦截、非法游标 400、内部 actuator 公网 404；WebSocket 返回 101，未认证连接收到 `auth-required`。首屏没有可读取的本地媒体样本，因此图片读取未作为本次验收证据；未运行会注册账号、发邮件或写入生产内容的全量冒烟。

本次回退不涉及 schema 降级，可恢复旧镜像后仅重建后端：

```bash
cd /opt/unipo
sudo docker image tag unipo-backend:rollback-20261021T072642Z unipo-backend:latest
sudo docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml \
    up -d --no-deps --no-build --force-recreate backend
sudo docker exec unipo-backend curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

后续更新只改后端时继续使用独立后端归档，先留数据库备份与旧镜像，再构建、切换和验证。若以后新增数据库迁移，先确认旧镜像与新 schema 的兼容性，再确定回退方式；不要自动覆盖生产数据库。

## 6. 排查

```bash
sudo docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml ps
sudo docker logs --since 10m unipo-backend | tail -50
sudo docker exec unipo-mysql mysql -ubiecuoguo -p"$MYSQL_PASSWORD" -e 'select version();' biecuoguo
curl -s http://127.0.0.1/actuator/health          # 只有 health 对外，其他 actuator 端点 404
```

| 现象 | 多半是 |
|---|---|
| 后端容器反复重启、日志里 `IllegalStateException` | `.env` 缺 JWT_SECRET / BOOTSTRAP_ADMIN_PASSWORD / SMTP_HOST |
| 登录成功但刷新后变游客 | `AUTH_COOKIE_SECURE=true` 但站点是 HTTP |
| 拉镜像 `i/o timeout` | 镜像没加 `docker.m.daocloud.io/` 前缀（minio 是 `MINIO_IMAGE`，不是 `DOCKER_REGISTRY`） |
| 构建 OOM | 看 `free -m` 的 swap 有没有生效 |

## 7. MySQL 备份与恢复（R03）

> **现状（2026-10-24 已安装）**：负责人同意后，脚本已装到 `/opt/unipo/scripts/ops/`（root 所有，哈希与仓库一致），
> `/etc/cron.d/unipo-backup` 每天 03:30 备份 `biecuoguo`、03:45 备份 `unipo_test`（留 7 份），03:40 / 03:50 跟着备份对应媒体桶（§7.7）。
> 首次手动备份：`biecuoguo` 10 KB、1 秒，`unipo_test` 36 KB、0 秒，`sha256sum -c` 全部 OK；在 cron 的最小环境里重跑一遍也成功。
> 备份和数据仍在同一块盘上，异机副本（§7.4）和备份失败告警（R05）还没做。

### 7.1 目标与边界

| 项 | 值 |
|---|---|
| RPO（可接受丢数据窗口） | ≤ 24 小时：每天一份逻辑备份，没有开 binlog 增量/时间点恢复，最坏丢最近一天 |
| RTO（从决定恢复到服务可用） | ≤ 4 小时：主要花在人工判断、保留现场和验证上，导入本身按首次服务器演练实测再填 |
| 来源 | 开发规划书 §12.3，**待演练确认的内部目标**，不是已达到的能力 |
| 备份内容 | 单个库的结构 + 数据 + routines/triggers/events（目前三者都为 0）+ `flyway_schema_history` |
| 不在备份里 | MinIO 对象（R04）、`/opt/unipo/.env` 和 `.env.test`（敏感配置单独受控保管）、Redis/内存会话 |

### 7.2 脚本做了什么

- `scripts/ops/mysql-backup.sh`：只读环境变量，没有参数，cron 行里看不到密码。
  在容器里跑 `mysqldump --single-transaction --quick --routines --triggers --events --hex-blob --no-tablespaces --set-gtid-purged=OFF`，
  **不带 `--databases`**，所以 dump 里没有 `CREATE DATABASE`/`USE`，恢复只会落进恢复时显式指定的库。
  gzip 后检查 mysqldump 的结尾标记 `-- Dump completed`，写 `<库名>-<UTC时间>.sql.gz` 和同名 `.sha256`（`sha256sum -c` 可直接校验），
  先写隐藏的 `.partial` 再改名，失败不留半截文件。目录 700、文件 600，同一个库同时只允许一个备份在跑（Linux 用 `flock`）。
- 保留按**份数**不按天数：成功后只删同名库、严格匹配命名的最旧几份，默认留 14 份。这样备份停了几天也不会把最后几份好的“过期”删掉。
- 密码默认用容器环境里的 `MYSQL_ROOT_PASSWORD`（在容器内转成 `MYSQL_PWD`），不经过任何命令行参数；
  也可以给专用账号写一个 600 权限的密码文件，脚本读出后只以变量名传给 `docker exec -e`。
- `scripts/ops/mysql-restore.sh`：先比对 `.sha256`（文件名和哈希都要对）、gzip 可读、结尾标记完整、没有切库语句，**全部通过才连 MySQL**。
  目标容器和库名必须显式给，没有默认值；目标库必须为空（不存在时要加 `--create-database`）。
  以下目标被视为“像生产”，不加 `--allow-production-target` 一律拒绝：容器 `unipo-mysql`、库名 `biecuoguo`、名字里有 `prod/production/live` 段、
  或者等于容器自己的 `MYSQL_DATABASE`。恢复完打印表数、Flyway 最新版本/失败数和 users/posts/comments/achievement_progress/conversations/messages 行数。
- 两个脚本都只认本机 Unix socket 的 Docker context，`DOCKER_HOST` 指向别处时直接拒绝。

| 环境变量（备份） | 默认 | 说明 |
|---|---|---|
| `UNIPO_BACKUP_DIR` | 必填 | 绝对路径，建议 `/var/backups/unipo/mysql` |
| `UNIPO_BACKUP_DATABASE` | 必填 | 生产 `biecuoguo`；测试库 `unipo_test` 可另配一行 |
| `UNIPO_BACKUP_CONTAINER` | `unipo-mysql` | |
| `UNIPO_BACKUP_RETENTION` | `14` | 该库保留的最新份数 |
| `UNIPO_BACKUP_MYSQL_USER` | `root` | 换成 `.env` 里的应用账号也能导（本机实测与 root 导出除结尾时间戳外完全一致），但它没有 `SHOW_ROUTINE`，以后有存储过程时会漏 |
| `UNIPO_BACKUP_PASSWORD_FILE` | 空 | 600/400、属于当前用户的普通文件；不给时 root/应用账号密码从容器环境读 |

### 7.3 服务器安装（需负责人同意后执行，本次未执行）

`/opt/unipo` 不是 git checkout。先把 `scripts/ops/mysql-ops-lib.sh`、`mysql-backup.sh`、`mysql-restore.sh` 三个文件随下一次部署包或单独 scp 到
`/opt/unipo/scripts/ops/`（保持可执行位），再手动跑一次，确认大小、耗时和校验：

```bash
cd /opt/unipo
sudo UNIPO_BACKUP_DIR=/var/backups/unipo/mysql UNIPO_BACKUP_DATABASE=biecuoguo scripts/ops/mysql-backup.sh
sudo sh -c 'cd /var/backups/unipo/mysql && sha256sum -c biecuoguo-*.sql.gz.sha256'
df -h /var/backups
```

第一次的输出（文件大小、秒数）记到本节，后续用它估容量和 RTO。确认没问题再装 cron（服务器本地时区 03:30，避开晚间活跃；
不要和部署/迁移同时跑——`--single-transaction` 挡不住并发 DDL）：

```bash
sudo tee /etc/cron.d/unipo-mysql-backup >/dev/null <<'EOF'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
30 3 * * * root UNIPO_BACKUP_DIR=/var/backups/unipo/mysql UNIPO_BACKUP_DATABASE=biecuoguo /opt/unipo/scripts/ops/mysql-backup.sh >>/var/log/unipo-mysql-backup.log 2>&1
# 可选：测试库少留几份
45 3 * * * root UNIPO_BACKUP_DIR=/var/backups/unipo/mysql UNIPO_BACKUP_DATABASE=unipo_test UNIPO_BACKUP_RETENTION=7 /opt/unipo/scripts/ops/mysql-backup.sh >>/var/log/unipo-mysql-backup.log 2>&1
EOF
sudo chmod 644 /etc/cron.d/unipo-mysql-backup
```

日志只有时间戳、文件名、大小、sha256 和错误，不含密码。备份失败目前只能看日志，告警等 R05；在那之前每周至少看一次
`tail /var/log/unipo-mysql-backup.log` 和目录里最新文件的日期。40G 盘上 14 份 gzip 逻辑备份按首次实测大小乘 14 再留余量评估。

### 7.4 异机副本（建议，未配置）

备份和数据在同一块云盘上，盘坏、机器被删、误删目录时一起没。至少每天把**新文件和它的 `.sha256` 一起**放到另一处，二选一：

1. **腾讯云 COS**：开版本控制 + 生命周期（例如 30 天），服务器上只放一个仅能写入该前缀的子账号密钥；
   上传前用 `age`/`gpg` 加密（dump 里有手机号、密码哈希、私信正文），解密私钥不放在这台服务器上。
2. **另一台机器定时拉取**：拉取方用自己的 SSH key 主动来取，服务器上不保存任何指向备份机的凭据；拉完在备份机上 `sha256sum -c`。

异机副本的延迟也算进 RPO：如果每天 03:30 备份、09:00 才同步，那 09:00 之前整机丢失就要退回前一天的副本。

### 7.5 恢复步骤

**A. 演练或查数据（生产照常运行）**——在服务器上起一个一次性容器，不映射端口、不连网络，用完即删；不要恢复进 `unipo-mysql`：

```bash
cd /opt/unipo
sudo bash -c 'umask 077; printf "MYSQL_ROOT_PASSWORD=%s\n" "$(openssl rand -hex 24)" >/root/unipo-restore-drill.env'
sudo docker run -d --name unipo-restore-drill --network none --tmpfs /var/lib/mysql \
    --env-file /root/unipo-restore-drill.env -e TZ=Asia/Shanghai \
    docker.m.daocloud.io/library/mysql:8.4 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
# 等到下面这条返回 1（初始化约半分钟）
sudo docker exec unipo-restore-drill sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h127.0.0.1 -uroot -N -e "SELECT 1"'
sudo scripts/ops/mysql-restore.sh --file /var/backups/unipo/mysql/biecuoguo-<UTC时间>.sql.gz \
    --container unipo-restore-drill --database unipo_restore_check --create-database
sudo docker rm -f unipo-restore-drill && sudo rm -f /root/unipo-restore-drill.env
```

数据量大到 tmpfs 放不下时把 `--tmpfs` 换成一个临时命名卷，用完连卷一起删。逻辑误删（某些行被删错）也走这条路：在一次性容器里取回需要的数据，
人工核对后再决定怎么写回生产，**不要整库覆盖**。

**B. 生产库丢失或损坏（卷坏、误删卷、MySQL 起不来）**——按顺序，每步记时间，用来核对 RTO：

1. 停写：`sudo docker stop unipo-backend unipo-test-backend`。
2. 保留现场：先在腾讯云控制台给数据盘打快照，再动卷；旧卷能读就别删。
3. 选备份：用本机最新一份；本机也没了就从异机副本拷回，连同 `.sha256`。
4. 重建空库：确认快照完成后再删卷（Docker 卷不能改名，快照就是旧数据的唯一退路）：
   ```bash
   sudo docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml --profile local-db rm -sf mysql
   sudo docker volume rm unipo_mysql_data
   sudo docker compose --env-file .env -f docker-compose.yml -f docker-compose.ip.yml --profile local-db up -d mysql
   ```
   容器初始化会按 `.env` 建好空的 `biecuoguo` 和应用账号；`docker ps` 显示 healthy 后再继续。
5. 导入：`sudo scripts/ops/mysql-restore.sh --file <备份> --container unipo-mysql --database biecuoguo --allow-production-target`
   （库已存在且为空，不需要 `--create-database`）。记下输出的 Flyway 版本和行数。
6. 测试库：`unipo_test` 也在同一实例里，要先按 [TEST-ENVIRONMENT.md](TEST-ENVIRONMENT.md) 重建 `unipo_test` 库和专用账号，再从它自己的备份导入；
   不需要测试实例时可以跳过，但不要让测试后端连上空库后自己从 V1 迁移。
7. 起应用：Flyway 版本要和当前后端镜像兼容（备份更旧时启动会补迁移，更新时先换回对应版本镜像）。
   `sudo docker start unipo-backend` 后按 §5.2 的只读检查清单验证 readiness、公网 health、学校/分类、帖子游标和 WebSocket 101。

恢复后会有这些已知差异：备份之后的注册、帖子和消息丢失（RPO）；之后签发的设备会话失效需重新登录；备份时还在 PENDING 的 Outbox 任务会再投递一次，
可能重复提醒（Outbox 本来就不承诺 exactly-once）；备份之后上传的图片在 MinIO 里成了没有引用的对象（R04 处理）。

### 7.6 本机演练证据（2026-10-23）

```bash
cd backend && mvn -o -B -ntp -DskipTests package && cd ..
bash scripts/integration/mysql-backup-restore.sh
```

演练脚本只建本轮带 `com.unipo.fixture.run` 标签的两个 MySQL 8.4 容器（源库映射 `127.0.0.1:22316`，目标库不映射端口，数据都在 tmpfs），
随机密码只走环境变量，不读仓库 `.env`，不碰本机 `unipo-mysql`/12306，也不连服务器。源库先写入本轮 marker，
再用**后端 jar 里打包的 Flyway 和迁移**（与源码目录逐文件比对一致才继续）从 V1 迁到 V25，然后导入 `scripts/integration/mysql-backup-restore-seed.sql`
的合成数据（emoji、中文、引号、反斜杠、换行、JSON、`DATETIME(6)`、生成列两种分支）。之后用上面两个脚本备份、恢复、比较，结束时核对所有权再删容器和网络。

最终一轮 run `f1d350bd9a88e138`（镜像 `sha256:0f51e63e…`，mysqldump 8.4.11）全部通过：

| 检查 | 结果 |
|---|---|
| Flyway | 空库应用 25 个迁移到 V25；恢复后 latest=25、failed=0 |
| 备份 | 12414 字节，`sha256 ffb83432…`；目录 700、文件 600 |
| 保留 | 预埋 3 份旧备份、retention=2：删最旧 2 份及其 `.sha256`，`notes.txt` 和 `other_db-*` 不动 |
| 抽查行数（源库 = 恢复库） | users=8、posts=7、comments=4、achievement_progress=4、conversations=2、messages=3、message_delivery_outbox=1 |
| 全库对比 | 40 张表逐表行数与 `CHECKSUM TABLE` 一致；information_schema 结构指纹（列类型/字符集/默认值/生成表达式/索引/外键/CHECK/AUTO_INCREMENT，708 行）一致 |
| 特殊值 | 昵称、私信、帖子正文按 HEX 比对一致；`activity_at` 与 `COALESCE(last_message_at, created_at)` 不一致行数 0；JSON 与微秒时间一致；marker 等于 run id |
| 拒绝路径（均退出 2） | 缺 `UNIPO_BACKUP_DIR`；密码文件 644；密码文件内容错误（Access denied，未留任何文件）；篡改 1 字节后 Checksum mismatch；目标 `biecuoguo`；目标 `unipo_prod_copy`；目标等于容器 `MYSQL_DATABASE`；目标库不存在且未加 `--create-database`；目标库非空。拒绝后目标实例里没有多出任何库 |
| 恢复路径 | 加 `--allow-production-target` 后可恢复进 `biecuoguo`，行数与普通恢复一致 |
| 清理 | 两个容器、项目网络已删；22316 无监听；产物在 `.logs/r03/f1d350bd9a88e138/`，搜索不到 48 位随机密码 |

数据只有 12KB，导入 0 秒，**不代表生产 RTO**。另外恢复库的 `SHOW CREATE TABLE` 文本会多出 `CHARACTER SET utf8mb4`（实际字符集/排序规则不变），
所以对比用 information_schema 而不是 DDL 文本，见 DEVLOG 2026-10-23。前几轮 `aa6ca8a45891b101`、`ca22c5e2bec941ea`（脚本自身问题）
和 `1d0ede9e35bf6222`（DDL 文本比对误报）的失败产物保留在 `.logs/r03/`。

还没验证的：服务器上的首次备份和恢复演练、实际数据量下的导入时长、cron、异机副本、备份失败告警、媒体与数据库引用的联合恢复。

## 7.7 媒体备份与恢复（R04）

> **现状（2026-10-24 已安装）**：和 §7.3 一起装在 `/etc/cron.d/unipo-backup`，日志 `/var/log/unipo-media-backup.log`，归档在 `/var/backups/unipo/media`（700）。
> 首次备份：`campuspo` 1 个对象 67 B、0 秒，`unipo-test-media` 10 个对象 9.7 MB、2 秒，校验全部 OK。
> 引用核对：生产 `biecuoguo × campuspo` 1 个对象、15 条引用、0 缺失（生产库还没有 `users.background_url`、`activities` 两处，核对时自动跳过）；
> 测试 `unipo_test × unipo-test-media` 10 个对象、53 条引用、0 缺失。

**为什么要「联合」**：图片对象在 MinIO，谁用了哪张图记在 MySQL（`media_objects` 记 bucket + key；`post_images.url`、`posts.cover_url`、
`users.avatar_url` / `background_url`、`activities.cover_url`、`spotlights.image_url` 里是 `/api/media/{id}/content` 或公开读 URL）。
只恢复其中一边，帖子会显示裂图。对象只增不改，所以**先跑 `mysql-backup.sh`、紧接着跑 `media-backup.sh`**：数据库里引用到的对象一定已经在桶里，
备份期间新传的对象只是多出来的孤儿，无害。

| 脚本 | 作用 |
|---|---|
| `scripts/ops/media-backup.sh` | 只读环境变量（`UNIPO_MEDIA_BACKUP_DIR`、`UNIPO_MEDIA_BUCKET` 必填；`UNIPO_MEDIA_CONTAINER` 默认 `unipo-minio`、`UNIPO_MEDIA_MC_ALIAS` 默认 `local`、`UNIPO_MEDIA_RETENTION` 默认 7）。用容器里 Bitnami 预置的 `mc` 别名逐个 `mc cat` 拉出对象（不在容器里落临时文件，不经过任何密钥参数），写 `<桶>-<UTC>.tar.gz` + `.sha256` + `.manifest.tsv`（每个对象的 sha256、字节数、key），先写 `.partial` 再改名，按份数保留 |
| `scripts/ops/media-restore.sh` | `--archive --container --bucket` 必填。先核对 `.sha256`、gzip、路径（无绝对/`..`/链接）、逐对象哈希与清单，**全部通过才连 MinIO**；目标桶必须不存在或为空；`unipo-minio`、`campuspo`、含 prod/production/live 的名字不加 `--allow-production-target` 一律拒绝；逐个 `mc pipe` 写入后核对对象数 |
| `scripts/ops/media-reference-check.sh` | 只读。拿数据库的引用对桶里的对象：缺对象的 `media_objects` 行、指向缺失对象或不存在 media id 的业务引用都会列出并以退出码 3 结束；没人记录的对象只计为孤儿。恢复后、以及日常巡检都可以跑 |

服务器安装（**需同意后执行，本次未执行**）：把 `mysql-ops-lib.sh` 和三个 `media-*.sh` 放到 `/opt/unipo/scripts/ops/`，先手跑一次：

```bash
cd /opt/unipo
sudo UNIPO_MEDIA_BACKUP_DIR=/var/backups/unipo/media UNIPO_MEDIA_BUCKET=campuspo scripts/ops/media-backup.sh
sudo scripts/ops/media-reference-check.sh --mysql-container unipo-mysql --database biecuoguo --minio-container unipo-minio --bucket campuspo
```

再在 §7.3 的 cron 文件里，紧跟 MySQL 那一行加一行（03:30 数据库，03:40 媒体）：

```bash
40 3 * * * root UNIPO_MEDIA_BACKUP_DIR=/var/backups/unipo/media UNIPO_MEDIA_BUCKET=campuspo /opt/unipo/scripts/ops/media-backup.sh >>/var/log/unipo-media-backup.log 2>&1
```

每次是全量归档，图片多了以后要换成增量（例如异机 `mc mirror`）；异机副本同 §7.4，归档里是用户上传的原图，上传前同样要加密。

恢复顺序：`mysql-restore.sh` 进空库 → `media-restore.sh` 进空桶 → 若桶名变了，把 `media_objects.bucket`（公开读时还有 URL 里的桶名）改成新桶 →
`media-reference-check.sh` 退出 0 再切流量。

**本机演练**：`bash scripts/integration/media-backup-restore.sh`（需先 `mvn -DskipTests package`、本机缓存 `mysql:8.4` 与 `bitnamilegacy/minio`）。
2026-10-24 run `70bd0cd128529324` 通过：5 个对象（含带空格的 key、一个孤儿）与帖子图片、头像、公开读背景引用一起备份；
篡改归档、生产名、生产模式名、非空桶四条拒绝路径都拒绝；恢复后 5 个对象逐字节一致、核对 0 缺失；删掉 2 个对象后核对报出 2 条缺失（含公开读引用），
再删掉一条 `media_objects` 后报出悬空引用。服务器 test 实例只读核对：10 个对象、10 条记录、53 条引用，0 缺失。

## 8. 还没做的（按优先级）

1. **HTTPS**：现在 80 明文。上域名 + Caddy（`Caddyfile` 已就绪）或云厂商证书，然后把 `AUTH_COOKIE_SECURE` 改成 true。
2. **备份**：数据库与媒体的每日备份已装到服务器（§7.3、§7.7，2026-10-24）。还缺：异机副本（备份与数据同盘）、服务器上的恢复演练、备份失败告警（R05）。
3. **多实例**：`STATE_BACKEND=memory` + WebSocket 单机 broker，扩到两台之前必须先切 Redis（或直接换 CloudBase）。
4. **漏洞面**：80 端口直接对外，限流是唯一的第一道闸；数据库/MinIO 目前靠「只绑 127.0.0.1」保护，别改。
