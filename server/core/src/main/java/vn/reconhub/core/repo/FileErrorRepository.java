package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.reconhub.core.domain.FileError;

import java.util.List;

public interface FileErrorRepository extends JpaRepository<FileError, Long> {
    List<FileError> findTop200ByFileIdOrderByRowNo(Long fileId);

    long countByFileId(Long fileId);
}
