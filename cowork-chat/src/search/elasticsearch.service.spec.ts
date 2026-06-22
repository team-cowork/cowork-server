import { Test, TestingModule } from '@nestjs/testing';
import { ElasticsearchService } from './elasticsearch.service';
import { ELASTICSEARCH_CLIENT } from './elasticsearch.constants';

const mockClient = {
    indices: {
        exists: jest.fn(),
        create: jest.fn(),
    },
    index: jest.fn(),
    update: jest.fn(),
    delete: jest.fn(),
    search: jest.fn(),
};

interface CapturedSearchRequest {
    query: { bool: { must: unknown[]; filter: unknown[] } };
    search_after?: unknown;
}

function getSearchRequestBody(): CapturedSearchRequest {
    const calls = mockClient.search.mock.calls as unknown as CapturedSearchRequest[][];
    return calls[0][0];
}

describe('ElasticsearchService', () => {
    let service: ElasticsearchService;

    beforeEach(async () => {
        const module: TestingModule = await Test.createTestingModule({
            providers: [
                ElasticsearchService,
                { provide: ELASTICSEARCH_CLIENT, useValue: mockClient },
            ],
        }).compile();

        service = module.get<ElasticsearchService>(ElasticsearchService);
        jest.clearAllMocks();
    });

    describe('onModuleInit / createIndexIfNotExists', () => {
        it('인덱스가 없으면 nori 설정으로 인덱스를 생성한다', async () => {
            mockClient.indices.exists.mockResolvedValue(false);
            mockClient.indices.create.mockResolvedValue({});

            await service.onModuleInit();

            expect(mockClient.indices.create).toHaveBeenCalledWith(
                expect.objectContaining({ index: 'chat_messages' }),
            );
        });

        it('인덱스가 이미 있으면 생성하지 않는다', async () => {
            mockClient.indices.exists.mockResolvedValue(true);

            await service.onModuleInit();

            expect(mockClient.indices.create).not.toHaveBeenCalled();
        });
    });

    describe('indexMessage', () => {
        it('messageId를 id로 사용해 chat_messages 인덱스에 문서를 저장한다', async () => {
            mockClient.index.mockResolvedValue({ result: 'created' });

            const doc = {
                messageId: 'msg-id-1',
                teamId: 10,
                projectId: 5,
                channelId: 2,
                authorId: 42,
                content: '안녕하세요',
                type: 'TEXT',
                hasAttachments: false,
                isPinned: false,
                createdAt: '2026-05-11T10:00:00.000Z',
            };

            await service.indexMessage(doc);

            expect(mockClient.index).toHaveBeenCalledWith({
                index: 'chat_messages',
                id: 'msg-id-1',
                document: doc,
            });
        });

        it('ES 오류가 발생하면 예외를 삼키고 정상 종료한다', async () => {
            mockClient.index.mockRejectedValue(new Error('ES connection error'));

            await expect(service.indexMessage({
                messageId: 'msg-id-1',
                teamId: 10,
                projectId: 5,
                channelId: 2,
                authorId: 42,
                content: '안녕',
                type: 'TEXT',
                hasAttachments: false,
                isPinned: false,
                createdAt: '2026-05-11T10:00:00.000Z',
            })).resolves.toBeUndefined();
        });
    });

    describe('updateMessage', () => {
        it('messageId에 해당하는 문서의 content를 업데이트한다', async () => {
            mockClient.update.mockResolvedValue({ result: 'updated' });

            await service.updateMessage('msg-id-1', '수정된 내용');

            expect(mockClient.update).toHaveBeenCalledWith({
                index: 'chat_messages',
                id: 'msg-id-1',
                doc: { content: '수정된 내용' },
            });
        });

        it('문서가 없어 404가 발생해도 예외를 던지지 않는다', async () => {
            mockClient.update.mockRejectedValue({ statusCode: 404 });

            await expect(service.updateMessage('nonexistent', '내용')).resolves.toBeUndefined();
        });
    });

    describe('deleteMessage', () => {
        it('messageId에 해당하는 문서를 삭제한다', async () => {
            mockClient.delete.mockResolvedValue({ result: 'deleted' });

            await service.deleteMessage('msg-id-1');

            expect(mockClient.delete).toHaveBeenCalledWith({
                index: 'chat_messages',
                id: 'msg-id-1',
            });
        });

        it('문서가 없어 404가 발생해도 예외를 던지지 않는다', async () => {
            mockClient.delete.mockRejectedValue({ statusCode: 404 });

            await expect(service.deleteMessage('nonexistent')).resolves.toBeUndefined();
        });
    });

    describe('searchMessages', () => {
        const makeHit = (messageId: string, overrides = {}) => ({
            _source: {
                messageId,
                channelId: 2,
                authorId: 42,
                content: '안녕하세요',
                type: 'TEXT',
                hasAttachments: false,
                isPinned: false,
                createdAt: '2026-05-11T10:00:00.000Z',
                ...overrides,
            },
            highlight: { content: ['<em>안녕</em>하세요'] },
            sort: ['2026-05-11T10:00:00.000Z', messageId],
        });

        const baseParams = {
            projectId: 5,
            accessibleChannelIds: [2, 3],
            q: '안녕',
            limit: 50,
        };

        it('projectId와 accessibleChannelIds로 필터링해 검색 결과를 반환한다', async () => {
            mockClient.search.mockResolvedValue({
                hits: { hits: [makeHit('msg-1')] },
            });

            const result = await service.searchMessages(baseParams);

            const body = getSearchRequestBody();
            expect(body.query.bool.must[0]).toEqual({ term: { projectId: 5 } });
            expect(body.query.bool.must[1]).toEqual({ terms: { channelId: [2, 3] } });
            expect(result.hits).toHaveLength(1);
            expect(result.hits[0].messageId).toBe('msg-1');
            expect(result.hits[0].highlight).toEqual(['<em>안녕</em>하세요']);
        });

        it('limit보다 적은 결과가 나오면 nextCursor는 null이다', async () => {
            mockClient.search.mockResolvedValue({
                hits: { hits: [makeHit('msg-1')] },
            });

            const { nextCursor } = await service.searchMessages({ ...baseParams, limit: 50 });

            expect(nextCursor).toBeNull();
        });

        it('결과가 limit과 같으면 마지막 sort 값을 base64 인코딩한 nextCursor를 반환한다', async () => {
            const hits = Array.from({ length: 50 }, (_, i) => makeHit(`msg-${i}`));
            mockClient.search.mockResolvedValue({ hits: { hits } });

            const { nextCursor } = await service.searchMessages({ ...baseParams, limit: 50 });

            const lastSort = ['2026-05-11T10:00:00.000Z', 'msg-49'];
            expect(nextCursor).toBe(Buffer.from(JSON.stringify(lastSort)).toString('base64'));
        });

        it('before가 있으면 search_after에 포함된다', async () => {
            mockClient.search.mockResolvedValue({ hits: { hits: [] } });
            const sortValues = ['cursor-id'];
            const before = Buffer.from(JSON.stringify(sortValues)).toString('base64');

            await service.searchMessages({ ...baseParams, before });

            const body = getSearchRequestBody();
            expect(body.search_after).toEqual(sortValues);
        });

        it('before가 없으면 search_after가 포함되지 않는다', async () => {
            mockClient.search.mockResolvedValue({ hits: { hits: [] } });

            await service.searchMessages(baseParams);

            const body = getSearchRequestBody();
            expect(body.search_after).toBeUndefined();
        });

        it('authorId 필터가 전달되면 filter에 포함된다', async () => {
            mockClient.search.mockResolvedValue({ hits: { hits: [] } });

            await service.searchMessages({ ...baseParams, authorId: 42 });

            const body = getSearchRequestBody();
            expect(body.query.bool.filter).toEqual(
                expect.arrayContaining([{ term: { authorId: 42 } }]),
            );
        });

        it('type 필터가 전달되면 filter에 포함된다', async () => {
            mockClient.search.mockResolvedValue({ hits: { hits: [] } });

            await service.searchMessages({ ...baseParams, type: 'FILE' });

            const body = getSearchRequestBody();
            expect(body.query.bool.filter).toEqual(
                expect.arrayContaining([{ term: { type: 'FILE' } }]),
            );
        });

        it('hasFile=true이면 hasAttachments:true 필터가 포함된다', async () => {
            mockClient.search.mockResolvedValue({ hits: { hits: [] } });

            await service.searchMessages({ ...baseParams, hasFile: true });

            const body = getSearchRequestBody();
            expect(body.query.bool.filter).toEqual(
                expect.arrayContaining([{ term: { hasAttachments: true } }]),
            );
        });

        it('하이라이팅이 없는 결과는 빈 배열로 반환된다', async () => {
            const hitWithoutHighlight = {
                _source: makeHit('msg-1')._source,
            };
            mockClient.search.mockResolvedValue({ hits: { hits: [hitWithoutHighlight] } });

            const { hits } = await service.searchMessages(baseParams);

            expect(hits[0].highlight).toEqual([]);
        });

        it('검색 결과가 없으면 빈 배열과 null cursor를 반환한다', async () => {
            mockClient.search.mockResolvedValue({ hits: { hits: [] } });

            const result = await service.searchMessages(baseParams);

            expect(result.hits).toEqual([]);
            expect(result.nextCursor).toBeNull();
        });
    });
});
