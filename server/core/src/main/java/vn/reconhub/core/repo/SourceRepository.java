package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.reconhub.core.domain.Enums.SourceStatus;
import vn.reconhub.core.domain.Source;

import java.util.List;

public interface SourceRepository extends JpaRepository<Source, String> {
    List<Source> findByStatusOrderByCode(SourceStatus status);
}
