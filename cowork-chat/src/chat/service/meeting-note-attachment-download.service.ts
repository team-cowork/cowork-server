import {
	BadRequestException,
	Injectable as injectable,
	NotFoundException,
	ServiceUnavailableException,
} from '@nestjs/common';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {ObjectStorageService} from '../../storage/object-storage.service.js';
import {MeetingNoteAttachmentProjectionRepository} from '../repository/meeting-note-attachment-projection.repository.js';
import {ChannelMessageReadAccessService} from './channel-message-read-access.service.js';

@injectable()
export class MeetingNoteAttachmentDownloadService {
	constructor(
		private readonly notes: MeetingNoteAttachmentProjectionRepository,
		private readonly access: ChannelMessageReadAccessService,
		private readonly readiness: ProjectionReadinessService,
		private readonly storage: ObjectStorageService,
	) {}

	private requireReady() {
		if (!this.readiness.areReady(['meetingNoteAttachment'])) {
			throw new ServiceUnavailableException('회의록 첨부파일 정보가 아직 동기화되지 않았습니다');
		}
	}

	async execute(channelId: number, noteId: number, userId: number, objectKey: string) {
		if (typeof objectKey !== 'string' || objectKey.length === 0 || objectKey.length > 200) {
			throw new BadRequestException('첨부파일 경로가 유효하지 않습니다');
		}

		await this.access.requireCanView(channelId, userId);
		this.requireReady();
		const note = await this.notes.find(noteId, channelId);
		const attachment = note?.attachments.find(a => a.objectKey === objectKey);
		this.requireReady();
		if (!note || !attachment) {
			throw new NotFoundException('회의록 첨부파일을 찾을 수 없습니다');
		}

		return this.storage.createPresignedDownload(attachment.objectKey, channelId, note.authorId, attachment.name);
	}
}
