package vn.reconhub.core.matching;

import org.junit.jupiter.api.Test;
import vn.reconhub.core.domain.Enums.Channel;
import vn.reconhub.core.domain.FeeComponent;
import vn.reconhub.core.domain.FeeComponent.Base;
import vn.reconhub.core.domain.FeeComponent.Condition;
import vn.reconhub.core.domain.FeeSchedule;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which schedule version applies to a payment line, by the line's transaction date in Vietnam. */
class FeeCalculatorTest {
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final List<FeeComponent> V1 = List.of(
            FeeComponent.fixed("Cước giao", 22_000, null),
            FeeComponent.percent("Phí thu hộ", "0.5", Base.COD, 5_000L, null),
            FeeComponent.fixed("Phụ phí vùng xa", 8_000, new Condition(true, null, null, null)));

    private static List<FeeComponent> plusLargeCod() {
        List<FeeComponent> v2 = new ArrayList<>(V1);
        v2.add(FeeComponent.fixed("Phụ phí COD lớn", 2_000, new Condition(null, Base.COD, 1_500_000L, null)));
        return v2;
    }

    private static FeeSchedule approved(int versionNo, LocalDate from, List<FeeComponent> components) {
        FeeSchedule s = new FeeSchedule("GHN", from, components, "test", null, null, Instant.EPOCH);
        s.approve(versionNo, null, Instant.EPOCH, null);
        return s;
    }

    private static Order order(long cod) {
        Order o = new Order("DH2610150123", Channel.WEBSITE, "Khách", cod, "GHN", Instant.now());
        o.setCodAmount(cod);
        return o;
    }

    /** A GHN line paid at the given local date and time. */
    private static PaymentLine line(String localDateTime, long cod) {
        return new PaymentLine("GHN", 1L, 1, "GHN1", "DH1", "GHN1", null, cod, 0, LocalDateTime.parse(localDateTime).atZone(VN).toInstant());
    }

    private final FeeCalculator.Approved schedules = new FeeCalculator.Approved(List.of(
            approved(1, FeeSchedule.FROM_THE_START, V1),
            approved(2, LocalDate.of(2026, 10, 13), plusLargeCod())), VN);

    @Test
    void theVersionInForceOnTheTransactionDateIsUsed() {
        Order big = order(2_000_000);
        var before = schedules.quote("GHN", big, line("2026-10-12T23:59:00", 2_000_000)).orElseThrow();
        assertThat(before.schedule().getVersionNo()).isEqualTo(1);
        assertThat(before.fee()).isEqualTo(32_000);
        // 00:30 on the 13th in Vietnam is still the 12th in UTC: the local date decides
        var onTheDay = schedules.quote("GHN", big, line("2026-10-13T00:30:00", 2_000_000)).orElseThrow();
        assertThat(onTheDay.schedule().getVersionNo()).isEqualTo(2);
        assertThat(onTheDay.fee()).isEqualTo(34_000);
        assertThat(schedules.quote("GHN", order(1_290_000), line("2026-10-20T10:00:00", 1_290_000)).orElseThrow().fee()).isEqualTo(28_450);
    }

    @Test
    void onTheSameEffectiveDateTheHigherVersionWins() {
        var sameDay = new FeeCalculator.Approved(List.of(
                approved(2, LocalDate.of(2026, 10, 13), V1),
                approved(3, LocalDate.of(2026, 10, 13), plusLargeCod())), VN);
        assertThat(sameDay.inForce("GHN", LocalDate.of(2026, 10, 13)).orElseThrow().getVersionNo()).isEqualTo(3);
    }

    @Test
    void noApprovedScheduleMeansNoExpectedFee() {
        assertThat(schedules.quote("VCB", order(500_000), line("2026-10-20T10:00:00", 500_000))).isEmpty();
        var later = new FeeCalculator.Approved(List.of(approved(1, LocalDate.of(2026, 11, 1), V1)), VN);
        assertThat(later.inForce("GHN", LocalDate.of(2026, 10, 31))).isEmpty();
    }
}
