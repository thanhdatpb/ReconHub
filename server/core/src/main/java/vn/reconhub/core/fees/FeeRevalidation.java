package vn.reconhub.core.fees;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Enums.FeeScheduleStatus;
import vn.reconhub.core.domain.FeeSchedule;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.matching.FeeCalculator;
import vn.reconhub.core.matching.MatchingEngine;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.repo.FeeScheduleRepository;
import vn.reconhub.core.workflow.ExceptionWorkflowService;

/**
 * After a fee schedule version is approved: re-checks the open fee differences (OPEN or IN_REVIEW) of its source
 * whose payment is dated on or after the version's effective date. One is closed only when the fee actually charged
 * equals, to the đồng, the fee of the version in force on the payment date; the others stay as they are. Matching is
 * not touched, and exceptions that are already closed or waiting for a write-off approval are left alone.
 */
@Service
public class FeeRevalidation {
    private static final Logger log = LoggerFactory.getLogger(FeeRevalidation.class);

    private final FeeScheduleRepository schedules;
    private final ExceptionRepository exceptions;
    private final FeeCalculator fees;
    private final ExceptionWorkflowService workflow;
    private final ReconProperties props;

    public FeeRevalidation(FeeScheduleRepository schedules, ExceptionRepository exceptions, FeeCalculator fees,
                           ExceptionWorkflowService workflow, ReconProperties props) {
        this.schedules = schedules;
        this.exceptions = exceptions;
        this.fees = fees;
        this.workflow = workflow;
        this.props = props;
    }

    public record Result(int checked, int resolved) {}

    @Transactional
    public Result revalidate(long scheduleId, MatchingEngine.Run run) {
        FeeSchedule schedule = schedules.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("No fee schedule #" + scheduleId));
        if (schedule.getStatus() != FeeScheduleStatus.APPROVED) {
            throw new IllegalStateException("Fee schedule #" + scheduleId + " is " + schedule.getStatus() + ", not approved");
        }
        FeeCalculator.Approved approved = fees.approved();
        int checked = 0, resolved = 0;
        for (ReconException e : exceptions.findOpenFeeDiffs(schedule.getSourceCode())) {
            if (e.getPayments().size() != 1 || e.getOrders().size() != 1) continue;
            PaymentLine line = e.getPayments().get(0);
            if (line.getTxnAt().atZone(props.getZone()).toLocalDate().isBefore(schedule.getEffectiveFrom())) continue;
            checked++;
            var quote = approved.quote(schedule.getSourceCode(), e.getOrders().get(0), line).orElse(null);
            if (quote == null || quote.fee() != line.getFee()) continue;
            workflow.resolveByFeeSchedule(e.getId(), quote.schedule(), quote.fee(), run.actor(), run.at());
            resolved++;
        }
        log.info("Fee schedule #{} ({} v{}): {} open fee differences checked, {} closed", scheduleId, schedule.getSourceCode(),
                schedule.getVersionNo(), checked, resolved);
        return new Result(checked, resolved);
    }
}
