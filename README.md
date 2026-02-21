# cowork-server

광주소프트웨어마이스터고등학교 협업 관리 플랫폼 **cowork**의 백엔드 서버입니다.
하나의 Spring Boot 애플리케이션에서 인증, 팀, 프로젝트, 채널, 채팅, 알림, 음성 기능을 제공합니다.

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어 / 런타임 | Kotlin 1.9, Java 21 |
| 프레임워크 | Spring Boot 3.5 (Web MVC, Data JPA, Security, WebSocket) |
| 데이터베이스 | MySQL 8.0, Flyway |
| 인증 | DataGSM OAuth2 (PKCE), JWT (jjwt) |
| 파일 저장소 | Amazon S3 (로컬: MinIO) |
| 실시간 통신 | STOMP over WebSocket, SSE |
| 푸시 알림 | Firebase Cloud Messaging |
| 음성 | LiveKit |
| 인프라 | Docker, AWS EC2 / ECR / RDS / S3, Nginx, GitHub Actions |

## 패키지 구조

```
src/main/kotlin/com/cowork
├── global        공통 설정, 보안(JWT, STOMP 인증), 예외 처리, 스토리지, 암호화
├── auth          DataGSM 로그인, 토큰 발급·갱신·만료
├── user          사용자 프로필, 검색, 프로필 이미지
├── team          팀, 팀 멤버, 커스텀 역할과 권한
├── project       프로젝트, 프로젝트 멤버, 정렬
├── channel       채널, 채널 멤버, 웹훅, 스레드
│   ├── account       계정 공유 채널 (자격 증명 암호화, OAuth 연결)
│   └── meetingnote   회의록 채널 (템플릿, 섹션, 회의록)
├── chat          메시지, 첨부파일, 실시간 전송, 검색
├── preference    계정·팀·프로젝트·채널 설정, 채널 알림 설정
├── notification  FCM 푸시, SSE 알림 스트림
└── voice         음성 세션, LiveKit 토큰과 웹훅
```

도메인 간 후처리(팀 삭제 시 하위 리소스 정리, 알림 발송 등)는 Spring 애플리케이션 이벤트로 연결합니다.

## 로컬 실행

1. 인프라 컨테이너를 실행합니다.

   ```bash
   cp .env.example .env
   docker compose up -d
   ```

   MySQL(3306), MinIO(9000, 콘솔 9001), LiveKit(7880)이 실행됩니다.

2. 애플리케이션을 실행합니다.

   ```bash
   ./gradlew bootRun
   ```

3. API 문서는 `http://localhost:8080/swagger-ui.html`에서 확인합니다.

## 테스트

```bash
./gradlew test
```

핵심 비즈니스 로직은 JUnit 5와 MockK로 단위 테스트합니다.

## 실시간 통신

| 용도 | 경로 |
|---|---|
| STOMP 엔드포인트 | `/ws` (CONNECT 헤더에 `Authorization: Bearer {token}`) |
| 채널 메시지 구독 | `/topic/channels/{channelId}` |
| 음성 참여자 변경 구독 | `/topic/channels/{channelId}/voice` |
| 사용자 상태 구독 | `/topic/users/{userId}/status` |
| 메시지 전송 | `/app/channels/{channelId}/messages` |
| 알림 스트림 (SSE) | `GET /notifications/stream?access_token={token}` |

## 배포

`main` 브랜치에 병합하면 GitHub Actions가 이미지를 빌드해 Amazon ECR에 올리고, EC2에서 `deploy/scripts/deploy.sh`로 교체 배포합니다.
운영 환경 변수는 `deploy/.env.example`을 참고합니다.

## 브랜치 전략

- `main`: 운영 배포 브랜치
- `develop`: 개발 통합 브랜치
- `add/*`, `update/*`, `fix/*`, `refactor/*`, `docs/*`, `test/*`, `cicd/*`: 작업 브랜치 (`develop`에서 분기하여 병합)
