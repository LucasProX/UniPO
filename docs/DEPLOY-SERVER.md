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

- **ATS**：App Transport Security 不拦裸 IP 的明文请求（它只对域名生效），所以现在这套 `http://IP` 不用加例外。
  真机上如果报 ATS 相关错误，把 `project.yml` 的 `NSAppTransportSecurity` 改成 `NSAllowsArbitraryLoads: true` 再生成一次。
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

## 7. 还没做的（按优先级）

1. **HTTPS**：现在 80 明文。上域名 + Caddy（`Caddyfile` 已就绪）或云厂商证书，然后把 `AUTH_COOKIE_SECURE` 改成 true。
2. **备份**：MySQL 现在没有任何备份策略，`unipo_mysql_data` 是唯一的副本。
3. **多实例**：`STATE_BACKEND=memory` + WebSocket 单机 broker，扩到两台之前必须先切 Redis（或直接换 CloudBase）。
4. **漏洞面**：80 端口直接对外，限流是唯一的第一道闸；数据库/MinIO 目前靠「只绑 127.0.0.1」保护，别改。
