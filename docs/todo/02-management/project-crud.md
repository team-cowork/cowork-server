# 프로젝트 수정·삭제·보관 API

- **서비스**: cowork-channel 또는 cowork-project
- **우선순위**: 🟠

## 필요 API

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `PATCH` | `/projects/{projectId}` | 이름·설명 수정 |
| `PATCH` | `/projects/{projectId}/status` | 보관(ARCHIVED) / 활성(ACTIVE) 전환 |
| `DELETE` | `/projects/{projectId}` | 프로젝트 삭제 (Admin+) |
