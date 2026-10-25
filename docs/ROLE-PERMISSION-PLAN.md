# 角色与权限系统：现状研究与方案（2026-10-25）

> 状态：**提议，待拍板**。拍板后按第 6 节分期实现，实现完成再补 ADR-0006。
> 依据：读了现有后端的全部角色判断点（`SecurityConfig`、`ActivityRules`、`PostService.canPostOfficial`、`AdminService`、各 `/api/admin/**` 控制器）和 iOS 对 `role` 的使用，以及 [PRODUCT-PLAN](PRODUCT-PLAN.md) §3、§6.6、§8 和 [DEVELOPMENT-PLAN](DEVELOPMENT-PLAN.md) §5.8 对角色的要求。

---

## 0. 结论

现在的「权限」其实只是用户表上的两个字符串：`role`（USER / OPERATOR / SCHOOL_OPERATOR / COLLEGE_OPERATOR / MAJOR_OPERATOR / ADMIN）和 `operator_scope`（student / school / college / major / admin）。它能撑住演示，但撑不住试点：

1. **权限范围锚在用户自己能改的资料上**——这是现成的越权漏洞（见 1.2）。
2. **后台没有学校隔离**：任何一个运营（哪怕是某个专业的运营）都能审核、置顶全平台所有学校的帖子，读全部审计日志。
3. **没有「谁授予、为什么、到什么时候」**：换届、复核、撤销都无从做起，而这是规划里金V和运营体系的前提。

建议改成 **「平台角色 + 范围授权 + 身份认证」三件事分开** 的模型：权限由代码里的能力清单定义，授权记录绑定到具体的学校 / 学院 / 专业 / 组织，带有效期和审计；所有模块通过一个 `PermissionApi` 判断。第一期只做授权表、能力清单、范围校验和迁移，就能把两个漏洞堵上，并给 O01 举报处理准备好「审核员」角色。

---

## 1. 现状

### 1.1 角色在哪里被判断

| 位置 | 判断方式 | 问题 |
|---|---|---|
| `SecurityConfig` | `/api/admin/users`、消息投递、反馈只给 ADMIN；其余 `/api/admin/**` 给任意运营角色 | 只有「是不是运营」一道门，不区分哪个学校、能做什么 |
| `ActivityRules.levelOf` | 用 `operator_scope` 推出 SCHOOL / COLLEGE / MAJOR 三档，决定可面向范围和奖励上限 | 档位对，但「哪个学院」取自用户资料（见 1.2） |
| `ActivityService.canManage` | 只有发布者本人或 ADMIN 能管理活动 | 社团换届、多个干部共管一场活动都做不到 |
| `PostService.canPostOfficial` | ADMIN 或按 `operator_scope` 判断能否发官方帖 | 同样不看具体学校 / 学院 |
| `PostService` 置顶 | 只有 ADMIN | 校运营无法置顶本校帖子 |
| `/api/admin/posts` 审核 | 任意运营角色 | **能改任何学校的帖子** |
| `/api/admin/spotlights` | 任意运营角色；轮播表没有学校字段 | 全平台共用一个首页轮播 |
| `/api/admin/audit-logs`、`/reports`、`/monitor` | 任意运营角色 | 专业运营能读全平台审计日志 |
| `/api/admin/achievement-tasks/*/guides` | 任意运营角色 | 能改任何学校任务的攻略关联 |
| `AdminService.updateUserRole` | 只有 ADMIN；同时推进身份版本 | 审计在控制器里事务外写（不是 `recordRequired`），没有理由、有效期 |
| iOS `PostCardView` | `author.role != "USER"` 就显示认证勾 | 「有运营权限」和「身份已认证」混成一件事 |

`users.verified_status`（金V 的预留字段）存在，但只在改角色时被顺手设成 `verified`，没有申请和审核流程。

### 1.2 两个现成的漏洞

**漏洞 A：运营范围跟着用户自己改的资料走。**
活动发布时，组织者的学校、学院、专业都取自 `users.school_id / college_id / major_id`（`ActivityService.create` → `schoolOf(organizer)`、`checkAudience(... organizer.getCollegeId() ...)`）。而这几个字段用户自己就能改：`PUT /users/me` 直接接受 `schoolId / collegeId / majorId`（`UserService.updateProfile` 第 81 行起），成长背景接口也能改。
结果：一个院级运营把自己的学院改成别的学院，就能以那个学院的名义发布带经验、金币奖励的活动；改学校就成了另一所学校的运营。

**漏洞 B：后台写操作没有学校隔离。**
`PATCH /api/admin/posts/{id}`（下架、置顶、官方、别错过）只检查「是运营」，不检查帖子属于哪个学校。规划里明确要求「各角色只能处理授权范围的对象，跨校越权必须拒绝」（DEVELOPMENT-PLAN §5.8）。

> 附带发现：普通用户也能通过 `PUT /users/me` 的 `schoolId` 随意切换学校，学校隔离目前完全靠自报。单校试点影响不大，但多校前必须收紧（见 6.1 第 6 项）。

### 1.3 规划里需要的角色

| 规划中的角色 | 来源 | 现在 |
|---|---|---|
| 平台管理员 | §5.7 | ADMIN ✅ |
| 平台内容组（主线编写、审核标准） | §6.7、§8.1 | 无对应角色，靠 ADMIN |
| 校运营（本地化、金V 初审、活动、拉新） | §6.6、§8.1 | SCHOOL_OPERATOR，范围不可靠 |
| 院 / 年级运营（专业支线、答疑） | §6.6 | COLLEGE / MAJOR_OPERATOR，范围不可靠 |
| 举报处理 / 审核员 | DEVELOPMENT-PLAN §5.8、O01 | 无 |
| 金V（班长、学委、社团负责人、教师） | §6.6 | 只有一个 `verified_status` 字段 |
| 学校 / 学院 / 社团官方主办方 | §3 | 活动上只有一个 `organizer_name` 文本 |

---

## 2. 设计原则

1. **权限来自授权记录，不来自用户资料。** 用户改学校、改专业，不影响他手里的授权；授权只能由有权的人在受控接口里改。
2. **三件事分开**：
   - **平台角色**：USER / ADMIN，全局，极少数人；
   - **范围授权**：某人在某个范围（学校、学院、专业、组织）担任某个角色，带有效期；
   - **身份认证**：金V 这类「这个人是谁」的可信标识，用于展示和信任，本身不等于运营权限。
3. **能力清单写在代码里**，角色是能力的固定组合。不做可在线编辑的角色表：试点阶段角色少，代码定义更好评审、好测试，不会被误配。
4. **默认拒绝，范围包含才放行**：授权范围必须包含目标对象所在的范围（学校 ⊃ 学院 ⊃ 专业，组织属于学校）。
5. **不能越级授予**：授予者只能授予自己能力的子集，且只能在自己范围内。
6. **每次授予、撤销都留痕**：授予者、理由、起止时间，`recordRequired` 审计与业务写入同一事务。
7. **客户端只认服务端给的能力**：iOS 按 `/users/me/capabilities` 显示入口，不再用 `role` 字符串做判断。

---

## 3. 模型

### 3.1 范围

```
平台 (PLATFORM)
└── 学校 (SCHOOL:1)
    ├── 学院 (COLLEGE:12)
    │   └── 专业 (MAJOR:305)
    └── 组织 (ORG:7)  ← 社团、学生会、学院团委等，属于某个学校，可挂在某个学院下
```

每个受保护的对象都能算出自己的范围：帖子有 `school_id / college_id / major_id`，活动有学校与面向范围（以后加 `org_id`），任务攻略按任务适用范围，举报按被举报对象。

### 3.2 能力清单（第一期）

| 能力 | 含义 |
|---|---|
| `post.official` | 发官方帖（带官方标识） |
| `post.feature` | 置顶、标「别错过」 |
| `post.moderate` | 下架、恢复帖子与评论 |
| `post.validity` | 设置攻略的核验日与有效期 |
| `activity.publish` | 发布带奖励的活动（奖励上限是角色属性） |
| `activity.manage` | 管理范围内的活动：审核报名、补签、撤销签到、编辑、取消 |
| `guide.manage` | 维护任务与攻略的关联 |
| `spotlight.manage` | 维护本范围首页轮播 |
| `report.handle` | 受理、处理举报（O01） |
| `appeal.handle` | 处理申诉（O02） |
| `verification.review` | 审核金V 申请 |
| `role.grant` | 在本范围内授予、撤销**更低一级**的角色 |
| `audit.read` | 读本范围的审计日志 |
| `analytics.read` | 读本范围的报表 |
| 平台专属 | `user.ban`、`feedback.read`、`ops.outbox`、`mainline.edit`，只给平台角色 |

### 3.3 角色模板（能力的固定组合）

| 角色 | 可授予的范围 | 能力 | 奖励上限（经验 / 金币） |
|---|---|---|---|
| 平台管理员 ADMIN | 平台 | 全部 | 100 / 200 |
| 平台内容编辑 CONTENT_EDITOR | 平台 | `mainline.edit`、`guide.manage`、`post.validity`、`post.official` | — |
| 校运营 SCHOOL_OPERATOR | 学校 | `post.official`、`post.feature`、`post.moderate`、`post.validity`、`activity.publish`、`activity.manage`、`guide.manage`、`spotlight.manage`、`report.handle`、`verification.review`、`role.grant`（院运营、审核员、组织负责人）、`audit.read`、`analytics.read` | 100 / 200 |
| 院运营 COLLEGE_OPERATOR | 学院 | `post.official`、`post.moderate`、`activity.publish`、`activity.manage`、`report.handle` | 50 / 100 |
| 专业运营 MAJOR_OPERATOR | 专业 | `post.official`、`activity.publish`、`activity.manage` | 30 / 60 |
| 审核员 MODERATOR | 学校 | `post.moderate`、`report.handle`、`appeal.handle` | — |
| 组织负责人 ORG_ADMIN（第二期） | 组织 | `activity.publish`、`activity.manage`（本组织的活动）、`role.grant`（组织成员） | 由校运营给组织定档 |

奖励上限沿用 `ActivityRules.Level` 现在的数值，只是从「档位」变成角色属性。

### 3.4 数据表（第一期，V36）

```sql
CREATE TABLE role_grants (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    role         VARCHAR(32)  NOT NULL,              -- 角色模板名
    scope_type   VARCHAR(16)  NOT NULL,              -- PLATFORM / SCHOOL / COLLEGE / MAJOR / ORG
    scope_id     BIGINT       NULL,                  -- PLATFORM 时为空
    school_id    BIGINT       NULL,                  -- 冗余，便于按学校过滤与清理
    granted_by   BIGINT       NULL,                  -- 迁移来的历史授权为空
    reason       VARCHAR(200) NOT NULL,
    starts_at    DATETIME     NOT NULL,
    expires_at   DATETIME     NULL,                  -- 换届 / 学年复核
    revoked_at   DATETIME     NULL,
    revoked_by   BIGINT       NULL,
    revoke_reason VARCHAR(200) NULL,
    created_at   DATETIME     NOT NULL,
    KEY idx_grants_user (user_id, revoked_at, expires_at),
    KEY idx_grants_scope (scope_type, scope_id, role)
);
```

- 「有效授权」= 未撤销、已开始、未过期。
- 同一用户可以同时有多条授权（比如某学院的院运营 + 全校审核员）。
- 不做物理删除；撤销只写 `revoked_*`，历史可追溯。

### 3.5 判断接口

```java
public interface PermissionApi {
    boolean can(CurrentUser actor, Capability capability, ScopeRef target);
    void require(CurrentUser actor, Capability capability, ScopeRef target);   // 越权抛 AccessDeniedException（403）
    List<ScopeRef> scopesFor(CurrentUser actor, Capability capability);         // 后台列表按范围过滤
    Optional<RewardCap> rewardCap(CurrentUser actor, ScopeRef audience);
    CapabilityView capabilities(CurrentUser actor);                              // 给 iOS 的入口清单
}
```

- `ScopeRef` 由对象给出（帖子 → 学校 / 学院 / 专业），判断时检查是否被某条有效授权的范围包含。
- 学院、专业、组织属于哪个学校，从分类字典查，不信请求参数。
- 每个请求最多查一次授权（按用户 ID 走索引，几行数据），不放进 JWT：授权撤销后下一次请求立即生效。
- `SecurityConfig` 只保留粗粒度的门（已登录；`/api/admin/**` 需要至少一条有效授权；平台专属接口需要 ADMIN），细粒度判断全部在业务服务里调 `PermissionApi`。

### 3.6 授予与撤销

| 接口 | 权限 | 说明 |
|---|---|---|
| `GET /api/admin/role-grants?scopeType=&scopeId=` | `role.grant` 或 `audit.read` | 只返回自己范围内的授权 |
| `POST /api/admin/role-grants` | `role.grant` | `{userUid, role, scopeType, scopeId, reason, expiresAt}`；只能授予比自己低的角色、且在自己范围内；理由必填 |
| `POST /api/admin/role-grants/{id}/revoke` | `role.grant` | `{reason}` |
| `GET /api/users/me/capabilities` | 登录 | `{platformRole, grants:[{role, scope, scopeName, expiresAt}], capabilities:[{capability, scopes}]}` |

- 授予和撤销与 `recordRequired` 审计在同一事务；同时推进被授权人的身份版本，让旧会话重新拉取能力。
- 默认有效期：到当前学年结束（8 月 31 日）。快到期前 14 天通知授予者复核（以后接通知）。
- 本期不做管理网页：授予通过受控接口和运维手册完成，与 DEVELOPMENT-PLAN「不新增管理网页」一致。

### 3.7 身份认证（金V，第二期）

单独一张 `identity_verifications`：申请人、身份类型（班长 / 学委 / 社团负责人 / 教师）、所属范围、证据（只存对象存储私有路径，不进日志）、审核人、结论、有效期。

- 认证通过只改**展示**：帖子和主页显示「金V · 计算机学院 2025 级班长」。
- 认证不自动带运营权限；需要发活动的社团负责人，另外授予「组织负责人」。
- iOS 的认证勾改为读认证结果，不再读 `role`。

### 3.8 组织（第二期）

`organizations`（名称、所属学校、可选学院、奖励档位、状态）+ 组织成员（通过 `role_grants` 的 `ORG` 范围表达）。

- 活动加 `org_id`，主办方名从组织带出，不再手填 `organizer_name`。
- 组织负责人都能管理本组织的活动：换届时撤销旧负责人、授予新负责人，活动照常可管。
- 奖励档位由校运营给组织定，组织负责人发活动时不能超过。

---

## 4. 现在的数据怎么迁过去（V36）

| 现在 | 迁移后 |
|---|---|
| `role=ADMIN` | 保留平台角色 ADMIN（`role_grants` 写一条 PLATFORM 授权，理由「历史数据迁移」） |
| `SCHOOL_OPERATOR` + scope `school` | `SCHOOL_OPERATOR @ SCHOOL:{当时的 school_id}` |
| `COLLEGE_OPERATOR` + scope `college` | `COLLEGE_OPERATOR @ COLLEGE:{当时的 college_id}` |
| `MAJOR_OPERATOR` + scope `major` | `MAJOR_OPERATOR @ MAJOR:{当时的 major_id}` |
| `OPERATOR`（泛运营，没有范围） | 迁移时列出来人工确认；默认不迁，避免凭空给出权限 |
| 迁移时缺学校 / 学院 / 专业的运营 | 不迁，列入迁移报告 |

- 迁移是**按当时的资料快照**，之后用户改资料不再影响授权——漏洞 A 就此关闭。
- `users.role` 之后只表示平台角色（USER / ADMIN）；`operator_scope` 停止写入。
- `/api/v1` 只做加法：`PostView.author.role`、`operatorScope` 继续返回，改为从授权推导，旧客户端不受影响。
- 测试库先迁、跑越权矩阵测试，再考虑生产（生产部署仍需你单独批准）。

---

## 5. 测试怎么做

- **越权矩阵单测**：角色 × 能力 × 目标范围（本学院 / 同校别的学院 / 别的学校 / 平台），每格断言放行或 403。
- **越级授予**：院运营授予校运营、跨校授予、给自己授予，全部拒绝。
- **锚定回归**：院运营改了自己的学院后，仍只能发原学院的活动（漏洞 A 的回归测试）。
- **过期与撤销**：过期或撤销后的下一次请求立即失去能力；审计记录完整。
- **迁移演练**：用隔离 MySQL 跑 V36，核对迁移报告与授权行数。

---

## 6. 分期

### 6.1 第一期：堵漏洞 + 给 O01 铺路（约 4–6 人日）

1. V36：`role_grants` 表 + 历史数据迁移 + 迁移报告。
2. `permission` 模块：能力清单、角色模板、`PermissionApi`，以及越权矩阵单测。
3. 活动发布、管理和官方帖改为从授权取范围与奖励上限（**修漏洞 A**）。
4. 后台帖子审核、攻略关联、审计日志、报表按授权范围过滤和校验（**修漏洞 B**）；轮播加 `school_id`。
5. 授予、撤销、查询接口 + `/users/me/capabilities`；改角色的旧接口 `PUT /admin/users/{id}/role` 改为调用新授予逻辑，审计改为 `recordRequired`。
6. 有运营授权的账号不能自行切换学校；普通用户切换学校前校验学校存在（多校前再决定是否需要学号或邮箱认证）。
7. iOS：「发布活动」「签到码」等入口改读 capabilities；认证勾暂时仍按「有运营授权」显示，第二期改为金V。
8. 新增「审核员 MODERATOR」角色，O01 举报处理直接用 `report.handle`。

### 6.2 第二期：组织与金V（约 6–8 人日）

组织表与组织负责人、活动挂组织、金V 申请与审核流、iOS 申请入口与认证展示。

### 6.3 第三期：按需

校运营向下授予（自助任命院运营、审核员）、到期复核提醒、授权变更通知被授权人。

---

## 7. 需要你拍板

1. **第一期的角色清单够不够**：平台管理员、平台内容编辑、校运营、院运营、专业运营、审核员。要不要再加「年级运营」？（规划里提过，但第一所学校「院级暂不设」）
2. **谁能授予**：建议第一期只允许平台管理员授予，校运营向下授予放到第三期。
3. **默认有效期**：建议到当前学年结束（8 月 31 日），到期需复核续期。
4. **泛运营 OPERATOR 怎么处理**：建议迁移时不自动迁，列出名单由你确认。
5. **有授权的人能不能改学校**：建议不能；要换学校先撤销授权。
6. **金V 是否带权限**：建议只做展示与信任标识，权限另行授予。
7. **组织（社团）放在第几期**：建议第二期，排在 O01 举报处理之后。

拍板后我从第一期开始做；建议排在 F01/F02 之前或同时做，因为举报处理（O01）需要「审核员」角色和按学校隔离。
