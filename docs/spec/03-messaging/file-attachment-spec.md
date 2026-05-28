# 파일·이미지 첨부 구현 스펙

- **서비스**: cowork-chat (NestJS + MongoDB)
- **작성일**: 2026-05-28

---

## 현황 파악

### 이미 구현된 것

| 기능 | 파일 | 상태 |
|------|------|------|
| presigned URL 발급 | `ChatController.createFileUploadUrl`, `ChatService.createFileUploadUrl` | ✅ 완료 |
| 업로드 확인(confirm) | `ChatController.confirmFileUpload`, `ChatService.confirmFileUpload` | ✅ 완료 |
| 파일 목록 조회 | `ChatController.getFiles`, `ChatService.getFileList` | ✅ 완료 |
| Message 스키마 `attachments` 배열 | `message.schema.ts` — `Attachment` 서브스키마 | ✅ 완료 |
| MinIO `removeObject` | `MinioService.removeObject` | ✅ 완료 |

### 미구현 — 이번 작업 대상

`DELETE /channels/{channelId}/files/{fileId}` 파일 삭제 엔드포인트 및 관련 로직

---

## 설계 결정 사항

| 항목 | 결정 |
|------|------|
| `fileId` 포맷 | GET /files `nextCursor`와 동일한 base64 인코딩 포맷 |
| 삭제 단위 | 파일이 포함된 **메시지 전체** 삭제 |
| MinIO 정리 | 메시지 삭제 시 모든 attachment의 MinIO 오브젝트도 `removeObject` 호출 |
| 권한 | 업로더(authorId) 본인 또는 ADMIN |
| WebSocket 이벤트 | `message:deleted` emit (기존 메시지 삭제와 동일) |
| 채널 범위 | 모든 채널 타입에 적용 (FILE_SHARE 한정 아님) |
| objectKey 추출 방식 | `fileUrl.replace(publicBaseUrl + '/', '')` |

---

## fileId 포맷

GET /files 응답의 각 파일 항목에 `fileId` 필드를 추가한다. 인코딩 구조:

```json
{
  "uploadedAt": "2026-05-28T12:00:00.000Z",
  "messageId": "6847a2f3e1b2c3d4e5f60001",
  "attachmentIndex": 0
}
```

위 JSON을 UTF-8 → base64 인코딩한 문자열. 기존 `encodeFileCursor`와 동일한 로직.

> `attachmentIndex`는 메시지 전체 삭제이므로 삭제 로직에서 실제로 사용되지 않지만,
> 어느 파일을 특정했는지 API 수준에서 명시적으로 전달하기 위해 포함한다.

---

## 구현 범위

### 1. `MessageRepository` — `findFileAttachments` 수정

**목적**: 반환 항목마다 `fileId` 필드 추가

- `FileAttachmentRow` 타입에 `fileId: string` 추가
- 기존 `encodeFileCursor(row)` 로직을 각 아이템에 동일하게 적용하여 `fileId` 생성
  - `encodeFileCursor`를 private static으로 변경하거나 인라인 처리
  - 각 pageRow에 대해 `Buffer.from(JSON.stringify({uploadedAt, messageId, attachmentIndex})).toString('base64')`

### 2. `FileListItemDto` — `fileId` 필드 추가

```typescript
// file-list.dto.ts
export class FileListItemDto {
    @ApiProperty({ description: 'DELETE /files/{fileId}에 사용할 파일 식별자' })
    fileId!: string;
    // ...기존 필드 유지
}
```

### 3. `ChatService.getFileList` — `fileId` 매핑 추가

```typescript
files: items.map((item) => ({
    fileId: item.fileId,   // 추가
    messageId: item.messageId,
    // ...기존 필드
})),
```

### 4. `MinioService` — `extractObjectKey` 메서드 추가

```typescript
extractObjectKey(fileUrl: string): string {
    const prefix = this.config.publicBaseUrl + '/';
    if (!fileUrl.startsWith(prefix)) {
        throw new BadRequestException('유효하지 않은 파일 URL입니다');
    }
    return fileUrl.slice(prefix.length);
}
```

> `publicBaseUrl`은 기존 `MinioConfig`에 이미 있음 (`this.config.publicBaseUrl`).

### 5. `MessageRepository` — `findByIdAndChannelId` 메서드 추가

채널 소속 검증과 메시지 조회를 한 번에 처리:

```typescript
findByIdAndChannelId(messageId: string, channelId: number): Promise<MessageDocument | null> {
    return this.messageModel.findOne({ _id: messageId, channelId });
}
```

### 6. `ChatService.deleteFile` 메서드 추가

```
입력: ctx: ChannelUserRoleContext, fileId: string

1. fileId base64 디코딩 → { messageId, attachmentIndex } 추출
   - 디코딩 실패 또는 messageId가 유효한 ObjectId가 아니면 BadRequestException
2. checkMembership(channelId, userId)
3. message = findByIdAndChannelId(messageId, channelId)
   - 없으면 NotFoundException
4. 권한 확인: message.authorId !== userId && !isAdmin(userRole) → ForbiddenException
5. message.attachments 각 URL에서 objectKey 추출 후 minioService.removeObject 호출
   - 실패해도 메시지 삭제는 진행 (Best-effort, 로그 남김)
6. messageRepository.deleteById(messageId)
7. message.projectId 있으면 elasticsearchService.deleteMessage(messageId)
8. chatGateway.server.to(`chat:${channelId}`).emit('message:deleted', { messageId })
9. return { channelId, messageId }
```

**MinIO 삭제 실패 처리**: `removeObject`가 throw하더라도 메시지 삭제와 WebSocket 이벤트는 계속 진행. 에러는 Logger.warn으로 기록.

### 7. `ChatController` — DELETE 엔드포인트 추가

```typescript
@Delete('files/:fileId')
@ApiOperation({ summary: '채팅 첨부파일 삭제 (메시지 전체 삭제)' })
@ApiResponse({ status: 200, type: DeleteMessageResponseDto })
@ApiResponse({ status: 400, description: '유효하지 않은 fileId' })
@ApiResponse({ status: 403, description: '채널 멤버 아님 또는 권한 없음' })
@ApiResponse({ status: 404, description: '파일(메시지)을 찾을 수 없음' })
async deleteFile(
    @Param('channelId', ParseIntPipe) channelId: number,
    @Param('fileId') fileId: string,
    @UserId() userId: number,
    @UserRole() userRole: string,
): Promise<DeleteMessageResponseDto> {
    return this.chatService.deleteFile({ channelId, userId, userRole }, fileId);
}
```

반환 타입은 기존 `DeleteMessageResponseDto` 재사용 (`{ channelId, messageId }`).

---

## 영향 범위

| 파일 | 변경 종류 |
|------|-----------|
| `src/chat/repository/message.repository.ts` | `FileAttachmentRow`에 `fileId` 추가, `findByIdAndChannelId` 추가, `findFileAttachments`에서 fileId 생성 |
| `src/chat/dto/file-list.dto.ts` | `FileListItemDto`에 `fileId` 추가 |
| `src/storage/minio.service.ts` | `extractObjectKey` 추가 |
| `src/chat/chat.service.ts` | `deleteFile` 추가 |
| `src/chat/chat.controller.ts` | `DELETE files/:fileId` 엔드포인트 추가 |

**변경 없는 파일**: `message.schema.ts`, `send-message.dto.ts`, `create-file-upload-url.dto.ts`, Kafka 관련 파일, WebSocket Gateway

---

## 엣지 케이스

| 케이스 | 처리 |
|--------|------|
| fileId 디코딩 실패 | `BadRequestException` |
| messageId가 유효한 ObjectId가 아님 | `BadRequestException` |
| 채널에 없는 메시지 | `NotFoundException` |
| MinIO 오브젝트가 이미 없음 | 무시하고 계속 진행 (idempotent) |
| MinIO removeObject 실패 | Logger.warn 후 계속 진행 |
| attachment가 없는 메시지에 대한 삭제 요청 | messageId가 있으면 메시지 삭제 진행 (MinIO 삭제 단계만 skip) |

---

## 구현 순서

1. `message.repository.ts` — `FileAttachmentRow`에 `fileId` 추가, `findFileAttachments` 수정, `findByIdAndChannelId` 추가
2. `file-list.dto.ts` — `fileId` 필드 추가
3. `minio.service.ts` — `extractObjectKey` 추가
4. `chat.service.ts` — `deleteFile` 메서드 추가, `getFileList`에서 `fileId` 매핑 추가
5. `chat.controller.ts` — DELETE 엔드포인트 추가
