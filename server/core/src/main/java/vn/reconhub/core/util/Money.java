package vn.reconhub.core.util;

import java.text.NumberFormat;
import java.util.Locale;

/** VND amounts are whole numbers; partner files use "1.290.000", "1290000" or "1,290,000". */
public final class Money {
    private static final Locale VI = Locale.forLanguageTag("vi-VN");

    private Money() {}

    public static long parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Thiếu số tiền");
        String s = raw.trim().replace("₫", "").replace("đ", "").replace("VND", "").replace(" ", "");
        boolean negative = s.startsWith("-");
        s = s.replace("-", "");
        // A decimal part like ",50" never appears in VND files; separators are only thousand groups.
        if (s.matches("\\d{1,3}([.,]\\d{3})+") || s.matches("\\d+")) {
            long v = Long.parseLong(s.replace(".", "").replace(",", ""));
            return negative ? -v : v;
        }
        if (s.matches("\\d+\\.0+")) return Long.parseLong(s.substring(0, s.indexOf('.')));
        throw new IllegalArgumentException("Số tiền không hợp lệ");
    }

    public static String format(long v) {
        return NumberFormat.getIntegerInstance(VI).format(v) + " ₫";
    }
}
