package vn.reconhub.core.matching;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.repo.MatchGroupRepository;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.repo.PaymentLineRepository;
import vn.reconhub.core.util.Money;
import vn.reconhub.core.workflow.ExceptionFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Checks that run after matching: fees charged against the fee rules, and delivered orders that never got paid. */
@Service
public class PostMatchChecks {
    static final List<String> FEE_SOURCES = List.of("GHN", "GHTK", "SHOPEE", "TIKTOK", "MOMO");
    static final List<String> RECEIVABLE_SOURCES = List.of("GHN", "GHTK", "SHOPEE", "TIKTOK");

    private final PaymentLineRepository lines;
    private final MatchGroupRepository groups;
    private final OrderRepository orders;
    private final FeeCalculator fees;
    private final ExceptionFactory exceptionFactory;
    private final MatchingEngine engine;

    public PostMatchChecks(PaymentLineRepository lines, MatchGroupRepository groups, OrderRepository orders,
                           FeeCalculator fees, ExceptionFactory exceptionFactory, MatchingEngine engine) {
        this.lines = lines;
        this.groups = groups;
        this.orders = orders;
        this.fees = fees;
        this.exceptionFactory = exceptionFactory;
        this.engine = engine;
    }

    /**
     * Compares the fee on each newly matched line with the fee schedule in force on the line's date; returns how many
     * differences were raised. Lines of sources without an approved schedule are not checked.
     */
    @Transactional
    public int validateFees(MatchingEngine.Run run) {
        FeeCalculator.Approved schedules = fees.approved();
        int raised = 0;
        for (PaymentLine line : lines.findMatchedFeeUnchecked(FEE_SOURCES)) {
            line.setFeeChecked(true);
            var group = groups.findById(line.getMatchGroupId()).orElse(null);
            if (group == null || group.getMethod() == vn.reconhub.core.domain.Enums.MatchMethod.MANY_TO_ONE) continue;
            List<String> codes = group.getItems().stream().map(i -> i.getOrderCode()).filter(c -> c != null).toList();
            if (codes.size() != 1) continue;
            Order order = orders.findById(codes.get(0)).orElse(null);
            if (order == null) continue;
            var quote = schedules.quote(line.getSourceCode(), order, line).orElse(null);
            if (quote == null || quote.fee() == line.getFee()) continue;
            long expected = quote.fee();
            Map<String, Object> meta = new HashMap<>();
            meta.put("expectedFee", expected);
            meta.put("actualFee", line.getFee());
            meta.put("feeScheduleId", quote.schedule().getId());
            meta.put("feeScheduleVersion", quote.schedule().getVersionNo());
            meta.put("feeRule", FeeFormula.title(quote.schedule()) + ": " + FeeFormula.describe(quote.schedule().getComponents()));
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
