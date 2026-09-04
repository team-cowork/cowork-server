import { Types } from 'mongoose';
import { ChatMessageProcessor } from './chat-message.processor';

const event = {
    contractVersion: 1 as const, eventType: 'MESSAGE_SENT' as const, teamId: 1, projectId: null,
    channelId: 2, authorId: 3, authorRole: 'MEMBER', content: 'hello <@4>', type: 'TEXT' as const,
    attachments: [], occurredAt: '2026-09-03T00:00:00.000Z',
};

describe('ChatMessageProcessor', () => {
    it('검증된 정상 record를 저장한 뒤 읽기 권한 대상에게 브로드캐스트한다', async () => {
        const messageId = new Types.ObjectId();
        const messages = { createMessage: jest.fn().mockResolvedValue({ _id: messageId, toObject: jest.fn().mockReturnValue({ id: 'message' }) }) };
        const members = { updateLastRead: jest.fn().mockResolvedValue(undefined) };
        const search = { indexMessage: jest.fn() };
        const access = { emitToReadableChannelUsers: jest.fn().mockResolvedValue(undefined) };
        const scopes = { validate: jest.fn().mockResolvedValue(undefined) };
        const processor = new ChatMessageProcessor(messages as never, members as never, search as never, access as never, scopes as never);
        const io = {} as never;
        processor.setSocketServer(io);

        await processor.process(event);
        await new Promise((resolve) => setImmediate(resolve));

        expect(messages.createMessage).toHaveBeenCalledWith(expect.objectContaining({ channelId: 2, mentions: [4] }));
        expect(access.emitToReadableChannelUsers).toHaveBeenCalledWith(io, 2, 'message', { id: 'message' });
        expect(members.updateLastRead).toHaveBeenCalledWith(2, 3, messageId);
    });

    it('duplicate key는 기존처럼 정상 처리로 간주한다', async () => {
        const messages = { createMessage: jest.fn().mockRejectedValue({ code: 11000 }) };
        const processor = new ChatMessageProcessor(messages as never, {} as never, {} as never, {} as never, { validate: jest.fn() } as never);

        await expect(processor.process(event)).resolves.toBeUndefined();
    });
});
