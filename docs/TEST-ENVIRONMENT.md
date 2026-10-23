# 手机验证码测试环境

2026-10-21 起，iOS Debug 使用服务器上的独立测试后端。手机号无需真实可收短信，但仍需符合 11 位中国大陆手机号格式。点击获取验证码后，输入任意 4 位数字即可登录；新号码自动创建普通测试账号，首次登录继续资料引导。

## 连接方式

2026-10-23 起测试实例直接对公网开放在 `49.234.21.63:18081`（腾讯云安全组已放行 TCP 18081），模拟器和真机都直连，不再需要 SSH 隧道，本机也不再运行任何后端组件。

```bash
curl -fsS http://49.234.21.63:18081/actuator/health/readiness
```

`ios/project.yml` 的 Debug API 为 `http://49.234.21.63:18081/api/v1`，Release API 仍指向生产。修改地址需重新运行 `xcodegen generate`、构建和安装；token 按 API URL 隔离存入 Keychain，换地址后需要重新登录。真机安装 Debug 包需要在 Xcode 里选择你自己的开发者 Team 签名。

公开端口意味着任何知道地址的人都能用任意验证码在测试实例上注册测试账号；它只连 `unipo_test` 库和测试桶，碰不到生产数据。端口直连没有 nginx 挡 actuator，Compose 里用 `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health` 只暴露健康检查（prometheus 公网 404）。`scripts/connect-test-api.sh` 仅作历史保留。

## 服务端隔离

| 项目 | 测试配置 |
| --- | --- |
| Compose | `/opt/unipo/docker-compose.test.yml`，项目名 `unipo-test`；端口 `0.0.0.0:18081` |
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

## 2026-10-23 更新记录

| 记录 | 值 |
| --- | --- |
| 后端版本 | `6cf3c50`（V26：入学年份与阶段学期区间） |
| 迁移 | 测试库从 V19 应用 7 个迁移到 V26；V20 起旧登录凭据失效，需要重新登录 |
| 部署前备份 | `/var/backups/unipo/test-before-6cf3c50-20261022T223227Z/unipo_test.sql.gz`（gzip 校验通过） |
| 回退镜像 | `unipo-backend:test-rollback-20261022T223323Z`；旧 Compose 备份为 `docker-compose.test.yml.bak-*` |

公网验证：readiness 200、prometheus 404；手机号任意 4 位码登录成功；学校选项、成长背景保存（2026 级 → 大一，`stageBasis=CALENDAR`，下一步「完成报到」）；信息流游标分页；WebSocket 握手 101；同一手机号与验证码请求生产被拒绝。模拟器直连服务器完成新号登录、4 步引导（含学校与入学年份）、首页阶段卡与图片加载。生产实例未更新。验证时新建了一个测试手机号账号（尾号 5791）和一个接口测试账号，留在测试库。

## 2026-10-23 A1 活动更新

测试实例更新到含 V27 活动表的后端（部署前备份 `/var/backups/unipo/test-before-a1-20261023T032247Z/`，回退镜像 `unipo-backend:test-rollback-20261023T032247Z`）。第一次启动因新模块 Mapper 未加入 `@MapperScan` 失败，但 Flyway 已先完成 V27，修复后重新部署即就绪。测试账号 13900135791 通过管理员接口设为 `SCHOOL_OPERATOR`（scope `school`），角色变化会吊销它原有登录，需要重新登录后才能在「我的」里看到「发布活动」。为验证新建了尾号 200001 至 200004、200011 至 200015 等测试手机号账号，以及两条测试活动。`/v3/api-docs` 在测试端口上可公开访问，用于生成前端类型，生产不经网关暴露。

## 2026-10-23 A2 签到与金币更新

测试实例迁移到 V28（签到字段、`user_wallets`、`coin_ledger`），部署前备份 `/var/backups/unipo/test-before-a2-20261023T035209Z/`，回退镜像 `unipo-backend:test-rollback-20261023T035209Z`。新建了「签到测试：读书分享会」「秋季迎新志愿者培训」两条可签到的测试活动，尾号 200001、200002、200004 的测试号已签到并获得经验和金币。

## 2026-10-23 A3 组织者工具更新

测试实例迁移到 V29（报名审核、撤销签到、活动取消字段与 `user_notifications`），部署前备份 `/var/backups/unipo/test-before-a3-20261023T041329Z/`，回退镜像 `unipo-backend:test-rollback-20261023T041329Z`。新增「审核测试：学生会面试」（已取消）与「提醒测试：午间英语角」两条测试活动。

## 2026-10-23 A4 成长整合更新

测试实例迁移到 V30（`user_tasks` 与三枚活动徽章元数据），部署前备份 `/var/backups/unipo/test-before-a4-20261023T044511Z/`，回退镜像 `unipo-backend:test-rollback-20261023T044511Z`。验收新建了尾号 200021 的测试号和「徽章测试：草坪音乐会」（补签后撤销，随后取消）；尾号 200002 的测试号留了一条「CET4 practice 20 sets」计划。

## 2026-10-23 G05 任务详情更新

测试实例迁移到 V31（任务步骤、期限提示、来源、复核日期、版本，完成记录的任务版本），部署前备份 `/var/backups/unipo/test-before-g05-20261023T091439Z/`，回退镜像 `unipo-backend:test-rollback-20261023T091439Z`。验收时临时把「确认本学期选课」的版本改到 2 再改回 1，尾号 200021 测试号的完成记录已撤回。

