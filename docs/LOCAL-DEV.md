# 本地开发环境（Windows）

> 2026-10-19 实测跑通。线上服务器 `115.190.3.204` 已停用，**所有开发都在本地 Docker + 本地 MySQL 上进行**。

## 1. 一次性准备（已完成）

| 组件 | 版本 | 位置 |
|---|---|---|
| Temurin JDK | 21.0.12.1 LTS | `D:\Environments\Java\jdk-21`（用户级 `JAVA_HOME`） |
| Apache Maven | 3.9.16 | `D:\Environments\Maven\apache-maven-3.9.16`（阿里云镜像） |
| Node / npm | 24.18.0 / 11.16.0 | 系统安装 |
| Docker Desktop | 29.8.2 | `C:\Program Files\Docker\Docker` |
| WSL | 2.7.13（内核 6.18.33.2） | winget 安装 |
| MySQL | 8.4（容器 `unipo-mysql`） | `127.0.0.1:12306` |

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

# 2) 起本地 MySQL（首次会建 volume，Flyway 建表靠后端）
cd D:\Projects\unipo
docker compose --profile local-db up -d mysql
docker compose --profile local-db ps      # 等到 healthy

# 3) 起后端（会自动读仓库根 .env）
cd backend
mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=local

# 4) 起前端
cd ..\frontend
npm.cmd run dev -- --host 127.0.0.1 --port 5173
```

打开 <http://127.0.0.1:5173/>。

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
MINIO_PUBLIC_READ=true
```

> `MINIO_*` 必须是**非空**的：`docker-compose.yml` 用了 `\${MINIO_ENDPOINT:?}` 必填语法，缺了连 `mysql` 单服务都起不来。

## 4. 默认账号

| 账号 | 密码 | 来源 |
|---|---|---|
| `admin@biecuoguo.local` | `Admin@123456` | `.env` 的 `BOOTSTRAP_*`，启动时创建 |
| `good` / `xiaoba` / `yuanhua` | 同用户名 | `AuthService` 硬编码（**R-2，上线前必须删**） |

## 5. 已知限制（本地）

- **MinIO 没启动**：头像/图片上传会失败，其余功能正常。
- **验证码是 mock**：任意 4 位数字通过（本地图方便，生产严禁，见 `docs/ISSUES.md` R-1）。
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
