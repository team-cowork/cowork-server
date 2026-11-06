import {isTeamSettingEvent} from './team-setting-event.consumer.js';

const event = (overrides: Record<string, unknown> = {}) => ({
	schemaVersion: 1,
	eventType: 'UPSERT',
	teamId: 10,
	defaultNotificationLevel: null,
	nicknameFormatEnforced: false,
	nicknameFormatExample: null,
	tagSpamBlock: true,
	occurredAt: '2026-10-23T01:02:03.000001Z',
	snapshot: false,
	...overrides,
});

describe('isTeamSettingEvent', () => {
	it('태그 스팸 차단 값을 포함한 레코드를 받는다', () => {
		expect(isTeamSettingEvent(event())).toBe(true);
	});

	it('잘못된 차단 값이나 다른 계약의 레코드는 거부한다', () => {
		for (const overrides of [
			{tagSpamBlock: 'true'},
			{schemaVersion: 2},
			{eventType: 'DELETE'},
			{teamId: 0},
			{occurredAt: 'yesterday'},
		]) {
			expect(isTeamSettingEvent(event(overrides))).toBe(false);
		}
	});
});
