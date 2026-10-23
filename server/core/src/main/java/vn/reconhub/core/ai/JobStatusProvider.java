package vn.reconhub.core.ai;

import java.util.List;

/** Implemented by the API, which can read Spring Batch metadata; lets the assistant answer job questions. */
public interface JobStatusProvider {
    /** {@code handled}: why a failed run needs no action (restarted, replaced, acknowledged), or null. */
    record JobLine(long id, String job, String status, String started, String exitMessage, String handled) {}

    List<JobLine> recent(int limit);
}
