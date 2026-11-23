import {BadRequestException, ConflictException, NotFoundException} from '@nestjs/common';
import {Types} from 'mongoose';
import {type ScheduledMessageRecord} from './scheduled-message.repository.js';
import {ScheduledMessageService} from './scheduled-message.service.js';

const NOW = new Date('2026-11-23T00:00:00.000Z');
const IN_ONE_HOUR = new Date(NOW.getTime() + (60 * 60 * 1000)).toISOString();
const ctx = {channelId: 1, userId: 42};
const attachment = {
	objectKey: 'chat-files/1/42/a.png', name: 'a.png', size: 1, mimeType: 'image/png', spoiler: false,
};

function record(overrides: Partial<ScheduledMessageRecord> = {}): ScheduledMessageRecord {
	return {
		_id: new Types.ObjectId(),
		authorId: 42,
		channelId: 1,
		teamId: 10,
		content: '공지',
		type: 'TEXT',
		attachments: [],
		clientMessageId: 'scheduled:client-1',
		scheduledAt: new Date(IN_ONE_HOUR),
		status: 'PENDING',
		nextAttemptAt: new Date(IN_ONE_HOUR),
		claimId: null,
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

describe('ScheduledMessageService', () => {
	const repository = {
		findByClientMessageId: jest.fn(),
		countWaiting: jest.fn(),
		create: jest.fn(),
		findForAuthor: jest.fn(),
		updatePending: jest.fn(),
		cancel: jest.fn(),
	};
	const chatService = {requireSendable: jest.fn()};
	const objectStorageService = {claimAttachments: jest.fn()};
	let service: ScheduledMessageService;

	beforeEach(() => {
		jest.resetAllMocks();
		repository.findByClientMessageId.mockResolvedValue(null);
		repository.countWaiting.mockResolvedValue(0);
		repository.create.mockImplementation(async (input: Partial<ScheduledMessageRecord>) => record(input));
		chatService.requireSendable.mockResolvedValue({teamId: 10, projectId: null});
		objectStorageService.claimAttachments.mockResolvedValue([attachment]);
		service = new ScheduledMessageService();
		Object.assign(service, {
			repository, chatService, objectStorageService,
		});
	});

	describe('create', () => {
		it('예약할 때 DM을 다시 노출하지 않고 검사한 뒤 첨부를 예약 키로 점유해 저장한다', async () => {
			const response = await service.create(ctx, {
				content: '', attachments: [{objectKey: attachment.objectKey}], scheduledAt: IN_ONE_HOUR, clientMessageId: 'client-1',
			}, NOW);

			expect(chatService.requireSendable).toHaveBeenCalledWith(ctx, {attachmentCount: 1, revealDm: false});
			expect(objectStorageService.claimAttachments).toHaveBeenCalledWith([{objectKey: attachment.objectKey}], 1, 42, 'scheduled:client-1');
			expect(repository.create).toHaveBeenCalledWith(expect.objectContaining({
				attachments: [attachment], clientMessageId: 'scheduled:client-1', status: 'PENDING', nextAttemptAt: new Date(IN_ONE_HOUR), teamId: 10,
			}));
			expect(response.status).toBe('PENDING');
		});

		it('첨부가 있는데 clientMessageId가 없으면 거부한다', async () => {
			await expect(service.create(ctx, {content: 'hi', attachments: [{objectKey: attachment.objectKey}], scheduledAt: IN_ONE_HOUR}, NOW))
				.rejects.toThrow(BadRequestException);
		});

		it('대기 중인 예약이 100건이면 첨부를 점유하기 전에 거부한다', async () => {
			repository.countWaiting.mockResolvedValue(100);

			await expect(service.create(ctx, {content: 'hi', scheduledAt: IN_ONE_HOUR}, NOW)).rejects.toThrow(ConflictException);
			expect(objectStorageService.claimAttachments).not.toHaveBeenCalled();
		});

		it('같은 clientMessageId로 다시 요청하면 처음 만든 예약을 돌려준다', async () => {
			const existing = record();
			repository.findByClientMessageId.mockResolvedValue(existing);

			const response = await service.create(ctx, {content: 'hi', scheduledAt: IN_ONE_HOUR, clientMessageId: 'client-1'}, NOW);

			expect(response.id).toBe(existing._id.toHexString());
			expect(repository.create).not.toHaveBeenCalled();
		});

		it('다른 사용자가 쓴 clientMessageId면 거부한다', async () => {
			repository.findByClientMessageId.mockResolvedValue(record({authorId: 7}));

			await expect(service.create(ctx, {content: 'hi', scheduledAt: IN_ONE_HOUR, clientMessageId: 'client-1'}, NOW))
				.rejects.toThrow(ConflictException);
		});
	});

	describe('update', () => {
		it('첨부만 있는 예약은 본문을 비울 수 있다', async () => {
			repository.findForAuthor.mockResolvedValue(record({attachments: [attachment]}));
			repository.updatePending.mockResolvedValue(record({content: ''}));

			await service.update(42, 'id', {content: ''}, NOW);

			expect(repository.updatePending).toHaveBeenCalledWith('id', 42, {content: '', scheduledAt: undefined});
		});

		it('발송 중인 예약은 수정할 수 없다', async () => {
			repository.findForAuthor.mockResolvedValue(record({status: 'DISPATCHING'}));
			repository.updatePending.mockResolvedValue(null);

			await expect(service.update(42, 'id', {scheduledAt: IN_ONE_HOUR}, NOW)).rejects.toThrow(ConflictException);
		});
	});

	describe('cancel', () => {
		it('대기 중이거나 실패한 예약을 취소 상태로 바꾸고 첨부 회수는 워커에 맡긴다', async () => {
			repository.cancel.mockResolvedValue(record({status: 'CANCELED', attachments: [attachment]}));

			await service.cancel(42, 'id', NOW);

			expect(repository.cancel).toHaveBeenCalledWith('id', 42, NOW);
		});

		it('발송 중인 예약은 취소할 수 없다', async () => {
			repository.cancel.mockResolvedValue(null);
			repository.findForAuthor.mockResolvedValue(record({status: 'DISPATCHING'}));

			await expect(service.cancel(42, 'id', NOW)).rejects.toThrow(ConflictException);
		});

		it.each([
			['없는 예약', null],
			['이미 취소한 예약', record({status: 'CANCELED'})],
		])('%s은 찾을 수 없다', async (_label, current) => {
			repository.cancel.mockResolvedValue(null);
			repository.findForAuthor.mockResolvedValue(current);

			await expect(service.cancel(42, 'id', NOW)).rejects.toThrow(NotFoundException);
		});
	});
});
