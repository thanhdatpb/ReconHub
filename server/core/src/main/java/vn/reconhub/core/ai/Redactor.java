package vn.reconhub.core.ai;

import vn.reconhub.core.util.VnText;

import java.util.Collection;
import java.util.Set;

/** Removes personal names from transfer descriptions before they are sent to the LLM. */
public final class Redactor {
    /** Words that appear in bank descriptions and are not names. */
    private static final Set<String> KEEP = Set.of("CK", "IBFT", "MBVCB", "TT", "DON", "DH", "THANH", "TOAN", "CHUYEN", "TIEN",
            "HANG", "TU", "VA", "GUI", "CHO", "MA", "SHOP", "NOP", "THU", "HO", "COD", "QR", "VIETQR", "NAPAS", "FT", "TK", "GIAO",
            "PHAN", "TRA", "LAI", "SP", "HOAN", "THANH TOAN");

    private Redactor() {}

    public static String redact(String description, Collection<String> knownNames) {
        if (description == null) return "";
        String text = VnText.fold(description);
        for (String name : knownNames) {
            String n = VnText.fold(name);
            if (!n.isBlank()) text = text.replace(n, "[TÊN KHÁCH]");
        }
        StringBuilder out = new StringBuilder();
        for (String token : text.split(" ")) {
            boolean nameLike = token.matches("[A-Z]{2,}") && !KEEP.contains(token);
            out.append(nameLike ? "[TÊN]" : token).append(' ');
        }
        return out.toString().replaceAll("(\\[TÊN\\] )+", "[TÊN] ").trim();
    }
}
