# 애월 (AeWol) — Backend

> 반려동물 디지털 지갑 서비스

## 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Java 17 |
| Framework | Spring Boot 3.x |
| ORM | MyBatis |
| Database | MySQL 8 |
| Cache | Redis 7 |
| Build | Gradle |

## 로컬 개발 환경

### 사전 요구사항

- JDK 17
- Docker & Docker Compose

### 인프라 실행

```bash
docker compose up -d
```

- MySQL: `localhost:3306` (DB: aewol, User: aewol / aewol1234)
- Redis: `localhost:6379`

### 애플리케이션 실행

```bash
./gradlew bootRun
```

## 브랜치 전략

```
main ← develop ← feature/{이슈번호}-{기능명}
```

| 브랜치 | 용도 |
|--------|------|
| `main` | 프로덕션 배포 |
| `develop` | 개발 통합 |
| `feature/*` | 기능 개발 |

## 팀

PJT-28-2
