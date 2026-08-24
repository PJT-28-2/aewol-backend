package com.aewol.external.sms;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 운영에서 SOLAPI 값이 비어 있으면 기동을 막는다.
 *
 * <p>빈 기본값이면 서버는 살아 있고 SMS 요청만 503이 난다. 호출 전이라 SOLAPI 로그도 없다.
 */
@Slf4j
@Component
@Profile("prod")
public class SolapiProdConfigValidator {

    private final String apiKey;
    private final String apiSecret;
    private final String sender;

    public SolapiProdConfigValidator(
            @Value("${external.solapi.api-key:}") String apiKey,
            @Value("${external.solapi.api-secret:}") String apiSecret,
            @Value("${external.solapi.sender:}") String sender) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.sender = sender;
    }

    @PostConstruct
    void validate() {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(apiKey)) {
            missing.add("SOLAPI_API_KEY");
        }
        if (!StringUtils.hasText(apiSecret)) {
            missing.add("SOLAPI_API_SECRET");
        }
        if (!StringUtils.hasText(sender)) {
            missing.add("SOLAPI_SENDER");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "운영 SOLAPI 설정이 비어 있습니다: " + missing
                            + ". SSM Parameter Store(/aewol/prod)를 확인하세요.");
        }
        log.info("SOLAPI 운영 설정 확인 완료. senderConfigured=true");
    }
}
