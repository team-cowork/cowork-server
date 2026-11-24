import {randomUUID} from 'node:crypto';
import {
	BadRequestException,
	ConflictException,
	Inject as inject,
	Injectable as injectable,
	NotFoundException,
} from '@nestjs/common';
import {ObjectStorageService} from '../../storage/object-storage.service.js';
import {ChatService} from '../chat.service.js';
import {type ChannelUserContext} from '../dto/index.js';
import {
	type CreateScheduledMessageDto,
	type ScheduledMessageResponseDto,
	type UpdateScheduledMessageDto,
} from './scheduled-message.dto.js';
import {
	SCHEDULED_CLIENT_MESSAGE_ID_PREFIX,
	requireScheduleCapacity,
	requireScheduleTime,
	requireScheduledContent,
} from './scheduled-message.policy.js';
import {ScheduledMessageRepository, type ScheduledMessageRecord} from './scheduled-message.repository.js';

const DUPLICATE_KEY = 11_000;

function toResponse(message: ScheduledMessageRecord): ScheduledMessageResponseDto {
	return {
		id: message._id.toHexString(),
		channelId: message.channelId,
		content: message.content,
		type: message.type,
		attachments: message.attachments.map(({objectKey, name, size, mimeType, spoiler}) => ({
			objectKey, name, size, mimeType, spoiler,
		})),
		scheduledAt: message.scheduledAt.toISOString(),
		status: message.status,
		failureReason: message.failureReason,
		createdAt: message.createdAt.toISOString(),
	};
}

/**
 예약 메시지의 생성, 조회, 수정, 취소를 담당한다. 발송은 `ScheduledMessageDispatcher`가 한다.
 */
@injectable()
export class ScheduledMessageService {
	@inject()
	private readonly repository!: ScheduledMessageRepository;

	@inject()
	private readonly chatService!: ChatService;

	@inject()
	private readonly objectStorageService!: ObjectStorageService;

	/**
     같은 키로 다시 만든 요청이면 처음 만든 예약을 돌려준다.

     @throws ConflictException 다른 사용자나 채널이 이미 쓴 키인 경우
     */
	private async findRetried(ctx: ChannelUserContext, clientMessageId: string): Promise<ScheduledMessageResponseDto | undefined> {
		const existing = await this.repository.findByClientMessageId(clientMessageId);
		if (!existing) {
			return undefined;
		}

		if (existing.authorId !== ctx.userId || existing.channelId !== ctx.channelId) {
			throw new ConflictException('이미 사용한 clientMessageId입니다');
		}

		return toResponse(existing);
	}

	/**
     예약을 만든다. 첨부는 지금 점유해 확정값을 저장하므로, 업로드 기록이 만료된 뒤에도 발송할 수 있다.

     @throws BadRequestException 예약 시각, 본문, 재시도 키가 올바르지 않은 경우
     @throws ConflictException 대기 중인 예약이 한도에 이르렀거나 채널이 보관된 경우
     @throws ForbiddenException 권한이 없거나 DM 상대가 차단한 경우
     */
	async create(ctx: ChannelUserContext, dto: CreateScheduledMessageDto, now = new Date()): Promise<ScheduledMessageResponseDto> {
		const scheduledAt = new Date(dto.scheduledAt);
		requireScheduleTime(scheduledAt, now);
		const attachmentCount = dto.attachments?.length ?? 0;
		requireScheduledContent(dto.content, attachmentCount);

		const requestedKey = dto.clientMessageId === '' ? undefined : dto.clientMessageId;
		if (requestedKey === undefined && attachmentCount > 0) {
			throw new BadRequestException('첨부 메시지에는 재시도에 사용할 clientMessageId가 필요합니다');
		}

		// 즉시 전송한 메시지와 키가 겹쳐 발송이 중복으로 버려지지 않게, 메시지 키 공간을 나눈다.
		const clientMessageId = `${SCHEDULED_CLIENT_MESSAGE_ID_PREFIX}${requestedKey ?? randomUUID()}`;
		const retried = await this.findRetried(ctx, clientMessageId);
		if (retried) {
			return retried;
		}

		const scope = await this.chatService.requireSendable(ctx, {attachmentCount, revealDm: false});
		// Shortcut: 동시 요청은 한도를 조금 넘길 수 있다. 정확한 한도가 필요해지면 사용자별 카운터 문서로 바꾼다.
		requireScheduleCapacity(await this.repository.countWaiting(ctx.userId));
		const attachments = await this.objectStorageService.claimAttachments(dto.attachments ?? [], ctx.channelId, ctx.userId, clientMessageId);

		try {
			const created = await this.repository.create({
				authorId: ctx.userId,
				channelId: ctx.channelId,
				teamId: scope.teamId,
				content: dto.content,
				type: dto.type ?? 'TEXT',
				attachments: attachments.map(attachment => ({...attachment, spoiler: attachment.spoiler ?? false})),
				clientMessageId,
				scheduledAt,
				status: 'PENDING',
				nextAttemptAt: scheduledAt,
				claimId: null,
				attempts: 0,
				lastError: null,
				failureReason: null,
				failedAt: null,
				finalizedAt: null,
				expiresAt: null,
			});
			return toResponse(created);
		} catch (error) {
			if ((error as {code?: number}).code === DUPLICATE_KEY) {
				const concurrent = await this.findRetried(ctx, clientMessageId);
				if (concurrent) {
					return concurrent;
				}
			}

			throw error;
		}
	}

	async list(userId: number): Promise<ScheduledMessageResponseDto[]> {
		const messages = await this.repository.listForAuthor(userId);
		return messages.map(message => toResponse(message));
	}

	/**
     발송 전인 예약의 내용과 시각을 바꾼다.

     @throws BadRequestException 바꿀 값이 없거나 올바르지 않은 경우
     @throws NotFoundException 내 예약이 아닌 경우
     @throws ConflictException 발송 중이거나 끝난 예약인 경우
     */
	async update(userId: number, id: string, dto: UpdateScheduledMessageDto, now = new Date()): Promise<ScheduledMessageResponseDto> {
		if (dto.content === undefined && dto.scheduledAt === undefined) {
			throw new BadRequestException('바꿀 내용이나 예약 시각을 입력하세요');
		}

		const current = await this.repository.findForAuthor(id, userId);
		if (!current || current.status === 'CANCELED') {
			throw new NotFoundException('예약 메시지를 찾을 수 없습니다');
		}

		const scheduledAt = dto.scheduledAt === undefined ? undefined : new Date(dto.scheduledAt);
		if (scheduledAt !== undefined) {
			requireScheduleTime(scheduledAt, now);
		}

		if (dto.content !== undefined) {
			requireScheduledContent(dto.content, current.attachments.length);
		}

		const updated = await this.repository.updatePending(id, userId, {content: dto.content, scheduledAt});
		if (!updated) {
			throw new ConflictException('발송 중이거나 발송이 끝난 예약은 수정할 수 없습니다');
		}

		return toResponse(updated);
	}

	/**
     대기 중인 예약을 취소하거나 실패한 예약을 목록에서 지운다. 첨부 회수는 발송 워커가 후처리로 한다.

     @throws NotFoundException 내 예약이 아닌 경우
     @throws ConflictException 발송 중이거나 발송한 예약인 경우
     */
	async cancel(userId: number, id: string, now = new Date()): Promise<void> {
		if (await this.repository.cancel(id, userId, now)) {
			return;
		}

		const current = await this.repository.findForAuthor(id, userId);
		if (!current || current.status === 'CANCELED') {
			throw new NotFoundException('예약 메시지를 찾을 수 없습니다');
		}

		throw new ConflictException('발송 중이거나 발송한 예약은 취소할 수 없습니다');
	}
}
