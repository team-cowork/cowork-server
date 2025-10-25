# API 설계 규칙

## URL

- 리소스는 복수형 명사로 씁니다. 예: `/teams`, `/channels/{channelId}/threads`
- 다른 리소스에 속한 목록은 상위 리소스 경로 아래에 둡니다. 예: `/teams/{teamId}/members`
- 동작이 리소스로 표현되지 않을 때만 동사형 하위 경로를 씁니다. 예: `/auth/signout`, `/voice/channels/{channelId}/join`
- 경로는 소문자 케밥 케이스로 씁니다. 예: `/meeting-note-templates`

## 상태 코드

| 상황 | 상태 코드 |
|---|---|
| 조회, 수정 성공 | `200 OK` |
| 생성 성공 | `201 Created` |
| 본문 없는 성공 (삭제 등) | `204 No Content` |
| 요청 값 오류 | `400 Bad Request` |
| 인증 실패 | `401 Unauthorized` |
| 권한 없음 | `403 Forbidden` |
| 리소스 없음 | `404 Not Found` |
| 상태 충돌 (중복 등) | `409 Conflict` |

## 오류 응답

모든 오류는 같은 형식으로 응답합니다.

```json
{
  "status": 403,
  "message": "팀 멤버가 아닙니다.",
  "timestamp": "2025-09-01T12:00:00"
}
```

- 서비스에서는 `BusinessException(HttpStatus, message)`를 던집니다. 예외 하위 클래스는 만들지 않습니다.
- 메시지는 사용자에게 그대로 보여 줄 수 있는 문장으로 씁니다.
- 예상하지 못한 예외는 내부 내용을 숨기고 `500`과 공통 메시지로 응답합니다.

## 목록 조회

- 사용자 검색처럼 전체 개수가 필요한 목록은 `page`, `size`를 쓰는 페이지 방식으로 조회합니다.
- 메시지, 파일, 회의록처럼 계속 늘어나는 목록은 커서 방식으로 조회합니다.
  - 요청: `before`(이 ID보다 오래된 항목), `limit`
  - 응답: 목록과 `nextCursor`. 다음 페이지가 없으면 `nextCursor`는 `null`입니다.
- `limit`은 서버에서 최대값으로 제한합니다.
