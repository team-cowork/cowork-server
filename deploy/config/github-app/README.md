# GitHub App 운영 경계

이 저장소는 [webhook Nginx 설정](./webhook-nginx.conf)만 관리한다. App 빌드와 배포는 별도
`cowork-github-app` 저장소의 책임이며 이 저장소의 `prod-cd`에 포함되지 않는다.

공개 주소는 `https://cowork-github-app.https.gsmsv.site/github/webhooks`다.
GSM TLS proxy가 App VM의 Nginx 8080으로 연결하고 App의 3000 포트는 내부 API에 사용한다.
현재 호스트 방화벽은 Project와 TLS proxy에 3000·8080 접근을 허용하므로 공개 전달 대상은
반드시 Nginx 8080이어야 한다. App 3000을 공개 프록시에 직접 연결하지 않는다.

## 외부 App의 미해결 제약

다음은 App 커밋 `16e11b07df1795a549fd61744e23ed5c0093c335`을 검토한 결과다.

- `/github/setup`은 상태 검증 전에 Redis revision과 Kafka 발행을 변경한다. state의 일회성 사용과
  installation 결합도 보완해야 한다. 해당 callback은 공개하지 않으며 신규 설치와 cowork 팀의
  연결 절차는 완성되지 않았다.
- Webhook delivery ID의 중복 제거가 없다. 재전달하면 Kafka 이벤트가 중복 발행될 수 있다.
- Nginx 상한은 25 MiB지만 App JSON parser는 기본 100 KiB다. 큰 요청은 서명 검증 전에 거부될 수
  있다. App에서 원문 보존을 유지한 채 parser 상한을 수정해야 한다.
- `/health`는 고정 응답이므로 Kafka·Redis 연결 준비 상태를 증명하지 않는다.

서명된 GitHub 요청 수신은 확인했지만 해당 이벤트의 업무 서비스 소비와 전체 처리까지 검증하지 않았다.

## GitHub 측 설정

Webhook은 활성화되어 있으며 `issues`·`pull_request`·`push`를 구독한다. App과 `team-cowork`
설치에 PR 병합·브랜치 삭제용 `Contents: write`를 적용했다. Webhook Secret은 App runtime과
GitHub 양쪽에 같은 값이 필요하며 Nginx 설정 배포로 갱신되지 않는다.

`NexTach`, `Be-Mentoring`, `Idea-Festival-project` 설치에는 새 권한과 구독이 반영되지 않았다.
현재 관리 계정은 이 설치들의 설정에 접근할 수 없으므로 각 조직 관리자의 승인이 필요하다.
