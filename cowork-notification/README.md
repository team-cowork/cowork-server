# cowork-notification

## 역할

사용자 설정에 맞춰 푸시 알림과 실시간 알림을 전달합니다.

- Kafka 알림 요청 소비와 수신 대상·표시 정보 결정
- Firebase Cloud Messaging 푸시 발송과 디바이스 토큰 관리
- SSE 기반 실시간 알림 스트림 제공

## 스택

- Go / Chi
- Go modules + Makefile
- GORM / MySQL
- Kafka / Firebase Admin SDK / Eureka / Config Server

## 포트

| 용도       | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------|---------------|--------------------------|
| HTTP / SSE | `8086`        | `8086`                   |

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수             | 기본값                      | 설명                                                      |
|------------------|-----------------------------|-----------------------------------------------------------|
| `APP_CONFIG_URL` | `http://cowork-config:8761` | 필수 Config Server 연결                                   |
| `APP_PROFILE`    | `local`                     | 설정 프로파일. Compose의 `SPRING_PROFILES_ACTIVE` 값 사용 |

- Config Server: 포트, Kafka topic·group, Eureka.
- Vault: `db.dsn`, 프로파일별 `fcm.credentials-json`.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 가이드](../docs/configuration.md)를 참고합니다.

## 디바이스 토큰 소유권

- `POST /notifications/tokens`는 FCM 토큰을 현재 계정에 등록한다. 같은 계정의 재등록은 `platform`과 `updated_at`을 갱신한다.
- 다른 계정이 이미 소유한 토큰을 등록하면 하나의 transaction에서 이전 행을 제거하고 새 소유권 행을 만든다. 새 `device_token_id`를 사용하므로 이전 계정의 대기 중인 FCM 재시도는 취소된다.
- 로그아웃이나 계정 전환 전에 `DELETE /notifications/tokens/{token}`을 호출한다. 현재 계정이 소유한 토큰만 해제할 수 있다.
- `V8__enforce_device_token_single_owner.sql`은 중복 토큰마다 `updated_at`, `id` 순으로 가장 최근인 행을 남기고 `token` 단독 unique 제약을 적용한다.

운영 데이터 사본에서 migration을 적용하기 전후로 다음 쿼리를 실행한다. 토큰 원문은 결과에 출력하지 않는다.

```sql
SELECT COUNT(*) AS duplicate_token_groups
FROM (
    SELECT token
    FROM tb_device_token
    GROUP BY token
    HAVING COUNT(*) > 1
) duplicates;

SELECT index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'tb_device_token'
GROUP BY index_name, non_unique;
```

적용 후 `duplicate_token_groups`는 `0`이고, `uq_tb_device_token_token`은 `token` 하나만 포함한 unique index여야 한다.
