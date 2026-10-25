package com.biecuoguo.moderation.api;

/**
 * 发布前的内容过滤（F02）。BLOCK 直接拒绝；REVIEW 表示可疑：帖子先审后发、评论照常显示但自动进入举报队列。
 * 只报告命中的分类，不把命中的词返回给客户端，也不写日志。
 */
public interface ContentFilterApi {

    enum Level { ALLOW, REVIEW, BLOCK }

    record Verdict(Level level, String category) {
        public static final Verdict ALLOW = new Verdict(Level.ALLOW, null);

        public boolean blocked() { return level == Level.BLOCK; }

        public boolean needsReview() { return level == Level.REVIEW; }
    }

    /** 多段文字一起判断，取最严重的结果。null 和空串忽略。 */
    Verdict check(String... texts);

    /** 是否所有帖子都先审后发（MODERATION_REVIEW_ALL_POSTS，默认关闭）。 */
    boolean reviewAllPosts();
}
