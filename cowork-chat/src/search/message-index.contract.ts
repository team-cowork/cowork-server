import { estypes } from '@elastic/elasticsearch';

/** 검색 읽기·쓰기가 사용하는 alias. 전체 재구축은 이 alias만 원자적으로 교체한다. */
export const MESSAGE_SEARCH_ALIAS = 'chat_messages';

/** 재구축이 만드는 물리 index 접두사. alias가 실제로 가리키는 index 이름이다. */
export const MESSAGE_SEARCH_INDEX_PREFIX = 'chat_messages-';

/**
 * 삭제 버전 tombstone 보존 기간.
 *
 * 외부 버전(`version_type: 'external'`) 삭제는 이 기간 동안만 버전을 기억하므로,
 * 이 값이 outbox claim 타임아웃보다 짧으면 지연된 upsert가 삭제된 문서를 되살릴 수 있다.
 */
export const MESSAGE_INDEX_GC_DELETES = '7d';

/** Elasticsearch에 저장하는 메시지 색인 문서. MongoDB 메시지에서 파생된다. */
export interface MessageIndexDoc {
    messageId: string;
    teamId: number;
    projectId: number;
    channelId: number;
    authorId: number;
    content: string;
    type: string;
    hasAttachments: boolean;
    isPinned: boolean;
    createdAt: string;
}

/** 색인 문서에 반드시 존재해야 하는 필드. 재구축 검증이 표본 문서에서 확인한다. */
export const MESSAGE_INDEX_REQUIRED_FIELDS: Array<keyof MessageIndexDoc> = [
    'messageId', 'teamId', 'projectId', 'channelId', 'authorId', 'content', 'type', 'createdAt',
];

/**
 * 색인 쓰기 결과.
 *
 * - `APPLIED`: 요청한 버전이 색인에 반영되었다.
 * - `SUPERSEDED`: 색인에 더 최신 버전이 있어 이 쓰기는 버려졌다. 재시도할 필요가 없다.
 * - `RETRYABLE`: 일시적 오류다. 백오프 후 같은 버전으로 다시 시도해야 한다.
 * - `PERMANENT`: 문서·매핑 계약 위반이다. 재시도해도 같은 결과이므로 운영자 개입이 필요하다.
 */
export type IndexWriteOutcome = 'APPLIED' | 'SUPERSEDED' | 'RETRYABLE' | 'PERMANENT';

export interface IndexWriteResult {
    outcome: IndexWriteOutcome;
    error?: string;
}

export const MESSAGE_INDEX_SETTINGS: estypes.IndicesIndexSettings = {
    'index.gc_deletes': MESSAGE_INDEX_GC_DELETES,
    analysis: {
        analyzer: {
            nori_analyzer: {
                type: 'custom',
                tokenizer: 'nori_tokenizer',
                filter: ['lowercase'],
            },
        },
    },
};

export const MESSAGE_INDEX_MAPPINGS: estypes.MappingTypeMapping = {
    properties: {
        messageId:      { type: 'keyword' },
        teamId:         { type: 'long' },
        projectId:      { type: 'long' },
        channelId:      { type: 'long' },
        authorId:       { type: 'long' },
        content:        { type: 'text', analyzer: 'nori_analyzer' },
        type:           { type: 'keyword' },
        hasAttachments: { type: 'boolean' },
        isPinned:       { type: 'boolean' },
        createdAt:      { type: 'date' },
    },
};

/** 재구축 대상 물리 index 이름을 만든다. alias 교체 전까지 검색 트래픽에 노출되지 않는다. */
export function buildMessageIndexName(now: Date): string {
    return `${MESSAGE_SEARCH_INDEX_PREFIX}${now.toISOString().replace(/[-:T.Z]/g, '').slice(0, 14)}`;
}

/** 재구축이 만든 물리 index인지 판별한다. 운영자가 만든 다른 index를 정리 대상으로 삼지 않는다. */
export function isManagedMessageIndex(name: string): boolean {
    return new RegExp(`^${MESSAGE_SEARCH_INDEX_PREFIX}\\d{14}$`).test(name);
}

function statusCodeOf(error: unknown): number | undefined {
    if (typeof error !== 'object' || error === null) return undefined;
    const direct = (error as { statusCode?: unknown }).statusCode;
    if (typeof direct === 'number') return direct;
    const meta = (error as { meta?: { statusCode?: unknown } }).meta?.statusCode;
    return typeof meta === 'number' ? meta : undefined;
}

/** 대상이 없다는 응답인지 판별한다. 통신 오류를 "없음"으로 오인하지 않기 위해 상태 코드만 본다. */
export function isElasticsearchNotFound(error: unknown): boolean {
    return statusCodeOf(error) === 404;
}

/** index 자체가 없어서 실패했는지 판별한다. 문서만 없는 `404`와 구분해야 한다. */
export function isIndexNotFound(error: unknown): boolean {
    if (!isElasticsearchNotFound(error)) return false;
    const body = (error as { body?: { error?: { type?: unknown } } }).body;
    return body?.error?.type === 'index_not_found_exception';
}

export function errorMessageOf(error: unknown): string {
    if (error instanceof Error) return error.message;
    return typeof error === 'string' ? error : 'unknown elasticsearch error';
}

/**
 * Elasticsearch 오류를 호출자가 구분할 수 있는 결과로 분류한다.
 *
 * 버전 충돌(409)은 더 최신 쓰기가 이미 반영되었다는 뜻이므로 실패가 아니라 `SUPERSEDED`다.
 * 요청 계약 오류(400)만 영구 실패로 보고, 나머지는 모두 재시도 가능으로 처리해
 * 일시적 장애가 색인 누락으로 굳지 않게 한다.
 */
export function classifyElasticsearchError(error: unknown): IndexWriteResult {
    const statusCode = statusCodeOf(error);
    if (statusCode === 409) return { outcome: 'SUPERSEDED' };
    if (statusCode === 400) return { outcome: 'PERMANENT', error: errorMessageOf(error) };
    return { outcome: 'RETRYABLE', error: errorMessageOf(error) };
}
