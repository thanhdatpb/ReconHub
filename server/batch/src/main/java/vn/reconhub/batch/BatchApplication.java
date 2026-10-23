package vn.reconhub.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.scheduling.annotation.EnableScheduling;
import vn.reconhub.core.config.CoreConfig;

/** Runs the Spring Batch jobs: on schedule, when files arrive in /data/inbox, and on JMS commands from the API. */
@SpringBootApplication
@Import(CoreConfig.class)
@EnableScheduling
@EnableJms
public class BatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(BatchApplication.class, args);
    }
}
