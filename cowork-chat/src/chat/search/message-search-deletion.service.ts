import { Injectable, Logger } from '@nestjs/common';
import { MessageRepository } from '../repository/message.repository';
import { MessageSearchTombstoneRepository } from '../repository/message-search-tombstone.repository';
import { isSearchIndexed } from './message-index-scope';

const DEFAULT_TOMBSTONE_RETENTION_DAYS = 7;

/**
 * 메시지 삭제와 삭제 색인 명령을 함께 durable하게 남긴다.
 *
 * 메시지는 hard delete되므로 삭제 의도는 tombstone에만 남는다. 순서는 반드시
 * `삭제 버전 예약 → tombstone 기록 → MongoDB 삭제`여야 한다.
 *
 * - 예약은 메시지를 `DELETING`으로 고정해 이후 본문·고정 변경이 버전을 올리지 못하게 하므로,
 *   tombstone 버전은 이 메시지의 어떤 upsert보다 항상 크다. 지연된 upsert가 삭제된 문서를
 *   되살릴 수 없다.
 * - tombstone을 먼저 남기므로 MongoDB 삭제 직전에 중단되어도 삭제 의도는 유실되지 않고,
 *   워커가 MongoDB 삭제와 색인 삭제를 이어서 완결한다.
 */
@Injectable()
export class MessageSearchDeletionService {
    private readonly logger = new Logger(MessageSearchDeletionService.name);

    constructor(
        private readonly messageRepository: MessageRepository,
        private readonly tombstoneRepository: MessageSearchTombstoneRepository,
    ) {}

    /**
     * 메시지를 삭제한다. 색인 대상이면 삭제 색인 명령을 함께 기록한다.
     *
     * 이미 다른 요청이 삭제를 예약했거나 메시지가 사라진 경우에는 아무 것도 하지 않는다.
     * 그 삭제를 예약한 경로가 tombstone 기록과 MongoDB 삭제를 끝까지 책임진다.
     */
    async deleteMessage(messageId: string): Promise<void> {
        const reserved = await this.messageRepository.reserveDeletion(messageId);
        if (!reserved) {
            this.logger.warn(`Message deletion already in progress or message is gone messageId=${messageId}`);
            return;
        }

        if (isSearchIndexed(reserved)) {
            await this.tombstoneRepository.create({
                messageId,
                teamId: reserved.teamId!,
                projectId: reserved.projectId!,
                channelId: reserved.channelId,
                version: reserved.searchIndexVersion,
                retentionDays: this.retentionDays(),
            });
        }
        await this.messageRepository.deleteById(messageId);
    }

    private retentionDays(): number {
        const value = Number(process.env.CHAT_SEARCH_TOMBSTONE_RETENTION_DAYS ?? DEFAULT_TOMBSTONE_RETENTION_DAYS);
        return Number.isSafeInteger(value) && value > 0 ? value : DEFAULT_TOMBSTONE_RETENTION_DAYS;
    }
}
