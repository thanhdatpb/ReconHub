package vn.reconhub.core.workflow;

import org.springframework.stereotype.Component;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Enums.ActionKind;
import vn.reconhub.core.domain.Enums.ExceptionType;
import vn.reconhub.core.domain.Enums.LineStatus;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.repo.ExceptionRepository;
import vn.reconhub.core.util.Codes;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Creates exceptions with an id, an SLA and the first history entry. */
@Component
public class ExceptionFactory {
    private final ExceptionRepository exceptions;
    private final ReconProperties props;

    public ExceptionFactory(ExceptionRepository exceptions, ReconProperties props) {
        this.exceptions = exceptions;
        this.props = props;
    }

    public ReconException create(ExceptionType type, String sourceCode, long diff, LocalDate runDate, Instant at, String actor,
                                 String note, List<Order> orders, List<PaymentLine> lines, Map<String, Object> meta) {
        String id = Codes.exceptionId(runDate, exceptions.nextSequence());
        ReconException e = new ReconException(id, type, sourceCode, diff, runDate, at, at.plus(Duration.ofHours(props.getSlaHours())));
        e.getOrders().addAll(orders);
        e.getPayments().addAll(lines);
        if (meta != null) e.getMeta().putAll(meta);
        // The FEE_DIFF line stays MATCHED: the money is right, only the fee is disputed.
        if (type != ExceptionType.FEE_DIFF) lines.forEach(l -> l.setStatus(LineStatus.EXCEPTION));
        e.log(at, actor, ActionKind.CREATED, note);
        return exceptions.save(e);
    }
}
