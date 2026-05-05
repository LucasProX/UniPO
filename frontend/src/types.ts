export type Role = 'USER' | 'OPERATOR' | 'SCHOOL_OPERATOR' | 'COLLEGE_OPERATOR' | 'MAJOR_OPERATOR' | 'ADMIN';
export type BoardCode = 'recommend' | 'school' | 'college' | 'major';

export interface ApiResponse<T> {
  data: T;
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
}
