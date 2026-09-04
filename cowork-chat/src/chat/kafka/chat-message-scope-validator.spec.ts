import { ChatMessageScopeError, ChatMessageScopeValidator } from './chat-message-scope-validator';

const event = {
    contractVersion: 1 as const, eventType: 'MESSAGE_SENT' as const, teamId: 1, projectId: null,
    channelId: 2, authorId: 3, authorRole: 'MEMBER', content: 'hello', type: 'TEXT' as const,
    attachments: [], occurredAt: '2026-09-03T00:00:00.000Z',
};

describe('ChatMessageScopeValidator', () => {
    const channels = { findById: jest.fn() };
    const messages = { findByIdAndChannelId: jest.fn() };
    let validator: ChatMessageScopeValidator;

    beforeEach(() => {
        jest.clearAllMocks();
        validator = new ChatMessageScopeValidator(channels as never, messages as never);
    });

    it('채널 team/project 불일치를 SCOPE_ERROR로 변환한다', async () => {
        channels.findById.mockResolvedValue({ teamId: 9, projectId: null });

        await expect(validator.validate(event)).rejects.toMatchObject<Partial<ChatMessageScopeError>>({
            reasonCode: 'CHANNEL_SCOPE_MISMATCH',
        });
    });

    it('부모 메시지가 같은 채널에 없으면 SCOPE_ERROR로 변환한다', async () => {
        const withParent = { ...event, parentMessageId: '507f1f77bcf86cd799439011' };
        channels.findById.mockResolvedValue({ teamId: 1, projectId: null });
        messages.findByIdAndChannelId.mockResolvedValue(null);

        await expect(validator.validate(withParent)).rejects.toMatchObject<Partial<ChatMessageScopeError>>({
            reasonCode: 'PARENT_NOT_FOUND',
        });
    });

    it('repository 오류는 scope error로 바꾸지 않고 전파한다', async () => {
        channels.findById.mockRejectedValue(new Error('Mongo timeout'));

        await expect(validator.validate(event)).rejects.toThrow('Mongo timeout');
    });
});
