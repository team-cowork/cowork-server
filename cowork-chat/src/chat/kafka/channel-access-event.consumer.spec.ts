import {CHANNEL_SCOPED_PERMISSIONS, parseChannelAccessEvent} from './channel-access-event.consumer.js';

const payload = (overrides: Record<string, unknown> = {}, allowed: string[] = ['message_read']) => ({
	eventType: 'UPSERT',
	channelId: 11,
	accountId: 7,
	teamId: 3,
	permissions: Object.fromEntries(CHANNEL_SCOPED_PERMISSIONS.map(key => [key, allowed.includes(key)])),
	readPrivate: true,
	sourceMembershipVersion: '2026-11-25T00:00:02Z',
	sourceAuthorizationVersion: '2026-11-25T00:00:01.000001Z',
	occurredAt: '2026-11-25T00:00:03.123456Z',
	...overrides,
});

describe('parseChannelAccessEvent', () => {
	it('허용된 권한과 원본 version을 읽는다', () => {
		const decision = parseChannelAccessEvent(payload({}, ['message_read', 'message_send']), '11:7');

		expect(decision).toMatchObject({
			channelId: 11, userId: 7, teamId: 3, allowedPermissions: ['message_read', 'message_send'], readPrivate: true,
		});
		expect(decision?.sourceAuthorizationVersion?.toString()).toBe('1795564801000001000');
		expect(decision?.sourceVersion.toString()).toBe('1795564803123456000');
	});

	it('활성 멤버가 아니라서 version이 없는 거부 기록을 읽는다', () => {
		const decision = parseChannelAccessEvent(payload({
			readPrivate: false, sourceMembershipVersion: '1970-01-01T00:00:00Z', sourceAuthorizationVersion: null,
		}, []), '11:7');

		expect(decision?.allowedPermissions).toEqual([]);
		expect(decision?.sourceAuthorizationVersion).toBeNull();
	});

	it.each([
		['인가 version 없이 권한을 허용한 기록', payload({sourceAuthorizationVersion: null}), '11:7'],
		['인가 version이 멤버십 version보다 늦은 기록', payload({sourceAuthorizationVersion: '2026-11-25T00:00:05Z'}), '11:7'],
		['인가 version이 빠진 기록', payload({sourceAuthorizationVersion: undefined}), '11:7'],
		['readPrivate가 빠진 기록', payload({readPrivate: undefined}), '11:7'],
		['채널 권한 키가 빠진 기록', payload({permissions: Object.fromEntries([['message_read', true]])}), '11:7'],
		['key가 다른 기록', payload(), '11:8'],
	])('%s은 거절한다', (_label, body, key) => {
		expect(parseChannelAccessEvent(body, key)).toBeNull();
	});
});
