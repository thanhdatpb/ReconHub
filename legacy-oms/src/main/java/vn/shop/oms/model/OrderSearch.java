package vn.shop.oms.model;

/** Search form of the order list. Empty fields are ignored by the dynamic SQL. */
public class OrderSearch {
    private String q;
    private String status;
    private String payVia;
    private int page = 1;
    private int pageSize = 30;

    public String getQ() { return q; }
    public void setQ(String q) { this.q = q; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPayVia() { return payVia; }
    public void setPayVia(String payVia) { this.payVia = payVia; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = Math.max(1, page); }
    public int getPageSize() { return pageSize; }
    public int getOffset() { return (page - 1) * pageSize; }
    public String getLike() { return q == null || q.isBlank() ? null : "%" + q.trim().toUpperCase() + "%"; }
}
