package vn.reconhub.core.ai;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Explains why a Spring Batch execution failed. Reads metadata only; never restarts anything. */
@Service
public class BatchExplainer {
    private final AiGateway ai;

    public BatchExplainer(AiGateway ai) {
        this.ai = ai;
    }

    public record Failure(long executionId, String job, String params, String exitMessage, List<String> steps) {}

    public record Explanation(String cause, List<String> evidence, List<String> steps, String engine) {}

    public Explanation explain(Failure f, String userId) {
        Explanation rules = rules(f);
        Optional<Explanation> llm = ai.call("batch-explain", userId, client -> {
            var r = client.prompt()
                    .system("""
                            Bạn là kỹ sư vận hành hệ thống đối soát chạy Spring Batch.
                            Giải thích ngắn gọn bằng tiếng Việt vì sao lần chạy bị lỗi và cách xử lý.
                            Chỉ dựa vào dữ kiện. Không đề xuất sửa dữ liệu trực tiếp trong cơ sở dữ liệu.
                            Trả về JSON: cause (1-2 câu), evidence (các dòng dữ kiện quan trọng, giữ nguyên văn), steps (2-4 bước xử lý), engine (để "llm").
                            """)
                    .user("Job: " + f.job() + "\nTham số: " + f.params() + "\nThông báo lỗi: " + f.exitMessage() + "\nCác bước:\n" + String.join("\n", f.steps()))
                    .call()
                    .responseEntity(Explanation.class);
            return new AiGateway.Result<>(r.entity(), r.response());
        });
        return llm.filter(x -> x.cause() != null).map(x -> new Explanation(x.cause(), x.evidence() == null ? rules.evidence() : x.evidence(),
                x.steps() == null ? rules.steps() : x.steps(), "llm")).orElse(rules);
    }

    static Explanation rules(Failure f) {
        String msg = f.exitMessage() == null ? "" : f.exitMessage();
        List<String> evidence = new ArrayList<>();
        evidence.add(firstLine(msg));
        f.steps().stream().filter(s -> s.contains("FAILED")).forEach(evidence::add);
        if (msg.contains("SkipLimitExceeded")) {
            return new Explanation("Quá nhiều dòng lỗi trong file nên job dừng để tránh nạp thiếu dữ liệu. Thường do đối tác đổi định dạng (ngày, số tiền) hoặc gửi nhầm file.",
                    evidence, List.of("Mở màn Nhận file, xem các dòng lỗi để biết cột nào sai định dạng.",
                    "Nếu đối tác đổi định dạng, cập nhật định dạng ngày hoặc ánh xạ cột ở Cấu hình nguồn rồi tải lại file.",
                    "Nếu file hỏng, yêu cầu đối tác gửi lại; file mới có checksum khác nên sẽ được nạp."), "rules");
        }
        if (msg.contains("Mail") || msg.contains("SMTP") || msg.contains("SocketTimeout")) {
            return new Explanation("Không gửi được email báo cáo vì máy chủ mail không phản hồi. Các bước tính toán trước đó đã xong.",
                    evidence, List.of("Kiểm tra máy chủ mail (trong Docker Compose là dịch vụ mailpit).",
                    "Khi máy chủ mail hoạt động lại, bấm Chạy lại: Spring Batch bỏ qua các bước đã xong và chỉ chạy lại bước gửi email."), "rules");
        }
        if (msg.contains("DateTimeParse") || msg.contains("NumberFormat")) {
            return new Explanation("Dữ liệu trong file không đúng định dạng đã cấu hình cho nguồn này.", evidence,
                    List.of("Xem dòng lỗi trong màn Nhận file.", "Cập nhật định dạng ở Cấu hình nguồn hoặc yêu cầu đối tác gửi lại file."), "rules");
        }
        if (msg.contains("NoSuchFile") || msg.contains("FileNotFound")) {
            return new Explanation("Không tìm thấy file cần xử lý; có thể file đã bị di chuyển hoặc xoá khỏi thư mục nhận.", evidence,
                    List.of("Kiểm tra thư mục /data/inbox và /data/archive.", "Tải lại file qua màn Nhận file."), "rules");
        }
        return new Explanation("Job dừng do lỗi chưa được phân loại. Xem thông báo lỗi bên dưới.", evidence,
                List.of("Xem log của dịch vụ batch (docker compose logs batch).", "Sửa nguyên nhân rồi bấm Chạy lại."), "rules");
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }
}
