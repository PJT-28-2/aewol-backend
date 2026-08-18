package com.aewol.common.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 컨테이너 헬스체크와 배포 직후 확인에 쓰는 엔드포인트.
 *
 * <p>Spring Boot Actuator가 없는 구조라 직접 둔다. 애플리케이션이 요청을 받을 수 있는
 * 상태인지만 알려주고 DB·Redis 같은 외부 의존성은 확인하지 않는다. 의존성까지 묶어
 * 판정하면 DB가 잠시 흔들릴 때 멀쩡한 컨테이너가 재시작되어 상황을 더 나쁘게 만든다.
 */
@Tag(name = "Health", description = "헬스체크")
@RestController
public class HealthController {

    @Operation(summary = "헬스체크", description = "애플리케이션이 요청을 처리할 수 있는 상태인지 확인한다.")
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
