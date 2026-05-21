import { ChatMessageConsumer } from './chat-message.consumer';

const mockMessageRepository = {
    createMessage: jest.fn(),
};

const mockConfigService = {
    get: jest.fn().mockReturnValue('localhost:9092'),
};

const mockElasticsearchService = {
    indexMessage: jest.fn().mockResolvedValue(undefined),
};

describe('ChatMessageConsumer', () => {
    let consumer: ChatMessageConsumer;

    beforeEach(() => {
        consumer = new ChatMessageConsumer(mockMessageRepository as any, mockConfigService as any, mockElasticsearchService as any);
        jest.clearAllMocks();
    });

    it('clientMessageId가 없으면 null 대신 undefined로 저장한다', async () => {
        mockMessageRepository.createMessage.mockResolvedValue({ toObject: jest.fn().mockReturnValue({}) });

        await (consumer as any).handleMessageEvent({
            teamId: 10,
            projectId: null,
            channelId: 1,
            authorId: 42,
            authorRole: 'MEMBER',
            content: 'hello',
            type: 'TEXT',
            attachments: [],
            clientMessageId: undefined,
            occurredAt: new Date().toISOString(),
        });

        expect(mockMessageRepository.createMessage).toHaveBeenCalledWith(
            expect.objectContaining({
                clientMessageId: undefined,
            }),
        );
    });
});
