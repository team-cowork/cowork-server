import {ConflictException, ForbiddenException} from '@nestjs/common';
import {Types} from 'mongoose';
import {type ClaimedScheduledMessage} from './scheduled-message.repository.js';
import {ScheduledMessageDispatcher} from './scheduled-message.dispatcher.js';
import {DISPATCH_TIMEOUT_MS} from './scheduled-message.policy.js';

const NOW = new Date('2026-11-23T00:00:00.000Z');
const attachment = {
	objectKey: 'chat-files/1/42/a.png', name: 'a.png', size: 1, mimeType: 'image/png', spoiler: false,
};

function scheduled(overrides: Partial<ClaimedScheduledMessage> = {}): ClaimedScheduledMessage {
	return {
		_id: new Types.ObjectId(),
		authorId: 42,
		channelId: 1,
		teamId: 10,
		content: '공지',
		type: 'TEXT',
		attachments: [],
		clientMessageId: 'scheduled:client-1',
		scheduledAt: NOW,
		status: 'DISPATCHING',
		nextAttemptAt: NOW,
		claimId: 'claim-1',
		attempts: 0,
		lastError: null,
		failureReason: null,
		failedAt: null,
		finalizedAt: null,
		expiresAt: null,
		createdAt: NOW,
		updatedAt: NOW,
		...overrides,
	};
}

describe('ScheduledMessageDispatcher', () => {
	const repository = {
		claimDue: jest.fn(),
		claimUnfinalized: jest.fn(),
		markSent: jest.fn(),
		markRetry: jest.fn(),
		markFailed: jest.fn(),
		markFinalized: jest.fn(),
	};
	const chatService = {requireSendable: jest.fn()};
	const channelProjectionRepository = {findById: jest.fn()};
	const readAccess = {requirePermission: jest.fn()};
	const slowmodeService = {reserve: jest.fn(), release: jest.fn()};
	const tagSpamService = {reserve: jest.fn(), release: jest.fn()};
	const chatMessageProducer = {sendMessage: jest.fn()};
	const notificationProducer = {send: jest.fn()};
	const attachmentDeletions = {record: jest.fn()};
	const objectStorageService = {releaseUploads: jest.fn()};
	const projectionReadiness = {isReady: jest.fn()};
	let dispatcher: ScheduledMessageDispatcher;

	beforeEach(() => {
		jest.resetAllMocks();
		repository.claimDue.mockResolvedValue([]);
		repository.claimUnfinalized.mockResolvedValue([]);
		repository.markFailed.mockResolvedValue(true);
		channelProjectionRepository.findById.mockResolvedValue({channelId: 1});
		chatService.requireSendable.mockResolvedValue({teamId: 10, projectId: null});
		projectionReadiness.isReady.mockReturnValue(true);
		dispatcher = new ScheduledMessageDispatcher();
		Object.assign(dispatcher, {
			repository,
			chatService,
			channelProjectionRepository,
			readAccess,
			slowmodeService,
			tagSpamService,
			chatMessageProducer,
			notificationProducer,
			attachmentDeletions,
			objectStorageService,
			projectionReadiness,
		});
	});

	describe('발송', () => {
		it('발송 시각이 된 예약을 저장한 첨부와 고정 clientMessageId로 발행하고 SENT로 바꾼다', async () => {
			const message = scheduled({attachments: [attachment]});
			repository.claimDue.mockResolvedValueOnce([message]);

			await dispatcher.runCycle(NOW);

			expect(readAccess.requirePermission).toHaveBeenCalledWith(1, 42, 'message_send');
			expect(readAccess.requirePermission).toHaveBeenCalledWith(1, 42, 'file_attach');
			expect(chatService.requireSendable).toHaveBeenCalledWith({channelId: 1, userId: 42}, {attachmentCount: 1, revealDm: true});
			expect(chatMessageProducer.sendMessage).toHaveBeenCalledWith(1, {
				teamId: 10, projectId: null, content: '공지', type: 'TEXT', attachments: [attachment], clientMessageId: 'scheduled:client-1',
			}, 42);
			expect(repository.markSent).toHaveBeenCalledWith(message._id, 'claim-1', expect.any(Date));
		});

		it('배치가 가득 차면 같은 주기에서 다음 배치를 이어 꺼낸다', async () => {
			const fullBatch = Array.from({length: 20}, (_, index) => scheduled({clientMessageId: `scheduled:${index}`}));
			repository.claimDue.mockResolvedValueOnce(fullBatch).mockResolvedValueOnce([scheduled()]);

			await dispatcher.runCycle(NOW);

			expect(repository.claimDue).toHaveBeenCalledTimes(2);
			expect(chatMessageProducer.sendMessage).toHaveBeenCalledTimes(21);
		});

		it('발송 시각에서 24시간이 지난 예약은 검사하거나 발행하지 않고 DISPATCH_TIMEOUT으로 실패시킨다', async () => {
			const message = scheduled({scheduledAt: new Date(NOW.getTime() - DISPATCH_TIMEOUT_MS)});
			repository.claimDue.mockResolvedValueOnce([message]);

			await dispatcher.runCycle(NOW);

			expect(readAccess.requirePermission).not.toHaveBeenCalled();
			expect(chatMessageProducer.sendMessage).not.toHaveBeenCalled();
			expect(repository.markFailed).toHaveBeenCalledWith(message, 'DISPATCH_TIMEOUT', expect.any(Date));
		});

		it('삭제된 채널은 권한 검사 전에 CHANNEL_NOT_FOUND로 실패시킨다', async () => {
			const message = scheduled();
			repository.claimDue.mockResolvedValueOnce([message]);
			channelProjectionRepository.findById.mockResolvedValue(null);

			await dispatcher.runCycle(NOW);

			expect(readAccess.requirePermission).not.toHaveBeenCalled();
			expect(repository.markFailed).toHaveBeenCalledWith(message, 'CHANNEL_NOT_FOUND', expect.any(Date));
		});

		it('첨부 권한을 잃었으면 보내지 않고 FILE_ATTACH_DENIED로 실패시킨다', async () => {
			const message = scheduled({attachments: [attachment]});
			repository.claimDue.mockResolvedValueOnce([message]);
			readAccess.requirePermission.mockImplementation(async (_channelId: number, _userId: number, permission: string) => {
				if (permission === 'file_attach') {
					throw new ForbiddenException();
				}
			});

			await dispatcher.runCycle(NOW);

			expect(chatMessageProducer.sendMessage).not.toHaveBeenCalled();
			expect(repository.markFailed).toHaveBeenCalledWith(message, 'FILE_ATTACH_DENIED', expect.any(Date));
			expect(repository.markSent).not.toHaveBeenCalled();
		});

		it('채널이 보관되었으면 CHANNEL_ARCHIVED로 실패시킨다', async () => {
			repository.claimDue.mockResolvedValueOnce([scheduled()]);
			chatService.requireSendable.mockRejectedValue(new ConflictException());

			await dispatcher.runCycle(NOW);

			expect(repository.markFailed).toHaveBeenCalledWith(expect.anything(), 'CHANNEL_ARCHIVED', expect.any(Date));
		});

		it('발행이 일시적으로 실패하면 예약을 되돌리고 전송 간격과 멘션 예약을 푼다', async () => {
			const message = scheduled();
			repository.claimDue.mockResolvedValueOnce([message]);
			slowmodeService.reserve.mockResolvedValue('slowmode');
			tagSpamService.reserve.mockResolvedValue('tag-spam');
			chatMessageProducer.sendMessage.mockRejectedValue(new Error('kafka unavailable'));

			await dispatcher.runCycle(NOW);

			expect(slowmodeService.release).toHaveBeenCalledWith('slowmode');
			expect(tagSpamService.release).toHaveBeenCalledWith('tag-spam');
			expect(repository.markRetry).toHaveBeenCalledWith(message._id, 'claim-1', expect.any(Date), 'kafka unavailable');
			expect(repository.markFailed).not.toHaveBeenCalled();
		});

		it('권한 projection이 준비되지 않았으면 예약을 점유하지 않는다', async () => {
			projectionReadiness.isReady.mockReturnValue(false);

			await dispatcher.runCycle(NOW);

			expect(repository.claimDue).not.toHaveBeenCalled();
		});
	});

	describe('후처리', () => {
		it('실패한 예약은 첨부를 회수 대상으로 넘기고 작성자에게 알린 뒤 30일 보관으로 끝낸다', async () => {
			const message = scheduled({
				status: 'FAILED', failureReason: 'DM_BLOCKED', failedAt: NOW, teamId: null, attachments: [attachment],
			});
			repository.claimUnfinalized.mockResolvedValueOnce([message]);

			await dispatcher.runCycle(NOW);

			expect(attachmentDeletions.record).toHaveBeenCalledWith({
				messageId: message._id.toHexString(), channelId: 1, objectKeys: [attachment.objectKey],
			});
			expect(objectStorageService.releaseUploads).toHaveBeenCalledWith([attachment.objectKey]);
			expect(notificationProducer.send).toHaveBeenCalledWith({
				eventId: `scheduled-message-failed:${message._id.toHexString()}`,
				type: 'SCHEDULED_MESSAGE_FAILED',
				targetUserIds: [],
				forcedUserIds: [42],
				data: {
					channelId: 1, teamId: null, authorId: 42, content: '공지', reason: 'DM_BLOCKED', occurredAt: NOW.toISOString(),
				},
			});
			const [, finalizedAt, expiresAt] = repository.markFinalized.mock.calls[0] as [unknown, Date, Date];
			expect(expiresAt.getTime() - finalizedAt.getTime()).toBe(30 * 24 * 60 * 60 * 1000);
		});

		it('취소한 예약은 알리지 않고 첨부만 회수 대상으로 넘긴 뒤 바로 지우게 한다', async () => {
			const message = scheduled({status: 'CANCELED', attachments: [attachment]});
			repository.claimUnfinalized.mockResolvedValueOnce([message]);

			await dispatcher.runCycle(NOW);

			expect(attachmentDeletions.record).toHaveBeenCalled();
			expect(notificationProducer.send).not.toHaveBeenCalled();
			expect(repository.markFinalized).toHaveBeenCalledWith(message, expect.any(Date), expect.any(Date));
			const [, finalizedAt, expiresAt] = repository.markFinalized.mock.calls[0] as [unknown, Date, Date];
			expect(expiresAt).toEqual(finalizedAt);
		});

		it('첨부 회수나 알림이 실패하면 끝났다고 기록하지 않아 점유가 끝난 뒤 다시 한다', async () => {
			repository.claimUnfinalized.mockResolvedValueOnce([scheduled({status: 'FAILED', failureReason: 'DM_BLOCKED', attachments: [attachment]})]);
			attachmentDeletions.record.mockRejectedValueOnce(new Error('mongo unavailable'));

			await dispatcher.runCycle(NOW);

			expect(notificationProducer.send).not.toHaveBeenCalled();
			expect(repository.markFinalized).not.toHaveBeenCalled();
		});
	});
});
