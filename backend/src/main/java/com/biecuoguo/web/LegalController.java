package com.biecuoguo.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 用户协议与隐私政策。正文放在 resources/legal/，登录页和设置页读同一份，改文本不用发 App。
 * 每次修改正文都要更新这里的 version 和生效日期。
 */
@RestController
public class LegalController {
    public record LegalDocument(String kind, String title, String version, String effectiveDate, String content) {}

    private record Meta(String title, String version, String effectiveDate) {}

    private static final Map<String, Meta> DOCUMENTS = Map.of(
            "terms", new Meta("用户协议", "2026-10-24", "2026-10-24"),
            "privacy", new Meta("隐私政策", "2026-10-24", "2026-10-24"));

    @GetMapping("/api/legal/{kind}")
    public ApiResponse<LegalDocument> document(@PathVariable String kind) {
        Meta meta = DOCUMENTS.get(kind);
        if (meta == null) throw new NoSuchElementException("文档不存在");
        return ApiResponse.ok(new LegalDocument(kind, meta.title(), meta.version(), meta.effectiveDate(), read(kind)));
    }

    private static String read(String kind) {
        try (var in = new ClassPathResource("legal/" + kind + ".md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
