/**
 * 채팅 메시지 발행 시 Kafka로 전송되는 이벤트 페이로드.
 *
 * `eventType`과 `type`이 별도로 존재하는 이유:
 * - `eventType`: Kafka 라우팅 수준의 이벤트 종류 (예: `CHAT_MESSAGE_CREATED`)
 * - `type`: 메시지 콘텐츠의 렌더링 방식 (예: `TEXT`, `FILE`, `SYSTEM`)
 *
 * `projectId`는 팀 채널이 아닌 프로젝트 채널에만 존재한다.
 * `clientMessageId`는 클라이언트가 생성한 멱등성 키로, 중복 발행 감지에 사용된다.
 * `occurredAt`은 ISO 8601 문자열이며 서버 시각 기준이다.
 */
export interface ChatMessageEvent {
    /** Kafka 라우팅용 이벤트 종류 식별자 */
    eventType: string;
    teamId: number;
    /** 프로젝트 채널일 때만 설정; 팀 채널은 null 또는 undefined */
    projectId?: number | null;
    channelId: number;
    authorId: number;
    authorRole: string;
    content: string;
    /** 메시지 렌더링 타입 (예: TEXT, FILE, SYSTEM) */
    type: string;
    attachments?: any[];
    /** 스레드 부모 메시지 ID (MongoDB ObjectId 문자열); 최상위 메시지는 undefined */
    parentMessageId?: string;
    /** 클라이언트 측 멱등성 키; 중복 전송 방지에 사용 */
    clientMessageId?: string;
    /** 이벤트 발생 시각 (ISO 8601, 서버 기준) */
    occurredAt: string;
}
