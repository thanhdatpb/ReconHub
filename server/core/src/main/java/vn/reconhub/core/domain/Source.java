package vn.reconhub.core.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import vn.reconhub.core.domain.Enums.AmountMode;
import vn.reconhub.core.domain.Enums.SourceKind;
import vn.reconhub.core.domain.Enums.SourceStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** A partner that sends settlement files: carrier, marketplace, bank or e-wallet. */
@Entity
@Table(name = "sources")
public class Source {
    @Id
    private String code;
    private String name;
    @Enumerated(EnumType.STRING)
    private SourceKind kind;
    @Enumerated(EnumType.STRING)
    private SourceStatus status;
    private String channel;
    private String filePattern;
    private String fileRegex;
    private String schedule;
    private Integer expectedHour;
    private String payoutDays;
    private String encoding;
    private String dateFormat;
    private String delimiter;
    @Enumerated(EnumType.STRING)
    private AmountMode amountMode;
    private String partnerEmail;
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> headers = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    private List<List<String>> sampleRows = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    private List<MappingRow> mapping = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON)
    private List<FeeRule> feeRules = new ArrayList<>();
    private Instant updatedAt;
    private String updatedBy;

    protected Source() {}

    /** Column mapped to a canonical field, if any. */
    public Optional<String> columnFor(String field) {
        return mapping.stream().filter(m -> m.field().equals(field) && m.column() != null).map(MappingRow::column).findFirst();
    }

    public List<Integer> payoutWeekdays() {
        if (payoutDays == null || payoutDays.isBlank()) return List.of();
        return Arrays.stream(payoutDays.split(",")).map(String::trim).map(Integer::parseInt).toList();
    }

    public void updateMapping(List<MappingRow> rows, String by, Instant at) {
        this.mapping = new ArrayList<>(rows);
        this.updatedBy = by;
        this.updatedAt = at;
    }

    public String getCode() { return code; }
    public String getName() { return name; }
    public SourceKind getKind() { return kind; }
    public SourceStatus getStatus() { return status; }
    public String getChannel() { return channel; }
    public String getFilePattern() { return filePattern; }
    public String getFileRegex() { return fileRegex; }
    public String getSchedule() { return schedule; }
    public Integer getExpectedHour() { return expectedHour; }
    public String getEncoding() { return encoding; }
    public String getDateFormat() { return dateFormat; }
    public char getDelimiter() { return delimiter == null || delimiter.isEmpty() ? ',' : delimiter.charAt(0); }
    public AmountMode getAmountMode() { return amountMode; }
    public String getPartnerEmail() { return partnerEmail; }
    public List<String> getHeaders() { return headers; }
    public List<List<String>> getSampleRows() { return sampleRows; }
    public List<MappingRow> getMapping() { return mapping; }
    public List<FeeRule> getFeeRules() { return feeRules; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
