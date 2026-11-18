package ir.payamkhan;

import java.util.ArrayList;
import java.util.List;

/**
 * تقسیم متن بلند به بخش‌های کوچک‌تر تا هم موتور آنلاین و هم موتور گوشی
 * بتوانند بدون افت کیفیت آن را بخوانند.
 * (هم صفحهٔ اصلی و هم دستیار شناور از همین کلاس استفاده می‌کنند.)
 */
public final class TextChunker {

    public static final int MAX_CHUNK = 300;

    private TextChunker() { }

    public static void chunk(String text, List<String> out) {
        if (text == null) return;
        String[] paras = text.split("\\n+");
        StringBuilder cur = new StringBuilder();
        for (String para : paras) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            if (cur.length() + p.length() + 1 <= MAX_CHUNK) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(p);
            } else {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                if (p.length() <= MAX_CHUNK) cur.append(p);
                else out.addAll(splitLong(p, MAX_CHUNK));
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.isEmpty() && !text.trim().isEmpty()) out.add(text.trim());
    }

    public static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        chunk(text, out);
        return out;
    }

    private static List<String> splitLong(String s, int max) {
        List<String> res = new ArrayList<>();
        String[] sentences = s.split("(?<=[.!؟?؛;:])\\s*");
        StringBuilder cur = new StringBuilder();
        for (String sen : sentences) {
            if (sen.trim().isEmpty()) continue;
            if (cur.length() + sen.length() + 1 <= max) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(sen);
            } else {
                if (cur.length() > 0) { res.add(cur.toString()); cur.setLength(0); }
                if (sen.length() <= max) cur.append(sen);
                else {
                    int i = 0;
                    while (i < sen.length()) {
                        res.add(sen.substring(i, Math.min(sen.length(), i + max)));
                        i += max;
                    }
                }
            }
        }
        if (cur.length() > 0) res.add(cur.toString());
        return res;
    }

    /** پاک‌سازی متن برای خواندن بهتر (حذف ایموجی‌های پیچیده و فاصله‌های اضافی) */
    public static String normalize(String text) {
        if (text == null) return "";
        // نیم‌فاصلهٔ فارسی (U+200C) دست‌نخورده می‌ماند تا تلفظ کلمات درست بماند؛
        // فقط نشانه‌های نامرئیِ جهت‌نما پاک می‌شوند.
        String t = text.replace('\u200F', ' ')
                       .replace('\u200E', ' ')
                       .replaceAll("[\\t\\r]+", " ")
                       .replaceAll(" {2,}", " ");
        // حذف ایموجی‌ها و نمادهای تصویری که خوانده نمی‌شوند
        t = t.replaceAll("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{2B00}-\\x{2BFF}]", " ");
        return t.trim();
    }
}
