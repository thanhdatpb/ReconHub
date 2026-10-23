package vn.reconhub.core.ai;

import org.springframework.stereotype.Service;
import vn.reconhub.core.domain.AiSuggestion;
import vn.reconhub.core.domain.AiSuggestion.Candidate;
import vn.reconhub.core.domain.AiSuggestion.Reason;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.fees.FeeRuleInference;
import vn.reconhub.core.matching.DescriptionParser;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.repo.ImportFileRepository;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.util.Money;
import vn.reconhub.core.util.VnText;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the "Gợi ý của AI" panel. A deterministic scorer always runs first and produces the candidates and
 * their reasons. When an API key is configured, the LLM then reviews those features (never names or phone
 * numbers) and may re-rank candidates and rewrite the summary.
 */
@Service
public class SuggestionService {
    private static final Duration LOOKBACK = Duration.ofDays(5);

    private final OrderRepository orders;
    private final ExceptionRepository exceptions;
    private final ImportFileRepository files;
    private final AiGateway ai;
    private final FeeRuleInference feeRules;
    private final Clock clock;

    public SuggestionService(OrderRepository orders, ExceptionRepository exceptions, ImportFileRepository files, AiGateway ai,
                             FeeRuleInference feeRules, Clock clock) {
        this.orders = orders;
        this.exceptions = exceptions;
        this.files = files;
        this.ai = ai;
        this.feeRules = feeRules;
        this.clock = clock;
    }

    /** Rule-based suggestion only; used during batch runs. */
    public AiSuggestion heuristic(ReconException e) {
        long started = System.nanoTime();
        AiSuggestion s = switch (e.getType()) {
            case UNMATCHED_PAYMENT -> unmatched(e);
            case AMOUNT_DIFF -> amountDiff(e);
            case FEE_DIFF -> feeDiff(e);
            case UNPAID_ORDER -> unpaid(e);
            case DUPLICATE -> duplicate(e);
        };
        long ms = Math.max(1, (System.nanoTime() - started) / 1_000_000);
        return new AiSuggestion(s.summary(), s.confidence(), s.recommended(), s.candidates(), s.evidence(), ms, 0, "rules");
    }

    /** Heuristic first, then an LLM review when enabled. {@code userId} is null for system jobs. */
    public AiSuggestion suggest(ReconException e, String userId) {
        AiSuggestion base = heuristic(e);
        if (!ai.enabled()) return base;
        long started = System.currentTimeMillis();
        Optional<LlmVerdict> verdict = ai.call("exception-suggestion", userId, client -> {
            var entity = client.prompt()
                    .system(SYSTEM)
                    .user(facts(e, base))
                    .call()
                    .responseEntity(LlmVerdict.class);
            return new AiGateway.Result<>(entity.entity(), entity.response());
        });
        if (verdict.isEmpty() || verdict.get().summary() == null) return base;
        LlmVerdict v = verdict.get();
        List<Candidate> ranked = new ArrayList<>(base.candidates());
        if (v.ranking() != null && !v.ranking().isEmpty()) {
            List<Candidate> reordered = new ArrayList<>();
            for (Integer i : v.ranking()) if (i != null && i >= 0 && i < ranked.size() && !reordered.contains(ranked.get(i))) reordered.add(ranked.get(i));
            for (Candidate c : ranked) if (!reordered.contains(c)) reordered.add(c);
            ranked = reordered;
        }
        String rec = v.recommended() == null ? base.recommended() : v.recommended().toUpperCase(Locale.ROOT);
        double conf = v.confidence() == null ? base.confidence() : Math.max(0, Math.min(1, v.confidence()));
        return new AiSuggestion(v.summary(), conf, rec, ranked, base.evidence(), System.currentTimeMillis() - started, 0, "llm");
    }

    /** What the LLM returns; validated and merged with the heuristic result. */
    public record LlmVerdict(String summary, String recommended, Double confidence, List<Integer> ranking) {}

    private static final String SYSTEM = """
            Bạn là trợ lý đối soát thanh toán của một cửa hàng bán online ở Việt Nam.
            Bạn nhận dữ kiện về một ngoại lệ đối soát và danh sách ứng viên đã được chấm điểm bằng quy tắc.
            Tên và số điện thoại của khách đã được che; chỉ dựa vào các dữ kiện được cung cấp.
            Khớp sai nguy hiểm hơn không khớp: chỉ đề xuất MATCH khi số tiền khớp và có thêm ít nhất một bằng chứng độc lập.
            Trả về JSON với các trường:
            - summary: tối đa 2 câu tiếng Việt, nói nguyên nhân khả dĩ nhất và việc nên làm.
            - recommended: một trong MATCH, CLAIM, WRITE_OFF, UPDATE_FEE_RULE, MARK_DUPLICATE.
            - confidence: số từ 0 đến 1.
            - ranking: chỉ số các ứng viên theo thứ tự nên chọn (bắt đầu từ 0), có thể rỗng.
            """;

    private String facts(ReconException e, AiSuggestion base) {
        List<String> names = e.getOrders().stream().map(Order::getCustomerName).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        sb.append("Loại ngoại lệ: ").append(e.getType()).append('\n');
        sb.append("Nguồn: ").append(e.getSourceCode()).append('\n');
        sb.append("Chênh lệch: ").append(e.getDiffAmount()).append(" VND\n");
        e.getMeta().forEach((k, val) -> sb.append(k).append(": ").append(val).append('\n'));
        for (PaymentLine p : e.getPayments()) {
            sb.append("Giao dịch: số tiền nhận ").append(p.getAmount()).append(", phí ").append(p.getFee())
                    .append(", nội dung (đã che tên): \"").append(Redactor.redact(p.getDescription(), names)).append("\"\n");
        }
        sb.append("Đánh giá bằng quy tắc: ").append(base.summary()).append(" (đề xuất ").append(base.recommended())
                .append(", tin cậy ").append(String.format(Locale.ROOT, "%.2f", base.confidence())).append(")\n");
        for (int i = 0; i < base.candidates().size(); i++) {
            Candidate c = base.candidates().get(i);
            sb.append("Ứng viên ").append(i).append(": ").append(c.orderCodes().size()).append(" đơn, tổng ").append(c.amount())
                    .append(" VND, điểm ").append(String.format(Locale.ROOT, "%.2f", c.score())).append(", bằng chứng: ")
                    .append(c.reasons().stream().map(r -> (r.ok() ? "[đúng] " : "[sai] ") + stripNames(r.text(), names)).collect(Collectors.joining("; ")))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String stripNames(String text, List<String> names) {
        String out = text;
        for (String n : names) out = out.replace(n, "[TÊN KHÁCH]");
        return out.replaceAll("\"[^\"]*\"", "\"…\"");
    }

    // ------------------------------------------------------------------ heuristics

    private AiSuggestion unmatched(ReconException e) {
        PaymentLine line = e.getPayments().get(0);
        String desc = line.getDescription() == null ? "" : line.getDescription();
        DescriptionParser.Parsed parsed = DescriptionParser.parse(desc);
        List<Order> pool = orders.findUnpaid(e.getSourceCode(), line.getTxnAt().minus(LOOKBACK), line.getTxnAt().plus(Duration.ofHours(1)));
        List<Candidate> cands = new ArrayList<>();
        for (Order o : pool.stream().limit(400).toList()) {
            boolean amountOk = o.expectedGross() == line.gross();
            int dist = DescriptionParser.closestDistance(parsed.folded(), o.getCode());
            boolean tail = parsed.tails().contains(o.getCode().substring(o.getCode().length() - 4));
            boolean name = VnText.containsName(desc, o.getCustomerName());
            long hours = Math.max(0, Duration.between(o.getCreatedAt(), line.getTxnAt()).toHours());
            double score = (amountOk ? 0.4 : 0) + (dist == 0 ? 0.35 : dist == 1 ? 0.3 : dist == 2 ? 0.12 : 0) + (name ? 0.25 : 0)
                    + (tail && dist > 2 ? 0.1 : 0) + (hours <= 24 ? 0.05 : hours <= 72 ? 0.03 : 0);
            if (score < 0.3 && !(amountOk && hours <= 72)) continue;
            List<Reason> reasons = new ArrayList<>();
            reasons.add(amountOk ? new Reason(true, "Số tiền khớp chính xác " + Money.format(line.gross()))
                    : new Reason(false, "Số tiền lệch " + Money.format(Math.abs(o.expectedGross() - line.gross()))));
            if (dist == 0) reasons.add(new Reason(true, "Nội dung có mã " + o.getCode() + " nhưng bị tách hoặc thiếu tiền tố"));
            else if (dist == 1) reasons.add(new Reason(true, "Nội dung có mã lệch 1 ký tự so với " + o.getCode()));
            else if (dist == 2) reasons.add(new Reason(true, "Nội dung có mã lệch 2 ký tự so với " + o.getCode()));
            else if (tail) reasons.add(new Reason(true, "Nội dung có 4 số cuối của mã " + o.getCode()));
            else reasons.add(new Reason(false, "Nội dung không nhắc tới mã đơn này"));
            reasons.add(name ? new Reason(true, "Tên người chuyển trùng tên khách \"" + o.getCustomerName() + "\"")
                    : new Reason(false, "Tên người chuyển khác tên khách \"" + o.getCustomerName() + "\""));
            reasons.add(new Reason(hours <= 72, "Chuyển khoản " + hours + " giờ sau khi đặt hàng"));
            cands.add(new Candidate(List.of(o.getCode()), o.getAmount(), Math.min(0.99, score), reasons));
        }
        // One transfer covering two orders of the same customer.
        List<Order> named = pool.stream().filter(o -> VnText.containsName(desc, o.getCustomerName())).limit(30).toList();
        for (int i = 0; i < named.size(); i++) {
            for (int j = i + 1; j < named.size(); j++) {
                Order a = named.get(i), b = named.get(j);
                if (!a.getCustomerName().equals(b.getCustomerName()) || a.getAmount() + b.getAmount() != line.gross()) continue;
                long tails = parsed.tails().stream().filter(t -> a.getCode().endsWith(t) || b.getCode().endsWith(t)).count();
                double score = Math.min(0.97, 0.6 + 0.2 + 0.08 * tails);
                cands.add(new Candidate(List.of(a.getCode(), b.getCode()), a.getAmount() + b.getAmount(), score, List.of(
                        new Reason(true, "Tổng 2 đơn bằng đúng số tiền chuyển " + Money.format(line.gross())),
                        new Reason(true, "Cả hai đơn của cùng khách \"" + a.getCustomerName() + "\", trùng tên người chuyển"),
                        new Reason(tails > 0, tails > 0 ? "Nội dung có " + tails + " trong 4 số cuối của hai mã" : "Nội dung không ghi mã đơn"))));
            }
        }
        cands.sort(Comparator.comparingDouble(Candidate::score).reversed());
        List<Candidate> top = cands.stream().limit(3).toList();
        List<String> evidence = List.of("Nội dung: \"" + desc + "\"",
                "Đã xét " + pool.size() + " đơn chưa có tiền về trong 5 ngày trước giao dịch");
        if (top.isEmpty()) {
            return new AiSuggestion("Không tìm thấy đơn nào phù hợp với số tiền và nội dung này. Nên liên hệ người chuyển để xác minh.",
                    0.2, "CLAIM", List.of(), evidence, 0, 0, "rules");
        }
        Candidate best = top.get(0);
        String codes = String.join(" và ", best.orderCodes());
        String summary;
        if (best.orderCodes().size() > 1) {
            summary = "Một lần chuyển cho 2 đơn của cùng khách: tổng " + codes + " bằng đúng số tiền nhận được.";
        } else if (best.score() >= 0.75) {
            summary = "Nhiều khả năng là tiền của đơn " + codes + ": " + best.reasons().stream().filter(Reason::ok).map(r -> lowerFirst(r.text())).collect(Collectors.joining(", ")) + ".";
        } else if (best.score() >= 0.5) {
            summary = "Có thể là đơn " + codes + ", nhưng còn điểm chưa khớp (" + best.reasons().stream().filter(r -> !r.ok()).map(r -> lowerFirst(r.text())).collect(Collectors.joining(", ")) + "). Nên xác nhận với khách trước khi khớp.";
        } else {
            summary = "Chỉ tìm thấy ứng viên yếu. Nên liên hệ người chuyển để xác minh trước khi khớp.";
        }
        return new AiSuggestion(summary, best.score(), best.score() >= 0.5 ? "MATCH" : "CLAIM", top, evidence, 0, 0, "rules");
    }

    private AiSuggestion amountDiff(ReconException e) {
        long diff = e.getDiffAmount();
        PaymentLine line = e.getPayments().isEmpty() ? null : e.getPayments().get(0);
        String note = line == null || line.getDescription() == null ? "" : VnText.fold(line.getDescription());
        long similar = exceptions.countSimilar(e.getSourceCode(), ExceptionType.AMOUNT_DIFF, diff, e.getCreatedAt().minus(Duration.ofDays(10)), e.getId());
        List<String> evidence = new ArrayList<>();
        if (line != null && line.getDescription() != null && !line.getDescription().isBlank()) evidence.add("Ghi chú của đối tác: \"" + line.getDescription() + "\"");
        if (Math.abs(diff) < 5_000) {
            evidence.add("Chênh lệch dưới 5.000 ₫");
            return new AiSuggestion("Chênh lệch rất nhỏ (" + Money.format(Math.abs(diff)) + "), nhiều khả năng do làm tròn phí. Có thể ghi giảm.", 0.88, "WRITE_OFF", List.of(), evidence, 0, 0, "rules");
        }
        if (note.contains("1 PHAN") || note.contains("TRA LAI") || note.contains("HOAN")) {
            evidence.add("Chưa thấy phiếu hoàn hàng tương ứng trong OMS");
            return new AiSuggestion("Đối tác ghi giao một phần: khách chỉ nhận một phần hàng nên tiền thu hộ thấp hơn. Cần đối chiếu phiếu hoàn hàng trước khi ghi giảm.", 0.74, "CLAIM", List.of(), evidence, 0, 0, "rules");
        }
        if (similar >= 3) {
            evidence.add(similar + " ngoại lệ " + e.getSourceCode() + " khác trong 10 ngày lệch đúng " + Money.format(diff));
            return new AiSuggestion("Chênh đúng " + Money.format(Math.abs(diff)) + " giống " + similar + " ngoại lệ khác cùng nguồn. Nhiều khả năng đối tác đã đổi biểu phí; nên cập nhật biểu phí rồi xử lý hàng loạt.", 0.86, "UPDATE_FEE_RULE", List.of(), evidence, 0, 0, "rules");
        }
        evidence.add("Không có ghi chú giải thích từ đối tác");
        return new AiSuggestion("Đối tác trả thiếu " + Money.format(Math.abs(diff)) + " mà không có ghi chú. Nên gửi yêu cầu đối soát.", 0.55, "CLAIM", List.of(), evidence, 0, 0, "rules");
    }

    private AiSuggestion feeDiff(ReconException e) {
        long expected = number(e.getMeta().get("expectedFee"));
        long actual = number(e.getMeta().get("actualFee"));
        long extra = actual - expected;
        long similar = exceptions.countSimilar(e.getSourceCode(), ExceptionType.FEE_DIFF, e.getDiffAmount(), e.getCreatedAt().minus(Duration.ofDays(10)), e.getId());
        List<String> evidence = new ArrayList<>();
        evidence.add("Phí theo biểu phí " + Money.format(expected) + ", phí thực trừ " + Money.format(actual));
        if ("GHN".equals(e.getSourceCode()) && extra == 8_000) {
            evidence.add("Phụ phí vùng xa của GHN là 8.000 ₫/đơn");
            return new AiSuggestion("Phí cao hơn đúng 8.000 ₫, bằng phụ phí vùng xa. Địa chỉ có thể thuộc vùng xa nhưng OMS chưa đánh dấu; nên cập nhật đơn và ghi nhận phí.", 0.82, "UPDATE_FEE_RULE", List.of(), evidence, 0, 0, "rules");
        }
        FeeRuleInference.Outcome tariff = feeRules.infer(e);
        if (tariff.found()) {
            FeeRuleInference.Inferred t = tariff.inferred();
            evidence.addAll(t.evidence());
            return new AiSuggestion("Phí " + e.getSourceCode() + " cao hơn biểu phí đúng " + Money.format(extra) + " ở " + t.cases()
                    + " giao dịch, khớp với quy tắc " + t.rule() + ". Có thể đối tác đã đổi biểu phí, nhưng cũng có thể đối tác tính sai."
                    + " Nếu đã có thông báo đổi phí, hãy đề xuất cập nhật biểu phí; nếu chưa có thông báo, nên gửi khiếu nại cho đối tác.",
                    0.7, "UPDATE_FEE_RULE", List.of(), evidence, 0, 0, "rules");
        }
        if (similar >= 3) {
            evidence.add(similar + " giao dịch " + e.getSourceCode() + " khác trong 10 ngày có cùng mức chênh");
            return new AiSuggestion("Phí cao hơn biểu phí " + Money.format(extra) + " và lặp lại ở " + similar + " giao dịch khác. Nên gửi khiếu nại phí cho đối tác.", 0.75, "CLAIM", List.of(), evidence, 0, 0, "rules");
        }
        return new AiSuggestion("Phí cao hơn biểu phí " + Money.format(extra) + " và không khớp phụ phí nào đã biết. Nên hỏi lại đối tác.", 0.6, "CLAIM", List.of(), evidence, 0, 0, "rules");
    }

    private AiSuggestion unpaid(ReconException e) {
        Order o = e.getOrders().get(0);
        LocalDate deliveredDay = o.getDeliveredAt().atZone(clock.getZone()).toLocalDate();
        long filesSince = files.countBySourceCodeAndBusinessDateGreaterThanEqual(e.getSourceCode(), deliveredDay.plusDays(1));
        String ref = o.getTrackingCode() != null ? "mã vận đơn " + o.getTrackingCode() : "mã đơn " + (o.getChannelRef() != null ? o.getChannelRef() : o.getCode());
        return new AiSuggestion("Đơn giao thành công nhưng " + ref + " không có trong " + filesSince + " file đối soát " + e.getSourceCode()
                + " kể từ đó. Nên gửi yêu cầu đối soát kèm " + ref + ".", 0.8, "CLAIM", List.of(),
                List.of("Trạng thái OMS: đã giao", "Đã quét " + filesSince + " file " + e.getSourceCode() + " sau ngày giao"), 0, 0, "rules");
    }

    private AiSuggestion duplicate(ReconException e) {
        List<String> evidence = e.getPayments().stream().map(p -> "Dòng #" + p.getId() + " · " + p.getExternalRef() + " · " + Money.format(p.getAmount())).toList();
        String summary = e.getPayments().size() > 1
                ? "Hai dòng có cùng số tham chiếu và số tiền, nằm ở hai file khác nhau (thường do sao kê có khoảng ngày chồng nhau). Nên đánh dấu dòng sau là trùng."
                : "Đơn này đã được thanh toán trước đó; khoản tiền mới có thể là chuyển hai lần. Nên liên hệ khách để hoàn tiền.";
        return new AiSuggestion(summary, e.getPayments().size() > 1 ? 0.97 : 0.7, e.getPayments().size() > 1 ? "MARK_DUPLICATE" : "CLAIM",
                List.of(), evidence, 0, 0, "rules");
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
