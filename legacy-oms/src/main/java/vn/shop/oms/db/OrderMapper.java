package vn.shop.oms.db;

import org.apache.ibatis.annotations.Param;
import vn.shop.oms.model.Order;
import vn.shop.oms.model.OrderSearch;
import vn.shop.oms.model.StatusCount;

import java.util.List;
import java.util.Map;

/** SQL is in mapper/OrderMapper.xml. */
public interface OrderMapper {
    List<Order> search(@Param("s") OrderSearch search);

    int count(@Param("s") OrderSearch search);

    Order findByCode(@Param("code") String code);

    String lastCodeWithPrefix(@Param("prefix") String prefix);

    int insert(Order order);

    int updateStatus(@Param("code") String code, @Param("status") String status, @Param("trackingCode") String trackingCode);

    List<StatusCount> countByStatus();

    /** Shows staff whether ReconHub has received the money for this order. */
    Map<String, Object> paymentOf(@Param("code") String code);

    Map<String, Object> staff(@Param("username") String username);
}
