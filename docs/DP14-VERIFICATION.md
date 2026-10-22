# DP14 持久消息投递验收记录

记录日期：2026 年 10 月 22 日，时间使用 Asia/Shanghai。当前状态为验证中。设计见 [ADR-0005](adr/0005-durable-message-delivery.md)，范围与完成标准见开发规划书 DP14。

当前 V25 源码的 Outbox、分设备推送、私信与社交专项均已在隔离 MySQL 通过；后端打包、含上传冒烟、iOS Core 与 Debug 构建也有通过记录。生产和既有远端 test 尚未因这些本机测试自动更新，真实供应商与网络仍按下面的剩余项验收。失败查询和受控重试工具已实现，操作说明见 OUTBOX-OPERATIONS。

## 已形成的实现

消息、会话时间和 REALTIME/PUSH 两条意图在同一事务提交，回滚不留提醒。每消息/通道唯一且事件 ID 稳定；重复入队不更换 ID。迁移仅创建投递表，不给历史消息补发提醒。

数据库 UTC 时间决定领取、租约及重试。短事务 RC 与 SKIP LOCKED 领取后先提交并释放连接，再执行外部工作。每轮最多 20 条、每次一条、租约 60 秒；后台存储动作与 worker 拒绝嵌入业务事务。租约 token 与有效期共同限制确认、重试、抑制和永久失败，旧实例的迟到结果不能覆盖新租约。

发送前检查消息存在、双方 active 与双向拉黑。不可见时写 SUPPRESSED，不因后续取消拉黑而补发旧通知。可重试失败保留安全错误码并延期，永久失败写 FAILED；两通道独立确认。DELIVERED 表示服务端通道工作结束，不是设备送达、显示或已读。

iOS 以最多 1024 个事件 ID 的缓存抑制近期重复，只保留 ID。旧帧无 `eventId` 时继续兼容，换连接清空缓存；旧 socket 的接收和心跳任务捕获原连接与代次，迟到结果不触发新账号回调。

## 当前 V24 证据

| 专项 | 实际结果 | 本机证据 |
| --- | --- | --- |
| 最终 Outbox `90d720d47dcd2880` | 18 项执行，零失败/错误/跳过；完整 V1 至 V24 | `.logs/dp14/mysql-90d720d47dcd2880/`；新增高重试次数后的设备分片仍零等待继续 |
| 设备推送 `21bbda7d3d05c18c` | 11 项执行，零失败/错误/跳过；完整 V1 至 V24 | `.logs/dp14/push-21bbda7d3d05c18c/`；真实 ledger、注册及事务，APNs 网络替身 |
| 最终私信 `7cd50ba26df6e411` | 15 项执行，零失败/错误/跳过；完整 V1 至 V24 | `.logs/dp03/message-7cd50ba26df6e411/`；独立 worker 及锁内检查 |
| 最终社交 `8ee3da02a07e172f` | 34 项执行，零失败/错误/跳过；完整 V1 至 V24 | `.logs/dp17/mysql-8ee3da02a07e172f/`；新增锁内读取不再借连接的断言 |
| 常规 Maven，18:41 | 279 项发现，175 项执行，104 项条件跳过，零失败/错误；package 成功 | `.logs/dp14/backend-final-package.log`；Push 单测 14 项、worker 单测 10 项均实际执行 |
| 当前 JAR 业务冒烟 `63216d31920354ce` | 原始 42/42，上传启用、零跳过、清理完成 | `.logs/dp02/smoke-63216d31920354ce/identity-smoke-results.json`；真实 MySQL/Redis/MinIO 与两台回环应用 |
| 最终 Boot 调度 `e5d04e2f8d779cdf` | 原始 42/42；本轮 1 条消息/2 条意图自动全部 DELIVERED；两端指标检查通过 | `.logs/dp02/smoke-e5d04e2f8d779cdf/identity-smoke-results.json`；`scheduled_outbox.complete=true`，零跳过、清理完成 |
| Core，18:35 | 103 项执行，零失败 | `.logs/dp14/core-final.log`；新增连接代次和有界事件去重 |

104 项为默认隔离的数据库/Redis 测试，不记成常规 Maven 全部执行；相关专项需看各自独立报告。每个随机 fixture 都已核归属并清理，原始日志不含真实凭据。最终 Boot 观察不调用 manual drain：原始冒烟给本轮专用 peer 发消息后，固定只读 SQL 等待两通道自然完成，并核验两台应用的十项 Outbox 指标名称、TYPE 与导出样本。`owned_peer_only=true`、`apns_disabled=true`、`metrics_a_ok=true`、`metrics_b_ok=true`、`cleanup_complete=true`、`credentials_recorded=false`；不会保存 peer ID、原 SQL 明细或 metrics 响应。

## 保留的 V23 切片证据

| 专项 | 实际结果 | 证据 |
| --- | --- | --- |
| Outbox MySQL `53a4383a88787b0d` | 17 项执行，0 失败、0 错误、0 跳过；V1 至 V23 完整迁移 | `.logs/dp14/mysql-53a4383a88787b0d/`，含 Maven 日志与带 run ID 的 JUnit XML；18:22:20 结束 |
| 消息/拉黑 MySQL `0757d59aa9a2302d` | 15 项执行，0 失败、0 错误、0 跳过；V1 至 V23 完整迁移 | `.logs/dp03/message-0757d59aa9a2302d/`，含 Maven 日志与带 run ID 的 JUnit XML；18:22:53 结束 |
| iOS Core，18:23 | 103 项执行，0 失败；新增 RealtimeClient 6 项 | `swift test --package-path ios/Packages/UniPOCore` 的执行结果；本机 macOS Core 测试，非 App UI 或真机验收 |

主代理随后完成 iPhone 17 / iOS 26.5 模拟器目标的 Debug `xcodebuild`。部署目标保持 iOS 17；构建通过只证明该工具链编译成功，不等于已经在 iOS 17 运行，也不代表真机签名或登录后 UI 验收。

日志位于本机 gitignored 目录，不随仓库发布。不同轮次的重复用例不相加成唯一覆盖量；常规 Maven 的条件跳过也不等于专项已经执行。上述两个数据库专项通过本机新建 MySQL fixture 的随机 marker 校验，使用真实 MyBatis、Spring 事务和独立 schema，不读取仓库 `.env` 或远端库。

### Outbox 的 17 项实际覆盖

数据库验证包含消息与两条意图的原子提交和回滚、未提交消息/意图对另一 worker 不可见、重复入队后事件 ID 稳定、按消息级联清理、两台 worker 在重叠领取事务中不重复取得有效租约，以及单轮工作上限与下一轮继续处理。

恢复验证使用独立 JVM，在投递探针已被调用、尚未确认时直接退出，再由另一 worker 恢复未确认记录。夹具可受控推进数据库中的租约期限，未声称是在真实网络或生产负载下等待完整 60 秒自然到期。另覆盖无其他 owner 时已经过期的确认拒绝，以及新 owner 领取后旧 owner 的全部 CAS 拒绝。

通道验证包含临时实时失败只重试 REALTIME 并保留原事件 ID，提供商临时推送失败保持已完成的实时通道，并持久保存最短重试时间；永久失败可查询且不阻塞另一通道。拉黑、封禁和注销状态阻止待投递正文；解除关系不会重做已抑制提醒。日志与错误元数据只使用稳定代码。

小连接池验证使用真实 Hikari 最大连接数 1，串行完成会话创建、发送、历史读取和独立 worker 投递；同时拒绝在业务事务中直接执行 worker 或租约动作。这证明该受控流程没有再嵌套借连接，不表示高并发吞吐或所有数据库慢查询场景已经通过。

外部通道使用探针或替身，不调用 Apple。消息专项中的本地多连接场景接真实 LocalRealtimeBroker 与 RealtimeHub，身份校验为替身；首个连接接收期间另一线程提交拉黑后，后续连接重新检查并拒绝该私信。它不替代远端多实例网络验收。

### iOS 新增的 6 项实际覆盖

重复事件 ID 只回调一次，不同 ID 继续，无 ID 的旧帧仍逐帧处理；第 1025 个不同 ID 导致最老 ID 淘汰，仍保留的 ID继续去重。重连清空缓存并保留新的回调，旧连接迟到帧既不能回调新账号，也不能占用新账号的事件 ID；断开后迟到帧与被取消后才恢复的旧心跳同样拒绝。

这些测试使用 MainActor 上可控的 socket 与心跳替身，不访问真实网络或 Keychain。它们验证 Core 状态与时序，App 构建、登录后 UI、iOS 17 和物理设备仍需各自证据。

## V24 设备级进度的实际覆盖

`push_delivery_batches` 与 `push_delivery_receipts` 按事件绑定用户与首轮最大设备 ID 快照，保存轮转游标，按事件/设备记录 ACCEPTED 或 INVALID。每页最多 64 个当前仍属原用户且未完成的快照内设备，每个 future 提交前再查当前 owner 和 token。已明确完成的设备不因另一个设备失败而重发；设备注册删除不级联抹掉回执。

真实 MySQL 的 11 项覆盖 137 个设备的有界分页、131 个设备的 APNs 服务分片继续、成功与 503 混合后重建上下文仅重试未完成设备、页面读取后提交前的换绑拒绝，以及在途 410 之后保留新 owner 的凭据。还覆盖伪造高水位不能扩大快照、空快照不吸纳新设备、事件不能换用户读取/确认、游标重建与并发首次初始化。网络对象为受控 ApnsClient/future，不请求 Apple。

`APNS_MORE_TARGETS` 表示还有设备分片待继续，属于工作继续，以零等待重新入队，不算提供商失败。最终 Outbox 新用例把既有尝试次数设为 100，仍在同轮继续下页并保留原事件 ID，没有继承失败退避。单批网络预算共享 10 秒，DB 读取和写回另有超时；当前实现为 5xx 延期至少 15 分钟，429 至少一分钟，暂时不可用/超时至少一秒。

APNs 的稳定 `apns-id` 与 pending collapse ID 不保证设备只显示一次。提供商已接受后、数据库记录前退出仍有重复窗口；取消网络 future 也不能撤回已经开始的请求。无 token 或日志实现正常返回不是用户设备送达证明，真实凭据和供应商联调仍待完成。

## 可观察性与尚未交付的操作能力

存储保留 `state`、`attempts`、`available_at`、`lease_until`、`completed_at` 和安全 `last_error`。失败行与未完成行不自动删除；运维可据此定位积压和失败，但受鉴权的查询、筛选、单条重试与审计工具尚未交付，不能要求运营直接改业务库代替它们。

当前 worker 暴露以下 Micrometer 名称：

| 名称 | 类型与含义 |
| --- | --- |
| `unipo.message.outbox.pending` | Gauge，待执行或延期的意图数 |
| `unipo.message.outbox.sending` | Gauge，已领取但尚未完成的意图数 |
| `unipo.message.outbox.failed.current` | Gauge，保留的永久失败意图数 |
| `unipo.message.outbox.oldest.age.seconds` | Gauge，最老未完成意图等待秒数 |
| `unipo.message.outbox.delivered` | Counter，本实例完成的通道动作 |
| `unipo.message.outbox.suppressed` | Counter，本实例抑制的提醒 |
| `unipo.message.outbox.retried` | Counter，本实例延期重试的动作 |
| `unipo.message.outbox.continued` | Counter，仍有设备分片待继续 |
| `unipo.message.outbox.failed` | Counter，本实例记入永久失败的动作 |
| `unipo.message.outbox.lease.lost` | Counter，本实例因租约过期或归属变化未能确认的动作 |

计数为本实例观察到的动作，队列存量来自最近成功 poll 的数据库摘要，暂停 worker 时不继续刷新；这些指标不是用户已收到通知或增长指标。没有使用 event ID、用户 ID 或正文作为指标标签。告警阈值、采样新鲜度和容量仍需 DP06/DP23 的实际配置与测量。

## 剩余验收

1. 登录后 UI、最低系统运行与真机；iOS Debug 构建已有通过记录，不替代这些项目。现有前端 lint/build 已通过，本轮没有 HTTP DTO 变更，原生成契约保留。
2. 真实 APNs、远端多实例、部署后通知与登录状态验收；尤其核对退出当前设备后的推送注册撤销、换号归属与旧任务。用户隐私状态在发送前最终检查之后发生变化时，已经在途的数据不能撤回。
3. 积压告警、数据保留规则，以及慢网络和并发负载下的租约/连接池测量。受控失败查询、重试与严格审计已交付并有 DP15 双实例 HTTP 权限证据。

DP14 保持验证中，整个 App 与 Release 均未据此结项。会话分页和发送幂等/重试、保留窗外恢复仍分别按 DP15、DP16 推进。
