package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.ImportFile;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ImportFileRepository extends JpaRepository<ImportFile, Long> {
    Optional<ImportFile> findFirstByChecksumAndStatusNot(String checksum, FileStatus status);

    List<ImportFile> findTop500ByOrderByReceivedAtDesc();

    List<ImportFile> findByReceivedAtGreaterThanEqual(Instant from);

    List<ImportFile> findByStatusIn(List<FileStatus> statuses);

    /** Registered by the API but never handed to a job, e.g. because the broker was down. */
    List<ImportFile> findByStatusAndJobExecutionIdIsNullAndReceivedAtBefore(FileStatus status, Instant before);

    long countBySourceCodeAndBusinessDateGreaterThanEqual(String sourceCode, java.time.LocalDate from);
}
