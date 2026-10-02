# 오브젝트 스토리지 공개 접근 계약

- **서비스**: cowork-chat, cowork-team, cowork-user, cowork-config, SeaweedFS, 외부 S3 ingress
- **우선순위**: 🔴 높음
- **현재 상태**: 객체별 읽기 권한·공개 ingress·서명·저장 URL 처리 정책을 확정하지 않았다.

## 문제

team icon·user profile·chat attachment는 같은 저장소와 bucket을 공유한다. bucket 전체 공개 읽기는
채팅 첨부까지 노출할 수 있고, 전부 private이면 API가 반환하는 만료 없는 URL을 직접 읽을 수 없다.
객체 종류별 읽기 계약이 필요하다.

team·user는 공개 endpoint로 업로드 URL을 서명하지만 chat은 내부 endpoint client를 사용한다.
외부에서 도달할 주소와 서명 입력이 맞는지 확정하지 않아 주소 설정만으로 문제를 해결할 수 없다.

## 결정할 정책

| 범위 | 결정할 내용 |
|---|---|
| icon·profile | 공개 범위, 영구 URL 또는 만료 URL, 교체 후 캐시 처리 |
| chat attachment | 채널 권한 검사 주체, 탈퇴·삭제 후 접근, proxy 또는 presigned GET |
| 저장 경계 | bucket 분리 또는 prefix 정책, object key 소유권 |
| ingress·signer | 공개 주소 형식, path-style, region, method·Host·raw path·query 보존 |
| CORS | 환경별 origin·method·header와 업로드 `Content-Type`·응답 header |
| 기존 데이터 | 절대 URL 유지·정정·폐기 또는 object key 기반 응답 전환 |

## 코드 근거

- [Chat S3 client](../../../../cowork-chat/src/storage/object-storage.module.ts#L23): 업로드 서명에 사용하는 client는 endpoint·port 설정으로 만든다. 반환용 public base URL과 서명 endpoint는 별개다.
- [Team 공개 signer](../../../../cowork-team/src/main/kotlin/com/cowork/team/global/config/S3PresignerConfig.kt#L24): public endpoint 전용 presigner를 사용한다.
- [User 공개 signer](../../../../cowork-user/lib/cowork_user/storage/object_storage.ex#L31): PUT·GET 서명은 public, HEAD·DELETE는 internal endpoint를 사용한다.

## 할 일

- 객체별 공개·비공개 정책과 bucket·prefix 경계를 정한다.
- 공개 DNS·TLS·업로드 한도와 ingress의 서명 전달 계약을 정한다.
- 세 서비스가 최종 공개 주소로 서명하고 내부 HEAD·DELETE를 별도 사용하는 계약을 맞춘다.
- URL 문자열 치환 대신 signer 설정을 수정하고 누락값의 거부·fallback 정책을 정한다.
- CORS와 저장 URL 처리 방침을 적용하고 운영 전환 순서를 기록한다.

## 검증

- object key 소유권·허용 prefix·파일 크기 같은 보안 판단만 단위 테스트로 확인한다.
- signer 설정은 정적으로 대조하고 실제 PUT·HEAD·GET·DELETE·CORS는 수동 운영 점검한다.
- 비공개 첨부의 권한 회수와 기존 URL 처리 결과를 확인한다.

## 완료 조건

- 객체별 읽기 권한·URL 수명·저장 경계가 확정되어 있고 비공개 객체가 공개되지 않는다.
- 세 서비스의 서명·ingress·CORS가 같은 계약으로 동작한다.
- 기존 URL 또는 object key의 처리 방침과 전환 결과가 기록되어 있다.
