export type Role = 'USER' | 'OPERATOR' | 'SCHOOL_OPERATOR' | 'COLLEGE_OPERATOR' | 'MAJOR_OPERATOR' | 'ADMIN';
export type BoardCode = 'recommend' | 'school' | 'college' | 'major';

/** 后端统一成功响应包，见 docs/MOBILE-API-PLAN.md 4.2。 */
export interface ApiResponse<T> {
  code: 'OK';
  message: string;
  data: T;
  requestId: string;
}

/**
 * 后端统一错误响应包。
 *
 * code 是稳定契约（AUTH_* / POST_* / USER_* / MSG_* / MEDIA_* / VALIDATION_FAILED /
 * INTERNAL ...），App 端应该 switch 它；message 只给人看，不要拿来做逻辑判断。
 * requestId 用来把用户报的故障对上服务端日志。
 */
export interface ApiErrorBody {
  code: string;
  message: string;
  data: unknown;
  requestId: string;
  timestamp: string;
}

/**
 * 游标分页响应，来自 GET /api/v1/posts/page。
 *
 * nextCursor 编码了上一页最后一条的完整排序键（pinned/official/dontMiss/
 * likeCount/commentCount/publishedAt/id），客户端原样回传即可，不要自己解析。
 * hasMore 为 false 时 nextCursor 为 null，表示没有下一页。
 */
export interface PagedResponse<T> {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
  limit: number;
}

export interface AuthorView {
  id: number;
  uid: string;
  nickname: string;
  avatarUrl?: string;
  role: Role;
  operatorScope?: string;
  level: number;
  levelTitle: string;
  school?: string;
  college?: string;
  major?: string;
  grade?: string;
  online?: boolean;
}

export interface PostView {
  id: number;
  board: BoardCode;
  sourceType: 'student' | 'official';
  title: string;
  content: string;
  excerpt: string;
  coverUrl?: string;
  images: string[];
  riskLevel: 'normal' | 'medium' | 'high';
  official: boolean;
  dontMiss: boolean;
  pinned: boolean;
  deadlineAt?: string;
  missConsequence?: string;
  nextAction?: string;
  tags: string[];
  likeCount: number;
  commentCount: number;
  favoriteCount: number;
  shareCount: number;
  liked: boolean;
  favorited: boolean;
  status: string;
  publishedAt: string;
  createdAt: string;
  author: AuthorView;
}

export interface CommentView {
  id: number;
  postId: number;
  userId: number;
  parentId?: number;
  content: string;
  likeCount: number;
  replyCount: number;
  liked: boolean;
  featured: boolean;
  status: string;
  createdAt: string;
  author: AuthorView;
}

export interface InteractionNoticeView {
  id: number;
  type: 'like' | 'favorite' | 'comment' | 'reply';
  actor: AuthorView;
  post: PostView;
  commentContent?: string;
  parentCommentId?: number;
  createdAt: string;
  unread: boolean;
}

export interface BoardView {
  code: BoardCode;
  name: string;
  description: string;
  count: number;
}

export interface UserProfile {
  id: number;
  publicUid: string;
  email: string;
  nickname: string;
  avatarUrl?: string;
  schoolId?: number;
  collegeId?: number;
  majorId?: number;
  college?: string;
  major?: string;
  grade?: string;
  bio?: string;
  verifiedStatus: string;
  role: Role;
  operatorScope?: string;
  level: number;
  xp: number;
  levelTitle: string;
  status: string;
  online?: boolean;
  // ---- 偏好与通知设置（后端 UserProfile 一直在返回，前端类型以前漏了）----
  /** 目标标签，逗号分隔，如 `奖学金,保研` */
  goalTags?: string;
  /** 兴趣分类，逗号分隔 */
  interestCategories?: string;
  preferUrgent?: boolean;
  preferHighRisk?: boolean;
  preferSameMajor?: boolean;
  emailNotificationEnabled?: boolean;
  browserNotificationEnabled?: boolean;
  remindBeforeHours?: number;
  createdAt?: string;
  lastLoginAt?: string;
  gender?: string;
  birthYear?: number;
  zodiac?: string;
  mbti?: string;
  showGender?: boolean;
  showAge?: boolean;
  showGrade?: boolean;
  onboardingCompleted?: boolean;
  backgroundUrl?: string | null;
}

/** 登录 / 注册 / 刷新三处共用的返回包（后端 AuthResponse）。 */
export interface AuthSessionView {
  token: string;
  refreshToken: string;
  expiresInSeconds: number;
  user: UserProfile;
}

export interface UserStats {
  favorites: number;
  likedPosts: number;
  sharedPosts: number;
  completed: number;
  comments: number;
  upcoming: number;
  avoidedRisks: number;
  following: number;
  followers: number;
  posts: number;
}

export interface PublicProfileView {
  profile: UserProfile;
  stats: UserStats;
  following: boolean;
  mine: boolean;
  /** 我拉黑了对方 */
  blocked: boolean;
  /** 对方拉黑了我 */
  blockedBy: boolean;
  followedBy?: boolean;
}

/** 关注/粉丝列表的一行（后端刻意不返回完整 UserProfile）。 */
export interface FollowUserView {
  id: number;
  publicUid: string;
  nickname: string;
  avatarUrl?: string;
  role: Role;
  level: number;
  levelTitle?: string;
  college?: string;
  major?: string;
  grade?: string;
  following: boolean;
  followedAt: string;
}

export interface NewFollowersView {
  count: number;
}

export interface BlockStatusView {
  blocked: boolean;
  blockedBy: boolean;
}

export interface CheckInView {
  profile: UserProfile;
  checkedInToday: boolean;
  streak: number;
  xpGained: number;
  checkinDate: string;
}

export interface ConversationView {
  id: number;
  peer: AuthorView;
  lastMessage: string;
  unreadCount: number;
  lastMessageAt: string;
  updatedAt: string;
}

export interface MessageView {
  id: number;
  conversationId: number;
  senderId: number;
  receiverId: number;
  content: string;
  read: boolean;
  createdAt: string;
  /** 发送重试的客户端意图标识；旧消息与旧客户端为空。 */
  clientMessageId?: string | null;
}

// ---- 前端扩展类型（组件化后从 App.vue 迁出，Web/App 两端共用）----

/** 帖子：后端 PostView + 前端热度估算字段 */
export interface CampusPost extends PostView {
  last2hLikes?: number;
  last2hComments?: number;
  eventDate?: string;
}

export interface CampusComment extends CommentView {}

export interface CampusMessage extends MessageView {
  optimistic?: boolean;
}

/**
 * 首页轮播位（后端 /api/spotlights）。
 *
 * source 区分运营手动位（MANUAL）与未来的算法推荐位（ALGORITHM）；
 * imageUrl 为空时前端用品牌渐变兜底，不依赖外链图片。
 */
export interface SpotlightView {
  id: number;
  title: string;
  subtitle: string | null;
  badge: string | null;
  imageUrl: string | null;
  linkUrl: string | null;
  source: 'MANUAL' | 'ALGORITHM' | string;
  weight: number;
  enabled: boolean;
  startsAt: string | null;
  endsAt: string | null;
  createdAt: string;
}

/** 课程表条目（本地创建，暂无后端接口） */
export interface Course {
  id: number;
  day: number;
  title: string;
  start: string;
  end: string;
  location: string;
  teacher: string;
  color: string;
  scope: string;
  startWeek: number;
  endWeek: number;
  seriesId: number;
}

export interface CalendarDay {
  dateKey: string;
  day: string;
  inMonth: boolean;
  postCount: number;
}
