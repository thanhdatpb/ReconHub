package vn.reconhub.core.it;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import vn.reconhub.core.config.CoreConfig;

/** The core alone, as the api and batch apps assemble it. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(CoreConfig.class)
class TestApp {}
