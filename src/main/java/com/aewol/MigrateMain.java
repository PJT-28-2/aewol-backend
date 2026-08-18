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

    public static void main(String[] args) {
        MigrateResult result = Flyway.configure()
                .dataSource(required("DB_URL"), required("DB_USERNAME"), required("DB_PASSWORD"))
                .locations("classpath:db/migration")
                .load()
                .migrate();

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
