package vn.reconhub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.reconhub.core.domain.Enums.Channel;
import vn.reconhub.core.domain.Order;
import vn.reconhub.core.repo.OrderRepository;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Instant;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Roadmap 2.1: the VietQR code for an order, as JSON and as a PNG for invoices. */
@SpringBootTest(properties = "spring.jms.listener.auto-startup=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OrderQrIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** Same as the vectors file row for 388.000 ₫ and DH2610240001, with the sample account from reconhub-core.yml. */
    static final String PAYLOAD = "00020101021238540010A00000072701240006970436011012345678900208QRIBFTTA530370454063880005802VN62160812DH26102400016304922A";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OrderRepository orders;

    @BeforeEach
    void orders() {
        orders.save(new Order("DH2610240001", Channel.WEBSITE, "Khách chuyển khoản", 388_000, "VCB", Instant.parse("2026-10-24T03:00:00Z")));
        orders.save(new Order("DH2610240002", Channel.WEBSITE, "Khách COD", 259_000, "GHN", Instant.parse("2026-10-24T03:05:00Z")));
    }

    @Test
    void anAccountantGetsTheCodeForABankTransferOrder() throws Exception {
        mvc.perform(get("/api/orders/DH2610240001/vietqr").header("Authorization", "Bearer " + login("ha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankBin", is("970436")))
                .andExpect(jsonPath("$.account", is("1234567890")))
                .andExpect(jsonPath("$.accountName", is("CONG TY TNHH SHOP DEMO")))
                .andExpect(jsonPath("$.amount", is(388_000)))
                .andExpect(jsonPath("$.memo", is("DH2610240001")))
                .andExpect(jsonPath("$.payload", is(PAYLOAD)));
    }

    @Test
    void thePngReadsBackToThePayload() throws Exception {
        byte[] png = mvc.perform(get("/api/orders/DH2610240001/vietqr.png").header("Authorization", "Bearer " + login("nam")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(img.getWidth(), img.getHeight(), pixels)));
        assertEquals(PAYLOAD, new QRCodeReader().decode(bitmap).getText());
    }

    @Test
    void onlyBankTransferOrdersHaveACode() throws Exception {
        mvc.perform(get("/api/orders/DH2610240002/vietqr").header("Authorization", "Bearer " + login("ha")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Đơn DH2610240002 không thanh toán bằng chuyển khoản ngân hàng, nên không có mã VietQR")));
        mvc.perform(get("/api/orders/DH2610249999/vietqr.png").header("Authorization", "Bearer " + login("ha")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Không có đơn DH2610249999")));
    }

    @Test
    void theAdminAndAnonymousCallersGetNoCode() throws Exception {
        mvc.perform(get("/api/orders/DH2610240001/vietqr").header("Authorization", "Bearer " + login("bao")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/orders/DH2610240001/vietqr.png")).andExpect(status().isUnauthorized());
    }

    private String login(String user) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
}
