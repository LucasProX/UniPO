# UniPO

校园 PO 信息流应用，包含 Vue 3 前端、Spring Boot 后端和 MySQL 数据库。MinIO 作为外部已部署服务接入，不由本仓库的 Docker Compose 创建。

## 技术栈

- 前端：Vue 3 + Vite + TypeScript + Tailwind CSS + Pinia + Lucide
- 后端：Java 21 + Spring Boot 3 + Spring Security/JWT + MyBatis-Plus + Flyway
- 数据库：MySQL 8
- 媒体存储：外部 MinIO，通过 `.env` 配置连接

## 云服务器部署

服务器需要先安装 Docker 和 Docker Compose。

```bash
git clone https://github.com/LucasProX/UniPO.git
cd UniPO
chmod +x deploy.sh
./deploy.sh
```

第一次执行会生成 `.env` 并停止，请编辑 `.env`：

```bash
nano .env
```

至少填写这些值：

- `MYSQL_PASSWORD`
- `MYSQL_ROOT_PASSWORD`
- `JWT_SECRET`
- `BOOTSTRAP_ADMIN_PASSWORD`
- `APP_CORS_ALLOWED_ORIGINS`
- `MINIO_ENDPOINT`
- `MINIO_PUBLIC_ENDPOINT`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`
- `MINIO_BUCKET`

保存后再次执行：

```bash
./deploy.sh
```

部署完成后访问：

- Web: `http://服务器IP/`
- 健康检查: `http://服务器IP/actuator/health`

如果服务器 80 端口已被占用，把 `.env` 中的 `APP_PORT` 改成其他端口，例如 `8088`。

## 更新部署

```bash
cd UniPO
git pull
./deploy.sh
```

脚本会重新构建并启动 `mysql`、`backend`、`frontend`。测试环境 MySQL 对公网开放在 `115.190.3.204:12306`，本地后端和云端后端默认都连接这同一个测试库。

## 本机开发

后端本地开发默认连接测试环境 MySQL（`115.190.3.204:12306`，JDBC 地址不要带 `http://`）：

```bash
cd backend
mvn -DskipTests spring-boot:run -Dspring-boot.run.profiles=local
```

前端：

```bash
cd frontend
npm install
npm run dev -- --host 127.0.0.1 --port 5173
```

访问：

- Web: `http://127.0.0.1:5173/`
- API health: `http://127.0.0.1:8080/actuator/health`

本机开发会读取仓库根目录 `.env` 中的数据库密码和 MinIO 配置；本地写入的数据会保留在测试环境数据库里。

## 主要 API

- `GET /api/posts?tab=recommend|school|college|major`
- `POST /api/posts`
- `GET /api/posts/hot-today`
- `GET /api/posts/dont-miss`
- `POST /api/posts/{id}/like`
- `POST /api/posts/{id}/favorite`
- `POST /api/posts/{id}/share`
- `GET/POST /api/posts/{id}/comments`
- `GET /api/posts/interactions`
- `GET /api/options/schools|colleges|majors|grades`
- `GET/POST /api/users/me/check-in`
- `POST/DELETE /api/users/{uid}/follow`
- `GET /api/messages/conversations`
- `GET/POST /api/messages/conversations/{id}/messages`
- `GET /api/messages/unread-count`
- `POST /api/media/upload`
- `PUT /api/admin/users/{id}/role`
