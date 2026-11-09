import {Types} from 'mongoose';
import {ChatMessageProcessor} from './chat-message.processor.js';

const event = {
	contractVersion: 2 as const, eventType: 'MESSAGE_SENT' as const, teamId: 1, projectId: null,
	channelId: 2, authorId: 3, authorRole: 'MEMBER', content: 'hello', type: 'TEXT' as const,
	attachments: [], clientMessageId: 'client-1', occurredAt: '2026-09-03T00:00:00.000Z',
};

function storedMessage(overrides: Record<string, unknown> = {}) {
	const fields = {
		_id: new Types.ObjectId(), channelId: 2, authorId: 3, content: 'hello', type: 'TEXT',
		attachments: [], reactions: [], mentions: [], isEdited: false, isPinned: false, clientMessageId: 'client-1',
		createdAt: new Date(), updatedAt: new Date(),
		...overrides,
	};
	return {...fields, toObject: () => fields};
}

describe('ChatMessageProcessor', () => {
	const messages = {createMessage: jest.fn(), findByClientMessageId: jest.fn()};
	const channelRead = {advance: jest.fn()};
	const readAccess = {emitToReadableChannelUsers: jest.fn()};
	const scope = {validate: jest.fn()};
	let processor: ChatMessageProcessor;

	beforeEach(() => {
		jest.clearAllMocks();
		channelRead.advance.mockResolvedValue(null);
		processor = new ChatMessageProcessor(messages as never, channelRead as never, readAccess as never, scope as never);
		processor.setSocketServer({to: jest.fn()} as never);
	});

	describe('process', () => {
		it('저장 뒤 브로드캐스트가 실패해 재전달되면 기존 메시지를 다시 브로드캐스트한다', async () => {
			const saved = storedMessage();
			messages.createMessage.mockRejectedValue(Object.assign(new Error('dup'), {code: 11_000}));
			messages.findByClientMessageId.mockResolvedValue(saved);

			await processor.process(event);

			expect(readAccess.emitToReadableChannelUsers).toHaveBeenCalledWith(
				expect.anything(), 2, expect.objectContaining({event: 'message'}),
			);
			expect(channelRead.advance).toHaveBeenCalledWith(2, 3, saved._id);
		});

		it('다른 작성자의 메시지와 키가 겹치면 브로드캐스트하지 않는다', async () => {
			messages.createMessage.mockRejectedValue(Object.assign(new Error('dup'), {code: 11_000}));
			messages.findByClientMessageId.mockResolvedValue(storedMessage({authorId: 99}));

			await processor.process(event);

			expect(readAccess.emitToReadableChannelUsers).not.toHaveBeenCalled();
		});

		it('중복이 아닌 저장 오류는 재전달되도록 다시 던진다', async () => {
			messages.createMessage.mockRejectedValue(new Error('mongo down'));

			await expect(processor.process(event)).rejects.toThrow('mongo down');
			expect(readAccess.emitToReadableChannelUsers).not.toHaveBeenCalled();
		});
	});
});
