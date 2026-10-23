package vn.reconhub.core.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** One call to the LLM, kept for cost tracking and daily limits. */
@Entity
@Table(name = "ai_requests")
public class AiRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String userId;
    private String feature;
    private String model;
    private long inputTokens;
    private long outputTokens;
    private long latencyMs;
    private boolean ok;
    private Instant at;

    protected AiRequest() {}

    public AiRequest(String userId, String feature, String model, long inputTokens, long outputTokens, long latencyMs, boolean ok, Instant at) {
        this.userId = userId;
        this.feature = feature;
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.ok = ok;
        this.at = at;
    }

    public Long getId() { return id; }
    public long getInputTokens() { return inputTokens; }
    public long getOutputTokens() { return outputTokens; }
}
