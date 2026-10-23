package vn.shop.oms.action;

import com.opensymphony.xwork2.ActionSupport;
import vn.shop.oms.db.Db;
import vn.shop.oms.model.Order;
import vn.shop.oms.model.OrderSearch;
import vn.shop.oms.model.StatusCount;

import java.util.List;

public class OrderListAction extends ActionSupport {
    private final OrderSearch search = new OrderSearch();
    private List<Order> orders;
    private int total;
    private List<StatusCount> statusCounts;

    @Override
    public String execute() {
        Db.tx(m -> {
            orders = m.search(search);
            total = m.count(search);
            statusCounts = m.countByStatus();
            return null;
        });
        return SUCCESS;
    }

    public OrderSearch getSearch() { return search; }
    public List<Order> getOrders() { return orders; }
    public int getTotal() { return total; }
    public int getPages() { return Math.max(1, (total + search.getPageSize() - 1) / search.getPageSize()); }
    public List<StatusCount> getStatusCounts() { return statusCounts; }
}
