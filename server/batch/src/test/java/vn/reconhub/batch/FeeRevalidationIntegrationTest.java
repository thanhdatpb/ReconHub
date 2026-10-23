package vn.reconhub.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.reconhub.batch.pipeline.ReconPipeline;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.Enums.ActionKind;
import vn.reconhub.core.domain.Enums.Channel;
import vn.reconhub.core.domain.Enums.ExceptionStatus;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.Enums.FeeScheduleStatus;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.Enums.OrderStatus;
import vn.reconhub.core.domain.ExceptionAction;
import vn.reconhub.core.domain.FeeComponent;
import vn.reconhub.core.domain.FeeSchedule;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.fees.FeeScheduleService;
import vn.reconhub.core.repo.AppUserRepository;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.repo.FeeScheduleRepository;
import vn.reconhub.core.repo.ImportFileRepository;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.repo.PaymentLineRepository;
import vn.reconhub.core.workflow.ExceptionWorkflowService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Roadmap 1.2 "done when": GHN starts charging 2.000 ₫ more on COD above 1.500.000 ₫ from 13/10. Matching and the
 * fee check raise FEE_DIFF with version 1; once version 2 is approved, feeRevalidationJob closes exactly the
 * differences version 2 explains and leaves every other one as it was.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class FeeRevalidationIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired ReconPipeline pipeline;
    @Autowired FeeScheduleService feeSchedules;
    @Autowired FeeScheduleRepository schedules;
    @Autowired ExceptionWorkflowService workflow;
    @Autowired ExceptionRepository exceptions;
    @Autowired OrderRepository orders;
    @Autowired PaymentLineRepository lines;
    @Autowired ImportFileRepository files;
    @Autowired AppUserRepository users;
    @Autowired TransactionTemplate tx;

    @Test
    void approvedVersionClosesOnlyTheDifferencesItExplains() {
        // v1 fees: 2.000.000 -> 32.000; 1.800.000 -> 31.000; 1.200.000 -> 28.000; 2.500.000 -> 34.500
        PaymentLine bigCod = paid(2_000_000, false, 34_000, "2026-10-15T10:00");        // v2 fee: closes
        PaymentLine claimed = paid(1_800_000, false, 33_000, "2026-10-15T11:00");       // v2 fee, IN_REVIEW: closes
        PaymentLine underThreshold = paid(1_200_000, false, 30_000, "2026-10-15T12:00"); // +2.000 but COD below 1.500.000: open
        PaymentLine remoteToo = paid(2_000_000, false, 42_000, "2026-10-15T13:00");     // also 8.000 remote fee OMS did not flag: open
        PaymentLine beforeChange = paid(2_000_000, false, 34_000, "2026-10-12T16:00");  // paid before 13/10: open
        PaymentLine pendingWriteOff = paid(2_500_000, false, 36_500, "2026-10-15T14:00"); // waiting for a write-off approval: untouched

        pipeline.matchAndCheck(Instant.parse("2026-10-16T01:00:00Z"), LocalDate.of(2026, 10, 16), "test");
        List<PaymentLine> all = List.of(bigCod, claimed, underThreshold, remoteToo, beforeChange, pendingWriteOff);
        for (PaymentLine l : all) {
            assertThat(exceptionFor(l).getType()).isEqualTo(ExceptionType.FEE_DIFF);
            assertThat(exceptionFor(l).getStatus()).isEqualTo(ExceptionStatus.OPEN);
        }
        AppUser ha = users.findByUsername("ha").orElseThrow();
        AppUser nam = users.findByUsername("nam").orElseThrow();
        ReconException toClaim = exceptionFor(claimed);
        workflow.sendClaim(toClaim.getId(), toClaim.getVersion(), "Đã hỏi GHN về phí", ha, Instant.parse("2026-10-16T02:00:00Z"), false);
        tx.executeWithoutResult(s -> exceptions.findById(exceptionFor(pendingWriteOff).getId()).orElseThrow()
                .setStatus(ExceptionStatus.WRITE_OFF_PENDING));

        FeeSchedule v1 = schedules.findByStatus(FeeScheduleStatus.APPROVED).stream()
                .filter(s -> s.getSourceCode().equals("GHN")).findFirst().orElseThrow();
        List<FeeComponent> v2Components = new ArrayList<>(v1.getComponents());
        v2Components.add(FeeComponent.fixed("Phụ phí COD lớn", 2_000, new FeeComponent.Condition(null, FeeComponent.Base.COD, 1_500_000L, null)));
        FeeSchedule proposed = feeSchedules.propose("GHN", LocalDate.of(2026, 10, 13), v2Components, "GHN thu thêm 2.000 ₫ đơn COD lớn",
                null, ha, Instant.parse("2026-10-16T03:00:00Z"));
        FeeSchedule v2 = feeSchedules.approve(proposed.getId(), proposed.getVersion(), null, nam, Instant.parse("2026-10-16T03:30:00Z"));

        JobExecution run = pipeline.feeRevalidation(v2.getId(), nam.getFullName());

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run.getStepExecutions().iterator().next().getWriteCount()).isEqualTo(2);
        for (PaymentLine l : List.of(bigCod, claimed)) {
            ReconException e = exceptionFor(l);
            assertThat(e.getStatus()).isEqualTo(ExceptionStatus.RESOLVED);
            ExceptionAction last = e.getActions().get(e.getActions().size() - 1);
            assertThat(last.getKind()).isEqualTo(ActionKind.FEE_RULE_UPDATED);
            assertThat(last.getActor()).isEqualTo("feeRevalidationJob #" + run.getId());
            assertThat(last.getNote()).startsWith("Theo biểu phí phiên bản 2 (áp dụng từ 13/10/2026, Lê Hoàng Nam duyệt): phí đúng ");
        }
        assertThat(exceptionFor(underThreshold).getStatus()).isEqualTo(ExceptionStatus.OPEN);
        assertThat(exceptionFor(remoteToo).getStatus()).isEqualTo(ExceptionStatus.OPEN);
        assertThat(exceptionFor(beforeChange).getStatus()).isEqualTo(ExceptionStatus.OPEN);
        assertThat(exceptionFor(pendingWriteOff).getStatus()).isEqualTo(ExceptionStatus.WRITE_OFF_PENDING);

        // one run per approved version
        assertThatThrownBy(() -> pipeline.feeRevalidation(v2.getId(), nam.getFullName())).isInstanceOf(IllegalStateException.class);
    }

    /** A GHN COD order and its settled line, charged {@code fee}, paid at a local date and time. */
    private PaymentLine paid(long cod, boolean remote, long fee, String localDateTime) {
        int n = SEQ.incrementAndGet();
        Order o = new Order(String.format("DH261013%04d", n), Channel.WEBSITE, "Khách " + n, cod, "GHN", Instant.parse("2026-10-10T03:00:00Z"));
        o.setCodAmount(cod);
        o.setRemoteArea(remote);
        o.setTrackingCode("GHNFEE" + n);
        o.setStatus(OrderStatus.SHIPPING);
        orders.save(o);
        Instant at = LocalDateTime.parse(localDateTime).atZone(VN).toInstant();
        ImportFile file = files.save(new ImportFile("GHN", "GHN_DOISOAT_fee_" + n + ".xlsx", null, at, at.atZone(VN).toLocalDate(),
                "fee-" + n, FileStatus.COMPLETED, "test"));
        return lines.save(new PaymentLine("GHN", file.getId(), 2, o.getTrackingCode(), o.getCode(), o.getTrackingCode(), "Giao thành công",
                cod - fee, fee, at));
    }

    private ReconException exceptionFor(PaymentLine line) {
        return tx.execute(s -> {
            ReconException e = exceptions.findAll().stream()
                    .filter(x -> x.getPayments().stream().anyMatch(p -> p.getId().equals(line.getId())))
                    .findFirst().orElseThrow();
            e.getActions().size();
            return e;
        });
    }
}
