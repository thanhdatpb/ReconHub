package vn.reconhub.core.vietqr;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Same tests as the legacy OMS copy (vn.shop.oms.vietqr.VietQrTest), over the same vectors file. */
class VietQrTest {
    private static final String VECTORS = "vietqr/vectors.csv";
    /** The other module's copy, seen from this module's directory when the whole repository is checked out. */
    private static final Path OTHER_MODULE = Path.of("../../legacy-oms");
    private static final String SOURCE = "src/main/java/vn/reconhub/core/vietqr/VietQr.java";
    private static final String OTHER_SOURCE = "src/main/java/vn/shop/oms/vietqr/VietQr.java";

    @Test
    void crcIsCcittFalse() {
        assertEquals("29B1", VietQr.crc16("123456789")); // the published check value of CRC-16/CCITT-FALSE
        assertEquals("FFFF", VietQr.crc16(""));
    }

    @Test
    void matchesEveryVector() throws IOException {
        List<String[]> vectors = vectors();
        assertFalse(vectors.isEmpty());
        for (String[] v : vectors) {
            String expected = v[4];
            assertEquals(expected, VietQr.payload(v[0], v[1], Long.parseLong(v[2]), v[3]), String.join(",", v));
            assertEquals(expected.substring(expected.length() - 4), VietQr.crc16(expected.substring(0, expected.length() - 4)));
        }
    }

    @Test
    void rejectsWhatABankAppWouldNotRead() {
        assertThrows(IllegalArgumentException.class, () -> VietQr.payload("97043", "1234567890", 1000, "DH1"));
        assertThrows(IllegalArgumentException.class, () -> VietQr.payload("970436", "1234-5678", 1000, "DH1"));
        assertThrows(IllegalArgumentException.class, () -> VietQr.payload("970436", "12345678901234567890", 1000, "DH1"));
        assertThrows(IllegalArgumentException.class, () -> VietQr.payload("970436", "1234567890", -1, "DH1"));
        assertThrows(IllegalArgumentException.class, () -> VietQr.payload("970436", "1234567890", 1000, "DH1 Nguyễn"));
        assertThrows(IllegalArgumentException.class, () -> VietQr.payload("970436", "1234567890", 1000, "DH2610240001 NGUYEN VAN AN"));
    }

    @Test
    void everyVectorPngReadsBackWithoutHints() throws Exception {
        for (String[] v : vectors()) assertEquals(v[4], decode(VietQr.png(v[4], 6)), String.join(",", v));
    }

    @Test
    void aDayOfOrderCodesReadsBack() throws Exception {
        for (int seq = 1; seq <= 300; seq++) {
            String payload = VietQr.payload("970436", "1234567890", 129_000 + seq * 7_000L, String.format("DH261024%04d", seq));
            assertEquals(payload, decode(VietQr.png(payload, 4)));
        }
    }

    @Test
    void vectorsAndSourceAreTheSameAsTheOtherModule() throws IOException {
        assumeTrue(Files.isDirectory(OTHER_MODULE), "only the repository checkout has both modules");
        assertArrayEquals(Files.readAllBytes(OTHER_MODULE.resolve("src/test/resources").resolve(VECTORS)),
                Files.readAllBytes(Path.of("src/test/resources", VECTORS)),
                "server/core and legacy-oms vietqr/vectors.csv differ; keep them identical");
        assertEquals(withoutPackage(OTHER_MODULE.resolve(OTHER_SOURCE)), withoutPackage(Path.of(SOURCE)),
                "server/core and legacy-oms VietQr.java differ; keep them identical apart from the package line");
    }

    private static List<String> withoutPackage(Path source) throws IOException {
        return Files.readAllLines(source).stream().filter(l -> !l.startsWith("package ")).map(String::stripTrailing).toList();
    }

    private static String decode(byte[] png) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(img.getWidth(), img.getHeight(), pixels)));
        return new QRCodeReader().decode(bitmap).getText();
    }

    private static List<String[]> vectors() throws IOException {
        try (InputStream in = VietQrTest.class.getClassLoader().getResourceAsStream(VECTORS)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).map(l -> l.split(",", -1)).toList();
        }
    }
}
