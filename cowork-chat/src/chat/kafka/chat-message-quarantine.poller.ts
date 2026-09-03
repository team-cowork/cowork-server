import { Injectable, Logger, OnModuleDestroy, OnModuleInit } from '@nestjs/common';
import { ChatMessageQuarantineRepository } from '../repository/chat-message-quarantine.repository';
import { ChatMessageQuarantineService } from '../service/chat-message-quarantine.service';
import { validateChatMessageEvent } from './event/chat-message-contract';
import { ChatMessageProcessor } from './chat-message.processor';

const POLL_INTERVAL_MS = 5_000;
const STALE_PROCESSING_THRESHOLD_MS = 2 * 60 * 1_000;

/** 운영자가 요청한 quarantine 재처리를 프로세스 내부의 정상 처리 경로로 수행한다. */
@Injectable()
export class ChatMessageQuarantinePoller implements OnModuleInit, OnModuleDestroy {
    private readonly logger = new Logger(ChatMessageQuarantinePoller.name);
    private timer?: ReturnType<typeof setInterval>;
    private running = false;

    constructor(
        private readonly repository: ChatMessageQuarantineRepository,
        private readonly quarantineService: ChatMessageQuarantineService,
        private readonly processor: ChatMessageProcessor,
    ) {}

    onModuleInit(): void {
        this.timer = setInterval(() => { void this.runCycle(); }, POLL_INTERVAL_MS);
    }

    onModuleDestroy(): void {
        clearInterval(this.timer);
    }

    private async runCycle(): Promise<void> {
        if (this.running) return;
        this.running = true;
        try {
            await this.repository.reclaimStaleProcessing(STALE_PROCESSING_THRESHOLD_MS);
            const record = await this.repository.claimReprocess();
            if (!record || !record.payload || record.payloadTruncated) return;
            const recordId = String((record as unknown as { _id: unknown })._id);
            try {
                const event = validateChatMessageEvent(JSON.parse(record.payload), record.eventKey);
                await this.processor.process(event);
                await this.repository.markReprocessed(recordId);
                this.quarantineService.recordReprocess('succeeded');
            } catch (error) {
                const message = error instanceof Error ? error.message : 'unknown reprocess error';
                await this.repository.releaseReprocess(recordId, message);
                this.quarantineService.recordReprocess('failed');
                this.logger.warn(`chat.message quarantine reprocess failed recordId=${recordId} error=${message}`);
            }
        } catch (error) {
            this.logger.error('chat.message quarantine poller failed', error);
        } finally {
            this.running = false;
        }
    }
}
