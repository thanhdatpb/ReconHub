package vn.reconhub.core.ai;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.report.ReportService;
import vn.reconhub.core.util.VnText;

import java.util.List;
import java.util.Optional;

/**
 * Answers questions about reconciliation figures. With an API key, Claude chooses among the read-only tools;
 * without one, a keyword router calls the same tools, so answers look the same either way.
 */
@Service
public class AssistantService {
    private final AiGateway ai;
    private final ReportService reports;
    private final ObjectProvider<JobStatusProvider> jobs;

    public AssistantService(AiGateway ai, ReportService reports, ObjectProvider<JobStatusProvider> jobs) {
        this.ai = ai;
        this.reports = reports;
        this.jobs = jobs;
    }

    public record Table(List<String> columns, List<List<Object>> rows) {}

    public record Chart(List<String> labels, List<Long> values, String label) {}

    public record Answer(String text, List<String> tools, Table table, Chart chart, String engine) {}

    private static final String SYSTEM = """
            Bạn là trợ lý số liệu của hệ thống đối soát thanh toán ReconHub cho một cửa hàng bán online ở Việt Nam.
            Chỉ trả lời bằng số liệu lấy từ các tool. Nếu tool không có số liệu cần thiết, nói rõ là không có.
            Trả lời bằng tiếng Việt, ngắn gọn (tối đa 3 câu), số tiền viết dạng 1.250.000 ₫.
            Bảng chi tiết đã được hiển thị riêng, không cần chép lại toàn bộ bảng.
            """;

    public Answer ask(String question, AppUser user) {
        ReadOnlyTools tools = new ReadOnlyTools(reports, user, jobs.getIfAvailable());
        Optional<String> llm = ai.call("assistant", user.getId(), client -> {
            var response = client.prompt().system(SYSTEM).user(question).tools(tools).call().chatResponse();
            String text = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
            return new AiGateway.Result<>(text, response);
        });
        if (llm.isPresent() && !llm.get().isBlank()) {
            return new Answer(llm.get(), tools.calls, tools.table, tools.chart, "llm");
        }
        return rules(question, tools);
    }

    /** Keyword routing used when no LLM is configured. */
    Answer rules(String question, ReadOnlyTools tools) {
        String q = VnText.fold(question).toLowerCase();
        if (q.matches(".*(ghn|ghtk|shopee|tiktok).*") && q.matches(".*(no|con|phai thu|chua tra).*")) {
            String source = q.contains("ghtk") ? "GHTK" : q.contains("shopee") ? "SHOPEE" : q.contains("tiktok") ? "TIKTOK" : "GHN";
            String raw = tools.getReceivables(source);
            return new Answer(firstSentence("Số tiền " + ReadOnlyTools.LABEL.get(source) + " còn phải trả theo tuổi nợ: ", raw), tools.calls, tools.table, null, "rules");
        }
        if (q.contains("ty le") && q.contains("khop")) {
            String raw = tools.getMatchRate(7);
            return new Answer("Tỷ lệ khớp tự động 7 ngày qua: " + raw.lines().findFirst().orElse("").replace("Tổng: ", "") + ". Chi tiết theo nguồn ở bảng dưới.", tools.calls, tools.table, null, "rules");
        }
        if (q.contains("phi")) {
            tools.getFees();
            long total = tools.chart == null ? 0 : tools.chart.values().stream().mapToLong(Long::longValue).sum();
            return new Answer("Từ đầu tháng đến nay, tổng phí bị trừ là " + vn.reconhub.core.util.Money.format(total) + ".", tools.calls, null, tools.chart, "rules");
        }
        if (q.contains("qua han") || q.contains("ngoai le") || q.contains("ton dong")) {
            String raw = tools.getExceptions(true, 5);
            return new Answer(raw.lines().findFirst().orElse("") + " Dưới đây là các ngoại lệ quá hạn lâu nhất.", tools.calls, tools.table, null, "rules");
        }
        if (q.contains("file")) {
            String raw = tools.getFileStatus();
            return new Answer(raw.lines().count() + " file đã nhận hôm nay.", tools.calls, tools.table, null, "rules");
        }
        if (q.contains("job") || q.contains("batch")) {
            tools.getJobStatus();
            return new Answer("Các lần chạy batch gần nhất ở bảng dưới.", tools.calls, tools.table, null, "rules");
        }
        return new Answer("Chưa cấu hình LLM nên mình chỉ hiểu các câu hỏi mẫu: công nợ đối tác, tỷ lệ khớp, phí, ngoại lệ quá hạn, file hôm nay, batch. "
                + "Thêm ANTHROPIC_API_KEY để hỏi tự do.", tools.calls, null, null, "rules");
    }

    private static String firstSentence(String prefix, String raw) {
        return prefix + raw.lines().findFirst().orElse("không có số liệu") + ".";
    }
}
