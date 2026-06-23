import { Model, Types } from 'mongoose';
import { Message } from '../schema/message.schema';
import { MessageRepository } from './message.repository';

const mockAggregate = jest.fn();
const mockCountDocuments = jest.fn();

const mockMessageModel = {
    aggregate: mockAggregate,
    countDocuments: mockCountDocuments,
    collection: { name: 'messages' },
};

type AggregatePipelineStage = Record<string, unknown>;

function getAggregatePipeline(): AggregatePipelineStage[] {
    const calls = mockAggregate.mock.calls as unknown as AggregatePipelineStage[][][];
    return calls[0][0];
}

describe('MessageRepository', () => {
    let repository: MessageRepository;

    beforeEach(() => {
        repository = new MessageRepository(mockMessageModel as unknown as Model<Message>);
        jest.clearAllMocks();
    });

    describe('countUnread', () => {
        it('afterId가 있으면 _id > afterId 조건으로 스레드 제외 카운트를 반환한다', async () => {
            const afterId = new Types.ObjectId();
            mockCountDocuments.mockResolvedValue(7);

            const result = await repository.countUnread(1, afterId);

            expect(mockCountDocuments).toHaveBeenCalledWith({
                channelId: 1,
                parentMessageId: null,
                _id: { $gt: afterId },
            });
            expect(result).toBe(7);
        });

        it('afterId가 null이면 _id 조건 없이 채널 전체를 카운트한다', async () => {
            mockCountDocuments.mockResolvedValue(20);

            const result = await repository.countUnread(1, null);

            expect(mockCountDocuments).toHaveBeenCalledWith({
                channelId: 1,
                parentMessageId: null,
            });
            expect(result).toBe(20);
        });

        it('메시지가 없으면 0을 반환한다', async () => {
            mockCountDocuments.mockResolvedValue(0);

            const result = await repository.countUnread(1, new Types.ObjectId());

            expect(result).toBe(0);
        });
    });

    describe('findMessages', () => {
        it('cursor 없이 aggregate를 실행하고 channelId 조건만 포함한다', async () => {
            mockAggregate.mockResolvedValue([]);

            await repository.findMessages(1);

            const pipeline = getAggregatePipeline();
            expect(pipeline[0].$match).toEqual({ channelId: 1 });
            expect(pipeline[1].$sort).toEqual({ _id: -1 });
            expect(pipeline[2].$limit).toBe(100);
        });

        it('before cursor가 있으면 _id 조건이 추가된다', async () => {
            const messageId = new Types.ObjectId().toString();
            mockAggregate.mockResolvedValue([]);

            await repository.findMessages(1, messageId);

            const pipeline = getAggregatePipeline();
            expect(pipeline[0].$match).toEqual({
                channelId: 1,
                _id: { $lt: new Types.ObjectId(messageId) },
            });
        });

        it('답글 원본 메시지를 mentionedMessage로 조회한다', async () => {
            mockAggregate.mockResolvedValue([]);

            await repository.findMessages(1);

            const pipeline = getAggregatePipeline();
            expect(pipeline[3].$lookup).toEqual(expect.objectContaining({
                from: 'messages',
                localField: 'parentMessageId',
                foreignField: '_id',
                as: 'mentionedMessage',
            }));
            expect(pipeline[4].$addFields).toEqual({
                mentionedMessage: { $arrayElemAt: ['$mentionedMessage', 0] },
            });
        });
    });

    describe('findFileAttachments', () => {
        it('첨부파일을 파일 단위로 반환한다', async () => {
            const createdAt = new Date('2026-05-12T02:03:04.000Z');
            mockAggregate.mockResolvedValue([
                {
                    _id: new Types.ObjectId('665f00000000000000000001'),
                    authorId: 42,
                    createdAt,
                    attachmentIndex: 0,
                    attachment: {
                        name: 'report.pdf',
                        url: 'http://localhost:9000/cowork-bucket/chat-files/1/42/report.pdf',
                        size: 2048,
                        mimeType: 'application/pdf',
                    },
                },
            ]);

            const result = await repository.findFileAttachments(1, undefined, 20);

            expect(result.items[0]).toMatchObject({
                messageId: '665f00000000000000000001',
                fileName: 'report.pdf',
                fileSize: 2048,
                fileUrl: 'http://localhost:9000/cowork-bucket/chat-files/1/42/report.pdf',
                mimeType: 'application/pdf',
                uploaderId: 42,
                uploadedAt: createdAt.toISOString(),
                attachmentIndex: 0,
            });
            expect(result.items[0].fileId).toBeDefined();
            expect(result.nextCursor).toBeNull();
        });

        it('before 커서를 사용해 다음 페이지를 조회한다', async () => {
            const cursorRow = {
                _id: new Types.ObjectId('665f00000000000000000002'),
                createdAt: new Date('2026-05-12T03:00:00.000Z'),
                attachmentIndex: 1,
            };
            const before = Buffer.from(JSON.stringify({
                uploadedAt: cursorRow.createdAt.toISOString(),
                messageId: cursorRow._id.toString(),
                attachmentIndex: cursorRow.attachmentIndex,
            })).toString('base64');
            mockAggregate.mockResolvedValue([]);

            await repository.findFileAttachments(1, before, 20);

            const pipeline = getAggregatePipeline();
            expect(pipeline[2].$match).toEqual({
                $or: [
                    { createdAt: { $lt: new Date(cursorRow.createdAt) } },
                    {
                        createdAt: new Date(cursorRow.createdAt),
                        _id: { $lt: new Types.ObjectId(cursorRow._id.toString()) },
                    },
                    {
                        createdAt: new Date(cursorRow.createdAt),
                        _id: new Types.ObjectId(cursorRow._id.toString()),
                        attachmentIndex: { $lt: 1 },
                    },
                ],
            });
        });
    });
});
