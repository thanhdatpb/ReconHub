package vn.reconhub.api.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.reconhub.api.security.CurrentUser;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.FeeComponent;
import vn.reconhub.core.domain.FeeSchedule;
import vn.reconhub.core.fees.FeeScheduleService;
import vn.reconhub.core.matching.FeeFormula;
import vn.reconhub.core.repo.AppUserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Fee schedule versions of a source: history, proposals by anyone, decisions by a chief accountant or admin. */
@RestController
@RequestMapping("/api")
public class FeeScheduleController {
    private final FeeScheduleService service;
    private final AppUserRepository users;
    private final CurrentUser current;
    private final Clock clock;

    public FeeScheduleController(FeeScheduleService service, AppUserRepository users, CurrentUser current, Clock clock) {
        this.service = service;
        this.users = users;
        this.current = current;
        this.clock = clock;
    }

    /**
     * {@code fromTheStart}: version 1 applies to every earlier payment, shown as "Áp dụng từ đầu".
     * {@code description}: the components in words, e.g. "Cước giao 22.000 ₫; Phí thu hộ 0,5% COD, tối thiểu 5.000 ₫".
     */
    public record FeeScheduleView(long id, String source, Integer versionNo, String status, LocalDate effectiveFrom, boolean fromTheStart,
                                  List<FeeComponent> components, String description, String note, String fromException,
                                  String proposedBy, String proposedByName, Instant proposedAt, String decidedBy, String decidedByName,
                                  Instant decidedAt, String decisionNote, int version) {}

    public record ProposeRequest(LocalDate effectiveFrom, List<FeeComponent> components, String note, String fromException) {}

    public record DecideRequest(@NotNull Integer version, String note) {}

    @GetMapping("/sources/{code}/fee-schedules")
    public List<FeeScheduleView> history(@PathVariable String code) {
        Map<String, String> names = names();
        return service.history(code).stream().map(s -> view(s, names)).toList();
    }

    @PostMapping("/sources/{code}/fee-schedules")
    public FeeScheduleView propose(@PathVariable String code, @RequestBody ProposeRequest req) {
        AppUser me = current.get();
        return view(service.propose(code, req.effectiveFrom(), req.components(), req.note(), blankToNull(req.fromException()), me,
                Instant.now(clock)), names());
    }

    /** "Đề xuất cập nhật biểu phí" on an AI suggestion: a filled-in proposal to review, or 400 with why none can be inferred. */
    @GetMapping("/exceptions/{id}/fee-proposal")
    public FeeScheduleService.Draft proposalFromException(@PathVariable String id) {
        return service.draftFromException(id);
    }

    @PostMapping("/fee-schedules/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'APPROVER')")
    public FeeScheduleView approve(@PathVariable long id, @Valid @RequestBody DecideRequest req) {
        return view(service.approve(id, req.version(), req.note(), current.get(), Instant.now(clock)), names());
    }

    @PostMapping("/fee-schedules/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'APPROVER')")
    public FeeScheduleView reject(@PathVariable long id, @Valid @RequestBody DecideRequest req) {
        return view(service.reject(id, req.version(), req.note(), current.get(), Instant.now(clock)), names());
    }

    private Map<String, String> names() {
        return users.findAll().stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
    }

    static FeeScheduleView view(FeeSchedule s, Map<String, String> names) {
        return new FeeScheduleView(s.getId(), s.getSourceCode(), s.getVersionNo(), s.getStatus().name(), s.getEffectiveFrom(),
                FeeSchedule.FROM_THE_START.equals(s.getEffectiveFrom()), s.getComponents(), FeeFormula.describe(s.getComponents()),
                s.getNote(), s.getFromException(), s.getProposedBy(), s.getProposedBy() == null ? null : names.get(s.getProposedBy()),
                s.getProposedAt(), s.getDecidedBy(), s.getDecidedBy() == null ? null : names.get(s.getDecidedBy()), s.getDecidedAt(),
                s.getDecisionNote(), s.getVersion());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
