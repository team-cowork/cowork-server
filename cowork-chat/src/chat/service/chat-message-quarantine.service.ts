import process from 'node:process';
import {Injectable as injectable, Logger} from '@nestjs/common';
import {DicoshotService} from 'dicoshot-nest';
import {Counter, register} from 'prom-client';
import {shouldAlert} from '../../common/util/alert-throttle.util.js';
import {ChatMessageQuarantineErrorType, ChatMessageQuarantineRecord, ChatMessageQuarantineStatus} from '../schema/index.js';
import {ChatMessageQuarantineRepository} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';

/**
Prometheus metric label 이름이다. 바꾸면 기존 대시보드·알림 쿼리와 달라진다.
*/
const metricLabel = {
	errorType: 'error_type',
	reasonCode: 'reason_code',
} as const;

const ALERT_COOLDOWN_MS = 5 * 60 * 1000;
const RECENT_RECORD_LIMIT = 100;

export type ChatMessageQuarantineRequest = {
	topic: string;
	partition: number;
	messageOffset: string;
	eventKey: Nullable<string>;
	payload: Nullable<string>;
	contractVersion: number;
	errorType: ChatMessageQuarantineErrorType;
	reasonCode: string;
	reason: string;
};

@injectable()
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

	private retentionDays(): number {
		const value = Number(process.env.CHAT_MESSAGE_QUARANTINE_RETENTION_DAYS ?? 30);
		return Number.isSafeInteger(value) && value > 0 ? value : 30;
	}

	async quarantine(request: ChatMessageQuarantineRequest): Promise<void> {
		await this.repository.quarantine({...request, groupId: 'cowork-chat', retentionDays: this.retentionDays()});
		this.quarantined.inc({[metricLabel.errorType]: request.errorType, [metricLabel.reasonCode]: request.reasonCode});
		this.logger.warn(
			`chat.message quarantined topic=${request.topic} partition=${request.partition} `
			+ `offset=${request.messageOffset} errorType=${request.errorType} reasonCode=${request.reasonCode}`,
		);
		const alertKey = `chat-message-quarantine:${request.errorType}:${request.reasonCode}`;
		if (shouldAlert(alertKey, ALERT_COOLDOWN_MS)) {
			void this.dicoshot.sendCustom({
				title: request.errorType === 'SCOPE_ERROR' ? '⚠️ chat.message 범위 오류 격리' : '⚠️ chat.message poison record 격리',
				description: 'chat.message 레코드를 저장·격리했습니다. 원문은 운영 절차에 따라 조회할 수 있습니다.',
				color: 'warning',
				fields: [
					{name: 'Topic', value: request.topic, inline: true},
					{name: 'Partition', value: String(request.partition), inline: true},
					{name: 'Offset', value: request.messageOffset, inline: true},
					{name: 'Reason code', value: request.reasonCode, inline: true},
				],
			}).catch(() => {/* Alert delivery is best-effort. */});
		}
	}

	async requestReprocess(id: string): Promise<boolean> {
		const isRequested = await this.repository.requestReprocess(id);
		this.reprocesses.inc({result: isRequested ? 'requested' : 'rejected'});
		return isRequested;
	}

	async discard(id: string): Promise<boolean> {
		return this.repository.discard(id);
	}

	async summary(): Promise<Array<{status: ChatMessageQuarantineStatus; errorType: ChatMessageQuarantineErrorType; reasonCode: string; count: number}>> {
		return this.repository.summary();
	}

	async findRecent(status?: ChatMessageQuarantineStatus): Promise<ChatMessageQuarantineRecord[]> {
		return this.repository.findRecent(status, RECENT_RECORD_LIMIT);
	}

	recordReprocess(result: 'succeeded' | 'failed'): void {
		this.reprocesses.inc({result});
	}
}

function counter<L extends string>(name: string, help: string, labelNames: L[]): Counter<L> {
	return (register.getSingleMetric(name) as Counter<L> | undefined) ?? new Counter({name, help, labelNames});
}
