package vn.reconhub.core.fees;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.Enums.FeeScheduleStatus;
import vn.reconhub.core.domain.Enums.Role;
import vn.reconhub.core.domain.FeeComponent;
import vn.reconhub.core.domain.FeeSchedule;
import vn.reconhub.core.messaging.JmsOutbox;
import vn.reconhub.core.messaging.Messages;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.repo.FeeScheduleRepository;
import vn.reconhub.core.repo.SourceRepository;
import vn.reconhub.core.workflow.WorkflowException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import static vn.reconhub.core.workflow.WorkflowException.Kind.CONFLICT;
import static vn.reconhub.core.workflow.WorkflowException.Kind.FORBIDDEN;
import static vn.reconhub.core.workflow.WorkflowException.Kind.INVALID;
import static vn.reconhub.core.workflow.WorkflowException.Kind.NOT_FOUND;

/**
 * Proposing and approving fee schedule versions. Anyone signed in may propose; a chief accountant (APPROVER) or an
 * admin approves or rejects, never their own proposal (maker-checker, as for write-offs). Approval gives the next
 * version number of the source and asks the batch app to re-check the open fee differences (feeRevalidationJob).
 */
@Service
@Transactional
public class FeeScheduleService {
    static final int MAX_NOTE = 500;

    private final FeeScheduleRepository schedules;
    private final SourceRepository sources;
    private final ExceptionRepository exceptions;
    private final JmsOutbox outbox;

    public FeeScheduleService(FeeScheduleRepository schedules, SourceRepository sources, ExceptionRepository exceptions, JmsOutbox outbox) {
        this.schedules = schedules;
        this.sources = sources;
        this.exceptions = exceptions;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<FeeSchedule> history(String sourceCode) {
        requireSource(sourceCode);
        return schedules.findBySourceCodeOrderByProposedAtDesc(sourceCode);
    }

    public FeeSchedule propose(String sourceCode, LocalDate effectiveFrom, List<FeeComponent> components, String note,
                               String fromException, AppUser actor, Instant at) {
        requireSource(sourceCode);
        if (effectiveFrom == null) throw new WorkflowException(INVALID, "Cần chọn ngày bắt đầu áp dụng");
        String why = required(note, "Cần ghi lý do đổi biểu phí, ví dụ thông báo của đối tác");
        validate(components);
        if (fromException != null && !exceptions.existsById(fromException)) {
            throw new WorkflowException(INVALID, "Không có ngoại lệ " + fromException);
        }
        return schedules.save(new FeeSchedule(sourceCode, effectiveFrom, components, why, fromException, actor.getId(), at));
    }

    public FeeSchedule approve(long id, int version, String note, AppUser actor, Instant at) {
        FeeSchedule s = decidable(id, version, actor);
        s.approve(schedules.maxVersionNo(s.getSourceCode()) + 1, actor.getId(), at, optional(note));
        try {
            schedules.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            throw new WorkflowException(CONFLICT, "Một phiên bản biểu phí khác của " + s.getSourceCode() + " vừa được duyệt. Tải lại trang rồi duyệt lại.");
        }
        outbox.sendAfterCommit(Messages.BATCH_COMMANDS, Messages.BatchCommand.revalidateFees(s.getId(), actor.getFullName()));
        return s;
    }

    public FeeSchedule reject(long id, int version, String note, AppUser actor, Instant at) {
        FeeSchedule s = decidable(id, version, actor);
        s.reject(actor.getId(), at, required(note, "Cần ghi lý do từ chối"));
        return s;
    }

    private FeeSchedule decidable(long id, int version, AppUser actor) {
        FeeSchedule s = schedules.findById(id).orElseThrow(() -> new WorkflowException(NOT_FOUND, "Không có đề xuất biểu phí #" + id));
        if (actor.getRole() != Role.APPROVER && actor.getRole() != Role.ADMIN) {
            throw new WorkflowException(FORBIDDEN, "Chỉ Kế toán trưởng hoặc quản trị được duyệt biểu phí.");
        }
        if (Objects.equals(s.getProposedBy(), actor.getId())) {
            throw new WorkflowException(FORBIDDEN, "Người đề xuất không được tự duyệt đề xuất của mình.");
        }
        if (s.getStatus() != FeeScheduleStatus.PROPOSED) throw new WorkflowException(CONFLICT, "Đề xuất này đã được xử lý");
        if (s.getVersion() != version) {
            throw new WorkflowException(CONFLICT, "Đề xuất vừa được người khác cập nhật. Tải lại trang để xem thay đổi mới nhất.");
        }
        return s;
    }

    static void validate(List<FeeComponent> components) {
        if (components == null || components.isEmpty()) throw new WorkflowException(INVALID, "Biểu phí cần ít nhất một khoản phí");
        for (FeeComponent c : components) {
            if (c.label() == null || c.label().isBlank()) throw new WorkflowException(INVALID, "Mỗi khoản phí cần có tên");
            String name = "khoản \"" + c.label().strip() + "\"";
            if (c.kind() == null) throw new WorkflowException(INVALID, "Chọn loại phí cho " + name);
            switch (c.kind()) {
                case FIXED -> {
                    if (c.amount() == null || c.amount() < 0) throw new WorkflowException(INVALID, "Số tiền của " + name + " phải từ 0 ₫");
                }
                case PERCENT -> {
                    if (c.rate() == null || c.rate().signum() < 0 || c.rate().compareTo(BigDecimal.valueOf(100)) > 0) {
                        throw new WorkflowException(INVALID, "Tỷ lệ của " + name + " phải từ 0 đến 100%");
                    }
                    if (c.base() == null) throw new WorkflowException(INVALID, "Chọn cơ sở tính phần trăm cho " + name);
                    if (c.min() != null && c.min() < 0) throw new WorkflowException(INVALID, "Mức tối thiểu của " + name + " phải từ 0 ₫");
                }
            }
            FeeComponent.Condition w = c.when();
            if (w != null && (w.above() != null || w.atMost() != null)) {
                if (w.base() == null) throw new WorkflowException(INVALID, "Chọn giá trị so sánh cho điều kiện của " + name);
                if ((w.above() != null && w.above() < 0) || (w.atMost() != null && w.atMost() < 0)) {
                    throw new WorkflowException(INVALID, "Ngưỡng trong điều kiện của " + name + " phải từ 0 ₫");
                }
                if (w.above() != null && w.atMost() != null && w.above() >= w.atMost()) {
                    throw new WorkflowException(INVALID, "Điều kiện của " + name + " không có giá trị nào thoả");
                }
            }
        }
    }

    private void requireSource(String code) {
        if (!sources.existsById(code)) throw new WorkflowException(NOT_FOUND, "Không có nguồn " + code);
    }

    private static String required(String text, String message) {
        if (text == null || text.isBlank()) throw new WorkflowException(INVALID, message);
        String t = text.strip();
        if (t.length() > MAX_NOTE) throw new WorkflowException(INVALID, "Ghi chú dài tối đa " + MAX_NOTE + " ký tự");
        return t;
    }

    private static String optional(String text) {
        return text == null || text.isBlank() ? null : required(text, "");
    }
}
