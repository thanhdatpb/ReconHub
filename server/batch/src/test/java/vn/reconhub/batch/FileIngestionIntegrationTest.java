package vn.reconhub.batch;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.reconhub.batch.pipeline.ReconPipeline;
import vn.reconhub.core.domain.Enums.FileStatus;
import vn.reconhub.core.domain.ImportFile;
import vn.reconhub.core.repo.FileErrorRepository;
import vn.reconhub.core.repo.ImportFileRepository;
import vn.reconhub.core.repo.PaymentLineRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** fileIngestionJob end to end: a partner CSV on disk becomes payment lines, bad rows are skipped and recorded. */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class FileIngestionIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static Path data;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        data = Files.createTempDirectory("reconhub-data");
        registry.add("reconhub.data.root", data::toString);
    }

    @Autowired ReconPipeline pipeline;
    @Autowired ImportFileRepository files;
    @Autowired FileErrorRepository errors;
    @Autowired PaymentLineRepository lines;

    private static final String GHTK_HEADER = "Mã đơn hàng,Mã đơn KH,Ngày,COD,Phí ship,Thực nhận,Trạng thái\n";

    @Test
    void badRowsAreSkippedAndRecordedWhileTheRestIsLoaded() throws IOException {
        StringBuilder csv = new StringBuilder(GHTK_HEADER);
        for (int i = 0; i < 20; i++) {
            csv.append("S1000").append(i).append(",DH26102000").append(10 + i).append(",20-10-2026 16:40,650000,20000,630000,Đã đối soát\n");
        }
        csv.append("S2000,DH2610209999,20-10-2026 16:40,650000,20000,N/A,Đã đối soát\n");
        Path file = write("GHTK_COD_20261021.csv", csv.toString());

        assertThat(pipeline.intake(file, Instant.now(), "test")).isTrue();

        ImportFile f = files.findAll().stream().filter(x -> x.getFileName().equals("GHTK_COD_20261021.csv")).findFirst().orElseThrow();
        assertThat(f.getStatus()).isEqualTo(FileStatus.COMPLETED_WITH_ERRORS);
        assertThat(f.getTotalRows()).isEqualTo(21);
        assertThat(lines.countByFileId(f.getId())).isEqualTo(20);
        assertThat(f.getAmountTotal()).isEqualTo(20 * 630_000L);
        var bad = errors.findTop200ByFileIdOrderByRowNo(f.getId());
        assertThat(bad).hasSize(1);
        assertThat(bad.get(0).getRowNo()).isEqualTo(22);
        assertThat(bad.get(0).getColumnName()).isEqualTo("Thực nhận");
    }

    @Test
    void aChangedDateFormatFailsTheWholeFileWithAReadableReason() throws IOException {
        StringBuilder csv = new StringBuilder(GHTK_HEADER);
        for (int i = 0; i < 12; i++) {
            csv.append("S3000").append(i).append(",DH26102100").append(10 + i).append(",21-10-2026 16h40,650000,20000,630000,Đã đối soát\n");
        }
        Path file = write("GHTK_COD_20261022.csv", csv.toString());

        assertThat(pipeline.intake(file, Instant.now(), "test")).isFalse();

        ImportFile f = files.findAll().stream().filter(x -> x.getFileName().equals("GHTK_COD_20261022.csv")).findFirst().orElseThrow();
        assertThat(f.getStatus()).isEqualTo(FileStatus.FAILED);
        assertThat(f.getMessage()).startsWith("Vượt ngưỡng bỏ qua").contains("dòng 2").contains("16h40");
        assertThat(lines.countByFileId(f.getId())).isZero();
        assertThat(errors.countByFileId(f.getId())).isGreaterThanOrEqualTo(5);
    }

    @Test
    void theSameFileReceivedTwiceIsLoadedOnce() throws IOException {
        String csv = GHTK_HEADER + "S40001,DH2610220001,22-10-2026 16:40,350000,20000,330000,Đã đối soát\n";
        assertThat(pipeline.intake(write("GHTK_COD_20261023.csv", csv), Instant.now(), "test")).isTrue();
        assertThat(pipeline.intake(write("GHTK_COD_20261023_gui_lai.csv", csv), Instant.now(), "test")).isFalse();

        ImportFile dup = files.findAll().stream().filter(x -> x.getFileName().equals("GHTK_COD_20261023_gui_lai.csv")).findFirst().orElseThrow();
        assertThat(dup.getStatus()).isEqualTo(FileStatus.DUPLICATE);
        assertThat(dup.getMessage()).contains("Trùng checksum với GHTK_COD_20261023.csv");
    }

    private static Path write(String name, String content) throws IOException {
        Path inbox = data.resolve("inbox");
        Files.createDirectories(inbox);
        return Files.writeString(inbox.resolve(name), content, StandardCharsets.UTF_8);
    }
}
