package vn.reconhub.core.ai;

import org.springframework.stereotype.Service;
import vn.reconhub.core.domain.MappingRow;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.util.VnText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Proposes how a new partner's columns map to the canonical fields. Runs once when a source is configured;
 * afterwards files are read with the saved mapping, never with AI per row.
 */
@Service
public class MappingSuggester {
    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "externalRef", List.of("MA VAN DON", "MA GIAO DICH", "SO THAM CHIEU", "ORDER ID", "TRANSACTION ID", "MA DON HANG"),
            "orderCode", List.of("MA DON HANG KH", "MA DON KH", "MA DON SHOP", "MA THAM CHIEU DOI TAC", "MA DON HANG", "ORDER ID"),
            "trackingCode", List.of("MA VAN DON", "TRACKING"),
            "amount", List.of("THUC TRA", "THUC NHAN", "TIEN TRA SHOP", "TONG TIEN DA THANH TOAN", "SO TIEN GHI CO", "SETTLEMENT AMOUNT", "SO TIEN"),
            "fee", List.of("TONG PHI", "TONG CUOC", "PHI SHIP", "PHI", "TOTAL FEES"),
            "txnDate", List.of("NGAY DOI SOAT", "NGAY GIAO DICH", "THOI GIAN", "NGAY", "SETTLEMENT TIME"),
            "description", List.of("GHI CHU", "NOI DUNG", "MO TA", "TRANG THAI"));

    private final AiGateway ai;

    public MappingSuggester(AiGateway ai) {
        this.ai = ai;
    }

    public record LlmMapping(List<Item> mapping) {
        public record Item(String field, String column, Double confidence, String note) {}
    }

    public List<MappingRow> suggest(Source source, String userId) {
        List<MappingRow> rules = heuristic(source.getHeaders());
        Optional<LlmMapping> llm = ai.call("mapping-suggestion", userId, client -> {
            var r = client.prompt()
                    .system("""
                            Bạn giúp cấu hình đọc file đối soát của một đối tác thanh toán ở Việt Nam.
                            Các trường chuẩn: externalRef (mã tham chiếu duy nhất mỗi dòng), orderCode (mã đơn của cửa hàng hoặc mã đơn trên sàn),
                            trackingCode (mã vận đơn), amount (số tiền cửa hàng thực nhận sau phí), fee (phí đối tác trừ),
                            txnDate (ngày giao dịch hoặc ngày đối soát), description (ghi chú, nội dung).
                            Với mỗi trường, chọn đúng một tên cột có trong tiêu đề hoặc null. Kiểm tra bằng các dòng mẫu, ví dụ amount có bằng tiền thu hộ trừ phí không.
                            confidence từ 0 đến 1. note: một câu tiếng Việt khi còn điều cần người kiểm tra, ngược lại null.
                            """)
                    .user("Tiêu đề: " + source.getHeaders() + "\nDòng mẫu: " + source.getSampleRows())
                    .call()
                    .responseEntity(LlmMapping.class);
            return new AiGateway.Result<>(r.entity(), r.response());
        });
        if (llm.isEmpty() || llm.get().mapping() == null) return rules;
        List<MappingRow> merged = new ArrayList<>();
        for (MappingRow rule : rules) {
            var item = llm.get().mapping().stream().filter(i -> rule.field().equals(i.field())).findFirst();
            if (item.isPresent() && (item.get().column() == null || source.getHeaders().contains(item.get().column()))) {
                merged.add(new MappingRow(rule.field(), item.get().column(), item.get().confidence(), item.get().note()));
            } else {
                merged.add(rule);
            }
        }
        return merged;
    }

    /** Header synonyms plus a sanity check of the sample values. */
    public static List<MappingRow> heuristic(List<String> headers) {
        List<MappingRow> rows = new ArrayList<>();
        for (String field : MappingRow.FIELDS) {
            String best = null;
            double conf = 0;
            String note = null;
            for (String h : headers) {
                String f = VnText.fold(h).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
                List<String> words = SYNONYMS.get(field);
                for (int i = 0; i < words.size(); i++) {
                    if (f.equals(words.get(i)) || f.startsWith(words.get(i) + " ")) {
                        double c = 0.95 - i * 0.05;
                        if (c > conf) {
                            best = h;
                            conf = c;
                        }
                    }
                }
            }
            if ("fee".equals(field) && headers.stream().filter(h -> VnText.fold(h).contains("PHI") || VnText.fold(h).contains("CUOC")).count() > 1) {
                note = "Phí nằm ở nhiều cột; hiện chỉ ánh xạ được 1 cột, cần kiểm tra.";
                conf = Math.min(conf, 0.6);
            }
            if ("amount".equals(field) && best != null && VnText.fold(best).contains("THUC")) {
                note = "Cột này có vẻ là số tiền sau phí; kiểm tra bằng dòng mẫu.";
                conf = Math.min(conf, 0.75);
            }
            rows.add(new MappingRow(field, best, best == null ? null : Math.round(conf * 100) / 100.0, note));
        }
        return rows;
    }
}
