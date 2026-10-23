package vn.shop.oms.model;

import java.math.BigDecimal;
import java.util.Date;

/** Row of oms.orders. Plain bean: MyBatis fills it, Struts binds form fields to it. */
public class Order {
    private String code;
    private String channel;
    private String channelRef;
    private String customerName;
    private String phone;
    private BigDecimal amount;
    private BigDecimal codAmount;
    private String payVia;
    private String trackingCode;
    private String status;
    private boolean remoteArea;
    private Date createdAt;
    private Date deliveredAt;
    private String note;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getChannelRef() { return channelRef; }
    public void setChannelRef(String channelRef) { this.channelRef = channelRef; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getCodAmount() { return codAmount; }
    public void setCodAmount(BigDecimal codAmount) { this.codAmount = codAmount; }
    public String getPayVia() { return payVia; }
    public void setPayVia(String payVia) { this.payVia = payVia; }
    public String getTrackingCode() { return trackingCode; }
    public void setTrackingCode(String trackingCode) { this.trackingCode = trackingCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isRemoteArea() { return remoteArea; }
    public void setRemoteArea(boolean remoteArea) { this.remoteArea = remoteArea; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
    public Date getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Date deliveredAt) { this.deliveredAt = deliveredAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
