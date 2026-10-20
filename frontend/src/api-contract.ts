/**
 * 前后端类型契约（ISSUES Q-6）。
 *
 * `api-types.ts` 是 `npm run gen:api` 从后端 `/v3/api-docs` 生成的，代表后端真实的 DTO；
 * `types.ts` 里的接口是手写的——因为生成的类型有两个用不了的毛病：
 *
 * 1. 所有字段都是可选的（springdoc 只按 `@NotNull` 之类标 required），手写类型才敢写成必填；
 * 2. 枚举全退化成 `string`（`role` / `board` / `sourceType` …），手写类型收窄成字面量联合，
 *    组件里 switch 才有穷尽性检查。
 *
 * 于是这里用类型断言把两边钉在一起：**字段名多一个少一个、类型改了对不上，`vue-tsc` 直接报错**，
 * 不用等运行时发现「这个字段一直是 undefined」。
 *
 * 怎么用：后端改了 DTO -> `cd frontend && npm run gen:api` -> `npm run build`。
 * 报错指向哪个 `...MatchesBackend`，就去改 `types.ts` 里对应的接口。
 */
import type { components } from './api-types';
import type {
  AuthorView,
  AuthSessionView,
  BoardView,
  CheckInView,
  CommentView,
  ConversationView,
  InteractionNoticeView,
  MessageView,
  PagedResponse,
  PostView,
  PublicProfileView,
  UserProfile,
  UserStats
} from './types';

type Generated = components['schemas'];

/** 顶层字段名必须完全一致：后端加/删字段都要同步改 types.ts。 */
type Keys<T> = keyof T & string;
type SameKeys<A, B> =
  [Exclude<Keys<A>, Keys<B>>, Exclude<Keys<B>, Keys<A>>] extends [never, never] ? true : false;

/**
 * 把两侧都「抹平」再比：
 * - 字面量联合 -> string/number/boolean（后端 schema 不生成 enum，没法逐个比对取值）
 * - 可选字段 -> 必填（springdoc 不出 required）
 * - 可空 -> 去空
 * 只保留「结构 + 字段类型」这一层，这正是会悄悄漂移、又最伤人的那部分。
 */
type Norm<T> = T extends string ? string
  : T extends number ? number
  : T extends boolean ? boolean
  : T extends readonly (infer U)[] ? Norm<U>[]
  : T extends object ? { [K in keyof T]-?: Norm<NonNullable<T[K]>> }
  : never;

type Same<A, B> = [Norm<A>] extends [Norm<B>] ? ([Norm<B>] extends [Norm<A>] ? true : false) : false;

type Contract<A, B> = SameKeys<A, B> extends true ? Same<A, B> : false;

type Assert<T extends true> = T;

export type AuthorViewMatchesBackend = Assert<Contract<AuthorView, Generated['AuthorView']>>;
export type PostViewMatchesBackend = Assert<Contract<PostView, Generated['PostView']>>;
export type CommentViewMatchesBackend = Assert<Contract<CommentView, Generated['CommentView']>>;
export type InteractionNoticeViewMatchesBackend =
  Assert<Contract<InteractionNoticeView, Generated['InteractionNoticeView']>>;
export type BoardViewMatchesBackend = Assert<Contract<BoardView, Generated['BoardView']>>;
export type UserProfileMatchesBackend = Assert<Contract<UserProfile, Generated['UserProfile']>>;
export type UserStatsMatchesBackend = Assert<Contract<UserStats, Generated['UserStats']>>;
export type PublicProfileViewMatchesBackend =
  Assert<Contract<PublicProfileView, Generated['PublicProfile']>>;
export type CheckInViewMatchesBackend = Assert<Contract<CheckInView, Generated['CheckInView']>>;
export type ConversationViewMatchesBackend = Assert<Contract<ConversationView, Generated['ConversationView']>>;
export type MessageViewMatchesBackend = Assert<Contract<MessageView, Generated['MessageView']>>;
export type AuthSessionViewMatchesBackend = Assert<Contract<AuthSessionView, Generated['AuthResponse']>>;
export type PagedResponseMatchesBackend =
  Assert<Contract<PagedResponse<PostView>, Generated['PostPageView']>>;
