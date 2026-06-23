import { ConfigService } from '@nestjs/config';
import { ChatMessageConsumer } from './chat-message.consumer';
import { ChatMessageEvent } from './event/chat-message.event';
import { ElasticsearchService } from '../../search/elasticsearch.service';
import { MessageRepository } from '../repository/message.repository';
import { ChannelMemberRepository } from '../repository/channel-member.repository';

type ConsumerWithPrivates = Omit<ChatMessageConsumer, 'handleMessageEvent'> & {
    handleMessageEvent: (event: ChatMessageEvent) => Promise<void>;
};

const mockMessageRepository = {
    createMessage: jest.fn(),
};

const mockConfigService = {
    get: jest.fn().mockReturnValue('localhost:9092'),
};

const mockChannelMemberRepository = {
    updateLastRead: jest.fn().mockResolvedValue(undefined),
};

const mockElasticsearchService = {
    indexMessage: jest.fn().mockResolvedValue(undefined),
};

describe('ChatMessageConsumer', () => {
    let consumer: ChatMessageConsumer;

    beforeEach(() => {
        consumer = new ChatMessageConsumer(
            mockMessageRepository as unknown as MessageRepository,
            mockChannelMemberRepository as unknown as ChannelMemberRepository,
            mockConfigService as unknown as ConfigService,
            mockElasticsearchService as unknown as ElasticsearchService,
        );
        jest.clearAllMocks();
    });

    it('clientMessageId가 없으면 null 대신 undefined로 저장한다', async () => {
        const savedMessage = { _id: 'msg-id-1', toObject: jest.fn().mockReturnValue({}) };
        mockMessageRepository.createMessage.mockResolvedValue(savedMessage);

        await (consumer as unknown as ConsumerWithPrivates).handleMessageEvent({
            eventType: 'MESSAGE_SENT',
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

    it('메시지 저장 후 작성자의 lastReadMessageId를 자동 업데이트한다', async () => {
        const savedMessage = { _id: 'msg-id-42', toObject: jest.fn().mockReturnValue({}) };
        mockMessageRepository.createMessage.mockResolvedValue(savedMessage);

        await (consumer as unknown as ConsumerWithPrivates).handleMessageEvent({
            teamId: 10,
            eventType: 'MESSAGE_SENT',
            projectId: null,
            channelId: 5,
            authorId: 42,
            authorRole: 'MEMBER',
            content: '자동 읽음 테스트',
            type: 'TEXT',
            attachments: [],
            occurredAt: new Date().toISOString(),
        });

        await new Promise((r) => setImmediate(r));
        expect(mockChannelMemberRepository.updateLastRead).toHaveBeenCalledWith(5, 42, 'msg-id-42');
    });
});
