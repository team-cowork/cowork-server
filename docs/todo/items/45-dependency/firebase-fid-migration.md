# Firebase 최신 SDK 적용을 위한 FCM 식별자 FID 전환

- **서비스**: cowork-notification, iOS·Android·Web 클라이언트
- **우선순위**: 🟠 중간
- **현재 상태**: 서버는 FID 전용 계약과 Firebase Admin Go `4.22.0`으로 전환했다. 클라이언트 전환과 실제 기기 확인은 남아 있다.
- **관련 작업**: [FCM device token 단일 계정 소유권 보장](../17-security/fcm-token-single-owner.md)
- **결론**: iOS·Android·Web 클라이언트의 FID 등록 전환, 배포, 수동 확인이 남아 있다.

> **2026-10-12 진척:** 서버의 등록·저장·전송·재시도를 FID 기준으로 바꿨다.
> `POST /notifications/installations`·`DELETE /notifications/installations/{fid}`가 token API를 대체하고,
> `V10__replace_device_token_with_fid_installation.sql`이 `tb_device_token`과 기존 재시도 원장을 이관 없이 제거한 뒤
> `tb_device_installation`과 `installation_id`·`fid` 기준 원장을 만든다. 전송은 `Message.Fid`만 사용하며
> SDK 고정 주석을 제거했다. 등록 시 FID 형식(생성된 22자 또는 Android의 레거시 IID 이관 11자)이 아닌 값은 `400`으로 거부한다.
> 상황별 API와 배포 순서는 [`cowork-notification/README.md`](../../../../cowork-notification/README.md)에 기록했다.
> 클라이언트 SDK의 FID 획득 조건과 실제 기기 수신은 아직 검증하지 않았다.

## 문제

서버의 등록·저장·전송과 선택적 재시도는 registration token을 기준으로 한다.
FID는 기존 token의 필드명 변경이나 값 복사로 도입하지 않고 클라이언트의 실제 식별자 등록부터
바꾼다. 플랫폼별 전송 전제 조건과 클라이언트 저장소는 아직 점검하지 않았다.

[공식 릴리스 노트](https://firebase.google.com/support/release-notes/admin/go)는 4.21.0에서 `Token`·`Tokens`를 deprecated 처리하고 `Fid`·`Fids`를 추가했으며, 두 대상의 공존도 지원한다고 설명한다. SDK 갱신 자체가 기존 token 전송의 즉시 제거를 요구하는 것은 아니다. 이 항목의 FID 전용 일괄 전환은 PR #389에서 유지한 프로젝트 결정이다. 적용 시점의 SDK·클라이언트 지원을 다시 확인한다.

## 전환 결정

FID 전용 계약으로 일괄 전환한다. token API 하위 호환·병행 전송·fallback은 제공하지 않는다.
기존 등록과 연결된 전송 상태의 보존·이관은 요구하지 않으며 클라이언트가 실제 FID를 새로 등록한다.
신규 설치에도 단일 계정 소유권과 선택적 재시도 정책을 적용한다.

## 코드 근거

- [Firebase SDK](../../../../cowork-notification/go.mod#L6): v4.22.0이며 고정 주석은 없다.
- [전송 식별자](../../../../cowork-notification/internal/infra/fcm/sender.go): `SendEach`에 `Message.Fid`만 전달한다.
- [등록 모델](../../../../cowork-notification/internal/domain/installation/model.go): `tb_device_installation`의 FID 설치 모델이다.

## 할 일

- iOS·Android·Web의 FID 획득·전송 전제 조건과 필요한 클라이언트 버전을 확인한다.
- ~~최초 등록·재설치·식별자 변경·계정 전환·해제의 FID API를 정한다.~~
- ~~후속 migration으로 설치 스키마와 재시도 참조를 바꾸고 기존 token 등록·연결 상태를 정리한다.~~
- ~~전송 대상·배치 결과를 신규 설치 ID와 연결하고 무효 식별자·계정 이전의 대기 전송을 처리한다.~~
- ~~호환 SDK를 적용하고 token 경로·임시 버전 고정·deprecated 검사 제외를 제거한다.~~
- ~~서버·클라이언트 일괄 배포와 신규 FID 등록 순서를 기록한다.~~
- 클라이언트가 FID를 등록하도록 전환하고 서버와 함께 배포한다.

## 검증

- 수신자·mute·설치 소유권 판단만 핵심 비즈니스 단위 테스트로 확인한다.
- 공식 SDK의 배치 한도·응답 순서·오류 계약과 schema·쿼리를 대조하고 빌드·정적 검사를 수행한다.
- 실제 클라이언트의 등록·수신·계정 이전·해제·선택적 재시도는 수동 확인한다.

## 완료 조건

- 실제 FID로 등록·저장·전송하며 token API·컬럼·호환 분기가 남아 있지 않다.
- 신규 설치의 소유권과 전송 상태가 일치하고 기존 token 연결 상태가 정리되어 있다.
- SDK 고정이 해제되고 서버·클라이언트 전환 결과가 기록되어 있다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#389](https://github.com/team-cowork/cowork-server/pull/389) · [#447](https://github.com/team-cowork/cowork-server/pull/447).
- [대조 코드](../../../../cowork-notification/internal/infra/fcm/sender.go): SDK는 4.20.0이며 SendEach에 Message.Token을 전달한다. FID 전용 API·schema·전송 경로는 없다. SDK의 deprecated와 token 즉시 제거는 구분한다.
- 판정: **미구현**. 프로젝트가 정한 FID 전용 계약과 클라이언트·서버 동시 전환을 남은 범위로 유지한다.
