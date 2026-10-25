package com.biecuoguo.moderation.internal;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 一份词表：按 [分类] 分段，每行一个词，# 开头是注释。匹配前把文本和词都做同样的归一化：
 * 全角转半角、转小写、去掉空白、标点和符号，所以「代 写·论文」「ＶＸ」也能命中。
 */
final class WordList {
    private static final Pattern NOISE = Pattern.compile("[\\p{Z}\\p{P}\\p{S}\\s]+");

    record Entry(String category, String word) {}

    private final List<Entry> entries;

    private WordList(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    static WordList parse(String text) {
        List<Entry> entries = new ArrayList<>();
        String category = "other";
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                category = line.substring(1, line.length() - 1).strip();
                continue;
            }
            String word = normalize(line);
            if (!word.isEmpty()) entries.add(new Entry(category, word));
        }
        return new WordList(entries);
    }

    static String normalize(String text) {
        if (text == null) return "";
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return NOISE.matcher(folded).replaceAll("");
    }

    /** 命中的第一个分类；没命中返回 null。normalized 必须已经归一化。 */
    String firstMatch(String normalized) {
        for (Entry entry : entries) {
            if (normalized.contains(entry.word())) return entry.category();
        }
        return null;
    }

    int size() {
        return entries.size();
    }
}
