import {ForbiddenException} from '@nestjs/common';
import {Types} from 'mongoose';
import {ChatService} from './chat.service.js';

const chatMessageProducer = {sendMessage: jest.fn()};
const slowmodeService = {reserve: jest.fn(), release: jest.fn()};
const tagSpamService = {reserve: jest.fn(), release: jest.fn()};
const objectStorageService = {claimAttachments: jest.fn()};

describe('ChatService sendMessage from an agent', () => {
	const ctx = {channelId: 1, userId: 42};
	let service: ChatService;
	let requireSendable: jest.SpyInstance;

	beforeEach(() => {
		jest.resetAllMocks();
		objectStorageService.claimAttachments.mockResolvedValue([]);
		service = Object.assign<ChatService, Record<string, unknown>>(new ChatService(), {
			chatMessageProducer, slowmodeService, tagSpamService, objectStorageService,
		});
		requireSendable = jest.spyOn(service, 'requireSendable').mockResolvedValue({teamId: 100, projectId: null});
	});

	it('threadId가 없으면 채널에 직접 게시하지 않는다', async () => {
		await expect(service.sendMessage(ctx, {content: 'hi'}, true)).rejects.toThrow(ForbiddenException);
		await expect(service.sendMessage(ctx, {content: 'hi', parentMessageId: new Types.ObjectId().toString()}, true))
			.rejects.toThrow(ForbiddenException);

		expect(requireSendable).not.toHaveBeenCalled();
		expect(chatMessageProducer.sendMessage).not.toHaveBeenCalled();
	});

	it('threadId가 있으면 스레드에 게시한다', async () => {
		await service.sendMessage(ctx, {content: 'hi', threadId: 7}, true);

		expect(chatMessageProducer.sendMessage).toHaveBeenCalledWith(1, expect.objectContaining({threadId: 7}), 42);
	});

	it('에이전트 요청이 아니면 threadId 없이 채널에 게시한다', async () => {
		await service.sendMessage(ctx, {content: 'hi'});

		expect(chatMessageProducer.sendMessage).toHaveBeenCalledWith(1, expect.objectContaining({threadId: undefined}), 42);
	});
});
