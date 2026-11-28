import {Injectable as injectable} from '@nestjs/common';
import {ConfigService} from '@nestjs/config';
import {LazyKafkaProducer} from '../../common/kafka/lazy-kafka-producer.js';
import {type Nullable} from '../../common/type/nullable.js';

/**
 알림 트리거 Kafka 이벤트 페이로드.

 `targetUserIds`: 채널 멤버 중 알림을 받을 후보 유저 목록.
 서버 측에서 조용히 설정 상태를 확인한 뒤 실제 발송 여부를 결정한다.
 `forcedUserIds`: 알림 설정과 무관하게 반드시 알림을 받아야 하는 유저 목록
 (예: 멘션된 사용자).
 */
export type NotificationTriggerEvent = {
	/**
     논리적 알림 이벤트의 안정적 식별자.
     FCM 개별 전송 결과의 선택적 재시도가 이 값과 device token을 canonical key로 사용하므로,
     같은 알림이 재발행되어도(outbox 재시도 등) 항상 같은 값을 유지해야 한다.
     */
	eventId: string;
	/**
    알림 종류 식별자 (예: CHAT_MESSAGE)
    */
	type: string;
	/**
    알림 수신 후보 유저 ID 목록
    */
	targetUserIds: number[];
	/**
    알림 설정 무시하고 강제 수신할 유저 ID 목록 (멘션 등)
    */
	forcedUserIds: number[];
	data: {
		channelId: number;
		/**
        DM 채널 메시지는 팀에 속하지 않으므로 null
        */
		teamId: Nullable<number>;
		authorId: number;
		content: string;
		/**
        예약 메시지 발송 실패 사유. `SCHEDULED_MESSAGE_FAILED` 알림에만 있다.
        */
		reason?: string;
		/**
        이벤트 발생 시각 (ISO 8601, 서버 기준)
        */
		occurredAt: string;
	};
};

@injectable()
export class NotificationTriggerProducer extends LazyKafkaProducer {
	constructor(configService: ConfigService) {
		super(configService, {clientId: 'cowork-chat-notification', label: 'Notification trigger producer'});
	}

	async send(event: NotificationTriggerEvent): Promise<void> {
		await this.publish([{topic: 'notification.trigger', value: JSON.stringify(event)}]);
	}
}
