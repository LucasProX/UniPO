# 消息投递失败查询与受控重试

范围：后端元数据接口与命令行工具，不新增管理网页。仅 ADMIN 可以查询或重试，普通运营角色、普通用户和未登录者均不具备权限。接口和脚本代码已提供，部署与真实 APNs 的实际效果须独立验收。

## 查询与重试规则

`GET /api/v1/admin/message-deliveries` 默认查询 FAILED。可选 `state` 为 PENDING、SENDING、DELIVERED、SUPPRESSED、FAILED；`channel` 为 REALTIME 或 PUSH。`limit` 默认 20、上限 50，`cursor` 是与筛选条件绑定的排他游标。返回 `{items,nextCursor,hasMore,limit}`，记录按 ID 倒序；不提供全量导出。

元数据只包括投递 ID、事件 ID、消息 ID、通道、状态、尝试次数、安全错误码及带 UTC 偏移的内部时间。不包含私信正文、参与者资料、设备 token、claim token 或提供商原异常。服务端的 DELIVERED 表示该通道处理结束，不是设备已收到或用户已读。

`POST /api/v1/admin/message-deliveries/{id}/retry` 接受 `{expectedAttempts,reason}`。`expectedAttempts` 必须来自刚查询的记录，`reason` 是一至二百字的单行处置说明，不要填写凭据或消息正文。

只允许 FAILED 转为 PENDING。状态或尝试次数不匹配返回 HTTP 409 / `STATE_CONFLICT`；不存在返回 404，参数错误返回 400。DELIVERED、SUPPRESSED、PENDING 和 SENDING 均不能通过此操作重新排队。重试保持事件 ID、尝试次数和已有分设备 receipt；真正再次领取时才增加 attempts，不重做已明确接受的设备。

状态变更与必需审计在同一事务提交，审计写入失败会回滚重试。审计记录操作者、投递 ID、旧/新状态、原尝试次数、通道和处置原因；不会写入请求凭据或消息正文。worker 仍在实际投递前检查账号、拉黑和设备归属，管理员重试不绕过隐私限制。

## 命令行

脚本仅调用 API，不连接或修改数据库。默认目标是 `http://127.0.0.1:8080/api/v1`。测试服务可通过既有 SSH 隧道使用 `http://127.0.0.1:18081/api/v1`；远端 API 必须 HTTPS，禁止将管理员 token 直接发到公网 HTTP。

```bash
python3 scripts/ops/message-deliveries.py --base http://127.0.0.1:18081/api/v1 list --state FAILED --limit 20
python3 scripts/ops/message-deliveries.py --base http://127.0.0.1:18081/api/v1 retry 123 --expected-attempts 4 --reason '修复推送配置后重新尝试'
```

未设置 `UNIPO_OPS_ACCESS_TOKEN` 时，交互终端会要求输入 access token，输入不回显。非交互任务需要从既有受控秘密管理机制注入该环境变量。不要把值写进命令参数、仓库、日志或工单。脚本不代登录，也不保存凭据；服务端过期后重新获取授权，不自动刷新或自动重放写请求。

远端重试还必须显式传 `--allow-remote-write` 并核对 `--base`。脚本不会跟随重定向，不继承环境中的 HTTP 代理，TLS 保持证书验证。输出仅允许经过格式校验的元数据；错误只显示 HTTP 状态、稳定错误码及 requestId，不回显 HTML、原消息、headers 或响应正文。保存输出时使用受限权限与既定保留期限。

## 验收步骤

1. 使用隔离测试数据产生或受控注入一条 FAILED，确认两通道中的另一个已完成状态不被影响。不得以直接修改生产库制造用例。
2. 用 ADMIN 查询失败元数据，核对 eventId、attempts 与安全错误码。用普通运营、普通用户和未登录请求同一接口，均应拒绝。
3. 在处理真实原因后，以本次 attempts 和单行 reason 提交重试。确认 PENDING 与审计共同出现；worker 随后可推进状态，但不能因为接口接受就宣称设备送达。
4. 再提交旧 attempts/状态，确认 409；对 DELIVERED、SUPPRESSED 和 SENDING 的重试也须 409，不能抢有效租约。
5. 隔离环境注入审计故障，确认状态仍为 FAILED，事件 ID 与 receipt 不变，没有无审计的重试成功。
6. 在列表筛选变化后复用旧 cursor，确认 400；多页结果不重复。排查时只保存固定元数据与 requestId，不取私信正文或设备凭据。

以上步骤是操作与验证要求，不能仅凭文档已存在标记验收通过。具体自动化结果由本期验收记录维护。

## 本机隔离验证

2026 年 10 月 22 日，`OutboxOperationsControllerTest` 4/4 通过，验证参数绑定、请求体验证及 HTTP 409 / `STATE_CONFLICT`；它使用 MVC 替身，不把它当作真实过滤器权限验收。日志在 `.logs/dp14/ops-controller-tests.log`。

`bash scripts/integration/outbox-operations.sh` 的 run `5fe8c272f1afd24c` 为 10/10 通过，零失败、错误或跳过，空库完整迁移 V1 至 V25。实际启用 method security、MyBatis 和 Spring 数据库事务；审计用真实 AdminAuditService/Mapper。故障 gate 在真实审计 INSERT 之后抛出异常，确认状态与该 INSERT 一起回滚，并发同 attempts 只有一次成功及一条审计。还覆盖权限在 Mapper 前拒绝、筛选游标绑定、禁止状态、时区与元数据字段边界。日志和 XML 在 `.logs/dp14/ops-5fe8c272f1afd24c/`，本轮容器、网络及 22306 已清理。

`python3 -B -m unittest discover -s scripts/ops -p 'test_*.py'` 的 6 项纯 CLI 测试通过，覆盖远端写目标开关、重定向拒绝、凭据不回显、输出字段白名单与错误体脱敏；不发网络请求。

这些结果不表示服务器已部署，也不替代真实 JWT/Cookie HTTP 权限链、真实 APNs 或人工故障恢复演练。旧审计 `record` 的 fail-open 行为保留；只有新增 `recordRequired` 明确要求并加入调用者事务。
