package vn.shop.oms.vietqr;

/**
 * The shop's bank account that order QR codes pay into, from VIETQR_BIN, VIETQR_ACCOUNT and VIETQR_ACCOUNT_NAME.
 * A plain bean rather than a record, so the JSPs can read it with OGNL.
 */
public final class QrAccount {
    /** Orders paid by transfer into the shop's Vietcombank account; carriers and marketplaces collect the rest. */
    public static final String BANK_TRANSFER = "VCB";
    private static final QrAccount FROM_ENVIRONMENT = new QrAccount(env("VIETQR_BIN", "970436"),
            env("VIETQR_ACCOUNT", "1234567890"), env("VIETQR_ACCOUNT_NAME", "CONG TY TNHH SHOP DEMO"));

    private final String bin;
    private final String account;
    private final String accountName;

    public QrAccount(String bin, String account, String accountName) {
        this.bin = bin;
        this.account = account;
        this.accountName = accountName;
    }

    public static QrAccount fromEnvironment() {
        return FROM_ENVIRONMENT;
    }

    /** The QR text for an order: its value, with the order code as the transfer description. */
    public String payloadFor(String orderCode, long amount) {
        return VietQr.payload(bin, account, amount, orderCode);
    }

    public String getBin() { return bin; }
    public String getAccount() { return account; }
    public String getAccountName() { return accountName; }

    private static String env(String name, String sample) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? sample : v.trim();
    }
}
