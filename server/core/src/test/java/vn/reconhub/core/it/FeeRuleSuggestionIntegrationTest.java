package vn.reconhub.core.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.reconhub.core.ai.SuggestionService;
import vn.reconhub.core.domain.AiSuggestion;
import vn.reconhub.core.domain.Enums.Channel;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.Enums.OrderStatus;
import vn.reconhub.core.domain.FeeComponent;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.fees.FeeScheduleService;
import vn.reconhub.core.matching.MatchingEngine;
import vn.reconhub.core.matching.PostMatchChecks;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.repo.ImportFileRepository;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.repo.PaymentLineRepository;
import vn.reconhub.core.workflow.WorkflowException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When a repeated fee difference is suggested as a new tariff (UPDATE_FEE_RULE) and when it stays a claim: at least
 * five same differences in fourteen days, explained by one fixed amount above a threshold of the order value.
 */
@SpringBootTest(classes = TestApp.class)
@Testcontainers(disabledWithoutDocker = true)
class FeeRuleSuggestionIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired MatchingEngine engine;
    @Autowired PostMatchChecks checks;
    @Autowired SuggestionService suggestions;
    @Autowired FeeScheduleService feeSchedules;
    @Autowired OrderRepository orders;
    @Autowired PaymentLineRepository lines;
    @Autowired ImportFileRepository files;
    @Autowired ExceptionRepository exceptions;
    @Autowired TransactionTemplate tx;

    private final Instant now = Instant.parse("2026-10-22T03:00:00Z");
    private final MatchingEngine.Run run = new MatchingEngine.Run(now, LocalDate.of(2026, 10, 22), "test");

    @Test
    void ghnChargingTwoThousandMoreAboveACodThresholdIsSuggestedAsATariffWithAFilledInProposal() {
        // v1 GHN fee: 22.000 + max(5.000, 0,5% COD)
        for (long cod : new long[] {1_620_000, 1_750_000, 2_000_000, 2_480_000, 3_100_000}) {
            ghn(cod, ghnFee(cod) + 2_000, "2026-10-15T10:00");
        }
        for (long cod : new long[] {450_000, 1_000_000, 1_250_000, 1_480_000}) ghn(cod, ghnFee(cod), "2026-10-16T10:00");
        ghn(2_200_000, ghnFee(2_200_000), "2026-10-12T10:00"); // big COD before the change: charged the old way
        PaymentLine last = ghn(1_900_000, ghnFee(1_900_000) + 2_000, "2026-10-17T10:00");
        validateFees();

        AiSuggestion s = suggestion(last);
        assertThat(s.recommended()).isEqualTo("UPDATE_FEE_RULE");
        assertThat(s.summary()).contains("+2.000 ₫ cho COD trên 1.500.000 ₫ từ 15/10/2026")
                .contains("đối tác tính sai").contains("nên gửi khiếu nại");
        assertThat(s.evidence()).anyMatch(x -> x.startsWith("6 giao dịch GHN từ 15/10/2026 bị trừ thêm đúng 2.000 ₫"));

        FeeScheduleService.Draft draft = feeSchedules.draftFromException(exceptionFor(last).getId());
        assertThat(draft.effectiveFrom()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(draft.components()).hasSize(4);
        FeeComponent added = draft.components().get(3);
        assertThat(added.amount()).isEqualTo(2_000);
        assertThat(added.when()).isEqualTo(new FeeComponent.Condition(null, FeeComponent.Base.COD, 1_500_000L, null));
        assertThat(draft.fromException()).isEqualTo(exceptionFor(last).getId());
    }

    @Test
    void fourSameDifferencesAreNotEnough() {
        PaymentLine last = null;
        for (long amount : new long[] {300_000, 450_000, 600_000, 750_000}) last = ghtk(amount, 25_000, "2026-10-16T09:00");
        validateFees();

        assertThat(suggestion(last).recommended()).isNotEqualTo("UPDATE_FEE_RULE");
        String id = exceptionFor(last).getId();
        assertThatThrownBy(() -> feeSchedules.draftFromException(id)).isInstanceOf(WorkflowException.class)
                .hasMessageContaining("Mới có 4 giao dịch GHTK bị trừ thêm đúng 5.000 ₫");
    }

    @Test
    void shopeeChargingOnePercentMoreStaysAClaim() {
        // one percent more on different order values: a different extra on every order
        PaymentLine varied = null;
        for (long amount : new long[] {259_000, 318_000, 427_000, 512_000, 689_000, 845_000}) {
            varied = shopee(amount, shopeeFee(amount) + halfUp(amount, 100), "2026-10-16T08:00");
        }
        // the same one percent on six 399.000 ₫ orders gives six equal differences, but other 399.000 ₫ orders
        // in the same days were charged correctly, so no threshold explains it
        PaymentLine same = null;
        for (int i = 0; i < 6; i++) same = shopee(399_000, shopeeFee(399_000) + 3_990, "2026-10-16T09:00");
        for (int i = 0; i < 3; i++) shopee(399_000, shopeeFee(399_000), "2026-10-16T10:00");
        validateFees();

        assertThat(suggestion(varied).recommended()).isEqualTo("CLAIM");
        assertThat(suggestion(same).recommended()).isEqualTo("CLAIM");
        String id = exceptionFor(same).getId();
        assertThatThrownBy(() -> feeSchedules.draftFromException(id)).isInstanceOf(WorkflowException.class)
                .hasMessageContaining("vẫn tính đúng biểu phí").hasMessageContaining("nên gửi khiếu nại");
    }

    private static long halfUp(long n, long d) {
        return (n + d / 2) / d;
    }

    private static long ghnFee(long cod) {
        return 22_000 + Math.max(5_000, halfUp(cod * 5, 1_000));
    }

    private static long shopeeFee(long amount) {
        return halfUp(amount * 400, 10_000) + halfUp(amount * 491, 10_000);
    }

    private PaymentLine ghn(long cod, long fee, String paidAt) {
        int n = SEQ.incrementAndGet();
        Order o = new Order(String.format("DH261014%04d", n), Channel.WEBSITE, "Khách " + n, cod, "GHN", now.minusSeconds(864_000));
        o.setCodAmount(cod);
        o.setTrackingCode("GHNS" + n);
        o.setStatus(OrderStatus.SHIPPING);
        orders.save(o);
        return line("GHN", o.getTrackingCode(), o.getCode(), o.getTrackingCode(), cod, fee, paidAt);
    }

    private PaymentLine ghtk(long amount, long fee, String paidAt) {
        int n = SEQ.incrementAndGet();
        Order o = new Order(String.format("DH261014%04d", n), Channel.WEBSITE, "Khách " + n, amount, "GHTK", now.minusSeconds(864_000));
        o.setCodAmount(amount);
        o.setTrackingCode("GHTKS" + n);
        o.setStatus(OrderStatus.SHIPPING);
        orders.save(o);
        return line("GHTK", o.getTrackingCode(), o.getCode(), o.getTrackingCode(), amount, fee, paidAt);
    }

    private PaymentLine shopee(long amount, long fee, String paidAt) {
        int n = SEQ.incrementAndGet();
        Order o = new Order(String.format("DH261014%04d", n), Channel.SHOPEE, "Khách " + n, amount, "SHOPEE", now.minusSeconds(864_000));
        o.setChannelRef("2610SP" + n);
        o.setStatus(OrderStatus.DELIVERED);
        orders.save(o);
        return line("SHOPEE", o.getChannelRef(), o.getChannelRef(), null, amount, fee, paidAt);
    }

    private PaymentLine line(String source, String ref, String orderRef, String tracking, long gross, long fee, String paidAt) {
        int n = SEQ.incrementAndGet();
        Instant at = LocalDateTime.parse(paidAt).atZone(VN).toInstant();
        ImportFile file = files.save(new ImportFile(source, source + "_rule_" + n + ".csv", null, at, at.atZone(VN).toLocalDate(),
                "rule-" + n, FileStatus.COMPLETED, "test"));
        PaymentLine l = lines.save(new PaymentLine(source, file.getId(), 2, ref, orderRef, tracking, null, gross - fee, fee, at));
        MatchingEngine.Outcome outcome = tx.execute(s -> engine.match(lines.findById(l.getId()).orElseThrow(), run));
        assertThat(outcome).isEqualTo(MatchingEngine.Outcome.EXACT);
        return l;
    }

    private void validateFees() {
        tx.execute(s -> checks.validateFees(run));
    }

    private ReconException exceptionFor(PaymentLine line) {
        return tx.execute(s -> exceptions.findAll().stream()
                .filter(e -> e.getType() == ExceptionType.FEE_DIFF && e.getPayments().stream().anyMatch(p -> p.getId().equals(line.getId())))
                .findFirst().orElseThrow());
    }

    private AiSuggestion suggestion(PaymentLine line) {
        return tx.execute(s -> suggestions.heuristic(exceptions.findById(exceptionFor(line).getId()).orElseThrow()));
    }
}
