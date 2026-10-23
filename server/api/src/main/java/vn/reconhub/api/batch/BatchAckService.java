package vn.reconhub.api.batch;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.api.web.Views;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.BatchExecutionAck;
import vn.reconhub.core.repo.BatchExecutionAckRepository;
import vn.reconhub.core.workflow.WorkflowException;
import vn.reconhub.core.workflow.WorkflowException.Kind;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * "Đánh dấu đã xử lý": a person records why a failed or stopped run needs nothing more, so the dashboard stops
 * warning about it. Any signed-in role may do it; the note is required and the first acknowledgement stays.
 */
@Service
public class BatchAckService {
    static final int MAX_NOTE = 500;
    private static final DateTimeFormatter HM_DMY = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final BatchQueryService batch;
    private final BatchExecutionAckRepository acks;
    private final Clock clock;

    public BatchAckService(BatchQueryService batch, BatchExecutionAckRepository acks, Clock clock) {
        this.batch = batch;
        this.acks = acks;
        this.clock = clock;
    }

    @Transactional
    public Views.JobView acknowledge(long executionId, String note, AppUser user) {
        String text = note == null ? "" : note.strip();
        if (text.isEmpty()) throw new WorkflowException(Kind.INVALID, "Cần nhập ghi chú: lỗi này đã được xử lý thế nào");
        if (text.length() > MAX_NOTE) throw new WorkflowException(Kind.INVALID, "Ghi chú dài tối đa " + MAX_NOTE + " ký tự");
        Views.JobView v = view(executionId);
        if (!Views.JobView.failedOrStopped(v.status())) {
            throw new WorkflowException(Kind.INVALID, "Lần chạy #" + executionId + " đang ở trạng thái " + v.status()
                    + ", chỉ đánh dấu được lần chạy lỗi (FAILED) hoặc bị dừng (STOPPED)");
        }
        if (v.acknowledged() != null) {
            throw new WorkflowException(Kind.CONFLICT, "Lần chạy #" + executionId + " đã được " + v.acknowledged().byName()
                    + " đánh dấu đã xử lý lúc " + HM_DMY.format(v.acknowledged().at().atZone(VN)));
        }
        try {
            acks.saveAndFlush(new BatchExecutionAck(executionId, user.getId(), clock.instant(), text));
        } catch (DataIntegrityViolationException e) {
            throw new WorkflowException(Kind.CONFLICT, "Lần chạy #" + executionId + " vừa được người khác đánh dấu đã xử lý. Tải lại trang để xem.");
        }
        return view(executionId);
    }

    private Views.JobView view(long executionId) {
        try {
            return batch.detail(executionId).view();
        } catch (IllegalArgumentException e) {
            throw new WorkflowException(Kind.NOT_FOUND, e.getMessage());
        }
    }
}
