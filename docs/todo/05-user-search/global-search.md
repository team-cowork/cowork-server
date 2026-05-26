# 전체 검색 API

- **서비스**: 신규 또는 cowork-gateway
- **우선순위**: 🟡

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/search/messages?q={query}&teamId={teamId}` | 메시지 검색 |
| `GET` | `/search/channels?q={query}&teamId={teamId}` | 채널 검색 |
| `GET` | `/users/search?q={query}&teamId={teamId}` | 멤버 검색 (멘션 자동완성 겸용) |
