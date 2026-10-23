package vn.reconhub.core.vietqr;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.repo.OrderRepository;
import vn.reconhub.core.workflow.WorkflowException;
import vn.reconhub.core.workflow.WorkflowException.Kind;

/**
 * The VietQR code printed on an order's invoice: the order value into the shop's account, with the order code as the
 * transfer description, so the bank statement line carries the code and matches on its own.
 */
@Service
public class OrderQrService {
    /** Orders paid by transfer into the shop's Vietcombank account; carriers and marketplaces collect the rest. */
    static final String BANK_TRANSFER = "VCB";

    private final OrderRepository orders;
    private final ReconProperties props;

    public OrderQrService(OrderRepository orders, ReconProperties props) {
        this.orders = orders;
        this.props = props;
    }

    public record OrderQr(String orderCode, String bankBin, String account, String accountName, long amount, String memo,
                          String payload) {}

    @Transactional(readOnly = true)
    public OrderQr forOrder(String code) {
        Order o = orders.findById(code).orElseThrow(() -> new WorkflowException(Kind.NOT_FOUND, "Không có đơn " + code));
        if (!BANK_TRANSFER.equals(o.getPayVia())) {
            throw new WorkflowException(Kind.INVALID, "Đơn " + code + " không thanh toán bằng chuyển khoản ngân hàng, nên không có mã VietQR");
        }
        ReconProperties.VietQrAccount account = props.getVietqr();
        String payload = VietQr.payload(account.getBin(), account.getAccount(), o.getAmount(), o.getCode());
        return new OrderQr(o.getCode(), account.getBin(), account.getAccount(), account.getAccountName(), o.getAmount(), o.getCode(), payload);
    }
}
