package vn.reconhub.core.vietqr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * VietQR transfer codes built locally, without calling any outside service.
 *
 * <p>The payload is EMVCo merchant-presented mode (EMV QRCPS-MPM): a list of tag, two-digit length, value. NAPAS puts
 * the beneficiary in tag 38 (GUID A000000727, bank BIN and account number, service QRIBFTTA "transfer to an account"),
 * the amount in tag 54 and the transfer description in tag 62, sub-tag 08. Tag 63 is a CRC-16/CCITT-FALSE over
 * everything before it, including "6304". Sources are listed in docs/architecture.md (VietQR section).
 *
 * <p>The legacy OMS has its own copy of this class (vn.shop.oms.vietqr.VietQr); both are checked against the same
 * test vectors, so keep them identical apart from the package.
 */
public final class VietQr {
    static final String NAPAS_GUID = "A000000727";
    static final String TRANSFER_TO_ACCOUNT = "QRIBFTTA";
    private static final Pattern BIN = Pattern.compile("\\d{6}");
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9]{1,19}");
    /** Plain ASCII only: some banking apps reject or garble anything else in the description. */
    private static final Pattern MEMO = Pattern.compile("[A-Za-z0-9 ]{1,25}");
    private static final ErrorCorrectionLevel[] LEVELS = {ErrorCorrectionLevel.M, ErrorCorrectionLevel.Q, ErrorCorrectionLevel.L, ErrorCorrectionLevel.H};

    private VietQr() {}

    /**
     * The text to put in the QR code.
     *
     * @param amount in VND; 0 makes a static code where the payer types the amount
     * @param memo   transfer description, at most 25 letters, digits or spaces; null or blank for none
     */
    public static String payload(String bin, String account, long amount, String memo) {
        if (bin == null || !BIN.matcher(bin).matches()) throw new IllegalArgumentException("BIN must be 6 digits: " + bin);
        if (account == null || !ACCOUNT.matcher(account).matches()) {
            throw new IllegalArgumentException("Account must be 1-19 letters or digits: " + account);
        }
        if (amount < 0 || amount > 9_999_999_999_999L) throw new IllegalArgumentException("Amount out of range: " + amount);
        boolean hasMemo = memo != null && !memo.isBlank();
        if (hasMemo && !MEMO.matcher(memo).matches()) {
            throw new IllegalArgumentException("Memo must be at most 25 letters, digits or spaces: " + memo);
        }
        String beneficiary = tlv("00", NAPAS_GUID) + tlv("01", tlv("00", bin) + tlv("01", account)) + tlv("02", TRANSFER_TO_ACCOUNT);
        StringBuilder s = new StringBuilder()
                .append(tlv("00", "01"))
                .append(tlv("01", amount > 0 ? "12" : "11"))
                .append(tlv("38", beneficiary))
                .append(tlv("53", "704"));
        if (amount > 0) s.append(tlv("54", Long.toString(amount)));
        s.append(tlv("58", "VN"));
        if (hasMemo) s.append(tlv("62", tlv("08", memo)));
        s.append("6304");
        return s.append(crc16(s.toString())).toString();
    }

    /** CRC-16/CCITT-FALSE (polynomial 0x1021, initial value 0xFFFF, no reflection) as four upper-case hex digits. */
    public static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }

    /**
     * The payload as a black-on-white PNG with a four-module quiet zone, {@code moduleSize} pixels per module.
     *
     * <p>A few payloads give a symbol whose data happens to look like a finder pattern to some detectors (ZXing's
     * reader fails on about one order code in ten at level M). So each symbol is read back with ZXing's detector, and the
     * next error-correction level is tried until one reads; every level is valid VietQR.
     */
    public static byte[] png(String payload, int moduleSize) {
        BitMatrix m = Arrays.stream(LEVELS).map(level -> encode(payload, level)).filter(c -> readsBack(c, payload))
                .findFirst().orElseGet(() -> encode(payload, LEVELS[0]));
        int width = m.getWidth() * moduleSize;
        BufferedImage img = new BufferedImage(width, width, BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) img.setRGB(x, y, m.get(x / moduleSize, y / moduleSize) ? 0x000000 : 0xFFFFFF);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static BitMatrix encode(String payload, ErrorCorrectionLevel level) {
        try {
            // Width and height 0: one pixel per module; png() scales by a whole number so modules stay sharp.
            return new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, level, EncodeHintType.MARGIN, 4));
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode QR payload", e);
        }
    }

    private static boolean readsBack(BitMatrix m, String payload) {
        int scale = 4;
        int width = m.getWidth() * scale;
        int[] pixels = new int[width * width];
        for (int y = 0; y < width; y++) {
            for (int x = 0; x < width; x++) pixels[y * width + x] = m.get(x / scale, y / scale) ? 0xFF000000 : 0xFFFFFFFF;
        }
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width, width, pixels)));
            return payload.equals(new QRCodeReader().decode(bitmap).getText());
        } catch (ReaderException e) {
            return false;
        }
    }

    private static String tlv(String tag, String value) {
        if (value.length() > 99) throw new IllegalArgumentException("Field " + tag + " longer than 99 characters");
        return tag + String.format("%02d", value.length()) + value;
    }
}
