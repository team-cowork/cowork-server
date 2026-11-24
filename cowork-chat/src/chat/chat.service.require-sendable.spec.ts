import {ForbiddenException} from '@nestjs/common';
import {ChatService} from './chat.service.js';

const channelMessageReadAccess = {requirePermissions: jest.fn()};
const channelProjectionRepository = {findById: jest.fn()};
const dmParticipantRepository = {findByChannelId: jest.fn(), setHidden: jest.fn()};
const blockService = {isBlocked: jest.fn()};

describe('ChatService requireSendable', () => {
	const ctx = {channelId: 1, userId: 42};
	let service: ChatService;

	beforeEach(() => {
		jest.resetAllMocks();
		channelProjectionRepository.findById.mockResolvedValue({
			channelId: 1, teamId: null, projectId: null, type: 'DM',
		});
		dmParticipantRepository.findByChannelId.mockResolvedValue([{userId: 42}, {userId: 7}]);
		blockService.isBlocked.mockResolvedValue(false);
		service = Object.assign<ChatService, Record<string, unknown>>(new ChatService(), {
			channelMessageReadAccess, channelProjectionRepository, dmParticipantRepository, blockService,
		});
	});

	it('revealDm이 거짓이면 DM 차단은 검사하지만 숨긴 대화를 다시 노출하지 않는다', async () => {
		await expect(service.requireSendable(ctx, {attachmentCount: 0, revealDm: false})).resolves.toEqual({teamId: null, projectId: null});

		expect(blockService.isBlocked).toHaveBeenCalledWith(7, 42);
		expect(dmParticipantRepository.setHidden).not.toHaveBeenCalled();
	});

	it('revealDm이 참이면 양쪽의 숨긴 대화를 다시 노출한다', async () => {
		await service.requireSendable(ctx, {attachmentCount: 0, revealDm: true});

		expect(dmParticipantRepository.setHidden).toHaveBeenCalledWith(1, 42, false);
		expect(dmParticipantRepository.setHidden).toHaveBeenCalledWith(1, 7, false);
	});

	it('첨부가 있으면 file_attach 권한도 검사한다', async () => {
		await service.requireSendable(ctx, {attachmentCount: 1, revealDm: false});

		expect(channelMessageReadAccess.requirePermissions).toHaveBeenCalledWith(1, 42, ['message_send', 'file_attach']);
	});

	it('상대방이 차단했으면 노출 여부와 관계없이 거부한다', async () => {
		blockService.isBlocked.mockResolvedValue(true);

		await expect(service.requireSendable(ctx, {attachmentCount: 0, revealDm: false})).rejects.toThrow(ForbiddenException);
	});
});
