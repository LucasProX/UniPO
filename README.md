# 别错过大学校园 PO MVP

这是一个可本机预览、可用 Docker Compose 部署到 Ubuntu 云服务器的 MVP：

- 前端：Vue 3 + Vite + TypeScript + Tailwind CSS + Pinia + Lucide。
- 后端：Java 21 + Spring Boot 3 + Spring Security/JWT + MyBatis-Plus + Flyway。
- 数据：本机开发可用 H2，部署使用 MySQL 8。
- 媒体：Docker Compose 内置 MinIO，用于头像和帖子图片上传。

## 当前产品形态

- 首页核心是学生校园 PO 信息流。
- 右侧是“今日热度”“今日别错过轮播”“详情面板”和评论。
- 底部是 iOS 风格毛玻璃 dock：首页、提醒、发 PO、消息、我的。
- PO 板块固定为：推荐、校 PO、院 PO、专业 PO。
- 用户有 8 位 UID、学校/学院/专业/年级、等级和每 10 级头衔。
- 管理员可分配运营角色：校霸、院花、级草。
- 新增关注、私信、点赞、收藏、分享、MinIO 上传等接口。

## 本机开发启动

后端使用 H2：

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
- H2 console: `http://127.0.0.1:8080/h2-console`

## Docker Compose 部署

```bash
cp .env.example .env
docker compose up -d --build
```

访问：

- Web: `http://服务器IP/`
- MinIO console: `http://服务器IP:9001/`
- 默认管理员：`.env` 中的 `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD`

如服务器 80 端口被占用，把 `.env` 的 `APP_PORT` 改成其他端口，例如 `8088`。

## 新增主要 API

- `GET /api/posts?tab=recommend|school|college|major`
- `POST /api/posts`
- `GET /api/posts/hot-today`
- `GET /api/posts/dont-miss`
- `POST /api/posts/{id}/like`
- `POST /api/posts/{id}/favorite`
- `POST /api/posts/{id}/share`
- `GET/POST /api/posts/{id}/comments`
- `GET /api/options/schools|colleges|majors|grades`
- `POST/DELETE /api/users/{uid}/follow`
- `GET /api/messages/conversations`
- `GET/POST /api/messages/conversations/{id}/messages`
- `GET /api/messages/unread-count`
- `POST /api/media/upload`
- `PUT /api/admin/users/{id}/role`
