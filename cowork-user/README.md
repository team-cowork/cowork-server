# cowork-user

## 역할

사용자 계정·공개 프로필의 원본 데이터를 관리합니다.

- 로그인 요청에 따른 계정·프로필 생성·동기화와 DataGSM 정보 갱신
- 프로필 조회·수정, 사용자 검색과 상태 메시지 관리
- 프로필 이미지 업로드·삭제와 온라인·오프라인 상태 반영

## 스택

- C# / ASP.NET Core (.NET 10)
- .NET CLI (`dotnet test cowork-user.slnx`)
- MySqlConnector · Dapper / MySQL (마이그레이션은 공식 Flyway CLI)
- Confluent.Kafka / StackExchange.Redis / AWS SDK S3 / SeaweedFS
- Eureka / Config Server

## 포트

| 용도 | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------|---------------|--------------------------|
| HTTP | `8082`        | `8082`                   |

## 환경변수

아래 값은 [로컬 Compose 설정](../deploy/compose/stack.yaml)의 컨테이너 입력입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `APP_CONFIG_PROFILE` | `local` | 설정 프로파일 |
| `CONFIG_SERVER_URL` | `http://cowork-config:8761` | 필수 Config Server 연결 |
| `CONFIG_CLIENT_USERNAME` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_USER_USERNAME`에서 전달 |
| `CONFIG_CLIENT_PASSWORD` | 없음 | 필수. `.env`의 `CONFIG_CLIENT_USER_PASSWORD`에서 전달 |

운영 검색은 `utf8mb4_unicode_ci`와 `NO_BACKSLASH_ESCAPES`가 없는 세션에 의존합니다.
