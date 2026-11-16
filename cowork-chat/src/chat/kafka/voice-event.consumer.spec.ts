import {
	formatVoiceDuration,
	parseVoiceEvent,
	voiceEventMessage,
	voiceEventUserId,
} from './voice-event.consumer.js';
import {type VoiceEvent} from './event/voice.event.js';

const event = (overrides: Partial<VoiceEvent> = {}): VoiceEvent => ({
	eventType: 'SESSION_STARTED',
	channelId: 11,
	teamId: 3,
	...overrides,
});

/**
 `cowork-voice`가 보내는 snake_case JSON 페이로드를 만든다.
 */
const wire = (overrides: Array<[string, unknown]> = []): unknown => Object.fromEntries([
	['event_type', 'SESSION_STARTED'],
	['channel_id', 11],
	['team_id', 3],
	['user_id', 7],
	['timestamp', '2026-11-16T01:02:03Z'],
	...overrides,
]);

describe('formatVoiceDuration', () => {
	it.each([
		[0, '1분 미만'],
		[59, '1분 미만'],
		[60, '1분'],
		[300, '5분'],
		[3599, '59분'],
		[3600, '1시간'],
		[3900, '1시간 5분'],
		[7260, '2시간 1분'],
	])('%i초는 %s로 쓴다', (seconds, expected) => {
		expect(formatVoiceDuration(seconds)).toBe(expected);
	});
});

describe('voiceEventMessage', () => {
	it.each([
		[event(), '음성 통화가 시작되었습니다.'],
		[event({eventType: 'SESSION_ENDED', durationSeconds: 3900}), '음성 통화가 종료되었습니다. (통화 시간 1시간 5분)'],
		[event({eventType: 'USER_JOINED', userId: 7}), '홍길동님이 음성 채널에 입장했습니다.'],
		[event({eventType: 'USER_LEFT', userId: 7}), '홍길동님이 음성 채널에서 나갔습니다.'],
		[event({eventType: 'LIVE_STARTED', hostUserId: 7}), '홍길동님이 라이브를 시작했습니다.'],
		[event({eventType: 'LIVE_ENDED', durationSeconds: 30}), '라이브가 종료되었습니다. (방송 시간 1분 미만)'],
	])('%j 이벤트로 메시지를 만든다', (voiceEvent, expected) => {
		expect(voiceEventMessage(voiceEvent, '홍길동')).toBe(expected);
	});

	it('이름을 찾지 못하면 알 수 없는 사용자로 쓴다', () => {
		expect(voiceEventMessage(event({eventType: 'USER_JOINED', userId: 7}), null)).toBe('알 수 없는 사용자님이 음성 채널에 입장했습니다.');
	});

	it('시청자 입장/퇴장과 모르는 이벤트는 메시지로 만들지 않는다', () => {
		for (const eventType of ['VIEWER_JOINED', 'VIEWER_LEFT', 'UNKNOWN']) {
			expect(voiceEventMessage(event({eventType}), '홍길동')).toBeNull();
		}
	});
});

describe('voiceEventUserId', () => {
	it('입장/퇴장은 userId, 라이브 시작은 hostUserId를 쓴다', () => {
		expect(voiceEventUserId(event({eventType: 'USER_JOINED', userId: 7}))).toBe(7);
		expect(voiceEventUserId(event({eventType: 'USER_LEFT', userId: 7}))).toBe(7);
		expect(voiceEventUserId(event({eventType: 'LIVE_STARTED', hostUserId: 9}))).toBe(9);
		expect(voiceEventUserId(event())).toBeNull();
	});
});

describe('parseVoiceEvent', () => {
	it('snake_case 페이로드를 검증하고 필요한 필드만 옮긴다', () => {
		expect(parseVoiceEvent(wire())).toEqual(event());
		expect(parseVoiceEvent(wire([['event_type', 'USER_JOINED']]))).toEqual(event({eventType: 'USER_JOINED', userId: 7}));
		expect(parseVoiceEvent(wire([['event_type', 'LIVE_STARTED'], ['host_user_id', 9]]))).toEqual(event({eventType: 'LIVE_STARTED', hostUserId: 9}));
		expect(parseVoiceEvent(wire([['event_type', 'SESSION_ENDED'], ['duration_seconds', 0]]))).toEqual(event({eventType: 'SESSION_ENDED', durationSeconds: 0}));
		expect(parseVoiceEvent(wire([['event_type', 'VIEWER_JOINED'], ['user_id', undefined]]))).toEqual(event({eventType: 'VIEWER_JOINED'}));
	});

	it('필드가 없거나 잘못된 페이로드는 거부한다', () => {
		for (const overrides of [
			[['event_type', undefined]],
			[['channel_id', 0]],
			[['team_id', '3']],
			[['event_type', 'USER_LEFT'], ['user_id', undefined]],
			[['event_type', 'LIVE_STARTED']],
			[['event_type', 'SESSION_ENDED'], ['duration_seconds', -1]],
			[['event_type', 'LIVE_ENDED'], ['duration_seconds', 1.5]],
		] satisfies Array<Array<[string, unknown]>>) {
			expect(parseVoiceEvent(wire(overrides))).toBeNull();
		}

		expect(parseVoiceEvent(null)).toBeNull();
	});
});
