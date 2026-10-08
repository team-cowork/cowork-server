# Config Server 접근 보호

- **서비스**: cowork-config, 인프라, 모든 Config/Eureka Client
- **우선순위**: 🔴 높음
- **현재 상태**: 서비스별 인증·인가·사설 IPv4 HTTP·방화벽·Vault AppRole 구현은 완료했으며, 운영 계정·자격 증명·네트워크 설정 전환이 남아 있다.

## 문제

현재 Config Server는 TLS를 제공하지 않는다. RFC1918 사설 IPv4 주소의 HTTP, 서비스별 Basic 인증,
허용 CIDR 방화벽을 사용하는 계약으로 바뀌었다. 이전 TODO의 Config 인증서 발급·HTTPS 필수 조건은
현재 구현과 맞지 않는다. Config가 접속하는 Vault의 HTTPS는 별도로 유지한다.

GitHub의 마지막 Config `cowork-runtime` 성공 기록은 `231bfea6`이며, 최신 `08706cdd` 배포는
Config 단계에서 실패했다. 인증·AppRole 코드가 병합된 사실만으로 운영 보호 적용을 판정할 수 없다.
절차는 [Config Server 접근 보호 운영](../../../config-server-access.md)을 따른다.

## 코드 근거

- [Config 보안 설정](../../../../cowork-config/src/main/kotlin/com/cowork/config/security/config/ControlPlaneSecurityConfig.kt): 서비스별 Basic 인증과 endpoint 제한을 적용한다.
- [배포 URL 검증](../../../../deploy/prod/config-access.py): Config/Eureka 주소를 RFC1918 IPv4 리터럴 HTTP로 제한한다.
- [Config backend](../../../../cowork-config/src/main/resources/application.yml): 운영 Vault는 HTTPS·AppRole, 설정 파일은 native를 사용한다.
- [방화벽](../../../../deploy/prod/lib/config-firewall.sh): 허용 peer만 Config 포트에 접근하도록 Docker 방화벽을 구성한다.

## 할 일

- 서비스별 계정과 Config AppRole 자격 증명을 준비하고 Vault의 서비스별 속성·배포 참조를 맞춘다.
- 최신 Config 배포 실패 원인을 확인하고, 사설 HTTP를 허용하는 클라이언트 11개와 Config의 배포 순서를 맞춘다.
- Config/Eureka 주소·사설 바인딩·클라우드 보안 그룹·Docker 방화벽의 허용 peer를 일치시킨다.
- Config → 앱·monitoring 순으로 전환하고 계정 교체·복구 자료와 감사 로그 알림을 정리한다.

## 검증

- 자기 설정 조회·Eureka 등록·heartbeat·metrics 수집과 다른 서비스·프로파일 접근 거부를 확인한다.
- 공개망과 비허용 peer의 연결 차단, Docker 재시작 후 방화벽 재적용을 확인한다.
- Vault HTTPS 검증, 실행 중 Config의 AppRole 갱신·재로그인 결과를 비밀 값 없이 기록한다.

## 완료 조건

- 모든 운영 Config/Eureka Client가 서비스별 계정과 허용된 사설 HTTP 주소를 사용한다.
- 권한 밖 설정 조회와 비허용 네트워크 접근이 차단되어 있다.
- Config의 AppRole 전환과 서비스별 배포·복구 결과가 기록되어 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#416](https://github.com/team-cowork/cowork-server/pull/416) · [#451](https://github.com/team-cowork/cowork-server/pull/451) · [#452](https://github.com/team-cowork/cowork-server/pull/452) · [#453](https://github.com/team-cowork/cowork-server/pull/453).
- [대조 코드](../../../../cowork-config/src/main/resources/application.yml): Config의 TLS 요구는 제거되었고 사설 HTTP·Basic 인증·AppRole 코드가 있다. 마지막 Config runtime 성공 SHA는 전환 전 231bfea6다.
- 판정: **운영 전환**. 계정·AppRole·서비스별 시크릿과 최신 Config·클라이언트의 운영 전환을 남은 범위로 유지한다.
