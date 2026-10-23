package vn.reconhub.batch.report;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** CSV files that Excel opens correctly with Vietnamese text: UTF-8 with a byte order mark. */
final class CsvFiles {
    private CsvFiles() {}

    static Path write(Path path, List<String> header, List<List<Object>> rows) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                w.write('﻿');
                w.write(line(header.stream().map(h -> (Object) h).toList()));
                for (List<Object> r : rows) w.write(line(r));
            }
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String line(List<Object> cells) {
        return cells.stream().map(CsvFiles::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    private static String cell(Object v) {
        String s = v == null ? "" : v.toString();
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
