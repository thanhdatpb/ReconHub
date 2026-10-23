package vn.reconhub.api.web;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.domain.ReconException;
import vn.reconhub.core.repo.FileErrorRepository;
import vn.reconhub.core.repo.ImportFileRepository;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.repo.SourceRepository;
import vn.reconhub.core.report.ExceptionQuery;
import vn.reconhub.core.report.ReportService;
import vn.reconhub.core.report.Rows;
import vn.reconhub.core.workflow.ExceptionWorkflowService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read side: builds the JSON views inside one read-only transaction so lazy collections can be walked. */
@Service
@Transactional(readOnly = true)
public class ViewService {
    private static final List<String> SOURCE_ORDER = List.of("GHN", "GHTK", "SHOPEE", "TIKTOK", "VCB", "MOMO", "VTP");

    private final ExceptionWorkflowService workflow;
    private final ReportService reports;
    private final ImportFileRepository files;
    private final FileErrorRepository fileErrors;
    private final SourceRepository sources;
    private final OrderRepository orders;

    public ViewService(ExceptionWorkflowService workflow, ReportService reports, ImportFileRepository files, FileErrorRepository fileErrors,
                       SourceRepository sources, OrderRepository orders) {
        this.workflow = workflow;
        this.reports = reports;
        this.files = files;
        this.fileErrors = fileErrors;
        this.sources = sources;
        this.orders = orders;
    }

    public Views.ExceptionView exception(String id) {
        ReconException e = workflow.get(id);
        Map<Long, String> fileNames = new HashMap<>();
        List<Views.PaymentView> payments = new ArrayList<>();
        for (PaymentLine p : e.getPayments()) {
            String name = fileNames.computeIfAbsent(p.getFileId(), fid -> files.findById(fid).map(ImportFile::getFileName).orElse(""));
            payments.add(Views.payment(p, name));
        }
        return new Views.ExceptionView(e.getId(), e.getType().name(), e.getSourceCode(), e.getStatus().name(), e.getDiffAmount(),
                e.getCreatedAt(), e.getSlaDueAt(), e.getAssigneeId(), e.getOrders().stream().map(Views::order).toList(), payments,
                e.getMeta(), e.getAi(), e.getActions().stream().map(Views::action).toList(), e.getWriteOff(), e.getVersion());
    }

    /** List rows carry only the first order and payment, which is all the queue shows. */
    public List<Views.ExceptionView> exceptions(ExceptionQuery q) {
        return reports.searchExceptions(q).stream().map(ViewService::row).toList();
    }

    static Views.ExceptionView row(Rows.ExceptionRow r) {
        List<Views.OrderView> o = r.orderCode == null ? List.of()
                : List.of(new Views.OrderView(r.orderCode, null, r.customerName, null, 0, 0, null, r.trackingCode, null, null, null));
        List<Views.PaymentView> p = r.externalRef == null ? List.of()
                : List.of(new Views.PaymentView(null, r.sourceCode, r.externalRef, r.description == null ? "" : r.description, 0, 0, null, null));
        Object ai = r.aiConfidence == null ? null : Map.of("confidence", r.aiConfidence);
        return new Views.ExceptionView(r.id, r.type, r.sourceCode, r.status, r.diffAmount, r.createdAt, r.slaDueAt, r.assigneeId, o, p,
                Map.of(), ai, List.of(), null, r.version);
    }

    public List<Views.FileView> files() {
        return files.findTop500ByOrderByReceivedAtDesc().stream().map(Views::file).toList();
    }

    public Views.FileView file(long id) {
        return files.findById(id).map(Views::file).orElseThrow(() -> new IllegalArgumentException("Không có file #" + id));
    }

    public List<Views.FileErrorView> fileErrors(long fileId) {
        return fileErrors.findTop200ByFileIdOrderByRowNo(fileId).stream().map(Views::fileError).toList();
    }

    public List<Views.SourceView> sources() {
        return sources.findAll().stream()
                .sorted(Comparator.comparingInt(s -> SOURCE_ORDER.indexOf(s.getCode()) < 0 ? 99 : SOURCE_ORDER.indexOf(s.getCode())))
                .map(Views::source).toList();
    }

    /** Free text over code, customer and tracking code; a number also matches the order amount. */
    public List<Views.OrderView> orders(String q) {
        String text = q == null ? "" : q.trim();
        long amount = -1;
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.length() >= 4 && digits.length() <= 12 && !text.matches(".*[A-Za-z].*")) amount = Long.parseLong(digits);
        List<Order> found = orders.search(text, amount);
        return found.stream().map(Views::order).toList();
    }
}
