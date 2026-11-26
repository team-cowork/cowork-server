import {parseTeamAccessEvent} from './team-access-event.consumer.js';

const event = {
	eventType: 'UPSERT', teamId: 8, accountId: 7,
	permissions: {administrator: true}, baseGrants: [],
	sourceMembershipVersion: '2026-10-10T12:00:00.000002Z',
	sourceAuthorizationVersion: '2026-10-10T12:00:00.000001Z',
	occurredAt: '2026-10-10T12:00:00.000003Z',
};

describe('parseTeamAccessEvent', () => {
	it('팀과 계정이 일치하는 판정에서 관리자 여부와 인가 버전을 보존한다', () => {
		const access = parseTeamAccessEvent(event, '8:7');
		expect(access?.administrator).toBe(true);
		expect((access?.sourceAuthorizationVersion?.toBigInt() ?? 0n) % 1_000_000n).toBe(1000n);
		expect(parseTeamAccessEvent(event, '8:9')).toBeNull();
		expect(parseTeamAccessEvent({...event, baseGrants: undefined}, '8:7')).toBeNull();
	});

	it('전체 거부 기록만 인가 버전 없이 수신한다', () => {
		const removed = {...event, sourceAuthorizationVersion: null};
		expect(parseTeamAccessEvent(removed, '8:7')).toBeNull();
		expect(parseTeamAccessEvent({...removed, permissions: {administrator: false}}, '8:7')?.administrator).toBe(false);
		expect(parseTeamAccessEvent({...event, permissions: {administrator: 'false'}}, '8:7')).toBeNull();
	});
});
