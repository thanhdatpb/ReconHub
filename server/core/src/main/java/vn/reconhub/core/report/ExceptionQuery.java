package vn.reconhub.core.report;

import java.time.Instant;
import java.util.List;

/** Filters of the exception queue. {@code tab} is todo | pending | done | all. */
public record ExceptionQuery(String tab, List<String> types, List<String> sources, String assignee, boolean overdueOnly,
                             String q, Instant now, int limit) {

    public boolean hasTypes() { return types != null && !types.isEmpty(); }

    public boolean hasSources() { return sources != null && !sources.isEmpty(); }

    public String like() { return q == null || q.isBlank() ? null : "%" + q.trim().toUpperCase() + "%"; }
}
