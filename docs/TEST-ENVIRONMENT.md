# 手机验证码测试环境

2026-10-21 起，iOS Debug 使用服务器上的独立测试后端。手机号无需真实可收短信，但仍需符合 11 位中国大陆手机号格式。点击获取验证码后，输入任意 4 位数字即可登录；新号码自动创建普通测试账号，首次登录继续资料引导。

## 连接方式

服务器 `49.234.21.63` 上的 `unipo-test-backend` 仅绑定 `127.0.0.1:18081`，不新增公网端口。Mac 上通过 SSH 转发访问，终端保持运行：

```bash
bash scripts/connect-test-api.sh
curl -fsS http://127.0.0.1:18081/actuator/health/readiness
```

已经有隧道时不要重复启动；Mac 重启、网络切换导致 SSH 退出时重新连接。模拟器与 Mac 共用回环网络。当前配置面向本机模拟器，不是面向其他设备开放的公网测试接口。

`ios/project.yml` 的 Debug API 为 `http://127.0.0.1:18081/api/v1`，Release API 仍指向原服务。修改地址需重新运行 `xcodegen generate`、构建和安装。token 按 API URL 隔离存入 Keychain。

## 服务端隔离

| 项目 | 测试配置 |
| --- | --- |
| Compose | `/opt/unipo/docker-compose.test.yml`，项目名 `unipo-test` |
| 容器和镜像 | `unipo-test-backend`，`unipo-backend:test` |
| Profile | 仅 `test` |
| 数据库和专用用户 | `unipo_test`，仅授权该库 |
| JWT 与初始化管理员密码 | 独立随机生成，未复用生产值 |
| 媒体桶 | `unipo-test-media`，共用现有 MinIO 进程 |
| 有状态存储 | 实例内 memory，不共用生产会话和实时事件 |
| 短信、邮件和推送 | 短信及邮件为测试模式，APNs 关闭 |
| 内存 | JVM 堆上限 384MB，容器上限 768MB |
| 配置文件 | `/opt/unipo/.env.test`，权限 600，gitignored |

MySQL 与 MinIO 基础服务进程共用，但测试不复制生产用户数据。对象存储沿用服务端接入配置，业务对象写入独立测试桶。生产后端镜像、容器及 `.env` 本轮未替换。

## 更新测试后端

配置样例为 `.env.test.example`；真实 `.env.test` 不入库。首次准备时需先创建测试数据库及专用用户，生成独立 JWT 和管理员密码，填好对象存储接入信息。当前服务器已准备完毕，更新代码时保留该配置与数据库。

先在本机执行后端测试和打包，再上传 JAR、`deploy/Dockerfile.test` 和测试 Compose 文件：

```bash
cd backend
mvn -B -ntp package
```

服务器已有的运行时镜像用于承载新 JAR。`/opt/unipo/test-build/` 内包含 `app.jar` 与由 `deploy/Dockerfile.test` 上传的 `Dockerfile`：

```bash
cd /opt/unipo
sudo docker build --build-arg BACKEND_RUNTIME_IMAGE=unipo-backend:latest \
    --build-arg SOURCE_REVISION=your-commit -t unipo-backend:test test-build
sudo docker compose -f docker-compose.test.yml config -q
sudo docker compose -f docker-compose.test.yml up -d --no-build backend-test
sudo docker exec unipo-test-backend curl -fsS http://127.0.0.1:8080/actuator/health/readiness
```

Flyway 管理测试库迁移，不直接手工改业务表。`StartupSafetyGuard` 保留原校验，生产或混合 `prod` profile 不允许短信 mock。不得把测试配置覆盖到生产 `.env`。

## 已验证

- 2026-10-21 本轮后端 69 项、Core 15 项、Bash 冒烟 42 项通过；iOS Debug 构建及前端 lint/build 通过。
- 通过真实隧道调用获取验证码成功，不向短信通道发送请求，不写验证码表。
- 使用 `0000` 和另一组 4 位码登录同一普通测试账号，读取 `/auth/me` 成功。
- 3 位、5 位、非数字、空码与错误手机号被拒绝；测试 token 请求生产接口返回 401。
- 模拟器默认显示手机号页，标题居中、协议符号与文字对齐；点击获取验证码成功倒计时。未自动勾选用户协议。
- 独立 test 已应用 V19 背景迁移和公开主页关系字段；主页关系、背景持久化/公开读取/清空、隐私裁剪及评论身份的接口冒烟通过，生产未更新。
- iPhone 17 / iOS 26.5 模拟器已验证三标签点选/横向拖动、相册背景真实上传与访客展示、单向关注/朋友状态、主页与私信导航，以及游客评论草稿登录后保留。测试修改的用户背景已恢复为默认 `null`，两条关注关系已恢复原状。

本轮没有通过 UI 发送私信，最低系统 iOS 17 尚未实机验证。详细范围和结果见
[SOCIAL-PROFILE-VERIFICATION.md](SOCIAL-PROFILE-VERIFICATION.md)。

可重复运行 `python3 scripts/smoke-test-login.py`；它只向回环测试端口写入测试账号，生产侧仅做鉴权拒绝验证，不打印 token 或密码。
