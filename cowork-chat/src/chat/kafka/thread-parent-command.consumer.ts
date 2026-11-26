import {
	Injectable as injectable,
	Logger,
	OnModuleDestroy,
	OnModuleInit,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {createKafkaProducer} from '../../common/kafka/lazy-kafka-producer.js';
import {type KafkaConsumerHandle, startCommittedConsumer} from '../../common/kafka/partition-fetcher.js';
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
	private consumer?: KafkaConsumerHandle;
	private producer!: ReturnType<typeof createKafkaProducer>;

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
			await this.producer.send({messages: [{topic: THREAD_PARENT_COMMAND_DLT_TOPIC, key, value}]});
			return;
		}

		const parent = await this.messageRepository.findByIdAndChannelId(command.parentMessageId, command.channelId);
		const result = judgeThreadParent(command, parent);
		await this.producer.send({
			messages: [{topic: THREAD_PARENT_RESULT_TOPIC, key: String(result.threadId), value: JSON.stringify(result)}],
		});
		this.logger.log(`thread parent judged threadId=${result.threadId} accepted=${result.accepted} reason=${result.reason ?? 'none'}`);
	}

	async onModuleInit() {
		this.producer = createKafkaProducer(this.config, 'cowork-chat-thread-parent');
		this.consumer = await startCommittedConsumer({
			logger: this.logger,
			configService: this.config,
			clientId: 'cowork-chat-thread-parent',
			groupId: 'cowork-chat-thread-parent-command-v1',
			topic: THREAD_PARENT_COMMAND_TOPIC,
			// 그룹이 처음 생길 때 이미 쌓인 command도 처리해야 스레드가 검증 대기에 머물지 않는다.
			fromBeginning: true,
			handle: async ({key, value}) => this.handle(key ?? undefined, value ?? ''),
		});
		this.logger.log(`Kafka consumer started: ${THREAD_PARENT_COMMAND_TOPIC}`);
	}

	async onModuleDestroy() {
		await this.consumer?.close();
		await this.producer.close();
	}
}
