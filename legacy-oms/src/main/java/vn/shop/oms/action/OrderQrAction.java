package vn.shop.oms.action;

import com.opensymphony.xwork2.ActionSupport;
import vn.shop.oms.db.Db;
import vn.shop.oms.model.Order;
import vn.shop.oms.vietqr.QrAccount;
import vn.shop.oms.vietqr.VietQr;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** The VietQR image of one bank-transfer order, shown on the order page (stream result, image/png). */
public class OrderQrAction extends ActionSupport {
    /** Pixels per QR module: an order code gives 37 modules plus the quiet zone, about 270 pixels. */
    private static final int MODULE_PIXELS = 6;

    private String code;
    private InputStream image;

    @Override
    public String execute() {
        Order order = Db.tx(m -> m.findByCode(code));
        if (order == null || !QrAccount.BANK_TRANSFER.equals(order.getPayVia())) {
            addActionError("Đơn " + code + " không có mã VietQR");
            return ERROR;
        }
        String payload = QrAccount.fromEnvironment().payloadFor(order.getCode(), order.getAmount().longValueExact());
        image = new ByteArrayInputStream(VietQr.png(payload, MODULE_PIXELS));
        return SUCCESS;
    }

    public void setCode(String code) { this.code = code; }
    public InputStream getImage() { return image; }
}
