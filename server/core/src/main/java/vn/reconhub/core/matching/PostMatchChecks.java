package vn.reconhub.core.matching;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.repo.MatchGroupRepository;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.repo.PaymentLineRepository;
import vn.reconhub.core.repo.SourceRepository;
import vn.reconhub.core.util.Money;
import vn.reconhub.core.workflow.ExceptionFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Checks that run after matching: fees charged against the fee rules, and delivered orders that never got paid. */
@Service
public class PostMatchChecks {
    static final List<String> FEE_SOURCES = List.of("GHN", "GHTK", "SHOPEE", "TIKTOK", "MOMO");
    static final List<String> RECEIVABLE_SOURCES = List.of("GHN", "GHTK", "SHOPEE", "TIKTOK");

    private final PaymentLineRepository lines;
    private final MatchGroupRepository groups;
    private final OrderRepository orders;
    private final SourceRepository sources;
    private final FeeCalculator fees;
    private final ExceptionFactory exceptionFactory;
    private final MatchingEngine engine;

    public PostMatchChecks(PaymentLineRepository lines, MatchGroupRepository groups, OrderRepository orders, SourceRepository sources,
                           FeeCalculator fees, ExceptionFactory exceptionFactory, MatchingEngine engine) {
        this.lines = lines;
        this.groups = groups;
        this.orders = orders;
        this.sources = sources;
        this.fees = fees;
        this.exceptionFactory = exceptionFactory;
        this.engine = engine;
    }

    /** Compares the fee on each newly matched line with the fee rule; returns how many differences were raised. */
    @Transactional
    public int validateFees(MatchingEngine.Run run) {
        Map<String, Source> bySource = sources.findAll().stream().collect(Collectors.toMap(Source::getCode, s -> s));
        int raised = 0;
        for (PaymentLine line : lines.findMatchedFeeUnchecked(FEE_SOURCES)) {
            line.setFeeChecked(true);
            var group = groups.findById(line.getMatchGroupId()).orElse(null);
            if (group == null || group.getMethod() == vn.reconhub.core.domain.Enums.MatchMethod.MANY_TO_ONE) continue;
            List<String> codes = group.getItems().stream().map(i -> i.getOrderCode()).filter(c -> c != null).toList();
            if (codes.size() != 1) continue;
            Order order = orders.findById(codes.get(0)).orElse(null);
            if (order == null) continue;
            long expected = fees.expectedFee(line.getSourceCode(), order, line.gross());
            if (expected < 0 || expected == line.getFee()) continue;
            Map<String, Object> meta = new HashMap<>();
            meta.put("expectedFee", expected);
            meta.put("actualFee", line.getFee());
            meta.put("feeRule", bySource.get(line.getSourceCode()).getFeeRules().stream()
                    .map(r -> r.name() + ": " + r.formula()).collect(Collectors.joining("; ")));
            exceptionFactory.create(ExceptionType.FEE_DIFF, line.getSourceCode(), expected - line.getFee(), run.runDate(), run.at(), run.actor(),
                    "Phí thực trừ " + Money.format(line.getFee()) + ", theo biểu phí " + Money.format(expected), List.of(order), List.of(line), meta);
            raised++;
        }
        return raised;
    }

    /**
     * Raises UNPAID_ORDER for delivered orders still without money. TikTok pays weekly, so it gets three extra days
     * before an order counts as unpaid.
     */
    @Transactional
    public int detectUnpaid(MatchingEngine.Run run) {
        int raised = 0;
        for (String source : RECEIVABLE_SOURCES) {
            int days = engine.props().getUnpaidAfterDays() + ("TIKTOK".equals(source) ? 3 : 0);
            Instant cutoff = run.at().minus(Duration.ofDays(days));
            for (Order o : orders.findDeliveredWithoutPayment(List.of(source), cutoff)) {
                long since = ChronoUnit.DAYS.between(o.getDeliveredAt(), run.at());
                exceptionFactory.create(ExceptionType.UNPAID_ORDER, o.getPayVia(), -o.expectedGross(), run.runDate(), run.at(), run.actor(),
                        "Giao thành công " + since + " ngày mà chưa có tiền về", List.of(o), List.of(), Map.of("daysSinceDelivery", since));
                raised++;
            }
        }
        return raised;
    }
}
