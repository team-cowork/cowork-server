# GitHub App 이슈·댓글·라벨 외부 계약 정립

- **서비스**: cowork-project, 외부 cowork-github-app
- **우선순위**: 🟠 중간
- **현재 상태**: `GithubAppClient`의 13개 호출 중 9개는 외부 github-app에 대응 route가 없어 404가 된다

## 문제

`cowork-project`의 `GithubAppClient`는 외부 [cowork-github-app](https://github.com/team-cowork/cowork-github-app)을 Feign으로 호출한다. 외부 [`c5bfbb2`](https://github.com/team-cowork/cowork-github-app/commit/c5bfbb2f89250ca73efbd2555851c97d4a6de17d)와 대조한 결과, route가 있는 호출은 조직 저장소 목록과 PR 목록·상세·파일 목록 4개뿐이었다.

이슈 목록·상세, 라벨 목록·교체, 이슈 댓글 목록·생성·조회·수정·삭제의 9개 호출은 대응 HTTP route도 Kafka command/result·멱등 계약도 없다. 이 상태는 해당 commit 기준이며 이후 외부 배포 상태는 아직 확인하지 않았다.

댓글·라벨 쓰기는 다른 서비스 상태 변경이므로 Kafka command로 전환할 대상이다. 영구적인 HTTP 예외로 인정한 상태가 아니라 외부 계약이 없어 보류된 갭이다. 이 저장소의 producer만 먼저 바꾸면 처리할 consumer가 없는 command를 성공으로 접수하게 되므로 외부 github-app과 함께 변경해야 한다.

## 호출별 전환 방향

| 호출 | 방향 |
|---|---|
| 이슈 목록·상세, 라벨 목록, 댓글 목록·조회 | 완전한 versioned event feed가 없는 GitHub 원본 조회이므로 request-scoped HTTP로 둔다. 외부 route를 구현한다 |
| 라벨 교체, 댓글 생성·수정·삭제 | Kafka command/result와 멱등 key 계약을 정의하고 외부 consumer를 구현한다 |

## 할 일

### 외부 계약

- 외부 github-app의 현재 `main`과 배포본에서 9개 호출의 구현 여부를 다시 확인한다.
- 조회 호출의 HTTP route와 응답 형식을 외부 github-app에 구현한다.
- 쓰기 호출의 command·result topic, 멱등 key, 실패 결과 형식을 정의한다.

### cowork-project

- 댓글·라벨 쓰기 service를 command 발행과 결과 반영으로 전환한다.
- 외부 consumer 배포 이후에만 producer 전환을 배포한다.
- `GithubAppClient`의 KDoc에서 해소된 404 경고를 정리한다.

## 검증

- 댓글·라벨 command 결과 반영 로직의 단위 테스트를 작성한다.

## 완료 조건

- `GithubAppClient`에 외부 route가 없는 호출이 남아 있지 않다.
- 댓글·라벨 쓰기는 동기 HTTP를 사용하지 않는다.
