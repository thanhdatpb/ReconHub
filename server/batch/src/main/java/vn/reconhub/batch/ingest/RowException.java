package vn.reconhub.batch.ingest;

/** A row that cannot be loaded. Skippable: it is written to file_errors and the job goes on. */
public class RowException extends RuntimeException {
    private final int rowNo;
    private final String column;
    private final String value;

    public RowException(int rowNo, String column, String value, String reason) {
        super(reason);
        this.rowNo = rowNo;
        this.column = column;
        this.value = value;
    }

    public int rowNo() { return rowNo; }
    public String column() { return column; }
    public String value() { return value; }
}
