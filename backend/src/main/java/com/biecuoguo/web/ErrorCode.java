package com.biecuoguo.web;

/**
 * 业务错误码。
 *
 * 为什么要它：错误体现在只有中文 message，App 端要判断错误类型只能匹配
 * 中文文案——翻译一次全挂、改一次错别字就静默改变行为。requestId 则让
 * 用户截图就能定位到具体一次请求。
 *
 * 命名分层见 docs/MOBILE-API-PLAN.md 4.2：
 * AUTH_* / POST_* / USER_* / MSG_* / MEDIA_* / RATE_LIMIT / VALIDATION_FAILED / INTERNAL。
 *
 * code 是**稳定契约**：只能新增，不要改名或改含义；废弃的码保留但标记 @Deprecated。
 */
public enum ErrorCode {
    // ---- 认证 / 鉴权 ----
    AUTH_INVALID_CREDENTIALS("邮箱或密码不正确"),
    AUTH_UNAUTHENTICATED("请先登录"),
    AUTH_FORBIDDEN("没有权限执行该操作"),
    AUTH_CSRF_INVALID("请求校验失败，请刷新页面后重试"),
    AUTH_VERIFICATION_CODE_INVALID("验证码不正确或已过期"),
    AUTH_EMAIL_TAKEN("该邮箱已被注册"),
    AUTH_NICKNAME_TAKEN("该昵称已被占用"),

    // ---- 内容 ----
    POST_NOT_FOUND("帖子不存在或已删除"),
    POST_DELETE_FORBIDDEN("只能删除自己的帖子"),
    POST_SCHOOL_REQUIRED("请先选择学校"),
    COMMENT_NOT_FOUND("评论不存在或已删除"),

    // ---- 用户 ----
    USER_NOT_FOUND("用户不存在"),
    USER_ALREADY_FOLLOWED("已经关注过该用户"),
    USER_NOT_FOLLOWED("还没有关注该用户"),

    // ---- 私信 ----
    CONVERSATION_NOT_FOUND("会话不存在"),
    MSG_EMPTY("消息内容不能为空"),

    // ---- 媒体 ----
    MEDIA_UPLOAD_FAILED("上传失败，请重试"),
    MEDIA_TOO_LARGE("图片太大了，请换一张 5MB 以内的图片"),

    // ---- 通用 ----
    VALIDATION_FAILED("请求参数不完整或格式不正确"),
    DUPLICATE("名称或账号已被占用"),
    NOT_FOUND("请求的资源不存在"),
    RATE_LIMITED("操作太频繁了，请稍后再试"),
    INTERNAL("服务器开小差了，请稍后再试");

    private final String defaultMessage;

    ErrorCode(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    /** 给 App 端展示的默认中文提示。调用方可覆盖，但建议尽量用默认值保持一致。 */
    public String defaultMessage() {
        return defaultMessage;
    }
}