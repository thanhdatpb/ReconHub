package vn.shop.oms.action;

import com.opensymphony.xwork2.ActionSupport;
import vn.shop.oms.db.Db;
import vn.shop.oms.model.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** View, create and change the status of one order. */
public class OrderAction extends ActionSupport {
    public static final List<String> STATUSES = List.of("NEW", "SHIPPING", "DELIVERED", "CANCELLED", "RETURNED");
    public static final List<String> CHANNELS = List.of("WEBSITE", "SHOPEE", "TIKTOK");
    public static final List<String> PAY_VIA = List.of("GHN", "GHTK", "VCB", "MOMO", "SHOPEE", "TIKTOK");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private String code;
    private Order order = new Order();
    private Map<String, Object> payment;
    private String newStatus;
    private String trackingCode;

    public String view() {
        order = Db.tx(m -> m.findByCode(code));
        if (order == null) {
            addActionError("Không có đơn " + code);
            return ERROR;
        }
        payment = Db.tx(m -> m.paymentOf(code));
        return SUCCESS;
    }

    /** Blank form for a new order. */
    public String form() {
        order.setChannel("WEBSITE");
        order.setPayVia("VCB");
        return INPUT;
    }

    public String create() {
        if (order.getCustomerName() == null || order.getCustomerName().isBlank()) addFieldError("order.customerName", "Nhập tên khách");
        if (order.getAmount() == null || order.getAmount().signum() <= 0) addFieldError("order.amount", "Giá trị đơn phải lớn hơn 0");
        if (!PAY_VIA.contains(order.getPayVia())) addFieldError("order.payVia", "Chọn kênh thanh toán");
        if (hasErrors()) return INPUT;
        boolean cod = order.getPayVia().equals("GHN") || order.getPayVia().equals("GHTK");
        order.setCodAmount(cod ? order.getAmount() : BigDecimal.ZERO);
        code = Db.tx(m -> {
            String prefix = "DH" + LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).format(YYMMDD);
            String last = m.lastCodeWithPrefix(prefix);
            int next = last == null ? 1 : Integer.parseInt(last.substring(prefix.length())) + 1;
            order.setCode(prefix + String.format("%04d", next));
            m.insert(order);
            return order.getCode();
        });
        addActionMessage("Đã tạo đơn " + code);
        return SUCCESS;
    }

    public String status() {
        if (!STATUSES.contains(newStatus)) {
            addActionError("Trạng thái không hợp lệ");
            return view();
        }
        Db.tx(m -> m.updateStatus(code, newStatus, trackingCode == null || trackingCode.isBlank() ? null : trackingCode.trim()));
        return SUCCESS;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Order getOrder() { return order; }
    public Map<String, Object> getPayment() { return payment; }
    public void setNewStatus(String newStatus) { this.newStatus = newStatus; }
    public void setTrackingCode(String trackingCode) { this.trackingCode = trackingCode; }
    public List<String> getStatuses() { return STATUSES; }
    public List<String> getChannels() { return CHANNELS; }
    public List<String> getPayVias() { return PAY_VIA; }
}
