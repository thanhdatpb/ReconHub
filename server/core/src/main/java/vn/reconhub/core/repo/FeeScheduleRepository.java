package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import vn.reconhub.core.domain.Enums.FeeScheduleStatus;
import vn.reconhub.core.domain.FeeSchedule;

import java.util.List;

public interface FeeScheduleRepository extends JpaRepository<FeeSchedule, Long> {
    List<FeeSchedule> findByStatus(FeeScheduleStatus status);

    List<FeeSchedule> findBySourceCodeOrderByProposedAtDesc(String sourceCode);

    @Query("select coalesce(max(f.versionNo), 0) from FeeSchedule f where f.sourceCode = ?1")
    int maxVersionNo(String sourceCode);
}
