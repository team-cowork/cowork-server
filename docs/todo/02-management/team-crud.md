# 팀 정보 수정·삭제 API

- **서비스**: cowork-team 또는 cowork-channel
- **우선순위**: 🟠

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `PATCH` | `/teams/{teamId}` | 팀 이름·설명 수정 |
| `POST` | `/teams/{teamId}/icon` | 팀 아이콘 이미지 교체 (multipart) |
| `DELETE` | `/teams/{teamId}` | 팀 삭제 (Owner만) |

## 응답

수정된 `TeamResponse` 반환.
