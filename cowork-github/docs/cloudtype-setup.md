# Cloudtype 배포 설정 가이드

## 1. 서비스 생성

1. [cloudtype.app](https://cloudtype.app) 로그인 후 **새 서비스** 생성
2. **Docker** 유형 선택
3. GitHub 저장소 연결 (`cowork-github`)
4. 브랜치: `main`
5. Dockerfile 경로: `./Dockerfile` (기본값 유지)

---

## 2. 환경변수 설정

Cloudtype 서비스 > **환경변수** 탭에서 아래 항목을 모두 입력합니다.

| 키 | 예시 값 | 설명 |
|----|---------|------|
| `PORT` | `3000` | 앱 리슨 포트 |
| `KAFKA_BROKERS` | `kafka-host:9092` | Kafka 브로커 주소 (콤마로 여러 개) |
| `KAFKA_GROUP_ID` | `cowork-github-group` | 컨슈머 그룹 ID |
| `GITHUB_APP_ID` | `123456` | GitHub App ID |
| `GITHUB_PRIVATE_KEY` | `LS0tLS1CRUdJTi...` | PEM 키를 **base64 인코딩**한 문자열 |
| `REDIS_HOST` | `redis-host` | Redis 호스트 |
| `REDIS_PORT` | `6379` | Redis 포트 |
| `GITHUB_TOKEN_CACHE_TTL_SECONDS` | `3300` | (선택) |
| `GITHUB_INSTALLATION_CACHE_TTL_SECONDS` | `86400` | (선택) |
| `GITHUB_INSTALLATION_MEMORY_CACHE_MAX_SIZE` | `1000` | (선택) |
| `GITHUB_ISSUE_MAX_RETRIES` | `3` | (선택) |

> **GITHUB_PRIVATE_KEY base64 인코딩 방법**
> ```bash
> base64 -i private-key.pem | tr -d '\n'
> ```

---

## 3. 포트 설정

Cloudtype 서비스 > **네트워크** 탭:

- **컨테이너 포트**: `3000`
- HTTP 라우팅이 필요하다면 도메인 연결 후 포트 `3000` 매핑

---

## 4. Kafka / Redis 연결

Cloudtype에서 Kafka와 Redis를 직접 띄우거나 외부 서비스를 연결합니다.

### 옵션 A — Cloudtype 내부 서비스 (권장)
1. **새 서비스 추가** → Redis (공식 템플릿) 생성
2. 서비스 내부 호스트명을 `REDIS_HOST`에 입력 (예: `redis.내프로젝트.svc`)
3. Kafka도 동일하게 Apache Kafka 템플릿으로 생성 후 `KAFKA_BROKERS`에 내부 주소 입력

### 옵션 B — 외부 서비스
외부 Kafka(Confluent Cloud 등)와 Redis(Upstash 등) 주소를 환경변수에 직접 입력

---

## 5. Deploy Webhook (GitHub Actions 연동)

GitHub Actions에서 배포를 트리거하려면 Cloudtype **Deploy Hook URL**이 필요합니다.

1. Cloudtype 서비스 > **설정** > **Deploy Hook** 복사
2. GitHub 저장소 > **Settings** > **Secrets and variables** > **Actions**
3. `CLOUDTYPE_DEPLOY_HOOK` 이름으로 위 URL 저장

이후 `main` 브랜치 푸시 → CI 통과 → 릴리스 생성 → Cloudtype 자동 배포 순으로 동작합니다.

---

## 6. 배포 흐름 요약

```
git push origin main
  └─ GitHub Actions (cowork-prod-cd.yml)
       ├─ lint / build / test
       ├─ 날짜 기반 태그 생성 및 GitHub Release 발행
       ├─ POST CLOUDTYPE_DEPLOY_HOOK  ← 배포 트리거
       └─ Discord 알림
```

---

## 7. 헬스체크

서비스 기동 확인용 엔드포인트:

```
GET /  →  200 OK
```

Cloudtype 헬스체크 경로: `/`, 포트: `3000`
