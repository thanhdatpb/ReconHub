package vn.reconhub.core.domain;

import jakarta.persistence.*;
import vn.reconhub.core.domain.Enums.Channel;
import vn.reconhub.core.domain.Enums.OrderStatus;

import java.time.Instant;

/** An order in the legacy OMS. ReconHub reads it; only the OMS writes it. */
@Entity
@Table(name = "orders", schema = "oms")
public class Order {
    @Id
    private String code;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    private String channelRef;
    private String customerName;
    private String phone;
    private long amount;
    private long codAmount;
    private String payVia;
    private String trackingCode;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private boolean remoteArea;
    private Instant createdAt;
    private Instant deliveredAt;
    private String note;

    protected Order() {}

    public Order(String code, Channel channel, String customerName, long amount, String payVia, Instant createdAt) {
        this.code = code;
        this.channel = channel;
        this.customerName = customerName;
        this.amount = amount;
        this.payVia = payVia;
        this.createdAt = createdAt;
        this.status = OrderStatus.NEW;
    }

    /** What the partner should pay before its fee: the COD amount for carriers, otherwise the order value. */
    public long expectedGross() {
        return codAmount > 0 ? codAmount : amount;
    }

    public String getCode() { return code; }
    public Channel getChannel() { return channel; }
    public String getChannelRef() { return channelRef; }
    public void setChannelRef(String channelRef) { this.channelRef = channelRef; }
    public String getCustomerName() { return customerName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public long getAmount() { return amount; }
    public long getCodAmount() { return codAmount; }
    public void setCodAmount(long codAmount) { this.codAmount = codAmount; }
    public String getPayVia() { return payVia; }
    public String getTrackingCode() { return trackingCode; }
    public void setTrackingCode(String trackingCode) { this.trackingCode = trackingCode; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public boolean isRemoteArea() { return remoteArea; }
    public void setRemoteArea(boolean remoteArea) { this.remoteArea = remoteArea; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
    public String getNote() { return note; }
}
