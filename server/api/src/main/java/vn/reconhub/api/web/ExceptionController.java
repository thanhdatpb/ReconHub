package vn.reconhub.api.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.reconhub.api.security.CurrentUser;
import vn.reconhub.core.report.ExceptionQuery;
import vn.reconhub.core.report.ReportService;
import vn.reconhub.core.report.Rows;
import vn.reconhub.core.workflow.ExceptionWorkflowService;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** The exception queue and every action on an exception. Writes carry the version the user saw (optimistic locking). */
@RestController
@RequestMapping("/api/exceptions")
public class ExceptionController {
    private final ExceptionWorkflowService workflow;
    private final ViewService views;
    private final ReportService reports;
    private final CurrentUser current;
    private final Clock clock;

    public ExceptionController(ExceptionWorkflowService workflow, ViewService views, ReportService reports, CurrentUser current, Clock clock) {
        this.workflow = workflow;
        this.views = views;
        this.reports = reports;
        this.current = current;
        this.clock = clock;
    }

    @GetMapping
    public List<Views.ExceptionView> list(@RequestParam(defaultValue = "todo") String tab,
                                          @RequestParam(required = false) List<String> type,
                                          @RequestParam(required = false) List<String> source,
                                          @RequestParam(defaultValue = "all") String assignee,
                                          @RequestParam(defaultValue = "false") boolean overdue,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "500") int limit) {
        return views.exceptions(new ExceptionQuery(tab, split(type), split(source), assignee, overdue, q, Instant.now(clock), Math.min(limit, 2000)));
    }

    @GetMapping("/counts")
    public Rows.ExceptionCounts counts() {
        return reports.exceptionCounts();
    }

    @GetMapping("/{id}")
    public Views.ExceptionView get(@PathVariable String id) {
        return views.exception(id);
    }

    public record AssignRequest(@NotEmpty List<String> ids, String assigneeId) {}

    @PostMapping("/assign")
    public ResponseEntity<Void> assign(@Valid @RequestBody AssignRequest req) {
        workflow.assign(req.ids(), req.assigneeId(), current.get(), Instant.now(clock));
        return ResponseEntity.noContent().build();
    }

    public record MatchRequest(@NotNull Integer version, @NotEmpty List<String> orderCodes, boolean viaAi) {}

    @PostMapping("/{id}/match")
    public Views.ExceptionView match(@PathVariable String id, @Valid @RequestBody MatchRequest req) {
        workflow.match(id, req.version(), req.orderCodes(), req.viaAi(), current.get(), Instant.now(clock));
        return views.exception(id);
    }

    public record WriteOffRequest(@NotNull Integer version, @NotNull String reason, String note) {}

    public record WriteOffResponse(boolean autoApproved, Views.ExceptionView exception) {}

    @PostMapping("/{id}/write-off")
    public WriteOffResponse writeOff(@PathVariable String id, @Valid @RequestBody WriteOffRequest req) {
        var result = workflow.requestWriteOff(id, req.version(), req.reason(), req.note(), current.get(), Instant.now(clock));
        return new WriteOffResponse(result.autoApproved(), views.exception(id));
    }

    public record DecisionRequest(@NotNull Integer version, boolean approve, String note) {}

    @PostMapping("/{id}/write-off/decision")
    public Views.ExceptionView decide(@PathVariable String id, @Valid @RequestBody DecisionRequest req) {
        workflow.decideWriteOff(id, req.version(), req.approve(), req.note(), current.get(), Instant.now(clock));
        return views.exception(id);
    }

    public record NoteRequest(@NotNull Integer version, String note) {}

    @PostMapping("/{id}/claim")
    public Views.ExceptionView claim(@PathVariable String id, @Valid @RequestBody NoteRequest req) {
        workflow.sendClaim(id, req.version(), req.note(), current.get(), Instant.now(clock), true);
        return views.exception(id);
    }

    public record VersionRequest(@NotNull Integer version) {}

    @PostMapping("/{id}/duplicate")
    public Views.ExceptionView duplicate(@PathVariable String id, @Valid @RequestBody VersionRequest req) {
        workflow.markDuplicate(id, req.version(), current.get(), Instant.now(clock));
        return views.exception(id);
    }

    public record CommentRequest(@NotNull Integer version, String text) {}

    @PostMapping("/{id}/comments")
    public Views.ExceptionView comment(@PathVariable String id, @Valid @RequestBody CommentRequest req) {
        workflow.comment(id, req.version(), req.text(), current.get(), Instant.now(clock));
        return views.exception(id);
    }

    /** Asks for a fresh suggestion, through the LLM when one is configured. */
    @PostMapping("/{id}/suggestion")
    public Views.ExceptionView suggest(@PathVariable String id) {
        workflow.refreshSuggestion(id, current.get(), Instant.now(clock));
        return views.exception(id);
    }

    /** Accepts both ?type=A&type=B and ?type=A,B. */
    private static List<String> split(List<String> values) {
        if (values == null) return List.of();
        return values.stream().flatMap(v -> Arrays.stream(v.split(","))).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
