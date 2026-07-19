import { Inject, Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { Client, estypes } from '@elastic/elasticsearch';
import { ELASTICSEARCH_CLIENT } from './elasticsearch.constants';

const INDEX = 'chat_messages';

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

@Injectable()
export class ElasticsearchService implements OnModuleInit {
    private readonly logger = new Logger(ElasticsearchService.name);

    constructor(@Inject(ELASTICSEARCH_CLIENT) private readonly client: Client) {}

    async onModuleInit() {
        await this.createIndexIfNotExists();
    }

    private async createIndexIfNotExists() {
        try {
            const exists = await this.client.indices.exists({ index: INDEX });
            if (exists) return;

            const params: estypes.IndicesCreateRequest = {
                index: INDEX,
                settings: {
                    analysis: {
                        analyzer: {
                            nori_analyzer: {
                                type: 'custom',
                                tokenizer: 'nori_tokenizer',
                                filter: ['lowercase'],
                            },
                        },
                    },
                },
                mappings: {
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
                },
            };
            await this.client.indices.create(params);

            this.logger.log(`Index created: ${INDEX}`);
        } catch (err) {
            this.logger.error('Failed to create ES index', err);
        }
    }

    async indexMessage(doc: MessageIndexDoc): Promise<void> {
        try {
            await this.client.index({
                index: INDEX,
                id: doc.messageId,
                document: doc,
            });
        } catch (err) {
            this.logger.error(`Failed to index ES message id=${doc.messageId}`, err);
        }
    }

    async updateMessage(messageId: string, content: string): Promise<void> {
        try {
            await this.client.update({
                index: INDEX,
                id: messageId,
                doc: { content },
            });
        } catch (err) {
            if (this.isNotFoundError(err)) return;
            this.logger.error(`Failed to update ES message id=${messageId}`, err);
        }
    }

    async updatePinStatus(messageId: string, isPinned: boolean): Promise<void> {
        try {
            await this.client.update({
                index: INDEX,
                id: messageId,
                doc: { isPinned },
            });
        } catch (err) {
            if (this.isNotFoundError(err)) return;
            this.logger.error(`Failed to update ES message pin status id=${messageId}`, err);
        }
    }

    async deleteMessage(messageId: string): Promise<void> {
        try {
            await this.client.delete({ index: INDEX, id: messageId });
        } catch (err) {
            if (this.isNotFoundError(err)) return;
            this.logger.error(`Failed to delete ES message id=${messageId}`, err);
        }
    }

    private isNotFoundError(err: unknown): boolean {
        return typeof err === 'object' && err !== null && 'statusCode' in err && err.statusCode === 404;
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

    async searchTeamMessages(params: SearchTeamMessagesParams): Promise<{ hits: SearchHit[]; nextCursor: string | null }> {
        const { teamId, accessibleChannelIds, q, authorId, type, hasFile, before, limit } = params;

        const filter: estypes.QueryDslQueryContainer[] = [];
        if (authorId !== undefined) filter.push({ term: { authorId } });
        if (type !== undefined) filter.push({ term: { type } });
        if (hasFile === true) filter.push({ term: { hasAttachments: true } });

        const searchAfter = this.parseSearchAfter(before);

        const response = await this.client.search<MessageIndexDoc>({
            index: INDEX,
            query: {
                    bool: {
                        must: [
                            { term: { teamId } },
                            { terms: { channelId: accessibleChannelIds } },
                            {
                                bool: {
                                    should: [
                                        { match: { content: { query: q, fuzziness: 'AUTO' } } },
                                        { match_phrase: { content: { query: q, boost: 2 } } },
                                    ],
                                    minimum_should_match: 1,
                                },
                            },
                        ],
                        filter,
                    },
                },
                highlight: {
                    fields: {
                        content: {
                            pre_tags: ['<em>'],
                            post_tags: ['</em>'],
                            number_of_fragments: 3,
                            fragment_size: 150,
                        },
                    },
                },
                sort: [{ createdAt: 'desc' }, { messageId: 'desc' }],
                size: limit,
                ...(searchAfter ? { search_after: searchAfter } : {}),
        });

        const rawHits = response.hits.hits;
        const hits = this.toSearchHits(rawHits);
        const nextCursor = this.buildNextCursor(rawHits, hits.length, limit);

        return { hits, nextCursor };
    }

    async searchMessages(params: SearchMessagesParams): Promise<{ hits: SearchHit[]; nextCursor: string | null }> {
        const { projectId, accessibleChannelIds, q, channelId, authorId, type, hasFile, before, limit } = params;

        const filter: estypes.QueryDslQueryContainer[] = [];
        if (channelId !== undefined) {
            filter.push({ term: { channelId } });
        }
        if (authorId !== undefined) {
            filter.push({ term: { authorId } });
        }
        if (type !== undefined) {
            filter.push({ term: { type } });
        }
        if (hasFile === true) {
            filter.push({ term: { hasAttachments: true } });
        }

        const searchAfter = this.parseSearchAfter(before);

        const response = await this.client.search<MessageIndexDoc>({
            index: INDEX,
            query: {
                    bool: {
                        must: [
                            { term: { projectId } },
                            { terms: { channelId: accessibleChannelIds } },
                            {
                                bool: {
                                    should: [
                                        { match: { content: { query: q, fuzziness: 'AUTO' } } },
                                        { match_phrase: { content: { query: q, boost: 2 } } },
                                    ],
                                    minimum_should_match: 1,
                                },
                            },
                        ],
                        filter,
                    },
                },
                highlight: {
                    fields: {
                        content: {
                            pre_tags: ['<em>'],
                            post_tags: ['</em>'],
                            number_of_fragments: 3,
                            fragment_size: 150,
                        },
                    },
                },
                sort: [{ createdAt: 'desc' }, { messageId: 'desc' }],
                size: limit,
                ...(searchAfter ? { search_after: searchAfter } : {}),
        });

        const rawHits = response.hits.hits;
        const hits = this.toSearchHits(rawHits);
        const nextCursor = this.buildNextCursor(rawHits, hits.length, limit);

        return { hits, nextCursor };
    }
}
