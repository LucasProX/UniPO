package com.biecuoguo.moderation.internal;

import com.biecuoguo.moderation.api.ContentFilterApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * 词表在 resources/moderation/ 下：blocked.txt 命中即拒绝，review.txt 命中需人工看一眼。
 * 词表改动要随版本发布；运营补词先提给开发，不在运行时改。
 */
@Service
public class ContentFilterService implements ContentFilterApi {
    private static final Logger log = LoggerFactory.getLogger(ContentFilterService.class);

    private final WordList blocked;
    private final WordList review;
    private final boolean reviewAllPosts;

    // 两个构造函数时必须标明 Spring 用哪个，另一个只给单测注入词表。
    @Autowired
    public ContentFilterService(@Value("${app.moderation.review-all-posts:false}") boolean reviewAllPosts) {
        this(load("moderation/blocked.txt"), load("moderation/review.txt"), reviewAllPosts);
        log.info("Content filter loaded: blocked={} review={} reviewAllPosts={}", blocked.size(), review.size(), reviewAllPosts);
    }

    ContentFilterService(WordList blocked, WordList review, boolean reviewAllPosts) {
        this.blocked = blocked;
        this.review = review;
        this.reviewAllPosts = reviewAllPosts;
    }

    @Override
    public Verdict check(String... texts) {
        StringBuilder joined = new StringBuilder();
        for (String text : texts) {
            if (text != null && !text.isBlank()) joined.append(WordList.normalize(text)).append('\u0000');
        }
        if (joined.isEmpty()) return Verdict.ALLOW;
        String normalized = joined.toString();
        String category = blocked.firstMatch(normalized);
        if (category != null) return new Verdict(Level.BLOCK, category);
        category = review.firstMatch(normalized);
        if (category != null) return new Verdict(Level.REVIEW, category);
        return Verdict.ALLOW;
    }

    @Override
    public boolean reviewAllPosts() {
        return reviewAllPosts;
    }

    private static WordList load(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return WordList.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException("词表加载失败：" + path, ex);
        }
    }
}
