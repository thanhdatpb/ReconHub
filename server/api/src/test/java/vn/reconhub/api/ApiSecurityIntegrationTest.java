package vn.reconhub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login, token checks and role rules of the REST API. */
@SpringBootTest(properties = "spring.jms.listener.auto-startup=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApiSecurityIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void loginReturnsATokenThatOpensTheApi() throws Exception {
        String token = login("ha");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Nguyễn Thu Hà")))
                .andExpect(jsonPath("$.role", is("ACCOUNTANT")));
        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordIsRejectedWithAMessage() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ha\",\"password\":\"sai\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Sai tên đăng nhập hoặc mật khẩu")));
    }

    @Test
    void everythingElseNeedsAToken() throws Exception {
        mvc.perform(get("/api/exceptions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminOrChiefAccountantChangesHowFilesAreRead() throws Exception {
        String mapping = "[{\"field\":\"externalRef\",\"column\":\"Mã vận đơn\"},{\"field\":\"amount\",\"column\":\"Tiền trả shop\"},{\"field\":\"txnDate\",\"column\":\"Ngày đối soát\"}]";
        mvc.perform(put("/api/sources/GHN/mapping").header("Authorization", "Bearer " + login("ha"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapping))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/sources/GHN/mapping").header("Authorization", "Bearer " + login("bao"))
                        .contentType(MediaType.APPLICATION_JSON).content(mapping))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedBy", is("Phạm Quốc Bảo")));
    }

    @Test
    void anAdminDoesNotSeeMoneyInTheAssistant() throws Exception {
        mvc.perform(post("/api/ai/ask").header("Authorization", "Bearer " + login("bao"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"GHN còn nợ mình bao nhiêu?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engine", is("rules")))
                .andExpect(jsonPath("$.text", is("Tài khoản của bạn không được xem số liệu tiền, nên mình không trả lời được câu này.")));
    }

    private String login(String user) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
}
