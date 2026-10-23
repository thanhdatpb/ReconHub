package vn.reconhub.core.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "match_items")
public class MatchItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private MatchGroup group;
    private String orderCode;
    private Long paymentLineId;
    private long amount;

    protected MatchItem() {}

    MatchItem(MatchGroup group, String orderCode, Long paymentLineId, long amount) {
        this.group = group;
        this.orderCode = orderCode;
        this.paymentLineId = paymentLineId;
        this.amount = amount;
    }

    public Long getId() { return id; }
    public String getOrderCode() { return orderCode; }
    public Long getPaymentLineId() { return paymentLineId; }
    public long getAmount() { return amount; }
}
