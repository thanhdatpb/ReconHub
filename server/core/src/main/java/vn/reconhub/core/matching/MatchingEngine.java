package vn.reconhub.core.matching;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Enums.ActionKind;
import vn.reconhub.core.domain.Enums.ExceptionStatus;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.Enums.LineStatus;
import vn.reconhub.core.domain.Enums.MatchMethod;
import vn.reconhub.core.domain.Enums.SourceKind;
import vn.reconhub.core.domain.MatchGroup;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.repo.MatchGroupRepository;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.repo.PaymentLineRepository;
import vn.reconhub.core.repo.SourceRepository;
import vn.reconhub.core.util.Money;
import vn.reconhub.core.workflow.ExceptionFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Matches one payment line at a time, in tiers. A wrong automatic match is worse than no match,
 * so every tier needs both an identifier and the exact amount; anything else becomes an exception.
 *
 * <ol>
 *   <li>Duplicate: the same reference and amount was already loaded from another file.</li>
 *   <li>EXACT: tracking code, marketplace id or order code in the line, and the amount agrees.</li>
 *   <li>MANY_TO_ONE: the description names several orders whose total equals the transfer.</li>
 *   <li>AMOUNT_DATE: no code, but exactly one unpaid order with that amount in the 72 hours before.</li>
 * </ol>
 */
@Service
public class MatchingEngine {
    private static final Duration AMOUNT_DATE_WINDOW = Duration.ofHours(72);
    private static final Duration CODE_WINDOW = Duration.ofDays(14);

    private final OrderRepository orders;
    private final PaymentLineRepository lines;
    private final MatchGroupRepository groups;
    private final SourceRepository sources;
    private final ExceptionRepository exceptions;
    private final ExceptionFactory exceptionFactory;
    private final ReconProperties props;

    public MatchingEngine(OrderRepository orders, PaymentLineRepository lines, MatchGroupRepository groups, SourceRepository sources,
                          ExceptionRepository exceptions, ExceptionFactory exceptionFactory, ReconProperties props) {
        this.orders = orders;
        this.lines = lines;
        this.groups = groups;
        this.sources = sources;
        this.exceptions = exceptions;
        this.exceptionFactory = exceptionFactory;
        this.props = props;
    }

    public enum Outcome { EXACT, AMOUNT_DATE, MANY_TO_ONE, DUPLICATE, AMOUNT_DIFF, UNMATCHED }

    /** Context of one matching run: when it ran (simulated time during demo backfill) and who to credit. */
    public record Run(Instant at, LocalDate runDate, String actor) {}

    @Transactional
    public Outcome match(PaymentLine line, Run run) {
        Source source = sources.findById(line.getSourceCode()).orElseThrow();

        List<PaymentLine> earlier = lines.findEarlierCopies(line.getSourceCode(), line.getExternalRef(), line.getAmount(), line.getFileId(), line.getId());
        if (!earlier.isEmpty()) {
            PaymentLine original = earlier.get(0);
            List<Order> matchedOrders = groupOrders(original.getMatchGroupId());
            exceptionFactory.create(ExceptionType.DUPLICATE, source.getCode(), line.getAmount(), run.runDate(), run.at(), run.actor(),
                    "Trùng số tham chiếu " + line.getExternalRef() + " với dòng #" + original.getId(),
                    matchedOrders, List.of(original, line), Map.of());
            return Outcome.DUPLICATE;
        }

        Optional<Order> byId = findByIdentifier(source, line);
        if (byId.isPresent()) {
            Order order = byId.get();
            if (alreadySettled(order)) {
                exceptionFactory.create(ExceptionType.DUPLICATE, source.getCode(), line.getAmount(), run.runDate(), run.at(), run.actor(),
                        "Đơn " + order.getCode() + " đã được thanh toán trước đó", List.of(order), List.of(line), Map.of());
                return Outcome.DUPLICATE;
            }
            long expected = order.expectedGross();
            if (line.gross() == expected) {
                settle(source, run, MatchMethod.EXACT, 1.0, List.of(order), line);
                return Outcome.EXACT;
            }
            Map<String, Object> meta = new HashMap<>();
            meta.put("expectedAmount", expected - line.getFee());
            meta.put("paidAmount", line.getAmount());
            exceptionFactory.create(ExceptionType.AMOUNT_DIFF, source.getCode(), line.gross() - expected, run.runDate(), run.at(), run.actor(),
                    "Tìm thấy đơn " + order.getCode() + " nhưng lệch " + Money.format(line.gross() - expected), List.of(order), List.of(line), meta);
            return Outcome.AMOUNT_DIFF;
        }

        if (source.getKind() == SourceKind.BANK || source.getKind() == SourceKind.EWALLET) {
            DescriptionParser.Parsed parsed = DescriptionParser.parse(line.getDescription());
            Optional<List<Order>> many = manyToOne(source, line, parsed);
            if (many.isPresent()) {
                settle(source, run, MatchMethod.MANY_TO_ONE, 0.95, many.get(), line);
                return Outcome.MANY_TO_ONE;
            }
            if (parsed.codes().isEmpty() && parsed.tails().isEmpty()) {
                List<Order> sameAmount = orders.findUnpaid(source.getCode(), line.getTxnAt().minus(AMOUNT_DATE_WINDOW), line.getTxnAt()).stream()
                        .filter(o -> o.getAmount() == line.gross()).toList();
                if (sameAmount.size() == 1) {
                    settle(source, run, MatchMethod.AMOUNT_DATE, 0.97, sameAmount, line);
                    return Outcome.AMOUNT_DATE;
                }
            }
        }

        exceptionFactory.create(ExceptionType.UNMATCHED_PAYMENT, source.getCode(), line.gross(), run.runDate(), run.at(), run.actor(),
                "Không khớp được ở 3 tầng quy tắc", List.of(), List.of(line), Map.of());
        return Outcome.UNMATCHED;
    }

    /** Creates a settled match group; public so manual and AI-confirmed matches use the same bookkeeping. */
    @Transactional
    public MatchGroup settle(Source source, Run run, MatchMethod method, double confidence, List<Order> matchedOrders, PaymentLine... matchedLines) {
        MatchGroup g = new MatchGroup(source.getCode(), method, confidence, run.runDate(), run.at(), run.actor());
        matchedOrders.forEach(o -> g.addOrder(o.getCode(), o.expectedGross()));
        for (PaymentLine l : matchedLines) g.addLine(l.getId(), l.getAmount());
        MatchGroup saved = groups.save(g);
        for (PaymentLine l : matchedLines) l.matched(saved.getId());
        String ref = matchedLines.length == 0 ? "" : matchedLines[0].getExternalRef();
        for (Order o : matchedOrders) {
            for (ReconException unpaid : exceptions.findOpenUnpaid(o.getCode())) {
                unpaid.close(ExceptionStatus.RESOLVED, run.at());
                unpaid.log(run.at(), run.actor(), ActionKind.MATCHED, "Tiền về muộn (" + ref + ") đã khớp với đơn " + o.getCode() + "; tự đóng");
            }
        }
        return saved;
    }

    private Optional<Order> findByIdentifier(Source source, PaymentLine line) {
        return switch (source.getKind()) {
            case CARRIER -> Optional.ofNullable(line.getTrackingCode()).flatMap(orders::findByTrackingCode)
                    .or(() -> Optional.ofNullable(line.getOrderRef()).flatMap(orders::findById));
            case MARKETPLACE -> Optional.ofNullable(line.getOrderRef()).flatMap(orders::findByChannelRef);
            case BANK, EWALLET -> {
                Optional<Order> byRef = Optional.ofNullable(line.getOrderRef()).filter(s -> !s.isBlank()).flatMap(orders::findById);
                if (byRef.isPresent()) yield byRef;
                List<String> codes = DescriptionParser.parse(line.getDescription()).codes();
                yield codes.size() == 1 ? orders.findById(codes.get(0)).filter(o -> o.getPayVia().equals(source.getCode())) : Optional.empty();
            }
        };
    }

    private Optional<List<Order>> manyToOne(Source source, PaymentLine line, DescriptionParser.Parsed parsed) {
        List<String> refs = new ArrayList<>(parsed.codes());
        refs.addAll(parsed.tails());
        if (refs.size() < 2) return Optional.empty();
        List<Order> unpaid = orders.findUnpaid(source.getCode(), line.getTxnAt().minus(CODE_WINDOW), line.getTxnAt());
        List<Order> picked = new ArrayList<>();
        for (String ref : refs) {
            List<Order> hits = unpaid.stream().filter(o -> ref.startsWith("DH") ? o.getCode().equals(ref) : o.getCode().endsWith(ref)).toList();
            if (hits.size() != 1) return Optional.empty();
            if (!picked.contains(hits.get(0))) picked.add(hits.get(0));
        }
        long total = picked.stream().mapToLong(Order::getAmount).sum();
        return total == line.gross() ? Optional.of(picked) : Optional.empty();
    }

    private boolean alreadySettled(Order order) {
        return groups.findByOrderCode(order.getCode()).isPresent();
    }

    private List<Order> groupOrders(Long groupId) {
        if (groupId == null) return List.of();
        return groups.findById(groupId).map(g -> orders.findByCodeIn(g.getItems().stream()
                .map(i -> i.getOrderCode()).filter(c -> c != null).toList())).orElse(List.of());
    }

    public ReconProperties props() {
        return props;
    }
}
