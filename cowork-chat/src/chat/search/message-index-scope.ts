import { MessageIndexDoc } from '../../search/message-index.contract';

export const SEARCH_INDEX_STATUSES = [
    'PENDING', 'PROCESSING', 'SYNCED', 'FAILED', 'DELETING', 'SKIPPED',
] as const;
export type SearchIndexStatus = typeof SEARCH_INDEX_STATUSES[number];

/**
 * 색인 대상 메시지 조건.
 *
 * 팀·프로젝트 채널의 사용자 메시지만 검색에 노출한다. DM(`teamId: null`)과 프로젝트 무관 채널,
 * 그리고 입퇴장 안내 같은 `SYSTEM` 메시지는 대상이 아니다. 증분 동기화와 전체 재구축이
 * 같은 조건을 사용해야 MongoDB 기준으로 누락·잔존 문서를 판정할 수 있다.
 */
export const SEARCH_INDEX_SCOPE_FILTER = {
    teamId: { $ne: null },
    projectId: { $ne: null },
    type: { $ne: 'SYSTEM' },
};

/** 색인 대상 메시지의 최소 필드. 스캔 projection과 write 경로가 공유한다. */
export interface IndexableMessage {
    teamId: number | null;
    projectId: number | null;
    type: string;
}

export function isSearchIndexed(message: IndexableMessage): boolean {
    return message.teamId !== null && message.teamId !== undefined
        && message.projectId !== null && message.projectId !== undefined
        && message.type !== 'SYSTEM';
}

/** 색인 대상이면 `PENDING`, 아니면 `SKIPPED`를 돌려준다. */
export function initialSearchIndexStatus(message: IndexableMessage): SearchIndexStatus {
    return isSearchIndexed(message) ? 'PENDING' : 'SKIPPED';
}

export interface MessageIndexSource extends IndexableMessage {
    _id: { toString(): string };
    channelId: number;
    authorId: number;
    content: string;
    attachments?: unknown[];
    isPinned?: boolean;
    createdAt: Date;
}

/**
 * MongoDB 메시지를 색인 문서로 변환한다.
 *
 * 부분 갱신 없이 항상 전체 문서를 만들기 때문에 최초 색인이 누락된 메시지도
 * 이후 어떤 변경으로든 복원된다. 호출 전에 {@link isSearchIndexed}로 대상을 확인해야 한다.
 */
export function buildMessageIndexDoc(message: MessageIndexSource): MessageIndexDoc {
    return {
        messageId: message._id.toString(),
        teamId: message.teamId!,
        projectId: message.projectId!,
        channelId: message.channelId,
        authorId: message.authorId,
        content: message.content,
        type: message.type,
        hasAttachments: (message.attachments?.length ?? 0) > 0,
        isPinned: message.isPinned ?? false,
        createdAt: message.createdAt.toISOString(),
    };
}
