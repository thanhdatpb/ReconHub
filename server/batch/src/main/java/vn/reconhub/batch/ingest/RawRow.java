package vn.reconhub.batch.ingest;

import java.util.Map;

/** One data row of a partner file, keyed by the file's own column names. {@code rowNo} counts the header as row 1. */
public record RawRow(int rowNo, Map<String, String> values) {}
