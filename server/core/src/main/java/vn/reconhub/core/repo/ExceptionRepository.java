package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.reconhub.core.domain.Enums.ExceptionStatus;
import vn.reconhub.core.domain.ReconException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface ExceptionRepository extends JpaRepository<ReconException, String> {

    @Query(value = "select nextval('exception_seq')", nativeQuery = true)
    long nextSequence();

    List<ReconException> findByAiIsNullAndStatusIn(Collection<ExceptionStatus> statuses);

    List<ReconException> findByStatusInAndRunDateBefore(Collection<ExceptionStatus> statuses, LocalDate before);

    @Query("select count(e) > 0 from ReconException e join e.payments p where p.id = :lineId")
    boolean existsForLine(@Param("lineId") Long lineId);

    @Query("select count(e) from ReconException e where e.sourceCode = :source and e.type = :type and e.diffAmount = :diff and e.createdAt > :since and e.id <> :id")
    long countSimilar(@Param("source") String source, @Param("type") vn.reconhub.core.domain.Enums.ExceptionType type,
                      @Param("diff") long diff, @Param("since") java.time.Instant since, @Param("id") String id);
}
