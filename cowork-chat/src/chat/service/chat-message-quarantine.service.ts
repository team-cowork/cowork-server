import { Injectable, Logger } from '@nestjs/common';
import { DicoshotService } from 'dicoshot-nest';
import { Counter, register } from 'prom-client';
import { AlertThrottleUtil } from '../../common/util/alert-throttle.util';
import { ChatMessageQuarantineErrorType } from '../schema/chat-message-quarantine.schema';
import { ChatMessageQuarantineRepository } from '../repository/chat-message-quarantine.repository';

const ALERT_COOLDOWN_MS = 5 * 60 * 1_000;

export type ChatMessageQuarantineRequest = {
    topic: string;
    partition: number;
    messageOffset: string;
    eventKey: string | null;
    payload: string | null;
    contractVersion: number;
    errorType: ChatMessageQuarantineErrorType;
    reasonCode: string;
    reason: string;
};

@Injectable()
export class ChatMessageQuarantineService {
    private readonly logger = new Logger(ChatMessageQuarantineService.name);
    private readonly quarantined: Counter<'error_type' | 'reason_code'>;
    private readonly reprocesses: Counter<'result'>;

    constructor(
        private readonly repository: ChatMessageQuarantineRepository,
        private readonly dicoshot: DicoshotService,
    ) {
        this.quarantined = counter('cowork_chat_message_quarantine_total', 'Durably quarantined chat.message records.', ['error_type', 'reason_code']);
        this.reprocesses = counter('cowork_chat_message_quarantine_reprocess_total', 'Chat message quarantine reprocess attempts.', ['result']);
    }

    async quarantine(request: ChatMessageQuarantineRequest): Promise<void> {
        await this.repository.quarantine({ ...request, groupId: 'cowork-chat', retentionDays: this.retentionDays() });
        this.quarantined.inc({ error_type: request.errorType, reason_code: request.reasonCode });
        this.logger.warn(`chat.message quarantined topic=${request.topic} partition=${request.partition} offset=${request.messageOffset} errorType=${request.errorType} reasonCode=${request.reasonCode}`);
        const alertKey = `chat-message-quarantine:${request.errorType}:${request.reasonCode}`;
        if (AlertThrottleUtil.shouldAlert(alertKey, ALERT_COOLDOWN_MS)) {
            void this.dicoshot.sendCustom({
                title: request.errorType === 'SCOPE_ERROR' ? '⚠️ chat.message 범위 오류 격리' : '⚠️ chat.message poison record 격리',
                description: 'chat.message 레코드를 저장·격리했습니다. 원문은 운영 절차에 따라 조회할 수 있습니다.',
                color: 'warning',
                fields: [
                    { name: 'Topic', value: request.topic, inline: true },
                    { name: 'Partition', value: String(request.partition), inline: true },
                    { name: 'Offset', value: request.messageOffset, inline: true },
                    { name: 'Reason code', value: request.reasonCode, inline: true },
                ],
            }).catch(() => {});
        }
    }

    async requestReprocess(id: string): Promise<boolean> {
        const requested = await this.repository.requestReprocess(id);
        this.reprocesses.inc({ result: requested ? 'requested' : 'rejected' });
        return requested;
    }

    recordReprocess(result: 'succeeded' | 'failed'): void {
        this.reprocesses.inc({ result });
    }

    private retentionDays(): number {
        const value = Number(process.env.CHAT_MESSAGE_QUARANTINE_RETENTION_DAYS ?? 30);
        return Number.isSafeInteger(value) && value > 0 ? value : 30;
    }
}

function counter<L extends string>(name: string, help: string, labelNames: L[]): Counter<L> {
    return (register.getSingleMetric(name) as Counter<L> | undefined) ?? new Counter({ name, help, labelNames });
}
