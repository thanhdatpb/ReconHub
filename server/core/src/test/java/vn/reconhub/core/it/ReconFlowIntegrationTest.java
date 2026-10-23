package vn.reconhub.core.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.Enums.Channel;
import vn.reconhub.core.domain.Enums.ExceptionStatus;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.Enums.LineStatus;
import vn.reconhub.core.domain.Enums.OrderStatus;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.matching.MatchingEngine;
import vn.reconhub.core.matching.MatchingEngine.Outcome;
import vn.reconhub.core.matching.PostMatchChecks;
import vn.reconhub.core.repo.AppUserRepository;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.repo.ImportFileRepository;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.repo.PaymentLineRepository;
import vn.reconhub.core.workflow.ExceptionWorkflowService;
import vn.reconhub.core.workflow.WorkflowException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Matching rules and the exception workflow against a real PostgreSQL with the Flyway schema.
 * Each test uses its own order codes, so tests do not depend on each other.
 */
@SpringBootTest(classes = TestApp.class)
@Testcontainers(disabledWithoutDocker = true)
class ReconFlowIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    @Autowired MatchingEngine engine;
    @Autowired PostMatchChecks checks;
    @Autowired ExceptionWorkflowService workflow;
    @Autowired OrderRepository orders;
    @Autowired PaymentLineRepository lines;
    @Autowired ImportFileRepository files;
    @Autowired ExceptionRepository exceptions;
    @Autowired AppUserRepository users;
    @Autowired TransactionTemplate tx;

    private final Instant now = Instant.parse("2026-10-22T03:00:00Z");
    private final MatchingEngine.Run run = new MatchingEngine.Run(now, LocalDate.ofInstant(now, VN), "test");

    @Test
    void carrierLineWithTrackingCodeAndRightAmountIsMatchedExactly() {
        Order o = carrierOrder("GHN", 485_000, false);
        PaymentLine line = line("GHN", o.getTrackingCode(), o.getCode(), o.getTrackingCode(), null, 458_000, 27_000);

        assertThat(match(line)).isEqualTo(Outcome.EXACT);
        assertThat(lines.findById(line.getId()).orElseThrow().getStatus()).isEqualTo(LineStatus.MATCHED);
    }

    @Test
    void shortPaymentBecomesAmountDifferenceWithBothAmounts() {
        Order o = carrierOrder("GHTK", 650_000, false);
        PaymentLine line = line("GHTK", o.getTrackingCode(), o.getCode(), o.getTrackingCode(), null, 600_000, 20_000);

        assertThat(match(line)).isEqualTo(Outcome.AMOUNT_DIFF);
        ReconException e = exceptionFor(line);
        assertThat(e.getType()).isEqualTo(ExceptionType.AMOUNT_DIFF);
        assertThat(e.getDiffAmount()).isEqualTo(-30_000);
        assertThat(e.getMeta()).containsEntry("paidAmount", 600_000).containsEntry("expectedAmount", 630_000);
    }

    @Test
    void transferWithoutOrderCodeMatchesOnlyWhenTheAmountIsUnique() {
        long amount = 7_000_000 + SEQ.incrementAndGet() * 1_000L; // amounts no other test uses
        Order unique = bankOrder(amount, now.minus(Duration.ofHours(5)));
        PaymentLine paid = line("VCB", "FT" + SEQ.incrementAndGet(), null, null, "CK tien hang", amount, 0);
        assertThat(match(paid)).isEqualTo(Outcome.AMOUNT_DATE);
        assertThat(orders.findById(unique.getCode())).isPresent();

        long twice = amount + 500;
        bankOrder(twice, now.minus(Duration.ofHours(6)));
        bankOrder(twice, now.minus(Duration.ofHours(7)));
        PaymentLine ambiguous = line("VCB", "FT" + SEQ.incrementAndGet(), null, null, "chuyen khoan", twice, 0);
        assertThat(match(ambiguous)).isEqualTo(Outcome.UNMATCHED);
    }

    @Test
    void sameReferenceInASecondStatementIsADuplicate() {
        Order o = bankOrder(321_000, now.minus(Duration.ofHours(3)));
        String ref = "FT" + SEQ.incrementAndGet();
        PaymentLine first = line("VCB", ref, null, null, "CK " + o.getCode(), 321_000, 0);
        PaymentLine copy = line("VCB", ref, null, null, "CK " + o.getCode(), 321_000, 0);

        assertThat(match(first)).isEqualTo(Outcome.EXACT);
        assertThat(match(copy)).isEqualTo(Outcome.DUPLICATE);
        assertThat(exceptionFor(copy).getType()).isEqualTo(ExceptionType.DUPLICATE);
    }

    @Test
    void largeWriteOffNeedsTheChiefAccountantAndTheCurrentVersion() {
        Order o = carrierOrder("GHN", 1_290_000, false);
        PaymentLine line = line("GHN", o.getTrackingCode(), o.getCode(), o.getTrackingCode(), null, 1_180_000, 28_450);
        assertThat(match(line)).isEqualTo(Outcome.AMOUNT_DIFF);
        String id = exceptionFor(line).getId();
        AppUser ha = users.findById("u1").orElseThrow();
        AppUser nam = users.findById("u3").orElseThrow();

        var result = workflow.requestWriteOff(id, version(id), "Khách chuyển thiếu", null, ha, now);
        assertThat(result.autoApproved()).isFalse();
        assertThat(exceptions.findById(id).orElseThrow().getStatus()).isEqualTo(ExceptionStatus.WRITE_OFF_PENDING);

        int v = version(id);
        assertThatThrownBy(() -> workflow.decideWriteOff(id, v, true, null, ha, now))
                .isInstanceOf(WorkflowException.class).extracting("kind").isEqualTo(WorkflowException.Kind.FORBIDDEN);
        assertThatThrownBy(() -> workflow.decideWriteOff(id, v - 1, true, null, nam, now))
                .isInstanceOf(WorkflowException.class).extracting("kind").isEqualTo(WorkflowException.Kind.CONFLICT);

        workflow.decideWriteOff(id, v, true, "Đồng ý", nam, now);
        assertThat(exceptions.findById(id).orElseThrow().getStatus()).isEqualTo(ExceptionStatus.WRITTEN_OFF);
        assertThat(lines.findById(line.getId()).orElseThrow().getStatus()).isEqualTo(LineStatus.MATCHED);
    }

    @Test
    void latePayoutClosesTheUnpaidOrderException() {
        Order o = carrierOrder("GHN", 399_000, false);
        tx.executeWithoutResult(s -> {
            Order managed = orders.findById(o.getCode()).orElseThrow();
            managed.setStatus(OrderStatus.DELIVERED);
            managed.setDeliveredAt(now.minus(Duration.ofDays(9)));
        });
        tx.executeWithoutResult(s -> checks.detectUnpaid(run));
        ReconException unpaid = exceptions.findAll().stream()
                .filter(e -> e.getType() == ExceptionType.UNPAID_ORDER)
                .filter(e -> tx.execute(s -> exceptions.findById(e.getId()).orElseThrow().getOrders().stream().anyMatch(x -> x.getCode().equals(o.getCode()))))
                .findFirst().orElseThrow();
        assertThat(unpaid.getStatus()).isEqualTo(ExceptionStatus.OPEN);

        PaymentLine late = line("GHN", o.getTrackingCode(), o.getCode(), o.getTrackingCode(), null, 372_000, 27_000);
        assertThat(match(late)).isEqualTo(Outcome.EXACT);
        assertThat(exceptions.findById(unpaid.getId()).orElseThrow().getStatus()).isEqualTo(ExceptionStatus.RESOLVED);
    }

    // ---------------------------------------------------------------- helpers

    private Outcome match(PaymentLine line) {
        return tx.execute(s -> engine.match(lines.findById(line.getId()).orElseThrow(), run));
    }

    private int version(String id) {
        return exceptions.findById(id).orElseThrow().getVersion();
    }

    private ReconException exceptionFor(PaymentLine line) {
        return tx.execute(s -> exceptions.findAll().stream()
                .filter(e -> e.getPayments().stream().anyMatch(p -> p.getId().equals(line.getId())))
                .findFirst().orElseThrow());
    }

    private Order carrierOrder(String carrier, long cod, boolean remote) {
        int n = SEQ.incrementAndGet();
        Order o = new Order(String.format("DH261020%04d", n), Channel.WEBSITE, "Khách " + n, cod, carrier, now.minus(Duration.ofDays(2)));
        o.setCodAmount(cod);
        o.setTrackingCode(carrier + "T" + n);
        o.setRemoteArea(remote);
        o.setStatus(OrderStatus.SHIPPING);
        return orders.save(o);
    }

    private Order bankOrder(long amount, Instant createdAt) {
        int n = SEQ.incrementAndGet();
        return orders.save(new Order(String.format("DH261021%04d", n), Channel.WEBSITE, "Khách " + n, amount, "VCB", createdAt));
    }

    private PaymentLine line(String source, String ref, String orderRef, String tracking, String description, long amount, long fee) {
        ImportFile file = files.save(new ImportFile(source, source + "_test_" + SEQ.incrementAndGet() + ".csv", "/tmp/none",
                now, LocalDate.ofInstant(now, VN), "sum" + SEQ.incrementAndGet(), FileStatus.COMPLETED, "test"));
        return lines.save(new PaymentLine(source, file.getId(), 2, ref, orderRef, tracking, description, amount, fee,
                now.minus(Duration.ofHours(1))));
    }
}
