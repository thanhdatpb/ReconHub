package vn.reconhub.core.domain;

import jakarta.persistence.*;
import vn.reconhub.core.domain.Enums.MatchMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Orders and payment lines that settle each other. */
@Entity
@Table(name = "match_groups")
public class MatchGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sourceCode;
    @Enumerated(EnumType.STRING)
    private MatchMethod method;
    private BigDecimal confidence;
    private LocalDate runDate;
    private Instant createdAt;
    private String createdBy;
    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<MatchItem> items = new ArrayList<>();

    protected MatchGroup() {}

    public MatchGroup(String sourceCode, MatchMethod method, double confidence, LocalDate runDate, Instant createdAt, String createdBy) {
        this.sourceCode = sourceCode;
        this.method = method;
        this.confidence = BigDecimal.valueOf(confidence);
        this.runDate = runDate;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public void addOrder(String orderCode, long amount) {
        items.add(new MatchItem(this, orderCode, null, amount));
    }

    public void addLine(Long paymentLineId, long amount) {
        items.add(new MatchItem(this, null, paymentLineId, amount));
    }

    public Long getId() { return id; }
    public String getSourceCode() { return sourceCode; }
    public MatchMethod getMethod() { return method; }
    public double getConfidence() { return confidence.doubleValue(); }
    public LocalDate getRunDate() { return runDate; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public List<MatchItem> getItems() { return items; }
}
