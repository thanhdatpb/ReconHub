package vn.reconhub.core.matching;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Enums.FeeScheduleStatus;
import vn.reconhub.core.domain.FeeSchedule;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.repo.FeeScheduleRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Expected partner fee of a payment line, from the source's fee schedule in force on the line's transaction date:
 * the approved version with the latest {@code effective_from} on or before that date, the higher version on a tie.
 * Sources without an approved schedule (e.g. bank transfers) have no expected fee.
 */
@Component
public class FeeCalculator {
    private static final Comparator<FeeSchedule> NEWEST_FIRST = Comparator.comparing(FeeSchedule::getEffectiveFrom)
            .thenComparing(FeeSchedule::getVersionNo).reversed();

    private final FeeScheduleRepository schedules;
    private final ZoneId zone;

    public FeeCalculator(FeeScheduleRepository schedules, ReconProperties props) {
        this.schedules = schedules;
        this.zone = props.getZone();
    }

    /** The fee and the schedule version it comes from. */
    public record Quote(long fee, FeeSchedule schedule) {}

    /** All approved schedules, read once per run and then used for every line. */
    @Transactional(readOnly = true)
    public Approved approved() {
        return new Approved(schedules.findByStatus(FeeScheduleStatus.APPROVED), zone);
    }

    public static final class Approved {
        private final Map<String, List<FeeSchedule>> bySource;
        private final ZoneId zone;

        public Approved(List<FeeSchedule> approved, ZoneId zone) {
            this.bySource = approved.stream().collect(Collectors.groupingBy(FeeSchedule::getSourceCode,
                    Collectors.collectingAndThen(Collectors.toList(), l -> l.stream().sorted(NEWEST_FIRST).toList())));
            this.zone = zone;
        }

        public Optional<FeeSchedule> inForce(String sourceCode, LocalDate day) {
            return bySource.getOrDefault(sourceCode, List.of()).stream().filter(s -> !s.getEffectiveFrom().isAfter(day)).findFirst();
        }

        public Optional<Quote> quote(String sourceCode, Order order, PaymentLine line) {
            return inForce(sourceCode, line.getTxnAt().atZone(zone).toLocalDate())
                    .map(s -> new Quote(FeeFormula.fee(s.getComponents(), order, line.gross()), s));
        }
    }
}
