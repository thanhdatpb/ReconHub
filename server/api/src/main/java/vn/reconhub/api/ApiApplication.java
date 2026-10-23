package vn.reconhub.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import vn.reconhub.core.config.CoreConfig;

/** REST API behind the web console. Long work (loading files, restarting jobs) is handed to the batch app over JMS. */
@SpringBootApplication
@Import(CoreConfig.class)
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
