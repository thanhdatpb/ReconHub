package vn.reconhub.core.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.reconhub.core.domain.Order;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, String> {

    Optional<Order> findByTrackingCode(String trackingCode);

    Optional<Order> findByChannelRef(String channelRef);

    List<Order> findByCodeIn(Collection<String> codes);

    /** Orders paid through {@code payVia} that no match group or open/closed exception has claimed yet. */
    @Query("""
            select o from Order o
            where o.payVia = :payVia and o.createdAt between :from and :to
              and o.status <> vn.reconhub.core.domain.Enums.OrderStatus.CANCELLED
              and not exists (select 1 from MatchItem mi where mi.orderCode = o.code)
            order by o.createdAt desc
            """)
    List<Order> findUnpaid(@Param("payVia") String payVia, @Param("from") Instant from, @Param("to") Instant to);

    @Query("""
            select o from Order o
            where o.payVia in :payVia and o.status = vn.reconhub.core.domain.Enums.OrderStatus.DELIVERED
              and o.deliveredAt < :deliveredBefore
              and not exists (select 1 from MatchItem mi where mi.orderCode = o.code)
              and not exists (select 1 from ReconException e join e.orders eo where eo.code = o.code)
            """)
    List<Order> findDeliveredWithoutPayment(@Param("payVia") Collection<String> payVia, @Param("deliveredBefore") Instant deliveredBefore);

    @Query("select o from Order o where upper(o.code) like concat('%', upper(:q), '%') or upper(o.customerName) like concat('%', upper(:q), '%') or upper(o.trackingCode) like concat('%', upper(:q), '%') or o.amount = :amount order by o.createdAt desc limit 20")
    List<Order> search(@Param("q") String q, @Param("amount") long amount);

    @Query("select count(o) from Order o")
    long countAll();
}
