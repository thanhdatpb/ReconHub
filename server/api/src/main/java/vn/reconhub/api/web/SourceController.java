package vn.reconhub.api.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.reconhub.api.security.CurrentUser;
import vn.reconhub.core.ai.MappingSuggester;
import vn.reconhub.core.domain.MappingRow;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.repo.SourceRepository;
import vn.reconhub.core.workflow.WorkflowException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Partner sources (column mapping, fee rules) and the OMS order search used for manual matching. */
@RestController
@RequestMapping("/api")
public class SourceController {
    private final SourceRepository sources;
    private final MappingSuggester suggester;
    private final ViewService views;
    private final CurrentUser current;
    private final Clock clock;

    public SourceController(SourceRepository sources, MappingSuggester suggester, ViewService views, CurrentUser current, Clock clock) {
        this.sources = sources;
        this.suggester = suggester;
        this.views = views;
        this.current = current;
        this.clock = clock;
    }

    @GetMapping("/sources")
    public List<Views.SourceView> list() {
        return views.sources();
    }

    @PostMapping("/sources/{code}/mapping/suggest")
    public List<MappingRow> suggest(@PathVariable String code) {
        return suggester.suggest(find(code), current.get().getId());
    }

    /** Changing how files are read changes what money is loaded, so accountants cannot do it alone. */
    @PutMapping("/sources/{code}/mapping")
    @PreAuthorize("hasAnyRole('ADMIN', 'APPROVER')")
    @Transactional
    public Views.SourceView saveMapping(@PathVariable String code, @RequestBody List<MappingRow> mapping) {
        Source s = find(code);
        for (MappingRow m : mapping) {
            if (!MappingRow.FIELDS.contains(m.field())) throw new WorkflowException(WorkflowException.Kind.INVALID, "Trường không hợp lệ: " + m.field());
            if (m.column() != null && !s.getHeaders().contains(m.column())) {
                throw new WorkflowException(WorkflowException.Kind.INVALID, "File của " + s.getName() + " không có cột \"" + m.column() + "\"");
            }
        }
        for (String required : MappingRow.REQUIRED) {
            boolean mapped = mapping.stream().anyMatch(m -> m.field().equals(required) && m.column() != null);
            if (!mapped) throw new WorkflowException(WorkflowException.Kind.INVALID, "Chưa chọn cột cho trường bắt buộc " + required);
        }
        s.updateMapping(mapping, current.get().getFullName(), Instant.now(clock));
        return Views.source(sources.save(s));
    }

    @GetMapping("/orders")
    public List<Views.OrderView> orders(@RequestParam(defaultValue = "") String q) {
        return views.orders(q);
    }

    private Source find(String code) {
        return sources.findById(code).orElseThrow(() -> new WorkflowException(WorkflowException.Kind.NOT_FOUND, "Không có nguồn " + code));
    }
}
