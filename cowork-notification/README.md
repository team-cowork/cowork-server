# cowork-notification

## 역할

사용자 설정에 맞춰 푸시 알림과 실시간 알림을 전달합니다.

- Kafka 알림 요청 소비와 수신 대상·표시 정보 결정
- Firebase Cloud Messaging 푸시 발송과 Firebase 설치(FID) 관리
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

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 변경 절차](../docs/deployment.md#설정-변경과-재배포)를 참고합니다.

## Firebase 설치(FID) 소유권

FCM 전송 대상은 Firebase installation ID(FID)뿐이다. registration token API·컬럼·전송 경로는 없다.

| 상황 | 클라이언트 호출 |
| --- | --- |
| 최초 등록·앱 시작 | `POST /notifications/installations` `{ "fid", "platform" }` |
| 재설치 | 새 FID로 `POST`. 이전 FID는 FCM이 `UNREGISTERED`로 응답할 때 서버가 삭제한다. |
| FID 변경 | 이전 FID로 `DELETE` 후 새 FID로 `POST` |
| 계정 전환 | 이전 계정으로 `DELETE` 후 새 계정으로 `POST` |
| 로그아웃 | `DELETE /notifications/installations/{fid}` |

- `fid`는 Firebase Installations SDK가 만든 URL-safe base64 문자열이어야 한다. 새로 생성된 FID는 22자이고, Android SDK가 레거시 Instance ID를 이관해 그대로 쓰는 FID는 11자다. registration token은 `400`으로 거부한다.
- 같은 계정의 재등록은 `platform`과 `updated_at`을 갱신한다.
- 다른 계정이 이미 소유한 FID를 등록하면 하나의 transaction에서 이전 행을 제거하고 새 소유권 행을 만든다. 새 `installation_id`를 사용하므로 이전 계정의 대기 중인 FCM 재시도는 취소된다.
- 현재 계정이 소유한 FID만 해제할 수 있다.

### FID 전환 배포

`V10__replace_device_token_with_fid_installation.sql`은 registration token 등록(`tb_device_token`)과 그에 연결된 재시도 원장을 이관 없이 삭제하고 `tb_device_installation`과 FID 기준 원장을 만든다. 배포 순서는 다음과 같다.

1. FID를 등록하는 클라이언트 버전을 준비한다.
2. 서버를 배포한다. 이 시점부터 기존 token 등록은 사라지고 `/notifications/tokens`는 없다.
3. 클라이언트를 배포한다. 앱은 시작·로그인 때 FID를 등록하므로 업데이트한 기기부터 다시 푸시를 받는다.

적용 후 다음 쿼리로 스키마를 확인한다. FID 원문은 결과에 출력하지 않는다.

```sql
SELECT COUNT(*) AS legacy_token_table
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name = 'tb_device_token';

SELECT index_name, non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS indexed_columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND table_name = 'tb_device_installation'
GROUP BY index_name, non_unique;
```

`legacy_token_table`은 `0`이고, `uq_tb_device_installation_fid`는 `fid` 하나만 포함한 unique index여야 한다.
