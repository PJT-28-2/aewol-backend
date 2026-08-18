package com.aewol;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 배포 시 애플리케이션보다 먼저 단독으로 실행하는 Flyway 마이그레이션 진입점.
 *
 * <p>로컬 개발은 기존대로 {@code ./gradlew flywayMigrate}를 쓴다. 운영 이미지에는
 * Gradle도 소스도 없어 같은 방식을 쓸 수 없으므로, 애플리케이션 JAR 안에 함께 실린
 * classpath의 마이그레이션 스크립트를 직접 실행한다.
 *
 * <p>앱 기동 과정에 마이그레이션을 끼워넣지 않고 분리한 이유는, 실패했을 때 애플리케이션이
 * 절반만 올라간 상태가 되지 않도록 하기 위해서다. 이 프로세스가 0이 아닌 코드로 종료하면
 * compose의 {@code service_completed_successfully} 조건이 깨져 app 컨테이너가 아예
 * 기동하지 않는다.
 */
public final class MigrateMain {

    private MigrateMain() {
    }

    /**
     * 마이그레이션 스크립트 위치.
     *
     * <p>클래스패스가 아니라 파일시스템을 본다. 이 프로젝트의 실행 JAR은 Spring Boot가
     * 아니라 Gradle {@code Jar} 태스크로 직접 만든 것이라, Flyway의 클래스패스 스캐너가
     * 항목 이름을 위치 기준으로 상대화하지 못한다. 그 결과 파일명을
     * {@code V1__init_schema.sql}이 아니라 {@code db/migration/V1__init_schema.sql}로 읽어
     * "V로 시작하지 않는다"며 36개 전부를 건너뛰고 스키마가 만들어지지 않았다.
     *
     * <p>Dockerfile이 같은 스크립트를 이 경로에 복사해 둔다. 파일시스템 경로는 해석이
     * 명확해 같은 문제가 생기지 않는다. 로컬 개발은 그대로 {@code ./gradlew flywayMigrate}를
     * 쓰므로 영향이 없다.
     */
    private static final String DEFAULT_LOCATIONS = "filesystem:/app/db/migration";

    public static void main(String[] args) {
        String locations = System.getenv().getOrDefault("FLYWAY_LOCATIONS", DEFAULT_LOCATIONS);

        MigrateResult result = Flyway.configure()
                .dataSource(required("DB_URL"), required("DB_USERNAME"), required("DB_PASSWORD"))
                .locations(locations.split(","))
                // 이름 규칙에 맞지 않는 파일이 있으면 조용히 건너뛰지 말고 즉시 실패한다.
                // 위 문제가 경고 한 줄로만 남고 "적용 0건"으로 성공 처리됐던 것을 막는다.
                .validateMigrationNaming(true)
                .load()
                .migrate();

        if (result.migrationsExecuted == 0 && result.initialSchemaVersion == null) {
            throw new IllegalStateException(
                    "적용된 마이그레이션이 없습니다. locations(" + locations + ")를 확인하세요.");
        }

        System.out.printf("Flyway 마이그레이션 완료 — 적용 %d건, 스키마 버전 %s%n",
                result.migrationsExecuted,
                result.targetSchemaVersion == null ? "변경 없음" : result.targetSchemaVersion);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("환경변수 " + name + " 가 설정되지 않았습니다.");
        }
        return value;
    }
}
