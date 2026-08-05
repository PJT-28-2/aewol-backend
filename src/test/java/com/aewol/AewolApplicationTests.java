package com.aewol;

import com.aewol.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringJUnitWebConfig(classes = AppConfig.class)
class AewolApplicationTests {

    @Test
    void contextLoads() {
        // Spring ApplicationContext가 정상적으로 로드되는지 확인
    }
}
