package com.biecuoguo.achievement.api;

import java.time.LocalDate;

/**
 * 按入学年份推算学期序号：0 = 大一上，1 = 大一下，以此类推。
 *
 * <p>学年从 9 月开始；1 月仍算上学期（期末与寒假），2 月到 8 月算下学期。
 * 这是没有学校校历时的通用规则，有校历后应由校历覆盖，而不是在客户端拼字符串。
 */
public final class GrowthTerms {
    private static final String[] YEARS = {"大一", "大二", "大三", "大四"};

    private GrowthTerms() {}

    public static int termIndex(int entryYear, LocalDate today) {
        int month = today.getMonthValue();
        int academicYear = month >= 9 ? today.getYear() : today.getYear() - 1;
        int term = (month >= 9 || month == 1) ? 0 : 1;
        return (academicYear - entryYear) * 2 + term;
    }

    /** 学期标签，如「大一上学期」；入学前与毕业后各有明确说法。 */
    public static String termLabel(int termIndex) {
        if (termIndex < 0) return "入学前";
        if (termIndex >= YEARS.length * 2) return "已毕业";
        return YEARS[termIndex / 2] + (termIndex % 2 == 0 ? "上学期" : "下学期");
    }

    /** 年级标签，与 iOS 引导页的年级文案一致。 */
    public static String gradeLabel(int termIndex) {
        if (termIndex < 0) return "准大一";
        if (termIndex >= YEARS.length * 2) return "已毕业";
        return YEARS[termIndex / 2];
    }

    public static boolean plausibleEntryYear(int entryYear, LocalDate today) {
        return entryYear >= today.getYear() - 10 && entryYear <= today.getYear() + 1;
    }

    /** 某天所在学期的起始日：9 月 1 日（秋季，含次年 1 月）或 2 月 1 日（春季）。不依赖入学年份。 */
    public static LocalDate termStartOf(LocalDate day) {
        int month = day.getMonthValue();
        if (month >= 9) return LocalDate.of(day.getYear(), 9, 1);
        if (month == 1) return LocalDate.of(day.getYear() - 1, 9, 1);
        return LocalDate.of(day.getYear(), 2, 1);
    }

    public static boolean isTermStart(LocalDate day) {
        return day.getDayOfMonth() == 1 && (day.getMonthValue() == 9 || day.getMonthValue() == 2);
    }

    public static LocalDate nextTermStart(LocalDate termStart) {
        return termStart.getMonthValue() == 9 ? LocalDate.of(termStart.getYear() + 1, 2, 1)
                : LocalDate.of(termStart.getYear(), 9, 1);
    }

    public static LocalDate previousTermStart(LocalDate termStart) {
        return termStart.getMonthValue() == 9 ? LocalDate.of(termStart.getYear(), 2, 1)
                : LocalDate.of(termStart.getYear() - 1, 9, 1);
    }

    /** 有入学年份时用「大一上学期」，否则用「2026 秋季学期」。 */
    public static String termLabel(Integer entryYear, LocalDate termStart) {
        if (entryYear != null) return termLabel(termIndex(entryYear, termStart));
        return termStart.getYear() + (termStart.getMonthValue() == 9 ? " 秋季学期" : " 春季学期");
    }
}
