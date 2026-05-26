# 회의록 수정·삭제 및 템플릿 관리

- **서비스**: cowork-channel
- **우선순위**: 🟢 추후 고려

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `PATCH` | `/meeting-notes/{noteId}` | 회의록 수정 |
| `DELETE` | `/meeting-notes/{noteId}` | 회의록 삭제 |
| `POST` | `/meeting-note-templates` | 템플릿 생성 |
| `PATCH` | `/meeting-note-templates/{id}` | 템플릿 수정 |
| `DELETE` | `/meeting-note-templates/{id}` | 템플릿 삭제 |
