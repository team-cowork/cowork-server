# WebSocket 이벤트 보강

- **서비스**: cowork-chat, cowork-channel
- **우선순위**: 🟢 추후 고려

## 현재 누락된 이벤트

| 이벤트 | 설명 |
|--------|------|
| `channel:created` | 채널 생성 실시간 반영 |
| `channel:updated` | 채널 정보 변경 실시간 반영 |
| `channel:deleted` | 채널 삭제 실시간 반영 |
| `project:created` | 프로젝트 생성 |
| `project:updated` | 프로젝트 수정 |
| `project:deleted` | 프로젝트 삭제 |
| `member:joined` | 팀/채널 멤버 합류 |
| `member:left` | 팀/채널 멤버 탈퇴 |
| `member:role:updated` | 멤버 역할 변경 |
| `message:pinned` | 메시지 고정 |
| `message:unpinned` | 메시지 고정 해제 |
| `reaction:added` | 이모지 반응 추가 |
| `reaction:removed` | 이모지 반응 제거 |
