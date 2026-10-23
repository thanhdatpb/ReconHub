package vn.reconhub.batch.seed;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;

/**
 * seedOrdersJob: loads the sample orders the data generator wrote into the OMS tables. Only used by the demo:
 * in production the orders are already in the OMS database. Existing orders are left alone.
 */
@Configuration
public class SeedSteps {
    public static final String JOB = "seedOrdersJob";
    static final String[] COLUMNS = {"code", "channel", "channel_ref", "customer_name", "phone", "amount", "cod_amount", "pay_via",
            "tracking_code", "status", "remote_area", "created_at", "delivered_at", "note"};

    @Bean
    Job seedOrdersJob(JobRepository repo, Step loadOrdersStep) {
        return new JobBuilder(JOB, repo).start(loadOrdersStep).build();
    }

    @Bean
    Step loadOrdersStep(JobRepository repo, PlatformTransactionManager tx, FlatFileItemReader<FieldSet> orderCsvReader,
                        JdbcBatchItemWriter<FieldSet> orderWriter) {
        return new StepBuilder("loadOrdersStep", repo).<FieldSet, FieldSet>chunk(1000, tx)
                .reader(orderCsvReader)
                .writer(orderWriter)
                .build();
    }

    @Bean
    @StepScope
    FlatFileItemReader<FieldSet> orderCsvReader(@Value("#{jobParameters['file']}") String file) {
        return new FlatFileItemReaderBuilder<FieldSet>()
                .name("orderCsvReader")
                .resource(new FileSystemResource(file))
                .encoding(StandardCharsets.UTF_8.name())
                .linesToSkip(1)
                .delimited().names(COLUMNS)
                .fieldSetMapper(fs -> fs)
                .build();
    }

    @Bean
    JdbcBatchItemWriter<FieldSet> orderWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<FieldSet>()
                .dataSource(dataSource)
                .sql("""
                        insert into oms.orders (code, channel, channel_ref, customer_name, phone, amount, cod_amount, pay_via,
                                                tracking_code, status, remote_area, created_at, delivered_at, note)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (code) do nothing
                        """)
                .itemPreparedStatementSetter((fs, ps) -> {
                    ps.setString(1, fs.readString("code"));
                    ps.setString(2, fs.readString("channel"));
                    ps.setString(3, blank(fs.readString("channel_ref")));
                    ps.setString(4, fs.readString("customer_name"));
                    ps.setString(5, blank(fs.readString("phone")));
                    ps.setLong(6, fs.readLong("amount"));
                    ps.setLong(7, fs.readLong("cod_amount"));
                    ps.setString(8, fs.readString("pay_via"));
                    ps.setString(9, blank(fs.readString("tracking_code")));
                    ps.setString(10, fs.readString("status"));
                    ps.setBoolean(11, Boolean.parseBoolean(fs.readString("remote_area")));
                    ps.setTimestamp(12, Timestamp.from(OffsetDateTime.parse(fs.readString("created_at")).toInstant()));
                    String delivered = fs.readString("delivered_at");
                    if (delivered.isBlank()) ps.setTimestamp(13, null);
                    else ps.setTimestamp(13, Timestamp.from(OffsetDateTime.parse(delivered).toInstant()));
                    ps.setString(14, blank(fs.readString("note")));
                })
                .assertUpdates(false)
                .build();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
