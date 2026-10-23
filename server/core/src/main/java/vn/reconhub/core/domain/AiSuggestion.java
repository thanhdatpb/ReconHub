package vn.reconhub.core.domain;

import java.util.List;

/**
 * What the assistant proposes for an exception. {@code engine} says whether it came from the
 * heuristic scorer or from the LLM, so the UI can show it.
 */
public record AiSuggestion(String summary, double confidence, String recommended, List<Candidate> candidates,
                           List<String> evidence, long latencyMs, long tokens, String engine) {

    public record Candidate(List<String> orderCodes, long amount, double score, List<Reason> reasons) {}

    public record Reason(boolean ok, String text) {}
}
