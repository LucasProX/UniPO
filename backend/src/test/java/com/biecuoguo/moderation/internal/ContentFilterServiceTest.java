package com.biecuoguo.moderation.internal;

import com.biecuoguo.moderation.api.ContentFilterApi.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentFilterServiceTest {
    private final ContentFilterService filter = new ContentFilterService(
            WordList.parse("# 注释\n[academic_fraud]\n代写论文\n[gambling]\n网赌\n"),
            WordList.parse("[contact_lure]\n加微信\n加VX\n"), false);

    @Test
    void blocksEvenWhenSplitBySpacesPunctuationOrFullWidth() {
        assertEquals(Level.BLOCK, filter.check("专业 代 写·论 文，包过").level());
        assertEquals("academic_fraud", filter.check("代写-论文").category());
        assertEquals(Level.BLOCK, filter.check("正常标题", "来玩网*赌").level());
    }

    @Test
    void reviewWordsAreCaseAndWidthInsensitive() {
        assertEquals(Level.REVIEW, filter.check("有问题 加ｖｘ 聊").level());
        assertEquals(Level.REVIEW, filter.check("加 微 信").level());
    }

    @Test
    void blockWinsOverReviewAndCleanTextPasses() {
        assertEquals(Level.BLOCK, filter.check("加微信", "代写论文").level());
        assertEquals(Level.ALLOW, filter.check("四级报名明天截止", null, "").level());
        assertEquals(Level.ALLOW, filter.check().level());
        assertFalse(filter.reviewAllPosts());
    }

    @Test
    void shippedListsLoadAndCoverTheirCategories() {
        ContentFilterService shipped = new ContentFilterService(false);
        assertTrue(shipped.check("找人代考").blocked());
        assertTrue(shipped.check("兼职日结，私聊").needsReview());
        assertEquals(Level.ALLOW, shipped.check("图书馆四楼期末周人少").level());
    }
}
