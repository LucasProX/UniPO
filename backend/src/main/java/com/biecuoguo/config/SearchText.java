package com.biecuoguo.config;

/**
 * 把用户输入的搜索词变成 MySQL 全文索引（ngram）的短语查询。
 *
 * 整个词包成一个短语：+ - * ( ) 等布尔运算符在短语里不起作用，用户输入不会变成查询语法。
 * ngram_token_size 默认 2，单个字查不到东西，所以至少两个字。
 */
public final class SearchText {
    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 40;

    private SearchText() {
    }

    /** 去掉首尾空白和引号后的搜索词；过短或过长时抛 400。 */
    public static String normalize(String query) {
        String text = query == null ? "" : query.replace("\"", " ").strip().replaceAll("\\s+", " ");
        if (text.codePointCount(0, text.length()) < MIN_LENGTH) {
            throw new IllegalArgumentException("至少输入两个字");
        }
        if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
            throw new IllegalArgumentException("搜索词最多 " + MAX_LENGTH + " 个字");
        }
        return text;
    }

    public static String phrase(String query) {
        return "\"" + normalize(query) + "\"";
    }
}
