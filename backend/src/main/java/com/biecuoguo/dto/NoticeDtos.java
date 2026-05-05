package com.biecuoguo.dto;

import com.biecuoguo.domain.Category;
import com.biecuoguo.domain.Notice;
import com.biecuoguo.domain.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

public final class NoticeDtos {
    private NoticeDtos() {}

    public record NoticeQuery(String q, Long categoryId, String filter, String status) {}

    public record NoticeSummary(
            Long id,
            String title,
            Long schoolId,
            String schoolName,
            Long categoryId,
            String categoryName,
            String importance,
            String riskLevel,
            LocalDateTime deadlineAt,
            String summary,
            String missConsequence,
            String nextAction,
            String suitableFor,
            String status,
            Boolean recommended,
            Boolean pinned,
            Boolean highRisk,
            Boolean urgent,
            Integer sortWeight,
            long commentCount,
            boolean favorited,
            boolean reminded,
            String userStatus,
            List<TagView> tags
    ) {}

    public record NoticeDetail(
            Long id,
            String title,
            Long schoolId,
            String schoolName,
            String college,
            String grade,
            Long categoryId,
            String categoryName,
            String importance,
            String riskLevel,
            LocalDateTime deadlineAt,
            String officialUrl,
            String attachmentUrl,
            String summary,
            String whatIsIt,
            String whyImportant,
            String suitableFor,
            String notSuitableFor,
            String missConsequence,
            String nextAction,
            String materialsNeeded,
            String seniorTip,
            String status,
            Boolean recommended,
            Boolean pinned,
            Boolean highRisk,
            Boolean urgent,
            Integer sortWeight,
            LocalDateTime publishedAt,
            long commentCount,
            boolean favorited,
            boolean reminded,
            String userStatus,
            List<TagView> tags
    ) {}

    public record NoticeRequest(
            @NotBlank String title,
            Long schoolId,
            String college,
            String grade,
            @NotNull Long categoryId,
            String importance,
            String riskLevel,
            LocalDateTime deadlineAt,
            String officialUrl,
            String attachmentUrl,
            @NotBlank String summary,
            String whatIsIt,
            String whyImportant,
            String suitableFor,
            String notSuitableFor,
            String missConsequence,
            String nextAction,
            String materialsNeeded,
            String seniorTip,
            String status,
            Boolean recommended,
            Boolean pinned,
            Boolean highRisk,
            Boolean urgent,
            Integer sortWeight,
            List<Long> tagIds
    ) {}

    public record FeedbackRequest(String value) {}

    public record CalendarDay(String date, long total, long highRisk, List<NoticeSummary> notices) {}

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

    public static Notice apply(Notice notice, NoticeRequest request) {
        notice.setTitle(request.title());
        notice.setSchoolId(request.schoolId() == null ? 1L : request.schoolId());
        notice.setCollege(request.college());
        notice.setGrade(request.grade());
        notice.setCategoryId(request.categoryId());
        notice.setImportance(defaultText(request.importance(), "medium"));
        notice.setRiskLevel(defaultText(request.riskLevel(), "medium"));
        notice.setDeadlineAt(request.deadlineAt());
        notice.setOfficialUrl(request.officialUrl());
        notice.setAttachmentUrl(request.attachmentUrl());
        notice.setSummary(request.summary());
        notice.setWhatIsIt(request.whatIsIt());
        notice.setWhyImportant(request.whyImportant());
        notice.setSuitableFor(request.suitableFor());
        notice.setNotSuitableFor(request.notSuitableFor());
        notice.setMissConsequence(request.missConsequence());
        notice.setNextAction(request.nextAction());
        notice.setMaterialsNeeded(request.materialsNeeded());
        notice.setSeniorTip(request.seniorTip());
        notice.setStatus(defaultText(request.status(), "draft"));
        notice.setRecommended(Boolean.TRUE.equals(request.recommended()));
        notice.setPinned(Boolean.TRUE.equals(request.pinned()));
        notice.setHighRisk(Boolean.TRUE.equals(request.highRisk()));
        notice.setUrgent(Boolean.TRUE.equals(request.urgent()));
        notice.setSortWeight(request.sortWeight() == null ? 0 : request.sortWeight());
        return notice;
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
