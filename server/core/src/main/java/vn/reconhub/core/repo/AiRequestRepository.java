package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.reconhub.core.domain.AiRequest;

import java.time.Instant;

public interface AiRequestRepository extends JpaRepository<AiRequest, Long> {
    long countByUserIdAndAtAfter(String userId, Instant after);
}
