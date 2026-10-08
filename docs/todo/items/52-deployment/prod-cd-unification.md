# 운영 서비스의 CD 배포 일원화

- **서비스**: 모든 운영 서비스, Vault, GitHub Actions
- **우선순위**: 🔴 높음
- **현재 상태**: 14개 CD target 중 12개에 runtime 성공 기록이 있으며, user·monitoring의 성공 기록과 최신 Config·앱 전환 및 수동 복구 잔여물 정리가 남아 있다.
- **관련 작업**: [Config Server 접근 보호](../08-security/config-server-access-control.md)

## 문제

2026-10-02에는 team·user·notification·voice의 Vault 배포 문서가 없고 수동 이미지로 복구한 상태였다.
이후 team·voice·notification에 `cowork-runtime` 성공 기록이 생겼으므로 당시 상태를 현재 배포 불가로
단정하지 않는다. CD 코드의 14개 target 편입은 이미 완료되어 있다.

CD health 대기 상한과 Vault 재부팅 복구도 `develop` 병합으로 구현 완료 처리했다.
이 항목은 실제 CD 전환과 수동 복구 잔여물·자격 증명 정리를 추적한다.

2026-10-08 조회한 runtime 기록에서 앱 11개의 마지막 성공 SHA는 `231bfea6`, Vault는 `08706cdd`다.
user와 monitoring에는 runtime 성공 기록이 없다. 최신 `08706cdd` 자동 배포는 Config 단계에서 실패해
앱 배포를 건너뛰었다. 이전 버전의 배포 성공과 최신 보안·인가·복구 변경의 운영 적용을 구분한다.

성공 기록은 해당 배포 시점의 readiness 통과를 뜻한다. 현재 실행 상태, 외부 Vault 문서의 최신 값,
수동 컨테이너·자격 증명 파일의 잔존 여부는 별도 운영 자료가 필요하다.

## 배포 근거

- [CD inventory](../../../../deploy/prod/inventory.json): 앱·Config·Gateway·monitoring·Vault의 14개 target이 등록되어 있다.
- [runtime 기록](../../../../deploy/prod/deployment_state.py): `task=cowork-runtime`만 실제 배포 성공으로 판정하며 check-only job은 제외한다.
- [최신 자동 CD](https://github.com/team-cowork/cowork-server/actions/runs/37571972300): Vault 성공, Config 실패, 앱 배포 skipped다.
- [team 배포](https://github.com/team-cowork/cowork-server/actions/runs/37130756211), [voice 배포](https://github.com/team-cowork/cowork-server/actions/runs/37326134539), [notification 배포](https://github.com/team-cowork/cowork-server/actions/runs/37452069393): 기존 404·수동 이미지 상태 이후 runtime 성공 기록이 있다.

아래는 `task=cowork-runtime`의 target별 마지막 성공 기록이다. 자동 생성된 Environment job 성공과
`check_only`는 제외했다. 시각은 한국 표준시다. 기록이 없는 target을 현재 중단이라고 단정하지 않는다.

| target | 마지막 성공 SHA | 성공 기록 시각 (KST) | 실행 기록 |
|---|---|---|---|
| authorization | `231bfea6` | 2026-10-04 01:25 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37136725666) |
| channel | `231bfea6` | 2026-10-05 18:36 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37291017089) |
| chat | `231bfea6` | 2026-10-06 01:01 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37337456074) |
| config | `231bfea6` | 2026-09-29 00:52 | [run](https://github.com/team-cowork/cowork-server/actions/runs/36446755559) |
| gateway | `231bfea6` | 2026-09-29 00:56 | [run](https://github.com/team-cowork/cowork-server/actions/runs/36447254521) |
| notification | `231bfea6` | 2026-10-06 19:51 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37452069393) |
| preference | `231bfea6` | 2026-10-04 03:28 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37143971044) |
| project | `231bfea6` | 2026-10-06 00:35 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37333799641) |
| roadmap | `231bfea6` | 2026-10-04 01:01 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37135216263) |
| team | `231bfea6` | 2026-10-03 23:47 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37130756211) |
| vault | `08706cdd` | 2026-10-07 13:47 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37571972300) |
| voice | `231bfea6` | 2026-10-05 23:52 | [run](https://github.com/team-cowork/cowork-server/actions/runs/37326134539) |
| user | 성공 기록 없음 | — | 환경 job 실패 기록만 확인 |
| monitoring | 성공 기록 없음 | — | 환경 job 실패 기록만 확인 |

## 구현 완료된 배포 보완

- [#450](https://github.com/team-cowork/cowork-server/pull/450)·[#459](https://github.com/team-cowork/cowork-server/pull/459): SSH 실행 제한은 45분이며, 후보·롤백 대기 두 번과 준비 시간 600초를 고려해 `HEALTH_TIMEOUT_SECONDS`를 1~1050초로 제한한다. 실패 후보 로그 보존과 기존 비정상 컨테이너의 롤백 대기 생략도 반영되어 있다. [상한 검증 코드](https://github.com/team-cowork/cowork-server/blob/6436053a/deploy/prod/vault-settings.py)를 확인했다.
- [#458](https://github.com/team-cowork/cowork-server/pull/458): unseal 뒤 Docker health를 최대 120초 확인하고 `vault status` 성공을 요구한다. Vault → Config 설정 조회 `200` → 앱 readiness·Eureka 복구 순서도 [배포 문서](https://github.com/team-cowork/cowork-server/blob/dbc63025/docs/deployment.md)에 반영되어 있다.

두 보완의 배포·운영 검증 대기를 별도 구현 TODO로 남기지 않는다. 기록된 Vault 성공 SHA는 수정 전
버전이므로 실제 복구 때 사용할 스크립트의 SHA는 위 병합 이력과 대조한다.

## FCM 등록 항목 통합

notification의 2026-10-06 runtime 성공 SHA `231bfea6`도 `fcm.credentials-json`을 필수로 읽고
`fcm.NewSender` 실패 시 프로세스를 종료한다. 따라서 자격 증명 부재로 기동하지 못한다는 기존
항목 55의 장애 설명은 해당 배포에서 해소된 것으로 판단한다. 별도 TODO를 제거하고 남은 운영
정리를 이 항목에 통합한다.

이 기록만으로 값의 Vault 출처, 실제 FCM 전송 권한·클라이언트 프로젝트 일치, 사용한 키 파일의
폐기를 입증하지 않는다. 시크릿 원문을 조회하거나 재등록하지 않고 공급 경로와 잔여 참조를 확인한다.

## 할 일

### 최신 버전 전환

- user·monitoring의 Vault 배포 문서와 마지막 실패 원인을 확인하고 CD 배포를 완료한다.
- Config 접근 보호·AppRole 전환을 완료한 뒤 최신 호환 SHA로 각 앱을 배포한다.
- SHA별 보안·정책·migration 전환 순서를 지키고 실제 runtime 성공 기록과 VM의 이미지 SHA를 맞춘다.
- `log-agent`는 문서의 VM별 수동 target 방식을 유지할지 inventory에 넣을지 결정한다.

### 수동 복구 잔여물 정리

- 현재 VM에서 확인한 `*-old`·수동 `:local`·`verify-test` 이미지 컨테이너를 CD 전환 후 제거한다.
- team의 실행 VM과 접속 주소를 확인하고 수동 Docker 네트워크 연결이 더 이상 필요하지 않으면 제거한다.
- `~/pref.env`, `~/auth.env`, `~/noti.env`, `~/user.env`와 작업용 Firebase 키 파일의 잔존 여부를 확인하고 불필요한 사본을 제거한다.
- notification의 자격 증명 공급 경로·Firebase 프로젝트 일치·실제 수신을 확인하고 기존 키 파일 참조를 정리한다.

## 검증

- 14개 target의 `cowork-runtime` 성공 SHA와 실제 배포 이미지를 대조한다.
- check-only·Environment job 성공을 실제 rollout으로 계산하지 않는다.
- 서비스 readiness·Eureka 등록·외부 연동을 확인하고 성공 이후의 현재 가용성은 별도로 관측한다.
- 운영 자격 증명의 값과 설정 응답 본문을 로그·문서에 남기지 않는다.

## 완료 조건

- 모든 target이 승인한 최신 버전으로 CD 배포되어 있다.
- 수동 복구용 컨테이너·네트워크·불필요한 자격 증명 사본과 참조가 정리되어 있다.
- 실제 VM의 배포 버전과 `cowork-runtime` 성공 기록이 일치한다.

## 점검 근거 (2026-10-08)

- 관련 PR: [#363](https://github.com/team-cowork/cowork-server/pull/363) · [#366](https://github.com/team-cowork/cowork-server/pull/366) · [#376](https://github.com/team-cowork/cowork-server/pull/376).
- [대조 코드](../../../../deploy/prod/inventory.json): 14개 target 중 12개에 runtime 성공 기록이 있다. user·monitoring은 없고 최신 Config 실패로 앱 전환이 막혀 있다.
- 판정: **운영 전환**. 최신 버전 전환·수동 복구 잔여물·FCM 자격 증명 정리를 남은 범위로 유지한다.
