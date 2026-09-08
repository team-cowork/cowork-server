import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { MessageRepository } from '../repository/message.repository';
import { MessageSearchTombstoneRepository } from '../repository/message-search-tombstone.repository';
import { MESSAGE_INDEX_GC_DELETES_DAYS } from '../../search/message-index.contract';
import { isSearchIndexed } from './message-index-scope';

const DEFAULT_TOMBSTONE_RETENTION_DAYS = 7;

/**
 * `deleteMessage` 처리 결과.
 * - `DELETED`: 이 호출이 삭제를 완결했다.
 * - `ALREADY_IN_PROGRESS`: 다른 요청이 이미 삭제를 예약해 진행 중이다. 그 요청이 완결을 책임진다.
 * - `NOT_FOUND`: 메시지가 이미 존재하지 않는다.
 */
export type DeleteMessageResult = 'DELETED' | 'ALREADY_IN_PROGRESS' | 'NOT_FOUND';

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
export class MessageSearchDeletionService implements OnModuleInit {
    private readonly logger = new Logger(MessageSearchDeletionService.name);

    constructor(
        private readonly messageRepository: MessageRepository,
        private readonly tombstoneRepository: MessageSearchTombstoneRepository,
    ) {}

    /**
     * `CHAT_SEARCH_TOMBSTONE_RETENTION_DAYS`가 Elasticsearch `gc_deletes`
     * ({@link MESSAGE_INDEX_GC_DELETES_DAYS})보다 짧으면 MongoDB tombstone이 재구축 catch-up이
     * 삭제를 재생하기 전에 TTL로 먼저 사라질 수 있다. 둘 다 환경마다 독립적으로 바뀔 수 있는
     * 값이라 부팅 시 한 번 관계를 확인해 조용히 어긋나지 않게 한다.
     */
    onModuleInit(): void {
        const retentionDays = this.retentionDays();
        if (retentionDays < MESSAGE_INDEX_GC_DELETES_DAYS) {
            this.logger.error(
                `CHAT_SEARCH_TOMBSTONE_RETENTION_DAYS(${retentionDays}) is shorter than Elasticsearch gc_deletes(${MESSAGE_INDEX_GC_DELETES_DAYS}d). `
                + 'A tombstone may TTL-expire before a search index rebuild can replay its deletion.',
            );
        }
    }

    /**
     * 메시지를 삭제한다. 색인 대상이면 삭제 색인 명령을 함께 기록한다.
     *
     * 이미 다른 요청이 삭제를 예약했거나 메시지가 사라진 경우에는 아무 것도 하지 않고 그 상태를
     * 반환한다. 호출자는 `DELETED`일 때만 이 요청이 삭제를 완결했다고 판단해야 한다 —
     * `ALREADY_IN_PROGRESS`는 아직 tombstone 기록·MongoDB 삭제가 끝나지 않았을 수 있어,
     * 이 시점에 완료 이벤트를 내보내면 실제 삭제보다 먼저 나갈 수 있다.
     */
    async deleteMessage(messageId: string): Promise<DeleteMessageResult> {
        const reserved = await this.messageRepository.reserveDeletion(messageId);
        if (!reserved) {
            const stillExists = await this.messageRepository.existsById(messageId);
            if (stillExists) {
                this.logger.warn(`Message deletion already in progress messageId=${messageId}`);
                return 'ALREADY_IN_PROGRESS';
            }
            this.logger.warn(`Message already deleted messageId=${messageId}`);
            return 'NOT_FOUND';
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
        return 'DELETED';
    }

    private retentionDays(): number {
        const value = Number(process.env.CHAT_SEARCH_TOMBSTONE_RETENTION_DAYS ?? DEFAULT_TOMBSTONE_RETENTION_DAYS);
        return Number.isSafeInteger(value) && value > 0 ? value : DEFAULT_TOMBSTONE_RETENTION_DAYS;
    }
}
