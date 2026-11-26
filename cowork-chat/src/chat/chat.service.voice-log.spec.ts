import {NotFoundException} from '@nestjs/common';
import {Types} from 'mongoose';
import {ChatService} from './chat.service.js';

const joinedAt = new Date(1000);
const messageId = new Types.ObjectId().toHexString();
const systemMessage = {
	_id: new Types.ObjectId(messageId), channelId: 30, authorId: 0, createdAt: new Date(2000), type: 'SYSTEM', content: '음성 통화가 시작되었습니다.',
};

const messageRepository = {
	findMessages: jest.fn(),
	findMessagesAround: jest.fn(),
	findByIdAndChannelId: jest.fn(),
	findPinnedMessages: jest.fn(),
	addReaction: jest.fn(),
	countUnreadForChannels: jest.fn(),
	createSystemMessage: jest.fn(),
};
const channelMessageReadAccess = {
	requireCanRead: jest.fn(),
	requirePermission: jest.fn(),
	requirePermissions: jest.fn(),
	requireTeamJoinedAt: jest.fn().mockResolvedValue(joinedAt),
	requireReadableTeamChannelIds: jest.fn(),
	requireVoiceLogHiddenChannelIds: jest.fn(),
	findReadableUsersByChannel: jest.fn(),
};
const readStateRepository = {findForChannels: jest.fn()};
const channelProjectionRepository = {findByIds: jest.fn(), findById: jest.fn(), findByIdIncludingDeleted: jest.fn()};
const unreadCounterService = {getMany: jest.fn(), setMany: jest.fn(), incrementIfPresent: jest.fn()};

describe('ChatService voice channel logs', () => {
	let service: ChatService;

	beforeEach(() => {
		jest.clearAllMocks();
		service = Object.assign<ChatService, Record<string, unknown>>(new ChatService(), {
			messageRepository,
			channelMessageReadAccess,
			readStateRepository,
			channelProjectionRepository,
			unreadCounterService,
		});
		channelMessageReadAccess.requireVoiceLogHiddenChannelIds.mockResolvedValue(new Set([30]));
		unreadCounterService.getMany.mockResolvedValue(null);
		channelProjectionRepository.findByIds.mockImplementation(async (ids: number[]) =>
			ids.map(channelId => ({channelId, archived: false})));
	});

	it('로그를 볼 수 없으면 SYSTEM 메시지를 빼고 조회한다', async () => {
		messageRepository.findMessages.mockResolvedValue([]);

		await service.getMessages({channelId: 30, userId: 42});

		expect(channelMessageReadAccess.requireVoiceLogHiddenChannelIds).toHaveBeenCalledWith(42, [30]);
		expect(messageRepository.findMessages).toHaveBeenCalledWith(30, undefined, {
			parentMessageId: undefined, threadId: undefined, shouldExcludeSystem: true,
		}, undefined);
	});

	it('로그를 볼 수 없으면 SYSTEM 메시지를 기준으로 앞뒤를 조회할 수 없다', async () => {
		messageRepository.findByIdAndChannelId.mockResolvedValue(systemMessage);

		await expect(service.getMessages({channelId: 30, userId: 42}, {around: messageId})).rejects.toThrow(NotFoundException);
		expect(messageRepository.findMessagesAround).not.toHaveBeenCalled();
	});

	it('로그를 볼 수 없으면 고정 메시지에서도 SYSTEM 메시지를 뺀다', async () => {
		messageRepository.findPinnedMessages.mockResolvedValue([]);

		await service.getPinnedMessages({channelId: 30, userId: 42});

		expect(messageRepository.findPinnedMessages).toHaveBeenCalledWith(30, {shouldExcludeSystem: true});
	});

	it('로그를 볼 수 없으면 SYSTEM 메시지에 답장할 수 없다', async () => {
		channelProjectionRepository.findById.mockResolvedValue({
			channelId: 30, teamId: 10, projectId: null, type: 'VOICE', archived: false,
		});
		messageRepository.findByIdAndChannelId.mockResolvedValue(systemMessage);

		await expect(service.sendMessage({channelId: 30, userId: 42}, {content: '답장', parentMessageId: messageId}))
			.rejects.toThrow(NotFoundException);
	});

	it('로그를 볼 수 없으면 SYSTEM 메시지에 반응할 수 없다', async () => {
		messageRepository.findByIdAndChannelId.mockResolvedValue(systemMessage);

		await expect(service.addReaction({channelId: 30, userId: 42}, messageId, '👍')).rejects.toThrow(NotFoundException);
		expect(messageRepository.addReaction).not.toHaveBeenCalled();
	});

	it('로그를 볼 수 없는 채널은 팀 안읽음 수에서 SYSTEM 메시지를 빼고 센다', async () => {
		channelMessageReadAccess.requireReadableTeamChannelIds.mockResolvedValue([1, 30]);
		readStateRepository.findForChannels.mockResolvedValue([
			{channelId: 1, lastReadMessageId: null},
			{channelId: 30, lastReadMessageId: null},
		]);
		messageRepository.countUnreadForChannels.mockResolvedValue(new Map([[1, 2]]));

		await expect(service.getTeamUnread(10, 42)).resolves.toEqual([
			{channelId: 1, unreadCount: 2},
			{channelId: 30, unreadCount: 0},
		]);
		expect(messageRepository.countUnreadForChannels).toHaveBeenCalledWith([
			{
				channelId: 1, lastReadMessageId: null, joinedAt, shouldExcludeSystem: false,
			},
			{
				channelId: 30, lastReadMessageId: null, joinedAt, shouldExcludeSystem: true,
			},
		]);
	});

	it('SYSTEM 메시지의 안읽음 수는 로그를 볼 수 있는 사용자에게만 올린다', async () => {
		channelProjectionRepository.findByIdIncludingDeleted.mockResolvedValue({
			channelId: 30, teamId: 10, projectId: null, type: 'VOICE', deleted: false,
		});
		messageRepository.createSystemMessage.mockResolvedValue(systemMessage);
		channelMessageReadAccess.findReadableUsersByChannel.mockResolvedValue(new Map([[30, [7]]]));

		await service.saveSystemMessage(10, 30, '음성 통화가 시작되었습니다.', 'system:voice:a:30');

		expect(channelMessageReadAccess.findReadableUsersByChannel).toHaveBeenCalledWith([30], {voiceLogs: true});
		expect(unreadCounterService.incrementIfPresent).toHaveBeenCalledWith(30, [7], systemMessage.createdAt);
	});
});
