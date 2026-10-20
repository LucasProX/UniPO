package com.biecuoguo.taxonomy.api;

import com.biecuoguo.domain.Category;
import com.biecuoguo.domain.Tag;

/**
 * 分类 / 标签的下拉视图。
 *
 * <p>这两个 record 原来放在 {@code NoticeDtos} 里，是 notices 时代的遗留。
 * 分类和标签本身还在用（{@code /api/categories}、{@code /api/tags}），
 * 所以独立出来，别再挂靠在已经删掉的 notices DTO 上。
 */
public final class TaxonomyDtos {
    private TaxonomyDtos() {}

    public record TagView(Long id, String name, String type, String color) {
        public static TagView from(Tag tag) {
            return new TagView(tag.getId(), tag.getName(), tag.getType(), tag.getColor());
        }
    }

    public record CategoryView(Long id, String name, String code, Integer sortOrder) {
        public static CategoryView from(Category category) {
            return new CategoryView(category.getId(), category.getName(), category.getCode(), category.getSortOrder());
        }
    }
}