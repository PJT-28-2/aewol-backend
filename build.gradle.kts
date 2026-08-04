plugins {
    java
    id("org.flywaydb.flyway") version "9.22.3"
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

    // ── JWT ───────────────────────────────────────────────────────
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // ── Swagger (OpenAPI 3.0 모델만 사용, UI는 별도 구성) ─────────
    implementation("io.swagger.core.v3:swagger-models:2.2.25")
    implementation("io.swagger.core.v3:swagger-annotations:2.2.25")

    // ── YAML 프로퍼티 로딩 (SnakeYAML) ────────────────────────────
    implementation("org.yaml:snakeyaml:2.3")

    // ── Mail ──────────────────────────────────────────────────────
    implementation("com.sun.mail:jakarta.mail:1.6.7")

    // ── JSON 유틸 ─────────────────────────────────────────────────
    implementation("com.google.code.gson:gson:2.11.0")

    // ── HTTP 클라이언트 (RestTemplate) ────────────────────────────
    implementation("org.apache.httpcomponents:httpclient:4.5.14")

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

    // TODO(팀 합의 후 logback 전환): 런타임 로그 확인용 임시 백엔드
    runtimeOnly("org.slf4j:slf4j-simple:1.7.36")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Flyway 설정: docker-compose 로 띄운 로컬 MySQL 기준 (환경변수로 오버라이드 가능)
flyway {
    url = System.getenv("DB_URL") ?: "jdbc:mysql://localhost:3307/aewol?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
    user = System.getenv("DB_USERNAME") ?: "aewol"
    password = System.getenv("DB_PASSWORD") ?: "aewol1234"
    locations = arrayOf("filesystem:src/main/resources/db/migration")
}

// 실행 가능한 Fat JAR 생성
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "META-INF/INDEX.LIST",
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "module-info.class",
        "META-INF/versions/**/module-info.class"
    )
    manifest {
        attributes["Main-Class"] = "com.aewol.AewolApplication"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
}
