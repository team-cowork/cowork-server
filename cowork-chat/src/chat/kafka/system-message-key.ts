/**
 시스템 메시지를 만든 Kafka 메시지의 위치(토픽, 파티션, offset).
 */
export type KafkaMessageSource = {
	topic: string;
	partition: number;
	offset: string;
};

/**
 Kafka 메시지 위치로 시스템 메시지의 `clientMessageId`를 만든다.

 같은 메시지가 다시 전달되어도 채널마다 같은 키가 나오므로, `clientMessageId` 유니크 인덱스가
 중복 저장을 막는다. 사용자 메시지의 UUID 키와 겹치지 않도록 `system:` 접두사를 붙인다.

 @param message - Kafka 메시지 위치
 @param channelId - 시스템 메시지를 저장할 채널 ID
 @returns `system:{topic}:{partition}:{offset}:{channelId}` 형식의 키
 */
export function systemMessageKey(
	message: KafkaMessageSource,
	channelId: number,
): string {
	return `system:${message.topic}:${message.partition}:${message.offset}:${channelId}`;
}
