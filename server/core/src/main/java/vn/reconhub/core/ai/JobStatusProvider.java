package vn.reconhub.core.ai;

import java.util.List;

/** Implemented by the API, which can read Spring Batch metadata; lets the assistant answer job questions. */
public interface JobStatusProvider {
    record JobLine(long id, String job, String status, String started, String exitMessage) {}

    List<JobLine> recent(int limit);
}
