package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.reconhub.core.domain.BatchExecutionAck;

public interface BatchExecutionAckRepository extends JpaRepository<BatchExecutionAck, Long> {
}
