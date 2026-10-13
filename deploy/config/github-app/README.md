# GitHub App webhook receiver

이 디렉터리는 독립 GitHub App VM의 webhook 전용 Nginx 설정을 관리한다.
GitHub App 이미지 빌드와 실행은 이 설정의 범위에 포함하지 않는다.

## 네트워크 경로

```text
GitHub HTTPS 요청
  → GSM TLS proxy 10.0.0.117
  → GitHub App VM 10.0.0.150:8080 (Nginx)
  → 10.0.0.150:3000 (GitHub App)
```

- Nginx는 사설 IPv4 `10.0.0.150:8080`에만 listen한다.
- TCP peer가 `10.0.0.117/32`인 요청만 upstream에 접근할 수 있다.
  `X-Forwarded-For`를 접근 허용 판정에 사용하지 않는다.
- query string이 없는 정확한 `POST /github/webhooks`만 전달한다.
  다른 method, 경로 인코딩, 후행 slash, `/api/*`, `/github/setup`, `/health`는 전달하지 않는다.
- 현재 호스트 방화벽은 `3000/tcp`와 `8080/tcp`에 공통 peer 정책을 사용하며,
  Project `10.0.0.88`과 TLS proxy `10.0.0.117` 외의 원격 접근을 차단한다.
  `8080/tcp`에서는 Nginx의 추가 ACL이 TLS proxy만 upstream으로 통과시킨다.
- `3000/tcp`는 Project의 내부 HTTP API용이며 공개 HTTPS 대상으로 등록하지 않는다.
  TLS proxy의 공개 연결 대상은 Nginx `8080/tcp`로 제한한다.
- proxy는 본문을 재작성하지 않는다. GitHub 서명 헤더와 원문 본문을 App에 전달한다.
  접근 로그에는 본문, query string, 인증 헤더를 기록하지 않는다.
- proxy가 upstream 요청을 자동으로 재시도하지 않도록 설정한다.

## 설치와 갱신

운영자는 먼저 기존 Nginx 패키지, 활성 사이트, listen 포트를 확인한다.
신규 VM에는 배포판의 공식 Ubuntu 패키지 저장소에서 Nginx를 설치한다.
패키지 설치 중에는 Nginx의 자동 시작을 막고, 새로 설치된 기본 사이트를 비활성화한 뒤
이 설정을 적용한다. 기존 사이트가 있는 VM에서는 해당 사이트를 삭제하거나 덮어쓰지 않는다.

`webhook-nginx.conf`를 root 소유 `0644` 파일로
`/etc/nginx/sites-available/cowork-github-webhook.conf`에 설치하고,
동일한 이름으로 `sites-enabled` symlink를 만든다.
`sudo nginx -t`가 성공한 뒤 서비스를 시작하거나 reload한다.
최종 listen 주소와 설정 파일 checksum을 기록한다.

이 파일에는 secret이 없다. GitHub App의 `GITHUB_WEBHOOK_SECRET`은 별도의 private runtime
환경 파일에서 공급하며, GitHub에 저장한 secret과 일치해야 한다.
GitHub의 webhook 활성화와 HTTPS 주소 등록은 receiver 설치 후 별도로 수행한다.

## App 소스에서 확인한 인증 범위

아래 내용은 `cowork-github-app` main 커밋
`16e11b07df1795a549fd61744e23ed5c0093c335`을 기준으로 한다.

| 경로 | App의 처리 |
| --- | --- |
| `POST /github/webhooks` | `rawBody: true`로 보존한 본문에 HMAC SHA-256을 계산한다. 서명 길이를 확인한 뒤 `timingSafeEqual`로 비교한다. 누락되거나 잘못된 서명은 거부한다. |
| `GET /health` | 인증 없이 고정 상태를 반환한다. Kafka와 Redis의 현재 연결 상태를 확인하지 않는다. |
| `/api/*` | 조회 경로와 기존 PR merge/approve HTTP 경로에 내부 API 키 guard가 적용된다. 공개 receiver는 모두 차단한다. |
| `GET /github/setup` | HTTP guard 없이 installation을 조회하고 연결 이벤트를 발행한다. 공개 receiver는 차단한다. |

Setup 이벤트의 Team 소비자는 서명된 state와 5분 만료를 검증한다.
그러나 App의 Redis revision 증가와 Kafka 발행은 이 검증보다 먼저 실행된다.
Team의 revision 원장도 state 검증보다 먼저 갱신된다.
설치 연결 callback을 공개하기 전에는 이 처리 순서와 state의 일회성 사용,
installation과의 결합을 별도로 보완해야 한다.
이 receiver만으로 새로운 GitHub App 설치와 cowork 팀의 연결 절차가 완성되지는 않는다.

Webhook handler에는 delivery ID를 사용한 중복 제거가 없다.
GitHub가 유효한 요청을 재전달하면 Kafka 이벤트가 다시 발행될 수 있다.
Nginx의 본문 상한은 GitHub의 최대 payload 크기를 수용하도록 `25m`으로 둔다.
현재 App의 `main.ts`는 `rawBody: true`만 지정하며 parser 크기를 변경하지 않는다.
따라서 Nest Express의 기본 JSON 상한인 `100kb`가 적용된다.
이보다 큰 요청은 HMAC 검증과 handler 실행 전에 거부될 수 있다.
큰 webhook payload를 지원하려면 App에서 원문 보존을 유지한 채 JSON parser 상한을 별도로 수정해야 한다.
receiver 설정만으로 이 App 제한은 해소되지 않는다.

## 이벤트 구독과 권한

2026-10-13에 사용자 승인으로 Webhook URL과 `issues`·`pull_request`·`push` 구독을 적용했다.
URL은 `https://cowork-github-app.https.gsmsv.site/github/webhooks`이고 SSL 검증은 활성화했다.
기존 PR 병합·head branch 삭제 구현에 필요한 `Contents: write`를 추가했다. 현재 소스에서 사용하지 않는
`repository_projects` 권한은 제거했다. receiver 파일 설치 자체가 GitHub 설정을 바꾸지는 않는다.

| 소스가 처리하는 이벤트 | 필요한 설정 | 확인한 상태 |
| --- | --- | --- |
| `installation` | GitHub App에 기본 전달된다. 수동 구독 대상이 아니다. | handler는 `deleted`만 연결 해제 이벤트로 처리한다. Webhook 활성화는 적용했다. |
| `issues` | Issues 읽기 이상의 권한과 이벤트 구독 | 현재 `issues: write`로 권한은 충족한다. App 등록과 `team-cowork` 설치의 구독을 확인했다. |
| `pull_request` | Pull requests 읽기 이상의 권한과 이벤트 구독 | 현재 `pull_requests: write`로 권한은 충족한다. App 등록과 `team-cowork` 설치의 구독을 확인했다. |
| `push` | Contents 읽기 이상의 권한과 이벤트 구독 | `contents: write`와 구독을 App 등록 및 `team-cowork` 설치에서 확인했다. |

`push`, `issues`, `pull_request`는 `github.repo.event`로 발행한다.
`installation.deleted`는 `team.github.disconnected`로 발행한다.
다른 이벤트는 처리하지 않는다. receiver 구성이 완료되어도 구독과 권한이 없는 설치의 이벤트는 수신하지 않는다.

`NexTach`, `Be-Mentoring`, `Idea-Festival-project` 설치는 현재 로그인 계정으로 관리 화면에 접근할 수 없었다.
조회한 이 세 설치에는 Contents 권한과 이벤트 구독이 아직 반영되지 않았다. 각 조직 관리자의 승인이 필요하다.

활성화 직후 서명 헤더가 없던 요청은 HTTP `401`로 거부됐다. 이 과거 기록만으로 현재 Secret 저장
상태를 판단하지 않는다. 2026-10-13 20:24:51과 20:24:53 KST에 GitHub가 보낸 `push`와
`pull_request.synchronize`는 `X-Hub-Signature-256`을 포함했고 모두 HTTP `200`을 받았다.
실행 중 App과 private runtime 파일의 Secret이 준비한 값과 일치함을 값 노출 없이 확인했다.
이는 현재 서명 검증과 webhook 수신의 성공을 확인한 결과다. 이 요청들의 업무 서비스 Kafka 소비와
전체 업무 처리 완료까지 검증한 결과는 아니다.

참고: [GitHub 이벤트와 권한](https://docs.github.com/en/webhooks/webhook-events-and-payloads),
[Nginx 주소 접근 제어](https://nginx.org/en/docs/http/ngx_http_access_module.html),
[Nginx proxy 처리](https://nginx.org/en/docs/http/ngx_http_proxy_module.html),
[Nest 원문 본문과 parser 상한](https://docs.nestjs.com/faq/raw-body#body-parser-size-limit).
