# DP02 隔离集成夹具

## Redis R06

从仓库根运行：

```bash
bash scripts/integration/redis-session.sh
```

脚本要求本地 Docker Unix socket、Compose、JDK 21、Maven、openssl 和 lsof；不拉取镜像。如果本机缓存的是其他 Redis 7 镜像，用一次性变量指定：

```bash
UNIPO_DP02_REDIS_IMAGE='redis:7-alpine' bash scripts/integration/redis-session.sh
```

变量的值应换成本机实际缓存的镜像名称，不复制生产配置。脚本缺镜像、端口占用或 Docker context 不是 Unix socket 时直接退出。

本夹具只创建一个运行 ID 对应的 Redis 容器和网络：

| 资源 | 隔离方式 |
| --- | --- |
| 容器 | `unipo-dp02-redis-{随机运行ID}`，本轮 run 标签 |
| Compose 项目/网络 | `unipo-dp02-{随机运行ID}`，每轮全新 |
| Redis 宿主端口 | 固定 `127.0.0.1:26379`；占用时退出，不复用实例 |
| 数据 | `/data` tmpfs，关闭 AOF/RDB，无持久卷 |
| 配置 | 显式独立 Compose 文件及 `--env-file /dev/null`，不读取仓库 `.env` |
| 所有权 | 容器 ID、run 标签、随机项目网络标签；只清理本轮资源 |
| JUnit 连接 | 固定 loopback 测试端口，写数据前匹配本轮 Redis marker |

测试直接构造 RedisSessionStore，不启动 Spring，不访问数据库、媒体、服务器或 SSH 隧道。仅删除测试生成的明确 key，不调用 FLUSHDB/FLUSHALL。普通 `mvn test` 默认跳过该集成测试；显式开启后连接失败、marker 不匹配或用例被跳过都会失败，不静默降级。脚本要求本轮随机后缀的 Surefire 报告，不能误用上轮文件把跳过测试当成成功。脚本在成功和失败后均清理本轮夹具。

脚本运行 `RedisSessionStoreIntegrationTest`：

1. 立即撤销同一用户两台设备，验证其他用户不受影响。
2. 保存 TTL 四秒，约两秒后 touch，跨过最初索引到期时刻；用不续期的 PTTL 证明 session 至少还有一秒寿命，再执行全部撤销。最终身份仍可 resolve 时失败。这条断言覆盖用户行为，修复后索引继续存在也能通过；撤销和验证耗时必须小于刚确认的剩余寿命并保留余量，调度延迟导致自然到期不能被当作测试成功。

Maven 输出和独立 Surefire XML 保存在 `.logs/dp02/{运行ID}/`，不会被下一轮 Maven 覆盖。报告只输出布尔状态和 TTL，不输出 session ID、CSRF 或凭据。脚本保留测试失败退出码。

2026-10-21 旧代码基线已实跑，运行 ID `cc11921946df0244`：**2 项运行、1 项失败、0 错误、0 跳过**，用例总耗时 4.865 秒。立即撤销及其他用户对照通过；跨初始 TTL 的主断言失败：

```text
R06 before revoke: sessionAlive=true userIndexExists=false sessionTtlMs=1787
R06 after revoke: oldIdentityResolves=true
R06: revoke-all must reject an active session after the original user-index lifetime;
index present before revoke=false ==> expected: <false> but was: <true>
```

基线产物在 `.logs/dp02/cc11921946df0244/`。这是对 R06 的运行时缺陷复现，尚不是修复验收；生产 Java 未由夹具任务修改。本轮容器 `unipo-dp02-redis-cc11921946df0244` 及对应随机项目网络已由退出清理逻辑移除，没有持久卷需要删除。

补齐撤销时序余量和随机 Surefire 报告后缀后，以运行 ID `0672abdb7db4e7b2` 复验：仍为 2 项运行、1 失败、0 错误、0 跳过，撤销前剩余 TTL 1788ms，撤销后旧身份仍可 resolve；产物在对应 `.logs/dp02/` 子目录，夹具同样已清理。未显式启用时单独执行该类，实际为 2 项跳过、构建成功，没有连接 Redis。

父任务改为原子 Lua 后，大 BIGINT 用户 ID 用例又暴露了 cjson 数字精度问题：Lua 拼出的用户索引与 Java 保存键不一致。SessionData 的 userId 改为 JSON 字符串、Lua 使用精确字符串后，原测试未降低 ID 范围，以 `d96cc43fa0264257` 实跑 **2/2 通过、0 跳过**。跨初始索引期限时用户索引仍存在，session 剩余 1785ms；撤销后旧身份不可 resolve。日志和 XML 在 `.logs/dp02/d96cc43fa0264257/`，容器与网络已清理。

## MySQL 设备身份与并发事务

从仓库根运行：

```bash
bash scripts/integration/mysql-identity.sh
```

本机必须已经缓存 `docker.m.daocloud.io/library/mysql:8.4`；脚本不拉镜像，不改 pom，也不启动 Spring Boot。工具要求与 Redis 夹具相同。固定 `127.0.0.1:22306` 被占用时退出，不复用现有 MySQL、日常 `12306` 或 SSH 隧道。

每轮生成独立 MySQL 容器、Compose 项目、网络和 `unipo_dp02_{随机运行ID}` schema，容器带本轮 run 标签。root 与测试数据库密码在运行时随机生成，仅通过环境传递；脚本不打印密码，不读仓库 `.env`。MySQL 数据目录使用 tmpfs，没有持久卷。所有 Docker/Compose 操作显式固定本地 Unix socket context、独立 Compose 文件和项目、`--env-file /dev/null`；清理前复核容器 ID、run 标签和项目网络所有权，只移除本轮资源。

marker 首先由本轮容器内部写入新 schema。JUnit 只连接固定 loopback 测试端口，并先核对数据库名、本轮 marker、schema 中只有 marker 表，之后才允许迁移和写入。Flyway 明确以版本 0 基线接纳这张已核验的 marker 表，仍执行仓库 V1 起的全量 migration，包含 V20 身份会话迁移；不创建另一套测试 schema，也不修改旧迁移。

`AccountSecurityMySqlIntegrationTest` 使用轻量 Spring 配置装配真实 MyBatis-Plus、SqlSessionTemplate 和 DataSourceTransactionManager，调用经过事务代理的 AccountSecurityApi；不做 Boot 自动配置、组件扫描或应用启动初始化。十三项用例覆盖：

1. 多设备独立 session 与配置驱动的绝对有效期，验证不会滑动续期。
2. 单设备撤销的属主检查，保留其他设备与其他用户。
3. 全设备撤销提升 auth version，其他用户仍有效，新版本可以重新登录。
4. 在撤销前完成的旧版本校验结果，不能再创建新 session。
5. disabled/deleted 账号拒绝创建与校验。
6. 校验采用数据库最新 role/email，不沿用旧凭据声明。
7. 自然到期拒绝；通过调整合成 row 的时间构造边界，不等待真实有效期。
8. session 不能跨用户或跨版本复用。
9. 无版本旧凭据、缺失或格式错误的 session 拒绝认证。
10. 用真实 UserMapper 写回旧 User 实体，可以修改 nickname，但不能覆盖已提升的 auth version、新密码 hash、角色、账号状态或运营权限范围；不打印 hash。
11. CyclicBarrier 同时触发创建与全设备撤销，结束后旧版本 principal 均不能有效。
12. 业务事务持有 MySQL repeatable-read 旧快照，另一个事务撤销后，身份校验仍必须看到最新撤销结果。
13. 全设备撤销加入外层业务事务；外层回滚后，version 和设备撤销标记都回滚，原设备仍有效。

普通 `mvn test` 默认跳过该类且不连接数据库；显式运行时 marker 不匹配、连接失败或测试被跳过均失败。运行输出和本轮随机后缀的 Surefire XML 保存在 `.logs/dp02/mysql-{运行ID}/`。断言不打印 session、密码或用户实际资料；每项测试只删自己创建的合成用户与设备会话。容器成功或失败均进入带所有权核验的清理流程。

这是领域身份、迁移及真实事务验收，不等于 HTTP/refresh、Cookie、WS 或应用双实例的完整 DP02 验收。

2026-10-21 已在全新夹具 `c4d47af57e1362c1` 实跑：**13/13 通过、0 跳过**；Flyway 从 V1 起成功应用全部 20 个迁移。日志及本轮 XML 在 `.logs/dp02/mysql-c4d47af57e1362c1/`；容器与网络已清理。同轮领域单测及 Realtime 筛选合计 46/46 通过（15 项领域、31 项实时），不替代下述双应用验证。

也可将命令作为参数传入，复用数据库从创建、marker 到统一清理的完整生命周期：

```bash
bash scripts/integration/mysql-identity.sh true
```

命令模式只创建新 MySQL 和 schema 内 marker，不自动运行领域 JUnit 或 Flyway；被调用脚本须先核对本轮 marker 再迁移或写入。它会收到 `UNIPO_DP02_MYSQL_RUN_ID/HOST/PORT/USER/SCHEMA/PASSWORD` 以及容器名等本轮环境变量；密码不通过命令参数传递，不生成 `.env`。传入命令的退出码原样返回，并执行同一所有权清理；默认无参数时仍运行领域测试并检查本轮报告。上面的 true 只演示生命周期；完整 HTTP/双实例使用下节的复合启动器。Redis 脚本同样支持传入命令，在本轮 marker 与连接变量就绪后运行，并在返回时清理。

## HTTP/双实例夹具

先完成当前后端打包，再从仓库根运行 `bash scripts/integration/identity-e2e.sh`。

启动器不会调用 Maven。它核对 jar 与当前 target/classes 字节一致、Java 源码不新于对应 class、资源与打包内容一致，避免误测旧构建；可复现打包会把 jar 时间戳设为 1980 年，不能用 jar mtime 判断新旧。也可用 `UNIPO_DP02_BACKEND_JAR` 显式指定当前构建。脚本嵌套上述 MySQL 与 Redis 的独立命令夹具，直接验证宿主连接目标的本轮 marker，然后启动两台仅绑定 loopback 的本机 Java 进程，端口为 28080/28081。不会部署远端或复用现有服务。

两进程共用本轮数据库、Redis 和随机 JWT 签名密钥；使用 test profile，关闭限流、演示账号种子与 APNs；验证码开发开关仅存在于该隔离环境。access 与 Cookie TTL 一分钟、设备/refresh 绝对期限三分钟，供过期用例使用。媒体不属于本组用例，不启动媒体服务；配置指向未使用的 loopback 29000 和本轮独立桶，避免落到日常 MinIO。

启动时从当前 jar 复制独立快照，生成 sha256，工作目录在本轮私有日志目录的 runtime 子目录内。显式覆盖数据源和全部身份环境，禁用配置导入；即使资源中的相对 `../.env` 导入仍被处理，也只会定位到本轮目录，不会读取仓库真实 `.env`。随机密码只通过环境传递，不写 `.env`、不放 Java 命令参数或控制台。启动器导出带 `/api/v1` 的 `UNIPO_DP02_HTTP_A/B`、`UNIPO_DP02_ADMIN_ACCOUNT/PASSWORD`、`UNIPO_DP02_ARTIFACT_DIR` 与允许的 WS Origin，调用独立的 `identity-http.py` 客户端。

结束后仅终止本启动器创建并持有的两个 Popen 子进程，外层脚本再核对所有权并清理本轮容器与网络；日志、结果、jar 快照及哈希保留在 `.logs/dp02/http-{MySQL运行ID}/`。2026-10-22 最终当前 jar 的实际主套结果为 **19/19 场景、1415 条检查全部通过**，产物在 `.logs/dp02/http-a481dad79fe7d885/`，最终 OpenAPI 是该目录的 `openapi-A.json` 和 `openapi-B.json`。覆盖内容包括全部 HTTP 身份动作、四类运营角色的管理权限边界、Cookie/CSRF/载体隔离、七类身份动作后的真实 WS 关闭与私密事件阻断，以及 access 自然到期后用有效 refresh 退出；场景细节以 JSON 中逐项记录为准。

不要复用日常 `12306/6379`、服务器 test/prod 或现有 SSH 隧道。即使 `docker ps` 展示了相同映射，宿主端口仍可能由 ssh 监听，应检查真实监听和 fixture marker。不要将根 Compose 加 `-p` 后直接当隔离夹具，里面存在固定 container_name。

Redis 夹具证明 R06 存储撤销与 TTL，MySQL 夹具证明设备身份领域、迁移和事务行为；两者均不替代 HTTP/refresh、Cookie 安全兼容、WS 最长失效秒数或应用双实例 DP02 验收。

## 故障与重启模式

`UNIPO_DP02_FAULT=1 bash scripts/integration/identity-e2e.sh` 固定执行 `identity-fault.py`，不会混用旧版基线或主验收客户端。两应用的 access/Cookie 使用五分钟、refresh 使用十五分钟，连接池等待二秒，避免把自然过期混成故障失效。

该模式为本轮随机 MySQL/Redis 使用带 run 标签的独立命名卷，Redis 开启 AOF，以便 stop/start 后仍验证原数据；默认领域、基线及主验收模式继续使用 tmpfs。结束时先核容器 ID、标签及卷标签，再清理本轮容器、网络和卷，不涉及现有资源。

客户端只能向本轮私有 `control.sock`（权限 0600）发送六个固定动作：重启 A/B、停/启 MySQL、停/启 Redis。控制器只能操作自身持有的 Popen 或固定 ID/标签匹配的容器，不接受命令、路径或任意容器名称。恢复先验原有 marker，再验依赖健康；marker 首次创建后不带 TTL，生命周期就是本轮容器/卷，恢复时不得补写，以保留持久数据丢失的检测能力。控制产物记录受控失败 phase，客户端同时保留业务原始失败和恢复失败，不能让 finally 异常覆盖最初原因。

2026-10-22 两轮早期故障测试产物分别位于 `.logs/dp02/fault-69897164d9bc3a73/` 与 `.logs/dp02/fault-a80f2007ecb514b5/`。两轮 F01 应用重启与 F02 数据库不可确认均通过；F03 尚未通过：第一轮 Redis 恢复连接暖机未完成即探测导致 401；第二轮 marker 的旧十分钟 TTL 已到期，控制恢复在 marker 阶段失败，同时单设备退出实际为 401。它们是保留的失败证据，不标记成产品验收通过。后续修正去掉 marker TTL、记录恢复阶段，并允许认证 filter 的 401 或业务依赖错误 500 作为明确失败；只在恢复后成功重试并确认持久撤销，或原响应确为 200 时，才认定设备退出完成。

2026-10-22 最终故障产物在 `.logs/dp02/fault-53307b3e36a4941c/`：**3/3 场景、478 条检查全部通过**。独立 Cookie-only 退出在 Redis 故障时返回 500，没有伪报成功；恢复后先确认原 Cookie 仍有效，再重试退出 200，旧设备的 Cookie/access/refresh 都被拒绝，另一设备与其他用户仍有效。Bearer 退出也记录到故障 500、恢复后重试 200。原有效凭据连接恢复屏障实际耗时 11.654 秒，暂时的 401 只记录为暖机观测，未当作撤销验收；最终恢复列表为空。

本组覆盖应用 A 重启、MySQL/Redis 的本轮容器停启与恢复；没有注入宿主机崩溃、数据库副本故障或分布式网络分区。精确游标排他性、排序和去重属于消息续传专项，本组仅以健康 replay 正对照验证故障时不能绕过鉴权。

## 含上传的原始 42 项冒烟

`UNIPO_DP02_SMOKE=1 bash scripts/integration/identity-e2e.sh` 固定执行 `identity-smoke.py`。它在同一新 MySQL/Redis/双应用夹具中，再新建仅本轮拥有的 MinIO（缓存 `bitnamilegacy/minio:latest` 的精确 image ID、`--pull=never`、loopback 29000、临时 data tmpfs、随机凭据仅经环境传递）。先创建合成私信对象，再原样执行仓库 `scripts/smoke.sh`，不改原脚本、不跳过上传。客户端仅保存固定检查名称及脱敏状态，不保存原始动态响应。

2026-10-22 的实际结果是 **42/42 通过、上传启用、零跳过、原冒烟退出码 0**，产物在 `.logs/dp02/smoke-1542ccc7171c3582/identity-smoke-results.json`，固定检查摘要为同目录 `identity-smoke-summary.txt`。MinIO 的 `cleanup_complete=true`；终态核对 22306、26379、28080、28081、29000 均无监听，全部带本夹具标签的容器和卷已移除，没有夹具 Java/Python 进程遗留。

## DP17 互动幂等与计数并发专项

从仓库根执行 `bash scripts/integration/post-interactions.sh`。脚本复用独立 MySQL fixture 生命周期，只运行 `PostInteractionMySqlIntegrationTest`，验证数据库 marker 后从 V1 完整应用 migration；不启动 Boot、不读取仓库 `.env`，也不连接 Redis 或远端服务。测试通过真实 MySQL、MyBatis 与 Spring 事务代理检查帖子互动记录和冗余计数在并发、重复请求、旧事务快照及帖子删除/审核状态下保持一致，同时覆盖评论与分享幂等键、每日统计和统一经验更新。

2026-10-22 V21 基线 `9aa2f8bb82891860` 为 25 项，随后 `c493246b3cb17bbb` 为 26 项，仅通过关系 stub 验证评论副作用。V22 起改用真实 SocialService 和 user_blocks；`5288fa910c5c1e7f` 的 29 项通过后，又加入注销交错、不同 pair 并发和旧事务权威读取。最终 run `f44fe580211a48ef` **34/34 通过、0 失败/错误/跳过**，从空库完整迁移 V1–V22，并显式断言 V22 成功。日志/XML 在 `.logs/dp17/mysql-f44fe580211a48ef/`。普通 `mvn test` 默认跳过，runner 固定核对 34 项显式执行。

注销用例使用真实 UserService SQL，但 SessionService 是替身，身份撤销继续看 DP02；此处证明的是关系不复活。初次 `6906746051cd832d` 因 fixture binlog 下创建触发器报 1419 失败，仅隔离 Compose 添加 `log-bin-trust-function-creators` 后重跑。结果不证明热点吞吐、iOS 登录后交互或服务器已部署。详细边界见 [DP03 验收记录](../../docs/DP03-VERIFICATION.md)。

## DP03 私信与拉黑事务专项

从仓库根执行 `bash scripts/integration/message-blocks.sh`。使用同一新建 MySQL 22306 隔离生命周期、空库和随机 marker，只运行 `MessageBlockMySqlIntegrationTest`，不读根 `.env`，不连接远端或真实 APNs。不要与其他 22306 fixture 同时运行。

最终 run `3baedfe913ad44db` **15/15 通过、0 失败/错误/跳过**，V1–V22 成功，产物在 `.logs/dp03/message-3baedfe913ad44db/`。真实 Spring 事务、MyBatis、MessageService 和 SocialService 覆盖双向拉黑、取消恢复、锁等待、回滚无通知、独立连接确认提交、旧 RR 的列表/聚合与越权。多连接用例使用真实 LocalRealtimeBroker/RealtimeHub，身份为替身，覆盖首个 sink 期间拉黑提交后下一 sink 重新检查。

最早 run `6f57cdbfc64b4d79` 的合成昵称重复导致初始化失败，未进入业务，原报告保留。通过轮次的容器/网络已清理，runner 固定核对 15 项与零跳过；常规 Maven 不代替显式运行。

包含上述改动的最终 JAR 在 run `368a979751d9d0b5` 重新通过双应用与真实媒体的 42 项原始冒烟，上传开启、零跳过、清理完成；产物在 `.logs/dp02/smoke-368a979751d9d0b5/`。

## DP03 Redis replay 专项

`redis-session.sh` 同时运行 RedisSessionStoreIntegrationTest 和 RedisRealtimeStoreIntegrationTest，两个独立连接共享本轮 Redis。run `c555026108bf06b0` 的会话 2 项和 replay 1 项均通过，零跳过，产物在 `.logs/dp02/c555026108bf06b0/`。

新增 replay 验证私信不占全局序号、不写 Stream，以及 100 条上限、按应用序号排序和排他分页。它不代表保留窗外的恢复协议、多实例 Pub/Sub 网络、持久 Outbox 或 iOS 端到端验收。默认镜像未缓存时脚本会安全退出；可用 `UNIPO_DP02_REDIS_IMAGE` 指定已缓存 Redis 7 镜像。

## SMS 登录并发专项

从仓库根执行 `bash scripts/integration/sms-login.sh`。它只在全新 MySQL 22306/schema/marker 生命周期内运行 `SmsLoginMySqlIntegrationTest`，不启动 Boot，不读真实 `.env`，不连接 Redis、短信 provider、服务器 test/prod。普通 `mvn test` 默认跳过该类；显式专项不会把跳过当通过。runner 只打印 gate、动作、布尔结果与异常类型，不输出合成手机号、验证码、hash 或 token。

测试使用真实 AuthService、AdminService、SessionService 和 MySQL/MyBatis 事务，SmsService 的真实 mock 模式格式校验由 spy 包住；UserMapper 和 AccountSecurityApi 只在指定位置加可控 latch/barrier，实际查询和条件建会话仍使用生产实现。线性化断言为：

| 固定 gate | logout-all/角色变更提交后继续 | 封禁提交后继续 |
| --- | --- | --- |
| 验证验证码之前 | 后续首次读账号可捕获新版本，允许作为新登录 | 拒绝 |
| 验证返回后、首次账号读取之前 | 同上，角色使用数据库最新值 | 拒绝 |
| 已读旧 User、尚未写登录时间 | 不能把已捕获旧版本升级成新版本；条件建会话拒绝 | 拒绝 |
| 已写登录时间、createSession 前 | 登录已持 users 行写锁，可先完成；之后撤销提交，刚签出的凭据也必须失效 | 同样按提交先后串行，不把重叠请求一概要求失败 |

真实非 mock 验证会先读短信 challenge，可能更早建立 Repeatable Read 快照；本组不把 mock 无数据库读取的“可捕获新版本”推断成所有 provider 模式的可用性保证。读到旧快照后条件建会话拒绝属于保守拒绝。实际短信发送 provider 仍未接通；后述真实验证码测试通过本地合成 challenge 验证存储消费链路，不调用发送接口。

2026-10-22 在独立 run `4eb1863dbc9dd4fa` 实跑 **15 项：13 通过、2 失败、0 错误、0 跳过**。12 个身份安全交错及正常手机号登录全部通过；两条实际红灯为：

1. `email == phone` 兼容账号成功登录，但原始数据库查询确认 phone 仍为空；setPhone 没有被仅写登录时间的更新持久化。
2. 相同新手机号的两个合法请求在 UserMapper.insert 前 barrier 同时放行；唯一约束保住一个 users、一份偏好及成功请求的一条设备记录，但另一请求以 DuplicateKeyException 结束。该红灯是并发首登未收敛为同一账号的可用性问题，不是重复账号或旧身份越权。

原红测日志及独立 Surefire XML 保存在 `.logs/dp02/sms-4eb1863dbc9dd4fa/`。后续修复保持这两条原行为期望，没有降低断言：phone 绑定用 id/version/active 条件的显式列更新；SMS 登录以 REQUIRES_NEW TransactionTemplate 在完整验证码/账号/偏好/设备事务外最多重试三次，仅重试 DuplicateKeyException 或 TransientDataAccessException，失败尝试先完整回滚，下一次从新快照重新验证。校验/认证失败、其他永久数据故障不重试。真实验证码消费改为未消费且未过期的条件 UPDATE，影响行必须恰为一。

2026-10-22 修复后在全新 run `26cbe31b8868a383` 实跑 **19/19 通过、0 跳过**，V1–V21 全量迁移成功，产物在 `.logs/dp02/sms-26cbe31b8868a383/`。原两条红灯转绿，并新增：

- 同手机号两次首登都成功，只有一用户/一份偏好，分别得到两个独立有效设备；insert barrier 只阻塞首轮，重试不会等待已完成请求。
- fallback 已捕获旧版本时，撤销提交后不能绑定手机号或创建设备。
- 两个真实 MySQL 事务先读同一条合成验证码再并发消费，仅一个成功、另一个明确拒绝，数据库只有一条已消费记录。
- 读取后验证码在数据库已过期，CAS 不能继续消费。
- 在完整登录的第一次设备写入前注入唯一冲突，验证码消费、新用户及偏好一起回滚；下一独立事务重新验证后成功，只留一组正式记录。

同轮 `AuthServiceSmsRetryTest` 七项与 `SmsServiceTest` 十项共 **17/17 通过**，验证事务传播、新事务句柄、回滚/提交顺序、重试分类与三次上限，以及 CAS 条件与失败语义。没有修改 pom；MySQL 容器及网络已清理，Maven/22306 已交给下一专项。以上是本地隔离短信登录/验证码存储验收，不代表真实短信 provider、正式部署或整包 DP02 已完成。

2026-10-22 在独立 run `ae388004c979bcc3` 再跑当前源码，**20/20 通过、0 跳过**，V1–V21 全量迁移成功，产物保存在 `.logs/dp02/sms-ae388004c979bcc3/`。新增的非 mock challenge 用例覆盖“验证码已验证并读到旧账号版本后，logout-all 先提交”的交错：登录被拒绝，challenge 消费与登录写入一起回滚。

最终 V21 迁移后又在全新 run `d27500773c1624de` 重跑 **20/20 通过、0 跳过**，产物保存在 `.logs/dp02/sms-d27500773c1624de/`。新增迁移及该轮专项均成功；它仍只证明隔离数据库中的登录事务和验证码存储行为，不代表真实短信供应商、生产部署或 DP02 整体验收完成。

## DP14 持久通知与设备进度

`bash scripts/integration/outbox-delivery.sh` 运行 18 项真实 MySQL 用例，`bash scripts/integration/push-delivery.sh` 运行 11 项设备进度用例。两者都复用新建 22306 fixture，核对 marker/schema，完整 Flyway，运行完仅清理本轮资源；不读根 `.env`，不访问远端或 Apple。不要与其他使用 22306 的专项并行。

当前 V24 的 Outbox run `90d720d47dcd2880` **18/18**，设备 run `21bbda7d3d05c18c` **11/11**，均零失败/错误/跳过。前者包括独立 JVM 进入 transport 后 abrupt halt、新 worker 重领、旧 owner CAS、Hikari 最大连接数 1、通道分离与下页零等待；后者包括 64 设备上限、部分接受后重建继续、换绑最终检查、固定设备 ID 快照与持久轮转。

最终社交与私信分别为 `8ee3da02a07e172f` 的 34 项、`7cd50ba26df6e411` 的 15 项；V1 至 V24 完整迁移成功。原始报告分别位于 `.logs/dp14/mysql-{run}/`、`.logs/dp14/push-{run}/`、`.logs/dp17/mysql-{run}/` 和 `.logs/dp03/message-{run}/`。Runner 对实际项数与零跳过都有固定断言，普通 `mvn test` 的条件跳过不代替显式执行。

网络通道使用探针/ApnsClient 替身，提供商接受但 receipt 未落库的退出窗口仍可能重复；不据此声明 Apple 真通道、远端 WebSocket 或 iOS UI 已通过。完整边界见 [DP14 验收记录](../../docs/DP14-VERIFICATION.md)。

自动调度与指标可用 `UNIPO_DP14_SCHEDULE_SMOKE=1 UNIPO_DP02_SMOKE=1 bash scripts/integration/identity-e2e.sh` 检查；Redis/MySQL 镜像按本机已有缓存指定。原 42 项不变，之后独立读取归属 marker，并只聚合本轮专用 peer 的消息，在 20 秒内等待两通道自然完成；最后检查两台 Boot 的十项 Prometheus 名称和 TYPE。默认不启用该额外 gate，以保持旧包冒烟入口。

最终 run `e5d04e2f8d779cdf`：42/42，上传开启，1 条本轮消息、2 条意图全部自动 DELIVERED，其余状态为 0，两端指标通过。JSON 的 `scheduled_outbox.complete`、`owned_peer_only`、`metrics_a_ok`、`metrics_b_ok` 和 `cleanup_complete` 均 true；凭据未记录，未调用 manual drain。APNs 关闭，只证明服务端通道处理结束。产物在 `.logs/dp02/smoke-e5d04e2f8d779cdf/`。

## R03 MySQL 备份恢复演练

先 `cd backend && mvn -DskipTests package`，再从仓库根执行 `bash scripts/integration/mysql-backup-restore.sh`。本机必须已缓存 `docker.m.daocloud.io/library/mysql:8.4`（或用 `UNIPO_R03_MYSQL_IMAGE` 指定缓存的 8.4 镜像），脚本不拉镜像、不读仓库 `.env`。

每轮建两个带本轮 run 标签的容器：源库映射固定 `127.0.0.1:22316`（占用即退出），目标库不映射端口，数据目录都是 tmpfs；随机 root/应用密码只走环境变量。源库先由容器内部写入 marker，`FixtureFlywayMigrate.java` 用后端 jar 里打包的 Flyway、连接器和迁移（先与源码目录逐文件比对）核对 marker 后从 V1 迁到最新版本，再导入 `mysql-backup-restore-seed.sql` 的合成数据。随后调用 `scripts/ops/mysql-backup.sh` 与 `mysql-restore.sh` 跑正常路径、保留策略和九条拒绝路径，最后比对两库的逐表行数、`CHECKSUM TABLE`、information_schema 结构指纹与特殊值。退出时核对容器 ID、run 标签和项目网络后清理，产物留在 `.logs/r03/{运行ID}/`。

2026-10-23 最终 run `f1d350bd9a88e138` 通过，结果与边界见 [部署文档](../../docs/DEPLOY-SERVER.md) §7.6。它证明脚本和恢复链路正确，不代表服务器已装定时备份，也不代表生产数据量下的恢复耗时。

## R04 媒体备份恢复演练

先 `cd backend && mvn -DskipTests package`，再从仓库根执行 `bash scripts/integration/media-backup-restore.sh`。需要本机已缓存 `docker.m.daocloud.io/library/mysql:8.4` 和 `bitnamilegacy/minio:latest`（可用 `UNIPO_R04_MYSQL_IMAGE` / `UNIPO_R04_MINIO_IMAGE` 指定），不拉镜像、不读 `.env`。

每轮建四个带 run 标签的容器（源/目标 MySQL、源/目标 MinIO，数据都在 tmpfs），源库固定映射 `127.0.0.1:22317`。源库沿用 R03 的 marker + `FixtureFlywayMigrate.java` 迁到最新版本并导入 R03 的合成用户和帖子，再上传 5 个带真实 JPEG/PNG 文件头的对象、记录其中 4 个并挂到帖子图片、头像和公开读背景上。之后依次跑 `mysql-backup.sh`、`media-backup.sh`、四条恢复拒绝路径、`mysql-restore.sh`、`media-restore.sh`、逐字节比对和 `media-reference-check.sh` 的通过 / 缺失 / 悬空三种结果。退出时核对 run 标签后清理，产物在 `.logs/r04/{运行ID}/`。结果见 [部署文档](../../docs/DEPLOY-SERVER.md) §7.7。

