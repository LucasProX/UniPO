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
