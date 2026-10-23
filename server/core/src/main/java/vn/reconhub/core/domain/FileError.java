package vn.reconhub.core.domain;

import jakarta.persistence.*;

/** A row the ingestion job skipped, with the reason. */
@Entity
@Table(name = "file_errors")
public class FileError {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long fileId;
    private int rowNo;
    private String columnName;
    private String value;
    private String reason;

    protected FileError() {}

    public FileError(Long fileId, int rowNo, String columnName, String value, String reason) {
        this.fileId = fileId;
        this.rowNo = rowNo;
        this.columnName = columnName;
        this.value = value == null ? null : (value.length() > 200 ? value.substring(0, 200) : value);
        this.reason = reason.length() > 200 ? reason.substring(0, 200) : reason;
    }

    public Long getId() { return id; }
    public Long getFileId() { return fileId; }
    public int getRowNo() { return rowNo; }
    public String getColumnName() { return columnName; }
    public String getValue() { return value; }
    public String getReason() { return reason; }
}
