# 공개 API 진입점과 TLS 인증서 정합성 복구

- **서비스**: Gateway, Config, Channel, 공개 프록시
- **우선순위**: 🔴 높음
- **현재 상태**: 운영 Config가 제공하는 공개 API 주소는 점검 클라이언트의 TLS 인증서 검증을 통과하지 못한다.

## 문제

2026-10-13 운영 Config의 `PUBLIC_API_BASE_URL`은 `https://ssh.gsmsv.site:22132`였다.
해당 주소는 subject와 issuer가 모두 `CN=ssh.gsmsv.site`인 자체 서명 인증서를 반환했다.
인증서 검증을 유지한 `curl` 요청은 종료 코드 `60`, 인증서 검증 결과 `18`로 실패했다.
HTTP 응답은 받지 못했으며 `curl`의 HTTP 코드 출력은 `000`이었다.

현재 값이 의도한 공개 API 진입점인지, 패널 포트 전달이 어느 VM과 포트로 연결되는지는 아직 확정하지 않았다.
내부 서비스의 health가 성공해도 공개 클라이언트의 TLS 연결 성공을 보장하지 않는다.
클라이언트에서 인증서 검증을 끄는 방식으로 해결하지 않는다.

`PUBLIC_API_BASE_URL`은 [`Config 배포 스크립트`](../../../../deploy/prod/services/config.sh)가 전달하며,
[`Channel 운영 설정`](../../../../cowork-config/src/main/resources/configs/cowork-channel-prod.yml)의
외부 연동 callback 주소에도 사용된다. 공개 주소를 변경할 때 프록시만 수정하면 배포 설정과
외부 연동 주소가 달라질 수 있다.

## 확정할 항목

| 항목 | 확인 기준 |
|------|-----------|
| 대표 공개 주소 | 외부 클라이언트가 사용하는 scheme, hostname, port를 하나의 운영 기준으로 확정한다. |
| 패널 전달 규칙 | 공개 listener, 전달 방식, 대상 Gateway VM, host port를 연결해 기록한다. |
| TLS 종료 위치 | 패널 프록시 또는 Gateway 앞 프록시 중 실제 인증서를 제공하는 위치를 확인한다. |
| 인증서 | hostname을 포함한 SAN, 신뢰 체인, 유효기간, 갱신 경로를 확인한다. |
| 애플리케이션 설정 | `PUBLIC_API_BASE_URL`, 관련 callback 주소, `PUBLIC_WEB_ORIGINS`를 실제 클라이언트 계약과 대조한다. |

## 할 일

- GSM SV 패널의 공개 포트 전달과 실제 Gateway 실행 VM을 대조한다.
- 대표 공개 API 주소와 TLS 종료 위치를 확정한다.
- 일반 클라이언트가 신뢰하는 인증서와 전체 인증서 체인을 적용한다.
- 인증서 갱신과 프록시 재적용 절차를 기록한다.
- Vault의 `secret/deploy/config`와 필요한 앱 설정을 `update-config` 경로로 갱신한다.
- Config와 영향을 받는 서비스를 문서의 배포 순서에 따라 재배포한다.
- 외부 연동에 등록된 callback 주소와 실제 공개 주소를 함께 갱신한다.

## 검증

- 패널 전달 규칙, Vault 배포 문서, Config 값, 애플리케이션 callback 경로를 정적으로 대조한다.
- 외부 클라이언트에서 인증서 검증을 유지한 HTTPS 연결을 수동 확인한다.
- 인증서의 hostname, SAN, 신뢰 체인, 유효기간을 확인한다.
- 공개 health 또는 부작용 없는 조회 경로의 실제 HTTP 응답을 확인한다.
- 운영에 사용하는 WebSocket upgrade와 SSE 전달 조건을 프록시 설정 및 수동 연결 결과로 확인한다.
- 회귀·통합 테스트를 작성하거나 실행하지 않는다.

## 완료 조건

- 대표 공개 API 주소가 실제 Gateway의 공개 진입점과 일치되어 있다.
- 일반 클라이언트의 인증서 검증을 통과하는 TLS 구성이 적용되어 있다.
- Config 설정과 외부 연동 callback 주소가 같은 공개 주소를 사용하도록 정리되어 있다.
- VM 재생성 또는 프록시 재기동 후에도 필요한 전달 규칙과 인증서가 유지되도록 구성되어 있다.
