package vn.reconhub.core.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class Codes {
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private Codes() {}

    /** EX-261022-0042: date of the matching run, then a global sequence. */
    public static String exceptionId(LocalDate runDate, long seq) {
        return "EX-" + runDate.format(YYMMDD) + "-" + String.format("%04d", seq);
    }
}
