import {Types} from 'mongoose';
import {ChatMessageBroadcastSkippedError, ChatMessageProcessor} from './chat-message.processor.js';

const event = {
	contractVersion: 3 as const, eventType: 'MESSAGE_SENT' as const, teamId: 1, projectId: null,
	channelId: 2, authorId: 3, content: 'hello', type: 'TEXT' as const,
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
	const messages = {createMessage: jest.fn(), findUnbroadcastedByClientMessageId: jest.fn(), markBroadcasted: jest.fn()};
	const channelRead = {advance: jest.fn()};
	const readAccess = {emitToReadableChannelUsers: jest.fn()};
	const scope = {validate: jest.fn()};
	// @todo 2026-11-18 KST 추가한 임시 지연 측정 코드다. 2026-12-02까지 제거한다.
	const latency = {record: jest.fn()};
	let processor: ChatMessageProcessor;

	beforeEach(() => {
		jest.clearAllMocks();
		channelRead.advance.mockResolvedValue(null);
		messages.markBroadcasted.mockResolvedValue(undefined);
		readAccess.emitToReadableChannelUsers.mockResolvedValue(true);
		// @todo 2026-11-18 KST 추가한 임시 지연 측정 코드다. 2026-12-02까지 제거한다. Object.assign을 지우고 생성자 호출만 남긴다.
		processor = Object.assign(
			new ChatMessageProcessor(messages as never, channelRead as never, readAccess as never, scope as never),
			{latencyMetrics: latency},
		);
		processor.setSocketServer({to: jest.fn()} as never);
	});

	describe('process', () => {
		it('저장 뒤 브로드캐스트가 실패해 재전달되면 기존 메시지를 다시 브로드캐스트한다', async () => {
			const saved = storedMessage();
			messages.createMessage.mockRejectedValue(Object.assign(new Error('dup'), {code: 11_000}));
			messages.findUnbroadcastedByClientMessageId.mockResolvedValue(saved);

			await processor.process(event);

			expect(readAccess.emitToReadableChannelUsers).toHaveBeenCalledWith(
				expect.anything(), 2, expect.objectContaining({event: 'message'}),
			);
			expect(channelRead.advance).toHaveBeenCalledWith(2, 3, saved._id);
		});

		it('다른 작성자의 메시지와 키가 겹치면 브로드캐스트하지 않는다', async () => {
			messages.createMessage.mockRejectedValue(Object.assign(new Error('dup'), {code: 11_000}));
			messages.findUnbroadcastedByClientMessageId.mockResolvedValue(storedMessage({authorId: 99}));

			await processor.process(event);

			expect(readAccess.emitToReadableChannelUsers).not.toHaveBeenCalled();
		});

		it('이미 브로드캐스트한 메시지의 키가 다시 오면 보내지 않는다', async () => {
			messages.createMessage.mockRejectedValue(Object.assign(new Error('dup'), {code: 11_000}));
			messages.findUnbroadcastedByClientMessageId.mockResolvedValue(null);

			await processor.process(event);

			expect(readAccess.emitToReadableChannelUsers).not.toHaveBeenCalled();
		});

		it('브로드캐스트를 마치면 전송 완료를 기록한다', async () => {
			const saved = storedMessage();
			messages.createMessage.mockResolvedValue(saved);

			await processor.process(event);

			expect(messages.markBroadcasted).toHaveBeenCalledWith(saved._id);
		});

		it('보내지 못하면 전송 완료를 기록하지 않고 재전달되도록 던진다', async () => {
			messages.createMessage.mockResolvedValue(storedMessage());
			readAccess.emitToReadableChannelUsers.mockResolvedValue(false);

			await expect(processor.process(event)).rejects.toBeInstanceOf(ChatMessageBroadcastSkippedError);
			expect(messages.markBroadcasted).not.toHaveBeenCalled();
		});

		it('재전달로 다시 보내지 못해도 전송 완료를 기록하지 않는다', async () => {
			messages.createMessage.mockRejectedValue(Object.assign(new Error('dup'), {code: 11_000}));
			messages.findUnbroadcastedByClientMessageId.mockResolvedValue(storedMessage());
			readAccess.emitToReadableChannelUsers.mockResolvedValue(false);

			await expect(processor.process(event)).rejects.toBeInstanceOf(ChatMessageBroadcastSkippedError);
			expect(messages.markBroadcasted).not.toHaveBeenCalled();
		});

		it('clientMessageId가 없으면 보내지 못해도 다시 저장되지 않도록 던지지 않는다', async () => {
			messages.createMessage.mockResolvedValue(storedMessage({clientMessageId: undefined}));
			readAccess.emitToReadableChannelUsers.mockResolvedValue(false);

			await processor.process({...event, clientMessageId: undefined});

			expect(messages.markBroadcasted).not.toHaveBeenCalled();
		});

		// @todo 2026-11-18 KST 추가한 임시 지연 측정 코드다. 2026-12-02까지 제거한다. 아래 세 테스트를 지운다.
		it('consumer가 받은 시각을 주면 전송을 마친 뒤 구간별 지연을 모두 기록한다', async () => {
			messages.createMessage.mockResolvedValue(storedMessage());

			await processor.process(event, Date.now());

			expect(latency.record.mock.calls.map(([stage]) => stage as string)).toEqual(['queue', 'validate_save', 'emit', 'total']);
		});

		it('보내지 못하면 emit과 total 지연을 기록하지 않는다', async () => {
			messages.createMessage.mockResolvedValue(storedMessage());
			readAccess.emitToReadableChannelUsers.mockResolvedValue(false);

			await expect(processor.process(event, Date.now())).rejects.toBeInstanceOf(ChatMessageBroadcastSkippedError);

			expect(latency.record.mock.calls.map(([stage]) => stage as string)).toEqual(['queue', 'validate_save']);
		});

		it('quarantine 재처리처럼 받은 시각이 없으면 지연을 기록하지 않는다', async () => {
			messages.createMessage.mockResolvedValue(storedMessage());

			await processor.process(event);

			expect(latency.record).not.toHaveBeenCalled();
		});

		it('중복이 아닌 저장 오류는 재전달되도록 다시 던진다', async () => {
			messages.createMessage.mockRejectedValue(new Error('mongo down'));

			await expect(processor.process(event)).rejects.toThrow('mongo down');
			expect(readAccess.emitToReadableChannelUsers).not.toHaveBeenCalled();
		});
	});
});
