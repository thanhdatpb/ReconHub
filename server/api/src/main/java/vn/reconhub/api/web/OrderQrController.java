package vn.reconhub.api.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.reconhub.core.vietqr.OrderQrService;
import vn.reconhub.core.vietqr.VietQr;

/**
 * VietQR codes for printing on invoices. Accountants and the chief accountant print invoices; the admin account does
 * not see money, so it gets 403 like in the assistant.
 */
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasAnyRole('ACCOUNTANT', 'APPROVER')")
public class OrderQrController {
    /** Pixels per QR module: an order code gives 37 modules plus the quiet zone, about 360 pixels. */
    private static final int MODULE_PIXELS = 8;

    private final OrderQrService qr;

    public OrderQrController(OrderQrService qr) {
        this.qr = qr;
    }

    @GetMapping("/{code}/vietqr")
    public OrderQrService.OrderQr vietqr(@PathVariable String code) {
        return qr.forOrder(code);
    }

    /** No {@code produces}: an unknown order must still get the usual JSON error body. */
    @GetMapping("/{code}/vietqr.png")
    public ResponseEntity<byte[]> vietqrPng(@PathVariable String code) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(VietQr.png(qr.forOrder(code).payload(), MODULE_PIXELS));
    }
}
