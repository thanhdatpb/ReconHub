package vn.reconhub.core.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.reconhub.core.config.ReconProperties;
import vn.reconhub.core.repo.AiRequestRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Checks the exact request Spring AI sends, against a local stand-in for the Anthropic API. */
class AiGatewayRequestTest {
    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile String reply;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AiGateway gateway() {
        ReconProperties props = new ReconProperties();
        props.getAi().setApiKey("test-key");
        props.getAi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new AiGateway(props, mock(AiRequestRepository.class), Clock.system(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    private static String message(String stopReason, String text) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5",
                 "content":[{"type":"text","text":%s}],
                 "stop_reason":"%s","stop_sequence":null,"usage":{"input_tokens":12,"output_tokens":7}}
                """.formatted(com.fasterxml.jackson.databind.node.TextNode.valueOf(text).toString(), stopReason);
    }

    @Test
    void sendsModelAndMaxTokensButNoTemperature() {
        reply = message("end_turn", "{\"summary\":\"Khớp đơn DH1\",\"recommended\":\"MATCH\",\"confidence\":0.9,\"ranking\":[0]}");
        Optional<SuggestionService.LlmVerdict> v = gateway().call("test", null, client -> {
            var r = client.prompt().user("xin chào").call().responseEntity(SuggestionService.LlmVerdict.class);
            return new AiGateway.Result<>(r.entity(), r.response());
        });

        assertThat(v).isPresent();
        assertThat(v.get().summary()).isEqualTo("Khớp đơn DH1");
        assertThat(lastBody.get())
                .contains("\"model\":\"claude-opus-5-5\"")
                .contains("\"max_tokens\":8000")
                .doesNotContain("temperature")
                .doesNotContain("top_p")
                .doesNotContain("top_k");
    }

    @Test
    void refusalFallsBackToRules() {
        reply = message("refusal", "");
        Optional<String> v = gateway().call("test", null, client -> {
            var resp = client.prompt().user("x").call().chatResponse();
            return new AiGateway.Result<>(resp.getResult().getOutput().getText(), resp);
        });
        assertThat(v).isEmpty();
    }
}
