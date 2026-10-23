package vn.reconhub.core.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Vietnamese text helpers: bank descriptions arrive without diacritics and in upper case. */
public final class VnText {
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private VnText() {}

    /** "Nguyễn Thị Lan" → "NGUYEN THI LAN". */
    public static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = MARKS.matcher(n).replaceAll("").replace('đ', 'd').replace('Đ', 'D');
        return SPACES.matcher(n.toUpperCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** Text decoded from legacy encodings such as Windows-1258 keeps tone marks as separate characters. */
    public static String nfc(String s) {
        return s == null ? null : Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    /** True when every word of the customer's name appears, in order, in the folded text. */
    public static boolean containsName(String text, String name) {
        String t = " " + fold(text).replaceAll("[^A-Z0-9 ]", " ") + " ";
        String n = fold(name);
        if (n.isBlank()) return false;
        return SPACES.matcher(t).replaceAll(" ").contains(" " + n + " ");
    }

    /** Levenshtein distance, used to spot order codes with one typo. */
    public static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
