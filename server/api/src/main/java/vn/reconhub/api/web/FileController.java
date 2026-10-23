package vn.reconhub.api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vn.reconhub.core.messaging.JmsOutbox;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.reconhub.api.security.CurrentUser;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.AppUser;
import vn.reconhub.core.domain.Enums.SourceStatus;
import vn.reconhub.core.domain.Source;
import vn.reconhub.core.files.FileRegistrar;
import vn.reconhub.core.messaging.Messages;
import vn.reconhub.core.repo.SourceRepository;
import vn.reconhub.core.workflow.WorkflowException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * File intake. An upload is registered here (checksum, archive copy) and then loaded by the batch app,
 * which receives an INGEST command over JMS; the UI follows the job on the Files and Batch screens.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {
    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final ViewService views;
    private final FileRegistrar registrar;
    private final SourceRepository sources;
    private final JmsOutbox outbox;
    private final CurrentUser current;
    private final ReconProperties props;
    private final Clock clock;

    public FileController(ViewService views, FileRegistrar registrar, SourceRepository sources, JmsOutbox outbox, CurrentUser current,
                          ReconProperties props, Clock clock) {
        this.views = views;
        this.registrar = registrar;
        this.sources = sources;
        this.outbox = outbox;
        this.current = current;
        this.props = props;
        this.clock = clock;
    }

    @GetMapping
    public List<Views.FileView> list() {
        return views.files();
    }

    @GetMapping("/{id}/errors")
    public List<Views.FileErrorView> errors(@PathVariable long id) {
        return views.fileErrors(id);
    }

    @PostMapping(consumes = "multipart/form-data")
    public Views.FileView upload(@RequestParam("file") MultipartFile file, @RequestParam("source") String sourceCode) throws IOException {
        AppUser user = current.get();
        Source source = sources.findById(sourceCode)
                .orElseThrow(() -> new WorkflowException(WorkflowException.Kind.NOT_FOUND, "Không có nguồn " + sourceCode));
        if (source.getStatus() != SourceStatus.ACTIVE) {
            throw new WorkflowException(WorkflowException.Kind.INVALID, "Nguồn " + source.getName() + " chưa được kích hoạt; hãy hoàn tất cấu hình trước");
        }
        String name = Path.of(file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() ? "upload.csv" : file.getOriginalFilename())
                .getFileName().toString();
        String lower = name.toLowerCase();
        if (!lower.endsWith(".csv") && !lower.endsWith(".xlsx")) {
            throw new WorkflowException(WorkflowException.Kind.INVALID, "Chỉ nhận file .csv hoặc .xlsx");
        }
        Path tmp = props.getData().getRoot().resolve(".uploads").resolve(UUID.randomUUID() + "_" + name);
        Files.createDirectories(tmp.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, tmp);
        }
        FileRegistrar.Registered reg = registrar.register(tmp, source, name, Instant.now(clock), user.getFullName());
        Views.FileView view = Views.file(reg.file());
        if (!reg.duplicate()) {
            log.info("{} uploaded {} as file #{}", user.getUsername(), name, reg.file().getId());
            if (!outbox.send(Messages.BATCH_COMMANDS, Messages.BatchCommand.ingest(reg.file().getId(), user.getFullName()))) {
                return new Views.FileView(view.id(), view.source(), view.fileName(), view.receivedAt(), view.status(), 0, 0, 0, view.checksum(), null,
                        "Đã lưu file nhưng hàng đợi lệnh (Artemis) chưa phản hồi; batch sẽ nạp khi hàng đợi hoạt động lại.", view.uploadedBy());
            }
        }
        return view;
    }
}
