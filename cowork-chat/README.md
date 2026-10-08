# cowork-chat

## 역할

실시간 채팅과 첨부파일 공유·메시지 검색을 제공합니다.

- 채널 메시지·답글·고정·이모지 반응과 타이핑 알림
- 첨부파일 업로드, DM 목록·숨기기, 사용자 차단과 미읽 카운트
- Elasticsearch 전문 검색과 GraphQL 통합 검색
- GitHub 이슈 생성 슬래시 커맨드와 사용자 알림 발행

## 스택

- TypeScript / Node.js / NestJS
- npm / TypeScript Compiler
- Socket.IO / GraphQL (Apollo)
- MongoDB (Mongoose) / Elasticsearch / Redis / KafkaJS
- SeaweedFS (S3 호환) / AWS SDK

## 린트

모듈 디렉터리에서 실행합니다. `XO` 버전은 잠금 파일과 함께 고정하며, 에디터의 ESLint 확장은 `eslint.config.mjs`를 통해 같은 `xo.config.mjs` 설정을 사용합니다.

```sh
npm ci
npm run lint -- --max-warnings=0
npm run lint:fix
npm run build
```

JS/TS 소스와 린터 설정을 검사하며, HTML·CSS·Markdown·JSON은 대상에서 제외합니다. 들여쓰기는 `.editorconfig`와 같은 공백 4칸입니다. NestJS의 CommonJS 빌드, 데코레이터와 MongoDB의 `null` 계약을 유지합니다. 타입 기반 검사는 `tsconfig.json`을 사용합니다.

프로젝트별 예외와 이유는 `xo.config.mjs`에 기록합니다. 자동 수정 후에는 diff와 빌드를 확인해야 합니다. Stage/Prod CI의 Node 작업은 빌드 전에 린트를 실행하며 경고도 실패로 처리합니다.

## 포트

| 용도             | 컨테이너 포트 | Compose 기본 호스트 포트 |
|------------------|---------------|--------------------------|
| HTTP / WebSocket | `8087`        | `8087`                   |

운영 WebSocket 연결은 Gateway의 `/ws/chat`을 사용합니다. 서비스 직접 연결은 운영에 노출하지 않습니다.

## 환경변수

아래 값은 [Docker Compose](../docker-compose.yml) 기준입니다.

| 변수                                | 기본값                      | 설명                                                                   |
|-------------------------------------|-----------------------------|------------------------------------------------------------------------|
| `APP_CONFIG_URL`                    | `http://cowork-config:8761` | 필수 Config Server 연결                                                |
| `APP_PROFILE`                       | `local`                     | 설정 프로파일. Compose의 `SPRING_PROFILES_ACTIVE` 값 사용              |
| `CHAT_PROJECTION_SOURCE_GENERATION` | `1` (앱 기본값)             | Kafka source 교체를 구분하는 운영 세대 값. 필요한 경우 명시적으로 주입 |

- Config Server: 포트, MongoDB 옵션, Elasticsearch, Kafka, Redis, Eureka, S3 endpoint·정책, rate limit.
- Vault: `MONGODB_URI`, `JWT_SECRET`, Discord webhook, S3 access·secret key.

Compose 기동 시 Config Server 조회가 필수입니다. 일반 설정은 [서비스별 설정 파일](../cowork-config/src/main/resources/configs/), 시크릿 공급은 [설정 변경 절차](../docs/deployment.md#설정-변경과-재배포)를 참고합니다.

## 메시지 범위와 기존 데이터 점검

메시지의 `teamId`·`projectId`는 채널 projection에서 결정하며 요청에서 받지 않습니다.
부모 메시지는 같은 채널에 존재해야 합니다. 답장의 답장을 허용하고 답장도 unread에 포함합니다.
`parentMessageId` 조회는 직계 답장을 반환하며, 그 응답의 `mentionedMessage`는 채우지 않습니다.
이 계약과 읽기 전용 감사 도구는 [#412](https://github.com/team-cowork/cowork-server/pull/412)·
[#448](https://github.com/team-cowork/cowork-server/pull/448)에 반영되어 있습니다.

기존 데이터를 유지·정정하기로 한 환경에서는 다음 절차를 사용합니다. 데이터 유지·복구·이관 여부는
[배포 운영 기준](../docs/deployment.md)에 따라 정합니다.

1. 채널 범위 확정과 읽기 경로의 부모 채널 제한이 적용된 버전인지 확인한다.
2. `cowork-chat`에서 `MONGODB_URI`를 설정하고 `npm run ops:message-scope-audit`를 실행한다.
   JSON Lines 보고서와 마지막 범주별 건수를 저장소 밖의 접근 제한된 위치에 보관한다.
3. `CHANNEL_MISSING_OR_DELETED`·`CHANNEL_SCOPE_INVALID`는 채널 삭제·projection 복구 상태를
   먼저 확인하며 자동 정리 대상에 넣지 않는다.
4. `MESSAGE_SCOPE_MISMATCH`는 활성 채널의 `teamId`·`projectId` 정정을,
   `PARENT_INVALID_ID`·`PARENT_MISSING`·`PARENT_CROSS_CHANNEL`은 부모 참조의 `null` 해제를 검토한다.
   메시지 ID와 이전·새 값을 포함한 적용 목록을 승인받는다.
5. 승인한 목록만 `_id`·`channelId`·감사 당시 필드 값으로 조건부 갱신한다.
   조건이 바뀐 문서는 건너뛰고 다시 감사한다. 원본과 적용 결과를 복구 가능한 운영 기록으로 보관한다.
6. 정정된 메시지가 색인 대상이면 `npm run ops:message-search-index -- rebuild`를 실행한다.
   완료 뒤 감사 명령과 색인 `status`를 다시 확인한다.

감사는 읽기 전용이다. 실제 데이터 변경과 재색인은 보고서 검토·승인 후 별도 운영 작업으로 수행한다.
