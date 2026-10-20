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