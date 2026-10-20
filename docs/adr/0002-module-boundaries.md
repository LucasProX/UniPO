# ADR-0002 · 模块边界与迁移顺序

- **状态**：已接受（2026-10-20）
- **关联**：ADR-0001 D5（模块化单体）
- **落点**：backend/src/test/java/com/biecuoguo/ArchitectureTest.java

## 1. 现状

后端是**按层分包**：web / service / mapper / domain / dto / security / state / realtime / config。
好处是新人一眼能看懂「Controller 在哪」，坏处是**业务边界不可见**：
PostService 可以随手调 MessageMapper，AdminService 可以读任意表，半年后没人说得清
「发帖」这件事到底横跨多少文件。

## 2. 目标

按领域分包，每个模块内部再分层：

    com.biecuoguo
      auth/        api/ internal/（登录、注册、验证码、JWT、会话）
      user/        api/ internal/（资料、关注、统计、在线状态）
      post/        api/ internal/（帖子、评论、点赞、收藏、分页）
      media/       api/ internal/（上传、文件头识别、MinIO）
      message/     api/ internal/（会话、私信、未读）
      realtime/    api/ internal/（WebSocket、broker、续传）   ← 已经是独立包
      taxonomy/    api/ internal/（学校、学院、专业、标签）
      admin/       api/ internal/（后台、审核）
      infra/       （config / state / security 基础设施）

规则：

1. 跨模块只能依赖对方的 api 包（facade + DTO），不能碰 internal。
2. 模块之间不共享 Mapper；要读别人的数据，走对方的 api。
3. 跨模块一致性用领域事件 + Outbox，不做分布式事务。
4. 表按模块归属，禁止跨模块直接写别人的表。

## 3. 为什么不是一次性搬完

- 现在有 124 个 Java 文件、22 个 Mapper、19 个 Controller。一次性改包名会制造一个
  「除了 import 什么都没变」的巨型 diff，review 成本极高，出问题也难二分。
- 模块边界不是搬出来的，是**依赖关系理出来的**：先看清谁依赖谁，再决定 api 切在哪。
- 每一步都必须保持 CI 绿、smoke 37/37。这比「一次到位」更重要。

## 4. 迁移顺序（每步一个 PR）

| 顺序 | 模块 | 为什么先做 |
|---|---|---|
| 0 | realtime / state / config | 已经是独立包，先立成 infra 边界 |
| 1 | media | 依赖少（ImageSignature + MinIO + media 表），适合当样板 |
| 2 | taxonomy | 纯字典表，读多写少 |
| 3 | message | 已经和 realtime 配合，边界相对清楚 |
| 4 | post | 最大的一块，等前面把 api 的形状摸清 |
| 5 | user / auth | 被所有模块依赖，放后面 |
| 6 | admin | 横向切面，最后收 |

## 5. 现在就能守住的规则（ArchitectureTest）

- web 不能直接依赖 mapper（Controller 只经 Service）
- service 不能依赖 web（不碰 HTTP）
- domain 不依赖 Spring / service / web / security / realtime / state
- mapper 只依赖 domain
- realtime 不依赖 service / web（它是被业务调用的底层能力）

**注意**：ArchUnit 的包名必须写全限定前缀。用 ..web.. 会把 org.springframework.web.socket
一起匹配进去，规则看起来生效、实际拦的是框架类——这个坑第一次写规则时就踩了。

## 6. 后续收紧（模块包落地后）

- 跨模块只能依赖 api：noClasses().that().resideOutsideOfPackage(..x..).should().dependOnClassesThat().resideInAPackage(..x.internal..)
- admin 不能直接调业务 Mapper，只能走各模块 api
- 事件发布方不能直接调订阅方（只能发事件）

## 7. 迁移记录

### media（2026-10-20，第一个模块）

结构：

    com.biecuoguo.media
      api/MediaDtos.java                 ← 对外只暴露 DTO
      internal/MediaController.java
      internal/MediaService.java
      internal/ImageSignature.java
      internal/MediaObject.java
      internal/MediaObjectMapper.java

改动点：

- MediaObjectMapper 不在 com.biecuoguo.mapper 下了，@MapperScan 必须显式加
  com.biecuoguo.media.internal（漏了这个，编译能过、启动时 Mapper 找不到）。
- MediaController 原来和 ApiResponse 同包，不需要 import；搬家后必须显式 import
  com.biecuoguo.web.ApiResponse。**web 包暂时充当共享内核**（统一响应包/错误码），
  等 infra 模块落地再决定是否下移。
- ImageSignatureTest 跟着移到 media.internal，包名和被测类一致。
- 新增 ArchUnit 规则：media.internal 不能被模块外访问；其余模块只能依赖 media.api。

验证：mvn test 39 绿；smoke.sh 37/37（含图片上传）；CI 全绿。

### taxonomy（2026-10-20，第二个模块）

结构：

    com.biecuoguo.taxonomy
      api/TaxonomyDtos.java
      internal/TaxonomyController.java
      internal/TaxonomyService.java

**刻意的妥协**：School / College / Major / Tag / Category 的实体与 Mapper 还留在共享的
domain / mapper 包里，没有一起搬。原因：PostService 直接用 SchoolMapper/CollegeMapper/MajorMapper
拼作者资料，直接搬走会让 post 模块瞬间依赖 taxonomy.internal。

正确做法是给 taxonomy 加一个查询 api（批量取学校/学院/专业名称），等 post 模块迁移时一起改。
在那之前，taxonomy 模块只收「自己的 Controller/Service/DTO」，边界仍然成立：
外部不能碰 taxonomy.internal。

新增 ArchUnit 规则：taxonomy.internal 不能被模块外访问。

验证：mvn test 40 绿；/api/schools、/api/categories、/api/tags 均 200；smoke 37/37；CI 全绿。

### message（2026-10-20，第三个模块）

结构：

    com.biecuoguo.message
      api/MessageDtos.java
      internal/MessageController.java
      internal/MessageService.java
      internal/Conversation.java
      internal/Message.java
      internal/ConversationMapper.java
      internal/MessageMapper.java

这次是完整搬走（实体 + Mapper 都进了 internal），因为 Conversation/Message 只被
MessageService 使用，没有其他模块直接读这两张表。

遗留耦合（写在注释和 AGENTS 里，别当没看见）：

- MessageService 还在用共享的 UserMapper / User（拼对方资料）；
- MessageDtos 依赖共享的 PostDtos（会话里会带一条帖子预览）；
- 还要 import com.biecuoguo.service.PresenceService（在线状态，也是共享服务）。

这些等 user / post 模块迁移时一起收口。@MapperScan 第三次扩包：
com.biecuoguo.mapper + media.internal + message.internal。

验证：mvn test 41 绿；smoke 的会话/发信/未读全部 200；WebSocket 实测仍能收到 event:message；CI 全绿。

### post（2026-10-20，已通过 facade 收口）

结构：

    com.biecuoguo.post
      api/PostDtos.java
      api/PostQueryApi.java          ← 唯一的跨模块出口
      internal/PostService.java
      internal/PostController.java
      internal/PostV1Controller.java
      internal/{Post,PostImage,PostLike,PostFavorite,PostShare,PostDailyStat,Comment,CommentLike}.java
      internal/{...}Mapper.java

第一次只搬了 Controller/DTO，因为 UserService / AdminService 直接注入 5 个帖子 Mapper。
这次先设计 facade，再整体收口：

    public interface PostQueryApi {
        record UserPostStats(long posts, long comments, long likes, long favorites, long shares) {}
        record AdminPostStats(long posts, long publishedPosts, long comments) {}
        UserPostStats statsForUser(long userId);
        AdminPostStats adminStats();
        ... userPosts / likedPosts / favoritePosts（含 byUid 变体）
    }

调用方变化：

- UserService：5 个 Mapper 字段换成 1 个 PostQueryApi，stats() 只拼 following/followers；
- AdminService：PostMapper/CommentMapper 换成 PostQueryApi；
- PublicUserController / UserController：直接依赖 PostQueryApi 而不是 PostService。

facade 的边界刻意定得窄：只暴露「页面需要的问题」，不暴露实体、不暴露 Mapper。
统计用 record 返回，避免调用方拿到实体后自己再拼 SQL。

@MapperScan 第四次扩包：加 com.biecuoguo.post.internal。

验证：mvn test 42 绿；/users/me/stats 返回真实计数；
/api/admin/analytics/overview 返回 users/posts/publishedPosts/comments；smoke 37/37；CI 全绿。

## 8. 代价

- 迁移期间会出现「两套写法并存」（旧层分包 + 新模块分包），持续几周。
- 包移动期间 git blame 会断一次，属于可接受成本。
- 模块 api 会多一层 DTO 映射；等真正拆服务时，这层映射就是现成的边界。
