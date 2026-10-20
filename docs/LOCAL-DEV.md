# 本地开发环境（Windows / macOS）

> 2026-10-19 在 Windows 上实测跑通；2026-10-20 起主力机迁到 macOS。
> 线上服务器 `115.190.3.204` 已停用，**所有开发都在本地数据库 + 本地 MinIO 上进行**。

## 0. macOS：colima + docker compose（与服务器一致）

2026-10-20 实测（macOS 26.6.2 / arm64）。**本地默认走 compose**，和服务器上的部署形态一致，
数据库端口也保持 12306，避免「本地能跑、服务器不一样」这类问题。

```bash
brew install colima docker docker-compose jq
# docker compose 插件不在默认搜索路径：写入 ~/.docker/config.json
# { "cliPluginsExtraDirs": ["/opt/homebrew/lib/docker/cli-plugins"] }

colima start --cpu 2 --memory 4 --disk 20     # 走代理时先 export HTTP(S)_PROXY
docker compose --profile local-db up -d mysql minio
```

- MySQL：`127.0.0.1:12306`（容器内 3306），账号密码来自 `.env` 的 `MYSQL_USER/MYSQL_PASSWORD`
- MinIO：`127.0.0.1:9000`（控制台 9001），`minioadmin/minioadmin`
- Redis（可选，只有 `STATE_BACKEND=redis` 才需要）：`docker compose --profile redis up -d redis`

`.env` 关键值（与服务器同一套，只有 `MYSQL_PUBLIC_BIND` 收紧到本机）：

```ini
MYSQL_PUBLIC_BIND=127.0.0.1
MYSQL_PUBLIC_PORT=12306
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:12306/biecuoguo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
MINIO_ENDPOINT=http://127.0.0.1:9000
```

启动顺序：

```bash
docker compose --profile local-db up -d mysql minio   # 首次会拉镜像；等 healthy
cd backend && mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=local   # 8080（Flyway 自动建表）
cd frontend && npm run dev -- --host 127.0.0.1 --port 5173                        # 5173
bash scripts/smoke.sh                                                             # 37 项
```

> **备选：brew 原生服务。** 如果 colima 拉虚拟机镜像太慢（GitHub CDN），可以临时用
> `brew install mysql@8.4 minio && brew services start mysql@8.4 minio`，
> 此时把 `SPRING_DATASOURCE_URL` 改成 `127.0.0.1:3306` 即可，代码不动。
> 这是应急路径，不是默认路径——它与服务器形态不一致，容易掩盖 compose/部署脚本的问题。
>
> 注意 compose 的 MySQL 和 brew 的 MySQL 不能同时起（端口/数据是两套），切换前先
> `brew services stop mysql@8.4` 或 `docker compose --profile local-db down`。


## 1. 一次性准备（Windows，已完成）

| 组件 | 版本 | 位置 |
|---|---|---|
| Temurin JDK | 21.0.12.1 LTS | `D:\Environments\Java\jdk-21`（用户级 `JAVA_HOME`） |
| Apache Maven | 3.9.16 | `D:\Environments\Maven\apache-maven-3.9.16`（阿里云镜像） |
| Node / npm | 24.18.0 / 11.16.0 | 系统安装 |
| Docker Desktop | 29.8.2 | `C:\Program Files\Docker\Docker` |
| WSL | 2.7.13（内核 6.18.33.2） | winget 安装 |
| MySQL | 8.4（容器 `unipo-mysql`） | `127.0.0.1:12306` |
| MinIO | RELEASE.2025-05-24（容器 `unipo-minio`，Bitnami 打包） | API `127.0.0.1:9000`，控制台 `127.0.0.1:9001` |

镜像加速写在 `C:\Users\36840\.docker\daemon.json`：

```json
{
  "registry-mirrors": ["https://docker.m.daocloud.io", "https://docker.1ms.run"]
}
```

## 2. 每次启动

```powershell
# 1) 先确认 Docker Desktop 在跑（AutoStart 是关的，需要手动启动）
docker info

# 2) 起本地 MySQL + MinIO（首次会建 volume；Flyway 建表靠后端）
cd D:\Projects\unipo
docker compose --profile local-db up -d mysql minio redis
docker compose --profile local-db ps      # 等到都 healthy

# Redis 是可选的：只有 `STATE_BACKEND=redis` 时后端才会连它。
# 想验证多实例行为（A 退出登录后 B 是否认这个 token）就把它和两个后端实例一起拉起来。
# 默认 memory 模式完全不需要 Redis，甚至可以只起 mysql minio。

# 3) 起后端（会自动读仓库根 .env）
cd backend
mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=local

# 4) 起前端
cd ..\frontend
npm.cmd run dev -- --host 127.0.0.1 --port 5173
```

打开 <http://127.0.0.1:5173/>。

### MinIO 说明（2026-10-19 补充）

- **镜像**：官方 `minio/minio` 在当前网络下**拉不动**（Docker Hub 返回 `denied`，试过的 RELEASE tag 全部 `not found`），所以 compose 改用 `bitnamilegacy/minio:latest`——同一个 MinIO 服务端，只是 Bitnami 打的包。需要换回官方镜像时设 `MINIO_IMAGE=minio/minio:<tag>`。
- **bucket 自动创建**：容器环境变量 `MINIO_DEFAULT_BUCKETS` 指向 `\${MINIO_BUCKET}`，启动即建 `biecuoguo`；后端的 `MediaService` 在桶不存在时也会自己建。
- **本地用 `MINIO_PUBLIC_READ=false`**：上传后返回的地址是 `/api/media/{id}/content`，由后端代理回源。好处是不用给桶配匿名读策略，也绕开跨域。
- 若要改成公开直读（`MINIO_PUBLIC_READ=true`），必须先给桶加匿名读策略，否则前端拿到的 `http://127.0.0.1:9000/...` 会 403：

```bash
docker exec unipo-minio sh -c "mc alias set local http://127.0.0.1:9000 minioadmin minioadmin && mc anonymous set download local/biecuoguo"
```

- 控制台：<http://127.0.0.1:9001>，账号 `minioadmin / minioadmin`（来自 `.env` 的 `MINIO_ACCESS_KEY/SECRET_KEY`）。

## 3. 数据源是怎么切过去的

不用改代码：

- `application.yml:6` 有 `spring.config.import: optional:file:../.env[.properties]`，后端启动时自动读**仓库根** `.env`；
- `.env`（已被 `.gitignore` 忽略）里写了 `SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:12306/biecuoguo`；
- `application-local.yml` 同样用 `\${SPRING_DATASOURCE_URL:...}`，所以 local profile 也吃这个值。

`.env` 模板见 `.env.example`，本地必填的几项：

```ini
MYSQL_ROOT_PASSWORD=root_local_dev_2026
MYSQL_DATABASE=biecuoguo
MYSQL_USER=biecuoguo
MYSQL_PASSWORD=biecuoguo_local_dev_2026
MYSQL_PUBLIC_BIND=127.0.0.1
MYSQL_PUBLIC_PORT=12306
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:12306/biecuoguo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
SPRING_DATASOURCE_USERNAME=biecuoguo
SPRING_DATASOURCE_PASSWORD=biecuoguo_local_dev_2026
JWT_SECRET=local-dev-only-secret-0123456789abcdef0123456789
BOOTSTRAP_ADMIN_EMAIL=admin@biecuoguo.local
BOOTSTRAP_ADMIN_PASSWORD=Admin@123456
MINIO_ENDPOINT=http://127.0.0.1:9000
MINIO_PUBLIC_ENDPOINT=http://127.0.0.1:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=biecuoguo
MINIO_PUBLIC_READ=false
```

> `MINIO_*` 必须是**非空**的：`docker-compose.yml` 用了 `\${MINIO_ENDPOINT:?}` 必填语法，缺了连 `mysql` 单服务都起不来。

## 4. 默认账号

| 账号 | 密码 | 来源 |
|---|---|---|
| `admin@biecuoguo.local` | `Admin@123456` | `.env` 的 `BOOTSTRAP_*`，**仅首次启动**创建；之后改的密码不会被覆盖 |
| `good@biecuoguo.local` | `good` | 演示账号，`seed-demo-data=true` 时写入（`local` profile 已默认打开） |
| `school-op@example.com` | `xiaoba` | 同上，`SCHOOL_OPERATOR` |
| `college-op@example.com` | `yuanhua` | 同上，`COLLEGE_OPERATOR` |

> 这三个演示账号只在 `local` / `dev` / `test` profile 下创建。生产 profile 下即使误开 `BOOTSTRAP_SEED_DEMO_DATA=true` 也会被 `config/StartupSafetyGuard.java` 拒绝启动。

## 5. 已知限制（本地）

- **MinIO 用的是 Bitnami 打包镜像**（官方 `minio/minio` 在国内网络拉不动）。功能等价，但版本冻结在 2025-05，长期建议换官方镜像或自建 registry。
- **图片走 `/api/media/{id}/content` 代理**（`MINIO_PUBLIC_READ=false`），流量过后端，本地无所谓，生产要评估。
- **验证码是 mock**：`application-local.yml` 里 `app.verification.mock-accept-any-code: true`。
  验证码这一栏**仍然要填**，但填任意 4 位数字都能过，而且后端不会真的发邮件（不写库、不发信，只打一条 WARN）。
  生产环境必须关掉（见 `docs/ISSUES.md` R-1）；关掉后需要配 `SMTP_HOST` 等，否则后端会**拒绝启动**——
  因为真实模式下没有 SMTP 就等于注册链路是断的。
- **管理员密码每次启动被重置**为 `BOOTSTRAP_ADMIN_PASSWORD`（R-3，本地无害，上线前必须改）。
- 数据库数据在 Docker volume `unipo_unipo_mysql_data`，删了重来：
  `docker compose --profile local-db down -v`。

## 6. 常见故障

| 现象 | 原因 / 处理 |
|---|---|
| `docker pull` 报 `dialing registry-1.docker.io:443` | 镜像加速没生效。`docker info --format '{{json .RegistryConfig.Mirrors}}'` 检查；改完 `daemon.json` 要重启 Docker Desktop |
| `wsl --status` 报 `REGDB_E_CLASSNOTREG` | WSL 包损坏：`winget upgrade --id Microsoft.WSL --force`（需提权） |
| Docker Desktop 弹 "unable to start" | 先确认 `wsl --version` 正常；再退出 Docker Desktop 后重新启动 |
| 后端启动报 `Communications link failure` | MySQL 容器没起或没 healthy，先 `docker compose ps` |
| 后端连到了远端库 | 仓库根没有 `.env`，或 `SPRING_DATASOURCE_URL` 拼错 |
| 端口 12306 被占用 | 本机已有别的 MySQL 或容器，改 `.env` 的 `MYSQL_PUBLIC_PORT` |
| 拉 `minio/minio` 报 `denied` / `not found` | MinIO 官方镜像在当前网络不可拉。用默认的 `bitnamilegacy/minio:latest`，或设 `MINIO_IMAGE` 指向可用的 registry |
| 后端启动报 `app.state.backend=redis，但连不上 Redis` | `STATE_BACKEND=redis` 但 redis 容器没起：`docker compose --profile local-db up -d redis`；或者干脆改回 `memory`（单实例够用） |
| 上传报 `无法连接 MinIO 服务` | `unipo-minio` 没起：`docker compose --profile local-db up -d minio`；或 `.env` 的 `MINIO_ENDPOINT` 不是 `http://127.0.0.1:9000` |
| 图片 403 | 把 `MINIO_PUBLIC_READ` 改成了 `true` 但没给桶配匿名读策略，见 §2 MinIO 说明 |
| 上传成功但前端不显示 | 检查返回的 `url` 是否以 `/api/` 开头；`MINIO_PUBLIC_READ=true` 时是 `http://127.0.0.1:9000/...`，需要桶策略 |

## 7. 冒烟测试（一条命令验全链路）

```bash
# 后端和 MySQL/MinIO 都在跑的前提下（macOS / Linux）
bash scripts/smoke.sh
# 只起了 MySQL、没有 MinIO 时
SMOKE_SKIP_UPLOAD=1 bash scripts/smoke.sh
```

```powershell
# Windows
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke.ps1
```

两份脚本检查同一批东西（37 项）：验证码 → 注册 → me → refresh / 拒绝 access 冒充 refresh →
发帖 → 评论 → 点赞 → 收藏 → 签到 → 统计 → 热榜 → 必看 → 关注 → 建会话 → 发私信 → 会话列表 → 未读数 →
**图片上传** → `/api/v1` 五个端点 → 游标分页走完不重不漏 → 非法游标 400 → 统一响应包（成功/错误/401）→
时间带时区偏移 → 极简注册 → 重名 400 → 缺验证码 400 → 超长密码 400。

`follow` / `conversation` 需要一个真实存在的对端用户：`ADMIN_UID` 没传时脚本会用 bootstrap 管理员
登录一次、从 `data.user.publicUid` 取，所以换库、换机器都不用改脚本。

全 PASS（`failed checks: 0`）说明 **后端 + MySQL + MinIO** 三条链路都健康；改完后端先跑这个再提交。

## 8. 前端排查技巧（无 DevTools 时）

**1）确认页面真的渲染出了什么 —— headless 导出 DOM**

```powershell
& 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe' --headless=new --disable-gpu --no-first-run `
  --user-data-dir=D:\Projects\unipo\.logs\edge-profile --virtual-time-budget=12000 `
  --dump-dom http://127.0.0.1:5173/ > D:\Projects\unipo\.logs\dom.html
```

然后在 `dom.html` 里搜关键字（`po-card`、`加载失败`）。比截图可靠得多。

**2）截图是白屏不一定是页面挂了**

Chromium 对**非激活/被遮挡的窗口**会暂停绘制，后台窗口截图经常是纯白。抓图前先让窗口到前台，或直接用上面的 headless 方式。

**3）把运行时错误写进 title**

临时在 `main.ts` 里加 `app.config.errorHandler` + `window.onerror`，把消息写到 `document.title`，再用 `list_windows` 读窗口标题即可看到首屏错误。排查完记得删掉。

**4）请求 500 但后端正常？**

Vite dev server 在代理目标（`:8080`）连不上时会自己返回 500，浏览器拿到的是 HTML 而不是 JSON。前端用 `describeError()` 统一兜底成中文提示。
