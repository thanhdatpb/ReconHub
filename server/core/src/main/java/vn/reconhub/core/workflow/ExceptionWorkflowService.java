package vn.reconhub.core.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.ai.SuggestionService;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.AiSuggestion;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.Enums.ActionKind;
import vn.reconhub.core.domain.Enums.ExceptionStatus;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.Enums.LineStatus;
import vn.reconhub.core.domain.Enums.MatchMethod;
import vn.reconhub.core.domain.Enums.Role;
import vn.reconhub.core.domain.FeeSchedule;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.domain.WriteOff;
import vn.reconhub.core.matching.FeeFormula;
import vn.reconhub.core.matching.MatchingEngine;
import vn.reconhub.core.messaging.JmsOutbox;
import vn.reconhub.core.messaging.Messages;
import vn.reconhub.core.repo.AppUserRepository;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.repo.MatchGroupRepository;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.repo.SourceRepository;
import vn.reconhub.core.util.Money;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static vn.reconhub.core.workflow.WorkflowException.Kind.*;

/**
 * Everything a person can do to an exception. Every method checks the version the person saw
 * (optimistic locking), applies the business rule and appends to the history.
 */
@Service
@Transactional
public class ExceptionWorkflowService {
    private static final Logger log = LoggerFactory.getLogger(ExceptionWorkflowService.class);

    private final ExceptionRepository exceptions;
    private final OrderRepository orders;
    private final MatchGroupRepository groups;
    private final SourceRepository sources;
    private final AppUserRepository users;
    private final MatchingEngine engine;
    private final SuggestionService suggestions;
    private final JmsOutbox outbox;
    private final ReconProperties props;
    private final Clock clock;

    public ExceptionWorkflowService(ExceptionRepository exceptions, OrderRepository orders, MatchGroupRepository groups,
                                    SourceRepository sources, AppUserRepository users, MatchingEngine engine,
                                    SuggestionService suggestions, JmsOutbox outbox, ReconProperties props, Clock clock) {
        this.exceptions = exceptions;
        this.orders = orders;
        this.groups = groups;
        this.sources = sources;
        this.users = users;
        this.engine = engine;
        this.suggestions = suggestions;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
    }

    public ReconException get(String id) {
        return exceptions.findById(id).orElseThrow(() -> new WorkflowException(NOT_FOUND, "Không tìm thấy ngoại lệ " + id));
    }

    public void assign(List<String> ids, String assigneeId, AppUser actor, Instant at) {
        AppUser target = assigneeId == null ? null : users.findById(assigneeId)
                .filter(u -> u.getRole() == Role.ACCOUNTANT)
                .orElseThrow(() -> new WorkflowException(INVALID, "Chỉ giao việc được cho kế toán"));
        for (String id : ids) {
            ReconException e = get(id);
            e.setAssigneeId(assigneeId);
            e.log(at, actor.getFullName(), ActionKind.ASSIGNED, target == null ? "Bỏ giao việc" : "Giao cho " + target.getFullName());
        }
    }

    /** Matches the payment line(s) of an exception to orders a person picked, possibly from the AI suggestion. */
    public ReconException match(String id, int version, List<String> orderCodes, boolean viaAi, AppUser actor, Instant at) {
        ReconException e = open(id, version);
        if (e.getType() != ExceptionType.UNMATCHED_PAYMENT) throw new WorkflowException(INVALID, "Chỉ khớp tay được với tiền về chưa xác định đơn");
        if (orderCodes == null || orderCodes.isEmpty()) throw new WorkflowException(INVALID, "Chọn ít nhất một đơn");
        List<Order> picked = orders.findByCodeIn(orderCodes);
        if (picked.size() != orderCodes.size()) throw new WorkflowException(INVALID, "Có mã đơn không tồn tại");
        for (Order o : picked) {
            if (groups.findByOrderCode(o.getCode()).isPresent()) throw new WorkflowException(CONFLICT, "Đơn " + o.getCode() + " đã được khớp với khoản tiền khác");
        }
        Source source = sources.findById(e.getSourceCode()).orElseThrow();
        double conf = viaAi && e.getAi() != null ? e.getAi().confidence() : 1.0;
        engine.settle(source, run(actor, at), viaAi ? MatchMethod.AI_CONFIRMED : MatchMethod.MANUAL, conf, picked,
                e.getPayments().toArray(PaymentLine[]::new));
        picked.forEach(o -> { if (!e.getOrders().contains(o)) e.getOrders().add(o); });
        e.close(ExceptionStatus.RESOLVED, at);
        e.log(at, actor.getFullName(), ActionKind.MATCHED, "Khớp với " + String.join(", ", orderCodes) + (viaAi ? " theo gợi ý AI" : " (chọn thủ công)"));
        return e;
    }

    public record WriteOffResult(ReconException exception, boolean autoApproved) {}

    /** Write-offs above the threshold wait for a second person (maker-checker). */
    public WriteOffResult requestWriteOff(String id, int version, String reason, String note, AppUser actor, Instant at) {
        ReconException e = open(id, version);
        long amount = Math.abs(e.getDiffAmount());
        e.setWriteOff(new WriteOff(actor.getFullName(), actor.getId(), reason, note == null ? "" : note, amount));
        e.log(at, actor.getFullName(), ActionKind.WRITE_OFF_REQUESTED, reason + " · " + Money.format(amount) + (note == null || note.isBlank() ? "" : " · " + note));
        if (amount <= props.getWriteOffApprovalThreshold()) {
            completeWriteOff(e, actor, at);
            e.log(at, "Hệ thống", ActionKind.APPROVED, "Tự duyệt vì không vượt " + Money.format(props.getWriteOffApprovalThreshold()));
            return new WriteOffResult(e, true);
        }
        e.setStatus(ExceptionStatus.WRITE_OFF_PENDING);
        return new WriteOffResult(e, false);
    }

    public ReconException decideWriteOff(String id, int version, boolean approve, String note, AppUser actor, Instant at) {
        ReconException e = get(id);
        if (e.getVersion() != version) throw stale();
        if (e.getStatus() != ExceptionStatus.WRITE_OFF_PENDING) throw new WorkflowException(INVALID, "Ngoại lệ không ở trạng thái chờ duyệt");
        if (actor.getRole() != Role.APPROVER) throw new WorkflowException(FORBIDDEN, "Chỉ Kế toán trưởng được duyệt ghi giảm.");
        if (e.getWriteOff() != null && Objects.equals(e.getWriteOff().requestedById(), actor.getId())) {
            throw new WorkflowException(FORBIDDEN, "Người đề nghị không được tự duyệt đề nghị của mình.");
        }
        if (approve) {
            completeWriteOff(e, actor, at);
            e.log(at, actor.getFullName(), ActionKind.APPROVED, note == null || note.isBlank() ? "Đồng ý ghi giảm" : note);
        } else {
            e.setWriteOff(null);
            e.setStatus(ExceptionStatus.OPEN);
            e.log(at, actor.getFullName(), ActionKind.REJECTED, note == null || note.isBlank() ? "Không đồng ý" : note);
        }
        return e;
    }

    /** Records that the partner (or customer) was asked; e-mails the partner through the JMS outbox when it has an address. */
    public ReconException sendClaim(String id, int version, String note, AppUser actor, Instant at, boolean notify) {
        ReconException e = open(id, version);
        Source source = sources.findById(e.getSourceCode()).orElseThrow();
        e.setStatus(ExceptionStatus.IN_REVIEW);
        String text = note == null || note.isBlank() ? "Đã gửi yêu cầu đối soát cho " + source.getName() : note;
        e.log(at, actor.getFullName(), ActionKind.CLAIM_SENT, text);
        if (notify && source.getPartnerEmail() != null && e.getType() != ExceptionType.UNMATCHED_PAYMENT) {
            outbox.sendAfterCommit(Messages.MAIL_OUTBOX, new Messages.MailMessage(source.getPartnerEmail(),
                    "[ReconHub] Yêu cầu đối soát " + e.getId(), text + "\n\nNgười gửi: " + actor.getFullName(), e.getId()));
        }
        return e;
    }

    public ReconException markDuplicate(String id, int version, AppUser actor, Instant at) {
        ReconException e = open(id, version);
        if (e.getType() != ExceptionType.DUPLICATE || e.getPayments().isEmpty()) throw new WorkflowException(INVALID, "Ngoại lệ này không phải giao dịch trùng");
        PaymentLine copy = e.getPayments().get(e.getPayments().size() - 1);
        copy.setStatus(LineStatus.DUPLICATE);
        e.close(ExceptionStatus.RESOLVED, at);
        e.log(at, actor.getFullName(), ActionKind.MARKED_DUPLICATE, "Bỏ qua dòng #" + copy.getId() + " (" + copy.getExternalRef() + ")");
        return e;
    }

    /**
     * Closes a fee difference that an approved fee schedule version explains: the fee charged equals the fee of that
     * version. Only an open one (OPEN or IN_REVIEW) is closed; a closed exception is never reopened.
     */
    public ReconException resolveByFeeSchedule(String id, FeeSchedule schedule, long fee, String actor, Instant at) {
        ReconException e = get(id);
        if (e.getType() != ExceptionType.FEE_DIFF) throw new WorkflowException(INVALID, "Ngoại lệ " + id + " không phải sai phí");
        if (!e.getStatus().isOpen()) throw new WorkflowException(CONFLICT, "Ngoại lệ " + id + " không còn mở");
        String approver = schedule.getDecidedBy() == null ? null
                : users.findById(schedule.getDecidedBy()).map(AppUser::getFullName).orElse(schedule.getDecidedBy());
        e.close(ExceptionStatus.RESOLVED, at);
        e.log(at, actor, ActionKind.FEE_RULE_UPDATED, "Theo biểu phí phiên bản " + schedule.getVersionNo() + " ("
                + FeeFormula.title(schedule).replaceFirst("^Phiên bản \\d+, ", "") + (approver == null ? "" : ", " + approver + " duyệt")
                + "): phí đúng " + Money.format(fee) + ", bằng phí thực trừ; tự đóng");
        return e;
    }

    public ReconException comment(String id, int version, String text, AppUser actor, Instant at) {
        ReconException e = get(id);
        if (e.getVersion() != version) throw stale();
        if (text == null || text.isBlank()) throw new WorkflowException(INVALID, "Bình luận trống");
        e.log(at, actor.getFullName(), ActionKind.COMMENT, text.trim());
        return e;
    }

    /** Re-runs the suggestion, with the LLM when it is configured. */
    public ReconException refreshSuggestion(String id, AppUser actor, Instant at) {
        ReconException e = get(id);
        AiSuggestion s = suggestions.suggest(e, actor.getId());
        e.setAi(s);
        e.log(at, "Trợ lý AI", ActionKind.AI_SUGGESTED, ("llm".equals(s.engine()) ? "LLM" : "Quy tắc") + " · độ tin cậy " + Math.round(s.confidence() * 100) + "%");
        return e;
    }

    private void completeWriteOff(ReconException e, AppUser actor, Instant at) {
        if (e.getType() == ExceptionType.AMOUNT_DIFF && !e.getPayments().isEmpty() && !e.getOrders().isEmpty()) {
            Source source = sources.findById(e.getSourceCode()).orElseThrow();
            engine.settle(source, run(actor, at), MatchMethod.MANUAL, 1.0, e.getOrders(), e.getPayments().toArray(PaymentLine[]::new));
        }
        e.close(ExceptionStatus.WRITTEN_OFF, at);
    }

    private ReconException open(String id, int version) {
        ReconException e = get(id);
        if (e.getVersion() != version) throw stale();
        if (!e.getStatus().isOpen()) throw new WorkflowException(CONFLICT, "Ngoại lệ đã được xử lý hoặc đang chờ duyệt");
        return e;
    }

    private static WorkflowException stale() {
        return new WorkflowException(CONFLICT, "Ngoại lệ vừa được người khác cập nhật. Tải lại trang để xem thay đổi mới nhất.");
    }

    private MatchingEngine.Run run(AppUser actor, Instant at) {
        return new MatchingEngine.Run(at, LocalDate.ofInstant(at, clock.getZone()), actor.getFullName());
    }
}
