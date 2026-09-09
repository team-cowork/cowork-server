import { BadRequestException, Inject, Injectable, Logger, OnModuleInit, ServiceUnavailableException } from '@nestjs/common';
import { Client, estypes } from '@elastic/elasticsearch';
import { ELASTICSEARCH_CLIENT } from './elasticsearch.constants';
import {
    classifyElasticsearchError,
    errorMessageOf,
    isElasticsearchBadRequest,
    isElasticsearchNotFound,
    isIndexNotFound,
    isManagedMessageIndex,
    IndexWriteResult,
    MESSAGE_INDEX_GC_DELETES,
    MESSAGE_INDEX_MAPPINGS,
    MESSAGE_INDEX_SETTINGS,
    MessageIndexDoc,
    MESSAGE_SEARCH_ALIAS,
    buildMessageIndexName,
} from './message-index.contract';

export type { MessageIndexDoc } from './message-index.contract';

/** `isReady()`가 한 번 `true`가 된 뒤에도 alias 존재를 다시 확인하는 최소 간격. */
const READY_RECHECK_INTERVAL_MS = 30_000;

export interface SearchMessagesParams {
    projectId: number;
    accessibleChannelIds: number[];
    q: string;
    channelId?: number;
    authorId?: number;
    type?: string;
    hasFile?: boolean;
    before?: string;
    limit: number;
}

export interface SearchTeamMessagesParams {
    teamId: number;
    accessibleChannelIds: number[];
    q: string;
    authorId?: number;
    type?: string;
    hasFile?: boolean;
    before?: string;
    limit: number;
}

export interface SearchHit {
    messageId: string;
    channelId: number;
    authorId: number;
    content: string;
    highlight: string[];
    type: string;
    hasAttachments: boolean;
    isPinned: boolean;
    createdAt: string;
}

export interface BulkUpsertEntry {
    doc: MessageIndexDoc;
    version: number;
}

export interface BulkUpsertResult {
    applied: number;
    superseded: number;
    failures: Array<{ messageId: string; error: string }>;
}

/**
 * 메시지 검색 색인의 Elasticsearch 접근 계층.
 *
 * 읽기·쓰기는 모두 {@link MESSAGE_SEARCH_ALIAS} alias를 통해 수행하므로 전체 재구축은
 * 새 물리 index를 만든 뒤 alias만 교체하면 된다. 모든 쓰기는 MongoDB가 부여한 단조 증가
 * 버전을 외부 버전으로 전달해, 지연된 쓰기가 최신 상태를 되돌리지 못하게 한다.
 *
 * 쓰기 메서드는 오류를 삼키지 않고 {@link IndexWriteResult}로 성공·무시·재시도·영구 실패를
 * 구분해 반환한다. 호출자는 outbox 상태 전이에 이 결과를 사용한다.
 */
@Injectable()
export class ElasticsearchService implements OnModuleInit {
    private readonly logger = new Logger(ElasticsearchService.name);
    private ready = false;
    private lastBootstrapError: string | null = null;
    private lastReadyCheckAt = 0;

    constructor(@Inject(ELASTICSEARCH_CLIENT) private readonly client: Client) {}

    async onModuleInit() {
        await this.ensureIndexReady();
    }

    /** 색인 부트스트랩이 끝났는지 여부. 검색 API와 readiness 노출이 이 값을 사용한다. */
    isReady(): boolean {
        return this.ready;
    }

    getLastBootstrapError(): string | null {
        return this.lastBootstrapError;
    }

    /**
     * alias와 물리 index가 준비되었는지 확인하고, 아니면 한 번 더 부트스트랩을 시도한다.
     *
     * 한 번 준비된 뒤에도 매 폴링 사이클(3초)마다 다시 확인하지는 않고,
     * {@link READY_RECHECK_INTERVAL_MS}(30초)마다 alias가 여전히 존재하는지만 가볍게 재확인한다.
     * 운영 중 alias가 지워지거나 클러스터가 재구성돼도 이 재확인이 없으면 `isReady()`가 계속
     * `true`를 반환해 `/health/ready`와 지표, 검색 API의 {@link assertReady}가 실제 상태를 놓친다.
     *
     * 부트스트랩 실패는 예외로 전파하지 않고 `false`를 반환한다. 색인 준비 실패가
     * 채팅 송수신 자체를 막지 않게 하되, 검색 API는 {@link assertReady}로 503을 반환한다.
     */
    async ensureIndexReady(): Promise<boolean> {
        if (this.ready) {
            if (Date.now() - this.lastReadyCheckAt < READY_RECHECK_INTERVAL_MS) return true;
            this.lastReadyCheckAt = Date.now();
            if (await this.aliasStillExists()) return true;
            this.ready = false;
            this.logger.warn(`Search alias no longer exists, re-bootstrapping: ${MESSAGE_SEARCH_ALIAS}`);
        }
        try {
            await this.bootstrapIndex();
            this.ready = true;
            this.lastReadyCheckAt = Date.now();
            this.lastBootstrapError = null;
            return true;
        } catch (error) {
            this.ready = false;
            this.lastBootstrapError = errorMessageOf(error);
            this.logger.error(`Failed to prepare search index alias=${MESSAGE_SEARCH_ALIAS}`, error);
            return false;
        }
    }

    /** alias 또는 (승격 전 배포의) 동명 물리 index가 여전히 존재하는지 확인한다. */
    private async aliasStillExists(): Promise<boolean> {
        try {
            if (await this.client.indices.existsAlias({ name: MESSAGE_SEARCH_ALIAS })) return true;
            return await this.client.indices.exists({ index: MESSAGE_SEARCH_ALIAS });
        } catch {
            return false;
        }
    }

    /**
     * alias가 없으면 만든다.
     *
     * alias 이름과 같은 이름의 물리 index가 이미 있는 배포(초기 구현)에서는 alias를 만들 수
     * 없으므로 그 index를 그대로 쓰고, alias 승격은 전체 재구축이 담당한다.
     */
    private async bootstrapIndex(): Promise<void> {
        if (await this.client.indices.existsAlias({ name: MESSAGE_SEARCH_ALIAS })) {
            return;
        }
        if (await this.client.indices.exists({ index: MESSAGE_SEARCH_ALIAS })) {
            await this.client.indices.putSettings({
                index: MESSAGE_SEARCH_ALIAS,
                settings: { index: { gc_deletes: MESSAGE_INDEX_GC_DELETES } },
            });
            this.logger.warn(`Search alias name is still a concrete index. Run a full rebuild to promote it to an alias: ${MESSAGE_SEARCH_ALIAS}`);
            return;
        }
        const index = buildMessageIndexName(new Date());
        await this.createIndex(index);
        await this.client.indices.updateAliases({ actions: [{ add: { index, alias: MESSAGE_SEARCH_ALIAS } }] });
        await this.collapseAliasTargets();
        this.logger.log(`Search index created and aliased: ${index} -> ${MESSAGE_SEARCH_ALIAS}`);
    }

    /**
     * alias가 둘 이상의 index를 가리키면 하나만 남긴다.
     *
     * 여러 replica가 동시에 콜드 스타트하면 각자 만든 빈 index를 같은 alias에 붙일 수 있고,
     * 그 상태에서는 Elasticsearch가 쓰기 대상을 정하지 못해 색인이 전부 실패한다. 이름이
     * 시각 순이므로 가장 먼저 만들어진 index를 남기면 어느 replica가 실행해도 같은 결과가 된다.
     */
    private async collapseAliasTargets(): Promise<void> {
        const targets = (await this.getAliasTargets()).sort();
        if (targets.length <= 1) return;

        const [keep, ...extras] = targets;
        await this.client.indices.updateAliases({
            actions: extras.map((name) => ({ remove: { index: name, alias: MESSAGE_SEARCH_ALIAS } })),
        });
        for (const extra of extras) {
            if (isManagedMessageIndex(extra)) await this.deleteIndex(extra);
        }
        this.logger.warn(`Collapsed concurrently bootstrapped search indices, kept ${keep}, removed ${extras.join(', ')}`);
    }

    /** 재구축용 물리 index를 생성한다. 검증 전까지 alias에 연결하지 않는다. */
    async createIndex(index: string): Promise<void> {
        await this.client.indices.create({
            index,
            settings: MESSAGE_INDEX_SETTINGS,
            mappings: MESSAGE_INDEX_MAPPINGS,
        });
    }

    async deleteIndex(index: string): Promise<void> {
        await this.client.indices.delete({ index });
    }

    /** 물리 index가 실제로 존재하는지 확인한다. 재구축 재개가 지워진 index로 잘못 이어지지 않게 한다. */
    async indexExists(index: string): Promise<boolean> {
        return this.client.indices.exists({ index });
    }

    async refreshIndex(index: string): Promise<void> {
        await this.client.indices.refresh({ index });
    }

    async countDocuments(index: string): Promise<number> {
        const response = await this.client.count({ index });
        return response.count;
    }

    /**
     * alias가 현재 가리키는 물리 index 목록. alias가 아직 없으면 빈 배열이다.
     *
     * alias 부재(`404`)만 빈 배열로 처리하고 다른 오류는 그대로 전파한다. 일시적 통신 오류를
     * "alias 없음"으로 오인하면 alias 전환이 살아 있는 index를 지울 수 있다.
     */
    async getAliasTargets(): Promise<string[]> {
        try {
            const response = await this.client.indices.getAlias({ name: MESSAGE_SEARCH_ALIAS });
            return Object.keys(response);
        } catch (error) {
            if (isElasticsearchNotFound(error)) return [];
            throw error;
        }
    }

    /** 재구축이 만든 물리 index 목록을 조회한다. 미사용 index 정리에 사용한다. */
    async listManagedIndices(prefix: string): Promise<string[]> {
        const response = await this.client.indices.get({ index: `${prefix}*`, ignore_unavailable: true });
        return Object.keys(response);
    }

    /**
     * alias가 가리키는 index를 한 번의 요청으로 교체한다.
     *
     * alias 이름이 아직 물리 index인 배포에서는 alias를 만들기 전에 그 index를 제거해야 한다.
     * 이 경우에만 짧은 검색 공백이 생기며, 승격이 끝나면 이후 교체는 완전히 원자적이다.
     */
    async promoteIndex(index: string): Promise<void> {
        const previous = await this.getAliasTargets();
        if (previous.length === 0 && await this.client.indices.exists({ index: MESSAGE_SEARCH_ALIAS })) {
            await this.client.indices.delete({ index: MESSAGE_SEARCH_ALIAS });
        }
        await this.client.indices.updateAliases({
            actions: [
                ...previous.map((name) => ({ remove: { index: name, alias: MESSAGE_SEARCH_ALIAS } })),
                { add: { index, alias: MESSAGE_SEARCH_ALIAS } },
            ],
        });
        this.ready = true;
        this.lastBootstrapError = null;
    }

    /**
     * 메시지 전체 문서를 색인에 upsert한다.
     *
     * 부분 갱신 대신 항상 MongoDB의 최신 전체 문서를 쓰기 때문에 최초 색인이 누락된 메시지도
     * 이후 편집·고정 작업으로 복원된다.
     *
     * @param version MongoDB가 부여한 메시지별 단조 증가 버전
     * @param index 대상 물리 index. 생략하면 검색 alias에 쓴다
     */
    async upsertMessage(doc: MessageIndexDoc, version: number, index: string = MESSAGE_SEARCH_ALIAS): Promise<IndexWriteResult> {
        try {
            await this.client.index({
                index,
                id: doc.messageId,
                document: doc,
                version,
                version_type: 'external',
            });
            return { outcome: 'APPLIED' };
        } catch (error) {
            return this.classifyWrite('upsert', doc.messageId, error);
        }
    }

    /**
     * 색인에서 메시지를 제거한다.
     *
     * 외부 버전 삭제는 문서가 이미 없어도 버전 tombstone을 남기고 `404`로 응답하므로,
     * 문서 부재는 성공으로 간주한다. 이 tombstone 덕분에 뒤늦게 도착한 낮은 버전 upsert가
     * 삭제된 문서를 되살리지 못한다.
     */
    async deleteMessage(messageId: string, version: number, index: string = MESSAGE_SEARCH_ALIAS): Promise<IndexWriteResult> {
        try {
            await this.client.delete({ index, id: messageId, version, version_type: 'external' });
            return { outcome: 'APPLIED' };
        } catch (error) {
            if (isElasticsearchNotFound(error) && !isIndexNotFound(error)) return { outcome: 'APPLIED' };
            return this.classifyWrite('delete', messageId, error);
        }
    }

    private classifyWrite(operation: string, messageId: string, error: unknown): IndexWriteResult {
        const result = classifyElasticsearchError(error);
        if (result.outcome === 'PERMANENT') {
            this.logger.error(`Search index ${operation} rejected permanently messageId=${messageId}: ${result.error}`);
        } else if (result.outcome === 'RETRYABLE') {
            this.logger.warn(`Search index ${operation} failed, will retry messageId=${messageId}: ${result.error}`);
        }
        return result;
    }

    /** 재구축 스캔이 사용하는 대량 색인. 항목별 실패를 호출자에게 그대로 돌려준다. */
    async bulkUpsertMessages(entries: BulkUpsertEntry[], index: string): Promise<BulkUpsertResult> {
        if (entries.length === 0) return { applied: 0, superseded: 0, failures: [] };

        const response = await this.client.bulk({
            operations: entries.flatMap(({ doc, version }) => [
                { index: { _index: index, _id: doc.messageId, version, version_type: 'external' as const } },
                doc,
            ]),
        });

        const result: BulkUpsertResult = { applied: 0, superseded: 0, failures: [] };
        for (const item of response.items) {
            const operation = item.index;
            if (!operation) continue;
            if (!operation.error) {
                result.applied += 1;
            } else if (operation.status === 409) {
                result.superseded += 1;
            } else {
                result.failures.push({
                    messageId: operation._id ?? 'unknown',
                    error: operation.error.reason ?? operation.error.type,
                });
            }
        }
        return result;
    }

    /** 재구축 검증이 사용하는 표본 조회. 필수 필드 누락을 확인한다. */
    async sampleDocuments(index: string, size: number): Promise<Array<Partial<MessageIndexDoc>>> {
        const response = await this.client.search<MessageIndexDoc>({
            index,
            size,
            query: { function_score: { query: { match_all: {} }, functions: [{ random_score: {} }] } },
        });
        return response.hits.hits.map((hit) => hit._source ?? {});
    }

    /**
     * 검색 색인이 준비되지 않았으면 503을 던진다.
     *
     * 색인이 없는 상태에서 빈 결과를 정상 응답으로 돌려주면 호출자가 "검색 결과 없음"으로
     * 오판하므로, 사용 불가 상태를 명시적으로 알린다.
     */
    private assertReady(): void {
        if (this.ready) return;
        throw new ServiceUnavailableException('메시지 검색 색인을 사용할 수 없습니다. 잠시 후 다시 시도해 주세요');
    }

    private toSearchHits(rawHits: estypes.SearchHit<MessageIndexDoc>[]): SearchHit[] {
        return rawHits.map((hit) => {
            const source = hit._source!;
            return {
                messageId: source.messageId,
                channelId: source.channelId,
                authorId: source.authorId,
                content: source.content,
                highlight: hit.highlight?.content ?? [],
                type: source.type,
                hasAttachments: source.hasAttachments,
                isPinned: source.isPinned,
                createdAt: source.createdAt,
            };
        });
    }

    private buildNextCursor(rawHits: estypes.SearchHit<MessageIndexDoc>[], hitCount: number, limit: number): string | null {
        const lastHit = rawHits.at(-1);
        return hitCount === limit && lastHit?.sort ? Buffer.from(JSON.stringify(lastHit.sort)).toString('base64') : null;
    }

    private parseSearchAfter(before: string | undefined): estypes.SortResults | undefined {
        if (!before) return undefined;
        try {
            return JSON.parse(Buffer.from(before, 'base64').toString()) as estypes.SortResults;
        } catch {
            return undefined;
        }
    }

    private buildContentQuery(q: string): estypes.QueryDslQueryContainer {
        return {
            bool: {
                should: [
                    { match: { content: { query: q, fuzziness: 'AUTO' } } },
                    { match_phrase: { content: { query: q, boost: 2 } } },
                ],
                minimum_should_match: 1,
            },
        };
    }

    private readonly highlight: estypes.SearchHighlight = {
        fields: {
            content: {
                pre_tags: ['<em>'],
                post_tags: ['</em>'],
                number_of_fragments: 3,
                fragment_size: 150,
            },
        },
    };

    private async runSearch(
        must: estypes.QueryDslQueryContainer[],
        filter: estypes.QueryDslQueryContainer[],
        before: string | undefined,
        limit: number,
    ): Promise<{ hits: SearchHit[]; nextCursor: string | null }> {
        this.assertReady();
        const searchAfter = this.parseSearchAfter(before);

        let response: estypes.SearchResponse<MessageIndexDoc>;
        try {
            response = await this.client.search<MessageIndexDoc>({
                index: MESSAGE_SEARCH_ALIAS,
                query: { bool: { must, filter } },
                highlight: this.highlight,
                sort: [{ createdAt: 'desc' }, { messageId: 'desc' }],
                size: limit,
                ...(searchAfter ? { search_after: searchAfter } : {}),
            });
        } catch (error) {
            if (isElasticsearchBadRequest(error)) {
                this.logger.warn(`Search query rejected as invalid alias=${MESSAGE_SEARCH_ALIAS}`, error);
                throw new BadRequestException('검색 조건이 올바르지 않습니다');
            }
            this.logger.error(`Search query failed alias=${MESSAGE_SEARCH_ALIAS}`, error);
            throw new ServiceUnavailableException('메시지 검색에 실패했습니다. 잠시 후 다시 시도해 주세요');
        }

        const rawHits = response.hits.hits;
        const hits = this.toSearchHits(rawHits);
        return { hits, nextCursor: this.buildNextCursor(rawHits, hits.length, limit) };
    }

    async searchTeamMessages(params: SearchTeamMessagesParams): Promise<{ hits: SearchHit[]; nextCursor: string | null }> {
        const { teamId, accessibleChannelIds, q, authorId, type, hasFile, before, limit } = params;

        const filter: estypes.QueryDslQueryContainer[] = [];
        if (authorId !== undefined) filter.push({ term: { authorId } });
        if (type !== undefined) filter.push({ term: { type } });
        if (hasFile === true) filter.push({ term: { hasAttachments: true } });

        return this.runSearch(
            [{ term: { teamId } }, { terms: { channelId: accessibleChannelIds } }, this.buildContentQuery(q)],
            filter,
            before,
            limit,
        );
    }

    async searchMessages(params: SearchMessagesParams): Promise<{ hits: SearchHit[]; nextCursor: string | null }> {
        const { projectId, accessibleChannelIds, q, channelId, authorId, type, hasFile, before, limit } = params;

        const filter: estypes.QueryDslQueryContainer[] = [];
        if (channelId !== undefined) filter.push({ term: { channelId } });
        if (authorId !== undefined) filter.push({ term: { authorId } });
        if (type !== undefined) filter.push({ term: { type } });
        if (hasFile === true) filter.push({ term: { hasAttachments: true } });

        return this.runSearch(
            [{ term: { projectId } }, { terms: { channelId: accessibleChannelIds } }, this.buildContentQuery(q)],
            filter,
            before,
            limit,
        );
    }
}
