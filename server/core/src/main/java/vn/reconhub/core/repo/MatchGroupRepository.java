package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.reconhub.core.domain.MatchGroup;

import java.util.Optional;

public interface MatchGroupRepository extends JpaRepository<MatchGroup, Long> {
    @Query("select g from MatchGroup g join g.items i where i.orderCode = :orderCode")
    Optional<MatchGroup> findByOrderCode(@Param("orderCode") String orderCode);
}
