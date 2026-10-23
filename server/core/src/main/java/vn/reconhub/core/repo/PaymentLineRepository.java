package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.reconhub.core.domain.Enums.LineStatus;
import vn.reconhub.core.domain.PaymentLine;

import java.util.Collection;
import java.util.List;

public interface PaymentLineRepository extends JpaRepository<PaymentLine, Long> {

    List<PaymentLine> findBySourceCodeAndStatusOrderByTxnAtAscIdAsc(String sourceCode, LineStatus status);

    List<PaymentLine> findByStatusOrderByTxnAtAscIdAsc(LineStatus status);

    /** Same reference already loaded from another file: the classic overlapping-statement duplicate. */
    @Query("select p from PaymentLine p where p.sourceCode = :source and p.externalRef = :ref and p.amount = :amount and p.fileId <> :fileId and p.status <> vn.reconhub.core.domain.Enums.LineStatus.DUPLICATE and p.id < :beforeId order by p.id")
    List<PaymentLine> findEarlierCopies(@Param("source") String source, @Param("ref") String ref, @Param("amount") long amount,
                                       @Param("fileId") Long fileId, @Param("beforeId") Long beforeId);

    @Query("select p from PaymentLine p where p.status = vn.reconhub.core.domain.Enums.LineStatus.MATCHED and p.feeChecked = false and p.sourceCode in :sources order by p.id")
    List<PaymentLine> findMatchedFeeUnchecked(@Param("sources") Collection<String> sources);

    long countByFileId(Long fileId);
}
