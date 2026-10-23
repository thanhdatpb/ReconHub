package vn.reconhub.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.ZoneId;

/** Settings under {@code reconhub.*}. Defaults suit the Docker Compose stack. */
@ConfigurationProperties(prefix = "reconhub")
public class ReconProperties {
    private ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Hours a person has to handle a new exception. */
    private int slaHours = 48;
    /** Write-offs above this amount need a second person to approve. */
    private long writeOffApprovalThreshold = 50_000;
    /** Orders delivered longer ago than this without money are reported as unpaid. */
    private int unpaidAfterDays = 7;
    /** Sender of every e-mail ReconHub sends. */
    private String mailFrom = "reconhub@shop.example";
    /** Who gets the daily aging report, comma separated. */
    private String reportRecipients = "ketoan@shop.example";
    private final Data data = new Data();
    private final Ai ai = new Ai();
    private final Demo demo = new Demo();

    public static class Data {
        private Path root = Path.of("/data");

        public Path getRoot() { return root; }
        public void setRoot(Path root) { this.root = root; }
        public Path inbox() { return root.resolve("inbox"); }
        public Path archive() { return root.resolve("archive"); }
        public Path rejected() { return root.resolve("rejected"); }
        public Path reports() { return root.resolve("reports"); }
        public Path seed() { return root.resolve("seed"); }
    }

    public static class Ai {
        /** Anthropic API key. Empty means the LLM is off and heuristics are used everywhere. */
        private String apiKey = "";
        private String model = "claude-opus-5-5";
        private String baseUrl = "https://api.anthropic.com";
        private int maxTokens = 8000;
        private int dailyLimitPerUser = 200;
        /** Ask the LLM during nightly matching; off by default so a backfill does not make hundreds of calls. */
        private boolean suggestDuringBatch = false;

        public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public int getDailyLimitPerUser() { return dailyLimitPerUser; }
        public void setDailyLimitPerUser(int dailyLimitPerUser) { this.dailyLimitPerUser = dailyLimitPerUser; }
        public boolean isSuggestDuringBatch() { return suggestDuringBatch; }
        public void setSuggestDuringBatch(boolean suggestDuringBatch) { this.suggestDuringBatch = suggestDuringBatch; }
    }

    public static class Demo {
        /** Backfill files by their business date and simulate a few weeks of staff work on first start. */
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public ZoneId getZone() { return zone; }
    public void setZone(ZoneId zone) { this.zone = zone; }
    public int getSlaHours() { return slaHours; }
    public void setSlaHours(int slaHours) { this.slaHours = slaHours; }
    public long getWriteOffApprovalThreshold() { return writeOffApprovalThreshold; }
    public void setWriteOffApprovalThreshold(long v) { this.writeOffApprovalThreshold = v; }
    public int getUnpaidAfterDays() { return unpaidAfterDays; }
    public void setUnpaidAfterDays(int unpaidAfterDays) { this.unpaidAfterDays = unpaidAfterDays; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String mailFrom) { this.mailFrom = mailFrom; }
    public String getReportRecipients() { return reportRecipients; }
    public void setReportRecipients(String reportRecipients) { this.reportRecipients = reportRecipients; }
    public Data getData() { return data; }
    public Ai getAi() { return ai; }
    public Demo getDemo() { return demo; }
}
