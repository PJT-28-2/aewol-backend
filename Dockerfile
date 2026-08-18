# syntax=docker/dockerfile:1

# ─────────────────────────────────────────────────────────────────────
# build
# ─────────────────────────────────────────────────────────────────────
FROM gradle:8.8-jdk17 AS build
WORKDIR /workspace

COPY --chown=gradle:gradle . .

# 테스트는 CI에서 이미 수행하므로 이미지 빌드에서는 건너뛴다.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle --no-daemon fatJar -x test

# .dockerignore가 걸러주지만, 컨텍스트 설정이 바뀌었을 때 로컬 시크릿이 이미지에
# 실려 레지스트리로 새어나가는 것을 막는 최후 방어선이다.
#
# 부분일치로 판정하면 함께 커밋되는 application-local.yml.example(플레이스홀더만 들어
# 있어 무해하다)까지 걸리므로 grep -x로 정확히 그 파일만 본다.
RUN JAR="$(ls build/libs/*-all.jar)" && \
    echo "JAR 내 application-local* 항목:" && \
    { jar tf "$JAR" | grep 'application-local' || echo "  (없음)"; } && \
    if jar tf "$JAR" | grep -qx 'application-local.yml'; then \
        echo "빌드 중단: application-local.yml이 JAR에 포함되었습니다. .dockerignore를 확인하세요." >&2; \
        exit 1; \
    fi && \
    cp "$JAR" /workspace/app.jar

# ─────────────────────────────────────────────────────────────────────
# runtime
# ─────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre AS runtime

ENV TZ=Asia/Seoul
RUN ln -snf "/usr/share/zoneinfo/$TZ" /etc/localtime && echo "$TZ" > /etc/timezone \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 컨테이너가 탈취됐을 때 피해 범위를 줄이기 위해 비root로 실행한다.
RUN useradd --system --create-home --uid 1001 aewol

WORKDIR /app
COPY --from=build /workspace/app.jar /app/app.jar

# 마이그레이션 스크립트를 파일시스템에 둔다. JAR 안에도 함께 들어가지만, 손으로 만든
# fat JAR에서는 Flyway의 클래스패스 스캐너가 파일명을 잘못 읽어 전부 건너뛴다(MigrateMain 참고).
COPY --from=build /workspace/src/main/resources/db/migration /app/db/migration

# S3 전환(#198) 전까지 업로드 파일이 쌓이는 경로. 컨테이너를 갈아끼워도
# 파일이 남도록 볼륨으로 마운트한다.
RUN mkdir -p /app/uploads && chown -R aewol:aewol /app

USER aewol
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=90s --retries=3 \
    CMD curl -fsS http://localhost:8080/api/health || exit 1

# AewolApplication은 활성 프로파일을 '시스템 프로퍼티'로 읽는다(Spring Boot가 아니라
# SPRING_PROFILES_ACTIVE 환경변수가 자동 반영되지 않는다). 그래서 -D로 넘겨준다.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Duser.timezone=Asia/Seoul -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod} -jar /app/app.jar"]
