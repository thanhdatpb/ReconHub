package vn.reconhub.core.domain;

import jakarta.persistence.*;
import vn.reconhub.core.domain.Enums.FileStatus;

import java.time.Instant;
import java.time.LocalDate;

/** A settlement file received from a partner, one row per copy received. */
@Entity
@Table(name = "import_files")
public class ImportFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sourceCode;
    private String fileName;
    private String storedPath;
    private Instant receivedAt;
    private LocalDate businessDate;
    @Enumerated(EnumType.STRING)
    private FileStatus status;
    private int totalRows;
    private int errorRows;
    private long amountTotal;
    private String checksum;
    private Long jobExecutionId;
    private String message;
    private String uploadedBy;

    protected ImportFile() {}

    public ImportFile(String sourceCode, String fileName, String storedPath, Instant receivedAt, LocalDate businessDate,
                      String checksum, FileStatus status, String uploadedBy) {
        this.sourceCode = sourceCode;
        this.fileName = fileName;
        this.storedPath = storedPath;
        this.receivedAt = receivedAt;
        this.businessDate = businessDate;
        this.checksum = checksum;
        this.status = status;
        this.uploadedBy = uploadedBy;
    }

    public void finish(int total, int errors, long amount) {
        this.totalRows = total;
        this.errorRows = errors;
        this.amountTotal = amount;
        this.status = errors > 0 ? FileStatus.COMPLETED_WITH_ERRORS : FileStatus.COMPLETED;
    }

    /** Back to PROCESSING when a failed file is restarted. */
    public void reopen() {
        this.status = FileStatus.PROCESSING;
        this.message = null;
    }

    public void fail(String message, int total, int errors) {
        this.status = FileStatus.FAILED;
        this.message = message;
        this.totalRows = total;
        this.errorRows = errors;
    }

    public Long getId() { return id; }
    public String getSourceCode() { return sourceCode; }
    public String getFileName() { return fileName; }
    public String getStoredPath() { return storedPath; }
    public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
    public Instant getReceivedAt() { return receivedAt; }
    public LocalDate getBusinessDate() { return businessDate; }
    public FileStatus getStatus() { return status; }
    public int getTotalRows() { return totalRows; }
    public int getErrorRows() { return errorRows; }
    public long getAmountTotal() { return amountTotal; }
    public String getChecksum() { return checksum; }
    public Long getJobExecutionId() { return jobExecutionId; }
    public void setJobExecutionId(Long jobExecutionId) { this.jobExecutionId = jobExecutionId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getUploadedBy() { return uploadedBy; }
}
