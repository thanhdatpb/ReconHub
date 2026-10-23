package vn.reconhub.core.matching;

import vn.reconhub.core.util.VnText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls order references out of free-text transfer descriptions such as
 * "CK DH2610150123 NGUYEN THI LAN" or "tt don 0123 va 0124".
 */
public final class DescriptionParser {
    /** Order codes are DH + yymmdd + 4 digits. Customers often drop the prefix. */
    private static final Pattern FULL_CODE = Pattern.compile("(?<![A-Z0-9])(?:DH)?(2\\d{9})(?![0-9])");
    private static final Pattern TAIL = Pattern.compile("(?<![0-9])(\\d{4})(?![0-9])");
    private static final Pattern ORDER_WORD = Pattern.compile("\\b(DON|DH|DON HANG|MA DON)\\b");

    private DescriptionParser() {}

    public record Parsed(List<String> codes, List<String> tails, String folded) {}

    public static Parsed parse(String description) {
        String folded = VnText.fold(description);
        Set<String> codes = new LinkedHashSet<>();
        Matcher m = FULL_CODE.matcher(folded);
        while (m.find()) codes.add("DH" + m.group(1));
        List<String> tails = new ArrayList<>();
        if (ORDER_WORD.matcher(folded).find()) {
            String withoutCodes = FULL_CODE.matcher(folded).replaceAll(" ");
            Matcher t = TAIL.matcher(withoutCodes);
            while (t.find()) tails.add(t.group(1));
        }
        return new Parsed(List.copyOf(codes), List.copyOf(tails), folded);
    }

    /** Smallest edit distance between any token of the description and the order code (with or without "DH"). */
    public static int closestDistance(String folded, String orderCode) {
        String digits = orderCode.startsWith("DH") ? orderCode.substring(2) : orderCode;
        int best = Integer.MAX_VALUE;
        String squashed = folded.replaceAll("(?<=\\d) (?=\\d)", "");
        for (String token : squashed.split("[^A-Z0-9]+")) {
            if (token.length() < 8) continue;
            String t = token.startsWith("DH") ? token.substring(2) : token;
            best = Math.min(best, VnText.distance(t, digits));
        }
        return best;
    }
}
