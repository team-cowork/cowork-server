# @멘션 지원

- **서비스**: cowork-chat, cowork-user
- **우선순위**: 🟡

## 필요 작업

- 메시지 내 `<@userId>` 파싱 → 알림 트리거 연동
- 멘션 자동완성용 멤버 검색 API 추가

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/users/search?query={q}&teamId={teamId}` | 멘션 자동완성용 멤버 검색 |
