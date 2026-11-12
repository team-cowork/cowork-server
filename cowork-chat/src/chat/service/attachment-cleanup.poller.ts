import {
	BadRequestException,
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
	Inject as inject,
} from '@nestjs/common';
import {MeetingNoteAttachmentProjectionRepository} from '../repository/meeting-note-attachment-projection.repository.js';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ObjectStorageService} from '../../storage/object-storage.service.js';
import {AttachmentUploadRepository} from '../../storage/attachment-upload.repository.js';
import {AttachmentUpload, ATTACHMENT_UPLOAD_RETENTION_SECONDS} from '../../storage/attachment-upload.schema.js';
import {MessageRepository} from '../repository/message.repository.js';
import {AttachmentDeletionRepository, type AttachmentDeletionRecord} from '../repository/attachment-deletion.repository.js';

const POLL_INTERVAL_MS = 10_000;
const DELETION_BATCH_SIZE = 20;
/**
점유 후 이 시간이 지나면 끝나지 않은 의도를 다시 처리한다. 실패 재시도 간격도 겸한다.
*/
const DELETION_LEASE_MS = 2 * 60 * 1000;
/**
요청 후 이 시간이 지나도 삭제 예약 없이 남은 메시지는 삭제가 중단된 것으로 보고 의도를 버린다.
tombstone 없는 삭제 예약이 되돌려지는 5분보다 길게 잡는다.
*/
export const ABANDONED_DELETION_MS = 15 * 60 * 1000;
const RECLAIM_INTERVAL_MS = 10 * 60 * 1000;
const RECLAIM_BATCH_SIZE = 50;
const RECLAIM_LEASE_MS = 60 * 60 * 1000;
/**
재시도 창(생성 후 7일) 뒤 비동기 메시지 저장·quarantine 재처리를 기다리는 유예.
*/
const UPLOAD_RECLAIM_GRACE_MS = 24 * 60 * 60 * 1000;
export const UPLOAD_RECLAIM_AGE_MS = (ATTACHMENT_UPLOAD_RETENTION_SECONDS * 1000) + UPLOAD_RECLAIM_GRACE_MS;

/**
 첨부 객체 정리 워커.

 - 메시지 삭제 의도: 메시지가 사라진 것을 확인한 뒤에만 객체를 지우고 의도를 제거한다.
 - 미전송 업로드: 재시도 창과 유예가 지난 업로드 기록을 정리하며, 메시지와 회의록이 참조하지 않는 확정 객체는 먼저 지운다.

 항목은 처리 시각을 뒤로 미루는 점유로 꺼내므로 여러 replica가 같은 항목을 동시에 처리하지 않는다.
 실패한 항목은 점유가 끝나면 다시 처리되고, 객체 삭제는 이미 없는 객체도 성공으로 보므로 반복해도 안전하다.
 */
@injectable()
export class AttachmentCleanupPoller implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(AttachmentCleanupPoller.name);
	private timer?: ReturnType<typeof setInterval>;
	private running = false;
	private lastReclaimAt = 0;

	@inject()
	private readonly meetingNotes!: MeetingNoteAttachmentProjectionRepository;

	@inject()
	private readonly readiness!: ProjectionReadinessService;

	constructor(
		private readonly deletions: AttachmentDeletionRepository,
		private readonly uploads: AttachmentUploadRepository,
		private readonly messages: MessageRepository,
		private readonly storage: ObjectStorageService,
	) {}

	private async processDeletion(intent: AttachmentDeletionRecord, now: Date): Promise<void> {
		const message = await this.messages.findById(intent.messageId);
		if (message) {
			// 객체는 메시지보다 먼저 지우지 않는다. 진행 중인 삭제는 점유가 끝난 뒤 다시 확인한다.
			if (message.searchIndexStatus !== 'DELETING' && now.getTime() - intent.requestedAt.getTime() > ABANDONED_DELETION_MS) {
				await this.deletions.remove(intent._id, intent.requestedAt);
				this.logger.warn(`Dropped attachment deletion intent of a message that was not deleted messageId=${intent.messageId}`);
			}

			return;
		}

		try {
			await Promise.all(intent.objectKeys.map(async objectKey => this.removeObject(objectKey)));
		} catch (error) {
			await this.deletions.markFailed(intent._id, String(error));
			this.logger.warn(`Attachment deletion failed, will retry messageId=${intent.messageId} attempts=${intent.attempts + 1}`, error);
			return;
		}

		await this.deletions.remove(intent._id, intent.requestedAt);
	}

	/**
     재시도 창이 지난 업로드 기록을 정리한다. 메시지와 회의록이 참조하지 않는 확정 객체는 기록보다 먼저 지운다.
     참조 중인 객체는 보관하고 업로드 기록만 지운다. 확정 전 기록의 임시 객체는 버킷 lifecycle이 지운다.

     @returns 객체를 회수했으면 `true`
     */
	private async reclaimUpload(upload: AttachmentUpload): Promise<boolean> {
		const {objectKey, clientMessageId} = upload;
		const isUnsent = objectKey !== undefined
			&& (clientMessageId === undefined || !await this.messages.referencesAttachment(clientMessageId, objectKey))
			&& !await this.meetingNotes.references(objectKey);
		if (isUnsent) {
			try {
				await this.removeObject(objectKey);
			} catch (error) {
				this.logger.warn(`Unsent attachment reclaim failed, will retry uploadKey=${upload.uploadKey}`, error);
				return false;
			}
		}

		await this.uploads.remove(upload.uploadKey);
		return isUnsent;
	}

	private async removeObject(objectKey: string): Promise<void> {
		try {
			if (await this.meetingNotes.references(objectKey)) {
				return;
			}

			// 동기화가 끊기면 참조가 없다는 판정을 신뢰하지 않고 다음 주기에 재시도한다.
			if (!this.readiness.isCaughtUp('meetingNoteAttachment')) {
				throw new Error('Meeting note attachment projection is not ready');
			}

			await this.storage.removeObject(objectKey);
		} catch (error) {
			// 형식이 잘못된 키는 재시도해도 지울 수 없다.
			if (!(error instanceof BadRequestException)) {
				throw error;
			}

			this.logger.warn(`Skipping attachment object with an invalid key [key=${objectKey}]`);
		}
	}

	async runCycle(now = new Date()): Promise<void> {
		if (this.running || !this.readiness.isCaughtUp('meetingNoteAttachment')) {
			return;
		}

		this.running = true;
		try {
			const intents = await this.deletions.claimDue(now, DELETION_LEASE_MS, DELETION_BATCH_SIZE);
			await Promise.all(intents.map(async intent => this.processDeletion(intent, now)));

			if (now.getTime() - this.lastReclaimAt >= RECLAIM_INTERVAL_MS) {
				this.lastReclaimAt = now.getTime();
				const uploads = await this.uploads.claimReclaimable(
					new Date(now.getTime() - UPLOAD_RECLAIM_AGE_MS), now, RECLAIM_LEASE_MS, RECLAIM_BATCH_SIZE,
				);
				if (uploads.length === RECLAIM_BATCH_SIZE) {
					// 밀린 기록이 남아 있으면 다음 사이클에서 이어서 처리한다.
					this.lastReclaimAt = 0;
				}

				const reclaimed = await Promise.all(uploads.map(async upload => this.reclaimUpload(upload)));
				const count = reclaimed.filter(Boolean).length;
				if (count > 0) {
					this.logger.log(`Reclaimed ${count} unsent attachment object(s)`);
				}
			}
		} catch (error) {
			this.logger.error('Attachment cleanup cycle failed', error);
		} finally {
			this.running = false;
		}
	}

	onModuleInit(): void {
		this.timer = setInterval(() => {
			void this.runCycle();
		}, POLL_INTERVAL_MS);
		this.logger.log('Attachment cleanup poller started');
	}

	onModuleDestroy(): void {
		clearInterval(this.timer);
	}
}
