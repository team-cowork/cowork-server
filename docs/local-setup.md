# 로컬 개발 환경 구성

## 필요한 도구

| 도구 | 버전 | 비고 |
|---|---|---|
| JDK | 21 | Temurin 권장 |
| Docker / Docker Compose | 최신 | MySQL, MinIO, LiveKit 실행 |
| IntelliJ IDEA | 2025.2 이상 | Kotlin 플러그인 기본 포함 |

## 처음 실행하기

1. 저장소를 받은 뒤 환경 변수 예시 파일을 복사합니다.

   ```bash
   cp .env.example .env
   ```

2. 인프라 컨테이너를 실행합니다.

   ```bash
   docker compose up -d
   ```

3. 애플리케이션을 실행합니다. 처음 실행하면 Flyway가 스키마를 만듭니다.

   ```bash
   ./gradlew bootRun
   ```

## 환경 변수

`.env`의 값을 비워 두면 `application.yml`의 기본값을 사용합니다.

| 이름 | 설명 | 로컬 기본값 |
|---|---|---|
| `DB_USERNAME`, `DB_PASSWORD` | MySQL 접속 계정 | `cowork` / `cowork` |
| `JWT_SECRET` | 액세스 토큰 서명 키 (32바이트 이상) | 개발용 기본 키 |
| `DATAGSM_CLIENT_ID` | DataGSM OAuth 클라이언트 ID | 없음 |
| `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY` | MinIO 접근 키 | `minioadmin` |
| `STORAGE_BUCKET` | 파일 저장 버킷 | `cowork` |

DataGSM 클라이언트 ID는 팀 노션의 개발 계정 페이지에서 확인합니다.

## 자주 묻는 질문

### 3306 포트가 이미 사용 중이라고 나옵니다

로컬에 설치한 MySQL이 실행 중인 경우입니다. 로컬 MySQL을 멈추거나 `docker-compose.yml`의 포트를 `3307:3306`으로 바꾸고 `DB_URL`도 함께 바꿉니다.

### MinIO 버킷이 없다는 오류가 납니다

`minio-init` 컨테이너가 버킷을 만들기 전에 애플리케이션이 실행된 경우입니다. `docker compose logs minio-init`로 완료를 확인한 뒤 다시 실행합니다.

### Swagger UI가 열리지 않습니다

`http://localhost:8080/swagger-ui.html`로 접속합니다. `/swagger-ui/index.html`은 보안 설정에서 허용하지 않습니다.
