package com.biecuoguo.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证码邮件两份正文的骨架：HTML 必须带上码、用途、有效期、请求时间，纯文本必须能独立读懂。
 *
 * <p>这里不连 SMTP —— 只钉住模板本身。真实的投递（授权码、SSL、垃圾箱）只能靠真邮箱验。
 */
class VerificationMailerTest {

    private static final String CODE = "2849";
    private static final String AT = "2026-10-20 12:51";

    @Test
    void textBodyCarriesCodeAndPurpose() {
        String text = VerificationMailer.textBody(CODE, "注册账号", 10, AT);
        assertTrue(text.contains(CODE), "纯文本里必须有验证码");
        assertTrue(text.contains("注册账号"), "纯文本里必须写清用途");
        assertTrue(text.contains("10 分钟"), "纯文本里必须有有效期");
        assertTrue(text.contains(AT), "纯文本里必须有请求时间");
        assertTrue(text.contains("UniPO"), "纯文本里要有品牌名");
        assertFalse(text.contains("<"), "纯文本不能混进 HTML 标签");
    }

    @Test
    void htmlBodyIsARenderableCard() {
        String html = VerificationMailer.htmlBody(CODE, "注册账号", 10, AT);
        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains(CODE));
        assertTrue(html.contains("注册账号"));
        assertTrue(html.contains("10 分钟"));
        assertTrue(html.contains(AT));
        assertTrue(html.contains("UniPO"));
        assertFalse(html.contains("校园 PO"), "品牌名只写 UniPO");
        // 不用远程图片：邮件客户端默认拦截，只会留下破图占位
        assertFalse(html.contains("<img"), "不要外链图片");
        assertFalse(html.contains("http://"), "不要外链资源");
        assertFalse(html.contains("https://"), "不要外链资源");
        // 布局必须走表格，Outlook 对 flex/grid 支持很差
        assertFalse(html.contains("display:flex"));
        assertFalse(html.contains("display:grid"));
    }

    @Test
    void resetPasswordPurposeIsRendered() {
        String html = VerificationMailer.htmlBody(CODE, "重置密码", 10, AT);
        assertTrue(html.contains("重置密码"));
    }
}
