package io.github.ajayaj724.tradecore;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class TradecoreApplicationIT {

    @Test
    void contextLoads(ApplicationContext context) {
        assertThat(context.getId()).isEqualTo("tradecore");
    }
}
