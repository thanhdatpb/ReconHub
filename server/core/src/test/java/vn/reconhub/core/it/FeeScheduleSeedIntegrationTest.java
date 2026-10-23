package vn.reconhub.core.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.reconhub.core.domain.Enums.Channel;
import vn.reconhub.core.domain.Enums.FeeScheduleStatus;
import vn.reconhub.core.domain.FeeSchedule;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.matching.FeeFormula;
import vn.reconhub.core.repo.FeeScheduleRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Version 1 of the fee schedules, as seeded by V6, must give exactly the fees of the hard-coded formulas it replaces
 * ({@link LegacyFees}, copied from FeeCalculator before the change), on many orders and on the rounding edges.
 */
@SpringBootTest(classes = TestApp.class)
@Testcontainers(disabledWithoutDocker = true)
class FeeScheduleSeedIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final List<String> SOURCES = List.of("GHN", "GHTK", "SHOPEE", "TIKTOK", "MOMO");

    @Autowired FeeScheduleRepository schedules;

    /** FeeCalculator.expectedFee before fee schedules, kept as the reference. */
    static final class LegacyFees {
        static long expectedFee(String sourceCode, Order order, long lineGross) {
            return switch (sourceCode) {
                case "GHN" -> 22_000 + Math.max(5_000, halfUp(order.getCodAmount() * 5, 1_000)) + (order.isRemoteArea() ? 8_000 : 0);
                case "GHTK" -> order.getAmount() > 900_000 ? 30_000 : 20_000;
                case "SHOPEE" -> halfUp(order.getAmount() * 400, 10_000) + halfUp(order.getAmount() * 491, 10_000);
                case "TIKTOK" -> halfUp(order.getAmount() * 5, 100);
                case "MOMO" -> halfUp(lineGross, 100);
                default -> -1;
            };
        }

        static long halfUp(long numerator, long denominator) {
            return (numerator + denominator / 2) / denominator;
        }
    }

    record Case(long amount, long cod, boolean remote, long gross) {}

    @Test
    void versionOneOfEverySourceMatchesTheOldFormulasExactly() {
        Map<String, FeeSchedule> v1 = schedules.findByStatus(FeeScheduleStatus.APPROVED).stream()
                .filter(s -> s.getVersionNo() == 1).collect(Collectors.toMap(FeeSchedule::getSourceCode, s -> s));
        assertThat(v1.keySet()).containsExactlyInAnyOrderElementsOf(SOURCES);
        v1.values().forEach(s -> assertThat(s.getEffectiveFrom()).isEqualTo(FeeSchedule.FROM_THE_START));

        List<Case> cases = cases();
        int compared = 0;
        for (String source : SOURCES) {
            for (Case c : cases) {
                Order o = new Order("DH1", Channel.WEBSITE, "Khách", c.amount(), source, Instant.parse("2026-10-22T03:00:00Z"));
                o.setCodAmount(c.cod());
                o.setRemoteArea(c.remote());
                long expected = LegacyFees.expectedFee(source, o, c.gross());
                long actual = FeeFormula.fee(v1.get(source).getComponents(), o, c.gross());
                assertThat(actual).as("%s %s", source, c).isEqualTo(expected);
                compared++;
            }
        }
        assertThat(compared).isGreaterThan(20_000);
    }

    /** 4.000 random orders plus the edges: thresholds, minimums, and amounts whose fee ends in exactly ,5 đồng. */
    static List<Case> cases() {
        Random r = new Random(20261023);
        List<Case> out = new ArrayList<>();
        for (int i = 0; i < 4_000; i++) {
            long amount = 10_000 + r.nextInt(5_000_000);
            long cod = r.nextInt(4) == 0 ? r.nextInt((int) amount + 1) : amount;
            out.add(new Case(amount, cod, r.nextInt(6) == 0, 1_000 + r.nextInt(20_000_000)));
        }
        long[] edges = {0, 1, 99, 100, 150, 250, 899_999, 900_000, 900_001, 999_900, 999_999, 1_000_000, 1_000_001, 1_000_100,
                1_000_199, 1_000_200, 1_290_000, 1_290_100, 1_500_000, 1_500_001, 399_000, 485_000, 359_000};
        for (long e : edges) {
            out.add(new Case(e, e, false, e));
            out.add(new Case(e, e, true, e));
        }
        // fee = x,5 đồng: 0,5% of COD, 4,91% and 5% of the order, 1% of the line
        LongStream.rangeClosed(1, 400_000).filter(a -> a * 5 % 1_000 == 500).limit(30).forEach(a -> out.add(new Case(a, a, false, a)));
        LongStream.rangeClosed(1, 400_000).filter(a -> a * 491 % 10_000 == 5_000).limit(30).forEach(a -> out.add(new Case(a, a, false, a)));
        LongStream.rangeClosed(1, 400_000).filter(a -> a * 5 % 100 == 50).limit(30).forEach(a -> out.add(new Case(a, a, false, a)));
        LongStream.rangeClosed(1, 400_000).filter(a -> a % 100 == 50).limit(30).forEach(a -> out.add(new Case(a, a, false, a)));
        return out;
    }
}
