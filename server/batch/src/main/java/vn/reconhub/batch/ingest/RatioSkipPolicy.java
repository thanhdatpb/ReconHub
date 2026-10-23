package vn.reconhub.batch.ingest;

import org.springframework.batch.core.step.skip.SkipLimitExceededException;
import org.springframework.batch.core.step.skip.SkipPolicy;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Skips bad rows up to 10% of the file (at least 5). Beyond that the file itself is probably wrong
 * (changed format, wrong file) and loading the rest would silently drop money, so the step fails.
 */
public class RatioSkipPolicy implements SkipPolicy {
    private final long limit;
    /** Errors seen so far, by row, so they can still be recorded when the step fails and its chunk rolls back. */
    private final Map<Integer, RowException> seen = new TreeMap<>();

    public RatioSkipPolicy(int totalRows) {
        this.limit = Math.max(5, totalRows / 10);
    }

    @Override
    public boolean shouldSkip(Throwable t, long skipCount) {
        if (!(t instanceof RowException e)) return false;
        synchronized (seen) {
            if (seen.size() < 200) seen.putIfAbsent(e.rowNo(), e);
        }
        if (skipCount >= limit) throw new SkipLimitExceededException(limit, t);
        return true;
    }

    public long limit() {
        return limit;
    }

    public Collection<RowException> seen() {
        synchronized (seen) {
            return java.util.List.copyOf(seen.values());
        }
    }
}
