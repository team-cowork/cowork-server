import {
	forwardRef,
	Inject as inject,
	Injectable as injectable,
	OnModuleDestroy,
	OnModuleInit,
	Logger,
} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import mongoose from 'mongoose';
import {ChatService} from '../chat.service.js';
import {UserClient} from '../service/index.js';
import {ProjectionReadinessService} from '../../common/kafka/projection-readiness.service.js';
import {waitForProjectionReadiness} from '../../common/kafka/projection-readiness-wait.js';
import {type KafkaConsumerHandle, startCommittedConsumer} from '../../common/kafka/partition-fetcher.js';
import {isSafePositiveInteger} from '../../common/util/safe-integer.util.js';
import {type Nullable} from '../../common/type/nullable.js';
import {type ForwardReference} from '../../common/type/forward-reference.js';
import {type VoiceEvent, type VoiceEventPayload} from './event/voice.event.js';
import {type KafkaMessageSource, voiceSystemMessageKey} from './system-message-key.js';

const UNKNOWN_USER_NAME = '알 수 없는 사용자';
const EVENT_ID_PATTERN = /^[\da-f]{24}$/v;

/**
 `voice.event` 페이로드를 검증하고 {@link VoiceEvent}로 바꾼다. 다룰 수 없는 형태면 `null`이다.

 `event_id`가 ObjectId hex인지, 채널과 팀 ID가 양의 정수인지, 메시지에 쓰는 사용자 ID와 시간이 있는지 본다.
 메시지로 만들지 않는 이벤트 종류(시청자 입장/퇴장 등)는 그 밖의 필드를 확인하지 않는다.
 */
export function parseVoiceEvent(payload: unknown): Nullable<VoiceEvent> {
	if (typeof payload !== 'object' || payload === null) {
		return null;
	}

	const wire = payload as VoiceEventPayload;
	if (typeof wire.event_id !== 'string' || !EVENT_ID_PATTERN.test(wire.event_id)
		|| typeof wire.event_type !== 'string' || !isSafePositiveInteger(wire.channel_id) || !isSafePositiveInteger(wire.team_id)) {
		return null;
	}

	const event: VoiceEvent = {
		eventId: wire.event_id, eventType: wire.event_type, channelId: wire.channel_id, teamId: wire.team_id,
	};
	switch (wire.event_type) {
		case 'USER_JOINED':
		case 'USER_LEFT': {
			return isSafePositiveInteger(wire.user_id) ? {...event, userId: wire.user_id} : null;
		}

		case 'LIVE_STARTED': {
			return isSafePositiveInteger(wire.host_user_id) ? {...event, hostUserId: wire.host_user_id} : null;
		}

		case 'SESSION_ENDED':
		case 'LIVE_ENDED': {
			return Number.isSafeInteger(wire.duration_seconds) && (wire.duration_seconds as number) >= 0
				? {...event, durationSeconds: wire.duration_seconds as number}
				: null;
		}

		default: {
			return event;
		}
	}
}

/**
 메시지에 이름을 넣을 사용자 ID를 고른다. 이름이 필요 없는 이벤트는 `null`이다.
 */
export function voiceEventUserId(event: VoiceEvent): Nullable<number> {
	switch (event.eventType) {
		case 'USER_JOINED':
		case 'USER_LEFT': {
			return event.userId ?? null;
		}

		case 'LIVE_STARTED': {
			return event.hostUserId ?? null;
		}

		default: {
			return null;
		}
	}
}

/**
 통화나 방송 시간을 `1시간 5분`, `5분`, `1분 미만` 형식으로 쓴다. 분이 0이면 `1시간`처럼 분을 뺀다.
 */
export function formatVoiceDuration(durationSeconds: number): string {
	const totalMinutes = Math.floor(durationSeconds / 60);
	if (totalMinutes < 1) {
		return '1분 미만';
	}

	const hours = Math.floor(totalMinutes / 60);
	const minutes = totalMinutes % 60;
	if (hours === 0) {
		return `${minutes}분`;
	}

	return minutes === 0 ? `${hours}시간` : `${hours}시간 ${minutes}분`;
}

/**
 음성 이벤트로 시스템 메시지 내용을 만든다. 메시지로 남기지 않는 이벤트는 `null`이다.

 `displayName`은 {@link voiceEventUserId} 사용자의 표시 이름이다. 찾지 못했으면 `null`이다.
 */
export function voiceEventMessage(event: VoiceEvent, displayName: Nullable<string>): Nullable<string> {
	const name = displayName ?? UNKNOWN_USER_NAME;
	switch (event.eventType) {
		case 'SESSION_STARTED': {
			return '음성 통화가 시작되었습니다.';
		}

		case 'SESSION_ENDED': {
			return `음성 통화가 종료되었습니다. (통화 시간 ${formatVoiceDuration(event.durationSeconds ?? 0)})`;
		}

		case 'USER_JOINED': {
			return `${name}님이 음성 채널에 입장했습니다.`;
		}

		case 'USER_LEFT': {
			return `${name}님이 음성 채널에서 나갔습니다.`;
		}

		case 'LIVE_STARTED': {
			return `${name}님이 라이브를 시작했습니다.`;
		}

		case 'LIVE_ENDED': {
			return `라이브가 종료되었습니다. (방송 시간 ${formatVoiceDuration(event.durationSeconds ?? 0)})`;
		}

		default: {
			return null;
		}
	}
}

/**
 Kafka `voice.event` 토픽을 구독하여 음성 채널 활동을 채팅에 남기는 컨슈머.

 통화 시작/종료, 사용자 입장/퇴장, 라이브 시작/종료를 이벤트의 `channel_id` 채널에 SYSTEM 메시지로 저장한 후
 Socket.IO로 브로드캐스트한다. 시청자 입장/퇴장은 메시지로 만들지 않는다.
 */
@injectable()
export class VoiceEventConsumer implements OnModuleInit, OnModuleDestroy {
	private readonly logger = new Logger(VoiceEventConsumer.name);
	private consumer?: KafkaConsumerHandle;

	@inject(forwardRef(() => ChatService))
	private readonly chatService!: ForwardReference<ChatService>;

	@inject()
	private readonly userClient!: UserClient;

	@inject()
	private readonly configService!: ConfigService;

	@inject()
	private readonly projectionReadiness!: ProjectionReadinessService;

	/**
     음성 이벤트를 SYSTEM 메시지로 저장하고 채널에 브로드캐스트한다.

     `source`(Kafka 메시지 위치)는 잘못된 페이로드를 로그에 남기는 데 사용한다.
     */
	private async handleVoiceEvent(payload: unknown, source: KafkaMessageSource): Promise<void> {
		const event = parseVoiceEvent(payload);
		if (event === null) {
			this.logger.warn(`Skipping invalid voice event [offset=${source.offset}]`);
			return;
		}

		if (voiceEventMessage(event, null) === null) {
			return;
		}

		await waitForProjectionReadiness(this.projectionReadiness);
		if (!this.projectionReadiness.isReady()) {
			throw new Error('Kafka projections became unavailable while processing voice.event');
		}

		const userId = voiceEventUserId(event);
		const displayNames = userId === null ? new Map<number, string>() : await this.userClient.getDisplayNames([userId], event.teamId);
		const content = voiceEventMessage(event, userId === null ? null : displayNames.get(userId) ?? null)!;

		const saved = await this.chatService.saveSystemMessage(
			event.teamId, event.channelId, content, voiceSystemMessageKey(event.eventId, event.channelId),
		);
		if (!saved) {
			return;
		}

		await this.chatService.broadcastSystemMessage(saved);
	}

	/**
     모듈 초기화 시 Kafka 컨슈머를 시작하고 `voice.event` 토픽 구독을 설정한다.

     JSON 파싱 오류와 Message 스키마 검증 실패는 재처리 불가 메시지로 판단하여 무시한다.
     그 외 오류는 예외로 전파하여 Kafka offset을 커밋하지 않고 재전달을 유도한다.
     */
	async onModuleInit() {
		this.consumer = await startCommittedConsumer({
			logger: this.logger,
			configService: this.configService,
			clientId: 'cowork-chat-voice-event',
			groupId: 'cowork-chat-voice-event',
			topic: 'voice.event',
			fromBeginning: false,
			handle: async ({topic, partition, offset, value}) => {
				if (value === null) {
					return;
				}

				try {
					const event = JSON.parse(value) as unknown;
					await this.handleVoiceEvent(event, {topic, partition, offset});
				} catch (error) {
					if (!(error instanceof SyntaxError) && !(error instanceof mongoose.Error.ValidationError)) {
						throw error;
					}

					this.logger.error('Failed to process voice event', error);
				}
			},
		});
		this.logger.log('Kafka consumer started: voice.event');
	}

	/**
     모듈 종료 시 Kafka 컨슈머 연결을 해제한다.
     */
	async onModuleDestroy() {
		await this.consumer?.close();
	}
}
