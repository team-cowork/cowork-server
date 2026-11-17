import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {Consumer, Kafka, Producer} from 'kafkajs';
import {getKafkaClientConfig} from '../../common/config/config.util.js';
import {connectWithRetry} from '../../common/kafka/connect-with-retry.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {MessageRepository} from '../repository/index.js';
import {type Nullable} from '../../common/type/nullable.js';

export const THREAD_PARENT_COMMAND_TOPIC = 'chat.thread-parent.command.v1';
export const THREAD_PARENT_COMMAND_DLT_TOPIC = 'chat.thread-parent.command.v1-dlt';
export const THREAD_PARENT_RESULT_TOPIC = 'chat.thread-parent.result.v1';
export const THREAD_PARENT_SCHEMA_VERSION = 1;

const OBJECT_ID = /^[0-9a-f]{24}$/v;

export type ThreadParentCommand = {
	schemaVersion: 1;
	threadId: number;
	channelId: number;
	parentMessageId: string;
};

export type ThreadParentRejectReason = 'PARENT_NOT_FOUND' | 'PARENT_IN_THREAD';

export type ThreadParentResult = ThreadParentCommand & {
	accepted: boolean;
	reason: Nullable<ThreadParentRejectReason>;
};

export function parseThreadParentCommand(payload: unknown, key?: string): Nullable<ThreadParentCommand> {
	const command = payload as Partial<ThreadParentCommand> | undefined;
	if (command?.schemaVersion !== THREAD_PARENT_SCHEMA_VERSION
		|| !isSafePositiveInteger(command.threadId) || key !== String(command.threadId)
		|| !isSafePositiveInteger(command.channelId)
		|| typeof command.parentMessageId !== 'string' || !OBJECT_ID.test(command.parentMessageId)) {
		return null;
	}

	return {
		schemaVersion: THREAD_PARENT_SCHEMA_VERSION,
		threadId: command.threadId,
		channelId: command.channelId,
		parentMessageId: command.parentMessageId,
	};
}

/**
 스레드 시작 메시지가 될 수 있는지 판정한다. 같은 채널에 있고, 그 자체가 스레드 메시지가 아니어야 한다.
 */
export function judgeThreadParent(
	command: ThreadParentCommand,
	parent: Nullable<{threadId?: Nullable<number>}>,
): ThreadParentResult {
	if (!parent) {
		return {...command, accepted: false, reason: 'PARENT_NOT_FOUND'};
	}

	return parent.threadId !== undefined && parent.threadId !== null ? {...command, accepted: false, reason: 'PARENT_IN_THREAD'} : {...command, accepted: true, reason: null};
}

/**
 `cowork-channel`이 스레드를 만들 때 보내는 시작 메시지 검증 command를 처리한다.

 메시지는 chat이 소유하므로 channel은 이 결과를 받아 스레드를 확정하거나 지운다.
 결과는 메시지 상태만으로 다시 계산되므로, 재전달로 같은 결과를 다시 보내도 channel이 무시한다.
 형식이 잘못된 command는 DLT에 격리한 뒤 넘어가고, 저장소·브로커 오류는 오프셋을 넘기지 않고 재시도한다.
 */
@injectable()
export class ThreadParentCommandConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(ThreadParentCommandConsumer.name);
	private consumer!: Consumer;
	private producer!: Producer;

	constructor(
		private readonly config: ConfigService,
		private readonly messageRepository: MessageRepository,
	) {}

	private async handle(key: string | undefined, value: string): Promise<void> {
		let payload: unknown;
		try {
			payload = JSON.parse(value);
		} catch {
			payload = undefined;
		}

		const command = parseThreadParentCommand(payload, key);
		if (!command) {
			this.logger.warn(`Invalid thread parent command, moving to DLT (key=${key ?? '<missing>'})`);
			await this.producer.send({topic: THREAD_PARENT_COMMAND_DLT_TOPIC, messages: [{key: key ?? null, value}]});
			return;
		}

		const parent = await this.messageRepository.findByIdAndChannelId(command.parentMessageId, command.channelId);
		const result = judgeThreadParent(command, parent);
		await this.producer.send({
			topic: THREAD_PARENT_RESULT_TOPIC,
			messages: [{key: String(result.threadId), value: JSON.stringify(result)}],
		});
		this.logger.log(`thread parent judged threadId=${result.threadId} accepted=${result.accepted} reason=${result.reason ?? 'none'}`);
	}

	async onModuleInit() {
		const kafka = new Kafka({clientId: 'cowork-chat-thread-parent', ...getKafkaClientConfig(this.config)});
		this.consumer = kafka.consumer({groupId: 'cowork-chat-thread-parent-command-v1'});
		this.producer = kafka.producer({idempotent: true});
		await connectWithRetry(this.logger, `Kafka consumer startup: ${THREAD_PARENT_COMMAND_TOPIC}`, async () => {
			await this.producer.connect();
			await this.consumer.connect();
			// 그룹이 처음 생길 때 이미 쌓인 command도 처리해야 스레드가 검증 대기에 머물지 않는다.
			await this.consumer.subscribe({topic: THREAD_PARENT_COMMAND_TOPIC, fromBeginning: true});
		});

		void this.consumer.run({
			eachMessage: async ({message}) => {
				const key = message.key?.toString();
				const value = message.value?.toString() ?? '';
				await this.handle(key, value);
			},
		}).catch((error: unknown) => {
			this.logger.error(`${THREAD_PARENT_COMMAND_TOPIC} Kafka consumer failed`, error);
			// 처리되지 않은 rejection으로 남겨 프로세스를 종료하고 재시작을 맡긴다.
			throw error;
		});
		this.logger.log(`Kafka consumer started: ${THREAD_PARENT_COMMAND_TOPIC}`);
	}

	async onModuleDestroy() {
		await this.consumer.disconnect();
		await this.producer.disconnect();
	}
}
