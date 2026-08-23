plugins {
    java
    id("org.flywaydb.flyway") version "9.22.3"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.aewol"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

buildscript {
    dependencies {
        classpath("org.flywaydb:flyway-mysql:9.22.3")
    }
}

val springVersion      = "5.3.39"
val springSecVersion   = "5.8.16"
val springBatchVersion = "4.3.10"
val tomcatVersion      = "9.0.98"

dependencies {
    // ── Spring Core ────────────────────────────────────────────────
    implementation("org.springframework:spring-webmvc:$springVersion")
    implementation("org.springframework:spring-context-support:$springVersion")
    implementation("org.springframework:spring-websocket:$springVersion")
    implementation("org.springframework:spring-messaging:$springVersion")
    implementation("org.springframework:spring-jdbc:$springVersion")
    implementation("org.springframework:spring-tx:$springVersion")

    // ── Spring Security 5.8.x ─────────────────────────────────────
    implementation("org.springframework.security:spring-security-web:$springSecVersion")
    implementation("org.springframework.security:spring-security-config:$springSecVersion")
    implementation("org.springframework.security:spring-security-messaging:$springSecVersion")

    // ── Spring Batch 4.3.x (Spring 5.x 전용) ──────────────────────
    implementation("org.springframework.batch:spring-batch-core:$springBatchVersion")
    implementation("org.springframework.batch:spring-batch-infrastructure:$springBatchVersion")

    // ── Spring Data Redis 2.7.x ───────────────────────────────────
    implementation("org.springframework.data:spring-data-redis:2.7.18")
    implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")

    // ── Embedded Tomcat 9 (javax.servlet 기반) ────────────────────
    implementation("org.apache.tomcat.embed:tomcat-embed-core:$tomcatVersion")
    implementation("org.apache.tomcat.embed:tomcat-embed-websocket:$tomcatVersion")
    implementation("org.apache.tomcat.embed:tomcat-embed-jasper:$tomcatVersion")

    // ── Jackson ───────────────────────────────────────────────────
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")

    // ── Bean Validation (javax.* 네임스페이스) ─────────────────────
    implementation("javax.validation:validation-api:2.0.1.Final")
    implementation("org.hibernate.validator:hibernate-validator:6.2.5.Final")

    // ── MyBatis ───────────────────────────────────────────────────
    implementation("org.mybatis:mybatis:3.5.19")
    implementation("org.mybatis:mybatis-spring:2.1.2")

    // ── MySQL ──────────────────────────────────────────────────────
    runtimeOnly("com.mysql:mysql-connector-j:8.4.0")

    // ── Flyway ────────────────────────────────────────────────────
    implementation("org.flywaydb:flyway-core:9.22.3")
    implementation("org.flywaydb:flyway-mysql:9.22.3")

    // ── Connection Pool ───────────────────────────────────────────
    implementation("com.zaxxer:HikariCP:5.1.0")

    // ── AWS S3 (업로드 파일 저장소, prod 프로파일) ────────────────
    implementation(platform("software.amazon.awssdk:bom:2.53.2"))
    implementation("software.amazon.awssdk:s3")

    // ── JWT ───────────────────────────────────────────────────────
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // ── Swagger (OpenAPI 3.0 애노테이션) ──────────────────────────
    implementation("io.swagger.core.v3:swagger-models:2.2.25")
    implementation("io.swagger.core.v3:swagger-annotations:2.2.25")

    // ── Springfox — 순수 Spring MVC용 OpenAPI 스캐너 + UI ─────────
    // Spring Boot가 아니라 springdoc-openapi를 쓸 수 없어 springfox를 사용한다.
    implementation("io.springfox:springfox-oas:3.0.0")
    implementation("io.springfox:springfox-swagger-ui:3.0.0")

    // ── YAML 프로퍼티 로딩 (SnakeYAML) ────────────────────────────
    implementation("org.yaml:snakeyaml:2.3")

    // ── Mail ──────────────────────────────────────────────────────
    implementation("com.sun.mail:jakarta.mail:1.6.7")

    // ── JSON 유틸 ─────────────────────────────────────────────────
    implementation("com.google.code.gson:gson:2.11.0")

    // ── HTTP 클라이언트 (RestTemplate) ────────────────────────────
    implementation("org.apache.httpcomponents:httpclient:4.5.14") {
        // jcl-over-slf4j가 commons-logging과 같은 클래스(org.apache.commons.logging.*)를
        // 제공한다. 둘 다 있으면 클래스패스 순서에 따라 어느 쪽이 뽑힐지 달라져,
        // HttpClient 로그가 slf4j를 타기도 하고 안 타기도 한다. 원본을 빼서 없앤다.
        exclude(group = "commons-logging", module = "commons-logging")
    }

    // ── 좌표계 변환 (EPSG:5174 Bessel TM -> EPSG:4326 WGS84) ───────
    implementation("org.locationtech.proj4j:proj4j:1.3.0")

    // ── Lombok ────────────────────────────────────────────────────
    compileOnly("org.projectlombok:lombok:1.18.36")
    annotationProcessor("org.projectlombok:lombok:1.18.36")

    // ── Test ──────────────────────────────────────────────────────
    testImplementation("org.springframework:spring-test:$springVersion")
    testImplementation("org.springframework.security:spring-security-test:$springSecVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("org.mockito:mockito-core:5.12.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.12.0")
    testImplementation("com.h2database:h2:2.3.232")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // slf4j-simple은 설정이 거의 불가능해(패턴·파일 출력·레벨별 분리 불가) 운영 로그로
    // 쓸 수 없었다. logback으로 바꾸고 설정은 logback.xml에 둔다.
    // (Spring Boot가 아니라 logback-spring.xml의 프로파일 기능은 쓸 수 없어 환경변수로 제어한다)
    runtimeOnly("ch.qos.logback:logback-classic:1.2.13")
    // 톰캣·MyBatis 등이 쓰는 다른 로깅 API를 slf4j로 모아 한 곳에서 제어한다.
    // jcl-over-slf4j는 클래스를 대신 제공하는 방식이라 두기만 하면 되지만,
    // jul-to-slf4j는 코드에서 SLF4JBridgeHandler.install()을 불러야 동작한다.
    // AewolApplication이 기동 초반에 부르므로 컴파일 시점에도 필요하다.
    runtimeOnly("org.slf4j:jcl-over-slf4j:1.7.36")
    implementation("org.slf4j:jul-to-slf4j:1.7.36")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // MethodValidationPostProcessor가 @RequestParam 제약조건 위반 메시지에 arg0/arg1 대신
    // 실제 파라미터명(petType, age 등)을 쓰도록 바이트코드에 파라미터명을 남긴다.
    options.compilerArgs.add("-parameters")
}

// Flyway 설정: docker-compose 로 띄운 로컬 MySQL 기준 (환경변수로 오버라이드 가능)
flyway {
    url = System.getenv("DB_URL") ?: "jdbc:mysql://localhost:3307/aewol?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
    user = System.getenv("DB_USERNAME") ?: "aewol"
    password = System.getenv("DB_PASSWORD") ?: "aewol1234"
    locations = arrayOf("filesystem:src/main/resources/db/migration")
}

// 실행 가능한 Fat JAR 생성
//
// 직접 zipTree로 합치던 방식은 DuplicatesStrategy.EXCLUDE 때문에 경로가 겹치는 파일 중
// 먼저 들어간 하나만 남겼다. 문제는 META-INF/services/* 다. 여러 의존성이 같은 경로에
// 각자의 목록을 두기 때문에, 하나만 남으면 ServiceLoader로 등록되는 기능이 조용히 사라진다.
//
// 실제로 flyway-core의 플러그인 목록이 flyway-mysql 것에 밀려 없어졌고, 그 결과 운영에서
// 마이그레이션 36개가 전부 "이름 규칙 위반"으로 무시되어 스키마가 생성되지 않았다.
// shadowJar의 mergeServiceFiles()는 이 파일들을 이어붙여 합친다.
tasks.shadowJar {
    archiveClassifier.set("all")
    manifest {
        attributes["Main-Class"] = "com.aewol.AewolApplication"
    }
    mergeServiceFiles()
    exclude(
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA"
    )
}

// Dockerfile과 문서가 쓰던 이름을 그대로 유지한다.
tasks.register("fatJar") {
    dependsOn(tasks.shadowJar)
}
