package vn.reconhub.core.files;

import org.springframework.stereotype.Service;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.repo.ImportFileRepository;

import java.util.List;
import java.util.Optional;

/**
 * When a partner re-sends a file that failed to load (e.g. GHTK_COD_20261021_v2.csv after GHTK_COD_20261021.csv),
 * the failed run needs no further action. The one rule for "replaced", used by the dashboard, the batch monitor,
 * the restart guard and the failure explanation: a file from the same source for the same business date, received
 * later, that loaded. The earliest such file is the replacement.
 */
@Service
public class ReplacementFiles {
    private static final List<FileStatus> LOADED = List.of(FileStatus.COMPLETED, FileStatus.COMPLETED_WITH_ERRORS);

    private final ImportFileRepository files;

    public ReplacementFiles(ImportFileRepository files) {
        this.files = files;
    }

    public Optional<ImportFile> replacementFor(ImportFile failed) {
        return files.findFirstBySourceCodeAndBusinessDateAndReceivedAtAfterAndStatusInOrderByReceivedAtAsc(
                failed.getSourceCode(), failed.getBusinessDate(), failed.getReceivedAt(), LOADED);
    }

    public Optional<ImportFile> replacementFor(long fileId) {
        return files.findById(fileId).flatMap(this::replacementFor);
    }
}
