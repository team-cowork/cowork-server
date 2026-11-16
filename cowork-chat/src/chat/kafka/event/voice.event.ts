/**
 `voice.event` 토픽의 JSON 페이로드. 필드 이름은 `cowork-voice`가 발행하는 snake_case 그대로다.

 `cowork-voice`가 음성 세션과 라이브 상태가 바뀔 때 발행하고 `cowork-chat`이 소비한다.
 */
export type VoiceEventPayload = {
	event_type?: unknown;
	channel_id?: unknown;
	team_id?: unknown;
	user_id?: unknown;
	host_user_id?: unknown;
	duration_seconds?: unknown;
};

/**
 검증을 마친 음성 이벤트. `cowork-chat`은 시청자 입장/퇴장을 뺀 이벤트를 음성 채널의 SYSTEM 메시지로 남긴다.
 */
export type VoiceEvent = {
	eventType: string;
	channelId: number;
	teamId: number;
	/**
    `USER_JOINED`, `USER_LEFT`에서 검증한다.
    */
	userId?: number;
	/**
    `LIVE_STARTED`에서 검증한다.
    */
	hostUserId?: number;
	/**
    `SESSION_ENDED`, `LIVE_ENDED`에서 검증한다.
    */
	durationSeconds?: number;
};
