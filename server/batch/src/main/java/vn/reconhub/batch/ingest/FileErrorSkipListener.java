package vn.reconhub.batch.ingest;

import org.springframework.batch.core.SkipListener;
import vn.reconhub.core.domain.FileError;
import vn.reconhub.core.domain.PaymentLine;
import vn.reconhub.core.repo.FileErrorRepository;

/** Records every skipped row so people can see exactly what was not loaded. */
public class FileErrorSkipListener implements SkipListener<RawRow, PaymentLine> {
    private final FileErrorRepository errors;
    private final long fileId;

    public FileErrorSkipListener(FileErrorRepository errors, long fileId) {
        this.errors = errors;
        this.fileId = fileId;
    }

    @Override
    public void onSkipInProcess(RawRow item, Throwable t) {
        if (t instanceof RowException e) {
            errors.save(new FileError(fileId, e.rowNo(), e.column(), e.value(), e.getMessage()));
        } else {
            errors.save(new FileError(fileId, item.rowNo(), null, null, t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
        }
    }

    @Override
    public void onSkipInRead(Throwable t) {
        errors.save(new FileError(fileId, 0, null, null, "Không đọc được dòng: " + t.getMessage()));
    }
}
