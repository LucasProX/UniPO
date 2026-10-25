package com.biecuoguo.error;

/** 内容命中违禁词，返回 400 + CONTENT_BLOCKED。消息固定，不带命中的词。 */
public final class ContentBlockedException extends RuntimeException {
    public ContentBlockedException() { super("内容包含不允许发布的词语，请修改后再发"); }
}
