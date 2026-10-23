package vn.reconhub.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.reconhub.core.messaging.JmsOutbox;
import vn.reconhub.core.messaging.Messages;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Roadmap 1.2: anyone proposes a fee schedule version; a chief accountant or admin approves it, never their own. */
@SpringBootTest(properties = "spring.jms.listener.auto-startup=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FeeScheduleIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JmsOutbox outbox;

    static final List<Map<String, Object>> GHN_V2 = List.of(
            Map.of("label", "Cước giao", "kind", "FIXED", "amount", 22_000),
            Map.of("label", "Phí thu hộ", "kind", "PERCENT", "rate", 0.5, "base", "COD", "min", 5_000),
            Map.of("label", "Phụ phí vùng xa", "kind", "FIXED", "amount", 8_000, "when", Map.of("remoteArea", true)),
            Map.of("label", "Phụ phí COD lớn", "kind", "FIXED", "amount", 2_000, "when", Map.of("base", "COD", "above", 1_500_000)));

    @Test
    void versionOneAppliesFromTheStartAndIsDescribedInWords() throws Exception {
        mvc.perform(get("/api/sources/GHN/fee-schedules").header("Authorization", "Bearer " + login("ha")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.versionNo == 1)].fromTheStart", hasItem(true)))
                .andExpect(jsonPath("$[?(@.versionNo == 1)].description",
                        hasItem("Cước giao 22.000 ₫; Phí thu hộ 0,5% COD, tối thiểu 5.000 ₫; Phụ phí vùng xa 8.000 ₫ nếu vùng xa")));
    }

    @Test
    void anAccountantProposesAndOnlyAChiefAccountantOrAdminApproves() throws Exception {
        JsonNode proposed = body(propose("ha", "GHN", "2026-10-13", GHN_V2, "GHN báo phụ phí đơn COD lớn từ 13/10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PROPOSED")))
                .andExpect(jsonPath("$.versionNo").doesNotExist())
                .andExpect(jsonPath("$.proposedByName", is("Nguyễn Thu Hà")))
                .andExpect(jsonPath("$.description", containsString("Phụ phí COD lớn 2.000 ₫ nếu COD trên 1.500.000 ₫"))));
        long id = proposed.get("id").asLong();
        int version = proposed.get("version").asInt();

        decide("khoa", id, "approve", version, null).andExpect(status().isForbidden());
        decide("ha", id, "approve", version, null).andExpect(status().isForbidden());
        JsonNode approved = body(decide("nam", id, "approve", version, "Đã đối chiếu email của GHN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.decidedByName", is("Lê Hoàng Nam"))));
        int versionNo = approved.get("versionNo").asInt();
        verify(outbox).sendAfterCommit(eq(Messages.BATCH_COMMANDS), argThat((Messages.BatchCommand c) ->
                c.kind().equals("REVALIDATE_FEES") && c.params().get("scheduleId").equals(String.valueOf(id))));

        decide("bao", id, "approve", approved.get("version").asInt(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Đề xuất này đã được xử lý")));
        mvc.perform(get("/api/sources/GHN/fee-schedules").header("Authorization", "Bearer " + login("khoa")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].versionNo", hasItem(versionNo)));
    }

    @Test
    void theProposerCannotApproveTheirOwnProposalEvenAsChiefAccountant() throws Exception {
        JsonNode proposed = body(propose("nam", "GHTK", "2026-11-01", List.of(
                Map.of("label", "Phí giao hàng", "kind", "FIXED", "amount", 25_000)), "GHTK đồng giá từ 1/11").andExpect(status().isOk()));
        long id = proposed.get("id").asLong();

        decide("nam", id, "approve", proposed.get("version").asInt(), null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is("Người đề xuất không được tự duyệt đề xuất của mình.")));
        decide("bao", id, "approve", proposed.get("version").asInt(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decidedByName", is("Phạm Quốc Bảo")));
    }

    @Test
    void proposalsAndDecisionsAreChecked() throws Exception {
        propose("ha", "GHN", "2026-10-13", GHN_V2, "  ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Cần ghi lý do đổi biểu phí, ví dụ thông báo của đối tác")));
        propose("ha", "GHN", "2026-10-13", List.of(), "x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Biểu phí cần ít nhất một khoản phí")));
        propose("ha", "SHOPEE", "2026-10-13", List.of(Map.of("label", "Phí", "kind", "PERCENT", "rate", 120, "base", "ORDER_AMOUNT")), "x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Tỷ lệ của khoản \"Phí\" phải từ 0 đến 100%")));
        propose("ha", "NOPE", "2026-10-13", GHN_V2, "x").andExpect(status().isNotFound());

        JsonNode proposed = body(propose("khoa", "TIKTOK", "2026-11-01", List.of(
                Map.of("label", "Phí giao dịch", "kind", "PERCENT", "rate", 6, "base", "ORDER_AMOUNT")), "TikTok tăng phí").andExpect(status().isOk()));
        long id = proposed.get("id").asLong();
        decide("nam", id, "reject", proposed.get("version").asInt(), " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Cần ghi lý do từ chối")));
        decide("nam", id, "approve", proposed.get("version").asInt() + 1, null).andExpect(status().isConflict());
        decide("nam", id, "reject", proposed.get("version").asInt(), "Chưa có thông báo chính thức của TikTok")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.versionNo").doesNotExist());
    }

    @Test
    void proposingNeedsAToken() throws Exception {
        mvc.perform(post("/api/sources/GHN/fee-schedules").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions propose(String user, String source, String from, List<Map<String, Object>> components, String note) throws Exception {
        return mvc.perform(post("/api/sources/" + source + "/fee-schedules").header("Authorization", "Bearer " + login(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("effectiveFrom", from, "components", components, "note", note))));
    }

    private ResultActions decide(String user, long id, String action, int version, String note) throws Exception {
        Map<String, Object> body = note == null ? Map.of("version", version) : Map.of("version", version, "note", note);
        return mvc.perform(post("/api/fee-schedules/" + id + "/" + action).header("Authorization", "Bearer " + login(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String login(String user) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
}
