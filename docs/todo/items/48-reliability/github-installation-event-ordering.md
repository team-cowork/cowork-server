# GitHub App 설치·해제 이벤트 순서 보장

- **서비스**: cowork-team, 외부 cowork-github-app
- **우선순위**: 🟡 낮음
- **현재 상태**: 설치·해제가 서로 다른 topic으로 도착하고 공통 revision이 없어 역순 도착을 복원할 수 없다

## 문제

외부 github-app은 설치를 `team.github.connected`로, 해제를 `team.github.disconnected`로 발행한다. `cowork-team`의 `TeamGithubInstallationConsumer`는 두 topic을 별도 consumer group으로 소비한다. 연결 시에는 state 서명, 다른 팀의 installation 소유 여부, 기존 installation 교체 여부를 확인하고, 해제 시에는 현재 installation ID가 일치할 때만 해제한다.

두 topic 사이에는 순서가 보장되지 않고, payload에 공통 source revision도 없다. 설치 뒤 해제가 일어났는데 해제가 먼저 처리되면 installation ID가 일치하지 않아 무시되고, 뒤늦은 설치가 이미 해제된 installation을 연결한다. 해제 뒤 새 installation으로 재설치했는데 설치가 먼저 처리되면 기존 installation 때문에 새 연결이 거부되고, 이어서 해제가 처리되어 실제로 설치된 팀이 해제 상태로 남는다.

`team.lifecycle`의 version·fence는 team이 수락한 뒤의 downstream 역전만 막는다. 이 ingress 순서 문제는 team consumer만으로 해결할 수 없다.

## 선택지

| 방식 | 내용 |
|---|---|
| installation별 full-state topic | 외부 producer가 installation ID를 key로 하는 단일 compacted topic에 현재 설치 상태와 revision을 발행한다 |
| 공통 revision 추가 | 기존 두 topic을 유지하고 installation별 단조 증가 revision을 두 이벤트에 싣는다 |
| reconciliation | team이 주기적으로 외부 설치 상태를 대조해 어긋난 상태를 교정한다 |

## 할 일

- 외부 github-app과 함께 선택지를 정한다.
- `TeamGithubInstallationConsumer`가 revision이 낮은 이벤트를 무시하도록 변경한다.
- 두 topic의 전환 순서와 기존 consumer group 정리 절차를 정한다.

## 검증

- 역순 도착한 설치·해제 이벤트의 revision 비교 단위 테스트를 작성한다.

## 완료 조건

- 같은 installation의 설치·해제가 어떤 순서로 도착해도 team은 최신 외부 상태로 수렴한다.
