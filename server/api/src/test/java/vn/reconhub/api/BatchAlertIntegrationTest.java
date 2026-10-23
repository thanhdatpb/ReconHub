package vn.reconhub.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.repo.ImportFileRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap 1.1: the dashboard warns only about failed runs that still need someone. A failed file that the partner
 * re-sent and that loaded is "replaced"; any other failed or stopped run can be acknowledged with a note.
 */
@SpringBootTest(properties = "spring.jms.listener.auto-startup=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BatchAlertIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicInteger SEQ = new AtomicInteger(500);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ImportFileRepository files;

    private final Instant failedAt = Instant.parse("2026-10-21T02:05:00Z");

    @Test
    void replacedFileLeavesTheDashboardButStaysInTheMonitorWithItsReplacement() throws Exception {
        int replaced = failedIngestion("GHTK", "GHTK_COD_x.csv", "GHTK_COD_x_v2.csv");
        int open = failedIngestion("GHN", "GHN_x.csv", null);
        String token = login("ha");

        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedJobs[*].id", not(hasItem(replaced))))
                .andExpect(jsonPath("$.failedJobs[*].id", hasItem(open)));
        mvc.perform(get("/api/batch/executions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + replaced + ")].status", hasItem("FAILED")))
                .andExpect(jsonPath("$[?(@.id == " + replaced + ")].supersededBy.fileName", hasItem("GHTK_COD_x_v2.csv")))
                .andExpect(jsonPath("$[?(@.id == " + open + ")].status", hasItem("FAILED")))
                .andExpect(jsonPath("$[?(@.id == " + open + ")].supersededBy").isEmpty());
    }

    @Test
    void restartOfAReplacedRunIsRefusedAndNamesTheReplacement() throws Exception {
        int replaced = failedIngestion("GHTK", "GHTK_COD_y.csv", "GHTK_COD_y_v2.csv");

        mvc.perform(post("/api/batch/executions/" + replaced + "/restart").header("Authorization", "Bearer " + login("nam")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.queued", is(false)))
                .andExpect(jsonPath("$.message", containsString("GHTK_COD_y_v2.csv")))
                .andExpect(jsonPath("$.message", containsString("nạp trùng tiền")));
    }

    @Test
    void acknowledgedRunLeavesTheDashboardAndShowsWhoWhenAndTheNote() throws Exception {
        int run = failedIngestion("GHN", "GHN_z.csv", null);
        String token = login("ha");

        ack(run, "ha", "  GHN xác nhận file hỏng, đã nhập tay 3 dòng  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged.by", is("u1")))
                .andExpect(jsonPath("$.acknowledged.byName", is("Nguyễn Thu Hà")))
                .andExpect(jsonPath("$.acknowledged.note", is("GHN xác nhận file hỏng, đã nhập tay 3 dòng")))
                .andExpect(jsonPath("$.acknowledged.at", notNullValue()));
        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.failedJobs[*].id", not(hasItem(run))));
        mvc.perform(get("/api/batch/executions").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[?(@.id == " + run + ")].acknowledged.byName", hasItem("Nguyễn Thu Hà")));
    }

    @Test
    void theNoteIsRequired() throws Exception {
        int run = failedIngestion("GHN", "GHN_w.csv", null);

        ack(run, "ha", "   \n\t ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Cần nhập ghi chú: lỗi này đã được xử lý thế nào")));
        mvc.perform(post("/api/batch/executions/" + run + "/acknowledge").header("Authorization", "Bearer " + login("ha")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + login("ha")))
                .andExpect(jsonPath("$.failedJobs[*].id", hasItem(run)));
    }

    @Test
    void aSecondAcknowledgementIsAConflictAndKeepsTheFirst() throws Exception {
        int run = failedIngestion("GHN", "GHN_v.csv", null);
        ack(run, "nam", "Đã đối chiếu với sao kê").andExpect(status().isOk());

        ack(run, "ha", "Ghi chú khác")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("đã được Lê Hoàng Nam đánh dấu đã xử lý")));
        mvc.perform(get("/api/batch/executions").header("Authorization", "Bearer " + login("ha")))
                .andExpect(jsonPath("$[?(@.id == " + run + ")].acknowledged.note", hasItem("Đã đối chiếu với sao kê")));
    }

    @Test
    void onlyFailedOrStoppedRunsCanBeAcknowledgedByAnyRole() throws Exception {
        int completed = execution("matchingJob", "COMPLETED", null);
        int stopped = execution("agingAndReportJob", "STOPPED", null);

        ack(completed, "bao", "Không có gì")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("chỉ đánh dấu được lần chạy lỗi (FAILED) hoặc bị dừng (STOPPED)")));
        ack(stopped, "bao", "Dừng khi khởi động lại máy chủ, đã chạy tay lúc 07:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledged.byName", is("Phạm Quốc Bảo")));
        ack(999_999, "ha", "Không có lần chạy này").andExpect(status().isNotFound());
    }

    @Test
    void acknowledgingNeedsAToken() throws Exception {
        int run = failedIngestion("GHN", "GHN_u.csv", null);
        mvc.perform(post("/api/batch/executions/" + run + "/acknowledge").contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions ack(int run, String user, String note) throws Exception {
        return mvc.perform(post("/api/batch/executions/" + run + "/acknowledge").header("Authorization", "Bearer " + login(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("note", note))));
    }

    /** A failed fileIngestionJob for its own business date; with {@code replacement}, the same day's file loaded three hours later. */
    private int failedIngestion(String source, String fileName, String replacement) {
        LocalDate day = LocalDate.of(2026, 1, 1).plusDays(7L * SEQ.incrementAndGet());
        ImportFile failed = files.save(new ImportFile(source, fileName, null, failedAt, day, UUID.randomUUID().toString(), FileStatus.FAILED, "test"));
        if (replacement != null) {
            files.save(new ImportFile(source, replacement, null, failedAt.plusSeconds(3 * 3600), day, UUID.randomUUID().toString(), FileStatus.COMPLETED, "test"));
        }
        return execution("fileIngestionJob", "FAILED", failed.getId());
    }

    private int execution(String job, String status, Long fileId) {
        int id = SEQ.incrementAndGet();
        jdbc.update("insert into batch_job_instance (job_instance_id, version, job_name, job_key) values (?, 0, ?, ?)", id, job, "k" + id);
        jdbc.update("insert into batch_job_execution (job_execution_id, version, job_instance_id, create_time, start_time, end_time, status, exit_code, exit_message) "
                + "values (?, 1, ?, now(), now(), now(), ?, ?, ?)", id, id, status, status, "FAILED".equals(status) ? "SkipLimitExceededException: Skip limit of '5' exceeded" : "");
        if (fileId != null) {
            jdbc.update("insert into batch_job_execution_params (job_execution_id, parameter_name, parameter_type, parameter_value, identifying) "
                    + "values (?, 'fileId', 'java.lang.Long', ?, 'Y')", id, fileId.toString());
        }
        return id;
    }

    private String login(String user) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"demo123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }
}
