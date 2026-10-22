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
}
