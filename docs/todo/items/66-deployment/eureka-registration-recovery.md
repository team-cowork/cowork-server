# 운영 Eureka 등록과 광고 주소 복구

- **서비스**: Config/Eureka, Gateway, Authorization, Notification
- **우선순위**: 🔴 높음
- **현재 상태**: 운영 Eureka에서 Authorization·Notification이 누락되어 있고 Gateway가 서로 다른 주소로 중복 등록되어 있다.
- **관련 작업**: [운영 Config 접근 보호와 AppRole 전환](../65-security/config-control-plane-rollout.md)

## 문제

2026-10-13 운영 점검에서 `cowork-db` VM의 Eureka 목록에 Authorization과 Notification이 없었다.
Authorization의 직접 health 요청은 HTTP `200`을 반환했지만 heartbeat 요청은 HTTP `404`를 반환했다.
로그에는 등록 성공과 heartbeat 실패가 반복되었다. 구버전 라이브러리가 출력한 `502`는 내부 오류 번호이며,
관측한 heartbeat의 실제 HTTP 상태 코드와 다르다. 등록 `POST`가 실패한 정확한 이유는 아직 확인하지 않았다.

Gateway는 `cowork-db` VM의 주소와 Docker 내부 주소 `172.17.0.2`로 각각 등록되어 있었다.
Docker 내부 주소는 다른 VM에서 접근할 수 없다. 직접 health 성공만으로 Eureka를 통한 호출 경로가
정상이라고 판정할 수 없다. 중복 등록을 만든 프로세스와 실제 트래픽 사용 여부를 추가로 확인한다.

현재 저장소에는 HTTP 응답을 검사하는 Eureka 전송 구현이 있다.
[관련 PR #416](https://github.com/team-cowork/cowork-server/pull/416)은 반영되었지만,
운영 Authorization은 이전 이미지로 실행되어 있다. 오류 판정 개선을 배포하고 등록 요청의 실제 실패 원인을
확인한다. 로그 문구만 바꾸거나 health 대기시간만 늘려 해결된 것으로 판정하지 않는다.

## 대조 기준

| 대상 | 확인할 값 |
|------|-----------|
| 배포 대상 | 실제 실행 VM, 컨테이너, 이미지 SHA, `APP_CONFIG_PROFILE` |
| Eureka 연결 | `EUREKA_SERVER_URL`, 서비스별 Config 계정, 요청의 실제 HTTP 상태 |
| 광고 주소 | `ADVERTISE_IP`, `HOST_PORT`, `EUREKA_INSTANCE_HOST`, `EUREKA_INSTANCE_PORT` |
| 등록 identity | 앱 이름, instance ID, 서버가 허용하는 서비스 계정과 identity의 대응 |
| 프로세스 수 | 전용 VM과 기존 VM의 같은 서비스 실행 여부, 남은 `local` 프로파일 컨테이너 |

등록 전송은 [`Authorization 클라이언트`](../../../../cowork-authorization/pkg/eureka/client.go)와
[`Notification 클라이언트`](../../../../cowork-notification/pkg/eureka/client.go)를 기준으로 대조한다.
주소 주입은 [`environment.sh`](../../../../deploy/prod/lib/environment.sh),
등록 권한은 [`EurekaRegistrationGuard`](../../../../cowork-config/src/main/kotlin/com/cowork/config/security/filter/EurekaRegistrationGuard.kt)를 확인한다.

## 할 일

### 등록 실패 원인 확인

- Authorization과 Notification의 실행 이미지, 실제 Eureka 주소, 계정, instance ID를 수집한다.
- 등록 `POST`와 heartbeat `PUT`의 상태 코드 및 서버 거부 로그를 대조한다. 자격 증명과 요청 헤더는 기록하지 않는다.
- HTTP 실패를 성공으로 처리하는 이전 구현과 현재 전송 구현을 비교하고 현재 버전을 배포한다.
- Notification은 신뢰한 SSH host fingerprint를 확인한 뒤 컨테이너와 등록 로그를 직접 확인한다.
- 인증, URL 경로, 서버 정책, payload 중 확인된 실패 원인을 수정한다.

### 주소와 실행 주체 정리

- Gateway 등록 두 건의 instance ID를 실제 VM과 프로세스에 연결한다.
- 전용 VM 배치를 확정하고 Vault 배포 문서의 광고 주소와 host port를 갱신한다.
- 다른 VM에서 도달 가능한 사설 주소를 광고하도록 적용한다.
- 새 인스턴스의 등록과 호출 경로를 확인한 뒤 이전 프로세스와 불필요한 등록을 정리한다.
- Config 인증 전환 후 각 서비스가 자기 계정으로 등록·갱신하는지 확인한다.

## 검증

- 배포 스크립트와 서비스 설정의 주소·identity 주입을 정적으로 대조한다.
- 운영 Eureka 목록에서 대상 서비스, instance ID, `UP` 상태, 광고 주소를 수동 확인한다.
- 복수 heartbeat 주기에 걸쳐 등록이 유지되고 실제 HTTP 실패가 반복되지 않는지 확인한다.
- Gateway VM에서 등록된 downstream 주소로 연결 가능한지 수동 확인한다.
- 서비스 직접 health 결과와 Eureka 등록 상태를 별도로 기록한다.
- 회귀·통합 테스트를 작성하거나 실행하지 않는다.

## 완료 조건

- Authorization과 Notification의 정상 인스턴스가 Eureka에 등록되어 있다.
- 모든 운영 등록 주소가 호출 주체의 VM에서 도달 가능한 주소로 설정되어 있다.
- Gateway의 이전 프로세스와 불필요한 중복 등록이 제거되어 있다.
- 등록 실패의 실제 HTTP 상태가 성공으로 처리되지 않는다.
- 배포 문서, 실제 실행 VM, Eureka identity가 일치되어 있다.
