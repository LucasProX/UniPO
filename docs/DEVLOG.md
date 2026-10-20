# 开发手记

自己的开发记录。记的是「当时怎么想、为什么这么改、哪里绕了弯路」，不是 API 文档也不是提交日志。

规矩很简单：踩到那种「当时想不通、想通了觉得值得记一笔」的坑，就当天记下来，不用等收尾。
写作格式见文末。

---

## 2026-10-20 游标分页：先写错一遍才想明白

做 feed 分页的时候，我一开始用的是最简单的写法：游标只装上一页最后一条的 id，SQL 加个 `id < cursor`。
写完自测，拉了 3 页，每页 5 条 —— 三页全是同一批：`2,3,1,4,5`。

第一反应是 SQL 写错了。于是打开 MyBatis 的 DEBUG 日志看真实执行的语句，发现生成的 SQL 就是我想写的：

```sql
WHERE status = ? AND school_id = ? AND id < 15 ORDER BY pinned DESC, official DESC, ... LIMIT 6
```

返回 `2,3,1,4,5,14`。看着很离谱，但**它完全正确**。

因为 ORDER BY 第一位是 `pinned`，第二位 `official`，一路排到 `like_count` —— 这是按热度排的。
id 小的帖子完全可能因为点赞多而排在 id 大的前面。所以 `id < 15` 这个条件跟排序键**没有任何关系**，
它只是把 id 大于 15 的过滤掉了，剩下的顺序一点没变，自然每页都吐同一批。

结论：游标分页的前提是「游标必须编码完整的排序键」，然后用 WHERE 把 ORDER BY 的字典序复现出来。
排序键有 7 个字段，游标就得装 7 个字段，少一个都会重复或漏条。

改成 keyset pagination：游标用 `|` 分隔存 `pinned|official|dontMiss|likeCount|commentCount|publishedAt|id`，
查询条件变成一串 OR：

```
pinned < k
  OR (pinned = k AND official < k)
  OR (pinned = k AND official = k AND dontMiss < k)
  OR ...
  OR (前 6 个全等 AND id < k)
```

这里还有两个 MyBatis-Plus 的小坑：`LambdaQueryWrapper` 没有 `and(Consumer)` 方法，只有 `nested`，
得用 `nested(w -> {...})` 把整个 OR 链包起来；另外静态嵌套 record 里调不到外部类的私有方法，
只能把 `getLikeCount() == null ? 0 : getLikeCount()` 这种空值兜底内联进去。

教训：**游标分页不是「记住位置」，是「记住排序键的值」**。如果排序规则复杂到不好编码，
就说明它不适合做游标分页，得先简化排序。

---

## 2026-10-20 统一响应包：为什么要加 code 和 requestId

之前所有接口返回的是 `{"data": ...}`，错误返回 `{"message": "中文提示", "timestamp": "..."}`。
看起来挺简洁，问题在客户端那侧：

App 想判断「是业务失败还是网络断了」，只能去看 `message` 的内容。于是代码里到处都是
`if (msg === "邮箱或密码不正确")` 这种东西。哪天我把提示语里的「不正确」改成「错误」，
所有依赖它的逻辑就静默失效了 —— 而且编译不报错，测试也不会挂，只有用户会发现行为变了。

所以要加一个机器可读的 `code`。设计上定了三条：

**第一，HTTP 状态码保留语义，不走「一律 200 + code」那套。**
理由是基础设施全都看 HTTP 状态码：Caddy 会按 5xx 报警，负载均衡按 4xx/5xx 决定要不要重试，
CDN 会缓存 200。如果服务端崩了还回 200，监控面板上就是一片绿，等你从用户嘴里知道出事了。
所以分工是：`code` 给 App 做业务分支，HTTP status 给代理/监控/重试策略看，各管一段。

**第二，`code` 用枚举而不是字符串常量。**
字符串散在各处就会拼错、就会漂移，`AUTH_INVALID_CREDENTIAL` 和 `AUTH_INVALID_CREDENTIALS`
差一个 S 编译器不会管。放进 `ErrorCode` 枚举之后，加一个新码必须显式声明默认提示语，
而且引用处写错名字直接编译失败。

**第三，`data` 字段一定要保留。**
这条是纯工程考虑：前端所有请求都走同一个 `unwrap()`，实现就是 `return response.data.data`。
只要新响应包里还有 `data`，前端一行都不用改，全站零回归。这是故意的兼容设计，不是巧合。

`requestId` 是顺手加的，但实际很实用。用户说「我发帖失败了」，没有 requestId 的时候只能靠时间戳猜，
或者让他复现一次。现在每个请求分配一个 16 位 id，同时写进响应头 `X-Request-Id` 和服务端日志的 MDC，
拿到那个 id 直接 grep 日志就精确命中那一次请求 —— 包括它跑了哪些 SQL、耗时多少、报的什么错。

实现上有个细节：这个过滤器必须排在 Spring Security 之前，否则安全层的日志就带不上 id。
这里没用 `@Order` 注解，而是显式 `FilterRegistrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE)` ——
因为 Spring Boot 对 `@Component` 过滤器跟 SecurityFilterChain 的注册顺序**不保证**谁先谁后。
这个坑在下面 addPathPrefix 那节还会再遇到一次。

---

## 2026-10-20 GlobalExceptionHandler 补上 500 兜底

原来那个异常处理器写了 400/401/403/404/413 的映射，但**没有 `@ExceptionHandler(Exception.class)`**。
这意味着任何没被显式接住的异常都会走 Spring 默认错误页，返回的是 Boot 自己那套
`{"timestamp","status","error","path"}`。

后果很讽刺：我们刚花力气把响应包统一了，但**最需要它的场景反而不统一** —— 服务端真的出 bug 的时候，
客户端拿到的是另一个格式，解析不出 `code`，也拿不到 `requestId`。而这恰恰是最需要定位信息的时刻。

所以补了兜底。这里有个决定我觉得比代码本身重要：

**兜底里绝对不能把 `ex.getMessage()` 回给客户端。**

原因很实在：异常消息里经常带 SQL 片段、文件绝对路径、内网主机名、甚至数据库连接串。
这些是攻击者最喜欢的信息，免费的信息泄露。所以兜底只回一句固定的「服务器开小差了」，
真正的堆栈用 `log.error` 打出去，靠 requestId 关联。想看详情？有服务器权限的人去看日志。

顺手还补了三个之前漏掉的映射：请求体不是合法 JSON（`HttpMessageNotReadableException`）、
静态资源不存在（`NoResourceFoundException`）、方法不支持（`HttpRequestMethodNotSupportedException`）。
尤其是第一个，做时间字段迁移的时候特别容易触发 —— 客户端传个裸的 `2026-10-19T15:22:01`，
而字段类型是 `OffsetDateTime`，Jackson 解析失败，如果没接住就变成 500。

### 这里有个不对称，我漏过一次

`@RestControllerAdvice` 是 **DispatcherServlet 层**的东西。但认证/鉴权失败发生在更早的
Security filter chain 里，那些请求**根本进不到 DispatcherServlet**，全局异常处理器完全管不到。

我是先只改了 `GlobalExceptionHandler`，然后测 401，发现返回的还是老格式
`{"message":"请先登录","timestamp":...}`，才反应过来。
最后在 `SecurityConfig` 的 `authenticationEntryPoint` 和 `accessDeniedHandler` 里手写了一份同格式的响应。

教训：**响应格式有两个产出点，一个在 DispatcherServlet 里，一个在 Security 过滤器链里。**
改格式必须两处一起改，否则就会出现「大部分接口对了但 401 还是老的」这种半吊子状态。

---

## 2026-10-20 时间字段：为什么 LocalDateTime 是错的

这件事我一开始觉得是「可选项」，后来越想越觉得是硬伤。

MySQL 的 `DATETIME` 不存时区。Java 的 `LocalDateTime` 也不带时区。两边都不带，看起来「很配」，
实际上是**双重丢失** —— 没人知道那个裸时间属于哪个时区。

具体会出什么事：数据库里存 `2026-10-19 15:22:01`，接口返回 `"2026-10-19T15:22:01"`。
成都的用户看到，以为是本地时间 15:22；东京的用户也以为是本地 15:22。只有东八区的人碰巧猜对了。

更要命的是算相对时间。App 想显示「3 分钟前」，得拿服务端时间减客户端时间。
但客户端不知道那个裸时间属于哪个时区，减出来的结果可能差 8 小时。这种 bug 在国内测试完全发现不了，
等有海外用户反馈才暴露，而且很难复现。

所以接口层必须带偏移：`"2026-10-19T15:22:01+08:00"`。

### 为什么数据库不改

理论上最干净的做法是数据库也存带时区的类型（`TIMESTAMP`）。但有两个现实问题：
一是 `DATETIME` 改 `TIMESTAMP` 要迁移存量数据，而且要处理迁移期间的双写；
二是 MySQL 的 `TIMESTAMP` 有 2038 年的上限。

最后决定：**数据库不动，只在接口边界转换。** 存进去还是 `LocalDateTime`，吐出来转成 `OffsetDateTime`。
新增了 `config/AppTime.java` 专门干这件事，全项目只有这两个方向：
出参 `AppTime.toOffset(...)`（写在 DTO 的 `from()` 里），入参 `AppTime.toLocal(...)`（Service 落库之前）。
不在业务逻辑中间到处转换，免得有一天忘了转。

### 两个实现上的选择

**为什么做成静态工具类而不是普通 Bean。**
因为 DTO 的 `from()` 是静态工厂方法，Spring 注入不进去。所以做成「Spring 构造时初始化一个静态字段，
之后全是静态方法」。不算优雅，但这东西全局只读、没有并发问题，是这个约束下的最省事做法。

**为什么用配置项而不是 JVM 默认时区。**
如果写 `ZoneId.systemDefault()`，那服务器时区一改（比如迁到 UTC 的云主机），所有接口返回的偏移值就悄悄变了 ——
前端存下来的历史数据全部错位，而且是那种「上线三天后才有人发现」的错位。
所以用 `app.time-zone` 显式钉死，默认 `Asia/Shanghai`，换机器不改变语义。

---

## 2026-10-20 addPathPrefix 把全站搞成 401

做 `/api/v1` 版本化的时候，我先想到的是最「正统」的写法：`WebMvcConfigurer.configurePathMatch` + `addPathPrefix`。
看起来就是「给所有接口加个前缀」，一行配置搞定。

写完编译通过，重启，然后：

```
/api/schools      -> 401
/api/categories   -> 401
/api/auth/login   -> 401     连登录都进不去
```

排查方式是把它改名成 `.bak`，重新编译重启，`/api/schools` 立刻恢复 200。确认就是它干的。

原因是 `addPathPrefix` 并不是「在字符串前面拼一段」，它**重建了整套 path matching 配置**。
而 Spring Security 的 `AntPathRequestMatcher` 用的是另一套解析逻辑，两边对不上。
结果就是 `SecurityConfig` 白名单里写的 `/api/schools` 匹配不到任何真实请求，
所有请求统统落到 `anyRequest().authenticated()`，全变 401。

最后改成 Servlet Filter 直接改写 URI：`/api/v1/xxx` 改写成 `/api/xxx` 再往下走。
这样白名单只要维护一份（都是 `/api/...`），MVC 层的路径匹配完全不动。

这里又踩了一次顺序的坑：最开始的 `@Order(1)` 不起作用，因为 Boot 不保证 `@Component` 过滤器
跟 SecurityFilterChain 谁先注册。改成 `FilterRegistrationBean` + `setOrder` 才稳。

教训：**框架里那些「看起来只是加个前缀 / 改个默认值」的 API，实际可能重建整条链路。**
动全局路径匹配这种地方，先在最小场景验证，别一次改完再一起看结果。

---

## 2026-10-20 卡我最久的居然是个换行符

这个坑值得单独写一节，因为它的症状完全不指向原因。

smoke 脚本里加了这几行验证 `/api/v1`：

```powershell
$Base = 'http://127.0.0.1:8080'
$v1 = ($Base.TrimEnd('/')) + '/api/v1'
```

跑出来所有 v1 请求都是 `http=000` —— curl 连都没连上，说明 URL 是坏的。

加 DEBUG 打印：

```
DEBUG Base=[http://127.0.0.1:8080] v1=[]
```

`Base` 明明有值，`v1` 是空串。可同样的两行单独写个 `.ps1` 跑，结果完全正确。

接下来试了一堆方向：换拼接方式、用 `Join-Path`、改成格式化字符串……全都没用。
因为方向从一开始就错了 —— 我一直在怀疑表达式。

真正的原因：**我用编辑工具改这个文件，它被写成了 LF 换行。**
PowerShell 5.1 解析 LF-only 的 `.ps1` 会出现这种诡异的静默失效：变量赋值看起来执行了，值却是空的，
而且不报任何错。整个文件转回 CRLF，立刻正常。

事后看 git diff 才发现，原本的文件就是 CRLF，是我自己给破坏的。

教训：**遇到「逻辑明明没错但结果就是空/就是不改」的症状，先怀疑文件编码和行尾，别怀疑逻辑。**
PowerShell 5.1 对行尾很敏感，而且报错信息完全不指向真正的原因。现在改完 `.ps1` 一律转回 CRLF。

---

## 2026-10-20 两个 replace 的坑，各浪费了一轮

**坑一：`tools.edit` 对 CRLF 文件静默不匹配。**
我给 `SecurityConfig` 加统一错误格式，`old_string` 里用的是 `\n`，但文件里是 `\r\n`。
编辑工具没报错，也没提示「没找到」，就是什么都没改。编译通过，重启，测 401 —— 还是旧格式。
我当时以为是代码逻辑错了，又去翻了一遍，最后才发现根本没写进去。

→ 现在改文件前一律先 `read` 拿到真实的行内容，不在脑子里拼 `old_string`。

**坑二：JS 字符串里的 `$'` 是个特殊模式。**
我在生成 PowerShell 脚本时，往 `String.replace()` 的替换串里塞了一个正则 `'T\d{2}:\d{2}:\d{2}$'`。
结果 `$'` 被 JS 当成「匹配位置之后的所有内容」这个特殊占位符，把后面的东西全吃掉了，
生成的 `.ps1` 语法错误，报的还是莫名其妙的错。

→ 替换内容里含 `$` 的时候，用函数式替换：`s.replace(old, () => newText)`，别传字符串。

---

## 2026-10-20 三个安全漏洞：默认值是最贵的决定

这次清 R-1 / R-2 / R-3，三条都是同一类问题：**代码写了「这是个开关」，但默认值是危险的那一侧。**

R-1 是验证码。`application.yml` 里写的是 `${VERIFICATION_MOCK_ACCEPT_ANY_CODE:true}` ——
冒号后面那个 `true` 就是默认值。也就是说，只要没人显式设这个环境变量（而绝大多数部署都不会设），
验证码校验就是「任意 4 位数字通过」。任何人都能用任意邮箱注册，然后走 `reset-password` 接管账号。

R-2 是三个演示账号。`AuthService.run()` 每次启动都无条件创建 `good` / `xiaoba` / `yuanhua`，
密码跟用户名一样。这不是「测试代码忘了删」——它是正经写在 `CommandLineRunner` 里的启动逻辑，
所以生产环境照样会执行。

R-3 最离谱：每次启动 `admin.setPasswordHash(encode(BOOTSTRAP_ADMIN_PASSWORD))`。
compose 里 `BOOTSTRAP_ADMIN_PASSWORD` 的默认值是 `Admin@123456`。所以哪怕管理员上线第一天就改了密码，
下次重启又变回 `Admin@123456`。**改密码这个操作在这个系统里是无效的。**

### 当时怎么想的

第一版我打算只改默认值：把 `true` 改成 `false`，本地 profile 再写回 `true`。这确实能解决 90% 的场景。

但我停下来想了一下：**「记得别在生产打开」这种约束，靠什么保证？**
答案是靠人记得。而人一定会忘 —— 尤其是半年后有个紧急问题，有人在服务器上 `export` 一堆环境变量调试，
顺手把 `VERIFICATION_MOCK_ACCEPT_ANY_CODE=true` 也带上了，然后再也没删。

打一条 WARN 也没用。启动日志几百行，没人看；就算看到了，也不会为了一个黄色警告停下来。

所以最后加的是 `config/StartupSafetyGuard.java`：非 `local/dev/test` profile 下，只要这两个开关有一个是 true，
**直接抛异常让进程起不来**。效果是：

```
java.lang.IllegalStateException: 拒绝启动：当前 profile=[] 不是开发环境，
但开启了危险开关 [app.verification.mock-accept-any-code=true]。...
```

这个决定的逻辑是：**一个静默的安全漏洞，比一个起不来的服务危险得多。**
服务起不来，五分钟内就有人来找你；漏洞静默存在，可能要等半年后某个陌生邮箱注册进来才发现。

### 为什么用 @PostConstruct 而不是 ApplicationRunner

第一版我把检查写成了 `ApplicationRunner`，跟 `AuthService` 一样。写完突然意识到不对：
`ApplicationRunner` 是容器初始化**完成之后**才跑的，那时候 `AuthService` 已经把演示账号和
重置过的管理员密码写进库了。检查跑在写库之后，等于没检查。

改成 `@PostConstruct`，在 bean 初始化阶段就拦住，早于任何 `ApplicationRunner`。
这种「执行顺序」的坑很容易漏，因为两个写法看起来都是「启动时跑一下」。

### R-3 真正的修法不是「加个判断」

我一开始想的是「那就在 setPasswordHash 前加个 `if (admin.getPasswordHash() == null)`」。
但读了一遍那段代码发现，它不只是改密码 —— 它把 nickname、avatar、level、xp、status 全部重写了一遍，
然后无条件 `updateById`。也就是说：**管理员在后台改的昵称和头像，也会在下次重启时被悄悄改回去。**

所以正确的修法是把整个块拆成两个分支：

- `admin == null`：首次创建，bootstrap 配置全部生效。
- `admin != null`：只补真正缺的字段（`publicUid` / `role` / `status`），用一个 `dirty` 标志决定要不要写库；
  密码、昵称、头像一律不碰。

验证方式我想了个比较硬的：BCrypt 每次 `encode()` 都会生成新的随机盐，所以只要重新编码过，
哈希串一定变。于是重启前后各查一次 `password_hash`：

```
BEFORE: $2a$10$H8E2fa0U3Wp1syVQRPIyw.3NFbR47lkpvZpiROOFr.ob8yKrerLv2  2026-10-20 09:08:12
AFTER:  $2a$10$H8E2fa0U3Wp1syVQRPIyw.3NFbR47lkpvZpiROOFr.ob8yKrerLv2  2026-10-20 09:08:12
```

完全一致 → 证明真的没重新写库。这比「登录还能成功」强，因为密码没变的情况下登录本来就会成功，
那种验证根本区分不出「没改」和「改成了同一个值」。

### 一个没绕过去的坑

验证演示账号登录的时候，我用 curl 发 JSON 一直返回 400 `VALIDATION_FAILED`。
查了半天以为是 DTO 字段名错了（`account` 还是 `email`），改成 `email` 还是 400。

真正原因是 **PowerShell 5.1 传给原生程序的参数会剥掉内层双引号**。
`-d '{"email":"..."}'` 传到 curl 手上变成了 `{email:...,password:...}`，不是合法 JSON，
于是 `HttpMessageNotReadableException` → 400。

这个坑以前在 smoke 脚本里踩过（当时的解法是 `--data-binary "@$file"`），这次又踩了一遍。
最后把 JSON 写进临时文件，用 `--data-binary $arg` 才通过。

→ **以后在 PowerShell 里给 curl 传 JSON，一律走文件，不要内联。**

### 还剩什么

R-1 其实只修了一半。默认值翻了、启动拦截加了，但**真实发邮件的通道还没接**。
现在 `mock=false` 的话，验证码只会被 `System.out.printf` 打到日志里，用户根本收不到。
所以严格说，R-1 现在是「安全但不能用」—— 要真正上线必须接 SMTP / Resend / SES。
这一点我写进 `docs/ISSUES.md` 了，免得下次以为已经完事。

---

## 2026-10-20 注册改成「随便填」——顺带发现密码长度是个假上限

之前注册要邮箱格式、密码至少 6 位、还要填昵称和 4 位验证码。自己测的时候要开一堆邮箱，
烦到不行。于是决定：开发阶段注册只要两个东西 —— **账号 + 密码**，其它全可选。

具体放宽了什么：

- `@Email` 去掉了。账号可以是任意字符串，`test1`、`测试号`都行。反正内部统一 `toLowerCase()` 之后判重。
- `nickname` 可选，不填就直接用账号本身当显示名 —— 反正账号是唯一的，昵称自然也唯一。
- `confirmPassword` 可选。界面上还是让填（防手滑），但接口层面允许省略，给 App 端留余地。
- `verificationCode` 在 mock 模式下**完全不需要**。判断条件是 `mockAcceptAnyCode == false` 时才校验，
  也就是只有真实环境才要求。
- 密码下限在 mock 模式下放到 1 位。`password: "1"` 能注册。

### 密码「不限长度」其实是个陷阱

我一开始想直接删掉 `@Size` 约束，让它真的不限长。写到 `passwordEncoder.encode()` 的时候停了一下，
因为想起来 BCrypt 有个 72 字节的输入上限。

去确认了一下：BCrypt 的算法只吃前 72 字节，超出的部分**静默丢弃**。不是报错，是丢掉。
后果是两个前 72 字节相同、后面不同的密码，会算出**同一个哈希**，都能登录同一个账号。
这个 bug 你测不出来 —— 除非专门拿两个超长密码去试。

所以最后没删上限，而是加了个显式检查：

```java
int bytes = password.getBytes(StandardCharsets.UTF_8).length;
if (bytes > 72) {
    throw new IllegalArgumentException(
        "密码太长：最多 72 字节（约 72 个英文字符或 24 个汉字），当前 " + bytes + " 字节");
}
```

**注意这里算的是字节不是字符。** 一个汉字在 UTF-8 里是 3 字节，所以 24 个汉字就到顶了。
如果按 `String.length()` 判断（那是 UTF-16 code unit 数），中文用户能填 72 个汉字 = 216 字节，
照样被截断，等于白检查。

宁可给一条明确的中文报错，也不要在背后悄悄截断 —— 截断是那种「上线三个月后才有人发现」的坑。

### 一个自己挖的坑

改 `AuthDtos.java` 的时候，我用字符串 `replace` 去换那几行 record 定义，跑完一编译报错。
看了下文件，那段文字一个字都没变。

原因又是 CRLF：文件是 `\r\n`，我 `old_string` 里拼的是 `\n`。而这已经是这个项目里第三次踩同一个坑了。
（前两次：改 `SecurityConfig` 静默不生效、改 `smoke.ps1` 把文件写成 LF 导致 PowerShell 变量算成空串。）

后面的改法固定成：先 `read` 拿到真实行，再 `split(/\r?\n/)` 按行操作，最后 `join("\r\n")` 写回。
不拼多行字符串，就不存在这个问题。

### 前端那边

`validateRegisterForm` 里原来把昵称和验证码都当必填。改成：昵称留空直接跳过、验证码填了才检查格式。

还有一个小 bug：`checkRegisterNickname()` 在昵称为空时 `return false`，而 `register()` 里是
`if (!(await checkRegisterNickname())) return;` —— 意思是「昵称为空」会被当成「有错误」直接中止注册。
改成 `return true` 才通。这种「返回值语义反了」的 bug 编译器管不了，只能靠读代码或者测出来。

界面文案也跟着改了：「绑定邮箱」→「账号」、`type="email"` → `type="text"`、
昵称 placeholder 改成「选填，默认与账号相同」、验证码 placeholder 改成「开发环境可留空」。

### 验证

smoke 从 29 项加到 33 项，新增 4 项专门盯这个：

- `register-minimal`：只传 `{email, password:"1"}`，确认 200 且昵称自动变成账号；
- `register-minimal-login`：拿刚建的号登录，证明真落库了；
- `register-duplicate`：重名必须 400；
- `register-longpassword`：73 字节必须 400，不能被静默截断。

另外手工试了中文账号 + 中文密码、`account` 字段别名、72 字节正好能过 —— 都对。

---

## 2026-10-20 把 notices 那一整套删了——先搞清谁在用，再动刀

项目里一直有两套内容模型：`posts`（新，前端在用）和 `notices`（最开始的「校园通知」版本）。
前端早就只调 posts 了，但 notices 从表到 Controller 全都还留着，19 个 Java 文件、5 张表。
每次改内容相关的东西我都要先花几分钟确认「这个类是干嘛的、还有没有人调」，成本高得离谱。

### 一开始想「直接删文件不就行了」

试了一下，编译不过。因为 notices 不是一个独立模块，它像藤蔓一样缠在 posts 那边：

- `AdminService.overview()` 统计的是 notices 的发布数；
- `UserService.stats()` 里的「已完成 / 临近截止 / 避坑」全是从 `user_notice_status` 和 `reminders` 来的；
- `CommentController` 的评论接口是挂在 `/api/notices/{id}/comments` 上的；
- `SecurityConfig` 白名单里有 `/api/notices/**`；
- `NoticeDtos` 里还藏着 `TagView` / `CategoryView`，而这两个被 `TaxonomyService` 用着。

所以第一件事不是删，是**查清楚引用关系**。我按类名一个个 grep，把「只有 notices 内部互相引用」
和「被 posts 侧共用」分成两类：

只有 notices 用 -> 删；被 posts 共用 -> 改。

具体到最有迷惑性的两个：

**`comments` 是两套共用的。** V1 建表时 `notice_id` 是 NOT NULL，V3 才 `MODIFY notice_id BIGINT NULL`
并加了 `post_id`。也就是说同一张表里躺着两种评论。这个必须保留，只把 `notice_id` 列摘掉。

**`NoticeDtos` 里有不属于 notices 的东西。** `TagView` / `CategoryView` 只是恰好写在这个文件里，
`/api/categories` 和 `/api/tags` 还在用。所以先把它们搬去新建的 `dto/TaxonomyDtos.java`，
再删 `NoticeDtos`——不然 taxonomy 那边会连坐编译不过。

### 删之前先数一下数据

删表是不可逆的，所以先查了行数：

```
notices 4 | notice_tags 13 | user_notice_status 0 | posts 31 | comments 26
```

notices 那 4 条就是 V2 种下去的演示数据，`user_notice_status` 一行都没有——说明「完成/避坑」
这两个功能**从来没人用过**，前端展示的永远是 0。确认没有真实数据，才敢 DROP。
删之前还是 `mysqldump --single-transaction --routines` 备份了一份（87 KB，几分钟的事，别省）。

### 外键的顺序坑

第一版 V11 写的是直接 `DROP TABLE notices`，报错：

```
ERROR 1217 (23000): Cannot drop table 'notices' referenced by a foreign key constraint
```

`comments.notice_id` 上有 `fk_comments_notice` 指向 notices。必须先摘外键、再删列、最后删表：

```sql
ALTER TABLE comments DROP FOREIGN KEY fk_comments_notice;
ALTER TABLE comments DROP INDEX idx_comments_notice;
ALTER TABLE comments DROP COLUMN notice_id;
DROP TABLE IF EXISTS notice_tags;   -- 这些也都有指向 notices 的外键
DROP TABLE IF EXISTS user_notice_status;
DROP TABLE IF EXISTS favorites;
DROP TABLE IF EXISTS reminders;
DROP TABLE IF EXISTS notices;       -- 最后才轮到它
```

顺带记一个：`DROP INDEX` 也要单独写。`idx_comments_notice` 是 `(notice_id, status, created_at)` 的
联合索引，删了列索引还留着会变成无效引用。

### 那三个恒为 0 的字段为什么没删

`UserDtos.UserStats` 里有 `completed` / `upcoming` / `avoidedRisks`，notices 没了它们就是摆设。
本来打算直接删字段，但停了一下——`AGENTS.md` 里自己立的规矩是 `/api/v1` **只做加法**，
删字段要升 v2。为了三个没人看的数字升一个大版本，不值。

所以留字段、置 0、在 Javadoc 里写清「因 notices 删除恒为 0，v2 时去掉」。
这样 Web/App 端的键名不用动，等真升 v2 的时候一起收拾。

**`favorites` 的处理不一样**：它原来是「通知收藏 + 帖子收藏」相加，现在只剩帖子收藏。
这个改法是**变准了**，不是变假了——`feed.ts` 里点赞/收藏时前端本来就在对这个数做 ±1，
以前那个 ±1 加在一个混合值上，其实一直差着通知收藏的基数。

### 验证

启动日志：`Migrating schema 'biecuoguo' to version "11 - drop notices domain"` ->
`Successfully applied 1 migration, now at v11`。清理后 23 张表，`comments` 只剩 11 列。

`mvn compile` 绿。smoke 33 项全过——注意这 33 项**一项都没覆盖 notices**（因为前端从来没用过），
所以「smoke 全绿」不能证明删对了，只能证明没删坏 posts 那边。真正的证据是编译通过 + 表结构对。

### 教训

1. **删旧东西之前，先把「谁在用」画出来**，别凭印象。这次有两个坑（comments 共用、
   NoticeDtos 里混着 taxonomy），全靠 grep 引用关系才提前发现。
2. **删表之前先数行数**。如果是「演示数据 4 条」和「真实用户数据 4 万条」，同样的代码，
   风险差五个数量级。
3. **`SecurityConfig` 白名单要跟着删**。白名单留着死路径不会报错，但会让人以为那个接口还活着。
4. smoke 覆盖不到的地方，「全绿」是假的安全感。得靠编译 + 看表结构。

---

## 2026-10-20 验证码：开关该管「怎么校验」，不该管「要不要校验」

上一轮为了自己建测试号方便，我把注册改成不用验证码了 —— 账号 + 密码两栏，一按就进。
当时觉得挺爽，测了几天发现不对劲：本地和线上跑的根本不是同一条调用链。

具体会漏什么：注册接口里原来有一步 `verifyCode(...)`，我给它包了个 `if (verificationRequired())`。
本地这个分支永远不进去，也就是说**本地从来没跑过「验码失败会怎么样」这条分支**。
等上线打开真实校验，报错文案、错误码、前端怎么显示这个错——全都没测过。
这种漏不叫「没测到细节」，叫「本地环境比线上少了一整个分支」。

### 改法：开关只管「怎么校验」

字段永远必填，`verifyCode()` 永远被调用，两种模式的差别全部收进 `VerificationService` 里：

```java
public void verifyCode(String email, String purpose, String code) {
    String normalizedCode = normalizeCode(code);   // 空值在这里就被拒，两种模式都一样
    if (mockMode()) {
        if (!normalizedCode.matches("\\d{4}")) { throw new IllegalArgumentException(...); }
        return;
    }
    // 真实模式：查库 -> 判过期 -> 比对 hash -> 标记 consumed
}
```

调用方（`AuthService.register` / `resetPassword`）现在只有一行，没有 if：

```java
verificationService.verifyCode(email, "register", request.verificationCode());
```

开发模式下 `requestCode()` 也直接 return，不写库不发信 —— 之前是「照常写一条随机码进库，
然后校验时无视它」，留下堆没用的行，还容易让人以为那行能用来登录。

### 接真 SMTP 时踩到的两个点

**第一个：`@ConditionalOnProperty` 对空字符串的行为。**
`spring.mail.host: ${SMTP_HOST:}` 这种写法，在 `SMTP_HOST` 没设的时候解析出来是空串。
我原以为 `MailSenderAutoConfiguration` 会因为 host 是空而不创建 bean，结果它照样创建了 ——
`@ConditionalOnProperty` 只判断「键存在不存在」，空串也算存在。
所以不能靠 `ObjectProvider.getIfAvailable()` 判断「有没有配 SMTP」，必须连 host 一起判：

```java
public boolean available() {
    return host != null && !host.isBlank() && mailSenders.getIfAvailable() != null;
}
```

要是只判 bean 在不在，就会得到一个「available() 返回 true、真发信时炸连接」的假阳性。

**第二个：反向的静默故障也要拦。**
之前 `StartupSafetyGuard` 拦的是「开发开关漏到生产」。这次多了一种方向相反的坑：
生产配成真实模式、但忘了配 SMTP。这时候进程起得来、接口返回 200、监控一片绿，
只有新用户发现验证码永远收不到——注册链路断了，没人知道。

所以 guard 里加了第二条：真实模式 + 没有 SMTP = 直接拒绝启动。
注意这条**不区分 profile**，因为它不是「开发开关」，是纯粹的配置缺失。

### 为什么把验证码规则写死成 4 位数字

开发模式也要求是 4 位数字（`\d{4}`），不放过空串、不放过 'abc'。
理由很实在：如果本地允许乱填，前端 `maxlength="4"`、`inputmode="numeric"` 这些东西就没人验证了。
限制成和线上一样，UI 上的坑才会在本地暴露出来。

### 顺带

smoke 加了第 34 项 `register-nocode`：不传验证码必须 400。
这一条是这次改动的**核心断言**——它钉住的不是「验证码能过」，而是「不能不填」。
（另外三项老的注册检查也都补了 `verificationCode = '1234'`，否则它们的 400 会来自
「缺验证码」而不是它们本来要测的东西，变成假通过。）

### 加个 mail 依赖，顺手把 /actuator/health 弄挂了

编译通过、启动也正常，`/actuator/health` 却一直 503。日志里翻到：

```
WARN o.s.b.actuate.mail.MailHealthIndicator : Mail health check failed
jakarta.mail.AuthenticationFailedException: failed to connect, no password specified?
```

`spring-boot-starter-mail` 一进来，Spring 就自动注册了 `MailHealthIndicator`，
每打一次 health 它就去连一次 SMTP。而我 `application.yml` 里写的是
`host: ${SMTP_HOST:}` —— 没配的时候这个键**仍然存在**，值是空串，
所以 `@ConditionalOnProperty` 照样成立，`JavaMailSender` 被创建，健康检查也被注册了。

杀伤力不在本地：容器 healthcheck 打的就是 `/actuator/health`，它一 DOWN 部署就判活失败，
可应用本身明明在跑 —— 排查方向会被带偏好一阵。

改法：`management.health.mail.enabled: false`。
「有没有配 SMTP」该由 `StartupSafetyGuard` 在启动时判一次，
不需要一个每次请求都去连 SMTP 的探针。

### smoke 全红，其实是 PowerShell 按 GBK 解了 curl 的输出

34 项里 15 项 FAIL。诡异的是 `register` 明明是 200，紧接着 `me` 就是 401 —— 说明 token 没拿到。
手动 curl 注册接口，body 完全正常，token 就在里面；用 Node 直接打也是 200 + token。
所以问题在 PowerShell 这一侧。

写了个最小复现，把 `$r.body` 的长度和内容打出来：

```
bodylen=994
PARSE-FAIL: 无效的 JSON 格式... (462): {"code":"OK",...,"major":"未设置专
```

打出来是 `未设置专` 后面跟一个被吃掉的字符。"专业"的"业"是 E4 B8 9A，
按 GBK 解码时 E4B8 算一个字符、剩下的 9A 是非法单字节被吃成 `?` —— 
而且它**连后面那个引号一起吞了**，JSON 字符串没闭合，整段直接非法。

根因：PowerShell 5.1 捕获原生程序（curl.exe）的输出时用的是 `[Console]::OutputEncoding`，
中文 Windows 下默认是 OEM 代码页 936。UTF-8 的响应体被当 GBK 解，不只是显示乱码，会破坏结构。

为什么之前一直没炸：之前那次是在 UTF-8 代码页的终端里跑的。这个 bug 一直在，
只要响应体里出现中文（`user.major = 未设置专业` 就够）就会中招。

修法：脚本开头把 `$OutputEncoding` 和 `[Console]::OutputEncoding` 都设成 UTF-8，
不再依赖当前控制台的代码页。

## 2026-10-20 数据库地址这种东西，就不该有默认值

R-4 在清单上躺了很久。一直没动的理由说出来挺没说服力的：本地有 `.env` 覆盖，
跑起来从来没出过事，看日志也看不到任何异常。

真正把这个当成事，是因为想清楚了一个问题：**出问题的场景不是本地，是任何一次没带 .env 的启动。**
`application.yml` 和 `application-local.yml` 原本都写着
`jdbc:mysql://115.190.3.204:12306/biecuoguo`，那台机器已经停服了。现在连不上，所以“看起来没事”。

但“连不上”不是设计出来的安全，是运气。如果那台机器的 12306 哪天被放开——
云安全组改错一次、机器转手给别人、或者干脆只是网络通了——
照默认值启动的后端会做两件事：**对着一台陌生库跑 Flyway**，然后跑 `AuthService` 的启动初始化。
前者会改别人的 schema，后者会往里写账号。这已经不是“连错库”，是“动了不该动的数据”。

### 中间想错的两个方向

第一个想法是把默认值改成一个**能连通的**地址，让它“开箱即用”。
这等于换个地方埋雷——默认值越“好用”，越没人会去显式配置它。

第二个想法是在启动时加连通性检查，连不上就拒绝启动。
方向对，但位置错了：`StartupSafetyGuard` 是给“危险开关”用的，
而“数据库地址”这件事根本不该有默认值来让人猜。**检查默认值，不如取消默认值。**

### 最后定的三档

```text
application.yml        -> 127.0.0.1:3306     # 本机标准端口，连不上就是本机没有
application-local.yml  -> 127.0.0.1:12306    # 对上 docker compose --profile local-db 的宿主机映射
docker-compose.yml     -> mysql:3306         # compose 网络里的服务名
deploy-app.sh / deploy-ip.sh -> 没有默认值，SPRING_DATASOURCE_URL 缺失直接退出
```

前三个是“本机”或“同一个网络里的自己人”，错也错在自己家里；
最后两个是部署脚本，它们连“自己家”在哪都不知道，**猜就是错的**。

顺手把两个脚本里 `Cannot connect to MySQL at ...` 的提示也改了。
原来那段写的是「Check these on server A」，还教人用 `./deploy-mysql.sh` 起 MySQL——
双机架构早就废了，照着提示排查会一路撞墙。现在换成了不依赖拓扑的五步检查。

### 最该改的其实是 README

README 里原本有一句「数据库连接默认就是服务器 A」，底下直接跟一段可以复制的 env 块，
里面是那个已经停用的 IP。**文档教人复制一个死地址**，比代码里的默认值更危险——
因为复制的人不会怀疑它。现在改成“必须显式配置”+ 本地示例。

改完 `mvn package` 和 `scripts/smoke.ps1` 都还是 34/34，本地链路一点没动。

## 2026-10-20 匿名访客看的东西，居然是「随便挑一个人」决定的

D-3 这条在清单上挂了很久，一直是「知道有问题但没空管」。真正动手是因为想加一个新用户注册，
翻到 `AuthService` 里 `user.setSchoolId(1L)` 写了四遍，然后想起访客那边也有个 `1L`——
再往上翻，发现访客那条路径根本不是 `1L`：

```java
private User defaultViewer() {
    return userMapper.selectList(new LambdaQueryWrapper<User>()
            .eq(User::getStatus, "active").last("limit 1"))
            .stream().findFirst().orElse(null);
}
```

`limit 1` 没有 `order by`。也就是说**未登录用户看哪批帖子，取决于 MySQL 先返回哪一行**。

### 为什么一直没被发现

因为库里所有账号的 `school_id` 都是 1。随便挑谁，挑出来的学校都是 1，
所以「随机」在数据上表现为「恒定」——看起来完全正常。

这个 bug 的触发条件很苛刻：得有个用户的 `school_id` 不是 1 且 id 排在最前面。
一旦真的出现（比如给某个学院开了独立账号），表现会是「未登录首页突然少了一堆帖子」，
而且**重启后可能又好了**——这种 bug 最难查，因为没有稳定复现路径。

### 改法：把「没有学校」这件事变成一个有确定答案的状态

我一开始想让访客看到**全部**帖子。想了想不对：这是校园 PO，混着不同校区的帖子比缺帖子更糟。
访客应该有确定的一个学校可看，那就把「默认学校」显式化：

```java
Long schoolId = viewer == null || viewer.getSchoolId() == null
        ? defaultSchoolId() : viewer.getSchoolId();
return wrapper.eq(Post::getSchoolId, schoolId);
```

`defaultViewer()` 直接删掉，三个调用点全部改成 `currentUser == null ? null : ...`。

顺带把 `AuthService` 里四处 `setSchoolId(1L)` 也收进同一个配置 `app.default-school-id`。
它们本来就是同一个概念（「这个人属于哪个学校」的默认值），分在五个地方写死纯属没想过。

### 怎么证明这个配置真的被读到了

改完这种事最容易假验证：启动成功、接口 200、数据看着对——但**可能根本没读那个配置**。
所以我没用默认值测，而是把它设成 `999`（库里不存在的学校），预期匿名 feed 变成 0 条。

第一次试就翻了两个坑：

**坑一：环境变量压不过 profile yml 里的字面值。**
我用 `APP_DEFAULT_SCHOOL_ID=999 BOOTSTRAP_SEED_DEMO_DATA=false` 起，结果启动直接失败：

```
java.sql.SQLIntegrityConstraintViolationException: Cannot add or update a child row:
a foreign key constraint fails (`biecuoguo`.`users`, CONSTRAINT `fk_users_school`
FOREIGN KEY (`school_id`) REFERENCES `schools` (`id`))
```

这说明 999 确实被用到了（不然不会撞外键），但也说明 `BOOTSTRAP_SEED_DEMO_DATA=false` **没生效**——
演示账号还是被建了。原因是我把它塞在 `application.yml` 的 `${...}` 占位符里，
而 `application-local.yml` 有一行字面的 `seed-demo-data: true`，那份文件优先级更高，
占位符根本轮不到。要压过它只能用命令行参数 `--app.bootstrap.seed-demo-data=false`。

**坑二：`school_id` 是有外键的。**
这条其实是个有用的副产品——它证明这个配置不能乱填。设成不存在的学校，
启动时建号就撞 `fk_users_school`。这算 fail fast，比悄悄写进去一个孤儿 school_id 好。
现在 AGENTS §4 和 §8 都写了这一条。

用命令行参数重测：`--app.default-school-id=999 --app.bootstrap.seed-demo-data=false`，
结果 feed 0 条、四个板块计数全 0、游标分页 0 条 `hasMore=false`，三条读路径全部跟着配置走。

### 一个顺带的教训

要验证「配置有没有生效」，别用默认值测。把配置改成一个**必然产生可见差异**的值，
再断言那个差异。这次如果我用默认的 1 去测，改前改后一模一样，什么问题都发现不了。

## 2026-10-20 JWT 的签名密钥，一直明明白白写在仓库里

这条在清单上被归成 Q（质量项），排在最后。翻配置的时候才反应过来它其实是 R：

```yaml
# application.yml（改之前）
jwt:
  secret: ${JWT_SECRET:change-this-secret-to-a-very-long-random-string-32-plus}
```

`.env.example` 里是 `change_me_to_a_long_random_secret_at_least_32_chars`，
`docker-compose.yml` 里是 `please-change-this-to-a-very-long-random-secret-32`。
三个地方三个值，但**全都是公开的**，而且 `docker compose up` 不配 `JWT_SECRET` 就会用它们。

### 为什么一直没当成事

因为它长得像占位符。带 `change` / `please-change` 字样，看上去已经提醒过人了——
仿佛「写了提醒」本身就等于「别人会去改」。

但占位符写在配置文件里就是**生效值**，不是注释。真正的后果是：
知道这个字符串的人可以自己签一个 `{"sub":"5","role":"ADMIN"}`，
然后 `Authorization: Bearer <伪造>` 直接进后台。
不需要注册，不需要验证码，**服务器也不会有任何一条异常日志**——
它认为这就是一个合法 token。

### 顺手发现的第二个问题

`security/JwtService.java` 里有这么一段：

```java
if (secret.length() < 32) {
    secret = secret + "0".repeat(32 - secret.length());
}
```

为了防止 JJWT 抛 `WeakKeyException`，它把短密钥补 `0` 凑到 32 字节。
一个 3 位密钥被补成 32 字节，看起来合规了，实际强度还是 3 位。
这种「补齐」比报错危险得多——报错会逼你改配置，补齐只会让你以为没事。

### 一个想过的方案：启动时自动生成密钥

第一反应是「干脆别要配置了，启动时随机生成一个」。听起来最省事。

问题是重启就换密钥，所有人当场被登出；多实例部署时每个实例的密钥还不一样，
A 实例签的 token 到 B 实例直接验不过。随机生成的密钥只适合「进程内临时用」，
不适合一个要跨重启、以后可能跨实例的会话系统。

所以结论反过来：**宁可启动失败，也不要悄悄用一个能用的密钥。**

### 改了什么

```text
application.yml        secret: ${JWT_SECRET:}                       # 空默认值
application-local.yml  secret: ...local-dev-only-jwt-secret...      # 只有本地有值
docker-compose.yml     JWT_SECRET: ${JWT_SECRET:?...必须显式配置}   # 缺失时 compose 直接报错
.env.example           JWT_SECRET=                                   # 空，附 openssl 生成命令
```

`StartupSafetyGuard` 加了第 0 条检查，**不区分 profile**：空值、三个历史示例值、
短于 32 字符、非开发环境用了本地那个专用值——四种情况一律拒绝启动。
异常信息里刻意不打印密钥本身，因为这段文字会进日志。

`JwtService` 的补 `0` 改成抛异常：密钥不合格应该在启动时炸，不该在签名时悄悄修补。

### 怎么验的

四条拒绝路径一条条跑，看退出码和文案对不对（空值 / 示例值 / `abc123` / 非开发 profile + 本地值），
都是 `exit=1`。然后正常路径：`local` 启动 → 登录拿 token → `/auth/me` 返回 `ADMIN`
（token 真的被签发并校验了，不是绕过去的）→ `smoke.ps1` 34/34。

`docker compose config` 那一步没验成——这个 shell 里 compose 插件没挂上，
`docker compose` 直接报 unknown command。`:?` 的语义是 Compose 规范里写死的，
但「确实会拦下」这件事得等有 compose 的环境再确认一次。

## 2026-10-20 上传接口说「只支持图片」，其实只信了客户端那句话

`MediaService` 原来的校验是这样的：

```java
String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
    throw new IllegalArgumentException("目前只支持图片上传");
}
```

`MultipartFile.getContentType()` 返回的是**请求头里的 Content-Type**，一个客户端随便写的值。
所以这段代码的实际含义不是「只接受图片」，而是「只接受请求头里写了 image/ 的东西」。

具体能干嘛：把一个 `.html` 或者带 `<script>` 的 `.svg` 用 `curl -F 'file=@x.html;type=image/png'` 传上来，
校验通过，文件原样进 MinIO，`media.content_type` 也存成 `image/png`，
然后 `/api/media/{id}/content` 用这个 Content-Type 把它吐回浏览器。
浏览器遇到 `image/png` 不会执行里面的脚本，所以这一步还不至于立刻 XSS；
但只要哪天为了「支持 SVG 图标」把类型放宽到 `image/svg+xml`，同源打开就是执行任意 JS。
更别提还顺便变成了一个任意文件的免费图床。

### 改法：认文件头，然后所有东西都以识别结果为准

加了 `service/ImageSignature.java`，读头 12 个字节，只认四种真实图片：

```text
JPEG  FF D8 FF
PNG   89 50 4E 47 0D 0A 1A 0A
GIF   "GIF8"
WebP  "RIFF" + 4 字节长度 + "WEBP"
```

认出来之后，**扩展名和 Content-Type 都用识别结果**，不再用 `file.getOriginalFilename()` 和请求头。
这样「文件名是 a.png、内容是 HTML」这种情况根本进不了下一步，因为它压根认不出来。

### 顺手砍掉的两个东西

**上限 20MB → 5MB。** 手机上随手拍的照片三四 MB，再大就该客户端压缩了；
20MB 更像是「懒得想」填的数字。`MEDIA_TOO_LARGE` 的文案也跟着改了。

**`purpose` 是拼进对象 key 的。** 之前直接 `firstNonBlank(purpose, "post")`，
客户端传什么就拼什么。对象存储没有路径穿越问题（key 是扁平字符串），
但传个超长或者带斜杠的值能把目录结构搞得很脏。现在只允许 `[A-Za-z0-9_-]{1,20}`，不合格一律当 `post`。

### 验证

四条一起测，因为「真图片能传」和「假图片被拒」必须同时成立，只测拒绝那一侧可能是校验写死了一律拒绝：

```text
真 PNG                    → 200  url 结尾 .png，contentType image/png
HTML 冒充 image/png        → 400  只支持 JPEG / PNG / GIF / WebP 图片（按文件内容识别，改扩展名没用）
SVG 冒充 image/png         → 400  同上
6MB PNG（头是真的）        → 413  图片太大了，请换一张 5MB 以内的图片
```

最后一条是 Spring 的 multipart 上限先拦的（`max-file-size: 5MB`），
不是 `MediaService` 里那个字节数检查——两个都在，前者是实际生效的那道，后者是纵深防御。

### 还没做的

没有校验像素尺寸，所以「5MB 的解压炸弹」理论上还是能传上来。
服务器自己不解码，炸弹炸的是浏览器，所以优先级不高；真要防得按格式读 IHDR/帧头，
而 WebP 没有内置的 ImageIO reader，得手写解析——现在不值得。

## 2026-10-20 加了限流，然后发现「按 IP 限流」在代理后面等于给全站上锁

要挡的东西很明确：验证码接口每发一封信都是真金白银，而且能拿去轰炸别人的邮箱；
登录接口没有上限就是无限撞库。于是写了个固定窗口限流，按「来源 IP + 接口」计数。

第一版写完准备收工，突然想到一个问题：**部署的时候后端前面站着 Caddy。**

Spring 的 `request.getRemoteAddr()` 拿的是 TCP 对端的地址。整栈部署时，
所有请求的 TCP 对端都是 Caddy 容器——不管是一百个人还是一万个人，
`getRemoteAddr()` 全都返回同一个 IP。也就是说：

```text
登录限流 20 次 / 5 分钟 / IP
→ 实际上是「全站所有用户加起来 5 分钟只能登录 20 次」

→ 一个人拿脚本刷 20 次错误密码，全站用户 5 分钟内登不进来
```

这不是限流，这是给自己装了个开关。而且它**只在部署后出现**：本地开发连的是
`localhost:8080`，remoteAddr 就是 127.0.0.1，一切正常，测试全绿。

### 所以要么读 X-Forwarded-For，要么别用 IP 限流

Caddy 会写 `X-Forwarded-For`。但它是个普通请求头，客户端可以自己伪造——
如果无脑信任，攻击者每次换一个假 IP，限流就完全失效。

结论是必须知道「我前面到底有没有代理」。这不是能从代码里推断出来的信息，
只能配置：

```yaml
app:
  rate-limit:
    trust-forwarded-for: ${RATE_LIMIT_TRUST_FORWARDED_FOR:false}
```

- 默认 `false`：用 `remoteAddr`。适合「直接暴露后端」或本地开发。
- `true`：取 `X-Forwarded-For` 第一段。**只有在前面确实有代理、且外部连不到后端时才成立**。

`docker-compose.yml` 里默认 `true`，因为那个栈一定带 Caddy 且后端没有对外端口。
这个默认值写在 compose 而不是代码里，是因为「有没有代理」只由部署形态决定。

### 为什么不用令牌桶

想过 Bucket4j（现成的，还有分布式实现），但当前需求是「别让人拿脚本刷」，
不是「精确整形流量」。固定窗口十几行、无后台线程、无额外依赖；
代价是窗口边界可能放过接近两倍的请求——比如 5 分钟窗口，
在 4:59 打满 20 次、5:01 再打满 20 次，两分钟内实际过了 40 次。可以接受。

真正该上 Bucket4j/Redis 的时刻是「多实例部署」，那时进程内的计数器本来就不成立（Q-4）。

### 数值怎么定的

第一版定得偏紧（验证码 5 次/10 分钟），然后意识到会打到自己的脚：
`smoke.ps1` 一次要发好几个验证码，连着跑三遍就把窗口用完了，
**而限流窗口是 10 分钟——等于本地开发被自己锁十分钟**。

最后定成「够挡脚本、够本地折腾」的量级：登录 30/5min、验证码 20/10min、
注册 20/1h、改密 20/10min、上传 30/5min、发帖 20/5min。

### 验证

smoke 34/34 全绿（说明没误伤正常链路），然后对着验证码接口连发 30 次：

```text
第 20 次开始 429，Retry-After: 595
{"code":"RATE_LIMITED","message":"操作太频繁了，请 595 秒后再试","data":null,"requestId":"..."}
别的接口仍然 200，登录仍然 200
```

那两个「仍然 200」是重点：限流必须只打中目标接口，不能因为规则写错把整站罩住。

### 没进 smoke

想过把「打 20 次看会不会 429」加进 `smoke.ps1`，但那样每跑一次就把窗口占满，
十分钟内没法再跑——用一个检查项换来十分钟不可用，不划算。
这条目前只能手动验，记在 ISSUES 里。

## 2026-10-20 验证 CI 的脚本，先在 Git bash 里被自己写的检查坑了一次

加 `.github/workflows/ci.yml`，其中有个 `files` job 干三件小事：
`.sh` 必须是 LF、`.ps1` 必须是 CRLF（PowerShell 5.1 解析 LF-only 会静默出错）、`.env` 不能入库。

workflow 写完没法在本机跑 GitHub Actions，但思路可以降级验证：本机有 Git for Windows 的 `bash.exe`，
把那两段脚本抠出来直接在仓库里跑一遍，总比 push 上去等结果强。

结果 `files` job 报：

```text
FAIL missing CRLF in scripts/smoke.ps1
```

可我明明记得那个文件是 CRLF 的（之前专门转过）。用 node 读字节确认：223 个 CRLF、223 个 LF、纯 LF 为 0——**文件是对的**。

### 问题在 grep，不在文件

MSYS2 版的 `grep` 默认按文本模式打开文件，会把 `\r\n` 读成 `\n`。
所以 `grep -q 

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r' file` 永远匹配不到东西——**在 Git bash 里，任何文件看起来都是 LF**。

实测：

```text
grep -q  

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r' scripts/smoke.ps1  → 不匹配
grep -qU 

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r' scripts/smoke.ps1  → 匹配（-U/--binary：别做换行转换）
```

Ubuntu 上的 GNU grep 没有这个文本模式转换，同一条命令在 CI 里是准的——
但「本地准不了」意味着我以后在 Windows 上根本没法预检这条规则。加 `-U` 两边就一致了。

### 顺带发现的第二件事：Linux runner 上 checkout 出来的 .ps1 是 LF

`.gitattributes` 原来只有一句 `*.sh text eol=lf`。`.ps1` 没规则，`core.autocrlf=true` 的机器上
工作区是 CRLF、索引里是 LF；**而 Linux runner 默认 `autocrlf=false`，checkout 出来就是索引里的 LF**。
也就是说：那条 CRLF 检查如果在 CI 里跑，即使逻辑写对了也会红——因为 CI 上的文件真的是 LF。

补了一句，让 checkout 出来的一定是 CRLF：

```gitattributes
*.ps1 text eol=crlf
```

### 另一处「我以为的接口」

冒烟断言里原本写 `GET /api/v1/posts/999999` 期望 404 + `POST_NOT_FOUND`，
实际返回 `405` + `VALIDATION_FAILED / 不支持的请求方法`——因为 `PostController` 上根本没有 `GET /{id}`，
只有 `POST`，所以这个路径在 Spring 眼里是「方法不允许」，不是「资源不存在」。

换成 `GET /api/v1/posts/999999/comments`（这个 GET 是存在的），返回才真是 `404` + `NOT_FOUND`。
顺带发现业务码不是 `POST_NOT_FOUND` 而是更笼统的 `NOT_FOUND`（文案「PO 不存在」）。

**教训**：写断言的时候不能凭印象写路径，得先 `curl` 一遍看真实返回，再把输出钉进断言。

### 现在的状态

`files` job 三条全过，`smoke` job 六条断言在本地 Git bash 下全过（`CI-SMOKE-PASS`）。
但 workflow 本身还没在 GitHub 上真正跑过一次——runner 环境、MySQL service 容器、
`setup-java` 的缓存放行都得等第一次 push 才知道。

## 2026-10-20 CI 第一次跑就是 0 个 job —— 文件被我自己的 replace 写坏了

`ci.yml` 提交上去，几分钟后去看：run 有了，名字也对，但是

```text
conclusion: failure
created_at: 02:45:26
updated_at: 02:45:26   ← 同一秒
jobs: total_count = 0
```

**0 个 job、同一秒失败**，说明 GitHub 根本没开始跑，是在解析 workflow 的时候就挂了。
但 `GET /actions/workflows` 里它又是 `active` 的，PyYAML 也能正常 parse。

第一反应是「是不是 runner 权限/配额/Actions 被关了」——全都不像，那类问题不会让 run 出现。
第二反应是「是不是 service 容器或者 setup-java 写错了」——也不像，那种会到 job 阶段才红。

### 真正的办法：在本地把文件喂给 actionlint

GitHub 的报错要在网页 logs 里看，而这个 run 连 job 都没有，压根没 logs。
于是下了 `actionlint`（Go 写的单文件二进制，走代理拉 zip），一跑：

```text
.github/workflows/ci.yml:155:0: could not parse as YAML: yaml: line 155: could not find expected ':'
    |
155 | \r' "$f"; then
```

第 155 行是一段**碎片**：`\r' "$f"; then`。前后还丢了别的东西。

### 原因：JS 的 String.replace 把 

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。 当成了替换模式

改 workflow 时我用了个偷懒的补丁脚本：

```js
y = y.replace('if grep -q $\'\\r\' "$f"; then', 'if grep -qU $\'\\r\' "$f"; then')
```

问题是 **`String.prototype.replace` 的替换串里 `# 开发手记

自己的开发记录。记的是「当时怎么想、为什么这么改、哪里绕了弯路」，不是 API 文档也不是提交日志。

规矩很简单：踩到那种「当时想不通、想通了觉得值得记一笔」的坑，就当天记下来，不用等收尾。
写作格式见文末。

---

## 2026-10-20 游标分页：先写错一遍才想明白

做 feed 分页的时候，我一开始用的是最简单的写法：游标只装上一页最后一条的 id，SQL 加个 `id < cursor`。
写完自测，拉了 3 页，每页 5 条 —— 三页全是同一批：`2,3,1,4,5`。

第一反应是 SQL 写错了。于是打开 MyBatis 的 DEBUG 日志看真实执行的语句，发现生成的 SQL 就是我想写的：

```sql
WHERE status = ? AND school_id = ? AND id < 15 ORDER BY pinned DESC, official DESC, ... LIMIT 6
```

返回 `2,3,1,4,5,14`。看着很离谱，但**它完全正确**。

因为 ORDER BY 第一位是 `pinned`，第二位 `official`，一路排到 `like_count` —— 这是按热度排的。
id 小的帖子完全可能因为点赞多而排在 id 大的前面。所以 `id < 15` 这个条件跟排序键**没有任何关系**，
它只是把 id 大于 15 的过滤掉了，剩下的顺序一点没变，自然每页都吐同一批。

结论：游标分页的前提是「游标必须编码完整的排序键」，然后用 WHERE 把 ORDER BY 的字典序复现出来。
排序键有 7 个字段，游标就得装 7 个字段，少一个都会重复或漏条。

改成 keyset pagination：游标用 `|` 分隔存 `pinned|official|dontMiss|likeCount|commentCount|publishedAt|id`，
查询条件变成一串 OR：

```
pinned < k
  OR (pinned = k AND official < k)
  OR (pinned = k AND official = k AND dontMiss < k)
  OR ...
  OR (前 6 个全等 AND id < k)
```

这里还有两个 MyBatis-Plus 的小坑：`LambdaQueryWrapper` 没有 `and(Consumer)` 方法，只有 `nested`，
得用 `nested(w -> {...})` 把整个 OR 链包起来；另外静态嵌套 record 里调不到外部类的私有方法，
只能把 `getLikeCount() == null ? 0 : getLikeCount()` 这种空值兜底内联进去。

教训：**游标分页不是「记住位置」，是「记住排序键的值」**。如果排序规则复杂到不好编码，
就说明它不适合做游标分页，得先简化排序。

---

## 2026-10-20 统一响应包：为什么要加 code 和 requestId

之前所有接口返回的是 `{"data": ...}`，错误返回 `{"message": "中文提示", "timestamp": "..."}`。
看起来挺简洁，问题在客户端那侧：

App 想判断「是业务失败还是网络断了」，只能去看 `message` 的内容。于是代码里到处都是
`if (msg === "邮箱或密码不正确")` 这种东西。哪天我把提示语里的「不正确」改成「错误」，
所有依赖它的逻辑就静默失效了 —— 而且编译不报错，测试也不会挂，只有用户会发现行为变了。

所以要加一个机器可读的 `code`。设计上定了三条：

**第一，HTTP 状态码保留语义，不走「一律 200 + code」那套。**
理由是基础设施全都看 HTTP 状态码：Caddy 会按 5xx 报警，负载均衡按 4xx/5xx 决定要不要重试，
CDN 会缓存 200。如果服务端崩了还回 200，监控面板上就是一片绿，等你从用户嘴里知道出事了。
所以分工是：`code` 给 App 做业务分支，HTTP status 给代理/监控/重试策略看，各管一段。

**第二，`code` 用枚举而不是字符串常量。**
字符串散在各处就会拼错、就会漂移，`AUTH_INVALID_CREDENTIAL` 和 `AUTH_INVALID_CREDENTIALS`
差一个 S 编译器不会管。放进 `ErrorCode` 枚举之后，加一个新码必须显式声明默认提示语，
而且引用处写错名字直接编译失败。

**第三，`data` 字段一定要保留。**
这条是纯工程考虑：前端所有请求都走同一个 `unwrap()`，实现就是 `return response.data.data`。
只要新响应包里还有 `data`，前端一行都不用改，全站零回归。这是故意的兼容设计，不是巧合。

`requestId` 是顺手加的，但实际很实用。用户说「我发帖失败了」，没有 requestId 的时候只能靠时间戳猜，
或者让他复现一次。现在每个请求分配一个 16 位 id，同时写进响应头 `X-Request-Id` 和服务端日志的 MDC，
拿到那个 id 直接 grep 日志就精确命中那一次请求 —— 包括它跑了哪些 SQL、耗时多少、报的什么错。

实现上有个细节：这个过滤器必须排在 Spring Security 之前，否则安全层的日志就带不上 id。
这里没用 `@Order` 注解，而是显式 `FilterRegistrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE)` ——
因为 Spring Boot 对 `@Component` 过滤器跟 SecurityFilterChain 的注册顺序**不保证**谁先谁后。
这个坑在下面 addPathPrefix 那节还会再遇到一次。

---

## 2026-10-20 GlobalExceptionHandler 补上 500 兜底

原来那个异常处理器写了 400/401/403/404/413 的映射，但**没有 `@ExceptionHandler(Exception.class)`**。
这意味着任何没被显式接住的异常都会走 Spring 默认错误页，返回的是 Boot 自己那套
`{"timestamp","status","error","path"}`。

后果很讽刺：我们刚花力气把响应包统一了，但**最需要它的场景反而不统一** —— 服务端真的出 bug 的时候，
客户端拿到的是另一个格式，解析不出 `code`，也拿不到 `requestId`。而这恰恰是最需要定位信息的时刻。

所以补了兜底。这里有个决定我觉得比代码本身重要：

**兜底里绝对不能把 `ex.getMessage()` 回给客户端。**

原因很实在：异常消息里经常带 SQL 片段、文件绝对路径、内网主机名、甚至数据库连接串。
这些是攻击者最喜欢的信息，免费的信息泄露。所以兜底只回一句固定的「服务器开小差了」，
真正的堆栈用 `log.error` 打出去，靠 requestId 关联。想看详情？有服务器权限的人去看日志。

顺手还补了三个之前漏掉的映射：请求体不是合法 JSON（`HttpMessageNotReadableException`）、
静态资源不存在（`NoResourceFoundException`）、方法不支持（`HttpRequestMethodNotSupportedException`）。
尤其是第一个，做时间字段迁移的时候特别容易触发 —— 客户端传个裸的 `2026-10-19T15:22:01`，
而字段类型是 `OffsetDateTime`，Jackson 解析失败，如果没接住就变成 500。

### 这里有个不对称，我漏过一次

`@RestControllerAdvice` 是 **DispatcherServlet 层**的东西。但认证/鉴权失败发生在更早的
Security filter chain 里，那些请求**根本进不到 DispatcherServlet**，全局异常处理器完全管不到。

我是先只改了 `GlobalExceptionHandler`，然后测 401，发现返回的还是老格式
`{"message":"请先登录","timestamp":...}`，才反应过来。
最后在 `SecurityConfig` 的 `authenticationEntryPoint` 和 `accessDeniedHandler` 里手写了一份同格式的响应。

教训：**响应格式有两个产出点，一个在 DispatcherServlet 里，一个在 Security 过滤器链里。**
改格式必须两处一起改，否则就会出现「大部分接口对了但 401 还是老的」这种半吊子状态。

---

## 2026-10-20 时间字段：为什么 LocalDateTime 是错的

这件事我一开始觉得是「可选项」，后来越想越觉得是硬伤。

MySQL 的 `DATETIME` 不存时区。Java 的 `LocalDateTime` 也不带时区。两边都不带，看起来「很配」，
实际上是**双重丢失** —— 没人知道那个裸时间属于哪个时区。

具体会出什么事：数据库里存 `2026-10-19 15:22:01`，接口返回 `"2026-10-19T15:22:01"`。
成都的用户看到，以为是本地时间 15:22；东京的用户也以为是本地 15:22。只有东八区的人碰巧猜对了。

更要命的是算相对时间。App 想显示「3 分钟前」，得拿服务端时间减客户端时间。
但客户端不知道那个裸时间属于哪个时区，减出来的结果可能差 8 小时。这种 bug 在国内测试完全发现不了，
等有海外用户反馈才暴露，而且很难复现。

所以接口层必须带偏移：`"2026-10-19T15:22:01+08:00"`。

### 为什么数据库不改

理论上最干净的做法是数据库也存带时区的类型（`TIMESTAMP`）。但有两个现实问题：
一是 `DATETIME` 改 `TIMESTAMP` 要迁移存量数据，而且要处理迁移期间的双写；
二是 MySQL 的 `TIMESTAMP` 有 2038 年的上限。

最后决定：**数据库不动，只在接口边界转换。** 存进去还是 `LocalDateTime`，吐出来转成 `OffsetDateTime`。
新增了 `config/AppTime.java` 专门干这件事，全项目只有这两个方向：
出参 `AppTime.toOffset(...)`（写在 DTO 的 `from()` 里），入参 `AppTime.toLocal(...)`（Service 落库之前）。
不在业务逻辑中间到处转换，免得有一天忘了转。

### 两个实现上的选择

**为什么做成静态工具类而不是普通 Bean。**
因为 DTO 的 `from()` 是静态工厂方法，Spring 注入不进去。所以做成「Spring 构造时初始化一个静态字段，
之后全是静态方法」。不算优雅，但这东西全局只读、没有并发问题，是这个约束下的最省事做法。

**为什么用配置项而不是 JVM 默认时区。**
如果写 `ZoneId.systemDefault()`，那服务器时区一改（比如迁到 UTC 的云主机），所有接口返回的偏移值就悄悄变了 ——
前端存下来的历史数据全部错位，而且是那种「上线三天后才有人发现」的错位。
所以用 `app.time-zone` 显式钉死，默认 `Asia/Shanghai`，换机器不改变语义。

---

## 2026-10-20 addPathPrefix 把全站搞成 401

做 `/api/v1` 版本化的时候，我先想到的是最「正统」的写法：`WebMvcConfigurer.configurePathMatch` + `addPathPrefix`。
看起来就是「给所有接口加个前缀」，一行配置搞定。

写完编译通过，重启，然后：

```
/api/schools      -> 401
/api/categories   -> 401
/api/auth/login   -> 401     连登录都进不去
```

排查方式是把它改名成 `.bak`，重新编译重启，`/api/schools` 立刻恢复 200。确认就是它干的。

原因是 `addPathPrefix` 并不是「在字符串前面拼一段」，它**重建了整套 path matching 配置**。
而 Spring Security 的 `AntPathRequestMatcher` 用的是另一套解析逻辑，两边对不上。
结果就是 `SecurityConfig` 白名单里写的 `/api/schools` 匹配不到任何真实请求，
所有请求统统落到 `anyRequest().authenticated()`，全变 401。

最后改成 Servlet Filter 直接改写 URI：`/api/v1/xxx` 改写成 `/api/xxx` 再往下走。
这样白名单只要维护一份（都是 `/api/...`），MVC 层的路径匹配完全不动。

这里又踩了一次顺序的坑：最开始的 `@Order(1)` 不起作用，因为 Boot 不保证 `@Component` 过滤器
跟 SecurityFilterChain 谁先注册。改成 `FilterRegistrationBean` + `setOrder` 才稳。

教训：**框架里那些「看起来只是加个前缀 / 改个默认值」的 API，实际可能重建整条链路。**
动全局路径匹配这种地方，先在最小场景验证，别一次改完再一起看结果。

---

## 2026-10-20 卡我最久的居然是个换行符

这个坑值得单独写一节，因为它的症状完全不指向原因。

smoke 脚本里加了这几行验证 `/api/v1`：

```powershell
$Base = 'http://127.0.0.1:8080'
$v1 = ($Base.TrimEnd('/')) + '/api/v1'
```

跑出来所有 v1 请求都是 `http=000` —— curl 连都没连上，说明 URL 是坏的。

加 DEBUG 打印：

```
DEBUG Base=[http://127.0.0.1:8080] v1=[]
```

`Base` 明明有值，`v1` 是空串。可同样的两行单独写个 `.ps1` 跑，结果完全正确。

接下来试了一堆方向：换拼接方式、用 `Join-Path`、改成格式化字符串……全都没用。
因为方向从一开始就错了 —— 我一直在怀疑表达式。

真正的原因：**我用编辑工具改这个文件，它被写成了 LF 换行。**
PowerShell 5.1 解析 LF-only 的 `.ps1` 会出现这种诡异的静默失效：变量赋值看起来执行了，值却是空的，
而且不报任何错。整个文件转回 CRLF，立刻正常。

事后看 git diff 才发现，原本的文件就是 CRLF，是我自己给破坏的。

教训：**遇到「逻辑明明没错但结果就是空/就是不改」的症状，先怀疑文件编码和行尾，别怀疑逻辑。**
PowerShell 5.1 对行尾很敏感，而且报错信息完全不指向真正的原因。现在改完 `.ps1` 一律转回 CRLF。

---

## 2026-10-20 两个 replace 的坑，各浪费了一轮

**坑一：`tools.edit` 对 CRLF 文件静默不匹配。**
我给 `SecurityConfig` 加统一错误格式，`old_string` 里用的是 `\n`，但文件里是 `\r\n`。
编辑工具没报错，也没提示「没找到」，就是什么都没改。编译通过，重启，测 401 —— 还是旧格式。
我当时以为是代码逻辑错了，又去翻了一遍，最后才发现根本没写进去。

→ 现在改文件前一律先 `read` 拿到真实的行内容，不在脑子里拼 `old_string`。

**坑二：JS 字符串里的 `$'` 是个特殊模式。**
我在生成 PowerShell 脚本时，往 `String.replace()` 的替换串里塞了一个正则 `'T\d{2}:\d{2}:\d{2}$'`。
结果 `$'` 被 JS 当成「匹配位置之后的所有内容」这个特殊占位符，把后面的东西全吃掉了，
生成的 `.ps1` 语法错误，报的还是莫名其妙的错。

→ 替换内容里含 `$` 的时候，用函数式替换：`s.replace(old, () => newText)`，别传字符串。

---

## 2026-10-20 三个安全漏洞：默认值是最贵的决定

这次清 R-1 / R-2 / R-3，三条都是同一类问题：**代码写了「这是个开关」，但默认值是危险的那一侧。**

R-1 是验证码。`application.yml` 里写的是 `${VERIFICATION_MOCK_ACCEPT_ANY_CODE:true}` ——
冒号后面那个 `true` 就是默认值。也就是说，只要没人显式设这个环境变量（而绝大多数部署都不会设），
验证码校验就是「任意 4 位数字通过」。任何人都能用任意邮箱注册，然后走 `reset-password` 接管账号。

R-2 是三个演示账号。`AuthService.run()` 每次启动都无条件创建 `good` / `xiaoba` / `yuanhua`，
密码跟用户名一样。这不是「测试代码忘了删」——它是正经写在 `CommandLineRunner` 里的启动逻辑，
所以生产环境照样会执行。

R-3 最离谱：每次启动 `admin.setPasswordHash(encode(BOOTSTRAP_ADMIN_PASSWORD))`。
compose 里 `BOOTSTRAP_ADMIN_PASSWORD` 的默认值是 `Admin@123456`。所以哪怕管理员上线第一天就改了密码，
下次重启又变回 `Admin@123456`。**改密码这个操作在这个系统里是无效的。**

### 当时怎么想的

第一版我打算只改默认值：把 `true` 改成 `false`，本地 profile 再写回 `true`。这确实能解决 90% 的场景。

但我停下来想了一下：**「记得别在生产打开」这种约束，靠什么保证？**
答案是靠人记得。而人一定会忘 —— 尤其是半年后有个紧急问题，有人在服务器上 `export` 一堆环境变量调试，
顺手把 `VERIFICATION_MOCK_ACCEPT_ANY_CODE=true` 也带上了，然后再也没删。

打一条 WARN 也没用。启动日志几百行，没人看；就算看到了，也不会为了一个黄色警告停下来。

所以最后加的是 `config/StartupSafetyGuard.java`：非 `local/dev/test` profile 下，只要这两个开关有一个是 true，
**直接抛异常让进程起不来**。效果是：

```
java.lang.IllegalStateException: 拒绝启动：当前 profile=[] 不是开发环境，
但开启了危险开关 [app.verification.mock-accept-any-code=true]。...
```

这个决定的逻辑是：**一个静默的安全漏洞，比一个起不来的服务危险得多。**
服务起不来，五分钟内就有人来找你；漏洞静默存在，可能要等半年后某个陌生邮箱注册进来才发现。

### 为什么用 @PostConstruct 而不是 ApplicationRunner

第一版我把检查写成了 `ApplicationRunner`，跟 `AuthService` 一样。写完突然意识到不对：
`ApplicationRunner` 是容器初始化**完成之后**才跑的，那时候 `AuthService` 已经把演示账号和
重置过的管理员密码写进库了。检查跑在写库之后，等于没检查。

改成 `@PostConstruct`，在 bean 初始化阶段就拦住，早于任何 `ApplicationRunner`。
这种「执行顺序」的坑很容易漏，因为两个写法看起来都是「启动时跑一下」。

### R-3 真正的修法不是「加个判断」

我一开始想的是「那就在 setPasswordHash 前加个 `if (admin.getPasswordHash() == null)`」。
但读了一遍那段代码发现，它不只是改密码 —— 它把 nickname、avatar、level、xp、status 全部重写了一遍，
然后无条件 `updateById`。也就是说：**管理员在后台改的昵称和头像，也会在下次重启时被悄悄改回去。**

所以正确的修法是把整个块拆成两个分支：

- `admin == null`：首次创建，bootstrap 配置全部生效。
- `admin != null`：只补真正缺的字段（`publicUid` / `role` / `status`），用一个 `dirty` 标志决定要不要写库；
  密码、昵称、头像一律不碰。

验证方式我想了个比较硬的：BCrypt 每次 `encode()` 都会生成新的随机盐，所以只要重新编码过，
哈希串一定变。于是重启前后各查一次 `password_hash`：

```
BEFORE: $2a$10$H8E2fa0U3Wp1syVQRPIyw.3NFbR47lkpvZpiROOFr.ob8yKrerLv2  2026-10-20 09:08:12
AFTER:  $2a$10$H8E2fa0U3Wp1syVQRPIyw.3NFbR47lkpvZpiROOFr.ob8yKrerLv2  2026-10-20 09:08:12
```

完全一致 → 证明真的没重新写库。这比「登录还能成功」强，因为密码没变的情况下登录本来就会成功，
那种验证根本区分不出「没改」和「改成了同一个值」。

### 一个没绕过去的坑

验证演示账号登录的时候，我用 curl 发 JSON 一直返回 400 `VALIDATION_FAILED`。
查了半天以为是 DTO 字段名错了（`account` 还是 `email`），改成 `email` 还是 400。

真正原因是 **PowerShell 5.1 传给原生程序的参数会剥掉内层双引号**。
`-d '{"email":"..."}'` 传到 curl 手上变成了 `{email:...,password:...}`，不是合法 JSON，
于是 `HttpMessageNotReadableException` → 400。

这个坑以前在 smoke 脚本里踩过（当时的解法是 `--data-binary "@$file"`），这次又踩了一遍。
最后把 JSON 写进临时文件，用 `--data-binary $arg` 才通过。

→ **以后在 PowerShell 里给 curl 传 JSON，一律走文件，不要内联。**

### 还剩什么

R-1 其实只修了一半。默认值翻了、启动拦截加了，但**真实发邮件的通道还没接**。
现在 `mock=false` 的话，验证码只会被 `System.out.printf` 打到日志里，用户根本收不到。
所以严格说，R-1 现在是「安全但不能用」—— 要真正上线必须接 SMTP / Resend / SES。
这一点我写进 `docs/ISSUES.md` 了，免得下次以为已经完事。

---

## 2026-10-20 注册改成「随便填」——顺带发现密码长度是个假上限

之前注册要邮箱格式、密码至少 6 位、还要填昵称和 4 位验证码。自己测的时候要开一堆邮箱，
烦到不行。于是决定：开发阶段注册只要两个东西 —— **账号 + 密码**，其它全可选。

具体放宽了什么：

- `@Email` 去掉了。账号可以是任意字符串，`test1`、`测试号`都行。反正内部统一 `toLowerCase()` 之后判重。
- `nickname` 可选，不填就直接用账号本身当显示名 —— 反正账号是唯一的，昵称自然也唯一。
- `confirmPassword` 可选。界面上还是让填（防手滑），但接口层面允许省略，给 App 端留余地。
- `verificationCode` 在 mock 模式下**完全不需要**。判断条件是 `mockAcceptAnyCode == false` 时才校验，
  也就是只有真实环境才要求。
- 密码下限在 mock 模式下放到 1 位。`password: "1"` 能注册。

### 密码「不限长度」其实是个陷阱

我一开始想直接删掉 `@Size` 约束，让它真的不限长。写到 `passwordEncoder.encode()` 的时候停了一下，
因为想起来 BCrypt 有个 72 字节的输入上限。

去确认了一下：BCrypt 的算法只吃前 72 字节，超出的部分**静默丢弃**。不是报错，是丢掉。
后果是两个前 72 字节相同、后面不同的密码，会算出**同一个哈希**，都能登录同一个账号。
这个 bug 你测不出来 —— 除非专门拿两个超长密码去试。

所以最后没删上限，而是加了个显式检查：

```java
int bytes = password.getBytes(StandardCharsets.UTF_8).length;
if (bytes > 72) {
    throw new IllegalArgumentException(
        "密码太长：最多 72 字节（约 72 个英文字符或 24 个汉字），当前 " + bytes + " 字节");
}
```

**注意这里算的是字节不是字符。** 一个汉字在 UTF-8 里是 3 字节，所以 24 个汉字就到顶了。
如果按 `String.length()` 判断（那是 UTF-16 code unit 数），中文用户能填 72 个汉字 = 216 字节，
照样被截断，等于白检查。

宁可给一条明确的中文报错，也不要在背后悄悄截断 —— 截断是那种「上线三个月后才有人发现」的坑。

### 一个自己挖的坑

改 `AuthDtos.java` 的时候，我用字符串 `replace` 去换那几行 record 定义，跑完一编译报错。
看了下文件，那段文字一个字都没变。

原因又是 CRLF：文件是 `\r\n`，我 `old_string` 里拼的是 `\n`。而这已经是这个项目里第三次踩同一个坑了。
（前两次：改 `SecurityConfig` 静默不生效、改 `smoke.ps1` 把文件写成 LF 导致 PowerShell 变量算成空串。）

后面的改法固定成：先 `read` 拿到真实行，再 `split(/\r?\n/)` 按行操作，最后 `join("\r\n")` 写回。
不拼多行字符串，就不存在这个问题。

### 前端那边

`validateRegisterForm` 里原来把昵称和验证码都当必填。改成：昵称留空直接跳过、验证码填了才检查格式。

还有一个小 bug：`checkRegisterNickname()` 在昵称为空时 `return false`，而 `register()` 里是
`if (!(await checkRegisterNickname())) return;` —— 意思是「昵称为空」会被当成「有错误」直接中止注册。
改成 `return true` 才通。这种「返回值语义反了」的 bug 编译器管不了，只能靠读代码或者测出来。

界面文案也跟着改了：「绑定邮箱」→「账号」、`type="email"` → `type="text"`、
昵称 placeholder 改成「选填，默认与账号相同」、验证码 placeholder 改成「开发环境可留空」。

### 验证

smoke 从 29 项加到 33 项，新增 4 项专门盯这个：

- `register-minimal`：只传 `{email, password:"1"}`，确认 200 且昵称自动变成账号；
- `register-minimal-login`：拿刚建的号登录，证明真落库了；
- `register-duplicate`：重名必须 400；
- `register-longpassword`：73 字节必须 400，不能被静默截断。

另外手工试了中文账号 + 中文密码、`account` 字段别名、72 字节正好能过 —— 都对。

---

## 2026-10-20 把 notices 那一整套删了——先搞清谁在用，再动刀

项目里一直有两套内容模型：`posts`（新，前端在用）和 `notices`（最开始的「校园通知」版本）。
前端早就只调 posts 了，但 notices 从表到 Controller 全都还留着，19 个 Java 文件、5 张表。
每次改内容相关的东西我都要先花几分钟确认「这个类是干嘛的、还有没有人调」，成本高得离谱。

### 一开始想「直接删文件不就行了」

试了一下，编译不过。因为 notices 不是一个独立模块，它像藤蔓一样缠在 posts 那边：

- `AdminService.overview()` 统计的是 notices 的发布数；
- `UserService.stats()` 里的「已完成 / 临近截止 / 避坑」全是从 `user_notice_status` 和 `reminders` 来的；
- `CommentController` 的评论接口是挂在 `/api/notices/{id}/comments` 上的；
- `SecurityConfig` 白名单里有 `/api/notices/**`；
- `NoticeDtos` 里还藏着 `TagView` / `CategoryView`，而这两个被 `TaxonomyService` 用着。

所以第一件事不是删，是**查清楚引用关系**。我按类名一个个 grep，把「只有 notices 内部互相引用」
和「被 posts 侧共用」分成两类：

只有 notices 用 -> 删；被 posts 共用 -> 改。

具体到最有迷惑性的两个：

**`comments` 是两套共用的。** V1 建表时 `notice_id` 是 NOT NULL，V3 才 `MODIFY notice_id BIGINT NULL`
并加了 `post_id`。也就是说同一张表里躺着两种评论。这个必须保留，只把 `notice_id` 列摘掉。

**`NoticeDtos` 里有不属于 notices 的东西。** `TagView` / `CategoryView` 只是恰好写在这个文件里，
`/api/categories` 和 `/api/tags` 还在用。所以先把它们搬去新建的 `dto/TaxonomyDtos.java`，
再删 `NoticeDtos`——不然 taxonomy 那边会连坐编译不过。

### 删之前先数一下数据

删表是不可逆的，所以先查了行数：

```
notices 4 | notice_tags 13 | user_notice_status 0 | posts 31 | comments 26
```

notices 那 4 条就是 V2 种下去的演示数据，`user_notice_status` 一行都没有——说明「完成/避坑」
这两个功能**从来没人用过**，前端展示的永远是 0。确认没有真实数据，才敢 DROP。
删之前还是 `mysqldump --single-transaction --routines` 备份了一份（87 KB，几分钟的事，别省）。

### 外键的顺序坑

第一版 V11 写的是直接 `DROP TABLE notices`，报错：

```
ERROR 1217 (23000): Cannot drop table 'notices' referenced by a foreign key constraint
```

`comments.notice_id` 上有 `fk_comments_notice` 指向 notices。必须先摘外键、再删列、最后删表：

```sql
ALTER TABLE comments DROP FOREIGN KEY fk_comments_notice;
ALTER TABLE comments DROP INDEX idx_comments_notice;
ALTER TABLE comments DROP COLUMN notice_id;
DROP TABLE IF EXISTS notice_tags;   -- 这些也都有指向 notices 的外键
DROP TABLE IF EXISTS user_notice_status;
DROP TABLE IF EXISTS favorites;
DROP TABLE IF EXISTS reminders;
DROP TABLE IF EXISTS notices;       -- 最后才轮到它
```

顺带记一个：`DROP INDEX` 也要单独写。`idx_comments_notice` 是 `(notice_id, status, created_at)` 的
联合索引，删了列索引还留着会变成无效引用。

### 那三个恒为 0 的字段为什么没删

`UserDtos.UserStats` 里有 `completed` / `upcoming` / `avoidedRisks`，notices 没了它们就是摆设。
本来打算直接删字段，但停了一下——`AGENTS.md` 里自己立的规矩是 `/api/v1` **只做加法**，
删字段要升 v2。为了三个没人看的数字升一个大版本，不值。

所以留字段、置 0、在 Javadoc 里写清「因 notices 删除恒为 0，v2 时去掉」。
这样 Web/App 端的键名不用动，等真升 v2 的时候一起收拾。

**`favorites` 的处理不一样**：它原来是「通知收藏 + 帖子收藏」相加，现在只剩帖子收藏。
这个改法是**变准了**，不是变假了——`feed.ts` 里点赞/收藏时前端本来就在对这个数做 ±1，
以前那个 ±1 加在一个混合值上，其实一直差着通知收藏的基数。

### 验证

启动日志：`Migrating schema 'biecuoguo' to version "11 - drop notices domain"` ->
`Successfully applied 1 migration, now at v11`。清理后 23 张表，`comments` 只剩 11 列。

`mvn compile` 绿。smoke 33 项全过——注意这 33 项**一项都没覆盖 notices**（因为前端从来没用过），
所以「smoke 全绿」不能证明删对了，只能证明没删坏 posts 那边。真正的证据是编译通过 + 表结构对。

### 教训

1. **删旧东西之前，先把「谁在用」画出来**，别凭印象。这次有两个坑（comments 共用、
   NoticeDtos 里混着 taxonomy），全靠 grep 引用关系才提前发现。
2. **删表之前先数行数**。如果是「演示数据 4 条」和「真实用户数据 4 万条」，同样的代码，
   风险差五个数量级。
3. **`SecurityConfig` 白名单要跟着删**。白名单留着死路径不会报错，但会让人以为那个接口还活着。
4. smoke 覆盖不到的地方，「全绿」是假的安全感。得靠编译 + 看表结构。

---

## 2026-10-20 验证码：开关该管「怎么校验」，不该管「要不要校验」

上一轮为了自己建测试号方便，我把注册改成不用验证码了 —— 账号 + 密码两栏，一按就进。
当时觉得挺爽，测了几天发现不对劲：本地和线上跑的根本不是同一条调用链。

具体会漏什么：注册接口里原来有一步 `verifyCode(...)`，我给它包了个 `if (verificationRequired())`。
本地这个分支永远不进去，也就是说**本地从来没跑过「验码失败会怎么样」这条分支**。
等上线打开真实校验，报错文案、错误码、前端怎么显示这个错——全都没测过。
这种漏不叫「没测到细节」，叫「本地环境比线上少了一整个分支」。

### 改法：开关只管「怎么校验」

字段永远必填，`verifyCode()` 永远被调用，两种模式的差别全部收进 `VerificationService` 里：

```java
public void verifyCode(String email, String purpose, String code) {
    String normalizedCode = normalizeCode(code);   // 空值在这里就被拒，两种模式都一样
    if (mockMode()) {
        if (!normalizedCode.matches("\\d{4}")) { throw new IllegalArgumentException(...); }
        return;
    }
    // 真实模式：查库 -> 判过期 -> 比对 hash -> 标记 consumed
}
```

调用方（`AuthService.register` / `resetPassword`）现在只有一行，没有 if：

```java
verificationService.verifyCode(email, "register", request.verificationCode());
```

开发模式下 `requestCode()` 也直接 return，不写库不发信 —— 之前是「照常写一条随机码进库，
然后校验时无视它」，留下堆没用的行，还容易让人以为那行能用来登录。

### 接真 SMTP 时踩到的两个点

**第一个：`@ConditionalOnProperty` 对空字符串的行为。**
`spring.mail.host: ${SMTP_HOST:}` 这种写法，在 `SMTP_HOST` 没设的时候解析出来是空串。
我原以为 `MailSenderAutoConfiguration` 会因为 host 是空而不创建 bean，结果它照样创建了 ——
`@ConditionalOnProperty` 只判断「键存在不存在」，空串也算存在。
所以不能靠 `ObjectProvider.getIfAvailable()` 判断「有没有配 SMTP」，必须连 host 一起判：

```java
public boolean available() {
    return host != null && !host.isBlank() && mailSenders.getIfAvailable() != null;
}
```

要是只判 bean 在不在，就会得到一个「available() 返回 true、真发信时炸连接」的假阳性。

**第二个：反向的静默故障也要拦。**
之前 `StartupSafetyGuard` 拦的是「开发开关漏到生产」。这次多了一种方向相反的坑：
生产配成真实模式、但忘了配 SMTP。这时候进程起得来、接口返回 200、监控一片绿，
只有新用户发现验证码永远收不到——注册链路断了，没人知道。

所以 guard 里加了第二条：真实模式 + 没有 SMTP = 直接拒绝启动。
注意这条**不区分 profile**，因为它不是「开发开关」，是纯粹的配置缺失。

### 为什么把验证码规则写死成 4 位数字

开发模式也要求是 4 位数字（`\d{4}`），不放过空串、不放过 'abc'。
理由很实在：如果本地允许乱填，前端 `maxlength="4"`、`inputmode="numeric"` 这些东西就没人验证了。
限制成和线上一样，UI 上的坑才会在本地暴露出来。

### 顺带

smoke 加了第 34 项 `register-nocode`：不传验证码必须 400。
这一条是这次改动的**核心断言**——它钉住的不是「验证码能过」，而是「不能不填」。
（另外三项老的注册检查也都补了 `verificationCode = '1234'`，否则它们的 400 会来自
「缺验证码」而不是它们本来要测的东西，变成假通过。）

### 加个 mail 依赖，顺手把 /actuator/health 弄挂了

编译通过、启动也正常，`/actuator/health` 却一直 503。日志里翻到：

```
WARN o.s.b.actuate.mail.MailHealthIndicator : Mail health check failed
jakarta.mail.AuthenticationFailedException: failed to connect, no password specified?
```

`spring-boot-starter-mail` 一进来，Spring 就自动注册了 `MailHealthIndicator`，
每打一次 health 它就去连一次 SMTP。而我 `application.yml` 里写的是
`host: ${SMTP_HOST:}` —— 没配的时候这个键**仍然存在**，值是空串，
所以 `@ConditionalOnProperty` 照样成立，`JavaMailSender` 被创建，健康检查也被注册了。

杀伤力不在本地：容器 healthcheck 打的就是 `/actuator/health`，它一 DOWN 部署就判活失败，
可应用本身明明在跑 —— 排查方向会被带偏好一阵。

改法：`management.health.mail.enabled: false`。
「有没有配 SMTP」该由 `StartupSafetyGuard` 在启动时判一次，
不需要一个每次请求都去连 SMTP 的探针。

### smoke 全红，其实是 PowerShell 按 GBK 解了 curl 的输出

34 项里 15 项 FAIL。诡异的是 `register` 明明是 200，紧接着 `me` 就是 401 —— 说明 token 没拿到。
手动 curl 注册接口，body 完全正常，token 就在里面；用 Node 直接打也是 200 + token。
所以问题在 PowerShell 这一侧。

写了个最小复现，把 `$r.body` 的长度和内容打出来：

```
bodylen=994
PARSE-FAIL: 无效的 JSON 格式... (462): {"code":"OK",...,"major":"未设置专
```

打出来是 `未设置专` 后面跟一个被吃掉的字符。"专业"的"业"是 E4 B8 9A，
按 GBK 解码时 E4B8 算一个字符、剩下的 9A 是非法单字节被吃成 `?` —— 
而且它**连后面那个引号一起吞了**，JSON 字符串没闭合，整段直接非法。

根因：PowerShell 5.1 捕获原生程序（curl.exe）的输出时用的是 `[Console]::OutputEncoding`，
中文 Windows 下默认是 OEM 代码页 936。UTF-8 的响应体被当 GBK 解，不只是显示乱码，会破坏结构。

为什么之前一直没炸：之前那次是在 UTF-8 代码页的终端里跑的。这个 bug 一直在，
只要响应体里出现中文（`user.major = 未设置专业` 就够）就会中招。

修法：脚本开头把 `$OutputEncoding` 和 `[Console]::OutputEncoding` 都设成 UTF-8，
不再依赖当前控制台的代码页。

## 2026-10-20 数据库地址这种东西，就不该有默认值

R-4 在清单上躺了很久。一直没动的理由说出来挺没说服力的：本地有 `.env` 覆盖，
跑起来从来没出过事，看日志也看不到任何异常。

真正把这个当成事，是因为想清楚了一个问题：**出问题的场景不是本地，是任何一次没带 .env 的启动。**
`application.yml` 和 `application-local.yml` 原本都写着
`jdbc:mysql://115.190.3.204:12306/biecuoguo`，那台机器已经停服了。现在连不上，所以“看起来没事”。

但“连不上”不是设计出来的安全，是运气。如果那台机器的 12306 哪天被放开——
云安全组改错一次、机器转手给别人、或者干脆只是网络通了——
照默认值启动的后端会做两件事：**对着一台陌生库跑 Flyway**，然后跑 `AuthService` 的启动初始化。
前者会改别人的 schema，后者会往里写账号。这已经不是“连错库”，是“动了不该动的数据”。

### 中间想错的两个方向

第一个想法是把默认值改成一个**能连通的**地址，让它“开箱即用”。
这等于换个地方埋雷——默认值越“好用”，越没人会去显式配置它。

第二个想法是在启动时加连通性检查，连不上就拒绝启动。
方向对，但位置错了：`StartupSafetyGuard` 是给“危险开关”用的，
而“数据库地址”这件事根本不该有默认值来让人猜。**检查默认值，不如取消默认值。**

### 最后定的三档

```text
application.yml        -> 127.0.0.1:3306     # 本机标准端口，连不上就是本机没有
application-local.yml  -> 127.0.0.1:12306    # 对上 docker compose --profile local-db 的宿主机映射
docker-compose.yml     -> mysql:3306         # compose 网络里的服务名
deploy-app.sh / deploy-ip.sh -> 没有默认值，SPRING_DATASOURCE_URL 缺失直接退出
```

前三个是“本机”或“同一个网络里的自己人”，错也错在自己家里；
最后两个是部署脚本，它们连“自己家”在哪都不知道，**猜就是错的**。

顺手把两个脚本里 `Cannot connect to MySQL at ...` 的提示也改了。
原来那段写的是「Check these on server A」，还教人用 `./deploy-mysql.sh` 起 MySQL——
双机架构早就废了，照着提示排查会一路撞墙。现在换成了不依赖拓扑的五步检查。

### 最该改的其实是 README

README 里原本有一句「数据库连接默认就是服务器 A」，底下直接跟一段可以复制的 env 块，
里面是那个已经停用的 IP。**文档教人复制一个死地址**，比代码里的默认值更危险——
因为复制的人不会怀疑它。现在改成“必须显式配置”+ 本地示例。

改完 `mvn package` 和 `scripts/smoke.ps1` 都还是 34/34，本地链路一点没动。

## 2026-10-20 匿名访客看的东西，居然是「随便挑一个人」决定的

D-3 这条在清单上挂了很久，一直是「知道有问题但没空管」。真正动手是因为想加一个新用户注册，
翻到 `AuthService` 里 `user.setSchoolId(1L)` 写了四遍，然后想起访客那边也有个 `1L`——
再往上翻，发现访客那条路径根本不是 `1L`：

```java
private User defaultViewer() {
    return userMapper.selectList(new LambdaQueryWrapper<User>()
            .eq(User::getStatus, "active").last("limit 1"))
            .stream().findFirst().orElse(null);
}
```

`limit 1` 没有 `order by`。也就是说**未登录用户看哪批帖子，取决于 MySQL 先返回哪一行**。

### 为什么一直没被发现

因为库里所有账号的 `school_id` 都是 1。随便挑谁，挑出来的学校都是 1，
所以「随机」在数据上表现为「恒定」——看起来完全正常。

这个 bug 的触发条件很苛刻：得有个用户的 `school_id` 不是 1 且 id 排在最前面。
一旦真的出现（比如给某个学院开了独立账号），表现会是「未登录首页突然少了一堆帖子」，
而且**重启后可能又好了**——这种 bug 最难查，因为没有稳定复现路径。

### 改法：把「没有学校」这件事变成一个有确定答案的状态

我一开始想让访客看到**全部**帖子。想了想不对：这是校园 PO，混着不同校区的帖子比缺帖子更糟。
访客应该有确定的一个学校可看，那就把「默认学校」显式化：

```java
Long schoolId = viewer == null || viewer.getSchoolId() == null
        ? defaultSchoolId() : viewer.getSchoolId();
return wrapper.eq(Post::getSchoolId, schoolId);
```

`defaultViewer()` 直接删掉，三个调用点全部改成 `currentUser == null ? null : ...`。

顺带把 `AuthService` 里四处 `setSchoolId(1L)` 也收进同一个配置 `app.default-school-id`。
它们本来就是同一个概念（「这个人属于哪个学校」的默认值），分在五个地方写死纯属没想过。

### 怎么证明这个配置真的被读到了

改完这种事最容易假验证：启动成功、接口 200、数据看着对——但**可能根本没读那个配置**。
所以我没用默认值测，而是把它设成 `999`（库里不存在的学校），预期匿名 feed 变成 0 条。

第一次试就翻了两个坑：

**坑一：环境变量压不过 profile yml 里的字面值。**
我用 `APP_DEFAULT_SCHOOL_ID=999 BOOTSTRAP_SEED_DEMO_DATA=false` 起，结果启动直接失败：

```
java.sql.SQLIntegrityConstraintViolationException: Cannot add or update a child row:
a foreign key constraint fails (`biecuoguo`.`users`, CONSTRAINT `fk_users_school`
FOREIGN KEY (`school_id`) REFERENCES `schools` (`id`))
```

这说明 999 确实被用到了（不然不会撞外键），但也说明 `BOOTSTRAP_SEED_DEMO_DATA=false` **没生效**——
演示账号还是被建了。原因是我把它塞在 `application.yml` 的 `${...}` 占位符里，
而 `application-local.yml` 有一行字面的 `seed-demo-data: true`，那份文件优先级更高，
占位符根本轮不到。要压过它只能用命令行参数 `--app.bootstrap.seed-demo-data=false`。

**坑二：`school_id` 是有外键的。**
这条其实是个有用的副产品——它证明这个配置不能乱填。设成不存在的学校，
启动时建号就撞 `fk_users_school`。这算 fail fast，比悄悄写进去一个孤儿 school_id 好。
现在 AGENTS §4 和 §8 都写了这一条。

用命令行参数重测：`--app.default-school-id=999 --app.bootstrap.seed-demo-data=false`，
结果 feed 0 条、四个板块计数全 0、游标分页 0 条 `hasMore=false`，三条读路径全部跟着配置走。

### 一个顺带的教训

要验证「配置有没有生效」，别用默认值测。把配置改成一个**必然产生可见差异**的值，
再断言那个差异。这次如果我用默认的 1 去测，改前改后一模一样，什么问题都发现不了。

## 2026-10-20 JWT 的签名密钥，一直明明白白写在仓库里

这条在清单上被归成 Q（质量项），排在最后。翻配置的时候才反应过来它其实是 R：

```yaml
# application.yml（改之前）
jwt:
  secret: ${JWT_SECRET:change-this-secret-to-a-very-long-random-string-32-plus}
```

`.env.example` 里是 `change_me_to_a_long_random_secret_at_least_32_chars`，
`docker-compose.yml` 里是 `please-change-this-to-a-very-long-random-secret-32`。
三个地方三个值，但**全都是公开的**，而且 `docker compose up` 不配 `JWT_SECRET` 就会用它们。

### 为什么一直没当成事

因为它长得像占位符。带 `change` / `please-change` 字样，看上去已经提醒过人了——
仿佛「写了提醒」本身就等于「别人会去改」。

但占位符写在配置文件里就是**生效值**，不是注释。真正的后果是：
知道这个字符串的人可以自己签一个 `{"sub":"5","role":"ADMIN"}`，
然后 `Authorization: Bearer <伪造>` 直接进后台。
不需要注册，不需要验证码，**服务器也不会有任何一条异常日志**——
它认为这就是一个合法 token。

### 顺手发现的第二个问题

`security/JwtService.java` 里有这么一段：

```java
if (secret.length() < 32) {
    secret = secret + "0".repeat(32 - secret.length());
}
```

为了防止 JJWT 抛 `WeakKeyException`，它把短密钥补 `0` 凑到 32 字节。
一个 3 位密钥被补成 32 字节，看起来合规了，实际强度还是 3 位。
这种「补齐」比报错危险得多——报错会逼你改配置，补齐只会让你以为没事。

### 一个想过的方案：启动时自动生成密钥

第一反应是「干脆别要配置了，启动时随机生成一个」。听起来最省事。

问题是重启就换密钥，所有人当场被登出；多实例部署时每个实例的密钥还不一样，
A 实例签的 token 到 B 实例直接验不过。随机生成的密钥只适合「进程内临时用」，
不适合一个要跨重启、以后可能跨实例的会话系统。

所以结论反过来：**宁可启动失败，也不要悄悄用一个能用的密钥。**

### 改了什么

```text
application.yml        secret: ${JWT_SECRET:}                       # 空默认值
application-local.yml  secret: ...local-dev-only-jwt-secret...      # 只有本地有值
docker-compose.yml     JWT_SECRET: ${JWT_SECRET:?...必须显式配置}   # 缺失时 compose 直接报错
.env.example           JWT_SECRET=                                   # 空，附 openssl 生成命令
```

`StartupSafetyGuard` 加了第 0 条检查，**不区分 profile**：空值、三个历史示例值、
短于 32 字符、非开发环境用了本地那个专用值——四种情况一律拒绝启动。
异常信息里刻意不打印密钥本身，因为这段文字会进日志。

`JwtService` 的补 `0` 改成抛异常：密钥不合格应该在启动时炸，不该在签名时悄悄修补。

### 怎么验的

四条拒绝路径一条条跑，看退出码和文案对不对（空值 / 示例值 / `abc123` / 非开发 profile + 本地值），
都是 `exit=1`。然后正常路径：`local` 启动 → 登录拿 token → `/auth/me` 返回 `ADMIN`
（token 真的被签发并校验了，不是绕过去的）→ `smoke.ps1` 34/34。

`docker compose config` 那一步没验成——这个 shell 里 compose 插件没挂上，
`docker compose` 直接报 unknown command。`:?` 的语义是 Compose 规范里写死的，
但「确实会拦下」这件事得等有 compose 的环境再确认一次。

## 2026-10-20 上传接口说「只支持图片」，其实只信了客户端那句话

`MediaService` 原来的校验是这样的：

```java
String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
    throw new IllegalArgumentException("目前只支持图片上传");
}
```

`MultipartFile.getContentType()` 返回的是**请求头里的 Content-Type**，一个客户端随便写的值。
所以这段代码的实际含义不是「只接受图片」，而是「只接受请求头里写了 image/ 的东西」。

具体能干嘛：把一个 `.html` 或者带 `<script>` 的 `.svg` 用 `curl -F 'file=@x.html;type=image/png'` 传上来，
校验通过，文件原样进 MinIO，`media.content_type` 也存成 `image/png`，
然后 `/api/media/{id}/content` 用这个 Content-Type 把它吐回浏览器。
浏览器遇到 `image/png` 不会执行里面的脚本，所以这一步还不至于立刻 XSS；
但只要哪天为了「支持 SVG 图标」把类型放宽到 `image/svg+xml`，同源打开就是执行任意 JS。
更别提还顺便变成了一个任意文件的免费图床。

### 改法：认文件头，然后所有东西都以识别结果为准

加了 `service/ImageSignature.java`，读头 12 个字节，只认四种真实图片：

```text
JPEG  FF D8 FF
PNG   89 50 4E 47 0D 0A 1A 0A
GIF   "GIF8"
WebP  "RIFF" + 4 字节长度 + "WEBP"
```

认出来之后，**扩展名和 Content-Type 都用识别结果**，不再用 `file.getOriginalFilename()` 和请求头。
这样「文件名是 a.png、内容是 HTML」这种情况根本进不了下一步，因为它压根认不出来。

### 顺手砍掉的两个东西

**上限 20MB → 5MB。** 手机上随手拍的照片三四 MB，再大就该客户端压缩了；
20MB 更像是「懒得想」填的数字。`MEDIA_TOO_LARGE` 的文案也跟着改了。

**`purpose` 是拼进对象 key 的。** 之前直接 `firstNonBlank(purpose, "post")`，
客户端传什么就拼什么。对象存储没有路径穿越问题（key 是扁平字符串），
但传个超长或者带斜杠的值能把目录结构搞得很脏。现在只允许 `[A-Za-z0-9_-]{1,20}`，不合格一律当 `post`。

### 验证

四条一起测，因为「真图片能传」和「假图片被拒」必须同时成立，只测拒绝那一侧可能是校验写死了一律拒绝：

```text
真 PNG                    → 200  url 结尾 .png，contentType image/png
HTML 冒充 image/png        → 400  只支持 JPEG / PNG / GIF / WebP 图片（按文件内容识别，改扩展名没用）
SVG 冒充 image/png         → 400  同上
6MB PNG（头是真的）        → 413  图片太大了，请换一张 5MB 以内的图片
```

最后一条是 Spring 的 multipart 上限先拦的（`max-file-size: 5MB`），
不是 `MediaService` 里那个字节数检查——两个都在，前者是实际生效的那道，后者是纵深防御。

### 还没做的

没有校验像素尺寸，所以「5MB 的解压炸弹」理论上还是能传上来。
服务器自己不解码，炸弹炸的是浏览器，所以优先级不高；真要防得按格式读 IHDR/帧头，
而 WebP 没有内置的 ImageIO reader，得手写解析——现在不值得。

## 2026-10-20 加了限流，然后发现「按 IP 限流」在代理后面等于给全站上锁

要挡的东西很明确：验证码接口每发一封信都是真金白银，而且能拿去轰炸别人的邮箱；
登录接口没有上限就是无限撞库。于是写了个固定窗口限流，按「来源 IP + 接口」计数。

第一版写完准备收工，突然想到一个问题：**部署的时候后端前面站着 Caddy。**

Spring 的 `request.getRemoteAddr()` 拿的是 TCP 对端的地址。整栈部署时，
所有请求的 TCP 对端都是 Caddy 容器——不管是一百个人还是一万个人，
`getRemoteAddr()` 全都返回同一个 IP。也就是说：

```text
登录限流 20 次 / 5 分钟 / IP
→ 实际上是「全站所有用户加起来 5 分钟只能登录 20 次」

→ 一个人拿脚本刷 20 次错误密码，全站用户 5 分钟内登不进来
```

这不是限流，这是给自己装了个开关。而且它**只在部署后出现**：本地开发连的是
`localhost:8080`，remoteAddr 就是 127.0.0.1，一切正常，测试全绿。

### 所以要么读 X-Forwarded-For，要么别用 IP 限流

Caddy 会写 `X-Forwarded-For`。但它是个普通请求头，客户端可以自己伪造——
如果无脑信任，攻击者每次换一个假 IP，限流就完全失效。

结论是必须知道「我前面到底有没有代理」。这不是能从代码里推断出来的信息，
只能配置：

```yaml
app:
  rate-limit:
    trust-forwarded-for: ${RATE_LIMIT_TRUST_FORWARDED_FOR:false}
```

- 默认 `false`：用 `remoteAddr`。适合「直接暴露后端」或本地开发。
- `true`：取 `X-Forwarded-For` 第一段。**只有在前面确实有代理、且外部连不到后端时才成立**。

`docker-compose.yml` 里默认 `true`，因为那个栈一定带 Caddy 且后端没有对外端口。
这个默认值写在 compose 而不是代码里，是因为「有没有代理」只由部署形态决定。

### 为什么不用令牌桶

想过 Bucket4j（现成的，还有分布式实现），但当前需求是「别让人拿脚本刷」，
不是「精确整形流量」。固定窗口十几行、无后台线程、无额外依赖；
代价是窗口边界可能放过接近两倍的请求——比如 5 分钟窗口，
在 4:59 打满 20 次、5:01 再打满 20 次，两分钟内实际过了 40 次。可以接受。

真正该上 Bucket4j/Redis 的时刻是「多实例部署」，那时进程内的计数器本来就不成立（Q-4）。

### 数值怎么定的

第一版定得偏紧（验证码 5 次/10 分钟），然后意识到会打到自己的脚：
`smoke.ps1` 一次要发好几个验证码，连着跑三遍就把窗口用完了，
**而限流窗口是 10 分钟——等于本地开发被自己锁十分钟**。

最后定成「够挡脚本、够本地折腾」的量级：登录 30/5min、验证码 20/10min、
注册 20/1h、改密 20/10min、上传 30/5min、发帖 20/5min。

### 验证

smoke 34/34 全绿（说明没误伤正常链路），然后对着验证码接口连发 30 次：

```text
第 20 次开始 429，Retry-After: 595
{"code":"RATE_LIMITED","message":"操作太频繁了，请 595 秒后再试","data":null,"requestId":"..."}
别的接口仍然 200，登录仍然 200
```

那两个「仍然 200」是重点：限流必须只打中目标接口，不能因为规则写错把整站罩住。

### 没进 smoke

想过把「打 20 次看会不会 429」加进 `smoke.ps1`，但那样每跑一次就把窗口占满，
十分钟内没法再跑——用一个检查项换来十分钟不可用，不划算。
这条目前只能手动验，记在 ISSUES 里。

## 2026-10-20 验证 CI 的脚本，先在 Git bash 里被自己写的检查坑了一次

加 `.github/workflows/ci.yml`，其中有个 `files` job 干三件小事：
`.sh` 必须是 LF、`.ps1` 必须是 CRLF（PowerShell 5.1 解析 LF-only 会静默出错）、`.env` 不能入库。

workflow 写完没法在本机跑 GitHub Actions，但思路可以降级验证：本机有 Git for Windows 的 `bash.exe`，
把那两段脚本抠出来直接在仓库里跑一遍，总比 push 上去等结果强。

结果 `files` job 报：

```text
FAIL missing CRLF in scripts/smoke.ps1
```

可我明明记得那个文件是 CRLF 的（之前专门转过）。用 node 读字节确认：223 个 CRLF、223 个 LF、纯 LF 为 0——**文件是对的**。

### 问题在 grep，不在文件

MSYS2 版的 `grep` 默认按文本模式打开文件，会把 `\r\n` 读成 `\n`。
所以 `grep -q 

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r' file` 永远匹配不到东西——**在 Git bash 里，任何文件看起来都是 LF**。

实测：

```text
grep -q  

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r' scripts/smoke.ps1  → 不匹配
grep -qU 

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r' scripts/smoke.ps1  → 匹配（-U/--binary：别做换行转换）
```

Ubuntu 上的 GNU grep 没有这个文本模式转换，同一条命令在 CI 里是准的——
但「本地准不了」意味着我以后在 Windows 上根本没法预检这条规则。加 `-U` 两边就一致了。

### 顺带发现的第二件事：Linux runner 上 checkout 出来的 .ps1 是 LF

`.gitattributes` 原来只有一句 `*.sh text eol=lf`。`.ps1` 没规则，`core.autocrlf=true` 的机器上
工作区是 CRLF、索引里是 LF；**而 Linux runner 默认 `autocrlf=false`，checkout 出来就是索引里的 LF**。
也就是说：那条 CRLF 检查如果在 CI 里跑，即使逻辑写对了也会红——因为 CI 上的文件真的是 LF。

补了一句，让 checkout 出来的一定是 CRLF：

```gitattributes
*.ps1 text eol=crlf
```

### 另一处「我以为的接口」

冒烟断言里原本写 `GET /api/v1/posts/999999` 期望 404 + `POST_NOT_FOUND`，
实际返回 `405` + `VALIDATION_FAILED / 不支持的请求方法`——因为 `PostController` 上根本没有 `GET /{id}`，
只有 `POST`，所以这个路径在 Spring 眼里是「方法不允许」，不是「资源不存在」。

换成 `GET /api/v1/posts/999999/comments`（这个 GET 是存在的），返回才真是 `404` + `NOT_FOUND`。
顺带发现业务码不是 `POST_NOT_FOUND` 而是更笼统的 `NOT_FOUND`（文案「PO 不存在」）。

**教训**：写断言的时候不能凭印象写路径，得先 `curl` 一遍看真实返回，再把输出钉进断言。

### 现在的状态

`files` job 三条全过，`smoke` job 六条断言在本地 Git bash 下全过（`CI-SMOKE-PASS`）。
但 workflow 本身还没在 GitHub 上真正跑过一次——runner 环境、MySQL service 容器、
`setup-java` 的缓存放行都得等第一次 push 才知道。
 有特殊含义**：`
## 2026-10-20 加了 refresh token，然后发现「退出登录」根本没用

需求很直接：现在 token 一签 7 天，偷到就是 7 天会话；过期了还得重新登录。改成短 access + 长 refresh。

### 先说两个自己挖的坑

**坑一：改完默认值，行为一点没变。**

`application.yml` 里把 `expiration-minutes` 的默认值从 `10080` 改成 `120`，重启，登录，解 token：

```text
access typ: access | 有效期分钟: 10080
```

十分钟后想起来仓库根有个 `.env`（gitignored，`spring.config.import: optional:file:../.env` 读的），
里面赫然一行 `JWT_EXPIRATION_MINUTES=10080`。环境变量/`.env` 比 yml 里的默认值优先，
**yml 里那个 120 只是「没人配的时候」的值**。这和之前 D-3 那次踩的是同一个坑：
`application-local.yml` 里的字面量 `seed-demo-data: true` 压过了 yml 的占位符。

验证这种改动不能「跑一下看看」，得**算出来**：解 JWT 的 payload，`(exp - iat) / 60` 必须等于预期分钟数。

**坑二：退出登录之后，refresh token 还能换出新 access。**

端到端脚本里第 11 条：`logout 后 refresh 换 token（应 401）` → 结果 **200**。

原因很蠢：`AuthController.logout` 拉黑的是 `Authorization` 头里的 access token，
而 `AuthService.refresh()` 从头到尾没查过黑名单——它只验签名和过期时间。
于是「退出登录」在服务端拉黑了一个 120 分钟就自然失效的 token，却放过了那个能活 14 天的。

补法就是在 `refresh()` 最前面加一句 `tokenBlacklistService.isRevoked(refreshToken)`。
这类 bug 天然不会在有状态测试里暴露，**必须真的走一遍「登录 → 退出 → 再刷新」这个顺序**。

### 为什么不做 refresh 轮换

标准做法是每次刷新都换一个新的 refresh token、把旧的立刻作废。我没做，理由：

轮换要求「旧 token 一旦用过就永久失效」，而这张黑名单是**进程内**的 `ConcurrentHashMap`（见 ISSUES Q-4）。
放到真实场景里会出两种误伤：

1. **多标签页**：两个页面同时 401，各自拿同一个 refresh token 去换；A 成功并作废旧 token，
   B 拿着刚被作废的 token 换失败 → B 那个页面被踢下线。前端可以做单飞刷新缓解，但两个标签页是两套 JS 上下文，管不到一起。
2. **多实例**：A 实例轮换、B 实例不知道，请求一落到 B 就 401。

而收益呢？refresh token 被偷，等价于「已经登录了」。轮换能缩小窗口，但前提是**作废这件事本身可靠**。
所以在拿到共享存储之前，轮换带来的净收益是负的——先把短 access 落地，轮换等 Redis 那一步再做。

这个决定写进了 AGENTS.md §4，别哪天顺手「补上了轮换」又把多标签页搞坏。

### 顺带补的

- 前端不是「存两个 token」就完了：401 时要**单飞**刷新再重放原请求。首屏七八个请求一起 401 的话，
  各自去刷新会打出一串重复的 `/auth/refresh`。用模块级 Promise 合并，谁先到谁发。
- `smoke.ps1` 从 34 项加到 37 项（refresh / 用刷新后的 token 调 /me / access 冒充 refresh 必须 401），
  CI 的 bash 冒烟也补了同样两条。两边都跑过才算数。

---

## 怎么写这篇文档` 是匹配到的内容，
`` # 开发手记

自己的开发记录。记的是「当时怎么想、为什么这么改、哪里绕了弯路」，不是 API 文档也不是提交日志。

规矩很简单：踩到那种「当时想不通、想通了觉得值得记一笔」的坑，就当天记下来，不用等收尾。
写作格式见文末。

---

## 2026-10-20 游标分页：先写错一遍才想明白

做 feed 分页的时候，我一开始用的是最简单的写法：游标只装上一页最后一条的 id，SQL 加个 `id < cursor`。
写完自测，拉了 3 页，每页 5 条 —— 三页全是同一批：`2,3,1,4,5`。

第一反应是 SQL 写错了。于是打开 MyBatis 的 DEBUG 日志看真实执行的语句，发现生成的 SQL 就是我想写的：

```sql
WHERE status = ? AND school_id = ? AND id < 15 ORDER BY pinned DESC, official DESC, ... LIMIT 6
```

返回 `2,3,1,4,5,14`。看着很离谱，但**它完全正确**。

因为 ORDER BY 第一位是 `pinned`，第二位 `official`，一路排到 `like_count` —— 这是按热度排的。
id 小的帖子完全可能因为点赞多而排在 id 大的前面。所以 `id < 15` 这个条件跟排序键**没有任何关系**，
它只是把 id 大于 15 的过滤掉了，剩下的顺序一点没变，自然每页都吐同一批。

结论：游标分页的前提是「游标必须编码完整的排序键」，然后用 WHERE 把 ORDER BY 的字典序复现出来。
排序键有 7 个字段，游标就得装 7 个字段，少一个都会重复或漏条。

改成 keyset pagination：游标用 `|` 分隔存 `pinned|official|dontMiss|likeCount|commentCount|publishedAt|id`，
查询条件变成一串 OR：

```
pinned < k
  OR (pinned = k AND official < k)
  OR (pinned = k AND official = k AND dontMiss < k)
  OR ...
  OR (前 6 个全等 AND id < k)
```

这里还有两个 MyBatis-Plus 的小坑：`LambdaQueryWrapper` 没有 `and(Consumer)` 方法，只有 `nested`，
得用 `nested(w -> {...})` 把整个 OR 链包起来；另外静态嵌套 record 里调不到外部类的私有方法，
只能把 `getLikeCount() == null ? 0 : getLikeCount()` 这种空值兜底内联进去。

教训：**游标分页不是「记住位置」，是「记住排序键的值」**。如果排序规则复杂到不好编码，
就说明它不适合做游标分页，得先简化排序。

---

## 2026-10-20 统一响应包：为什么要加 code 和 requestId

之前所有接口返回的是 `{"data": ...}`，错误返回 `{"message": "中文提示", "timestamp": "..."}`。
看起来挺简洁，问题在客户端那侧：

App 想判断「是业务失败还是网络断了」，只能去看 `message` 的内容。于是代码里到处都是
`if (msg === "邮箱或密码不正确")` 这种东西。哪天我把提示语里的「不正确」改成「错误」，
所有依赖它的逻辑就静默失效了 —— 而且编译不报错，测试也不会挂，只有用户会发现行为变了。

所以要加一个机器可读的 `code`。设计上定了三条：

**第一，HTTP 状态码保留语义，不走「一律 200 + code」那套。**
理由是基础设施全都看 HTTP 状态码：Caddy 会按 5xx 报警，负载均衡按 4xx/5xx 决定要不要重试，
CDN 会缓存 200。如果服务端崩了还回 200，监控面板上就是一片绿，等你从用户嘴里知道出事了。
所以分工是：`code` 给 App 做业务分支，HTTP status 给代理/监控/重试策略看，各管一段。

**第二，`code` 用枚举而不是字符串常量。**
字符串散在各处就会拼错、就会漂移，`AUTH_INVALID_CREDENTIAL` 和 `AUTH_INVALID_CREDENTIALS`
差一个 S 编译器不会管。放进 `ErrorCode` 枚举之后，加一个新码必须显式声明默认提示语，
而且引用处写错名字直接编译失败。

**第三，`data` 字段一定要保留。**
这条是纯工程考虑：前端所有请求都走同一个 `unwrap()`，实现就是 `return response.data.data`。
只要新响应包里还有 `data`，前端一行都不用改，全站零回归。这是故意的兼容设计，不是巧合。

`requestId` 是顺手加的，但实际很实用。用户说「我发帖失败了」，没有 requestId 的时候只能靠时间戳猜，
或者让他复现一次。现在每个请求分配一个 16 位 id，同时写进响应头 `X-Request-Id` 和服务端日志的 MDC，
拿到那个 id 直接 grep 日志就精确命中那一次请求 —— 包括它跑了哪些 SQL、耗时多少、报的什么错。

实现上有个细节：这个过滤器必须排在 Spring Security 之前，否则安全层的日志就带不上 id。
这里没用 `@Order` 注解，而是显式 `FilterRegistrationBean.setOrder(Ordered.HIGHEST_PRECEDENCE)` ——
因为 Spring Boot 对 `@Component` 过滤器跟 SecurityFilterChain 的注册顺序**不保证**谁先谁后。
这个坑在下面 addPathPrefix 那节还会再遇到一次。

---

## 2026-10-20 GlobalExceptionHandler 补上 500 兜底

原来那个异常处理器写了 400/401/403/404/413 的映射，但**没有 `@ExceptionHandler(Exception.class)`**。
这意味着任何没被显式接住的异常都会走 Spring 默认错误页，返回的是 Boot 自己那套
`{"timestamp","status","error","path"}`。

后果很讽刺：我们刚花力气把响应包统一了，但**最需要它的场景反而不统一** —— 服务端真的出 bug 的时候，
客户端拿到的是另一个格式，解析不出 `code`，也拿不到 `requestId`。而这恰恰是最需要定位信息的时刻。

所以补了兜底。这里有个决定我觉得比代码本身重要：

**兜底里绝对不能把 `ex.getMessage()` 回给客户端。**

原因很实在：异常消息里经常带 SQL 片段、文件绝对路径、内网主机名、甚至数据库连接串。
这些是攻击者最喜欢的信息，免费的信息泄露。所以兜底只回一句固定的「服务器开小差了」，
真正的堆栈用 `log.error` 打出去，靠 requestId 关联。想看详情？有服务器权限的人去看日志。

顺手还补了三个之前漏掉的映射：请求体不是合法 JSON（`HttpMessageNotReadableException`）、
静态资源不存在（`NoResourceFoundException`）、方法不支持（`HttpRequestMethodNotSupportedException`）。
尤其是第一个，做时间字段迁移的时候特别容易触发 —— 客户端传个裸的 `2026-10-19T15:22:01`，
而字段类型是 `OffsetDateTime`，Jackson 解析失败，如果没接住就变成 500。

### 这里有个不对称，我漏过一次

`@RestControllerAdvice` 是 **DispatcherServlet 层**的东西。但认证/鉴权失败发生在更早的
Security filter chain 里，那些请求**根本进不到 DispatcherServlet**，全局异常处理器完全管不到。

我是先只改了 `GlobalExceptionHandler`，然后测 401，发现返回的还是老格式
`{"message":"请先登录","timestamp":...}`，才反应过来。
最后在 `SecurityConfig` 的 `authenticationEntryPoint` 和 `accessDeniedHandler` 里手写了一份同格式的响应。

教训：**响应格式有两个产出点，一个在 DispatcherServlet 里，一个在 Security 过滤器链里。**
改格式必须两处一起改，否则就会出现「大部分接口对了但 401 还是老的」这种半吊子状态。

---

## 2026-10-20 时间字段：为什么 LocalDateTime 是错的

这件事我一开始觉得是「可选项」，后来越想越觉得是硬伤。

MySQL 的 `DATETIME` 不存时区。Java 的 `LocalDateTime` 也不带时区。两边都不带，看起来「很配」，
实际上是**双重丢失** —— 没人知道那个裸时间属于哪个时区。

具体会出什么事：数据库里存 `2026-10-19 15:22:01`，接口返回 `"2026-10-19T15:22:01"`。
成都的用户看到，以为是本地时间 15:22；东京的用户也以为是本地 15:22。只有东八区的人碰巧猜对了。

更要命的是算相对时间。App 想显示「3 分钟前」，得拿服务端时间减客户端时间。
但客户端不知道那个裸时间属于哪个时区，减出来的结果可能差 8 小时。这种 bug 在国内测试完全发现不了，
等有海外用户反馈才暴露，而且很难复现。

所以接口层必须带偏移：`"2026-10-19T15:22:01+08:00"`。

### 为什么数据库不改

理论上最干净的做法是数据库也存带时区的类型（`TIMESTAMP`）。但有两个现实问题：
一是 `DATETIME` 改 `TIMESTAMP` 要迁移存量数据，而且要处理迁移期间的双写；
二是 MySQL 的 `TIMESTAMP` 有 2038 年的上限。

最后决定：**数据库不动，只在接口边界转换。** 存进去还是 `LocalDateTime`，吐出来转成 `OffsetDateTime`。
新增了 `config/AppTime.java` 专门干这件事，全项目只有这两个方向：
出参 `AppTime.toOffset(...)`（写在 DTO 的 `from()` 里），入参 `AppTime.toLocal(...)`（Service 落库之前）。
不在业务逻辑中间到处转换，免得有一天忘了转。

### 两个实现上的选择

**为什么做成静态工具类而不是普通 Bean。**
因为 DTO 的 `from()` 是静态工厂方法，Spring 注入不进去。所以做成「Spring 构造时初始化一个静态字段，
之后全是静态方法」。不算优雅，但这东西全局只读、没有并发问题，是这个约束下的最省事做法。

**为什么用配置项而不是 JVM 默认时区。**
如果写 `ZoneId.systemDefault()`，那服务器时区一改（比如迁到 UTC 的云主机），所有接口返回的偏移值就悄悄变了 ——
前端存下来的历史数据全部错位，而且是那种「上线三天后才有人发现」的错位。
所以用 `app.time-zone` 显式钉死，默认 `Asia/Shanghai`，换机器不改变语义。

---

## 2026-10-20 addPathPrefix 把全站搞成 401

做 `/api/v1` 版本化的时候，我先想到的是最「正统」的写法：`WebMvcConfigurer.configurePathMatch` + `addPathPrefix`。
看起来就是「给所有接口加个前缀」，一行配置搞定。

写完编译通过，重启，然后：

```
/api/schools      -> 401
/api/categories   -> 401
/api/auth/login   -> 401     连登录都进不去
```

排查方式是把它改名成 `.bak`，重新编译重启，`/api/schools` 立刻恢复 200。确认就是它干的。

原因是 `addPathPrefix` 并不是「在字符串前面拼一段」，它**重建了整套 path matching 配置**。
而 Spring Security 的 `AntPathRequestMatcher` 用的是另一套解析逻辑，两边对不上。
结果就是 `SecurityConfig` 白名单里写的 `/api/schools` 匹配不到任何真实请求，
所有请求统统落到 `anyRequest().authenticated()`，全变 401。

最后改成 Servlet Filter 直接改写 URI：`/api/v1/xxx` 改写成 `/api/xxx` 再往下走。
这样白名单只要维护一份（都是 `/api/...`），MVC 层的路径匹配完全不动。

这里又踩了一次顺序的坑：最开始的 `@Order(1)` 不起作用，因为 Boot 不保证 `@Component` 过滤器
跟 SecurityFilterChain 谁先注册。改成 `FilterRegistrationBean` + `setOrder` 才稳。

教训：**框架里那些「看起来只是加个前缀 / 改个默认值」的 API，实际可能重建整条链路。**
动全局路径匹配这种地方，先在最小场景验证，别一次改完再一起看结果。

---

## 2026-10-20 卡我最久的居然是个换行符

这个坑值得单独写一节，因为它的症状完全不指向原因。

smoke 脚本里加了这几行验证 `/api/v1`：

```powershell
$Base = 'http://127.0.0.1:8080'
$v1 = ($Base.TrimEnd('/')) + '/api/v1'
```

跑出来所有 v1 请求都是 `http=000` —— curl 连都没连上，说明 URL 是坏的。

加 DEBUG 打印：

```
DEBUG Base=[http://127.0.0.1:8080] v1=[]
```

`Base` 明明有值，`v1` 是空串。可同样的两行单独写个 `.ps1` 跑，结果完全正确。

接下来试了一堆方向：换拼接方式、用 `Join-Path`、改成格式化字符串……全都没用。
因为方向从一开始就错了 —— 我一直在怀疑表达式。

真正的原因：**我用编辑工具改这个文件，它被写成了 LF 换行。**
PowerShell 5.1 解析 LF-only 的 `.ps1` 会出现这种诡异的静默失效：变量赋值看起来执行了，值却是空的，
而且不报任何错。整个文件转回 CRLF，立刻正常。

事后看 git diff 才发现，原本的文件就是 CRLF，是我自己给破坏的。

教训：**遇到「逻辑明明没错但结果就是空/就是不改」的症状，先怀疑文件编码和行尾，别怀疑逻辑。**
PowerShell 5.1 对行尾很敏感，而且报错信息完全不指向真正的原因。现在改完 `.ps1` 一律转回 CRLF。

---

## 2026-10-20 两个 replace 的坑，各浪费了一轮

**坑一：`tools.edit` 对 CRLF 文件静默不匹配。**
我给 `SecurityConfig` 加统一错误格式，`old_string` 里用的是 `\n`，但文件里是 `\r\n`。
编辑工具没报错，也没提示「没找到」，就是什么都没改。编译通过，重启，测 401 —— 还是旧格式。
我当时以为是代码逻辑错了，又去翻了一遍，最后才发现根本没写进去。

→ 现在改文件前一律先 `read` 拿到真实的行内容，不在脑子里拼 `old_string`。

**坑二：JS 字符串里的 `$'` 是个特殊模式。**
我在生成 PowerShell 脚本时，往 `String.replace()` 的替换串里塞了一个正则 `'T\d{2}:\d{2}:\d{2}$'`。
结果 `$'` 被 JS 当成「匹配位置之后的所有内容」这个特殊占位符，把后面的东西全吃掉了，
生成的 `.ps1` 语法错误，报的还是莫名其妙的错。

→ 替换内容里含 `$` 的时候，用函数式替换：`s.replace(old, () => newText)`，别传字符串。

---

## 2026-10-20 三个安全漏洞：默认值是最贵的决定

这次清 R-1 / R-2 / R-3，三条都是同一类问题：**代码写了「这是个开关」，但默认值是危险的那一侧。**

R-1 是验证码。`application.yml` 里写的是 `${VERIFICATION_MOCK_ACCEPT_ANY_CODE:true}` ——
冒号后面那个 `true` 就是默认值。也就是说，只要没人显式设这个环境变量（而绝大多数部署都不会设），
验证码校验就是「任意 4 位数字通过」。任何人都能用任意邮箱注册，然后走 `reset-password` 接管账号。

R-2 是三个演示账号。`AuthService.run()` 每次启动都无条件创建 `good` / `xiaoba` / `yuanhua`，
密码跟用户名一样。这不是「测试代码忘了删」——它是正经写在 `CommandLineRunner` 里的启动逻辑，
所以生产环境照样会执行。

R-3 最离谱：每次启动 `admin.setPasswordHash(encode(BOOTSTRAP_ADMIN_PASSWORD))`。
compose 里 `BOOTSTRAP_ADMIN_PASSWORD` 的默认值是 `Admin@123456`。所以哪怕管理员上线第一天就改了密码，
下次重启又变回 `Admin@123456`。**改密码这个操作在这个系统里是无效的。**

### 当时怎么想的

第一版我打算只改默认值：把 `true` 改成 `false`，本地 profile 再写回 `true`。这确实能解决 90% 的场景。

但我停下来想了一下：**「记得别在生产打开」这种约束，靠什么保证？**
答案是靠人记得。而人一定会忘 —— 尤其是半年后有个紧急问题，有人在服务器上 `export` 一堆环境变量调试，
顺手把 `VERIFICATION_MOCK_ACCEPT_ANY_CODE=true` 也带上了，然后再也没删。

打一条 WARN 也没用。启动日志几百行，没人看；就算看到了，也不会为了一个黄色警告停下来。

所以最后加的是 `config/StartupSafetyGuard.java`：非 `local/dev/test` profile 下，只要这两个开关有一个是 true，
**直接抛异常让进程起不来**。效果是：

```
java.lang.IllegalStateException: 拒绝启动：当前 profile=[] 不是开发环境，
但开启了危险开关 [app.verification.mock-accept-any-code=true]。...
```

这个决定的逻辑是：**一个静默的安全漏洞，比一个起不来的服务危险得多。**
服务起不来，五分钟内就有人来找你；漏洞静默存在，可能要等半年后某个陌生邮箱注册进来才发现。

### 为什么用 @PostConstruct 而不是 ApplicationRunner

第一版我把检查写成了 `ApplicationRunner`，跟 `AuthService` 一样。写完突然意识到不对：
`ApplicationRunner` 是容器初始化**完成之后**才跑的，那时候 `AuthService` 已经把演示账号和
重置过的管理员密码写进库了。检查跑在写库之后，等于没检查。

改成 `@PostConstruct`，在 bean 初始化阶段就拦住，早于任何 `ApplicationRunner`。
这种「执行顺序」的坑很容易漏，因为两个写法看起来都是「启动时跑一下」。

### R-3 真正的修法不是「加个判断」

我一开始想的是「那就在 setPasswordHash 前加个 `if (admin.getPasswordHash() == null)`」。
但读了一遍那段代码发现，它不只是改密码 —— 它把 nickname、avatar、level、xp、status 全部重写了一遍，
然后无条件 `updateById`。也就是说：**管理员在后台改的昵称和头像，也会在下次重启时被悄悄改回去。**

所以正确的修法是把整个块拆成两个分支：

- `admin == null`：首次创建，bootstrap 配置全部生效。
- `admin != null`：只补真正缺的字段（`publicUid` / `role` / `status`），用一个 `dirty` 标志决定要不要写库；
  密码、昵称、头像一律不碰。

验证方式我想了个比较硬的：BCrypt 每次 `encode()` 都会生成新的随机盐，所以只要重新编码过，
哈希串一定变。于是重启前后各查一次 `password_hash`：

```
BEFORE: $2a$10$H8E2fa0U3Wp1syVQRPIyw.3NFbR47lkpvZpiROOFr.ob8yKrerLv2  2026-10-20 09:08:12
AFTER:  $2a$10$H8E2fa0U3Wp1syVQRPIyw.3NFbR47lkpvZpiROOFr.ob8yKrerLv2  2026-10-20 09:08:12
```

完全一致 → 证明真的没重新写库。这比「登录还能成功」强，因为密码没变的情况下登录本来就会成功，
那种验证根本区分不出「没改」和「改成了同一个值」。

### 一个没绕过去的坑

验证演示账号登录的时候，我用 curl 发 JSON 一直返回 400 `VALIDATION_FAILED`。
查了半天以为是 DTO 字段名错了（`account` 还是 `email`），改成 `email` 还是 400。

真正原因是 **PowerShell 5.1 传给原生程序的参数会剥掉内层双引号**。
`-d '{"email":"..."}'` 传到 curl 手上变成了 `{email:...,password:...}`，不是合法 JSON，
于是 `HttpMessageNotReadableException` → 400。

这个坑以前在 smoke 脚本里踩过（当时的解法是 `--data-binary "@$file"`），这次又踩了一遍。
最后把 JSON 写进临时文件，用 `--data-binary $arg` 才通过。

→ **以后在 PowerShell 里给 curl 传 JSON，一律走文件，不要内联。**

### 还剩什么

R-1 其实只修了一半。默认值翻了、启动拦截加了，但**真实发邮件的通道还没接**。
现在 `mock=false` 的话，验证码只会被 `System.out.printf` 打到日志里，用户根本收不到。
所以严格说，R-1 现在是「安全但不能用」—— 要真正上线必须接 SMTP / Resend / SES。
这一点我写进 `docs/ISSUES.md` 了，免得下次以为已经完事。

---

## 2026-10-20 注册改成「随便填」——顺带发现密码长度是个假上限

之前注册要邮箱格式、密码至少 6 位、还要填昵称和 4 位验证码。自己测的时候要开一堆邮箱，
烦到不行。于是决定：开发阶段注册只要两个东西 —— **账号 + 密码**，其它全可选。

具体放宽了什么：

- `@Email` 去掉了。账号可以是任意字符串，`test1`、`测试号`都行。反正内部统一 `toLowerCase()` 之后判重。
- `nickname` 可选，不填就直接用账号本身当显示名 —— 反正账号是唯一的，昵称自然也唯一。
- `confirmPassword` 可选。界面上还是让填（防手滑），但接口层面允许省略，给 App 端留余地。
- `verificationCode` 在 mock 模式下**完全不需要**。判断条件是 `mockAcceptAnyCode == false` 时才校验，
  也就是只有真实环境才要求。
- 密码下限在 mock 模式下放到 1 位。`password: "1"` 能注册。

### 密码「不限长度」其实是个陷阱

我一开始想直接删掉 `@Size` 约束，让它真的不限长。写到 `passwordEncoder.encode()` 的时候停了一下，
因为想起来 BCrypt 有个 72 字节的输入上限。

去确认了一下：BCrypt 的算法只吃前 72 字节，超出的部分**静默丢弃**。不是报错，是丢掉。
后果是两个前 72 字节相同、后面不同的密码，会算出**同一个哈希**，都能登录同一个账号。
这个 bug 你测不出来 —— 除非专门拿两个超长密码去试。

所以最后没删上限，而是加了个显式检查：

```java
int bytes = password.getBytes(StandardCharsets.UTF_8).length;
if (bytes > 72) {
    throw new IllegalArgumentException(
        "密码太长：最多 72 字节（约 72 个英文字符或 24 个汉字），当前 " + bytes + " 字节");
}
```

**注意这里算的是字节不是字符。** 一个汉字在 UTF-8 里是 3 字节，所以 24 个汉字就到顶了。
如果按 `String.length()` 判断（那是 UTF-16 code unit 数），中文用户能填 72 个汉字 = 216 字节，
照样被截断，等于白检查。

宁可给一条明确的中文报错，也不要在背后悄悄截断 —— 截断是那种「上线三个月后才有人发现」的坑。

### 一个自己挖的坑

改 `AuthDtos.java` 的时候，我用字符串 `replace` 去换那几行 record 定义，跑完一编译报错。
看了下文件，那段文字一个字都没变。

原因又是 CRLF：文件是 `\r\n`，我 `old_string` 里拼的是 `\n`。而这已经是这个项目里第三次踩同一个坑了。
（前两次：改 `SecurityConfig` 静默不生效、改 `smoke.ps1` 把文件写成 LF 导致 PowerShell 变量算成空串。）

后面的改法固定成：先 `read` 拿到真实行，再 `split(/\r?\n/)` 按行操作，最后 `join("\r\n")` 写回。
不拼多行字符串，就不存在这个问题。

### 前端那边

`validateRegisterForm` 里原来把昵称和验证码都当必填。改成：昵称留空直接跳过、验证码填了才检查格式。

还有一个小 bug：`checkRegisterNickname()` 在昵称为空时 `return false`，而 `register()` 里是
`if (!(await checkRegisterNickname())) return;` —— 意思是「昵称为空」会被当成「有错误」直接中止注册。
改成 `return true` 才通。这种「返回值语义反了」的 bug 编译器管不了，只能靠读代码或者测出来。

界面文案也跟着改了：「绑定邮箱」→「账号」、`type="email"` → `type="text"`、
昵称 placeholder 改成「选填，默认与账号相同」、验证码 placeholder 改成「开发环境可留空」。

### 验证

smoke 从 29 项加到 33 项，新增 4 项专门盯这个：

- `register-minimal`：只传 `{email, password:"1"}`，确认 200 且昵称自动变成账号；
- `register-minimal-login`：拿刚建的号登录，证明真落库了；
- `register-duplicate`：重名必须 400；
- `register-longpassword`：73 字节必须 400，不能被静默截断。

另外手工试了中文账号 + 中文密码、`account` 字段别名、72 字节正好能过 —— 都对。

---

## 2026-10-20 把 notices 那一整套删了——先搞清谁在用，再动刀

项目里一直有两套内容模型：`posts`（新，前端在用）和 `notices`（最开始的「校园通知」版本）。
前端早就只调 posts 了，但 notices 从表到 Controller 全都还留着，19 个 Java 文件、5 张表。
每次改内容相关的东西我都要先花几分钟确认「这个类是干嘛的、还有没有人调」，成本高得离谱。

### 一开始想「直接删文件不就行了」

试了一下，编译不过。因为 notices 不是一个独立模块，它像藤蔓一样缠在 posts 那边：

- `AdminService.overview()` 统计的是 notices 的发布数；
- `UserService.stats()` 里的「已完成 / 临近截止 / 避坑」全是从 `user_notice_status` 和 `reminders` 来的；
- `CommentController` 的评论接口是挂在 `/api/notices/{id}/comments` 上的；
- `SecurityConfig` 白名单里有 `/api/notices/**`；
- `NoticeDtos` 里还藏着 `TagView` / `CategoryView`，而这两个被 `TaxonomyService` 用着。

所以第一件事不是删，是**查清楚引用关系**。我按类名一个个 grep，把「只有 notices 内部互相引用」
和「被 posts 侧共用」分成两类：

只有 notices 用 -> 删；被 posts 共用 -> 改。

具体到最有迷惑性的两个：

**`comments` 是两套共用的。** V1 建表时 `notice_id` 是 NOT NULL，V3 才 `MODIFY notice_id BIGINT NULL`
并加了 `post_id`。也就是说同一张表里躺着两种评论。这个必须保留，只把 `notice_id` 列摘掉。

**`NoticeDtos` 里有不属于 notices 的东西。** `TagView` / `CategoryView` 只是恰好写在这个文件里，
`/api/categories` 和 `/api/tags` 还在用。所以先把它们搬去新建的 `dto/TaxonomyDtos.java`，
再删 `NoticeDtos`——不然 taxonomy 那边会连坐编译不过。

### 删之前先数一下数据

删表是不可逆的，所以先查了行数：

```
notices 4 | notice_tags 13 | user_notice_status 0 | posts 31 | comments 26
```

notices 那 4 条就是 V2 种下去的演示数据，`user_notice_status` 一行都没有——说明「完成/避坑」
这两个功能**从来没人用过**，前端展示的永远是 0。确认没有真实数据，才敢 DROP。
删之前还是 `mysqldump --single-transaction --routines` 备份了一份（87 KB，几分钟的事，别省）。

### 外键的顺序坑

第一版 V11 写的是直接 `DROP TABLE notices`，报错：

```
ERROR 1217 (23000): Cannot drop table 'notices' referenced by a foreign key constraint
```

`comments.notice_id` 上有 `fk_comments_notice` 指向 notices。必须先摘外键、再删列、最后删表：

```sql
ALTER TABLE comments DROP FOREIGN KEY fk_comments_notice;
ALTER TABLE comments DROP INDEX idx_comments_notice;
ALTER TABLE comments DROP COLUMN notice_id;
DROP TABLE IF EXISTS notice_tags;   -- 这些也都有指向 notices 的外键
DROP TABLE IF EXISTS user_notice_status;
DROP TABLE IF EXISTS favorites;
DROP TABLE IF EXISTS reminders;
DROP TABLE IF EXISTS notices;       -- 最后才轮到它
```

顺带记一个：`DROP INDEX` 也要单独写。`idx_comments_notice` 是 `(notice_id, status, created_at)` 的
联合索引，删了列索引还留着会变成无效引用。

### 那三个恒为 0 的字段为什么没删

`UserDtos.UserStats` 里有 `completed` / `upcoming` / `avoidedRisks`，notices 没了它们就是摆设。
本来打算直接删字段，但停了一下——`AGENTS.md` 里自己立的规矩是 `/api/v1` **只做加法**，
删字段要升 v2。为了三个没人看的数字升一个大版本，不值。

所以留字段、置 0、在 Javadoc 里写清「因 notices 删除恒为 0，v2 时去掉」。
这样 Web/App 端的键名不用动，等真升 v2 的时候一起收拾。

**`favorites` 的处理不一样**：它原来是「通知收藏 + 帖子收藏」相加，现在只剩帖子收藏。
这个改法是**变准了**，不是变假了——`feed.ts` 里点赞/收藏时前端本来就在对这个数做 ±1，
以前那个 ±1 加在一个混合值上，其实一直差着通知收藏的基数。

### 验证

启动日志：`Migrating schema 'biecuoguo' to version "11 - drop notices domain"` ->
`Successfully applied 1 migration, now at v11`。清理后 23 张表，`comments` 只剩 11 列。

`mvn compile` 绿。smoke 33 项全过——注意这 33 项**一项都没覆盖 notices**（因为前端从来没用过），
所以「smoke 全绿」不能证明删对了，只能证明没删坏 posts 那边。真正的证据是编译通过 + 表结构对。

### 教训

1. **删旧东西之前，先把「谁在用」画出来**，别凭印象。这次有两个坑（comments 共用、
   NoticeDtos 里混着 taxonomy），全靠 grep 引用关系才提前发现。
2. **删表之前先数行数**。如果是「演示数据 4 条」和「真实用户数据 4 万条」，同样的代码，
   风险差五个数量级。
3. **`SecurityConfig` 白名单要跟着删**。白名单留着死路径不会报错，但会让人以为那个接口还活着。
4. smoke 覆盖不到的地方，「全绿」是假的安全感。得靠编译 + 看表结构。

---

## 2026-10-20 验证码：开关该管「怎么校验」，不该管「要不要校验」

上一轮为了自己建测试号方便，我把注册改成不用验证码了 —— 账号 + 密码两栏，一按就进。
当时觉得挺爽，测了几天发现不对劲：本地和线上跑的根本不是同一条调用链。

具体会漏什么：注册接口里原来有一步 `verifyCode(...)`，我给它包了个 `if (verificationRequired())`。
本地这个分支永远不进去，也就是说**本地从来没跑过「验码失败会怎么样」这条分支**。
等上线打开真实校验，报错文案、错误码、前端怎么显示这个错——全都没测过。
这种漏不叫「没测到细节」，叫「本地环境比线上少了一整个分支」。

### 改法：开关只管「怎么校验」

字段永远必填，`verifyCode()` 永远被调用，两种模式的差别全部收进 `VerificationService` 里：

```java
public void verifyCode(String email, String purpose, String code) {
    String normalizedCode = normalizeCode(code);   // 空值在这里就被拒，两种模式都一样
    if (mockMode()) {
        if (!normalizedCode.matches("\\d{4}")) { throw new IllegalArgumentException(...); }
        return;
    }
    // 真实模式：查库 -> 判过期 -> 比对 hash -> 标记 consumed
}
```

调用方（`AuthService.register` / `resetPassword`）现在只有一行，没有 if：

```java
verificationService.verifyCode(email, "register", request.verificationCode());
```

开发模式下 `requestCode()` 也直接 return，不写库不发信 —— 之前是「照常写一条随机码进库，
然后校验时无视它」，留下堆没用的行，还容易让人以为那行能用来登录。

### 接真 SMTP 时踩到的两个点

**第一个：`@ConditionalOnProperty` 对空字符串的行为。**
`spring.mail.host: ${SMTP_HOST:}` 这种写法，在 `SMTP_HOST` 没设的时候解析出来是空串。
我原以为 `MailSenderAutoConfiguration` 会因为 host 是空而不创建 bean，结果它照样创建了 ——
`@ConditionalOnProperty` 只判断「键存在不存在」，空串也算存在。
所以不能靠 `ObjectProvider.getIfAvailable()` 判断「有没有配 SMTP」，必须连 host 一起判：

```java
public boolean available() {
    return host != null && !host.isBlank() && mailSenders.getIfAvailable() != null;
}
```

要是只判 bean 在不在，就会得到一个「available() 返回 true、真发信时炸连接」的假阳性。

**第二个：反向的静默故障也要拦。**
之前 `StartupSafetyGuard` 拦的是「开发开关漏到生产」。这次多了一种方向相反的坑：
生产配成真实模式、但忘了配 SMTP。这时候进程起得来、接口返回 200、监控一片绿，
只有新用户发现验证码永远收不到——注册链路断了，没人知道。

所以 guard 里加了第二条：真实模式 + 没有 SMTP = 直接拒绝启动。
注意这条**不区分 profile**，因为它不是「开发开关」，是纯粹的配置缺失。

### 为什么把验证码规则写死成 4 位数字

开发模式也要求是 4 位数字（`\d{4}`），不放过空串、不放过 'abc'。
理由很实在：如果本地允许乱填，前端 `maxlength="4"`、`inputmode="numeric"` 这些东西就没人验证了。
限制成和线上一样，UI 上的坑才会在本地暴露出来。

### 顺带

smoke 加了第 34 项 `register-nocode`：不传验证码必须 400。
这一条是这次改动的**核心断言**——它钉住的不是「验证码能过」，而是「不能不填」。
（另外三项老的注册检查也都补了 `verificationCode = '1234'`，否则它们的 400 会来自
「缺验证码」而不是它们本来要测的东西，变成假通过。）

### 加个 mail 依赖，顺手把 /actuator/health 弄挂了

编译通过、启动也正常，`/actuator/health` 却一直 503。日志里翻到：

```
WARN o.s.b.actuate.mail.MailHealthIndicator : Mail health check failed
jakarta.mail.AuthenticationFailedException: failed to connect, no password specified?
```

`spring-boot-starter-mail` 一进来，Spring 就自动注册了 `MailHealthIndicator`，
每打一次 health 它就去连一次 SMTP。而我 `application.yml` 里写的是
`host: ${SMTP_HOST:}` —— 没配的时候这个键**仍然存在**，值是空串，
所以 `@ConditionalOnProperty` 照样成立，`JavaMailSender` 被创建，健康检查也被注册了。

杀伤力不在本地：容器 healthcheck 打的就是 `/actuator/health`，它一 DOWN 部署就判活失败，
可应用本身明明在跑 —— 排查方向会被带偏好一阵。

改法：`management.health.mail.enabled: false`。
「有没有配 SMTP」该由 `StartupSafetyGuard` 在启动时判一次，
不需要一个每次请求都去连 SMTP 的探针。

### smoke 全红，其实是 PowerShell 按 GBK 解了 curl 的输出

34 项里 15 项 FAIL。诡异的是 `register` 明明是 200，紧接着 `me` 就是 401 —— 说明 token 没拿到。
手动 curl 注册接口，body 完全正常，token 就在里面；用 Node 直接打也是 200 + token。
所以问题在 PowerShell 这一侧。

写了个最小复现，把 `$r.body` 的长度和内容打出来：

```
bodylen=994
PARSE-FAIL: 无效的 JSON 格式... (462): {"code":"OK",...,"major":"未设置专
```

打出来是 `未设置专` 后面跟一个被吃掉的字符。"专业"的"业"是 E4 B8 9A，
按 GBK 解码时 E4B8 算一个字符、剩下的 9A 是非法单字节被吃成 `?` —— 
而且它**连后面那个引号一起吞了**，JSON 字符串没闭合，整段直接非法。

根因：PowerShell 5.1 捕获原生程序（curl.exe）的输出时用的是 `[Console]::OutputEncoding`，
中文 Windows 下默认是 OEM 代码页 936。UTF-8 的响应体被当 GBK 解，不只是显示乱码，会破坏结构。

为什么之前一直没炸：之前那次是在 UTF-8 代码页的终端里跑的。这个 bug 一直在，
只要响应体里出现中文（`user.major = 未设置专业` 就够）就会中招。

修法：脚本开头把 `$OutputEncoding` 和 `[Console]::OutputEncoding` 都设成 UTF-8，
不再依赖当前控制台的代码页。

## 2026-10-20 数据库地址这种东西，就不该有默认值

R-4 在清单上躺了很久。一直没动的理由说出来挺没说服力的：本地有 `.env` 覆盖，
跑起来从来没出过事，看日志也看不到任何异常。

真正把这个当成事，是因为想清楚了一个问题：**出问题的场景不是本地，是任何一次没带 .env 的启动。**
`application.yml` 和 `application-local.yml` 原本都写着
`jdbc:mysql://115.190.3.204:12306/biecuoguo`，那台机器已经停服了。现在连不上，所以“看起来没事”。

但“连不上”不是设计出来的安全，是运气。如果那台机器的 12306 哪天被放开——
云安全组改错一次、机器转手给别人、或者干脆只是网络通了——
照默认值启动的后端会做两件事：**对着一台陌生库跑 Flyway**，然后跑 `AuthService` 的启动初始化。
前者会改别人的 schema，后者会往里写账号。这已经不是“连错库”，是“动了不该动的数据”。

### 中间想错的两个方向

第一个想法是把默认值改成一个**能连通的**地址，让它“开箱即用”。
这等于换个地方埋雷——默认值越“好用”，越没人会去显式配置它。

第二个想法是在启动时加连通性检查，连不上就拒绝启动。
方向对，但位置错了：`StartupSafetyGuard` 是给“危险开关”用的，
而“数据库地址”这件事根本不该有默认值来让人猜。**检查默认值，不如取消默认值。**

### 最后定的三档

```text
application.yml        -> 127.0.0.1:3306     # 本机标准端口，连不上就是本机没有
application-local.yml  -> 127.0.0.1:12306    # 对上 docker compose --profile local-db 的宿主机映射
docker-compose.yml     -> mysql:3306         # compose 网络里的服务名
deploy-app.sh / deploy-ip.sh -> 没有默认值，SPRING_DATASOURCE_URL 缺失直接退出
```

前三个是“本机”或“同一个网络里的自己人”，错也错在自己家里；
最后两个是部署脚本，它们连“自己家”在哪都不知道，**猜就是错的**。

顺手把两个脚本里 `Cannot connect to MySQL at ...` 的提示也改了。
原来那段写的是「Check these on server A」，还教人用 `./deploy-mysql.sh` 起 MySQL——
双机架构早就废了，照着提示排查会一路撞墙。现在换成了不依赖拓扑的五步检查。

### 最该改的其实是 README

README 里原本有一句「数据库连接默认就是服务器 A」，底下直接跟一段可以复制的 env 块，
里面是那个已经停用的 IP。**文档教人复制一个死地址**，比代码里的默认值更危险——
因为复制的人不会怀疑它。现在改成“必须显式配置”+ 本地示例。

改完 `mvn package` 和 `scripts/smoke.ps1` 都还是 34/34，本地链路一点没动。

## 2026-10-20 匿名访客看的东西，居然是「随便挑一个人」决定的

D-3 这条在清单上挂了很久，一直是「知道有问题但没空管」。真正动手是因为想加一个新用户注册，
翻到 `AuthService` 里 `user.setSchoolId(1L)` 写了四遍，然后想起访客那边也有个 `1L`——
再往上翻，发现访客那条路径根本不是 `1L`：

```java
private User defaultViewer() {
    return userMapper.selectList(new LambdaQueryWrapper<User>()
            .eq(User::getStatus, "active").last("limit 1"))
            .stream().findFirst().orElse(null);
}
```

`limit 1` 没有 `order by`。也就是说**未登录用户看哪批帖子，取决于 MySQL 先返回哪一行**。

### 为什么一直没被发现

因为库里所有账号的 `school_id` 都是 1。随便挑谁，挑出来的学校都是 1，
所以「随机」在数据上表现为「恒定」——看起来完全正常。

这个 bug 的触发条件很苛刻：得有个用户的 `school_id` 不是 1 且 id 排在最前面。
一旦真的出现（比如给某个学院开了独立账号），表现会是「未登录首页突然少了一堆帖子」，
而且**重启后可能又好了**——这种 bug 最难查，因为没有稳定复现路径。

### 改法：把「没有学校」这件事变成一个有确定答案的状态

我一开始想让访客看到**全部**帖子。想了想不对：这是校园 PO，混着不同校区的帖子比缺帖子更糟。
访客应该有确定的一个学校可看，那就把「默认学校」显式化：

```java
Long schoolId = viewer == null || viewer.getSchoolId() == null
        ? defaultSchoolId() : viewer.getSchoolId();
return wrapper.eq(Post::getSchoolId, schoolId);
```

`defaultViewer()` 直接删掉，三个调用点全部改成 `currentUser == null ? null : ...`。

顺带把 `AuthService` 里四处 `setSchoolId(1L)` 也收进同一个配置 `app.default-school-id`。
它们本来就是同一个概念（「这个人属于哪个学校」的默认值），分在五个地方写死纯属没想过。

### 怎么证明这个配置真的被读到了

改完这种事最容易假验证：启动成功、接口 200、数据看着对——但**可能根本没读那个配置**。
所以我没用默认值测，而是把它设成 `999`（库里不存在的学校），预期匿名 feed 变成 0 条。

第一次试就翻了两个坑：

**坑一：环境变量压不过 profile yml 里的字面值。**
我用 `APP_DEFAULT_SCHOOL_ID=999 BOOTSTRAP_SEED_DEMO_DATA=false` 起，结果启动直接失败：

```
java.sql.SQLIntegrityConstraintViolationException: Cannot add or update a child row:
a foreign key constraint fails (`biecuoguo`.`users`, CONSTRAINT `fk_users_school`
FOREIGN KEY (`school_id`) REFERENCES `schools` (`id`))
```

这说明 999 确实被用到了（不然不会撞外键），但也说明 `BOOTSTRAP_SEED_DEMO_DATA=false` **没生效**——
演示账号还是被建了。原因是我把它塞在 `application.yml` 的 `${...}` 占位符里，
而 `application-local.yml` 有一行字面的 `seed-demo-data: true`，那份文件优先级更高，
占位符根本轮不到。要压过它只能用命令行参数 `--app.bootstrap.seed-demo-data=false`。

**坑二：`school_id` 是有外键的。**
这条其实是个有用的副产品——它证明这个配置不能乱填。设成不存在的学校，
启动时建号就撞 `fk_users_school`。这算 fail fast，比悄悄写进去一个孤儿 school_id 好。
现在 AGENTS §4 和 §8 都写了这一条。

用命令行参数重测：`--app.default-school-id=999 --app.bootstrap.seed-demo-data=false`，
结果 feed 0 条、四个板块计数全 0、游标分页 0 条 `hasMore=false`，三条读路径全部跟着配置走。

### 一个顺带的教训

要验证「配置有没有生效」，别用默认值测。把配置改成一个**必然产生可见差异**的值，
再断言那个差异。这次如果我用默认的 1 去测，改前改后一模一样，什么问题都发现不了。

## 2026-10-20 JWT 的签名密钥，一直明明白白写在仓库里

这条在清单上被归成 Q（质量项），排在最后。翻配置的时候才反应过来它其实是 R：

```yaml
# application.yml（改之前）
jwt:
  secret: ${JWT_SECRET:change-this-secret-to-a-very-long-random-string-32-plus}
```

`.env.example` 里是 `change_me_to_a_long_random_secret_at_least_32_chars`，
`docker-compose.yml` 里是 `please-change-this-to-a-very-long-random-secret-32`。
三个地方三个值，但**全都是公开的**，而且 `docker compose up` 不配 `JWT_SECRET` 就会用它们。

### 为什么一直没当成事

因为它长得像占位符。带 `change` / `please-change` 字样，看上去已经提醒过人了——
仿佛「写了提醒」本身就等于「别人会去改」。

但占位符写在配置文件里就是**生效值**，不是注释。真正的后果是：
知道这个字符串的人可以自己签一个 `{"sub":"5","role":"ADMIN"}`，
然后 `Authorization: Bearer <伪造>` 直接进后台。
不需要注册，不需要验证码，**服务器也不会有任何一条异常日志**——
它认为这就是一个合法 token。

### 顺手发现的第二个问题

`security/JwtService.java` 里有这么一段：

```java
if (secret.length() < 32) {
    secret = secret + "0".repeat(32 - secret.length());
}
```

为了防止 JJWT 抛 `WeakKeyException`，它把短密钥补 `0` 凑到 32 字节。
一个 3 位密钥被补成 32 字节，看起来合规了，实际强度还是 3 位。
这种「补齐」比报错危险得多——报错会逼你改配置，补齐只会让你以为没事。

### 一个想过的方案：启动时自动生成密钥

第一反应是「干脆别要配置了，启动时随机生成一个」。听起来最省事。

问题是重启就换密钥，所有人当场被登出；多实例部署时每个实例的密钥还不一样，
A 实例签的 token 到 B 实例直接验不过。随机生成的密钥只适合「进程内临时用」，
不适合一个要跨重启、以后可能跨实例的会话系统。

所以结论反过来：**宁可启动失败，也不要悄悄用一个能用的密钥。**

### 改了什么

```text
application.yml        secret: ${JWT_SECRET:}                       # 空默认值
application-local.yml  secret: ...local-dev-only-jwt-secret...      # 只有本地有值
docker-compose.yml     JWT_SECRET: ${JWT_SECRET:?...必须显式配置}   # 缺失时 compose 直接报错
.env.example           JWT_SECRET=                                   # 空，附 openssl 生成命令
```

`StartupSafetyGuard` 加了第 0 条检查，**不区分 profile**：空值、三个历史示例值、
短于 32 字符、非开发环境用了本地那个专用值——四种情况一律拒绝启动。
异常信息里刻意不打印密钥本身，因为这段文字会进日志。

`JwtService` 的补 `0` 改成抛异常：密钥不合格应该在启动时炸，不该在签名时悄悄修补。

### 怎么验的

四条拒绝路径一条条跑，看退出码和文案对不对（空值 / 示例值 / `abc123` / 非开发 profile + 本地值），
都是 `exit=1`。然后正常路径：`local` 启动 → 登录拿 token → `/auth/me` 返回 `ADMIN`
（token 真的被签发并校验了，不是绕过去的）→ `smoke.ps1` 34/34。

`docker compose config` 那一步没验成——这个 shell 里 compose 插件没挂上，
`docker compose` 直接报 unknown command。`:?` 的语义是 Compose 规范里写死的，
但「确实会拦下」这件事得等有 compose 的环境再确认一次。

## 2026-10-20 上传接口说「只支持图片」，其实只信了客户端那句话

`MediaService` 原来的校验是这样的：

```java
String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
if (!contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
    throw new IllegalArgumentException("目前只支持图片上传");
}
```

`MultipartFile.getContentType()` 返回的是**请求头里的 Content-Type**，一个客户端随便写的值。
所以这段代码的实际含义不是「只接受图片」，而是「只接受请求头里写了 image/ 的东西」。

具体能干嘛：把一个 `.html` 或者带 `<script>` 的 `.svg` 用 `curl -F 'file=@x.html;type=image/png'` 传上来，
校验通过，文件原样进 MinIO，`media.content_type` 也存成 `image/png`，
然后 `/api/media/{id}/content` 用这个 Content-Type 把它吐回浏览器。
浏览器遇到 `image/png` 不会执行里面的脚本，所以这一步还不至于立刻 XSS；
但只要哪天为了「支持 SVG 图标」把类型放宽到 `image/svg+xml`，同源打开就是执行任意 JS。
更别提还顺便变成了一个任意文件的免费图床。

### 改法：认文件头，然后所有东西都以识别结果为准

加了 `service/ImageSignature.java`，读头 12 个字节，只认四种真实图片：

```text
JPEG  FF D8 FF
PNG   89 50 4E 47 0D 0A 1A 0A
GIF   "GIF8"
WebP  "RIFF" + 4 字节长度 + "WEBP"
```

认出来之后，**扩展名和 Content-Type 都用识别结果**，不再用 `file.getOriginalFilename()` 和请求头。
这样「文件名是 a.png、内容是 HTML」这种情况根本进不了下一步，因为它压根认不出来。

### 顺手砍掉的两个东西

**上限 20MB → 5MB。** 手机上随手拍的照片三四 MB，再大就该客户端压缩了；
20MB 更像是「懒得想」填的数字。`MEDIA_TOO_LARGE` 的文案也跟着改了。

**`purpose` 是拼进对象 key 的。** 之前直接 `firstNonBlank(purpose, "post")`，
客户端传什么就拼什么。对象存储没有路径穿越问题（key 是扁平字符串），
但传个超长或者带斜杠的值能把目录结构搞得很脏。现在只允许 `[A-Za-z0-9_-]{1,20}`，不合格一律当 `post`。

### 验证

四条一起测，因为「真图片能传」和「假图片被拒」必须同时成立，只测拒绝那一侧可能是校验写死了一律拒绝：

```text
真 PNG                    → 200  url 结尾 .png，contentType image/png
HTML 冒充 image/png        → 400  只支持 JPEG / PNG / GIF / WebP 图片（按文件内容识别，改扩展名没用）
SVG 冒充 image/png         → 400  同上
6MB PNG（头是真的）        → 413  图片太大了，请换一张 5MB 以内的图片
```

最后一条是 Spring 的 multipart 上限先拦的（`max-file-size: 5MB`），
不是 `MediaService` 里那个字节数检查——两个都在，前者是实际生效的那道，后者是纵深防御。

### 还没做的

没有校验像素尺寸，所以「5MB 的解压炸弹」理论上还是能传上来。
服务器自己不解码，炸弹炸的是浏览器，所以优先级不高；真要防得按格式读 IHDR/帧头，
而 WebP 没有内置的 ImageIO reader，得手写解析——现在不值得。

## 2026-10-20 加了限流，然后发现「按 IP 限流」在代理后面等于给全站上锁

要挡的东西很明确：验证码接口每发一封信都是真金白银，而且能拿去轰炸别人的邮箱；
登录接口没有上限就是无限撞库。于是写了个固定窗口限流，按「来源 IP + 接口」计数。

第一版写完准备收工，突然想到一个问题：**部署的时候后端前面站着 Caddy。**

Spring 的 `request.getRemoteAddr()` 拿的是 TCP 对端的地址。整栈部署时，
所有请求的 TCP 对端都是 Caddy 容器——不管是一百个人还是一万个人，
`getRemoteAddr()` 全都返回同一个 IP。也就是说：

```text
登录限流 20 次 / 5 分钟 / IP
→ 实际上是「全站所有用户加起来 5 分钟只能登录 20 次」

→ 一个人拿脚本刷 20 次错误密码，全站用户 5 分钟内登不进来
```

这不是限流，这是给自己装了个开关。而且它**只在部署后出现**：本地开发连的是
`localhost:8080`，remoteAddr 就是 127.0.0.1，一切正常，测试全绿。

### 所以要么读 X-Forwarded-For，要么别用 IP 限流

Caddy 会写 `X-Forwarded-For`。但它是个普通请求头，客户端可以自己伪造——
如果无脑信任，攻击者每次换一个假 IP，限流就完全失效。

结论是必须知道「我前面到底有没有代理」。这不是能从代码里推断出来的信息，
只能配置：

```yaml
app:
  rate-limit:
    trust-forwarded-for: ${RATE_LIMIT_TRUST_FORWARDED_FOR:false}
```

- 默认 `false`：用 `remoteAddr`。适合「直接暴露后端」或本地开发。
- `true`：取 `X-Forwarded-For` 第一段。**只有在前面确实有代理、且外部连不到后端时才成立**。

`docker-compose.yml` 里默认 `true`，因为那个栈一定带 Caddy 且后端没有对外端口。
这个默认值写在 compose 而不是代码里，是因为「有没有代理」只由部署形态决定。

### 为什么不用令牌桶

想过 Bucket4j（现成的，还有分布式实现），但当前需求是「别让人拿脚本刷」，
不是「精确整形流量」。固定窗口十几行、无后台线程、无额外依赖；
代价是窗口边界可能放过接近两倍的请求——比如 5 分钟窗口，
在 4:59 打满 20 次、5:01 再打满 20 次，两分钟内实际过了 40 次。可以接受。

真正该上 Bucket4j/Redis 的时刻是「多实例部署」，那时进程内的计数器本来就不成立（Q-4）。

### 数值怎么定的

第一版定得偏紧（验证码 5 次/10 分钟），然后意识到会打到自己的脚：
`smoke.ps1` 一次要发好几个验证码，连着跑三遍就把窗口用完了，
**而限流窗口是 10 分钟——等于本地开发被自己锁十分钟**。

最后定成「够挡脚本、够本地折腾」的量级：登录 30/5min、验证码 20/10min、
注册 20/1h、改密 20/10min、上传 30/5min、发帖 20/5min。

### 验证

smoke 34/34 全绿（说明没误伤正常链路），然后对着验证码接口连发 30 次：

```text
第 20 次开始 429，Retry-After: 595
{"code":"RATE_LIMITED","message":"操作太频繁了，请 595 秒后再试","data":null,"requestId":"..."}
别的接口仍然 200，登录仍然 200
```

那两个「仍然 200」是重点：限流必须只打中目标接口，不能因为规则写错把整站罩住。

### 没进 smoke

想过把「打 20 次看会不会 429」加进 `smoke.ps1`，但那样每跑一次就把窗口占满，
十分钟内没法再跑——用一个检查项换来十分钟不可用，不划算。
这条目前只能手动验，记在 ISSUES 里。

## 2026-10-20 验证 CI 的脚本，先在 Git bash 里被自己写的检查坑了一次

加 `.github/workflows/ci.yml`，其中有个 `files` job 干三件小事：
`.sh` 必须是 LF、`.ps1` 必须是 CRLF（PowerShell 5.1 解析 LF-only 会静默出错）、`.env` 不能入库。

workflow 写完没法在本机跑 GitHub Actions，但思路可以降级验证：本机有 Git for Windows 的 `bash.exe`，
把那两段脚本抠出来直接在仓库里跑一遍，总比 push 上去等结果强。

结果 `files` job 报：

```text
FAIL missing CRLF in scripts/smoke.ps1
```

可我明明记得那个文件是 CRLF 的（之前专门转过）。用 node 读字节确认：223 个 CRLF、223 个 LF、纯 LF 为 0——**文件是对的**。

### 问题在 grep，不在文件

MSYS2 版的 `grep` 默认按文本模式打开文件，会把 `\r\n` 读成 `\n`。
所以 `grep -q 

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r' file` 永远匹配不到东西——**在 Git bash 里，任何文件看起来都是 LF**。

实测：

```text
grep -q  

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r' scripts/smoke.ps1  → 不匹配
grep -qU 

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r' scripts/smoke.ps1  → 匹配（-U/--binary：别做换行转换）
```

Ubuntu 上的 GNU grep 没有这个文本模式转换，同一条命令在 CI 里是准的——
但「本地准不了」意味着我以后在 Windows 上根本没法预检这条规则。加 `-U` 两边就一致了。

### 顺带发现的第二件事：Linux runner 上 checkout 出来的 .ps1 是 LF

`.gitattributes` 原来只有一句 `*.sh text eol=lf`。`.ps1` 没规则，`core.autocrlf=true` 的机器上
工作区是 CRLF、索引里是 LF；**而 Linux runner 默认 `autocrlf=false`，checkout 出来就是索引里的 LF**。
也就是说：那条 CRLF 检查如果在 CI 里跑，即使逻辑写对了也会红——因为 CI 上的文件真的是 LF。

补了一句，让 checkout 出来的一定是 CRLF：

```gitattributes
*.ps1 text eol=crlf
```

### 另一处「我以为的接口」

冒烟断言里原本写 `GET /api/v1/posts/999999` 期望 404 + `POST_NOT_FOUND`，
实际返回 `405` + `VALIDATION_FAILED / 不支持的请求方法`——因为 `PostController` 上根本没有 `GET /{id}`，
只有 `POST`，所以这个路径在 Spring 眼里是「方法不允许」，不是「资源不存在」。

换成 `GET /api/v1/posts/999999/comments`（这个 GET 是存在的），返回才真是 `404` + `NOT_FOUND`。
顺带发现业务码不是 `POST_NOT_FOUND` 而是更笼统的 `NOT_FOUND`（文案「PO 不存在」）。

**教训**：写断言的时候不能凭印象写路径，得先 `curl` 一遍看真实返回，再把输出钉进断言。

### 现在的状态

`files` job 三条全过，`smoke` job 六条断言在本地 Git bash 下全过（`CI-SMOKE-PASS`）。
但 workflow 本身还没在 GitHub 上真正跑过一次——runner 环境、MySQL service 容器、
`setup-java` 的缓存放行都得等第一次 push 才知道。
 `` 是匹配位置之前的部分，**`

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。` 是匹配位置之后的部分**。

而我要写进去的正是 bash 的 ANSI-C 引用 `

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。\r'`——开头那个 `

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。` 被当成了「把剩下的整个文件插进来」。
于是文件从第 155 行开始被截断又拼接，YAML 结构彻底坏掉。

Python 的 `str.replace` 没有这个坑，所以之前用 Python 改文件一直没出事——**换到 JS 就踩了。**

### 以后怎么改 YAML（或任何含 `

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。` / `
---

## 怎么写这篇文档` 的文件）

1. 能用整文件重写就别用 `replace`：这次最后就是把 `ci.yml` 整个重新生成一遍，一次到位。
2. 非要用 `replace`，第二参传**函数**：`y.replace(a, () => b)`——函数返回值不会被当模板解释。
3. 改完跑一遍 `actionlint`。它能在 push 之前抓到这类「GitHub 才不认」的错，比等 Actions 快 10 倍。

### 顺带记住的两个事实

- **run 出现但 `total_count == 0`、`created_at == updated_at`** = workflow 解析失败，先怀疑文件本身，
  不要怀疑 runner、权限、service 容器。
- 这种 run **没有 logs**，所以别去 `runs/{id}/logs` 找原因，本地 lint 才是唯一快捷路径。

修好之后：`actionlint` 干净，PyYAML 四个 job 正常，把 `run:` 块从 yml 里抽出来丢进 Git bash 跑
（`files` 三条 + `接口冒烟` 六条）全过，再 push。

## 2026-10-20 进程内状态：先写了个「两个实例」的测试，再决定要不要 Redis

Q-4 的原话是「`TokenBlacklistService` / `PresenceService` 是进程内 `ConcurrentHashMap`，多实例部署后失效」。
问题是我手上只有一个实例，**「多实例会坏」这件事本身是没法用单实例测出来的**。

所以先别改代码，先把 bug 复现出来：同一个 jar 起两个进程（8080 / 8081，连同一个 MySQL），

```text
登录(A) 200 → 拿 token 在 B 上调 /me → 200
在 A 上退出登录 → 同一 token 在 B 上调 /me → 200   ← 离谱但确实如此
```

限流同理：狂刷 A 的登录接口刷到 429，B 上同一个接口还是 401（正常校验密码），也就是「各拦各的」。

这两个现象说明：**「A 登出」只是把 A 的内存改了一下**。生产上一旦起两个副本（或者以后加滚动更新），
用户在被拉黑之后仍然能用旧 token 访问一半的请求——取决于打到哪个实例。这是个隐蔽到极点的安全问题，
因为它只在多实例下出现，本地怎么点都正常。

### 改法：把三样状态抽成接口，实现可切换

新增 `com.biecuoguo.state` 包，三个接口各一对实现，由 `app.state.backend`（`STATE_BACKEND`）选：

| 接口 | memory | redis |
|---|---|---|
| `RevocationStore` | `ConcurrentHashMap` | `unipo:revoked:<token>`，TTL = 剩余有效期 |
| `PresenceStore` | `Map<Long, Instant>` | `unipo:presence:<uid>`，TTL 25 秒 |
| `RateLimitStore` | 固定窗口计数器 | `INCR` + 第一次 `EXPIRE` |

调用方（`TokenBlacklistService` / `PresenceService` / `RateLimitService`）签名一个没动，
所以业务代码、过滤器、Controller 全都不用改——这也是为什么值得先抽接口再选实现。

**默认仍然是 memory**：单实例够用，不该为了「以后可能多实例」让所有部署都背上 Redis 依赖。
要共享就显式 `STATE_BACKEND=redis`，并且启动时 ping 一下，连不上直接拒绝启动。
（不 ping 的话会非常难查：进程起得来、接口全 200、只有「退出登录不生效」——因为 Redis 调用抛的异常
在过滤器里被吞成了 401。）

### 三个失败语义是故意不一样的

- **拉黑查询失败 → 按「已撤销」处理。** 宁可多踢一次让用户重新登录，也不能让一个已经退出的 token 继续可用。
- **限流计数失败 → 放行（fail-open）。** 限流是挡脚本的手段，不是安全边界；
  Redis 抖一下把全站打成 429 才是真事故。这个选择写在代码注释里了，别「修复」成 fail-close。
- **在线状态失败 → 按离线处理。** 少显示一个绿点，无所谓。

### 验证（同一个脚本跑两遍）

```text
===== MEMORY 模式：两个实例 =====
退出登录(A) 200 → 同一 token 在 B 上：200   ← bug 复现
限流：A 触发 429，B 同一接口 401（各算一份）

===== REDIS 模式：两个实例 =====
退出登录(A) 200 → 同一 token 在 B 上：401   ← 修好了
限流：A 触发 429，B 同一接口 429（共用一个桶）
```

顺带在 Redis 里看到 `unipo:presence:5` 和 `unipo:rl:login|127.0.0.1|5974884` 两个 key，
key 里那个数字是固定窗口编号——这样 memory 实现靠它分桶，redis 实现靠它保证下个窗口一定换新 key，
万一 `EXPIRE` 丢了一次也不会写出一个永不过期的计数器。

### 留了两个尾巴

1. 默认 memory，多副本要手动改——**这是刻意的**，但必须在部署文档里写清楚，否则会静默中招（AGENTS §8 已记）。
2. refresh token 轮换依然没做。现在有了共享拉黑表，技术上可以做轮换了，但轮换还要求「同一标签页之外的并发刷新」
  不被误伤，前端单飞刷新只能管住一个页面。等真的要做多设备管理时再一起设计。

## 2026-10-20 第一次跑「前后端类型契约」就抓到 UserProfile 少了 10 个字段

Q-6 的原话是「`types.ts` 手写，与后端 DTO 已开始漂移」。但「已开始」是估的——
没人知道具体漂了几个字段，因为漏掉的字段在前端根本不报错，只是永远是 `undefined`。

所以先做生成：`npm run gen:api`（`openapi-typescript`）+ 后端 `/v3/api-docs` -> `src/api-types.ts`。
然后写 `src/api-contract.ts`，用类型断言要求手写的 `types.ts` 和生成的类型字段名、字段类型完全一致。

### 断言本身先错了一次

第一版直接拿两边做双向 `extends`，结果 13 个类型里 11 个报错。看着像「全线漂移」，其实是生成类型的特点：

- **所有字段都是可选的**——springdoc 只按 `@NotNull` 标 required，我们的 record 没有校验注解；
- **枚举全退化成 `string`**——`role` / `board` / `sourceType` 在后端就是普通 String，生成不出字面量联合。

于是 `{ level: number }` 和 `{ level?: number }` 互相不可赋值，`'USER' | 'OPERATOR'` 和 `string` 也是。
改成「先抹平再比」：字面量联合 -> 基础类型、可选 -> 必填、可空 -> 去空，只比结构和字段类型。
这样既不误报，又能抓住真正会出事的两类改动：**字段名变了、字段类型变了**。

### 抹平之后，剩下的报错是真的

`UserProfile` 红了，另外 `PublicProfileView` / `CheckInView` / `AuthResponse` 跟着红（它们内嵌 `UserProfile`）。
对比两边字段名，后端多出 10 个，前端一个都不认识：

```text
goalTags, interestCategories, preferUrgent, preferHighRisk, preferSameMajor,
emailNotificationEnabled, browserNotificationEnabled, remindBeforeHours,
createdAt, lastLoginAt
```

这些字段后端 `UserProfile.from()` 一直在返回（`/auth/me` 实测有值），前端只是从来没声明过。
换句话说：以后做「通知设置」「偏好设置」页面时，会以为是后端没接口，其实是类型里看不见。
这就是「漂移」最难受的地方——它不报错，只是让你以为没有。

顺带发现文档里写的路径是 `frontend/src/lib/types.ts`，实际文件在 `frontend/src/types.ts`（`lib/` 里只有 api/format 那几个）。
已经一起改了。

### 为什么没把 types.ts 整个换成生成类型

技术上可以，`components['schemas']['PostView']` 直接就能用。但换完会丢掉两样东西：

1. **必填性**：全可选意味着 `post.author.nickname` 也要写 `?.`，组件里到处是空判断；
2. **枚举收窄**：`switch (post.board)` 没有穷尽性检查了，后端加一个 board 前端不会提醒。

生成的类型当「事实来源」用来对账，手写的类型当「业务视图」用来写代码，两边用断言钉住——
这是取舍后的选择，不是没做完。真要做全生成，得先给后端 DTO 补 `@NotNull` / `@Schema(enumeration=...)`，那是另一件事。

### 验证：故意改坏两次

```text
删掉 types.ts 里的 remindBeforeHours  -> build 红（UserProfile 及其嵌套类型）
把 AuthorView.level 改成 string       -> build 红（AuthorView / PostView / CommentView 连带报错）
恢复                                 -> build 绿
```

CI 的冒烟 job 里也加了一步：后端起着的时候重新生成一遍，`git diff --exit-code` 比对仓库里的 `api-types.ts`。
这样「后端改了 DTO 但忘了 gen:api」会在 CI 红，而不是等前端在真机上发现字段是 undefined。

---

## 2026-10-20 补上第一套单测和 eslint 基线（以及两个自己写错的断言）

CI 四个 job 早就绿了，但没有一个测试：`mvn -B -DskipTests package` 里 `skipTests` 就是全部测试。
「CI 绿」只证明代码能编译、能起来、接口能通，不证明 JWT 的两种 token 真的不能混用。

### 先测纯逻辑，不碰数据库

CI 的冒烟 job 已经有一个真 MySQL service，单测再套一层 Spring 上下文只会更慢（每次 8-10 秒起步）。
所以这 22 个测试全部是纯 JVM：`JwtService`、`ImageSignature`、`AppTime`、`RateLimitService`、三个内存 store。
它们跑完 0.5 秒，本地改一行就能立刻知道有没有破坏那条安全边界。

### 坑一：`expected: <webp> but was: <image/webp>`

第一次跑 `mvn test` 就红了，报的是 `ImageSignatureTest.recognisesSupportedFormats`。
第一反应是「签名识别写错了」——去翻 `ImageSignature.detect()`，其实是好的。
是我自己的断言写错：`assertEquals("webp", ...contentType())`，前面那个应该是 `extension()`。
教训：**测试红了先看断言，再看被测代码**。这次差一点就把一个没问题的类「修」坏。

### 坑二：`AppTime` 的时区是 static volatile

`AppTime` 做成静态工具是因为 DTO 的 `from()` 是静态工厂、注入不进去，构造函数由 Spring 调一次写进静态字段。
测试里 `new AppTime("UTC")` 会改全局，于是「UTC 的用例」会把「Asia/Shanghai 的用例」一起带歪。
加了 `@AfterEach` 复位成 `Asia/Shanghai`——这类「为了可测性给静态状态留后门」的做法，
如果不复位，后面的用例会以一种完全不相关的方式失败。

### eslint 只开了一小撮规则

没有直接 `extends recommended`：项目代码是手写风格，一次上全量会冒出几百条纯风格告警，
那种 PR 除了噪音什么都得不到，最后的结果一定是「加个 eslint-disable 关掉」。

现在只开会让运行行为出问题的：`no-debugger` / `no-var` / `prefer-const` / `eqeqeq(smart)` /
`no-duplicate-case` / `no-unreachable` / `no-unsafe-finally`，`no-await-in-loop` 只 warn。
跑全量 `src` 的结果是 **0 error 0 warning**，基线干净。

验证方式：往 `src/lib/postHelpers.ts` 和 `src/views/HomeView.vue` 各插一行 `debugger`，
`npx eslint src` 报 2 条 `no-debugger`（`.ts` 和 `.vue` 都真的被解析了），删掉后回到 0。
**不验证这一步，就没法确定「0 条」是因为代码干净还是因为文件根本没被 lint 到**——
eslint 的 flat config 配错 `files` 时，就是这么安静。

### 现在的提交前清单

```text
npm.cmd run lint     # eslint 基线
npm.cmd run build    # vue-tsc + vite（含 api-contract 断言）
mvn test             # 22 个单测
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke.ps1   # 37 项
```

---

## 2026-10-20 把私信轮询换成 SSE（以及为什么既不是 WebSocket 也不是 EventSource）

清单最后一项 D-6 是「轮询过密」：会话列表 3s、当前会话 1.8s。开会话的时候浏览器每 1.8 秒问一次
「有没有新消息」，服务端每次都要查一遍会话、批量补作者和未读数。两个标签页就是两套定时器。

### 选 SSE 而不是 WebSocket

这里只有一种需求：**服务端单向推**「你收到一条新私信」。WebSocket 能给的是双向通道 + 自定义子协议，
代价是多一条 upgrade 链路：现有的 JWT 拦截、限流、requestId 日志都得在握手阶段重写一遍。
SSE 是普通 HTTP 长响应，`SecurityConfig` 那套原样复用，nginx/Caddy 也不用额外配 upgrade。

### 坑：EventSource 带不了 Authorization 头

浏览器原生 `EventSource` 不能设请求头，社区做法是把 token 拼进 query（`?token=...`）。
那条 URL 会进访问日志、进浏览器历史、可能通过 Referer 带出去——这个项目已经为「不要把凭证放进 URL」
改过一次（R-5），不想为了实时化再破一次规矩。
所以前端用 `fetch` + `ReadableStream` 读同一条 SSE 流，手写三十行解析（空行分帧、`event:` / `data:` 两个字段）。
代价是没有原生重连，得自己写指数退避：1s → 2s → … → 30s 封顶。

### 坑：SseEmitter 的三个回调少一个就是内存泄漏

`onCompletion`（正常结束）、`onTimeout`（30 分钟到点）、`onError`（连接异常）都要摘 emitter。
只挂 `onCompletion` 的话，用户切网络、关标签页、代理掐连接这些情况都不会触发它，
`emitters` 里会一直挂着再也发不出去的连接——而且表现为「越用越慢」，很难联想到是这里。

另外 `send()` 失败要当成正常路径（客户端断开是常态），不能往上抛，否则一次断连会污染整个 publish 循环。

### 验证

```text
B 登录 → curl -N -H "Authorization: Bearer $B_TOKEN" /api/v1/realtime/stream
  → event:ready  data:{"userId":59}
A 发消息 → SSE 流里立刻出现 event:message data:{"conversationId":36,"message":{...}}
未登录连流 → 401 {"code":"AUTH_UNAUTHENTICATED"}（不是一条假连接）
```

前端这边 **没有把轮询删掉**：SSE 连着的时候 `realtimeOpen = true`，两个定时器进去第一件事就是 return；
流断了（退避重连期间）轮询自动接管。删掉轮询看着更「干净」，但那样服务端一抖动前端就没有兜底了——
推送是加速通道，不是唯一通道。

### 已知限制

`RealtimeHub` 的 emitter 在进程内存里，多实例部署时 A 实例发的消息推不到连在 B 实例的客户端。
这跟 `STATE_BACKEND=memory` 是同一类问题（Q-4 已经踩过一次）。真要上多副本，第一步是给 `publish` 接 Redis 频道，
而不是把连接改成粘性会话。
---

## 怎么写这篇文档

几条自定的规矩：

- **一条坑一条记录，不用等收尾。** 想通了就写，隔几天就忘了当时为什么那么想。
- **标题写清楚是什么事。** 不写「问题记录」「踩坑一则」这种，写「addPathPrefix 把全站搞成 401」。
- **先说现象，再说当时的怀疑。** 怀疑错了什么、排除了什么，这部分最有价值 ——
  因为下次遇到类似的症状，能靠这段快速定位方向。
- **写具体的。** 哪个文件、哪行、什么报错原文、试了什么没用。不写「经过排查最终解决了」这种话，
  那等于没写。
- **最后写一句以后怎么办。** 能沉淀成规则的就写规则，能做成检查的就记下来，别只记「下次注意」。

反过来，这些不用写：

- 纯操作步骤（「然后我执行了 mvn compile」）。
- 一次就写对的东西。
- 别人文档里已经写清楚的东西，除非我在这上面栽过跟头。