import { Types } from 'mongoose';
import { ChatMessageEvent } from './chat-message.event';

export const CHAT_MESSAGE_CONTRACT_VERSION = 1 as const;
export const CHAT_MESSAGE_MAX_CONTENT_LENGTH = 25_000;

export class ChatMessageContractError extends Error {
    constructor(
        readonly reasonCode: string,
        message: string,
    ) {
        super(message);
        this.name = ChatMessageContractError.name;
    }
}

/**
 * Kafka의 untrusted payload를 ChatMessageEvent로 검증·정규화한다.
 * 버전 필드가 없는 배포 이전 레코드는 v1으로 호환한다.
 */
export function validateChatMessageEvent(payload: unknown, eventKey: string | null): ChatMessageEvent {
    if (!isRecord(payload)) throw contractError('INVALID_PAYLOAD', 'payload must be an object');
    const contractVersion = payload.contractVersion ?? CHAT_MESSAGE_CONTRACT_VERSION;
    if (contractVersion !== CHAT_MESSAGE_CONTRACT_VERSION) {
        throw contractError('UNSUPPORTED_CONTRACT_VERSION', 'contractVersion is not supported');
    }
    if (payload.eventType !== 'MESSAGE_SENT') throw contractError('INVALID_EVENT_TYPE', 'eventType must be MESSAGE_SENT');
    const channelId = positiveSafeInteger(payload.channelId, 'CHANNEL_ID');
    if (eventKey === null || eventKey !== String(channelId)) {
        throw contractError('KEY_CHANNEL_MISMATCH', 'Kafka key must match channelId');
    }
    const authorId = positiveSafeInteger(payload.authorId, 'AUTHOR_ID');
    const teamId = nullablePositiveSafeInteger(payload.teamId, 'TEAM_ID');
    const projectId = nullablePositiveSafeInteger(payload.projectId, 'PROJECT_ID');
    if (typeof payload.authorRole !== 'string' || payload.authorRole.length === 0) {
        throw contractError('INVALID_AUTHOR_ROLE', 'authorRole must be a non-empty string');
    }
    if (typeof payload.content !== 'string' || payload.content.length > CHAT_MESSAGE_MAX_CONTENT_LENGTH) {
        throw contractError('INVALID_CONTENT', 'content must be a string up to 25000 characters');
    }
    if (payload.type !== 'TEXT' && payload.type !== 'FILE') {
        throw contractError('INVALID_MESSAGE_TYPE', 'type must be TEXT or FILE');
    }
    const attachments = validateAttachments(payload.attachments);
    const parentMessageId = optionalObjectId(payload.parentMessageId, 'PARENT_MESSAGE_ID');
    const clientMessageId = optionalString(payload.clientMessageId, 'CLIENT_MESSAGE_ID');
    if (typeof payload.occurredAt !== 'string' || !isRfc3339(payload.occurredAt)) {
        throw contractError('INVALID_OCCURRED_AT', 'occurredAt must be a valid RFC3339 timestamp');
    }
    return {
        contractVersion: CHAT_MESSAGE_CONTRACT_VERSION,
        eventType: 'MESSAGE_SENT',
        teamId,
        projectId,
        channelId,
        authorId,
        authorRole: payload.authorRole,
        content: payload.content,
        type: payload.type,
        attachments,
        parentMessageId,
        clientMessageId,
        occurredAt: payload.occurredAt,
    };
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function positiveSafeInteger(value: unknown, name: string): number {
    if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0) {
        throw contractError(`INVALID_${name}`, `${name.toLowerCase()} must be a positive safe integer`);
    }
    return value;
}

function nullablePositiveSafeInteger(value: unknown, name: string): number | null {
    if (value === null || value === undefined) return null;
    return positiveSafeInteger(value, name);
}

function optionalString(value: unknown, name: string): string | undefined {
    if (value === undefined) return undefined;
    if (typeof value !== 'string') throw contractError(`INVALID_${name}`, `${name.toLowerCase()} must be a string`);
    return value;
}

function optionalObjectId(value: unknown, name: string): string | undefined {
    const candidate = optionalString(value, name);
    if (candidate !== undefined && !Types.ObjectId.isValid(candidate)) {
        throw contractError(`INVALID_${name}`, `${name.toLowerCase()} must be a MongoDB ObjectId`);
    }
    return candidate;
}

function validateAttachments(value: unknown): ChatMessageEvent['attachments'] {
    if (value === undefined) return undefined;
    if (!Array.isArray(value) || !value.every((attachment) => isAttachment(attachment))) {
        throw contractError('INVALID_ATTACHMENTS', 'attachments must be an array of valid attachment objects');
    }
    return value;
}

function isAttachment(value: unknown): value is NonNullable<ChatMessageEvent['attachments']>[number] {
    return isRecord(value)
        && typeof value.name === 'string'
        && typeof value.url === 'string'
        && typeof value.mimeType === 'string'
        && typeof value.size === 'number'
        && Number.isFinite(value.size)
        && value.size >= 0;
}

function isRfc3339(value: string): boolean {
    return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
        && !Number.isNaN(new Date(value).getTime());
}

function contractError(reasonCode: string, message: string): ChatMessageContractError {
    return new ChatMessageContractError(reasonCode, message);
}
