package vn.reconhub.batch.ingest;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * When the skip limit is exceeded the failing chunk rolls back, and with it the error rows the skip listener wrote.
 * Those are the rows that explain the failure, so they are written again here, outside the chunk transaction.
 */
public class SeenErrorsRecorder implements StepExecutionListener {
    private final long fileId;
    private final RatioSkipPolicy policy;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public SeenErrorsRecorder(long fileId, RatioSkipPolicy policy, JdbcTemplate jdbc, PlatformTransactionManager tx) {
        this.fileId = fileId;
        this.policy = policy;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tx);
    }

    @Override
    public ExitStatus afterStep(StepExecution step) {
        if (step.getStatus() != BatchStatus.FAILED) return null;
        tx.executeWithoutResult(s -> {
            Set<Integer> recorded = new HashSet<>(jdbc.queryForList("select row_no from file_errors where file_id = ?", Integer.class, fileId));
            List<RowException> missing = policy.seen().stream().filter(e -> !recorded.contains(e.rowNo())).toList();
            jdbc.batchUpdate("insert into file_errors (file_id, row_no, column_name, value, reason) values (?, ?, ?, ?, ?)", missing, 100, (ps, e) -> {
                ps.setLong(1, fileId);
                ps.setInt(2, e.rowNo());
                ps.setString(3, e.column());
                ps.setString(4, e.value() == null ? null : e.value().length() > 200 ? e.value().substring(0, 200) : e.value());
                ps.setString(5, e.getMessage());
            });
        });
        return null;
    }
}
