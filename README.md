# UniPO

校园 PO 信息流应用，包含 Vue 3 前端、Spring Boot 后端和 MySQL 数据库。默认部署只启动前后端，MySQL 和 MinIO 作为外部已部署服务接入。

## 技术栈

- 前端：Vue 3 + Vite + TypeScript + Tailwind CSS + Pinia + Lucide
- 后端：Java 21 + Spring Boot 3 + Spring Security/JWT + MyBatis-Plus + Flyway
- 数据库：MySQL 8
- 媒体存储：外部 MinIO，通过 `.env` 配置连接

## 两台服务器部署

服务器需要先安装 Docker 和 Docker Compose。

服务器分工：

- 服务器 A：只部署 MySQL，默认 IP `115.190.3.204`，数据库端口 `12306`
- 服务器 B：部署后端、前端、Caddy，连接服务器 A 的 MySQL
- MinIO 不由这些脚本重新部署，继续使用你现在稳定的那套 MinIO

### 服务器 A：部署 MySQL

在服务器 A 上执行：

```bash
cd /home
git clone https://github.com/LucasProX/UniPO.git
cd UniPO
chmod +x deploy-mysql.sh
./deploy-mysql.sh
```

第一次执行会生成 `.env` 并停止。编辑 `.env`：

```bash
nano .env
```

服务器 A 至少要改这两个值：

- `MYSQL_PASSWORD`
- `MYSQL_ROOT_PASSWORD`

确认这些默认值保持一致：

```env
MYSQL_PUBLIC_BIND=0.0.0.0
MYSQL_PUBLIC_PORT=12306
MYSQL_DATABASE=biecuoguo
MYSQL_USER=biecuoguo
```

保存后再次执行：

```bash
./deploy-mysql.sh
```

这个脚本只会启动 `mysql` 容器，不会部署后端、前端、Caddy，也不会动 MinIO。

### 服务器 B：部署前后端和 Caddy

在服务器 B 上执行：

```bash
cd /home
git clone https://github.com/LucasProX/UniPO.git
cd UniPO
chmod +x deploy-app.sh deploy.sh
./deploy-app.sh
```

第一次执行会生成 `.env` 并停止，请编辑 `.env`：

```bash
nano .env
```

服务器 B 至少填写这些值：

- `MYSQL_PASSWORD`：和服务器 A 一样；如果不单独填写 `SPRING_DATASOURCE_PASSWORD`，应用脚本会直接用它连接数据库
- `JWT_SECRET`
- `BOOTSTRAP_ADMIN_PASSWORD`
- `APP_CORS_ALLOWED_ORIGINS`
- `MINIO_ENDPOINT`
- `MINIO_PUBLIC_ENDPOINT`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`
- `MINIO_BUCKET`

数据库连接默认就是服务器 A：

```env
SPRING_DATASOURCE_URL=jdbc:mysql://115.190.3.204:12306/biecuoguo?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
SPRING_DATASOURCE_USERNAME=biecuoguo
MYSQL_PASSWORD=服务器A的MYSQL_PASSWORD
```

注意 JDBC 地址不要带 `http://`。

保存后再次执行：

```bash
./deploy-app.sh
```

部署完成后访问：

- Web: `http://服务器IP/`
- 健康检查: `http://服务器IP/actuator/health`

`deploy.sh` 现在只是兼容入口，效果等同于 `deploy-app.sh`。服务器 B 上以后可以继续执行：

```bash
./deploy.sh
```

这个脚本只会构建并启动 `backend`、`frontend`、`caddy`，不会启动 MySQL，也不会动 MinIO。

如果用域名，把 `.env` 里改成：

```env
CADDY_SITE_ADDRESS=你的域名
APP_CORS_ALLOWED_ORIGINS=https://你的域名
```

如果只用 IP 和 HTTP，保持：

```env
CADDY_SITE_ADDRESS=:80
APP_CORS_ALLOWED_ORIGINS=http://服务器B_IP
```

## 更新部署

服务器 A 更新 MySQL 配置或镜像：

```bash
cd UniPO
git pull
./deploy-mysql.sh
```

服务器 B 更新应用：

```bash
cd UniPO
git pull
./deploy-app.sh
```

服务器 B 每次部署都会重新构建并重启 `backend`、`frontend`、`caddy`。数据库数据在服务器 A 的 Docker volume 里，不会因为服务器 B 执行部署脚本被清空。MinIO 不在这些脚本里，也不会被重启。

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
