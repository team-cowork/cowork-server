import {Types} from 'mongoose';
import {ChatService, SystemMessageBroadcastSkippedError} from './chat.service.js';

const clientMessageId = 'system:voice:0123456789abcdef01234567:1';
const duplicateKeyError = Object.assign(new Error('E11000 duplicate key'), {code: 11_000});
const systemMessage = {
	_id: new Types.ObjectId(), teamId: 10, projectId: null, channelId: 1, authorId: 0, type: 'SYSTEM',
	content: '음성 통화가 시작되었습니다.', attachments: [], reactions: [], mentions: [],
	toObject() {
		return {...this};
	},
};

const messageRepository = {
	createSystemMessage: jest.fn(),
	findUnbroadcastedByClientMessageId: jest.fn(),
	markBroadcasted: jest.fn(),
};
const channelMessageReadAccess = {
	emitToReadableChannelUsers: jest.fn(),
	findReadableUsersByChannel: jest.fn(),
};
const channelProjectionRepository = {findByIdIncludingDeleted: jest.fn()};
const unreadCounterService = {incrementIfPresent: jest.fn()};
const chatGateway = {server: {}};

describe('ChatService system message delivery', () => {
	let service: ChatService;

	beforeEach(() => {
		jest.clearAllMocks();
		service = Object.assign<ChatService, Record<string, unknown>>(new ChatService(), {
			messageRepository,
			channelMessageReadAccess,
			channelProjectionRepository,
			unreadCounterService,
			chatGateway,
		});
		channelProjectionRepository.findByIdIncludingDeleted.mockResolvedValue({
			channelId: 1, teamId: 10, projectId: null, type: 'VOICE', deleted: false,
		});
	});

	describe('saveSystemMessage', () => {
		it('이미 저장했지만 아직 보내지 않은 메시지면 안읽음 수를 올리지 않고 그 메시지를 돌려준다', async () => {
			messageRepository.createSystemMessage.mockRejectedValue(duplicateKeyError);
			messageRepository.findUnbroadcastedByClientMessageId.mockResolvedValue(systemMessage);

			await expect(service.saveSystemMessage(10, 1, systemMessage.content, clientMessageId)).resolves.toBe(systemMessage);
			expect(messageRepository.findUnbroadcastedByClientMessageId).toHaveBeenCalledWith(clientMessageId);
			expect(unreadCounterService.incrementIfPresent).not.toHaveBeenCalled();
		});

		it('이미 보낸 메시지면 null을 돌려준다', async () => {
			messageRepository.createSystemMessage.mockRejectedValue(duplicateKeyError);
			messageRepository.findUnbroadcastedByClientMessageId.mockResolvedValue(null);

			await expect(service.saveSystemMessage(10, 1, systemMessage.content, clientMessageId)).resolves.toBeNull();
		});

		it('보내지 않은 메시지라도 채널이나 작성자가 다르면 돌려주지 않는다', async () => {
			messageRepository.createSystemMessage.mockRejectedValue(duplicateKeyError);
			messageRepository.findUnbroadcastedByClientMessageId.mockResolvedValueOnce({...systemMessage, channelId: 2});

			await expect(service.saveSystemMessage(10, 1, systemMessage.content, clientMessageId)).resolves.toBeNull();

			messageRepository.findUnbroadcastedByClientMessageId.mockResolvedValueOnce({...systemMessage, authorId: 7});

			await expect(service.saveSystemMessage(10, 1, systemMessage.content, clientMessageId)).resolves.toBeNull();
		});
	});

	describe('broadcastSystemMessage', () => {
		it('보낸 뒤 broadcastedAt을 기록한다', async () => {
			channelMessageReadAccess.emitToReadableChannelUsers.mockResolvedValue(true);
			messageRepository.markBroadcasted.mockResolvedValue(undefined);

			await service.broadcastSystemMessage(systemMessage as never);

			expect(channelMessageReadAccess.emitToReadableChannelUsers).toHaveBeenCalledWith(
				chatGateway.server, 1, expect.objectContaining({event: 'message'}),
			);
			expect(messageRepository.markBroadcasted).toHaveBeenCalledWith(systemMessage._id);
		});

		it('보내지 못하면 재전달을 위해 던지고 broadcastedAt을 기록하지 않는다', async () => {
			channelMessageReadAccess.emitToReadableChannelUsers.mockResolvedValue(false);

			await expect(service.broadcastSystemMessage(systemMessage as never)).rejects.toBeInstanceOf(SystemMessageBroadcastSkippedError);
			expect(messageRepository.markBroadcasted).not.toHaveBeenCalled();
		});

		it('broadcastedAt 기록이 실패해도 던지지 않는다', async () => {
			channelMessageReadAccess.emitToReadableChannelUsers.mockResolvedValue(true);
			messageRepository.markBroadcasted.mockRejectedValue(new Error('connection reset'));

			await expect(service.broadcastSystemMessage(systemMessage as never)).resolves.toBeUndefined();
		});
	});
});
