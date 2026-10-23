package vn.reconhub.batch.ingest;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import vn.reconhub.core.util.VnText;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a partner file row by row, CSV (any encoding and delimiter, e.g. Windows-1258 with ';') or XLSX.
 * Restartable: the item count is saved in the step context and skipped on restart.
 */
public class SourceFileReader extends AbstractItemCountingItemStreamItemReader<RawRow> {
    private final Path path;
    private final Charset charset;
    private final char delimiter;
    private List<String> headers;
    private Iterator<List<String>> rows;
    private BufferedReader csv;
    private Workbook workbook;
    private int rowNo;

    public SourceFileReader(Path path, String encoding, char delimiter) {
        this.path = path;
        this.charset = Charset.forName(encoding);
        this.delimiter = delimiter;
        setName("sourceFileReader");
    }

    @Override
    protected void doOpen() throws Exception {
        rowNo = 1;
        if (path.getFileName().toString().toLowerCase().endsWith(".xlsx")) {
            try (InputStream in = Files.newInputStream(path)) {
                workbook = new XSSFWorkbook(in);
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<List<String>> all = new ArrayList<>();
            for (Row r : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < r.getLastCellNum(); c++) {
                    Cell cell = r.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    cells.add(cell == null ? "" : fmt.formatCellValue(cell));
                }
                all.add(cells);
            }
            if (all.isEmpty()) throw new ItemStreamException("File không có dòng tiêu đề");
            headers = all.get(0).stream().map(h -> VnText.nfc(h).trim()).toList();
            rows = all.subList(1, all.size()).iterator();
        } else {
            csv = Files.newBufferedReader(path, charset);
            String header = csv.readLine();
            if (header == null) throw new ItemStreamException("File không có dòng tiêu đề");
            if (header.startsWith("﻿")) header = header.substring(1);
            headers = CsvParser.split(VnText.nfc(header), delimiter).stream().map(String::trim).toList();
        }
    }

    @Override
    protected RawRow doRead() throws Exception {
        List<String> cells;
        if (rows != null) {
            if (!rows.hasNext()) return null;
            cells = rows.next();
            rowNo++;
        } else {
            String line;
            do {
                line = csv.readLine();
                if (line == null) return null;
                rowNo++;
            } while (line.isBlank());
            cells = CsvParser.split(VnText.nfc(line), delimiter);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) values.put(headers.get(i), i < cells.size() ? cells.get(i).trim() : "");
        return new RawRow(rowNo, values);
    }

    @Override
    protected void doClose() throws Exception {
        if (csv != null) csv.close();
        if (workbook != null) workbook.close();
        csv = null;
        workbook = null;
        rows = null;
    }

    public List<String> headers() {
        return headers;
    }

    /** Number of data rows, used to size the skip limit. */
    public static int countRows(Path path, String encoding) {
        try {
            if (path.getFileName().toString().toLowerCase().endsWith(".xlsx")) {
                try (InputStream in = Files.newInputStream(path); Workbook wb = new XSSFWorkbook(in)) {
                    return Math.max(0, wb.getSheetAt(0).getPhysicalNumberOfRows() - 1);
                }
            }
            try (var lines = Files.lines(path, Charset.forName(encoding))) {
                return (int) Math.max(0, lines.filter(l -> !l.isBlank()).count() - 1);
            }
        } catch (IOException e) {
            return 0;
        }
    }
}
