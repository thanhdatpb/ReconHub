package vn.reconhub.core.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.domain.AiRequest;
import vn.reconhub.core.repo.AiRequestRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Function;

/**
 * Single entry point to the LLM (Claude through Spring AI). Everything that uses AI has a rule-based
 * fallback, so the system works without an API key, after a refusal, and when the daily limit is reached.
 *
 * <p>The chat model is built here instead of by Spring AI's auto-configuration because the auto-configured
 * defaults send {@code temperature}, which the Anthropic API now rejects. Our default options carry only the
 * model and max tokens.
 */
@Component
public class AiGateway {
    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);

    private final ReconProperties.Ai config;
    private final AiRequestRepository requests;
    private final Clock clock;
    private final ChatClient client;

    public AiGateway(ReconProperties props, AiRequestRepository requests, Clock clock) {
        this.config = props.getAi();
        this.requests = requests;
        this.clock = clock;
        this.client = config.enabled() ? ChatClient.builder(chatModel(config)).build() : null;
        log.info(config.enabled() ? "AI enabled with model {}" : "AI disabled (no API key); using rule-based suggestions", config.getModel());
    }

    static AnthropicChatModel chatModel(ReconProperties.Ai config) {
        AnthropicApi api = AnthropicApi.builder().baseUrl(config.getBaseUrl()).apiKey(config.getApiKey()).build();
        AnthropicChatOptions defaults = AnthropicChatOptions.builder()
                .model(config.getModel())
                .maxTokens(config.getMaxTokens())
                .build();
        return AnthropicChatModel.builder().anthropicApi(api).defaultOptions(defaults).build();
    }

    public boolean enabled() {
        return client != null;
    }

    public String model() {
        return config.getModel();
    }

    /** True when this user may still call the LLM today. System jobs (null user) are not limited. */
    public boolean withinDailyLimit(String userId) {
        if (userId == null) return true;
        Instant startOfDay = LocalDate.now(clock).atStartOfDay(clock.getZone()).toInstant();
        return requests.countByUserIdAndAtAfter(userId, startOfDay) < config.getDailyLimitPerUser();
    }

    /**
     * Runs one prompt and returns its result, or empty when AI is off, over the limit, refused or failed.
     * The caller then uses its rule-based answer.
     */
    public <T> Optional<T> call(String feature, String userId, Function<ChatClient, Result<T>> work) {
        if (!enabled() || !withinDailyLimit(userId)) return Optional.empty();
        long started = System.currentTimeMillis();
        try {
            Result<T> r = work.apply(client);
            String finish = finishReason(r.response());
            boolean refused = "refusal".equalsIgnoreCase(finish);
            record(feature, userId, r.response(), System.currentTimeMillis() - started, !refused);
            if (refused) {
                log.warn("AI refused {} request; falling back to rules", feature);
                return Optional.empty();
            }
            return Optional.ofNullable(r.value());
        } catch (RuntimeException e) {
            log.warn("AI call for {} failed: {}", feature, e.getMessage());
            record(feature, userId, null, System.currentTimeMillis() - started, false);
            return Optional.empty();
        }
    }

    /** What a prompt produced plus the raw response for usage accounting. */
    public record Result<T>(T value, ChatResponse response) {}

    private void record(String feature, String userId, ChatResponse response, long latency, boolean ok) {
        long in = 0, out = 0;
        if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            var u = response.getMetadata().getUsage();
            in = u.getPromptTokens() == null ? 0 : u.getPromptTokens();
            out = u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
        }
        requests.save(new AiRequest(userId, feature, config.getModel(), in, out, latency, ok, Instant.now(clock)));
    }

    private static String finishReason(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getMetadata() == null) return null;
        return response.getResult().getMetadata().getFinishReason();
    }
}
