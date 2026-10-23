package vn.reconhub.batch.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.reconhub.core.domain.AiSuggestion;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.Enums.ExceptionStatus;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.repo.AppUserRepository;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.workflow.ExceptionWorkflowService;
import vn.reconhub.core.workflow.WorkflowException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Plays the accountants' last few weeks through the real workflow service, so history, maker-checker and the
 * aging report look like a system in use: most exceptions older than two days are handled, the rest are
 * overdue, and a couple of large write-offs wait for the chief accountant.
 */
@Component
public class DemoHistorySimulator {
    private static final Logger log = LoggerFactory.getLogger(DemoHistorySimulator.class);

    private final ExceptionRepository exceptions;
    private final ExceptionWorkflowService workflow;
    private final AppUserRepository users;
    private final Clock clock;

    public DemoHistorySimulator(ExceptionRepository exceptions, ExceptionWorkflowService workflow, AppUserRepository users,
                                Clock clock) {
        this.exceptions = exceptions;
        this.workflow = workflow;
        this.users = users;
        this.clock = clock;
    }

    public void simulate() {
        Random r = new Random(20261022);
        AppUser ha = users.findById("u1").orElseThrow();
        AppUser khoa = users.findById("u2").orElseThrow();
        AppUser nam = users.findById("u3").orElseThrow();
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);

        List<ReconException> open = exceptions.findAll().stream()
                .filter(e -> e.getStatus() == ExceptionStatus.OPEN)
                .sorted(Comparator.comparing(ReconException::getCreatedAt))
                .toList();
        int handled = 0, pendingLeft = 0;
        for (ReconException e : open) {
            AppUser owner = ownerOf(e.getSourceCode(), ha, khoa);
            Instant assignedAt = before(e.getCreatedAt().plus(Duration.ofMinutes(20 + r.nextInt(60))), now);
            try {
                workflow.assign(List.of(e.getId()), owner.getId(), nam, assignedAt);
            } catch (WorkflowException ex) {
                continue;
            }
            boolean old = e.getRunDate().isBefore(today.minusDays(2));
            if (!old || r.nextDouble() < 0.12) continue; // recent work, or the few that slipped past their SLA

            Instant at = before(e.getCreatedAt().plus(Duration.ofHours(2 + r.nextInt(40))), now);
            try {
                if (handle(e.getId(), owner, nam, at, r, pendingLeft < 2 && e.getRunDate().isAfter(today.minusDays(6)))) handled++;
                if (exceptions.findById(e.getId()).map(x -> x.getStatus() == ExceptionStatus.WRITE_OFF_PENDING).orElse(false)) pendingLeft++;
            } catch (WorkflowException ex) {
                log.debug("Skipped {}: {}", e.getId(), ex.getMessage());
            }
        }
        log.info("Demo history: {} of {} exceptions handled, {} write-offs waiting for approval", handled, open.size(), pendingLeft);
    }

    /** What an accountant typically does with each kind of exception, following the suggestion. */
    private boolean handle(String id, AppUser owner, AppUser approver, Instant at, Random r, boolean mayLeavePending) {
        ReconException e = exceptions.findById(id).orElseThrow();
        AiSuggestion ai = e.getAi();
        switch (e.getType()) {
            case UNMATCHED_PAYMENT -> {
                if (ai != null && "MATCH".equals(ai.recommended()) && !ai.candidates().isEmpty()) {
                    workflow.match(id, e.getVersion(), ai.candidates().get(0).orderCodes(), true, owner, at);
                } else {
                    workflow.comment(id, e.getVersion(), "Đã gọi khách theo nội dung chuyển khoản, chờ khách gửi mã đơn.", owner, at);
                    return false;
                }
            }
            case DUPLICATE -> workflow.markDuplicate(id, e.getVersion(), owner, at);
            case AMOUNT_DIFF -> {
                long diff = Math.abs(e.getDiffAmount());
                if (diff < 5_000) {
                    workflow.requestWriteOff(id, e.getVersion(), "Chênh lệch làm tròn", null, owner, at);
                } else {
                    workflow.sendClaim(id, e.getVersion(), null, owner, at, false);
                    int v = exceptions.findById(id).orElseThrow().getVersion();
                    Instant later = before(at.plus(Duration.ofHours(20)), Instant.now(clock));
                    var result = workflow.requestWriteOff(id, v, "Khách chuyển thiếu, đã xác nhận với khách",
                            "Khách đồng ý bù ở đơn sau", owner, later);
                    if (!result.autoApproved() && !(mayLeavePending && r.nextBoolean())) {
                        int v2 = exceptions.findById(id).orElseThrow().getVersion();
                        workflow.decideWriteOff(id, v2, true, "Đồng ý, lưu chứng từ trao đổi với khách", approver,
                                before(later.plus(Duration.ofHours(3)), Instant.now(clock)));
                    }
                }
            }
            case FEE_DIFF, UNPAID_ORDER -> {
                workflow.sendClaim(id, e.getVersion(), null, owner, at, false);
                if (e.getType() == ExceptionType.FEE_DIFF && Math.abs(e.getDiffAmount()) < 5_000 && r.nextBoolean()) {
                    int v = exceptions.findById(id).orElseThrow().getVersion();
                    workflow.requestWriteOff(id, v, "Đối tác xác nhận phí đúng theo bảng giá mới", null, owner,
                            before(at.plus(Duration.ofHours(26)), Instant.now(clock)));
                }
            }
        }
        return true;
    }

    /** Carriers to Hà, marketplaces and money accounts to Khoa, as in the team's real split. */
    private static AppUser ownerOf(String source, AppUser ha, AppUser khoa) {
        return switch (source) {
            case "GHN", "GHTK", "VTP" -> ha;
            default -> khoa;
        };
    }

    private static Instant before(Instant at, Instant limit) {
        return at.isAfter(limit) ? limit.minusSeconds(120) : at;
    }

}
