package vn.reconhub.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

import java.time.Clock;

/** Wires the shared core into the api and batch applications. */
@Configuration
@ComponentScan("vn.reconhub.core")
@EntityScan("vn.reconhub.core.domain")
@EnableJpaRepositories("vn.reconhub.core.repo")
@MapperScan("vn.reconhub.core.report")
@EnableConfigurationProperties(ReconProperties.class)
public class CoreConfig {

    @Bean
    Clock clock(ReconProperties props) {
        return Clock.system(props.getZone());
    }

    /** JMS messages are JSON so either side can be replaced without Java serialization. */
    @Bean
    MessageConverter jmsMessageConverter(ObjectMapper mapper) {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(mapper);
        converter.setTargetType(MessageType.TEXT);
        converter.setTypeIdPropertyName("_type");
        return converter;
    }
}
