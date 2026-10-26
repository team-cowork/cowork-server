import {BadRequestException, Injectable, NotFoundException} from '@nestjs/common';
import {ObjectStorageService} from '../../storage/object-storage.service';
import {MessageRepository} from '../repository/message.repository';
import {AttachmentDownloadResponseDto} from '../dto/attachment-download.dto';
import {ChannelMessageReadAccessService} from './channel-message-read-access.service';

@Injectable()
export class AttachmentDownloadService {
    constructor(
        private readonly messages: MessageRepository,
        private readonly readAccess: ChannelMessageReadAccessService,
        private readonly storage: ObjectStorageService,
    ) {}

    async execute(channelId: number, userId: number, messageId: string, attachmentIndex: number): Promise<AttachmentDownloadResponseDto> {
        if (!/^[\da-f]{24}$/iu.test(messageId) || !Number.isSafeInteger(attachmentIndex) || attachmentIndex < 0) {
            throw new BadRequestException('메시지 또는 첨부파일 식별자가 유효하지 않습니다');
        }

        await this.readAccess.requireCanRead(channelId, userId);
        const message = await this.messages.findByIdAndChannelId(messageId, channelId);
        if (!message || message.searchIndexStatus === 'DELETING') {
            throw new NotFoundException('메시지를 찾을 수 없습니다');
        }

        const attachment = message.attachments[attachmentIndex];
        if (attachment === undefined || message.authorId === null) {
            throw new NotFoundException('첨부파일을 찾을 수 없습니다');
        }

        const objectKey = attachment.objectKey ?? this.storage.extractObjectKey(attachment.url ?? '');
        return this.storage.createPresignedDownload(objectKey, channelId, message.authorId, attachment.name);
    }
}
